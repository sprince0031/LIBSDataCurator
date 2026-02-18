# LIBS Data Curator

[![Tests](https://github.com/sprince0031/LIBSDataCurator/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/sprince0031/LIBSDataCurator/actions/workflows/build.yml)
[![Build](https://github.com/sprince0031/LIBSDataCurator/actions/workflows/release.yml/badge.svg)](https://github.com/sprince0031/LIBSDataCurator/actions/workflows/release.yml)

A tool for automated generation of Laser-Induced Breakdown Spectroscopy (LIBS) data from the NIST LIBS database. Generate comprehensive spectral datasets for material compositions with statistical compositional variations.

LIBS Data Curator enables systematic generation of synthetic spectral data for any material composition, helping researchers, materials scientists, and engineers gather LIBS reference data efficiently. Generate variations of base compositions to study compositional effects on spectral characteristics.

## Download and Installation

### Latest Release

Download the latest version from [GitHub Releases](https://github.com/sprince0031/LIBSDataCurator/releases/latest):

- **Linux/macOS**: `LIBSDataCurator-0.9.5-linux.tar.gz`
- **Windows**: `LIBSDataCurator-0.9.5-windows.zip`

### Installation

#### Linux/macOS
```bash
# Download and extract
tar -xzf LIBSDataCurator-0.9.5-linux.tar.gz
cd LIBSDataCurator-0.9.5/

# Run the application
./bin/run.sh [options]
```

#### Windows
```batch
# Extract the ZIP file
# Navigate to extracted folder
bin\run.bat [options]
```

### System Requirements

**Recommended (Self-contained packages)**:
- No Java installation required
- Works on Windows, Linux, and macOS
- ~50MB download size

**For building from source**:
- Java 21 or higher
- Maven 3.6 or higher

## Latest Changes

### [0.9.5] - 2026-02-18
- **New**: Multi-zone plasma model for calibration — configurable n-zone plasma with `-z, --plasma-zones`
- **New**: Asymmetric Least Squares (ALS) baseline correction with `--lambda`, `--p`, `--max-iterations` options
- **New**: `SpectrumUtils` class for spectrum interpolation and processing
- **New**: CSV delimiter option (`-d, --delimiter`) for calibration input files
- **Fixed**: R² calculation logic corrected; now used alongside RMSE during optimisation
- **Fixed**: NIST spectrum interpolation for hot and cold zones
- **Changed**: `InstrumentProfile` refactored to use `double[]` arrays for wavelength data

### [0.9.1–0.9.4] - 2026-02-07
- **New**: Instrument Profile Calibration Mode — standalone calibration tool (issue #84) with `./bin/calibrate.sh` / `bin\calibrate.bat`
- **New**: Automated Jupyter Notebook-based PDF calibration reports with spectrum comparison plots
- **New**: Dedicated `CmdlineParserUtil` class for cleaner argument parsing (extracted from `CommonUtils`)
- **New**: NIST data fetching augmented for calibration spectrum generation
- **Changed**: Profile JSON saved to `conf/` directory; CLI parsing separated from utility classes

### [0.9.0] - 2025-11-28
- **New**: Client-side recalculation for NIST LIBS data with significant performance improvements
- **New**: Decimal places option (`-nd, --num-decimal-places`) and debug mode (`-d, --debug`)
- **Fixed**: Wavelength unit default corrected to Nanometers (issue #74)

[See full changelog](/docs/CHANGELOG.md) for complete list of changes.


## Usage

### Basic Usage

```bash
# Show help and available options
./bin/run.sh

# Simple composition analysis
./bin/run.sh -c "Fe-80,C-20" --min-wavelength 200 --max-wavelength 300

# Generate compositional variations with advanced parameters
./bin/run.sh -c "Fe-70,C-1.5,Mn-1,Cr-#" -v -n 20 --max-delta 2.0 --plasma-temperature 1.5 --electron-density 1e18

# Use different wavelength units (Angstrom)
./bin/run.sh -c "Fe-80,C-20" --wavelength-unit 1 --resolution 2000

# Use MatWeb GUID for composition
./bin/run.sh -c "a1d2f3e4c5b6a7f8e9d0c1b2a3f4e5d6" -o steel_data.csv
```

### Command Line Options

**Basic Options:**
- `-c, --composition`: Material composition (e.g., "Fe-80,C-20") or MatWeb GUID
- `--min-wavelength`: Minimum wavelength in nm (default: 240)
- `--max-wavelength`: Maximum wavelength in nm (default: 420)
- `-o, --output`: Output directory path
- `-s, --series`: Process steel series from materials catalogue

**Compositional Variations:**
- `-v, --compvar`: Enable compositional variations
- `-n, --num-samples`: Number of compositional variations (default: 20)
- `--max-delta`: Maximum variation limit (default: 2.0)
- `-vm, --variation-mode`: Sampling mode (1: Dirichlet, 2: Gaussian)
- `-nd, --num-decimal-places`: Number of decimal places for composition percentages (default: 3)

**Advanced NIST LIBS Parameters:**
- `--resolution`: Wavelength resolution (default: 1000)
- `--plasma-temperature`: Plasma temperature in eV (default: 1)
- `--electron-density`: Electron density in cm^-3 (default: 1e17)
- `--wavelength-unit`: Unit (1: Angstrom, 2: Nanometer (default), 3: Micrometer)
- `--wavelength-condition`: Measurement condition (1: Mixed, 2: Vacuum)
- `--max-ion-charge`: Maximum ion charge (1: No limit, 2: 2+, 3: 3+, 4: 4+)
- `--min-relative-intensity`: Minimum intensity (1: No limit, 2: 0.1, 3: 0.01, 4: 0.001)
- `--intensity-scale`: Scale type (1: Energy flux, 2: Photon flux)

**Machine Learning Dataset Options:**
- `-ct, --class-type`: Class label type for ML dataset generation:
  - `1`: Composition percentages (default) - Multi-output regression with element weight percentages
  - `2`: Material grade name - Multi-class classification with specific material grades
  - `3`: Material type - Multi-class classification with broader material categories
- `-gs, --gen-stats`: Generate and save dataset statistics (mean, standard deviation)

**Materials Processing Options:**
- `-sc, --scale-coating`: Scale down all elements proportionally when applying coating percentages (default: subtract from dominant element)

**Debugging Options:**
- `-d, --debug`: Run with visible browser for troubleshooting Selenium workflows
- `-sd, --seed`: Seed for samplers to ensure reproducibility

### Instrument Profile Calibration

The calibration mode generates an instrument profile from real LIBS measurement data. This profile contains the wavelength grid and n-zone plasma parameters (Te, Ne, and zone weight) optimised to match your instrument's characteristics. Measured spectra undergo automatic baseline correction (Asymmetric Least Squares) before fitting.

```bash
# Generate instrument profile from sample measurements
./bin/calibrate.sh -i sample_readings.csv -c "Fe-98.0,C-0.5,Mn-1.0,Si-0.5"

# With custom output path, instrument name, and 3 plasma zones
./bin/calibrate.sh -i sample_readings.csv -c "Fe-98.0,C-0.5,Mn-1.0,Si-0.5" \
    -o my_instrument_profile.json -n "Ocean Optics HR2000" -z 3

# Custom baseline correction parameters
./bin/calibrate.sh -i sample_readings.csv -c "Fe-98.0,C-0.5,Mn-1.0,Si-0.5" \
    --lambda 100000 --p 0.001 --max-iterations 20

# With semicolon delimiter
./bin/calibrate.sh -i sample_readings.csv -c "Fe-98.0,C-0.5,Mn-1.0,Si-0.5" -dl ";"

# Windows
bin\calibrate.bat -i sample_readings.csv -c "Fe-98.0,C-0.5,Mn-1.0,Si-0.5"
```

**Calibration Options:**
- `-i, --input`: Path to sample LIBS measurement CSV file (required)
- `-c, --composition`: Exact composition of reference material (required)
- `-o, --output`: Output path for profile JSON (default: `conf/instrument_profile.json`)
- `-n, --name`: Instrument name/identifier (default: `Unknown`)
- `-z, --plasma-zones`: Number of plasma zones to fit (default: 2)
- `-dl, --delimiter`: Delimiter used in input CSV file (default: `;`)
- `-d, --debug`: Run with visible browser for troubleshooting NIST data fetching

**Baseline Correction Options:**
- `-bl, --lambda`: Smoothness parameter λ (default: 10000)
- `-bp, --p`: Asymmetry parameter p (default: 0.001)
- `-bi, --max-iterations`: Maximum iterations (default: 10)

**Input CSV Format:**
- Column headers should contain wavelength values (in nm)
- Each row represents one measurement shot
- Non-numeric columns (e.g., "Shot", "ID") are ignored

**Output Profile:**
The generated JSON profile contains:
- Wavelength grid extracted from your instrument
- N-zone plasma parameters (configurable via `-z`):
  - Each zone with independent Te (temperature), Ne (electron density), and weight
- Fit quality metrics (R², RMSE)

An automated PDF calibration report with spectrum comparison plots is generated at `data/calibration/`. The generated Jupyter notebook used to generate the is report is also available in the same directory location with file name, `calibration_report.ipynb`.

### Class Label Types

The tool supports different class label formats for machine learning applications:

```bash
# Default mode: Includes both material grade name and material type columns
./bin/run.sh -c "Fe-80,C-20"

# Material grade name classification only (e.g., "AISI 4140")
./bin/run.sh -c "some_matweb_guid" --class-type 2

# Material type classification only (e.g., "aisi 10xx series") 
./bin/run.sh -s aisi.10xx.series --class-type 3

# Composition percentages only (for multi-output regression)
./bin/run.sh -c "Fe-80,C-20" --class-type 1
```

**Default Behavior:** When no `--class-type` is specified, the CSV includes both `material_grade_name` and `material_type` columns alongside element compositions, providing maximum flexibility for different ML approaches.

### Output

The tool generates:
- Individual CSV files for each composition's spectral data
- Master CSV file combining all results with wavelength and composition data
- Automatic file organization in the specified output directory

## Building from Source

For development or custom builds, see [BUILD.md](/docs/BUILD.md) for complete instructions to clone the repository and build the tool from source.

### Quick Build

```bash
# Clone repository
git clone https://github.com/sprince0031/LIBSDataCurator.git
cd LIBSDataCurator

# Build with Maven (requires Java 21+)
mvn clean package

# Create self-contained package
./build/scripts/build-local.sh        # Linux/macOS
build\scripts\build-local.bat         # Windows
```

## Reporting Issues

If you encounter bugs, have feature requests, or need support:

1. **Search existing issues** at [GitHub Issues](https://github.com/sprince0031/LIBSDataCurator/issues)
2. **Create a new issue** with:
   - Clear description of the problem or request
   - Steps to reproduce (for bugs)
   - Your operating system and Java version
   - Relevant log files or error messages
   - Sample command line arguments used

### Contributing

Contributions are welcome! Please:
1. Fork the repository
2. Create a feature branch
3. Make your changes with appropriate tests
4. Submit a pull request with a clear description

For major changes, please open an issue first to discuss the proposed modifications.
