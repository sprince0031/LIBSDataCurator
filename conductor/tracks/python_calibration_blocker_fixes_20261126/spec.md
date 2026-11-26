# Specification: `TRACK-CORE-02A-FIX` — Patch Critical Python Calibration Blockers (`C1`–`C5`, `H1`–`H4`, `M2`–`M3`) Before `TRACK-CORE-02B`

- **Track ID:** `python_calibration_blocker_fixes_20261126`
- **Track Type:** `Bug`
- **Target Repository:** `LIBSDataCurator` (Python calibration & test modules only)
- **Predecessor:** `TRACK-CORE-02A` (`python_calibration_rte_fixes_20261126`) adversarial physics & code-parity review

---

## 1. Overview

Adversarial review of `TRACK-CORE-02A` uncovered critical and high-severity defects in the Python multi-family calibration pipeline (`scripts/gpu_calibration_engine.py`, `scripts/roi_catalogue.py`, `scripts/roi_joint_calibration.py`, and `scripts/measured_conditioning.py`) that silently permute `(Te, Ne)` dictionary columns, starve non-ferrous major-element ROIs (`Al`, `Si`, `Mg`), leak cross-family detectability checks, omit sub-bin uncertainty weights and RTE attenuation in family scale/absorption steps, apply unintended morphological structuring sizes, and leave 6 baseline failures in `pytest tests/ scripts/`.

This track resolves all Python calibration blockers (`C1`–`C5`, `H1`–`H4`, `M2`–`M3`) and restores a 100% passing Python test suite—including full planted-truth recovery (`Clean`, `Harsh`, `Edge`, `Split`) without relaxing any numerical tolerance—prior to starting the Java alignment track (`TRACK-CORE-02B`).

---

## 2. Functional Requirements

### FR-1 (`C1`): Numerical `(Te, Ne)` State-Column Ordering in `gpu_calibration_engine.py`
- In `scripts/gpu_calibration_engine.py` (`run()`, dictionary construction), replace lexicographical `state_keys = sorted(nist_paths.keys())` (which swaps `"1.0E+17"` ahead of `"5.0E+16"` across 24/48 columns) with explicit nested numerical iteration over `(self.te_grid, self.ne_grid)` matching `scripts/forward_model.py:build_material_dictionary`:
  ```python
  for te in self.te_grid:
      for ne in self.ne_grid:
          ...
  ```
- Support manifest key formats `f"{te}_{ne:.1E}"`, `f"{te:.1f}_{ne:.1E}"`, `f"{te}_{ne:.1e}"`, `f"{te:.1f}_{ne:.1e}"`, `f"{te}_{ne}"`, and fallback numerical `(te_val, ne_val)` key parsing within `rtol=1e-6`.
- If `nist_paths` is non-empty and any required `(te, ne)` grid state is missing, raise a descriptive `KeyError` naming the material and missing `(te, ne)` pair.

### FR-2 (`C2`): Remove Global `.head(1500)` ASD Line Starvation
- In `scripts/gpu_calibration_engine.py` (`run()`, NIST ASD line loading), remove `.sort_values("Aki", ascending=False).head(1500)` so global high-`Aki` transition-metal lines (`V`, `Cr`, `Fe`) never crowd out major non-ferrous matrix transitions (`Al`, `Si`, `Mg`, `Cu`, `Zn`, `Sn`, `Pb`).
- Pass all valid transitions `lines_df[valid_l]` (with `wl_nm`, `unc`, and `gA` columns populated) into `build_catalogue(...)`, allowing `roi_catalogue.py` to filter to each family's certified constituent elements and rank by composition-weighted LTE strength.

### FR-3 (`C5` & `H2`): Family-Scoped Conditioning, Baseline-Subtracted $5\sigma$ Check & Saturation Masking
- **Family-scoped conditioning (`C5`):** In `scripts/gpu_calibration_engine.py:run()`, pass `conditioned={m: cond_map[m] for m in fam_mats}` (never global `cond_map`) into `build_catalogue(...)` so non-ferrous ROIs are not rejected when steel materials lack emission at those wavelengths (and vice versa).
- **Baseline-subtracted detectability & saturation exclusion (`H2` + `C5`):** In `scripts/roi_catalogue.py:build_catalogue`, evaluate `y_win` on `cond.preprocessed_mean` when present (falling back to `cond.mean_spectrum` only if `preprocessed_mean is None`), AND reject any candidate ROI window `[w_lo, w_hi]` if `cond.valid_mask is not None` and `not np.all(cond.valid_mask[chan_mask])` in any calibrated family material.
- **Valid-pixel metric masking (`C5`):** In `scripts/gpu_calibration_engine.py:compute_family_calibration_stats`, intersect both `roi_mask` and `lit_mask` with `np.asarray(cond.valid_mask, dtype=bool)` (when `valid_mask` is present) before calling `compute_spectral_metrics(...)` so hard-saturated detector channels cannot corrupt `roiRSquared`, `roiRmse`, `wholeSpectrumRSquared`, or `wholeSpectrumRmse`.

### FR-4 (`C3`): `sigma_dict` Weighting & Multi-Zone RTE Forward Evaluation in `compute_family_scales_and_sharing_penalty`
- Update `compute_family_scales_and_sharing_penalty` in `scripts/roi_joint_calibration.py` and its call site in `scripts/gpu_calibration_engine.py` so the dict-mode evaluator:
  1. Accepts `sigma_dict: Optional[Dict[str, np.ndarray]] = None` (defaulting to unit `1.0` with floor `1e-6` when omitted) and computes inverse-variance weighted per-material scales and weighted objectives:
     $$\alpha_m = \frac{\sum_b (y_{m,b}\,\hat{y}_{m,b}) / \sigma_{m,b}^2}{\max\!\left(\sum_b \hat{y}_{m,b}^2 / \sigma_{m,b}^2,\, 10^{-12}\right)}, \quad \phi_m = \frac{1}{2}\sum_b \left(\frac{y_{m,b} - \alpha_m \hat{y}_{m,b}}{\sigma_{m,b}}\right)^2, \quad \phi_{\text{fam}} = \frac{1}{2}\sum_{m,b} \left(\frac{y_{m,b} - \alpha_{\text{med}} \hat{y}_{m,b}}{\sigma_{m,b}}\right)^2$$
  2. Accepts optional `k_absorption: float = 0.0`, `dictionary`, `sub_bins`, `spectral_response`, and `nz_indices` (or `te_grid`/`ne_grid`): whenever `k_absorption > 0.0` and channel-level inputs are provided, compute $\hat{y}_{m,b} = V_{\text{total}} \sum_{p \in b} R_p \, I_{\text{RTE}, m, p}$ via `apply_multi_zone_rte` (with $V_{\text{total}} = \sum_{j \in \text{nz}} w_j$ and normalized zone fractions $V_z = w_z / V_{\text{total}}$) instead of unattenuated `A_m @ reduced_weights`.

### FR-5 (`C4`): Channel-Exact Sub-Bin RTE Evaluation, Pre-Weighted Rescaling & Zero-Volume Ghost-Zone Guard in `scan_absorption`
- **Pre-weighted channel-exact RTE sub-bins:** Extend `scan_absorption` in `scripts/roi_joint_calibration.py` to accept optional full-spectrum `dictionary`, `sub_bins`, `spectral_response`, `reduced_weights`, and `coolest_state_idx` (or `nz_indices`). When provided, construct pre-weighted full-channel hot and cool profiles:
  - `hot_ch_m[p] = sum_{j != coolest_state_idx} D_m[p, j] * reduced_weights[j]`
  - `cool_ch_m[p] = D_m[p, coolest_state_idx] * reduced_weights[coolest_state_idx]`
  - `d_tilde_cool_m[p] = D_m[p, coolest_state_idx] / max(max_q D_m[q, coolest_state_idx], 1e-12)` (full-spectrum max across all channels $q$)
  and evaluate each trial $k \in \text{k\_grid}$ channel-by-channel before response multiplication and sub-bin summation:
  - `hot_sb[b] = sum_{p in sb_b} spectral_response[p] * hot_ch_m[p] * exp(-k * d_tilde_cool_m[p])`
  - `cool_sb[b] = sum_{p in sb_b} spectral_response[p] * cool_ch_m[p]`
  eliminating sub-bin-averaged `d_tilde` Jensen bias. Preserve legacy `sub_data` list and `A_dict` sub-bin modes for standalone callers.
- **Physical ghost-zone guard (no zero-volume absorbing zones):** Solving the 2-column weighted NNLS yields dimensionless rescaling factors `V = [V_hot, V_cool] >= 0`, mapping to candidate weights `cand_w[j] = reduced_weights[j] * V[0]` for active hot states (`j != coolest_state_idx`) and `cand_w[coolest_state_idx] = reduced_weights[coolest_state_idx] * V[1]`. Inside `scan_absorption`, treat any trial $k > 0$ where NNLS collapses `V[0] <= 0.0` or `V[1] <= 0.0` — OR where either resulting zone volume fraction (hot sum fraction or cool shell fraction, or any active zone fraction in `cand_w / sum(cand_w)`) falls below `1e-3` — as physically invalid and skip it (falling back to `k = 0.0` if no physically valid trial $k > 0$ meets the 2% Huber improvement gate).
- **Weight propagation (`return_weights`):** Add `return_weights: bool = False` to `scan_absorption`:
  - `return_weights=False` (default): returns `(best_k, flags_or_bool)` for full backward compatibility.
  - `return_weights=True`: returns `(best_k, flags_or_bool, updated_weights)`. When a physically valid `best_k > 0.0` passes the 2% Huber improvement gate, return `updated_weights` with every active hot state multiplied by `V[0]` and `coolest_state_idx` multiplied by `V[1]`; when `best_k == 0.0`, return `reduced_weights.copy()` unchanged. Because all hot zones have $k_z = 0$ and only the coolest shell has $k_z = \text{best\_k}$, `V_total * sum_{p in b} R[p] * apply_multi_zone_rte(...)` reproduces `V[0] * hot_sb(best_k) + V[1] * cool_sb` algebraically identically (within `1e-12`) for any `num_zones >= 2`.
- **Engine wiring:** In `scripts/gpu_calibration_engine.py:run()`, invoke `scan_absorption(..., return_weights=True)` with channel-level `dictionary`, `sub_bins`, and `spectral_response`, and assign `reduced_weights = updated_weights` prior to computing scales, `plasmaZones`, `elementParity`, and `calibrationStats`.

### FR-6 (`H1`): Opt-In Guard on `lineSpreadAddSigmaNm`
- In `scripts/gpu_calibration_engine.py:compute_line_spread_add_sigma`, return `np.zeros(len(det_wl), dtype=np.float64)` by default unless `bool(self.manifest.get("enableLineSpreadBroadening", False))` is explicitly `True` alongside `instrumentFwhmNm` or `instrumentResolvingPower`.
- This preserves flux-conserving boxcar resampling (`add_sigma_nm = 0.0`) on NIST `R = 3000` spectra and prevents double-broadening; joint NIST resolving power and detector LSF deconvolution is deferred to a future release using unbroadened NIST stick data (out of scope for `TRACK-CORE-02B`).

### FR-7 (`H3` & `H4`): Morphological Structuring Element (`25` px) & `unionChannelIndices` Schema Persistence
- **`H3` (`measured_conditioning.py` & `gpu_calibration_engine.py`):**
  - In `scripts/measured_conditioning.py:condition_material`, read `manifest.get("baselineCorrectionParams", {}).get("structuringElementSize")` first, falling back to top-level `manifest.get("structuringElementSize")` and `DEFAULT_MORPHOLOGICAL_SE_SIZE` (`25`).
  - In `scripts/gpu_calibration_engine.py`, set `DEFAULT_MORPHOLOGICAL_STRUCT_SIZE = 25` (matching `src/main/resources/metric_contract_v1.json`) and remove the `max(..., 55)` floor at `self.struct_size` (enforcing only `max(..., 1)`).
- **`H4` (`upsert_family_profile`):**
  - In `scripts/gpu_calibration_engine.py:upsert_family_profile` (and its call in `run()`), persist `"unionChannelIndices"` (sorted unique channel indices across all sub-bins / channel_indices of `family_rois`) under both `materialFamilyRois[family]` and `families[family]` in `regionsOfInterest.json`.

### FR-8 (`M2` & `M3`): Strict `compute_element_parity` Mapping, All-Pairs Gate G1 & `composition_scale_verified=False` Default
- **`M2` (`compute_element_parity`):** In `scripts/roi_joint_calibration.py`, remove the silent `z_idx % max(n_cols, 1)` fallback in `_resolve_state_col` and raise `ValueError` if none of `z["stateIndex"]`, valid `nz_indices`, or non-empty `(te_grid, ne_grid)` is supplied.
- **`M3` (`verify_gate_g1` & `composition_scale_verified`):**
  - In `scripts/gpu_calibration_engine.py:verify_gate_g1`, evaluate `composition_scale_check` across **all** material pairs `(m1, m2)` (`itertools.combinations(fam_mats, 2)`) that share at least one non-prone certified-element ROI with positive element fractions and positive dictionary peaks; pass iff at least one pair is evaluated AND **every** evaluated pair passes (`all(...)`), returning detailed per-pair diagnostics.
  - When computing the global `composition_scale_verified` boolean passed into `run_joint_solver(...)`, distinguish single-material families (`len(fam_mats) < 2`, where pairwise Gate G1 is N/A) from multi-material families (`len(fam_mats) >= 2`): set global `composition_scale_verified = True` iff at least one multi-material family is checked AND every multi-material family passes (do not let a 1-material family trigger a false global `COMPOSITION_SCALE_UNVERIFIED` failure). Emit per-family `COMPOSITION_SCALE_UNVERIFIED:<family>` for failing multi-material families without duplicating the unprefixed global flag.
  - Default `composition_scale_verified: bool = False` in both `evaluate_diagnostics(...)` and `run_joint_solver(...)` in `scripts/roi_joint_calibration.py`. Update standalone verified synthetic tests (`test_planted_truth_clean_scenario` and `test_diagnostics_r9_flags_construction`) to pass `composition_scale_verified=True` explicitly.

### FR-9: Joint Solver Outlier/Edge/Subgroup Fixes & Test-Suite Environment Guards
- Without relaxing any threshold in `scripts/test_planted_truth_recovery.py`, fix three mathematical defects in `scripts/roi_joint_calibration.py`:
  1. **`TE_GRID_EDGE` Modeled-Emission Fraction:** Compute `min_te_frac` and `max_te_frac` in `evaluate_diagnostics` from modeled emission per column ($\Phi_j = w_j \sum_p D_{m,p,j}$ when `dictionary` is supplied; fallback $w_j$ when omitted), matching R9's `> 5% of modeled emission` definition.
  2. **Huber-IRLS Block A (`block_a_ridge = 0.02`) & Iteration-Gated Chain-Rule Block B Weights:** In `run_joint_solver`, filter per-material Block A rows to `Y_mb > 3 * sig_meas` and `max_j A_m[b, j] > 1e-12` (when `>= 2` rows qualify), solve Block A with 4-step Huber IRLS (`c = 1.345`) and configurable `block_a_ridge: float = 0.02` implemented via augmented rows `[M_scaled; sqrt(block_a_ridge) * I]` on unit-L2-norm columns (effective diagonal ridge `0.02` on `M_scaled^T M_scaled`, leaving standalone `solve_block_a` exact at `0.0`), and weight Block B log-ratios by `(1 / n_f) * (min(Y_mb, M_mb) / sig_mb)^2 * h_weight` while enforcing the unmodeled-line gate `M_mb > 0.05 * Y_mb` only for `iteration >= 2` (ungated on iteration 1 so >10× dynamic-range spectrometers are never clipped on tilted starts).
  3. **Weight-Supported Overlap Span for `family_group_response_rms_diff`:** Restrict `[lo_common, hi_common]` to the intersection of each group's `[2%, 98%]` cumulative Block B weight domain (requiring `hi_common - lo_common >= 60.0` nm) using fixed converged `best_weights`, resolving `Split` cleanly (`<= 0.05`) while continuing to raise `RESPONSE_FAMILY_INCONSISTENT` (`> 0.25`) on genuinely divergent cross-family response curves.
- Add skip guards in `scripts/test_batch_instrument_calibration.py`, `scripts/test_batch_nist_pull.py` (`self.skipTest` when `data/reference/reference_compositions.json` is absent), and `scripts/test_calibration_inspector.py` (`pytest.importorskip("ipywidgets")`).

---

## 3. Non-Functional Requirements & Guardrails

1. **Read-Only Contract & Java Boundary:**
   - Do **NOT** modify `scripts/match_metrics.py`, `src/main/resources/metric_contract_v1.json`, `src/test/resources/metric_parity_golden_fixtures.json`, or `scripts/parity_bridge.py` (`TRACK-CORE-01`).
   - Do **NOT** modify any `.java` source or test file (all Java changes belong exclusively to `TRACK-CORE-02B`).
   - Do **NOT** regenerate or overwrite existing synthetic output `.csv` datasets on disk.
2. **Untouched Workspace Files:**
   - Leave `notebooks/calibration_inspector.ipynb` and `conductor/instrument_profile_debugging_and_improvement_specification_v1.md` untouched and unstaged.
3. **Determinism:** All solver operations remain deterministic (`float64`, fixed multi-start initializations).

---

## 4. Acceptance Criteria

1. **Column Ordering (`C1`):** Unit test with lexicographically adversarial keys (`"0.5_1.0E+17"`, `"0.5_5.0E+16"`, `"1.0_1.0E+17"`, `"1.0_5.0E+16"`) proves `gpu_calibration_engine.py` maps columns in strict numerical `(te_grid, ne_grid)` order and raises `KeyError` on missing states.
2. **Multi-Family ROI Survival & Saturation Masking (`C2`, `C5`, `H2`):** Unit test with >1,500 high-`Aki` trace `V` lines + `Al`/`Si` lines and a multi-family `cond_map` (Steel + Aluminum, including a saturated pixel and baseline continuum) proves `Al`/`Si` ROIs survive, saturated windows are excluded, and `compute_family_calibration_stats` masks out `~valid_mask` channels.
3. **Weighted & Attenuated Family Post-Processing & Ghost-Zone Guard (`C3`, `C4`):** Unit tests prove:
   - `compute_family_scales_and_sharing_penalty` respects `sigma_dict` and `k_absorption > 0` RTE attenuation;
   - `scan_absorption(..., return_weights=True)` evaluates channel-exact RTE sub-bins, returns updated weights whose `apply_multi_zone_rte` + `apply_response_and_scale` prediction matches the fitted `scan_absorption` prediction within `1e-12` (tested for both 2-zone and 3-zone inputs); and
   - `scan_absorption` skips trial `k > 0` values where NNLS collapses `V_hot <= 0`, `V_cool <= 0`, or any active zone volume fraction `< 1e-3`, falling back to `k = 0.0` so zero-volume absorbing ghost zones cannot occur.
4. **Broadening Guard, SE=25 & ROI Union Channels (`H1`, `H3`, `H4`):** Unit tests verify `compute_line_spread_add_sigma` returns zeros unless `enableLineSpreadBroadening: true`, `struct_size` resolves `25` (no `55` floor), and `upsert_family_profile` writes `"unionChannelIndices"` into `regionsOfInterest.json`.
5. **Strict Parity & All-Pairs Gate G1 (`M2`, `M3`):** Unit tests verify `compute_element_parity` raises `ValueError` without state mapping, `verify_gate_g1` catches a corrupted 3rd material `(m1, m3)` even when `(m1, m2)` passes, single-material families do not falsely fail global `composition_scale_verified`, and `run_joint_solver` / `evaluate_diagnostics` default `composition_scale_verified=False`.
6. **Full Python Suite Green:** `/home/puser/Work/PhD/Code/Medals_classifier/.venv/bin/python -m pytest tests/ scripts/` exits with `0` failures, including all 4 scenarios in `scripts/test_planted_truth_recovery.py` at their original tolerances.

---

## 5. Out of Scope

- Java `LIBSDataService.java`, `InstrumentProfileService.java`, and `SpectrumUtils.java` changes (deferred to `TRACK-CORE-02B`).
- Unbroadened NIST stick-spectrum LSF deconvolution (deferred to a future release).
- Full calibration manifest re-execution overwriting `conf/instrument_profile.json` or synthetic `.csv` outputs on disk.
