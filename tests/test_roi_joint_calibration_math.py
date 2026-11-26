"""Deterministic math, RTE, uncertainty, and calibration tests for TRACK-CORE-02A."""

from __future__ import annotations

from pathlib import Path
import numpy as np
import pandas as pd
import pytest


def test_apply_multi_zone_rte_canonical_and_combine_zones_alias() -> None:
    from scripts.forward_model import (
        NIST_UNIT_SCALE,
        PlasmaZoneSpec,
        apply_multi_zone_rte,
        combine_zones,
    )

    assert NIST_UNIT_SCALE == 1.0

    d_hot = np.array([100.0, 400.0, 800.0], dtype=np.float64)
    d_cool = np.array([50.0, 200.0, 400.0], dtype=np.float64)
    # Intentionally pass zones out of Te order to verify hot-to-cool sorting
    z_cool = PlasmaZoneSpec(te=0.6, ne=5e16, volume_fraction=0.35, k_absorption=1.5)
    z_hot = PlasmaZoneSpec(te=1.2, ne=1e17, volume_fraction=0.65, k_absorption=0.0)

    out_rte = apply_multi_zone_rte([d_cool, d_hot], [z_cool, z_hot])
    out_alias = combine_zones([d_cool, d_hot], [z_cool, z_hot])

    d_tilde_cool = d_cool / np.max(d_cool)
    expected = (0.65 * d_hot) * np.exp(-1.5 * d_tilde_cool) + 0.35 * d_cool
    np.testing.assert_allclose(out_rte, expected, rtol=1e-12, atol=1e-12)
    np.testing.assert_allclose(out_alias, expected, rtol=1e-12, atol=1e-12)


def test_load_nist_dictionary_uses_unity_scale_matching_java(tmp_path: Path) -> None:
    from scripts.forward_model import load_nist_dictionary

    wls = np.linspace(350.0, 355.0, 51)
    intens = 2000.0 * np.exp(-0.5 * ((wls - 352.5) / 0.2) ** 2)
    csv_path = tmp_path / "state.csv"
    pd.DataFrame(
        {"Wavelength (nm)": wls, "Sum(calc)": intens, "Fe I": intens}
    ).to_csv(csv_path, index=False)

    det_wl = np.linspace(350.0, 355.0, 11)
    D, elem_mats = load_nist_dictionary(
        {"1.0_1.0E+17": csv_path}, det_wl, add_sigma_nm=0.0, te_grid=[1.0], ne_grid=[1.0e17]
    )
    # Raw NIST peak ~2000 must NOT be scaled down by 1e-6
    assert float(np.max(D[:, 0])) > 100.0
    assert float(np.max(elem_mats["Fe"][:, 0])) > 100.0


def test_validate_spectral_match_uses_canonical_rte_and_metrics() -> None:
    from scripts.forward_model import (
        PlasmaZoneSpec,
        apply_multi_zone_rte,
        apply_response_and_scale,
    )
    from scripts.validate_spectral_match import synthesize_spectrum

    d_hot = np.array([1000.0, 5000.0, 2000.0, 400.0], dtype=np.float64)
    d_cool = np.array([300.0, 2500.0, 1000.0, 200.0], dtype=np.float64)
    dictionary = np.vstack([d_hot, d_cool])  # (N_states, N_channels)
    states = [(1.2, 1e17), (0.6, 5e16)]
    zones_dicts = [
        {"zoneIndex": 0, "Te": 1.2, "Ne": 1e17, "volumeFraction": 0.7, "kAbsorption": 0.0},
        {"zoneIndex": 1, "Te": 0.6, "Ne": 5e16, "volumeFraction": 0.3, "kAbsorption": 1.8},
    ]
    resp = np.array([0.9, 1.1, 1.0, 0.8], dtype=np.float64)
    scale = 2.5

    actual = synthesize_spectrum(dictionary, states, zones_dicts, scale, resp)
    expected_rad = apply_multi_zone_rte(
        [d_hot, d_cool], [PlasmaZoneSpec.from_dict(z) for z in zones_dicts]
    )
    expected = np.maximum(apply_response_and_scale(expected_rad, resp, scale, "absolute"), 0.0)
    # Previously validate_spectral_match omitted max(s_z) normalization -> exp(-1.8 * 2500) == 0!
    np.testing.assert_allclose(actual, expected, rtol=1e-12, atol=1e-12)


def test_conditioned_material_exposes_preprocessed_point_means_and_num_steady_shots(
    tmp_path: Path,
) -> None:
    from scripts.measured_conditioning import apply_morphological_baseline, condition_material

    p = 120
    wls = np.linspace(350.0, 700.0, p)
    base = 400.0 + 80.0 * np.sin(wls / 30.0)
    base[:10] = 50.0
    base[50] += 3000.0
    base[80] += 5000.0

    csv_paths = []
    for pt in range(2):
        shots = np.tile(base, (20, 1))
        df = pd.DataFrame(shots, columns=[f"{w:.3f}" for w in wls])
        path = tmp_path / f"pt_{pt}.csv"
        df.to_csv(path, index=False)
        csv_paths.append(str(path))

    cond = condition_material(csv_paths, manifest={"discardLeadingShots": 4})
    # 2 points * (20 - 4) steady shots = 32 steady shots (NOT 2 * 120 = 240 channels)
    assert cond.num_steady_shots == 32
    assert set(cond.preprocessed_point_means.keys()) == set(cond.point_means.keys())
    for pt_name, raw_pt in cond.point_means.items():
        expected_pre = apply_morphological_baseline(raw_pt, se_size=25)
        np.testing.assert_allclose(cond.preprocessed_point_means[pt_name], expected_pre, atol=1e-12)
        assert np.all(cond.preprocessed_point_means[pt_name] >= 0.0)


def test_compute_element_parity_recovers_zero_dex_with_noncontiguous_nz_indices() -> None:
    from scripts.forward_model import (
        PlasmaZoneSpec,
        apply_multi_zone_rte,
        apply_response_and_scale,
    )
    from scripts.roi_catalogue import RoiWindow
    from scripts.roi_joint_calibration import compute_element_parity

    n_ch = 20
    n_states = 10
    rng = np.random.default_rng(7)
    D_m = rng.uniform(50.0, 5000.0, size=(n_ch, n_states))
    # True active states are non-contiguous columns 3 (hot) and 8 (cool), NOT 0 and 1!
    nz_indices = [3, 8]
    plasma_zones = [
        {"zoneIndex": 0, "Te": 1.2, "Ne": 1e17, "volumeFraction": 0.65, "kAbsorption": 0.0},
        {"zoneIndex": 1, "Te": 0.6, "Ne": 5e16, "volumeFraction": 0.35, "kAbsorption": 1.4},
    ]
    resp = np.linspace(0.8, 1.2, n_ch)
    scale = 2.5

    i_rad = apply_multi_zone_rte(
        [D_m[:, 3], D_m[:, 8]],
        [PlasmaZoneSpec.from_dict(z) for z in plasma_zones],
    )
    y_meas = apply_response_and_scale(i_rad, resp, scale, "absolute")

    rois = [
        RoiWindow(
            roi_id="ROI_Fe",
            min_nm=400.0,
            max_nm=405.0,
            channel_indices=[1, 2, 3, 4],
            sub_bins=[[1, 2], [3, 4]],
            dominant_element="Fe",
            used_in_response_fit=True,
            self_absorption_prone=False,
        ),
        RoiWindow(
            roi_id="ROI_Cr",
            min_nm=420.0,
            max_nm=425.0,
            channel_indices=[10, 11, 12, 13],
            sub_bins=[[10, 11], [12, 13]],
            dominant_element="Cr",
            used_in_response_fit=False,
            self_absorption_prone=True,
        ),
    ]

    parity, has_bias = compute_element_parity(
        rois=rois,
        dictionary={"SS469": D_m},
        conditioned={"SS469": y_meas},
        plasma_zones=plasma_zones,
        scale_factor=scale,
        spectral_response=resp,
        nz_indices=nz_indices,
    )
    assert has_bias is False
    assert parity["Fe"] == pytest.approx(0.0, abs=1e-9)
    assert parity["Cr"] == pytest.approx(0.0, abs=1e-9)


def test_scan_absorption_attenuates_hot_core_matching_apply_multi_zone_rte() -> None:
    from scripts.forward_model import PlasmaZoneSpec, apply_multi_zone_rte
    from scripts.roi_joint_calibration import scan_absorption

    rng = np.random.default_rng(123)
    n_ch = 60
    d_hot_full = rng.uniform(500.0, 4000.0, size=n_ch)
    d_cool_full = rng.uniform(50.0, 2000.0, size=n_ch)
    # Plant a strong peak in d_cool_full outside sub-bins to verify full-spectrum max normalization
    d_cool_full[0] = 5000.0

    k_planted = 2.6
    zones = [
        PlasmaZoneSpec(te=1.2, ne=1e17, volume_fraction=0.65, k_absorption=0.0),
        PlasmaZoneSpec(te=0.6, ne=5e16, volume_fraction=0.35, k_absorption=k_planted),
    ]
    y_full = apply_multi_zone_rte([d_hot_full, d_cool_full], zones)

    # 20 single-channel sub-bins (indices 5..24), half marked prone
    ch_idx = np.arange(5, 25)
    is_prone = np.array([i % 2 == 0 for i in range(len(ch_idx))], dtype=bool)
    cool_max = float(np.max(d_cool_full))
    sub_data = [
        {
            "d_hot": float(d_hot_full[c]),
            "d_cool": float(d_cool_full[c]),
            "Y": float(y_full[c]),
            "sigma": 5.0,
            "is_prone": bool(is_prone[i]),
            "cool_zone_max": cool_max,
        }
        for i, c in enumerate(ch_idx)
    ]

    k_est, flags = scan_absorption(sub_data, k_grid=np.linspace(0.0, 8.0, 81))
    assert k_est == pytest.approx(k_planted, abs=0.1)
    assert "K_ABSORPTION_AT_BOUND" not in flags


def test_dict_wrappers_propagate_sigma_dict_instead_of_unit_sigma() -> None:
    from scripts.roi_joint_calibration import reduce_zones_to_num_zones, solve_family_plasma

    # Coupled 2-state design: bin 0 has huge outlier corruption AND huge sigma=1e6,
    # while bins 1 and 2 have clean signal (true w = [1.0, 0.0]) and small sigma=0.1.
    # If sigma_dict is respected, bin 0 is downweighted so w[0] ~= 1.0;
    # if sigma=1.0 is hardcoded, bin 0 pulls w heavily away from 1.0!
    A_m = np.array(
        [
            [10.0, 10.0],  # corrupted bin (outlier Y=1000, huge sigma=1e6)
            [10.0, 0.0],   # clean bin driven by state 0 (Y=10, sigma=0.1)
            [20.0, 1.0],   # clean bin driven by state 0 (Y=20, sigma=0.1)
        ],
        dtype=np.float64,
    )
    y_m = np.array([1000.0, 10.0, 20.0], dtype=np.float64)
    sigma_m = np.array([1e6, 0.1, 0.1], dtype=np.float64)

    w_weighted, _ = solve_family_plasma({"M1": A_m}, {"M1": y_m}, sigma_dict={"M1": sigma_m})
    w_unit, _ = solve_family_plasma({"M1": A_m}, {"M1": y_m})
    assert w_weighted[0] == pytest.approx(1.0, abs=1e-2)
    assert w_weighted[1] < 0.05
    # Verify unweighted solution would indeed be corrupted by bin 0
    assert w_unit[1] > 10.0

    w_red, penalty = reduce_zones_to_num_zones(
        {"M1": A_m}, {"M1": y_m}, np.array([1.0, 0.2]), num_zones=1, sigma_dict={"M1": sigma_m}
    )
    w_red_unit, _ = reduce_zones_to_num_zones(
        {"M1": A_m}, {"M1": y_m}, np.array([1.0, 0.2]), num_zones=1
    )
    # Dropping state 1 and re-solving NNLS with sigma_dict must keep w_red[0] ~= 1.0 (not corrupted by bin 0!)
    assert w_red[0] == pytest.approx(1.0, abs=1e-2)
    assert w_red[1] == 0.0
    assert w_red_unit[0] > 10.0
    assert penalty >= 1.0


def test_compute_plasma_dispersion_uses_ddof_1() -> None:
    from scripts.roi_joint_calibration import compute_plasma_dispersion

    pts_te = [0.90, 0.94, 0.98, 1.02, 1.06]
    pts_ne = [16.80, 16.90, 17.00, 17.10, 17.20]
    disp = compute_plasma_dispersion(pts_te, pts_ne)

    expected_te_std = float(np.std(pts_te, ddof=1))
    expected_ne_std = float(np.std(pts_ne, ddof=1))
    assert disp["teStdDev"] == pytest.approx(expected_te_std, abs=1e-12)
    assert disp["neLogStdDev"] == pytest.approx(expected_ne_std, abs=1e-12)


def test_run_joint_solver_uses_preprocessed_mean_and_real_family_group_rms_diff() -> None:
    from scripts.measured_conditioning import ConditionedMaterial
    from scripts.roi_catalogue import RoiWindow
    from scripts.roi_joint_calibration import run_joint_solver

    n_ch = 100
    det_wl = np.linspace(350.0, 750.0, n_ch)
    te_grid = [0.8, 1.0]
    ne_grid = [1.0e17]
    n_states = 2

    # Build 6 well-separated thin ROIs across 360..730 nm (> 60 nm common span)
    centers = [10, 25, 40, 55, 70, 85]
    rois = []
    D_fe = np.zeros((n_ch, n_states), dtype=np.float64)
    D_al = np.zeros((n_ch, n_states), dtype=np.float64)
    for idx_r, c in enumerate(centers):
        D_fe[c - 1 : c + 2, 0] = 100.0
        D_fe[c - 1 : c + 2, 1] = 200.0
        D_al[c - 1 : c + 2, 0] = 100.0
        D_al[c - 1 : c + 2, 1] = 200.0
        rois.append(
            RoiWindow(
                roi_id=f"ROI_{idx_r}",
                min_nm=float(det_wl[c - 1]),
                max_nm=float(det_wl[c + 1]),
                channel_indices=[c - 1, c, c + 1],
                sub_bins=[[c - 1, c, c + 1]],
                dominant_element="Fe",
                used_in_response_fit=True,
                self_absorption_prone=False,
            )
        )

    # Plant two strongly divergent response curves between ferrous (Steel) and non-ferrous (Al)
    # AND put a massive un-baselined continuum offset in mean_spectrum (while preprocessed_mean is clean!)
    r_fe = np.exp(0.8 * (det_wl - 550.0) / 200.0)
    r_al = np.exp(-0.8 * (det_wl - 550.0) / 200.0)
    y_fe_clean = (D_fe[:, 1] * 1.0) * r_fe
    y_al_clean = (D_al[:, 1] * 1.0) * r_al

    def _make_cond(y_clean: np.ndarray) -> ConditionedMaterial:
        return ConditionedMaterial(
            wavelengths=det_wl,
            mean_spectrum=y_clean + 50000.0,  # huge continuum offset that MUST be ignored
            preprocessed_mean=y_clean,
            sigma_mean=np.full(n_ch, 1.0),
            valid_mask=np.ones(n_ch, dtype=bool),
            lit_mask=np.ones(n_ch, dtype=bool),
            dark_level=0.0,
            point_means={"p1": y_clean + 50000.0},
            discarded_shots_per_point={"p1": 3},
            preprocessed_point_means={"p1": y_clean},
            num_steady_shots=37,
        )

    res = run_joint_solver(
        rois=rois,
        dictionary={"SS469": D_fe, "AL42000": D_al},
        conditioned={"SS469": _make_cond(y_fe_clean), "AL42000": _make_cond(y_al_clean)},
        detector_wavelengths=det_wl,
        material_families={"SS469": "StainlessSteel", "AL42000": "Aluminium"},
        te_grid=te_grid,
        ne_grid=ne_grid,
    )
    # Previously hardcoded to 0.02! With opposite +/-0.8 log tilts, real RMS diff must exceed 0.25!
    assert res.family_group_response_rms_diff != pytest.approx(0.02, abs=1e-6)
    assert res.family_group_response_rms_diff > 0.25
    assert "RESPONSE_FAMILY_INCONSISTENT" in res.flags
    # And weights must reflect y_clean (~1.0), NOT y_clean + 50000 (~250.0)!
    assert float(np.sum(res.plasma_weights["SS469"])) < 10.0
