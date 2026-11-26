package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.model.ElementStatistics;
import com.medals.libsdatagenerator.model.SeriesStatistics;
import org.apache.commons.math3.distribution.NormalDistribution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class ConcentrationParameterEstimatorTest {

    private final ConcentrationParameterEstimator estimator = new ConcentrationParameterEstimator();
    private static final String[] FE_CR_NI = {"Fe", "Cr", "Ni"};

    private SeriesStatistics buildSeries(
            double feAvg, double feMin, double feMax,
            double crAvg, double crMin, double crMax,
            double niAvg, double niMin, double niMax,
            int gradeCount) {
        SeriesStatistics stats = new SeriesStatistics("Test Steel Series", "test-guid");
        stats.addElementStatisticsToComposition(new ElementStatistics("Fe", feAvg, gradeCount, feMin, feMax));
        stats.addElementStatisticsToComposition(new ElementStatistics("Cr", crAvg, gradeCount, crMin, crMax));
        stats.addElementStatisticsToComposition(new ElementStatistics("Ni", niAvg, gradeCount, niMin, niMax));
        return stats;
    }

    @Test
    @DisplayName("Total Dirichlet concentration alpha_0 increases strictly monotonically as empirical alloy spread narrows")
    void testAlpha0IncreasesStrictlyMonotonicallyAsSpreadNarrows() {
        int gradeCount = 50;
        SeriesStatistics wide = buildSeries(
                70.0, 50.0, 90.0,
                20.0, 10.0, 30.0,
                10.0, 2.0, 18.0,
                gradeCount
        );
        SeriesStatistics medium = buildSeries(
                70.0, 60.0, 80.0,
                20.0, 14.0, 26.0,
                10.0, 6.0, 14.0,
                gradeCount
        );
        SeriesStatistics narrow = buildSeries(
                70.0, 66.0, 74.0,
                20.0, 18.0, 22.0,
                10.0, 8.0, 12.0,
                gradeCount
        );

        double[] wideAlphas = estimator.estimateParametersForElements(wide, FE_CR_NI);
        double[] mediumAlphas = estimator.estimateParametersForElements(medium, FE_CR_NI);
        double[] narrowAlphas = estimator.estimateParametersForElements(narrow, FE_CR_NI);

        assertNotNull(wideAlphas);
        assertNotNull(mediumAlphas);
        assertNotNull(narrowAlphas);

        double alpha0Wide = Arrays.stream(wideAlphas).sum();
        double alpha0Medium = Arrays.stream(mediumAlphas).sum();
        double alpha0Narrow = Arrays.stream(narrowAlphas).sum();

        // Must NOT collapse to sqrt(gradeCount) - 1 (~6.071)
        double oldCollapsed = Math.sqrt(gradeCount) - 1.0;
        assertTrue(Math.abs(alpha0Wide - oldCollapsed) > 5.0,
                "Wide alpha_0 should not collapse to sqrt(gradeCount)-1");
        assertTrue(alpha0Narrow > alpha0Medium,
                "Narrower alloy spread must yield larger alpha_0: narrow=" + alpha0Narrow + ", medium=" + alpha0Medium);
        assertTrue(alpha0Medium > alpha0Wide,
                "Medium alloy spread must yield larger alpha_0 than wide: medium=" + alpha0Medium + ", wide=" + alpha0Wide);

        assertTrue(estimator.validateParameters(wideAlphas));
        assertTrue(estimator.validateParameters(mediumAlphas));
        assertTrue(estimator.validateParameters(narrowAlphas));
    }

    @Test
    @DisplayName("Sentinel [0, 100] balance element (Fe: 80%) does not collapse alpha_0 when alloying elements have tight explicit ranges")
    void testSentinelBalanceElementExcludedWhenExplicitRangesExist() {
        int gradeCount = 50;
        // Tight explicit alloying ranges + sentinel [0, 100] balance element Fe: 80%
        SeriesStatistics tightSeries = new SeriesStatistics("Stainless Series", "test-guid");
        tightSeries.addElementStatisticsToComposition(new ElementStatistics("Fe", 80.0, gradeCount, 0.0, 100.0));
        tightSeries.addElementStatisticsToComposition(new ElementStatistics("Cr", 18.0, gradeCount, 17.5, 18.5));
        tightSeries.addElementStatisticsToComposition(new ElementStatistics("Mn", 2.0, gradeCount, 1.8, 2.2));

        double[] tightAlphas = estimator.estimateParametersForElements(tightSeries, new String[]{"Fe", "Cr", "Mn"});
        assertNotNull(tightAlphas);
        double tightAlpha0 = Arrays.stream(tightAlphas).sum();
        assertTrue(tightAlpha0 > 500.0,
                "Sentinel [0, 100] balance element must not collapse tight alloying alpha_0; got " + tightAlpha0);
        assertTrue(estimator.validateParameters(tightAlphas));

        // Moderate explicit ranges where exact pooled value is strictly below MAX_TOTAL_CONCENTRATION (1000.0)
        SeriesStatistics moderateSeries = new SeriesStatistics("Moderate Alloy Series", "test-guid");
        moderateSeries.addElementStatisticsToComposition(new ElementStatistics("Fe", 80.0, gradeCount, 0.0, 100.0));
        moderateSeries.addElementStatisticsToComposition(new ElementStatistics("Cr", 18.0, gradeCount, 14.0, 22.0));
        moderateSeries.addElementStatisticsToComposition(new ElementStatistics("Mn", 2.0, gradeCount, 0.5, 3.5));

        double[] modAlphas = estimator.estimateParametersForElements(moderateSeries, new String[]{"Fe", "Cr", "Mn"});
        assertNotNull(modAlphas);
        double modAlpha0 = Arrays.stream(modAlphas).sum();

        double dn = 2.0 * new NormalDistribution().inverseCumulativeProbability((gradeCount - 0.375) / (gradeCount + 0.25));
        double varCr = Math.pow(0.08 / dn, 2);
        double varMn = Math.pow(0.03 / dn, 2);
        double expectedAlpha0 = ((0.18 * 0.82) + (0.02 * 0.98)) / (varCr + varMn) - 1.0;

        assertEquals(expectedAlpha0, modAlpha0, 1e-4,
                "Pooled Dirichlet ratio must be computed strictly over explicit-range elements (Cr, Mn), excluding sentinel Fe");
        assertTrue(estimator.validateParameters(modAlphas));
    }

    @Test
    @DisplayName("All-sentinel [0, 100] fallback does not cancel mu*(1-mu) and yields valid parameters")
    void testAllSentinelFallbackIsNonCancellingAndValid() {
        SeriesStatistics count10 = buildSeries(
                70.0, 0.0, 100.0,
                20.0, 0.0, 100.0,
                10.0, 0.0, 100.0,
                10
        );
        SeriesStatistics count100 = buildSeries(
                70.0, 0.0, 100.0,
                20.0, 0.0, 100.0,
                10.0, 0.0, 100.0,
                100
        );

        double[] alphas10 = estimator.estimateParametersForElements(count10, FE_CR_NI);
        double[] alphas100 = estimator.estimateParametersForElements(count100, FE_CR_NI);

        assertNotNull(alphas10);
        assertNotNull(alphas100);
        assertTrue(estimator.validateParameters(alphas10));
        assertTrue(estimator.validateParameters(alphas100));

        double sum10 = Arrays.stream(alphas10).sum();
        double sum100 = Arrays.stream(alphas100).sum();
        // Relative-spread fallback sigma = 0.15 * p does not depend on sqrt(gradeCount)-1 cancellation
        double expectedSum = ((0.7 * 0.3) + (0.2 * 0.8) + (0.1 * 0.9))
                / (Math.pow(0.15 * 0.7, 2) + Math.pow(0.15 * 0.2, 2) + Math.pow(0.15 * 0.1, 2)) - 1.0;
        assertEquals(expectedSum, sum10, 1e-4);
        assertEquals(expectedSum, sum100, 1e-4);
    }

    @Test
    @DisplayName("Fixed min==max and single-grade (gradeCount < 2) R/4 fallback behave accurately")
    void testFixedRangeAndSingleGradeFallback() {
        // Fixed composition (min == max) -> hits MIN_VARIANCE_THRESHOLD -> clamped to MAX_TOTAL_CONCENTRATION (1000.0)
        SeriesStatistics fixed = buildSeries(
                70.0, 70.0, 70.0,
                20.0, 20.0, 20.0,
                10.0, 10.0, 10.0,
                20
        );
        double[] fixedAlphas = estimator.estimateParametersForElements(fixed, FE_CR_NI);
        assertNotNull(fixedAlphas);
        assertEquals(1000.0, Arrays.stream(fixedAlphas).sum(), 1e-6);
        assertTrue(estimator.validateParameters(fixedAlphas));

        // gradeCount = 1 -> R/4 range-rule fallback
        SeriesStatistics singleGrade = buildSeries(
                70.0, 60.0, 80.0,
                20.0, 14.0, 26.0,
                10.0, 6.0, 14.0,
                1
        );
        double[] singleAlphas = estimator.estimateParametersForElements(singleGrade, FE_CR_NI);
        assertNotNull(singleAlphas);
        double varSum = Math.pow(0.20 / 4.0, 2) + Math.pow(0.12 / 4.0, 2) + Math.pow(0.08 / 4.0, 2);
        double expectedAlpha0 = ((0.7 * 0.3) + (0.2 * 0.8) + (0.1 * 0.9)) / varSum - 1.0;
        assertEquals(expectedAlpha0, Arrays.stream(singleAlphas).sum(), 1e-4);
        assertTrue(estimator.validateParameters(singleAlphas));
    }

    @Test
    @DisplayName("Flooring trace elements to MIN_ALPHA preserves sum(alphas) <= MAX_TOTAL_CONCENTRATION and validateParameters == true")
    void testTraceElementMinAlphaAdjustmentPreservesMaxTotalConcentration() {
        SeriesStatistics tightWithTrace = new SeriesStatistics("Tight Trace Series", "test-guid");
        tightWithTrace.addElementStatisticsToComposition(new ElementStatistics("Fe", 79.99, 50, 79.5, 80.5));
        tightWithTrace.addElementStatisticsToComposition(new ElementStatistics("Cr", 20.00, 50, 19.8, 20.2));
        tightWithTrace.addElementStatisticsToComposition(new ElementStatistics("P", 0.005, 50, 0.004, 0.006));
        tightWithTrace.addElementStatisticsToComposition(new ElementStatistics("S", 0.005, 50, 0.004, 0.006));

        double[] alphas = estimator.estimateParametersForElements(tightWithTrace, new String[]{"Fe", "Cr", "P", "S"});
        assertNotNull(alphas);
        for (double a : alphas) {
            assertTrue(a >= 0.1 - 1e-12, "Every alpha must meet MIN_ALPHA floor (0.1), got " + a);
        }
        double total = Arrays.stream(alphas).sum();
        assertTrue(total <= 1000.0 + 1e-9,
                "Total concentration must remain <= MAX_TOTAL_CONCENTRATION (1000.0), got " + total);
        assertTrue(estimator.validateParameters(alphas),
                "Adjusted parameters for tight alloys with trace elements must pass validateParameters()");
    }

    @Test
    @DisplayName("Invalid inputs return null and validateParameters rejects invalid arrays")
    void testInvalidInputsAndValidationEdgeCases() {
        assertNull(estimator.estimateParametersForElements(null, FE_CR_NI));
        SeriesStatistics empty = new SeriesStatistics("Empty", "guid");
        assertNull(estimator.estimateParametersForElements(empty, FE_CR_NI));

        SeriesStatistics valid = buildSeries(70, 65, 75, 20, 18, 22, 10, 8, 12, 20);
        assertNull(estimator.estimateParametersForElements(valid, null));
        assertNull(estimator.estimateParametersForElements(valid, new String[]{}));
        assertNull(estimator.estimateParametersForElements(valid, new String[]{"Au", "Ag"}));

        assertFalse(estimator.validateParameters(null));
        assertFalse(estimator.validateParameters(new double[]{}));
        assertFalse(estimator.validateParameters(new double[]{Double.NaN, 1.0}));
        assertFalse(estimator.validateParameters(new double[]{-1.0, 5.0}));
        assertFalse(estimator.validateParameters(new double[]{0.2, 0.2}));
        assertFalse(estimator.validateParameters(new double[]{600.0, 500.0}));
    }
}
