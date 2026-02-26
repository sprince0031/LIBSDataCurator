# Product Guidelines

## Development Principles
- **Scientific Integrity:** Prioritize physical accuracy in spectral simulations. All mathematical models must be grounded in spectroscopy fundamentals and NIST standards.
- **Performance-Driven:** Continually optimize the data generation pipeline. Utilize browser-side processing and efficient data structures to handle high-volume dataset creation without excessive server load.
- **User Empowerment:** Maintain an intuitive CLI with comprehensive `--help` support. Error messages should be descriptive and actionable.

## Architectural & Code Quality Guidelines
- **Clean Code & DRY Principle:** Adhere strictly to the "Don't Repeat Yourself" (DRY) principle. The codebase must be kept clean with zero tolerance for unnecessary code duplication. Common logic should be abstracted into reusable utility classes or services.
- **Loose Coupling:** The application should be composed of independent, modular services (e.g., `LIBSDataService`, `MatwebDataService`, `DatasetStatisticsService`). This ensures that changes to one external data source (like a NIST website update) don't break the entire system.
- **Test-Driven Reliability:** Every feature, particularly those involving sampling or statistical calculation, must be accompanied by robust unit and integration tests.
- **Documentation:** Maintain a central repository of documentation in the `docs/` directory. Technical specs for features like "Instrument Profiling" should be finalized before implementation.

## Data & Output Standards
- **ML-Ready Outputs:** All spectral data must be exported in standardized, machine-readable CSV formats. Avoid arbitrary changes to output schemas to ensure stability for downstream ML pipelines.
- **Comprehensive Metadata:** Every dataset generation should be accompanied by a structured JSON metadata file. This file must record all input parameters, timestamps, and key metrics (like elemental distributions) to ensure full traceability and reproducibility.
- **Transparency:** Provide a debug mode (e.g., `-d, --debug`) to allow users to inspect the underlying Selenium-based data acquisition process for troubleshooting and verification.

## Evolutionary Guidelines
- **Metric Expansion:** Proactively identify and implement new metrics for dataset analysis. As the tool evolves, metadata should become increasingly rich with insights into the generated data's quality and characteristics.
