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


def test_gpu_engine_dynamic_add_sigma_nm_and_gate_g1(tmp_path: Path) -> None:
    import json
    from scripts.gpu_calibration_engine import GpuLibsCalibrationEngine
    from scripts.roi_catalogue import RoiWindow
    from scripts.spectral_resampling import calculate_add_sigma_nm

    manifest = {
        "outputProfilePath": str(tmp_path / "instrument_profile.json"),
        "nistResolvingPower": 5000.0,
        "instrumentFwhmNm": 0.35,
        "enableLineSpreadBroadening": True,
        "materialFamilies": {},
    }
    mpath = tmp_path / "manifest.json"
    mpath.write_text(json.dumps(manifest), encoding="utf-8")
    engine = GpuLibsCalibrationEngine(str(mpath))

    det_wl = np.linspace(350.0, 850.0, 50)
    add_sigma = engine.compute_line_spread_add_sigma(det_wl, r_nist=5000.0)
    expected_sigma = calculate_add_sigma_nm(0.35, det_wl / 5000.0)
    np.testing.assert_allclose(add_sigma, expected_sigma, rtol=1e-12, atol=1e-12)
    assert np.all(add_sigma > 0.0)

    # Gate G1 pass when peak scales linearly with atomic/composition fraction across two materials
    D1 = np.zeros((50, 2), dtype=np.float64)
    D2 = np.zeros((50, 2), dtype=np.float64)
    D1[10, 0] = 800.0  # Fe 80%
    D2[10, 0] = 600.0  # Fe 60% -> ratio 800/80 == 600/60 == 10.0
    roi = RoiWindow(
        roi_id="ROI_Fe",
        min_nm=float(det_wl[9]),
        max_nm=float(det_wl[11]),
        channel_indices=[9, 10, 11],
        sub_bins=[[9, 10, 11]],
        dominant_element="Fe",
        used_in_response_fit=True,
        self_absorption_prone=False,
    )
    passed, detail = engine.verify_gate_g1(
        fam_mats=["M1", "M2"],
        dictionary={"M1": D1, "M2": D2},
        comp_map={"M1": {"Fe": 80.0, "Cr": 20.0}, "M2": {"Fe": 60.0, "Cr": 40.0}},
        fam_rois=[roi],
    )
    assert passed is True
    assert detail is not None and detail["maxRelativeDifference"] < 0.05

    # Gate G1 fails when composition scaling is broken (e.g., 50% mismatch)
    D2_bad = D2.copy()
    D2_bad[10, 0] = 900.0
    passed_bad, _ = engine.verify_gate_g1(
        fam_mats=["M1", "M2"],
        dictionary={"M1": D1, "M2": D2_bad},
        comp_map={"M1": {"Fe": 80.0, "Cr": 20.0}, "M2": {"Fe": 60.0, "Cr": 40.0}},
        fam_rois=[roi],
    )
    assert passed_bad is False


def test_gpu_engine_multi_material_stats_o1_chi2_and_true_num_shots(tmp_path: Path) -> None:
    import json
    from scripts.forward_model import (
        PlasmaZoneSpec,
        apply_multi_zone_rte,
        apply_response_and_scale,
    )
    from scripts.gpu_calibration_engine import GpuLibsCalibrationEngine
    from scripts.measured_conditioning import ConditionedMaterial
    from scripts.roi_catalogue import RoiWindow

    manifest = {
        "outputProfilePath": str(tmp_path / "instrument_profile.json"),
        "nistResolvingPower": 5000.0,
        "materialFamilies": {},
    }
    mpath = tmp_path / "manifest.json"
    mpath.write_text(json.dumps(manifest), encoding="utf-8")
    engine = GpuLibsCalibrationEngine(str(mpath))

    rng = np.random.default_rng(42)
    n_ch = 80
    det_wl = np.linspace(350.0, 750.0, n_ch)
    resp = np.linspace(0.9, 1.1, n_ch)
    scale = 2.0
    nz_indices = [2, 5]
    plasma_zones_json = [
        {"zoneIndex": 0, "Te": 1.2, "Ne": 1e17, "volumeFraction": 0.7, "kAbsorption": 0.0},
        {"zoneIndex": 1, "Te": 0.6, "Ne": 5e16, "volumeFraction": 0.3, "kAbsorption": 1.2},
    ]
    zone_specs = [PlasmaZoneSpec.from_dict(z) for z in plasma_zones_json]

    rois = [
        RoiWindow(
            roi_id=f"ROI_{i}",
            min_nm=float(det_wl[c]),
            max_nm=float(det_wl[c + 2]),
            channel_indices=[c, c + 1, c + 2],
            sub_bins=[[c, c + 1, c + 2]],
            dominant_element="Fe",
            used_in_response_fit=True,
            self_absorption_prone=False,
        )
        for i, c in enumerate(range(5, 65, 5))
    ]
    union_chans = sorted({ch for r in rois for sb in r.sub_bins for ch in sb})

    dictionary = {}
    cond_map = {}
    for mat_idx, mat_name in enumerate(["SS469", "SS551"]):
        D_m = rng.uniform(500.0, 4000.0, size=(n_ch, 8))
        i_rad = apply_multi_zone_rte([D_m[:, 2], D_m[:, 5]], zone_specs)
        y_true = apply_response_and_scale(i_rad, resp, scale, "absolute")
        # Plant realistic combined measurement + 10% model noise so reduced_chi2 is O(1)
        sigma_ch = np.full(n_ch, 50.0, dtype=np.float64)
        noise_total = rng.normal(0.0, np.sqrt(sigma_ch ** 2 + (0.10 * y_true) ** 2))
        y_noisy = np.maximum(y_true + noise_total, 0.0)
        dictionary[mat_name] = D_m
        cond_map[mat_name] = ConditionedMaterial(
            wavelengths=det_wl,
            mean_spectrum=y_noisy,
            preprocessed_mean=y_noisy,
            sigma_mean=sigma_ch,
            valid_mask=np.ones(n_ch, dtype=bool),
            lit_mask=np.ones(n_ch, dtype=bool),
            dark_level=10.0,
            point_means={"p1": y_noisy},
            discarded_shots_per_point={"p1": 4},
            preprocessed_point_means={"p1": y_noisy},
            num_steady_shots=36 + mat_idx * 4,  # 36 and 40 -> total 76
        )

    stats, fit_cmp = engine.compute_family_calibration_stats(
        fam_mats=["SS469", "SS551"],
        dictionary=dictionary,
        cond_map=cond_map,
        fam_rois=rois,
        union_chans=union_chans,
        plasma_zones_json=plasma_zones_json,
        nz_indices=nz_indices,
        scale_factor_final=scale,
        spectral_response=resp,
        red_penalty=1.02,
        sharing_penalty=1.05,
        element_parity={"Fe": 0.01},
        n_params=4,
    )

    # Reduced chi2 must be O(1), NEVER O(1e8)!
    assert 0.2 <= stats["roiChi2Reduced"] <= 5.0
    assert stats["roiRSquared"] > 0.80
    assert "roiRmseRaw" in stats and "wholeSpectrumRmseRaw" in stats
    # Must aggregate across ALL materials in family, not just fam_mats[0]
    assert set(stats["perMaterialStats"].keys()) == {"SS469", "SS551"}
    assert set(fit_cmp.keys()) == {"SS469", "SS551"}
    assert stats["perMaterialStats"]["SS469"]["numSteadyShots"] == 36
    assert stats["perMaterialStats"]["SS551"]["numSteadyShots"] == 40
    # Family mean must equal average of per-material values
    mean_chi2 = 0.5 * (
        stats["perMaterialStats"]["SS469"]["roiChi2Reduced"]
        + stats["perMaterialStats"]["SS551"]["roiChi2Reduced"]
    )
    assert stats["roiChi2Reduced"] == pytest.approx(mean_chi2, abs=1e-3)


def test_gpu_engine_run_writes_true_summed_num_shots_and_line_spread(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    import json
    import scripts.gpu_calibration_engine as gce
    from scripts.roi_catalogue import RoiWindow

    n_ch = 60
    det_wl = np.linspace(350.0, 650.0, n_ch)
    peaks = [10, 20, 30, 40, 50]

    # Create 2 materials with 20 raw shots each (discardLeadingShots=4 -> 16 steady shots each -> sum = 32)
    mat_entries = []
    for mat_name, fe_pct in [("M1", 80.0), ("M2", 60.0)]:
        base = np.full(n_ch, 100.0, dtype=np.float64)
        base[:6] = 20.0
        for p in peaks:
            base[p] += 100.0 * fe_pct
        meas_csv = tmp_path / f"{mat_name}_pt1.csv"
        pd.DataFrame(np.tile(base, (20, 1)), columns=[f"{w:.3f}" for w in det_wl]).to_csv(
            meas_csv, index=False
        )

        nist_paths = {}
        for te in [0.8, 1.0]:
            for ne in [1.0e17]:
                s = np.zeros(n_ch, dtype=np.float64)
                for p in peaks:
                    s[p] = 50.0 * fe_pct * te
                ncsv = tmp_path / f"{mat_name}_Te{te}_Ne{ne:.1E}.csv"
                pd.DataFrame({"Wavelength (nm)": det_wl, "Sum(calc)": s}).to_csv(ncsv, index=False)
                nist_paths[f"{te}_{ne:.1E}"] = str(ncsv)

        mat_entries.append(
            {
                "materialName": mat_name,
                "composition": {"Fe": fe_pct, "Cr": 100.0 - fe_pct},
                "measuredCsvPaths": [str(meas_csv)],
                "nistGridCsvPaths": nist_paths,
            }
        )

    out_profile = tmp_path / "instrument_profile.json"
    manifest = {
        "outputProfilePath": str(out_profile),
        "outputRoisPath": str(tmp_path / "regionsOfInterest.json"),
        "nistResolvingPower": 5000.0,
        "instrumentFwhmNm": 0.35,
        "enableLineSpreadBroadening": True,
        "discardLeadingShots": 4,
        "numZones": 2,
        "teAnchorGridEv": [0.8, 1.0],
        "neAnchorGridCm3": [1.0e17],
        "materialFamilies": {"Steel": mat_entries},
    }
    mpath = tmp_path / "manifest.json"
    mpath.write_text(json.dumps(manifest), encoding="utf-8")

    # Mock ASD cache & ROI catalogue cleanly so test is hermetic and fast
    fake_lines = pd.DataFrame(
        {
            "element": ["Fe"] * len(peaks),
            "wavelength_nm": [float(det_wl[p]) for p in peaks],
            "Aki": [1e7] * len(peaks),
            "Ek_eV": [4.0] * len(peaks),
            "g_k": [5.0] * len(peaks),
            "accuracy": ["B"] * len(peaks),
        }
    )
    monkeypatch.setattr(gce, "build_cache", lambda *a, **kw: None)
    monkeypatch.setattr(gce, "load_lines", lambda *a, **kw: fake_lines.copy())

    import roi_catalogue

    fake_rois = [
        RoiWindow(
            roi_id=f"ROI_{i}",
            min_nm=float(det_wl[p - 1]),
            max_nm=float(det_wl[p + 1]),
            channel_indices=[p - 1, p, p + 1],
            sub_bins=[[p - 1, p, p + 1]],
            dominant_element="Fe",
            used_in_response_fit=True,
            self_absorption_prone=False,
        )
        for i, p in enumerate(peaks)
    ]
    monkeypatch.setattr(roi_catalogue, "build_catalogue", lambda *a, **kw: fake_rois)

    engine = gce.GpuLibsCalibrationEngine(str(mpath))
    engine.run()

    assert out_profile.is_file()
    prof = json.loads(out_profile.read_text(encoding="utf-8"))
    # True summed steady shots across M1 (16) + M2 (16) = 32 (NOT 60+60=120 or 8192!)
    assert prof["numShots"] == 32
    assert len(prof["lineSpreadAddSigmaNm"]) == n_ch
    assert all(s > 0.0 for s in prof["lineSpreadAddSigmaNm"])
    fam_stats = prof["materialFamilyProfiles"]["Steel"]["calibrationStats"]
    assert set(fam_stats["perMaterialStats"].keys()) == {"M1", "M2"}
    assert fam_stats["perMaterialStats"]["M1"]["numSteadyShots"] == 16
    assert fam_stats["perMaterialStats"]["M2"]["numSteadyShots"] == 16
    # Gate G1 passed cleanly for linear Fe 80% vs 60% peaks
    assert not any("COMPOSITION_SCALE_UNVERIFIED" in f for f in prof["calibrationDiagnostics"]["flags"])


def test_gpu_engine_resolves_nist_states_in_numerical_te_ne_order_and_raises_on_missing(
    tmp_path: Path,
) -> None:
    """C1: Must iterate (te_grid, ne_grid) numerically, NEVER lexicographical sorted(keys)."""
    import json
    import scripts.gpu_calibration_engine as gce

    det_wl = np.linspace(400.0, 500.0, 20)
    te_grid = [0.5, 1.0]
    ne_grid = [1.0e16, 5.0e16, 1.0e17]

    # Insert in adversarial lexicographical order where "1.0E+17" sorts before "5.0E+16"
    nist_paths = {}
    for te in te_grid:
        for ne in [1.0e17, 5.0e16, 1.0e16]:
            sig_val = 1000.0 * te + np.log10(ne)
            csv_p = tmp_path / f"M1_Te{te}_Ne{ne:.1E}.csv"
            pd.DataFrame({"Wavelength (nm)": det_wl, "Sum(calc)": np.full(len(det_wl), sig_val)}).to_csv(
                csv_p, index=False
            )
            nist_paths[f"{te}_{ne:.1E}"] = str(csv_p)

    mpath = tmp_path / "manifest.json"
    mpath.write_text(
        json.dumps({"teAnchorGridEv": te_grid, "neAnchorGridCm3": ne_grid}),
        encoding="utf-8",
    )
    engine = gce.GpuLibsCalibrationEngine(str(mpath))

    D = engine.load_material_dictionary("M1", nist_paths, det_wl, add_sigma_nm=np.zeros_like(det_wl))
    assert D.shape == (len(det_wl), len(te_grid) * len(ne_grid))

    col = 0
    for te in te_grid:
        for ne in ne_grid:
            expected_val = 1000.0 * te + np.log10(ne)
            assert np.allclose(D[:, col], expected_val, atol=1e-9), (
                f"Column {col} expected (Te={te}, Ne={ne:.1E}) -> {expected_val}, got {D[0, col]}"
            )
            col += 1

    # Missing required state must raise KeyError rather than silently shifting columns
    incomplete = dict(nist_paths)
    del incomplete["1.0_5.0E+16"]
    with pytest.raises(KeyError):
        engine.load_material_dictionary("M1", incomplete, det_wl, add_sigma_nm=np.zeros_like(det_wl))


def test_line_spread_add_sigma_disabled_by_default_and_opt_in_gated(tmp_path: Path) -> None:
    """H1: compute_line_spread_add_sigma returns zeros unless enableLineSpreadBroadening is True."""
    import json
    import scripts.gpu_calibration_engine as gce

    det_wl = np.linspace(350.0, 850.0, 30)

    # Even with instrumentFwhmNm / instrumentResolvingPower present, default must be 0.0
    mpath_default = tmp_path / "m_default.json"
    mpath_default.write_text(
        json.dumps({"instrumentFwhmNm": 0.35, "instrumentResolvingPower": 1500.0}),
        encoding="utf-8",
    )
    eng_default = gce.GpuLibsCalibrationEngine(str(mpath_default))
    sig_default = eng_default.compute_line_spread_add_sigma(det_wl, r_nist=5000.0)
    assert np.all(sig_default == 0.0)

    # Explicit opt-in enables additional Gaussian broadening
    mpath_optin = tmp_path / "m_optin.json"
    mpath_optin.write_text(
        json.dumps({"instrumentFwhmNm": 0.35, "enableLineSpreadBroadening": True}),
        encoding="utf-8",
    )
    eng_optin = gce.GpuLibsCalibrationEngine(str(mpath_optin))
    sig_optin = eng_optin.compute_line_spread_add_sigma(det_wl, r_nist=5000.0)
    assert np.all(sig_optin > 0.0)


def test_build_catalogue_uses_preprocessed_mean_and_excludes_saturated_windows() -> None:
    """H2 & C5: build_catalogue checks 5-sigma detectability on preprocessed_mean and rejects saturated windows."""
    from scripts.measured_conditioning import ConditionedMaterial
    from scripts.roi_catalogue import build_catalogue

    det_wl = np.linspace(380.0, 500.0, 241)  # 0.5 nm step
    n_ch = len(det_wl)

    # 3 candidate Al I lines:
    # 1) 400.0 nm: clean peak in preprocessed_mean, valid_mask all True -> MUST survive
    # 2) 430.0 nm: zero in preprocessed_mean, huge raw continuum in mean_spectrum -> MUST be rejected (H2)
    # 3) 460.0 nm: strong peak in preprocessed_mean, but 1 pixel saturated (valid_mask=False) -> MUST be rejected (C5)
    model_lines = pd.DataFrame(
        {
            "element": ["Al", "Al", "Al"],
            "species": ["Al I", "Al I", "Al I"],
            "wavelength_nm": [400.0, 430.0, 460.0],
            "Aki": [1.0e8, 1.0e8, 1.0e8],
            "Ek_eV": [3.5, 3.5, 3.5],
            "Ei_eV": [2.5, 2.5, 2.5],
            "g_k": [4.0, 4.0, 4.0],
            "accuracy": ["B", "B", "B"],
            "unc": [0.10, 0.10, 0.10],
            "boltzmann_ok": [True, True, True],
        }
    )

    D_ref = np.zeros((n_ch, 2), dtype=np.float64)
    y_pre = np.zeros(n_ch, dtype=np.float64)
    y_raw = np.zeros(n_ch, dtype=np.float64)
    valid_mask = np.ones(n_ch, dtype=bool)
    lit_mask = np.ones(n_ch, dtype=bool)
    lit_mask[:5] = False  # dark channels with sigma_dark=5.0

    for wl_c in [400.0, 430.0, 460.0]:
        idx = int(np.argmin(np.abs(det_wl - wl_c)))
        D_ref[idx - 1 : idx + 2, :] = 5000.0

    idx_clean = int(np.argmin(np.abs(det_wl - 400.0)))
    y_pre[idx_clean - 1 : idx_clean + 2] = 5000.0
    y_raw[idx_clean - 1 : idx_clean + 2] = 5000.0

    idx_continuum = int(np.argmin(np.abs(det_wl - 430.0)))
    y_pre[idx_continuum - 1 : idx_continuum + 2] = 0.0
    y_raw[idx_continuum - 1 : idx_continuum + 2] = 50000.0  # un-baselined continuum only

    idx_sat = int(np.argmin(np.abs(det_wl - 460.0)))
    y_pre[idx_sat - 1 : idx_sat + 2] = 5000.0
    y_raw[idx_sat - 1 : idx_sat + 2] = 65000.0
    valid_mask[idx_sat] = False  # saturated channel inside window

    cond = ConditionedMaterial(
        wavelengths=det_wl,
        mean_spectrum=y_raw,
        preprocessed_mean=y_pre,
        sigma_mean=np.full(n_ch, 5.0, dtype=np.float64),
        valid_mask=valid_mask,
        lit_mask=lit_mask,
        dark_level=0.0,
        point_means={"p1": y_raw},
        discarded_shots_per_point={"p1": 0},
        preprocessed_point_means={"p1": y_pre},
        num_steady_shots=10,
    )

    rois = build_catalogue(
        family="Aluminum",
        compositions={"AL1": {"Al": 98.0, "Si": 2.0}},
        model_lines=model_lines,
        dictionary_ref=D_ref,
        conditioned={"AL1": cond},
        detector_wavelengths=det_wl,
    )
    assert len(rois) == 1
    assert rois[0].min_nm <= 400.0 <= rois[0].max_nm
    assert not any(r.min_nm <= 430.0 <= r.max_nm for r in rois)
    assert not any(r.min_nm <= 460.0 <= r.max_nm for r in rois)


def test_gpu_engine_multi_family_roi_survival_without_head1500_or_cross_family_leak(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """C2 & C5: >1500 high-Aki V lines and Steel cond_map must NOT starve Aluminum Al/Si ROIs."""
    import json
    import scripts.gpu_calibration_engine as gce

    det_wl = np.linspace(380.0, 580.0, 401)  # 0.5 nm step
    n_ch = len(det_wl)
    fe_wls = [400.0, 440.0, 480.0, 520.0]
    al_wls = [415.0, 460.0, 505.0]
    si_wls = [430.0, 540.0]

    def _make_mat_entry(mat_name: str, comp: dict, peak_wls: list) -> dict:
        spec = np.zeros(n_ch, dtype=np.float64)
        for w in peak_wls:
            idx = int(np.argmin(np.abs(det_wl - w)))
            spec[idx - 1 : idx + 2] = 8000.0
        spec[:10] = 5.0  # dark
        meas_csv = tmp_path / f"{mat_name}_meas.csv"
        pd.DataFrame(np.tile(spec, (12, 1)), columns=[f"{w:.3f}" for w in det_wl]).to_csv(
            meas_csv, index=False
        )
        nist_paths = {}
        for te in [0.8, 1.0]:
            for ne in [1.0e17]:
                ncsv = tmp_path / f"{mat_name}_Te{te}_Ne{ne:.1E}.csv"
                pd.DataFrame({"Wavelength (nm)": det_wl, "Sum(calc)": spec * 0.5 * te}).to_csv(
                    ncsv, index=False
                )
                nist_paths[f"{te}_{ne:.1E}"] = str(ncsv)
        return {
            "materialName": mat_name,
            "composition": comp,
            "measuredCsvPaths": [str(meas_csv)],
            "nistGridCsvPaths": nist_paths,
        }

    # Build >1550 high-Aki trace V lines that would starve Al/Si under .head(1500)
    rows = []
    for k in range(1550):
        rows.append(
            {
                "element": "V",
                "species": "V I",
                "wavelength_nm": 385.0 + (k % 180) * 1.0,
                "Aki": 1.0e9 + k,
                "Ek_eV": 4.0,
                "Ei_eV": 2.5,
                "g_k": 6.0,
                "accuracy": "B",
                "boltzmann_ok": True,
            }
        )
    for w in fe_wls:
        rows.append({"element": "Fe", "species": "Fe I", "wavelength_nm": w, "Aki": 5.0e7, "Ek_eV": 4.0, "Ei_eV": 2.5, "g_k": 5.0, "accuracy": "B", "boltzmann_ok": True})
    for w in al_wls:
        rows.append({"element": "Al", "species": "Al I", "wavelength_nm": w, "Aki": 1.0e6, "Ek_eV": 3.5, "Ei_eV": 2.5, "g_k": 4.0, "accuracy": "B", "boltzmann_ok": True})
    for w in si_wls:
        rows.append({"element": "Si", "species": "Si I", "wavelength_nm": w, "Aki": 1.0e6, "Ek_eV": 4.5, "Ei_eV": 2.5, "g_k": 4.0, "accuracy": "B", "boltzmann_ok": True})
    fake_lines = pd.DataFrame(rows)

    out_profile = tmp_path / "instrument_profile.json"
    out_rois = tmp_path / "regionsOfInterest.json"
    manifest = {
        "outputProfilePath": str(out_profile),
        "outputRoisPath": str(out_rois),
        "nistResolvingPower": 3000.0,
        "discardLeadingShots": 2,
        "numZones": 2,
        "teAnchorGridEv": [0.8, 1.0],
        "neAnchorGridCm3": [1.0e17],
        "materialFamilies": {
            "Steel": [_make_mat_entry("ST1", {"Fe": 99.0, "V": 1.0}, fe_wls)],
            "Aluminum": [_make_mat_entry("AL1", {"Al": 90.0, "Si": 10.0}, al_wls + si_wls)],
        },
    }
    mpath = tmp_path / "manifest.json"
    mpath.write_text(json.dumps(manifest), encoding="utf-8")

    monkeypatch.setattr(gce, "build_cache", lambda *a, **kw: None)
    monkeypatch.setattr(gce, "load_lines", lambda *a, **kw: fake_lines.copy())

    engine = gce.GpuLibsCalibrationEngine(str(mpath))
    engine.run()

    assert out_rois.is_file()
    rois_json = json.loads(out_rois.read_text(encoding="utf-8"))
    al_fam = rois_json["materialFamilyRois"]["Aluminum"]
    al_elements = {r["dominantElement"] for r in al_fam["rois"]}
    assert "Al" in al_elements, f"Al ROIs starved or leaked out! Got: {al_elements}"
    assert "Si" in al_elements, f"Si ROIs starved or leaked out! Got: {al_elements}"


def test_compute_family_calibration_stats_masks_saturated_channels(tmp_path: Path) -> None:
    """C5: compute_family_calibration_stats must intersect roi_mask and lit_mask with valid_mask."""
    import json
    from scripts.gpu_calibration_engine import GpuLibsCalibrationEngine
    from scripts.measured_conditioning import ConditionedMaterial
    from scripts.roi_catalogue import RoiWindow

    mpath = tmp_path / "manifest.json"
    mpath.write_text(json.dumps({"materialFamilies": {}}), encoding="utf-8")
    engine = GpuLibsCalibrationEngine(str(mpath))

    n_ch = 40
    det_wl = np.linspace(400.0, 500.0, n_ch)
    D = np.zeros((n_ch, 1), dtype=np.float64)
    D[10:30, 0] = np.linspace(100.0, 1000.0, 20)
    resp = np.ones(n_ch, dtype=np.float64)

    y_meas = D[:, 0].copy()
    valid_mask = np.ones(n_ch, dtype=bool)
    lit_mask = np.ones(n_ch, dtype=bool)
    # Corrupt one saturated channel inside both ROI and lit masks
    y_meas[15] = 1.0e7
    valid_mask[15] = False

    cond = ConditionedMaterial(
        wavelengths=det_wl,
        mean_spectrum=y_meas,
        preprocessed_mean=y_meas,
        sigma_mean=np.full(n_ch, 5.0),
        valid_mask=valid_mask,
        lit_mask=lit_mask,
        dark_level=0.0,
        point_means={"p1": y_meas},
        discarded_shots_per_point={"p1": 0},
        preprocessed_point_means={"p1": y_meas},
        num_steady_shots=10,
    )
    roi = RoiWindow(
        roi_id="R1",
        min_nm=float(det_wl[10]),
        max_nm=float(det_wl[20]),
        channel_indices=list(range(10, 21)),
        sub_bins=[list(range(10, 14)), list(range(16, 20))],
        dominant_element="Fe",
        used_in_response_fit=True,
        self_absorption_prone=False,
    )
    zones = [{"zoneIndex": 0, "Te": 1.0, "Ne": 1e17, "volumeFraction": 1.0, "kAbsorption": 0.0}]

    stats, _ = engine.compute_family_calibration_stats(
        fam_mats=["M1"],
        dictionary={"M1": D},
        cond_map={"M1": cond},
        fam_rois=[roi],
        union_chans=list(range(10, 21)),
        plasma_zones_json=zones,
        nz_indices=[0],
        scale_factor_final=1.0,
        spectral_response=resp,
        red_penalty=1.0,
        sharing_penalty=1.0,
        element_parity={"Fe": 0.0},
        n_params=1,
    )
    assert stats["roiRSquared"] == pytest.approx(1.0, abs=1e-6)
    assert stats["wholeSpectrumRSquared"] == pytest.approx(1.0, abs=1e-6)
    assert stats["roiRmseRaw"] == pytest.approx(0.0, abs=1e-6)


def test_structuring_element_alignment_and_manifest_resolution(tmp_path: Path) -> None:
    """H3: Default SE=25 without 55 floor, and condition_material checks baselineCorrectionParams first."""
    import json
    import scripts.gpu_calibration_engine as gce
    from scripts.measured_conditioning import apply_morphological_baseline, condition_material

    assert gce.DEFAULT_MORPHOLOGICAL_STRUCT_SIZE == 25

    mpath_def = tmp_path / "m_def.json"
    mpath_def.write_text(json.dumps({}), encoding="utf-8")
    assert gce.GpuLibsCalibrationEngine(str(mpath_def)).struct_size == 25

    mpath_small = tmp_path / "m_small.json"
    mpath_small.write_text(
        json.dumps({"baselineCorrectionParams": {"structuringElementSize": 15}}),
        encoding="utf-8",
    )
    assert gce.GpuLibsCalibrationEngine(str(mpath_small)).struct_size == 15

    # Construct a spectrum with a medium-width hump (width ~17 channels) where SE=7 vs SE=45 differ strongly
    n_ch = 120
    wl = np.linspace(350.0, 470.0, n_ch)
    x = np.arange(n_ch, dtype=np.float64)
    spec = 500.0 * np.exp(-0.5 * ((x - 60.0) / 8.0) ** 2) + 50.0
    spec[:10] = 0.0
    csv_p = tmp_path / "pt1.csv"
    pd.DataFrame(np.tile(spec, (8, 1)), columns=[f"{w:.3f}" for w in wl]).to_csv(csv_p, index=False)

    cond_nested = condition_material(
        [csv_p],
        manifest={
            "discardLeadingShots": 0,
            "baselineCorrectionParams": {"structuringElementSize": 7},
            "structuringElementSize": 45,
        },
    )
    expected_7 = apply_morphological_baseline(cond_nested.mean_spectrum, se_size=7)
    expected_45 = apply_morphological_baseline(cond_nested.mean_spectrum, se_size=45)
    assert not np.allclose(expected_7, expected_45, atol=1.0)
    np.testing.assert_allclose(cond_nested.preprocessed_mean, expected_7, rtol=1e-12, atol=1e-12)


def test_upsert_family_profile_persists_union_channel_indices(tmp_path: Path) -> None:
    """H4: upsert_family_profile must persist unionChannelIndices in regionsOfInterest.json."""
    import json
    import scripts.gpu_calibration_engine as gce

    out_prof = tmp_path / "instrument_profile.json"
    out_rois = tmp_path / "regionsOfInterest.json"
    mpath = tmp_path / "manifest.json"
    mpath.write_text(
        json.dumps(
            {
                "outputProfilePath": str(out_prof),
                "outputRoisPath": str(out_rois),
                "materialFamilies": {"Steel": [{"materialName": "469"}]},
            }
        ),
        encoding="utf-8",
    )
    engine = gce.GpuLibsCalibrationEngine(str(mpath))
    fam_rois = [
        {"roiId": "R1", "channelIndices": [10, 11, 12], "subBins": [[10, 11], [11, 12]]},
        {"roiId": "R2", "channelIndices": [20, 21], "subBins": [[20, 21]]},
    ]
    engine.upsert_family_profile("Steel", {"scaleFactor": 1.0}, fam_rois)

    rois_data = json.loads(out_rois.read_text(encoding="utf-8"))
    assert rois_data["materialFamilyRois"]["Steel"]["unionChannelIndices"] == [10, 11, 12, 20, 21]

    # Also verify legacy 'families' schema branch receives unionChannelIndices
    out_rois.write_text(json.dumps({"schemaVersion": "2.0", "families": {}}), encoding="utf-8")
    engine.upsert_family_profile("Steel", {"scaleFactor": 1.0}, fam_rois)
    rois_legacy = json.loads(out_rois.read_text(encoding="utf-8"))
    assert rois_legacy["families"]["Steel"]["unionChannelIndices"] == [10, 11, 12, 20, 21]


def test_scan_absorption_channel_exact_rte_and_updated_weights_parity() -> None:
    """C4: Channel-exact RTE scan_absorption recovers planted k, updates weights, and matches apply_multi_zone_rte < 1e-12 for 2- and 3-zone profiles."""
    from scripts.forward_model import (
        PlasmaZoneSpec,
        apply_multi_zone_rte,
        apply_response_and_scale,
    )
    from scripts.roi_joint_calibration import scan_absorption

    rng = np.random.default_rng(777)
    n_ch = 80
    resp = np.linspace(0.7, 1.4, n_ch, dtype=np.float64)
    sub_bins = [list(range(i, i + 4)) for i in range(0, 60, 4)]  # 15 sub-bins of 4 channels each
    prone_mask = [b % 2 == 0 for b in range(len(sub_bins))]

    for num_zones in (2, 3):
        n_states = 4
        D = np.zeros((n_ch, n_states), dtype=np.float64)
        # Plant sharp peaks inside each 4-channel sub-bin so sub-bin mean d_tilde would have strong Jensen bias
        for sb in sub_bins:
            D[sb[0], :] = rng.uniform(5.0, 20.0, size=n_states)
            D[sb[1], :] = rng.uniform(800.0, 4000.0, size=n_states)  # sharp peak pixel
            D[sb[2], :] = rng.uniform(10.0, 40.0, size=n_states)
            D[sb[3], :] = rng.uniform(5.0, 20.0, size=n_states)
        # Distinct cool-zone peak spectrum (state 0 = coolest)
        D[:, 0] = rng.uniform(10.0, 100.0, size=n_ch)
        for b_idx, sb in enumerate(sub_bins):
            D[sb[1], 0] = 3500.0 if b_idx % 2 == 0 else 400.0

        k_planted = 3.2
        if num_zones == 2:
            # Hot state 3 (Te=1.4), cool state 0 (Te=0.6)
            true_weights = np.array([0.8, 0.0, 0.0, 1.6], dtype=np.float64)
            init_weights = np.array([1.2, 0.0, 0.0, 0.7], dtype=np.float64)
            active_specs = [
                (3, 1.4, 1e17, 0.0),
                (0, 0.6, 5e16, k_planted),
            ]
        else:
            # Two hot states (3 and 2) scaled together by V_hot, plus cool state 0
            true_weights = np.array([0.9, 0.0, 0.5, 1.5], dtype=np.float64)
            # Initial weights have same hot ratio (0.5:1.5 == 1:3) but mismatched global V_hot vs V_cool
            init_weights = np.array([1.5, 0.0, 0.25, 0.75], dtype=np.float64)
            active_specs = [
                (3, 1.4, 1e17, 0.0),
                (2, 1.0, 1e17, 0.0),
                (0, 0.6, 5e16, k_planted),
            ]

        v_tot_true = float(np.sum(true_weights))
        zones_true = [
            PlasmaZoneSpec(
                te=te, ne=ne, volume_fraction=float(true_weights[idx] / v_tot_true), k_absorption=kz
            )
            for idx, te, ne, kz in active_specs
        ]
        i_rad_true = apply_multi_zone_rte([D[:, idx] for idx, _, _, _ in active_specs], zones_true)
        y_full_true = apply_response_and_scale(i_rad_true, resp, v_tot_true, emission_scale="absolute")
        y_sb = np.array([float(np.sum(y_full_true[sb])) for sb in sub_bins], dtype=np.float64)
        sig_sb = np.full(len(sub_bins), 5.0, dtype=np.float64)

        # Verify default return_weights=False keeps 2-tuple backward compatibility
        res_2 = scan_absorption(
            dictionary={"M1": D},
            sub_bins=sub_bins,
            spectral_response=resp,
            y_dict={"M1": y_sb},
            sigma_dict={"M1": sig_sb},
            reduced_weights=init_weights,
            coolest_state_idx=0,
            prone_mask=prone_mask,
        )
        assert len(res_2) == 2

        best_k, k_flag, updated_w = scan_absorption(
            dictionary={"M1": D},
            sub_bins=sub_bins,
            spectral_response=resp,
            y_dict={"M1": y_sb},
            sigma_dict={"M1": sig_sb},
            reduced_weights=init_weights,
            coolest_state_idx=0,
            prone_mask=prone_mask,
            return_weights=True,
        )
        assert best_k == pytest.approx(k_planted, abs=1e-9)
        assert k_flag is False
        assert updated_w.shape == init_weights.shape
        assert np.all(updated_w >= 0.0)
        np.testing.assert_allclose(updated_w, true_weights, rtol=1e-9, atol=1e-9)

        # Verify apply_multi_zone_rte using updated_w reproduces fitted prediction within 1e-12
        v_tot_est = float(np.sum(updated_w))
        zones_est = [
            PlasmaZoneSpec(
                te=te,
                ne=ne,
                volume_fraction=float(updated_w[idx] / v_tot_est),
                k_absorption=(best_k if idx == 0 else 0.0),
            )
            for idx, te, ne, _ in active_specs
        ]
        i_rad_est = apply_multi_zone_rte([D[:, idx] for idx, _, _, _ in active_specs], zones_est)
        y_full_est = apply_response_and_scale(i_rad_est, resp, v_tot_est, emission_scale="absolute")
        y_sb_est = np.array([float(np.sum(y_full_est[sb])) for sb in sub_bins], dtype=np.float64)
        np.testing.assert_allclose(y_sb_est, y_sb, rtol=1e-12, atol=1e-12)


def test_scan_absorption_rejects_zero_volume_absorbing_ghost_zone() -> None:
    """C4: Trial k > 0 where NNLS collapses V_cool <= 0 or vf < 1e-3 must be skipped (no ghost zone)."""
    from scripts.roi_joint_calibration import scan_absorption

    rng = np.random.default_rng(909)
    n_ch = 60
    resp = np.ones(n_ch, dtype=np.float64)
    sub_bins = [list(range(i, i + 4)) for i in range(0, 48, 4)]
    prone_mask = [True] * len(sub_bins)

    D = np.zeros((n_ch, 2), dtype=np.float64)
    D[:, 0] = rng.uniform(100.0, 2000.0, size=n_ch)  # cool zone
    D[:, 1] = rng.uniform(500.0, 3000.0, size=n_ch)  # hot zone
    d_tilde_cool = D[:, 0] / np.max(D[:, 0])

    # Plant pure attenuated hot core with ZERO cool-zone emission (V_cool == 0.0)!
    # Unguarded NNLS would fit k=4.5 with V_cool=0.0 (unphysical zero-volume absorbing ghost zone).
    y_full = D[:, 1] * np.exp(-4.5 * d_tilde_cool)
    y_sb = np.array([float(np.sum(y_full[sb])) for sb in sub_bins], dtype=np.float64)
    sig_sb = np.full(len(sub_bins), 5.0, dtype=np.float64)
    init_w = np.array([0.5, 0.5], dtype=np.float64)

    best_k, _, updated_w = scan_absorption(
        dictionary={"M1": D},
        sub_bins=sub_bins,
        spectral_response=resp,
        y_dict={"M1": y_sb},
        sigma_dict={"M1": sig_sb},
        reduced_weights=init_w,
        coolest_state_idx=0,
        prone_mask=prone_mask,
        return_weights=True,
    )
    # Either best_k fell back to 0.0, or if any valid k>0 passed, BOTH zone volume fractions MUST be >= 1e-3
    if best_k > 0.0:
        vfs = updated_w / float(np.sum(updated_w))
        assert vfs[0] >= 1e-3 and vfs[1] >= 1e-3
    else:
        assert best_k == 0.0
        np.testing.assert_allclose(updated_w, init_w)


def test_compute_family_scales_uses_sigma_dict_and_multi_zone_rte() -> None:
    """C3: compute_family_scales_and_sharing_penalty respects sigma_dict (1/sigma^2) and k_absorption > 0 RTE."""
    from scripts.forward_model import (
        PlasmaZoneSpec,
        apply_multi_zone_rte,
        apply_response_and_scale,
    )
    from scripts.roi_joint_calibration import compute_family_scales_and_sharing_penalty

    # Part A: sigma_dict inverse-variance weighting suppresses high-sigma outlier bin
    A_m = np.array([[10.0, 0.0], [10.0, 0.0], [20.0, 0.0]], dtype=np.float64)
    w_red = np.array([1.0, 0.0], dtype=np.float64)
    y_m1 = np.array([1.0e6, 20.0, 40.0], dtype=np.float64)  # true alpha = 2.0 on bins 1, 2
    y_m2 = np.array([1.0e6, 30.0, 60.0], dtype=np.float64)  # true alpha = 3.0 on bins 1, 2
    sig_m = np.array([1.0e6, 0.1, 0.1], dtype=np.float64)

    res_w = compute_family_scales_and_sharing_penalty(
        {"M1": A_m, "M2": A_m},
        {"M1": y_m1, "M2": y_m2},
        w_red,
        sigma_dict={"M1": sig_m, "M2": sig_m},
    )
    assert res_w["materialScaleFactors"]["M1"] == pytest.approx(2.0, abs=1e-4)
    assert res_w["materialScaleFactors"]["M2"] == pytest.approx(3.0, abs=1e-4)
    assert res_w["scaleFactor"] == pytest.approx(2.5, abs=1e-4)

    # Part B: k_absorption > 0 evaluates channel-exact apply_multi_zone_rte
    rng = np.random.default_rng(314)
    n_ch = 40
    resp = np.linspace(0.8, 1.3, n_ch, dtype=np.float64)
    sub_bins = [list(range(i, i + 4)) for i in range(0, 32, 4)]
    te_grid = [0.6, 1.2]
    ne_grid = [1.0e17]
    D1 = rng.uniform(100.0, 2000.0, size=(n_ch, 2))
    D2 = rng.uniform(100.0, 2000.0, size=(n_ch, 2))
    w_zones = np.array([0.4, 0.6], dtype=np.float64)  # state 0 cool (0.6 eV), state 1 hot (1.2 eV)
    k_abs = 3.0

    def _attenuated_sb(D_mat: np.ndarray, alpha: float) -> np.ndarray:
        zones = [
            PlasmaZoneSpec(te=1.2, ne=1e17, volume_fraction=0.6, k_absorption=0.0),
            PlasmaZoneSpec(te=0.6, ne=1e17, volume_fraction=0.4, k_absorption=k_abs),
        ]
        i_rad = apply_multi_zone_rte([D_mat[:, 1], D_mat[:, 0]], zones)
        y_full = apply_response_and_scale(i_rad, resp, alpha * 1.0, emission_scale="absolute")
        return np.array([float(np.sum(y_full[sb])) for sb in sub_bins], dtype=np.float64)

    y1_att = _attenuated_sb(D1, 1.8)
    y2_att = _attenuated_sb(D2, 2.4)
    A1 = np.array([np.sum(D1[sb, :] * resp[sb, None], axis=0) for sb in sub_bins])
    A2 = np.array([np.sum(D2[sb, :] * resp[sb, None], axis=0) for sb in sub_bins])
    sig_att = np.full(len(sub_bins), 5.0, dtype=np.float64)

    res_att = compute_family_scales_and_sharing_penalty(
        {"M1": A1, "M2": A2},
        {"M1": y1_att, "M2": y2_att},
        w_zones,
        sigma_dict={"M1": sig_att, "M2": sig_att},
        k_absorption=k_abs,
        dictionary={"M1": D1, "M2": D2},
        sub_bins=sub_bins,
        spectral_response=resp,
        nz_indices=[1, 0],
        te_grid=te_grid,
        ne_grid=ne_grid,
    )
    assert res_att["materialScaleFactors"]["M1"] == pytest.approx(1.8, abs=1e-6)
    assert res_att["materialScaleFactors"]["M2"] == pytest.approx(2.4, abs=1e-6)
    assert res_att["scaleFactor"] == pytest.approx(2.1, abs=1e-6)


def test_compute_element_parity_raises_without_state_mapping() -> None:
    """M2: compute_element_parity must raise ValueError rather than silently indexing z_idx % n_cols."""
    from scripts.roi_catalogue import RoiWindow
    from scripts.roi_joint_calibration import compute_element_parity

    n_ch = 20
    D = np.ones((n_ch, 4), dtype=np.float64) * 100.0
    roi = RoiWindow(
        roi_id="R1",
        min_nm=400.0,
        max_nm=405.0,
        channel_indices=[5, 6, 7],
        sub_bins=[[5, 6, 7]],
        self_absorption_prone=False,
        used_in_response_fit=True,
        dominant_element="Fe",
    )
    zones = [{"zoneIndex": 0, "Te": 1.0, "Ne": 1e17, "volumeFraction": 1.0, "kAbsorption": 0.0}]

    with pytest.raises(ValueError, match="state mapping|stateIndex|nz_indices|te_grid"):
        compute_element_parity(
            rois=[roi],
            dictionary={"M1": D},
            conditioned={"M1": np.ones(n_ch) * 100.0},
            plasma_zones=zones,
            scale_factor=1.0,
        )


def test_verify_gate_g1_checks_all_material_pairs_in_family(tmp_path: Path) -> None:
    """M3: verify_gate_g1 must check ALL material pairs (m1, m2), catching a corrupted 3rd material."""
    import json
    from scripts.gpu_calibration_engine import GpuLibsCalibrationEngine
    from scripts.roi_catalogue import RoiWindow

    mpath = tmp_path / "manifest.json"
    mpath.write_text(json.dumps({"materialFamilies": {}}), encoding="utf-8")
    engine = GpuLibsCalibrationEngine(str(mpath))

    roi = RoiWindow(
        roi_id="R_Fe",
        min_nm=400.0,
        max_nm=405.0,
        channel_indices=[4, 5, 6],
        sub_bins=[[4, 5, 6]],
        dominant_element="Fe",
        used_in_response_fit=True,
        self_absorption_prone=False,
    )
    comp_map = {"M1": {"Fe": 80.0}, "M2": {"Fe": 60.0}, "M3": {"Fe": 40.0}}
    D1 = np.zeros((20, 2), dtype=np.float64); D1[5, :] = 800.0
    D2 = np.zeros((20, 2), dtype=np.float64); D2[5, :] = 600.0  # (M1, M2) passes 800/600 == 80/60
    D3_bad = np.zeros((20, 2), dtype=np.float64); D3_bad[5, :] = 800.0  # (M1, M3) fails!
    D3_good = np.zeros((20, 2), dtype=np.float64); D3_good[5, :] = 400.0

    passed_bad, info_bad = engine.verify_gate_g1(
        ["M1", "M2", "M3"], {"M1": D1, "M2": D2, "M3": D3_bad}, comp_map, [roi]
    )
    assert passed_bad is False
    assert info_bad is not None and len(info_bad.get("pairs", [])) == 3

    passed_good, info_good = engine.verify_gate_g1(
        ["M1", "M2", "M3"], {"M1": D1, "M2": D2, "M3": D3_good}, comp_map, [roi]
    )
    assert passed_good is True
    assert info_good is not None and len(info_good.get("pairs", [])) == 3


def test_global_gate_g1_ignores_single_material_families_when_multi_material_passes(tmp_path: Path) -> None:
    """M3: Single-material families (pairwise G1 N/A) must NOT trigger a false global Gate G1 failure."""
    import json
    from scripts.gpu_calibration_engine import GpuLibsCalibrationEngine
    from scripts.roi_catalogue import RoiWindow

    mpath = tmp_path / "manifest.json"
    mpath.write_text(json.dumps({"materialFamilies": {}}), encoding="utf-8")
    engine = GpuLibsCalibrationEngine(str(mpath))

    roi_fe = RoiWindow(
        roi_id="R_Fe", min_nm=400.0, max_nm=405.0, channel_indices=[4, 5, 6], sub_bins=[[4, 5, 6]],
        dominant_element="Fe", used_in_response_fit=True, self_absorption_prone=False,
    )
    roi_cu = RoiWindow(
        roi_id="R_Cu", min_nm=500.0, max_nm=505.0, channel_indices=[10, 11, 12], sub_bins=[[10, 11, 12]],
        dominant_element="Cu", used_in_response_fit=True, self_absorption_prone=False,
    )
    D1 = np.zeros((20, 2), dtype=np.float64); D1[5, :] = 800.0
    D2 = np.zeros((20, 2), dtype=np.float64); D2[5, :] = 600.0
    Db = np.zeros((20, 2), dtype=np.float64); Db[11, :] = 900.0

    global_ok, fam_status = engine.verify_global_gate_g1(
        families_dict={"Steel": [], "Bronze": []},
        fam_map={"M1": "Steel", "M2": "Steel", "B1": "Bronze"},
        dictionary={"M1": D1, "M2": D2, "B1": Db},
        comp_map={"M1": {"Fe": 80.0}, "M2": {"Fe": 60.0}, "B1": {"Cu": 90.0}},
        rois_by_family={"Steel": [roi_fe], "Bronze": [roi_cu]},
    )
    assert global_ok is True
    assert fam_status["Steel"] is True
    assert fam_status["Bronze"] is None  # single-material family -> N/A

    # If ONLY single-material families exist, no pairwise check was performed -> global_ok is False
    global_only_single, fam_only_single = engine.verify_global_gate_g1(
        families_dict={"Bronze": []},
        fam_map={"B1": "Bronze"},
        dictionary={"B1": Db},
        comp_map={"B1": {"Cu": 90.0}},
        rois_by_family={"Bronze": [roi_cu]},
    )
    assert global_only_single is False
    assert fam_only_single["Bronze"] is None


def test_evaluate_diagnostics_and_run_joint_solver_default_composition_scale_unverified() -> None:
    """M3: evaluate_diagnostics and run_joint_solver default composition_scale_verified=False."""
    from scripts.measured_conditioning import ConditionedMaterial
    from scripts.roi_catalogue import RoiWindow
    from scripts.roi_joint_calibration import evaluate_diagnostics, run_joint_solver

    diag_default = evaluate_diagnostics(
        weights_by_mat={"M1": np.array([0.0, 100.0, 0.0])},
        te_grid=[0.5, 1.0, 1.5],
        ne_grid=[1e17],
        response_curve=np.array([1.0, 2.0]),
        response_restart_spread=0.01,
        iterations=5,
        gap_flag=False,
        reduced_chi2_by_mat={"M1": 1.0},
    )
    assert "COMPOSITION_SCALE_UNVERIFIED" in diag_default["flags"]

    diag_verified = evaluate_diagnostics(
        weights_by_mat={"M1": np.array([0.0, 100.0, 0.0])},
        te_grid=[0.5, 1.0, 1.5],
        ne_grid=[1e17],
        response_curve=np.array([1.0, 2.0]),
        response_restart_spread=0.01,
        iterations=5,
        gap_flag=False,
        reduced_chi2_by_mat={"M1": 1.0},
        composition_scale_verified=True,
    )
    assert "COMPOSITION_SCALE_UNVERIFIED" not in diag_verified["flags"]

    # run_joint_solver default must also flag COMPOSITION_SCALE_UNVERIFIED unless passed True
    n_ch = 60
    det_wl = np.linspace(380.0, 620.0, n_ch)
    D = np.zeros((n_ch, 3), dtype=np.float64)
    rois = []
    for i, c in enumerate([10, 20, 30, 40, 50]):
        D[c - 1 : c + 2, 1] = 1000.0
        rois.append(
            RoiWindow(
                roi_id=f"R{i}", min_nm=float(det_wl[c - 1]), max_nm=float(det_wl[c + 1]),
                channel_indices=[c - 1, c, c + 1], sub_bins=[[c - 1, c, c + 1]],
                dominant_element="Fe", used_in_response_fit=True, self_absorption_prone=False,
            )
        )
    y = D[:, 1].copy()
    cond = ConditionedMaterial(
        wavelengths=det_wl, mean_spectrum=y, preprocessed_mean=y,
        sigma_mean=np.full(n_ch, 1.0), valid_mask=np.ones(n_ch, dtype=bool),
        lit_mask=np.ones(n_ch, dtype=bool), dark_level=0.0,
        point_means={"p1": y}, discarded_shots_per_point={"p1": 0},
        preprocessed_point_means={"p1": y}, num_steady_shots=10,
    )
    res_unverified = run_joint_solver(
        rois=rois, dictionary={"M1": D}, conditioned={"M1": cond},
        detector_wavelengths=det_wl, material_families={"M1": "Steel"},
        te_grid=[0.5, 1.0, 1.5], ne_grid=[1e17],
    )
    assert "COMPOSITION_SCALE_UNVERIFIED" in res_unverified.diagnostics_flags

    res_verified = run_joint_solver(
        rois=rois, dictionary={"M1": D}, conditioned={"M1": cond},
        detector_wavelengths=det_wl, material_families={"M1": "Steel"},
        te_grid=[0.5, 1.0, 1.5], ne_grid=[1e17],
        composition_scale_verified=True,
    )
    assert "COMPOSITION_SCALE_UNVERIFIED" not in res_verified.diagnostics_flags







