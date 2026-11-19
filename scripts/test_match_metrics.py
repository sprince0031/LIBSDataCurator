"""Unit tests for spectral match metrics (R11.1)."""

import numpy as np
import pytest

from scripts.match_metrics import (
    calculate_reduced_chi2,
    calculate_roi_metrics,
    calculate_whole_spectrum_metrics,
    calculate_sub_bin_residuals,
    calculate_element_parity_table,
)


def test_calculate_reduced_chi2():
    y_meas = np.array([100.0, 200.0, 300.0])
    y_model = np.array([102.0, 198.0, 301.0])
    variances = np.array([4.0, 4.0, 1.0])

    # residuals: [2, -2, 1], squared: [4, 4, 1], chi2: [1.0, 1.0, 1.0] sum = 3.0
    # N = 3, num_params = 1 -> dof = 2 -> reduced chi2 = 1.5
    red_chi2 = calculate_reduced_chi2(y_meas, y_model, variances, num_params=1)
    assert np.isclose(red_chi2, 1.5)


def test_calculate_roi_metrics():
    # 10 channels
    y_meas = np.array([10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0])
    # perfect match
    r2, rmse = calculate_roi_metrics(y_meas, y_meas, roi_channel_indices=[0, 1, 2, 3, 4])
    assert np.isclose(r2, 1.0)
    assert np.isclose(rmse, 0.0)

    # perturbed
    y_model = y_meas.copy()
    y_model[0] += 5.0
    r2_p, rmse_p = calculate_roi_metrics(y_meas, y_model, roi_channel_indices=[0, 1, 2, 3, 4])
    assert r2_p < 1.0
    assert rmse_p > 0.0
    # RMSE normalized by max(y_meas[union]) = 50.0
    # diff = [5, 0, 0, 0, 0], mse = 25 / 5 = 5, rms = sqrt(5) = 2.236
    # normalized = 2.236 / 50.0 = 0.04472
    expected_rmse = np.sqrt(25.0 / 5.0) / 50.0
    assert np.isclose(rmse_p, expected_rmse)


def test_calculate_whole_spectrum_metrics():
    y_meas = np.array([0.0, 100.0, 200.0, 0.0])
    y_model = np.array([0.0, 100.0, 200.0, 0.0])
    lit_mask = np.array([False, True, True, False])

    r2, rmse = calculate_whole_spectrum_metrics(y_meas, y_model, lit_mask)
    assert np.isclose(r2, 1.0)
    assert np.isclose(rmse, 0.0)


def test_calculate_sub_bin_residuals():
    # 4 sub-bins: 2 thin, 2 prone
    # synthetic / measured ratios
    y_meas = np.array([100.0, 100.0, 100.0, 100.0])
    # log10(100/100) = 0.0, log10(110/100) = 0.04139
    y_model = np.array([100.0, 110.0, 100.0, 200.0])
    prone_flags = [False, False, True, True]

    res = calculate_sub_bin_residuals(y_meas, y_model, prone_flags)
    assert "thin" in res
    assert "prone" in res
    assert res["thin"]["count"] == 2
    assert res["prone"]["count"] == 2
    assert np.isclose(res["thin"]["median"], 0.04139 / 2.0, atol=1e-3)
    # prone has log10(1) = 0 and log10(2) = 0.3010
    assert np.isclose(res["prone"]["median"], 0.3010 / 2.0, atol=1e-3)


def test_calculate_element_parity_table():
    dominant_elements = ["Fe", "Fe", "Cr", "Cr"]
    y_meas = np.array([100.0, 100.0, 100.0, 100.0])
    # Fe: ratios 1.0 (log10=0) and 1.2 (log10=0.079) -> median ~0.0395
    # Cr: ratios 0.8 (log10=-0.0969) and 0.8 (log10=-0.0969) -> median ~-0.0969
    y_model = np.array([100.0, 120.0, 80.0, 80.0])

    parity = calculate_element_parity_table(dominant_elements, y_meas, y_model)
    assert "Fe" in parity
    assert "Cr" in parity
    assert np.isclose(parity["Fe"], 0.0395, atol=1e-3)
    assert np.isclose(parity["Cr"], -0.0969, atol=1e-3)
