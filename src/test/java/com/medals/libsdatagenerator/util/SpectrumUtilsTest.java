package com.medals.libsdatagenerator.util;

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

        // Channel 0:
        // S_0_norm = 10.0 / 20.0 = 0.5, I_0 = 0.8 * 0.5 = 0.4
        // S_1_norm = 2.0 / 4.0 = 0.5, transmission = exp(-0.5 * 0.5) = exp(-0.25) ~= 0.77880
        // Expected = 0.4 * exp(-0.25) + (0.2 * 0.5) = 0.31152 + 0.1 = 0.41152
        double expected0 = (0.8 * 0.5) * Math.exp(-0.5 * 0.5) + (0.2 * 0.5);
        assertEquals(expected0, combined[0], 1e-4);

        // Channel 1:
        // S_0_norm = 20.0 / 20.0 = 1.0, I_0 = 0.8 * 1.0 = 0.8
        // S_1_norm = 4.0 / 4.0 = 1.0, transmission = exp(-0.5 * 1.0) = exp(-0.5) ~= 0.60653
        // Expected = 0.8 * exp(-0.5) + (0.2 * 1.0) = 0.485225 + 0.2 = 0.685225
        double expected1 = (0.8 * 1.0) * Math.exp(-0.5 * 1.0) + (0.2 * 1.0);
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
    @DisplayName("combineNZones performs per-zone max-normalization before recursive RTE")
    void testCombineNZonesPerZoneMaxNormalization() {
        double[] core = { 1000.0, 500.0 };
        double[] shell = { 250.0, 500.0 };

        PlasmaZone z0 = new PlasmaZone(1.0, 1e17, 0.8, 0.0);
        PlasmaZone z1 = new PlasmaZone(0.7, 1e16, 0.2, 1.0);

        double[] combined = SpectrumUtils.combineNZones(List.of(core, shell), List.of(z0, z1));
        assertNotNull(combined);
        assertEquals(2, combined.length);

        // Channel 0: Core norm = 1.0 (v=0.8 -> 0.8). Shell norm = 0.5 (k=1.0, v=0.2).
        // combined[0] = 0.8 * exp(-1.0 * 0.5) + 0.2 * 0.5 = 0.8 * exp(-0.5) + 0.1
        double expectedCh0 = 0.8 * Math.exp(-0.5) + 0.1;
        assertEquals(expectedCh0, combined[0], 1e-4);

        // Channel 1: Core norm = 0.5 (v=0.8 -> 0.4). Shell norm = 1.0 (k=1.0, v=0.2).
        // combined[1] = 0.4 * exp(-1.0 * 1.0) + 0.2 * 1.0 = 0.4 * exp(-1.0) + 0.2
        double expectedCh1 = 0.4 * Math.exp(-1.0) + 0.2;
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

    private static double[] toDoubleArray(org.json.JSONArray arr) {
        double[] res = new double[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            res[i] = arr.getDouble(i);
        }
        return res;
    }
}
