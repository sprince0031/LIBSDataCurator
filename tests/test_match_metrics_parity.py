"""Contract schema and cross-language spectral metric parity tests (TRACK-CORE-01)."""

from __future__ import annotations

import json
from pathlib import Path


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
