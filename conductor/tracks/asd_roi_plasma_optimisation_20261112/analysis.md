# Calibration Analysis Report

**Subject:** response / plasma calibration in `scripts/gpu_calibration_engine.py` (v0.9.8), the inverse-problem
methodology, the pending ROI track, and how closely synthetic spectra can match the measured ones
**Date:** 2026-11-17
**Companion documents:** [`spec.md`](./spec.md), [`plan.md`](./plan.md); scripts in [`analysis/`](./analysis/)

---

## 1. Summary

Your diagnosis was that the instrument response is fitted before, and independently of, the plasma
optimisation. That is true, but it is not the main problem. The evidence points to five problems, in
this order of importance:

1. **Lines are ranked on two incompatible scales.** Lines without a transition probability are scored
   as `rel_intensity * 1e4`, which outranks every line scored by `g·A·exp(-Ek/T)`. With your ASD cache,
   all 85 of the response anchor lines chosen by the current engine have no transition probability, and
   NIST LIBS spectra do not contain such lines at all.
2. **The forward model renders lines too narrow and point-samples them.** The NIST spectra have line
   widths of 0.14–0.18 nm; your measured lines are 0.33–0.40 nm wide and only 1.2–1.4 pixels across.
   `np.interp` then samples each narrow line at pixel centres. On your SS469 spectrum, replacing this
   with measured-width, pixel-integrated lines raised whole-spectrum R² from 0.33 to 0.59 with nothing
   else changed.
3. **The response estimator cannot represent a response.** It has about two effective degrees of
   freedom out of eight. The response in your latest profile is 98.9% a straight line in log space,
   rising 2.3× from 342 to 897 nm. Nitrogen air lines in your own spectrum, which give a
   temperature-independent check, say the response *falls* by a factor of 2 to 5 between 745 and
   866 nm.
4. **The plasma search ends on its bounds.** In all five families the absorber sits at the maximum
   `kAbsorption`, the two zone temperatures sit exactly at the minimum allowed gap, and 8 of 10 zone
   densities are within 10% of a grid limit. The reported optimum is set by the bounds, not the data.
5. **The schedule is one pass with two different objectives.** This is the flaw you identified. In
   simulation, looping the current two steps to convergence changes nothing, because the stiff
   estimator reaches its biased answer in one pass.

On your three questions:

| Question | Answer |
|---|---|
| Does spline fitting work on ROIs only? | Yes where ROIs are dense, to a few percent in simulation. In gaps comparable to the knot spacing the response is extrapolated and can be wrong by tens of percent. |
| Would plasma optimisation take over? | Partly. A flat-response fit can look acceptable inside the ROIs while giving 2–15× larger errors on a composition it was not fitted to. On one material the two are not separable at all (section 8.3). |
| χ² or RMSE / R²? | χ², but with a model-error term and a robust loss. χ² built from shot variance alone was nearly as biased as an unweighted fit once model violations were present. |

How good the match can get, and how to judge it without fooling yourself, is in section 10. Sources of
bias in the synthetic dataset, and which of them this track removes, are in section 11.

---

## 2. Evidence base

| Source | What it supports | Limits |
|---|---|---|
| Code reading: `scripts/gpu_calibration_engine.py`, `scripts/nist_lines.py`, Java forward model, git history | Sections 4, 6 | None beyond the usual |
| Latest `instrument_profile.json` | Sections 4.2, 4.3 | One calibration run |
| One measured file: SS469, one point, 40 shots | Sections 3, 4.2, 8.3, 10 | One of 75 files; one material. Treat as indicative. |
| Your ASD cache (`nist_lines.zip`, 60 species) | Sections 4.1, 6, 10 | A-values only for the lines NIST lists |
| Five NIST LIBS spectra for SS469 (Te 1.7 and 2.0 eV) | Section 5 | High-temperature grid nodes only; none near the fitted 0.6–1.0 eV |
| Planted-truth simulation | Sections 7, 8, 9 | Synthetic line list, not NIST data; no continuum; plasma step is NNLS, not your Monte Carlo search |

Things I could not check are listed in section 13.

---

## 3. What the measured SS469 file shows

| Property | Finding |
|---|---|
| Grid | 2048 channels, 341.952–896.916 nm, pixel pitch 0.294 nm falling to 0.243 nm |
| Unlit channels | Channels 0–15 (341.95–346.36 nm) sit at about 633 counts; the signal jumps to about 7,290 counts at 346.66 nm |
| Saturation | Six readings at or above 0.9 of full scale, all in shot 1 (Ca II 393/397, Ca I 423, Mn 403, Fe 404 nm) |
| Line width | 0.34–0.40 nm at the 25th percentile across 347–550 nm (1.2–1.4 pixels), so λ/FWHM is about 1,000–1,300 |
| Continuum | 62–63% of all counts, depending on which shots are averaged. Net line signal is 28%, 37% or 43% of the total for structuring elements of 25, 65 or 129 channels |
| Shot-to-shot noise | One common scale factor carries 89% of the variance. Raw variance scales as mean^1.26; after scaling each shot to the mean it scales as mean^0.95 and 86% of the variance is gone |
| Early shots | Shot 1 has Na 589 nm at 5.9× and K 766/770 nm at 13–15× the later shots. The continuum-to-line ratio drifts from 2.2 to 1.6 over the first dozen shots. Pearson r reaches 0.9995 only from shot 13 |
| Detectable lines | About 275 peaks: 174 below 550 nm, 101 above. Above 700 nm the strongest are N I and O I air lines |
| Isolation | Of the 400 strongest Fe I and 400 strongest Cr I lines, five pass a 10% blend limit plus a clean-profile check |

Three consequences follow.

- **Pearson correlation hides what matters.** All shots correlate above 0.966, yet the first shot has
  several times more sodium and potassium. Correlation is dominated by the strong matrix lines.
- **Single-line ROIs do not exist in the steel spectrum at this resolution.** Every ±0.85 nm window is
  a blend. ROIs must be treated as groups and compared as integrated counts through the forward model.
- **Peak heights are unreliable.** At 1.2–1.4 pixels per line width, height depends on where the line
  falls within a pixel. Integrated counts do not.

---

## 4. The current implementation (v0.9.8)

### 4.1 Line ranking mixes two scales

`AsdLineAdvisor._load_scored_lines` (`gpu_calibration_engine.py:131-153`) scores a line as
`g_k·A_ki·exp(-E_k/0.95)` when it has a transition probability, and as `rel_intensity·1e4` when it does
not. The two are not comparable. With your cache, Cu I 521.82 nm scores 4.7e5, while Cu II 508.8 nm,
which has no A-value and an upper level 16.9 eV above the ion ground state, scores 2.9e8.

Running the engine's own anchor rule on the real cache:

| Composition | Anchor lines | Without A-value | Species chosen |
|---|---|---|---|
| Ferritic (SS469) | 85 | 85 (100%) | 44 Fe I, 40 C I, 1 Fe II |
| Bronze (SS551) | 85 | 85 (100%) | 41 Cu II, 27 Fe I, 15 Zn I, 2 Fe II |

NIST LIBS spectra contain only lines with transition probabilities (section 5), so every anchor is
centred on a line the model does not have. The ±1.5 nm windows still catch neighbouring modelled lines
by accident, which is why the estimator returns something, but it is not measuring what it was designed
to measure. The same score drives `build_family_tramp_weights`.

### 4.2 The response estimator

`solve_material_agnostic_spectral_response` (`:435-505`) uses 8 coefficients, `mu_r = 2500`, a ridge of
0.25 toward flat, log-ratios clipped to ±0.85 and an output clip of [0.25, 2.5].

- **Effective freedom.** The pooled weight per channel cannot exceed about 5 (one unit per family). At
  that scale the penalty dominates by about three orders of magnitude and the fit has 1.9–2.6 effective
  degrees of freedom. It would need pooled weights in the hundreds to exceed 5.
- **What it produced.** In the latest profile the response runs monotonically from 0.78 at 342 nm to
  1.76 at 897 nm, and a straight line in wavelength explains 98.9% of the variance of log R.
- **Independent check.** The N I multiplets at 742–747, 818–824 and 857–872 nm share one lower term and
  have upper levels within 0.25 eV, so their ratios barely depend on temperature and cannot be
  self-absorbed.

| Ratio | Implied by N I lines | Latest profile |
|---|---|---|
| R(821 nm) / R(745 nm) | 0.57 (line-by-line fit) to 0.74 (window sums) | 1.10 |
| R(866 nm) / R(745 nm) | 0.22 to 0.49 | 1.21 |

The two methods differ by up to a factor of two because they apportion blended Fe lines differently,
but both give a falling response where the profile gives a rising one. As an internal check, the three
lines sharing the ⁴S° upper level should have ratios 0.29 : 0.61 : 1 and measured 0.38 : 0.57 : 1. The
O I 845/777 ratio points the same way (0.26–0.31 against 1.11 in the profile), though I trust it less
because the two lines belong to different spin systems.

This is one file, and it tests only the near infrared. It does not tell us the response in the visible.

### 4.3 The plasma search

From the latest profile:

| Family | Te zone 0 / 1 (eV) | Gap | Ne zone 0 / 1 (cm⁻³) | k zone 1 | R² |
|---|---|---|---|---|---|
| Ferritic | 0.740 / 0.620 | 0.120 | 4.96e17 / 1.04e16 | 4.999 | 0.535 |
| Phosphor bronze | 0.753 / 0.633 | 0.120 | 4.88e17 / 4.54e17 | 4.981 | 0.223 |
| Low alloy | 1.008 / 0.888 | 0.120 | 4.73e17 / 1.10e16 | 5.000 | 0.517 |
| Austenitic | 0.757 / 0.637 | 0.120 | 4.99e17 / 1.02e16 | 4.957 | 0.555 |
| Aluminium | 0.626 / 0.506 | 0.120 | 1.08e16 / 4.12e17 | 4.999 | 0.634 |

Limits in the code are a minimum gap of 0.12 eV, `k` in [0.5, 5.0] and Ne in [1e16, 5e17]. Every family
is pinned on the gap and on `k`. My reading, which is an inference, is that the optimiser is using
maximum absorption to compress the dynamic range of synthetic lines that are too tall and narrow
(section 4.4), not to describe real self-absorption. Note also that `k` has a lower bound of 0.5, so
"no absorption" cannot be represented.

The 0.27 eV difference between low-alloy steel and the two stainless families is large for one
instrument and one laser setting. It rests on a single material, so I would not interpret it.

### 4.4 The forward model

| Step | Current behaviour | Evidence |
|---|---|---|
| Line width | NIST spectra used as delivered | NIST FWHM 0.14–0.18 nm against 0.33–0.40 nm measured |
| Resampling | `np.interp` at pixel centres (`:391`) | On the NIST files, peak heights change by a factor of 0.72–1.48 (10th–90th percentile) depending on sub-pixel position. Against a broadened, pixel-integrated rendering of the *same* spectrum, the point-sampled version reaches R² of only 0.74 |
| Zone scale | Each state divided by its own maximum (`:391-395`, `:413-414`) | NIST intensities share one absolute scale across states (section 5). Normalising discards it and makes zone weights depend on which line happens to be strongest |
| Off-grid states | Bilinear interpolation between grid spectra (`:399-414`) | Java fetches the exact off-grid Te from NIST (`LIBSDataService.java:347`), so calibration and generation use different spectra |
| Label | Profile records `resolution: 5000` | The five NIST files measure λ/FWHM of 2,700–3,000. I cannot tell whether those files come from the same run |

On the measured SS469 spectrum, holding the model freedom fixed:

| Line rendering | Whole spectrum R² | 495–565 nm R² |
|---|---|---|
| λ/3000, point-sampled, flat response | 0.33 | 0.15 |
| λ/3000, point-sampled, profile response | 0.35 | 0.24 |
| 0.37 nm, pixel-integrated, flat response | 0.59 | 0.42 |
| 0.37 nm, pixel-integrated, profile response | 0.62 | 0.51 |

The model here gives each species its own free temperature weights, which is more flexible than the
NIST dictionary, so the absolute values are optimistic. The comparison between rows is the point: line
rendering moves R² by about 0.26, the fitted response by about 0.03.

### 4.5 The objective

- The cost `0.5·w_mse + 0.5·cos_loss` (`:595`) is one criterion written twice. With the least-squares
  scale applied, the weighted MSE equals a constant times (1 − cos²) of the same weighted cosine. I
  checked this numerically.
- R² and RMSE are likewise one criterion, since R² = 1 − N·RMSE²/SS_tot for fixed data.
- `sigma_mean` is computed (`:366`) and discarded (`:648`). The fit is unweighted, so a line's influence
  grows as the square of its intensity.
- Reported R² uses a per-material scale, while the exported `scaleFactor` is the family mean
  (`:583-599`). For multi-material families the viewer may not reproduce the reported statistics.

### 4.6 Schedule and history

`run()` performs plasma (flat response), then response, then plasma, once, with no convergence test.

The history matters here. Commit `096ab85` already contained the methodology's loop: NNLS, pooled
log-ratio spline with 14 knots, 15 iterations, and a comment about clipping "to prevent runaway".
v0.9.8 replaced it with the stiff estimator. So the textbook loop has been tried and failed on real
data. Section 7.3 reproduces that failure and identifies its causes; the new spec is built to avoid
them, not to repeat the earlier attempt.

### 4.7 Data handling

- Unlit channels are included in the fit.
- Conditioning shots are averaged in with the rest.
- A channel in which every shot saturates is set to zero rather than masked (`nv = max(count, 1)`).
  This did not occur in the sample file.

---

## 5. What the NIST synthetic spectra show

These five files resolved three points I had flagged as unverified.

| Property | Finding |
|---|---|
| Grid | About 32,110 rows, 339.82–898.86 nm, median step 0.020 nm, with gaps up to 1.76 nm where there is no emission |
| Columns | `Sum(calc)` plus 22 species columns. Column headers carry the species fractions, for example Fe I 2.8e-4, Fe II 0.19, Fe III 0.67 at 1.7 eV and 1e17 cm⁻³ |
| Sum | Equals the sum of the species columns to within 0.05% |
| Line width | FWHM 0.140, 0.147, 0.183 nm in three bands, so λ/FWHM is 2,710–2,980. Independent of electron density |
| Intensity scale | Shared across states. For Fe I 373.71 nm the ratio of peak to species fraction is 1.38e10 at all three densities at 1.7 eV, and 1.28–1.30e10 at 2.0 eV |
| Line inventory | Only lines with transition probabilities. I took the strongest tenth of ASD lines without an A-value that stand at least 0.15 nm clear of any modelled line: NIST shows a peak at none of 56 for Fe I, none of 174 for Cr I and none of 38 for Fe II |
| Wavelengths | Air, agreeing with the ASD cache to about 0.005 nm |
| Reproducibility | ASD lines with A-values, a Boltzmann factor and a λ/3000 Gaussian reproduce the species columns with R² of 0.994 (Fe I), 0.9985 (Cr I) and 0.969 (Fe II), using intensity ∝ g·A·exp(−E_k/kT)/λ |

Two opportunities follow, both outside this track.

- Species spectra scale linearly with elemental fraction at fixed state, so a new composition could be
  assembled from per-element columns without a new NIST request.
- Within-species spectra can be computed locally from the ASD cache at any temperature, which would
  allow a finer temperature grid.

---

## 6. The pending ROI track as written

I ported the spec's `discover_rois_for_family` and ran it on the real ASD cache and detector grid.

| | Ferritic (SS469) | Bronze (SS551) |
|---|---|---|
| Lines selected | 66 | 78 |
| Without A-value, so absent from NIST spectra | 52 (79%) | 66 (85%) |
| Matrix-element lines without A-value | 14 of 14 Fe | 14 of 14 Cu |
| On unlit channels | Cr II 342.27, Cr II 343.33, Ni I 345.85 | Ni I 344.63, Ni I 345.85 |

For bronze, all 14 copper lines chosen are Cu II with upper levels 10.6–18 eV above the ion ground
state. None of Cu I 510.55, 515.32 or 521.82 nm is selected; Cu I 521.82 ranks 741st of 1,367 copper
lines. The selection therefore misses the lines that carry the copper signal in the synthetic spectra.

Other issues with the track as written:

- It keeps the response estimator of section 4.2 unchanged and adds a plasma fit on a different channel
  set with a different objective, which widens the inconsistency you set out to remove.
- It optimises R² and RMSE.
- It normalises by the maximum inside the ROI union and uses fixed category weights of 1.0, 1.5 and
  2.0, neither of which has a statistical basis.

What is worth keeping: centralising the parser in `nist_lines.py`, the channel-disjoint mapping, and
the `regionsOfInterest.json` export.

---

## 7. Simulation evidence

All numbers in this section come from a planted-truth simulation, not from your data. Errors are the
RMS error of the recovered response, and the 90th-percentile error of nickel line ratios for a
composition that was not used in calibration.

### 7.1 One pass, looping, and weighting

Response range 20×, with planted self-absorption and 5% bad A-values:

| Variant | Response error, 350–550 nm | 550–890 nm | Held-out error |
|---|---|---|---|
| Flat response | 25% | ×4.2 | 60% |
| Current estimator, one pass | 17% | ×6.0 | ×2.2 |
| Current estimator, looped 30 times | 17% | ×6.0 | ×2.2 |
| Methodology blocks, one pass | 4% | 61% | 19% |
| Converged, unweighted | 34% | ×2.8 | 54% |
| Converged, χ² with measurement σ only | 23% | 66% | 40% |
| Converged, χ² with model-error term | 5% | 13% | 15% |
| Same, robust loss, resonance lines masked | 2% | 7% | 5% |

- The two converged fits with the highest in-fit R² (0.91 unweighted, 0.92 measurement-only χ²) had
  the worst recovery. The fit with the model-error term and robust loss had an in-fit R² of 0.73 and
  recovered the response five to ten times better. In-fit R² is not a reliable guide.
- Convergence typically took 10–45 iterations, about 6 to reach 1%. One unweighted case needed 111.
- A 2.8× response gave the same ordering.

### 7.2 ROI-only fitting

| Variant | Response error, 350–550 nm | 550–890 nm | Held-out error |
|---|---|---|---|
| All channels except resonance windows | 2% | 7% | 5% |
| ROI-only, about 43 lines per material, resonance-cleaned | 2% | 22% | 7% |
| ROI-only, selection ranked by exp(−E_k/T) | 3% | 21% | 28% |
| ROI-only, flat response | 25% | ×4.2 | about 85% |

In a 94 nm coverage gap the response error was 60–80% for any knot count of 8 or more.

### 7.3 Why the earlier loop ran away

I added the three real-data conditions my first simulation lacked: synthetic lines much narrower than
measured, point sampling, and 30% of lines missing from the model plus unmodelled air lines.

| Variant | Response span (truth 22×) | Response error, 350–550 nm | Outcome |
|---|---|---|---|
| `096ab85` style: pointwise ratios, 14 knots, μ = 100, measurement σ; narrow point-sampled lines | 2,518× | ×2.07 | Not converged after 60 iterations |
| Same, flux-conserving lines | 40× | ×1.52 | Converged |
| Pointwise, 8 coefficients, scaled penalty, model-error term | 26–44× | ×1.16–1.29 | Converged in 7–9 iterations |
| ROI-integrated counts, 8 coefficients, model-error term, flux-conserving lines | 40–50× | ×1.05–1.10 | Converged in 13–22 iterations |

The causes are point sampling of narrow lines, pointwise log-ratios, a penalty that is negligible
against signal-to-noise-squared weights, and no allowance for model error. Under these harsher
conditions held-out line-ratio errors were ×1.4–2.4 at the 90th percentile for every converged variant
(×3.1 for the runaway). That is
a realistic expectation for individual trace-element lines when a third of the lines are missing from
the model.

---

## 8. ROI-only optimisation

### 8.1 What it looks like

The same joint loop, restricted to ROI rows. Each ROI is compared as integrated counts, measured
against modelled, in sub-bins a few pixels wide. The response spline spans only the range the ROIs
cover and is constrained only where they exist.

### 8.2 Does the spline work?

Yes where ROIs are dense. In gaps it extrapolates. For steel that means the response above about
700 nm is unconstrained by sample lines: what is there is mostly air.

### 8.3 Does plasma take over?

Partly, and on a single material completely. For lines with similar lower levels,
`E_k ≈ E_i + hc/λ`, so a change of temperature has the same shape as a smooth response tilt.

I ran the joint fit on the real SS469 spectrum with each species free to choose its own temperature
weights. The result was degenerate: Fe I collapsed onto the lowest grid temperature (0.5 eV), Fe II
onto the highest (1.5 eV), and the response spanned more than 400×. That answer was almost identical
for 6, 8 and 10 coefficients, for both halves of the shot series, and for a different baseline. So it
is reproducible and still not credible. **Stability under resampling is not identifiability.**

This is a worst case, because the NIST dictionary ties species together through composition and the
Saha balance, which my free-species model does not. It still shows that one material cannot separate
the two, and that the fit needs several materials sharing one response, plus explicit checks.

### 8.4 Thin lines and strong lines need different treatment

In simulation, a calibration fitted only to optically thin ROIs recovered the response well but
over-predicted the strongest iron lines by about 0.23 dex. Those lines were self-absorbed in the data
and the model is optically thin. A flat-response fit to everything matched those lines better
in-sample, but only by biasing the temperature.

So the best physical parameters and the best match on strong lines are different targets if both are
asked of one optically thin model. The spec separates them: the response and plasma state are fitted
on thin ROIs, then absorption is fitted on the strong-line ROIs of every matrix element with the
response locked.

### 8.5 Design consequences

- Compare integrated counts through the forward model, not peaks or pointwise ratios.
- Choose calibration ROIs from lines that exist in the model, are detected, are not resonance lines of
  matrix elements, and are spread in upper-level energy within each wavelength band.
- Keep the response low-order and confined to the ROI span.
- Fail loudly when a solution lands on a grid or parameter bound.
- Check the response against itself: fit iron-based and other families separately and compare. In
  simulation the two agreed to about 1% when the problem was well posed.

One safeguard I tried did not work. Adding a fixed tilt to the converged response and re-solving the
plasma raised the objective by only 1–8%, both for the well-posed seven-material case and for a
single material, so it does not discriminate. The spec therefore relies on the grid-edge check
(a true temperature below the grid put 100% of the emission on the edge state in simulation), the
response-range check, and the cross-family comparison.

---

## 9. χ² against RMSE and R²

χ² is the right family. It is the likelihood under Gaussian noise and has an absolute scale. Three
things decide whether it helps.

1. **Which σ.** Raw shot variance is mostly the common scale fluctuation, so σ is nearly proportional
   to intensity and χ² becomes a relative-error fit. Scale each shot to the mean spectrum first, then
   use the variance of the mean.
2. **Model error exceeds noise.** In simulation, 15% atomic-data error alone gave χ²/N of 130–220 with
   measurement σ, and 1,000–2,300 with planted violations. Measurement-only χ² then over-trusts the
   brightest lines.
3. **Add a model-error term.** Use `σ² = σ_meas² + (ε·y)²`, with ε from the atomic-data grade or a
   global 10–20%, and a Huber loss.

With that term, a line's influence is roughly equal for all well-detected lines, rather than scaling
as intensity² (RMSE) or intensity (shot-noise χ²). Keep R² and RMSE as descriptive outputs for the
viewer.

---

## 10. How good can the match get, and how to judge it

### 10.1 What the evidence says is achievable

| Change | Evidence | Size of the effect |
|---|---|---|
| Measured-width, pixel-integrated lines | Real SS469 spectrum (4.4) | Whole-spectrum R² 0.33 to 0.59; 495–565 nm block 0.15 to 0.42 |
| A response that can bend, fitted jointly | Simulation (7.1, 7.3) | Response error from 17% to 5–10% where ROIs are dense |
| Weights that allow for model error | Simulation (7.1) | Response error from 23–34% to 5% when the model has violations |
| Integrated sub-bin counts with flux-conserving lines | Simulation (7.3) | Response error ×1.05–1.10, against ×1.16–1.52 for pointwise ratios. The original pointwise form with point-sampled lines did not converge |
| Absorption fitted only on strong lines, lower bound 0 | Latest profile (4.3) | Removes the pinning at `k = 5`; size on real data unknown |

Only the first row is measured on your data. The others are simulation results and should be read as
direction and rough size.

### 10.2 What will still not match

- **Lines the model does not have.** NIST spectra contain only lines with transition probabilities.
  That is 18 of 292 listed Cu I lines, 1 of 49 Zn I, 9 of 42 Sn I, 12 of 45 Pb I, 419 of 2,676 Cr I
  and 1,718 of 3,434 Fe I in your cache.
- **Air and surface lines.** Above 700 nm roughly 70% of the peak counts in SS469 sit at N, O, H, Na,
  K or Ca wavelengths. (The 0.3 nm matching tolerance makes this approximate.)
- **Individual weak lines.** With a third of the lines missing and realistic atomic-data errors, the
  simulation gave errors of ×1.4–2.4 at the 90th percentile for single trace-element line ratios.
- **The continuum.** It is 62% of measured counts and absent from the model. Model lines themselves
  account for only 1–8% under the baseline operator, so the rest is real continuum and unmodelled
  lines.

### 10.3 How to judge the match

1. **Use held-out data.** The latest profile calibrated on 469–472 and 551–555, so 473 and 556 appear
   to be free as test materials, and each calibrated material has a held-out point. In-sample numbers
   always look better.
2. **Report reduced χ² with the model-error term, and the distribution of sub-bin ratios.** The median
   and the 10th–90th percentile of `log10(synthetic/measured)` say more than R². In simulation the
   fit with the highest in-fit R² had the worst recovery (7.1).
3. **Report the same numbers per element.** A fit can look good overall while one element is
   systematically off.
4. **Run ablations.** The same metrics with a flat response, with absorption off, and with the old
   line rendering show what each part contributes.
5. **Apply the same baseline operator to both spectra** before computing any metric.

---

## 11. Bias audit of the synthetic dataset

"Bias" here means a systematic difference between synthetic and measured spectra that a downstream
user of the dataset could pick up.

| Source | Evidence | Status in this track |
|---|---|---|
| Line selection favouring particular lines or elements | The current score ranks lines without A-values first (4.1); the pending spec used category weights of 1.0, 1.5, 2.0 (6) | Removed. One selection rule for every element, no weights, and no input describing a downstream use |
| A calibration dominated by one group | 8 of the 15 reference materials are iron-based and 6 are bronzes | Addressed. Response weights are divided by the number of materials in the family, and iron-based and other families are fitted separately as a check |
| Per-zone max-normalisation | NIST intensities share one scale across states (5) | Removed |
| Per-spectrum max-normalisation in the generator | Every synthetic spectrum's strongest line equals `scaleFactor` whatever the composition | Removed if the NIST scale is confirmed to hold across compositions; otherwise kept and flagged. I could not check this with one composition |
| Synthetic lines narrower than measured, point-sampled | 4.4 | Removed |
| Baseline subtracted from measured spectra only | The operator removes 1–8% of a line-only synthetic spectrum and 62% of the measured one | Addressed for all reported metrics. The fit itself accepts the 1–8% asymmetry |
| Absorption forced on (`k ≥ 0.5`) and pinned at its limit | 4.3 | Removed |
| Element-specific residual error | Not yet measured | Measured and reported per element; flagged above 0.30 dex |
| Lines without A-values | 10.2 | Not fixable here. It is element-dependent: for zinc the 468.0, 472.2 and 481.1 nm lines have no A-value in the cache and are absent from the synthetic spectra |
| Air, hydrogen and surface-contamination lines | 3, 10.2 | Not modelled. These regions are masked from the fit |
| Response outside the calibrated span | Nitrogen lines indicate a 2–5× fall between 745 and 866 nm (4.2) | Held constant and marked as outside the valid range. Synthetic intensities there are unvalidated |
| Noise model | Generator applies independent per-channel noise with a hard-coded 0.095; measured shot noise is 89% one common scale (3) | Out of scope |
| Conditioning shots discarded | First shots carry surface sodium, potassium and calcium (3) | By design. Synthetic spectra represent a conditioned surface |
| One plasma state and one `k` per family | Self-absorption depends on concentration | Partly. Per-material solutions and the cost of sharing are reported |

Three of these deserve a note. The missing lines and the air lines are properties of the NIST-based
model, not of the calibration, so no calibration can remove them; they should be stated wherever the
dataset is described. The response outside the span could be extended into the near infrared using
the nitrogen groups as temperature-insensitive anchors, which I have left as a follow-up.

---

## 12. The methodology document

The formulation is sound and I recommend building on it. Six points to add or qualify:

1. The monotone-descent guarantee holds for the stated objective. It does not make the problem well
   conditioned: the single-material fit in 8.3 converged to a degenerate answer.
2. "Convergence typically requires few iterations" was 10–45 in my simulation.
3. The pointwise log-ratio of Eq. 19 fails when model lines are narrower than measured ones. The
   document's own recommendation to use integrated line areas should be the default, not an option.
4. The replicate variance of Eq. 24 should be computed after removing the common shot scale.
   Otherwise Σ is far from diagonal.
5. Rejecting saturated shots biases the mean low, because the rejected shots are the bright ones.
   Normalising shots before averaging removes most of this.
6. If a calibrated lamp spectrum through the same optics, or the manufacturer's relative response, can
   be obtained, it removes the response from the unknowns and with it the degeneracy. That is worth
   asking your partner for.

---

## 13. Not verified

- The response in the visible. The nitrogen check covers 745–866 nm only.
- Anything about bronze, aluminium, low-alloy or austenitic spectra. I saw one ferritic point.
- NIST spectra at the fitted temperatures. The five files are at 1.7 and 2.0 eV.
- Whether the NIST files and the latest profile come from the same run; the widths say λ/3000 and the
  profile says 5000.
- The percentages attached to NIST accuracy grades, which I used from memory. Check the ASD
  documentation before using them as ε.
- Line identifications above 550 nm other than the N I and O I groups, and the 70% air-line share
  quoted in 10.2, which rests on a 0.3 nm wavelength match.
- Whether NIST intensities keep one scale across compositions. I verified it across states for one
  composition only. The spec makes this a gate before the generator's normalisation is changed.
