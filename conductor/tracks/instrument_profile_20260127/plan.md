# Implementation Plan - Instrument Profile Generation

## Phase 1: Core Models & Data Structures
Define the data model for the Instrument Profile and ensure it can be serialized/deserialized correctly.

- [ ] Task: Create `InstrumentProfile` and related POJOs
    - [ ] Create/Refine `InstrumentProfile.java` with fields for wavelengths, plasma parameters (two-zone), and stats.
    - [ ] Create `PlasmaZone.java` (Te, Ne) and `CalibrationStats.java` for structure.
    - [ ] **Test:** Write unit tests for JSON serialization/deserialization using `org.json`.
    - [ ] **Implement:** `InstrumentProfile` and supporting classes.

- [ ] Task: Conductor - User Manual Verification 'Phase 1: Core Models' (Protocol in workflow.md) [checkpoint: ]

## Phase 2: Input Processing & Preprocessing
Implement logic to read, average, and clean the reference measurements.

- [ ] Task: Implement `CalibrationDataParser` with Averaging
    - [ ] **Test:** Verify parsing of wavelengths and averaging of multiple shots into a single spectrum.
    - [ ] **Implement:** Logic using Apache Commons CSV to produce an averaged `double[]` spectrum.

- [ ] Task: Implement Baseline Correction
    - [ ] **Test:** Verify that a spectrum with a known background offset is correctly corrected.
    - [ ] **Implement:** A baseline correction utility (e.g., polynomial fit or simple subtraction) in `InstrumentProfileService`.

- [ ] Task: Conductor - User Manual Verification 'Phase 2: Input Preprocessing' (Protocol in workflow.md) [checkpoint: ]

## Phase 3: Calibration Logic (Grid Search)
Implement the core optimization logic by comparing real data with NIST-generated theoretical spectra.

- [ ] Task: Integrate NIST Theoretical Spectrum Generation
    - [ ] **Refactor/Reuse:** Ensure `LIBSDataService` can be invoked with specific Te/Ne parameters to return spectral data without writing to disk.
    - [ ] **Implement:** A wrapper to generate combined two-zone synthetic spectra for a given composition.

- [ ] Task: Implement Grid Search & Optimization
    - [ ] **Test:** Verify that the grid search correctly identifies parameters that minimize MSE for a mock dataset.
    - [ ] **Implement:** Grid search over $T_e$ and $N_e$ ranges. Use MSE as the objective function to compare the preprocessed real spectrum vs. the NIST synthetic spectrum.

- [ ] Task: Conductor - User Manual Verification 'Phase 3: Calibration Service Logic' (Protocol in workflow.md) [checkpoint: ]

## Phase 4: CLI Controller Integration
Wire up the service to the command-line interface using existing configuration patterns.

- [ ] Task: Implement `InstrumentProfileController`
    - [ ] **Reuse:** Integrate `UserInputConfig` for handling shared parameters (wavelength range, resolution).
    - [ ] **Test:** Integration tests mocking CLI arguments and verifying service orchestration.
    - [ ] **Implement:** CLI parsing for `-i`, `-c`, `-o`, `-n` and invocation of `InstrumentProfileService`.

- [ ] Task: Create `calibrate.sh` / `calibrate.bat`
    - [ ] **Implement:** Shell scripts similar to `run.sh` that target the `InstrumentProfileController`.

- [ ] Task: Conductor - User Manual Verification 'Phase 4: CLI Controller Integration' (Protocol in workflow.md) [checkpoint: ]

## Phase 5: E2E Verification & Documentation
Final validation and user-facing documentation.

- [ ] Task: End-to-End Integration Test
    - [ ] **Test:** Run `calibrate.sh` with sample stainless steel readings -> Verify generated `instrument_profile.json` accuracy and structure.

- [ ] Task: Update User Documentation
    - [ ] Add a section to `README.md` and create/update `docs/CALIBRATION.md` explaining the instrument profiling workflow.

- [ ] Task: Conductor - User Manual Verification 'Phase 5: E2E Verification' (Protocol in workflow.md) [checkpoint: ]