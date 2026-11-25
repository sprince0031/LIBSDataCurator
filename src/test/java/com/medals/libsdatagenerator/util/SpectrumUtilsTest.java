package com.medals.libsdatagenerator.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medals.libsdatagenerator.model.PlasmaZone;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SpectrumUtilsTest {

    private SpectrumUtils spectrumUtils;

    @BeforeEach
    void setUp() {
        spectrumUtils = new SpectrumUtils();
    }

    @Test
    @DisplayName("combineNZones applies Beer-Lambert self-absorption correctly")
    void testCombineNZonesTwoZonesAbsorption() {
        // Zone 0: Core
        PlasmaZone core = new PlasmaZone(1.2, 2.0e17, 0.8, 0.0);
        // Zone 1: Periphery
        PlasmaZone periph = new PlasmaZone(0.6, 2.0e16, 0.2, 0.5);

        double[] coreSpec = {10.0, 20.0};
        double[] periphSpec = {2.0, 4.0};

        List<double[]> spectra = List.of(coreSpec, periphSpec);
        List<PlasmaZone> zones = List.of(core, periph);

        double[] combined = spectrumUtils.combineNZones(spectra, zones);
        assertNotNull(combined);
        assertEquals(2, combined.length);

        // Channel 0 (R4: unnormalized emission, max-normalized opacity):
        // I_0 = 0.8 * 10.0 = 8.0
        // S_1_norm = 2.0 / 4.0 = 0.5, transmission = exp(-0.5 * 0.5) = exp(-0.25) ~= 0.77880
        // Expected = 8.0 * exp(-0.25) + (0.2 * 2.0) = 6.2304 + 0.4 = 6.6304
        double expected0 = (0.8 * 10.0) * Math.exp(-0.5 * 0.5) + (0.2 * 2.0);
        assertEquals(expected0, combined[0], 1e-4);

        // Channel 1:
        // I_0 = 0.8 * 20.0 = 16.0
        // S_1_norm = 4.0 / 4.0 = 1.0, transmission = exp(-0.5 * 1.0) = exp(-0.5) ~= 0.60653
        // Expected = 16.0 * exp(-0.5) + (0.2 * 4.0) = 9.7045 + 0.8 = 10.5045
        double expected1 = (0.8 * 20.0) * Math.exp(-0.5 * 1.0) + (0.2 * 4.0);
        assertEquals(expected1, combined[1], 1e-4);
    }

    @Test
    @DisplayName("applySpectralResponse correctly multiplies spectrum by r(lambda)")
    void testApplySpectralResponse() {
        double[] spec = {10.0, 20.0, 30.0};
        List<Double> resp = List.of(0.5, 1.0, 1.5);

        double[] result = spectrumUtils.applySpectralResponse(spec, resp);
        assertNotNull(result);
        assertEquals(3, result.length);
        assertEquals(5.0, result[0], 1e-6);
        assertEquals(20.0, result[1], 1e-6);
        assertEquals(45.0, result[2], 1e-6);
    }

    @Test
    @DisplayName("normaliseAndScaleSpectrum with spectral response scales normalized output")
    void testNormaliseAndScaleWithSpectralResponse() {
        double[] spec = {10.0, 20.0};
        List<Double> resp = List.of(0.5, 2.0); // -> [5.0, 40.0], max = 40.0 -> [0.125, 1.0]
        double scaleFactor = 1000.0;

        List<Double> scaled = spectrumUtils.normaliseAndScaleSpectrum(spec, scaleFactor, resp);
        assertNotNull(scaled);
        assertEquals(2, scaled.size());
        assertEquals(125.0, scaled.get(0), 1e-4);
        assertEquals(1000.0, scaled.get(1), 1e-4);
    }

    @Test
    @DisplayName("combineNZones uses unnormalized emission with max-normalized opacity in RTE (R4)")
    void testCombineNZonesPerZoneMaxNormalization() {
        double[] core = { 1000.0, 500.0 };
        double[] shell = { 250.0, 500.0 };

        PlasmaZone z0 = new PlasmaZone(1.0, 1e17, 0.8, 0.0);
        PlasmaZone z1 = new PlasmaZone(0.7, 1e16, 0.2, 1.0);

        double[] combined = SpectrumUtils.combineNZones(List.of(core, shell), List.of(z0, z1));
        assertNotNull(combined);
        assertEquals(2, combined.length);

        // Channel 0: Core unnormalized emission = 0.8 * 1000 = 800. Shell norm for opacity = 250/500 = 0.5.
        // combined[0] = 800.0 * exp(-1.0 * 0.5) + 0.2 * 250.0 = 800.0 * exp(-0.5) + 50.0
        double expectedCh0 = 800.0 * Math.exp(-0.5) + 50.0;
        assertEquals(expectedCh0, combined[0], 1e-4);

        // Channel 1: Core unnormalized emission = 0.8 * 500 = 400. Shell norm for opacity = 500/500 = 1.0.
        // combined[1] = 400.0 * exp(-1.0 * 1.0) + 0.2 * 500.0 = 400.0 * exp(-1.0) + 100.0
        double expectedCh1 = 400.0 * Math.exp(-1.0) + 100.0;
        assertEquals(expectedCh1, combined[1], 1e-4);
    }

    @Test
    @DisplayName("applyInstrumentResponseAndNoise normalizes to unit max and scales to scaleFactor without noise")
    void testApplyInstrumentResponseAndNoiseNoNoise() {
        double[] rteSpectrum = { 0.5, 1.0, 0.2 };
        double[] response = { 1.0, 1.5, 0.8 };
        double scaleFactor = 45000.0;

        double[] result = SpectrumUtils.applyInstrumentResponseAndNoise(
                rteSpectrum, response, scaleFactor, 0.0, null
        );

        assertNotNull(result);
        assertEquals(3, result.length);
        double maxVal = Arrays.stream(result).max().orElse(0.0);
        assertEquals(scaleFactor, maxVal, 1e-4);
    }

    @Test
    @DisplayName("applyInstrumentResponseAndNoise injects non-negative Finotello multiplicative noise")
    void testApplyInstrumentResponseAndNoiseWithNoise() {
        double[] rteSpectrum = { 0.5, 1.0, 0.2 };
        double[] response = { 1.0, 1.0, 1.0 };
        double scaleFactor = 50000.0;
        java.util.Random rng = new java.util.Random(42);

        double[] result = SpectrumUtils.applyInstrumentResponseAndNoise(
                rteSpectrum, response, scaleFactor, 0.095, rng
        );

        assertNotNull(result);
        for (double val : result) {
            assertTrue(val >= 0.0, "Noise-injected counts must be non-negative");
        }
    }

    @Test
    @DisplayName("resampleToDetector, combineNZones, and applyInstrumentResponseAndNoise match Python fixture to 1e-9 (AC5)")
    void testForwardModelParityWithPythonFixture() throws Exception {
        java.io.InputStream is = getClass().getResourceAsStream("/forward_model_fixture.json");
        assertNotNull(is, "forward_model_fixture.json must exist in test resources");
        String content = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        org.json.JSONObject json = new org.json.JSONObject(content);

        // 1. resampleToDetector
        double[] srcWl = toDoubleArray(json.getJSONArray("srcWl"));
        double[] srcI = toDoubleArray(json.getJSONArray("srcI"));
        double[] detWl = toDoubleArray(json.getJSONArray("detWl"));
        double[] addSigma = toDoubleArray(json.getJSONArray("addSigmaNm"));
        double[] resampledExp = toDoubleArray(json.getJSONArray("resampledExpected"));

        double[] resampledActual = SpectrumUtils.resampleToDetector(srcWl, srcI, detWl, addSigma);
        assertNotNull(resampledActual);
        assertEquals(resampledExp.length, resampledActual.length);
        for (int i = 0; i < resampledExp.length; i++) {
            assertEquals(resampledExp[i], resampledActual[i], Math.abs(resampledExp[i]) * 1e-9 + 1e-12,
                    "Resampled channel " + i + " mismatch");
        }

        // 2. combineNZones (R4: unnormalized emission, max-normalized opacity)
        double[] d0 = toDoubleArray(json.getJSONArray("d0"));
        double[] d1 = toDoubleArray(json.getJSONArray("d1"));
        double[] combinedExp = toDoubleArray(json.getJSONArray("combinedExpected"));

        PlasmaZone z0 = new PlasmaZone(1.1, 1e17, 0.35, 0.0);
        PlasmaZone z1 = new PlasmaZone(0.7, 5e16, 0.65, 1.4);
        double[] combinedActual = SpectrumUtils.combineNZones(List.of(d0, d1), List.of(z0, z1));
        assertNotNull(combinedActual);
        assertEquals(combinedExp.length, combinedActual.length);
        for (int i = 0; i < combinedExp.length; i++) {
            assertEquals(combinedExp[i], combinedActual[i], Math.abs(combinedExp[i]) * 1e-9 + 1e-12,
                    "Combined channel " + i + " mismatch");
        }

        // 3. applyInstrumentResponseAndNoise
        double[] response = toDoubleArray(json.getJSONArray("spectralResponse"));
        double scaleFactor = json.getDouble("scaleFactor");
        double[] finalAbsExp = toDoubleArray(json.getJSONArray("finalAbsoluteExpected"));
        double[] finalNormExp = toDoubleArray(json.getJSONArray("finalMaxNormalisedExpected"));

        double[] finalAbsActual = SpectrumUtils.applyInstrumentResponseAndNoise(
                combinedActual, response, scaleFactor, 0.0, null, "absolute"
        );
        for (int i = 0; i < finalAbsExp.length; i++) {
            assertEquals(finalAbsExp[i], finalAbsActual[i], Math.abs(finalAbsExp[i]) * 1e-9 + 1e-12,
                    "Final absolute channel " + i + " mismatch");
        }

        double[] finalNormActual = SpectrumUtils.applyInstrumentResponseAndNoise(
                combinedActual, response, scaleFactor, 0.0, null, "max_normalised"
        );
        for (int i = 0; i < finalNormExp.length; i++) {
            assertEquals(finalNormExp[i], finalNormActual[i], Math.abs(finalNormExp[i]) * 1e-9 + 1e-12,
                    "Final max-normalised channel " + i + " mismatch");
        }
    }

    @Test
    @DisplayName("metric_contract_v1.json loads from classpath and exposes canonical contract schema")
    void testMetricContractV1ClasspathLoading() throws Exception {
        try (java.io.InputStream is = getClass().getResourceAsStream("/metric_contract_v1.json")) {
            assertNotNull(is, "metric_contract_v1.json must be available on the classpath");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(is);

            assertEquals("metric_contract_v1", root.get("schema_version").asText());
            assertEquals(1e-12, root.get("tolerances").get("eps").asDouble(), 0.0);
            assertEquals(1e-6, root.get("tolerances").get("wavelength_tol").asDouble(), 0.0);
            assertTrue(root.get("normalization_modes").has("none"));
            assertTrue(root.get("normalization_modes").has("max"));
            assertTrue(root.get("normalization_modes").has("l2"));
            assertTrue(root.get("normalization_modes").has("sum"));
            assertEquals(9, root.get("canonical_metric_keys").size());
            assertEquals(4, root.get("preprocessing_pipeline").get("shot_level_order").size());
            assertEquals(1, root.get("preprocessing_pipeline").get("post_average_order").size());
        }
    }

    @Test
    @DisplayName("computeSpectralMetrics (NONE, no variance) returns canonical payload matching metric_contract_v1")
    void testComputeSpectralMetricsCanonicalNone() {
        double[] yRef = {10.0, 20.0, 30.0, 40.0};
        double[] yPred = {12.0, 18.0, 33.0, 37.0};

        SpectrumUtils.SpectralMetrics m = SpectrumUtils.computeSpectralMetrics(yRef, yPred);
        assertEquals(SpectrumUtils.NormalizationMode.NONE, m.normalization());
        assertEquals(4, m.nValid());
        assertNull(m.reducedChi2());

        double expectedRmse = Math.sqrt((4.0 + 4.0 + 9.0 + 9.0) / 4.0);
        assertEquals(expectedRmse, m.rmse(), 1e-12);
        assertEquals(expectedRmse / 40.0, m.nrmseMax(), 1e-12);
        assertEquals((2.0 + 2.0 + 3.0 + 3.0) / 4.0, m.mae(), 1e-12);
        assertEquals(1.0 - 26.0 / 500.0, m.r2(), 1e-12);
        assertTrue(m.pearsonR2() >= 0.0 && m.pearsonR2() <= 1.0);
        assertEquals(m.r2(), SpectrumUtils.calculateR2(yRef, yPred), 1e-12);
        assertEquals(m.pearsonR2(), SpectrumUtils.calculatePearsonR2(yRef, yPred), 1e-12);
        assertEquals(m.mae(), SpectrumUtils.calculateMAE(yRef, yPred), 1e-12);
        assertEquals(m.cosineSimilarity(), SpectrumUtils.calculateCosineSimilarity(yRef, yPred), 1e-12);
        // Backward-compatible instance methods
        assertEquals(m.rmse(), spectrumUtils.calculateRMSE(yRef, yPred), 1e-12);
        assertEquals(m.pearsonR2(), spectrumUtils.calculateSpectralSimilarity(yPred, yRef), 1e-12);
    }

    @Test
    @DisplayName("computeSpectralMetrics supports NONE, MAX, L2, SUM normalization and variance scaling")
    void testComputeSpectralMetricsNormalizationModesAndVarianceScaling() {
        double[] yRef = {-5.0, 10.0, 25.0, 15.0};
        double[] yPred = {-4.0, 12.0, 20.0, 18.0};
        double[] variance = {1.0, 4.0, 9.0, 16.0};

        for (SpectrumUtils.NormalizationMode mode : SpectrumUtils.NormalizationMode.values()) {
            SpectrumUtils.SpectralMetrics m = SpectrumUtils.computeSpectralMetrics(
                    yRef, yPred, null, mode, variance, 1, SpectrumUtils.DEFAULT_METRIC_EPS
            );
            assertEquals(mode, m.normalization());
            assertEquals(4, m.nValid());
            assertNotNull(m.reducedChi2());

            double sRef;
            double sPred;
            switch (mode) {
                case NONE -> { sRef = 1.0; sPred = 1.0; }
                case MAX -> { sRef = 25.0; sPred = 20.0; }
                case L2 -> {
                    sRef = Math.sqrt(25.0 + 100.0 + 625.0 + 225.0);
                    sPred = Math.sqrt(16.0 + 144.0 + 400.0 + 324.0);
                }
                case SUM -> { sRef = 5.0 + 10.0 + 25.0 + 15.0; sPred = 4.0 + 12.0 + 20.0 + 18.0; }
                default -> throw new IllegalStateException("Unexpected mode: " + mode);
            }

            double chi2Sum = 0.0;
            double sqErrSum = 0.0;
            for (int i = 0; i < yRef.length; i++) {
                double diff = (yRef[i] / sRef) - (yPred[i] / sPred);
                sqErrSum += diff * diff;
                double vScaled = Math.max(variance[i] / (sRef * sRef), 1e-12);
                chi2Sum += (diff * diff) / vScaled;
            }
            assertEquals(Math.sqrt(sqErrSum / 4.0), m.rmse(), 1e-12);
            assertEquals(chi2Sum / 3.0, m.reducedChi2(), 1e-12);
        }
    }

    @Test
    @DisplayName("computeSpectralMetrics enforces ROI masking, wavelengthTol, and array-length validation")
    void testComputeSpectralMetricsValidationAndMasking() {
        double[] wlRef = {350.0, 351.0, 352.0, 353.0};
        double[] wlOk = {350.0 + 5e-7, 351.0 + 5e-7, 352.0 + 5e-7, 353.0 + 5e-7};
        double[] wlBad = {350.0 + 2e-6, 351.0, 352.0, 353.0};
        double[] yRef = {100.0, 10.0, 20.0, 30.0};
        double[] yPred = {9999.0, 10.0, 20.0, 30.0};
        boolean[] mask = {false, true, true, true};

        SpectrumUtils.SpectralMetrics masked = SpectrumUtils.computeSpectralMetrics(
                yRef, yPred, wlRef, wlOk, null, mask,
                SpectrumUtils.NormalizationMode.NONE, 0,
                SpectrumUtils.DEFAULT_METRIC_EPS, SpectrumUtils.DEFAULT_WAVELENGTH_TOL
        );
        assertEquals(3, masked.nValid());
        assertEquals(0.0, masked.rmse(), 1e-12);
        assertEquals(1.0, masked.r2(), 1e-12);

        // Wavelength mismatch -> IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> SpectrumUtils.computeSpectralMetrics(
                yRef, yPred, wlRef, wlBad, null, mask,
                SpectrumUtils.NormalizationMode.NONE, 0, 1e-12, 1e-6
        ));
        // Length mismatches -> IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> SpectrumUtils.computeSpectralMetrics(yRef, new double[]{1.0, 2.0}));
        assertThrows(IllegalArgumentException.class, () -> SpectrumUtils.computeSpectralMetrics(
                yRef, yPred, new boolean[]{true, false}, SpectrumUtils.NormalizationMode.NONE, 1e-12));
        assertThrows(IllegalArgumentException.class, () -> SpectrumUtils.computeSpectralMetrics(
                yRef, yPred, null, SpectrumUtils.NormalizationMode.NONE, new double[]{1.0, 2.0}, 0, 1e-12));
        assertThrows(IllegalArgumentException.class, () -> SpectrumUtils.computeSpectralMetrics(
                yRef, yPred, new double[]{350.0}, wlRef, null, null,
                SpectrumUtils.NormalizationMode.NONE, 0, 1e-12, 1e-6));
        assertThrows(IllegalArgumentException.class, () -> SpectrumUtils.computeSpectralMetrics(
                yRef, yPred, wlRef, new double[]{350.0}, null, null,
                SpectrumUtils.NormalizationMode.NONE, 0, 1e-12, 1e-6));
        // Empty valid mask & empty input -> IllegalArgumentException
        assertThrows(IllegalArgumentException.class, () -> SpectrumUtils.computeSpectralMetrics(
                yRef, yPred, new boolean[]{false, false, false, false}, SpectrumUtils.NormalizationMode.NONE, 1e-12));
        assertThrows(IllegalArgumentException.class, () -> SpectrumUtils.computeSpectralMetrics(
                new double[]{}, new double[]{}));
    }

    @Test
    @DisplayName("computeSpectralMetrics handles zero-variance and zero-norm edge cases without NaN")
    void testComputeSpectralMetricsZeroVarianceAndZeroNormNeverNaN() {
        double[] constant = {5.0, 5.0, 5.0};
        SpectrumUtils.SpectralMetrics eqConst = SpectrumUtils.computeSpectralMetrics(constant, new double[]{5.0, 5.0, 5.0});
        assertEquals(1.0, eqConst.r2(), 1e-12);
        assertEquals(1.0, eqConst.pearsonR2(), 1e-12);
        assertEquals(1.0, eqConst.cosineSimilarity(), 1e-12);

        SpectrumUtils.SpectralMetrics neqConst = SpectrumUtils.computeSpectralMetrics(constant, new double[]{6.0, 6.0, 6.0});
        assertEquals(0.0, neqConst.r2(), 1e-12);
        assertEquals(0.0, neqConst.pearsonR2(), 1e-12);
        assertFalse(Double.isNaN(neqConst.pearsonR2()));

        double[] zeros = {0.0, 0.0, 0.0, 0.0};
        SpectrumUtils.SpectralMetrics eqZero = SpectrumUtils.computeSpectralMetrics(zeros, new double[]{0.0, 0.0, 0.0, 0.0});
        assertEquals(1.0, eqZero.r2(), 1e-12);
        assertEquals(1.0, eqZero.pearsonR2(), 1e-12);
        assertEquals(1.0, eqZero.cosineSimilarity(), 1e-12);

        SpectrumUtils.SpectralMetrics neqZero = SpectrumUtils.computeSpectralMetrics(zeros, new double[]{0.0, 1.0, 2.0, 3.0});
        assertEquals(0.0, neqZero.r2(), 1e-12);
        assertEquals(0.0, neqZero.pearsonR2(), 1e-12);
        assertEquals(0.0, neqZero.cosineSimilarity(), 1e-12);
    }

    @Test
    @DisplayName("SpectrumUtils.computeSpectralMetrics matches Python match_metrics.py within 1e-9 across synthetic and 5 golden materials")
    void testGoldenFixturesPythonJavaParity() throws Exception {
        try (java.io.InputStream is = getClass().getResourceAsStream("/metric_parity_golden_fixtures.json")) {
            assertNotNull(is, "metric_parity_golden_fixtures.json must exist in test resources");
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(is);

            assertEquals("metric_contract_v1", root.get("schema_version").asText());
            double tol = root.get("parity_tolerance").asDouble();
            assertEquals(1e-9, tol, 0.0);

            JsonNode vectorsNode = root.get("material_vectors");
            List<String> expectedMaterials = List.of("SS469", "SS551", "SS465", "SS405", "EN AC-42000");
            for (String mat : expectedMaterials) {
                assertTrue(vectorsNode.has(mat), "Missing golden material vectors for " + mat);
            }

            JsonNode casesNode = root.get("cases");
            assertTrue(casesNode.size() >= 28, "Expected synthetic + 5x4 golden material cases");

            for (JsonNode caseNode : casesNode) {
                String caseId = caseNode.get("id").asText();
                String kind = caseNode.get("kind").asText();
                double[] yRef;
                double[] yPred;
                double[] variance;
                if ("golden_material".equals(kind)) {
                    JsonNode mv = vectorsNode.get(caseNode.get("material").asText());
                    yRef = toDoubleArray(mv.get("y_ref"));
                    yPred = toDoubleArray(mv.get("y_pred"));
                    variance = caseNode.get("use_variance").asBoolean() ? toDoubleArray(mv.get("variance")) : null;
                } else {
                    yRef = toDoubleArray(caseNode.get("y_ref"));
                    yPred = toDoubleArray(caseNode.get("y_pred"));
                    variance = caseNode.get("variance").isNull() ? null : toDoubleArray(caseNode.get("variance"));
                }
                boolean[] mask = caseNode.get("mask").isNull() ? null : toBooleanArray(caseNode.get("mask"));
                SpectrumUtils.NormalizationMode mode = SpectrumUtils.NormalizationMode.fromContractName(
                        caseNode.get("normalization").asText());
                int numParams = caseNode.get("num_params").asInt();

                SpectrumUtils.SpectralMetrics actual = SpectrumUtils.computeSpectralMetrics(
                        yRef, yPred, mask, mode, variance, numParams, SpectrumUtils.DEFAULT_METRIC_EPS);

                JsonNode exp = caseNode.get("expected");
                assertEquals(exp.get("normalization").asText(), actual.normalization().contractName(), caseId);
                assertEquals(exp.get("n_valid").asInt(), actual.nValid(), caseId);

                assertParityWithinTol(exp.get("rmse").asDouble(), actual.rmse(), tol, caseId + ":rmse");
                assertParityWithinTol(exp.get("nrmse_max").asDouble(), actual.nrmseMax(), tol, caseId + ":nrmse_max");
                assertParityWithinTol(exp.get("mae").asDouble(), actual.mae(), tol, caseId + ":mae");
                assertParityWithinTol(exp.get("r2").asDouble(), actual.r2(), tol, caseId + ":r2");
                assertParityWithinTol(exp.get("pearson_r2").asDouble(), actual.pearsonR2(), tol, caseId + ":pearson_r2");
                assertParityWithinTol(exp.get("cosine_similarity").asDouble(), actual.cosineSimilarity(), tol, caseId + ":cosine_similarity");

                if (exp.get("reduced_chi2").isNull()) {
                    assertNull(actual.reducedChi2(), caseId + ":reduced_chi2 expected null");
                } else {
                    assertNotNull(actual.reducedChi2(), caseId + ":reduced_chi2 expected non-null");
                    assertParityWithinTol(exp.get("reduced_chi2").asDouble(), actual.reducedChi2(), tol, caseId + ":reduced_chi2");
                }
            }
        }
    }

    private static void assertParityWithinTol(double expected, double actual, double tol, String label) {
        assertFalse(Double.isNaN(actual), label + " must not be NaN");
        double delta = Math.abs(expected - actual);
        // Check absolute tolerance <= 1e-9 (or relative 1e-9 for huge reduced_chi2 values > 1e6 where double ULP scales with magnitude)
        double allowed = Math.max(tol, Math.abs(expected) * 1e-12);
        assertTrue(delta <= allowed,
                label + " parity mismatch: expected=" + expected + ", actual=" + actual + ", delta=" + delta);
    }

    private static double[] toDoubleArray(JsonNode arr) {
        double[] res = new double[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            res[i] = arr.get(i).asDouble();
        }
        return res;
    }

    private static boolean[] toBooleanArray(JsonNode arr) {
        boolean[] res = new boolean[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            res[i] = arr.get(i).asBoolean();
        }
        return res;
    }

    private static double[] toDoubleArray(org.json.JSONArray arr) {
        double[] res = new double[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            res[i] = arr.getDouble(i);
        }
        return res;
    }
}
