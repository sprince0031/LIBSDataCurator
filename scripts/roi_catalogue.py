"""ROI Catalogue selection and sub-bin partitioning (R5).

Composition-informed selection of Regions of Interest (ROIs) for joint
plasma and instrument response calibration.

Guiding principles (R5, AC3):
- Element-neutral: No hardcoded element names, category weights, or element-specific constants.
- Certified element coverage: Every certified element >= 0.05 wt% with detectable lines is represented.
- Tercile stratification: Thin lines are stratified across Ek terciles per 25 nm band.
- Prone rule: Low-Ei lines of major elements (>= 5 wt%, Ei <= 2.0 eV) are flagged self-absorption prone.
- Sub-bin partitioning: Channel-disjoint runs of 4 detector channels (remainder < 3 joins previous).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple

import numpy as np
import pandas as pd

from scripts.measured_conditioning import ConditionedMaterial
from scripts.nist_lines import lte_strength, to_atomic_fractions

#: Default mask windows covering atmospheric / unmodelled / contaminant lines (R5.2)
DEFAULT_MASK_WINDOWS_NM: List[Tuple[float, float]] = [
    (653.3, 659.3),   # H
    (775.8, 778.8),   # O I
    (843.1, 846.1),   # O I
    (741.6, 747.6),   # N I
    (817.6, 825.0),   # N I
    (855.9, 872.6),   # N I
    (588.0, 590.6),   # Na
    (765.5, 770.9),   # K
    (392.6, 394.2),   # Ca II
    (396.0, 397.7),   # Ca II
    (421.9, 423.5),   # Ca I
]

DEFAULT_ROI_HALF_WIDTH_NM: float = 1.2
DEFAULT_SUB_BIN_PIXELS: int = 4
DEFAULT_LINES_PER_BAND: int = 12
DEFAULT_LINES_PER_ELEMENT: int = 6
DEFAULT_PRONE_EI_EV: float = 2.0
DEFAULT_PRONE_ELEMENT_WT_THRESHOLD: float = 5.0
DEFAULT_MIN_CERTIFIED_WT: float = 0.05
DEFAULT_MAX_UNC: float = 0.25
DEFAULT_MIN_LINE_RATIO: float = 1e-4
DEFAULT_MIN_WINDOW_LINE_FRACTION: float = 0.10
DEFAULT_MIN_WINDOW_MODEL_DETECTABILITY: float = 0.005
DEFAULT_MIN_MEASURED_SNR: float = 5.0
DEFAULT_MIN_LINE_SPACING_NM: float = 0.6
DEFAULT_PRONE_FLUX_FRACTION_MERGED: float = 0.30


@dataclass
class SubBin:
    """A channel-disjoint partition of a detector ROI window."""
    sub_bin_id: str
    channel_indices: List[int]
    wavelength_range_nm: Tuple[float, float]
    center_wavelength_nm: float
    used_in_response_fit: bool
    self_absorption_prone: bool

    def to_dict(self) -> Dict[str, Any]:
        return {
            "subBinId": self.sub_bin_id,
            "channelIndices": self.channel_indices,
            "minNm": round(self.wavelength_range_nm[0], 3),
            "maxNm": round(self.wavelength_range_nm[1], 3),
            "centerNm": round(self.center_wavelength_nm, 3),
            "usedInResponseFit": self.used_in_response_fit,
            "selfAbsorptionProne": self.self_absorption_prone,
        }


@dataclass
class RoiWindow:
    """An ROI window covering one or more atomic emission lines."""
    roi_id: str
    min_nm: float
    max_nm: float
    channel_indices: List[int]
    sub_bins: List[List[int]]
    self_absorption_prone: bool
    used_in_response_fit: bool
    dominant_element: str
    model_lines: List[Dict[str, Any]] = field(default_factory=list)
    per_material: Dict[str, Dict[str, float]] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "roiId": self.roi_id,
            "minNm": round(self.min_nm, 3),
            "maxNm": round(self.max_nm, 3),
            "channelIndices": self.channel_indices,
            "subBins": self.sub_bins,
            "selfAbsorptionProne": self.self_absorption_prone,
            "usedInResponseFit": self.used_in_response_fit,
            "dominantElement": self.dominant_element,
            "modelLines": self.model_lines,
            "perMaterial": self.per_material,
        }


def is_line_masked(wl_nm: float, mask_windows: Optional[List[Tuple[float, float]]] = None) -> bool:
    """Check if wavelength falls inside any exclusion mask window."""
    windows = DEFAULT_MASK_WINDOWS_NM if mask_windows is None else mask_windows
    for lo, hi in windows:
        if lo <= wl_nm <= hi:
            return True
    return False


def get_candidates(
    compositions: Dict[str, Dict[str, float]],
    model_lines: pd.DataFrame,
    lit_range: Tuple[float, float],
    min_certified_wt: float = DEFAULT_MIN_CERTIFIED_WT,
    max_unc: float = DEFAULT_MAX_UNC,
    mask_windows: Optional[List[Tuple[float, float]]] = None,
) -> pd.DataFrame:
    """Filter model lines to eligible candidates (R5.1, R5.2)."""
    # 1. Identify certified elements with max wt% >= min_certified_wt
    elem_max_wt: Dict[str, float] = {}
    for comp in compositions.values():
        for el, wt in comp.items():
            elem_max_wt[el] = max(elem_max_wt.get(el, 0.0), float(wt))

    certified_elements = {el for el, wt in elem_max_wt.items() if wt >= min_certified_wt}

    if model_lines.empty or not certified_elements:
        return pd.DataFrame()

    # 2. Filter model lines
    df = model_lines[
        model_lines["element"].isin(certified_elements)
        & (model_lines["wavelength_nm"] >= lit_range[0])
        & (model_lines["wavelength_nm"] <= lit_range[1])
        & (model_lines["boltzmann_ok"] == True)
        & (model_lines["Aki"] > 0)
    ].copy()

    if "unc" in df.columns:
        df = df[df["unc"] <= max_unc].copy()

    # Exclude masked windows
    mask = [not is_line_masked(wl, mask_windows) for wl in df["wavelength_nm"]]
    df = df[mask].copy()

    return df.reset_index(drop=True)


def merge_windows(windows: List[Tuple[float, float]]) -> List[Tuple[float, float]]:
    """Merge overlapping intervals into disjoint contiguous intervals."""
    if not windows:
        return []
    sorted_wins = sorted(windows, key=lambda w: w[0])
    merged: List[Tuple[float, float]] = [sorted_wins[0]]

    for current in sorted_wins[1:]:
        prev_lo, prev_hi = merged[-1]
        curr_lo, curr_hi = current
        if curr_lo <= prev_hi:
            merged[-1] = (prev_lo, max(prev_hi, curr_hi))
        else:
            merged.append(current)

    return merged


def partition_sub_bins(channel_indices: List[int], sub_bin_pixels: int = DEFAULT_SUB_BIN_PIXELS) -> List[List[int]]:
    """Partition a list of channel indices into runs of sub_bin_pixels.
    
    A final remainder shorter than 3 channels joins the previous run.
    """
    n = len(channel_indices)
    if n == 0:
        return []
    if n < 3:
        return [channel_indices]

    runs: List[List[int]] = []
    for i in range(0, n, sub_bin_pixels):
        runs.append(channel_indices[i : i + sub_bin_pixels])

    if len(runs) > 1 and len(runs[-1]) < 3:
        last = runs.pop()
        runs[-1].extend(last)

    return runs


def build_catalogue(
    family: str,
    compositions: Dict[str, Dict[str, float]],
    model_lines: pd.DataFrame,
    dictionary_ref: np.ndarray,
    conditioned: Dict[str, ConditionedMaterial],
    detector_wavelengths: np.ndarray,
    roi_half_width_nm: float = DEFAULT_ROI_HALF_WIDTH_NM,
    sub_bin_pixels: int = DEFAULT_SUB_BIN_PIXELS,
    lines_per_band: int = DEFAULT_LINES_PER_BAND,
    lines_per_element: int = DEFAULT_LINES_PER_ELEMENT,
    prone_ei_ev: float = DEFAULT_PRONE_EI_EV,
    prone_element_wt_threshold: float = DEFAULT_PRONE_ELEMENT_WT_THRESHOLD,
    min_certified_wt: float = DEFAULT_MIN_CERTIFIED_WT,
    max_unc: float = DEFAULT_MAX_UNC,
    min_line_ratio: float = DEFAULT_MIN_LINE_RATIO,
    min_window_line_fraction: float = DEFAULT_MIN_WINDOW_LINE_FRACTION,
    min_window_model_detectability: float = DEFAULT_MIN_WINDOW_MODEL_DETECTABILITY,
    min_measured_snr: float = DEFAULT_MIN_MEASURED_SNR,
    min_line_spacing_nm: float = DEFAULT_MIN_LINE_SPACING_NM,
    prone_flux_fraction_merged: float = DEFAULT_PRONE_FLUX_FRACTION_MERGED,
    mask_windows_nm: Optional[List[Tuple[float, float]]] = None,
) -> List[RoiWindow]:
    """Build the composition-informed ROI catalogue for a material family (R5)."""
    det_wl = np.asarray(detector_wavelengths, dtype=np.float64)

    # Determine lit range across calibrated materials
    common_lit = np.ones(len(det_wl), dtype=bool)
    for cond in conditioned.values():
        if cond.lit_mask is not None:
            common_lit &= cond.lit_mask

    if np.any(common_lit):
        lit_channels = det_wl[common_lit]
        lit_range = (float(np.min(lit_channels)), float(np.max(lit_channels)))
    else:
        lit_range = (float(np.min(det_wl)), float(np.max(det_wl)))

    # Compute family representative atomic fractions
    elem_max_wt: Dict[str, float] = {}
    elem_avg_wt: Dict[str, float] = {}
    for comp in compositions.values():
        for el, wt in comp.items():
            elem_max_wt[el] = max(elem_max_wt.get(el, 0.0), float(wt))
            elem_avg_wt[el] = elem_avg_wt.get(el, 0.0) + float(wt) / len(compositions)

    certified_elements = {el for el, wt in elem_max_wt.items() if wt >= min_certified_wt}
    if not certified_elements:
        return []

    x_fam = to_atomic_fractions(elem_avg_wt)

    # 1. Candidates
    cands = get_candidates(
        compositions, model_lines, lit_range, min_certified_wt, max_unc, mask_windows_nm
    )
    if cands.empty:
        return []

    # Precompute LTE strength at 0.8 eV for all candidates
    cands = cands.copy()
    if "wl_nm" not in cands.columns:
        cands["wl_nm"] = cands["wavelength_nm"]
    if "gA" not in cands.columns:
        cands["gA"] = cands["g_k"] * cands["Aki"]
    cands["lte_st"] = lte_strength(cands, 0.8)
    cands["weighted_st"] = [
        r["lte_st"] * x_fam.get(r["element"], 0.0) for _, r in cands.iterrows()
    ]

    max_weighted_st = float(cands["weighted_st"].max()) if not cands.empty else 0.0
    if max_weighted_st <= 0.0:
        return []

    # Precalculate window model sums across detector
    # W_p(wl) over [wl - roi_half_width, wl + roi_half_width]
    dict_ref = np.asarray(dictionary_ref, dtype=np.float64)

    cand_indices: List[int] = []
    cand_win_fluxes: List[float] = []

    for idx, row in cands.iterrows():
        wl = float(row["wavelength_nm"])
        w_lo = wl - roi_half_width_nm
        w_hi = wl + roi_half_width_nm
        mask_chan = (det_wl >= w_lo) & (det_wl <= w_hi)
        win_sum = float(np.sum(dict_ref[mask_chan]))
        cand_indices.append(idx)
        cand_win_fluxes.append(win_sum)

    max_win_flux = max(cand_win_fluxes) if cand_win_fluxes else 0.0

    # 2. Filter by Model Detectability and Measured Detectability
    detectable_candidates = []
    for i, idx in enumerate(cand_indices):
        row = cands.loc[idx]
        wl = float(row["wavelength_nm"])
        w_lo = wl - roi_half_width_nm
        w_hi = wl + roi_half_width_nm
        w_flux = cand_win_fluxes[i]

        # Model detectability: window sum >= 0.5% of max window sum
        if max_win_flux > 0 and (w_flux < min_window_model_detectability * max_win_flux):
            continue

        # Line LTE significance: >= 1e-4 of max candidate
        line_st = float(row["weighted_st"])
        if line_st < min_line_ratio * max_weighted_st:
            continue

        # Line fraction: >= 10% of model lines inside its window
        in_win_lines = cands[
            (cands["wavelength_nm"] >= w_lo) & (cands["wavelength_nm"] <= w_hi)
        ]
        sum_win_lines = float(in_win_lines["weighted_st"].sum())
        if sum_win_lines > 0 and (line_st / sum_win_lines < min_window_line_fraction):
            continue

        # Measured detectability: >= 5 sigma in EVERY calibrated material
        chan_mask = (det_wl >= w_lo) & (det_wl <= w_hi)
        num_px = int(np.sum(chan_mask))
        if num_px == 0:
            continue

        measured_ok = True
        for cond in conditioned.values():
            y_win = float(np.sum(cond.mean_spectrum[chan_mask]))
            sig_win = float(np.sqrt(np.sum(cond.sigma_mean[chan_mask] ** 2)))
            if cond.lit_mask is not None and not np.all(cond.lit_mask):
                unlit_sig = cond.sigma_mean[~cond.lit_mask]
                sigma_dark = float(np.median(unlit_sig)) if len(unlit_sig) > 0 else 30.0
            else:
                sigma_dark = 30.0
            if sigma_dark <= 0.0:
                sigma_dark = 30.0
            dark_floor = 3.0 * np.sqrt(num_px) * sigma_dark
            denom = max(sig_win, dark_floor)
            if y_win < min_measured_snr * denom:
                measured_ok = False
                break

        if not measured_ok:
            continue

        # Prone flag
        el = row["element"]
        is_prone = (elem_max_wt.get(el, 0.0) >= prone_element_wt_threshold) and (
            float(row["Ei_eV"]) <= prone_ei_ev
        )

        detectable_candidates.append({
            "idx": idx,
            "species": row["species"],
            "element": el,
            "wl_nm": wl,
            "Ei_eV": float(row["Ei_eV"]),
            "Ek_eV": float(row["Ek_eV"]),
            "Aki": float(row["Aki"]),
            "accuracy": str(row.get("accuracy", "")),
            "unc": float(row.get("unc", 0.10)),
            "weighted_st": line_st,
            "is_prone": is_prone,
        })

    if not detectable_candidates:
        return []

    df_det = pd.DataFrame(detectable_candidates)

    # 3. Selection: Thin set and Element coverage
    selected_indices: Set[int] = set()

    # Thin set selection per 25 nm band
    df_thin = df_det[~df_det["is_prone"]].copy()
    if not df_thin.empty:
        band_starts = np.arange(lit_range[0], lit_range[1], 25.0)
        for b_lo in band_starts:
            b_hi = b_lo + 25.0
            in_band = df_thin[(df_thin["wl_nm"] >= b_lo) & (df_thin["wl_nm"] < b_hi)]
            if in_band.empty:
                continue

            sorted_ek = in_band.sort_values(by="Ek_eV").reset_index(drop=True)
            n_band = len(sorted_ek)
            t_size = max(1, n_band // 3)
            lines_per_tercile = max(1, lines_per_band // 3)

            terciles = [
                sorted_ek.iloc[:t_size],
                sorted_ek.iloc[t_size : 2 * t_size],
                sorted_ek.iloc[2 * t_size :],
            ]
            for t_df in terciles:
                if not t_df.empty:
                    top_t = t_df.sort_values(by="weighted_st", ascending=False).head(lines_per_tercile)
                    selected_indices.update(top_t["idx"].tolist())

    # Element coverage: up to lines_per_element per certified element (spacing >= min_line_spacing_nm)
    for el in certified_elements:
        el_cands = df_det[df_det["element"] == el].sort_values(by="weighted_st", ascending=False)
        if el_cands.empty:
            continue

        selected_for_el: List[float] = [
            float(df_det.loc[df_det["idx"] == i, "wl_nm"].iloc[0])
            for i in selected_indices
            if df_det.loc[df_det["idx"] == i, "element"].iloc[0] == el
        ]

        for _, row in el_cands.iterrows():
            if len(selected_for_el) >= lines_per_element:
                break
            line_wl = float(row["wl_nm"])
            cand_idx = int(row["idx"])
            if cand_idx in selected_indices:
                continue

            # Check min line spacing
            if any(abs(line_wl - sel_wl) < min_line_spacing_nm for sel_wl in selected_for_el):
                continue

            selected_indices.add(cand_idx)
            selected_for_el.append(line_wl)

    if not selected_indices:
        return []

    # 4. Windows, Merging and Sub-bin partitioning
    df_sel = df_det[df_det["idx"].isin(selected_indices)].sort_values(by="wl_nm").reset_index(drop=True)

    raw_windows = [(float(row["wl_nm"]) - roi_half_width_nm, float(row["wl_nm"]) + roi_half_width_nm) for _, row in df_sel.iterrows()]
    merged_intervals = merge_windows(raw_windows)

    rois: List[RoiWindow] = []

    for roi_idx, (w_lo, w_hi) in enumerate(merged_intervals):
        # Channels in merged window
        chan_mask = (det_wl >= w_lo) & (det_wl <= w_hi)
        chan_indices = [int(p) for p in np.where(chan_mask)[0]]
        if len(chan_indices) == 0:
            continue

        # Model lines in window
        in_win = df_det[(df_det["wl_nm"] >= w_lo) & (df_det["wl_nm"] <= w_hi)]
        model_lines_list: List[Dict[str, Any]] = []
        for _, r in in_win.iterrows():
            model_lines_list.append({
                "species": str(r["species"]),
                "wlNm": round(float(r["wl_nm"]), 4),
                "EiEv": round(float(r["Ei_eV"]), 3),
                "EkEv": round(float(r["Ek_eV"]), 3),
                "grade": str(r["accuracy"]),
                "unc": round(float(r["unc"]), 3),
            })

        # Calculate prone status:
        # A merged window is prone if any prone line contributes more than 30% of its reference model flux
        total_win_flux = float(np.sum(dict_ref[chan_mask]))
        prone_lines = in_win[in_win["is_prone"]]

        is_window_prone = False
        if not prone_lines.empty:
            if total_win_flux > 0:
                # Estimate flux contribution of prone lines
                prone_st = float(prone_lines["weighted_st"].sum())
                total_st = float(in_win["weighted_st"].sum())
                if total_st > 0 and (prone_st / total_st > prone_flux_fraction_merged):
                    is_window_prone = True
            else:
                is_window_prone = True

        used_in_response = not is_window_prone

        # Dominant element
        elem_st = in_win.groupby("element")["weighted_st"].sum()
        dom_elem = str(elem_st.idxmax()) if not elem_st.empty else in_win.iloc[0]["element"]

        center_wl = 0.5 * (w_lo + w_hi)
        roi_id = f"ROI_{roi_idx:03d}_{dom_elem}_{int(round(center_wl))}"

        sub_bins = partition_sub_bins(chan_indices, sub_bin_pixels)

        rois.append(RoiWindow(
            roi_id=roi_id,
            min_nm=w_lo,
            max_nm=w_hi,
            channel_indices=chan_indices,
            sub_bins=sub_bins,
            self_absorption_prone=is_window_prone,
            used_in_response_fit=used_in_response,
            dominant_element=dom_elem,
            model_lines=model_lines_list,
        ))

    return rois


def get_response_set_sub_bins(rois: List[RoiWindow]) -> List[List[int]]:
    """Return all sub-bins belonging to thin (non-prone) ROI windows."""
    sub_bins: List[List[int]] = []
    for r in rois:
        if r.used_in_response_fit:
            sub_bins.extend(r.sub_bins)
    return sub_bins


def get_full_set_sub_bins(rois: List[RoiWindow]) -> List[List[int]]:
    """Return all sub-bins across all ROI windows."""
    sub_bins: List[List[int]] = []
    for r in rois:
        sub_bins.extend(r.sub_bins)
    return sub_bins


def get_union_channel_indices(rois: List[RoiWindow]) -> List[int]:
    """Return sorted unique list of all detector channel indices in the catalogue."""
    channels: Set[int] = set()
    for r in rois:
        channels.update(r.channel_indices)
    return sorted(channels)
