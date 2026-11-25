# Implementation Plan: `TRACK-CORE-01` — Unify Canonical Spectral Metric & Preprocessing Contract in `LIBSDataCurator`

- **Track ID:** `canonical_metric_contract_20261125` (`TRACK-CORE-01`)
- **Workflow Reference:** `conductor/workflow.md` (Strict TDD: Red → Green → Refactor, >95% coverage, Phase Checkpoints)

---

## Phase 1: Shared Metric & Preprocessing Contract Schema (`metric_contract_v1.json`) [checkpoint: 7efbdaa]

- [x] Task: Create contract schema validation tests (Red phase) (ccb1526)
  - [x] Create `tests/test_match_metrics_parity.py` with initial tests loading `src/main/resources/metric_contract_v1.json` and asserting presence of `schema_version` (`metric_contract_v1`), canonical metric keys, normalization modes (`none`, `max`, `l2`, `sum`), `eps` (`1e-12`), `wavelength_tol` (`1e-6`), variance-scaling rule, edge-case rules, and shot-level vs. post-average preprocessing stages.
  - [x] Add a JUnit test in `src/test/java/com/medals/libsdatagenerator/util/SpectrumUtilsTest.java` verifying `metric_contract_v1.json` is loadable from the classpath and parses cleanly with Jackson.
  - [x] Run `pytest tests/test_match_metrics_parity.py` and `mvn test -Dtest=SpectrumUtilsTest` to confirm Red failure.
- [x] Task: Author `src/main/resources/metric_contract_v1.json` (Green phase) (ccb1526)
  - [x] Define canonical metric keys (`rmse`, `nrmse_max`, `mae`, `r2`, `pearson_r2`, `cosine_similarity`, `reduced_chi2`, `normalization`, `n_valid`).
  - [x] Document `eps = 1e-12`, `wavelength_tol = 1e-6`, normalization formulas (`none`, `max`, `l2`, `sum` via L1 `sum(abs(y))`), variance scaling (`variance / max(scale_ref, eps)^2`), array-length mismatch and empty-mask error contracts, and zero-variance / zero-norm rules (never `NaN`).
  - [x] Document exact shot-level (`apply_lit_and_saturation_mask` → `dark_subtract` → `trim_conditioning_shots` → shot-scale normalization / averaging) and post-average (`apply_morphological_baseline`) call order.
  - [x] Verify Phase 1 contract tests pass in both Python and Java.
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) (7efbdaa)

---

## Phase 2: Python Canonical Metric API & Conditioning Helpers [checkpoint: 17a8e40]

- [x] Task: Probe `paper-harness/scripts/run_parity_fixture.py` (read-only) before coding
  - [x] Inspect `paper-harness/scripts/run_parity_fixture.py` (read-only) to confirm the exact module path, function name, signature, and return keys it probes in `LIBSDataCurator/scripts/match_metrics.py` so Phase 4 resolves cleanly without `"fallback"`. *(Finding: `run_parity_fixture.py:39-59` probes `parity_bridge.py` / `scripts/parity_bridge.py` for `compute_spectral_metrics(y_ref, y_pred)` and reads `rmse`, `mae`, `r2`, `cosine_similarity`; we will expose a thin `scripts/parity_bridge.py` re-exporting `scripts/match_metrics.py:compute_spectral_metrics`.)*
- [x] Task: Write failing unit tests for `compute_spectral_metrics` in `scripts/match_metrics.py` (Red phase) (7457060)
  - [x] Expand `tests/test_match_metrics_parity.py` and `scripts/test_match_metrics.py` to cover:
    - All normalization modes (`none`, `max`, `l2`, `sum` with L1 norm).
    - Array-length mismatch checks (`y_ref`, `y_pred`, `mask`, `variance`, `wavelengths_ref`, `wavelengths_pred`) raising `ValueError`.
    - Strict wavelength grid validation (`wavelength_tol = 1e-6` pass vs. mismatch `ValueError`).
    - Boolean `mask` filtering and empty-mask `ValueError` (`n_valid == 0`).
    - `variance=None` returning `reduced_chi2: None` (no silent `1.0` substitution) vs. empirical `variance` with `1 / max(scale_ref, eps)^2` scaling under normalization and `dof = max(n_valid - num_params, 1)`.
    - Zero-variance `r2` (`1.0` iff `ss_res < eps`, else `0.0`), zero-variance `pearson_r2` and zero-norm `cosine_similarity` (`1.0` if equal within `eps`, else `0.0`, never `NaN`).
    - Return payload completeness (`normalization`, `n_valid`, and all 7 metric keys) and backward compatibility of existing `match_metrics.py` helpers.
  - [x] Run `pytest tests/test_match_metrics_parity.py scripts/test_match_metrics.py` to confirm Red failure.
- [x] Task: Implement `compute_spectral_metrics` in `scripts/match_metrics.py` (Green & Refactor phase) (7457060)
  - [x] Implement pure, stateless `float64` `compute_spectral_metrics(y_ref, y_pred, *, wavelengths_ref=None, wavelengths_pred=None, variance=None, mask=None, normalization="none", num_params=0, eps=1e-12, wavelength_tol=1e-6) -> dict` (plus `scripts/parity_bridge.py` delegating to it for `paper-harness`).
  - [x] Enforce input array-length consistency, strict wavelength equality within `wavelength_tol`, empty-mask `ValueError`, variance scaling under normalization, and zero-variance / zero-norm rules matching `metric_contract_v1.json`.
  - [x] Ensure existing functions (`calculate_reduced_chi2`, `calculate_roi_metrics`, `calculate_whole_spectrum_metrics`, `calculate_sub_bin_residuals`) remain backward-compatible.
  - [x] Run `pytest tests/test_match_metrics_parity.py scripts/test_match_metrics.py` and verify >95% coverage on `scripts/match_metrics.py`.
- [x] Task: Write failing unit tests for standardized preprocessing helpers in `scripts/measured_conditioning.py` (Red phase) (00b415b)
  - [x] Add unit tests in `scripts/test_measured_conditioning.py` for standalone `apply_lit_and_saturation_mask`, `dark_subtract`, `trim_conditioning_shots`, and `apply_morphological_baseline(spectrum, se_size=25)` (including non-negative clamp and contract call order).
  - [x] Run `pytest scripts/test_measured_conditioning.py` to confirm Red failure.
- [x] Task: Extract and standardize preprocessing helpers in `scripts/measured_conditioning.py` (Green & Refactor phase) (00b415b)
  - [x] Export `apply_lit_and_saturation_mask`, `dark_subtract`, `trim_conditioning_shots`, and `apply_morphological_baseline` (preserving `preprocess` alias/compatibility and matching default `se_size=25`).
  - [x] Refactor `condition_material(...)` to orchestrate the exported helpers in the exact shot-level vs. post-average sequence documented in `metric_contract_v1.json`.
  - [x] Run `pytest scripts/test_measured_conditioning.py` and verify all existing and new tests pass with >93% coverage.
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) (17a8e40)

---

## Phase 3: Java Canonical Metric API (`SpectrumUtils.java`)

- [x] Task: Write failing JUnit tests for Java `SpectralMetrics` contract in `SpectrumUtilsTest.java` (Red phase) (9b3c87b)
  - [x] Add unit tests in `src/test/java/com/medals/libsdatagenerator/util/SpectrumUtilsTest.java` for:
    - `NormalizationMode` (`NONE`, `MAX`, `L2`, `SUM` with L1 norm).
    - `SpectralMetrics` record fields (`rmse`, `nrmseMax`, `mae`, `r2`, `pearsonR2`, `cosineSimilarity`, nullable `Double reducedChi2`, `NormalizationMode normalization`, `int nValid`).
    - Explicit static helpers (`calculateR2`, `calculatePearsonR2`, `calculateMAE`, `calculateCosineSimilarity`).
    - Array-length mismatch checks (`yRef`, `yPred`, `mask`, `variance`, `wavelengthsRef`, `wavelengthsPred`) raising `IllegalArgumentException`.
    - Wavelength grid equality validation (`wavelengthTol = 1e-6` pass vs. `IllegalArgumentException` on mismatch) and empty valid mask (`IllegalArgumentException`).
    - `variance == null` returning `reducedChi2 == null` vs. empirical variance scaling (`1 / max(scaleRef, eps)^2`) and `dof = max(nValid - numParams, 1)`.
    - Zero-variance `r2`, zero-variance `pearsonR2`, and zero-norm `cosineSimilarity` edge cases (never `NaN`).
    - Backward compatibility of existing public methods (`calculateRMSE`, `calculateSpectralSimilarity`, `normalizeSpectrum`).
  - [x] Run `mvn test -Dtest=SpectrumUtilsTest` to confirm Red failure.
- [x] Task: Implement canonical metric API in `SpectrumUtils.java` (Green & Refactor phase) (9b3c87b)
  - [x] Add `NormalizationMode` enum and `SpectralMetrics` record aligned with `metric_contract_v1.json`.
  - [x] Implement stateless `double`-precision `computeSpectralMetrics(...)` overloads (with optional `mask`, `NormalizationMode`, `variance`, `numParams`, `wavelengthsRef`, `wavelengthsPred`, `eps`, `wavelengthTol`) and explicit `calculateR2`, `calculatePearsonR2`, `calculateMAE`, `calculateCosineSimilarity`.
  - [x] Enforce array-length mismatch, empty-mask, and strict wavelength grid checks raising `IllegalArgumentException`.
  - [x] Delegate existing public methods internally where appropriate without breaking existing method signatures or callers.
  - [x] Run `mvn test -Dtest=SpectrumUtilsTest` and full `mvn test` suite to verify zero regressions.
- [~] Task: Phase Verification & Checkpoint (Refer to workflow.md)

---

## Phase 4: Cross-Language Golden-Fixture Parity & Harness Verification

- [ ] Task: Create committed golden-fixture parity dataset (`src/test/resources/metric_parity_golden_fixtures.json`)
  - [ ] Generate and commit `src/test/resources/metric_parity_golden_fixtures.json` containing deterministic synthetic edge cases plus paired vectors and reference expected outputs for all 5 golden materials (`SS469`, `SS551`, `SS465`, `SS405`, `EN AC-42000`) across normalization modes and variance configurations, so `mvn test` never depends on hardcoded external repository paths.
- [ ] Task: Run cross-language parity unit tests (Python & Java)
  - [ ] Add parity test cases in `tests/test_match_metrics_parity.py` and `src/test/java/com/medals/libsdatagenerator/util/SpectrumUtilsTest.java` loading `src/test/resources/metric_parity_golden_fixtures.json`.
  - [ ] Assert Python (`scripts/match_metrics.py`) vs. Java (`SpectrumUtils.java`) parity within `delta <= 1.0e-9` for every numeric metric and exact equality for `n_valid`, `normalization`, and `reduced_chi2 == null/None`.
- [ ] Task: Execute end-to-end deterministic verification commands
  - [ ] Run `pytest tests/test_match_metrics_parity.py && mvn test -Dtest=SpectrumUtilsTest` and confirm 100% pass.
  - [ ] Run `uv run python scripts/run_parity_fixture.py --config harness.config.yaml` from `paper-harness/` and verify `outputs/artifacts/parity_diff.json` reports `"core_evaluator_source"` resolving to `LIBSDataCurator/scripts/match_metrics.py` (NOT `"fallback"`).
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
