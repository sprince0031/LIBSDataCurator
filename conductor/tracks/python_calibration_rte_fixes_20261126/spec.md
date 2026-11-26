# Specification: `TRACK-CORE-02A` — Fix Critical Python Calibration, Radiative-Transfer & Uncertainty Math Bugs in `LIBSDataCurator`

- **Track ID:** `python_calibration_rte_fixes_20261126` (`TRACK-CORE-02A`)
- **Type:** Bug
- **Linked Findings:** `SH-02`, `SH-03`, `SH-06`
- **Scope Boundary:** Python calibration, radiative-transfer, conditioning, and validation modules only (Java edits and empirical `finotelloNoiseBeta` estimation are deferred to `TRACK-CORE-02B`).

## 1. Overview
Fix critical physics, radiative-transfer, uncertainty propagation, continuum consistency, and diagnostic bugs across `scripts/forward_model.py`, `scripts/roi_joint_calibration.py`, `scripts/gpu_calibration_engine.py`, `scripts/measured_conditioning.py`, and `scripts/validate_spectral_match.py`. Every recursive multi-zone RTE call will route through one canonical operator matching Java's `SpectrumUtils.combineNZones`, true R6 sub-bin uncertainties ($\sigma_{m,b}$) will replace all `sigma = 1.0` / `np.ones_like` shortcuts, continuum baseline subtraction (`se_size=25`) will be applied consistently across all calibration stages, and diagnostic/unit-scaling/shot-count bugs will be eliminated.

## 2. Functional Requirements

### 2.1 Canonical Multi-Zone RTE & `NIST_UNIT_SCALE` Unification
- Expose `apply_multi_zone_rte(zone_spectra, zones)` in `scripts/forward_model.py` as the canonical multi-zone RTE operator (hot-to-cool $T_e$ sorting, unnormalized emission $V_z d_z$, max-normalized opacity proxy $\tilde{d}_z = d_z / \max(d_z)$, recursive hot-core attenuation $I_z = I_{z-1} \exp(-k_z \tilde{d}_z) + V_z d_z$), keeping `combine_zones` as a backward-compatible alias.
- Route **all** multi-zone RTE evaluations in `gpu_calibration_engine.py:1004-1014`, `roi_joint_calibration.py:1011-1019`, and `validate_spectral_match.py:110-137` through `apply_multi_zone_rte` (eliminating unnormalized `exp(-kz * s_z)`).
- Unify `NIST_UNIT_SCALE = 1.0` in `scripts/forward_model.py` (matching Java's `NISTUtils.parseNistCsv` / `LIBSDataService.java` raw NIST `Sum(calc)` convention) and apply `NIST_UNIT_SCALE` consistently in `forward_model.py:load_nist_dictionary`, `gpu_calibration_engine.py`, and `validate_spectral_match.py`.

### 2.2 Fix `compute_element_parity` & `scan_absorption` Physics Bugs (`scripts/roi_joint_calibration.py`)
- **Element Parity Indexing (`roi_joint_calibration.py:1015`):** Resolve each zone's true state-grid column index (`nz_indices`, explicit `stateIndex` on zone dicts, or nearest `(Te, Ne)` lookup on `te_grid`/`ne_grid` rather than `z["zoneIndex"] % D_m.shape[1]`), and synthesize `y_synth` via `apply_multi_zone_rte` + `apply_response_and_scale` so noiseless multi-zone synthetic data recovers `0.0 dex` with zero false `ELEMENT_BIAS`.
- **Self-Absorption Direction & 1:1 Forward Consistency (`roi_joint_calibration.py:835-895`):**
  - Fix `scan_absorption` so the cool outer shell attenuates the **hot core** `d_hot` (`I = V_hot * d_hot * exp(-k * d_tilde_cool) + V_cool * d_cool`), matching `apply_multi_zone_rte` 1:1.
  - Normalize $\tilde{d}_{\text{cool}} = d_{\text{cool}} / \max(d_{\text{cool, full}})$ using the **full-spectrum cool-zone maximum** (passed via full-spectrum dictionary / `cool_zone_max` when available, falling back to `max(d_cool)` only when standalone sub-bin dicts lack full-spectrum context) so fitted $k$ matches forward synthesis `apply_multi_zone_rte` 1:1.
  - Apply attenuation `exp(-k * d_tilde_cool)` across all channels/sub-bins consistently with `apply_multi_zone_rte`, using `is_prone` strictly as the fitting-residual mask / prone-line presence gate rather than altering the forward operator.
  - Update the legacy buggy test expectations in `scripts/test_roi_joint_calibration.py:416, 439` that previously planted attenuation on `d_cool`.

### 2.3 Propagate True R6 Sub-Bin Uncertainty $\sigma_{m,b}$ & Canonical Metrics
- Remove all `sigma = 1.0` dict-wrapper shortcuts in `solve_family_plasma` (`roi_joint_calibration.py:648`), `reduce_zones_to_num_zones` (`line 753`), and `scan_absorption` (`line 847`): accept `sigma_dict` (per-material R6 sub-bin uncertainty vectors $\sigma_{m,b}$ computed via `compute_sub_bin_variances` from empirical `sigma_mean` + atomic/model/dark floor), propagating them through NNLS, WLS, Huber zone reduction, and absorption scanning.
- Replace `calculate_reduced_chi2(..., np.ones_like(y_dict[ref_m]))` in `gpu_calibration_engine.py:1018` with canonical `compute_spectral_metrics(y_meas_sb, y_pred_sb, variance=sigma_sb**2, normalization="none", num_params=n_params)["reduced_chi2"]` (read-only import from `scripts/match_metrics.py`), explicitly specifying `normalization="none"` so `y_meas_sb`, `y_pred_sb`, and `sigma_sb` share identical physical count units and accurate `num_params` (reflecting actual fitted parameters, not hardcoded `0`) yields $\mathcal{O}(1)$ `roiChi2Reduced` on noisy synthetic benchmarks.
- Route `roiRSquared`/`roiRmse` and `wholeSpectrumRSquared`/`wholeSpectrumRmse` in `gpu_calibration_engine.py` and `validate_spectral_match.py` through `compute_spectral_metrics(..., mask=..., normalization="none")`, reporting `roiRmse` / `wholeSpectrumRmse` as `nrmse_max` under `normalization="none"` (preserving existing `calibrationStats` schema compatibility) and also recording unnormalized `roiRmseRaw` / `wholeSpectrumRmseRaw`.

### 2.4 Continuum Consistency & Unbiased Dispersion
- Extend `ConditionedMaterial` in `scripts/measured_conditioning.py` with two new fields with safe defaults (preserving all existing fields and `TRACK-CORE-01` tests):
  - `preprocessed_point_means: Dict[str, np.ndarray]` (baseline-subtracted via `apply_morphological_baseline(..., se_size=struct_size)` with default `se_size=25` matching `metric_contract_v1.json`)
  - `num_steady_shots: int` (true total number of conditioned steady shots across all points)
- Use baseline-subtracted `cond.preprocessed_mean` in `run_joint_solver` (`roi_joint_calibration.py:387, 394`) for `compute_sub_bin_variances` and flux-weighted sub-bin centroids, and use `cond.preprocessed_point_means` in per-point dispersion fitting (`gpu_calibration_engine.py:957`).
- Change `compute_plasma_dispersion` (`roi_joint_calibration.py:959, 964`) to sample standard deviation `ddof=1` when `len >= 2`.

### 2.5 Real Diagnostics, Gate G1 Wiring, Multi-Material Stats, True `numShots`, & Dynamic `add_sigma_nm`
- **Real `family_group_rms_diff` (`roi_joint_calibration.py:598-599`):** Replace hardcoded `0.02` by fitting Block B response curves separately on ferrous (`iron_mats`) vs. non-ferrous (`other_mats`) subsets over their common wavelength span (when span $\ge 60\text{ nm}$), normalizing each curve to geometric mean `1.0` on `grid_common`, and computing the actual RMS relative difference.
- **Gate G1 (`forward_model.py:147`):** Wire `forward_model.composition_scale_check(...)` into `gpu_calibration_engine.py` when $\ge 2$ materials share a family and common isolated lines, passing the real verification boolean to `evaluate_diagnostics` (emitting `COMPOSITION_SCALE_UNVERIFIED` on failure or when unverified).
- **Multi-Material `calibrationStats` (`gpu_calibration_engine.py:1002-1041`):** Compute ROI/whole-spectrum metrics and `reduced_chi2` across **all** materials in `fam_mats` (not only `fam_mats[0]`), reporting both family means (`roiChi2Reduced`, `roiRSquared`, `roiRmse`, `wholeSpectrumRSquared`, `wholeSpectrumRmse`) and `perMaterialStats` (including `numSteadyShots` per material), plus `fit_comparison` entries for every material in the family. Inspect Java `InstrumentProfile` / `CalibrationStats` DTO (read-only) to confirm Jackson unknown-property behavior before emitting new JSON keys.
- **True `numShots` (`gpu_calibration_engine.py:783, 1080`):** Define top-level `numShots` in `instrument_profile.json` as the total sum of `cond.num_steady_shots` across all calibrated materials (`sum(cond.num_steady_shots for cond in cond_map.values())`), and record per-material `numSteadyShots` inside `perMaterialStats`.
- **Dynamic `add_sigma_nm` (`gpu_calibration_engine.py:845, 1074`):** Compute `add_sigma_nm` via `scripts/spectral_resampling.py:calculate_add_sigma_nm` when instrument FWHM (configured in manifest or estimated via `measure_instrument_fwhm`) and source resolving power `R_nist` are available, defaulting to `0.0` only when instrument FWHM is absent or narrower than source FWHM, and persist the computed array into `lineSpreadAddSigmaNm` in `instrument_profile.json`.

## 3. Guardrails & Constraints
- **Do NOT modify `TRACK-CORE-01` contract files:** `scripts/match_metrics.py`, `src/main/resources/metric_contract_v1.json`, `src/test/resources/metric_parity_golden_fixtures.json`, and `scripts/parity_bridge.py` MUST remain untouched.
- **Do NOT touch Java files:** All Java fixes (`LIBSDataService.java`, `ConcentrationParameterEstimator.java`, `InputCompositionProcessor.java`) and empirical `finotelloNoiseBeta` calibration are deferred to `TRACK-CORE-02B`.
- **Do NOT regenerate or overwrite synthetic output CSVs on disk** (concurrent `TRACK-APP-01` verification depends on them).

## 4. Acceptance Criteria
1. `pytest tests/test_roi_joint_calibration_math.py tests/test_match_metrics_parity.py scripts/test_measured_conditioning.py` (plus existing Python calibration/RTE tests: `scripts/test_forward_model.py`, `scripts/test_roi_joint_calibration.py`, `scripts/test_validate_spectral_match.py`, `scripts/test_planted_truth_recovery.py`, `scripts/test_gpu_calibration_engine.py`) passes with 0 failures.
2. Dedicated unit tests in `tests/test_roi_joint_calibration_math.py` deterministically verify:
   - (a) `compute_element_parity` recovers `0.0 dex` (within `1e-9`) with `has_bias == False` on noiseless multi-zone synthetic data with non-contiguous `nz_indices`.
   - (b) `scan_absorption` attenuates `d_hot` (NOT `d_cool`) using full-spectrum cool-zone max normalization matching `apply_multi_zone_rte` 1:1 and recovers a planted $k > 0$ within `0.1`.
   - (c) `roiChi2Reduced` using true R6 sub-bin uncertainty $\sigma_{m,b}$ via `compute_spectral_metrics(..., normalization="none", num_params=n_params)` is $\mathcal{O}(1)$ on noisy synthetic benchmarks (NOT $\mathcal{O}(10^8)$).
   - (d) `ConditionedMaterial.num_steady_shots`, per-material `numSteadyShots`, and top-level `numShots` (sum across calibrated materials) equal actual conditioned steady shot counts (NOT `8192`).
   - (e) `apply_multi_zone_rte` is invoked consistently across `forward_model.py`, `roi_joint_calibration.py`, `gpu_calibration_engine.py`, and `validate_spectral_match.py` with max-normalized opacity $\tilde{d}_z$ and `NIST_UNIT_SCALE = 1.0`.
   - (f) Continuum consistency (`preprocessed_mean` / `preprocessed_point_means`, `se_size=25`), `ddof=1` plasma dispersion, actual computed `family_group_rms_diff` (not hardcoded `0.02`), Gate G1 wiring, multi-material `calibrationStats`, and dynamic `add_sigma_nm`.

## 5. Out of Scope
- Java defects (`LIBSDataService.java` RNG seeding, `ConcentrationParameterEstimator.java`, `InputCompositionProcessor.java` deep-copy) and empirical `finotelloNoiseBeta` calibration (`TRACK-CORE-02B`).
- Any edits to `TRACK-CORE-01` contract files or regeneration of synthetic CSV outputs.
- Adding a modeled continuum term to the forward model.
