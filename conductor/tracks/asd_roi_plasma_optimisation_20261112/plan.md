# Implementation Plan: ASD & Composition-Informed ROI-Only Plasma Optimisation & `regionsOfInterest.json` Export

## Track ID: `asd_roi_plasma_optimisation_20261112`

### Phase 1: Centralize Parsing & ASD Scoring in `scripts/nist_lines.py`
- [ ] Task: Write unit tests for centralized composition parsing and LTE scoring in `scripts/test_nist_lines.py`
    - [ ] Write unit tests for `parse_composition_dict` covering string `-`/`:` formats, dictionary inputs, `#` remainder calculation, and 100 wt.% normalization
    - [ ] Write unit tests for `parse_elements_from_composition`
    - [ ] Write unit tests for `ACCURACY_WEIGHTS` mapping and `load_scored_asd_lines` validating LTE scoring, Einstein $A_{ki}$, Boltzmann factors, and accuracy weights
    - [ ] Verify test suite fails as expected prior to implementation (Red Phase)
- [ ] Task: Implement centralized parsing and scored ASD line loading in `scripts/nist_lines.py`
    - [ ] Define `ACCURACY_WEIGHTS` dictionary
    - [ ] Implement `parse_composition_dict(raw_comp) -> dict[str, float]`
    - [ ] Implement `parse_elements_from_composition(comp_str: str) -> list[str]`
    - [ ] Implement `load_scored_asd_lines(...) -> pd.DataFrame`
    - [ ] Verify unit tests pass (Green Phase)
- [ ] Task: Conductor - User Manual Verification 'Phase 1: Centralize Parsing & ASD Scoring in scripts/nist_lines.py' (Protocol in workflow.md)

### Phase 2: ASD & Composition-Informed ROI Discovery Module (`scripts/asd_roi_discovery.py`)
- [ ] Task: Write unit tests for ROI discovery engine in `scripts/test_asd_roi_discovery.py`
    - [ ] Write unit tests for element categorization (Matrix $\ge 5\%$, Minor $0.5\%-5\%$, Tramp $< 0.5\%$, filtering ambient air elements `H`, `O`, `N`)
    - [ ] Write unit tests for line scoring, concentration scaling ($C^{0.65}$), and matrix-interference isolation penalty
    - [ ] Write unit tests for line clustering ($\pm 0.85\text{ nm}$, merge $\le 0.40\text{ nm}$, max cluster width $6.0\text{ nm}$)
    - [ ] Write unit tests for channel grid mapping verifying strict disjointness (`startChannelIdx_k > endChannelIdx_{k-1}`) and minimum channel width $\ge 3$
    - [ ] Write unit tests for `SpectralRoiSpec` schema and normalized importance weights
    - [ ] Verify test suite fails as expected prior to implementation (Red Phase)
- [ ] Task: Implement `scripts/asd_roi_discovery.py`
    - [ ] Define `SpectralRoiSpec` dataclass
    - [ ] Implement `AsdRoiDiscoveryEngine` leveraging `scripts/nist_lines.py` as single source of truth
    - [ ] Implement `discover_rois_for_family(family_name)` returning `(rois, roi_weights_p, union_indices)`
    - [ ] Verify unit tests pass (Green Phase)
- [ ] Task: Conductor - User Manual Verification 'Phase 2: ASD & Composition-Informed ROI Discovery Module' (Protocol in workflow.md)

### Phase 3: Update `scripts/gpu_calibration_engine.py` with ROI-Exclusive Pass 2 & Metrics Export
- [ ] Task: Write unit tests in `scripts/test_gpu_calibration_engine.py` for ROI masking and metrics export
    - [ ] Write unit tests verifying `AsdLineAdvisor` refactoring (removal of `build_family_tramp_weights`, keeping `build_resonance_mask` and `get_asd_anchor_windows`)
    - [ ] Write unit tests for Pass-1 unweighted full-spectrum warm-up vs. Pass-2 ROI-masked optimization
    - [ ] Write unit tests for ROI-only OLS `scaleFactor`, hybrid objective function, and metrics calculation ($R^2_{\text{ROI}}$, $\text{RMSE}_{\text{ROI}}$, $R^2_{\text{whole}}$, $\text{RMSE}_{\text{whole}}$)
    - [ ] Write unit tests for non-destructive upsert into `data/regionsOfInterest.json`
    - [ ] Verify test suite fails as expected prior to implementation (Red Phase)
- [ ] Task: Implement ROI-exclusive Pass 2 & `data/regionsOfInterest.json` export in `scripts/gpu_calibration_engine.py`
    - [ ] Refactor imports to consume helpers from `nist_lines.py` and `asd_roi_discovery.py`
    - [ ] Remove `build_family_tramp_weights` from `AsdLineAdvisor`
    - [ ] Update `GpuLibsCalibrationEngine.run()`: Pass 1 with uniform full-spectrum weights; locked global P-spline $R(\lambda)$; Pass 2 strictly evaluated over $\mathcal{P}_{\text{ROI}, f}$
    - [ ] Implement `evaluate_and_export_rois_json(...)` to non-destructively upsert `<repo_root>/data/regionsOfInterest.json`
    - [ ] Verify unit tests pass (Green Phase)
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Update scripts/gpu_calibration_engine.py with ROI-Exclusive Pass 2 & Metrics Export' (Protocol in workflow.md)

### Phase 4: End-to-End Calibration & Parity Verification
- [ ] Task: Execute automated end-to-end test suite
    - [ ] Run `pytest scripts/` to ensure all Python tests pass
    - [ ] Run `mvn clean test` to verify zero regression across Java models and services
- [ ] Task: End-to-end calibration run & `data/regionsOfInterest.json` verification
    - [ ] Run `./calibrate.sh` on reference dataset / manifest
    - [ ] Validate `data/regionsOfInterest.json` schema, channel disjointness, minimum channel width $\ge 3$, and metric consistency with `conf/instrument_profile.json`
    - [ ] Run `./run.sh` to confirm synthetic spectrum synthesis parity
- [ ] Task: Conductor - User Manual Verification 'Phase 4: End-to-End Calibration & Parity Verification' (Protocol in workflow.md)
