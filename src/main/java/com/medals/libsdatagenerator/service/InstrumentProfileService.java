package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.InstrumentProfile;
import com.medals.libsdatagenerator.util.InputCompositionProcessor;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.math3.analysis.MultivariateFunction;
import org.apache.commons.math3.optim.*;
import org.apache.commons.math3.optim.nonlinear.scalar.*;
import org.apache.commons.math3.optim.nonlinear.scalar.noderiv.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Service for generating instrument profiles from real LIBS measurement data.
 * Extracts wavelength grids and optimizes two-zone plasma parameters to match
 * measured spectra with synthetic NIST data.
 * 
 * @author Siddharth Prince | 13/01/26 08:30
 */
public class InstrumentProfileService {

    private static final Logger logger = Logger.getLogger(InstrumentProfileService.class.getName());

    // Default optimization bounds
    private static final double TE_MIN = 0.3;   // Minimum plasma temperature (eV)
    private static final double TE_MAX = 3.0;   // Maximum plasma temperature (eV)
    private static final double NE_MIN = 1e15;  // Minimum electron density (cm^-3)
    private static final double NE_MAX = 1e18;  // Maximum electron density (cm^-3)
    private static final double WEIGHT_MIN = 0.1;  // Minimum zone weight
    private static final double WEIGHT_MAX = 0.9;  // Maximum zone weight

    // Optimization settings
    private static final int MAX_EVALUATIONS = 5000;
    private static final double CONVERGENCE_THRESHOLD = 1e-6;
    
    // Default number of decimal places for composition percentages
    private static final int DEFAULT_DECIMAL_PLACES = 3;

    private static InstrumentProfileService instance = null;

    public static InstrumentProfileService getInstance() {
        if (instance == null) {
            instance = new InstrumentProfileService();
        }
        return instance;
    }

    /**
     * Generates an instrument profile from a sample LIBS measurement CSV file.
     * 
     * @param sampleCsvPath Path to the sample CSV file with real LIBS readings
     * @param compositionString Composition of the reference material (e.g., "Fe-80,C-20")
     * @param instrumentName Optional name for the instrument
     * @return Generated InstrumentProfile
     * @throws IOException if file cannot be read
     */
    public InstrumentProfile generateProfile(Path sampleCsvPath, String compositionString, String instrumentName) 
            throws IOException {
        
        logger.info("Generating instrument profile from: " + sampleCsvPath);
        logger.info("Reference composition: " + compositionString);
        
        // 1. Extract wavelength grid from CSV header
        List<Double> wavelengthGrid = extractWavelengthGrid(sampleCsvPath);
        if (wavelengthGrid.isEmpty()) {
            throw new IOException("Failed to extract wavelength grid from CSV header");
        }
        logger.info("Extracted wavelength grid with " + wavelengthGrid.size() + " points");
        
        // 2. Extract measured spectra (all shots)
        List<double[]> measuredSpectra = extractMeasuredSpectra(sampleCsvPath, wavelengthGrid.size());
        logger.info("Extracted " + measuredSpectra.size() + " measurement shots");
        
        // 3. Calculate average measured spectrum
        double[] avgMeasuredSpectrum = calculateAverageSpectrum(measuredSpectra);
        
        // 4. Parse composition
        List<Element> composition = parseComposition(compositionString);
        if (composition.isEmpty()) {
            throw new IllegalArgumentException("Invalid composition string: " + compositionString);
        }
        
        // 5. Create initial profile
        InstrumentProfile profile = new InstrumentProfile(wavelengthGrid, 
                sampleCsvPath.toString(), compositionString);
        profile.setInstrumentName(instrumentName != null ? instrumentName : "Unknown");
        profile.setNumShots(measuredSpectra.size());
        
        // 6. Optimize plasma parameters
        logger.info("Starting two-zone plasma parameter optimization...");
        optimizePlasmaParameters(profile, avgMeasuredSpectrum, wavelengthGrid, composition);
        
        logger.info("Profile generation complete: " + profile);
        return profile;
    }

    /**
     * Extracts the wavelength grid from the CSV header line.
     * Expects wavelengths as column headers (numeric values).
     * 
     * @param csvPath Path to the CSV file
     * @return List of wavelength values
     * @throws IOException if file cannot be read
     */
    public List<Double> extractWavelengthGrid(Path csvPath) throws IOException {
        List<Double> wavelengths = new ArrayList<>();
        
        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("Empty CSV file");
            }
            
            String[] headers = headerLine.split(";");
            for (String header : headers) {
                String trimmed = header.trim().replaceAll("\"", "");
                try {
                    double wavelength = Double.parseDouble(trimmed);
                    // Filter out unreasonable wavelength values (typical LIBS range: 100-1000 nm)
                    if (wavelength >= 100 && wavelength <= 1000) {
                        wavelengths.add(wavelength);
                    }
                } catch (NumberFormatException e) {
                    // Not a wavelength column, skip (could be label column like "Shot", "ID", etc.)
                }
            }
        }
        
        // Sort wavelengths
        Collections.sort(wavelengths);
        return wavelengths;
    }

    /**
     * Extracts measured intensity spectra from the CSV file.
     * Each row represents one shot/measurement.
     * 
     * @param csvPath Path to the CSV file
     * @param numWavelengths Expected number of wavelength columns
     * @return List of intensity arrays, one per shot
     * @throws IOException if file cannot be read
     */
    public List<double[]> extractMeasuredSpectra(Path csvPath, int numWavelengths) throws IOException {
        List<double[]> spectra = new ArrayList<>();
        
        try (BufferedReader reader = Files.newBufferedReader(csvPath);
             CSVParser parser = CSVFormat.DEFAULT.withDelimiter(';').withFirstRecordAsHeader().parse(reader)) {
            
            // Identify wavelength columns
            Map<String, Integer> headerMap = parser.getHeaderMap();
            List<String> wavelengthColumns = new ArrayList<>();
            
            for (String header : headerMap.keySet()) {
                String trimmed = header.trim().replaceAll("\"", "");
                try {
                    double wavelength = Double.parseDouble(trimmed);
                    if (wavelength >= 100 && wavelength <= 1000) {
                        wavelengthColumns.add(header);
                    }
                } catch (NumberFormatException e) {
                    // Not a wavelength column
                }
            }
            
            // Sort by wavelength value
            wavelengthColumns.sort((a, b) -> {
                double wa = Double.parseDouble(a.trim().replaceAll("\"", ""));
                double wb = Double.parseDouble(b.trim().replaceAll("\"", ""));
                return Double.compare(wa, wb);
            });
            
            // Extract spectra
            for (CSVRecord record : parser) {
                double[] spectrum = new double[wavelengthColumns.size()];
                for (int i = 0; i < wavelengthColumns.size(); i++) {
                    try {
                        spectrum[i] = Double.parseDouble(record.get(wavelengthColumns.get(i)).trim());
                    } catch (IllegalArgumentException e) {
                        // IllegalArgumentException catches both NumberFormatException (which extends it)
                        // and cases where the column is missing from the record
                        spectrum[i] = 0.0;
                    }
                }
                spectra.add(spectrum);
            }
        }
        
        return spectra;
    }

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
     * Applies baseline correction to the spectrum.
     * Current implementation subtracts the minimum value from all points.
     * 
     * @param spectrum Input spectrum
     * @return Baseline corrected spectrum
     */
    public double[] applyBaselineCorrection(double[] spectrum) {
        if (spectrum == null || spectrum.length == 0) {
            return new double[0];
        }
        
        double min = Double.MAX_VALUE;
        for (double val : spectrum) {
            if (val < min) {
                min = val;
            }
        }
        
        double[] corrected = new double[spectrum.length];
        for (int i = 0; i < spectrum.length; i++) {
            corrected[i] = Math.max(0.0, spectrum[i] - min);
        }
        
        return corrected;
    }

    /**
     * Parses a composition string into a list of Elements.
     * 
     * @param compositionString Format: "Element1-Percentage1,Element2-Percentage2,..."
     * @return List of Element objects
     */
    private List<Element> parseComposition(String compositionString) {
        try {
            // Convert composition string to list format expected by generateElementsList
            List<String> compositionArray = Arrays.asList(compositionString.split(","));
            Map<String, Object> result = InputCompositionProcessor.getInstance()
                    .generateElementsList(compositionArray, DEFAULT_DECIMAL_PLACES);
            
            @SuppressWarnings("unchecked")
            List<Element> elements = (List<Element>) result.get("elementsList");
            return elements != null ? elements : new ArrayList<>();
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to parse composition using standard parser, using fallback", e);
            return parseCompositionFallback(compositionString);
        }
    }

    /**
     * Fallback composition parser for simple format "Element-Percentage,..."
     */
    private List<Element> parseCompositionFallback(String compositionString) {
        List<Element> elements = new ArrayList<>();
        String[] parts = compositionString.split(",");
        
        for (String part : parts) {
            String[] elemParts = part.trim().split("-");
            if (elemParts.length == 2) {
                try {
                    String symbol = elemParts[0].trim();
                    double percentage = Double.parseDouble(elemParts[1].trim());
                    elements.add(new Element(symbol, symbol, percentage, null, null, null));
                } catch (NumberFormatException e) {
                    logger.warning("Failed to parse element: " + part);
                }
            }
        }
        
        return elements;
    }

    /**
     * Optimizes the two-zone plasma parameters to best match measured spectrum.
     * Uses Nelder-Mead simplex optimization algorithm.
     * 
     * @param profile InstrumentProfile to update with optimized parameters
     * @param measuredSpectrum Average measured spectrum
     * @param wavelengthGrid Wavelength values
     * @param composition Material composition
     */
    private void optimizePlasmaParameters(InstrumentProfile profile, double[] measuredSpectrum,
                                         List<Double> wavelengthGrid, List<Element> composition) {
        
        // Normalize measured spectrum for comparison
        double[] normalizedMeasured = normalizeSpectrum(measuredSpectrum);
        
        // Define the objective function to minimize (RMSE between synthetic and measured)
        MultivariateFunction objectiveFunction = point -> {
            // Parameters: [hotCoreTe, hotCoreNe_log, coolPeripheryTe, coolPeripheryNe_log, hotCoreWeight]
            double hotCoreTe = clamp(point[0], TE_MIN, TE_MAX);
            double hotCoreNe = Math.pow(10, clamp(point[1], Math.log10(NE_MIN), Math.log10(NE_MAX)));
            double coolPeripheryTe = clamp(point[2], TE_MIN, TE_MAX);
            double coolPeripheryNe = Math.pow(10, clamp(point[3], Math.log10(NE_MIN), Math.log10(NE_MAX)));
            double hotCoreWeight = clamp(point[4], WEIGHT_MIN, WEIGHT_MAX);
            
            // Generate synthetic spectrum with these parameters
            double[] syntheticSpectrum = generateTwoZoneSyntheticSpectrum(
                    wavelengthGrid, composition,
                    hotCoreTe, hotCoreNe, coolPeripheryTe, coolPeripheryNe, hotCoreWeight);
            
            // Calculate RMSE
            return calculateRMSE(normalizedMeasured, normalizeSpectrum(syntheticSpectrum));
        };

        // Initial guess
        double[] initialGuess = {
                profile.getHotCoreTe(),
                Math.log10(profile.getHotCoreNe()),
                profile.getCoolPeripheryTe(),
                Math.log10(profile.getCoolPeripheryNe()),
                profile.getHotCoreWeight()
        };

        try {
            // Use Nelder-Mead simplex optimizer
            NelderMeadSimplex simplex = new NelderMeadSimplex(5);
            SimplexOptimizer optimizer = new SimplexOptimizer(CONVERGENCE_THRESHOLD, CONVERGENCE_THRESHOLD);
            
            PointValuePair result = optimizer.optimize(
                    new MaxEval(MAX_EVALUATIONS),
                    new ObjectiveFunction(objectiveFunction),
                    GoalType.MINIMIZE,
                    new InitialGuess(initialGuess),
                    simplex
            );
            
            // Update profile with optimized parameters
            double[] optimizedParams = result.getPoint();
            profile.setHotCoreTe(clamp(optimizedParams[0], TE_MIN, TE_MAX));
            profile.setHotCoreNe(Math.pow(10, clamp(optimizedParams[1], Math.log10(NE_MIN), Math.log10(NE_MAX))));
            profile.setCoolPeripheryTe(clamp(optimizedParams[2], TE_MIN, TE_MAX));
            profile.setCoolPeripheryNe(Math.pow(10, clamp(optimizedParams[3], Math.log10(NE_MIN), Math.log10(NE_MAX))));
            profile.setHotCoreWeight(clamp(optimizedParams[4], WEIGHT_MIN, WEIGHT_MAX));
            profile.setCoolPeripheryWeight(1.0 - profile.getHotCoreWeight());
            
            // Calculate final RMSE and fit score
            double finalRmse = result.getValue();
            profile.setRmse(finalRmse);
            profile.setFitScore(1.0 - Math.min(finalRmse, 1.0));  // Convert RMSE to 0-1 score
            
            logger.info(String.format("Optimization complete. RMSE: %.4f, Fit score: %.4f", 
                    finalRmse, profile.getFitScore()));
            logger.info(String.format("Hot core: Te=%.2f eV, Ne=%.2e cm^-3, weight=%.2f",
                    profile.getHotCoreTe(), profile.getHotCoreNe(), profile.getHotCoreWeight()));
            logger.info(String.format("Cool periphery: Te=%.2f eV, Ne=%.2e cm^-3, weight=%.2f",
                    profile.getCoolPeripheryTe(), profile.getCoolPeripheryNe(), profile.getCoolPeripheryWeight()));
            
        } catch (Exception e) {
            logger.log(Level.WARNING, "Optimization failed, using default parameters", e);
            profile.setFitScore(0.0);
            profile.setRmse(1.0);
        }
    }

    /**
     * Generates a synthetic two-zone spectrum by combining hot core and cool periphery contributions.
     * This is a simplified model that approximates the actual NIST LIBS spectrum calculation.
     * 
     * @param wavelengthGrid Wavelength values
     * @param composition Material composition
     * @param hotCoreTe Hot core plasma temperature (eV)
     * @param hotCoreNe Hot core electron density (cm^-3)
     * @param coolPeripheryTe Cool periphery plasma temperature (eV)
     * @param coolPeripheryNe Cool periphery electron density (cm^-3)
     * @param hotCoreWeight Weight of hot core contribution (0-1)
     * @return Synthetic spectrum intensity array
     */
    private double[] generateTwoZoneSyntheticSpectrum(List<Double> wavelengthGrid, List<Element> composition,
                                                       double hotCoreTe, double hotCoreNe,
                                                       double coolPeripheryTe, double coolPeripheryNe,
                                                       double hotCoreWeight) {
        double[] spectrum = new double[wavelengthGrid.size()];
        double coolPeripheryWeight = 1.0 - hotCoreWeight;
        
        // For each element in the composition
        for (Element element : composition) {
            double percentage = element.getPercentageComposition() / 100.0;
            
            // Generate contribution from each zone
            double[] hotCoreContrib = generateSingleZoneSpectrum(wavelengthGrid, element.getSymbol(), 
                    hotCoreTe, hotCoreNe);
            double[] coolPeripheryContrib = generateSingleZoneSpectrum(wavelengthGrid, element.getSymbol(),
                    coolPeripheryTe, coolPeripheryNe);
            
            // Combine contributions
            for (int i = 0; i < spectrum.length; i++) {
                spectrum[i] += percentage * (hotCoreWeight * hotCoreContrib[i] + 
                        coolPeripheryWeight * coolPeripheryContrib[i]);
            }
        }
        
        return spectrum;
    }

    /**
     * Generates a simplified single-zone spectrum for an element.
     * Uses a simplified Boltzmann distribution model.
     * 
     * Note: In a full implementation, this would use actual NIST spectral line data.
     * 
     * @param wavelengthGrid Wavelength values (nm)
     * @param elementSymbol Element symbol
     * @param temperature Plasma temperature (eV)
     * @param electronDensity Electron density (cm^-3)
     * @return Intensity array
     */
    private double[] generateSingleZoneSpectrum(List<Double> wavelengthGrid, String elementSymbol,
                                                 double temperature, double electronDensity) {
        double[] spectrum = new double[wavelengthGrid.size()];
        
        // Get approximate emission lines for the element (simplified model)
        List<double[]> emissionLines = getApproximateEmissionLines(elementSymbol);
        
        // Boltzmann factor
        double kT = temperature; // Already in eV
        
        for (double[] line : emissionLines) {
            double wavelength = line[0]; // nm
            double relativeIntensity = line[1];
            double excitationEnergy = line.length > 2 ? line[2] : 1.0; // eV
            
            // Boltzmann distribution factor
            double boltzmannFactor = Math.exp(-excitationEnergy / kT);
            
            // Stark broadening estimate (simplified)
            double starkWidth = 0.1 * Math.pow(electronDensity / 1e17, 0.5);
            
            // Add Gaussian profile to spectrum
            for (int i = 0; i < wavelengthGrid.size(); i++) {
                double wl = wavelengthGrid.get(i);
                double diff = wl - wavelength;
                double width = Math.max(0.1, starkWidth);
                double intensity = relativeIntensity * boltzmannFactor * 
                        Math.exp(-0.5 * (diff / width) * (diff / width));
                spectrum[i] += intensity;
            }
        }
        
        return spectrum;
    }

    /**
     * Returns approximate emission line data for common LIBS elements.
     * Format: [wavelength (nm), relative intensity, excitation energy (eV)]
     * 
     * Note: This is simplified data for optimization. Full implementation would
     * use the complete NIST atomic spectra database.
     */
    private List<double[]> getApproximateEmissionLines(String elementSymbol) {
        List<double[]> lines = new ArrayList<>();
        
        switch (elementSymbol.toUpperCase()) {
            case "FE":
                lines.add(new double[]{259.94, 1.0, 4.78});
                lines.add(new double[]{274.65, 0.8, 4.52});
                lines.add(new double[]{275.57, 0.7, 4.51});
                lines.add(new double[]{302.06, 0.6, 4.10});
                lines.add(new double[]{358.12, 0.5, 3.46});
                lines.add(new double[]{371.99, 0.9, 3.33});
                lines.add(new double[]{373.49, 0.8, 3.32});
                lines.add(new double[]{404.58, 0.4, 3.06});
                break;
            case "C":
                lines.add(new double[]{247.86, 1.0, 7.68});
                lines.add(new double[]{426.73, 0.3, 7.94});
                lines.add(new double[]{833.52, 0.2, 7.49});
                break;
            case "MN":
                lines.add(new double[]{257.61, 0.9, 4.81});
                lines.add(new double[]{259.37, 1.0, 4.78});
                lines.add(new double[]{403.08, 0.7, 3.07});
                lines.add(new double[]{403.31, 0.8, 3.07});
                break;
            case "SI":
                lines.add(new double[]{250.69, 0.8, 4.95});
                lines.add(new double[]{251.43, 0.6, 4.93});
                lines.add(new double[]{251.61, 1.0, 4.93});
                lines.add(new double[]{288.16, 0.9, 4.30});
                break;
            case "NI":
                lines.add(new double[]{231.10, 0.8, 5.36});
                lines.add(new double[]{341.48, 1.0, 3.63});
                lines.add(new double[]{352.45, 0.7, 3.51});
                break;
            case "CR":
                lines.add(new double[]{267.72, 0.7, 4.63});
                lines.add(new double[]{283.56, 0.9, 4.37});
                lines.add(new double[]{284.32, 1.0, 4.36});
                lines.add(new double[]{425.44, 0.8, 2.91});
                break;
            case "CU":
                lines.add(new double[]{324.75, 1.0, 3.82});
                lines.add(new double[]{327.40, 0.9, 3.79});
                lines.add(new double[]{510.55, 0.3, 2.43});
                lines.add(new double[]{521.82, 0.2, 2.38});
                break;
            case "AL":
                lines.add(new double[]{308.22, 0.6, 4.02});
                lines.add(new double[]{309.27, 1.0, 4.00});
                lines.add(new double[]{394.40, 0.8, 3.14});
                lines.add(new double[]{396.15, 0.9, 3.13});
                break;
            case "ZN":
                lines.add(new double[]{213.86, 0.9, 5.80});
                lines.add(new double[]{334.50, 0.5, 3.70});
                lines.add(new double[]{472.22, 0.3, 2.63});
                break;
            default:
                // Generic emission line for unknown elements
                lines.add(new double[]{350.0, 1.0, 3.5});
                break;
        }
        
        return lines;
    }

    /**
     * Normalizes a spectrum to [0, 1] range.
     * If spectrum is empty or has no positive values, returns array of zeros.
     * 
     * @param spectrum Input intensity array
     * @return Normalized array with values in [0, 1] range
     */
    private double[] normalizeSpectrum(double[] spectrum) {
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
     * Calculates Root Mean Square Error between two spectra.
     */
    private double calculateRMSE(double[] spectrum1, double[] spectrum2) {
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
     * Clamps a value to a specified range.
     */
    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
