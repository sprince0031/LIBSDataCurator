# Specification: Top 100 Elemental LIBS Wavelengths Analysis Notebook

## Overview
Create a Jupyter Notebook script/guide (`libs_element_wavelengths.ipynb`) to parse all NIST LIBS `composition*.csv` files in `./data/NIST LIBS/`, aggregate atomic/ionic species intensities by base chemical element, extract the top 100 peak wavelengths per element, and visualize them using stem/line spectrum plots.

## Functional Requirements
1. **CSV Discovery & Parsing**:
   - Locate all CSV files in `./data/NIST LIBS/` matching pattern `composition*.csv`.
   - Identify the first column as `Wavelength` (nm or Å).
   - Parse species column headers (e.g., `C I (1.9e-3)`, `Fe II (9.4e-1)`) using regular expressions to map each column to its base element (`C`, `Fe`, `Cr`, `Mn`, `Mo`, `Si`, `P`, `S`, `Ni`, `Zn`, etc.).
2. **Species Intensity Aggregation**:
   - For each composition file, sum emission intensities across all ionization states of an element for each wavelength (e.g., `Total_C = C I + C II + C III`).
   - Across all composition files, compute the maximum peak intensity at each wavelength per element.
3. **Top 100 Line Selection & Data Structuring**:
   - Rank wavelengths by peak intensity for each element and extract the top 100 characteristic emission wavelengths.
   - Organize the resulting data into a Python dictionary of DataFrames (`{'Element': df_element}`), where each DataFrame contains `Wavelength` and `Intensity` columns.
4. **Spectral Visualization**:
   - Generate stem/line spectrum subplots for each element displaying its top 100 emission wavelengths.
   - Use logarithmic intensity scaling (`log` y-axis) to accommodate high dynamic range between matrix elements (e.g., Fe) and trace elements (e.g., P, S).

## Non-Functional Requirements
- Uses standard Python data science stack (`pandas`, `numpy`, `matplotlib`, `seaborn`, `pathlib`, `re`).
- Clear cell-by-cell notebook structure.

## Acceptance Criteria
- [x] All `composition*.csv` files in `./data/NIST LIBS/` are dynamically loaded.
- [x] NIST species headers are correctly regex-parsed to their base element symbols.
- [x] Top 100 peak wavelengths per element are accurately extracted into a dictionary of DataFrames.
- [x] Legible stem/line spectrum subplots with log intensity scale are generated per element.

## Out of Scope
- Direct Java application changes in `LIBSDataCurator` core CLI package.
