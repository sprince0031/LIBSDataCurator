# Implementation Plan: ROI-Scoped Joint Response–Plasma Calibration for Best Spectral Match

## Track ID: `asd_roi_plasma_optimisation_20261112`

Requirement numbers (R1–R12) and acceptance criteria (AC1–AC12) refer to [`spec.md`](./spec.md).
All work stays on `feat/instrument-profile-optimisation`.

### Phase 1: Line Data and Flux-Conserving Resampling (R1, R2) [checkpoint: 790b72d]
- [x] Task: Write unit tests for line data in `scripts/test_nist_lines.py` (297ac0c)
    - [x] Tests for `parse_composition_dict` (string with `-` and `:` delimiters, ranges, `#` remainder, dict input, 100 wt% normalisation)
    - [x] Tests for `to_atomic_fractions`
    - [x] Tests for `ACCURACY_UNCERTAINTY` lookup, including grades with a trailing `'` and unknown grades
    - [x] Tests that `load_model_lines` returns only rows with `boltzmann_ok` and adds `wl_nm`, `gA`, `unc`
    - [x] Test that `lte_strength` equals `gA / wl_nm * exp(-Ek_eV / t_ev)`
    - [x] Confirm the tests fail (Red Phase)
- [x] Task: Implement R1 in `scripts/nist_lines.py` (2361cfc)
    - [x] Add the five functions and the constant
    - [x] Delete every `rel_intensity * 1e4` fallback in `scripts/`; remove the duplicate `parse_composition_dict` and `ACCURACY_WEIGHTS` from `gpu_calibration_engine.py`
    - [x] Check the grade percentages against the NIST ASD documentation and record the source in a comment
    - [x] Confirm the tests pass (Green Phase)
- [x] Task: Write unit tests for resampling in `scripts/test_spectral_resampling.py` (ca3c170)
    - [x] `pixel_edges` length and monotonicity on the real 2048-channel grid
    - [x] Flux conservation within 0.1% on a non-uniform source grid with gaps (AC4)
    - [x] Pixel sum of a single line changes by less than 2% as its centre moves across a pixel (AC4)
    - [x] `σ == 0` reduces to pixel-box averaging
    - [x] `measure_source_fwhm` recovers a known Gaussian width within 5%
    - [x] `measure_instrument_fwhm` recovers a planted 0.37 nm width within 15% and respects the clamp
    - [x] Confirm the tests fail (Red Phase)
- [x] Task: Implement `scripts/spectral_resampling.py` (7c5c052)
    - [x] `pixel_edges`, `resample_to_detector`, `measure_source_fwhm`, `measure_instrument_fwhm`, `add_sigma_nm`
    - [x] Confirm the tests pass (Green Phase)
- [x] Task: Conductor - User Manual Verification 'Phase 1: Line Data and Flux-Conserving Resampling' (Protocol in workflow.md)

### Phase 2: Measured-Data Conditioning (R3) [checkpoint: 640b469]
- [x] Task: Write unit tests in `scripts/test_measured_conditioning.py` (6b74601)
    - [x] Lit mask finds a planted run of unlit leading channels and subtracts the dark level
    - [x] Conditioning-shot rule discards planted anomalous leading shots and respects the `[3, 15]` clamp and the manifest override
    - [x] Saturated readings are excluded per shot; a channel with too few valid shots is masked, not zeroed
    - [x] Shot-scale normalisation removes a planted common-mode fluctuation
    - [x] `sigma_mean` equals the standard error of normalised shots
    - [x] `preprocess` is idempotent on an already-processed spectrum and is the same function used for measured and synthetic spectra
    - [x] Confirm the tests fail (Red Phase)
- [x] Task: Implement `scripts/measured_conditioning.py` (ed48c0f)
    - [x] `condition_material(csv_paths, manifest) -> ConditionedMaterial` returning wavelengths, mean, `sigma_mean`, valid mask, per-point means, `n0` per point
    - [x] `preprocess(spectrum, struct_size)`
    - [x] Confirm the tests pass (Green Phase)
- [x] Task: Conductor - User Manual Verification 'Phase 2: Measured-Data Conditioning' (Protocol in workflow.md)

### Phase 3: Forward Model on the Absolute Scale and Java Parity (R2.7, R4) [checkpoint: acaaa7c]
- [x] Task: Write unit tests in `scripts/test_forward_model.py` (0aef0f5)
    - [x] The R4 operator for one, two and three zones against hand-computed values
    - [x] `k = 0` reduces to a non-negative mixture; the opacity term uses the max-normalised zone spectrum
    - [x] Both `emissionScale` modes
    - [x] Gate G1 passes on two synthetic compositions that share a scale and fails when one is rescaled
    - [x] The test writes `src/test/resources/forward_model_fixture.json`
    - [x] Confirm the tests fail (Red Phase)
- [x] Task: Implement `scripts/forward_model.py` (c9929a9)
    - [x] Dictionary loader: NIST `Sum(calc)` and per-element sums of species columns, through `resample_to_detector`, with `NIST_UNIT_SCALE`
    - [x] `combine_zones`, `apply_response_and_scale`, `composition_scale_check`
    - [x] Confirm the tests pass (Green Phase)
- [x] Task: Write Java tests for parity (0b2bfcd)
    - [x] `SpectrumUtilsTest`: `resampleToDetector`, `combineNZones` and `applyInstrumentResponseAndNoise` reproduce the fixture to a relative `1e-9` in both `emissionScale` modes (AC5)
    - [x] `InstrumentProfileTest`: new optional fields round-trip; a profile without them still loads
    - [x] `InstrumentProfileServiceTest`: the manifest upsert preserves `validationMaterials`
    - [x] Confirm the tests fail (Red Phase)
- [x] Task: Implement the Java changes (5c1c6e7)
    - [x] `SpectrumUtils.resampleToDetector`; update `combineNZones` and `applyInstrumentResponseAndNoise`
    - [x] `LIBSDataService` uses `resampleToDetector` when the profile carries `lineSpreadAddSigmaNm`
    - [x] `InstrumentProfile` fields `emissionScale`, `lineSpreadAddSigmaNm`, `lineSpreadFwhmNm`, `spectralResponseValidRangeNm`, `validChannelRangeNm`
    - [x] `mvn test` passes (Green Phase)
- [x] Task: Conductor - User Manual Verification 'Phase 3: Forward Model on the Absolute Scale and Java Parity' (Protocol in workflow.md) (acaaa7c)

### Phase 4: ROI Catalogue (R5) [checkpoint: 89eeee2]
- [x] Task: Write unit tests in `scripts/test_roi_catalogue.py` (b266128)
    - [x] Candidates contain only model lines of certified elements at or above 0.05 wt%, inside the lit range
    - [x] Mask windows exclude air and contaminant lines
    - [x] Model detectability, line significance and measured detectability thresholds
    - [x] The prone rule
    - [x] Tercile stratification by `Ek_eV` per 25 nm band, and element coverage for every certified element
    - [x] Window merging, prone classification of merged windows, sub-bin partition and channel disjointness
    - [x] With the real ASD cache fixture and the SS551 composition, the catalogue contains Cu I 515.324 and 521.820 nm and no Cu II line (AC2)
    - [x] A source scan asserting that `roi_catalogue.py` and `roi_joint_calibration.py` contain no element symbols or category weights (AC3)
    - [x] Confirm the tests fail (Red Phase)
- [x] Task: Implement `scripts/roi_catalogue.py` (cd45c11)
    - [x] `RoiWindow` dataclass and `build_catalogue(family, compositions, model_lines, dictionary, conditioned) -> list[RoiWindow]`
    - [x] Response set and full set accessors
    - [x] Confirm the tests pass (Green Phase)
- [x] Task: Conductor - User Manual Verification 'Phase 4: ROI Catalogue' (Protocol in workflow.md) (89eeee2)

### Phase 5: Joint Solver and Planted-Truth Recovery (R6, R7, R9, R12)
- [x] Task: Write the planted-truth generator and test in `scripts/test_planted_truth_recovery.py` (b70d78a)
    - [x] Port the generator from `analysis/20_planted_truth_sim.py` and `analysis/22_planted_truth_runaway.py` as a test fixture
    - [x] Scenarios Clean, Harsh, Edge and Split with the thresholds of R12
    - [x] Confirm the tests fail (Red Phase)
- [x] Task: Write unit tests in `scripts/test_roi_joint_calibration.py` (b70d78a)
    - [x] Sub-bin variance model: measurement, model-error and floor terms; `ε_atomic` from line grades
    - [x] Block A returns non-negative weights and reproduces a planted mixture
    - [x] Block B recovers a planted smooth response from exact log-ratios, applies the `1/n_f` family factor and the gauge
    - [x] Knot-count reduction against the widest gap
    - [x] Three-start loop, convergence criteria, `responseRestartSpread`
    - [x] One test per flag of R9 that constructs its condition (AC8)
    - [x] Confirm the tests fail (Red Phase)
- [x] Task: Implement `scripts/roi_joint_calibration.py` (43b504c)
    - [x] Sub-bin operators, variance model, Huber IRLS weights
    - [x] Blocks A and B, the loop, restarts
    - [x] Diagnostics of R9, including the two-group response comparison
    - [x] Confirm unit tests and all four planted-truth scenarios pass (Green Phase, AC7)
- [ ] Task: Conductor - User Manual Verification 'Phase 5: Joint Solver and Planted-Truth Recovery' (Protocol in workflow.md)

### Phase 6: Family Zones, Absorption, Scale and Engine Integration (R8, R10, R13)
- [ ] Task: Write unit tests in `scripts/test_roi_joint_calibration.py` and `scripts/test_gpu_calibration_engine.py`
    - [ ] Family-shared weights with per-material scales recover a planted family state
    - [ ] Zone reduction to `numZones` and `zoneReductionPenalty`
    - [ ] Absorption scan recovers a planted `k`, returns 0 when there is none, and flags the scan limit
    - [ ] Median family `scaleFactor`, `materialScaleFactors`, `materialPlasmaZones`, `familySharingPenalty`
    - [ ] Dispersion from per-point solutions and its clamps
    - [ ] `elementParity` and the `ELEMENT_BIAS` flag
    - [ ] Engine: validation materials are excluded from fitting; profile and `regionsOfInterest.json` are upserted without losing other families; the module imports without `torch`; `--strict` exits with status 2 on a flag
    - [ ] Intermediate debug JSON dumps are written to `temp/calibration_debug/<materialFamily>/` without overwriting existing families (R13)
    - [ ] Confirm the tests fail (Red Phase)
- [ ] Task: Implement R8, R10, and R13 debug export
    - [ ] Stage functions in `roi_joint_calibration.py`
    - [ ] `scripts/match_metrics.py` with the shared metric functions
    - [ ] Rewire `GpuLibsCalibrationEngine.run()`; write the profile (§5.1) and `data/regionsOfInterest.json` (§5.2); leave legacy functions in place, unused
    - [ ] Instrument calibration pipeline to dump `conditioned_<material>.json`, `rois_<family>.json`, `solver_trace_<family>.json`, and `fit_comparison_<material>.json` under `temp/calibration_debug/<materialFamily>/`
    - [ ] Ensure non-destructive upserts preserve other families in `conf/instrument_profile.json` and debug storage
    - [ ] Confirm the tests pass (Green Phase)
- [ ] Task: Create interactive diagnostic notebook in `notebooks/calibration_inspector.ipynb` (R13, AC13)
    - [ ] Implement family and material interactive dropdown selectors reading from `temp/calibration_debug/`
    - [ ] Build Plotly interactive spectral viewer with zoom/pan, line species hover tooltips, and ROI window overlays
    - [ ] Build solver convergence trace visualizations (objective Φ and spline β evolution per iteration)
    - [ ] Build element parity log-ratio bar charts
    - [ ] Verify notebook execution against test fixture JSON dumps
- [ ] Task: Conductor - User Manual Verification 'Phase 6: Family Zones, Absorption, Scale and Engine Integration' (Protocol in workflow.md)

### Phase 7: Match Report and End-to-End Verification (R11, AC9–AC12)
- [ ] Task: Write unit tests in `scripts/test_validate_spectral_match.py`
    - [ ] Hold-out data is never read by the calibration stages
    - [ ] Per-material metrics, thin/prone split, element parity table
    - [ ] The three ablations (flat response, no absorption, legacy rendering)
    - [ ] Output schema of `data/spectralMatchReport.json`
    - [ ] Confirm the tests fail (Red Phase)
- [ ] Task: Implement `scripts/validate_spectral_match.py`
    - [ ] Confirm the tests pass (Green Phase)
- [ ] Task: Run the automated suites
    - [ ] `pytest scripts/`
    - [ ] `mvn clean test`
- [ ] Task: End-to-end calibration on the reference dataset
    - [ ] Set `validationMaterials` in `temp/calibration_manifest.json` if 473 and 556 are free
    - [ ] Run `./calibrate.sh` for every family; record `calibrationDiagnostics.flags`
    - [ ] Check AC2, AC3, AC6 and AC9 against the written profile and `data/regionsOfInterest.json`
    - [ ] Run `scripts/validate_spectral_match.py`; check AC11
    - [ ] Check AC10 with the Java forward model and noise disabled; run `./run.sh` to confirm generation completes
    - [ ] Record the before/after table (legacy rendering against the new calibration, per family, hold-out data) in the track notes
- [ ] Task: Conductor - User Manual Verification 'Phase 7: Match Report and End-to-End Verification' (Protocol in workflow.md)
