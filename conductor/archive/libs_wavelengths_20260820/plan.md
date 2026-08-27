# Implementation Plan: Top 100 Elemental LIBS Wavelengths Analysis Notebook

Create a Python/Jupyter notebook guide (`libs_element_wavelengths.ipynb`) to parse composition CSV files from the NIST LIBS database, aggregate atomic/ionic species by element, extract top 100 peak emission wavelengths per element into a dictionary of DataFrames, and visualize them using stem/line spectrum subplots.

## User Review Required

> [!IMPORTANT]
> This track produces a Python analysis notebook for NIST LIBS composition datasets.

- **Dependencies**: `pandas`, `numpy`, `matplotlib`, `seaborn`, `pathlib`, `re`
- **Output Data Structure**: Dictionary of DataFrames (`element_dfs['Element']`) containing `Wavelength` and `Intensity`.

## Proposed Phases

### Phase 1: Environment & NIST Composition CSV Ingestion
- [x] Task: Create notebook imports and data file discovery logic
    - [x] Add imports (`pandas`, `numpy`, `matplotlib`, `seaborn`, `pathlib`, `re`)
    - [x] Configure `pathlib.Path` search for `./data/NIST LIBS/composition*.csv`
- [x] Task: Implement NIST species header parser regex function
    - [x] Write `extract_element(col_name)` to extract element symbols (e.g. `Fe`, `C`, `Cr`, `Mn`)
- [x] Task: Conductor - User Manual Verification 'Phase 1: Environment & NIST Composition CSV Ingestion' (Protocol in workflow.md)

### Phase 2: Species Aggregation & Top 100 Wavelength Extraction
- [x] Task: Aggregate element intensities across ionization species and composition files
    - [x] For each file, sum species columns by base element
    - [x] Compute max peak intensity per wavelength for each element across all files
- [x] Task: Extract top 100 peak wavelengths per element into DataFrame dictionary
    - [x] Rank wavelengths by intensity, select top 100, and store in `element_dfs[elem]`
- [x] Task: Conductor - User Manual Verification 'Phase 2: Species Aggregation & Top 100 Wavelength Extraction' (Protocol in workflow.md)

### Phase 3: Visualization & Notebook Documentation
- [x] Task: Implement stem/line spectral plot routine per element
    - [x] Create Matplotlib subplots for each element
    - [x] Use `vlines`/`scatter` stem display with log-scale y-axis
- [x] Task: Conductor - User Manual Verification 'Phase 3: Visualization & Notebook Documentation' (Protocol in workflow.md)
