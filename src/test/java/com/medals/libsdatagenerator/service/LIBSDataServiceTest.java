package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.VariationMode;
import com.medals.libsdatagenerator.model.UserInputConfig;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.apache.commons.cli.CommandLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions; // For Gaussian test constant checks
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class LIBSDataServiceTest {

    private final CompositionalVariations compVariations = CompositionalVariations.getInstance();
    private static final double DELTA = 0.01; // Increased tolerance slightly for sums after variation

    private boolean compositionsDiffer(List<Element> comp1, List<Element> comp2) {
        if (comp1.size() != comp2.size()) return true;
        for (int i = 0; i < comp1.size(); i++) {
            // Compare symbols to ensure elements are in the same order or match them up
            if (!comp1.get(i).getSymbol().equals(comp2.get(i).getSymbol())) return true; // Should not happen if order is preserved
            if (Math.abs(comp1.get(i).getPercentageComposition() - comp2.get(i).getPercentageComposition()) > DELTA) {
                return true;
            }
        }
        return false;
    }

    private double sumComposition(List<Element> composition) {
        double sum = 0;
        for (Element el : composition) {
            sum += el.getPercentageComposition();
        }
        return sum;
    }

    // Helper to check if LIBSDataGenConstants are populated for test symbols
    private void assumeGaussianConstantsPresent(String... symbols) {
        Map<String, Double> stdDevs = LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK;
        if (stdDevs == null) {
            // This will cause the test to be skipped.
            Assumptions.assumeTrue(false, "ELEMENT_STD_DEVS_FALLBACK map is null. Skipping Gaussian test.");
            return;
        }
        for (String symbol : symbols) {
            Assumptions.assumeTrue(stdDevs.containsKey(symbol),
                "Skipping Gaussian test: Symbol '" + symbol + "' not in ELEMENT_STD_DEVS_FALLBACK map.");
        }
    }

    @Test
    void testGenerateVariations_allFixed_gaussianFallback() {
        assumeGaussianConstantsPresent("A", "B"); // Gaussian tests need these symbols in constants

        List<Element> originalComposition = new ArrayList<>();
        originalComposition.add(new Element("A", "A", 50.0, 50.0, 50.0, 50.0));
        originalComposition.add(new Element("B", "B", 50.0, 50.0, 50.0, 50.0));

        MaterialGrade materialGrade = new MaterialGrade(originalComposition, null, null);
        CommandLine cmd = CommonUtils.getInstance().getTerminalArgHandler(new String[]{
                "-"+LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT, "A-50,B-#",
                "-"+LIBSDataGenConstants.CMD_OPT_VARY_BY_SHORT, "0.1",
                "-"+LIBSDataGenConstants.CMD_OPT_MAX_DELTA_SHORT, "5.0",
                "-"+LIBSDataGenConstants.CMD_OPT_VAR_MODE_SHORT, String.valueOf(VariationMode.GAUSSIAN),
                "-"+LIBSDataGenConstants.CMD_OPT_NUM_VARS_SHORT, "5"});
        UserInputConfig config = new UserInputConfig(cmd);
        // samples = 10, so 1 original + 10 variations expected if fallback works
        List<List<Element>> compositions = compVariations.generateCompositionalVariations(materialGrade, config);

        // Expected size is specified number of samples with original included
        assertEquals(5, compositions.size(), "Expected size is specified number of samples with original included");

        boolean foundDifferent = false;
        for (int i = 1; i < compositions.size(); i++) { // Start from 1 to skip original
            List<Element> variedComp = compositions.get(i);
            assertEquals(originalComposition.size(), variedComp.size(), "Varied composition should have same number of elements.");
            assertEquals(100.0, sumComposition(variedComp), DELTA, "Sum of varied composition should be ~100.");
            if (compositionsDiffer(originalComposition, variedComp)) {
                foundDifferent = true;
            }
        }
        assertTrue(foundDifferent, "Expected at least one generated composition to differ from the all-fixed original due to Gaussian fallback.");
    }

    @Test
    void testGenerateVariations_mixedFixedAndVariable_gaussian() {
        assumeGaussianConstantsPresent("Fe", "Cr");

        List<Element> originalComposition = new ArrayList<>();
        originalComposition.add(new Element("Fe", "Fe", 70.0, 70.0, 70.0, 70.0)); // Fixed
        originalComposition.add(new Element("Cr", "Cr", 30.0, 25.0, 35.0, 30.0)); // Variable

        MaterialGrade materialGrade = new MaterialGrade(originalComposition, null, null);
        CommandLine cmd = CommonUtils.getInstance().getTerminalArgHandler(new String[]{
                "-"+LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT, "Cr-25:35,Fe-#",
                "-"+LIBSDataGenConstants.CMD_OPT_VARY_BY_SHORT, "0.5", // varyBy=0.5
                "-"+LIBSDataGenConstants.CMD_OPT_MAX_DELTA_SHORT, "2.0", // limit=2 for Cr
                "-"+LIBSDataGenConstants.CMD_OPT_VAR_MODE_SHORT, String.valueOf(VariationMode.GAUSSIAN),
                "-"+LIBSDataGenConstants.CMD_OPT_NUM_VARS_SHORT, "5"});
        UserInputConfig config = new UserInputConfig(cmd);
        List<List<Element>> compositions = compVariations.generateCompositionalVariations(materialGrade, config);

        // Expected size is specified number of samples with original included
        assertEquals(5, compositions.size(), "Expected size is specified number of samples with original included");
        for (int i = 1; i < compositions.size(); i++) { // Skip original
            List<Element> variedComp = compositions.get(i);
            assertEquals(2, variedComp.size());
            Element fe = variedComp.stream().filter(e -> e.getSymbol().equals("Fe")).findFirst().orElse(null);
            Element cr = variedComp.stream().filter(e -> e.getSymbol().equals("Cr")).findFirst().orElse(null);

            assertNotNull(fe, "Fe element should be present in varied composition.");
            assertNotNull(cr, "Cr element should be present in varied composition.");

            assertEquals(70.0, fe.getPercentageComposition(), DELTA, "Fixed element Fe should remain at 70%");
            assertTrue(cr.getPercentageComposition() >= (25.0 - DELTA) && cr.getPercentageComposition() <= (35.0 + DELTA),
                "Variable element Cr (" + cr.getPercentageComposition() + ") should be within its original range [25,35]");
            assertEquals(100.0, sumComposition(variedComp), DELTA, "Sum of varied composition should be ~100.");
        }
    }
}
