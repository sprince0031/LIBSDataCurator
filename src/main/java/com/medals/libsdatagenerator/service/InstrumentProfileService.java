package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.InstrumentProfile;
import com.medals.libsdatagenerator.model.UserInputConfig;
import com.medals.libsdatagenerator.util.CommonUtils;
import com.medals.libsdatagenerator.util.InputCompositionProcessor;
import com.medals.libsdatagenerator.util.PythonUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

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
    private static final double TE_MIN = 0.3; // Minimum plasma temperature (eV)
    private static final double TE_MAX = 3.0; // Maximum plasma temperature (eV)
    private static final double NE_MIN = 1e15; // Minimum electron density (cm^-3)
    private static final double NE_MAX = 1e18; // Maximum electron density (cm^-3)
    private static final double WEIGHT_MIN = 0.1; // Minimum zone weight
    private static final double WEIGHT_MAX = 0.9; // Maximum zone weight

    // Optimization settings
    private static final int MAX_EVALUATIONS = 20000;
    private static final double CONVERGENCE_THRESHOLD = 1e-4;
    
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
     * @param sampleCsvPath     Path to the sample CSV file with real LIBS readings
     * @param compositionString Composition of the reference material (e.g.,
     *                          "Fe-80,C-20")
     * @param instrumentName    Optional name for the instrument
     * @return Generated InstrumentProfile
     * @throws IOException if file cannot be read
     */
    public InstrumentProfile generateProfile(Path sampleCsvPath, String delimiter, String compositionString,
            String instrumentName)
            throws IOException {

        logger.info("Generating instrument profile from: " + sampleCsvPath);
        logger.info("Reference composition: " + compositionString);

        // 1. Extract wavelength grid from CSV header
        List<Double> wavelengthGrid = extractWavelengthGrid(sampleCsvPath, delimiter);
        if (wavelengthGrid.isEmpty()) {
            throw new IOException("Failed to extract wavelength grid from CSV header");
        }
        logger.info("Extracted wavelength grid with " + wavelengthGrid.size() + " points");

        // 2. Extract measured spectra (all shots)
        List<double[]> measuredSpectra = extractMeasuredSpectra(sampleCsvPath, wavelengthGrid, delimiter);
        logger.info("Extracted " + measuredSpectra.size() + " measurement shots");

        // 3. Calculate average measured spectrum
        double[] avgMeasuredSpectrum = calculateAverageSpectrum(measuredSpectra);

        // 3b. Apply Baseline Correction (Simple Minimum Subtraction)
        avgMeasuredSpectrum = applyBaselineCorrection(avgMeasuredSpectrum);

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

        // 7. Generate Jupyter Report
        if (PythonUtils.getInstance().setupPythonEnvironment()) {
            try {
                Path jupyterPath = PythonUtils.getInstance().getVenvJupyterPath();
                if (jupyterPath == null) {
                    throw new IOException("Jupyter executable not found in virtual environment.");
                }

                Path calibrationDir = Paths.get(CommonUtils.DATA_PATH, LIBSDataGenConstants.CALIBRATION_DIR);
                Path reportPath = calibrationDir.resolve(LIBSDataGenConstants.CALIBRATION_REPORT_OUTPUT_FILE);

                Path targetCsv = calibrationDir.resolve("target_processed.csv");
                Path hotCsv = calibrationDir.resolve(LIBSDataGenConstants.CALIBRATION_HOT_DIR).resolve("best_hot.csv");
                Path coolCsv = calibrationDir.resolve(LIBSDataGenConstants.CALIBRATION_COOL_DIR)
                        .resolve("best_cool.csv");

                generateJupyterReport(profile, reportPath, targetCsv, hotCsv, coolCsv);
                executeNotebook(reportPath, jupyterPath);
                convertNotebookToPdf(reportPath, jupyterPath);

            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to generate or execute calibration report", e);
            }
        } else {
            System.out.println(
                    "Warning: Calibration report could not be generated because Python 3 is not installed or environment setup failed.");
            logger.warning("Python environment setup failed. Skipping report generation.");
        }

        logger.info("Profile generation complete: " + profile);
        return profile;
    }

    /**
     * Extracts the wavelength grid from the CSV header line.
     * Expects wavelengths as column headers (numeric values).
     * 
     * @param csvPath   Path to the CSV file
     * @param delimiter Delimiter character used in source spectra CSV
     * @return List of wavelength values
     * @throws IOException if file cannot be read
     */
    public List<Double> extractWavelengthGrid(Path csvPath, String delimiter) throws IOException {
        List<Double> wavelengths = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IOException("Empty CSV file");
            }

            String[] headers = headerLine.split(delimiter);
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
     * @param csvPath        Path to the CSV file
     * @param wavelengthGrid Wavelength list extracted from input spectra file
     * @param delimiter      Delimiter character used in source spectra CSV
     * @return List of intensity arrays, one per shot
     * @throws IOException if file cannot be read
     */
    public List<double[]> extractMeasuredSpectra(Path csvPath, List<Double> wavelengthGrid, String delimiter)
            throws IOException {
        List<double[]> spectra = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(csvPath);
                CSVParser parser = CSVFormat.DEFAULT.withDelimiter(delimiter.toCharArray()[0])
                        .withFirstRecordAsHeader().parse(reader)) {

            // Extract spectra
            for (CSVRecord record : parser) {
                double[] spectrum = new double[wavelengthGrid.size()];
                for (int i = 0; i < wavelengthGrid.size(); i++) {
                    try {
                        spectrum[i] = Double.parseDouble(record.get(String.valueOf(wavelengthGrid.get(i))).trim());
                    } catch (IllegalArgumentException e) {
                        // IllegalArgumentException catches both NumberFormatException (which extends
                        // it)
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
     * @param spectrum Averaged input spectrum
     * @return Baseline corrected spectrum
     */
    public double[] applyBaselineCorrection(double[] spectrum) {
        return BaselineCorrectionService.getInstance().correctBaseline(spectrum);
    }

    /**
     * Generates a Jupyter Notebook report for the calibration.
     * 
     * @param profile    The instrument profile containing data and parameters
     * @param outputPath Path to save the .ipynb file
     * @throws IOException if writing fails
     */
    public void generateJupyterReport(InstrumentProfile profile, Path outputPath, Path targetCsv, Path hotCsv,
            Path coolCsv) throws IOException {
        // Load template from conf directory
        Path templatePath = Paths.get(CommonUtils.CONF_PATH, LIBSDataGenConstants.CALIBRATION_REPORT_TEMPLATE_FILE);
        String templateContent;
        if (!Files.exists(templatePath)) {
            // Fallback to resources if not in conf (e.g. during dev/test before deployment)
            logger.warning("Template not found in conf: " + templatePath + ". Checking resources.");
            try (java.io.InputStream is = getClass()
                    .getResourceAsStream("/" + LIBSDataGenConstants.CALIBRATION_REPORT_TEMPLATE_FILE)) {
                if (is == null) {
                    throw new IOException("Template file not found in conf or resources");
                }
                // Read from stream to temp file to read as string later or just read bytes
                // Simplest:
                templateContent = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IOException("Failed to load template", e);
            }
        } else {
            templateContent = Files.readString(templatePath);
        }

        // Prepare data strings for replacement
        // Using raw strings for paths, escaped for Python
        String inputCsvPath = profile.getSourceFile().replace("\\", "\\\\");
        String targetCsvPath = targetCsv.toAbsolutePath().toString().replace("\\", "\\\\");
        String hotCsvPath = hotCsv.toAbsolutePath().toString().replace("\\", "\\\\");
        String coolCsvPath = coolCsv.toAbsolutePath().toString().replace("\\", "\\\\");

        // Replace placeholders
        String content = templateContent
                .replace("<INSTRUMENT_NAME>", profile.getInstrumentName())
                .replace("\"<FIT_SCORE>\"", String.format("%.4f", profile.getFitScore()))
                .replace("<FIT_SCORE>", String.format("%.4f", profile.getFitScore()))
                .replace("\"<RMSE>\"", String.format("%.4f", profile.getRmse()))
                .replace("<RMSE>", String.format("%.4f", profile.getRmse()))
                .replace("<INPUT_CSV_PATH>", inputCsvPath)
                .replace("<PROCESSED_SPECTRUM_PATH>", targetCsvPath)
                .replace("<HOT_SPECTRUM_PATH>", hotCsvPath)
                .replace("<COOL_SPECTRUM_PATH>", coolCsvPath)
                .replace("<HOT_CORE_TE>", String.valueOf(profile.getHotCoreTe()))
                .replace("<HOT_CORE_NE>", String.valueOf(profile.getHotCoreNe()))
                .replace("<WEIGHT>", String.valueOf(profile.getHotCoreWeight())) // NOTE: Template uses WEIGHT, verify
                .replace("<COOL_PERIPHERY_TE>", String.valueOf(profile.getCoolPeripheryTe()))
                .replace("<COOL_PERIPHERY_NE>", String.valueOf(profile.getCoolPeripheryNe()));

        // Save filled notebook
        Files.write(outputPath, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        logger.info("Jupyter notebook report generated: " + outputPath);
    }

    /**
     * Executes the Jupyter Notebook in place.
     */
    private void executeNotebook(Path notebookPath, Path jupyterPath) {
        logger.info("Executing Jupyter Notebook: " + notebookPath + " using " + jupyterPath);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    jupyterPath.toString(), "nbconvert",
                    "--to", "notebook",
                    "--execute",
                    "--inplace",
                    notebookPath.toString());
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read output
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.fine("[Jupyter] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logger.info("Notebook executed successfully.");
            } else {
                logger.warning("Notebook execution failed with exit code: " + exitCode);
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to execute Jupyter Notebook.", e);
        }
    }

    /**
     * Converts the Jupyter Notebook to PDF.
     */
    private void convertNotebookToPdf(Path notebookPath, Path jupyterPath) {
        logger.info("Converting Notebook to PDF...");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    jupyterPath.toString(), "nbconvert",
                    "--to", "pdf",
                    notebookPath.toString());
            pb.directory(notebookPath.getParent().toFile()); // Run in same dir
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read output (consume stream to prevent blocking)
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logger.fine("[PDF Convert] " + line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logger.info("PDF conversion successful.");
            } else {
                logger.warning("PDF conversion failed with exit code: " + exitCode);
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to convert notebook to PDF. Ensure latex/pandoc are installed.", e);
        }
    }

    /**
     * Parses a composition string into a list of Elements.
     * 
     * @param compositionString Format:
     *                          "Element1-Percentage1,Element2-Percentage2,..."
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
     * Uses a Grid Search approach with Selenium-based spectrum generation.
     * 
     * @param profile             InstrumentProfile to update with optimized
     *                            parameters
     * @param avgMeasuredSpectrum Average measured spectrum
     * @param wavelengthGrid      Wavelength values
     * @param composition         Material composition
     */
    private void optimizePlasmaParameters(InstrumentProfile profile, double[] avgMeasuredSpectrum,
            List<Double> wavelengthGrid, List<Element> composition) {

        logger.info("Starting Grid Search optimization for plasma parameters...");

        // Configuration for fetching
        UserInputConfig config = new UserInputConfig();
        config.minWavelength = String.valueOf(profile.getMinWavelength());
        config.maxWavelength = String.valueOf(profile.getMaxWavelength());
        config.resolution = "1000"; // Should match input data resolution logic if possible

        // Define Grid Search Space
        // Ranges for Te (eV)
        double[] teValues = { 0.5, 0.8, 1.0, 1.2, 1.5, 2.0 };
        // Ranges for Ne (cm^-3) - using exponents
        double[] neExponents = { 16.0, 16.5, 17.0, 17.5 };

        double bestRmse = Double.MAX_VALUE;
        double bestHotTe = 0;
        double bestHotNe = 0;
        double bestCoolTe = 0;
        double bestCoolNe = 0;
        double bestHotWeight = 0;

        // Temporary storage for spectra to avoid re-fetching
        // Key: "Te_Ne" -> double[] spectrum
        Map<String, double[]> spectrumCache = new HashMap<>();

        try {
            // Setup output directories
            Path calibDir = Paths.get(CommonUtils.DATA_PATH, LIBSDataGenConstants.CALIBRATION_DIR);
            Path hotDir = calibDir.resolve(LIBSDataGenConstants.CALIBRATION_HOT_DIR);
            Path coolDir = calibDir.resolve(LIBSDataGenConstants.CALIBRATION_COOL_DIR);

            Files.createDirectories(hotDir);
            Files.createDirectories(coolDir);

            // Save Target Spectrum
            Path targetPath = calibDir.resolve("target_processed.csv");
            saveSpectrumToCsv(targetPath, wavelengthGrid, avgMeasuredSpectrum);

            // Pre-fetch all necessary spectra using the single Selenium session
            for (double te : teValues) {
                for (double neExp : neExponents) {
                    double ne = Math.pow(10, neExp);
                    String key = String.format("%.2f_%.2e", te, ne);

                    logger.info("Fetching spectrum for " + key);

                    Path tempPath = Files.createTempFile("libs_calib_", ".csv");
                    String csvData = LIBSDataService.getInstance().fetchCalibrationSpectrum(
                            composition, config, te, ne, tempPath);

                    if (!csvData.equals(String.valueOf(java.net.HttpURLConnection.HTTP_NOT_FOUND))) {
                        // Parse and cache
                        Map<Double, Double> waveMap = parseNistCsv(csvData);
                        double[] spectrum = alignSpectrum(waveMap, wavelengthGrid);
                        spectrumCache.put(key, spectrum);
                    }

                    Files.deleteIfExists(tempPath);
                }
            }

            // Normalization for RMSE calculation
            double maxMeasuredIntensity = Arrays.stream(avgMeasuredSpectrum).max().orElse(1.0);
            if (maxMeasuredIntensity == 0)
                maxMeasuredIntensity = 1.0;

            double[] normalizedMeasuredSpectrum = new double[avgMeasuredSpectrum.length];
            for (int i = 0; i < avgMeasuredSpectrum.length; i++) {
                normalizedMeasuredSpectrum[i] = avgMeasuredSpectrum[i] / maxMeasuredIntensity;
            }

            // Grid Search Logic (Combinatorial)
            // Iterate Hot Core candidates
            for (double hotTe : teValues) {
                for (double hotNeExp : neExponents) {
                    double hotNe = Math.pow(10, hotNeExp);
                    String hotKey = String.format("%.2f_%.2e", hotTe, hotNe);
                    double[] hotSpectrum = spectrumCache.get(hotKey);

                    if (hotSpectrum == null)
                        continue;

                    // Iterate Cool Periphery candidates
                    for (double coolTe : teValues) {
                        if (coolTe >= hotTe)
                            continue; // Constraint: Cool zone must be cooler

                        for (double coolNeExp : neExponents) {
                            double coolNe = Math.pow(10, coolNeExp);
                            String coolKey = String.format("%.2f_%.2e", coolTe, coolNe);
                            double[] coolSpectrum = spectrumCache.get(coolKey);

                            if (coolSpectrum == null)
                                continue;

                            // Optimize Weight (0.1 to 0.9)
                            for (double w = 0.1; w <= 0.9; w += 0.1) {
                                double[] combined = combineSpectra(hotSpectrum, coolSpectrum, w);
                                // Use normalized measured spectrum for RMSE calculation
                                double rmse = calculateRMSE(normalizedMeasuredSpectrum, normalizeSpectrum(combined));

                                if (rmse < bestRmse) {
                                    bestRmse = rmse;
                                    bestHotTe = hotTe;
                                    bestHotNe = hotNe;
                                    bestCoolTe = coolTe;
                                    bestCoolNe = coolNe;
                                    bestHotWeight = w;
                                }
                            }
                        }
                    }
                }
            }

            // Update profile with best found parameters
            profile.setHotCoreTe(bestHotTe);
            profile.setHotCoreNe(bestHotNe);
            profile.setCoolPeripheryTe(bestCoolTe);
            profile.setCoolPeripheryNe(bestCoolNe);
            profile.setHotCoreWeight(bestHotWeight);
            profile.setCoolPeripheryWeight(1.0 - bestHotWeight);
            profile.setRmse(bestRmse);
            profile.setFitScore(1.0 - Math.min(bestRmse, 1.0));

            logger.info("Optimization complete. Best RMSE: " + bestRmse);

            // Save Best Spectra to Persistent Files
            logger.info("Saving best fit spectra...");
            Path bestHotPath = hotDir.resolve("best_hot.csv");
            Path bestCoolPath = coolDir.resolve("best_cool.csv");

            // Re-fetch best hot and save scaled
            logger.info("Fetching best Hot Core spectrum for saving...");
            Path tempHot = Files.createTempFile("temp_hot", ".csv");
            String hotCsvData = LIBSDataService.getInstance().fetchCalibrationSpectrum(
                    composition, config, bestHotTe, bestHotNe, tempHot);
            processAndSaveSpectrum(hotCsvData, bestHotPath, wavelengthGrid, maxMeasuredIntensity);
            Files.deleteIfExists(tempHot);

            // Re-fetch best cool and save scaled
            logger.info("Fetching best Cool Periphery spectrum for saving...");
            Path tempCool = Files.createTempFile("temp_cool", ".csv");
            String coolCsvData = LIBSDataService.getInstance().fetchCalibrationSpectrum(
                    composition, config, bestCoolTe, bestCoolNe, tempCool);
            processAndSaveSpectrum(coolCsvData, bestCoolPath, wavelengthGrid, maxMeasuredIntensity);
            Files.deleteIfExists(tempCool);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Grid search optimization failed", e);
        } finally {
            // Close Selenium session
            com.medals.libsdatagenerator.util.SeleniumUtils.getInstance().quitSelenium();
        }
    }

    /**
     * Processes raw CSV data, normalizes it, scales it by the measured intensity
     * factor, and saves it.
     */
    private void processAndSaveSpectrum(String csvData, Path outputPath, List<Double> wavelengthGrid,
            double scaleFactor) throws IOException {
        if (csvData == null || csvData.equals(String.valueOf(java.net.HttpURLConnection.HTTP_NOT_FOUND))) {
            logger.warning("Failed to fetch spectrum data for saving: " + outputPath);
            return;
        }

        Map<Double, Double> waveMap = parseNistCsv(csvData);
        double[] spectrum = alignSpectrum(waveMap, wavelengthGrid);
        double[] normalized = normalizeSpectrum(spectrum);

        double[] scaled = new double[normalized.length];
        for (int i = 0; i < normalized.length; i++) {
            scaled[i] = normalized[i] * scaleFactor;
        }

        saveSpectrumToCsv(outputPath, wavelengthGrid, scaled);
    }

    private void saveSpectrumToCsv(Path path, List<Double> wavelengths, double[] intensity) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path);
                CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("Wavelength", "Intensity"))) {
            for (int i = 0; i < wavelengths.size(); i++) {
                if (i < intensity.length) {
                    printer.printRecord(wavelengths.get(i), intensity[i]);
                }
            }
        }
    }

    private double[] alignSpectrum(Map<Double, Double> waveMap, List<Double> targetGrid) {
        double[] aligned = new double[targetGrid.size()];
        // Simple nearest neighbor or interpolation could be used.
        // For now, mapping closest existing wavelength.
        for (int i = 0; i < targetGrid.size(); i++) {
            double targetWl = targetGrid.get(i);
            // Find closest key in map (inefficient for large maps, but acceptable for
            // prototype)
            // Ideally, waveMap keys should be navigable
            // Using exact match or very close tolerance for simplicity if grid matches NIST
            aligned[i] = waveMap.getOrDefault(targetWl, 0.0);
        }
        return aligned;
    }

    private Map<Double, Double> parseNistCsv(String csvData) throws IOException {
        Map<Double, Double> map = new HashMap<>();
        try (java.io.StringReader sr = new java.io.StringReader(csvData);
                CSVParser parser = CSVFormat.DEFAULT.withFirstRecordAsHeader().parse(sr)) {
            for (CSVRecord record : parser) {
                try {
                    String wStr = record.get("Wavelength (nm)");
                    String iStr = record.isMapped("Sum") ? record.get("Sum") : record.get("Sum(calc)");
                    if (wStr != null && iStr != null) {
                        map.put(Double.parseDouble(wStr), Double.parseDouble(iStr));
                    }
                } catch (Exception e) {
                    // Skip bad rows
                }
            }
        }
        return map;
    }

    private double[] combineSpectra(double[] hot, double[] cool, double weight) {
        double[] combined = new double[hot.length];
        for (int i = 0; i < hot.length; i++) {
            combined[i] = weight * hot[i] + (1.0 - weight) * cool[i];
        }
        return combined;
    }

    /**
     * Normalizes a spectrum to [0, 1] range.
     * If spectrum is empty or has no positive values, returns array of zeros.
     * 
     * @param spectrum Input intensity array
     * @return Normalized array with values in [0, 1] range
     */
    double[] normalizeSpectrum(double[] spectrum) {
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
    double calculateRMSE(double[] spectrum1, double[] spectrum2) {
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
}
