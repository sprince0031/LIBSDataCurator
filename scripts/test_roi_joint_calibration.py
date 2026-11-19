"""Unit tests for ROI joint calibration solver, variance model, and R9 diagnostics (R6, R7, R9, AC8)."""

from __future__ import annotations

import numpy as np
import pytest

from scripts.measured_conditioning import ConditionedMaterial
from scripts.roi_catalogue import RoiWindow, SubBin
from scripts.roi_joint_calibration import (
    CubicBSplineBasis,
    compute_sub_bin_variances,
    solve_block_a,
    solve_block_b,
    reduce_knots_against_gap,
    evaluate_diagnostics,
    run_joint_solver,
    CalibrationResult,
)


def test_cubic_b_spline_basis_properties():
    """B-spline basis evaluates clamped uniform cubic splines and second difference operator."""
    wl_grid = np.linspace(350.0, 850.0, 501)
    k_coeffs = 5
    span = (350.0, 850.0)

    basis = CubicBSplineBasis(k_coeffs=k_coeffs, span=span)
    B = basis.evaluate(wl_grid)

    assert B.shape == (len(wl_grid), k_coeffs)
    # Partition of unity: sum of B-spline basis functions is 1.0 everywhere in the span
    row_sums = np.sum(B, axis=1)
    assert np.allclose(row_sums, 1.0, atol=1e-5)

    # Second difference operator D2
    D2 = basis.second_difference_matrix()
    assert D2.shape == (k_coeffs - 2, k_coeffs)
    # Expected rows of D2: [1, -2, 1, 0, 0], [0, 1, -2, 1, 0], [0, 0, 1, -2, 1]
    expected_row = np.array([1.0, -2.0, 1.0, 0.0, 0.0])
    assert np.allclose(D2[0], expected_row)


def test_sub_bin_variance_model():
    """R6 variance model combines measurement, model error, atomic line uncertainty, and floor terms."""
    # 4 channels
    chans = [10, 11, 12, 13]
    y_meas = np.array([100.0, 150.0, 200.0, 250.0])
    sigma_meas = np.array([5.0, 5.0, 5.0, 5.0])

    model_lines = [
        {"species": "Fe I", "weighted_st": 10.0, "unc": 0.07},  # B+
        {"species": "Fe I", "weighted_st": 30.0, "unc": 0.18},  # C+
    ]

    var_dict = compute_sub_bin_variances(
        channels=chans,
        mean_spectrum=y_meas,
        sigma_mean=sigma_meas,
        model_lines=model_lines,
        model_error_fraction=0.10,
        dark_std=10.0,
    )

    y_sum = np.sum(y_meas[chans])
    assert var_dict["Y"] == y_sum

    # eps_atomic = sqrt((10*0.07^2 + 30*0.18^2)/(10+30)) ~= sqrt((0.049 + 0.972)/40) = sqrt(1.021/40) ~= 0.1598
    eps_atomic = np.sqrt((10.0 * 0.07 ** 2 + 30.0 * 0.18 ** 2) / 40.0)
    eps_b = np.sqrt(0.10 ** 2 + eps_atomic ** 2)
    assert np.isclose(var_dict["eps_b"], eps_b, atol=1e-4)

    # sigma_floor = 3 * sqrt(4) * 10 = 60.0
    sigma_floor = 3.0 * np.sqrt(4) * 10.0
    # sigma_sq = sum(sigma_meas^2) + (eps_b * y_sum)^2 + sigma_floor^2
    expected_sigma_sq = np.sum(sigma_meas[chans] ** 2) + (eps_b * y_sum) ** 2 + sigma_floor ** 2
    assert np.isclose(var_dict["sigma_sq"], expected_sigma_sq, rtol=1e-4)


def test_block_a_nnls_recovers_planted_weights():
    """Block A solves NNLS with column scaling and returns non-negative plasma weights."""
    n_sub_bins = 15
    n_states = 6

    rng = np.random.default_rng(42)
    # Simulated dictionary in sub-bins
    A_unscaled = rng.uniform(10.0, 1000.0, size=(n_sub_bins, n_states))
    # Planted true weights on two states
    w_true = np.array([0.0, 15.0, 0.0, 45.0, 0.0, 0.0])
    y_sub = A_unscaled @ w_true
    sigma_sub = np.full(n_sub_bins, 1.0)

    w_est = solve_block_a(A_unscaled, y_sub, sigma_sub)

    assert len(w_est) == n_states
    assert np.all(w_est >= 0.0), "NNLS must return non-negative weights"
    assert np.allclose(w_est, w_true, atol=1e-5), "Block A must recover exact planted weights"


def test_block_b_recovers_smooth_response_with_gauge():
    """Block B recovers B-spline response from log-ratios and fixes the gauge."""
    sub_bin_centers = np.linspace(380.0, 820.0, 25)
    span = (350.0, 850.0)
    k_coeffs = 5
    basis = CubicBSplineBasis(k_coeffs=k_coeffs, span=span)

    # Planted beta with zero gauge (mean of B @ beta_true is zero)
    beta_true = np.array([0.5, -0.3, 0.2, 0.4, -0.8])
    log_r_sub = basis.evaluate(sub_bin_centers) @ beta_true
    log_r_sub -= np.mean(log_r_sub)

    # Simulated log-ratios rho = log(Y / a)
    rho = log_r_sub.copy()
    weights = np.full(len(sub_bin_centers), 100.0)

    beta_est = solve_block_b(
        basis=basis,
        sub_bin_centers=sub_bin_centers,
        rho=rho,
        weights=weights,
        mu_r=1.0,
    )

    log_r_est = basis.evaluate(sub_bin_centers) @ beta_est
    # Check gauge: weighted mean of log r must be 0
    assert np.isclose(np.average(log_r_est, weights=weights), 0.0, atol=1e-6)
    # Check recovery accuracy
    assert np.allclose(log_r_est, log_r_sub, atol=0.05)


def test_knot_count_reduction_against_gap():
    """K reduces towards 4 when knot spacing is less than 1.5 * widest gap."""
    span = (350.0, 850.0)
    # Sub-bin centers with a wide gap of 150 nm between 500 and 650
    centers = np.array([360.0, 400.0, 450.0, 500.0, 650.0, 700.0, 800.0, 840.0])

    # For K=5: span/(5-3) = 500/2 = 250. 1.5 * 150 = 225. 250 >= 225 -> K=5 is valid.
    k_opt, gap_flag = reduce_knots_against_gap(centers, span, initial_k=5)
    assert k_opt == 5
    assert gap_flag is False

    # Sub-bin centers with an even wider gap of 200 nm (1.5 * 200 = 300 > 250)
    # For K=4: span/(4-3) = 500/1 = 500. 500 >= 300 -> K=4 is chosen.
    centers_wide = np.array([360.0, 400.0, 450.0, 650.0, 700.0, 840.0])
    k_opt2, gap_flag2 = reduce_knots_against_gap(centers_wide, span, initial_k=5)
    assert k_opt2 == 4
    assert gap_flag2 is False

    # Sub-bin centers with an impossible gap of 350 nm (1.5 * 350 = 525 > 500 at K=4)
    centers_huge = np.array([360.0, 720.0, 840.0])
    k_opt3, gap_flag3 = reduce_knots_against_gap(centers_huge, span, initial_k=5)
    assert k_opt3 == 4
    assert gap_flag3 is True, "RESPONSE_GAP must be flagged when gap cannot be met"


def test_diagnostics_r9_flags_construction(sample_manifest):
    """AC8: Verify that each R9 diagnostic flag is raised when its trigger condition occurs."""
    # 1. TE_GRID_EDGE
    diag1 = evaluate_diagnostics(
        weights_by_mat={"M1": np.array([100.0, 0.0, 0.0, 0.0])},  # state 0 is at grid edge min Te
        te_grid=[0.5, 0.8, 1.2, 1.8],
        ne_grid=[1e17],
        response_curve=np.array([1.0, 2.0]),
        response_restart_spread=0.01,
        iterations=10,
        gap_flag=False,
        reduced_chi2_by_mat={"M1": 1.2},
    )
    assert "TE_GRID_EDGE" in diag1["flags"]

    # 2. RESPONSE_RANGE_SUSPECT (max/min > 30)
    diag2 = evaluate_diagnostics(
        weights_by_mat={"M1": np.array([0.0, 100.0, 0.0, 0.0])},
        te_grid=[0.5, 0.8, 1.2, 1.8],
        ne_grid=[1e17],
        response_curve=np.array([1.0, 45.0]),  # 45/1 = 45 > 30
        response_restart_spread=0.01,
        iterations=10,
        gap_flag=False,
        reduced_chi2_by_mat={"M1": 1.2},
    )
    assert "RESPONSE_RANGE_SUSPECT" in diag2["flags"]

    # 3. RESPONSE_GAP
    diag3 = evaluate_diagnostics(
        weights_by_mat={"M1": np.array([0.0, 100.0, 0.0, 0.0])},
        te_grid=[0.5, 0.8, 1.2, 1.8],
        ne_grid=[1e17],
        response_curve=np.array([1.0, 2.0]),
        response_restart_spread=0.01,
        iterations=10,
        gap_flag=True,
        reduced_chi2_by_mat={"M1": 1.2},
    )
    assert "RESPONSE_GAP" in diag3["flags"]

    # 4. RESPONSE_RESTART_SPREAD (> 0.10)
    diag4 = evaluate_diagnostics(
        weights_by_mat={"M1": np.array([0.0, 100.0, 0.0, 0.0])},
        te_grid=[0.5, 0.8, 1.2, 1.8],
        ne_grid=[1e17],
        response_curve=np.array([1.0, 2.0]),
        response_restart_spread=0.15,
        iterations=10,
        gap_flag=False,
        reduced_chi2_by_mat={"M1": 1.2},
    )
    assert "RESPONSE_RESTART_SPREAD" in diag4["flags"]

    # 5. CHI2_HIGH (> 5.0)
    diag5 = evaluate_diagnostics(
        weights_by_mat={"M1": np.array([0.0, 100.0, 0.0, 0.0])},
        te_grid=[0.5, 0.8, 1.2, 1.8],
        ne_grid=[1e17],
        response_curve=np.array([1.0, 2.0]),
        response_restart_spread=0.01,
        iterations=10,
        gap_flag=False,
        reduced_chi2_by_mat={"M1": 6.8},  # > 5.0
    )
    assert "CHI2_HIGH" in diag5["flags"]

    # 6. NOT_CONVERGED (>= 100 iterations)
    diag6 = evaluate_diagnostics(
        weights_by_mat={"M1": np.array([0.0, 100.0, 0.0, 0.0])},
        te_grid=[0.5, 0.8, 1.2, 1.8],
        ne_grid=[1e17],
        response_curve=np.array([1.0, 2.0]),
        response_restart_spread=0.01,
        iterations=100,
        gap_flag=False,
        reduced_chi2_by_mat={"M1": 1.2},
    )
    assert "NOT_CONVERGED" in diag6["flags"]


@pytest.fixture
def sample_manifest():
    return {
        "modelErrorFraction": 0.10,
        "responsePenalty": 1.0,
    }
