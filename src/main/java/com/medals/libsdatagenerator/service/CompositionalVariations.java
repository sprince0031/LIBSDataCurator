package com.medals.libsdatagenerator.service;


import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.util.CommonUtils;

import java.util.ArrayList;
import java.util.Random;
import java.util.logging.Logger;

/**
 * @author Siddharth Prince | 13/02/25 13:14
 */

public class CompositionalVariations {

    public static CompositionalVariations instance = null;
    private final CommonUtils commonUtils;
    private final Logger logger = Logger.getLogger(CompositionalVariations.class.getName());

    public CompositionalVariations() {
        commonUtils = new CommonUtils();
    }

    public static CompositionalVariations getInstance() {
        if (instance == null) {
            instance = new CompositionalVariations();
        }
        return instance;
    }

    public void gaussianSampling(ArrayList<Element> baseComp, double maxDelta, int samples,
                                                          ArrayList<ArrayList<Element>> variations) {
        Random rand = new Random();

        while (variations.size() < samples) {
            ArrayList<Element> variant = new ArrayList<>();
            double total = 0;

            // First pass: Apply Gaussian noise
            for(Element e : baseComp) {
                logger.info("Processing element: " + e.toString());
                double baseVal = e.getPercentageComposition();
                double delta = rand.nextGaussian() * LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK.get(e.getSymbol()); // TODO: Refactor to use matweb
                delta = Math.max(-maxDelta, Math.min(delta, maxDelta));
                double newVal = baseVal + delta;
                newVal = Math.max(0, newVal);
                if (newVal == 0) {
                    logger.info("Element: " + e.getName() + " sampled with 0 percentage. Skipping.");
                    break; // Do not take any variant with any of the element percentages being 0.
                }
                variant.add(new Element(e.getName(), e.getSymbol(), newVal, null, null, null));
                total += newVal;
            }

            if (variant.size() != baseComp.size()) {
                logger.info("Skipping sample due to zero in composition");
                continue; // Move on to the next sample without considering the current variant which had a 0 %
            }

            // Second pass: Normalize to 100%
            double scalingFactor = 100.0 / total;
            for(Element e : variant) {
                double normalized = e.getPercentageComposition() * scalingFactor;
                e.setPercentageComposition(
                        CommonUtils.roundToNDecimals(normalized, e.getNumberDecimalPlaces())
                );
            }
            logger.info("New composition added: " + commonUtils.buildCompositionString(variant));
            variations.add(variant);
        }
    }

    public void getUniformDistribution(int index, ArrayList<Element> original, double varyBy, double limit,
                                     double currentSum, ArrayList<Element> currentCombo,
                                     ArrayList<ArrayList<Element>> results) {

        // If this is the last element, we must "fix" it so total = 100%
        if (index == original.size() - 1) {
            double originalVal = original.get(index).getPercentageComposition();
            // Allowed range for this element:
            double low = Math.max(0, originalVal - limit);
            double high = Math.min(100, originalVal + limit);

            // The only possible value (if valid) to keep sum at 100
            double lastVal = 100 - currentSum;

            // Check if lastVal is within [low, high]
            if (lastVal >= low && lastVal <= high) {
                // Accept this composition
                currentCombo.add(new Element(
                        original.get(index).getName(),
                        original.get(index).getSymbol(),
                        lastVal,
                        null,
                        null,
                        null
                ));
                ArrayList<Element> newComposition = commonUtils.deepCopy(currentCombo);
                results.add(newComposition);
                logger.info("New composition added: " + commonUtils.buildCompositionString(newComposition));
                currentCombo.removeLast();
            }
            // If lastVal not in range, no valid composition from this path
            return;
        }

        // Not the last element: try all valid values from (originalVal-limit) to (originalVal+limit)
        double originalVal = original.get(index).getPercentageComposition();
        double low = Math.max(0, originalVal - limit);
        double high = Math.min(100, originalVal + limit);

        for (double val = low; val <= high; val += varyBy) {
            // Only proceed if we won't exceed 100% so far
            if (currentSum + val <= 100) {
                currentCombo.add(new Element(
                        original.get(index).getName(),
                        original.get(index).getSymbol(),
                        val,
                        null,
                        null,
                        null
                ));
                // Recurse for the next element
                getUniformDistribution(index + 1, original, varyBy, limit,
                        currentSum + val, currentCombo, results);

                // Backtrack (undo)
                currentCombo.removeLast();
            }
        }
    }

}
