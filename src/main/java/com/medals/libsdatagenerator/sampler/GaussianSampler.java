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

        double maxDelta = (Double) metadata.get("maxDelta");
        Random rand = new Random();

        // A map to store the standard deviations
        // TODO: load from a properties file.
        Map<String, Double> stdDevs = LIBSDataGenConstants.ELEMENT_STD_DEVS_FALLBACK;

        while (variations.size() < numSamples) {
            List<Element> newVariation = new ArrayList<>();
            double totalPercentage = 0;

            // First pass: Generate new values for all elements
            for (Element baseElement : baseComp) {
                double stdDev = stdDevs.getOrDefault(baseElement.getSymbol(), 0.1); // Default to 0.1 if not in map
                double delta = rand.nextGaussian() * stdDev;
                delta = Math.max(-maxDelta, Math.min(delta, maxDelta));

                double newPercentage = baseElement.getPercentageComposition() + delta;

                // Clamp the new percentage to the element's min/max range
                if (baseElement.getMin() != null) {
                    newPercentage = Math.max(newPercentage, baseElement.getMin());
                }
                if (baseElement.getMax() != null) {
                    newPercentage = Math.min(newPercentage, baseElement.getMax());
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
