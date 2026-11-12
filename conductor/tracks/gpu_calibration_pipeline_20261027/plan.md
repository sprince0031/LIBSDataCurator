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
- [x] Task: Extend CLI argument parser for material hierarchy and directory input (2fc799a)
    - [x] Write unit tests for `-mt` (`--material-type`), `-mg` (`--material-grade`), `-hc` (`--holdout-csvs`), and directory path parsing for `-i`
    - [x] Update `LIBSDataGenConstants.java`, `CmdlineParserUtil.java`, and `UserInputConfig.java` to expose and validate the new options
    - [x] Verify CLI parser tests pass
- [x] Task: Conductor - User Manual Verification 'Phase 2: Model & CLI Argument Refactoring' (Protocol in workflow.md)

### Phase 3: Incremental Manifest & Selenium 48-State NIST Grid Caching
- [x] Task: Multi-CSV directory scanning and holdout partitioning (7ab5606)
    - [x] Write unit tests in `InstrumentProfileServiceTest` verifying scanning of `-i` directory (5 CSVs) and 4-calibration / 1-holdout partitioning
    - [x] Implement directory scanning and CSV partitioning logic in `InstrumentProfileService.java`
- [x] Task: Selenium 48-state (Te, Ne) NIST anchor grid caching (7ab5606)
    - [x] Implement systematic 48-state discrete anchor grid caching in `LIBSDataService.java` and `InstrumentProfileService.java`
    - [x] Ensure cached NIST files are saved under `cache/nist/<materialName>_Te<Te>_Ne<Ne>.csv`
- [x] Task: Incremental manifest staging & Python execution bridge (7ab5606)
    - [x] Write tests verifying creation and upserting of `temp/calibration_manifest.json` under `materialFamilies.<materialType>`
    - [x] Implement `temp/calibration_manifest.json` generation in `InstrumentProfileService.java`
    - [x] Add `PythonUtils.runGpuCalibrationEngine(Path scriptPath, Path manifestPath)` using `ProcessBuilder`
    - [x] Verify Phase 3 tests pass
- [x] Task: Conductor - User Manual Verification 'Phase 3: Incremental Manifest & Selenium 48-State NIST Grid Caching' (Protocol in workflow.md)

### Phase 4: PyTorch CUDA Calibration Engine & Non-Destructive Profile Upsert
- [x] Task: Raw shot ingestion, saturation masking & morphological opening (3663570)
    - [x] Create `scripts/gpu_calibration_engine.py` skeleton with CUDA/CPU device resolution and manifest loading
    - [x] Implement per-shot saturation masking ($< 0.9 \times y_{\text{FS}}$) and averaging across non-saturated shots
    - [x] Implement 1D Morphological Grey Opening baseline subtraction (`scipy.ndimage.grey_opening`, structuring element size = 25)
- [x] Task: Differential Voigt Transfer Matrix Ψ construction (3663570)
    - [x] Implement pseudo-Voigt peak fitting on isolated thin lines to determine $\text{FWHM}_G(\lambda)$ and $\text{FWHM}_L(\lambda)$ (cross-referencing ASD line data in `/home/puser/Work/PhD/Code/Medals_classifier/data/reference/nist_lines`)
    - [x] Build GPU sparse/dense convolution matrix $\boldsymbol{\Psi} \in \mathbb{R}^{P \times P_{\text{NIST}}}$
- [x] Task: Stage 1 Global NNLS & B-Spline instrument response solver (3663570)
    - [x] Implement Stage 1 global NNLS solver with 14-knot cubic B-spline basis and second-derivative curvature penalty
    - [x] Normalize recovered response $\mathbf{r}(\lambda)$ to geometric mean = 1.0
- [x] Task: Stage 2 GPU 2D Bilinear Interpolation & Contracting Hypercube Monte Carlo RTE (3663570)
    - [x] Implement GPU 2D Bilinear Interpolation (`torch.nn.functional.grid_sample`) on the 48-state anchor grid tensor
    - [x] Implement Contracting Hypercube Monte Carlo search over $T_e, N_e, V_{\text{frac}}, \kappa_{\text{abs}}$ per zone minimizing weighted line correlation cost
- [x] Task: Stage 3 spatial bootstrap & non-destructive JSON profile upsert (3663570)
    - [x] Implement spatial point bootstrap across calibration CSVs to calculate `teStdDev`, `neLogStdDev`, and Finotello noise $\beta$
    - [x] Implement non-destructive upsert into `conf/instrument_profile.json` preserving existing material family entries
    - [x] Write unit/integration tests verifying GPU engine execution and upsert behavior
- [x] Task: Conductor - User Manual Verification 'Phase 4: PyTorch CUDA Calibration Engine & Non-Destructive Profile Upsert' (Protocol in workflow.md)

### Phase 5: Forward Synthetic Pipeline Integration, Batch Runner & Legacy Hard-Deletion
- [x] Task: Optional residuals injection for tramp elements using Bernoulli trials (005092c)
    - [x] Add CLI flag `--inject-residuals` / `-ir` (and optional `--residuals-prob`) to `LIBSDataGenConstants.java`, `CmdlineParserUtil.java`, and `UserInputConfig.java`
    - [x] Write unit tests for Bernoulli trial occurrence sampling, tramp concentration injection, and CLI flag gating
    - [x] Implement `ResidualsInjector` to inject tramp elements (e.g., Cu, Sn, Pb, As, Sb, Cr, Ni) via independent Bernoulli trials with scrap contamination ranges and matrix (Fe) rebalancing only when `--inject-residuals` is active
    - [x] Integrate `ResidualsInjector` into the forward synthetic composition generation flow (`LIBSDataController` / `run.sh`)
- [x] Task: Coupled (Te, Ne) Gaussian sampling in forward synthesis (6e18108)
    - [x] Write unit tests for `PlasmaTempSampler.java` coupled sampling in $(T_e, \log_{10} N_e)$
    - [x] Update `src/main/java/com/medals/libsdatagenerator/sampler/PlasmaTempSampler.java` using per-zone mean and std dev from profile
- [x] Task: SpectrumUtils multi-zone synthesis & instrument response application (649343c)
    - [x] Write unit tests for `SpectrumUtils.combineNZones()` applying absorption exponentiation and $\mathbf{r}(\lambda) \times \text{scaleFactor}$
    - [x] Update `SpectrumUtils.java` to integrate differential Voigt transfer matrix and spectral response
- [x] Task: Update batch calibration runner script (completed)
    - [x] Update `/home/puser/Work/PhD/Code/Notebooks/UtilScripts/run_batch_calibration.py` to read `reference_compositions.json`, format `-c` strings, pass `-mt`, `-mg`, `-z`, and `-hc`, and invoke `./calibrate.sh`
- [x] Task: Delete deprecated legacy files & verify clean build (c148388)
    - [x] Hard delete deprecated template notebooks (`conf/calibration_report_template.ipynb`, `conf/calibration_report_multi_material_template.ipynb`, and resource copies)
    - [x] Remove deprecated ALS baseline classes and obsolete optimization methods in `InstrumentProfileService.java`
    - [x] Run full project test suite (`mvn clean test`) and end-to-end forward synthesis dry run
- [x] Task: Conductor - User Manual Verification 'Phase 5: Forward Synthetic Pipeline Integration, Batch Runner & Legacy Hard-Deletion' (Protocol in workflow.md)

### Phase 6: NIST ASD Line Acquisition, CLI Integration & Hard-Coded Window Removal
- [x] Task: Update `scripts/nist_lines.py` with `-` and `:` composition parser, `--elements` / `-c` CLI options, and default cache dir `data/nist_lines` (fb7472d)
  - [x] Update `DEFAULT_CACHE_DIR` to `Path(__file__).resolve().parent.parent / "data" / "nist_lines"`
  - [x] Implement `parse_elements_from_composition(comp_str)` in `scripts/nist_lines.py` supporting both `-` and `:` key-value delimiters (e.g., `'C-0.279,Si-6.5:7.5,Fe-#'` and `'C:0.279,Fe:87.8'`) via `re.split(r"[-:]", token.strip(), maxsplit=1)`
  - [x] Update `build_cache(...)` to accept `elements: list[str] | None = None` and `include_common: bool = True`
  - [x] Add `--elements` and `-c` / `--composition` CLI flags to `scripts/nist_lines.py`
- [x] Task: Add `PythonUtils.ensureNistLinesCached` and unit tests in `PythonUtilsTest` (d841bb9)
  - [x] Write unit tests in `PythonUtilsTest` validating CLI command construction for `scripts/nist_lines.py`
  - [x] Implement `ensureNistLinesCached(List<String> elementSymbols, Path cacheDir, double minWlNm, double maxWlNm)` in `src/main/java/com/medals/libsdatagenerator/util/PythonUtils.java`
- [x] Task: Refactor `InstrumentProfileService.java` to trigger ASD line caching and remove hard-coded out-of-range manifest windows (8ed5db8)
  - [x] Delete `populateDefaultManifestWindows(...)` from `InstrumentProfileService.java` and strip any legacy `"trampWindowsNm"` or `"resonanceMaskWindowsNm"` keys when loading/saving `temp/calibration_manifest.json`
  - [x] Update `stageMaterialAndRunGpuCalibration(...)` in `InstrumentProfileService.java` to invoke `pythonUtils.ensureNistLinesCached(elementSymbols, Path.of("data", "nist_lines"), 340.0, 900.0)`
  - [x] Write both `"composition"` and `"compositionMap"` to each material manifest entry and `"nistLinesDir": "data/nist_lines"` at the root of `temp/calibration_manifest.json`
  - [x] Update `InstrumentProfileServiceTest` to verify `ensureNistLinesCached` is called and no hard-coded `trampWindowsNm` or `resonanceMaskWindowsNm` exist in the manifest
- [x] Task: Conductor - User Manual Verification 'Phase 6: NIST ASD Line Acquisition, CLI Integration & Hard-Coded Window Removal' (Protocol in workflow.md)

### Phase 7: Complete Voigt Profile Removal across Java and JSON Schemas
- [x] Task: Delete `VoigtProfile.java` and remove `voigtProfile` field/methods from `InstrumentProfile.java` (5c2f18c)
  - [x] Update `InstrumentProfileTest` and related model tests to remove all assertions on `VoigtProfile`
  - [x] Hard-delete `src/main/java/com/medals/libsdatagenerator/model/VoigtProfile.java` and `SpectralConvolver.java` (if present)
  - [x] Remove `voigtProfile` field, getter, setter, and constructor parameters from `InstrumentProfile.java`
- [x] Task: Remove Voigt convolution and transfer matrix references from Java services (5c2f18c)
  - [x] Update `LIBSDataService.java` and `InstrumentProfileService.java` so cached NIST spectra are linearly interpolated directly onto `instrumentProfile.getWavelengths()` without Voigt convolution
  - [x] Ensure `@JsonIgnoreProperties(ignoreUnknown = true)` is present on `InstrumentProfile`, `MaterialFamilyProfile`, `PlasmaZone`, and `BaselineCorrectionParams`
- [x] Task: Verify clean build and model tests (`mvn test -Dtest=InstrumentProfileTest,InstrumentProfileServiceTest`) (5c2f18c)
- [x] Task: Conductor - User Manual Verification 'Phase 7: Complete Voigt Profile Removal across Java and JSON Schemas' (Protocol in workflow.md)

### Phase 8: 1:1 Forward RTE Parity in Java & Morphological Baseline Structuring Size Upgrade (65 Channels)
- [x] Task: Update `BaselineCorrectionParams.java` and `InstrumentProfileService.java` default structuring element size to 65 (6a69ea5)
  - [x] Update `structuringElementSize = 65` in `BaselineCorrectionParams.java` and `DEFAULT_MORPHOLOGICAL_STRUCT_SIZE = 65`, `NIST_RESOLVING_POWER = 3000.0` in `InstrumentProfileService.java`
  - [x] Update `BaselineCorrectionParamsTest` to assert default `structuringElementSize == 65`
- [x] Task: Refactor `SpectrumUtils.java` for 1:1 normalized 5-step RTE parity with Python (cb85c5d)
  - [x] Update `SpectrumUtils.combineNZones(List<double[]> zoneSpectra, List<PlasmaZone> zones)` to max-normalize Zone 0 (`coreRaw[p] / maxCore`) and every outer shell (`shellRaw[p] / maxShell`) to `[0, 1]` before applying $I_z(\lambda) = I_{z-1}(\lambda)\exp(-k_z \tilde{S}_z(\lambda)) + V_z \tilde{S}_z(\lambda)$
  - [x] Update `SpectrumUtils.applyInstrumentResponseAndNoise(...)` to multiply by `spectralResponse`, max-normalize the post-response spectrum to `[0, 1]`, multiply by `scaleFactor`, and apply Finotello multiplicative noise $\max(0.0, 1.0 + \beta \mathcal{N}(0, 1))$
  - [x] Write unit tests in `SpectrumUtilsTest` verifying that `max(applyInstrumentResponseAndNoise(..., finotelloBeta=0.0)) == scaleFactor`
- [x] Task: Update `LIBSDataService.java` forward synthesis to invoke the updated `SpectrumUtils` pipeline (cb85c5d)
- [x] Task: Conductor - User Manual Verification 'Phase 8: 1:1 Forward RTE Parity in Java & Morphological Baseline Structuring Size Upgrade' (Protocol in workflow.md)

### Phase 9: Upgrade PyTorch GPU Calibration Engine (`scripts/gpu_calibration_engine.py`)
- [x] Task: Implement `parse_composition_dict` and `AsdLineAdvisor` with purely dynamic in-range (`[lam_meas[0], lam_meas[-1]]`) window lookup (219aabc)
  - [x] Support both `"compositionMap"` and `"composition"` keys and both `-` and `:` delimiters (`C-0.279,Si-6.5:7.5,Fe-#`) in `parse_composition_dict`
  - [x] Implement `AsdLineAdvisor` importing `build_cache` and `load_lines` from `scripts/nist_lines.py` (`data/nist_lines/`)
  - [x] Compute resonance masks (`build_resonance_mask`), family tramp weights (`build_family_tramp_weights`), and non-resonant P-spline anchor windows (`get_asd_anchor_windows`) strictly from `data/nist_lines/` within `[lam_meas[0], lam_meas[-1]]` (`341.952–896.916 nm`), ignoring any legacy manifest window arrays
- [x] Task: Replace Voigt transfer matrix with direct 1D NIST resampling and per-state max-normalization (219aabc)
  - [x] Remove `estimate_measured_voigt_fwhm` and `build_gpu_voigt_transfer_matrix`
  - [x] Interpolate cached NIST CSVs directly onto `lam_meas` via `np.interp` and normalize each state column to unit max (`[0, 1]`)
- [x] Task: Implement constrained 2-Zone GPU Contracting Hypercube RTE Optimizer (`optimize_family_rte_gpu`) (219aabc)
  - [x] Enforce physical bounds: $T_{e,0} \in [0.60, 1.45]\text{ eV}$, $T_{e,1} \in [0.50, 0.95]\text{ eV}$ with $T_{e,0} - T_{e,1} \ge 0.12\text{ eV}$, $V_0 \in [0.75, 0.95]$, $V_1 = 1.0 - V_0$, $k_0 = 0.0$, $k_1 \in [0.50, 5.00]$
  - [x] Remove $1/\sigma_m^2$ inverse-variance weighting; optimize using tramp-weighted MSE + cosine loss (`0.5 * w_mse + 0.5 * cos_loss`) and compute `scaleFactor`, `rSquared`, and `rmse` via unweighted Ordinary Least Squares against the max-normalized post-response spectrum
- [x] Task: Implement 2-Pass Alternating RTE + ASD Peak-Envelope 8-Knot P-Spline `spectralResponse` Solver (219aabc)
  - [x] Pass 1: Fit initial 2-zone RTE parameters for all staged families using flat response $R^{(0)}(\lambda) = \mathbf{1}$
  - [x] Global Response Fit: Solve 8-knot cubic P-spline ($\mu_R = 2500$, ridge prior $0.25$) over ASD anchor peak-envelope log-ratios with Inverse-Family-Size Balanced Weighting ($1 / M_f$), per-material median centering, Huber clipping ($\pm 0.85$), and $[0.25, 2.50]$ bounds
  - [x] Pass 2: Re-optimize 2-zone RTE parameters and spatial bootstrap variances (`teStdDev`, `neLogStdDev`) for **all** staged families in `temp/calibration_manifest.json` using the locked $R(\lambda)$, strip any legacy `"voigtProfile"` key, and save `conf/instrument_profile.json`
- [x] Task: Write Python unit/integration test verifying `gpu_calibration_engine.py` end-to-end execution (219aabc)
- [ ] Task: Conductor - User Manual Verification 'Phase 9: Upgrade PyTorch GPU Calibration Engine' (Protocol in workflow.md)

### Phase 10: End-to-End Verification & Test Suite Parity
- [ ] Task: Run full Maven test suite (`mvn clean test`) and Python tests
- [ ] Task: Run calibration dry-run and verify `conf/instrument_profile.json` contains no `voigtProfile` and `temp/calibration_manifest.json` contains no out-of-range hard-coded windows
- [ ] Task: Conductor - User Manual Verification 'Phase 10: End-to-End Verification & Test Suite Parity' (Protocol in workflow.md)