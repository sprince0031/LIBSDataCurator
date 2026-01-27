# Specification: Instrument Profile Generation

## Overview
Implement a feature to generate an `instrument_profile.json` file from a set of real-world LIBS measurements (provided as a CSV). This profile captures the specific characteristics of the target instrument, specifically the wavelength grid and the plasma parameters (Electron Temperature $T_e$ and Electron Density $N_e$) for a two-zone model (hot core and cool periphery). This profile allows the `LIBSDataCurator` to generate synthetic data that closely mimics the actual instrument's output.

## Core Requirements

### 1. Input Data Processing
- **Input Format:** CSV file containing spectral data.
- **Content:** Rows represent individual measurements (shots). Columns represent wavelength channels.
- **Parsing:** Extract the wavelength grid from the CSV header or first row. Extract intensity values for processing.

### 2. Instrument Profile Model (`InstrumentProfile`)
- **Structure:** JSON-serializable object.
- **Fields:**
    - `instrumentName` (String): Identifier for the instrument.
    - `wavelengths` (List<Double>): The exact wavelength channels of the spectrometer.
    - `plasmaParameters` (Object):
        - `hotCore`: { `Te`: double, `Ne`: double }
        - `coolPeriphery`: { `Te`: double, `Ne`: double }
    - `calibrationStats` (Object): Metadata about the fit quality (e.g., $R^2$, RMSE).

### 3. Calibration Logic (`InstrumentProfileService`)
- **Wavelength Extraction:** reliably parse wavelengths from the input CSV.
- **Parameter Estimation:**
    - Implement (or finalize) logic to estimate $T_e$ and $N_e$ for two zones based on the input reference spectra and a known reference composition.
    - *Note:* If complex fitting algorithms are required, standardized Apache Commons Math optimization can be used.
- **Profile Generation:** Construct the `InstrumentProfile` object.

### 4. CLI Integration
- **Command:** Support a new execution mode (likely via `calibrate.sh` invoking a specific main class or flag).
- **Arguments:**
    - `-i, --input`: Path to input CSV.
    - `-c, --composition`: Reference material composition (e.g., "Fe-98,C-2").
    - `-o, --output`: Path to save `instrument_profile.json`.
    - `-n, --name`: Instrument name (optional).

### 5. Data & Output
- **Output File:** `instrument_profile.json` formatted with indentation for readability.
- **Validation:** Ensure generated JSON is valid and loadable by the main generation engine (future integration).

## Technical Constraints
- **Language:** Java 21.
- **Libraries:** Apache Commons CSV, Jackson or org.json (as per stack), Apache Commons Math.
- **Testing:** >95% code coverage.
- **Code Style:** Clean, DRY, modular.
