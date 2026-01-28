# Implementation Plan - Instrument Profile Generation

## Phase 1: Core Models & Data Structures
Define the data model for the Instrument Profile and ensure it can be serialized/deserialized correctly.

- [x] Task: Create `InstrumentProfile` and related POJOs fc44c25
    - [x] Create/Refine `InstrumentProfile.java` with fields for wavelengths, plasma parameters (two-zone), and stats.
    - [x] Create `PlasmaZone.java` (Te, Ne) and `CalibrationStats.java` for structure.
    - [x] **Test:** Write unit tests for JSON serialization/deserialization using `org.json`.
    - [x] **Implement:** `InstrumentProfile` and supporting classes.

- [ ] Task: Conductor - User Manual Verification 'Phase 1: Core Models' (Protocol in workflow.md) [checkpoint: ]

## Phase 2: Input Processing & Preprocessing
Implement logic to read, average, and clean the reference measurements.

- [x] Task: Implement `CalibrationDataParser` with Averaging 6f6cf6b
    - [x] **Test:** Verify parsing of wavelengths and averaging of multiple shots into a single spectrum.
    - [x] **Implement:** Logic using Apache Commons CSV to produce an averaged `double[]` spectrum.

- [x] Task: Implement Baseline Correction 6f6cf6b
    - [x] **Test:** Verify that a spectrum with a known background offset is correctly corrected.
    - [x] **Implement:** A baseline correction utility (e.g., polynomial fit or simple subtraction) in `InstrumentProfileService`.

- [x] Task: Conductor - User Manual Verification 'Phase 2: Input Preprocessing' (Protocol in workflow.md) [checkpoint: 8656909]

## Phase 3: Calibration Logic (Grid Search)
Implement the core optimization logic by comparing real data with NIST-generated theoretical spectra.

- [x] Task: Integrate NIST Theoretical Spectrum Generation 26b930f
    - [x] **Refactor/Reuse:** Ensure `LIBSDataService` can be invoked with specific Te/Ne parameters to return spectral data without writing to disk.
    - [x] **Implement:** A wrapper to generate combined two-zone synthetic spectra for a given composition.

- [x] Task: Implement Grid Search & Optimization 26b930f
    - [x] **Test:** Verify that the grid search correctly identifies parameters that minimize MSE for a mock dataset.
    - [x] **Implement:** Grid search over $T_e$ and $N_e$ ranges. Use MSE as the objective function to compare the preprocessed real spectrum vs. the NIST synthetic spectrum.

- [x] Task: Conductor - User Manual Verification 'Phase 3: Calibration Service Logic' (Protocol in workflow.md) [checkpoint: 1c65b9c]

## Phase 4: CLI Controller Integration
Wire up the service to the command-line interface using existing configuration patterns.

- [x] Task: Implement `InstrumentProfileController` 4e9bf57
    - [x] **Reuse:** Integrate `UserInputConfig` for handling shared parameters (wavelength range, resolution).
    - [x] **Test:** Integration tests mocking CLI arguments and verifying service orchestration.
    - [x] **Implement:** CLI parsing for `-i`, `-c`, `-o`, `-n` and invocation of `InstrumentProfileService`.

- [x] Task: Create `calibrate.sh` / `calibrate.bat` 4e9bf57
    - [x] **Implement:** Shell scripts similar to `run.sh` that target the `InstrumentProfileController`.

- [ ] Task: Conductor - User Manual Verification 'Phase 4: CLI Controller Integration' (Protocol in workflow.md) [checkpoint: ]

## Phase 5: End-to-End Verification & Documentation
Ensure the entire flow works as expected and is documented.

- [ ] Task: Implement Jupyter Notebook Generator
    - [ ] **Test:** Verify generation of a valid .ipynb JSON structure containing the spectral data arrays and plotting code.
    - [ ] **Implement:** Logic to create `calibration_report.ipynb` using `org.json`. It should contain pre-filled Python code cells to plot:
        -   Raw and Averaged Measured Spectra.
        -   Baseline Corrected Spectrum.
        -   Individual Hot/Cool Synthetic Spectra.
        -   Combined Synthetic vs. Measured Overlay.
        -   Parameter Context (Te, Ne, Weight).

- [ ] Task: End-to-End Integration Test
    - [ ] **Test:** Create a full system test: Run the CLI command with a real sample CSV -> Verify `instrument_profile.json` is created -> Verify JSON content validity.

- [ ] Task: Update User Documentation
    - [ ] Add a section to `README.md` and create/update `docs/CALIBRATION.md` explaining the instrument profiling workflow.

- [ ] Task: Conductor - User Manual Verification 'Phase 5: E2E Verification' (Protocol in workflow.md) [checkpoint: ]