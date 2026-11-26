"""Absolute-scale LIBS forward radiative transfer equation (RTE) model (R4).

Implements:
1. PlasmaZoneSpec: representation of physical plasma zones.
2. combine_zones: 1:1 forward multi-zone RTE with unnormalized emission and max-normalized opacity.
3. apply_response_and_scale: support for absolute and max_normalised emission scale modes.
4. composition_scale_check: Gate G1 verification for absolute composition scaling.
5. load_nist_dictionary: resamples NIST grid states in absolute NIST units with NIST_UNIT_SCALE.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd

try:
    from scripts.spectral_resampling import resample_to_detector
except ImportError:
    from spectral_resampling import resample_to_detector

#: Canonical unit scale applied to raw NIST values matching Java NISTUtils.parseNistCsv (1.0)
NIST_UNIT_SCALE: float = 1.0


@dataclass
class PlasmaZoneSpec:
    """Specification of an optically active or absorbing plasma zone."""
    te: float
    ne: float
    volume_fraction: float
    k_absorption: float = 0.0
    te_std_dev: float = 0.0
    ne_log_std_dev: float = 0.0

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> PlasmaZoneSpec:
        return cls(
            te=float(d.get("Te", d.get("te", 0.0))),
            ne=float(d.get("Ne", d.get("ne", 0.0))),
            volume_fraction=float(d.get("volumeFraction", d.get("volume_fraction", 0.0))),
            k_absorption=float(d.get("kAbsorption", d.get("k_absorption", 0.0))),
            te_std_dev=float(d.get("teStdDev", d.get("te_std_dev", 0.0))),
            ne_log_std_dev=float(d.get("neLogStdDev", d.get("ne_log_std_dev", 0.0))),
        )

    def to_dict(self, zone_index: int = 0) -> Dict[str, Any]:
        return {
            "zoneIndex": zone_index,
            "Te": self.te,
            "Ne": self.ne,
            "volumeFraction": self.volume_fraction,
            "kAbsorption": self.k_absorption,
            "teStdDev": self.te_std_dev,
            "neLogStdDev": self.ne_log_std_dev,
        }


def apply_multi_zone_rte(
    zone_spectra: List[np.ndarray],
    zones: List[PlasmaZoneSpec],
) -> np.ndarray:
    """Combine multiple plasma zones using the canonical R4 forward RTE operator.

    Zones are sorted by Te descending (hot core -> cool outer shell).
    For zone 0 (hot core):
        I_0 = V_0 * d_0
    For outer zones z = 1..Q-1:
        d_tilde_z = d_z / max(d_z)
        I_z = I_{z-1} * exp(-k_z * d_tilde_z) + V_z * d_z

    Parameters
    ----------
    zone_spectra : list of 1D arrays of resampled NIST spectra for each zone.
    zones : list of PlasmaZoneSpec instances for each zone.

    Returns
    -------
    I_final : 1D array of combined forward radiance before spectral response and scale.
    """
    if len(zone_spectra) != len(zones):
        raise ValueError(f"Number of spectra ({len(zone_spectra)}) must match zones ({len(zones)})")
    if not zones:
        raise ValueError("Must provide at least one zone")

    # Sort zones by Te descending
    sorted_pairs = sorted(zip(zones, zone_spectra), key=lambda pair: pair[0].te, reverse=True)

    # Zone 0 (hot core)
    z0, d0 = sorted_pairs[0]
    i_rad = z0.volume_fraction * np.asarray(d0, dtype=np.float64)

    # Outer zones (cool shells attenuating hot core)
    for z, d in sorted_pairs[1:]:
        d_arr = np.asarray(d, dtype=np.float64)
        max_d = float(np.max(d_arr))
        if max_d > 1e-12:
            d_tilde = d_arr / max_d
        else:
            d_tilde = np.zeros_like(d_arr)

        k = max(float(z.k_absorption), 0.0)
        opacity = np.exp(-k * d_tilde)
        i_rad = i_rad * opacity + z.volume_fraction * d_arr

    return i_rad


def combine_zones(
    zone_spectra: List[np.ndarray],
    zones: List[PlasmaZoneSpec],
) -> np.ndarray:
    """Backward-compatible alias for apply_multi_zone_rte."""
    return apply_multi_zone_rte(zone_spectra, zones)


def apply_response_and_scale(
    spectrum: np.ndarray,
    spectral_response: np.ndarray,
    scale_factor: float,
    emission_scale: str = "absolute",
) -> np.ndarray:
    """Apply instrument spectral response R(lambda) and scale factor to forward radiance.
    
    Parameters
    ----------
    spectrum : 1D array of uncalibrated forward radiance.
    spectral_response : 1D array of instrument spectral response R(lambda).
    scale_factor : overall scaling factor.
    emission_scale : "absolute" (default) or "max_normalised".
    
    Returns
    -------
    synthetic : 1D array of modelled synthetic spectrum.
    """
    spec = np.asarray(spectrum, dtype=np.float64)
    resp = np.asarray(spectral_response, dtype=np.float64)
    
    r_times_i = spec * resp
    
    if emission_scale.lower() == "max_normalised":
        max_val = float(np.max(r_times_i))
        if max_val > 1e-12:
            normed = r_times_i / max_val
        else:
            normed = np.zeros_like(r_times_i)
        return float(scale_factor) * normed
    else:  # "absolute"
        return float(scale_factor) * r_times_i


def composition_scale_check(
    peak_1: float,
    species_frac_1: float,
    at_frac_1: float,
    peak_2: float,
    species_frac_2: float,
    at_frac_2: float,
) -> Dict[str, Any]:
    """Execute Gate G1 composition scale check across two reference materials.
    
    Verifies that for an isolated line the ratio:
        peak / (species_fraction * elemental_atomic_fraction)
    agrees between two compositions within 5% at the same plasma state.
    """
    denom_1 = float(species_frac_1 * at_frac_1)
    denom_2 = float(species_frac_2 * at_frac_2)
    
    if denom_1 <= 0 or denom_2 <= 0:
        return {"passed": False, "maxRelativeDifference": 1.0, "ratio1": 0.0, "ratio2": 0.0}
        
    ratio_1 = float(peak_1 / denom_1)
    ratio_2 = float(peak_2 / denom_2)
    
    max_ratio = max(ratio_1, ratio_2)
    rel_diff = abs(ratio_1 - ratio_2) / max_ratio if max_ratio > 0 else 0.0
    passed = rel_diff <= 0.05
    
    return {
        "passed": passed,
        "maxRelativeDifference": rel_diff,
        "ratio1": ratio_1,
        "ratio2": ratio_2,
    }


def load_nist_dictionary(
    nist_paths_map: Dict[str, str | Path],
    lam_det: np.ndarray,
    add_sigma_nm: np.ndarray | float,
    te_grid: List[float],
    ne_grid: List[float],
) -> Tuple[np.ndarray, Dict[str, np.ndarray]]:
    """Load and resample NIST grid spectra into absolute detector dictionary matrix.
    
    Parameters
    ----------
    nist_paths_map : dict mapping state keys (e.g. '0.8_1.0E+17') to CSV paths.
    lam_det : 1D detector wavelength grid.
    add_sigma_nm : additional Gaussian broadening per detector channel.
    te_grid : list of Te states in eV.
    ne_grid : list of Ne states in cm^-3.
    
    Returns
    -------
    dict_matrix : 2D array of shape (len(lam_det), n_states) in absolute NIST units * NIST_UNIT_SCALE.
    element_matrices : dict mapping element symbol to 2D array of species-summed columns.
    """
    p_len = len(lam_det)
    n_states = len(te_grid) * len(ne_grid)
    dict_matrix = np.zeros((p_len, n_states), dtype=np.float64)
    element_matrices: Dict[str, np.ndarray] = {}
    
    col_idx = 0
    for te in te_grid:
        for ne in ne_grid:
            key_sci = f"{te}_{ne:.1E}"
            key_raw = f"{te}_{ne}"
            csv_path = nist_paths_map.get(key_sci) or nist_paths_map.get(key_raw)
            if csv_path is None or not Path(csv_path).exists():
                col_idx += 1
                continue
                
            df = pd.read_csv(csv_path)
            wl_src = df.iloc[:, 0].to_numpy(dtype=np.float64)
            # Sum(calc) is column 1
            s_sum = df.iloc[:, 1].to_numpy(dtype=np.float64) * NIST_UNIT_SCALE
            
            dict_matrix[:, col_idx] = resample_to_detector(wl_src, s_sum, lam_det, add_sigma_nm)
            
            # Element species attribution columns (species like "Fe I", "Cr II", etc.)
            for col_name in df.columns[2:]:
                col_clean = str(col_name).strip()
                parts = col_clean.split()
                if not parts:
                    continue
                el = parts[0]
                if el not in element_matrices:
                    element_matrices[el] = np.zeros((p_len, n_states), dtype=np.float64)
                s_species = df[col_name].to_numpy(dtype=np.float64) * NIST_UNIT_SCALE
                resampled_sp = resample_to_detector(wl_src, s_species, lam_det, add_sigma_nm)
                element_matrices[el][:, col_idx] += resampled_sp
                
            col_idx += 1
            
    return dict_matrix, element_matrices
