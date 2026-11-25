"""Shared spectral match metrics for LIBS calibration engine and validation (R11.1).

Computes reduced chi-squared, ROI and whole-spectrum R^2 and normalized RMSE,
sub-bin residual distributions for thin and prone lines, and element parity tables.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Tuple, Union
import numpy as np


_VALID_NORMALIZATION_MODES = ("none", "max", "l2", "sum")


def _as_1d_float64(values: Any, name: str) -> np.ndarray:
    """Convert array-like input to a 1D float64 NumPy array."""
    arr = np.asarray(values, dtype=np.float64)
    if arr.ndim != 1:
        raise ValueError(f"{name} must be a 1D array, got ndim={arr.ndim}")
    return arr


def _normalization_scale(arr: np.ndarray, mode: str, eps: float) -> float:
    """Compute canonical normalization denominator matching metric_contract_v1.json."""
    if mode == "none":
        return 1.0
    if mode == "max":
        return max(float(np.max(np.abs(arr))), eps)
    if mode == "l2":
        return max(float(np.sqrt(np.sum(arr ** 2))), eps)
    if mode == "sum":
        return max(float(np.sum(np.abs(arr))), eps)
    raise ValueError(
        f"Unsupported normalization mode '{mode}'; expected one of {_VALID_NORMALIZATION_MODES}"
    )


def compute_spectral_metrics(
    y_ref: Union[List[float], np.ndarray],
    y_pred: Union[List[float], np.ndarray],
    *,
    wavelengths_ref: Optional[Union[List[float], np.ndarray]] = None,
    wavelengths_pred: Optional[Union[List[float], np.ndarray]] = None,
    variance: Optional[Union[List[float], np.ndarray]] = None,
    mask: Optional[Union[List[bool], np.ndarray]] = None,
    normalization: str = "none",
    num_params: int = 0,
    eps: float = 1e-12,
    wavelength_tol: float = 1e-6,
) -> Dict[str, Any]:
    """Compute canonical spectral comparison metrics per metric_contract_v1.json.

    Pure, stateless IEEE 754 float64 evaluator returning:
    rmse, nrmse_max, mae, r2, pearson_r2, cosine_similarity, reduced_chi2,
    normalization, and n_valid.
    """
    if not isinstance(normalization, str):
        raise ValueError(f"normalization must be a string, got {type(normalization).__name__}")
    norm_mode = normalization.strip().lower()
    if norm_mode not in _VALID_NORMALIZATION_MODES:
        raise ValueError(
            f"Unsupported normalization mode '{normalization}'; "
            f"expected one of {_VALID_NORMALIZATION_MODES}"
        )

    ref = _as_1d_float64(y_ref, "y_ref")
    pred = _as_1d_float64(y_pred, "y_pred")
    n_total = ref.size
    if pred.size != n_total:
        raise ValueError(
            f"y_ref and y_pred length mismatch: {n_total} != {pred.size}"
        )

    wl_ref_arr: Optional[np.ndarray] = None
    wl_pred_arr: Optional[np.ndarray] = None
    if wavelengths_ref is not None:
        wl_ref_arr = _as_1d_float64(wavelengths_ref, "wavelengths_ref")
        if wl_ref_arr.size != n_total:
            raise ValueError(
                f"wavelengths_ref length mismatch: {wl_ref_arr.size} != {n_total}"
            )
    if wavelengths_pred is not None:
        wl_pred_arr = _as_1d_float64(wavelengths_pred, "wavelengths_pred")
        if wl_pred_arr.size != n_total:
            raise ValueError(
                f"wavelengths_pred length mismatch: {wl_pred_arr.size} != {n_total}"
            )
    if wl_ref_arr is not None and wl_pred_arr is not None and n_total > 0:
        max_wl_diff = float(np.max(np.abs(wl_ref_arr - wl_pred_arr)))
        if max_wl_diff > wavelength_tol:
            raise ValueError(
                f"wavelength grid mismatch: max |wavelengths_ref - wavelengths_pred| = "
                f"{max_wl_diff:.3e} nm exceeds wavelength_tol={wavelength_tol:.3e} nm"
            )

    var_arr: Optional[np.ndarray] = None
    if variance is not None:
        var_arr = _as_1d_float64(variance, "variance")
        if var_arr.size != n_total:
            raise ValueError(
                f"variance length mismatch: {var_arr.size} != {n_total}"
            )

    if mask is not None:
        mask_arr = np.asarray(mask, dtype=bool)
        if mask_arr.ndim != 1 or mask_arr.size != n_total:
            raise ValueError(
                f"mask must be a 1D boolean array of length {n_total}, got shape {mask_arr.shape}"
            )
        ref = ref[mask_arr]
        pred = pred[mask_arr]
        if var_arr is not None:
            var_arr = var_arr[mask_arr]

    n_valid = int(ref.size)
    if n_valid == 0:
        raise ValueError("Cannot compute spectral metrics with n_valid == 0 (empty input or mask)")

    scale_ref = _normalization_scale(ref, norm_mode, eps)
    scale_pred = _normalization_scale(pred, norm_mode, eps)
    ref_n = ref / scale_ref
    pred_n = pred / scale_pred

    diff = ref_n - pred_n
    abs_diff = np.abs(diff)
    ss_res = float(np.sum(diff ** 2))
    rmse = float(np.sqrt(ss_res / n_valid))
    nrmse_max = float(rmse / max(float(np.max(np.abs(ref_n))), eps))
    mae = float(np.mean(abs_diff))

    ref_mean = float(np.mean(ref_n))
    ref_centered = ref_n - ref_mean
    ss_tot = float(np.sum(ref_centered ** 2))
    if ss_tot > eps:
        r2 = float(1.0 - (ss_res / ss_tot))
    else:
        r2 = 1.0 if ss_res < eps else 0.0

    pred_mean = float(np.mean(pred_n))
    pred_centered = pred_n - pred_mean
    ss_pred = float(np.sum(pred_centered ** 2))
    max_abs_diff = float(np.max(abs_diff))
    if ss_tot <= eps or ss_pred <= eps:
        pearson_r2 = 1.0 if max_abs_diff <= eps else 0.0
    else:
        corr = float(np.sum(ref_centered * pred_centered) / np.sqrt(ss_tot * ss_pred))
        pearson_r2 = float(min(max(corr * corr, 0.0), 1.0))

    norm_ref = float(np.sqrt(np.sum(ref_n ** 2)))
    norm_pred = float(np.sqrt(np.sum(pred_n ** 2)))
    if norm_ref <= eps or norm_pred <= eps:
        cosine_similarity = 1.0 if max_abs_diff <= eps else 0.0
    else:
        cosine_similarity = float(np.sum(ref_n * pred_n) / (norm_ref * norm_pred))

    reduced_chi2: Optional[float]
    if var_arr is None:
        reduced_chi2 = None
    else:
        var_scaled = var_arr if norm_mode == "none" else var_arr / (scale_ref ** 2)
        var_safe = np.maximum(var_scaled, eps)
        dof = max(n_valid - int(num_params), 1)
        reduced_chi2 = float(np.sum((diff ** 2) / var_safe) / dof)

    return {
        "rmse": rmse,
        "nrmse_max": nrmse_max,
        "mae": mae,
        "r2": r2,
        "pearson_r2": pearson_r2,
        "cosine_similarity": cosine_similarity,
        "reduced_chi2": reduced_chi2,
        "normalization": norm_mode,
        "n_valid": n_valid,
    }


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
