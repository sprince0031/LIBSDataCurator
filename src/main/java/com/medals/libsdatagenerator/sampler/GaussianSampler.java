package com.medals.libsdatagenerator.sampler;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.service.CompositionalVariations;
import com.medals.libsdatagenerator.util.CommonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

public class GaussianSampler implements Sampler {

    private static final Logger logger = Logger.getLogger(GaussianSampler.class.getName());

    private static GaussianSampler instance = null;

    public static GaussianSampler getInstance() {
        if (instance == null) {
            instance = new GaussianSampler();
        }
        return instance;
    }

    public void sample(List<Element> baseComp, int numSamples,
                       List<List<Element>> variations, Map<String, Object> metadata) {

        Random rand = new Random();

        // A map to store the standard deviations
        // TODO: load from a properties file.
        Map<String, Double> stdDevs = LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK;

        while (variations.size() < numSamples) {
            List<Element> newVariation = new ArrayList<>();
            double totalPercentage = 0;

            // First pass: Generate new values for all elements
            for (Element baseElement : baseComp) {
                double mu = baseElement.getPercentageComposition();
                Double min = baseElement.getMin();
                Double max = baseElement.getMax();

                // Calculating the max delta based on min-max range of element
                double maxDelta;
                if (min != null && max != null) { // The maximum possible change is half the range
                    maxDelta = (max - min) / 2.0;
                } else { // Fallback for elements without a defined range
                    maxDelta = mu * 0.1; // e.g., 10% of the base value
                }
                double stdDev = stdDevs.getOrDefault(baseElement.getSymbol(), 0.1); // Default to 0.1 if not in map
                double delta = rand.nextGaussian() * stdDev;

                // Clamping delta within allowed ranges
                delta = Math.max(-maxDelta, Math.min(delta, maxDelta));
                double newPercentage = mu + delta;

                // Clamp the new percentage to the element's min/max range
                if (min != null) {
                    newPercentage = Math.max(newPercentage, min);
                }
                if (max != null) {
                    newPercentage = Math.min(newPercentage, max);
                }
                newPercentage = Math.max(0, newPercentage); // Ensure non-negative

                Element newElement = new Element(
                        baseElement.getName(),
                        baseElement.getSymbol(),
                        newPercentage,
                        baseElement.getMin(),
                        baseElement.getMax(),
                        baseElement.getAverageComposition()
                );
                newVariation.add(newElement);
                totalPercentage += newPercentage;
            }

            // Second pass: Normalize the composition to sum to 100
            if (totalPercentage > 0) {
                for (Element element : newVariation) {
                    double normalizedPercentage = (element.getPercentageComposition() / totalPercentage) * 100.0;
                    element.setPercentageComposition(CommonUtils.roundToNDecimals(normalizedPercentage, element.getNumberDecimalPlaces()));
                }
            }

            // Validate the final composition before adding it
            if (CompositionalVariations.getInstance().validateVariation(newVariation)) {
                logger.info("New composition added: " + CommonUtils.getInstance().buildCompositionString(newVariation));
                variations.add(newVariation);
            } else {
                logger.info("Discarding invalid sample: " + CommonUtils.getInstance().buildCompositionString(newVariation));
            }
        }
    }

}
