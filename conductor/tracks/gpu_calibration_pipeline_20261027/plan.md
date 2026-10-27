# Implementation Plan: Multi-Family GPU Instrument Calibration & Physics-Informed Synthetic LIBS Pipeline

## Track ID: `gpu_calibration_pipeline_20261027`

### Phase 1: Cleanup & Deprecation of Legacy Reporting & ALS Baseline
- [x] Task: Deprecate legacy baseline and reporting methods (facf096)
    - [x] Add `@Deprecated` annotations to `BaselineCorrectionService.java` and remove Java ALS calls from calibration execution path
    - [x] Add `@Deprecated` annotations to Papermill, Pandoc, and notebook execution methods in `PythonUtils.java`
- [x] Task: Refactor BaselineCorrectionParams to Morphological Opening (2fd601c)
    - [x] Write unit tests for `BaselineCorrectionParams` deserialization with legacy ALS fields ignored and morphological opening defaulted
    - [x] Update `src/main/java/com/medals/libsdatagenerator/model/BaselineCorrectionParams.java` with `@JsonIgnoreProperties(ignoreUnknown = true)`, defaulting to `method = "morphological_opening"` and `structuringElementSize = 25`
    - [x] Verify tests pass (`mvn test -Dtest=BaselineCorrectionParamsTest`)
- [x] Task: Conductor - User Manual Verification 'Phase 1: Cleanup & Deprecation of Legacy Reporting & ALS Baseline' (Protocol in workflow.md)

### Phase 2: Model & CLI Argument Refactoring
- [x] Task: Update domain models for profile and plasma zones (cd3be7f)
    - [x] Write unit tests for updated `PlasmaZone`, `MaterialFamilyProfile`, and `InstrumentProfile` JSON serialization and deserialization
    - [x] Update `src/main/java/com/medals/libsdatagenerator/model/PlasmaZone.java` to support `teStdDev`, `neLogStdDev`, `volumeFraction`, and `kAbsorption`
    - [x] Update `MaterialFamilyProfile.java` and `InstrumentProfile.java` to support `spectralResponse`, `voigtProfile`, `finotelloNoiseBeta`, and `validationHoldoutCsvs`
    - [x] Verify model tests pass
- [~] Task: Extend CLI argument parser for material hierarchy and directory input
    - [ ] Write unit tests for `-mt` (`--material-type`), `-mg` (`--material-grade`), `-hc` (`--holdout-csvs`), and directory path parsing for `-i`
    - [ ] Update `LIBSDataGenConstants.java`, `CmdlineParserUtil.java`, and `UserInputConfig.java` to expose and validate the new options
    - [ ] Verify CLI parser tests pass
- [ ] Task: Conductor - User Manual Verification 'Phase 2: Model & CLI Argument Refactoring' (Protocol in workflow.md)

### Phase 3: Incremental Manifest & Selenium 48-State NIST Grid Caching
- [ ] Task: Multi-CSV directory scanning and holdout partitioning
    - [ ] Write unit tests in `InstrumentProfileServiceTest` verifying scanning of `-i` directory (5 CSVs) and 4-calibration / 1-holdout partitioning
    - [ ] Implement directory scanning and CSV partitioning logic in `InstrumentProfileService.java`
- [ ] Task: Selenium 48-state (Te, Ne) NIST anchor grid caching
    - [ ] Implement systematic 48-state discrete anchor grid caching in `LIBSDataService.java` and `InstrumentProfileService.java`
    - [ ] Ensure cached NIST files are saved under `cache/nist/<materialName>_Te<Te>_Ne<Ne>.csv`
- [ ] Task: Incremental manifest staging & Python execution bridge
    - [ ] Write tests verifying creation and upserting of `temp/calibration_manifest.json` under `materialFamilies.<materialType>`
    - [ ] Implement `temp/calibration_manifest.json` generation in `InstrumentProfileService.java`
    - [ ] Add `PythonUtils.runGpuCalibrationEngine(Path scriptPath, Path manifestPath)` using `ProcessBuilder`
    - [ ] Verify Phase 3 tests pass
- [ ] Task: Conductor - User Manual Verification 'Phase 3: Incremental Manifest & Selenium 48-State NIST Grid Caching' (Protocol in workflow.md)

### Phase 4: PyTorch CUDA Calibration Engine & Non-Destructive Profile Upsert
- [ ] Task: Raw shot ingestion, saturation masking & morphological opening
    - [ ] Create `scripts/gpu_calibration_engine.py` skeleton with CUDA/CPU device resolution and manifest loading
    - [ ] Implement per-shot saturation masking ($< 0.9 \times y_{\text{FS}}$) and averaging across non-saturated shots
    - [ ] Implement 1D Morphological Grey Opening baseline subtraction (`scipy.ndimage.grey_opening`, structuring element size = 25)
- [ ] Task: Differential Voigt Transfer Matrix Ψ construction
    - [ ] Implement pseudo-Voigt peak fitting on isolated thin lines to determine $\text{FWHM}_G(\lambda)$ and $\text{FWHM}_L(\lambda)$ (cross-referencing ASD line data in `/home/puser/Work/PhD/Code/Medals_classifier/data/reference/nist_lines`)
    - [ ] Build GPU sparse/dense convolution matrix $\boldsymbol{\Psi} \in \mathbb{R}^{P \times P_{\text{NIST}}}$
- [ ] Task: Stage 1 Global NNLS & B-Spline instrument response solver
    - [ ] Implement Stage 1 global NNLS solver with 14-knot cubic B-spline basis and second-derivative curvature penalty
    - [ ] Normalize recovered response $\mathbf{r}(\lambda)$ to geometric mean = 1.0
- [ ] Task: Stage 2 GPU 2D Bilinear Interpolation & Contracting Hypercube Monte Carlo RTE
    - [ ] Implement GPU 2D Bilinear Interpolation (`torch.nn.functional.grid_sample`) on the 48-state anchor grid tensor
    - [ ] Implement Contracting Hypercube Monte Carlo search over $T_e, N_e, V_{\text{frac}}, \kappa_{\text{abs}}$ per zone minimizing weighted line correlation cost
- [ ] Task: Stage 3 spatial bootstrap & non-destructive JSON profile upsert
    - [ ] Implement spatial point bootstrap across calibration CSVs to calculate `teStdDev`, `neLogStdDev`, and Finotello noise $\beta$
    - [ ] Implement non-destructive upsert into `conf/instrument_profile.json` preserving existing material family entries
    - [ ] Write unit/integration tests verifying GPU engine execution and upsert behavior
- [ ] Task: Conductor - User Manual Verification 'Phase 4: PyTorch CUDA Calibration Engine & Non-Destructive Profile Upsert' (Protocol in workflow.md)

### Phase 5: Forward Synthetic Pipeline Integration, Batch Runner & Legacy Hard-Deletion
- [ ] Task: Optional residuals injection for tramp elements using Bernoulli trials
    - [ ] Add CLI flag `--inject-residuals` / `-ir` (and optional `--residuals-prob`) to `LIBSDataGenConstants.java`, `CmdlineParserUtil.java`, and `UserInputConfig.java`
    - [ ] Write unit tests for Bernoulli trial occurrence sampling, tramp concentration injection, and CLI flag gating
    - [ ] Implement `ResidualsInjector` to inject tramp elements (e.g., Cu, Sn, Pb, As, Sb, Cr, Ni) via independent Bernoulli trials with scrap contamination ranges and matrix (Fe) rebalancing only when `--inject-residuals` is active
    - [ ] Integrate `ResidualsInjector` into the forward synthetic composition generation flow (`LIBSDataController` / `run.sh`)
- [ ] Task: Coupled (Te, Ne) Gaussian sampling in forward synthesis
    - [ ] Write unit tests for `PlasmaTempSampler.java` coupled sampling in $(T_e, \log_{10} N_e)$
    - [ ] Update `src/main/java/com/medals/libsdatagenerator/sampler/PlasmaTempSampler.java` using per-zone mean and std dev from profile
- [ ] Task: SpectrumUtils multi-zone synthesis & instrument response application
    - [ ] Write unit tests for `SpectrumUtils.combineNZones()` applying absorption exponentiation and $\mathbf{r}(\lambda) \times \text{scaleFactor}$
    - [ ] Update `SpectrumUtils.java` to integrate differential Voigt transfer matrix and spectral response
- [ ] Task: Update batch calibration runner script
    - [ ] Update `Code/Notebooks/UtilScripts/run_batch_calibration.py` to read `reference_compositions.json`, format `-c` strings, pass `-mt`, `-mg`, `-z`, and `-hc`, and invoke `./calibrate.sh`
- [ ] Task: Delete deprecated legacy files & verify clean build
    - [ ] Hard delete deprecated template notebooks (`conf/calibration_report_template.ipynb`, `conf/calibration_report_multi_material_template.ipynb`, and resource copies)
    - [ ] Remove deprecated ALS baseline classes and obsolete optimization methods in `InstrumentProfileService.java`
    - [ ] Run full project test suite (`mvn clean test`) and end-to-end forward synthesis dry run
- [ ] Task: Conductor - User Manual Verification 'Phase 5: Forward Synthetic Pipeline Integration, Batch Runner & Legacy Hard-Deletion' (Protocol in workflow.md)
