# Specification: Multi-Family GPU Instrument Calibration & Physics-Informed Synthetic LIBS Pipeline (v0.9.8)

## Track ID: `gpu_calibration_pipeline_20261027`
Reference Specification: `conductor/instrument_profile_debugging_and_improvement_specification_v1.md`

### 1. Overview
Refactor the **Instrument Profile Calibration** (`calibrate.sh`) and **Forward Synthetic Dataset Generation** (`run.sh`) pipelines in `LIBSDataCurator` into a high-performance, GPU-accelerated, multi-material and multi-family calibration architecture with zero Voigt broadening artifacts and 100% data-driven NIST ASD window lookup.

The pipeline processes multi-CSV reference material directories (5 spatial points per reference material, 40 shots per point) across 5 alloy families, separating concerns cleanly between:
1. **Python Batch Runner** (`Code/Notebooks/UtilScripts/run_batch_calibration.py`) for orchestrating runs across reference materials.
2. **Java Calibration CLI, NIST Grid Cacher & ASD Line Cacher** (`InstrumentProfileController`, `InstrumentProfileService`, `PythonUtils`) for composition parsing, shot directory partitioning (4 calibration CSVs + 1 holdout CSV), 48-state discrete $(T_e, N_e)$ NIST anchor grid fetching/caching (`R = 3000.0`) via Selenium, automatic NIST ASD Parquet line caching into `data/nist_lines/` via `scripts/nist_lines.py`, and staging `temp/calibration_manifest.json` (with no hard-coded `trampWindowsNm` or `resonanceMaskWindowsNm`).
3. **Python PyTorch CUDA Engine** (`scripts/gpu_calibration_engine.py`) as the sole owner of calibration mathematics: raw shot saturation masking, Morphological Opening baseline removal (`structuringElementSize = 65`), direct 1D linear resampling of cached NIST spectra onto `lam_meas` (no Voigt convolution), dynamic ASD resonance masking & tramp weighting from `data/nist_lines/` strictly within `[lam_meas[0], lam_meas[-1]]`, 2-Pass Alternating RTE + ASD Peak-Envelope 8-knot P-Spline `spectralResponse` recovery, Stage 3 spatial bootstrap for parameter variances, and full-family synchronized upsert into `conf/instrument_profile.json` (with no `voigtProfile` object).
4. **Java Forward Synthetic Pipeline** (`run.sh` / `PlasmaTempSampler`, `SpectrumUtils`, `ResidualsInjector`) reading `conf/instrument_profile.json` for coupled $(T_e, N_e)$ sampling, direct linear interpolation onto `wavelengths`, 1:1 normalized recursive $N$-zone RTE synthesis, post-response max normalization, `scaleFactor` + Finotello noise application, and optional Bernoulli-trial-based tramp residuals injection.

---

### 2. Functional Requirements

#### 2.1 CLI Ingestion & Material Hierarchy (`calibrate.sh`)
- Support directory ingestion via `-i, --input` targeting reference material folders containing spatial shot files (`point_1.csv` ... `point_5.csv`).
- Support material hierarchy flags:
    - `-mt, --material-type` (e.g., `Ferritic_Stainless_Steel`)
    - `-mg, --material-grade` / material name (e.g., `469`)
    - `-hc, --holdout-csvs` (default: 1, holding out the 5th spatial point for out-of-sample validation).
- Use existing `InputCompositionProcessor` via `-c` to parse compositions with `-` or `:` delimiters, `min:max` range syntax, and remainder `#`.

#### 2.2 NIST Anchor Grid Caching, Dynamic ASD Line Caching & Manifest Staging
- Systematically verify or fetch the 48-state discrete anchor grid at resolving power `NIST_RESOLVING_POWER = 3000.0`:
    - $T_e \in [0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.5, 1.7, 2.0]\text{ eV}$ (12 states)
    - $N_e \in [1.0\times 10^{16}, 5.0\times 10^{16}, 1.0\times 10^{17}, 5.0\times 10^{17}]\text{ cm}^{-3}$ (4 states)
- Cache NIST spectra in `cache/nist/` using naming convention `<materialName>_Te<Te>_Ne<Ne>.csv`.
- Invoke `PythonUtils.ensureNistLinesCached(...)` during `calibrate.sh` to run `scripts/nist_lines.py --elements <E1> <E2> ... --cache-dir data/nist_lines`, caching neutral (`I`) and singly ionized (`II`) NIST ASD Parquet tables (plus common ambient species `H, C, N, O, Na, K, Ca, Mg, Li`) in `<repo_root>/data/nist_lines/`.
- Construct and upsert `temp/calibration_manifest.json` under `materialFamilies.<materialType>`, storing `"compositionMap"` (and `"composition"`) per material and `"nistLinesDir": "data/nist_lines"` at the manifest root.
- **No Hard-Coded Windows:** Remove `populateDefaultManifestWindows` from `InstrumentProfileService.java` and strip any hard-coded `trampWindowsNm` or `resonanceMaskWindowsNm` from `temp/calibration_manifest.json`.

#### 2.3 Complete Voigt Removal & 1:1 Forward Model Parity
- **Complete Voigt Removal:** Delete `VoigtProfile.java` and `SpectralConvolver.java` (if present). Remove `voigtProfile` from `InstrumentProfile.java` and strip `"voigtProfile"` from `conf/instrument_profile.json`. Resample NIST spectra onto detector wavelengths via direct 1D linear interpolation (`np.interp` in Python, linear interpolation in Java).
- **Exact 5-Step Normalized Forward Operator (Python & Java Parity):**
    1. **Per-Zone Max Normalization:** $\tilde{S}_z(\lambda) = \frac{S_z(\lambda)}{\max_\lambda S_z(\lambda) + 10^{-12}}$
    2. **Recursive $N$-Zone RTE:** $I_0(\lambda) = V_0 \tilde{S}_0(\lambda)$, and $I_z(\lambda) = I_{z-1}(\lambda) \exp(-k_z \tilde{S}_z(\lambda)) + V_z \tilde{S}_z(\lambda)$
    3. **Apply `spectralResponse` $R(\lambda)$:** $I_{\text{resp}}(\lambda) = I_{n-1}(\lambda) \cdot R(\lambda)$
    4. **Post-Response Max Normalization to $[0, 1]$:** $\tilde{I}_{\text{resp}}(\lambda) = \frac{I_{\text{resp}}(\lambda)}{\max_\lambda I_{\text{resp}}(\lambda) + 10^{-12}}$
    5. **Apply `scaleFactor` & Finotello Noise:** $\hat{y}(\lambda) = \text{scaleFactor} \cdot \tilde{I}_{\text{resp}}(\lambda) \cdot \max(0.0, 1.0 + \beta_{\text{noise}} \mathcal{N}(0, 1))$

#### 2.4 GPU Calibration Engine (`scripts/gpu_calibration_engine.py`)
- **Saturation Rejection & Baseline Stripping:** Mask channels $\ge 0.9 \times y_{\text{FS}}$ on individual raw shots before averaging, then apply 1D Morphological Grey Opening (`scipy.ndimage.grey_opening`) with `structuringElementSize = 65` channels.
- **Dynamic In-Range ASD Resonance Masking & Tramp Weighting (`AsdLineAdvisor`):**
    - Query cached Parquet tables in `data/nist_lines/` strictly within the instrument's measured wavelength bounds `[lam_meas[0], lam_meas[-1]]` (`341.952–896.916 nm`).
    - **Resonance & Ambient Mask (`build_resonance_mask`):** Mask ambient air lines (`H`, `O`, `N` with `rel_intensity > 100`, $\pm 1.2\text{ nm}$) and top ground-state/low-lying resonance transitions (`Ei_eV <= 0.50 eV`, 90th percentile of LTE score, $\pm 0.60\text{ nm}$) of major matrix elements ($\ge 5\text{ wt.}\%$).
    - **Family Tramp Weights (`build_family_tramp_weights`):** For minor/tramp elements ($0 < C_E < 25\text{ wt.}\%$) in the family's composition, select top 6 Boltzmann-valid ASD transitions isolated from strong matrix lines ($> 0.35\text{ nm}$ separation) within `[lam_meas[0], lam_meas[-1]]` and apply concentration-scaled boost weights ($3.5\times$ for $<0.5\%$, $2.8\times$ for $<2.0\%$, $2.2\times$ for $<25\%$) over $\pm 0.45\text{ nm}$.
- **Physical 2-Zone RTE Bounds & Cost Function:**
    - Core Zone 0: $T_{e,0} \in [0.60, 1.45]\text{ eV}$, $N_{e,0} \in [10^{16}, 5\times 10^{17}]\text{ cm}^{-3}$, $V_0 \in [0.75, 0.95]$, $k_0 = 0.0$.
    - Periphery Zone 1: $T_{e,1} \in [0.50, 0.95]\text{ eV}$ with $T_{e,0} - T_{e,1} \ge 0.12\text{ eV}$, $V_1 = 1.0 - V_0 \in [0.05, 0.25]$, $k_1 \in [0.50, 5.00]$.
    - Optimize candidates using tramp-weighted MSE + cosine similarity (`0.5 * w_mse + 0.5 * cos_loss`) without $1/\sigma_m^2$ variance division. Report unweighted Ordinary Least Squares `scaleFactor`, `rSquared`, and normalized `rmse` matching the LIBS Viewer.
- **2-Pass Alternating RTE + ASD Peak-Envelope P-Spline Spectral Response $R(\lambda)$:**
    - **Pass 1:** Optimize initial 2-zone RTE parameters per staged family using flat response $R^{(0)}(\lambda) = \mathbf{1}$.
    - **Global Response Fit:** Evaluate peak-envelope log-ratios $\log(\tilde{y}_{m,w} / \tilde{I}_{\text{RTE},m,w})$ solely inside non-resonant ASD anchor windows (`Ei_eV > 0.40 eV`, $\pm 1.5\text{ nm}$, prominence $> 4\%$) using Inverse-Family-Size Balanced Weighting ($1 / M_f$), per-material median centering, Huber clipping ($\pm 0.85$), and a stiff 8-knot cubic P-spline ($\mu_R = 2500$, ridge prior $0.25$), clamped to $R(\lambda) \in [0.25, 2.50]$.
    - **Pass 2 (Full-Family Synchronization):** Re-optimize 2-zone RTE parameters and spatial bootstrap variances (`teStdDev`, `neLogStdDev`) for **all** staged material families against the locked $R(\lambda)$, and update `conf/instrument_profile.json`.

---

### 3. Acceptance Criteria
1. `./calibrate.sh -i <dir> -c <comp> -mt <family> -mg <grade> -z 2` caches NIST ASD Parquet files in `data/nist_lines/`, caches the 48-state NIST grid at `R = 3000`, and updates `conf/instrument_profile.json`.
2. `conf/instrument_profile.json` contains `spectralResponse` ($\in [0.25, 2.50]$), `finotelloNoiseBeta`, `baselineCorrectionParams` (`structuringElementSize = 65`), and per-family `plasmaZones`—with **zero** `voigtProfile` fields and **zero** out-of-range hard-coded windows in `temp/calibration_manifest.json`.
3. All staged families in `conf/instrument_profile.json` satisfy physical 2-zone constraints ($V_0 \in [0.75, 0.95]$, $V_1 \in [0.05, 0.25]$, $k_1 \in [0.50, 5.00]$, $T_{e,0} - T_{e,1} \ge 0.12\text{ eV}$) and remain synchronized with the latest `spectralResponse`.
4. `./run.sh` produces synthetic spectra using the exact 5-step normalized RTE + response + scaleFactor pipeline without Voigt convolution.
5. All JUnit and Python test suites pass (`mvn clean test`).