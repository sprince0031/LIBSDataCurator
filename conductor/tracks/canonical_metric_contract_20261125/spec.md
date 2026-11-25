# Specification: `TRACK-CORE-01` — Unify Canonical Spectral Metric & Preprocessing Contract in `LIBSDataCurator`

- **Track ID:** `canonical_metric_contract_20261125` (`TRACK-CORE-01`)
- **Type:** Refactor
- **Linked Findings:** `SH-01`, `SH-02`, `SH-06`, `SH-07`

## 1. Overview
Unify the spectral evaluation and measured-data conditioning contract across Python (`scripts/match_metrics.py`, `scripts/measured_conditioning.py`) and Java (`SpectrumUtils.java`) in `LIBSDataCurator`, backed by a shared machine-readable contract at `src/main/resources/metric_contract_v1.json`. This eliminates the `paper-harness` fallback (`parity_diff.json: "core_evaluator_source": "fallback"`), resolves the $R^2$ vs. Pearson $r^2$ and normalized vs. unnormalized RMSE ambiguity, and locks down shot-level vs. post-average preprocessing order.

## 2. Functional Requirements

### 2.1 Shared Contract Schema (`src/main/resources/metric_contract_v1.json`)
- Placed at `src/main/resources/metric_contract_v1.json` so Java (`SpectrumUtils` and JUnit parity tests) loads it natively from the classpath while Python (`match_metrics.py`, `measured_conditioning.py`, and external harnesses) reads the exact same file from disk.
- Defines:
  - `schema_version`: `"metric_contract_v1"`
  - Canonical payload keys: `rmse`, `nrmse_max`, `mae`, `r2`, `pearson_r2`, `cosine_similarity`, `reduced_chi2`, `normalization`, `n_valid`
  - Supported normalization modes: `"none"`, `"max"` (`max(abs(y))`), `"l2"` (`sqrt(sum(y**2))`), `"sum"` (L1 norm `sum(abs(y))`), each guarded by `max(scale, eps)`
  - Numerical guard `eps = 1e-12` (division and zero-variance / equality guards)
  - Decoupled wavelength grid tolerance `wavelength_tol = 1e-6` (nm)
  - Variance scaling rule under normalization: when `normalization != "none"`, `variance_scaled = variance / (max(scale_ref, eps) ** 2)` where `scale_ref` is the denominator applied to `y_ref`
  - Edge-case rules:
    - Array-length mismatches (`y_ref`, `y_pred`, `mask`, `variance`, `wavelengths_ref`, `wavelengths_pred`) and empty valid masks (`n_valid == 0`) raise `ValueError` (Python) / `IllegalArgumentException` (Java).
    - Zero-variance `r2`: when `SS_tot <= eps`, return `1.0` iff `SS_res < eps`, else `0.0`.
    - Zero-variance `pearson_r2` and zero-norm `cosine_similarity`: return `1.0` if `y_ref` and `y_pred` are equal within `eps` (`max(|y_ref - y_pred|) <= eps`), else `0.0` (never `NaN`).
  - Explicit preprocessing call order:
    - **Shot-level:** `apply_lit_and_saturation_mask` → `dark_subtract` → `trim_conditioning_shots` → shot-scale normalization / averaging (`mean_spectrum`, `sigma_mean`).
    - **Post-average:** `apply_morphological_baseline` (with configurable `se_size` and non-negative clamp).

### 2.2 Canonical Python Metric Entrypoint (`scripts/match_metrics.py`)
- Export pure, stateless, `float64` function:
  ```python
  compute_spectral_metrics(
      y_ref,
      y_pred,
      *,
      wavelengths_ref=None,
      wavelengths_pred=None,
      variance=None,
      mask=None,
      normalization="none",
      num_params=0,
      eps=1e-12,
      wavelength_tol=1e-6,
  ) -> dict
  ```
- **Input & Wavelength Validation:**
  - Validate matching 1D lengths for `y_ref`, `y_pred`, and any provided optional arrays (`variance`, `mask`, `wavelengths_ref`, `wavelengths_pred`); raise `ValueError` on shape/length mismatch or invalid `normalization` mode.
  - If both `wavelengths_ref` and `wavelengths_pred` are provided, enforce strict grid equality within `wavelength_tol` (`np.allclose(wl_ref, wl_pred, atol=wavelength_tol, rtol=0.0)`); raise `ValueError` on mismatch.
- **Masking:** Apply boolean `mask` (if provided) before normalization and metric computation. If `n_valid == 0`, raise `ValueError`.
- **Normalization & Variance Scaling:** Apply `"none"`, `"max"`, `"l2"`, or `"sum"` (`sum(abs(y))`) with `max(scale, eps)` floor independently to `y_ref` (`scale_ref`) and `y_pred` (`scale_pred`) before computing metrics. When `variance is not None` and `normalization != "none"`, scale `variance` by `1.0 / (max(scale_ref, eps) ** 2)`.
- **Metrics & Return Payload:** Return dict with exact keys:
  - `rmse`, `nrmse_max` (`rmse / max(max(abs(y_ref_norm)), eps)`), `mae`, `r2`, `pearson_r2`, `cosine_similarity`
  - `reduced_chi2`: `(1 / dof) * sum((y_ref_norm - y_pred_norm)^2 / max(var_scaled, eps))` with `dof = max(n_valid - num_params, 1)`, or `None` when `variance is None` (never substituting `1.0`)
  - `normalization`: canonical lowercase mode string
  - `n_valid`: `int` count of evaluated channels
- Preserve existing helper signatures (`calculate_reduced_chi2`, `calculate_roi_metrics`, `calculate_whole_spectrum_metrics`, `calculate_sub_bin_residuals`) for backward compatibility.

### 2.3 Standardized Preprocessing Helpers (`scripts/measured_conditioning.py`)
- Extract and export explicit, unit-testable helpers:
  - `apply_lit_and_saturation_mask`
  - `dark_subtract`
  - `trim_conditioning_shots`
  - `apply_morphological_baseline(spectrum, se_size=65)` (non-negative clamp)
- Refactor `condition_material(...)` to orchestrate these helpers in the exact shot-level vs. post-average order documented in `src/main/resources/metric_contract_v1.json`.

### 2.4 Canonical Java Metrics (`src/main/java/com/medals/libsdatagenerator/util/SpectrumUtils.java`)
- Keep all existing public method signatures (`calculateRMSE`, `calculateSpectralSimilarity`, `normalizeSpectrum`, etc.) intact for backward compatibility, delegating internally to canonical implementations where appropriate.
- Add `NormalizationMode` enum (`NONE`, `MAX`, `L2`, `SUM`) and `SpectralMetrics` record with aligned fields:
  - `double rmse`
  - `double nrmseMax`
  - `double mae`
  - `double r2`
  - `double pearsonR2`
  - `double cosineSimilarity`
  - `Double reducedChi2` (nullable; `null` when `variance == null`)
  - `NormalizationMode normalization`
  - `int nValid`
- Expose static `computeSpectralMetrics(...)` overloads accepting `yRef`, `yPred`, optional `boolean[] mask`, `NormalizationMode`, optional `double[] variance`, `int numParams`, optional `double[] wavelengthsRef`, `double[] wavelengthsPred`, `double eps`, and `double wavelengthTol`, implementing the exact `metric_contract_v1.json` math, variance scaling, strict array-length & wavelength validation (`IllegalArgumentException`), empty-mask error (`IllegalArgumentException`), and zero-variance / zero-norm rules (never returning `NaN`).
- Expose explicit helper methods (`calculateR2`, `calculatePearsonR2`, `calculateMAE`, `calculateCosineSimilarity`).

### 2.5 Cross-Language Parity Tests
- Store shared Python-vs-Java parity vectors in `src/test/resources/metric_parity_golden_fixtures.json` (covering synthetic edge cases + the 5 golden materials `SS469`, `SS551`, `SS465`, `SS405`, `EN AC-42000`) so `mvn test` has zero dependency on hardcoded external repository paths.
- `tests/test_match_metrics_parity.py`: Verify `compute_spectral_metrics` across all normalization modes, masking, empty-mask and array-length mismatch `ValueError`s, wavelength mismatch `ValueError` using `wavelength_tol`, zero-variance/zero-norm edge cases (no `NaN`), `variance=None` vs. empirical variance scaling under normalization, `measured_conditioning.py` helper ordering, and the golden fixtures.
- `src/test/java/com/medals/libsdatagenerator/util/SpectrumUtilsTest.java`: Load `metric_contract_v1.json` and `metric_parity_golden_fixtures.json` from the classpath and verify `SpectrumUtils.computeSpectralMetrics` matches Python `scripts/match_metrics.py` within `1.0e-9`.

## 3. Non-Functional Requirements
- **Determinism & Precision:** All metric math uses IEEE 754 `float64` / Java `double`.
- **Statelessness:** `compute_spectral_metrics` and `SpectrumUtils.computeSpectralMetrics` must be pure functions with no global or mutable state.
- **Backward Compatibility:** Existing callers in `LIBSDataCurator` (Java CLI/services and Python calibration pipeline) continue to work without breaking changes.

## 4. Acceptance Criteria
1. `pytest tests/test_match_metrics_parity.py && mvn test -Dtest=SpectrumUtilsTest` passes with 0 failures.
2. Internal Python-vs-Java parity (`scripts/match_metrics.py` vs. `SpectrumUtils.java`) achieves `delta <= 1.0e-9` across all 5 golden fixtures (`SS469`, `SS551`, `SS465`, `SS405`, `EN AC-42000`) and all synthetic/edge-case fixtures for every numeric metric (`rmse`, `nrmse_max`, `mae`, `r2`, `pearson_r2`, `cosine_similarity`, `reduced_chi2`).
3. Running `uv run python scripts/run_parity_fixture.py --config harness.config.yaml` from `paper-harness/` resolves `"core_evaluator_source"` to `LIBSDataCurator/scripts/match_metrics.py` (NOT `"fallback"`).
4. *(Note on downstream cross-repo gate)*: End-to-end cross-repo parity (`delta <= 1.0e-6` between `LIBSDataCurator` and `Medals_classifier`) will be gated once the downstream Streamlit decoupling track completes.

## 5. Out of Scope
- Modifying `Medals_classifier` (Streamlit app) or `paper-harness` files (handled in separate tracks).
- Implementing wavelength interpolation/resampling inside `compute_spectral_metrics` (strict grid equality within `wavelength_tol` is enforced instead).
