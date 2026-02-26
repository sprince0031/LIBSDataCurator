package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.util.SpectrumUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Unit tests for InstrumentProfileService normalization logic.
 * 
 * @author Siddharth Prince, Antigravity | 08/02/2026 23:26
 */
class SpectrumNormalisationTest {

    private SpectrumUtils spectrumUtils;

    @BeforeEach
    void setUp() {
        spectrumUtils = new SpectrumUtils();
    }

    @Test
    void testNormalizeSpectrum() {
        double[] input = { 10.0, 50.0, 20.0, 100.0, 0.0 };
        double[] expected = { 0.1, 0.5, 0.2, 1.0, 0.0 };

        double[] result = spectrumUtils.normaliseSpectrum(input);

        assertNotNull(result);
        assertEquals(input.length, result.length);
        assertArrayEquals(expected, result, 0.001);
    }

    @Test
    void testNormalizeSpectrumAllZeros() {
        double[] input = { 0.0, 0.0, 0.0 };
        double[] expected = { 0.0, 0.0, 0.0 };

        double[] result = spectrumUtils.normaliseSpectrum(input);

        assertArrayEquals(expected, result, 0.001);
    }

    @Test
    void testNormalizeSpectrumEmpty() {
        double[] input = {};
        double[] result = spectrumUtils.normaliseSpectrum(input);

        assertEquals(0, result.length);
    }

    @Test
    void testNormalizeSpectrumNull() {
        double[] result = spectrumUtils.normaliseSpectrum(null);
        assertEquals(0, result.length);
    }

    @Test
    void testCalculateRMSE() {
        double[] s1 = { 0.0, 0.5, 1.0 };
        double[] s2 = { 0.0, 0.5, 1.0 };

        double rmse = spectrumUtils.calculateRMSE(s1, s2);
        assertEquals(0.0, rmse, 0.001);

        double[] s3 = { 0.0, 1.0, 0.0 };
        // Diff: 0, 0.5, 1.0 -> Sq: 0, 0.25, 1.0 -> Sum: 1.25 -> Mean: 1.25/3 ~= 0.4166
        // -> Sqrt ~= 0.645
        double rmse2 = spectrumUtils.calculateRMSE(s1, s3);
        assertEquals(Math.sqrt(1.25 / 3.0), rmse2, 0.001);
    }

    @Test
    void testRSquared() {
        double[] s1 = { 0.0, 0.5, 1.0 };
        double[] s2 = { 0.0, 0.5, 1.0 };
        double rSquared = spectrumUtils.calculateSpectralSimilarity(s1, s2);
        assertEquals(1.0, rSquared, 0.001);
        double[] s3 = { 0.0, 1.0, 0.5 };
        double rSquared2 = spectrumUtils.calculateSpectralSimilarity(s1, s3);
        assertEquals(0.25, rSquared2, 0.001);
    }
}
