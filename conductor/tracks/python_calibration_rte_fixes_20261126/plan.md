# Implementation Plan: `TRACK-CORE-02A` — Fix Critical Python Calibration, Radiative-Transfer & Uncertainty Math Bugs in `LIBSDataCurator`

- **Track ID:** `python_calibration_rte_fixes_20261126` (`TRACK-CORE-02A`)
- **Workflow Reference:** `conductor/workflow.md` (Strict TDD: Red → Green → Refactor, >95% coverage target, Phase Checkpoints)

---

## Phase 1: Canonical Multi-Zone RTE, `NIST_UNIT_SCALE = 1.0`, & Conditioning Fields [checkpoint: f439415]

- [x] Task: Write failing unit tests for canonical RTE, `NIST_UNIT_SCALE = 1.0`, `validate_spectral_match`, and `ConditionedMaterial` fields (Red phase) (f439415)
  - [x] Create `tests/test_roi_joint_calibration_math.py` with tests covering:
    - `forward_model.apply_multi_zone_rte` (hot-to-cool sorting, `d_tilde = d_z / max(d_z)`, hot-core attenuation) and `combine_zones` backward-compatible alias.
    - `forward_model.NIST_UNIT_SCALE == 1.0` matching Java raw NIST `Sum(calc)` units across `load_nist_dictionary` and `validate_spectral_match.load_nist_dictionary`.
    - `validate_spectral_match.synthesize_spectrum` producing identical output to `apply_multi_zone_rte` + `apply_response_and_scale` (no unnormalized `exp(-kz * sz)`), and `evaluate_material_match` routing metrics via canonical `compute_spectral_metrics(..., normalization="none")`.
    - `ConditionedMaterial.preprocessed_point_means` (baseline-subtracted with `se_size=25`, non-negative) and `ConditionedMaterial.num_steady_shots` (true steady-shot count across all points, preserving `point_means`).
  - [x] Run `pytest tests/test_roi_joint_calibration_math.py` to confirm Red failure.
- [x] Task: Implement Phase 1 fixes in `forward_model.py`, `validate_spectral_match.py`, and `measured_conditioning.py` (Green & Refactor phase) (f439415)
  - [x] Update `scripts/forward_model.py`: set `NIST_UNIT_SCALE = 1.0`, expose `apply_multi_zone_rte(zone_spectra, zones)` as canonical entrypoint with `combine_zones` delegating to it; update any legacy `1e-6` assertion in `scripts/test_forward_model.py` if present.
  - [x] Update `scripts/validate_spectral_match.py`: route `synthesize_spectrum` through `apply_multi_zone_rte` + `apply_response_and_scale`, apply `NIST_UNIT_SCALE` in `load_nist_dictionary`, and route `evaluate_material_match` through `compute_spectral_metrics(..., normalization="none")`.
  - [x] Update `scripts/measured_conditioning.py`: add defaulted `preprocessed_point_means` and `num_steady_shots` to `ConditionedMaterial` and populate them inside `condition_material(...)` without breaking existing fields or `TRACK-CORE-01` tests.
  - [x] Run `pytest tests/test_roi_joint_calibration_math.py tests/test_match_metrics_parity.py scripts/test_measured_conditioning.py scripts/test_forward_model.py scripts/test_validate_spectral_match.py` and verify 100% pass.
- [x] Task: Phase Verification & Checkpoint (Refer to workflow.md) (f439415)

---

## Phase 2: Physics, Uncertainty Propagation, Continuum & Diagnostics Fixes in `scripts/roi_joint_calibration.py`

- [~] Task: Write failing unit tests for `roi_joint_calibration.py` math bugs (Red phase)
  - [ ] Expand `tests/test_roi_joint_calibration_math.py` to test:
    - `compute_element_parity` recovering `0.0 dex` (within `1e-9`) and `has_bias == False` on noiseless multi-zone synthetic data with non-contiguous `nz_indices` (e.g., `[2, 7]`).
    - `scan_absorption` attenuating `d_hot` (NOT `d_cool`) with full-spectrum cool-zone max normalization matching `apply_multi_zone_rte` 1:1, recovering planted $k > 0$ within `0.1`, using `is_prone` only for residual masking / presence checks.
    - `solve_family_plasma`, `reduce_zones_to_num_zones`, and `scan_absorption` dict wrappers propagating true per-material `sigma_dict` ($\sigma_{m,b}$) instead of hardcoding `sigma = 1.0`.
    - `run_joint_solver` using baseline-subtracted `cond.preprocessed_mean` for sub-bin variances & flux centroids, and computing real ferrous-vs-non-ferrous `family_group_rms_diff` (not hardcoded `0.02`).
    - `compute_plasma_dispersion` using `ddof=1` sample standard deviation.
  - [ ] Run `pytest tests/test_roi_joint_calibration_math.py` to confirm Red failure.
- [ ] Task: Implement Phase 2 fixes in `scripts/roi_joint_calibration.py` (Green & Refactor phase)
  - [ ] Fix `compute_element_parity` to resolve true state-grid column indices (`nz_indices` / `stateIndex` / nearest `(Te, Ne)`) and synthesize via `apply_multi_zone_rte` + `apply_response_and_scale`.
  - [ ] Fix `scan_absorption` to attenuate `d_hot` using full-spectrum cool-zone max normalization matching `apply_multi_zone_rte` 1:1, propagating `sigma_dict` and keeping `is_prone` strictly as residual mask / presence check; update legacy buggy test expectations in `scripts/test_roi_joint_calibration.py:416, 439`.
  - [ ] Update `solve_family_plasma` and `reduce_zones_to_num_zones` dict wrappers to accept and propagate `sigma_dict` (no hardcoded `sigma = 1.0`).
  - [ ] Switch `run_joint_solver` (`lines 387, 394`) to `cond.preprocessed_mean`, switch `compute_plasma_dispersion` (`lines 959, 964`) to `ddof=1`, and implement real ferrous-vs-non-ferrous Block B response RMS comparison (`lines 586-600`).
  - [ ] Run `pytest tests/test_roi_joint_calibration_math.py scripts/test_roi_joint_calibration.py scripts/test_planted_truth_recovery.py` and verify 100% pass.
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

---

## Phase 3: Calibration Engine Wiring (`scripts/gpu_calibration_engine.py`)

- [ ] Task: Inspect Java `InstrumentProfile` / `CalibrationStats` DTO (read-only) before adding new JSON keys
  - [ ] Read Java `InstrumentProfile` and nested `CalibrationStats` DTOs (read-only) to verify whether Jackson ignores unknown JSON properties (`@JsonIgnoreProperties(ignoreUnknown = true)` or `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES`); document the finding in the Phase 3 checkpoint summary for `TRACK-CORE-02B` awareness.
- [ ] Task: Write failing unit tests for `gpu_calibration_engine.py` calibration wiring (Red phase)
  - [ ] Expand `tests/test_roi_joint_calibration_math.py` with unit tests verifying:
    - `roiChi2Reduced` computed via `compute_spectral_metrics(..., variance=sigma_sb**2, normalization="none", num_params=n_params)["reduced_chi2"]` uses actual fitted `n_params` (not hardcoded `0`), unit-matched `sigma_sb` in physical count units, and is $\mathcal{O}(1)$ on noisy synthetic benchmarks (NOT $\mathcal{O}(10^8)$).
    - `calibrationStats` aggregates across ALL materials in `fam_mats` (reporting family means + `perMaterialStats` with per-material `numSteadyShots`, `roiRmse`/`wholeSpectrumRmse` as `nrmse_max` under `normalization="none"`, plus `roiRmseRaw`/`wholeSpectrumRmseRaw`).
    - Top-level `numShots` in `instrument_profile.json` equals total `sum(cond.num_steady_shots)` across all calibrated materials (NOT `8192`).
    - Per-point dispersion uses `cond.preprocessed_point_means`; `compute_element_parity` receives `nz_indices`; multi-zone synthesis routes through `apply_multi_zone_rte`.
    - Gate G1 (`forward_model.composition_scale_check`) is executed and wired into `evaluate_diagnostics` (`COMPOSITION_SCALE_UNVERIFIED` emitted on failure/unverified).
    - Dynamic `add_sigma_nm` is computed via `spectral_resampling.calculate_add_sigma_nm` when instrument FWHM & `R_nist` are available and persisted into `lineSpreadAddSigmaNm`.
  - [ ] Run `pytest tests/test_roi_joint_calibration_math.py` to confirm Red failure.
- [ ] Task: Implement Phase 3 fixes in `scripts/gpu_calibration_engine.py` (Green & Refactor phase)
  - [ ] Wire `NIST_UNIT_SCALE`, dynamic `add_sigma_nm` (`calculate_add_sigma_nm` + `lineSpreadAddSigmaNm`), true R6 sub-bin $\sigma_{m,b}$ propagation (same physical count units as `y_meas`/`y_pred` with accurate `n_params`), canonical `apply_multi_zone_rte`, `nz_indices` in `compute_element_parity`, `cond.preprocessed_point_means`, Gate G1 check, multi-material `calibrationStats` + `perMaterialStats` + `fit_comparison`, and true top-level `numShots` sum.
  - [ ] Run `pytest tests/test_roi_joint_calibration_math.py scripts/test_gpu_calibration_engine.py` and verify 100% pass.
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

---

## Phase 4: End-to-End Regression & Guardrail Audit

- [ ] Task: Execute full Python verification suite & guardrail boundary audit
  - [ ] Run `pytest tests/test_roi_joint_calibration_math.py tests/test_match_metrics_parity.py scripts/test_measured_conditioning.py scripts/test_forward_model.py scripts/test_roi_joint_calibration.py scripts/test_validate_spectral_match.py scripts/test_planted_truth_recovery.py scripts/test_gpu_calibration_engine.py`.
  - [ ] Audit `git diff --name-only` across the track commits to verify zero modifications to `TRACK-CORE-01` contract files (`scripts/match_metrics.py`, `src/main/resources/metric_contract_v1.json`, `src/test/resources/metric_parity_golden_fixtures.json`, `scripts/parity_bridge.py`), zero Java file changes, and zero regenerated/overwritten synthetic CSV outputs on disk.
- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
