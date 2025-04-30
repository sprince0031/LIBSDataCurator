package com.medals.libsdatagenerator.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class CompositionalVariationsTest {

    private CompositionalVariations compositionalVariations;

    @BeforeEach
    void setUp() {
        compositionalVariations = new CompositionalVariations();
    }

    @Test
    void testGaussianSampling_Basic() {
        // Arrange
        ArrayList<Element> baseComp = new ArrayList<>();
        baseComp.add(new Element("Carbon", "C", 0.2, 0.1, 0.3));
        baseComp.add(new Element("Silicon", "Si", 1.0, 0.8, 1.2));
        baseComp.add(new Element("Iron", "Fe", 98.8, 98.0, 99.5));

        double maxDelta = 0.1;
        int samples = 5;
        ArrayList<ArrayList<Element>> variations = new ArrayList<>();

        // Act
        compositionalVariations.gaussianSampling(baseComp, maxDelta, samples, variations);

        // Assert
        assertEquals(samples, variations.size(), "Should generate the requested number of samples.");
        assertFalse(variations.isEmpty(), "Variations list should not be empty.");

        for (ArrayList<Element> variation : variations) {
            assertEquals(baseComp.size(), variation.size(),
                    "Each variation should have the same number of elements as the base.");
            double sum = variation.stream().mapToDouble(Element::getPercentageComposition).sum();
            assertEquals(100.0, sum, 0.005, "Element percentages in each variation should sum to 100.");
        }
    }

    @Test
    void testGaussianSampling_WithMinMax() {
        // Arrange
        ArrayList<Element> baseComp = new ArrayList<>();
        Element carbon = new Element("Carbon", "C", 0.5, 0.1, 1.0);
        Element iron = new Element("Iron", "Fe", 99.5, 99.0, 99.9);

        baseComp.add(carbon);
        baseComp.add(iron);

        double maxDelta = 0.2;
        int samples = 10;
        ArrayList<ArrayList<Element>> variations = new ArrayList<>();

        // Act
        compositionalVariations.gaussianSampling(baseComp, maxDelta, samples, variations);

        // Assert
        assertEquals(samples, variations.size());
        for (ArrayList<Element> variation : variations) {
            assertEquals(2, variation.size());
            double sum = variation.stream().mapToDouble(Element::getPercentageComposition).sum();
            assertEquals(100.0, sum, 0.005);

            for (Element e : variation) {
                if (e.getName().equals("Carbon")) {
                    assertTrue(e.getPercentageComposition() >= 0.0,
                            "Carbon percentage should not be negative.");
                } else if (e.getName().equals("Iron")) {
                    assertTrue(e.getPercentageComposition() > 0.0,
                            "Iron percentage should be greater than zero.");
                }
            }
        }
    }

    @Test
    void testGetUniformDistribution() {
        // Arrange
        ArrayList<Element> baseComp = new ArrayList<>();
        baseComp.add(new Element("Carbon", "C", 0.2, 0.1, 0.3));
        baseComp.add(new Element("Iron", "Fe", 99.8, 99.5, 99.9));

        double varyBy = 0.05;
        double limit = 0.1;
        ArrayList<ArrayList<Element>> results = new ArrayList<>();

        // Act
        compositionalVariations.getUniformDistribution(
                0, baseComp, varyBy, limit, 0.0, new ArrayList<>(), results);

        // Assert
        assertFalse(results.isEmpty(), "Should generate at least one variation");

        for (ArrayList<Element> variation : results) {
            assertEquals(baseComp.size(), variation.size(),
                    "Each variation should have the same number of elements");

            double sum = variation.stream()
                    .mapToDouble(Element::getPercentageComposition)
                    .sum();
            assertEquals(100.0, sum, 0.001, "Element percentages should sum to 100%");
        }
    }

    /*
     * // Old commented out test for uniformSampling
     * 
     * /*
     * // Old commented out test for uniformSampling
     */

    // Additional edge case tests
    @Test
    void testGaussianSampling_ZeroDelta() {
        // Arrange
        ArrayList<Element> baseComp = new ArrayList<>();
        baseComp.add(new Element("Carbon", "C", 0.2, 0.1, 0.3));
        baseComp.add(new Element("Iron", "Fe", 99.8, 99.5, 99.9));

        double maxDelta = 0.0; // Zero delta should return compositions very close to original
        int samples = 3;
        ArrayList<ArrayList<Element>> variations = new ArrayList<>();

        // Act
        compositionalVariations.gaussianSampling(baseComp, maxDelta, samples, variations);

        // Assert
        assertEquals(samples, variations.size());

        for (ArrayList<Element> variation : variations) {
            assertEquals(baseComp.size(), variation.size());
            // With zero delta, values should be very close to original
            double carbonPercent = variation.get(0).getPercentageComposition();
            double ironPercent = variation.get(1).getPercentageComposition();

            // Allow a very small tolerance due to normalization
            assertEquals(0.2, carbonPercent, 0.01);
            assertEquals(99.8, ironPercent, 0.01);
        }
    }
}
