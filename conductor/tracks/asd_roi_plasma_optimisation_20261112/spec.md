# Specification: ROI-Scoped Joint Response–Plasma Calibration for Best Spectral Match

## Track Metadata
- **Track ID:** `asd_roi_plasma_optimisation_20261112` (unchanged)
- **Git Branch Policy:** Stay on the existing branch `feat/instrument-profile-optimisation`. Do not create or check out another branch.
- **Prerequisite Track:** `gpu_calibration_pipeline_20261027` (v0.9.8).
- **Supersedes:** the earlier specification of this track (commit `a714c8b`). The registry title in `conductor/tracks.md` is unchanged.
- **Rationale and evidence:** [`analysis.md`](./analysis.md). References such as "A§4.1" point into that report.
- **Backward compatibility:** not required. The profile schema and the Java forward model may change.
- **Out of scope:** any classifier or downstream model; cleanup of the stale Java grid-search flow in `InstrumentProfileController`; the generator's noise model; generating NIST spectra locally from the ASD cache.

---

## 1. Goal

Make the synthetic spectrum match the available measured spectra as closely as the physics model
allows, inside regions of interest (ROIs) that are chosen from composition and atomic data alone, and
show the quality of that match on data that was not used for calibration.

Two principles govern every requirement:

1. **Calibration is driven only by certified composition, atomic data and the measured spectra.** No
   downstream use of the dataset may influence which channels are fitted or how they are weighted.
2. **Every element is treated by the same rule.** There are no per-element or per-category weights.
   Residual bias per element is measured and reported, not tuned away.

### 1.1 Why the previous approach is replaced
1. Lines were ranked on two incompatible scales, so ROIs and response anchors fell on lines that NIST LIBS spectra do not contain (A§4.1, A§6).
2. Synthetic lines were 2–3× narrower than measured and were point-sampled onto the detector grid (A§4.4).
3. The response estimator had about two effective degrees of freedom (A§4.2).
4. The plasma search ended on its bounds in every family (A§4.3).
5. An earlier NNLS + spline loop (commit `096ab85`) ran away. Its causes are identified in A§7.3 and each is addressed below.

---

## 2. Definitions

| Term | Meaning |
|---|---|
| Lit channel | A detector channel that receives light. The leading channels 0–15 of this instrument do not (A§3). |
| Steady-state shots | Shots remaining after the leading conditioning shots of each measurement point are discarded. |
| Model line | An ASD line with `boltzmann_ok == True`. Only these exist in NIST LIBS spectra (A§5). |
| State | One `(Te, Ne)` node of the anchor grid `teAnchorGridEv × neAnchorGridCm3`. |
| Dictionary | For material *m*, the matrix `D_m[p, j]`: the NIST `Sum(calc)` spectrum of state *j*, resampled to the detector grid in **absolute NIST units**. |
| ROI window | A contiguous wavelength interval. |
| Sub-bin | A run of `subBinPixels` consecutive channels inside an ROI window. Sub-bin sums are the data of the fit. |
| Thin ROI | A window whose lines are unlikely to be self-absorbed. Used for response, plasma, absorption and scale. |
| Prone ROI | A window dominated by a strong low-lying line of a matrix element, likely self-absorbed. Excluded from the response fit; used for absorption and scale. |

---

## 3. Requirements

### R1. Line data (`scripts/nist_lines.py`)
`nist_lines.py` is the single owner of composition parsing and line scoring.

1. Add `parse_composition_dict(raw_comp) -> dict[str, float]` (string with `-` or `:` delimiters, ranges `lo:hi`, remainder `#`, or a dict; result sums to 100 wt%). Remove the duplicate from `gpu_calibration_engine.py`.
2. Add `to_atomic_fractions(comp_wt: dict[str, float]) -> dict[str, float]` using standard atomic masses.
3. Add `ACCURACY_UNCERTAINTY: dict[str, float]`, the fractional A-value uncertainty per NIST grade. A trailing `'` is stripped before lookup; unknown grades map to `0.50`.
   Defaults: `AAA 0.003, AA 0.01, A+ 0.02, A 0.03, B+ 0.07, B 0.10, C+ 0.18, C 0.25, D+ 0.40, D 0.50, E 1.00`.
   **These values must be checked against the NIST ASD documentation before the track is closed.**
4. Add `load_model_lines(elements, wl_range, cache_dir=None, include_common=False) -> pd.DataFrame`. It returns only rows with `boltzmann_ok`, with added columns `wl_nm`, `gA = g_k * Aki` and `unc`.
5. Add `lte_strength(df, t_ev: float) -> np.ndarray` returning `gA / wl_nm * exp(-Ek_eV / t_ev)`.
6. **No function may rank lines by `rel_intensity`**, alone or mixed with `lte_strength`. Delete the `rel_intensity * 1e4` fallback wherever it occurs.

### R2. Flux-conserving resampling with the measured line spread
New module `scripts/spectral_resampling.py`, with a Java twin.

1. `pixel_edges(lam_det) -> np.ndarray` of length `P + 1`: midpoints between channel centres, end edges extrapolated by half a pitch.
2. `resample_to_detector(wl_src, s_src, lam_det, add_sigma_nm) -> np.ndarray`. For pixel *p* with edges `e_p, e_{p+1}` and `σ = add_sigma_nm[p]`:

   $$W_p(\lambda) = \tfrac{1}{2}\left[\operatorname{erf}\!\frac{e_{p+1}-\lambda}{\sqrt2\,\sigma} - \operatorname{erf}\!\frac{e_p-\lambda}{\sqrt2\,\sigma}\right],\qquad
   \text{out}_p = \frac{1}{e_{p+1}-e_p}\int s_{\text{src}}(\lambda)\,W_p(\lambda)\,d\lambda$$

   The integral uses trapezoid weights on the non-uniform source grid, restricted to `[e_p − 5σ, e_{p+1} + 5σ]`. When `σ == 0`, `W_p` is the pixel box. The result is the mean spectral density over the pixel.
3. `measure_source_fwhm(wl_src, s_src)`: FWHM of isolated peaks of a NIST spectrum per band, linearly interpolated in wavelength. The source width is **measured**, never taken from a label (A§4.4).
4. `measure_instrument_fwhm(lam_det, y_mean) -> np.ndarray` per channel: for peaks above `8 × median SEM`, twice the smaller half-width at half maximum; the lower quartile per band (347–450, 450–550, 550–700, 700–897 nm); linear interpolation between band centres; clamped to `[1.0, 4.0]` pixels. The manifest key `lineSpreadFwhmNm` (scalar or per-channel array) overrides it.
5. `add_sigma_nm = sqrt(max(fwhm_inst² − fwhm_src², 0)) / 2.3548`.
6. `np.interp` and `interpolate_dictionary_bilinear_gpu` must not appear on the new calibration path.
7. **Java:** add `SpectrumUtils.resampleToDetector(double[] srcWl, double[] srcI, double[] detWl, double[] addSigmaNm)` with identical arithmetic. `LIBSDataService` uses it in place of `interpolateSpectrum` whenever the profile carries `lineSpreadAddSigmaNm`, and passes that array unchanged. Generation must request NIST spectra at the family `resolution`, the same resolving power used for calibration.

### R3. Measured-data conditioning (`scripts/measured_conditioning.py`)
Applied per material, pooling all calibration points.

1. **Lit mask.** Let `m0` be the median over the first 8 channels of the across-shot mean. Leading channels are unlit until the first channel whose mean exceeds `3 × m0`. Unlit channels are excluded from every fit and metric. The dark level `d` is the median of the unlit means (0 if fewer than 4 unlit channels) and is subtracted from every shot.
2. **Conditioning shots.** Per point, let `r_n` be the Pearson correlation over lit channels between shot *n* and the mean of the last half of the shots. Discard shots `1..n0`, where `n0` is the last index with `r_n < 0.999`, clamped to `[3, 15]`. The manifest key `discardLeadingShots` overrides it. Log `n0` per point.
3. **Saturation.** A reading is invalid when `≥ saturationThresholdFactor × detectorFullScale`. A channel with fewer than `max(5, 25%)` valid steady-state shots is invalid for that material. It is masked, never set to zero.
4. **Shot scale.** `a_n = ⟨y_n, ȳ⟩ / ⟨ȳ, ȳ⟩` over lit, valid channels, with one refinement pass. Normalised shots are `y_n / a_n`.
5. **Mean and variance.** `ȳ_p` is the mean of valid normalised shots; `σ²_meas,p = var / N_p`. `sigma_mean` must reach the solver.
6. **Baseline.** `preprocess(spectrum)` is the morphological opening with `structuringElementSize` (default 65) followed by `max(· , 0)`. It is applied to `ȳ`.
7. **Same operator on both sides.** Every reported metric applies `preprocess` to the synthetic spectrum as well as the measured one. (The opening removes 1–8% of a line-only synthetic spectrum, A§11. The fit itself uses the unprocessed model; this residual asymmetry is accepted.)
8. Per-point means are produced the same way for R8.6.

### R4. Forward model on the absolute NIST scale (`scripts/forward_model.py`, `SpectrumUtils.java`)
Python and Java implement the same operator. Zones `z = 0..Q−1` are sorted by `Te` descending; `d_z` is the resampled NIST spectrum of zone *z* at the material's composition; `d̃_z = d_z / max_p d_z`; `k_0 = 0`.

$$I_0 = V_0\,d_0,\qquad I_z = I_{z-1}\,e^{-k_z \tilde d_z} + V_z\,d_z,\qquad \hat y = \text{scaleFactor}\cdot R\cdot I_{Q-1}$$

1. The emission term uses `d_z` **without per-zone max-normalisation**. NIST intensities share one scale across states (A§5), so `V_z` are relative emission measures. Only the opacity term uses `d̃_z`.
2. `k_z` has lower bound 0.
3. Zones lie on grid states, so Java fetches exactly the spectra used in calibration.
4. **Gate G1 (composition scale).** Using the cached NIST grids of two materials of one family, verify that for an isolated line the ratio `peak / (species fraction × elemental atomic fraction)` agrees between the two compositions within 5% at the same state. Record the result in `calibrationDiagnostics.compositionScaleCheck`.
   - **If G1 passes:** the post-response max-normalisation is removed in Python and Java, as in the formula above. Synthetic brightness then varies with composition, and no channel depends on which line happens to be strongest.
   - **If G1 fails:** keep `Ĩ = R·I / max(R·I)` before applying `scaleFactor`, raise `COMPOSITION_SCALE_UNVERIFIED`, and record this in the profile as a known source of bias.
5. NIST values are multiplied by a fixed constant `NIST_UNIT_SCALE = 1e-6` on load, for numerical convenience only.
6. **Java:** update `SpectrumUtils.combineNZones` and `applyInstrumentResponseAndNoise` accordingly. The profile field `emissionScale` (`"absolute"` or `"max_normalised"`) tells Java which form to use.

### R5. ROI catalogue (`scripts/roi_catalogue.py`)
Replaces the earlier `asd_roi_discovery.py` design. Inputs are the family compositions, model lines, the dictionary and the measured spectra. Nothing else.

1. **Candidates.** For each family, all model lines of every certified element at or above 0.05 wt%, inside the lit range, with `unc ≤ 0.25`.
2. **Masks.** Exclude lines inside `maskWindowsNm`. Defaults cover lines that are present in measured spectra but absent from the model: H 653.3–659.3; O I 775.8–778.8 and 843.1–846.1; N I 741.6–747.6, 817.6–825.0, 855.9–872.6; Na 588.0–590.6; K 765.5–770.9; Ca II 392.6–394.2 and 396.0–397.7; Ca I 421.9–423.5.
3. **Model detectability.** The window sum of the reference dictionary column (`Te = 0.8 eV`, `Ne = 1e17`) is at least 0.5% of the largest such window sum. In addition the line itself must matter: its `lte_strength(0.8) ×` atomic fraction is at least `1e-4` of the largest such value among the family's candidates, and at least 10% of the summed value over model lines of all certified elements inside its window. (This removes lines from very high levels, such as visible Cu II lines, that sit next to a strong line.)
4. **Measured detectability.** The window sum is at least `5σ` in every calibrated material of the family.
5. **Prone rule.** A candidate is *prone* when its element is at least 5 wt% in the family and `Ei_eV ≤ proneEiEv` (default 2.0). Otherwise it is *thin*.
6. **Selection, identical for every element.**
   - *Thin set:* per 25 nm band, split thin candidates into terciles of `Ek_eV` and keep the strongest `linesPerBand / 3` in each tercile (`linesPerBand` default 12), ranked by `lte_strength(0.8) ×` atomic fraction.
   - *Element coverage:* for every certified element, add its `linesPerElement` (default 6) strongest detectable candidates not already selected, thin or prone, keeping at least 0.6 nm between lines of the same element.
7. **Windows.** `± roiHalfWidthNm` (default 1.2) around each selected line, merged when they overlap. A merged window is prone if any prone line contributes more than 30% of its reference model flux.
8. **Sub-bins.** Every window is partitioned into consecutive runs of `subBinPixels` (default 4) channels; a final remainder shorter than 3 channels joins the previous run. Sub-bins are channel-disjoint.
9. **Sets.** The *response set* is all thin sub-bins. The *full set* is all sub-bins.

### R6. Objective
For material *m* and sub-bin *b*:

$$Y_{m,b}=\sum_{p\in b} y_{m,p},\qquad
\sigma^2_{m,b}=\sum_{p\in b}\sigma^2_{\text{meas},m,p}+(\varepsilon_b\,Y_{m,b})^2+\sigma_{\text{floor}}^2,\qquad
\varepsilon_b=\sqrt{\varepsilon_{\text{model}}^2+\varepsilon_{\text{atomic},b}^2}$$

$$\Phi=\sum_{m}\sum_{b}\rho_c\!\left(\frac{Y_{m,b}-M_{m,b}}{\sigma_{m,b}}\right)+\mu_R\,\bar v\,\lVert\Delta_2\boldsymbol\beta\rVert^2$$

- `M_{m,b}` is the modelled sub-bin sum.
- `ε_model` defaults to 0.10 (manifest key `modelErrorFraction`).
- `ε_atomic,b` is the strength-weighted RMS of `unc` over model lines in the sub-bin, capped at 0.5.
- `σ_floor = 3·sqrt(n_px)·σ_dark`, with `σ_dark` the per-reading standard deviation on unlit channels (30 counts if there are none).
- `ρ_c` is the Huber loss with `c = 1.345`, applied as IRLS weights frozen within an outer iteration.
- `v̄` is the mean response weight, which makes `μ_R` (default 1.0) dimensionless.
- **No per-element, per-category or per-ROI weights exist.** R² and RMSE are reported, never optimised.

### R7. Joint solver (`scripts/roi_joint_calibration.py`)
Two-block descent on Φ over the response set. No `torch` import.

**Response model.** `log r = B β`, cubic B-spline with `K` coefficients and clamped uniform knots spanning `[λ_lo, λ_hi]`, the extent of the response set. Outside the span the response is held at the boundary value. `K` defaults to 5 and is reduced, to a minimum of 4, until the knot spacing `(λ_hi − λ_lo)/(K − 3)` is at least 1.5 times the widest gap between consecutive response sub-bin centres.

**Block A (plasma, per material).** With `r` fixed, solve
`w_m = argmin_{w ≥ 0} ‖(Y_m − A_m w)/σ_m‖²` by NNLS, where `A_m[b, j] = Σ_{p∈b} r_p D_m[p, j]`. Columns are scaled to unit maximum before the solve and unscaled afterwards.

**Block B (response).** With `{w_m}` fixed, let `a_{m,b} = Σ_{p∈b} (D_m w_m)_p`. For sub-bins with `Y > 3σ_meas` and `a > 0`:
`ρ_{m,b} = log(Y_{m,b}/a_{m,b})`, centre `λ_{m,b}` the flux-weighted mean wavelength, and weight

$$v_{m,b} = \frac{1}{n_{f(m)}}\left(\frac{Y_{m,b}}{\sigma_{m,b}}\right)^{2} h_{m,b}$$

where `h` is the Huber weight and `n_f` the number of calibrated materials in the material's family. The `1/n_f` factor keeps a family with many reference materials from dominating the shared response. Solve

$$\boldsymbol\beta=\left(\mathbf B^\top\mathbf V\mathbf B+\mu_R\,\bar v\,\Delta_2^\top\Delta_2\right)^{-1}\mathbf B^\top\mathbf V\boldsymbol\rho$$

pooling all materials of all families, then fix the gauge by subtracting the `v`-weighted mean of `log r` at the sub-bin centres.

**Loop.** Start from `β = 0`. Alternate A, B until `max |Δ log r| < 1e-4` at the sub-bin centres and the relative change of Φ is below `1e-6`, to a maximum of 100 iterations. Run three starts (flat, and a linear tilt of ±0.5 ln units across the span) and keep the lowest Φ. Record the largest difference in `log r` between starts as `responseRestartSpread`.

**No clipping.** The solver never clips `r` or `β`.

### R8. Family zones, absorption, scale, dispersion
With the response locked, on the full set:

1. **Family weights.** For family *f*, solve shared weights `w_f ≥ 0` and per-material scales `α_m > 0` by alternating NNLS (stacked rows `α_m A_m`) and closed-form weighted least squares for `α_m`, to a relative change below `1e-4` or 5 sweeps. In this step prone sub-bins are excluded.
2. **Zone reduction.** While the number of non-zero states exceeds `numZones` (default 2), drop the state with the smallest total contribution and re-solve on the remaining support. Record `Φ_reduced / Φ_full` as `zoneReductionPenalty`.
3. **Absorption.** If `numZones ≥ 2` and the family has prone sub-bins, scan `k` for the coolest zone over 81 values in `[0, 8]`. For each `k`, form the R4 model on the full set, re-solve `V` and `α`, and evaluate Φ. Keep the minimiser, except that `k = 0` is kept when the improvement is under 2% of `Φ(0)`. Other zones have `k = 0`.
4. **Export.** `volumeFraction_z = V_z / ΣV`; `Te`, `Ne` are grid values.
5. **Scale factor.** `α_m` is the weighted least-squares scale between `Y_{m,b}` and the modelled sub-bin sums over the full set. `scaleFactor` is the **median** over the family's materials; per-material values are stored in `materialScaleFactors`. All reported statistics use the exported family `scaleFactor`.
6. **Dispersion.** Repeat Block A per measurement point. `teStdDev` is the standard deviation across points of the emission-weighted mean `Te`, clamped to `[0.01, 0.10]`; `neLogStdDev` likewise for `log10 Ne`, clamped to `[0.02, 0.30]`. Both are applied to every zone.
7. **Per-material reference.** Also store the per-material Block A solution reduced to `numZones` as `materialPlasmaZones`, and `familySharingPenalty = Φ_family / Σ_m Φ_material`. This shows how much of the remaining mismatch comes from sharing one plasma state across a family.

### R9. Diagnostics
Diagnostics are written to `calibrationDiagnostics` and logged at WARNING. With `--strict`, any flag makes the engine exit with status 2.

| Flag | Condition |
|---|---|
| `TE_GRID_EDGE` | A state at the minimum or maximum `Te` carries more than 5% of a material's modelled ROI emission |
| `RESPONSE_RANGE_SUSPECT` | `max r / min r > 30` over the span |
| `RESPONSE_GAP` | The knot-spacing rule of R7 cannot be met with `K = 4` |
| `RESPONSE_RESTART_SPREAD` | `responseRestartSpread > 0.10` |
| `RESPONSE_FAMILY_INCONSISTENT` | Split the families into two groups by dominant element (iron-based, and all others). When each group has response sub-bins covering a common span of at least 60 nm, run R7 on each group alone and compute the RMS difference of `log r` over the common span after removing the mean. Flag when it exceeds 0.25. The value is stored as `familyGroupResponseRmsDiff` |
| `SOURCE_RESOLUTION_MISMATCH` | The measured source resolving power (median `λ / FWHM` of the dictionary spectra) differs from the manifest `nistResolvingPower` by more than 10% |
| `COMPOSITION_SCALE_UNVERIFIED` | Gate G1 of R4 failed or could not be run |
| `ZONE_REDUCTION_PENALTY` | `zoneReductionPenalty > 1.10` |
| `K_ABSORPTION_AT_BOUND` | The absorption scan selects `k = 8` |
| `ELEMENT_BIAS` | For a certified element with at least 3 sub-bins in which it supplies over 70% of the modelled flux, the median `log10(synthetic/measured)` over those sub-bins exceeds 0.30 in magnitude |
| `CHI2_HIGH` | Reduced χ² of a material exceeds 5 |
| `NOT_CONVERGED` | The loop reaches 100 iterations |

Element attribution for `ELEMENT_BIAS` uses the per-species columns of the NIST CSVs at the family's zone states, summed per element.

### R10. Engine integration and outputs
1. `GpuLibsCalibrationEngine.run()` orchestrates R3 → R5 → R7 → R8 → R9 and writes the profile and `data/regionsOfInterest.json`. The module must import without `torch`; legacy functions may import it lazily. Legacy functions stay in the file unused; removing them is not part of this track.
2. Materials listed under the manifest key `validationMaterials` are loaded but excluded from R5–R8. The Java manifest upsert must preserve this key.
3. Profile and ROI export schemas are in §5. Both files are upserted without discarding other families.
4. `resolution` in each family profile keeps its meaning: the resolving power at which the dictionary was requested from NIST, taken from the manifest.
5. The engine takes no input that describes a downstream use of the dataset.

### R11. Match report (`scripts/validate_spectral_match.py`, `scripts/match_metrics.py`)
1. `match_metrics.py` holds the metric functions used by both the engine and the validation script, so that reported numbers are computed one way.
2. For every material with hold-out CSVs, and every validation material, compare the measured hold-out mean with the synthetic spectrum from the exported profile (family zones, response, `scaleFactor`, the material's own dictionary). Hold-out data must not have been used in R5–R8.
3. Per material, report:
   - reduced χ² over the full set, with the variance model of R6;
   - `roiRSquared`, `roiRmse` over ROI channels and `wholeSpectrumRSquared`, `wholeSpectrumRmse` over lit channels, both sides passed through `preprocess`;
   - the distribution of sub-bin `log10(synthetic/measured)`: median, 10th and 90th percentile, for thin and prone sub-bins separately;
   - the per-element median of the same quantity (the element parity table).
4. Report the same numbers for three ablations, each with family zones re-solved: flat response (`r ≡ 1`); no absorption (`k ≡ 0`); and legacy rendering (`np.interp` without added line spread).
5. Output: `data/spectralMatchReport.json`.

### R12. Planted-truth recovery test (`scripts/test_planted_truth_recovery.py`)
A generator with known truth that does not share the fitting discretisation:
- 2032 lit channels of the real grid; at least 1,500 synthetic lines over 8 elements with `E_k = E_i + hc/λ`; Saha–Boltzmann emissivities; 7 materials in 5 families.
- The true response is an analytic function with a 20× range. The true plasma is two off-grid zones per material.
- Source spectra are rendered at λ/3000 on a 0.02 nm grid and pass through `resample_to_detector`. The generating line width is 0.40 nm; the fitting width is whatever `measure_instrument_fwhm` returns.
- The fitting dictionary uses A-values perturbed by 15% (log-normal).

| Scenario | Additional conditions | Must hold |
|---|---|---|
| Clean | none | Response RMS error ≤ 10% over the span; converged; no flags |
| Harsh | 30% of lines absent from the dictionary; unmodelled lines above 560 nm; self-absorption on strong low-`E_i` matrix lines; 5% of A-values wrong by a factor of about 2 | Response RMS error ≤ 20% in 350–550 nm; `max r / min r` ≤ 3× truth; converged |
| Edge | one material whose true temperature (0.42 eV) lies below the grid minimum | `TE_GRID_EDGE` is raised |
| Split | Clean scenario, iron-based and other families fitted separately | `familyGroupResponseRmsDiff` ≤ 0.05; no `RESPONSE_FAMILY_INCONSISTENT` |

The reference implementation behind these thresholds is in [`analysis/`](./analysis/). There the Clean scenario gave 5–8% with five coefficients, the residual coming from a short-wavelength cut-on in the planted response that so stiff a spline cannot follow, and the Harsh scenario gave 5–10% with eight. The thresholds leave margin for a different random line list. The test must run in under 60 s on CPU.

### R13. Intermediate debug output and interactive visualization
1. By default on every calibration run, intermediate debug outputs must be dumped as structured JSONs partitioned by family under `temp/calibration_debug/<materialFamily>/`:
   - `conditioned_<material>.json`: lit channels mask, dark floor, conditioned mean spectrum, and shot variance `sigma_mean`.
   - `rois_<family>.json`: detected candidate lines, thin vs. prone windows, line attributions with atomic uncertainties, and channel-disjoint sub-bins.
   - `solver_trace_<family>.json`: step-by-step history of the alternating Block A/B loop (iteration index, objective Φ, B-spline coefficients β, plasma state weights, gauge offset).
   - `fit_comparison_<material>.json`: measured vs. modeled sub-bin counts, element parity medians, residuals, and per-point dispersion.
2. **Non-destructive family profile upsert:** Calibrations on individual materials or specific families must incrementally update that family's profile in `conf/instrument_profile.json` (and `data/regionsOfInterest.json`) without erasing or resetting calibrations from previously calibrated families.
3. **Interactive inspection notebook (`notebooks/calibration_inspector.ipynb`):** An interactive notebook using `ipywidgets` and `plotly` to inspect the dumped debug JSONs with:
   - Material and family dropdown selectors;
   - Interactive Plotly spectral plots with zoom/pan and hover tooltips showing species line assignments, ROI boundaries, and measured vs. synthetic overlays;
   - Convergence trace charts showing objective Φ and response spline β evolution across iterations;
   - Bar charts of element parity log-ratios (`log10(synthetic/measured)`).

---

## 4. Non-functional requirements
- New Python library modules depend only on `numpy`, `scipy`, `pandas`, `pyarrow`. The diagnostic notebook additionally uses `plotly` and `ipywidgets`.
- A full calibration of the five families completes in under 5 minutes on CPU, excluding NIST fetches.
- Every numeric default in §3 is a module-level constant overridable from the manifest.
- New code has unit tests written first, per `conductor/workflow.md`.

---

## 5. Schemas

### 5.1 `conf/instrument_profile.json` (changed and added fields)
```json
{
  "schemaVersion": "1.0.0",
  "emissionScale": "absolute",
  "wavelengths": [341.952, "..."],
  "spectralResponse": [1.0, "..."],
  "spectralResponseValidRangeNm": [352.4, 688.1],
  "validChannelRangeNm": [346.655, 896.916],
  "lineSpreadFwhmNm": [0.37, "..."],
  "lineSpreadAddSigmaNm": [0.146, "..."],
  "calibrationDiagnostics": {
    "flags": ["TE_GRID_EDGE:Low_Alloy_Steel:SS405"],
    "iterations": 17,
    "responseRestartSpread": 0.012,
    "familyGroupResponseRmsDiff": 0.08,
    "measuredSourceResolvingPower": 2900,
    "compositionScaleCheck": {"passed": true, "maxRelativeDifference": 0.012},
    "splineCoefficients": 5
  },
  "materialFamilyProfiles": {
    "Ferritic_Stainless_Steel": {
      "materialFamily": "Ferritic_Stainless_Steel",
      "resolution": 3000,
      "scaleFactor": 0.0412,
      "materialScaleFactors": {"469": 0.0419, "470": 0.0402},
      "plasmaZones": [
        {"zoneIndex": 0, "Te": 0.9, "Ne": 1.0e17, "volumeFraction": 0.31, "kAbsorption": 0.0, "teStdDev": 0.03, "neLogStdDev": 0.08},
        {"zoneIndex": 1, "Te": 0.6, "Ne": 5.0e16, "volumeFraction": 0.69, "kAbsorption": 1.4, "teStdDev": 0.03, "neLogStdDev": 0.08}
      ],
      "materialPlasmaZones": {"469": [{"Te": 0.9, "Ne": 1.0e17, "volumeFraction": 0.28}, {"Te": 0.6, "Ne": 5.0e16, "volumeFraction": 0.72}]},
      "calibrationStats": {
        "roiChi2Reduced": 1.8,
        "roiRSquared": 0.91, "roiRmse": 0.041,
        "wholeSpectrumRSquared": 0.63, "wholeSpectrumRmse": 0.058,
        "zoneReductionPenalty": 1.03,
        "familySharingPenalty": 1.12,
        "elementParity": {"Fe": -0.02, "Cr": 0.05, "Mn": 0.11, "Ni": -0.18},
        "numCalibratedMaterials": 4,
        "calibratedMaterials": ["469", "470", "471", "472"]
      },
      "validationHoldoutCsvs": {}
    }
  }
}
```
- `roiRSquared` and `roiRmse` are computed channel-wise over the union of ROI channels, with RMSE normalised by the largest measured value in that union.
- `elementParity` is the per-element median `log10(synthetic/measured)`.
- `lineSpreadFwhmNm` is informational; Java consumes `lineSpreadAddSigmaNm`.
- All numbers above are illustrative.

### 5.2 `data/regionsOfInterest.json`
```json
{
  "schemaVersion": "2.0",
  "instrumentName": "LSA_Calibrated_Instrument",
  "wavelengths": [341.952, "..."],
  "validChannelRangeNm": [346.655, 896.916],
  "materialFamilyRois": {
    "Ferritic_Stainless_Steel": {
      "calibratedMaterials": ["469", "470", "471", "472"],
      "unionChannelIndices": [212, 213, "..."],
      "coveragePercent": 21.4,
      "rois": [
        {
          "roiId": "ROI_017_Fe_495",
          "minNm": 494.6, "maxNm": 496.9,
          "channelIndices": [529, 530, "..."],
          "subBins": [[529, 530, 531, 532], [533, 534, 535, 536]],
          "selfAbsorptionProne": false,
          "usedInResponseFit": true,
          "dominantElement": "Fe",
          "modelLines": [{"species": "Fe I", "wlNm": 495.760, "EiEv": 2.81, "EkEv": 5.31, "grade": "B+"}],
          "perMaterial": {
            "469": {"measuredCounts": 21840.0, "syntheticCounts": 20511.0, "log10Ratio": -0.027, "rSquared": 0.94}
          }
        }
      ]
    }
  }
}
```

### 5.3 Manifest keys read by the engine
`validationMaterials`, `discardLeadingShots`, `lineSpreadFwhmNm`, `modelErrorFraction`, `responseSplineCoefficients`, `responsePenalty`, `subBinPixels`, `roiHalfWidthNm`, `linesPerBand`, `linesPerElement`, `proneEiEv`, `maskWindowsNm`. All are optional.

---

## 6. Module summary

| File | Status | Contents |
|---|---|---|
| `scripts/nist_lines.py` | changed | R1 |
| `scripts/spectral_resampling.py` | new | R2 |
| `scripts/measured_conditioning.py` | new | R3 |
| `scripts/forward_model.py` | new | R4 (Python side) |
| `scripts/roi_catalogue.py` | new | R5 |
| `scripts/roi_joint_calibration.py` | new | R6–R9 |
| `scripts/match_metrics.py` | new | R11.1 |
| `scripts/gpu_calibration_engine.py` | changed | R10 |
| `scripts/validate_spectral_match.py` | new | R11 |
| `notebooks/calibration_inspector.ipynb` | new | R13 |
| `scripts/test_*.py` | new / changed | unit tests, R12 |
| `src/.../util/SpectrumUtils.java` | changed | `resampleToDetector`, `combineNZones`, `applyInstrumentResponseAndNoise` |
| `src/.../service/LIBSDataService.java` | changed | use `resampleToDetector` |
| `src/.../model/InstrumentProfile.java` | changed | new optional fields (`emissionScale`, `lineSpreadAddSigmaNm`, `lineSpreadFwhmNm`, `spectralResponseValidRangeNm`, `validChannelRangeNm`) |
| `src/.../service/InstrumentProfileService.java` | changed | preserve `validationMaterials` |

---

## 7. Acceptance Criteria

1. **Branch.** `git branch --show-current` is `feat/instrument-profile-optimisation`.
2. **Line data.** No ranking or scoring expression in `scripts/*.py` uses `rel_intensity`. Every line in `regionsOfInterest.json` has a transition probability. With the real ASD cache and the SS551 composition, the bronze catalogue contains Cu I 515.324 and 521.820 nm and no Cu II line.
3. **Element neutrality.** The ROI rule and the objective contain no element names, category weights or per-element constants. Every certified element at or above 0.05 wt% that has a detectable model line appears in the catalogue.
4. **Resampling.** `resample_to_detector` conserves flux: the sum of `out_p × pixel width` equals the trapezoid integral of the source within 0.1%. A line's pixel sum changes by less than 2% as its centre moves across a pixel.
5. **Java parity.** For a shared fixture (`src/test/resources/forward_model_fixture.json`, written by the Python tests), Java `resampleToDetector` and the R4 operator agree with Python to a relative `1e-9`.
6. **Conditioning.** Unlit channels and conditioning shots are excluded; `sigma_mean` is consumed by the solver; no channel is silently zeroed.
7. **Planted truth.** All four scenarios of R12 pass.
8. **Diagnostics.** Each flag of R9 has a unit test that constructs its condition, appears in `calibrationDiagnostics.flags` when raised, and fails `--strict`.
9. **No bound pinning.** In the end-to-end run, zone temperatures are grid values, and no family has `kAbsorption` at the scan limit without `K_ABSORPTION_AT_BOUND`.
10. **Statistics parity.** For one calibrated material, the Java forward model with noise disabled (`finotelloBeta = 0`) and zones at their profile values reproduces the Python synthetic spectrum to a relative `1e-6`. `roiRSquared` and `roiRmse` recomputed from it over `unionChannelIndices` match the profile to 4 decimal places.
11. **Match report.** `data/spectralMatchReport.json` exists, covers every hold-out point and every validation material, and includes the three ablations. On hold-out data, ROI reduced χ² and `roiRSquared` are no worse than the legacy-rendering ablation for every family.
12. **Regression.** `pytest scripts/` and `mvn clean test` pass.
13. **Intermediate diagnostics & notebook.** On any calibration run, `temp/calibration_debug/<materialFamily>/` is populated with `conditioned_<material>.json`, `rois_<family>.json`, `solver_trace_<family>.json`, and `fit_comparison_<material>.json`. `notebooks/calibration_inspector.ipynb` runs cleanly and renders interactive Plotly figures for spectrum overlays, ROI line attributions, and solver traces. Calibrating one family preserves profiles of all other families.

---

## 8. Decisions taken by default

These defaults let implementation proceed. Each can be changed without redesign.

| Decision | Default | When to revisit |
|---|---|---|
| Validation materials | none. Set `validationMaterials` to `["473", "556"]` if those are free; the latest profile did not calibrate on them | Before the end-to-end run |
| Temperature grid | unchanged | If `TE_GRID_EDGE` is raised, extend the grid and re-fetch |
| Response prior | none | If `RESPONSE_FAMILY_INCONSISTENT` or `RESPONSE_RANGE_SUSPECT` is raised, or if a lamp or manufacturer response curve becomes available |
| Number of zones | 2 | If `ZONE_REDUCTION_PENALTY` is raised |
| Prone threshold `proneEiEv` | 2.0 eV | After the first end-to-end run, from the thin/prone split of the match report |
