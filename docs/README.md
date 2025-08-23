# LIBS Data Generator Documentation

## Overview
LIBS Data Generator is a Java application designed to automate the collection of Laser-Induced Breakdown Spectroscopy (LIBS) data from the NIST LIBS database. The tool supports generating datasets with various material compositions and includes features for compositional variations and statistical sampling.

## Core Features

### 1. Data Retrieval
- Automated fetching of LIBS spectral data from NIST database
- Support for custom wavelength ranges (default: 200-800 nm)
- Built-in retry mechanism and error handling for network requests
- Selenium-based web automation for reliable data extraction

### 2. Composition Management
- Support for complex material compositions with multiple elements
- Flexible input formats including:
  - Direct composition specification (e.g., "C:0.26,Mn:0.65,Si:0.22,Fe:98.87")
  - MatWeb GUID-based composition retrieval
- Automatic validation against periodic table elements
- Support for percentage ranges and remainder calculations

### 3. Compositional Variations
Three sampling modes available:
1. Uniform Distribution Sampling
   - Systematic variation within specified limits
   - Configurable step size and delta ranges
   - Ensures composition totals remain at 100%

2. Gaussian Distribution Sampling (Default)
   - Element-specific standard deviations
   - Configurable maximum delta values
   - Automatic normalization to maintain 100% total
   
3. Dirichlet Distribution Sampling (Planned)
   - To be implemented in future versions
   - Will provide more natural compositional variations

### 4. Data Management
- CSV format output for all spectral data
- Automatic file organization and backup
- Support for incremental updates to master dataset
- Built-in data validation and error checking

## Command Line Interface

Basic usage:
```bash
java -jar LIBSDataGenerator.jar -c <composition> [options]
```

Common Options:
- `-c, --composition`: Required. Material composition or MatWeb GUID
- `-mi, --min-wavelength`: Minimum wavelength (default: 200nm)
- `-ma, --max-wavelength`: Maximum wavelength (default: 800nm)
- `-o, --output`: Output directory path
- `-v, --compvar`: Enable compositional variations
- `-vm, --variation-mode`: Sampling mode (0=uniform, 1=gaussian, 2=dirichlet)
- `-vb, --vary-by`: Step size for variations
- `-md, --max-delta`: Maximum allowed variation
- `-ff, --force-fetch`: Force re-download of existing data
- `-na, --no-append`: Create new master file instead of appending

## Project Structure

```
LIBSDataGenerator/
├── src/main/java/com/medals/libsdatagenerator/
│   ├── controller/       # Main application control
│   ├── service/         # Core business logic
│   └── util/           # Helper utilities
├── conf/                # Configuration files (at root level)
├── build/
│   ├── scripts/         # Runtime scripts
│   ├── data/            # Output data storage
│   ├── lib/             # Dependencies
│   └── logs/            # Application logs
```

## Dependencies
- Java 21+
- Selenium WebDriver (4.28.1+)
- Apache Commons Libraries:
  - Commons CSV (1.13.0)
  - Commons CLI (1.9.0)
  - Commons IO (2.18.0)
  - Commons HTTP Client (4.5.13)

## Known Issues

1. Browser Automation Stability
   - Selenium WebDriver may occasionally fail to initialize
   - Connection timeouts can occur with slow networks
   - Resolution: Implement better retry mechanisms and error handling

2. Data Validation
   - Limited validation of NIST LIBS data quality
   - No automated checks for spectral line quality
   - Resolution: Add data quality metrics and validation

3. Memory Usage
   - Large datasets can cause memory pressure
   - Resolution: Implement streaming data processing

## Future Enhancements

1. Data Processing
   - [ ] Implement Dirichlet sampling for compositions
   - [ ] Add spectral line quality metrics
   - [ ] Support for custom element property files
   - [ ] Batch processing capabilities

2. User Interface
   - [ ] Add GUI interface
   - [ ] Real-time progress visualization
   - [ ] Interactive composition builder

3. Data Management
   - [ ] Database integration
   - [ ] Data versioning support
   - [ ] Export to multiple formats
   - [ ] Data compression options

4. Analysis Features
   - [ ] Basic spectral analysis tools
   - [ ] Statistical reporting
   - [ ] Data visualization components
   - [ ] Composition optimization suggestions

## Building from Source

```bash
mvn clean package
```

The build produces:
- `target/libsdatagenerator-1.0.0.jar`: Core library
- `target/libsdatagenerator-1.0.0-jar-with-dependencies.jar`: Standalone executable

## Contributing
Contributions are welcome! Please follow these steps:
1. Fork the repository
2. Create a feature branch
3. Commit your changes
4. Push to your branch
5. Create a Pull Request

## License
[License information should be added here]
