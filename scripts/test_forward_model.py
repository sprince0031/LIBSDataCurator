"""Unit tests for absolute-scale forward model and Java parity fixture generation (R4, AC5)."""

import json
from pathlib import Path
import numpy as np
import pytest

from scripts.forward_model import (
    combine_zones,
    apply_response_and_scale,
    composition_scale_check,
    NIST_UNIT_SCALE,
    PlasmaZoneSpec,
)
from scripts.spectral_resampling import resample_to_detector


def test_combine_zones_single_zone():
    d0 = np.array([100.0, 500.0, 200.0])
    zones = [PlasmaZoneSpec(te=1.0, ne=1e17, volume_fraction=1.0, k_absorption=0.0)]
    
    # 1 zone: I_0 = V_0 * d_0
    out = combine_zones([d0], zones)
    np.testing.assert_allclose(out, d0)


def test_combine_zones_two_and_three_zones_hand_computed():
    d0 = np.array([100.0, 200.0, 400.0])  # max = 400.0
    d1 = np.array([50.0, 150.0, 300.0])   # max = 300.0, d_tilde_1 = [50/300, 150/300, 300/300] = [1/6, 1/2, 1.0]
    
    z0 = PlasmaZoneSpec(te=1.0, ne=1e17, volume_fraction=0.4, k_absorption=0.0)
    z1 = PlasmaZoneSpec(te=0.7, ne=5e16, volume_fraction=0.6, k_absorption=1.2)
    
    # Hand calculation for 2 zones:
    # I_0 = 0.4 * d0 = [40.0, 80.0, 160.0]
    # d_tilde_1 = [50/300, 150/300, 1.0]
    # opacity = exp(-1.2 * d_tilde_1) = exp(-1.2 * [1/6, 1/2, 1.0]) = [exp(-0.2), exp(-0.6), exp(-1.2)]
    # I_1 = I_0 * opacity + 0.6 * d1
    expected_I1 = (0.4 * d0) * np.exp(-1.2 * (d1 / 300.0)) + 0.6 * d1
    
    out2 = combine_zones([d0, d1], [z0, z1])
    np.testing.assert_allclose(out2, expected_I1, rtol=1e-12)
    
    # 3 zones:
    d2 = np.array([20.0, 80.0, 100.0])
    z2 = PlasmaZoneSpec(te=0.5, ne=1e16, volume_fraction=0.3, k_absorption=0.5)
    expected_I2 = expected_I1 * np.exp(-0.5 * (d2 / 100.0)) + 0.3 * d2
    
    out3 = combine_zones([d0, d1, d2], [z0, z1, z2])
    np.testing.assert_allclose(out3, expected_I2, rtol=1e-12)


def test_combine_zones_zero_absorption_reduces_to_linear_mixture():
    d0 = np.array([100.0, 200.0, 300.0])
    d1 = np.array([400.0, 100.0, 50.0])
    z0 = PlasmaZoneSpec(te=1.1, ne=1e17, volume_fraction=0.3, k_absorption=0.0)
    z1 = PlasmaZoneSpec(te=0.8, ne=5e16, volume_fraction=0.7, k_absorption=0.0)
    
    out = combine_zones([d0, d1], [z0, z1])
    expected = 0.3 * d0 + 0.7 * d1
    np.testing.assert_allclose(out, expected, rtol=1e-12)


def test_apply_response_and_scale_both_modes():
    spectrum = np.array([10.0, 50.0, 100.0])
    response = np.array([1.2, 1.0, 0.8])
    scale_factor = 2.5
    
    # Absolute mode: scaleFactor * R * I
    out_abs = apply_response_and_scale(
        spectrum, response, scale_factor=scale_factor, emission_scale="absolute"
    )
    expected_abs = 2.5 * (np.array([12.0, 50.0, 80.0]))
    np.testing.assert_allclose(out_abs, expected_abs, rtol=1e-12)
    
    # Max-normalised mode: scaleFactor * (R * I) / max(R * I)
    out_norm = apply_response_and_scale(
        spectrum, response, scale_factor=scale_factor, emission_scale="max_normalised"
    )
    r_i = np.array([12.0, 50.0, 80.0])
    expected_norm = 2.5 * (r_i / 80.0)
    np.testing.assert_allclose(out_norm, expected_norm, rtol=1e-12)


def test_composition_scale_check():
    # Pass scenario: two materials where peak / (species_fraction * atomic_fraction) matches within 5%
    # Material 1: peak = 1000.0, species_fraction = 0.8, at_frac = 0.5 -> ratio = 1000 / (0.8 * 0.5) = 2500
    # Material 2: peak = 756.0, species_fraction = 0.84, at_frac = 0.36 -> ratio = 756 / 0.3024 = 2500
    res_pass = composition_scale_check(
        peak_1=1000.0, species_frac_1=0.8, at_frac_1=0.5,
        peak_2=756.0, species_frac_2=0.84, at_frac_2=0.36,
    )
    assert res_pass["passed"]
    assert res_pass["maxRelativeDifference"] < 0.05
    
    # Fail scenario: artificial 25% discrepancy
    res_fail = composition_scale_check(
        peak_1=1000.0, species_frac_1=0.8, at_frac_1=0.5,
        peak_2=950.0, species_frac_2=0.84, at_frac_2=0.36,
    )
    assert not res_fail["passed"]
    assert res_fail["maxRelativeDifference"] > 0.05


def test_generate_forward_model_parity_fixture():
    """Generates the shared fixture JSON for SpectrumUtilsTest Java parity verification (AC5)."""
    fixture_path = Path(__file__).resolve().parent.parent / "src" / "test" / "resources" / "forward_model_fixture.json"
    fixture_path.parent.mkdir(parents=True, exist_ok=True)
    
    # 1. Resampling parameters
    src_wl = np.linspace(350.0, 360.0, 100)
    src_i = 1000.0 * np.exp(-0.5 * ((src_wl - 355.0) / 0.15) ** 2) + 50.0
    det_wl = np.linspace(350.0, 360.0, 20)
    add_sigma = np.full_like(det_wl, 0.12)
    
    resampled = resample_to_detector(src_wl, src_i, det_wl, add_sigma)
    
    # 2. Zone combinations
    # 2 zones
    d0 = resampled * 1.5
    d1 = resampled * 0.8
    zones = [
        PlasmaZoneSpec(te=1.1, ne=1e17, volume_fraction=0.35, k_absorption=0.0),
        PlasmaZoneSpec(te=0.7, ne=5e16, volume_fraction=0.65, k_absorption=1.4),
    ]
    combined_i = combine_zones([d0, d1], zones)
    
    # 3. Response and scale
    response = 1.0 + 0.3 * np.sin(det_wl / 2.0)
    scale = 0.045
    final_abs = apply_response_and_scale(combined_i, response, scale, "absolute")
    final_norm = apply_response_and_scale(combined_i, response, scale, "max_normalised")
    
    fixture = {
        "srcWl": src_wl.tolist(),
        "srcI": src_i.tolist(),
        "detWl": det_wl.tolist(),
        "addSigmaNm": add_sigma.tolist(),
        "resampledExpected": resampled.tolist(),
        "d0": d0.tolist(),
        "d1": d1.tolist(),
        "zones": [
            {"te": 1.1, "ne": 1e17, "volumeFraction": 0.35, "kAbsorption": 0.0},
            {"te": 0.7, "ne": 5e16, "volumeFraction": 0.65, "kAbsorption": 1.4},
        ],
        "combinedExpected": combined_i.tolist(),
        "spectralResponse": response.tolist(),
        "scaleFactor": scale,
        "finalAbsoluteExpected": final_abs.tolist(),
        "finalMaxNormalisedExpected": final_norm.tolist(),
    }
    
    with open(fixture_path, "w", encoding="utf-8") as f:
        json.dump(fixture, f, indent=2)
        
    assert fixture_path.exists()


def test_resample_real_cached_nist_spectrum():
    """Verify resampling real cached NIST high-resolution spectrum onto AvaSpec detector channels."""
    import pandas as pd
    nist_csv = Path("build/release-package/cache/nist/469_Te1.0_Ne1.0E+17.csv")
    det_csv = Path("data/LSA/Medals_Samples_Feb26/469/LSA_ava_S20260311162055_E20260311162058.csv")
    if not nist_csv.exists() or not det_csv.exists():
        pytest.skip("Real NIST or detector CSVs not found")

    df_nist = pd.read_csv(nist_csv)
    df_det = pd.read_csv(det_csv, sep=";", nrows=1)

    src_wl = df_nist["Wavelength (nm)"].to_numpy()
    src_flux = df_nist["Sum(calc)"].to_numpy()
    det_wl = np.array([float(c) for c in df_det.columns if c not in ("MeasCtr", "TimeStamp")], dtype=np.float64)

    assert len(src_wl) > 10000, f"Expected > 10000 high-res points, got {len(src_wl)}"
    assert len(det_wl) == 2048, f"Expected 2048 detector channels, got {len(det_wl)}"

    resampled = resample_to_detector(src_wl, src_flux, det_wl, add_sigma_nm=0.0)

    assert resampled.shape == (2048,)
    assert np.all(resampled >= 0.0)
    # Total resampled flux must be positive and non-zero
    assert np.sum(resampled) > 0.0

