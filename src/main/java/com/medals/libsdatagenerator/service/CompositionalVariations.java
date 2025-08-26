package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.sampler.DirichletSampler;
import com.medals.libsdatagenerator.sampler.GaussianSampler;
import com.medals.libsdatagenerator.util.CommonUtils;

import java.util.*;
import java.util.logging.Logger;

/**
 * @author Siddharth Prince | 13/02/25 13:14
 */

public class CompositionalVariations {

    private final Logger logger = Logger.getLogger(CompositionalVariations.class.getName());
    private static CompositionalVariations instance = null;
    private final CommonUtils commonUtils;
    private static final double POST_NORM_CHECK_DELTA = 0.0001;
    private static final double FINAL_SUM_TOLERANCE = 0.1;

    public CompositionalVariations() {
        commonUtils = new CommonUtils();
    }

    public static CompositionalVariations getInstance() {
        if (instance == null) {
            instance = new CompositionalVariations();
        }
        return instance;
    }

    public List<List<Element>> generateCompositionalVariations(List<Element> originalComposition,
                                                               double varyBy, double maxDelta, int variationMode,
                                                               int samples, String overviewGuid) {

        List<List<Element>> compositions = new ArrayList<>();
        if (originalComposition == null || originalComposition.isEmpty()) {
            logger.warning("Original composition is null or empty. Cannot generate variations.");
            return compositions; // Return empty list, or perhaps add the (empty) originalComposition if that's desired.
        }
        compositions.add(originalComposition); // Adding the original composition

        boolean allElementsAreFixed = true;
        for (Element el : originalComposition) {
            Double minComp = el.getMin();
            Double maxComp = el.getMax();
            if (!(minComp != null && maxComp != null && minComp.equals(maxComp))) {
                allElementsAreFixed = false;
                break;
            }
        }

        List<Element> effectiveComposition = originalComposition;
        if (allElementsAreFixed) {
            // originalComposition is not empty here due to the check at the beginning.
            logger.info("All elements in the input composition are fixed. Applying fallback variation logic, ignoring X:X constraints for sampling.");
            effectiveComposition = new ArrayList<>();
            for (Element el : originalComposition) {
                effectiveComposition.add(new Element(
                        el.getName(),
                        el.getSymbol(),
                        el.getPercentageComposition(),
                        null, // Effectively remove min/max for variation generation
                        null,
                        el.getAverageComposition()
                ));
            }
        }

        // Generate all combinations by Uniform distribution
        // System.out.println("\nGenerating different combinations for the input composition (refer log for list)...");
        logger.info("\nGenerating different combinations for the input composition (refer log for list)...");

        int numVariationsToGenerate = Math.max(0, samples - 1);

        if (variationMode == LIBSDataGenConstants.STAT_VAR_MODE_GAUSSIAN_DIST) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("maxDelta", maxDelta);
            GaussianSampler.getInstance().sample(effectiveComposition, numVariationsToGenerate, compositions, metadata);

        } else if (variationMode == LIBSDataGenConstants.STAT_VAR_MODE_DIRICHLET_DIST) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("overviewGuid", overviewGuid);
            DirichletSampler.getInstance().sample(effectiveComposition, numVariationsToGenerate, compositions, metadata);

        } else { // For uniform distribution
            // Start backtracking with an empty "current combo" and a running sum of 0
            CompositionalVariations.getInstance().getUniformDistribution(
                    0,
                    effectiveComposition,
                    varyBy,
                    maxDelta, // 'limit'
                    0.0,
                    new ArrayList<Element>(),
                    compositions);
        }

        return compositions;
    }

    /**
     * Validates that a variation meets all constraints
     */
    public boolean validateVariation(List<Element> variation) {
        double totalPercentage = 0.0;

        for (Element element : variation) {
            double percentage = element.getPercentageComposition();

            // Check individual element constraints
            if (element.getMin() != null &&
                    percentage < element.getMin() - POST_NORM_CHECK_DELTA) {
                return false;
            }

            if (element.getMax() != null &&
                    percentage > element.getMax() + POST_NORM_CHECK_DELTA) {
                return false;
            }

            // Check for negative values
            if (percentage < 0) {
                return false;
            }

            totalPercentage += percentage;
        }

        // Check that total percentage is close to 100%
        if (Math.abs(totalPercentage - 100.0) > FINAL_SUM_TOLERANCE) {
            return false;
        }

        return true;
    }

    @Deprecated
    public void getUniformDistribution(int index, List<Element> original, double varyBy, double limit,
                                       double currentSum, List<Element> currentCombo,
                                       List<List<Element>> results) {

        Element elementAtIndex = original.get(index);
        double originalVal = elementAtIndex.getPercentageComposition();
        Double minComp = elementAtIndex.getMin();
        Double maxComp = elementAtIndex.getMax();
        // Use .doubleValue() for comparison if not null, to avoid object comparison issues with ==
        boolean isFixed = (minComp != null && maxComp != null &&
                Math.abs(minComp.doubleValue() - maxComp.doubleValue()) < POST_NORM_CHECK_DELTA &&
                Math.abs(minComp.doubleValue() - originalVal) < POST_NORM_CHECK_DELTA);


        // Handle Last Element
        if (index == original.size() - 1) {
            double lastValRequired = 100.0 - currentSum;
            double roundedLastVal = CommonUtils.roundToNDecimals(lastValRequired, elementAtIndex.getNumberDecimalPlaces());

            if (isFixed) {
                // For a fixed last element, its value must be what's required to sum to 100
                // and this required value must be its fixed value.
                if (Math.abs(roundedLastVal - originalVal) <= POST_NORM_CHECK_DELTA) {
                    // Value is consistent with fixed value and sums to 100
                    currentCombo.add(new Element(
                            elementAtIndex.getName(), elementAtIndex.getSymbol(), roundedLastVal, // or originalVal
                            minComp, maxComp, elementAtIndex.getAverageComposition()
                    ));
                    List<Element> newComposition = commonUtils.deepCopy(currentCombo);
                    results.add(newComposition);
                    logger.info("New composition (fixed last element): " + commonUtils.buildCompositionString(newComposition));
                    currentCombo.remove(currentCombo.size() - 1);
                }
                // If not, this path is invalid because the fixed last element cannot satisfy sum to 100
            } else { // Variable last element
                double low = Math.max(0, originalVal - limit);
                if (minComp != null) {
                    low = Math.max(low, minComp.doubleValue());
                }
                double high = Math.min(100, originalVal + limit);
                if (maxComp != null) {
                    high = Math.min(high, maxComp.doubleValue());
                }

                // Check if low is not greater than high (valid range)
                // And if roundedLastVal is within this dynamic range [low, high]
                if (low <= high + POST_NORM_CHECK_DELTA &&
                        roundedLastVal >= low - POST_NORM_CHECK_DELTA &&
                        roundedLastVal <= high + POST_NORM_CHECK_DELTA) {
                    currentCombo.add(new Element(
                            elementAtIndex.getName(), elementAtIndex.getSymbol(), roundedLastVal,
                            minComp, maxComp, elementAtIndex.getAverageComposition()
                    ));
                    List<Element> newComposition = commonUtils.deepCopy(currentCombo);
                    results.add(newComposition);
                    logger.info("New composition (variable last element): " + commonUtils.buildCompositionString(newComposition));
                    currentCombo.remove(currentCombo.size() - 1);
                }
            }
            return; // End of processing for the last element
        }

        // Handle Non-Last Elements
        if (isFixed) {
            double fixedVal = originalVal; // or minComp.doubleValue()
            // Ensure adding this fixed value doesn't significantly exceed 100
            if (currentSum + fixedVal <= 100.0 + POST_NORM_CHECK_DELTA) {
                currentCombo.add(new Element(
                        elementAtIndex.getName(), elementAtIndex.getSymbol(), fixedVal,
                        minComp, maxComp, elementAtIndex.getAverageComposition()
                ));
                getUniformDistribution(index + 1, original, varyBy, limit,
                        currentSum + fixedVal, currentCombo, results);
                currentCombo.remove(currentCombo.size() - 1); // Backtrack
            }
            // If it exceeds 100, this path is invalid.
            // Return because this element is fixed and cannot be changed.
        } else { // Variable Non-Last Element
            double low = Math.max(0, originalVal - limit);
            if (minComp != null) {
                low = Math.max(low, minComp.doubleValue());
            }
            double high = Math.min(100, originalVal + limit);
            if (maxComp != null) {
                high = Math.min(high, maxComp.doubleValue());
            }

            if (low > high + POST_NORM_CHECK_DELTA) { // If range is invalid
                return;
            }

            // Iterate through possible values for the current variable element
            // Ensure 'val' does not step over 'high' due to varyBy precision.
            for (double val = low; val <= high + POST_NORM_CHECK_DELTA; val += varyBy) {
                // Clamp val to be no more than high to handle floating point overshoot
                double currentVal = Math.min(val, high);

                // Only proceed if currentSum + currentVal won't significantly exceed 100
                if (currentSum + currentVal <= 100.0 + POST_NORM_CHECK_DELTA) {
                    currentCombo.add(new Element(
                            elementAtIndex.getName(), elementAtIndex.getSymbol(), currentVal,
                            minComp, maxComp, elementAtIndex.getAverageComposition()
                    ));
                    getUniformDistribution(index + 1, original, varyBy, limit,
                            currentSum + currentVal, currentCombo, results);
                    currentCombo.remove(currentCombo.size() - 1); // Backtrack
                } else if (currentSum + currentVal > 100.0 + POST_NORM_CHECK_DELTA && currentVal == low) {
                    // If even the lowest possible value for this element makes the sum exceed 100,
                    // then no further values in this loop will work.
                    break;
                }
            }
        }
    }
}
