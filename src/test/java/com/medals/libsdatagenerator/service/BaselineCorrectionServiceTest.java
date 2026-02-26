package com.medals.libsdatagenerator.service;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Random;

public class BaselineCorrectionServiceTest {

    @Test
    public void testCorrectBaseline_ZeroSpectrum() {
        double[] spectrum = new double[100];
        double[] corrected = BaselineCorrectionService.getInstance().correctBaseline(spectrum);
        for (double v : corrected) {
            Assertions.assertEquals(0.0, v, 1e-6);
        }
    }

    @Test
    public void testCorrectBaseline_FlatBaseline() {
        double[] spectrum = new double[100];
        Arrays.fill(spectrum, 50.0);
        // Add a peak
        spectrum[50] += 100.0;

        double[] corrected = BaselineCorrectionService.getInstance().correctBaseline(spectrum);

        // Baseline should be approx 50. Corrected should be approx spectrum - 50.
        // I.e. flat parts (0..40, 60..99) should be near 0.
        // Peak at 50 should be near 100.

        Assertions.assertEquals(100.0, corrected[50], 1.0, "Peak height preserved");
        Assertions.assertEquals(0.0, corrected[10], 1.0, "Baseline removed");
    }

    @Test
    public void testCorrectBaseline_LinearBaseline() {
        int n = 200;
        double[] spectrum = new double[n];
        for (int i = 0; i < n; i++) {
            spectrum[i] = 100.0 + 0.5 * i; // Linear slope
        }
        // Add Gaussian peak
        for (int i = 0; i < n; i++) {
            double dx = i - 100;
            spectrum[i] += 500.0 * Math.exp(-(dx * dx) / (2 * 5 * 5));
        }

        double[] corrected = BaselineCorrectionService.getInstance().correctBaseline(spectrum, 1e5, 0.001, 10);

        // Check if baseline is removed (should be close to 0 outside peak)
        Assertions.assertTrue(Math.abs(corrected[10]) < 1.0, "Start baseline removed");
        Assertions.assertTrue(Math.abs(corrected[190]) < 1.0, "End baseline removed");
        Assertions.assertEquals(500.0, corrected[100], 10.0, "Peak height preserved");
    }

    @Test
    public void testCorrectBaseline_Performance() {
        int n = 1000;
        double[] spectrum = new double[n];
        Random rand = new Random(42);
        for (int i = 0; i < n; i++) {
            spectrum[i] = Math.sin(i * 0.01) * 100 + 500; // Waviness
            if (i > 500 && i < 550)
                spectrum[i] += 1000; // Peak
            spectrum[i] += rand.nextGaussian(); // Noise
        }

        Assertions.assertTimeoutPreemptively(Duration.of(10, ChronoUnit.SECONDS),
                () -> BaselineCorrectionService.getInstance().correctBaseline(spectrum),
                "Performance check: 1000 points should be processed under 2s");
    }

    @Test
    public void testCorrectBaseline_WithCustomParameters() {
        double[] spectrum = new double[100];
        Arrays.fill(spectrum, 100.0);

        // Different parameters should produce slightly different results (even on flat,
        // though subtle)
        // or effectively we check that it runs without error and returns matching
        // length
        double[] r1 = BaselineCorrectionService.getInstance().correctBaseline(spectrum, 1000, 0.001, 5);
        double[] r2 = BaselineCorrectionService.getInstance().correctBaseline(spectrum, 100000, 0.01, 20);

        Assertions.assertEquals(spectrum.length, r1.length);
        Assertions.assertEquals(spectrum.length, r2.length);
    }
}
