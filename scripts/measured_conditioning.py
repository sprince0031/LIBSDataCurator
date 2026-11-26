"""Measured data conditioning module (R3).

Implements:
1. Lit mask identification and dark-level subtraction.
2. Pearson correlation conditioning-shot filtering with [3, 15] clamps.
3. Saturation threshold masking without zeroing.
4. Two-pass shot-scale normalization.
5. Standard error of the mean (sigma_mean) computation.
6. Morphological opening preprocessing.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
import warnings

import numpy as np
import pandas as pd
from scipy.ndimage import grey_opening


DEFAULT_MORPHOLOGICAL_SE_SIZE = 25


@dataclass
class ConditionedMaterial:
    """Conditioned measured spectral data for a reference material."""
    wavelengths: np.ndarray
    mean_spectrum: np.ndarray
    preprocessed_mean: np.ndarray
    sigma_mean: np.ndarray
    valid_mask: np.ndarray
    lit_mask: np.ndarray
    dark_level: float
    point_means: Dict[str, np.ndarray]
    discarded_shots_per_point: Dict[str, int]
    preprocessed_point_means: Dict[str, np.ndarray] = field(default_factory=dict)
    num_steady_shots: int = 0


def apply_morphological_baseline(
    spectrum: np.ndarray,
    se_size: int = DEFAULT_MORPHOLOGICAL_SE_SIZE,
) -> np.ndarray:
    """Apply post-average morphological grey opening baseline subtraction with non-negative clamp.

    Idempotent on previously processed spectra (metric_contract_v1).
    """
    spec = np.asarray(spectrum, dtype=np.float64)
    if len(spec) < se_size:
        return np.maximum(spec, 0.0)
    opening = grey_opening(spec, size=se_size)
    return np.maximum(spec - opening, 0.0)


def preprocess(
    spectrum: np.ndarray,
    struct_size: int = DEFAULT_MORPHOLOGICAL_SE_SIZE,
) -> np.ndarray:
    """Backward-compatible alias for apply_morphological_baseline."""
    return apply_morphological_baseline(spectrum, se_size=struct_size)


def apply_lit_and_saturation_mask(
    shots: np.ndarray,
    *,
    det_full_scale: float = 65535.0,
    sat_factor: float = 0.9,
    lit_mask: Optional[np.ndarray] = None,
    min_valid_fraction: float = 0.25,
    min_valid_floor: int = 5,
) -> Tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Identify lit detector channels and non-saturated readings without zeroing.

    Parameters
    ----------
    shots : 2D array of shape (n_shots, n_channels).
    det_full_scale : Detector ADC full-scale count limit.
    sat_factor : Fraction of full scale above which readings are marked saturated.
    lit_mask : Optional precomputed 1D boolean lit-channel mask.
    min_valid_fraction : Minimum fraction of unsaturated shots required per valid channel.
    min_valid_floor : Minimum absolute count of unsaturated shots required per valid channel.

    Returns
    -------
    (lit_mask, valid_readings_mask, channel_valid_mask)
    """
    arr = np.asarray(shots, dtype=np.float64)
    if arr.ndim != 2 or arr.shape[0] == 0 or arr.shape[1] == 0:
        raise ValueError(f"shots must be a non-empty 2D array, got shape {arr.shape}")

    n_shots, p_len = arr.shape
    if lit_mask is None:
        initial_mean = np.mean(arr, axis=0)
        m0 = float(np.median(initial_mean[:min(8, p_len)]))
        unlit_end = 0
        for p in range(p_len):
            if initial_mean[p] > 3.0 * m0:
                unlit_end = p
                break
        resolved_lit_mask = np.zeros(p_len, dtype=bool)
        resolved_lit_mask[unlit_end:] = True
    else:
        resolved_lit_mask = np.asarray(lit_mask, dtype=bool)
        if resolved_lit_mask.ndim != 1 or resolved_lit_mask.size != p_len:
            raise ValueError(
                f"lit_mask length mismatch: {resolved_lit_mask.shape} vs n_channels={p_len}"
            )

    sat_threshold = float(sat_factor) * float(det_full_scale)
    valid_readings_mask = arr < sat_threshold
    valid_counts_per_channel = np.sum(valid_readings_mask, axis=0)
    min_valid_shots = max(int(min_valid_floor), int(min_valid_fraction * n_shots))
    channel_valid = (valid_counts_per_channel >= min_valid_shots) & resolved_lit_mask
    return resolved_lit_mask, valid_readings_mask, channel_valid


def dark_subtract(
    shots: np.ndarray,
    *,
    lit_mask: Optional[np.ndarray] = None,
    dark_level: Optional[float] = None,
) -> Tuple[np.ndarray, float]:
    """Subtract detector dark offset computed from unlit channels (or explicit dark_level).

    Returns (dark_subtracted_shots, dark_level).
    """
    arr = np.asarray(shots, dtype=np.float64)
    if dark_level is None:
        if lit_mask is not None:
            unlit = ~np.asarray(lit_mask, dtype=bool)
            if int(np.sum(unlit)) >= 4:
                mean_spec = np.mean(arr, axis=0) if arr.ndim == 2 else arr
                resolved_dark = float(np.median(mean_spec[unlit]))
            else:
                resolved_dark = 0.0
        else:
            resolved_dark = 0.0
    else:
        resolved_dark = float(dark_level)
    return arr - resolved_dark, resolved_dark


def trim_conditioning_shots(
    point_shots: np.ndarray,
    lit_mask: np.ndarray,
    *,
    discard_override: Optional[int] = None,
    corr_threshold: float = 0.999,
    min_discard: int = 3,
    max_discard: int = 15,
) -> Tuple[np.ndarray, int]:
    """Discard leading laser-conditioning shots for a single measurement point.

    Uses Pearson correlation against the second-half steady mean over lit channels,
    clamped to [min_discard, min(max_discard, n_total - 1)] unless discard_override is set.
    Returns (steady_shots, n_discarded).
    """
    pts = np.asarray(point_shots, dtype=np.float64)
    if pts.ndim != 2:
        raise ValueError(f"point_shots must be a 2D array, got shape {pts.shape}")
    lit = np.asarray(lit_mask, dtype=bool)
    n_total = len(pts)

    if discard_override is not None:
        n0 = int(discard_override)
    elif n_total <= min_discard:
        n0 = 0
    else:
        ref_half = np.mean(pts[n_total // 2:], axis=0)
        ref_lit = ref_half[lit]
        ref_norm = ref_lit - np.mean(ref_lit)
        ref_std = np.std(ref_lit)

        last_sub = -1
        for s in range(n_total):
            s_lit = pts[s, lit]
            s_norm = s_lit - np.mean(s_lit)
            s_std = np.std(s_lit)
            if ref_std > 1e-12 and s_std > 1e-12:
                r = float(np.mean(s_norm * ref_norm) / (s_std * ref_std))
            else:
                r = 0.0
            if r < corr_threshold:
                last_sub = s

        n0 = int(np.clip(last_sub + 1, min_discard, min(max_discard, n_total - 1)))

    return pts[n0:], n0


def condition_material(
    csv_paths: List[str | Path],
    manifest: Optional[Dict[str, Any]] = None,
) -> ConditionedMaterial:
    """Condition raw measured LIBS CSV data for a material across all measurement points.
    
    Parameters
    ----------
    csv_paths : list of paths to measured CSV files for this material.
    manifest : optional calibration manifest dictionary containing overrides and thresholds.
    
    Returns
    -------
    ConditionedMaterial container holding wavelengths, conditioned mean, sigma_mean,
    valid and lit masks, dark level, and per-point conditioned data.
    """
    manifest = manifest or {}
    det_full_scale = float(manifest.get("detectorFullScale", 65535.0))
    sat_factor = float(manifest.get("saturationThresholdFactor", 0.9))
    struct_size = int(manifest.get("structuringElementSize", DEFAULT_MORPHOLOGICAL_SE_SIZE))
    discard_override = manifest.get("discardLeadingShots")
    
    if not csv_paths:
        raise ValueError("csv_paths must contain at least one CSV file path")
        
    point_raw_shots: Dict[str, np.ndarray] = {}
    wavelengths: Optional[np.ndarray] = None
    
    for path in csv_paths:
        p_obj = Path(path)
        with open(p_obj, "r", encoding="utf-8", errors="ignore") as f:
            first_line = f.readline()
        sep = ";" if ";" in first_line else ","
        df = pd.read_csv(p_obj, sep=sep)
        
        # Filter for numeric wavelength columns (drop MeasCtr, TimeStamp, etc.)
        numeric_cols = []
        for col in df.columns:
            try:
                float(str(col).strip())
                numeric_cols.append(col)
            except ValueError:
                pass
                
        if numeric_cols:
            df = df[numeric_cols]
            
        if wavelengths is None:
            if numeric_cols:
                wavelengths = np.array([float(str(c).strip()) for c in numeric_cols], dtype=np.float64)
            else:
                wavelengths = np.arange(df.shape[1], dtype=np.float64)
                
        point_raw_shots[p_obj.stem] = df.to_numpy(dtype=np.float64)
        
    p_len = len(wavelengths)
    all_raw_shots = np.vstack(list(point_raw_shots.values()))

    # 1. Lit mask identification (shot-level) & dark-level subtraction
    lit_mask, _, _ = apply_lit_and_saturation_mask(
        all_raw_shots,
        det_full_scale=det_full_scale,
        sat_factor=sat_factor,
    )
    _, dark_level = dark_subtract(all_raw_shots, lit_mask=lit_mask)
    for pt_name in point_raw_shots:
        point_raw_shots[pt_name], _ = dark_subtract(
            point_raw_shots[pt_name], dark_level=dark_level
        )

    # 2. Conditioning shot trimming per point (shot-level)
    discarded_per_pt: Dict[str, int] = {}
    steady_shots_by_pt: Dict[str, np.ndarray] = {}
    for pt_name, pt_shots in point_raw_shots.items():
        steady_pt, n0 = trim_conditioning_shots(
            pt_shots,
            lit_mask,
            discard_override=discard_override,
        )
        discarded_per_pt[pt_name] = n0
        steady_shots_by_pt[pt_name] = steady_pt

    all_steady_shots = np.vstack(list(steady_shots_by_pt.values()))
    n_steady_total = len(all_steady_shots)

    # 3. Saturation masking on steady shots (shot-level, without zeroing)
    lit_mask, valid_readings_mask, channel_valid = apply_lit_and_saturation_mask(
        all_steady_shots,
        det_full_scale=det_full_scale,
        sat_factor=sat_factor,
        lit_mask=lit_mask,
    )
    
    # 4. Shot-scale normalization
    # Compute initial reference mean over valid lit channels
    valid_matrix = np.where(valid_readings_mask & channel_valid[None, :], all_steady_shots, np.nan)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", category=RuntimeWarning)
        initial_steady_mean = np.nanmean(valid_matrix, axis=0)
    initial_steady_mean = np.nan_to_num(initial_steady_mean, nan=0.0)
    
    active_channels = channel_valid & (initial_steady_mean > 0)
    ref_vec = initial_steady_mean[active_channels]
    denom = float(np.sum(ref_vec ** 2))
    
    # Pass 1 scale
    a_scales = np.ones(n_steady_total, dtype=np.float64)
    if denom > 1e-12:
        for i in range(n_steady_total):
            shot_valid = valid_readings_mask[i] & active_channels
            if np.sum(shot_valid) > 0:
                dot = np.sum(all_steady_shots[i, shot_valid] * initial_steady_mean[shot_valid])
                norm_sq = np.sum(initial_steady_mean[shot_valid] ** 2)
                if norm_sq > 1e-12:
                    a_scales[i] = max(dot / norm_sq, 1e-4)
                    
    normed_shots = all_steady_shots / a_scales[:, None]
    
    # Pass 2 refinement
    valid_matrix_pass1 = np.where(valid_readings_mask & channel_valid[None, :], normed_shots, np.nan)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", category=RuntimeWarning)
        ref_mean_pass1 = np.nanmean(valid_matrix_pass1, axis=0)
    ref_mean_pass1 = np.nan_to_num(ref_mean_pass1, nan=0.0)
    
    active_pass1 = channel_valid & (ref_mean_pass1 > 0)
    a_scales_refined = np.ones(n_steady_total, dtype=np.float64)
    for i in range(n_steady_total):
        shot_valid = valid_readings_mask[i] & active_pass1
        if np.sum(shot_valid) > 0:
            dot = np.sum(all_steady_shots[i, shot_valid] * ref_mean_pass1[shot_valid])
            norm_sq = np.sum(ref_mean_pass1[shot_valid] ** 2)
            if norm_sq > 1e-12:
                a_scales_refined[i] = max(dot / norm_sq, 1e-4)
                
    final_normed_shots = all_steady_shots / a_scales_refined[:, None]
    final_valid_matrix = np.where(valid_readings_mask, final_normed_shots, np.nan)
    
    # 5. Mean & sigma_mean
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", category=RuntimeWarning)
        mean_spectrum = np.nanmean(final_valid_matrix, axis=0)
    mean_spectrum = np.nan_to_num(mean_spectrum, nan=0.0)
    
    # Standard error of the mean
    sigma_mean = np.zeros(p_len, dtype=np.float64)
    for p in range(p_len):
        valid_vals = final_normed_shots[valid_readings_mask[:, p], p]
        k = len(valid_vals)
        if k >= 2:
            var_p = float(np.var(valid_vals, ddof=1))
            sigma_mean[p] = np.sqrt(max(var_p / k, 0.0))
        elif k == 1:
            sigma_mean[p] = 0.1 * float(valid_vals[0])
        else:
            sigma_mean[p] = 0.0
            
    preprocessed_mean = apply_morphological_baseline(mean_spectrum, se_size=struct_size)
    
    # Per-point conditioned means (raw and post-average baseline-subtracted)
    point_means: Dict[str, np.ndarray] = {}
    preprocessed_point_means: Dict[str, np.ndarray] = {}
    offset = 0
    for pt_name, pt_steady in steady_shots_by_pt.items():
        k_pt = len(pt_steady)
        pt_normed = final_valid_matrix[offset: offset + k_pt]
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", category=RuntimeWarning)
            pt_m = np.nanmean(pt_normed, axis=0)
        pt_mean_clean = np.nan_to_num(pt_m, nan=0.0)
        point_means[pt_name] = pt_mean_clean
        preprocessed_point_means[pt_name] = apply_morphological_baseline(
            pt_mean_clean, se_size=struct_size
        )
        offset += k_pt
        
    return ConditionedMaterial(
        wavelengths=wavelengths,
        mean_spectrum=mean_spectrum,
        preprocessed_mean=preprocessed_mean,
        sigma_mean=sigma_mean,
        valid_mask=channel_valid,
        lit_mask=lit_mask,
        dark_level=dark_level,
        point_means=point_means,
        discarded_shots_per_point=discarded_per_pt,
        preprocessed_point_means=preprocessed_point_means,
        num_steady_shots=int(n_steady_total),
    )
