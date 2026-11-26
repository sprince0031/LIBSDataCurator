"""Joint plasma and instrument response calibration solver (R6, R7, R9).

Solves the bi-convex optimization problem:
    Phi = sum_{m, b} rho_c((Y_{m,b} - M_{m,b}) / sigma_{m,b}) + mu_R * v_bar * ||Delta_2 beta||^2
over the response set of thin sub-bins using alternating NNLS (Block A)
and regularized B-spline regression (Block B).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Set, Tuple

import numpy as np
from scipy.interpolate import BSpline
from scipy.optimize import nnls

try:
    from scripts.forward_model import (
        PlasmaZoneSpec,
        apply_multi_zone_rte,
        apply_response_and_scale,
    )
    from scripts.measured_conditioning import ConditionedMaterial
    from scripts.roi_catalogue import RoiWindow
except ImportError:
    from forward_model import (
        PlasmaZoneSpec,
        apply_multi_zone_rte,
        apply_response_and_scale,
    )
    from measured_conditioning import ConditionedMaterial
    from roi_catalogue import RoiWindow


@dataclass
class CalibrationResult:
    """Output of the joint plasma-response solver."""
    spectral_response: np.ndarray
    response_beta: np.ndarray
    plasma_weights: Dict[str, np.ndarray]
    converged: bool
    iterations: int
    objective_value: float
    response_restart_spread: float
    family_group_response_rms_diff: float
    diagnostics_flags: List[str] = field(default_factory=list)
    reduced_chi2: Dict[str, float] = field(default_factory=dict)
    history_phi: List[float] = field(default_factory=list)
    history_beta: List[np.ndarray] = field(default_factory=list)

    @property
    def flags(self) -> List[str]:
        return self.diagnostics_flags


class CubicBSplineBasis:
    """Clamped uniform cubic B-spline basis on [span_lo, span_hi]."""

    def __init__(self, k_coeffs: int = 5, span: Tuple[float, float] = (350.0, 850.0)):
        if k_coeffs < 4:
            raise ValueError(f"k_coeffs must be at least 4, got {k_coeffs}")
        self.k_coeffs = k_coeffs
        self.span_lo, self.span_hi = float(span[0]), float(span[1])

        # Clamped knot vector: 4 knots at span_lo, (k_coeffs - 4) interior knots, 4 knots at span_hi
        # Total knots = k_coeffs + 4, giving exactly k_coeffs basis functions
        n_interior = k_coeffs - 4
        if n_interior > 0:
            interior_knots = np.linspace(self.span_lo, self.span_hi, n_interior + 2)[1:-1]
        else:
            interior_knots = np.empty(0, dtype=np.float64)

        self.knots = np.concatenate([
            np.full(4, self.span_lo),
            interior_knots,
            np.full(4, self.span_hi),
        ])

    def evaluate(self, wavelengths: np.ndarray) -> np.ndarray:
        """Evaluate B-spline basis matrix of shape (len(wavelengths), k_coeffs)."""
        wls = np.asarray(wavelengths, dtype=np.float64)
        # Clamped outside span: hold at boundary
        wls_clamped = np.clip(wls, self.span_lo, self.span_hi)
        return BSpline.design_matrix(wls_clamped, self.knots, 3).toarray()

    def second_difference_matrix(self) -> np.ndarray:
        """Compute the (k_coeffs - 2, k_coeffs) second difference matrix Delta_2."""
        d2 = np.zeros((self.k_coeffs - 2, self.k_coeffs), dtype=np.float64)
        for i in range(self.k_coeffs - 2):
            d2[i, i] = 1.0
            d2[i, i + 1] = -2.0
            d2[i, i + 2] = 1.0
        return d2


def reduce_knots_against_gap(
    centers: np.ndarray,
    span: Tuple[float, float],
    initial_k: int = 5,
) -> Tuple[int, bool]:
    """Reduce knot count K down to 4 until knot spacing >= 1.5 * widest gap (R7).
    
    Returns (K, gap_flag).
    """
    sorted_centers = np.sort(np.asarray(centers, dtype=np.float64))
    if len(sorted_centers) < 2:
        return 4, False

    gaps = sorted_centers[1:] - sorted_centers[:-1]
    widest_gap = float(np.max(gaps))

    span_width = float(span[1] - span[0])
    for k in range(initial_k, 3, -1):
        spacing = span_width / (k - 3)
        if spacing >= 1.5 * widest_gap:
            return k, False

    # Could not meet gap condition even at K=4
    return 4, True


def compute_sub_bin_variances(
    channels: List[int],
    mean_spectrum: np.ndarray,
    sigma_mean: np.ndarray,
    model_lines: Optional[List[Dict[str, Any]]] = None,
    model_error_fraction: float = 0.10,
    dark_std: float = 30.0,
) -> Dict[str, float]:
    """Compute sub-bin sum, atomic error, and total variance according to R6."""
    chans = np.asarray(channels, dtype=int)
    y_meas = mean_spectrum[chans]
    sig_meas = sigma_mean[chans]

    y_sum = float(np.sum(y_meas))
    meas_var = float(np.sum(sig_meas ** 2))

    # eps_atomic: strength-weighted RMS of unc over model lines, capped at 0.5
    if model_lines:
        weights = []
        uncs_sq = []
        for line in model_lines:
            st = float(line.get("weighted_st", 1.0))
            u = float(line.get("unc", 0.10))
            weights.append(st)
            uncs_sq.append(u ** 2)

        total_st = sum(weights)
        if total_st > 0:
            rms_unc = np.sqrt(sum(w * u2 for w, u2 in zip(weights, uncs_sq)) / total_st)
            eps_atomic = min(0.50, float(rms_unc))
        else:
            eps_atomic = 0.10
    else:
        eps_atomic = 0.10

    eps_b = float(np.sqrt(model_error_fraction ** 2 + eps_atomic ** 2))

    n_px = len(chans)
    d_std = dark_std if dark_std > 0 else 30.0
    sigma_floor = 3.0 * np.sqrt(n_px) * d_std

    sigma_sq = meas_var + (eps_b * y_sum) ** 2 + sigma_floor ** 2
    return {
        "Y": y_sum,
        "sigma_sq": sigma_sq,
        "sigma": float(np.sqrt(sigma_sq)),
        "eps_b": eps_b,
        "sigma_floor": sigma_floor,
        "meas_var": meas_var,
    }


def solve_block_a(
    A_m: np.ndarray,
    Y_m: np.ndarray,
    sigma_m: np.ndarray,
    *,
    huber_c: Optional[float] = None,
    l2_reg: float = 0.0,
    irls_iters: int = 4,
) -> np.ndarray:
    """Solve Block A (plasma weights) via column-scaled NNLS (with optional Huber IRLS and ridge):

    w_m = argmin_{w >= 0} ||(Y_m - A_m w) / sigma_m||^2  (+ l2_reg * ||w_scaled||^2)

    When ``l2_reg > 0.0``, columns of the weighted design matrix are normalized to unit L2 norm
    and augmented with ``[M_scaled; sqrt(l2_reg) * I]``, yielding an effective diagonal ridge of
    ``l2_reg`` on ``M_scaled^T M_scaled``. Default ``huber_c=None, l2_reg=0.0`` is exact NNLS.
    """
    sig = np.asarray(sigma_m, dtype=np.float64)
    sig = np.where(sig > 1e-12, sig, 1.0)

    if huber_c is None and l2_reg <= 0.0:
        M = A_m / sig[:, np.newaxis]
        y_scaled = Y_m / sig
        col_max = np.max(np.abs(M), axis=0)
        col_max = np.where(col_max > 1e-12, col_max, 1.0)
        M_scaled = M / col_max[np.newaxis, :]
        w_scaled = nnls(M_scaled, y_scaled)[0]
        return w_scaled / col_max

    n_iters = max(int(irls_iters), 1) if huber_c is not None else 1
    w_irls = np.ones(len(Y_m), dtype=np.float64)
    w_sol = np.zeros(A_m.shape[1], dtype=np.float64)
    aug_mult = float(np.sqrt(max(l2_reg, 0.0)))

    for _ in range(n_iters):
        eff_sig = sig / np.sqrt(np.maximum(w_irls, 1e-6))
        M = A_m / eff_sig[:, np.newaxis]
        y_scaled = Y_m / eff_sig
        col_norm = np.sqrt(np.sum(M ** 2, axis=0))
        col_norm = np.where(col_norm > 1e-12, col_norm, 1.0)
        M_scaled = M / col_norm[np.newaxis, :]
        if aug_mult > 0.0:
            n_states = M_scaled.shape[1]
            M_aug = np.vstack([M_scaled, aug_mult * np.eye(n_states, dtype=np.float64)])
            y_aug = np.concatenate([y_scaled, np.zeros(n_states, dtype=np.float64)])
            w_scaled = nnls(M_aug, y_aug)[0]
        else:
            w_scaled = nnls(M_scaled, y_scaled)[0]
        w_sol = w_scaled / col_norm

        if huber_c is not None:
            u = np.abs((Y_m - A_m @ w_sol) / sig)
            w_irls = np.where(u <= huber_c, 1.0, huber_c / np.maximum(u, 1e-12))

    return w_sol


def solve_block_b(
    basis: CubicBSplineBasis,
    sub_bin_centers: np.ndarray,
    rho: np.ndarray,
    weights: np.ndarray,
    mu_r: float = 1.0,
) -> np.ndarray:
    """Solve Block B (response B-spline beta) with gauge fixation:
    
    beta = (B^T V B + mu_R * v_bar * Delta_2^T Delta_2)^(-1) B^T V rho
    """
    B = basis.evaluate(sub_bin_centers)
    v = np.asarray(weights, dtype=np.float64)
    r_vec = np.asarray(rho, dtype=np.float64)

    v_bar = float(np.mean(v)) if len(v) > 0 else 1.0

    # B^T V B
    B_weighted = B * v[:, np.newaxis]
    BtVB = B.T @ B_weighted

    # mu_R * v_bar * Delta_2^T Delta_2
    D2 = basis.second_difference_matrix()
    reg = mu_r * v_bar * (D2.T @ D2)

    A_mat = BtVB + reg
    rhs = B.T @ (v * r_vec)

    try:
        beta = np.linalg.solve(A_mat, rhs)
    except np.linalg.LinAlgError:
        beta = np.linalg.lstsq(A_mat, rhs, rcond=None)[0]

    # Fix gauge: subtract v-weighted mean of log r at sub-bin centers
    log_r_centers = B @ beta
    sum_v = float(np.sum(v))
    gauge_offset = float(np.sum(v * log_r_centers) / sum_v) if sum_v > 0 else float(np.mean(log_r_centers))
    beta -= gauge_offset

    return beta


def evaluate_diagnostics(
    weights_by_mat: Dict[str, np.ndarray],
    te_grid: List[float],
    ne_grid: List[float],
    response_curve: np.ndarray,
    response_restart_spread: float,
    iterations: int,
    gap_flag: bool,
    reduced_chi2_by_mat: Dict[str, float],
    family_group_response_rms_diff: float = 0.0,
    source_resolving_power_diff: float = 0.0,
    composition_scale_verified: bool = False,
    zone_reduction_penalty: float = 1.0,
    k_absorption_selected: float = 0.0,
    element_biases: Optional[Dict[str, float]] = None,
    dictionary: Optional[Dict[str, np.ndarray]] = None,
) -> Dict[str, Any]:
    """Check R9 criteria and return list of raised flags."""
    flags: List[str] = []

    # 1. TE_GRID_EDGE: state at min or max Te carries > 5% of modeled emission
    n_te = len(te_grid)
    n_ne = len(ne_grid)
    min_te_indices = set(range(0, n_ne))  # first Te row
    max_te_indices = set(range((n_te - 1) * n_ne, n_te * n_ne))  # last Te row

    r_arr = np.asarray(response_curve, dtype=np.float64)
    for mat_name, w in weights_by_mat.items():
        w_arr = np.asarray(w, dtype=np.float64)
        if dictionary is not None and mat_name in dictionary:
            D_m = np.asarray(dictionary[mat_name], dtype=np.float64)
            n_cols = min(len(w_arr), D_m.shape[1])
            if len(r_arr) == D_m.shape[0]:
                # True modeled detector emission M^(j)_p = R_p * D_{m, p, j} * w_{m, j}
                contrib = np.sum(D_m[:, :n_cols] * r_arr[:, np.newaxis], axis=0) * w_arr[:n_cols]
            else:
                contrib = np.sum(D_m[:, :n_cols], axis=0) * w_arr[:n_cols]
        else:
            contrib = w_arr
        total_c = float(np.sum(contrib))
        if total_c > 1e-12:
            min_te_frac = sum(float(contrib[idx]) for idx in min_te_indices if idx < len(contrib)) / total_c
            max_te_frac = sum(float(contrib[idx]) for idx in max_te_indices if idx < len(contrib)) / total_c
            if min_te_frac > 0.05 or max_te_frac > 0.05:
                flags.append(f"TE_GRID_EDGE:{mat_name}")

    # 2. RESPONSE_RANGE_SUSPECT: max r / min r > 30
    min_r = float(np.min(response_curve))
    max_r = float(np.max(response_curve))
    if min_r > 0 and (max_r / min_r > 30.0):
        flags.append("RESPONSE_RANGE_SUSPECT")

    # 3. RESPONSE_GAP
    if gap_flag:
        flags.append("RESPONSE_GAP")

    # 4. RESPONSE_RESTART_SPREAD: > 0.10
    if response_restart_spread > 0.10:
        flags.append("RESPONSE_RESTART_SPREAD")

    # 5. RESPONSE_FAMILY_INCONSISTENT: > 0.25
    if family_group_response_rms_diff > 0.25:
        flags.append("RESPONSE_FAMILY_INCONSISTENT")

    # 6. SOURCE_RESOLUTION_MISMATCH: > 10%
    if abs(source_resolving_power_diff) > 0.10:
        flags.append("SOURCE_RESOLUTION_MISMATCH")

    # 7. COMPOSITION_SCALE_UNVERIFIED
    if not composition_scale_verified:
        flags.append("COMPOSITION_SCALE_UNVERIFIED")

    # 8. ZONE_REDUCTION_PENALTY: > 1.10
    if zone_reduction_penalty > 1.10:
        flags.append("ZONE_REDUCTION_PENALTY")

    # 9. K_ABSORPTION_AT_BOUND: k = 8
    if abs(k_absorption_selected - 8.0) < 1e-3:
        flags.append("K_ABSORPTION_AT_BOUND")

    # 10. ELEMENT_BIAS: |bias| > 0.30
    if element_biases:
        for el, bias in element_biases.items():
            if abs(bias) > 0.30:
                flags.append(f"ELEMENT_BIAS:{el}")

    # 11. CHI2_HIGH: reduced chi2 > 5.0
    for mat_name, chi2 in reduced_chi2_by_mat.items():
        if chi2 > 5.0:
            flags.append(f"CHI2_HIGH:{mat_name}")

    # 12. NOT_CONVERGED
    if iterations >= 100:
        flags.append("NOT_CONVERGED")

    return {
        "flags": flags,
        "iterations": iterations,
        "responseRestartSpread": round(float(response_restart_spread), 4),
        "familyGroupResponseRmsDiff": round(float(family_group_response_rms_diff), 4),
    }


def run_joint_solver(
    rois: List[RoiWindow],
    dictionary: Dict[str, np.ndarray],
    conditioned: Dict[str, ConditionedMaterial],
    detector_wavelengths: np.ndarray,
    material_families: Dict[str, str],
    te_grid: List[float],
    ne_grid: List[float],
    mu_r: float = 1.0,
    model_error_fraction: float = 0.10,
    max_iter: int = 100,
    tol_log_r: float = 1e-4,
    tol_phi: float = 1e-6,
    composition_scale_verified: bool = False,
    block_a_ridge: float = 0.02,
) -> CalibrationResult:
    """Run the 3-start alternating joint calibration solver (R7)."""
    det_wl = np.asarray(detector_wavelengths, dtype=np.float64)

    # 1. Collect response set sub-bins
    response_sub_bins: List[Dict[str, Any]] = []
    for r in rois:
        if r.used_in_response_fit:
            for sb_chans in r.sub_bins:
                sb_wls = det_wl[sb_chans]
                response_sub_bins.append({
                    "roi_id": r.roi_id,
                    "channels": sb_chans,
                    "model_lines": r.model_lines,
                    "center_wl": float(np.mean(sb_wls)),
                })

    if not response_sub_bins:
        raise ValueError("No response sub-bins available for joint calibration")

    # Determine response span and knot reduction
    sb_centers = np.array([sb["center_wl"] for sb in response_sub_bins])
    all_resp_channels = [ch for sb in response_sub_bins for ch in sb["channels"]]
    span = (float(det_wl[min(all_resp_channels)]), float(det_wl[max(all_resp_channels)]))

    k_coeffs, gap_flag = reduce_knots_against_gap(sb_centers, span, initial_k=5)
    basis = CubicBSplineBasis(k_coeffs=k_coeffs, span=span)

    # Precompute sub-bin statistics per material
    mat_names = list(conditioned.keys())
    # Count calibrated materials per family for 1 / n_f weighting
    fam_mat_counts: Dict[str, int] = {}
    for m in mat_names:
        f = material_families.get(m, "DefaultFamily")
        fam_mat_counts[f] = fam_mat_counts.get(f, 0) + 1

    mat_sub_data: Dict[str, List[Dict[str, Any]]] = {}
    for m in mat_names:
        cond = conditioned[m]
        # Calculate dark std from unlit channels
        if cond.lit_mask is not None and not np.all(cond.lit_mask):
            unlit_sig = cond.sigma_mean[~cond.lit_mask]
            dark_std = float(np.median(unlit_sig)) if len(unlit_sig) > 0 else 30.0
        else:
            dark_std = 30.0

        # Use baseline-subtracted preprocessed_mean consistently across all calibration stages (TRACK-CORE-02A)
        y_spec = (
            cond.preprocessed_mean
            if getattr(cond, "preprocessed_mean", None) is not None
            else cond.mean_spectrum
        )
        sb_list = []
        for sb in response_sub_bins:
            chans = sb["channels"]
            var_info = compute_sub_bin_variances(
                channels=chans,
                mean_spectrum=y_spec,
                sigma_mean=cond.sigma_mean,
                model_lines=sb["model_lines"],
                model_error_fraction=model_error_fraction,
                dark_std=dark_std,
            )
            # Flux-weighted center wavelength on baseline-subtracted spectrum
            y_meas = y_spec[chans]
            sum_y = np.sum(y_meas)
            if sum_y > 0:
                flux_center = float(np.sum(det_wl[chans] * y_meas) / sum_y)
            else:
                flux_center = sb["center_wl"]

            var_info["channels"] = chans
            var_info["center_wl"] = flux_center
            var_info["meas_sigma_sub"] = float(np.sqrt(var_info["meas_var"]))
            sb_list.append(var_info)
        mat_sub_data[m] = sb_list

    # Huber loss parameter c = 1.345
    c_huber = 1.345

    def run_single_start(initial_beta: np.ndarray) -> Tuple[np.ndarray, Dict[str, np.ndarray], float, int, bool, List[float], List[np.ndarray]]:
        """Execute alternating loop for one starting point."""
        beta = initial_beta.copy()
        prev_log_r = basis.evaluate(sb_centers) @ beta
        prev_phi = 1e18
        converged = False
        iteration = 0
        hist_phi: List[float] = []
        hist_beta: List[np.ndarray] = []

        current_weights: Dict[str, np.ndarray] = {}

        for iteration in range(1, max_iter + 1):
            # Compute current response on detector
            r_det = np.exp(basis.evaluate(det_wl) @ beta)

            # Block A: solve plasma weights for each material
            for m in mat_names:
                D_m = dictionary[m]
                sb_list = mat_sub_data[m]
                n_bins = len(sb_list)
                n_states = D_m.shape[1]

                A_m = np.zeros((n_bins, n_states), dtype=np.float64)
                Y_m = np.zeros(n_bins, dtype=np.float64)
                sigma_m = np.zeros(n_bins, dtype=np.float64)
                meas_sig_m = np.zeros(n_bins, dtype=np.float64)

                for b_idx, sb_info in enumerate(sb_list):
                    chans = sb_info["channels"]
                    # A_m[b, j] = sum_{p in b} r_p * D_m[p, j]
                    A_m[b_idx, :] = np.sum(r_det[chans, np.newaxis] * D_m[chans, :], axis=0)
                    Y_m[b_idx] = sb_info["Y"]
                    sigma_m[b_idx] = sb_info["sigma"]
                    meas_sig_m[b_idx] = sb_info["meas_sigma_sub"]

                active_rows = (Y_m > 3.0 * meas_sig_m) & (np.max(A_m, axis=1) > 1e-12)
                if int(np.sum(active_rows)) >= 2:
                    w_m = solve_block_a(
                        A_m[active_rows],
                        Y_m[active_rows],
                        sigma_m[active_rows],
                        huber_c=c_huber,
                        l2_reg=block_a_ridge,
                    )
                else:
                    w_m = solve_block_a(
                        A_m,
                        Y_m,
                        sigma_m,
                        huber_c=c_huber,
                        l2_reg=block_a_ridge,
                    )
                current_weights[m] = w_m

            # Block B: assemble log-ratios and weights across all materials
            b_centers_list: List[float] = []
            rho_list: List[float] = []
            weights_list: List[float] = []

            total_huber_loss = 0.0

            for m in mat_names:
                D_m = dictionary[m]
                w_m = current_weights[m]
                sb_list = mat_sub_data[m]
                n_f = fam_mat_counts.get(material_families.get(m, "DefaultFamily"), 1)

                for sb_info in sb_list:
                    chans = sb_info["channels"]
                    Y_mb = sb_info["Y"]
                    sig_mb = sb_info["sigma"]
                    sig_meas = sb_info["meas_sigma_sub"]

                    # a_{m, b} = sum_{p in b} (D_m w_m)_p
                    a_mb = float(np.sum((D_m[chans, :] @ w_m)))
                    r_at_center = float(np.exp(basis.evaluate(np.array([sb_info["center_wl"]])) @ beta)[0])
                    M_mb = r_at_center * a_mb

                    # Residual and Huber loss
                    u_mb = (Y_mb - M_mb) / sig_mb
                    abs_u = abs(u_mb)
                    if abs_u <= c_huber:
                        rho_c = 0.5 * u_mb ** 2
                        h_weight = 1.0
                    else:
                        rho_c = c_huber * abs_u - 0.5 * c_huber ** 2
                        h_weight = c_huber / abs_u

                    total_huber_loss += rho_c

                    # Condition for Block B inclusion: Y > 3 * sigma_meas, a > 0, and
                    # unmodeled-line ratio gate (M > 0.05 * Y) enforced for iteration >= 2
                    gate_ok = True if iteration < 2 else (M_mb > 0.05 * Y_mb)
                    if Y_mb > 3.0 * sig_meas and a_mb > 1e-12 and gate_ok:
                        rho_val = np.log(Y_mb / a_mb)
                        # Chain-rule Fisher weight uses min(Y, M) so missing-line outliers cannot explode (Y/sigma)^2
                        eff_signal = min(Y_mb, M_mb)
                        v_val = (1.0 / n_f) * ((eff_signal / sig_mb) ** 2) * h_weight

                        b_centers_list.append(sb_info["center_wl"])
                        rho_list.append(rho_val)
                        weights_list.append(v_val)

            # Regularization penalty
            D2 = basis.second_difference_matrix()
            v_bar = float(np.mean(weights_list)) if weights_list else 1.0
            phi_reg = mu_r * v_bar * float(np.sum((D2 @ beta) ** 2))
            phi_total = total_huber_loss + phi_reg

            hist_phi.append(float(phi_total))
            hist_beta.append(beta.copy())

            if not b_centers_list:
                break

            # Solve Block B
            beta = solve_block_b(
                basis=basis,
                sub_bin_centers=np.array(b_centers_list),
                rho=np.array(rho_list),
                weights=np.array(weights_list),
                mu_r=mu_r,
            )

            curr_log_r = basis.evaluate(sb_centers) @ beta
            max_delta_log_r = float(np.max(np.abs(curr_log_r - prev_log_r)))
            rel_delta_phi = abs(phi_total - prev_phi) / max(prev_phi, 1e-9)

            if max_delta_log_r < tol_log_r and rel_delta_phi < tol_phi and iteration >= 2:
                converged = True
                break

            prev_log_r = curr_log_r
            prev_phi = phi_total

        return beta, current_weights, phi_total, iteration, converged, hist_phi, hist_beta

    # 3 Starts:
    # 1. Flat (beta = 0)
    beta_flat = np.zeros(k_coeffs, dtype=np.float64)

    # 2. Linear tilt +0.5 across span
    mid_span = 0.5 * (span[0] + span[1])
    span_w = span[1] - span[0]
    tilt_plus_target = 0.5 * (sb_centers - mid_span) / (span_w * 0.5)
    B_centers = basis.evaluate(sb_centers)
    beta_plus = np.linalg.lstsq(B_centers, tilt_plus_target, rcond=None)[0]

    # 3. Linear tilt -0.5 across span
    tilt_minus_target = -0.5 * (sb_centers - mid_span) / (span_w * 0.5)
    beta_minus = np.linalg.lstsq(B_centers, tilt_minus_target, rcond=None)[0]

    starts = [beta_flat, beta_plus, beta_minus]
    start_results = []
    start_log_rs = []

    for b_init in starts:
        b_res, w_res, phi_res, iters, conv, h_phi, h_beta = run_single_start(b_init)
        start_results.append((b_res, w_res, phi_res, iters, conv, h_phi, h_beta))
        start_log_rs.append(basis.evaluate(sb_centers) @ b_res)

    # Measure responseRestartSpread
    spreads = []
    for i in range(len(start_log_rs)):
        for j in range(i + 1, len(start_log_rs)):
            spreads.append(np.max(np.abs(start_log_rs[i] - start_log_rs[j])))
    response_restart_spread = float(max(spreads)) if spreads else 0.0

    # Pick lowest Phi
    start_results.sort(key=lambda item: item[2])
    best_beta, best_weights, best_phi, best_iters, best_conv, best_h_phi, best_h_beta = start_results[0]

    # Final response on detector wavelengths
    final_response = np.exp(basis.evaluate(det_wl) @ best_beta)

    # Reduced Chi-squared per material
    reduced_chi2: Dict[str, float] = {}
    for m in mat_names:
        D_m = dictionary[m]
        w_m = best_weights[m]
        sb_list = mat_sub_data[m]
        chi2_sum = 0.0
        for sb_info in sb_list:
            chans = sb_info["channels"]
            Y_mb = sb_info["Y"]
            sig_mb = sb_info["sigma"]
            r_at_center = float(np.exp(basis.evaluate(np.array([sb_info["center_wl"]])) @ best_beta)[0])
            a_mb = float(np.sum(D_m[chans, :] @ w_m))
            chi2_sum += ((Y_mb - r_at_center * a_mb) / sig_mb) ** 2
        dof = max(1, len(sb_list) - 2)
        reduced_chi2[m] = float(chi2_sum / dof)

    # Group comparison (iron-based vs others)
    iron_mats = [m for m in mat_names if "Fe" in m or "Steel" in material_families.get(m, "") or material_families.get(m, "").startswith("Ferritic") or material_families.get(m, "").startswith("Stainless")]
    other_mats = [m for m in mat_names if m not in iron_mats]

    family_group_rms_diff = 0.0
    if len(iron_mats) > 0 and len(other_mats) > 0:
        def _collect_group_block_b(group_mats: List[str]) -> Tuple[List[float], List[float], List[float]]:
            grp_centers: List[float] = []
            grp_rho: List[float] = []
            grp_w: List[float] = []
            for gm in group_mats:
                D_gm = dictionary[gm]
                w_gm = best_weights[gm]
                n_f = fam_mat_counts.get(material_families.get(gm, "DefaultFamily"), 1)
                for sb_info in mat_sub_data[gm]:
                    chans = sb_info["channels"]
                    Y_mb = sb_info["Y"]
                    sig_mb = sb_info["sigma"]
                    sig_meas = sb_info["meas_sigma_sub"]
                    a_mb = float(np.sum(D_gm[chans, :] @ w_gm))
                    if Y_mb > 3.0 * sig_meas and a_mb > 1e-12:
                        r_curr = float(
                            np.exp(basis.evaluate(np.array([sb_info["center_wl"]])) @ best_beta)[0]
                        )
                        M_mb = r_curr * a_mb
                        if M_mb <= 0.05 * Y_mb:
                            continue
                        abs_u = abs((Y_mb - M_mb) / sig_mb)
                        h_w = 1.0 if abs_u <= c_huber else (c_huber / abs_u)
                        eff_signal = min(Y_mb, M_mb)
                        grp_centers.append(sb_info["center_wl"])
                        grp_rho.append(float(np.log(Y_mb / a_mb)))
                        grp_w.append((1.0 / n_f) * ((eff_signal / sig_mb) ** 2) * h_w)
            return grp_centers, grp_rho, grp_w

        def _weight_support_bounds(centers: List[float], weights: List[float]) -> Tuple[float, float]:
            c_arr = np.asarray(centers, dtype=np.float64)
            w_arr = np.asarray(weights, dtype=np.float64)
            order = np.argsort(c_arr)
            c_s = c_arr[order]
            w_s = w_arr[order]
            w_sum = float(np.sum(w_s))
            if w_sum <= 1e-12:
                return float(c_s[0]), float(c_s[-1])
            cdf = np.cumsum(w_s) / w_sum
            lo_idx = int(np.searchsorted(cdf, 0.02))
            hi_idx = min(len(c_s) - 1, int(np.searchsorted(cdf, 0.98)))
            return float(c_s[lo_idx]), float(c_s[hi_idx])

        iron_c, iron_rho, iron_w = _collect_group_block_b(iron_mats)
        other_c, other_rho, other_w = _collect_group_block_b(other_mats)
        if len(iron_c) >= 2 and len(other_c) >= 2:
            iron_lo, iron_hi = _weight_support_bounds(iron_c, iron_w)
            other_lo, other_hi = _weight_support_bounds(other_c, other_w)
            lo_common = max(iron_lo, other_lo)
            hi_common = min(iron_hi, other_hi)
            if hi_common - lo_common >= 60.0:
                grid_common = np.linspace(lo_common, hi_common, 100)
                beta_iron = solve_block_b(
                    basis=basis,
                    sub_bin_centers=np.array(iron_c, dtype=np.float64),
                    rho=np.array(iron_rho, dtype=np.float64),
                    weights=np.array(iron_w, dtype=np.float64),
                    mu_r=mu_r,
                )
                beta_other = solve_block_b(
                    basis=basis,
                    sub_bin_centers=np.array(other_c, dtype=np.float64),
                    rho=np.array(other_rho, dtype=np.float64),
                    weights=np.array(other_w, dtype=np.float64),
                    mu_r=mu_r,
                )
                log_r_iron = basis.evaluate(grid_common) @ beta_iron
                log_r_iron = log_r_iron - float(np.mean(log_r_iron))
                log_r_other = basis.evaluate(grid_common) @ beta_other
                log_r_other = log_r_other - float(np.mean(log_r_other))
                # RMS difference in log-response space (geometric-mean normalized, consistent with response_restart_spread)
                family_group_rms_diff = float(np.sqrt(np.mean((log_r_iron - log_r_other) ** 2)))

    # Evaluate R9 diagnostics
    diag = evaluate_diagnostics(
        weights_by_mat=best_weights,
        te_grid=te_grid,
        ne_grid=ne_grid,
        response_curve=final_response,
        response_restart_spread=response_restart_spread,
        iterations=best_iters,
        gap_flag=gap_flag,
        reduced_chi2_by_mat=reduced_chi2,
        family_group_response_rms_diff=family_group_rms_diff,
        composition_scale_verified=composition_scale_verified,
        dictionary=dictionary,
    )

    return CalibrationResult(
        spectral_response=final_response,
        response_beta=best_beta,
        plasma_weights=best_weights,
        converged=best_conv,
        iterations=best_iters,
        objective_value=best_phi,
        response_restart_spread=response_restart_spread,
        family_group_response_rms_diff=family_group_rms_diff,
        diagnostics_flags=diag["flags"],
        reduced_chi2=reduced_chi2,
        history_phi=best_h_phi,
        history_beta=best_h_beta,
    )


def solve_family_plasma(
    mat_sub_data: Any,
    n_states: Any = None,
    max_sweeps: int = 5,
    tol: float = 1e-4,
    sigma_dict: Optional[Dict[str, np.ndarray]] = None,
) -> Tuple[np.ndarray, Dict[str, float]]:
    """R8.1: Solve family-shared plasma weights w_f >= 0 and per-material scales alpha_m > 0.
    
    Excludes prone sub-bins from fitting. Alternates between:
    1. Stacked NNLS across materials for shared w_f >= 0
    2. Closed-form weighted least squares for alpha_m > 0
    """
    if mat_sub_data and isinstance(next(iter(mat_sub_data.values())), np.ndarray):
        A_dict = mat_sub_data
        y_dict = n_states
        first_A = next(iter(A_dict.values()))
        actual_n_states = first_A.shape[1] if hasattr(first_A, "shape") and len(first_A.shape) > 1 else len(first_A)
        actual_sub_data = {
            m: [
                {
                    "A_row": A_dict[m][b],
                    "Y": float(y_dict[m][b]),
                    "sigma": float(sigma_dict[m][b]) if (sigma_dict is not None and m in sigma_dict) else 1.0,
                    "is_prone": False,
                }
                for b in range(len(y_dict[m]))
            ]
            for m in A_dict.keys()
        }
        return solve_family_plasma(actual_sub_data, n_states=actual_n_states, max_sweeps=max_sweeps, tol=tol)

    mat_names = sorted(mat_sub_data.keys())
    # Exclude prone sub-bins
    filtered_data = {
        m: [sb for sb in mat_sub_data[m] if not sb.get("is_prone", False)]
        for m in mat_names
    }

    # Initialize per-material scales
    scales = {m: 1.0 for m in mat_names}
    w_fam = np.zeros(n_states, dtype=np.float64)

    for sweep in range(max_sweeps):
        prev_scales = scales.copy()

        # Step 1: Stacked NNLS for w_f
        row_list = []
        target_list = []
        for m in mat_names:
            alpha_m = scales[m]
            for sb in filtered_data[m]:
                A_row = np.asarray(sb["A_row"], dtype=np.float64)
                Y_val = float(sb["Y"])
                sig_val = max(float(sb.get("sigma", 1.0)), 1e-6)
                row_list.append((alpha_m * A_row) / sig_val)
                target_list.append(Y_val / sig_val)

        if not row_list:
            break

        X_mat = np.array(row_list, dtype=np.float64)
        y_vec = np.array(target_list, dtype=np.float64)

        col_scales = np.max(np.abs(X_mat), axis=0)
        col_scales[col_scales == 0] = 1.0
        X_scaled = X_mat / col_scales

        w_scaled, _ = nnls(X_scaled, y_vec)
        w_fam = w_scaled / col_scales

        # Step 2: Closed-form WLS for alpha_m
        for m in mat_names:
            num = 0.0
            den = 0.0
            for sb in filtered_data[m]:
                A_row = np.asarray(sb["A_row"], dtype=np.float64)
                Y_val = float(sb["Y"])
                sig_val = max(float(sb.get("sigma", 1.0)), 1e-6)
                pred_val = float(np.dot(A_row, w_fam))
                num += (Y_val * pred_val) / (sig_val ** 2)
                den += (pred_val ** 2) / (sig_val ** 2)
            if den > 1e-12:
                scales[m] = max(1e-12, num / den)

        # Check convergence
        rel_diffs = [abs(scales[m] - prev_scales[m]) / max(prev_scales[m], 1e-12) for m in mat_names]
        if max(rel_diffs) < tol and sweep >= 1:
            break

    return w_fam, scales


class DispersionResult(dict):
    """Dictionary that also unpacks as (teStdDev, neLogStdDev)."""
    def __iter__(self):
        yield self["teStdDev"]
        yield self["neLogStdDev"]


class FamilyScalesResult(dict):
    """Dictionary that also unpacks as (scaleFactor, materialScaleFactors, familySharingPenalty)."""
    def __iter__(self):
        yield self["scaleFactor"]
        yield self["materialScaleFactors"]
        yield self["familySharingPenalty"]


def reduce_zones_to_num_zones(
    initial_weights: Any,
    sub_data: Any = None,
    *args: Any,
    num_zones: int = 2,
    c_huber: float = 1.345,
    **kwargs: Any,
) -> Tuple[np.ndarray, float]:
    """R8.2: Reduce plasma state support to numZones and compute zoneReductionPenalty."""
    if isinstance(initial_weights, dict):
        A_dict = initial_weights
        y_dict = sub_data
        sigma_dict = kwargs.get("sigma_dict")
        w_init = args[0] if len(args) > 0 else kwargs.get("w_family", np.zeros(next(iter(A_dict.values())).shape[1]))
        if "num_zones" in kwargs:
            num_zones = kwargs["num_zones"]
        elif len(args) > 1 and isinstance(args[1], int):
            num_zones = args[1]
        flat_sub_data = []
        for m in sorted(A_dict.keys()):
            for b in range(len(y_dict[m])):
                sig_val = float(sigma_dict[m][b]) if (sigma_dict is not None and m in sigma_dict) else 1.0
                flat_sub_data.append({
                    "A_row": A_dict[m][b],
                    "Y": float(y_dict[m][b]),
                    "sigma": sig_val,
                    "is_prone": False,
                })
        return reduce_zones_to_num_zones(w_init, flat_sub_data, num_zones=num_zones, c_huber=c_huber)

    def eval_phi(w: np.ndarray) -> float:
        phi = 0.0
        for sb in sub_data:
            if sb.get("is_prone", False):
                continue
            A_row = np.asarray(sb["A_row"], dtype=np.float64)
            Y = float(sb["Y"])
            sig = max(float(sb.get("sigma", 1.0)), 1e-6)
            M = float(np.dot(A_row, w))
            u = abs((Y - M) / sig)
            if u <= c_huber:
                phi += 0.5 * u ** 2
            else:
                phi += c_huber * u - 0.5 * c_huber ** 2
        return phi

    phi_full = max(eval_phi(initial_weights), 1e-12)

    active_indices = [int(j) for j in np.where(initial_weights > 1e-6)[0]]
    current_w = initial_weights.copy()

    # Pre-extract A and Y for non-prone sub-bins
    valid_sbs = [sb for sb in sub_data if not sb.get("is_prone", False)]
    if not valid_sbs:
        return current_w, 1.0

    A_all = np.array([sb["A_row"] for sb in valid_sbs], dtype=np.float64)
    Y_all = np.array([sb["Y"] for sb in valid_sbs], dtype=np.float64)
    sig_all = np.array([max(float(sb.get("sigma", 1.0)), 1e-6) for sb in valid_sbs], dtype=np.float64)

    A_weighted = A_all / sig_all[:, np.newaxis]
    y_weighted = Y_all / sig_all

    while len(active_indices) > num_zones:
        # Calculate contribution for each active state
        contributions = {}
        for j in active_indices:
            contributions[j] = float(np.sum(A_all[:, j] * current_w[j]))
        
        # State with smallest contribution
        min_j = min(contributions.keys(), key=lambda j: contributions[j])
        active_indices.remove(min_j)

        # Re-solve NNLS on reduced support
        A_sub = A_weighted[:, active_indices]
        col_scale = np.max(np.abs(A_sub), axis=0)
        col_scale[col_scale == 0] = 1.0
        w_sub_scaled, _ = nnls(A_sub / col_scale, y_weighted)
        w_sub = w_sub_scaled / col_scale

        current_w = np.zeros_like(initial_weights)
        for idx, k in enumerate(active_indices):
            current_w[k] = w_sub[idx]

    phi_reduced = eval_phi(current_w)
    penalty = max(1.0, float(phi_reduced / phi_full))
    return current_w, penalty


def scan_absorption(
    sub_data: Any = None,
    k_grid: np.ndarray = np.linspace(0.0, 8.0, 81),
    c_huber: float = 1.345,
    return_weights: bool = False,
    **kwargs: Any,
) -> Tuple[Any, ...]:
    """R8.3: Scan self-absorption k in [0, 8] for the coolest zone across prone sub-bins.

    Supports three invocation modes:
    1. Channel-exact RTE mode (preferred): pass ``dictionary``, ``sub_bins``, ``spectral_response``,
       ``y_dict``, ``reduced_weights``, and ``coolest_state_idx`` (optional ``sigma_dict``, ``prone_mask``).
       Evaluates channel-wise ``exp(-k * d_tilde_cool_p)`` before multiplying by ``spectral_response``
       and summing into sub-bins, matching ``apply_multi_zone_rte`` 1:1.
    2. Legacy ``A_dict`` sub-bin dict mode.
    3. Standalone ``sub_data`` list mode.

    Skips any trial ``k > 0`` where NNLS collapses ``V_hot <= 0``, ``V_cool <= 0``, or any active
    zone volume fraction below ``1e-3`` (preventing unphysical zero-volume absorbing ghost zones).
    Keeps ``k = 0`` if the improvement over ``k = 0`` is under 2%.

    When ``return_weights=True``, returns a 3-tuple ``(best_k, flags_or_bool, updated_weights)``;
    otherwise preserves backward-compatible 2-tuple ``(best_k, flags_or_bool)``.
    """
    min_zone_vf = 1e-3

    def _apply_v_to_weights(base_w: np.ndarray, v_vec: np.ndarray, cool_idx: int) -> np.ndarray:
        out = np.asarray(base_w, dtype=np.float64).copy()
        v_hot = float(v_vec[0])
        v_cool = float(v_vec[1])
        for j in range(len(out)):
            if j == cool_idx:
                out[j] = out[j] * v_cool
            elif out[j] > 0.0:
                out[j] = out[j] * v_hot
        return out

    def _is_valid_absorbing_zones(
        v_vec: np.ndarray,
        base_w: Optional[np.ndarray] = None,
        cool_idx: Optional[int] = None,
    ) -> bool:
        if float(v_vec[0]) <= 0.0 or float(v_vec[1]) <= 0.0:
            return False
        if base_w is not None and cool_idx is not None:
            cand_w = _apply_v_to_weights(base_w, v_vec, cool_idx)
            w_tot = float(np.sum(cand_w))
            if w_tot <= 1e-12:
                return False
            cool_vf = float(cand_w[cool_idx]) / w_tot
            hot_vf = float(w_tot - cand_w[cool_idx]) / w_tot
            if cool_vf < min_zone_vf or hot_vf < min_zone_vf:
                return False
            for j in range(len(base_w)):
                if float(base_w[j]) > 0.0 and (float(cand_w[j]) / w_tot) < min_zone_vf:
                    return False
            return True
        v_sum = float(v_vec[0]) + float(v_vec[1])
        if v_sum <= 1e-12:
            return False
        return (float(v_vec[0]) / v_sum >= min_zone_vf) and (float(v_vec[1]) / v_sum >= min_zone_vf)

    # Mode 1: Channel-exact RTE evaluation directly from full-spectrum dictionary & sub_bins
    if "dictionary" in kwargs and kwargs.get("dictionary") is not None and kwargs.get("sub_bins") is not None:
        dictionary = kwargs["dictionary"]
        sub_bins_arg = kwargs["sub_bins"]
        y_dict = kwargs.get("y_dict", {})
        sigma_dict = kwargs.get("sigma_dict")
        spectral_response = kwargs.get("spectral_response")
        reduced_weights = np.asarray(kwargs["reduced_weights"], dtype=np.float64)
        coolest_idx = int(kwargs.get("coolest_state_idx", 0))
        prone_mask = kwargs.get("prone_mask", [])

        mat_order = sorted(dictionary.keys())
        mat_cache: List[Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, List[np.ndarray]]] = []
        y_list: List[float] = []
        sig_list: List[float] = []
        prone_list: List[bool] = []

        for m in mat_order:
            D_m = np.asarray(dictionary[m], dtype=np.float64)
            n_ch, n_st = D_m.shape
            resp_m = (
                np.asarray(spectral_response, dtype=np.float64)
                if spectral_response is not None
                else np.ones(n_ch, dtype=np.float64)
            )
            cool_raw = D_m[:, coolest_idx]
            cool_max = max(float(np.max(cool_raw)), 1e-12)
            d_tilde_cool_ch = cool_raw / cool_max
            cool_ch = cool_raw * float(reduced_weights[coolest_idx])
            hot_ch = np.zeros(n_ch, dtype=np.float64)
            for j in range(n_st):
                if j != coolest_idx and reduced_weights[j] > 0.0:
                    hot_ch += D_m[:, j] * float(reduced_weights[j])

            sbs_m = sub_bins_arg[m] if isinstance(sub_bins_arg, dict) else sub_bins_arg
            sb_arrays = [np.asarray(sb, dtype=np.int64) for sb in sbs_m]
            mat_cache.append((hot_ch, cool_ch, d_tilde_cool_ch, resp_m, sb_arrays))

            y_m = y_dict[m]
            for b_idx in range(len(sb_arrays)):
                y_list.append(float(y_m[b_idx]))
                sig_val = float(sigma_dict[m][b_idx]) if (sigma_dict is not None and m in sigma_dict) else 1.0
                sig_list.append(max(sig_val, 1e-6))
                prone_list.append(bool(prone_mask[b_idx]) if b_idx < len(prone_mask) else False)

        Y_all = np.asarray(y_list, dtype=np.float64)
        sig_all = np.asarray(sig_list, dtype=np.float64)
        is_prone = np.asarray(prone_list, dtype=bool)
        eval_mask = is_prone if np.any(is_prone) else np.ones(len(Y_all), dtype=bool)

        if len(Y_all) == 0:
            if return_weights:
                return 0.0, False, reduced_weights.copy()
            return 0.0, False

        # Precompute static cool sub-bin column once (independent of k)
        cool_sb_list: List[float] = []
        for _, cool_ch, _, resp_m, sb_arrays in mat_cache:
            rc = resp_m * cool_ch
            for sb_idx in sb_arrays:
                cool_sb_list.append(float(np.sum(rc[sb_idx])))
        cool_sb_all = np.asarray(cool_sb_list, dtype=np.float64)
        cool_col_w = cool_sb_all / sig_all
        y_w = Y_all / sig_all

        def _eval_k_exact(k_val: float) -> Tuple[float, np.ndarray]:
            hot_sb_list: List[float] = []
            kf = float(k_val)
            for hot_ch, _, d_tilde_cool_ch, resp_m, sb_arrays in mat_cache:
                att_hot = resp_m * hot_ch * np.exp(-kf * d_tilde_cool_ch)
                for sb_idx in sb_arrays:
                    hot_sb_list.append(float(np.sum(att_hot[sb_idx])))
            hot_sb_all = np.asarray(hot_sb_list, dtype=np.float64)
            X = np.column_stack([hot_sb_all / sig_all, cool_col_w])
            v_sol, _ = nnls(X, y_w)
            pred = hot_sb_all * v_sol[0] + cool_sb_all * v_sol[1]
            u = np.abs((Y_all - pred) / sig_all)
            u_eval = u[eval_mask]
            phi = float(
                np.sum(np.where(u_eval <= c_huber, 0.5 * u_eval ** 2, c_huber * u_eval - 0.5 * c_huber ** 2))
            )
            return phi, v_sol

        phi_zero, v_zero = _eval_k_exact(0.0)
        best_k = 0.0
        min_phi = phi_zero
        best_v = v_zero

        for k in k_grid:
            kf = float(k)
            if kf <= 0.0:
                continue
            phi_k, v_k = _eval_k_exact(kf)
            if not _is_valid_absorbing_zones(v_k, reduced_weights, coolest_idx):
                continue
            if phi_k < min_phi:
                min_phi = phi_k
                best_k = kf
                best_v = v_k

        if best_k <= 0.0 or (phi_zero - min_phi) < 0.02 * phi_zero:
            best_k = 0.0
            updated_weights = reduced_weights.copy()
        else:
            updated_weights = _apply_v_to_weights(reduced_weights, best_v, coolest_idx)

        k_flag = bool(abs(best_k - 8.0) < 1e-2 or best_k >= 7.95)
        if return_weights:
            return best_k, k_flag, updated_weights
        return best_k, k_flag

    # Mode 2: Legacy A_dict sub-bin wrapper
    if sub_data is None or isinstance(sub_data, dict) or "A_dict" in kwargs:
        A_dict = sub_data if isinstance(sub_data, dict) else kwargs.get("A_dict", {})
        y_dict = kwargs.get("y_dict", {})
        sigma_dict = kwargs.get("sigma_dict")
        cool_zone_max_dict = kwargs.get("cool_zone_max_dict")
        d_tilde_cool_dict = kwargs.get("d_tilde_cool_dict")
        reduced_weights = np.asarray(kwargs.get("reduced_weights", []), dtype=np.float64)
        coolest_idx = int(kwargs.get("coolest_state_idx", 0))
        prone_mask = kwargs.get("prone_mask", [])

        flat_sub_data = []
        for m in sorted(A_dict.keys()):
            A_m = A_dict[m]
            y_m = y_dict[m]
            for b in range(len(y_m)):
                d_c = float(A_m[b, coolest_idx] * reduced_weights[coolest_idx])
                d_h = float(sum(A_m[b, j] * reduced_weights[j] for j in range(len(reduced_weights)) if j != coolest_idx))
                is_p = bool(prone_mask[b]) if b < len(prone_mask) else False
                sig_val = float(sigma_dict[m][b]) if (sigma_dict is not None and m in sigma_dict) else 1.0
                entry: Dict[str, Any] = {
                    "d_cool": d_c,
                    "d_hot": d_h,
                    "Y": float(y_m[b]),
                    "sigma": sig_val,
                    "is_prone": is_p,
                }
                if d_tilde_cool_dict is not None and m in d_tilde_cool_dict:
                    entry["d_tilde_cool"] = float(d_tilde_cool_dict[m][b])
                elif cool_zone_max_dict is not None and m in cool_zone_max_dict:
                    entry["cool_zone_max"] = float(cool_zone_max_dict[m])
                flat_sub_data.append(entry)
        best_k, flags, best_v = scan_absorption(
            flat_sub_data,
            k_grid=k_grid,
            c_huber=c_huber,
            return_weights=True,
            _base_weights=reduced_weights if len(reduced_weights) > 0 else None,
            _coolest_idx=coolest_idx,
        )
        k_flag = bool("K_ABSORPTION_AT_BOUND" in flags or any("BOUND" in f for f in flags))
        if return_weights:
            if best_k > 0.0 and len(reduced_weights) > 0:
                updated_w = _apply_v_to_weights(reduced_weights, best_v, coolest_idx)
            else:
                updated_w = reduced_weights.copy()
            return best_k, k_flag, updated_w
        return best_k, k_flag

    # Mode 3: Standalone sub_data list
    base_weights_opt = kwargs.get("_base_weights")
    coolest_idx_opt = kwargs.get("_coolest_idx")

    d_cool = np.array([float(sb["d_cool"]) for sb in sub_data], dtype=np.float64)
    d_hot = np.array([float(sb["d_hot"]) for sb in sub_data], dtype=np.float64)
    Y_all = np.array([float(sb["Y"]) for sb in sub_data], dtype=np.float64)
    sig_all = np.array([max(float(sb.get("sigma", 1.0)), 1e-6) for sb in sub_data], dtype=np.float64)
    is_prone = np.array([bool(sb.get("is_prone", False)) for sb in sub_data], dtype=bool)

    if all("d_tilde_cool" in sb for sb in sub_data):
        d_tilde_cool = np.array([float(sb["d_tilde_cool"]) for sb in sub_data], dtype=np.float64)
    elif all("cool_zone_max" in sb for sb in sub_data):
        cool_max_arr = np.array([max(float(sb["cool_zone_max"]), 1e-12) for sb in sub_data], dtype=np.float64)
        d_tilde_cool = d_cool / cool_max_arr
    else:
        max_cool = max(float(np.max(d_cool)), 1e-12)
        d_tilde_cool = d_cool / max_cool

    eval_mask = is_prone if np.any(is_prone) else np.ones(len(sub_data), dtype=bool)

    def eval_k(k_val: float) -> Tuple[float, np.ndarray]:
        # Match apply_multi_zone_rte 1:1: cool shell attenuates hot core emission
        # I = V_hot * (d_hot * exp(-k * d_tilde_cool)) + V_cool * d_cool
        attenuation = np.exp(-float(k_val) * d_tilde_cool)
        hot_col = (d_hot * attenuation) / sig_all
        cool_col = d_cool / sig_all
        X = np.column_stack([hot_col, cool_col])
        y = Y_all / sig_all
        V, _ = nnls(X, y)
        pred = (d_hot * attenuation) * V[0] + d_cool * V[1]
        u = np.abs((Y_all - pred) / sig_all)
        u_eval = u[eval_mask]
        phi = np.sum(np.where(u_eval <= c_huber, 0.5 * u_eval ** 2, c_huber * u_eval - 0.5 * c_huber ** 2))
        return float(phi), V

    phi_zero, v_zero = eval_k(0.0)
    best_k = 0.0
    min_phi = phi_zero
    best_v = v_zero

    for k in k_grid:
        kf = float(k)
        if kf <= 0.0:
            continue
        phi, v_k = eval_k(kf)
        if not _is_valid_absorbing_zones(v_k, base_weights_opt, coolest_idx_opt):
            continue
        if phi < min_phi:
            min_phi = phi
            best_k = kf
            best_v = v_k

    # 2% improvement rule
    if best_k <= 0.0 or (phi_zero - min_phi) < 0.02 * phi_zero:
        best_k = 0.0
        best_v = v_zero

    flags = []
    if abs(best_k - 8.0) < 1e-2 or best_k >= 7.95:
        flags.append("K_ABSORPTION_AT_BOUND")

    if return_weights:
        return best_k, flags, best_v
    return best_k, flags


def compute_family_scales_and_sharing_penalty(
    material_scales: Any,
    phi_family: Any = None,
    phi_materials: Any = None,
    **kwargs: Any,
) -> FamilyScalesResult:
    """R8.5, R8.7: Compute median scaleFactor, per-material scales, and family sharing penalty.

    In dict-mode (``material_scales=A_dict, phi_family=y_dict, phi_materials=reduced_weights``),
    supports optional ``sigma_dict`` (1/sigma^2 inverse-variance weighting) and channel-exact
    multi-zone RTE attenuation when ``k_absorption > 0`` alongside ``dictionary``, ``sub_bins``,
    ``spectral_response``, ``nz_indices``, and ``te_grid``/``ne_grid``.
    """
    if isinstance(material_scales, dict) and len(material_scales) > 0 and isinstance(next(iter(material_scales.values())), np.ndarray):
        A_dict = material_scales
        y_dict = phi_family
        reduced_weights = np.asarray(phi_materials, dtype=np.float64)
        sigma_dict = kwargs.get("sigma_dict")
        k_absorption = float(kwargs.get("k_absorption", 0.0))
        dictionary = kwargs.get("dictionary")
        sub_bins_arg = kwargs.get("sub_bins")
        spectral_response = kwargs.get("spectral_response")
        nz_indices_kw = kwargs.get("nz_indices")
        te_grid = kwargs.get("te_grid")
        ne_grid = kwargs.get("ne_grid")

        use_rte = (
            k_absorption > 0.0
            and dictionary is not None
            and sub_bins_arg is not None
            and np.any(reduced_weights > 0.0)
        )

        nz_sorted: List[int] = []
        v_total = 0.0
        zone_specs_rte: List[PlasmaZoneSpec] = []
        if use_rte:
            if nz_indices_kw is not None and len(nz_indices_kw) > 0:
                nz_raw = [int(i) for i in nz_indices_kw if reduced_weights[int(i)] > 0.0]
            else:
                nz_raw = [int(i) for i in np.where(reduced_weights > 0.0)[0]]
            n_ne = len(ne_grid) if ne_grid is not None and len(ne_grid) > 0 else 1
            if te_grid is not None and len(te_grid) > 0:
                nz_sorted = sorted(nz_raw, key=lambda idx: float(te_grid[idx // n_ne]), reverse=True)
            else:
                nz_sorted = list(nz_raw)
            v_total = float(sum(reduced_weights[idx] for idx in nz_sorted))
            if v_total > 1e-12 and len(nz_sorted) >= 2:
                for z_i, idx in enumerate(nz_sorted):
                    te_v = float(te_grid[idx // n_ne]) if (te_grid is not None and len(te_grid) > 0) else float(len(nz_sorted) - z_i)
                    ne_v = float(ne_grid[idx % n_ne]) if (ne_grid is not None and len(ne_grid) > 0) else 1.0e17
                    vf_v = float(reduced_weights[idx] / v_total)
                    kz_v = k_absorption if z_i == len(nz_sorted) - 1 else 0.0
                    zone_specs_rte.append(
                        PlasmaZoneSpec(te=te_v, ne=ne_v, volume_fraction=vf_v, k_absorption=kz_v)
                    )
            else:
                use_rte = False

        mat_scales: Dict[str, float] = {}
        phi_mats: Dict[str, float] = {}
        preds_by_mat: Dict[str, np.ndarray] = {}
        sigs_by_mat: Dict[str, np.ndarray] = {}

        for m in sorted(A_dict.keys()):
            A_m = A_dict[m]
            y_m = np.asarray(y_dict[m], dtype=np.float64)
            if sigma_dict is not None and m in sigma_dict:
                sig_m = np.maximum(np.asarray(sigma_dict[m], dtype=np.float64), 1e-6)
            else:
                sig_m = np.ones_like(y_m, dtype=np.float64)

            if use_rte and m in dictionary:
                D_m = np.asarray(dictionary[m], dtype=np.float64)
                resp_m = (
                    np.asarray(spectral_response, dtype=np.float64)
                    if spectral_response is not None
                    else np.ones(D_m.shape[0], dtype=np.float64)
                )
                zone_spectra = [D_m[:, idx] for idx in nz_sorted]
                i_rad = apply_multi_zone_rte(zone_spectra, zone_specs_rte)
                y_full_pred = apply_response_and_scale(i_rad, resp_m, v_total, emission_scale="absolute")
                sbs_m = sub_bins_arg[m] if isinstance(sub_bins_arg, dict) else sub_bins_arg
                pred_m = np.array(
                    [float(np.sum(y_full_pred[np.asarray(sb, dtype=np.int64)])) for sb in sbs_m],
                    dtype=np.float64,
                )
            else:
                pred_m = A_m @ reduced_weights

            inv_var = 1.0 / (sig_m ** 2)
            num = float(np.sum(y_m * pred_m * inv_var))
            den = float(np.sum((pred_m ** 2) * inv_var))
            alpha_m = float(num / max(den, 1e-12))
            mat_scales[m] = alpha_m
            u_mat = (y_m - alpha_m * pred_m) / sig_m
            phi_mats[m] = float(0.5 * np.sum(u_mat ** 2))
            preds_by_mat[m] = pred_m
            sigs_by_mat[m] = sig_m

        med_scale = float(np.median(list(mat_scales.values()))) if mat_scales else 1.0
        phi_fam = float(
            0.5
            * sum(
                np.sum(((np.asarray(y_dict[m], dtype=np.float64) - med_scale * preds_by_mat[m]) / sigs_by_mat[m]) ** 2)
                for m in sorted(A_dict.keys())
            )
        )
        return compute_family_scales_and_sharing_penalty(mat_scales, phi_fam, phi_mats)

    scales_list = list(material_scales.values())
    med_scale = float(np.median(scales_list)) if scales_list else 1.0
    sum_phi_mat = max(float(sum(phi_materials.values())), 1e-12)
    sharing_penalty = float(phi_family / sum_phi_mat)

    return FamilyScalesResult({
        "scaleFactor": med_scale,
        "materialScaleFactors": {m: float(s) for m, s in material_scales.items()},
        "familySharingPenalty": sharing_penalty,
    })


def compute_plasma_dispersion(
    point_te: Any = None,
    point_ne: Any = None,
    **kwargs: Any,
) -> DispersionResult:
    """R8.6: Compute plasma dispersion across points clamped to [0.01, 0.10] Te and [0.02, 0.30] log10 Ne."""
    if "point_solutions" in kwargs:
        point_solutions = kwargs["point_solutions"]
        te_grid = np.asarray(kwargs.get("te_grid", []))
        ne_grid = np.asarray(kwargs.get("ne_grid", []))
        n_ne = len(ne_grid) if len(ne_grid) > 0 else 1
        pt_tes = []
        pt_nes = []
        for w_pt in point_solutions:
            tot = float(np.sum(w_pt))
            if tot > 1e-12:
                te_mean = float(sum(w_pt[idx] * te_grid[idx // n_ne] for idx in range(len(w_pt))) / tot)
                ne_mean = float(sum(w_pt[idx] * np.log10(max(ne_grid[idx % n_ne], 1.0)) for idx in range(len(w_pt))) / tot)
                pt_tes.append(te_mean)
                pt_nes.append(ne_mean)
        return compute_plasma_dispersion(pt_tes, pt_nes)

    te_arr = np.asarray(point_te, dtype=np.float64)
    ne_arr = np.asarray(point_ne, dtype=np.float64)

    te_std = float(np.std(te_arr, ddof=1)) if len(te_arr) > 1 else 0.01
    if np.any(ne_arr > 100.0):
        ne_log_arr = np.log10(np.maximum(ne_arr, 1.0))
    else:
        ne_log_arr = ne_arr
    ne_log_std = float(np.std(ne_log_arr, ddof=1)) if len(ne_log_arr) > 1 else 0.02

    te_clamped = min(0.10, max(0.01, te_std))
    ne_log_clamped = min(0.30, max(0.02, ne_log_std))

    return DispersionResult({
        "teStdDev": float(te_clamped),
        "neLogStdDev": float(ne_log_clamped),
    })


def compute_element_parity(
    sub_bins: Any = None,
    **kwargs: Any,
) -> Tuple[Dict[str, float], Any]:
    """R9, R11: Compute per-element median log10(synthetic / measured) and ELEMENT_BIAS flag."""
    if sub_bins is not None and isinstance(sub_bins, list) and len(sub_bins) > 0 and isinstance(sub_bins[0], dict) and "dominant_element" in sub_bins[0]:
        by_elem: Dict[str, List[float]] = {}
        for sb in sub_bins:
            el = sb.get("dominant_element") or sb.get("element", "Unknown")
            synth = float(sb.get("synthetic", 0.0))
            meas = float(sb.get("measured", 0.0))
            if synth > 0 and meas > 0:
                log_ratio = float(np.log10(synth / meas))
                by_elem.setdefault(el, []).append(log_ratio)

        parity: Dict[str, float] = {}
        flags: List[str] = []
        for el, ratios in sorted(by_elem.items()):
            med_ratio = float(np.median(ratios))
            parity[el] = med_ratio
            if abs(med_ratio) > 0.30:
                flags.append(f"ELEMENT_BIAS:{el}")
        return parity, flags

    rois = kwargs.get("rois", [])
    dictionary = kwargs.get("dictionary", {})
    conditioned = kwargs.get("conditioned", {})
    plasma_zones = kwargs.get("plasma_zones", [])
    scale_factor = float(kwargs.get("scale_factor", 1.0))
    spectral_response = kwargs.get("spectral_response")
    nz_indices = kwargs.get("nz_indices")
    te_grid = kwargs.get("te_grid")
    ne_grid = kwargs.get("ne_grid")

    def _resolve_state_col(z: Dict[str, Any], n_cols: int) -> int:
        if "stateIndex" in z and z["stateIndex"] is not None:
            return int(z["stateIndex"])
        z_idx = int(z.get("zoneIndex", 0))
        if nz_indices is not None and 0 <= z_idx < len(nz_indices):
            return int(nz_indices[z_idx])
        if te_grid is not None and ne_grid is not None and len(te_grid) > 0 and len(ne_grid) > 0:
            te_t = float(z.get("Te", 0.0))
            ne_t = float(z.get("Ne", 1.0))
            best_c = 0
            best_d = float("inf")
            n_ne_g = len(ne_grid)
            for c_i in range(min(n_cols, len(te_grid) * n_ne_g)):
                d_val = abs(float(te_grid[c_i // n_ne_g]) - te_t) + abs(
                    np.log10(max(float(ne_grid[c_i % n_ne_g]), 1.0)) - np.log10(max(ne_t, 1.0))
                )
                if d_val < best_d:
                    best_d = d_val
                    best_c = c_i
            return best_c
        raise ValueError(
            "compute_element_parity requires explicit state mapping via zone 'stateIndex', "
            "'nz_indices', or non-empty ('te_grid', 'ne_grid'); refusing silent zoneIndex % n_cols fallback."
        )

    sub_bins_list = []
    for m in dictionary.keys():
        D_m = dictionary[m]
        y_meas = conditioned[m]
        p_chans = D_m.shape[0]
        if plasma_zones:
            zone_spectra = [D_m[:, _resolve_state_col(z, D_m.shape[1])] for z in plasma_zones]
            zone_specs = [PlasmaZoneSpec.from_dict(z) for z in plasma_zones]
            i_rad = apply_multi_zone_rte(zone_spectra, zone_specs)
            resp_arr = (
                np.asarray(spectral_response, dtype=np.float64)
                if spectral_response is not None
                else np.ones(p_chans, dtype=np.float64)
            )
            y_synth = apply_response_and_scale(i_rad, resp_arr, scale_factor, emission_scale="absolute")
        else:
            y_synth = np.zeros(p_chans, dtype=np.float64)

        for r in rois:
            el = getattr(r, "dominant_element", "Unknown")
            for sb in getattr(r, "sub_bins", []):
                sb_idx = np.array(sb, dtype=np.int64)
                s_val = float(np.sum(y_synth[sb_idx]))
                m_val = float(np.sum(y_meas[sb_idx]))
                sub_bins_list.append({"dominant_element": el, "synthetic": s_val, "measured": m_val})

    parity, flags = compute_element_parity(sub_bins_list)
    has_bias = bool(len(flags) > 0)
    return parity, has_bias

