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

from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, List, Optional
import warnings

import numpy as np
import pandas as pd
from scipy.ndimage import grey_opening


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


def preprocess(spectrum: np.ndarray, struct_size: int = 65) -> np.ndarray:
    """Apply morphological grey opening baseline subtraction followed by non-negative clamping.
    
    Idempotent on previously processed spectra.
    """
    spec = np.asarray(spectrum, dtype=np.float64)
    if len(spec) < struct_size:
        return np.maximum(spec, 0.0)
    opening = grey_opening(spec, size=struct_size)
    return np.maximum(spec - opening, 0.0)


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
    sat_threshold = sat_factor * det_full_scale
    struct_size = int(manifest.get("structuringElementSize", 65))
    discard_override = manifest.get("discardLeadingShots")
    
    if not csv_paths:
        raise ValueError("csv_paths must contain at least one CSV file path")
        
    point_raw_shots: Dict[str, np.ndarray] = {}
    wavelengths: Optional[np.ndarray] = None
    
    for path in csv_paths:
        p_obj = Path(path)
        df = pd.read_csv(p_obj)
        if wavelengths is None:
            try:
                wavelengths = np.array([float(col) for col in df.columns], dtype=np.float64)
            except ValueError:
                wavelengths = np.arange(df.shape[1], dtype=np.float64)
        point_raw_shots[p_obj.stem] = df.to_numpy(dtype=np.float64)
        
    p_len = len(wavelengths)
    all_raw_shots = np.vstack(list(point_raw_shots.values()))
    
    # 1. Lit mask & dark level
    initial_mean = np.mean(all_raw_shots, axis=0)
    m0 = float(np.median(initial_mean[:min(8, p_len)]))
    
    unlit_end = 0
    for p in range(p_len):
        if initial_mean[p] > 3.0 * m0:
            unlit_end = p
            break
            
    lit_mask = np.zeros(p_len, dtype=bool)
    lit_mask[unlit_end:] = True
    
    if unlit_end >= 4:
        dark_level = float(np.median(initial_mean[:unlit_end]))
    else:
        dark_level = 0.0
        
    # Subtract dark level across all shots
    for pt_name in point_raw_shots:
        point_raw_shots[pt_name] = point_raw_shots[pt_name] - dark_level
        
    # 2. Conditioning shots per point
    discarded_per_pt: Dict[str, int] = {}
    steady_shots_by_pt: Dict[str, np.ndarray] = {}
    
    for pt_name, pt_shots in point_raw_shots.items():
        n_total = len(pt_shots)
        if discard_override is not None:
            n0 = int(discard_override)
        else:
            if n_total <= 3:
                n0 = 0
            else:
                ref_half = np.mean(pt_shots[n_total // 2:], axis=0)
                ref_lit = ref_half[lit_mask]
                ref_norm = ref_lit - np.mean(ref_lit)
                ref_std = np.std(ref_lit)
                
                last_sub = -1
                for s in range(n_total):
                    s_lit = pt_shots[s, lit_mask]
                    s_norm = s_lit - np.mean(s_lit)
                    s_std = np.std(s_lit)
                    if ref_std > 1e-12 and s_std > 1e-12:
                        r = float(np.mean(s_norm * ref_norm) / (s_std * ref_std))
                    else:
                        r = 0.0
                    if r < 0.999:
                        last_sub = s
                        
                n0 = int(np.clip(last_sub + 1, 3, min(15, n_total - 1)))
                
        discarded_per_pt[pt_name] = n0
        steady_shots_by_pt[pt_name] = pt_shots[n0:]
        
    all_steady_shots = np.vstack(list(steady_shots_by_pt.values()))
    n_steady_total = len(all_steady_shots)
    
    # 3. Saturation masking
    is_saturated = all_steady_shots >= sat_threshold
    valid_readings_mask = ~is_saturated
    
    valid_counts_per_channel = np.sum(valid_readings_mask, axis=0)
    min_valid_shots = max(5, int(0.25 * n_steady_total))
    
    channel_valid = (valid_counts_per_channel >= min_valid_shots) & lit_mask
    
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
            
    preprocessed_mean = preprocess(mean_spectrum, struct_size=struct_size)
    
    # Per-point conditioned means
    point_means: Dict[str, np.ndarray] = {}
    offset = 0
    for pt_name, pt_steady in steady_shots_by_pt.items():
        k_pt = len(pt_steady)
        pt_normed = final_valid_matrix[offset: offset + k_pt]
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", category=RuntimeWarning)
            pt_m = np.nanmean(pt_normed, axis=0)
        point_means[pt_name] = np.nan_to_num(pt_m, nan=0.0)
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
    )
