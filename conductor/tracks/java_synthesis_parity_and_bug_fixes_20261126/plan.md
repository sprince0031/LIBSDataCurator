# Implementation Plan: `TRACK-CORE-02B` — Fix Java Synthesis Parity, Deterministic Seeding, DTO Bindings & Composition Bugs in `LIBSDataCurator`

- **Track ID:** `java_synthesis_parity_and_bug_fixes_20261126`
- **Track Type:** `Bug`
- **Test Runner:** `mvn test`
- **Strict Guardrails:** Java source & JUnit tests ONLY. Zero edits to Python files (`scripts/`, `tests/`), `TRACK-CORE-01` contract/fixture files (`src/main/resources/metric_contract_v1.json`, `src/test/resources/metric_parity_golden_fixtures.json`, `scripts/match_metrics.py`, `scripts/parity_bridge.py`), `src/test/resources/forward_model_fixture.json`, synthetic `.csv` outputs on disk, `notebooks/calibration_inspector.ipynb`, or untracked `conductor/instrument_profile_debugging_and_improvement_specification_v1.md`.

---

## Phase 1: DTO Bindings & Default Configuration Alignment (`M1`, `H3`)

- [x] Task: Complete `CalibrationStats.java` Jackson & `org.json` bindings (`M1`, FR-3) (856b208)
  - [x] Write failing unit tests in `src/test/java/com/medals/libsdatagenerator/model/CalibrationStatsTest.java` covering: (a) Jackson `ObjectMapper` and `org.json` `toJson()`/`fromJson()` lossless round-trip of all 11 new fields (`roiChi2Reduced`, `roiRSquared`, `roiRmse`, `roiRmseRaw`, `wholeSpectrumRSquared`, `wholeSpectrumRmse`, `wholeSpectrumRmseRaw`, `zoneReductionPenalty`, `familySharingPenalty`, `elementParity`, `perMaterialStats`) alongside legacy fields (`rSquared`, `rmse`, `weightedCorrelationCost`, `numCalibratedMaterials`, `calibratedMaterials`), (b) absence of duplicate lowercased Jackson keys (e.g., no `"rsquared"` alongside `"rSquared"`), and (c) backward-compatible deserialization of legacy JSON containing only `rSquared` and `rmse`
  - [x] Confirm failing tests via `mvn test -Dtest=CalibrationStatsTest` (Red)
  - [x] Implement the 11 fields, getters/setters, exact `@JsonProperty` annotations, and updated `toJson()` / `fromJson(JSONObject)` in `src/main/java/com/medals/libsdatagenerator/model/CalibrationStats.java`
  - [x] Run `mvn test -Dtest=CalibrationStatsTest,InstrumentProfileTest,ModelRefactoringTest` and verify Green

- [~] Task: Align morphological structuring element defaults to `25` (`H3`, FR-4)
  - [ ] Update assertions in `src/test/java/com/medals/libsdatagenerator/model/BaselineCorrectionParamsTest.java` (`testDefaultValues`, `testLegacyAlsJsonDeserialization`) and add a manifest `baselineCorrectionParams.structuringElementSize == 25` assertion in `src/test/java/com/medals/libsdatagenerator/service/InstrumentProfileServiceRefactoringTest.java`
  - [ ] Confirm failing tests via `mvn test -Dtest=BaselineCorrectionParamsTest,InstrumentProfileServiceRefactoringTest` (Red)
  - [ ] Update `InstrumentProfileService.DEFAULT_MORPHOLOGICAL_STRUCT_SIZE` (`InstrumentProfileService.java:69`) and `BaselineCorrectionParams.DEFAULT_STRUCTURING_ELEMENT_SIZE` (`BaselineCorrectionParams.java:11`) from `65` to `25`
  - [ ] Run `mvn test -Dtest=BaselineCorrectionParamsTest,InstrumentProfileServiceRefactoringTest,InstrumentProfileServiceTest` and verify Green

- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

---

## Phase 2: Compositional Sampling & Coating Immutability / NPE Fixes (`SH-03`, `SH-06`)

- [ ] Task: Fix Dirichlet algebraic cancellation & sentinel exclusion in `ConcentrationParameterEstimator.java` (FR-5)
  - [ ] Write failing unit tests in `src/test/java/com/medals/libsdatagenerator/service/ConcentrationParameterEstimatorTest.java` covering: (a) strict monotonic increase in total concentration $\alpha_0$ as empirical alloy `[min, max]` spread narrows (holding means and `gradeCount` constant, proving no $\sqrt{\text{gradeCount}} - 1$ collapse), (b) sentinel `[0.0, 100.0]` balance element (`Fe: 80%`) excluded from the pooled Dirichlet sum whenever alloying elements (`Cr`, `Mn`) have tight explicit ranges so $\alpha_0$ is not collapsed, (c) all-sentinel `[0.0, 100.0]` fallback remaining non-cancelling and valid, (d) `min == max` fixed-composition handling and `gradeCount < 2` $R/4$ fallback, and (e) trace-element `MIN_ALPHA` flooring keeping `sum(alphas) <= MAX_TOTAL_CONCENTRATION` so `validateParameters(alphas)` returns `true`
  - [ ] Confirm failing tests via `mvn test -Dtest=ConcentrationParameterEstimatorTest` (Red)
  - [ ] Implement Wan et al. (2014) range-to-SD variance (`NormalDistribution.inverseCumulativeProbability`, $d_n \in [2.0, 6.0]$, $R/4$ for $n < 2$), explicit-range-filtered pooled Dirichlet method-of-moments $\alpha_0 = \sum \mu_i(1-\mu_i) / \sum \text{Var}(X_i) - 1$ (with sentinel fallback only when zero elements have explicit ranges), and `MAX_TOTAL_CONCENTRATION`-preserving `MIN_ALPHA` adjustment in `src/main/java/com/medals/libsdatagenerator/service/ConcentrationParameterEstimator.java`
  - [ ] Run `mvn test -Dtest=ConcentrationParameterEstimatorTest,CompositionalVariationsTest,SeriesStatisticsExtractorTest` and verify Green

- [ ] Task: Deep-copy `Element` objects & fix latent NPE in `InputCompositionProcessor.applyCoating` (FR-6)
  - [ ] Write failing unit tests in `src/test/java/com/medals/libsdatagenerator/util/InputCompositionProcessorTest.java` covering: (a) multi-composition non-mutation of caller `baseCompositions`, contained `Element`s, and `coatingElement` across both `scaleCoating == false` and `scaleCoating == true` (for both new and pre-existing coating elements), (b) no shared mutable `Element` object references across output compositions, (c) latent NPE regression in `scaleCoating == false` when `maxPercentElement` has non-null `[min, max]` while pre-existing `coatedElement` has `null` `[min, max]`, and (d) accurate `coatedElement` `[min, max]` update when `coatedElement` has non-null bounds (even when `maxPercentElement` has `null` bounds)
  - [ ] Confirm failing tests via `mvn test -Dtest=InputCompositionProcessorTest` (Red)
  - [ ] Implement `copyElement(Element)` deep-copy helper, empty-composition guard, deep copies in both `scaleCoating == false` and `scaleCoating == true` branches, and line-513 `coatedElement.getMax() != null && coatedElement.getMin() != null` fix in `src/main/java/com/medals/libsdatagenerator/util/InputCompositionProcessor.java`
  - [ ] Run `mvn test -Dtest=InputCompositionProcessorTest` and verify Green

- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)

---

## Phase 3: Java Synthesis Parity, Deterministic `--seed` & End-to-End Parity Gate (`SH-02`, `H5`, `M4`)

- [ ] Task: Enforce unconditional flux-conserving resampling (`H5`), deterministic SplitMix64 `--seed` propagation, and end-to-end forward parity testing (`M4`) in `LIBSDataService.java` (FR-1, FR-2, FR-7)
  - [ ] Write failing unit & end-to-end tests in `src/test/java/com/medals/libsdatagenerator/service/LIBSDataServiceTest.java` covering: (a) full Java synthesis chain (`NISTUtils.parseNistCsv` -> `SpectrumUtils.resampleToDetector` -> `SpectrumUtils.combineNZones` -> `SpectrumUtils.applyInstrumentResponseAndNoise`) via `LIBSDataService.synthesizeSpectrumFromZoneCsvs(...)` matching `src/test/resources/forward_model_fixture.json` (`finalAbsoluteExpected` & `finalMaxNormalisedExpected`) within `Math.abs(exp) * 1e-9 + 1e-12`, (b) `null` and empty `lineSpreadAddSigmaNm` matching explicit zero-filled `SpectrumUtils.resampleToDetector(..., new double[detWl.length])` within `1e-12` and conserving flux on a narrow sub-pixel peak (diverging from pointwise `interpolateSpectrum`), (c) bit-exact determinism for identical `(seed, sampleIndex)` when `finotelloNoiseBeta = 0.095` and distinct non-negative outputs for differing seeds/indices, and (d) default `finotelloNoiseBeta == 0.0` preservation
  - [ ] Confirm failing tests via `mvn test -Dtest=LIBSDataServiceTest` (Red)
  - [ ] Extract package-private `synthesizeSpectrumFromZoneCsvs(...)` and `deriveSampleNoiseRandom(Long baseSeed, int sampleIndex)` (SplitMix64 mixing) in `src/main/java/com/medals/libsdatagenerator/service/LIBSDataService.java`, replace the `interpolateSpectrum` fallback (`LIBSDataService.java:366-374`) with unconditional `SpectrumUtils.resampleToDetector(...)` using a zero-filled `double[detWl.length]` when `lineSpreadAddSigmaNm` is `null` or empty, replace `new Random()` (`LIBSDataService.java:387`) with `deriveSampleNoiseRandom(config != null ? config.seed : null, sampleIndex)`, and remove the unused local `SpectrumUtils` variable
  - [ ] Run `mvn test -Dtest=LIBSDataServiceTest,SpectrumUtilsTest` and verify Green

- [ ] Task: Full Java Regression & Guardrail Verification Gate
  - [ ] Run full `mvn test` and confirm 100% pass with 0 failures and 0 errors
  - [ ] Verify via `git status` and `git diff --name-only` that zero Python files, zero `TRACK-CORE-01` contract/fixture files, zero `forward_model_fixture.json` edits, zero synthetic `.csv` outputs, and neither `notebooks/calibration_inspector.ipynb` nor `conductor/instrument_profile_debugging_and_improvement_specification_v1.md` were modified

- [ ] Task: Phase Verification & Checkpoint (Refer to workflow.md)
