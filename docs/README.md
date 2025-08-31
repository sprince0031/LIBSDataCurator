# LIBS Data Curator

[![Build](https://github.com/sprince0031/LIBSDataCurator/actions/workflows/build.yml/badge.svg?branch=main)](https://github.com/sprince0031/LIBSDataCurator/actions/workflows/build.yml)
[![Release](https://github.com/sprince0031/LIBSDataCurator/actions/workflows/release.yml/badge.svg)](https://github.com/sprince0031/LIBSDataCurator/actions/workflows/release.yml)

A tool for automated generation of Laser-Induced Breakdown Spectroscopy (LIBS) data from the NIST LIBS database. Generate comprehensive spectral datasets for material compositions with statistical compositional variations.

LIBS Data Curator enables systematic generation of synthetic spectral data for any material composition, helping researchers, materials scientists, and engineers gather LIBS reference data efficiently. Generate variations of base compositions to study compositional effects on spectral characteristics.

## Latest Changes

### [0.8.6] - 2025-08-30
- **Added**: New command-line options for resolution, plasma temperature, electron density, and advanced NIST LIBS parameters
- **Added**: UserInputConfig class to centralize parameter management
- **Added**: Support for wavelength units, conditions, ion charge limits, and intensity scale selection
- **Fixed**: Overview GUID not being passed to Dirichlet sampler (bug #44)
- **Fixed**: Issue with `-c` option behavior and CSV spectrum data writing
- **Enhanced**: Input parameter validation with enum-based options

[See full changelog](/docs/CHANGELOG.md) for complete list of changes.

## Download and Installation

### Latest Release

Download the latest version from [GitHub Releases](https://github.com/sprince0031/LIBSDataCurator/releases/latest):

- **Linux/macOS**: `LIBSDataCurator-0.8.6-linux.tar.gz`
- **Windows**: `LIBSDataCurator-0.8.6-windows.zip`

### Installation

#### Linux/macOS
```bash
# Download and extract
tar -xzf LIBSDataCurator-0.8.6-linux.tar.gz
cd LIBSDataCurator-0.8.6/

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
- `--min-wavelength`: Minimum wavelength in nm (default: 200)
- `--max-wavelength`: Maximum wavelength in nm (default: 800)
- `-o, --output`: Output directory path
- `-s, --series`: Process steel series from materials catalogue

**Compositional Variations:**
- `-v, --compvar`: Enable compositional variations
- `-n, --num-samples`: Number of compositional variations (default: 20)
- `--max-delta`: Maximum variation limit (default: 2.0)
- `-vm, --variation-mode`: Sampling mode (1: Dirichlet, 2: Gaussian)

**Advanced NIST LIBS Parameters:**
- `--resolution`: Wavelength resolution (default: 1000)
- `--plasma-temperature`: Plasma temperature in eV (default: 1)
- `--electron-density`: Electron density in cm^-3 (default: 1e17)
- `--wavelength-unit`: Unit (1: Angstrom, 2: Nanometer, 3: Micrometer)
- `--wavelength-condition`: Measurement condition (1: Mixed, 2: Vacuum)
- `--max-ion-charge`: Maximum ion charge (1: No limit, 2: 2+, 3: 3+, 4: 4+)
- `--min-relative-intensity`: Minimum intensity (1: No limit, 2: 0.1, 3: 0.01, 4: 0.001)
- `--intensity-scale`: Scale type (1: Energy flux, 2: Photon flux)

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