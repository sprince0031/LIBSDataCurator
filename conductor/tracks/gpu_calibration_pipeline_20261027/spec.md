# Specification: Multi-Family GPU Instrument Calibration & Physics-Informed Synthetic LIBS Pipeline

## Track ID: `gpu_calibration_pipeline_20261027`

### 1. Overview
Refactor the **Instrument Profile Calibration** (`calibrate.sh`) and **Forward Synthetic Dataset Generation** (`run.sh`) pipelines in `LIBSDataCurator` into a high-performance, GPU-accelerated, multi-material and multi-family calibration architecture.

The pipeline processes multi-CSV reference material directories (5 spatial points per reference material, 40 shots per point) across multiple alloy families, separating concerns cleanly between:
1. **Python Batch Runner** (`Code/Notebooks/UtilScripts/run_batch_calibration.py`) for orchestrating runs across reference materials.
2. **Java Calibration CLI & NIST Cacher** (`InstrumentProfileController` / `InstrumentProfileService`) for composition parsing, shot directory partitioning (4 calibration CSVs + 1 holdout CSV), 48-state discrete $(T_e, N_e)$ NIST anchor grid fetching/caching via Selenium, and staging `temp/calibration_manifest.json`.
3. **Python PyTorch CUDA Engine** (`scripts/gpu_calibration_engine.py`) as the sole owner of calibration mathematics: raw shot saturation masking, Morphological Opening baseline removal (structuring element size = 25), differential Voigt transfer matrix $\boldsymbol{\Psi}$, Stage 1 NNLS + B-spline response recovery, Stage 2 PyTorch bilinear interpolation (`F.grid_sample`) + contracting hypercube Monte Carlo N-zone RTE optimization, Stage 3 spatial bootstrap for parameter variances, and non-destructive upsert into `conf/instrument_profile.json`.
4. **Java Forward Synthetic Pipeline** (`run.sh` / `PlasmaTempSampler`, `SpectrumUtils`, `ResidualsInjector`) reading the enriched `instrument_profile.json` for coupled $(T_e, N_e)$ sampling, 2D bilinear interpolation, transfer matrix application, N-zone synthesis, optional Bernoulli-trial-based tramp residuals injection, and instrument scaling.

---

### 2. Functional Requirements

#### 2.1 CLI Ingestion & Material Hierarchy (`calibrate.sh`)
- Support directory ingestion via `-i, --input` targeting reference material folders containing spatial shot files (`point_1.csv` ... `point_5.csv`).
- Support material hierarchy flags:
  - `-mt, --material-type` (e.g., `Ferritic_Stainless_Steel`)
  - `-mg, --material-grade` / material name (e.g., `469`)
  - `-hc, --holdout-csvs` (default: 1, holding out the 5th spatial point for out-of-sample validation).
- Use existing `InputCompositionProcessor` via `-c` to parse complex compositions with range syntax and remainder `#` without Java JSON schema additions.

#### 2.2 NIST Anchor Grid Caching & Manifest Staging
- Systematically verify or fetch the 48-state discrete anchor grid:
  - $T_e \in [0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.5, 1.7, 2.0]\text{ eV}$ (12 states)
  - $N_e \in [1.0\times 10^{16}, 5.0\times 10^{16}, 1.0\times 10^{17}, 5.0\times 10^{17}]\text{ cm}^{-3}$ (4 states)
- Cache NIST spectra in `cache/nist/` using naming convention `<materialName>_Te<Te>_Ne<Ne>.csv`.
- Construct and upsert `temp/calibration_manifest.json` under `materialFamilies.<materialType>`.
- Cross-reference downloaded ASD line information in `/home/puser/Work/PhD/Code/Medals_classifier/data/reference/nist_lines` for atomic line parameters and tramp element window validation.

#### 2.3 GPU Calibration Engine (`scripts/gpu_calibration_engine.py`)
- **Saturation Rejection:** Mask channels $\ge 0.9 \times y_{\text{FS}}$ on individual raw shots before shot averaging.
- **Baseline Stripping:** Apply 1D Morphological Grey Opening (`scipy.ndimage.grey_opening`) with default `structuringElementSize = 25` channels.
- **Differential Voigt Matrix:** Extract $\text{FWHM}_G(\lambda)$ and $\text{FWHM}_L(\lambda)$ from isolated emission lines and construct GPU transfer matrix $\boldsymbol{\Psi} \in \mathbb{R}^{P \times P_{\text{NIST}}}$.
- **Stage 1 Response Recovery:** Solve Non-Negative Least Squares (NNLS) with B-spline basis (14 knots, smoothness penalty) to recover smooth instrument response $\mathbf{r}(\lambda)$, normalized to geometric mean = 1.0.
- **Stage 2 Monte Carlo N-Zone RTE Optimization:** Utilize GPU 2D Bilinear Interpolation (`torch.nn.functional.grid_sample`) with contracting hypercube Monte Carlo search over $T_e, N_e, V_{\text{frac}}, \kappa_{\text{abs}}$ per zone, optimizing weighted line correlation.
- **Stage 3 Bootstrap & Variance Estimation:** Bootstrap across spatial point CSVs to compute `teStdDev`, `neLogStdDev`, and Finotello noise parameter $\beta$.
- **Non-Destructive Upsert:** Safely update `conf/instrument_profile.json` by updating existing material families in-place or appending new ones without overwriting unrelated families.

#### 2.4 Forward Synthetic Pipeline Integration (`run.sh`)
- **Coupled Sampling:** `PlasmaTempSampler.java` performs coupled Gaussian sampling in $(T_e, \log_{10} N_e)$ space based on calibrated means and standard deviations.
- **Multi-Zone Radiative Transfer:** `SpectrumUtils.java` implements multi-zone combination with self-absorption exponentiation, differential Voigt convolution, and spectral response application.
- **Optional Tramp Residuals Injection via Bernoulli Trials:**
  - Controlled by CLI flag `--inject-residuals` / `-ir` (with optional `--residuals-prob`).
  - When enabled, `ResidualsInjector` evaluates independent Bernoulli trials for tramp elements (such as Cu, Sn, Pb, As, Sb, Cr, Ni).
  - If a trial succeeds, a residual concentration within scrap tolerance limits is injected and the balance element (Fe) is re-normalized prior to NIST interpolation and synthesis.
  - If `--inject-residuals` is omitted, synthetic generation proceeds with nominal composition without injecting tramp residuals.

#### 2.5 Batch Calibration Runner (`run_batch_calibration.py`)
- Update `Code/Notebooks/UtilScripts/run_batch_calibration.py` to read `reference_compositions.json`, format `-c` strings, pass `-mt`, `-mg`, `-z`, and `-hc`, and sequentially invoke `./calibrate.sh`.

---

### 3. Non-Functional & Migration Requirements
- **Hardware Acceleration:** PyTorch CUDA target on NVIDIA RTX 4070 MAX SUPER with graceful fallback to CPU.
- **Legacy Deprecation Strategy:**
  - Deprecate Papermill, Pandoc, and ALS baseline methods during Phases 1–4 using `@Deprecated`.
  - Delete legacy notebook templates (`conf/calibration_report_*.ipynb`, duplicate resources) and obsolete ALS classes in Phase 5 after automated tests pass.
- **Model Backward Compatibility:** Jackson `@JsonIgnoreProperties(ignoreUnknown = true)` on `BaselineCorrectionParams` and profile models to ensure existing JSON profiles load seamlessly.

---

### 4. Acceptance Criteria
1. `./calibrate.sh -i <dir> -c <comp> -mt <family> -mg <grade> -z 2` completes without errors, generating valid cached NIST grids and updating `conf/instrument_profile.json`.
2. `conf/instrument_profile.json` contains valid `spectralResponse`, `voigtProfile`, `finotelloNoiseBeta`, and per-family `plasmaZones` with standard deviations.
3. Batch runner executes across all materials without corrupting existing profiles in `conf/instrument_profile.json`.
4. `./run.sh` produces synthetic spectra incorporating coupled plasma fluctuations and instrument response.
5. When `--inject-residuals` is passed to `./run.sh`, tramp element residuals are injected according to Bernoulli trials; when omitted, nominal compositions are preserved.
6. All JUnit test suites pass (`mvn test`).

---

### 5. Out of Scope
- Streamlit-Plotly web reporting dashboard (handled in separate external analysis repository).
- Direct JSON composition parser inside Java CLI.
