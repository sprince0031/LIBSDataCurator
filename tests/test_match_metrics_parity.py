"""Contract schema and cross-language spectral metric parity tests (TRACK-CORE-01)."""

from __future__ import annotations

import importlib.util
import json
import math
from pathlib import Path

import numpy as np
import pytest


REPO_ROOT = Path(__file__).resolve().parent.parent
CONTRACT_PATH = REPO_ROOT / "src" / "main" / "resources" / "metric_contract_v1.json"


def _load_contract() -> dict:
    assert CONTRACT_PATH.exists(), f"Missing shared contract file: {CONTRACT_PATH}"
    with CONTRACT_PATH.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def test_metric_contract_v1_schema_metadata_and_tolerances() -> None:
    contract = _load_contract()

    assert contract["schema_version"] == "metric_contract_v1"
    assert contract["tolerances"]["eps"] == 1e-12
    assert contract["tolerances"]["wavelength_tol"] == 1e-6
    assert contract["tolerances"]["wavelength_tol_unit"] == "nm"
    assert contract["tolerances"]["python_java_parity_tol"] == 1e-9
    assert contract["tolerances"]["cross_repo_parity_tol"] == 1e-6


def test_metric_contract_v1_keys_and_normalization_modes() -> None:
    contract = _load_contract()

    expected_keys = [
        "rmse",
        "nrmse_max",
        "mae",
        "r2",
        "pearson_r2",
        "cosine_similarity",
        "reduced_chi2",
        "normalization",
        "n_valid",
    ]
    assert contract["canonical_metric_keys"] == expected_keys

    modes = contract["normalization_modes"]
    assert list(modes.keys()) == ["none", "max", "l2", "sum"]
    assert "sum(abs(y))" in modes["sum"]["scale_formula"]
    assert "max(scale_ref, eps)" in contract["variance_scaling"]["formula"]
    assert contract["variance_scaling"]["applies_when"] == "normalization != 'none'"


def test_metric_contract_v1_edge_cases_and_preprocessing_order() -> None:
    contract = _load_contract()

    edges = contract["edge_cases"]
    assert edges["empty_valid_mask"]["python_exception"] == "ValueError"
    assert edges["empty_valid_mask"]["java_exception"] == "IllegalArgumentException"
    assert edges["array_length_mismatch"]["python_exception"] == "ValueError"
    assert edges["array_length_mismatch"]["java_exception"] == "IllegalArgumentException"
    assert edges["wavelength_mismatch"]["python_exception"] == "ValueError"
    assert edges["wavelength_mismatch"]["java_exception"] == "IllegalArgumentException"
    assert edges["missing_variance"]["reduced_chi2_value"] is None
    assert edges["zero_variance_r2"]["equal_within_eps"] == 1.0
    assert edges["zero_variance_r2"]["not_equal"] == 0.0
    assert edges["zero_variance_pearson_r2"]["equal_within_eps"] == 1.0
    assert edges["zero_variance_pearson_r2"]["not_equal"] == 0.0
    assert edges["zero_norm_cosine_similarity"]["equal_within_eps"] == 1.0
    assert edges["zero_norm_cosine_similarity"]["not_equal"] == 0.0

    pipeline = contract["preprocessing_pipeline"]
    assert pipeline["shot_level_order"] == [
        "apply_lit_and_saturation_mask",
        "dark_subtract",
        "trim_conditioning_shots",
        "shot_scale_normalize_and_average",
    ]
    assert pipeline["post_average_order"] == [
        "apply_morphological_baseline",
    ]
    assert pipeline["morphological_baseline_defaults"]["se_size"] == 25
    assert pipeline["morphological_baseline_defaults"]["non_negative_clamp"] is True


def test_compute_spectral_metrics_canonical_none_and_payload() -> None:
    from scripts.match_metrics import compute_spectral_metrics

    y_ref = np.array([10.0, 20.0, 30.0, 40.0], dtype=np.float64)
    y_pred = np.array([12.0, 18.0, 33.0, 37.0], dtype=np.float64)

    res = compute_spectral_metrics(y_ref, y_pred)
    contract = _load_contract()
    assert list(res.keys()) == contract["canonical_metric_keys"]
    assert res["normalization"] == "none"
    assert res["n_valid"] == 4
    assert res["reduced_chi2"] is None

    diff = y_ref - y_pred
    expected_rmse = math.sqrt(float(np.mean(diff ** 2)))
    assert res["rmse"] == pytest.approx(expected_rmse, abs=1e-12)
    assert res["nrmse_max"] == pytest.approx(expected_rmse / 40.0, abs=1e-12)
    assert res["mae"] == pytest.approx(float(np.mean(np.abs(diff))), abs=1e-12)
    ss_res = float(np.sum(diff ** 2))
    ss_tot = float(np.sum((y_ref - np.mean(y_ref)) ** 2))
    assert res["r2"] == pytest.approx(1.0 - ss_res / ss_tot, abs=1e-12)
    assert 0.0 <= res["pearson_r2"] <= 1.0
    expected_cos = float(np.dot(y_ref, y_pred) / (np.linalg.norm(y_ref) * np.linalg.norm(y_pred)))
    assert res["cosine_similarity"] == pytest.approx(expected_cos, abs=1e-12)


@pytest.mark.parametrize("mode", ["none", "max", "l2", "sum"])
def test_compute_spectral_metrics_normalization_modes_and_variance_scaling(mode: str) -> None:
    from scripts.match_metrics import compute_spectral_metrics

    y_ref = np.array([-5.0, 10.0, 25.0, 15.0], dtype=np.float64)
    y_pred = np.array([-4.0, 12.0, 20.0, 18.0], dtype=np.float64)
    variance = np.array([1.0, 4.0, 9.0, 16.0], dtype=np.float64)

    res = compute_spectral_metrics(
        y_ref,
        y_pred,
        variance=variance,
        normalization=mode,
        num_params=1,
    )
    assert res["normalization"] == mode
    assert res["n_valid"] == 4
    assert res["reduced_chi2"] is not None

    if mode == "none":
        s_ref, s_pred = 1.0, 1.0
    elif mode == "max":
        s_ref, s_pred = float(np.max(np.abs(y_ref))), float(np.max(np.abs(y_pred)))
    elif mode == "l2":
        s_ref, s_pred = float(np.linalg.norm(y_ref)), float(np.linalg.norm(y_pred))
    else:
        s_ref, s_pred = float(np.sum(np.abs(y_ref))), float(np.sum(np.abs(y_pred)))

    yr = y_ref / s_ref
    yp = y_pred / s_pred
    var_scaled = variance / (s_ref ** 2)
    expected_chi2 = float(np.sum(((yr - yp) ** 2) / np.maximum(var_scaled, 1e-12)) / (4 - 1))
    assert res["reduced_chi2"] == pytest.approx(expected_chi2, abs=1e-12)
    assert res["rmse"] == pytest.approx(float(np.sqrt(np.mean((yr - yp) ** 2))), abs=1e-12)


def test_compute_spectral_metrics_masking_and_wavelength_validation() -> None:
    from scripts.match_metrics import compute_spectral_metrics

    wl_ref = np.array([350.0, 351.0, 352.0, 353.0], dtype=np.float64)
    wl_ok = wl_ref + 5e-7  # within wavelength_tol=1e-6
    wl_bad = wl_ref + 2e-6  # exceeds wavelength_tol=1e-6

    y_ref = np.array([100.0, 10.0, 20.0, 30.0], dtype=np.float64)
    y_pred = np.array([9999.0, 10.0, 20.0, 30.0], dtype=np.float64)
    mask = np.array([False, True, True, True], dtype=bool)

    res = compute_spectral_metrics(
        y_ref,
        y_pred,
        wavelengths_ref=wl_ref,
        wavelengths_pred=wl_ok,
        mask=mask,
    )
    assert res["n_valid"] == 3
    assert res["rmse"] == pytest.approx(0.0, abs=1e-12)
    assert res["r2"] == pytest.approx(1.0, abs=1e-12)

    with pytest.raises(ValueError, match="wavelength"):
        compute_spectral_metrics(y_ref, y_pred, wavelengths_ref=wl_ref, wavelengths_pred=wl_bad)


def test_compute_spectral_metrics_length_mismatch_empty_mask_and_bad_mode() -> None:
    from scripts.match_metrics import compute_spectral_metrics

    y_ref = np.array([1.0, 2.0, 3.0])
    y_pred = np.array([1.0, 2.0, 3.0])

    with pytest.raises(ValueError):
        compute_spectral_metrics(y_ref, np.array([1.0, 2.0]))
    with pytest.raises(ValueError):
        compute_spectral_metrics(y_ref, y_pred, mask=np.array([True, False]))
    with pytest.raises(ValueError):
        compute_spectral_metrics(y_ref, y_pred, variance=np.array([1.0, 1.0]))
    with pytest.raises(ValueError):
        compute_spectral_metrics(y_ref, y_pred, wavelengths_ref=np.array([1.0, 2.0]))
    with pytest.raises(ValueError):
        compute_spectral_metrics(y_ref, y_pred, wavelengths_pred=np.array([1.0, 2.0]))
    with pytest.raises(ValueError):
        compute_spectral_metrics(y_ref, y_pred, mask=np.array([False, False, False]))
    with pytest.raises(ValueError):
        compute_spectral_metrics(np.array([]), np.array([]))
    with pytest.raises(ValueError):
        compute_spectral_metrics(y_ref, y_pred, normalization="invalid_mode")
    with pytest.raises(ValueError):
        compute_spectral_metrics(y_ref, y_pred, normalization=123)  # type: ignore[arg-type]
    with pytest.raises(ValueError):
        compute_spectral_metrics(np.zeros((2, 2)), np.zeros((2, 2)))


def test_compute_spectral_metrics_mask_with_variance_and_dof_floor() -> None:
    from scripts.match_metrics import compute_spectral_metrics

    y_ref = np.array([999.0, 10.0, 20.0, 30.0], dtype=np.float64)
    y_pred = np.array([0.0, 12.0, 18.0, 31.0], dtype=np.float64)
    variance = np.array([1.0, 4.0, 4.0, 1.0], dtype=np.float64)
    mask = np.array([False, True, True, True], dtype=bool)

    res = compute_spectral_metrics(y_ref, y_pred, variance=variance, mask=mask, num_params=10)
    assert res["n_valid"] == 3
    # dof = max(3 - 10, 1) = 1 -> chi2 = 4/4 + 4/4 + 1/1 = 3.0
    assert res["reduced_chi2"] == pytest.approx(3.0, abs=1e-12)


def test_compute_spectral_metrics_zero_variance_and_zero_norm_never_nan() -> None:
    from scripts.match_metrics import compute_spectral_metrics

    # Equal constant non-zero arrays -> r2=1.0, pearson_r2=1.0, cosine=1.0
    const = np.array([5.0, 5.0, 5.0], dtype=np.float64)
    res_eq = compute_spectral_metrics(const, const.copy())
    assert res_eq["r2"] == 1.0
    assert res_eq["pearson_r2"] == 1.0
    assert res_eq["cosine_similarity"] == pytest.approx(1.0, abs=1e-12)

    # Unequal constant arrays -> r2=0.0, pearson_r2=0.0
    res_neq = compute_spectral_metrics(const, np.array([6.0, 6.0, 6.0], dtype=np.float64))
    assert res_neq["r2"] == 0.0
    assert res_neq["pearson_r2"] == 0.0
    assert not math.isnan(res_neq["pearson_r2"])

    # Both all-zero arrays -> r2=1.0, pearson_r2=1.0, cosine=1.0 (no NaN)
    zeros = np.zeros(4, dtype=np.float64)
    res_zero_eq = compute_spectral_metrics(zeros, zeros.copy())
    assert res_zero_eq["r2"] == 1.0
    assert res_zero_eq["pearson_r2"] == 1.0
    assert res_zero_eq["cosine_similarity"] == 1.0

    # Zero ref vs non-zero pred -> r2=0.0, pearson_r2=0.0, cosine=0.0 (no NaN)
    res_zero_neq = compute_spectral_metrics(zeros, np.array([0.0, 1.0, 2.0, 3.0], dtype=np.float64))
    assert res_zero_neq["r2"] == 0.0
    assert res_zero_neq["pearson_r2"] == 0.0
    assert res_zero_neq["cosine_similarity"] == 0.0


def test_parity_bridge_standalone_import_for_paper_harness() -> None:
    bridge_path = REPO_ROOT / "scripts" / "parity_bridge.py"
    assert bridge_path.is_file(), f"Missing parity bridge: {bridge_path}"

    spec = importlib.util.spec_from_file_location("_test_core_parity_bridge", bridge_path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)

    fn = getattr(module, "compute_spectral_metrics", None)
    assert callable(fn)
    out = fn(np.array([1.0, 2.0, 3.0]), np.array([1.0, 2.5, 2.5]))
    for key in ("rmse", "mae", "r2", "cosine_similarity"):
        assert key in out
        assert isinstance(out[key], float)
