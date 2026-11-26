# Implementation Plan: `TRACK-CORE-02A-FIX` — Patch Critical Python Calibration Blockers (`C1`–`C5`, `H1`–`H4`, `M2`–`M3`)

- **Track ID:** `python_calibration_blocker_fixes_20261126`
- **Track Type:** `Bug`
- **Test Runner:** `/home/puser/Work/PhD/Code/Medals_classifier/.venv/bin/python -m pytest`
- **Strict Guardrails:** Zero edits to `TRACK-CORE-01` contract files (`scripts/match_metrics.py`, `src/main/resources/metric_contract_v1.json`, `src/test/resources/metric_parity_golden_fixtures.json`, `scripts/parity_bridge.py`), zero edits to `.java` files, zero synthetic `.csv` regeneration, and zero edits to `notebooks/calibration_inspector.ipynb` or untracked user docs.

---

## Phase 1: State-Column Alignment, Broadening Guard, Multi-Family ROI Survival & Masking (`C1`, `C2`, `C5`, `H1`, `H2`, `H3`, `H4`) [checkpoint: 3177735]

- [x] Task: Fix numerical `(Te, Ne)` state-column ordering (`C1`) and opt-in broadening guard (`H1`) in `scripts/gpu_calibration_engine.py` (d32e11a)
  - [x] Write failing unit tests in `tests/test_roi_joint_calibration_math.py` covering lexicographically adversarial `(Te, Ne)` manifest keys (`"0.5_1.0E+17"`, `"0.5_5.0E+16"`, `"1.0_1.0E+17"`, `"1.0_5.0E+16"`), `KeyError` on missing grid states, and `compute_line_spread_add_sigma` returning zeros unless `enableLineSpreadBroadening: true`
  - [x] Implement explicit nested `(self.te_grid, self.ne_grid)` state-key resolution helper in `scripts/gpu_calibration_engine.py` (supporting `f"{te}_{ne:.1E}"`, `f"{te:.1f}_{ne:.1E}"`, lowercase `e`, raw `f"{te}_{ne}"`, and numerical key fallback within `rtol=1e-6`, raising `KeyError` on missing states)
  - [x] Gate `compute_line_spread_add_sigma` behind `bool(self.manifest.get("enableLineSpreadBroadening", False))` (default `False` -> zero vector)
  - [x] Run targeted pytest and verify Green

- [x] Task: Eliminate `.head(1500)` line starvation (`C2`), scope `cond_map` per family (`C5`), and enforce baseline-subtracted $5\sigma$ & saturation masking (`C5`, `H2`) (1d21f01)
  - [x] Write failing unit tests in `tests/test_roi_joint_calibration_math.py` covering (a) >1,500 high-`Aki` trace `V` lines + `Al`/`Si` lines retaining `Al`/`Si` ROIs, (b) multi-family `cond_map` (Steel + Aluminum) not rejecting Aluminum ROIs, (c) `build_catalogue` evaluating $5\sigma$ detectability on `preprocessed_mean` and excluding windows with any `~valid_mask` (saturated) channel, and (d) `compute_family_calibration_stats` intersecting `roi_mask` and `lit_mask` with `valid_mask`
  - [x] Remove `.sort_values("Aki", ascending=False).head(1500)` and pass family-scoped `{m: cond_map[m] for m in fam_mats}` to `build_catalogue(...)` in `scripts/gpu_calibration_engine.py:run()`
  - [x] Update `scripts/roi_catalogue.py:build_catalogue` to evaluate $5\sigma$ detectability on `cond.preprocessed_mean` (falling back to `cond.mean_spectrum` only when `None`) and reject any candidate window where `cond.valid_mask is not None` and `not np.all(cond.valid_mask[chan_mask])`
  - [x] Intersect `roi_mask` and `lit_mask` with `cond.valid_mask` in `scripts/gpu_calibration_engine.py:compute_family_calibration_stats`
  - [x] Run targeted pytest and verify Green

- [x] Task: Align morphological structuring element (`25` px) (`H3`) and persist `unionChannelIndices` (`H4`) (3177735)
  - [x] Write failing unit tests in `tests/test_roi_joint_calibration_math.py` asserting `condition_material` reads `baselineCorrectionParams.structuringElementSize` before top-level `structuringElementSize` and default `25`, `GpuCalibrationEngine.struct_size` defaults to `25` with no `55` floor, and `upsert_family_profile` writes `"unionChannelIndices"` into `regionsOfInterest.json`
  - [x] Update `scripts/measured_conditioning.py:condition_material` to resolve `baselineCorrectionParams.structuringElementSize` -> top-level `structuringElementSize` -> `DEFAULT_MORPHOLOGICAL_SE_SIZE` (`25`)
  - [x] Set `DEFAULT_MORPHOLOGICAL_STRUCT_SIZE = 25`, remove the `max(..., 55)` floor, and persist `"unionChannelIndices"` inside `upsert_family_profile` (both `materialFamilyRois[family]` and `families[family]`) in `scripts/gpu_calibration_engine.py`
  - [x] Run targeted pytest and verify Green

- [x] Task: Phase Verification & Checkpoint (Refer to `workflow.md`) — 59 passed, 0 failed (`tests/ scripts/test_measured_conditioning.py scripts/test_roi_catalogue.py scripts/test_gpu*`)

---

## Phase 2: Channel-Exact Absorption Scan, Ghost-Zone Guard & Weighted/Attenuated Family Scales (`C3`, `C4`)

- [ ] Task: Implement channel-exact RTE `scan_absorption` with `return_weights=True` and physical ghost-zone guard (`C4`)
  - [ ] Write failing unit tests in `tests/test_roi_joint_calibration_math.py` covering: (a) channel-exact RTE sub-bin evaluation eliminating sub-bin-mean Jensen bias, (b) `return_weights=True` returning `(best_k, k_flag, updated_weights)` while default `return_weights=False` preserves 2-tuple compatibility, (c) `apply_multi_zone_rte` + `apply_response_and_scale` with `updated_weights` reproducing the fitted `scan_absorption` sub-bin prediction within `1e-12` for both 2-zone and 3-zone profiles, and (d) skipping trial $k > 0$ when NNLS yields `V_hot <= 0`, `V_cool <= 0`, or any active zone volume fraction `< 1e-3` (falling back to `k = 0.0` so no zero-volume absorbing ghost zone is emitted)
  - [ ] Implement channel-exact pre-weighted `(hot_ch, cool_ch)` RTE evaluation, physical zone-fraction validity check (`V_hot > 0`, `V_cool > 0`, all active `vf >= 1e-3`), and `return_weights: bool = False` weight update (`updated_weights[j] *= V[0]` for active hot states, `updated_weights[coolest_state_idx] *= V[1]`) in `scripts/roi_joint_calibration.py:scan_absorption`
  - [ ] Wire `scripts/gpu_calibration_engine.py:run()` to call `scan_absorption(..., dictionary=..., sub_bins=..., spectral_response=..., return_weights=True)` and propagate `updated_weights` into downstream family scales, `plasmaZones`, `elementParity`, and `calibrationStats`
  - [ ] Run targeted pytest and verify Green

- [ ] Task: Propagate `sigma_dict` and multi-zone RTE attenuation into `compute_family_scales_and_sharing_penalty` (`C3`)
  - [ ] Write failing unit tests in `tests/test_roi_joint_calibration_math.py` proving `compute_family_scales_and_sharing_penalty` uses `1 / sigma^2` weighting (downweighting high-`sigma` outlier bins) and evaluates channel-exact `apply_multi_zone_rte` when `k_absorption > 0.0`
  - [ ] Update `scripts/roi_joint_calibration.py:compute_family_scales_and_sharing_penalty` dict-mode branch to accept `sigma_dict`, `k_absorption`, `dictionary`, `sub_bins`, `spectral_response`, `nz_indices`, and `te_grid`/`ne_grid`, computing inverse-variance weighted `alpha_m`, `phi_m`, and `phi_fam` with attenuated predictions whenever `k_absorption > 0.0`
  - [ ] Wire `scripts/gpu_calibration_engine.py:run()` to pass `sigma_dict`, `k_absorption`, `dictionary`, `sub_bins`, `spectral_response`, `nz_indices`, `te_grid`, and `ne_grid` using the updated `reduced_weights`
  - [ ] Run targeted pytest and verify Green

- [ ] Task: Phase Verification & Checkpoint (Refer to `workflow.md`)

---

## Phase 3: Strict Element-Parity State Mapping, All-Pairs Gate G1 & `composition_scale_verified=False` Default (`M2`, `M3`)

- [ ] Task: Remove silent modulo fallback in `compute_element_parity` (`M2`)
  - [ ] Write failing unit test in `tests/test_roi_joint_calibration_math.py` verifying `compute_element_parity` raises `ValueError` when no `stateIndex`, valid `nz_indices`, or non-empty `(te_grid, ne_grid)` is provided
  - [ ] Replace `return z_idx % max(n_cols, 1)` in `scripts/roi_joint_calibration.py:_resolve_state_col` with a descriptive `ValueError`
  - [ ] Run targeted pytest and verify Green

- [ ] Task: Enforce all-pairs Gate G1 (with single-material N/A handling) and safe `composition_scale_verified=False` defaults (`M3`)
  - [ ] Write failing unit tests in `tests/test_roi_joint_calibration_math.py` verifying (a) `verify_gate_g1` checks every `(m1, m2)` combination in a 3+ material family and fails when `(m1, m3)` violates composition scaling even if `(m1, m2)` passes, (b) global Gate G1 passes iff at least one multi-material family (`len(fam_mats) >= 2`) is checked and all multi-material families pass (ignoring 1-material families where pairwise G1 is N/A), and (c) `evaluate_diagnostics` and `run_joint_solver` default `composition_scale_verified=False`
  - [ ] Update `scripts/gpu_calibration_engine.py:verify_gate_g1` to evaluate all valid material pairs via `itertools.combinations(fam_mats, 2)` and compute global `composition_scale_verified` across multi-material families only (`len(fam_mats) >= 2`) before calling `run_joint_solver(..., composition_scale_verified=...)` without duplicating flags
  - [ ] Set `composition_scale_verified: bool = False` default in `scripts/roi_joint_calibration.py:evaluate_diagnostics` and `run_joint_solver`
  - [ ] Update verified synthetic unit tests (`scripts/test_roi_joint_calibration.py` and `scripts/test_planted_truth_recovery.py:test_planted_truth_clean_scenario`) to pass `composition_scale_verified=True` explicitly where compositions are verified by construction
  - [ ] Run targeted pytest and verify Green

- [ ] Task: Phase Verification & Checkpoint (Refer to `workflow.md`)

---

## Phase 4: Joint Solver Planted-Truth Math Fixes & Full Python Test-Suite Green Gate (`FR-9`)

- [ ] Task: Fix modeled-emission `TE_GRID_EDGE`, Huber-IRLS Block A (`block_a_ridge=0.02`), chain-rule Block B (`M_mb > 0.05 * Y_mb` for `iter >= 2`), and `[2%, 98%]` weight-domain `family_group_response_rms_diff`
  - [ ] Verify existing failures in `scripts/test_planted_truth_recovery.py` (`Clean`, `Harsh`, `Split`) and write targeted unit tests in `tests/test_roi_joint_calibration_math.py` for modeled-emission `TE_GRID_EDGE` and iteration-gated Block B outlier protection
  - [ ] Update `scripts/roi_joint_calibration.py:evaluate_diagnostics` to compute `TE_GRID_EDGE` fractions on modeled column emission ($\Phi_j = w_j \sum_p D_{m,p,j}$) when `dictionary` is supplied (falling back to $w_j$ when omitted)
  - [ ] Update `scripts/roi_joint_calibration.py:run_joint_solver` to filter active per-material Block A rows (`Y_mb > 3 * sig_meas` & `max_j A_m[b, j] > 1e-12`), solve 4-step Huber IRLS with `block_a_ridge: float = 0.02` (`[M_scaled; sqrt(block_a_ridge) * I]`, leaving standalone `solve_block_a` exact at `0.0`), compute Block B weights as `(1 / n_f) * (min(Y_mb, M_mb) / sig_mb)^2 * h_weight` with `M_mb > 0.05 * Y_mb` enforced for `iteration >= 2`, and restrict `family_group_response_rms_diff` to the intersection of each group's `[2%, 98%]` cumulative Block B weight domain (`>= 60 nm`)
  - [ ] Run `pytest scripts/test_planted_truth_recovery.py tests/test_roi_joint_calibration_math.py scripts/test_roi_joint_calibration.py` and verify 100% pass with zero tolerance relaxation

- [ ] Task: Add missing-resource & optional-dependency skip guards and run full Python regression gate
  - [ ] Add `if not Path(DEFAULT_JSON_PATH).exists(): self.skipTest(...)` in `scripts/test_batch_instrument_calibration.py` and `scripts/test_batch_nist_pull.py`, and `pytest.importorskip("ipywidgets")` in `scripts/test_calibration_inspector.py`
  - [ ] Run full `/home/puser/Work/PhD/Code/Medals_classifier/.venv/bin/python -m pytest tests/ scripts/` and confirm 0 failures
  - [ ] Verify `git status` and `git diff --name-only` confirm zero changes to `TRACK-CORE-01` contract files, `.java` files, synthetic `.csv` outputs, or untracked user files

- [ ] Task: Phase Verification & Checkpoint (Refer to `workflow.md`)
