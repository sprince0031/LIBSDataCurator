package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.model.Element; // Assuming this is the correct location
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.sampler.GaussianSampler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class CompositionalVariationsTest {

    private final CompositionalVariations cv = CompositionalVariations.getInstance();
    private static final double DELTA = 0.001; // For floating point comparisons
    private static final Long SEED = 42L;

    private double sumComposition(List<Element> composition) {
        double sum = 0;
        for (Element el : composition) {
            sum += el.getPercentageComposition();
        }
        return sum;
    }

    @Test
    void testGaussianSampling_respectsMinMaxConstraints() {
        Assumptions.assumeTrue(
            com.medals.libsdatagenerator.controller.LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.containsKey("A"),
            "Skipping test: Symbol 'A' not in ELEMENT_STD_DEVS_FALLBACK map."
        );
        Assumptions.assumeTrue(
            com.medals.libsdatagenerator.controller.LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.containsKey("B"),
            "Skipping test: Symbol 'B' not in ELEMENT_STD_DEVS_FALLBACK map."
        );
        Assumptions.assumeTrue(
            com.medals.libsdatagenerator.controller.LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.containsKey("C"),
            "Skipping test: Symbol 'C' not in ELEMENT_STD_DEVS_FALLBACK map."
        );
        List<Element> baseComp = new ArrayList<>();
        baseComp.add(new Element("A", "A", 50.0, 45.0, 55.0, 50.0));
        baseComp.add(new Element("B", "B", 30.0, 28.0, 32.0, 30.0));
        baseComp.add(new Element("C", "C", 20.0, 15.0, 25.0, 20.0));

        MaterialGrade materialGrade = new MaterialGrade(baseComp, null, null);
        List<List<Element>> variations = new ArrayList<>();
        GaussianSampler.getInstance().sample(materialGrade, 100, variations, SEED);

        assertEquals(100, variations.size(), "Should generate the requested number of samples");

        for (List<Element> variant : variations) {
            assertEquals(3, variant.size());
            double totalPercentage = 0;
            for (int i = 0; i < variant.size(); i++) {
                Element elVar = variant.get(i);
                Element elBase = baseComp.get(i);

                assertNotNull(elVar.getPercentageComposition(), "Percentage should not be null for " + elVar.getSymbol());
                assertTrue(elVar.getPercentageComposition() >= 0, "Percentage should be non-negative for " + elVar.getSymbol());
                if (elBase.getMin() != null) {
                    assertTrue(elVar.getPercentageComposition() >= elBase.getMin() - DELTA,
                            elVar.getSymbol() + " value " + elVar.getPercentageComposition() + " below min " + elBase.getMin());
                }
                if (elBase.getMax() != null) {
                    assertTrue(elVar.getPercentageComposition() <= elBase.getMax() + DELTA,
                            elVar.getSymbol() + " value " + elVar.getPercentageComposition() + " above max " + elBase.getMax());
                }
                totalPercentage += elVar.getPercentageComposition();
            }
            assertEquals(100.0, totalPercentage, DELTA, "Sum of percentages should be 100 for variant: " + variant);
        }
    }

    @Test
    void testGaussianSampling_withTightConstraints() {
        Assumptions.assumeTrue(
            com.medals.libsdatagenerator.controller.LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.containsKey("A"),
            "Skipping test: Symbol 'A' not in ELEMENT_STD_DEVS_FALLBACK map."
        );
        Assumptions.assumeTrue(
            com.medals.libsdatagenerator.controller.LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.containsKey("B"),
            "Skipping test: Symbol 'B' not in ELEMENT_STD_DEVS_FALLBACK map."
        );
        List<Element> baseComp = new ArrayList<>();
        baseComp.add(new Element("A", "A", 50.0, 49.9, 50.1, 50.0));
        baseComp.add(new Element("B", "B", 50.0, 40.0, 60.0, 50.0));

        MaterialGrade materialGrade = new MaterialGrade(baseComp, null, null);
        List<List<Element>> variations = new ArrayList<>();
        GaussianSampler.getInstance().sample(materialGrade, 50, variations, SEED);

        assertEquals(50, variations.size());
        for (List<Element> variant : variations) {
            Element elA = variant.get(0);
            assertTrue(elA.getPercentageComposition() >= 49.9 - DELTA && elA.getPercentageComposition() <= 50.1 + DELTA, "Element A value " + elA.getPercentageComposition() + " out of tight range [49.9, 50.1]");
            assertEquals(100.0, sumComposition(variant), DELTA, "Sum of percentages should be 100 for variant: " + variant);
        }
    }

//    @Test
//    void testGaussianSampling_elementFixedByMinMax() {
//        Assumptions.assumeTrue(
//            com.medals.libsdatagenerator.controller.LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.containsKey("Fe"),
//            "Skipping test: Symbol 'Fe' not in ELEMENT_STD_DEVS_FALLBACK map."
//        );
//        Assumptions.assumeTrue(
//            com.medals.libsdatagenerator.controller.LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.containsKey("Cr"),
//            "Skipping test: Symbol 'Cr' not in ELEMENT_STD_DEVS_FALLBACK map."
//        );
//        List<Element> baseComp = new ArrayList<>();
//        baseComp.add(new Element("Fe", "Fe", 70.0, 70.0, 70.0, 70.0));
//        baseComp.add(new Element("Cr", "Cr", 30.0, 20.0, 40.0, 30.0));
//
//        List<List<Element>> variations = new ArrayList<>();
//        cv.gaussianSampler(baseComp, 5.0, 50, variations);
//
//        assertEquals(50, variations.size());
//        for (List<Element> variant : variations) {
//            Element elFe = variant.get(0);
//            Element elCr = variant.get(1);
//            assertEquals(70.0, elFe.getPercentageComposition(), DELTA, "Fe should be fixed at 70% in variant: " + variant);
//            assertTrue(elCr.getPercentageComposition() >= 20.0 - DELTA && elCr.getPercentageComposition() <= 40.0 + DELTA, "Cr value " + elCr.getPercentageComposition() + " out of range [20,40] in variant: " + variant);
//            assertEquals(100.0, sumComposition(variant), DELTA, "Sum of percentages should be 100 for variant: " + variant);
//        }
//    }

    @Test
    void testGetUniformDistribution_respectsMinMaxConstraints() {
        List<Element> originalComp = new ArrayList<>();
        originalComp.add(new Element("X", "X", 50.0, 48.0, 52.0, 50.0));
        originalComp.add(new Element("Y", "Y", 30.0, 28.0, 32.0, 30.0));
        originalComp.add(new Element("Z", "Z", 20.0, 18.0, 22.0, 20.0));

        List<List<Element>> results = new ArrayList<>();
        cv.getUniformDistribution(0, originalComp, 0.5, 5.0, 0.0, new ArrayList<>(), results);

        assertTrue(results.size() > 0, "Should generate some results for uniform distribution");

        for (List<Element> result : results) {
            assertEquals(3, result.size());
            double totalPercentage = 0;
            for (int i = 0; i < result.size(); i++) {
                Element elRes = result.get(i);
                Element elOrig = originalComp.get(i);

                assertNotNull(elRes.getPercentageComposition(), "Percentage should not be null for " + elRes.getSymbol());
                assertTrue(elRes.getPercentageComposition() >= 0, "Percentage should be non-negative for " + elRes.getSymbol());
                if (elOrig.getMin() != null) {
                    assertTrue(elRes.getPercentageComposition() >= elOrig.getMin() - DELTA,
                            elRes.getSymbol() + " value " + elRes.getPercentageComposition() + " below min " + elOrig.getMin());
                }
                if (elOrig.getMax() != null) {
                    assertTrue(elRes.getPercentageComposition() <= elOrig.getMax() + DELTA,
                            elRes.getSymbol() + " value " + elRes.getPercentageComposition() + " above max " + elOrig.getMax());
                }
                totalPercentage += elRes.getPercentageComposition();
            }
            assertEquals(100.0, totalPercentage, DELTA, "Sum of percentages should be 100 for result: " + result);
        }
    }

    @Test
    void testGetUniformDistribution_lastElementCalculationWithConstraints() {
        List<Element> originalComp = new ArrayList<>();
        originalComp.add(new Element("A", "A", 40.0, 35.0, 42.0, 40.0));
        originalComp.add(new Element("B", "B", 30.0, 25.0, 32.0, 30.0));
        originalComp.add(new Element("C", "C", 30.0, 28.0, 33.0, 30.0));

        List<List<Element>> results = new ArrayList<>();
        cv.getUniformDistribution(0, originalComp, 1.0, 5.0, 0.0, new ArrayList<>(), results);

        assertTrue(results.size() > 0, "Should find valid combinations for last element constraints.");

        for (List<Element> result : results) {
            Element elC = result.get(2);
            assertTrue(elC.getPercentageComposition() >= (28.0 - DELTA) && elC.getPercentageComposition() <= (33.0 + DELTA),
                    "Element C (" + elC.getPercentageComposition() + ") out of its specific range [28, 33] in result: " + result);
            assertEquals(100.0, sumComposition(result), DELTA, "Sum must be 100 for result: " + result);
        }
    }

    @Test
    void testGetUniformDistribution_noValidResultsDueToStrictConstraints() {
        List<Element> originalComp = new ArrayList<>();
        originalComp.add(new Element("A", "A", 10.0,  8.0, 12.0, 10.0));
        originalComp.add(new Element("B", "B", 10.0,  8.0, 12.0, 10.0));
        originalComp.add(new Element("C", "C", 80.0, 85.0, 90.0, 80.0));

        List<List<Element>> results = new ArrayList<>();
        cv.getUniformDistribution(0, originalComp, 0.1, 3.0, 0.0, new ArrayList<>(), results);

        assertEquals(0, results.size(), "Should generate no results due to conflicting constraints for uniform distribution.");
    }

    @Test
    void testGaussianSampling_reproducibilityWithSeed() {
        Assumptions.assumeTrue(
            com.medals.libsdatagenerator.controller.LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.containsKey("Fe"),
            "Skipping test: Symbol 'Fe' not in ELEMENT_STD_DEVS_FALLBACK map."
        );
        Assumptions.assumeTrue(
            com.medals.libsdatagenerator.controller.LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.containsKey("C"),
            "Skipping test: Symbol 'C' not in ELEMENT_STD_DEVS_FALLBACK map."
        );
        
        List<Element> baseComp = new ArrayList<>();
        baseComp.add(new Element("Fe", "Fe", 80.0, 75.0, 85.0, 80.0));
        baseComp.add(new Element("C", "C", 20.0, 15.0, 25.0, 20.0));

        MaterialGrade materialGrade = new MaterialGrade(baseComp, null, null);
        
        // First run with seed
        List<List<Element>> variations1 = new ArrayList<>();
        GaussianSampler.getInstance().sample(materialGrade, 10, variations1, SEED);
        
        // Second run with same seed
        List<List<Element>> variations2 = new ArrayList<>();
        GaussianSampler.getInstance().sample(materialGrade, 10, variations2, SEED);
        
        // Both runs should produce identical results
        assertEquals(variations1.size(), variations2.size(), "Both runs should generate the same number of samples");
        
        for (int i = 0; i < variations1.size(); i++) {
            List<Element> var1 = variations1.get(i);
            List<Element> var2 = variations2.get(i);
            
            assertEquals(var1.size(), var2.size(), "Sample " + i + " should have same number of elements");
            
            for (int j = 0; j < var1.size(); j++) {
                Element el1 = var1.get(j);
                Element el2 = var2.get(j);
                
                assertEquals(el1.getSymbol(), el2.getSymbol(), "Sample " + i + " element " + j + " should have same symbol");
                assertEquals(el1.getPercentageComposition(), el2.getPercentageComposition(), DELTA,
                    "Sample " + i + " element " + el1.getSymbol() + " should have same percentage");
            }
        }
    }

    @Test
    void testGaussianSampling_differentSeedsProduceDifferentResults() {
        Assumptions.assumeTrue(
            com.medals.libsdatagenerator.controller.LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.containsKey("Fe"),
            "Skipping test: Symbol 'Fe' not in ELEMENT_STD_DEVS_FALLBACK map."
        );
        Assumptions.assumeTrue(
            com.medals.libsdatagenerator.controller.LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.containsKey("C"),
            "Skipping test: Symbol 'C' not in ELEMENT_STD_DEVS_FALLBACK map."
        );
        
        List<Element> baseComp = new ArrayList<>();
        baseComp.add(new Element("Fe", "Fe", 80.0, 75.0, 85.0, 80.0));
        baseComp.add(new Element("C", "C", 20.0, 15.0, 25.0, 20.0));

        MaterialGrade materialGrade = new MaterialGrade(baseComp, null, null);
        
        // First run with seed 42
        List<List<Element>> variations1 = new ArrayList<>();
        GaussianSampler.getInstance().sample(materialGrade, 10, variations1, 42L);
        
        // Second run with seed 123
        List<List<Element>> variations2 = new ArrayList<>();
        GaussianSampler.getInstance().sample(materialGrade, 10, variations2, 123L);
        
        // At least one sample should be different
        boolean foundDifference = false;
        for (int i = 0; i < Math.min(variations1.size(), variations2.size()); i++) {
            List<Element> var1 = variations1.get(i);
            List<Element> var2 = variations2.get(i);
            
            for (int j = 0; j < var1.size(); j++) {
                Element el1 = var1.get(j);
                Element el2 = var2.get(j);
                
                if (Math.abs(el1.getPercentageComposition() - el2.getPercentageComposition()) > DELTA) {
                    foundDifference = true;
                    break;
                }
            }
            if (foundDifference) break;
        }
        
        assertTrue(foundDifference, "Different seeds should produce different results");
    }
}
