package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.model.ElementStatistics;
import com.medals.libsdatagenerator.model.SeriesStatistics;
import org.apache.commons.math3.distribution.NormalDistribution;

import java.util.Arrays;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Estimates Dirichlet concentration parameters from series statistics.
 * Uses empirical alloy composition spreads via the Wan et al. (2014) range-to-SD
 * estimator and pooled Dirichlet method-of-moments concentration estimation.
 *
 * @author Siddharth Prince | 02/06/25 15:00
 */
public class ConcentrationParameterEstimator {
    private static final Logger logger = Logger.getLogger(ConcentrationParameterEstimator.class.getName());
    private static final NormalDistribution STANDARD_NORMAL = new NormalDistribution();

    // Minimum parameter values to ensure numerical stability
    private static final double MIN_ALPHA = 0.1;
    private static final double MIN_VARIANCE_THRESHOLD = 1e-6;
    private static final double MAX_TOTAL_CONCENTRATION = 1000.0;
    private static final double MIN_TOTAL_CONCENTRATION = 1.0;
    private static final double DEFAULT_FALLBACK_CONCENTRATION = 10.0;

    // Range-to-SD conversion constants (Wan et al., 2014)
    private static final double MIN_RANGE_DIVISOR = 2.0;
    private static final double MAX_RANGE_DIVISOR = 6.0;
    private static final double SINGLE_GRADE_RANGE_DIVISOR = 4.0;
    private static final double SENTINEL_RELATIVE_STD = 0.15;

    /**
     * Estimates Dirichlet concentration parameters for specific elements only.
     *
     * @param statistics     Series statistics containing element averages, ranges, and grade counts
     * @param targetElements Array of element symbols to include in parameter estimation
     * @return Array of concentration parameters (alphas) for specified elements only
     */
    public double[] estimateParametersForElements(SeriesStatistics statistics, String[] targetElements) {
        if (statistics == null || !statistics.isValidForDirichletSampling()) {
            logger.warning("Invalid statistics provided for parameter estimation");
            return null;
        }

        if (targetElements == null || targetElements.length == 0) {
            logger.warning("No target elements specified for parameter estimation");
            return null;
        }

        Map<String, ElementStatistics> allElementStats = statistics.getAllElementStatistics();
        int numTargetElements = targetElements.length;

        logger.info("Estimating Dirichlet parameters for " + numTargetElements + " target elements");

        // Extract statistics for target elements only
        double[] means = new double[numTargetElements];
        double[] variances = new double[numTargetElements];
        boolean[] found = new boolean[numTargetElements];
        boolean[] explicitRanges = new boolean[numTargetElements];

        for (int i = 0; i < numTargetElements; i++) {
            String targetElement = targetElements[i];
            ElementStatistics stats = allElementStats.get(targetElement);

            if (stats != null) {
                // Convert percentage to proportion
                means[i] = stats.getAveragePercentage() / 100.0;
                explicitRanges[i] = hasExplicitRange(stats);
                variances[i] = estimateVariance(stats);
                found[i] = true;

                logger.info(String.format("Target element %s: mean=%.4f, variance=%.6f, gradeCount=%d, explicitRange=%b",
                        targetElement, means[i], variances[i], stats.getGradeCount(), explicitRanges[i]));
            } else {
                logger.warning("Target element " + targetElement + " not found in series statistics");
                // Use default values for missing elements
                means[i] = 0.01; // 1% default
                variances[i] = MIN_VARIANCE_THRESHOLD;
                found[i] = false;
                explicitRanges[i] = false;
            }
        }

        // Check if we found at least some elements
        int foundCount = 0;
        for (boolean f : found) {
            if (f) foundCount++;
        }

        if (foundCount == 0) {
            logger.severe("None of the target elements found in series statistics");
            return null;
        }

        logger.info("Found " + foundCount + " out of " + numTargetElements + " target elements in series statistics");

        // Normalize means to ensure they sum to 1.0
        double meanSum = Arrays.stream(means).sum();
        if (meanSum <= 0) {
            logger.severe("Sum of means is non-positive: " + meanSum);
            return null;
        }

        for (int i = 0; i < means.length; i++) {
            means[i] /= meanSum;
        }

        // Pooled Dirichlet method-of-moments estimation
        double totalConcentration = estimateTotalConcentration(means, variances, explicitRanges);
        double[] alphas = new double[numTargetElements];

        for (int i = 0; i < numTargetElements; i++) {
            alphas[i] = means[i] * totalConcentration;
        }

        // Validate and adjust parameters
        alphas = validateAndAdjustParameters(alphas, targetElements);

        logger.info("Estimated parameters for target elements: " + Arrays.toString(alphas));
        logger.info("Total concentration: " + Arrays.stream(alphas).sum());

        return alphas;
    }

    /**
     * Checks whether empirical [min, max] range data is present (i.e., not the default [0, 100] sentinel).
     */
    private boolean hasExplicitRange(ElementStatistics stats) {
        return !(stats.getMinPercentage() == 0.0 && stats.getMaxPercentage() == 100.0);
    }

    /**
     * Estimates proportion variance for an element from its empirical alloy range and grade count
     * using Wan et al. (2014) range-to-SD conversion, or a non-cancelling relative-spread fallback
     * when only the default [0, 100] sentinel range is present.
     */
    private double estimateVariance(ElementStatistics stats) {
        if (hasExplicitRange(stats)) {
            double rangeProportion = Math.max(0.0, (stats.getMaxPercentage() - stats.getMinPercentage()) / 100.0);
            if (rangeProportion <= 0.0) {
                return MIN_VARIANCE_THRESHOLD;
            }
            double divisor = computeRangeDivisor(stats.getGradeCount());
            double sigma = rangeProportion / divisor;
            return Math.max(sigma * sigma, MIN_VARIANCE_THRESHOLD);
        }

        // Sentinel [0, 100] fallback: fixed relative spread (does not cancel mu * (1 - mu))
        double proportion = stats.getAveragePercentage() / 100.0;
        double sigma = SENTINEL_RELATIVE_STD * proportion;
        return Math.max(sigma * sigma, MIN_VARIANCE_THRESHOLD);
    }

    /**
     * Computes Wan et al. (2014) expected range divisor d_n = 2 * Phi^{-1}((n - 0.375) / (n + 0.25))
     * clamped to [2.0, 6.0] for n >= 2, falling back to 4.0 (classic R/4 rule) for n < 2.
     */
    private double computeRangeDivisor(int gradeCount) {
        if (gradeCount < 2) {
            return SINGLE_GRADE_RANGE_DIVISOR;
        }
        double p = (gradeCount - 0.375) / (gradeCount + 0.25);
        double dn = 2.0 * STANDARD_NORMAL.inverseCumulativeProbability(p);
        return Math.max(MIN_RANGE_DIVISOR, Math.min(MAX_RANGE_DIVISOR, dn));
    }

    /**
     * Estimates total concentration parameter alpha_0 using the pooled Dirichlet method-of-moments identity:
     *   alpha_0 = (sum_i mu_i * (1 - mu_i)) / (sum_i Var(X_i)) - 1.
     *
     * Whenever at least one element has an explicit [min, max] range, the pooled sum is computed
     * strictly over those explicit-range elements so sentinel [0, 100] balance/remainder elements
     * cannot swamp tight alloying-element variances.
     */
    private double estimateTotalConcentration(double[] means, double[] variances, boolean[] explicitRanges) {
        boolean anyExplicitRange = false;
        for (int i = 0; i < means.length; i++) {
            if (explicitRanges[i] && means[i] > 0.0 && means[i] < 1.0 && variances[i] > 0.0) {
                anyExplicitRange = true;
                break;
            }
        }

        double numerator = 0.0;
        double denominator = 0.0;
        int validEstimates = 0;

        for (int i = 0; i < means.length; i++) {
            if (anyExplicitRange && !explicitRanges[i]) {
                continue;
            }
            if (means[i] > 0.0 && means[i] < 1.0 && variances[i] > 0.0) {
                numerator += means[i] * (1.0 - means[i]);
                denominator += variances[i];
                validEstimates++;
            }
        }

        if (validEstimates == 0 || numerator <= 0.0 || denominator <= 0.0) {
            logger.warning("No valid concentration estimates, using default value");
            return DEFAULT_FALLBACK_CONCENTRATION;
        }

        double pooledConcentration = (numerator / denominator) - 1.0;

        // Apply bounds to ensure reasonable values
        pooledConcentration = Math.max(MIN_TOTAL_CONCENTRATION, pooledConcentration);
        pooledConcentration = Math.min(MAX_TOTAL_CONCENTRATION, pooledConcentration);

        logger.info("Estimated total concentration: " + pooledConcentration +
                " (pooled over " + validEstimates + (anyExplicitRange ? " explicit-range" : " fallback") + " elements)");

        return pooledConcentration;
    }

    /**
     * Validates and adjusts parameters to ensure numerical stability while keeping
     * sum(alphas) <= MAX_TOTAL_CONCENTRATION after MIN_ALPHA flooring of trace elements.
     */
    private double[] validateAndAdjustParameters(double[] alphas, String[] elementOrder) {
        double[] adjustedAlphas = alphas.clone();
        boolean adjusted = false;

        // Check for NaN or infinite values first
        for (int i = 0; i < adjustedAlphas.length; i++) {
            if (!Double.isFinite(adjustedAlphas[i])) {
                logger.warning("Invalid parameter for " + elementOrder[i] +
                        ", setting to default value");
                adjustedAlphas[i] = 1.0;
                adjusted = true;
            }
        }

        // Ensure minimum parameter values
        for (int i = 0; i < adjustedAlphas.length; i++) {
            if (adjustedAlphas[i] < MIN_ALPHA) {
                logger.warning("Parameter for " + elementOrder[i] + " too small (" +
                        adjustedAlphas[i] + "), adjusting to " + MIN_ALPHA);
                adjustedAlphas[i] = MIN_ALPHA;
                adjusted = true;
            }
        }

        // Preserve MAX_TOTAL_CONCENTRATION cap if MIN_ALPHA flooring pushed total above cap
        double total = Arrays.stream(adjustedAlphas).sum();
        while (total > MAX_TOTAL_CONCENTRATION + 1e-9) {
            double fixedSum = 0.0;
            double adjustableSum = 0.0;
            for (double a : adjustedAlphas) {
                if (a <= MIN_ALPHA + 1e-12) {
                    fixedSum += a;
                } else {
                    adjustableSum += a;
                }
            }
            double targetAdjustable = MAX_TOTAL_CONCENTRATION - fixedSum;
            if (adjustableSum <= 0.0 || targetAdjustable <= 0.0) {
                break;
            }
            double scale = targetAdjustable / adjustableSum;
            for (int i = 0; i < adjustedAlphas.length; i++) {
                if (adjustedAlphas[i] > MIN_ALPHA + 1e-12) {
                    adjustedAlphas[i] = Math.max(MIN_ALPHA, adjustedAlphas[i] * scale);
                }
            }
            adjusted = true;
            total = Arrays.stream(adjustedAlphas).sum();
        }

        if (adjusted) {
            logger.info("Parameters were adjusted for numerical stability");
        }

        return adjustedAlphas;
    }

    /**
     * Validates that estimated parameters will produce reasonable samples.
     */
    public boolean validateParameters(double[] alphas) {
        if (alphas == null || alphas.length == 0) {
            return false;
        }

        // Check all parameters are positive and finite
        for (double alpha : alphas) {
            if (!Double.isFinite(alpha) || alpha <= 0) {
                return false;
            }
        }

        // Check total concentration is reasonable
        double totalConcentration = Arrays.stream(alphas).sum();
        if (totalConcentration < MIN_TOTAL_CONCENTRATION || totalConcentration > MAX_TOTAL_CONCENTRATION) {
            logger.warning("Total concentration outside reasonable range: " + totalConcentration);
            return false;
        }

        return true;
    }
}
