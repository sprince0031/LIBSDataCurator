package com.medals.libsdatagenerator.service;

import java.util.Arrays;
import java.util.Random;
import java.util.logging.Logger;

/**
 * Generates compositional samples using Dirichlet distribution
 * @author Siddharth Prince | 02/06/25 15:00
 */
public class DirichletSampler {
    private static final Logger logger = Logger.getLogger(DirichletSampler.class.getName());

    private final double[] concentrationParameters;
    private final String[] elementOrder;
    private final Random random;
    private final double[] minConstraints;
    private final double[] maxConstraints;

    // Numerical constants
    private static final double EPSILON = 1e-10;
    private static final int MAX_REJECTION_ATTEMPTS = 1000;

    /**
     * Creates a Dirichlet sampler with specified concentration parameters
     *
     * @param alphas Concentration parameters for each element
     * @param elementOrder Array of element symbols in the same order as alphas
     */
    public DirichletSampler(double[] alphas, String[] elementOrder) {
        this(alphas, elementOrder, null, null);
    }

    /**
     * Creates a Dirichlet sampler with concentration parameters and constraints
     *
     * @param alphas Concentration parameters for each element
     * @param elementOrder Array of element symbols in the same order as alphas
     * @param minConstraints Minimum percentage constraints for each element (can be null)
     * @param maxConstraints Maximum percentage constraints for each element (can be null)
     */
    public DirichletSampler(double[] alphas, String[] elementOrder,
                            double[] minConstraints, double[] maxConstraints) {
        if (alphas == null || elementOrder == null) {
            throw new IllegalArgumentException("Alphas and element order cannot be null");
        }
        if (alphas.length != elementOrder.length) {
            throw new IllegalArgumentException("Alphas and element order must have same length");
        }

        this.concentrationParameters = alphas.clone();
        this.elementOrder = elementOrder.clone();
        this.random = new Random();

        // Set up constraints (convert percentages to proportions)
        this.minConstraints = minConstraints != null ?
                Arrays.stream(minConstraints).map(x -> x / 100.0).toArray() : null;
        this.maxConstraints = maxConstraints != null ?
                Arrays.stream(maxConstraints).map(x -> x / 100.0).toArray() : null;

        logger.info("Created Dirichlet sampler for " + alphas.length + " elements");
        logger.info("Concentration parameters: " + Arrays.toString(alphas));
    }

    /**
     * Generates a single compositional sample
     *
     * @return Array of element percentages that sum to 100%
     */
    public double[] generateSample() {
        double[] sample = null;
        int attempts = 0;

        while (sample == null && attempts < MAX_REJECTION_ATTEMPTS) {
            sample = generateCandidateSample();

            if (!satisfiesConstraints(sample)) {
                sample = null;
                attempts++;
            }
        }

        if (sample == null) {
            logger.warning("Failed to generate valid sample after " + MAX_REJECTION_ATTEMPTS +
                    " attempts, using constraint-adjusted sample");
            sample = generateConstraintAdjustedSample();
        }

        // Convert proportions back to percentages
        double[] percentages = new double[sample.length];
        for (int i = 0; i < sample.length; i++) {
            percentages[i] = sample[i] * 100.0;
        }

        return percentages;
    }

    /**
     * Generates multiple compositional samples
     *
     * @param numSamples Number of samples to generate
     * @return Array of sample arrays, each containing element percentages
     */
    public double[][] generateSamples(int numSamples) {
        double[][] samples = new double[numSamples][];

        logger.info("Generating " + numSamples + " Dirichlet samples");

        for (int i = 0; i < numSamples; i++) {
            samples[i] = generateSample();

            if (i > 0 && i % 1000 == 0) {
                logger.info("Generated " + i + " samples");
            }
        }

        logger.info("Successfully generated " + numSamples + " samples");
        return samples;
    }

    /**
     * Generates a candidate sample using gamma distribution approach
     */
    private double[] generateCandidateSample() {
        double[] gammaValues = new double[concentrationParameters.length];
        double sum = 0.0;

        // Generate gamma random variables
        for (int i = 0; i < concentrationParameters.length; i++) {
            gammaValues[i] = generateGamma(concentrationParameters[i], 1.0);
            sum += gammaValues[i];
        }

        // Normalize to create Dirichlet sample
        double[] sample = new double[concentrationParameters.length];
        for (int i = 0; i < sample.length; i++) {
            sample[i] = gammaValues[i] / sum;
        }

        return sample;
    }

    /**
     * Generates a gamma random variable using Marsaglia and Tsang method
     */
    private double generateGamma(double shape, double scale) {
        if (shape < 1.0) {
            // For shape < 1, use acceptance-rejection method
            return generateGammaSmallShape(shape, scale);
        } else {
            // For shape >= 1, use Marsaglia and Tsang method
            return generateGammaMarsaglia(shape, scale);
        }
    }

    /**
     * Generates gamma random variable for shape < 1
     */
    private double generateGammaSmallShape(double shape, double scale) {
        double c = (1.0 / shape);
        double d = ((1.0 - shape) * Math.pow(shape, (shape / (1.0 - shape))));

        while (true) {
            double u = random.nextDouble();
            double v = random.nextDouble();
            double w = u * d;

            if (u <= c) {
                double x = Math.pow(w, c);
                if (v <= Math.exp(-x)) {
                    return x * scale;
                }
            } else {
                double x = -Math.log((1.0 - w) / (1.0 - c));
                if (v <= Math.pow(x, shape - 1.0)) {
                    return x * scale;
                }
            }
        }
    }

    /**
     * Generates gamma random variable using Marsaglia and Tsang method for shape >= 1
     */
    private double generateGammaMarsaglia(double shape, double scale) {
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);

        while (true) {
            double x, v;

            do {
                x = random.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0);

            v = v * v * v;
            double u = random.nextDouble();

            if (u < 1.0 - 0.0331 * x * x * x * x) {
                return d * v * scale;
            }

            if (Math.log(u) < 0.5 * x * x + d * (1.0 - v + Math.log(v))) {
                return d * v * scale;
            }
        }
    }

    /**
     * Checks if a sample satisfies the constraints
     */
    private boolean satisfiesConstraints(double[] sample) {
        if (minConstraints != null) {
            for (int i = 0; i < sample.length; i++) {
                if (sample[i] < minConstraints[i] - EPSILON) {
                    return false;
                }
            }
        }

        if (maxConstraints != null) {
            for (int i = 0; i < sample.length; i++) {
                if (sample[i] > maxConstraints[i] + EPSILON) {
                    return false;
                }
            }
        }

        return true;
    }

    /**
     * Generates a constraint-adjusted sample when rejection sampling fails
     */
    private double[] generateConstraintAdjustedSample() {
        double[] sample = generateCandidateSample();

        // Apply constraint adjustments
        if (minConstraints != null) {
            for (int i = 0; i < sample.length; i++) {
                if (sample[i] < minConstraints[i]) {
                    sample[i] = minConstraints[i];
                }
            }
        }

        if (maxConstraints != null) {
            for (int i = 0; i < sample.length; i++) {
                if (sample[i] > maxConstraints[i]) {
                    sample[i] = maxConstraints[i];
                }
            }
        }

        // Renormalize to ensure sum equals 1
        double sum = Arrays.stream(sample).sum();
        if (sum > EPSILON) {
            for (int i = 0; i < sample.length; i++) {
                sample[i] /= sum;
            }
        }

        return sample;
    }

    /**
     * Gets the element order used by this sampler
     */
    public String[] getElementOrder() {
        return elementOrder.clone();
    }

    /**
     * Gets the concentration parameters used by this sampler
     */
    public double[] getConcentrationParameters() {
        return concentrationParameters.clone();
    }

    /**
     * Calculates the expected mean composition (for validation)
     */
    public double[] getExpectedMeans() {
        double sum = Arrays.stream(concentrationParameters).sum();
        double[] means = new double[concentrationParameters.length];

        for (int i = 0; i < means.length; i++) {
            means[i] = (concentrationParameters[i] / sum) * 100.0; // Convert to percentage
        }

        return means;
    }

    /**
     * Calculates the expected variances (for validation)
     */
    public double[] getExpectedVariances() {
        double sum = Arrays.stream(concentrationParameters).sum();
        double[] variances = new double[concentrationParameters.length];

        for (int i = 0; i < variances.length; i++) {
            double mean = concentrationParameters[i] / sum;
            variances[i] = (mean * (1 - mean) / (sum + 1)) * 10000.0; // Convert to percentage^2
        }

        return variances;
    }
}

