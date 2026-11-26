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
