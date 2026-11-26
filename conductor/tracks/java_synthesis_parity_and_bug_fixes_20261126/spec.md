# Specification: `TRACK-CORE-02B` — Fix Java Synthesis Parity, Deterministic Seeding, DTO Bindings & Composition Bugs in `LIBSDataCurator`

- **Track ID:** `java_synthesis_parity_and_bug_fixes_20261126`
- **Track Type:** `Bug`
- **Target Repository:** `LIBSDataCurator` (Java source & JUnit tests ONLY)
- **Linked Findings:** `SH-02`, `SH-03`, `SH-06`, plus adversarial audit items `H3`, `H5`, `M1`, `M4`
- **Executive Scope Decision:** Empirical `finotelloNoiseBeta` calibration in Python is explicitly **DEFERRED** to a future release (leave `finotelloNoiseBeta = 0.0` by default and do NOT modify Python calibration scripts).

---

## 1. Overview

Following completion of the canonical metric contract (`TRACK-CORE-01`) and Python multi-family calibration blocker fixes (`TRACK-CORE-02A` / `TRACK-CORE-02A-FIX`), seven Java-side parity, reproducibility, serialization, default-configuration, and compositional-sampling defects remain in `LIBSDataCurator`:

1. Non-flux-conserving linear interpolation fallback when `lineSpreadAddSigmaNm` is `null` or empty (`LIBSDataService.java:366-374`, `H5`).
2. Unseeded `new Random()` in Finotello noise injection breaking `--seed` reproducibility (`LIBSDataService.java:387`).
3. Incomplete `CalibrationStats.java` DTO silently dropping all `TRACK-CORE-02A` / `02A-FIX` telemetry fields on Jackson and `org.json` round-trips (`M1`).
4. Stale morphological opening default structuring element size `65` instead of `25` in both `InstrumentProfileService.java:69` and `BaselineCorrectionParams.java:11` (`H3`).
5. Algebraic $\mu_i(1 - \mu_i)$ cancellation in `ConcentrationParameterEstimator.java:122-126, 145` collapsing total Dirichlet concentration $\alpha_0$ to $\sqrt{\text{gradeCount}} - 1$.
6. In-place mutation of caller `Element` lists and shared `coatingElement` across loop iterations, plus a latent NPE check on `maxPercentElement` instead of `coatedElement` in `InputCompositionProcessor.java:487-535`.
7. Isolated helper-only forward parity test in `SpectrumUtilsTest.java:144-202` that does not exercise the end-to-end Java synthesis chain (`M4`).

This track resolves all seven Java defects with strict TDD unit and integration test coverage while keeping Python scripts, `TRACK-CORE-01` contract files, and on-disk synthetic `.csv` outputs completely untouched.

---

## 2. Functional Requirements

### FR-1 (`H5`): Unconditional Flux-Conserving Resampling & Extracted Synthesis Helper (`LIBSDataService.java`)
- Extract a package-private (testable without Selenium) end-to-end synthesis helper in `LIBSDataService`:
  ```java
  double[] synthesizeSpectrumFromZoneCsvs(
          List<String> zoneCsvs,
          List<PlasmaZone> plasmaZones,
          InstrumentProfile instrumentProfile,
          double scaleFactor,
          String wavelengthUnit,
          Random noiseRng) throws IOException
  ```
  and delegate `fetchAndProcessSpectra(...)` to it after fetching the zone CSV strings.
- Inside the synthesis helper, for every zone CSV:
  1. Parse with `NISTUtils.parseNistCsv(csvData, wavelengthUnit)` and construct `Spectrum parsedSpectrum = new Spectrum(waveMap)`.
  2. If `instrumentProfile.getWavelengthGrid() == null`, initialize `instrumentProfile.setWavelengthGrid(parsedSpectrum.getWavelengths())` (preserving existing single-initialization fallback).
  3. **Always** resample via `SpectrumUtils.resampleToDetector(srcWl, srcI, detWl, addSigma)` — remove the non-flux-conserving `spectrumUtils.interpolateSpectrum(...)` branch (`LIBSDataService.java:366-374`).
  4. When `instrumentProfile.getLineSpreadAddSigmaNm()` is `null` or empty, pass a zero-filled `new double[detWl.length]` array (`0.0` nm additional broadening = pure flux-conserving boxcar integration matching Python `resample_to_detector(..., add_sigma_nm=0.0)` 1:1).
  5. Combine resampled zone spectra with `SpectrumUtils.combineNZones(fetchedZoneSpectra, plasmaZones)`.
  6. Apply spectral response, scale factor, emission scale (`"absolute"` default when null), and Finotello noise via `SpectrumUtils.applyInstrumentResponseAndNoise(combinedSpectrum, spectralResponse, scaleFactor, finotelloBeta, noiseRng, emissionScale)`.
- Keep `SpectrumUtils.interpolateSpectrum(...)` intact for backward compatibility with any direct callers, but remove the unused local `SpectrumUtils` instance in `LIBSDataService.fetchAndProcessSpectra`.

### FR-2: Deterministic `--seed` Propagation with Per-Sample SplitMix64 Derivation (`LIBSDataService.java`)
- Add a package-private deterministic RNG helper in `LIBSDataService`:
  ```java
  static Random deriveSampleNoiseRandom(Long baseSeed, int sampleIndex)
  ```
  - When `baseSeed == null`: return `new Random()` (non-deterministic default when `--seed` is omitted).
  - When `baseSeed != null`: derive an independent, deterministic 64-bit seed using SplitMix64 mixing of `(baseSeed, sampleIndex)`:
    ```java
    long z = baseSeed + 0x9E3779B97F4A7C15L * (sampleIndex + 1L);
    z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
    z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
    z = z ^ (z >>> 31);
    return new Random(z);
    ```
- In `fetchAndProcessSpectra(...)`, track a deterministic `sampleIndex` incremented per `(composition, plasmaZones)` synthesis attempt (regardless of whether an earlier NIST fetch failed/broke) and pass `deriveSampleNoiseRandom(config != null ? config.seed : null, sampleIndex)` instead of `new Random()` at line 387.
- Keep `InstrumentProfile.finotelloNoiseBeta` defaulting to `0.0` (empirical noise calibration in Python remains deferred).

### FR-3 (`M1`): Complete `CalibrationStats.java` Jackson & `org.json` Bindings
- Extend `com.medals.libsdatagenerator.model.CalibrationStats` to bind all 11 `TRACK-CORE-02A` / `02A-FIX` fields emitted by `scripts/gpu_calibration_engine.py:compute_family_calibration_stats`, while preserving full backward compatibility with legacy `rSquared`, `rmse`, `weightedCorrelationCost`, `numCalibratedMaterials`, and `calibratedMaterials`:
  - `roiChi2Reduced` (`double`)
  - `roiRSquared` (`double`)
  - `roiRmse` (`double`)
  - `roiRmseRaw` (`double`)
  - `wholeSpectrumRSquared` (`double`)
  - `wholeSpectrumRmse` (`double`)
  - `wholeSpectrumRmseRaw` (`double`)
  - `zoneReductionPenalty` (`double`)
  - `familySharingPenalty` (`double`)
  - `elementParity` (`Map<String, Double>`, initialized to `new LinkedHashMap<>()`)
  - `perMaterialStats` (`Map<String, Map<String, Object>>`, initialized to `new LinkedHashMap<>()`)
- Annotate fields (and getters/setters where JavaBean naming could otherwise lowercase multi-cap prefixes such as `rSquared` / `roiRSquared`) with exact `@JsonProperty("<key>")` names so Jackson `ObjectMapper` never emits duplicate keys and round-trips all fields losslessly.
- Update `CalibrationStats.toJson()` and `CalibrationStats.fromJson(JSONObject)` so all 16 fields (11 new + 5 existing) serialize and deserialize cleanly via `org.json`, safely handling absent fields in legacy JSON (defaulting numeric values to `0.0` and maps to empty `LinkedHashMap<>`) and preserving numeric/integer/string types inside nested `perMaterialStats` maps.

### FR-4 (`H3`): Align Morphological Opening Default Structuring Element Size to `25`
- Update `InstrumentProfileService.DEFAULT_MORPHOLOGICAL_STRUCT_SIZE` (`InstrumentProfileService.java:69`) from `65` to `25`.
- Update `BaselineCorrectionParams.DEFAULT_STRUCTURING_ELEMENT_SIZE` (`BaselineCorrectionParams.java:11`) from `65` to `25`.
- Update `BaselineCorrectionParamsTest.java` assertions (`testDefaultValues` and `testLegacyAlsJsonDeserialization`) to expect `25`, and add/verify manifest-initialization assertions in `InstrumentProfileServiceRefactoringTest.java` confirming fresh manifests emit `structuringElementSize = 25` matching `src/main/resources/metric_contract_v1.json`.

### FR-5 (`SH-03` / Dirichlet): Fix Algebraic Cancellation & Sentinel Exclusion in `ConcentrationParameterEstimator.java`
- Replace the cancelling `p * (1 - p) / sqrt(gradeCount)` variance proxy in `ConcentrationParameterEstimator.estimateVariance(ElementStatistics stats)` with a range-based Wan et al. (2014) empirical-spread estimator that uses `minPercentage`, `maxPercentage`, and `gradeCount`:
  1. Detect explicit range availability: `boolean hasExplicitRange = !(stats.getMinPercentage() == 0.0 && stats.getMaxPercentage() == 100.0);`
  2. When `hasExplicitRange` is `true`:
     - Compute proportion range $R = \max(0.0, (\text{maxPercentage} - \text{minPercentage}) / 100.0)$.
     - If $R \le 0.0$ (fixed composition, `min == max`), return `MIN_VARIANCE_THRESHOLD`.
     - Compute the range-to-SD divisor $d_n$:
       - If `gradeCount >= 2`: compute $p_n = (\text{gradeCount} - 0.375) / (\text{gradeCount} + 0.25)$ and $d_n = \text{clamp}(2.0 \cdot \Phi^{-1}(p_n),\, 2.0,\, 6.0)$ using Apache Commons Math `NormalDistribution.inverseCumulativeProbability(p_n)`.
       - If `gradeCount < 2`: use classic range-rule fallback $d_n = 4.0$.
     - Estimate $\sigma = R / d_n$ and return $\max(\sigma^2, \text{MIN\_VARIANCE\_THRESHOLD})$.
  3. When `hasExplicitRange` is `false` (sentinel `[0.0, 100.0]` when MatWeb range text is absent):
     - Use a non-cancelling relative-spread fallback $\sigma = 0.15 \cdot p$ (where $p = \text{averagePercentage} / 100.0$) and return $\max(\sigma^2, \text{MIN\_VARIANCE\_THRESHOLD})$, which does **not** cancel $\mu_i(1 - \mu_i)$ and keeps $\alpha_0$ well inside `[MIN_TOTAL_CONCENTRATION, MAX_TOTAL_CONCENTRATION]`.
- Update `estimateTotalConcentration(double[] means, double[] variances, boolean[] hasExplicitRange)` to use the exact pooled Dirichlet method-of-moments identity:
  $$\alpha_0 = \frac{\sum_{i \in \mathcal{S}} \mu_i (1 - \mu_i)}{\sum_{i \in \mathcal{S}} \text{Var}(X_i)} - 1$$
  - Whenever **at least one** target element has `hasExplicitRange[i] == true` (with $0 < \mu_i < 1$ and $\text{Var}(X_i) > 0$), restrict the pooled index set $\mathcal{S}$ **strictly to those explicit-range elements**—excluding sentinel `[0.0, 100.0]` balance/remainder elements (e.g., `Fe: 80%`) so their `0.15 * p` fallback variance cannot swamp tight alloying-element variances by orders of magnitude.
  - Only include sentinel-fallback elements in $\mathcal{S}$ when **zero** target elements have explicit ranges.
  - Clamp $\alpha_0$ to `[MIN_TOTAL_CONCENTRATION, MAX_TOTAL_CONCENTRATION]` (`[1.0, 1000.0]`), falling back to `10.0` only if the numerator or denominator is non-positive.
- Guard `validateAndAdjustParameters(double[] alphas, String[] elementOrder)` so that if flooring tiny trace elements up to `MIN_ALPHA` (`0.1`) pushes `sum(alphas)` above `MAX_TOTAL_CONCENTRATION`, the non-floored entries (`> MIN_ALPHA`) are proportionally scaled down to keep `sum(alphas) <= MAX_TOTAL_CONCENTRATION` (preventing spurious `validateParameters(alphas) == false` rejections on tight alloys with trace elements).

### FR-6: Deep-Copy `Element` Objects & Fix Latent NPE in `InputCompositionProcessor.applyCoating`
- In `InputCompositionProcessor.java:468-569`, add a private helper `copyElement(Element src)` creating a new `Element(src.getName(), src.getSymbol(), src.getPercentageComposition(), src.getMin(), src.getMax(), src.getAverageComposition())`.
- Guard empty `baseComposition` lists gracefully (adding an empty copy without throwing `NoSuchElementException`).
- In **`scaleCoating == false`**:
  - Populate `coatedComposition` with deep copies (`copyElement(e)`) of every element in `baseComposition` (never `coatedComposition.addAll(baseComposition)`).
  - Fix the latent NPE at line 513: replace `if (maxPercentElement.getMax() != null && maxPercentElement.getMin() != null)` with `if (coatedElement.getMax() != null && coatedElement.getMin() != null)` before reading/updating `coatedElement.getMax()` and `coatedElement.getMin()`.
  - When `indexOfCoatingElement < 0`, add `copyElement(coatingElement)` rather than sharing the caller's `coatingElement` instance.
- In **`scaleCoating == true`**:
  - When `element.getSymbol().equals(coatingElement.getSymbol())`, construct a **fresh `Element`** with `newPercentage = (element.getPercentageComposition() * scaleFactor) + coatingPercentage` (preserving `element`'s name, symbol, min, max, and averageComposition) instead of mutating `coatingElement.setPercentageComposition(newPercentage)` and reusing the same object across compositions.
  - When `!coatingElementExists`, add `copyElement(coatingElement)` rather than the caller's `coatingElement` instance.
- Guarantee across **both** branches that neither `baseCompositions` (nor any `Element` inside it) nor `coatingElement` is ever mutated, and no two returned compositions share mutable `Element` object references.

### FR-7 (`M4`): End-to-End Forward Parity & Determinism JUnit Tests
- Add JUnit tests in `LIBSDataServiceTest.java` exercising the full Java synthesis chain via `LIBSDataService.synthesizeSpectrumFromZoneCsvs(...)` (`NISTUtils.parseNistCsv` -> `SpectrumUtils.resampleToDetector` -> `SpectrumUtils.combineNZones` -> `SpectrumUtils.applyInstrumentResponseAndNoise`):
  1. **Fixture End-to-End Parity (`<= 1e-9`):** Build two NIST CSV strings (`Wavelength (nm),Sum(calc)\n...`) from `src/test/resources/forward_model_fixture.json` (`1.5 * srcI` for zone 0, `0.8 * srcI` for zone 1, matching Python's `test_forward_model.py:111-125`), run `synthesizeSpectrumFromZoneCsvs(...)` for both `"absolute"` and `"max_normalised"` emission scales with `addSigmaNm`, and assert channel-wise parity against `finalAbsoluteExpected` and `finalMaxNormalisedExpected` within `Math.abs(exp) * 1e-9 + 1e-12`.
  2. **Null / Empty `lineSpreadAddSigmaNm` Boxcar Parity:** Verify that `lineSpreadAddSigmaNm = null` and `lineSpreadAddSigmaNm = Collections.emptyList()` produce bit-identical results (within `1e-12`) to explicit zero-filled `addSigma = new double[detWl.length]` in `SpectrumUtils.resampleToDetector(...)`, conserve integrated flux on a narrow sub-pixel peak, and diverge from non-flux-conserving pointwise `interpolateSpectrum`.
  3. **Deterministic `--seed` Propagation:** Verify that repeated synthesis runs with `finotelloNoiseBeta = 0.095` and identical `seed` + `sampleIndex` produce bit-exact identical output arrays, whereas different seeds or sample indices produce distinct non-negative noisy spectra.

---

## 3. Non-Functional Requirements & Guardrails

1. **Strict Java-Only Boundary:**
   - Do **NOT** modify any Python files (`scripts/*.py`, `tests/*.py`).
   - Do **NOT** modify `TRACK-CORE-01` contract or fixture files (`src/main/resources/metric_contract_v1.json`, `src/test/resources/metric_parity_golden_fixtures.json`, `scripts/match_metrics.py`, `scripts/parity_bridge.py`).
   - Do **NOT** modify `src/test/resources/forward_model_fixture.json`.
   - Do **NOT** regenerate or overwrite existing synthetic `.csv` datasets or `conf/instrument_profile.json` on disk.
2. **Untouched Workspace Files:**
   - Leave `notebooks/calibration_inspector.ipynb` and untracked `conductor/instrument_profile_debugging_and_improvement_specification_v1.md` untouched and unstaged.
3. **Backward Compatibility:**
   - All legacy constructors, getters, and JSON formats (`CalibrationStats(rSquared, rmse)`, legacy JSON without new fields, `InstrumentProfile.fromJson`) remain fully functional.
4. **Build & Test Stability:**
   - `mvn test` passes 100% with zero skipped/failing Java tests and >95% coverage on all new/modified code paths.

---

## 4. Acceptance Criteria

1. **Flux-Conserving Resampling & E2E Parity (`H5`, `M4`):** End-to-end JUnit test through `LIBSDataService.synthesizeSpectrumFromZoneCsvs` (`NISTUtils.parseNistCsv` -> `SpectrumUtils.resampleToDetector` -> `SpectrumUtils.combineNZones` -> `SpectrumUtils.applyInstrumentResponseAndNoise`) matches `forward_model_fixture.json` (`finalAbsoluteExpected` & `finalMaxNormalisedExpected`) within `1e-9`, and `null`/empty `lineSpreadAddSigmaNm` matches explicit zero-array flux-conserving boxcar integration within `1e-12`.
2. **Deterministic Seeding:** Repeated calls with `finotelloNoiseBeta > 0` and identical `(seed, sampleIndex)` yield bit-exact identical spectra (`assertArrayEquals`), while distinct seeds/indices yield distinct non-negative spectra; `finotelloNoiseBeta` remains `0.0` by default.
3. **Lossless `CalibrationStats` Round-Trip (`M1`):** Dedicated `CalibrationStatsTest` verifies both Jackson `ObjectMapper` and `org.json` `toJson()`/`fromJson()` round-trip all 11 new fields (`roiChi2Reduced`, `roiRSquared`, `roiRmse`, `roiRmseRaw`, `wholeSpectrumRSquared`, `wholeSpectrumRmse`, `wholeSpectrumRmseRaw`, `zoneReductionPenalty`, `familySharingPenalty`, `elementParity`, `perMaterialStats`) plus legacy fields (`rSquared`, `rmse`, `weightedCorrelationCost`, `numCalibratedMaterials`, `calibratedMaterials`) without field loss or duplicate Jackson keys, and confirms backward compatibility on legacy JSON.
4. **Morphological Default Alignment (`H3`):** Both `InstrumentProfileService.DEFAULT_MORPHOLOGICAL_STRUCT_SIZE` and `BaselineCorrectionParams.DEFAULT_STRUCTURING_ELEMENT_SIZE` equal `25`; `loadOrInitializeManifest(...)` and `new BaselineCorrectionParams()` emit `structuringElementSize = 25`; `BaselineCorrectionParamsTest` and `InstrumentProfileServiceRefactoringTest` pass.
5. **Monotonic Dirichlet Estimator & Sentinel Exclusion (`ConcentrationParameterEstimatorTest`):** Unit tests prove estimated total concentration $\alpha_0$ strictly increases as empirical alloy `[min, max]` spread narrows (holding means and `gradeCount` constant), is insensitive to the old `sqrt(gradeCount) - 1` collapse, excludes a sentinel `[0, 100]` balance element (`Fe: 80%`) from the pooled sum so tight explicit ranges on alloying elements (`Cr`, `Mn`) are not swamped, handles `min == max` and all-sentinel `[0, 100]` safely, and always produces `validateParameters(alphas) == true` (including with trace elements). Existing `CompositionalVariationsTest` remains green.
6. **Immutable `applyCoating` & NPE Fix (`InputCompositionProcessorTest`):** Unit tests across multiple base compositions in both `scaleCoating == false` and `scaleCoating == true` (for both new and pre-existing coating elements) verify: (a) original `baseCompositions`, their `Element`s, and `coatingElement` are never mutated; (b) returned compositions do not share `Element` object references; and (c) `scaleCoating == false` with `maxPercentElement` having non-null `[min, max]` while `coatedElement` has `null` `[min, max]` does not throw `NullPointerException`, and vice versa updates `coatedElement` bounds accurately.
7. **Full Java Suite Green:** `mvn test` exits with status `0` and 100% passing JUnit tests; `git diff --name-only` confirms zero modifications to Python files, `TRACK-CORE-01` contract files, `forward_model_fixture.json`, or synthetic `.csv` outputs.

---

## 5. Out of Scope

- Empirical `finotelloNoiseBeta` calibration in Python (explicitly deferred to a future release).
- Any edits to Python calibration or test scripts (`scripts/`, `tests/`).
- Unbroadened NIST stick-spectrum LSF deconvolution.
- Re-running calibration or regenerating synthetic `.csv` datasets on disk.
