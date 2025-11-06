package com.medals.libsdatagenerator.sampler;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.SeriesStatistics;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.service.CompositionalVariations;
import com.medals.libsdatagenerator.service.ConcentrationParameterEstimator;
import com.medals.libsdatagenerator.service.MatwebDataService;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.apache.commons.rng.UniformRandomProvider;
import org.apache.commons.rng.simple.RandomSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class DirichletSampler implements Sampler {

    private static final Logger logger = Logger.getLogger(DirichletSampler.class.getName());

    private static DirichletSampler instance = null;

    public static DirichletSampler getInstance() {
        if (instance == null) {
            instance = new DirichletSampler();
        }
        return instance;
    }

    /**
     * New Dirichlet sampling method for compositional variations
     *
     * @param baseMaterialGrade Base material grade composition and metadata
     * @param numSamples Number of numSamples to generate
     * @param variations List to store generated variations
     */
    @Override
    public void sample(MaterialGrade baseMaterialGrade, int numSamples,
                       List<List<Element>> variations, Long seed) {

        List<Element> baseComp = baseMaterialGrade.getComposition();
        String overviewGuid = baseMaterialGrade.getParentSeries().getOverviewGuid();

        logger.info("Starting Dirichlet sampling with overview GUID: " + overviewGuid);

        ConcentrationParameterEstimator parameterEstimator = new ConcentrationParameterEstimator();

        // Get series statistics from overview sheet
        SeriesStatistics seriesStats = MatwebDataService.getInstance().getSeriesStatistics(overviewGuid);
        if (seriesStats == null) {
            logger.severe("Failed to extract series statistics from overview sheet. Falling back to Gaussian sampling.");
            // Fallback to Gaussian sampling
            GaussianSampler.getInstance().sample(baseMaterialGrade, numSamples, variations, seed);
            return;
        }

        logger.info("Successfully extracted series statistics: " + seriesStats);

        // Create element order array from base composition
        String[] elementOrder = new String[baseComp.size()];

        for (int i = 0; i < baseComp.size(); i++) {
            Element element = baseComp.get(i);
            elementOrder[i] = element.getSymbol();
        }

        // Estimate Dirichlet concentration parameters for the base composition elements only
        double[] concentrationParams = parameterEstimator.estimateParametersForElements(seriesStats, elementOrder);
        if (concentrationParams == null || !parameterEstimator.validateParameters(concentrationParams)) {
            logger.severe("Failed to estimate valid Dirichlet parameters. Falling back to Gaussian sampling.");
            GaussianSampler.getInstance().sample(baseMaterialGrade, numSamples, variations, seed);
            return;
        }

        // Verify arrays have same length
        if (concentrationParams.length != elementOrder.length) {
            logger.severe("Mismatch between concentration parameters (" + concentrationParams.length +
                    ") and element order (" + elementOrder.length + "). Falling back to Gaussian sampling.");
            GaussianSampler.getInstance().sample(baseMaterialGrade, numSamples, variations, seed);
            return;
        }

        UniformRandomProvider rng = seed != null ?
                RandomSource.XO_RO_SHI_RO_128_PP.create(seed) :
                RandomSource.XO_RO_SHI_RO_128_PP.create();

        logger.info("Starting Dirichlet sampling with " + (seed != null ? "seed: " + seed : "random seed"));

        org.apache.commons.rng.sampling.distribution.DirichletSampler sampler = org.apache.commons.rng.sampling.distribution.DirichletSampler.of(rng, concentrationParams);

        logger.info("Created Dirichlet sampler with parameters: " + Arrays.toString(concentrationParams));


        // Generate numSamples
        int successfulSamples = 0;
        int attempts = 0;
        int maxAttempts = numSamples * 10; // Allow up to 10x attempts to account for constraint violations

        while (successfulSamples < numSamples && attempts < maxAttempts) {
            attempts++;

            try {
                double[] sample = sampler.sample();
                logger.info("Sample: " + Arrays.toString(sample));
                List<Element> variation = createElementVariation(baseComp, sample);

                if (CompositionalVariations.getInstance().validateVariation(variation)) {
                    variations.add(variation);
                    successfulSamples++;

                    if (successfulSamples % 100 == 0) {
                        logger.info("Generated " + successfulSamples + " valid Dirichlet numSamples");
                    }
                } else {
                    logger.fine("Sample failed validation, attempting another");
                }

            } catch (Exception e) {
                logger.warning("Error generating Dirichlet sample: " + e.getMessage());
            }
        }

        logger.info("Dirichlet sampling completed: " + successfulSamples + " successful numSamples out of " +
                attempts + " attempts");

        if (successfulSamples < numSamples) {
            logger.warning("Could not generate all requested numSamples. Generated " + successfulSamples +
                    " out of " + numSamples + " requested numSamples.");
        }
    }

    /**
     * Creates an Element variation from a Dirichlet sample
     */
    private List<Element> createElementVariation(List<Element> baseComp, double[] sample) {
        List<Element> variation = new ArrayList<>();
        double totalPercentage = 0;
        for (int i = 0; i < baseComp.size(); i++) {
            Element baseElement = baseComp.get(i);
            double min = baseElement.getMin();
            double max = baseElement.getMax();

            // Scale the sample to the element's allowed range
            double newPercentage = min + (sample[i] * (max - min));
            totalPercentage += newPercentage;

            // Round to appropriate decimal places
            double roundedValue = CommonUtils.roundToNDecimals(newPercentage, baseElement.getNumberDecimalPlaces());

            Element variationElement = new Element(
                    baseElement.getName(),
                    baseElement.getSymbol(),
                    roundedValue,
                    baseElement.getMin(),
                    baseElement.getMax(),
                    baseElement.getAverageComposition()
            );
            variation.add(variationElement);
        }

        // Normalize the composition to sum to 100%
        if (totalPercentage > 0) {
            for (Element element : variation) {
                element.setPercentageComposition((element.getPercentageComposition() / totalPercentage) * 100.0);
            }
        }
        return variation;
    }

}
