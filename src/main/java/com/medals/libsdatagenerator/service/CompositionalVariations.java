package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.SeriesStatistics;
import com.medals.libsdatagenerator.util.CommonUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;
import java.util.logging.Logger;

/**
 * @author Siddharth Prince | 13/02/25 13:14
 */

public class CompositionalVariations {

    public static CompositionalVariations instance = null;
    private final CommonUtils commonUtils;
    private final Logger logger = Logger.getLogger(CompositionalVariations.class.getName());
    private static final double POST_NORM_CHECK_DELTA = 0.0001;
    private static final double FINAL_SUM_TOLERANCE = 0.1;

    // Enhanced services for Dirichlet sampling
    private final MatwebDataService matwebService;
    private final ConcentrationParameterEstimator parameterEstimator;

    public CompositionalVariations() {
        commonUtils = new CommonUtils();
        matwebService = MatwebDataService.getInstance();
        parameterEstimator = new ConcentrationParameterEstimator();
    }

    public static CompositionalVariations getInstance() {
        if (instance == null) {
            instance = new CompositionalVariations();
        }
        return instance;
    }

    /**
     * New Dirichlet sampling method for compositional variations
     *
     * @param baseComp Base composition elements
     * @param overviewGuid GUID of the overview datasheet for series statistics
     * @param samples Number of samples to generate
     * @param variations List to store generated variations
     */
    public void dirichletSampling(ArrayList<Element> baseComp, String overviewGuid, int samples,
                                  ArrayList<ArrayList<Element>> variations) {

        logger.info("Starting Dirichlet sampling with overview GUID: " + overviewGuid);

        // Get series statistics from overview sheet
        SeriesStatistics seriesStats = matwebService.getSeriesStatistics(overviewGuid);
        if (seriesStats == null) {
            logger.severe("Failed to extract series statistics from overview sheet. Falling back to Gaussian sampling.");
            // Fallback to Gaussian sampling
            gaussianSampling(baseComp, 5.0, samples, variations); // Use default maxDelta of 5.0
            return;
        }

        logger.info("Successfully extracted series statistics: " + seriesStats);

        // Create element order array from base composition
        String[] elementOrder = new String[baseComp.size()];
        double[] minConstraints = new double[baseComp.size()];
        double[] maxConstraints = new double[baseComp.size()];

        for (int i = 0; i < baseComp.size(); i++) {
            Element element = baseComp.get(i);
            elementOrder[i] = element.getSymbol();

            // Set constraints from element min/max values
            minConstraints[i] = element.getPercentageCompositionMin() != null ?
                    element.getPercentageCompositionMin() : 0.0;
            maxConstraints[i] = element.getPercentageCompositionMax() != null ?
                    element.getPercentageCompositionMax() : 100.0;
        }

        // Estimate Dirichlet concentration parameters for the base composition elements only
        double[] concentrationParams = parameterEstimator.estimateParametersForElements(seriesStats, elementOrder);
        if (concentrationParams == null || !parameterEstimator.validateParameters(concentrationParams)) {
            logger.severe("Failed to estimate valid Dirichlet parameters. Falling back to Gaussian sampling.");
            gaussianSampling(baseComp, 5.0, samples, variations);
            return;
        }

        // Verify arrays have same length
        if (concentrationParams.length != elementOrder.length) {
            logger.severe("Mismatch between concentration parameters (" + concentrationParams.length +
                    ") and element order (" + elementOrder.length + "). Falling back to Gaussian sampling.");
            gaussianSampling(baseComp, 5.0, samples, variations);
            return;
        }

        // Create Dirichlet sampler
        DirichletSampler sampler = new DirichletSampler(concentrationParams, elementOrder,
                minConstraints, maxConstraints);

        logger.info("Created Dirichlet sampler with parameters: " + Arrays.toString(concentrationParams));
        logger.info("Expected means: " + Arrays.toString(sampler.getExpectedMeans()));

        // Generate samples
        int initialSize = variations.size();
        int successfulSamples = 0;
        int attempts = 0;
        int maxAttempts = samples * 10; // Allow up to 10x attempts to account for constraint violations

        while (successfulSamples < samples && attempts < maxAttempts) {
            attempts++;

            try {
                double[] sample = sampler.generateSample();
                ArrayList<Element> variation = createElementVariation(baseComp, sample, elementOrder);

                if (validateVariation(variation)) {
                    variations.add(variation);
                    successfulSamples++;

                    if (successfulSamples % 100 == 0) {
                        logger.info("Generated " + successfulSamples + " valid Dirichlet samples");
                    }
                } else {
                    logger.fine("Sample failed validation, attempting another");
                }

            } catch (Exception e) {
                logger.warning("Error generating Dirichlet sample: " + e.getMessage());
            }
        }

        logger.info("Dirichlet sampling completed: " + successfulSamples + " successful samples out of " +
                attempts + " attempts");

        if (successfulSamples < samples) {
            logger.warning("Could not generate all requested samples. Generated " + successfulSamples +
                    " out of " + samples + " requested samples.");
        }
    }

    /**
     * Creates an Element variation from a Dirichlet sample
     */
    private ArrayList<Element> createElementVariation(ArrayList<Element> baseComp, double[] sample,
                                                      String[] elementOrder) {
        ArrayList<Element> variation = new ArrayList<>();

        for (int i = 0; i < baseComp.size(); i++) {
            Element baseElement = baseComp.get(i);

            // Find the corresponding sample value for this element
            double sampleValue = 0.0;
            for (int j = 0; j < elementOrder.length; j++) {
                if (elementOrder[j].equals(baseElement.getSymbol())) {
                    sampleValue = sample[j];
                    break;
                }
            }

            // Round to appropriate decimal places
            double roundedValue = CommonUtils.roundToNDecimals(sampleValue, baseElement.getNumberDecimalPlaces());

            Element variationElement = new Element(
                    baseElement.getName(),
                    baseElement.getSymbol(),
                    roundedValue,
                    baseElement.getPercentageCompositionMin(),
                    baseElement.getPercentageCompositionMax(),
                    baseElement.getAverageComposition()
            );

            variation.add(variationElement);
        }

        return variation;
    }

    /**
     * Validates that a variation meets all constraints
     */
    private boolean validateVariation(ArrayList<Element> variation) {
        double totalPercentage = 0.0;

        for (Element element : variation) {
            double percentage = element.getPercentageComposition();

            // Check individual element constraints
            if (element.getPercentageCompositionMin() != null &&
                    percentage < element.getPercentageCompositionMin() - POST_NORM_CHECK_DELTA) {
                return false;
            }

            if (element.getPercentageCompositionMax() != null &&
                    percentage > element.getPercentageCompositionMax() + POST_NORM_CHECK_DELTA) {
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

    public void gaussianSampling(ArrayList<Element> baseComp, double maxDelta, int samples,
                                 ArrayList<ArrayList<Element>> variations) {
        Random rand = new Random();
        int initialSize = variations.size();

        while (variations.size() < initialSize + samples) {
            double sumFixedPercentages = 0.0;
            ArrayList<Element> tempVariableElements = new ArrayList<>();
            // currentRawVariantElements stores elements in original order, some fixed, some are initial variable samples
            ArrayList<Element> currentRawVariantElements = new ArrayList<>();

            // First Pass: Identify fixed/variable, apply initial sampling to variables
            for (Element baseElement : baseComp) {
                Double minComp = baseElement.getPercentageCompositionMin();
                Double maxComp = baseElement.getPercentageCompositionMax();
                boolean isFixed = minComp != null && maxComp != null && minComp.doubleValue() == maxComp.doubleValue();

                if (isFixed) {
                    Element fixedElementCopy = new Element(baseElement.getName(), baseElement.getSymbol(),
                            minComp, // Use the fixed percentage
                            minComp, maxComp, baseElement.getAverageComposition());
                    currentRawVariantElements.add(fixedElementCopy);
                    sumFixedPercentages += minComp;
                } else {
                    double baseVal = baseElement.getPercentageComposition();
                    // Ensure symbol exists in fallback map, otherwise skip this sample generation attempt
                    if (!LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.containsKey(baseElement.getSymbol())) {
                        logger.warning("Symbol " + baseElement.getSymbol() + " not in ELEMENT_STD_DEVS_FALLBACK. Skipping sample generation attempt.");
                        currentRawVariantElements.clear(); // Mark as invalid attempt
                        break;
                    }
                    double stdDev = LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.get(baseElement.getSymbol());
                    double delta = rand.nextGaussian() * stdDev;
                    delta = Math.max(-maxDelta, Math.min(delta, maxDelta));
                    double newVal = baseVal + delta;
                    newVal = Math.max(0, newVal); // Clamp to 0 minimum

                    if (minComp != null) {
                        newVal = Math.max(newVal, minComp);
                    }
                    if (maxComp != null) {
                        newVal = Math.min(newVal, maxComp);
                    }

                    Element variableElementInstance = new Element(baseElement.getName(), baseElement.getSymbol(),
                            newVal, minComp, maxComp, baseElement.getAverageComposition());
                    currentRawVariantElements.add(variableElementInstance);
                    tempVariableElements.add(variableElementInstance);
                }
            }

            // If loop was broken (e.g. missing std dev), restart sample generation
            if (currentRawVariantElements.size() != baseComp.size()){
                logger.info("Skipping sample due to issue in first pass (e.g. missing std dev for a symbol).");
                continue;
            }

            // Zero Check for Variable Elements that were non-zero initially
            boolean variableElementBecameZero = false;
            for (int i = 0; i < tempVariableElements.size(); i++) {
                Element varElement = tempVariableElements.get(i);
                // Find original base element corresponding to this tempVariableElement
                // This is a bit indirect; assumes tempVariableElements are added in same order as variable elements appear in baseComp
                Element originalBaseForVar = null;
                int varCounter = 0;
                for(Element baseElem : baseComp) {
                    boolean isBaseFixed = baseElem.getPercentageCompositionMin() != null && baseElem.getPercentageCompositionMax() != null && baseElem.getPercentageCompositionMin().doubleValue() == baseElem.getPercentageCompositionMax().doubleValue();
                    if (!isBaseFixed) {
                        if (varCounter == i) {
                            originalBaseForVar = baseElem;
                            break;
                        }
                        varCounter++;
                    }
                }

                if (varElement.getPercentageComposition() == 0 && originalBaseForVar != null && originalBaseForVar.getPercentageComposition() > 0) {
                    logger.info("Variable Element: " + varElement.getName() + " (originally > 0%) sampled to 0. Discarding sample.");
                    variableElementBecameZero = true;
                    break;
                }
            }
            if (variableElementBecameZero) {
                continue;
            }

            // Normalization of Variable Elements
            double currentSumVariableRaw = 0;
            for (Element e : tempVariableElements) {
                currentSumVariableRaw += e.getPercentageComposition();
            }

            double targetSumVariable = 100.0 - sumFixedPercentages;

            if (targetSumVariable < 0) { // Sum of fixed elements already > 100%
                logger.info("Sum of fixed percentages (" + sumFixedPercentages + "%) > 100%. Discarding sample.");
                continue;
            }
            if (currentSumVariableRaw <= 0 && targetSumVariable > 0 && !tempVariableElements.isEmpty()) {
                logger.info("Sum of raw variable percentages is " + currentSumVariableRaw +
                        " but target is " + targetSumVariable + ". Cannot normalize. Discarding sample.");
                continue;
            }


            double variableScalingFactor = 1.0;
            if (currentSumVariableRaw > 0) {
                variableScalingFactor = targetSumVariable / currentSumVariableRaw;
            } else if (targetSumVariable == 0 && currentSumVariableRaw == 0) { // All variable elements are zero, and target is zero
                variableScalingFactor = 0; // Effectively keeps them zero
            }


            for (Element varElement : tempVariableElements) {
                double scaledVal = varElement.getPercentageComposition() * variableScalingFactor;

                // Re-clamp against min/max and ensure >= 0
                scaledVal = Math.max(0, scaledVal);
                if (varElement.getPercentageCompositionMin() != null) {
                    scaledVal = Math.max(scaledVal, varElement.getPercentageCompositionMin());
                }
                if (varElement.getPercentageCompositionMax() != null) {
                    scaledVal = Math.min(scaledVal, varElement.getPercentageCompositionMax());
                }
                varElement.setPercentageComposition(CommonUtils.roundToNDecimals(scaledVal, varElement.getNumberDecimalPlaces()));
            }

            // Final Variant Construction and Validation
            ArrayList<Element> finalVariantElements = new ArrayList<>();
            double finalSum = 0.0;
            boolean constraintViolatedAfterNormalization = false;

            int varElemIdx = 0; // To iterate through tempVariableElements
            for(Element rawElementFromFirstPass : currentRawVariantElements) {
                Double minComp = rawElementFromFirstPass.getPercentageCompositionMin();
                Double maxComp = rawElementFromFirstPass.getPercentageCompositionMax();
                boolean isFixed = minComp != null && maxComp != null && minComp.doubleValue() == maxComp.doubleValue();

                if (isFixed) {
                    finalVariantElements.add(rawElementFromFirstPass); // Already a copy with fixed value
                    finalSum += rawElementFromFirstPass.getPercentageComposition();
                } else {
                    // This must be one of the elements from tempVariableElements
                    if (varElemIdx < tempVariableElements.size()) {
                        Element processedVarElement = tempVariableElements.get(varElemIdx);
                        finalVariantElements.add(processedVarElement);
                        finalSum += processedVarElement.getPercentageComposition();

                        // Final min/max check for this variable element
                        if (processedVarElement.getPercentageCompositionMin() != null &&
                                processedVarElement.getPercentageComposition() < (processedVarElement.getPercentageCompositionMin() - POST_NORM_CHECK_DELTA)) {
                            logger.info("Sample discarded post-norm: Element " + processedVarElement.getSymbol() +
                                    " (" + processedVarElement.getPercentageComposition() + ") below min " + processedVarElement.getPercentageCompositionMin());
                            constraintViolatedAfterNormalization = true;
                            break;
                        }
                        if (processedVarElement.getPercentageCompositionMax() != null &&
                                processedVarElement.getPercentageComposition() > (processedVarElement.getPercentageCompositionMax() + POST_NORM_CHECK_DELTA)) {
                            logger.info("Sample discarded post-norm: Element " + processedVarElement.getSymbol() +
                                    " (" + processedVarElement.getPercentageComposition() + ") above max " + processedVarElement.getPercentageCompositionMax());
                            constraintViolatedAfterNormalization = true;
                            break;
                        }
                        varElemIdx++;
                    } else {
                        // Should not happen if logic is correct
                        logger.severe("Mismatch between currentRawVariantElements and tempVariableElements. Discarding sample.");
                        constraintViolatedAfterNormalization = true;
                        break;
                    }
                }
            }

            if (constraintViolatedAfterNormalization) {
                continue;
            }

            finalSum = CommonUtils.roundToNDecimals(finalSum, 2); // Round final sum before check
            if (Math.abs(finalSum - 100.0) > FINAL_SUM_TOLERANCE) {
                logger.info("Final sum " + finalSum + " is too far from 100. Discarding sample: " + commonUtils.buildCompositionString(finalVariantElements));
                continue;
            }

            // If all checks pass
            logger.info("New composition added: " + commonUtils.buildCompositionString(finalVariantElements));
            variations.add(finalVariantElements);
        }
    }

    public void getUniformDistribution(int index, ArrayList<Element> original, double varyBy, double limit,
                                       double currentSum, ArrayList<Element> currentCombo,
                                       ArrayList<ArrayList<Element>> results) {

        Element elementAtIndex = original.get(index);
        double originalVal = elementAtIndex.getPercentageComposition();
        Double minComp = elementAtIndex.getPercentageCompositionMin();
        Double maxComp = elementAtIndex.getPercentageCompositionMax();
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
                    ArrayList<Element> newComposition = commonUtils.deepCopy(currentCombo);
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
                    ArrayList<Element> newComposition = commonUtils.deepCopy(currentCombo);
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
