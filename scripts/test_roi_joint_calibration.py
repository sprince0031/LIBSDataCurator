"""Unit tests for ROI joint calibration solver, variance model, and R9 diagnostics (R6, R7, R9, AC8)."""

from __future__ import annotations

import json
from pathlib import Path
import numpy as np
import pandas as pd
import pytest

from scripts.measured_conditioning import ConditionedMaterial, condition_material
from scripts.forward_model import resample_to_detector
from scripts.nist_lines import load_lines, accuracy_to_uncertainty
from scripts.roi_catalogue import RoiWindow, SubBin, build_catalogue
from scripts.roi_joint_calibration import (
    CubicBSplineBasis,
    compute_sub_bin_variances,
    solve_block_a,
    solve_block_b,
    reduce_knots_against_gap,
    evaluate_diagnostics,
    run_joint_solver,
    CalibrationResult,
    solve_family_plasma,
    reduce_zones_to_num_zones,
    scan_absorption,
    compute_family_scales_and_sharing_penalty,
    compute_plasma_dispersion,
    compute_element_parity,
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
    chans = [0, 1, 2, 3]
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
        mu_r=1e-4,
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
    assert any("TE_GRID_EDGE" in f for f in diag1["flags"])

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
    assert any("RESPONSE_RANGE_SUSPECT" in f for f in diag2["flags"])

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
    assert any("RESPONSE_GAP" in f for f in diag3["flags"])

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
    assert any("RESPONSE_RESTART_SPREAD" in f for f in diag4["flags"])

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
    assert any("CHI2_HIGH" in f for f in diag5["flags"])

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
    assert any("NOT_CONVERGED" in f for f in diag6["flags"])


@pytest.fixture
def sample_manifest():
    return {
        "modelErrorFraction": 0.10,
        "responsePenalty": 1.0,
    }


def test_joint_solver_on_real_reference_dataset():
    """Verify joint plasma-response solver on real experimental LIBS dataset (469, 470, 551).
    
    Uses:
    - Real conditioned LSA measured CSVs from data/LSA/Medals_Samples_Feb26
    - Real cached NIST calculated spectra from build/release-package/cache/nist
    - Real ASD line transitions from data/nist_lines
    - Real ROI catalogue generated via build_catalogue
    """
    mats = ["469", "470", "551"]
    cond_map = {}
    for m in mats:
        paths = list(Path(f"data/LSA/Medals_Samples_Feb26/{m}").glob("*.csv"))
        if not paths:
            pytest.skip(f"Measured CSVs for material {m} not found")
        cond_map[m] = condition_material(paths)
    det_wl = cond_map["469"].wavelengths

    comp_map = {
        "469": {"Fe": 86.49, "Cr": 11.93, "Ni": 0.25, "Mn": 0.60, "Si": 0.42, "C": 0.28},
        "470": {"Fe": 81.17, "Cr": 17.68, "Ni": 0.37, "Mn": 0.24, "Si": 0.34, "C": 0.15},
        "551": {"Cu": 84.5, "Sn": 6.0, "Pb": 4.5, "Zn": 4.5, "Ni": 0.5},
    }
    fam_map = {"469": "Steel", "470": "Steel", "551": "Bronze"}

    # Load real ASD lines
    lines = load_lines(["steel", "bronze"], wl_range=(float(det_wl[0]), float(det_wl[-1])))
    valid = (lines["Aki"] > 0) & (lines["Ek_eV"] > 0) & (lines["g_k"] > 0) & lines["wavelength_nm"].notna()
    model_lines = lines[valid].copy().sort_values("Aki", ascending=False).head(1000).reset_index(drop=True)
    model_lines["wl_nm"] = model_lines["wavelength_nm"]
    model_lines["unc"] = model_lines["accuracy"].apply(accuracy_to_uncertainty)
    model_lines["gA"] = model_lines["g_k"] * model_lines["Aki"]

    # Load cached NIST dictionary
    te_grid = [0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.5, 1.7, 2.0]
    ne_grid = [1e16, 5e16, 1e17, 5e17]
    cache_dir = Path("build/release-package/cache/nist")
    dictionary = {}
    for m in mats:
        files = sorted(cache_dir.glob(f"{m}_Te*.csv"))
        if not files:
            pytest.skip(f"Cached NIST spectra for {m} not found")
        D = np.zeros((len(det_wl), len(files)), dtype=np.float64)
        for i, f in enumerate(files):
            df_c = pd.read_csv(f)
            D[:, i] = resample_to_detector(df_c["Wavelength (nm)"].to_numpy(), df_c["Sum(calc)"].to_numpy(), det_wl, 0.0)
        dictionary[m] = D

    # Build real ROIs
    rois = build_catalogue(
        family="Steel",
        compositions=comp_map,
        model_lines=model_lines,
        dictionary_ref=dictionary["469"],
        conditioned=cond_map,
        detector_wavelengths=det_wl,
    )
    assert len(rois) >= 10, f"Expected at least 10 ROIs, got {len(rois)}"

    # Run joint solver on real data
    result = run_joint_solver(
        rois=rois,
        dictionary=dictionary,
        conditioned=cond_map,
        detector_wavelengths=det_wl,
        material_families=fam_map,
        te_grid=te_grid,
        ne_grid=ne_grid,
    )

    # Verification assertions on real data
    assert result.converged is True, "Solver must converge on real experimental data"
    assert result.spectral_response.shape == det_wl.shape
    assert np.all(result.spectral_response > 0.0), "Instrument response must be strictly positive"
    resp_ratio = float(np.max(result.spectral_response) / np.min(result.spectral_response))
    assert resp_ratio < 30.0, f"Instrument response dynamic range {resp_ratio:.1f} must be < 30"
    for m in mats:
        assert np.all(result.plasma_weights[m] >= 0.0), f"Weights for {m} must be non-negative"
        assert np.sum(result.plasma_weights[m]) > 0.0, f"Weights for {m} must have non-zero sum"


def test_family_shared_weights_and_scales():
    """R8.1: Family-shared weights with per-material scales recover a planted family state."""
    n_sub_bins = 20
    n_states = 5
    rng = np.random.default_rng(42)

    A_ref = rng.uniform(10.0, 500.0, size=(n_sub_bins, n_states))
    w_planted = np.array([0.0, 15.0, 0.0, 35.0, 0.0])
    y_base = A_ref @ w_planted

    scale_469 = 1.40
    scale_470 = 0.75

    y_469 = y_base * scale_469
    y_470 = y_base * scale_470
    sigma = np.full(n_sub_bins, 1.0)

    # Build sub-bin info
    mat_sub_data = {
        "469": [{"A_row": A_ref[b], "Y": y_469[b], "sigma": sigma[b], "is_prone": False} for b in range(n_sub_bins)],
        "470": [{"A_row": A_ref[b], "Y": y_470[b], "sigma": sigma[b], "is_prone": False} for b in range(n_sub_bins)],
    }

    w_fam, scales = solve_family_plasma(mat_sub_data, n_states=n_states)

    assert len(w_fam) == n_states
    assert np.all(w_fam >= 0.0)
    # Recovered support matches planted states 1 and 3
    assert w_fam[0] == pytest.approx(0.0, abs=1e-3)
    assert w_fam[2] == pytest.approx(0.0, abs=1e-3)
    assert w_fam[4] == pytest.approx(0.0, abs=1e-3)
    # Scale ratio matches 1.40 / 0.75
    ratio_recovered = scales["469"] / scales["470"]
    assert ratio_recovered == pytest.approx(scale_469 / scale_470, rel=1e-2)


def test_zone_reduction():
    """R8.2: Zone reduction reduces support to numZones and computes zoneReductionPenalty."""
    n_sub_bins = 20
    n_states = 6
    rng = np.random.default_rng(43)

    A_ref = rng.uniform(10.0, 500.0, size=(n_sub_bins, n_states))
    # 4 non-zero zones: states 0 (weak), 1 (strong), 3 (medium), 4 (very weak)
    w_initial = np.array([5.0, 40.0, 0.0, 20.0, 1.0, 0.0])
    y = A_ref @ w_initial
    sigma = np.full(n_sub_bins, 1.0)

    sub_data = [{"A_row": A_ref[b], "Y": y[b], "sigma": sigma[b], "is_prone": False} for b in range(n_sub_bins)]

    w_red, penalty = reduce_zones_to_num_zones(
        initial_weights=w_initial,
        sub_data=sub_data,
        num_zones=2,
    )

    non_zero_indices = np.where(w_red > 1e-4)[0]
    assert len(non_zero_indices) <= 2, f"Expected <= 2 non-zero zones, got {len(non_zero_indices)}"
    # Strongest states 1 and 3 should be preserved
    assert 1 in non_zero_indices
    assert 3 in non_zero_indices
    assert penalty >= 1.0, f"zoneReductionPenalty {penalty} must be >= 1.0"


def test_absorption_scan():
    """R8.3: Absorption scan recovers planted k, returns 0 when no benefit, and flags bound."""
    n_sub_bins = 25
    rng = np.random.default_rng(44)
    # Coolest zone profile (d_cool), hottest zone profile (d_hot)
    d_cool = rng.uniform(50.0, 200.0, n_sub_bins)
    d_hot = rng.uniform(10.0, 100.0, n_sub_bins)
    sigma = np.full(n_sub_bins, 1.0)
    is_prone = np.array([b % 3 == 0 for b in range(n_sub_bins)])

    # Case 1: Planted k = 2.4 — cool shell attenuates hot core emission (matching apply_multi_zone_rte 1:1)
    k_planted = 2.4
    d_tilde_cool = d_cool / np.max(d_cool)
    attenuation = np.exp(-k_planted * d_tilde_cool)
    y_synth = 0.4 * d_hot * attenuation + 0.6 * d_cool

    sub_data = [
        {"d_cool": d_cool[b], "d_hot": d_hot[b], "Y": y_synth[b], "sigma": sigma[b], "is_prone": is_prone[b]}
        for b in range(n_sub_bins)
    ]

    k_est, flags = scan_absorption(sub_data, k_grid=np.linspace(0.0, 8.0, 81))
    assert k_est == pytest.approx(k_planted, abs=0.15)
    assert "K_ABSORPTION_AT_BOUND" not in flags

    # Case 2: No absorption planted (k = 0) -> retains k = 0
    y_no_abs = 0.6 * d_cool + 0.4 * d_hot
    sub_data_no_abs = [
        {"d_cool": d_cool[b], "d_hot": d_hot[b], "Y": y_no_abs[b], "sigma": sigma[b], "is_prone": is_prone[b]}
        for b in range(n_sub_bins)
    ]
    k_zero, flags_zero = scan_absorption(sub_data_no_abs, k_grid=np.linspace(0.0, 8.0, 81))
    assert k_zero == pytest.approx(0.0, abs=1e-5)

    # Case 3: Planted k = 8.0 -> hits scan limit, flags K_ABSORPTION_AT_BOUND
    attenuation_bound = np.exp(-8.0 * d_tilde_cool)
    y_bound = 0.4 * d_hot * attenuation_bound + 0.6 * d_cool
    sub_data_bound = [
        {"d_cool": d_cool[b], "d_hot": d_hot[b], "Y": y_bound[b], "sigma": sigma[b], "is_prone": is_prone[b]}
        for b in range(n_sub_bins)
    ]
    k_bound, flags_bound = scan_absorption(sub_data_bound, k_grid=np.linspace(0.0, 8.0, 81))
    assert k_bound == pytest.approx(8.0, abs=0.1)
    assert any("K_ABSORPTION_AT_BOUND" in f for f in flags_bound)


def test_family_scale_factors_and_sharing_penalty():
    """R8.5, R8.7: Median family scaleFactor, materialScaleFactors, materialPlasmaZones, familySharingPenalty."""
    mat_scales = {"469": 1.20, "470": 1.50, "471": 0.90}
    mat_phis = {"469": 10.0, "470": 15.0, "471": 8.0}
    phi_family = 36.3  # 10% penalty over sum(10+15+8 = 33)

    metrics = compute_family_scales_and_sharing_penalty(
        material_scales=mat_scales,
        phi_family=phi_family,
        phi_materials=mat_phis,
    )

    # Median of [0.90, 1.20, 1.50] is 1.20
    assert metrics["scaleFactor"] == pytest.approx(1.20, abs=1e-4)
    assert metrics["materialScaleFactors"]["469"] == 1.20
    assert metrics["materialScaleFactors"]["470"] == 1.50
    assert metrics["materialScaleFactors"]["471"] == 0.90
    # familySharingPenalty = 36.3 / 33.0 = 1.10
    assert metrics["familySharingPenalty"] == pytest.approx(1.10, rel=1e-4)


def test_plasma_dispersion_and_clamps():
    """R8.6: teStdDev in [0.01, 0.10], neLogStdDev in [0.02, 0.30] clamped."""
    # Point mean temperatures: tight spread (std = 0.002 -> clamp to 0.01)
    tight_te = [0.850, 0.852, 0.851, 0.849, 0.850]
    tight_ne = [17.00, 17.01, 16.99, 17.00, 17.00]  # log10 Ne
    disp_tight = compute_plasma_dispersion(tight_te, tight_ne)
    assert disp_tight["teStdDev"] == pytest.approx(0.01, abs=1e-5)
    assert disp_tight["neLogStdDev"] == pytest.approx(0.02, abs=1e-5)

    # Wide spread: std Te = 0.25 -> clamp to 0.10, std log10 Ne = 0.50 -> clamp to 0.30
    wide_te = [0.60, 0.90, 1.20, 0.70, 1.10]
    wide_ne = [15.5, 17.5, 16.0, 16.8, 17.2]
    disp_wide = compute_plasma_dispersion(wide_te, wide_ne)
    assert disp_wide["teStdDev"] == pytest.approx(0.10, abs=1e-5)
    assert disp_wide["neLogStdDev"] == pytest.approx(0.30, abs=1e-5)

    # Moderate spread within bounds
    mod_te = [0.80, 0.85, 0.90, 0.82, 0.88]  # std ~= 0.041
    mod_ne = [16.8, 17.0, 17.1, 16.9, 17.0]  # std ~= 0.114
    disp_mod = compute_plasma_dispersion(mod_te, mod_ne)
    assert 0.01 < disp_mod["teStdDev"] < 0.10
    assert 0.02 < disp_mod["neLogStdDev"] < 0.30


def test_element_parity_and_bias_flag():
    """R9, R11: elementParity distribution and ELEMENT_BIAS flag when |bias| > 0.30."""
    sub_bins = [
        {"dominant_element": "Fe", "synthetic": 400.0, "measured": 100.0},  # log10(4) = +0.602 (bias)
        {"dominant_element": "Fe", "synthetic": 800.0, "measured": 200.0},
        {"dominant_element": "Cr", "synthetic": 110.0, "measured": 100.0},  # log10(1.1) = +0.041
        {"dominant_element": "Ni", "synthetic": 95.0, "measured": 100.0},   # log10(0.95) = -0.022
    ]

    parity, flags = compute_element_parity(sub_bins)

    assert "Fe" in parity
    assert parity["Fe"] == pytest.approx(np.log10(4.0), abs=1e-3)
    assert parity["Cr"] == pytest.approx(np.log10(1.1), abs=1e-3)
    assert any("ELEMENT_BIAS:Fe" in f for f in flags)
    assert not any("ELEMENT_BIAS:Cr" in f for f in flags)
    assert not any("ELEMENT_BIAS:Ni" in f for f in flags)


