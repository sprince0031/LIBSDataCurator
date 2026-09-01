package com.medals.libsdatagenerator.sampler;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.SeriesStatistics;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.service.CompositionalVariations;
import com.medals.libsdatagenerator.service.ConcentrationParameterEstimator;
import org.apache.commons.rng.UniformRandomProvider;
import org.apache.commons.rng.simple.RandomSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DirichletSampler implements Sampler {

    private static final Logger logger = Logger.getLogger(DirichletSampler.class.getName());
    private static final double TARGET_TOTAL_PERCENTAGE = 100.0;
    private static final double BOUNDS_EPSILON = 1e-9;

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
    public void sample(MaterialGrade baseMaterialGrade, int numSamples, List<List<Element>> variations, Long seed) {

        List<Element> baseComp = baseMaterialGrade.getComposition();
        
        // Check if parent series is available, fall back to Gaussian if not
        if (baseMaterialGrade.getParentSeries() == null) {
            logger.info("No parent series available. Falling back to Gaussian sampling.");
            GaussianSampler.getInstance().sample(baseMaterialGrade, numSamples, variations, seed);
            return;
        }

        logger.info("Starting Dirichlet sampling with overview GUID: " +
                baseMaterialGrade.getParentSeries().getOverviewGuid());

        ConcentrationParameterEstimator parameterEstimator = new ConcentrationParameterEstimator();

        SeriesStatistics seriesStats = baseMaterialGrade.getOverviewStatistics();
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
                logger.log(Level.WARNING, "Error generating Dirichlet sample: ", e);
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
        if (baseComp == null || sample == null || baseComp.size() != sample.length) {
            throw new IllegalArgumentException("Base composition and Dirichlet sample must have matching size.");
        }

        List<Element> variation = new ArrayList<>();
        double[] mins = new double[baseComp.size()];
        double[] maxs = new double[baseComp.size()];

        for (int i = 0; i < baseComp.size(); i++) {
            Element baseElement = baseComp.get(i);
            double min = baseElement.getMin() != null ? baseElement.getMin() : 0.0;
            double max = baseElement.getMax() != null ? baseElement.getMax() : TARGET_TOTAL_PERCENTAGE;
            if (max < min) {
                max = min;
            }
            mins[i] = min;
            maxs[i] = max;
        }

        double[] boundedPercentages = generateBoundedPercentages(sample, mins, maxs);
        if (boundedPercentages == null) {
            throw new IllegalArgumentException("Could not generate bounded Dirichlet sample within element ranges.");
        }

        for (int i = 0; i < baseComp.size(); i++) {
            Element baseElement = baseComp.get(i);
            Element variationElement = new Element(
                    baseElement.getName(),
                    baseElement.getSymbol(),
                    boundedPercentages[i],
                    baseElement.getMin(),
                    baseElement.getMax(),
                    baseElement.getAverageComposition()
            );
            variation.add(variationElement);
        }

        return variation;
    }

    private double[] generateBoundedPercentages(double[] sample, double[] mins, double[] maxs) {
        double minTotal = 0.0;
        double maxTotal = 0.0;
        for (int i = 0; i < mins.length; i++) {
            minTotal += mins[i];
            maxTotal += maxs[i];
        }

        if (minTotal > TARGET_TOTAL_PERCENTAGE + BOUNDS_EPSILON ||
                maxTotal < TARGET_TOTAL_PERCENTAGE - BOUNDS_EPSILON) {
            return null;
        }

        double[] weights = getNormalizedWeights(sample, mins, maxs);
        if (weights == null) {
            return null;
        }

        double low = 0.0;
        double high = 1.0;
        while (sumWithScale(high, weights, mins, maxs) < TARGET_TOTAL_PERCENTAGE - BOUNDS_EPSILON &&
                high < 1e12) {
            high *= 2.0;
        }

        if (sumWithScale(high, weights, mins, maxs) < TARGET_TOTAL_PERCENTAGE - BOUNDS_EPSILON) {
            return null;
        }

        for (int i = 0; i < 100; i++) {
            double mid = (low + high) / 2.0;
            double sum = sumWithScale(mid, weights, mins, maxs);
            if (sum < TARGET_TOTAL_PERCENTAGE) {
                low = mid;
            } else {
                high = mid;
            }
        }

        double[] values = new double[mins.length];
        for (int i = 0; i < mins.length; i++) {
            values[i] = clamp(mins[i] + high * weights[i], mins[i], maxs[i]);
        }

        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        double difference = TARGET_TOTAL_PERCENTAGE - total;

        if (difference > 0) {
            for (int i = 0; i < values.length && difference > BOUNDS_EPSILON; i++) {
                double available = maxs[i] - values[i];
                if (available > 0) {
                    double increment = Math.min(available, difference);
                    values[i] += increment;
                    difference -= increment;
                }
            }
        } else if (difference < 0) {
            double excess = -difference;
            for (int i = 0; i < values.length && excess > BOUNDS_EPSILON; i++) {
                double reducible = values[i] - mins[i];
                if (reducible > 0) {
                    double decrement = Math.min(reducible, excess);
                    values[i] -= decrement;
                    excess -= decrement;
                }
            }
            difference = -excess;
        }

        return Math.abs(difference) <= 1e-6 ? values : null;
    }

    private double[] getNormalizedWeights(double[] sample, double[] mins, double[] maxs) {
        double[] weights = new double[sample.length];
        double positiveWeightSum = 0.0;

        for (int i = 0; i < sample.length; i++) {
            if (maxs[i] - mins[i] <= BOUNDS_EPSILON) {
                weights[i] = 0.0;
                continue;
            }
            double rawWeight = (Double.isFinite(sample[i]) && sample[i] > 0.0) ? sample[i] : 0.0;
            if (rawWeight == 0.0) {
                rawWeight = 1e-9;
            }
            weights[i] = rawWeight;
            positiveWeightSum += rawWeight;
        }

        if (positiveWeightSum <= 0.0) {
            return null;
        }

        for (int i = 0; i < weights.length; i++) {
            weights[i] /= positiveWeightSum;
        }
        return weights;
    }

    private double sumWithScale(double scale, double[] weights, double[] mins, double[] maxs) {
        double sum = 0.0;
        for (int i = 0; i < weights.length; i++) {
            sum += clamp(mins[i] + scale * weights[i], mins[i], maxs[i]);
        }
        return sum;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

}
