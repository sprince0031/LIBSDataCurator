# Implementation Plan - Instrument Profile Generation

## Phase 1: Core Models & Data Structures
Define the data model for the Instrument Profile and ensure it can be serialized/deserialized correctly.

- [ ] Task: Create `InstrumentProfile` and related POJOs
    - [ ] Create/Refine `InstrumentProfile.java` with fields for wavelengths, plasma parameters (two-zone), and stats.
    - [ ] Create `PlasmaZone.java` (Te, Ne) and `CalibrationStats.java` if needed for structure.
    - [ ] **Test:** Write unit tests for JSON serialization/deserialization using `org.json` or Jackson.
    - [ ] **Implement:** `InstrumentProfile` class.

- [ ] Task: Conductor - User Manual Verification 'Phase 1: Core Models' (Protocol in workflow.md) [checkpoint: ]

## Phase 2: Input Data Ingestion
Implement the logic to read and parse the reference calibration CSV file.

- [ ] Task: Implement `CalibrationDataParser`
    - [ ] **Test:** Create a sample CSV with headers (wavelengths) and rows (intensities). Write tests to verify correct parsing of wavelengths and data matrix.
    - [ ] **Implement:** `CalibrationDataParser` (or method in `InstrumentProfileService`) to use Apache Commons CSV.

- [ ] Task: Conductor - User Manual Verification 'Phase 2: Input Data Ingestion' (Protocol in workflow.md) [checkpoint: ]

## Phase 3: Calibration Service Logic
Implement the service that orchestrates the profile generation, including the estimation of plasma parameters.

- [ ] Task: Implement Parameter Estimation Logic
    - [ ] **Test:** Mock input data and expected plasma parameters. Write tests for the estimation logic (even if using simplified heuristics initially).
    - [ ] **Implement:** `InstrumentProfileService` methods to calculate/fit Te and Ne based on reference composition vs measured spectra. *Note: Ensure loose coupling from the controller.*

- [ ] Task: Implement Profile Generation Method
    - [ ] **Test:** Verify that `generateProfile(inputData, referenceComposition)` returns a valid, populated `InstrumentProfile` object.
    - [ ] **Implement:** Orchestration logic in `InstrumentProfileService`.

- [ ] Task: Conductor - User Manual Verification 'Phase 3: Calibration Service Logic' (Protocol in workflow.md) [checkpoint: ]

## Phase 4: CLI Controller Integration
Wire up the service to the command-line interface.

- [ ] Task: Update/Create `InstrumentProfileController`
    - [ ] **Test:** Write integration tests mocking the CLI arguments and verifying the Service is called with correct parameters.
    - [ ] **Implement:** Argument parsing for `-i`, `-c`, `-o`, `-n`. Call the service and write the output JSON.

- [ ] Task: Verify `calibrate.sh` / `calibrate.bat`
    - [ ] Check existing scripts or create new ones to invoke the new controller logic specifically.

- [ ] Task: Conductor - User Manual Verification 'Phase 4: CLI Controller Integration' (Protocol in workflow.md) [checkpoint: ]

## Phase 5: End-to-End Verification & Documentation
Ensure the entire flow works as expected and is documented.

- [ ] Task: End-to-End Integration Test
    - [ ] **Test:** Create a full system test: Run the CLI command with a real sample CSV -> Verify `instrument_profile.json` is created -> Verify JSON content validity.

- [ ] Task: Update Documentation
    - [ ] Update `README.md` or `docs/` with usage instructions for the new calibration feature.

- [ ] Task: Conductor - User Manual Verification 'Phase 5: E2E Verification' (Protocol in workflow.md) [checkpoint: ]
