"""Shared spectral match metrics for LIBS calibration engine and validation (R11.1).

Computes reduced chi-squared, ROI and whole-spectrum R^2 and normalized RMSE,
sub-bin residual distributions for thin and prone lines, and element parity tables.
"""

from __future__ import annotations

from typing import Dict, List, Optional, Tuple, Union
import numpy as np


def calculate_reduced_chi2(
    measured: np.ndarray,
    synthetic: np.ndarray,
    variances: np.ndarray,
    num_params: int = 0
) -> float:
    """Calculate reduced chi-squared: (1 / dof) * sum((Y - Y_hat)^2 / var)."""
    y = np.asarray(measured, dtype=np.float64)
    y_hat = np.asarray(synthetic, dtype=np.float64)
    var = np.asarray(variances, dtype=np.float64)

    valid = var > 0.0
    if not np.any(valid):
        return 0.0

    residuals_sq = (y[valid] - y_hat[valid]) ** 2
    chi2 = np.sum(residuals_sq / var[valid])
    n = np.sum(valid)
    dof = max(n - num_params, 1)
    return float(chi2 / dof)


def calculate_roi_metrics(
    measured_spectrum: np.ndarray,
    synthetic_spectrum: np.ndarray,
    roi_channel_indices: Union[List[int], np.ndarray]
) -> Tuple[float, float]:
    """Calculate channel-wise R^2 and normalized RMSE over union of ROI channels.

    RMSE is normalised by the largest measured value in that union (§5.1).
    """
    chans = np.asarray(roi_channel_indices, dtype=np.int64)
    if len(chans) == 0:
        return 0.0, 0.0

    y = np.asarray(measured_spectrum, dtype=np.float64)[chans]
    y_hat = np.asarray(synthetic_spectrum, dtype=np.float64)[chans]

    ss_res = np.sum((y - y_hat) ** 2)
    y_mean = np.mean(y)
    ss_tot = np.sum((y - y_mean) ** 2)

    if ss_tot > 1e-12:
        r2 = float(1.0 - (ss_res / ss_tot))
    else:
        r2 = 1.0 if ss_res < 1e-12 else 0.0

    y_max = np.max(y) if len(y) > 0 else 1.0
    if y_max <= 0.0:
        y_max = 1.0

    rmse = float(np.sqrt(np.mean((y - y_hat) ** 2)) / y_max)
    return r2, rmse


def calculate_whole_spectrum_metrics(
    measured_spectrum: np.ndarray,
    synthetic_spectrum: np.ndarray,
    lit_channel_mask: Union[List[bool], np.ndarray]
) -> Tuple[float, float]:
    """Calculate channel-wise R^2 and normalized RMSE over lit channels.

    RMSE is normalised by the largest measured value in that set (§5.1).
    """
    mask = np.asarray(lit_channel_mask, dtype=bool)
    if not np.any(mask):
        return 0.0, 0.0

    y = np.asarray(measured_spectrum, dtype=np.float64)[mask]
    y_hat = np.asarray(synthetic_spectrum, dtype=np.float64)[mask]

    ss_res = np.sum((y - y_hat) ** 2)
    y_mean = np.mean(y)
    ss_tot = np.sum((y - y_mean) ** 2)

    if ss_tot > 1e-12:
        r2 = float(1.0 - (ss_res / ss_tot))
    else:
        r2 = 1.0 if ss_res < 1e-12 else 0.0

    y_max = np.max(y) if len(y) > 0 else 1.0
    if y_max <= 0.0:
        y_max = 1.0

    rmse = float(np.sqrt(np.mean((y - y_hat) ** 2)) / y_max)
    return r2, rmse


def calculate_sub_bin_residuals(
    measured_sub_bins: np.ndarray,
    synthetic_sub_bins: np.ndarray,
    prone_flags: Union[List[bool], np.ndarray],
    eps: float = 1e-12
) -> Dict[str, Dict[str, Optional[float]]]:
    """Distribution of sub-bin log10(synthetic/measured): median, 10th and 90th percentile,

    reported separately for thin and self-absorption prone sub-bins.
    """
    y = np.asarray(measured_sub_bins, dtype=np.float64)
    y_hat = np.asarray(synthetic_sub_bins, dtype=np.float64)
    prone = np.asarray(prone_flags, dtype=bool)

    log_ratios = np.log10(np.maximum(y_hat, eps) / np.maximum(y, eps))

    thin_mask = ~prone
    prone_mask = prone

    def _stats(vals: np.ndarray) -> Dict[str, Optional[float]]:
        if len(vals) == 0:
            return {"median": None, "p10": None, "p90": None, "count": 0}
        return {
            "median": round(float(np.percentile(vals, 50)), 6),
            "p10": round(float(np.percentile(vals, 10)), 6),
            "p90": round(float(np.percentile(vals, 90)), 6),
            "count": int(len(vals)),
        }

    return {
        "thin": _stats(log_ratios[thin_mask]),
        "prone": _stats(log_ratios[prone_mask]),
    }


def calculate_element_parity_table(
    dominant_elements: List[str],
    measured_sub_bins: np.ndarray,
    synthetic_sub_bins: np.ndarray,
    eps: float = 1e-12
) -> Dict[str, float]:
    """Per-element median of log10(synthetic/measured) across sub-bins assigned to that element."""
    y = np.asarray(measured_sub_bins, dtype=np.float64)
    y_hat = np.asarray(synthetic_sub_bins, dtype=np.float64)

    elem_ratios: Dict[str, List[float]] = {}
    for elem, y_m, y_s in zip(dominant_elements, y, y_hat):
        if not elem:
            continue
        ratio = float(np.log10(max(y_s, eps) / max(y_m, eps)))
        elem_ratios.setdefault(elem, []).append(ratio)

    parity_table: Dict[str, float] = {}
    for elem, ratios in elem_ratios.items():
        parity_table[elem] = round(float(np.median(ratios)), 4)

    return parity_table
