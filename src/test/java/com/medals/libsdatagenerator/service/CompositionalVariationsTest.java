package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.model.Element; // Assuming this is the correct location
import com.medals.libsdatagenerator.model.ElementStatistics;
import com.medals.libsdatagenerator.model.SeriesStatistics;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.matweb.SeriesInput;
import com.medals.libsdatagenerator.sampler.DirichletSampler;
import com.medals.libsdatagenerator.sampler.GaussianSampler;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void testDirichletSampling_reproducibilityWithSeed() {
        // Note: This test verifies seeding behavior when Dirichlet sampler falls back to Gaussian
        // since we don't have series statistics available in the test environment.
        // The seed propagation through Dirichlet -> Gaussian is tested here.
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

        // Create MaterialGrade with null parent series (will trigger fallback to Gaussian)
        MaterialGrade materialGrade = new MaterialGrade(baseComp, null, null);
        
        // First run with seed
        List<List<Element>> variations1 = new ArrayList<>();
        DirichletSampler.getInstance().sample(materialGrade, 10, variations1, SEED);
        
        // Second run with same seed
        List<List<Element>> variations2 = new ArrayList<>();
        DirichletSampler.getInstance().sample(materialGrade, 10, variations2, SEED);
        
        // Both runs should produce identical results due to seed propagation
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
    void testDirichletSampling_differentSeedsProduceDifferentResults() {
        // Note: This test verifies seeding behavior when Dirichlet sampler falls back to Gaussian
        // since we don't have series statistics available in the test environment.
        // The seed propagation through Dirichlet -> Gaussian is tested here.
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

        // Create MaterialGrade with null parent series (will trigger fallback to Gaussian)
        MaterialGrade materialGrade = new MaterialGrade(baseComp, null, null);
        
        // First run with seed 42
        List<List<Element>> variations1 = new ArrayList<>();
        DirichletSampler.getInstance().sample(materialGrade, 10, variations1, 42L);
        
        // Second run with seed 123
        List<List<Element>> variations2 = new ArrayList<>();
        DirichletSampler.getInstance().sample(materialGrade, 10, variations2, 123L);
        
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

    @Test
    void testDirichletSampling_respectsBaseElementRanges() {
        List<Element> baseComp = new ArrayList<>();
        baseComp.add(new Element("Iron", "Fe", 70.0, 69.9, 70.1, 70.0));
        baseComp.add(new Element("Chromium", "Cr", 20.0, 19.9, 20.1, 20.0));
        baseComp.add(new Element("Nickel", "Ni", 10.0, 9.9, 10.1, 10.0));

        SeriesInput parentSeries = new SeriesInput("test-series", List.of("mat-guid"), "overview-guid");
        MaterialGrade materialGrade = new MaterialGrade(baseComp, "mat-guid", parentSeries);

        SeriesStatistics overviewStats = new SeriesStatistics("test-series", "overview-guid");
        overviewStats.addElementStatisticsToComposition(new ElementStatistics("Fe", 70.0, 50, 0.0, 100.0));
        overviewStats.addElementStatisticsToComposition(new ElementStatistics("Cr", 20.0, 50, 0.0, 100.0));
        overviewStats.addElementStatisticsToComposition(new ElementStatistics("Ni", 10.0, 50, 0.0, 100.0));
        materialGrade.setOverviewStatistics(overviewStats);

        List<List<Element>> variations = new ArrayList<>();
        DirichletSampler.getInstance().sample(materialGrade, 25, variations, SEED);

        assertEquals(25, variations.size(), "Should generate requested Dirichlet samples within tight base ranges");
        for (List<Element> variation : variations) {
            double total = 0.0;
            for (int i = 0; i < variation.size(); i++) {
                Element generated = variation.get(i);
                Element base = baseComp.get(i);
                assertTrue(generated.getPercentageComposition() >= base.getMin() - DELTA,
                        generated.getSymbol() + " below base min");
                assertTrue(generated.getPercentageComposition() <= base.getMax() + DELTA,
                        generated.getSymbol() + " above base max");
                total += generated.getPercentageComposition();
            }
            assertEquals(100.0, total, 0.01, "Generated sample should sum to 100%");
        }
    }
}
