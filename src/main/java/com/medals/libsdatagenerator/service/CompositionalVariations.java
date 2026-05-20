package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.UserInputConfig;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.VariationMode;
import com.medals.libsdatagenerator.sampler.DirichletSampler;
import com.medals.libsdatagenerator.sampler.GaussianSampler;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * @author Siddharth Prince | 13/02/25 13:14
 */

public class CompositionalVariations {

    private final Logger logger = Logger.getLogger(CompositionalVariations.class.getName());
    private static CompositionalVariations instance = null;
    public static final double POST_NORM_CHECK_DELTA = 0.0001;
    private static final double FINAL_SUM_TOLERANCE = 0.1;


    public static CompositionalVariations getInstance() {
        if (instance == null) {
            instance = new CompositionalVariations();
        }
        return instance;
    }

    public List<List<Element>> generateCompositionalVariations(MaterialGrade materialGrade,
                                                               UserInputConfig config) throws Exception {

        List<List<Element>> compositions = new ArrayList<>();
        if (materialGrade.getComposition() == null || materialGrade.getComposition().isEmpty()) {
            logger.warning("Original composition is null or empty. Cannot generate variations.");
            return compositions; // Return empty list
        }
        compositions.add(materialGrade.getComposition()); // Adding the original composition

        boolean allElementsAreFixed = true;
        for (Element el : materialGrade.getComposition()) {
            Double minComp = el.getMin();
            Double maxComp = el.getMax();
            if (!(minComp != null && minComp.equals(maxComp))) { // No need to check maxComp for null. Handled by equals()
                allElementsAreFixed = false;
                break;
            }
        }

        List<Element> effectiveComposition = materialGrade.getComposition();
        if (allElementsAreFixed) {
            // materialGrade.getComposition() is not empty here due to the check at the beginning.
            logger.info("All elements in the input composition are fixed. Applying fallback variation logic, ignoring X:X constraints for sampling.");
            effectiveComposition = new ArrayList<>();
            for (Element el : materialGrade.getComposition()) {
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

        materialGrade.setComposition(effectiveComposition);

        // Generate all combinations by Uniform distribution
        // System.out.println("\nGenerating different combinations for the input composition (refer log for list)...");
        logger.info("\nGenerating different combinations for the input composition (refer log for list)...");

        int numVariationsToGenerate = Math.max(0, config.numSamples - 1);

        if (config.variationMode == VariationMode.GAUSSIAN) {
            GaussianSampler.getInstance().sample(materialGrade, numVariationsToGenerate, compositions, config.seed);
        } else if (config.variationMode == VariationMode.DIRICHLET) {
            DirichletSampler.getInstance().sample(materialGrade, numVariationsToGenerate, compositions, config.seed);
        } else { // For uniform distribution
            System.out.println("Unsupported variation mode. Please try again with a valid variation mode.");
            throw new Exception("Unsupported variation mode. Please try again with a valid variation mode.");
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

}
