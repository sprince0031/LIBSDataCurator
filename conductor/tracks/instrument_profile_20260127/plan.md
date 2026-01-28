# Implementation Plan - Instrument Profile Generation

## Phase 1: Core Models & Data Structures
Define the data model for the Instrument Profile and ensure it can be serialized/deserialized correctly.

- [x] Task: Create `InstrumentProfile` and related POJOs
    - [x] Create/Refine `InstrumentProfile.java` with fields for wavelengths, plasma parameters (two-zone), and stats.
    - [x] Create `PlasmaZone.java` (Te, Ne) and `CalibrationStats.java` for structure.
    - [x] **Test:** Write unit tests for JSON serialization/deserialization using `org.json`.
    - [x] **Implement:** `InstrumentProfile` and supporting classes.

- [ ] Task: Conductor - User Manual Verification 'Phase 1: Core Models' (Protocol in workflow.md) [checkpoint: ]

## Phase 2: Input Processing & Preprocessing
Implement logic to read, average, and clean the reference measurements.

- [x] Task: Implement `CalibrationDataParser` with Averaging
    - [x] **Test:** Verify parsing of wavelengths and averaging of multiple shots into a single spectrum.
    - [x] **Implement:** Logic using Apache Commons CSV to produce an averaged `double[]` spectrum.

- [x] Task: Implement Baseline Correction
    - [x] **Test:** Verify that a spectrum with a known background offset is correctly corrected.
    - [x] **Implement:** A baseline correction utility (e.g., polynomial fit or simple subtraction) in `InstrumentProfileService`.

- [ ] Task: Conductor - User Manual Verification 'Phase 2: Input Preprocessing' (Protocol in workflow.md) [checkpoint: 8656909]

## Phase 3: NIST Interaction Extensions
Enhance the existing Selenium-based service to support the calibration workflow, specifically allowing dynamic updates to Te and Ne without reloading the page.

- [x] Task: Extend `NISTUtils` for Plasma Parameters
    - [x] **Implement:** `updatePlasmaParameters(double electronTemp, double electronDensity)` method in `NISTUtils`. This should locate the corresponding input fields in the NIST LIBS result form, update them, and handle the "Recalculate" click.
    - [x] **Implement:** `downloadCalibrationSpectrum(Path destination)` logic that handles the specific download flow for calibration iterations (saving to hot/cool temp folders).

- [x] Task: Extend `LIBSDataService` for Calibration Loop
    - [x] **Implement:** `fetchCalibrationSpectrum(double te, double ne, Path outputPath, boolean keepSessionAlive)` method.
    - [x] **Logic:** This method must reuse the existing Selenium session. If the session is new, it does the full fetch. If it's active, it calls `NISTUtils.updatePlasmaParameters` + `recalculate` + `download`.

- [ ] Task: Conductor - User Manual Verification 'Phase 3: NIST Interaction Extensions' (Protocol in workflow.md) [checkpoint: ]

## Phase 4: Calibration Logic (Grid Search via Selenium)
Implement the optimization loop using the enhanced `LIBSDataService`.

- [x] Task: Implement Grid Search & Optimization Loop
    - [x] **Refactor:** `InstrumentProfileService.optimizePlasmaParameters` to use `LIBSDataService` for spectrum generation instead of local physics.
    - [x] **Implement:** The Grid Search loop:
        1. Define ranges for Te and Ne.
        2. Iteration Loop:
            a. Set params for **Hot Core** -> `LIBSDataService.fetchCalibrationSpectrum(...)`.
            b. Set params for **Cool Periphery** -> `LIBSDataService.fetchCalibrationSpectrum(...)`.
            c. Read downloaded CSVs, combine spectra ($I_{total}$), calculate RMSE vs Measured.
            d. Check convergence (stop if no improvement for N steps, default 10).
    - [x] **Implement:** CLI parameter for convergence steps (`--convergence-steps`, default 10).

- [ ] Task: Conductor - User Manual Verification 'Phase 4: Calibration Logic' (Protocol in workflow.md) [checkpoint: ]

## Phase 5: CLI Controller Integration
Wire up the service to the command-line interface using existing configuration patterns.

- [x] Task: Implement `InstrumentProfileController`
    - [x] **Reuse:** Integrate `UserInputConfig` for handling shared parameters (wavelength range, resolution).
    - [x] **Test:** Integration tests mocking CLI arguments and verifying service orchestration.
    - [x] **Implement:** CLI parsing for `-i`, `-c`, `-o`, `-n` and invocation of `InstrumentProfileService`.

- [x] Task: Create `calibrate.sh` / `calibrate.bat`
    - [x] **Implement:** Shell scripts similar to `run.sh` that target the `InstrumentProfileController`.

- [ ] Task: Conductor - User Manual Verification 'Phase 5: CLI Controller Integration' (Protocol in workflow.md) [checkpoint: ]

## Phase 6: End-to-End Verification & Documentation
Ensure the entire flow works as expected and is documented.

- [x] Task: Refactor Output & Data Organization
    - [x] **Implement:** Save fetched/generated spectral data to `<TOOL_HOME>/data/calibration/hot` and `cool`.
    - [x] **Implement:** Ensure `instrument_profile.json` is saved to `<TOOL_HOME>/conf`.

- [x] Task: Implement Jupyter Notebook Generator
    - [x] **Refactor:** Use a template-based approach (`calibration_report_template.ipynb` in resources).
    - [x] **Implement:** Logic to read template, replace placeholders, write to output, and execute cells.
    - [x] **Test:** Verify generation and execution (if possible) of the notebook.

- [~] Task: End-to-End Integration Test
    - [ ] **Test:** Create a full system test: Run the CLI command with the REAL sample CSV -> Verify `instrument_profile.json` is created -> Verify JSON content validity.

- [ ] Task: Update User Documentation
    - [ ] Add a section to `README.md` and create/update `docs/CALIBRATION.md` explaining the instrument profiling workflow.

## Phase 7: Python Environment Management
Ensure robust report generation by managing a local Python environment.

- [x] Task: Implement `PythonUtils`
    - [x] **Implement:** Check for local Python 3 installation.
    - [x] **Implement:** Logic to create `venv` at `<TOOL_HOME>/python_env` if it doesn't exist.
    - [x] **Implement:** `installRequirements` to `pip install` necessary packages.
    - [x] **Implement:** Helper to get the absolute path to `jupyter` executable within the venv.

- [x] Task: Integrate Python Env with `InstrumentProfileService`
    - [x] **Refactor:** `generateProfile` to call `PythonUtils` setup before report generation.
    - [x] **Refactor:** `executeNotebook` and `convertNotebookToPdf` to use the venv paths.
    - [x] **Implement:** Graceful fallback (skip reporting) if Python is missing.

- [ ] Task: Conductor - User Manual Verification 'Phase 7: Python Env' (Protocol in workflow.md) [checkpoint: ]