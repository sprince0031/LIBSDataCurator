# Specification: Instrument Profile Generation

## Overview
Implement a feature to generate an `instrument_profile.json` file from real-world LIBS measurements (CSV format). This profile captures the specific characteristics of the target instrument, including the exact wavelength grid and optimized plasma parameters (Electron Temperature $T_e$ and Electron Density $N_e$) for a two-zone model (hot core and cool periphery). This profile enables the `LIBSDataCurator` to generate synthetic data that is highly representative of physical hardware.

## Non-negotiables:
- The feature has to work on real data and not mock data. The sample measured spectra CSV for calibration sample SS-CRM 469 is available at `/home/puser/Work/PhD/Datasets/Partner\ Data/LSA/Limerick\ Dataset\ Calibration\ Samples/Medals_CalibrationSamples/469_LSA_ava_S20251008173111_E20251008173212.csv` whose corresponding composition is `C-0.279,Si-0.421,Mn-0.598,P-0.015,S-0.020,Cr-11.93,Ni-0.246,Fe-#`. The composition given here is what should go as a user input to the `-c` flag when running `<TOOL_HOME>/bin/calibrate.sh`.
- Emphasis on real data also means the calibration step has to fetch synthetic LIBS spectra for corresponding input composition, Te and Ne from the [NIST LIBS website](https://physics.nist.gov/cgi-bin/ASD/lines1.pl). The LIBSDataService already does this for user inputs by constructing the URL with corresponding URL parameters. For whatever URL params are not provided by the user here when used for the calibration step, default params should be used if not already handled by the UserInputConfig logic.

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
- Ensure additions/changes have been made to script generation logic for `calibrate.sh` / `calibrate.bat` in `build-local.bat` / `build-local.sh` and in the github release action flow, `.github/workflows/release.yml`.

### 5. Data & Output
- **Output:** Validated, indented `instrument_profile.json`. This is to be stored in `<TOOL_HOME>/conf`.
- **Integrity:** Ensure the generated profile is structurally compatible with the main generation engine's input requirements.
- **Data Organization:**
    -   Calibration spectral data fetched from NIST must be saved to a new directory `<TOOL_HOME>/data/calibration/` (not the default `<TOOL_HOME>/data/NIST LIBS/`).
    -   Use subfolders `hot` and `cool` for respective spectra.
- **Visualization:** Generate a `calibration_report.ipynb` (Jupyter Notebook) in the output directory i.e., `<TOOL_HOME>/data/calibration` directory. 
    - The placeholder strings in the template notebook located in `<TOOL_HOME>/conf` such as `<HOT_CORE_TE>`, `<COOL_PERIPHERY_TE>`, etc. will should be added to the LIBSDataConstants class. In the notebook generation class, simply replace these placeholders with the actual values via the java replace() method.
    - After replacing, ensure that the final notebook is saved to `<TOOL_HOME>/data/calibration` as `calibration_report.ipynb`.
    - Then, run all the code cells in the notebook i.e., `<TOOL_HOME>/data/calibration/calibration_report.ipynb`. Decide how to do this with minimal effort from the `InstrumentProfileService` class like running a shell instance with the command(s) that can run all cells and save it in the notebook.
    - As a final step, convert `calibration_report.ipynb` to a PDF named `calibration_report.pdf` in the same directory path, `<TOOL_HOME>/data/calibration` for easy viewing by the user.
    - Plots:
      -   Raw measured spectra (all shots overlaid).
      -   Averaged measured spectrum.
      -   Baseline-corrected spectrum.
      -   **Individual Zone Spectra:** Plot of the optimized "Hot Core" spectrum and "Cool Periphery" spectrum separately.
      -   **Combined Synthetic Spectrum:** Plot of the final weighted sum ($I_{total} = w \cdot I_{hot} + (1-w) \cdot I_{cool}$).
      -   **Comparison:** Overlay of Averaged Measured vs. Combined Synthetic spectra with residual error.
    -   Context: Display final Te, Ne, and Weight parameters in the notebook.

### 6. Python Environment Management
- **Requirement:** The tool must verify if Python 3 is installed locally before attempting to generate the calibration report.
- **Graceful Failure:** If Python 3 is not found, the tool should log a warning and skip the report generation (PDF/Notebook execution), ensuring the core `instrument_profile.json` is still produced.
- **Virtual Environment:**
    - If Python 3 is available, create a local virtual environment in `<TOOL_HOME>/python_env`.
    - Automatically install required packages: `jupyter`, `nbconvert`, `pandas`, `matplotlib`, `numpy`.
    - Use this isolated environment for executing the notebook and converting it to PDF to avoid polluting the user's global Python environment.

## Technical Constraints
- **Language:** Java 21.
- **Libraries:** Apache Commons CSV, `org.json`, Apache Commons Math.
- **Quality Gate:** >95% code coverage; zero code duplication; strictly modular design.

## Additional context
The following is the directory structure for the deployable build of the LIBS Data Curator tool.  
<TOOL_HOME>  
    ├── bin  
    ├── conf  
    ├── data  
    │   └── NIST LIBS  
    ├── docs  
    ├── jre-custom  
    ├── lib  
    └── logs

The synthetic spectrum for LIBS will be calculated via LIBSDataService by querying the NIST LIBS database (https://physics.nist.gov/cgi-bin/ASD/lines1.pl). I want the new calibration feature being implemented by you to only use this and not complicate things with the ASD database to calculate the spectra locally. If you've failed to note before, the tool has the capability to recalculate for different input parameters after the initial spectrum has been fetched by changing whatever input values need to be changed in the recaluclate form on the result page of the NIST LIBS website. This is all done via selenium within the tool and the recalculation is done in the client-side browser's memory. It does not require separate requests to the NIST LIBS server everytime. This will work when doing a grid search for the Te and Ne parameters for hot and cool spectra.