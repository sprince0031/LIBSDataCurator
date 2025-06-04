package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.model.ElementStatistics;
import com.medals.libsdatagenerator.model.SeriesStatistics;

import java.util.Arrays;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Estimates Dirichlet concentration parameters from series statistics
 * @author Manus AI | Enhanced LIBSDataCurator Phase 1
 */
public class ConcentrationParameterEstimator {
    private static final Logger logger = Logger.getLogger(ConcentrationParameterEstimator.class.getName());

    // Minimum parameter values to ensure numerical stability
    private static final double MIN_ALPHA = 0.1;
    private static final double MIN_VARIANCE_THRESHOLD = 1e-6;
    private static final double MAX_TOTAL_CONCENTRATION = 1000.0;
    private static final double MIN_TOTAL_CONCENTRATION = 1.0;

    /**
     * Estimates Dirichlet concentration parameters for specific elements only
     *
     * @param statistics Series statistics containing element averages and grade counts
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

        for (int i = 0; i < numTargetElements; i++) {
            String targetElement = targetElements[i];
            ElementStatistics stats = allElementStats.get(targetElement);

            if (stats != null) {
                // Convert percentage to proportion
                means[i] = stats.getAveragePercentage() / 100.0;
                variances[i] = estimateVariance(stats);
                found[i] = true;

                logger.info(String.format("Target element %s: mean=%.4f, variance=%.6f, gradeCount=%d",
                        targetElement, means[i], variances[i], stats.getGradeCount()));
            } else {
                logger.warning("Target element " + targetElement + " not found in series statistics");
                // Use default values for missing elements
                means[i] = 0.01; // 1% default
                variances[i] = MIN_VARIANCE_THRESHOLD;
                found[i] = false;
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

        // Method of moments estimation
        double totalConcentration = estimateTotalConcentration(means, variances);
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
     * Estimates Dirichlet concentration parameters using method of moments
     *
     * @param statistics Series statistics containing element averages and grade counts
     * @return Array of concentration parameters (alphas) for Dirichlet distribution
     */
    public double[] estimateParameters(SeriesStatistics statistics) {
        if (statistics == null || !statistics.isValidForDirichletSampling()) {
            logger.warning("Invalid statistics provided for parameter estimation");
            return null;
        }

        Map<String, ElementStatistics> elementStats = statistics.getAllElementStatistics();
        int numElements = elementStats.size();

        logger.info("Estimating Dirichlet parameters for " + numElements + " elements");

        // Convert percentages to proportions and extract statistics
        double[] means = new double[numElements];
        double[] variances = new double[numElements];
        String[] elementOrder = new String[numElements];

        int index = 0;
        for (Map.Entry<String, ElementStatistics> entry : elementStats.entrySet()) {
            ElementStatistics stats = entry.getValue();
            elementOrder[index] = entry.getKey();

            // Convert percentage to proportion
            means[index] = stats.getAveragePercentage() / 100.0;
            variances[index] = estimateVariance(stats);

            logger.info(String.format("Element %s: mean=%.4f, variance=%.6f, gradeCount=%d",
                    stats.getElementSymbol(), means[index], variances[index], stats.getGradeCount()));
            index++;
        }

        // Normalize means to ensure they sum to 1.0
        double meanSum = Arrays.stream(means).sum();
        if (meanSum <= 0) {
            logger.severe("Sum of means is non-positive: " + meanSum);
            return null;
        }

        for (int i = 0; i < means.length; i++) {
            means[i] /= meanSum;
        }

        // Method of moments estimation
        double totalConcentration = estimateTotalConcentration(means, variances);
        double[] alphas = new double[numElements];

        for (int i = 0; i < numElements; i++) {
            alphas[i] = means[i] * totalConcentration;
        }

        // Validate and adjust parameters
        alphas = validateAndAdjustParameters(alphas, elementOrder);

        logger.info("Estimated parameters: " + Arrays.toString(alphas));
        logger.info("Total concentration: " + Arrays.stream(alphas).sum());

        return alphas;
    }

    /**
     * Estimates variance for an element based on its statistics
     */
    private double estimateVariance(ElementStatistics stats) {
        double proportion = stats.getAveragePercentage() / 100.0;
        int gradeCount = stats.getGradeCount();

        // For compositional data, use empirical relationship between sample count and variance
        // Base variance for proportion: p(1-p), adjusted by effective sample size
        double baseVariance = proportion * (1 - proportion);

        // Adjust variance based on grade count - more samples should mean lower variance
        // Use a conservative adjustment to avoid overly small variances
        double adjustedVariance = baseVariance / Math.max(1.0, Math.sqrt(gradeCount));

        // Ensure minimum variance threshold
        return Math.max(adjustedVariance, MIN_VARIANCE_THRESHOLD);
    }

    /**
     * Estimates total concentration parameter using method of moments
     */
    private double estimateTotalConcentration(double[] means, double[] variances) {
        // Method of moments: For Dirichlet, Var(X_i) = μ_i(1-μ_i)/(α_0+1)
        // where α_0 is the total concentration parameter

        double totalVarianceEstimate = 0.0;
        int validEstimates = 0;

        for (int i = 0; i < means.length; i++) {
            if (means[i] > 0 && means[i] < 1 && variances[i] > 0) {
                // Solve for α_0: α_0 = μ_i(1-μ_i)/Var(X_i) - 1
                double concentrationEstimate = (means[i] * (1 - means[i]) / variances[i]) - 1;

                if (concentrationEstimate > 0) {
                    totalVarianceEstimate += concentrationEstimate;
                    validEstimates++;
                }
            }
        }

        if (validEstimates == 0) {
            logger.warning("No valid concentration estimates, using default value");
            return 10.0; // Default reasonable value
        }

        double averageConcentration = totalVarianceEstimate / validEstimates;

        // Apply bounds to ensure reasonable values
        averageConcentration = Math.max(MIN_TOTAL_CONCENTRATION, averageConcentration);
        averageConcentration = Math.min(MAX_TOTAL_CONCENTRATION, averageConcentration);

        logger.info("Estimated total concentration: " + averageConcentration +
                " (from " + validEstimates + " valid estimates)");

        return averageConcentration;
    }

    /**
     * Validates and adjusts parameters to ensure numerical stability
     */
    private double[] validateAndAdjustParameters(double[] alphas, String[] elementOrder) {
        double[] adjustedAlphas = alphas.clone();
        boolean adjusted = false;

        // Ensure minimum parameter values
        for (int i = 0; i < adjustedAlphas.length; i++) {
            if (adjustedAlphas[i] < MIN_ALPHA) {
                logger.warning("Parameter for " + elementOrder[i] + " too small (" +
                        adjustedAlphas[i] + "), adjusting to " + MIN_ALPHA);
                adjustedAlphas[i] = MIN_ALPHA;
                adjusted = true;
            }
        }

        // Check for NaN or infinite values
        for (int i = 0; i < adjustedAlphas.length; i++) {
            if (!Double.isFinite(adjustedAlphas[i])) {
                logger.warning("Invalid parameter for " + elementOrder[i] +
                        ", setting to default value");
                adjustedAlphas[i] = 1.0;
                adjusted = true;
            }
        }

        if (adjusted) {
            logger.info("Parameters were adjusted for numerical stability");
        }

        return adjustedAlphas;
    }

    /**
     * Estimates parameters using alternative maximum likelihood approach (for future enhancement)
     */
    public double[] estimateParametersML(SeriesStatistics statistics) {
        // Placeholder for maximum likelihood estimation
        // This could be implemented in future versions for improved accuracy
        logger.info("Maximum likelihood estimation not yet implemented, using method of moments");
        return estimateParameters(statistics);
    }

    /**
     * Validates that estimated parameters will produce reasonable samples
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
        if (totalConcentration < MIN_TOTAL_CONCENTRATION ||
                totalConcentration > MAX_TOTAL_CONCENTRATION) {
            logger.warning("Total concentration outside reasonable range: " + totalConcentration);
            return false;
        }

        return true;
    }
}

