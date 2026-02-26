package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.model.Spectrum;
import org.apache.commons.math3.analysis.interpolation.LinearInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class SpectrumUtils {
    private Logger logger = Logger.getLogger(SpectrumUtils.class.getName());

    /**
     * Calculates the average spectrum from multiple shots.
     *
     * @param spectra List of intensity arrays
     * @return Average intensity array
     */
    public double[] calculateAverageSpectrum(List<double[]> spectra) {
        if (spectra.isEmpty()) {
            return new double[0];
        }

        int length = spectra.get(0).length;
        double[] avg = new double[length];

        for (double[] spectrum : spectra) {
            for (int i = 0; i < Math.min(length, spectrum.length); i++) {
                avg[i] += spectrum[i];
            }
        }

        for (int i = 0; i < length; i++) {
            avg[i] /= spectra.size();
        }

        return avg;
    }

    /**
     * Performs linear interpolation on a spectrum
     * @param waveMap TreeMap of wavelength -> intensity i.e., spectrum to interpolate on
     * @param targetGrid List of target wavelength arrays
     * @return Array of intensities aligned to the target wavelength grid.
     */
    public double[] interpolateSpectrum(Map<Double, Double> waveMap, double[] targetGrid) {
        logger.info("Interpolating to target wavelength grid...");

        Spectrum spectrum = new Spectrum(waveMap);
        double[] originalGrid = spectrum.getWavelengths();
        double[] originalIntensities = spectrum.getIntensities();
        LinearInterpolator interpolator = new LinearInterpolator();
        PolynomialSplineFunction interpolationFunction = interpolator.interpolate(originalGrid, originalIntensities);

        double[] aligned = new double[targetGrid.length];
        double minX = originalGrid[0];
        double maxX = originalGrid[originalGrid.length - 1];
        double firstY = originalIntensities[0];
        double lastY = originalIntensities[originalIntensities.length - 1];

        // 2. Evaluate for every point in the new grid
        for (int i = 0; i < targetGrid.length; i++) {
            double x = targetGrid[i];

            if (x < minX) {
                // numpy.interp default: fill with the first value
                aligned[i] = firstY;
            } else if (x > maxX) {
                // numpy.interp default: fill with the last value
                aligned[i] = lastY;
            } else {
                // Standard interpolation
                aligned[i] = interpolationFunction.value(x);
            }
        }
        return aligned;
    }

    public List<Double> normaliseAndScaleSpectrum(double[] spectrum, double scaleFactor) {
        List<Double> scaledSpectrum = new ArrayList<>();
        for (double val : normaliseSpectrum(spectrum)) {
            scaledSpectrum.add(val * scaleFactor);
        }
        return scaledSpectrum;
    }

    public double[] combineSpectra(double[] spectrum1, double[] spectrum2, double weight) {
        double[] combined = new double[spectrum1.length];
        for (int i = 0; i < spectrum1.length; i++) {
            combined[i] = weight * spectrum1[i] + (1.0 - weight) * spectrum2[i];
        }
        return combined;
    }

    /**
     * Normalises a spectrum to [0, 1] range.
     * If spectrum is empty or has no positive values, returns array of zeros.
     *
     * @param spectrum Input intensity array
     * @return Normalised array with values in [0, 1] range
     */
    public double[] normaliseSpectrum(double[] spectrum) {
        if (spectrum == null || spectrum.length == 0) {
            logger.warning("Empty spectrum provided for normalization, returning empty array");
            return new double[0];
        }

        double max = Arrays.stream(spectrum).max().orElse(0.0);
        if (max <= 0) {
            logger.fine("Spectrum has no positive values, returning zeros");
            return new double[spectrum.length]; // Returns array of zeros
        }

        double[] normalized = new double[spectrum.length];
        for (int i = 0; i < spectrum.length; i++) {
            normalized[i] = spectrum[i] / max;
        }
        return normalized;
    }

    /**
     * Calculates Root Mean Square Error (RMSE) between two spectra.
     * @param spectrum1 First spectrum
     * @param spectrum2 Second spectrum
     * @return RMSE value
     */
    public double calculateRMSE(double[] spectrum1, double[] spectrum2) {
        if (spectrum1.length != spectrum2.length || spectrum1.length == 0) {
            return Double.MAX_VALUE;
        }

        double sumSquaredError = 0;
        for (int i = 0; i < spectrum1.length; i++) {
            double diff = spectrum1[i] - spectrum2[i];
            sumSquaredError += diff * diff;
        }

        return Math.sqrt(sumSquaredError / spectrum1.length);
    }

    /**
     * Calculates the square of the Pearson correlation coefficient between two spectra to compare their similarities
     * @param synthetic Array representing synthetic spectrum
     * @param measured Array representing real and measured LIBS spectrum
     * @return r^2
     */
    public double calculateSpectralSimilarity(double[] synthetic, double[] measured) {
        PearsonsCorrelation pc = new PearsonsCorrelation();
        double correlation = pc.correlation(synthetic, measured);

        // Return r^2 (0 to 1)
        return correlation * correlation;
    }

    /**
     * Clip spectrum of sharp drop-offs at either ends
     */
    public Spectrum clipSpectrum(double[] wavelengths, double[] intensities) {
        int startindex = 0;
        double lastAvgIntensity = intensities[0];
        double sumIntensity = intensities[0];
        for (int i = 1; i < 100; i++) {
            sumIntensity += intensities[i];
            double diff = Math.abs(sumIntensity/(i+1) - lastAvgIntensity);
            if (diff > 100) {
                startindex = i;
                break;
            }
            lastAvgIntensity = sumIntensity/(i+1);
        }

        int endIndex = wavelengths.length - 1;
        lastAvgIntensity = intensities[wavelengths.length - 1];
        sumIntensity = intensities[wavelengths.length - 1];
        for (int i = wavelengths.length - 2; i > wavelengths.length - 100; i--) {
            sumIntensity += intensities[i];
            int count = wavelengths.length - i;
            double diff = Math.abs(sumIntensity/count - lastAvgIntensity);
            if (diff > 100) {
                endIndex = i;
                break;
            }
            lastAvgIntensity = sumIntensity/count;
        }

        double[] clippedWavelengths = Arrays.copyOfRange(wavelengths, startindex, endIndex);
        double[] clippedIntensities = Arrays.copyOfRange(intensities, startindex, endIndex);

        return new Spectrum(clippedWavelengths, clippedIntensities);
    }
}
