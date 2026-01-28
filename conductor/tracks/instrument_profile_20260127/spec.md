# Specification: Instrument Profile Generation

## Overview
Implement a feature to generate an `instrument_profile.json` file from real-world LIBS measurements (CSV format). This profile captures the specific characteristics of the target instrument, including the exact wavelength grid and optimized plasma parameters (Electron Temperature $T_e$ and Electron Density $N_e$) for a two-zone model (hot core and cool periphery). This profile enables the `LIBSDataCurator` to generate synthetic data that is highly representative of physical hardware.

## Core Requirements

### 1. Input Data Processing & Preprocessing
- **Input Format:** CSV file where rows are individual measurement shots and columns represent wavelength channels.
- **Parsing:** Extract the wavelength grid from headers/first row.
- **Preprocessing:**
    - **Averaging:** Calculate the mean intensity across all shots to create a single representative spectrum.
    - **Baseline Correction:** Implement or utilize a method to remove the spectral baseline/background noise from the averaged spectrum.
- **Code Reuse Mandate:** 
    - Utilize the existing `UserInputConfig` and `NistUrlOptions` flow for consistency.
    - Leverage existing NIST-interaction services (`LIBSDataService`, `SeleniumUtils`) to fetch theoretical line data. Avoid any logic duplication.

### 2. Instrument Profile Model (`InstrumentProfile`)
- **Structure:** JSON-serializable object.
- **Fields:**
    - `instrumentName` (String): Identifier for the instrument.
    - `wavelengths` (List<Double>): The exact wavelength channels of the spectrometer.
    - `plasmaParameters` (Object):
        - `hotCore`: { `Te`: double, `Ne`: double }
        - `coolPeriphery`: { `Te`: double, `Ne`: double }
    - `calibrationStats` (Object): Metadata about the fit quality (e.g., $R^2$, RMSE, MSE).

### 3. Calibration Logic (`InstrumentProfileService`)
- **Theoretical Spectrum Generation:**
    - For the provided reference composition, generate synthetic spectra using the existing tool logic for various combinations of $T_e$ and $N_e$.
- **Parameter Estimation (Grid Search):**
    - Implement a grid search algorithm to find the optimal $T_e$ and $N_e$ for both the hot core and cool periphery.
    - **Optimization Goal:** Minimize the difference (e.g., Mean Squared Error) between the preprocessed real spectrum and the combined synthetic two-zone spectrum.
    - **Combined Spectrum Model:** $I_{total} = I_{hot}(\lambda) + I_{cool}(1-\lambda)$. $\lambda$ is the weight that determines which is more pronounced.
- **Library Usage:** Use Apache Commons Math for optimization or statistical calculations where appropriate.

### 4. CLI Integration
- **Command:** `calibrate.sh` / `calibrate.bat` invoking the `InstrumentProfileController`.
- **Arguments:**
    - `-i, --input`: Path to input CSV (reference shots).
    - `-c, --composition`: Reference material composition (e.g., "Fe-98,C-2").
    - `-o, --output`: Path to save `instrument_profile.json`.
    - `-n, --name`: Instrument identifier.

### 5. Data & Output
- **Output:** Validated, indented `instrument_profile.json`.
- **Integrity:** Ensure the generated profile is structurally compatible with the main generation engine's input requirements.

## Technical Constraints
- **Language:** Java 21.
- **Libraries:** Apache Commons CSV, `org.json`, Apache Commons Math.
- **Quality Gate:** >95% code coverage; zero code duplication; strictly modular design.