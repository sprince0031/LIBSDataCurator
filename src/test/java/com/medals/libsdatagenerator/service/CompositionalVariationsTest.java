import com.medals.libsdatagenerator.controller.LIBSDataGenConstants; // Added for static block
import com.medals.libsdatagenerator.service.Element; // Assuming this is the correct location
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
// import java.util.HashMap; // Not directly used if LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK is already a map

class CompositionalVariationsTest {

    private final CompositionalVariations cv = CompositionalVariations.getInstance();
    private static final double DELTA = 0.001; // For floating point comparisons

    @BeforeAll
    static void setupConstants() {
        // This ensures that the map is initialized if it's null, and then adds values.
        // Note: If ELEMENT_STD_DEVS_FALLBACK is declared `final` and initialized, e.g. `final = new HashMap<>()`,
        // then it cannot be set to a new HashMap if it's null (which it wouldn't be in that case).
        // If it's `static final Map<String, Double> ELEMENT_STD_DEVS_FALLBACK = someMethodToLoad();`
        // this approach might also have issues.
        // This setup assumes it's either non-final, or final but not yet initialized,
        // or initialized as a mutable map.
        // The most common case is a static final initialized map: `static final Map<String, Double> INSTANCE = new HashMap<>();`
        // In this case, .putIfAbsent() is fine.

        // Ensure the map itself is not null (it shouldn't be if it's a static final field in LIBSDataGenConstants)
        if (LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK == null) {
             // This case is problematic. If it's null, it means it's likely not initialized or not public static.
             // For tests to run, this map MUST be available and mutable or pre-populated.
             // System.err.println("LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK is null. Cannot run some tests.");
             // Alternatively, throw an IllegalStateException if this is critical for tests.
             // For now, we hope it's a non-null, mutable map.
        }

        // Populate with default values if not present, assuming the map instance exists.
        // If ELEMENT_STD_DEVS_FALLBACK is null, this will throw a NullPointerException.
        // This is an unavoidable dependency for these tests.
        try {
            LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.putIfAbsent("A", 1.0);
            LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.putIfAbsent("B", 1.0);
            LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.putIfAbsent("C", 1.0);
            LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.putIfAbsent("Fe", 1.0);
            LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.putIfAbsent("Cr", 1.0);
        } catch (NullPointerException e) {
            System.err.println("Failed to populate LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK. It might be null.");
            System.err.println("Gaussian sampling tests might fail or be unreliable.");
            // Depending on test policy, might want to throw here to make test failure explicit.
            // throw new IllegalStateException("LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK is null, cannot setup test constants.", e);
        }
    }

    private double sumComposition(List<Element> composition) {
        double sum = 0;
        for (Element el : composition) {
            sum += el.getPercentageComposition();
        }
        return sum;
    }

    @Test
    void testGaussianSampling_respectsMinMaxConstraints() {
        ArrayList<Element> baseComp = new ArrayList<>();
        baseComp.add(new Element("A", "A", 50.0, 45.0, 55.0, 50.0));
        baseComp.add(new Element("B", "B", 30.0, 28.0, 32.0, 30.0));
        baseComp.add(new Element("C", "C", 20.0, 15.0, 25.0, 20.0));

        ArrayList<ArrayList<Element>> variations = new ArrayList<>();
        cv.gaussianSampling(baseComp, 5.0, 100, variations);

        assertEquals(100, variations.size(), "Should generate the requested number of samples");

        for (ArrayList<Element> variant : variations) {
            assertEquals(3, variant.size());
            double totalPercentage = 0;
            for (int i = 0; i < variant.size(); i++) {
                Element elVar = variant.get(i);
                Element elBase = baseComp.get(i);

                assertNotNull(elVar.getPercentageComposition(), "Percentage should not be null for " + elVar.getSymbol());
                assertTrue(elVar.getPercentageComposition() >= 0, "Percentage should be non-negative for " + elVar.getSymbol());
                if (elBase.getPercentageCompositionMin() != null) {
                    assertTrue(elVar.getPercentageComposition() >= elBase.getPercentageCompositionMin() - DELTA,
                            elVar.getSymbol() + " value " + elVar.getPercentageComposition() + " below min " + elBase.getPercentageCompositionMin());
                }
                if (elBase.getPercentageCompositionMax() != null) {
                    assertTrue(elVar.getPercentageComposition() <= elBase.getPercentageCompositionMax() + DELTA,
                            elVar.getSymbol() + " value " + elVar.getPercentageComposition() + " above max " + elBase.getPercentageCompositionMax());
                }
                totalPercentage += elVar.getPercentageComposition();
            }
            assertEquals(100.0, totalPercentage, DELTA, "Sum of percentages should be 100 for variant: " + variant);
        }
    }

    @Test
    void testGaussianSampling_withTightConstraints() {
        ArrayList<Element> baseComp = new ArrayList<>();
        baseComp.add(new Element("A", "A", 50.0, 49.9, 50.1, 50.0));
        baseComp.add(new Element("B", "B", 50.0, 40.0, 60.0, 50.0));

        ArrayList<ArrayList<Element>> variations = new ArrayList<>();
        cv.gaussianSampling(baseComp, 2.0, 50, variations);

        assertEquals(50, variations.size());
        for (ArrayList<Element> variant : variations) {
            Element elA = variant.get(0);
            assertTrue(elA.getPercentageComposition() >= 49.9 - DELTA && elA.getPercentageComposition() <= 50.1 + DELTA, "Element A value " + elA.getPercentageComposition() + " out of tight range [49.9, 50.1]");
            assertEquals(100.0, sumComposition(variant), DELTA, "Sum of percentages should be 100 for variant: " + variant);
        }
    }

    @Test
    void testGaussianSampling_elementFixedByMinMax() {
        ArrayList<Element> baseComp = new ArrayList<>();
        baseComp.add(new Element("Fe", "Fe", 70.0, 70.0, 70.0, 70.0));
        baseComp.add(new Element("Cr", "Cr", 30.0, 20.0, 40.0, 30.0));

        ArrayList<ArrayList<Element>> variations = new ArrayList<>();
        cv.gaussianSampling(baseComp, 5.0, 50, variations);

        assertEquals(50, variations.size());
        for (ArrayList<Element> variant : variations) {
            Element elFe = variant.get(0);
            Element elCr = variant.get(1);
            assertEquals(70.0, elFe.getPercentageComposition(), DELTA, "Fe should be fixed at 70% in variant: " + variant);
            assertTrue(elCr.getPercentageComposition() >= 20.0 - DELTA && elCr.getPercentageComposition() <= 40.0 + DELTA, "Cr value " + elCr.getPercentageComposition() + " out of range [20,40] in variant: " + variant);
            assertEquals(100.0, sumComposition(variant), DELTA, "Sum of percentages should be 100 for variant: " + variant);
        }
    }

    @Test
    void testGetUniformDistribution_respectsMinMaxConstraints() {
        ArrayList<Element> originalComp = new ArrayList<>();
        originalComp.add(new Element("X", "X", 50.0, 48.0, 52.0, 50.0));
        originalComp.add(new Element("Y", "Y", 30.0, 28.0, 32.0, 30.0));
        originalComp.add(new Element("Z", "Z", 20.0, 18.0, 22.0, 20.0));

        ArrayList<ArrayList<Element>> results = new ArrayList<>();
        cv.getUniformDistribution(0, originalComp, 0.5, 5.0, 0.0, new ArrayList<>(), results);

        assertTrue(results.size() > 0, "Should generate some results for uniform distribution");

        for (ArrayList<Element> result : results) {
            assertEquals(3, result.size());
            double totalPercentage = 0;
            for (int i = 0; i < result.size(); i++) {
                Element elRes = result.get(i);
                Element elOrig = originalComp.get(i);

                assertNotNull(elRes.getPercentageComposition(), "Percentage should not be null for " + elRes.getSymbol());
                assertTrue(elRes.getPercentageComposition() >= 0, "Percentage should be non-negative for " + elRes.getSymbol());
                if (elOrig.getPercentageCompositionMin() != null) {
                    assertTrue(elRes.getPercentageComposition() >= elOrig.getPercentageCompositionMin() - DELTA,
                            elRes.getSymbol() + " value " + elRes.getPercentageComposition() + " below min " + elOrig.getPercentageCompositionMin());
                }
                if (elOrig.getPercentageCompositionMax() != null) {
                    assertTrue(elRes.getPercentageComposition() <= elOrig.getPercentageCompositionMax() + DELTA,
                            elRes.getSymbol() + " value " + elRes.getPercentageComposition() + " above max " + elOrig.getPercentageCompositionMax());
                }
                totalPercentage += elRes.getPercentageComposition();
            }
            assertEquals(100.0, totalPercentage, DELTA, "Sum of percentages should be 100 for result: " + result);
        }
    }

    @Test
    void testGetUniformDistribution_lastElementCalculationWithConstraints() {
        ArrayList<Element> originalComp = new ArrayList<>();
        originalComp.add(new Element("A", "A", 40.0, 35.0, 42.0, 40.0));
        originalComp.add(new Element("B", "B", 30.0, 25.0, 32.0, 30.0));
        originalComp.add(new Element("C", "C", 30.0, 28.0, 33.0, 30.0));

        ArrayList<ArrayList<Element>> results = new ArrayList<>();
        cv.getUniformDistribution(0, originalComp, 1.0, 5.0, 0.0, new ArrayList<>(), results);

        assertTrue(results.size() > 0, "Should find valid combinations for last element constraints.");

        for (ArrayList<Element> result : results) {
            Element elC = result.get(2);
            assertTrue(elC.getPercentageComposition() >= (28.0 - DELTA) && elC.getPercentageComposition() <= (33.0 + DELTA),
                    "Element C (" + elC.getPercentageComposition() + ") out of its specific range [28, 33] in result: " + result);
            assertEquals(100.0, sumComposition(result), DELTA, "Sum must be 100 for result: " + result);
        }
    }

    @Test
    void testGetUniformDistribution_noValidResultsDueToStrictConstraints() {
        ArrayList<Element> originalComp = new ArrayList<>();
        originalComp.add(new Element("A", "A", 10.0,  8.0, 12.0, 10.0));
        originalComp.add(new Element("B", "B", 10.0,  8.0, 12.0, 10.0));
        originalComp.add(new Element("C", "C", 80.0, 85.0, 90.0, 80.0));

        ArrayList<ArrayList<Element>> results = new ArrayList<>();
        cv.getUniformDistribution(0, originalComp, 0.1, 3.0, 0.0, new ArrayList<>(), results);

        assertEquals(0, results.size(), "Should generate no results due to conflicting constraints for uniform distribution.");
    }
}
