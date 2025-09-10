# Changelog

All notable changes to the LIBS Data Generator project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.8.7] - 2025-09-10

### Added
- **Class Label Type Selection**: New `-ct, --class-type` option for machine learning dataset generation
  - Type 1: Composition percentages (default) - Multi-output regression with element weight percentages
  - Type 2: Material grade name - Multi-class classification with specific material grades (e.g., AISI 4140)
  - Type 3: Material type - Multi-class classification with broader material categories (e.g., Carbon steel)
- **Enhanced CSV Output**: Default behavior now includes both `material_grade_name` and `material_type` columns
- **Selective Class Columns**: When `-ct` flag is specified, only the requested class column is included
- **General Purpose Design**: Uses "Material" terminology instead of "Steel" for broader applicability
- **Smart Fallbacks**: Handles missing data gracefully with "Unknown Grade" or "Unknown Type"
- **Series Processing**: Converts series keys like `aisi.10xx.series` to readable format `aisi 10xx series`

### Changed
- **Default CSV Output**: Now includes material classification columns by default for enhanced ML capabilities
- **MaterialGrade Model**: Enhanced to track series information for material type classification

## [0.8.6] - 2025-08-31
- New command-line options for resolution, plasma temperature, electron density.
- Support for wavelength unit selection (Angstrom, Nanometer, Micrometer)
- Support for wavelength condition selection (vacuum/air combinations)
- Support for maximum ion charge limits (2+, 3+, 4+, no limit)
- Support for minimum relative intensity thresholds
- Support for intensity scale selection (energy flux vs photon flux)
- UserInputConfig class to centralize parameter management and replace scattered parameter passing
- Enhanced input parameter validation with enum-based options
- Reorganized model classes into `matweb` and `nist` packages for better structure

### Changed
- **BREAKING:** Removed backward compatibility for reading legacy CSV files with colon-based filenames. The application now only generates and reads CSV files using the new cross-platform compatible filename format with hyphens instead of colons (e.g., `C-0.26;Fe-99.74` instead of `C:0.26;Fe:99.74`). Existing legacy files will no longer be recognized or read by the system.

## [0.8.5] - 2025-08-26
### Added
- Comprehensive GitHub Actions CI/CD workflows with build and release automation
- Self-contained packaging scripts (build-local.sh, build-local.bat) with bundled JRE support
- Cross-platform support (Windows and Linux/macOS packages)
- Optimized custom JRE using jlink with security modules
- SSL/TLS support with updated certificate store for NIST LIBS connectivity
- Enhanced build documentation (BUILD.md) with complete build instructions
- Copilot instructions for development support

### Fixed
- Fixed write permissions in release workflow
- Fixed logging to file functionality in local build scripts
- Fixed issue with number of input samples generated not accounting for original composition
- Changed default `-n` parameter from 50 to 20 samples
- Updated `-vm` description to reflect Dirichlet sampling as default
- Windows incompatible filenames for NIST CSV data by replacing colons with hyphens
- SSL handshake exceptions resolved in self-contained packages

### Changed
- **BREAKING:** Migrated configuration files from `build/conf/` to `conf/` directory
- Updated GitHub workflow files to reflect changes to build process
- **BREAKING:** Removed backward compatibility for reading legacy CSV files with colon-based filenames
- Cleaned up build process to remove duplicate packaging steps and reorganized directory structure
- Enhanced CLI help with improved composition parameter validation
- Migrated build system to use custom JRE creation for distribution packages
- Simplified Maven configuration by removing complex assembly descriptors

## [0.8.0] - 2025-07-29
### Added  
- **Complete Dirichlet sampling implementation**: Finished Dirichlet sampling implementation using Apache Commons RNG for more realistic compositional variations
- **Sampler interface architecture**: Refactored sampling methods into common interface with DirichletSampler and GaussianSampler implementations  
- **Archive.org fallback mechanism**: Added fallback to use archived version of datasheet from Wayback Machine when MatWeb is down
- **Enhanced compositional variations**: Support for both Dirichlet and Gaussian sampling with proper value clamping within element ranges
- **Materials catalogue input feature**: Support for accepting a series or multiple series of MatWeb datasheets
- **Model classes**: Added MaterialGrade, SeriesInput, ElementStatistics, and SeriesStatistics models for better data structure

### Enhanced
- **Controller architecture**: Major refactoring of controller structure to remove redundant code and improve readability  
- **Element model**: Moved Element class to model package as part of code structure improvements
- **Statistical accuracy**: Better parameter estimation and more realistic compositional variations using Dirichlet distributions
- **Architecture documentation**: Added high-level architecture diagram to documentation assets

### Fixed
- **Dirichlet sampling constraints**: Ensured proper clamping of sampled values within individual material value ranges from datasheets
- **Gaussian sampling logic**: Improved maxDelta calculation using min/max range for more accurate sampling  
- **Test coverage**: Added JUnit @Test annotations for proper test execution during build

### Changed
- **Default sampling method**: Changed from uniform/Gaussian to Dirichlet sampling for more realistic compositional variations
- **Code structure**: Significant refactoring of sampler logic and controller orchestration for better maintainability
- **Version**: Updated from 0.7 to 0.8 in pom.xml
- **Dependencies**: Updated and enhanced dependency management for sampling libraries

## [0.7.0] - 2025-06-20  
### Added
- **MatWeb datasheet scraping**: Implemented material composition inputs from MatWeb datasheet scraping capabilities
- **Materials catalogue support**: Added steel series processing with steel_series_catalog.properties file containing MatWeb GUID collections for steel grades  
- **Series statistics extraction**: New service to extract statistical information from MatWeb overview sheets with average values and grade counts
- **Concentration parameter estimation**: Enhanced statistical modeling for concentration distributions from series data
- **Steel series CLI support**: New `-s/--series` command-line option to process steel series from materials catalogue
- **GitHub Actions workflow**: Added automated testing workflow for continuous integration
- **Initial Dirichlet sampling**: Partial implementation of Dirichlet sampling method for compositional variations
- **Element composition ranges**: Added composition ranges for elements from MatWeb data in Element class with updated data parsing logic

### Enhanced  
- **MatWeb integration**: Improved data processing with better error handling and series data analysis
- **CSV parsing**: Fixed CSV parsing issues for reading from cached CSV files
- **Element model**: Enhanced Element class with composition range support from MatWeb datasheets

### Fixed  
- **Static file paths**: Fixed static path to material series properties file to load using file path relative to classpath
- **CSV data handling**: Resolved issues with CSV parsing for cached files
- **Material series processing**: Enhanced series processing logic for multiple material grades

### Changed
- **CLI interface**: Enhanced command-line parsing with new series option and improved validation
- **Configuration management**: Improved handling of materials catalogue properties

## [0.6.0] - 2025-02-04
### Added
- Initial release with core functionality
- Support for NIST LIBS database integration
- Selenium-based web automation
- CSV data export capabilities
- Compositional variation generation
  - Uniform distribution sampling
  - Gaussian distribution sampling
- MatWeb integration for composition retrieval (partial implementation)
- Command-line interface
- Logging system with rotation
- Configuration management
- Basic error handling

### Changed
- Updated to Java 21
- Migrated to Selenium WebDriver 4.28.1
- Enhanced CSV handling with Apache Commons CSV 1.13.0

### Known Issues
- Memory usage with large datasets needs optimization
- Occasional browser automation stability issues
- Limited data validation capabilities

## [0.1.0] - 2024-12-16
### Added
- Initial demo version
- Basic project structure
- Selenium integration
- Element management system
- Simple CSV export

## Planned features
- GUI interface for easier user interaction
- Database integration for local data storage
- Enhanced data validation and error reporting
- Real-time spectral analysis tools
- Data compression and export options
- Integration with additional spectroscopic databases
- Advanced filtering and search capabilities
- Batch processing capabilities for large-scale data collection
