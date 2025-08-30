# Changelog

All notable changes to the LIBS Data Generator project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.8.5] - 2025-08-26
### Fixed
- Fixed write permissions in release workflow
- Fixed logging to file functionality in local build scripts
- Fixed issue with number of input samples generated not accounting for original composition
- Changed default `-n` parameter from 50 to 20 samples
- Updated `-vm` description to reflect Dirichlet sampling as default

### Changed
- Updated GitHub workflow files to reflect changes to build process
- **BREAKING:** Removed backward compatibility for reading legacy CSV files with colon-based filenames
- Cleaned up build process to remove duplicate packaging steps and reorganized directory structure

## [0.8.0] - 2025-08-25
### Added
- Self-contained packaging with bundled JRE (no Java installation required)
- SSL/TLS support with updated certificate store for NIST LIBS connectivity
- Cross-platform support (Windows and Linux/macOS packages)
- Optimized custom JRE using jlink with security modules

### Fixed
- Windows incompatible filenames for NIST CSV data by replacing colons with hyphens
- SSL handshake exceptions resolved in self-contained packages

### Changed
- Migrated build system to use custom JRE creation
- Updated release workflow for multiplatform package creation

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

## [Unreleased]
### Changed
- **BREAKING:** Removed backward compatibility for reading legacy CSV files with colon-based filenames. The application now only generates and reads CSV files using the new cross-platform compatible filename format with hyphens instead of colons (e.g., `C-0.26;Fe-99.74` instead of `C:0.26;Fe:99.74`). Existing legacy files will no longer be recognized or read by the system.

### Planned
- Dirichlet sampling implementation
- GUI interface
- Database integration
- Enhanced data validation
- Spectral analysis tools
- Data compression options
