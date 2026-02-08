package com.medals.libsdatagenerator.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InstrumentProfileService normalization logic.
 * 
 * @author A. Gravity
 */
class InstrumentProfileNormalisationTest {

    private InstrumentProfileService service;

    @BeforeEach
    void setUp() {
        service = InstrumentProfileService.getInstance();
    }

    @Test
    void testNormalizeSpectrum() {
        double[] input = { 10.0, 50.0, 20.0, 100.0, 0.0 };
        double[] expected = { 0.1, 0.5, 0.2, 1.0, 0.0 };

        double[] result = service.normalizeSpectrum(input);

        assertNotNull(result);
        assertEquals(input.length, result.length);
        assertArrayEquals(expected, result, 0.001);
    }

    @Test
    void testNormalizeSpectrumAllZeros() {
        double[] input = { 0.0, 0.0, 0.0 };
        double[] expected = { 0.0, 0.0, 0.0 };

        double[] result = service.normalizeSpectrum(input);

        assertArrayEquals(expected, result, 0.001);
    }

    @Test
    void testNormalizeSpectrumEmpty() {
        double[] input = {};
        double[] result = service.normalizeSpectrum(input);

        assertEquals(0, result.length);
    }

    @Test
    void testNormalizeSpectrumNull() {
        double[] result = service.normalizeSpectrum(null);
        assertEquals(0, result.length);
    }

    @Test
    void testCalculateRMSE() {
        double[] s1 = { 0.0, 0.5, 1.0 };
        double[] s2 = { 0.0, 0.5, 1.0 };

        double rmse = service.calculateRMSE(s1, s2);
        assertEquals(0.0, rmse, 0.001);

        double[] s3 = { 0.0, 1.0, 0.0 };
        // Diff: 0, 0.5, 1.0 -> Sq: 0, 0.25, 1.0 -> Sum: 1.25 -> Mean: 1.25/3 ~= 0.4166
        // -> Sqrt ~= 0.645
        double rmse2 = service.calculateRMSE(s1, s3);
        assertEquals(Math.sqrt(1.25 / 3.0), rmse2, 0.001);
    }
}
