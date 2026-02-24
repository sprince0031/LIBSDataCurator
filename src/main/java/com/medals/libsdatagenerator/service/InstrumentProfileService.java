package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.BaselineCorrectionParams;
import com.medals.libsdatagenerator.model.InstrumentProfile;
import com.medals.libsdatagenerator.model.PlasmaZone;
import com.medals.libsdatagenerator.model.Spectrum;
import com.medals.libsdatagenerator.model.UserInputConfig;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.WavelengthUnit;
import com.medals.libsdatagenerator.util.CommonUtils;
import com.medals.libsdatagenerator.util.InputCompositionProcessor;
import com.medals.libsdatagenerator.util.NISTUtils;
import com.medals.libsdatagenerator.util.PythonUtils;
import com.medals.libsdatagenerator.util.SpectrumUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
     * @param delimiter The delimiter character used by the sample CSV file
     * @param compositionString Composition of the reference material (e.g.: "Fe-80,C-20")
     * @param instrumentName Optional name for the instrument
     * @param baselineParams Object containing lambda, p and maxIter values for baseline correction
     * @param plasmaZones Number of plasma zones to consider and combine when comparing fit of synthetic spectrum
     * @param debugMode Enable debug mode which shows browser actions in a browser window
     * @return Generated InstrumentProfile
     * @throws IOException if file cannot be read
     */
    public InstrumentProfile generateProfile(Path sampleCsvPath, String delimiter, String compositionString,
            String instrumentName, BaselineCorrectionParams baselineParams,
            int plasmaZones, boolean debugMode) throws IOException {

        logger.info("Generating instrument profile from: " + sampleCsvPath);
        logger.info("Reference composition: " + compositionString);

        // 1. Extract wavelength grid from CSV header
        double[] wavelengthGrid = extractWavelengthGrid(sampleCsvPath, delimiter);
        if (wavelengthGrid.length == 0) {
            throw new IOException("Failed to extract wavelength grid from CSV header");
        }
        logger.info("Extracted wavelength grid with " + wavelengthGrid.length + " points");

        // 2. Extract measured spectra (all shots)
        List<double[]> measuredSpectra = extractMeasuredSpectra(sampleCsvPath, wavelengthGrid, delimiter);
        logger.info("Extracted " + measuredSpectra.size() + " measurement shots");

        SpectrumUtils spectrumUtils = new SpectrumUtils();

        // 3. Calculate average measured spectrum
        double[] avgMeasuredSpectrum = spectrumUtils.calculateAverageSpectrum(measuredSpectra);

        // 3a. Clip spectrum
        Spectrum clippedSpectrum = spectrumUtils.clipSpectrum(wavelengthGrid, avgMeasuredSpectrum);

        // 3b. Apply Baseline Correction (Asymmetric Least Squares)
        double[] baselineCorrectedIntensities = BaselineCorrectionService.getInstance().correctBaseline(
                clippedSpectrum.getIntensities(), baselineParams.getLambda(), baselineParams.getP(),
                baselineParams.getMaxIterations());
        Spectrum processedMeasuredSpectrum = new Spectrum(clippedSpectrum.getWavelengths(),
                baselineCorrectedIntensities);

        // 4. Parse composition
        MaterialGrade materialGrade = InputCompositionProcessor.getInstance()
                .getMaterial(compositionString, null, Integer.parseInt(LIBSDataGenConstants.DEFAULT_N_DECIMAL_PLACES));
        if (materialGrade.getComposition() == null) {
            throw new IllegalArgumentException("Invalid composition string: " + compositionString);
        }

        // 5. Create initial profile
        InstrumentProfile profile = new InstrumentProfile(processedMeasuredSpectrum.getWavelengths(),
                sampleCsvPath.toString(), compositionString);
        profile.setInstrumentName(instrumentName != null ? instrumentName : "Unknown");
        profile.setNumShots(measuredSpectra.size());
        profile.setBaselineParams(baselineParams);

        // 6. Optimize plasma parameters
        logger.info("Starting " + plasmaZones + "-zone plasma parameter optimization...");
        optimizePlasmaParameters(profile, processedMeasuredSpectrum, materialGrade, plasmaZones, debugMode);

        // 7. Generate Jupyter Report
        if (PythonUtils.getInstance().setupPythonEnvironment()) {
            try {
                Path jupyterPath = PythonUtils.getInstance().getVenvJupyterPath();
                if (jupyterPath == null) {
                    throw new IOException("Jupyter executable not found in virtual environment.");
                }

                Path calibrationDir = Paths.get(CommonUtils.DATA_PATH, LIBSDataGenConstants.CALIBRATION_DIR);
                Path reportPath = calibrationDir
                        .resolve(LIBSDataGenConstants.CALIBRATION_REPORT_OUTPUT_FILE + ".ipynb");

                Path targetCsv = calibrationDir.resolve("target_processed.csv");
                Path zonesCsv = calibrationDir.resolve("best_zones.csv");

                generateJupyterReport(profile, reportPath, targetCsv, zonesCsv);
                executeNotebook(reportPath, jupyterPath);
                convertNotebookToPdf(reportPath, jupyterPath, instrumentName);

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
     * @param csvPath Path to the CSV file
     * @param delimiter Delimiter character used in source spectra CSV
     * @return List of wavelength values
     * @throws IOException if file cannot be read
     */
    public double[] extractWavelengthGrid(Path csvPath, String delimiter) throws IOException {
        List<Double> wavelengths = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(csvPath);
                CSVParser parser = CSVFormat.DEFAULT.withDelimiter(delimiter.toCharArray()[0])
                        .withFirstRecordAsHeader().parse(reader)) {
            List<String> headerLine = parser.getHeaderNames();
            if (headerLine == null) {
                throw new IOException("Empty CSV file");
            }

            for (String header : headerLine) {
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

        // Convert to double[] for compatibility with interpolation utilities downstream
        double[] finalWavelengths = new double[wavelengths.size()];
        for (int i = 0; i < wavelengths.size(); i++) {
            finalWavelengths[i] = wavelengths.get(i);
        }

        return finalWavelengths;
    }

    /**
     * Extracts measured intensity spectra from the CSV file.
     * Each row represents one shot/measurement.
     *
     * @param csvPath Path to the CSV file
     * @param wavelengthGrid Wavelength list extracted from input spectra file
     * @param delimiter Delimiter character used in source spectra CSV
     * @return List of intensity arrays, one per shot
     * @throws IOException if file cannot be read
     */
    public List<double[]> extractMeasuredSpectra(Path csvPath, double[] wavelengthGrid, String delimiter)
            throws IOException {
        List<double[]> spectra = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(csvPath);
                CSVParser parser = CSVFormat.DEFAULT.withDelimiter(delimiter.toCharArray()[0])
                        .withFirstRecordAsHeader().parse(reader)) {

            // Extract spectra
            Map<String, Integer> headerMap = parser.getHeaderMap();

            // Extract spectra using column indices to avoid header-name format issues
            for (CSVRecord record : parser) {
                double[] spectrum = new double[wavelengthGrid.length];
                int i = 0;
                for (Map.Entry<String, Integer> entry: headerMap.entrySet()) {//int i = 0; i < wavelengthGrid.length; i++) {
                    try {
                        if (Double.parseDouble(entry.getKey()) == wavelengthGrid[i]) {
                            spectrum[i] = Double.parseDouble(record.get(entry.getValue()).trim());
                            i++;
                        }
                    } catch (NumberFormatException e) {
                        // skip column as not a wavelength column
                    }
                }
                spectra.add(spectrum);
            }
        }

        return spectra;
    }

    /**
     * Generates a Jupyter Notebook report for the calibration.
     * 
     * @param profile The instrument profile containing data and parameters
     * @param outputPath Path to save the .ipynb file
     * @throws IOException if writing fails
     */
    public void generateJupyterReport(InstrumentProfile profile, Path outputPath, Path targetCsv, Path zonesCsv)
            throws IOException {
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
                templateContent = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IOException("Failed to load template", e);
            }
        } else {
            templateContent = Files.readString(templatePath);
        }

        // Prepare data strings for replacement
        // Using raw strings for paths, escaped for Python
        String content = getProcessedNotebook(profile, zonesCsv, templateContent);

        // Save filled notebook
        Files.write(outputPath, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        logger.info("Jupyter notebook report generated: " + outputPath);
    }

    private String getProcessedNotebook(InstrumentProfile profile, Path zonesCsv, String templateContent) {
        // Use forward slashes for paths to ensure cross-platform compatibility in
        // Jupyter/Python without needing escaping
        String inputCsvPath = profile.getSourceFile().replace("\\", "/");
        String zonesCsvPath = zonesCsv.toAbsolutePath().toString().replace("\\", "/");

        // Replace placeholders
        return templateContent
                .replace(LIBSDataGenConstants.INSTRUMENT_NAME, profile.getInstrumentName())
                .replace(LIBSDataGenConstants.RSQUARE_SCORE, String.format("%.4f", profile.getRSquaredValue()))
                .replace(LIBSDataGenConstants.RMSE, String.format("%.4f", profile.getRmse()))
                .replace(LIBSDataGenConstants.INPUT_CSV_PATH, inputCsvPath)
                .replace(LIBSDataGenConstants.ZONES_CSV_PATH, zonesCsvPath)
                .replace(LIBSDataGenConstants.LAMBDA, String.valueOf(profile.getLambda()))
                .replace(LIBSDataGenConstants.P, String.valueOf(profile.getP()))
                .replace(LIBSDataGenConstants.MAX_ITERATIONS, String.valueOf(profile.getMaxIterations()));
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
    private void convertNotebookToPdf(Path notebookPath, Path jupyterPath, String instrumentName) {
        if (!dependencyCheckForPdfConversion()) {
            return; // Skip PDF conversion if dependencies are not met
        }

        System.out.println("Generating calibration report PDF...");
        logger.info("Converting Notebook to PDF...");
        String pdfReportPath = LIBSDataGenConstants.CALIBRATION_REPORT_OUTPUT_FILE + "_" + instrumentName + "_"
                + System.currentTimeMillis();
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    jupyterPath.toString(), "nbconvert",
                    "--to", "pdf", notebookPath.toString(), "--output", pdfReportPath);
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
                System.out.println("Calibration report PDF generated: " + pdfReportPath + ".pdf");
            } else {
                logger.warning("PDF conversion failed with exit code: " + exitCode);
                System.out.println("PDF conversion failed. Ensure that LaTeX and Pandoc are installed and properly configured. Skipping PDF generation.");
            }

        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to convert notebook to PDF. Ensure latex/pandoc are installed.", e);
        }
    }

    private boolean dependencyCheckForPdfConversion() {
        // Check if pandoc is installed as it is a dependency for nbconvert PDF conversion
        try {
            ProcessBuilder pb = new ProcessBuilder("pandoc", "--version");
            pb.redirectErrorStream(true);

            Process process = pb.start();
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                logger.info("Pandoc installed. Proceeding with PDF conversion.");
                return true;
            } else {
                logger.warning("Pandoc installation check failed with exit code: " + exitCode);
            }
        } catch (Exception e) {
            System.out.println("""
                        Pandoc is required for PDF conversion but was not found.
                        Consider installing at https://pandoc.org/installing.html to enable PDF calibration report generation.
                        Skipping PDF generation.""");
            logger.log(Level.WARNING, "Pandoc installation check failed", e);
        }
        return false;
    }

    /**
     * Optimizes the n-zone plasma parameters to best match measured spectrum.
     * Uses a recursive Grid Search approach with Selenium-based spectrum
     * generation.
     *
     * @param profile                   InstrumentProfile to update with optimized
     *                                  parameters
     * @param processedMeasuredSpectrum Average measured spectrum
     * @param composition               Material composition
     * @param plasmaZones               Number of plasma zones to combine
     * @param debugMode
     */
    private void optimizePlasmaParameters(InstrumentProfile profile, Spectrum processedMeasuredSpectrum,
            MaterialGrade composition, int plasmaZones, boolean debugMode) {

        double[] wavelengthGrid = processedMeasuredSpectrum.getWavelengths();
        double[] measuredIntensities = processedMeasuredSpectrum.getIntensities();

        logger.info("Starting Grid Search optimization for " + plasmaZones + " plasma zones...");

        // Configuration for fetching
        UserInputConfig config = new UserInputConfig();
        config.minWavelength = String.valueOf(profile.getMinWavelength());
        config.maxWavelength = String.valueOf(profile.getMaxWavelength());
        config.resolution = "1000";
        config.setDebugMode(debugMode);

        // Define Grid Search Space
        double[] teValues = { 0.5, 0.8, 1.0, 1.2, 1.5, 1.7, 2.0 };
        double[] neExponents = { 15.0, 15.5, 16.0, 16.5, 17.0, 17.5 };

        // Normalization for RMSE calculation
        double maxMeasuredIntensity = Arrays.stream(measuredIntensities).max().orElse(1.0);
        profile.setScaleFactor(maxMeasuredIntensity);
        if (maxMeasuredIntensity == 0)
            maxMeasuredIntensity = 1.0;
        double[] normalisedMeasuredSpectrum = new double[measuredIntensities.length];
        for (int i = 0; i < measuredIntensities.length; i++) {
            normalisedMeasuredSpectrum[i] = measuredIntensities[i] / maxMeasuredIntensity;
        }

        SpectrumUtils spectrumUtils = new SpectrumUtils();
        Map<String, double[]> spectrumCache = new HashMap<>();
        PrintStream out = System.out;

        try {
            // Setup output directories
            Path calibDir = Paths.get(CommonUtils.DATA_PATH, LIBSDataGenConstants.CALIBRATION_DIR);
            Files.createDirectories(calibDir);

            // Save Target Spectrum
            Path targetPath = calibDir.resolve("target_processed.csv");
            saveSpectrumToCsv(targetPath, wavelengthGrid, measuredIntensities);

            // Pre-fetch all necessary spectra
            logger.info("Starting grid search...");
            int i = 0;
            int gridSize = teValues.length * neExponents.length;
            for (double te : teValues) {
                for (double neExp : neExponents) {
                    double ne = Math.pow(10, neExp);
                    String key = String.format("%.2f_%.2e", te, ne);

                    if (spectrumCache.containsKey(key))
                        continue;

                    logger.info("Fetching spectrum for " + key);
                    String csvData = LIBSDataService.getInstance().fetchPlasmaZoneSpectrum(
                            composition.getComposition(), config, te, ne, composition.getRemainderElementIdx());

                    if (!csvData.equals(String.valueOf(java.net.HttpURLConnection.HTTP_NOT_FOUND))) {
                        Map<Double, Double> waveMap = NISTUtils.parseNistCsv(csvData, WavelengthUnit.NANOMETER.getUnitString());
                        double[] spectrum = spectrumUtils.interpolateSpectrum(waveMap, wavelengthGrid);
                        // Store NON-NORMALIZED spectrum in cache for final combination
                        spectrumCache.put(key, spectrum);
                    }
                    CommonUtils.printProgressBar(i + 1, gridSize, "spectra fetched from NIST LIBS db", out);
                    i++;
                }
            }
            CommonUtils.finishProgressBar(gridSize, out);

            // normalize cached spectra for optimization comparison
            Map<String, double[]> normalizedSpectrumCache = new HashMap<>();
            i = 0;
            for (Map.Entry<String, double[]> entry : spectrumCache.entrySet()) {
                normalizedSpectrumCache.put(entry.getKey(), spectrumUtils.normalizeSpectrum(entry.getValue()));
                CommonUtils.printProgressBar(i + 1, gridSize, "Spectra normalised", out);
                i++;
            }
            CommonUtils.finishProgressBar(gridSize, out);

            // Recursive Grid Search
            OptimizationResult bestResult = findBestCombination(plasmaZones, teValues, neExponents,
                    normalizedSpectrumCache, normalisedMeasuredSpectrum);

            // Update profile with best parameters
            List<PlasmaZone> zones = new ArrayList<>();
            for (i = 0; i < bestResult.parameters.size(); i++) {
                ZoneParams params = bestResult.parameters.get(i);
                double weight = bestResult.weights.get(i);
                zones.add(new PlasmaZone(params.te, params.ne, weight));
            }

            profile.setZones(zones);
            profile.setRmse(bestResult.rmse);
            profile.setRSquaredValue(bestResult.rSquared);

            logger.info("Optimization complete. Best RMSE: " + bestResult.rmse + ", R^2: " + bestResult.rSquared);

            // Save Best Zones and Spectra to Single CSV
            Path zonesCsvPath = calibDir.resolve("best_zones.csv");

            // Saving normalized * maxMeasured to scale synthetic spectrum close to measured spectrum
            saveZonesToCsv(zonesCsvPath, zones, wavelengthGrid, spectrumCache, maxMeasuredIntensity);

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Grid search optimization failed", e);
        } finally {
            com.medals.libsdatagenerator.util.SeleniumUtils.getInstance().quitSelenium();
        }
    }

    private void saveSpectrumToCsv(Path path, double[] wavelengths, double[] intensity) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(path);
                CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader("Wavelength", "Intensity"))) {
            for (int i = 0; i < wavelengths.length; i++) {
                if (i < intensity.length) {
                    printer.printRecord(wavelengths[i], intensity[i]);
                }
            }
        }
    }

    private static class ZoneParams {
        double te;
        double ne;

        public ZoneParams(double te, double ne) {
            this.te = te;
            this.ne = ne;
        }
    }

    private static class OptimizationResult {
        List<ZoneParams> parameters = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        double rmse = Double.MAX_VALUE;
        double rSquared = Double.MIN_VALUE;
    }

    private OptimizationResult findBestCombination(int numZones, double[] teValues, double[] neExponents,
            Map<String, double[]> normalizedCache, double[] targetSpectrum) {

        PrintStream out = System.out;
        OptimizationResult bestResult = new OptimizationResult();

        out.println("Generating parameter and weight combinations for grid search...");
        // Generate parameter combinations
        List<List<ZoneParams>> allParamCombinations = new ArrayList<>();
        generateParamCombinations(numZones, teValues, neExponents, new ArrayList<>(), allParamCombinations);

        // Generate weight combinations (simplex steps of 0.1)
        List<List<Double>> allWeightCombinations = new ArrayList<>();
        generateWeightCombinations(numZones, 1.0, new ArrayList<>(), allWeightCombinations);

        // Iterate and find best
        int progress = 0;
        int totalCombinations = allParamCombinations.size() * allWeightCombinations.size();
        for (List<ZoneParams> params : allParamCombinations) {
            for (List<Double> weights : allWeightCombinations) {
                // Combine spectra
                double[] combined = new double[targetSpectrum.length];
                boolean possible = true;

                for (int i = 0; i < numZones; i++) {
                    ZoneParams p = params.get(i);
                    String key = String.format("%.2f_%.2e", p.te, p.ne);
                    double[] s = normalizedCache.get(key);
                    if (s == null) {
                        possible = false;
                        break;
                    }
                    double w = weights.get(i);
                    for (int j = 0; j < combined.length; j++) {
                        combined[j] += s[j] * w;
                    }
                }

                if (!possible)
                    continue;

                SpectrumUtils spectrumUtils = new SpectrumUtils();
                // Normalize combined result for comparison against normalized target
                double[] finalCombined = spectrumUtils.normalizeSpectrum(combined);

                double rmse = spectrumUtils.calculateRMSE(targetSpectrum, finalCombined);
                double rSquared = spectrumUtils.calculateSpectralSimilarity(targetSpectrum, finalCombined);

                // Selection Criteria: Strictly better RMSE and R^2, or improvement in R^2
                if (rmse < bestResult.rmse && rSquared > bestResult.rSquared) {
                    bestResult.rmse = rmse;
                    bestResult.rSquared = rSquared;
                    bestResult.parameters = params;
                    bestResult.weights = weights;
                } else if (rmse == bestResult.rmse && rSquared > bestResult.rSquared) {
                    bestResult.rSquared = rSquared;
                    bestResult.parameters = params;
                    bestResult.weights = weights;
                } else if (rSquared == bestResult.rSquared && rmse < bestResult.rmse) {
                    bestResult.rmse = rmse;
                    bestResult.parameters = params;
                    bestResult.weights = weights;
                }
                CommonUtils.printProgressBar(progress + 1, totalCombinations, "combinations processed", out);
                progress++;
            }
        }
        CommonUtils.finishProgressBar(totalCombinations, out);

        return bestResult;
    }

    private void generateParamCombinations(int zonesLeft, double[] teValues, double[] neExponents,
            List<ZoneParams> current, List<List<ZoneParams>> results) {
        if (zonesLeft == 0) {
            results.add(new ArrayList<>(current));
            return;
        }

        double lastTe = current.isEmpty() ? Double.MAX_VALUE : current.get(current.size() - 1).te;

        for (double te : teValues) {
            // Constraint: Te must be <= previous Te (Hot to Cool ordering)
            if (te > lastTe)
                continue;

            for (double neExp : neExponents) {
                double ne = Math.pow(10, neExp);
                current.add(new ZoneParams(te, ne));
                generateParamCombinations(zonesLeft - 1, teValues, neExponents, current, results);
                current.remove(current.size() - 1);
            }
        }
    }

    private void generateWeightCombinations(int zonesLeft, double remainingWeight,
            List<Double> current, List<List<Double>> results) {
        if (zonesLeft == 1) {
            // Last zone gets all remaining weight
            // Rounding to avoid precision errors, though double precision usually suffices
            // for step 0.1
            double w = Math.round(remainingWeight * 100.0) / 100.0;
            if (w < 0)
                return; // Should not happen
            List<Double> full = new ArrayList<>(current);
            full.add(w);
            results.add(full);
            return;
        }

        // Step size 0.1, up to remaining weight
        for (double w = 0.05; w <= remainingWeight - 0.05 * (zonesLeft - 1); w += 0.05) {
            current.add(w);
            generateWeightCombinations(zonesLeft - 1, remainingWeight - w, current, results);
            current.remove(current.size() - 1);
        }
    }

    private void saveZonesToCsv(Path outputPath, List<PlasmaZone> zones,
            double[] wavelengthGrid, Map<String, double[]> spectrumCache,
            double scaleFactor) throws IOException {

        try (BufferedWriter writer = Files.newBufferedWriter(outputPath);
                CSVPrinter printer = new CSVPrinter(writer, CSVFormat.DEFAULT)) {

            // Header: Te, Ne, Weight, <Wavelengths>
            List<String> header = new ArrayList<>();
            header.add("Te");
            header.add("Ne");
            header.add("Weight");
            for (double w : wavelengthGrid)
                header.add(String.valueOf(w));
            printer.printRecord(header);

            SpectrumUtils spectrumUtils = new SpectrumUtils();

            for (PlasmaZone zone : zones) {
                String key = String.format("%.2f_%.2e", zone.getTe(), zone.getNe());
                double[] rawSpectrum = spectrumCache.get(key);

                List<Object> record = new ArrayList<>();
                record.add(zone.getTe());
                record.add(zone.getNe());
                record.add(zone.getWeight());

                if (rawSpectrum != null) {
                    // Normalize and then scale
                    double[] normalized = spectrumUtils.normalizeSpectrum(rawSpectrum);
                    for (double val : normalized) {
                        record.add(val * scaleFactor);
                    }
                } else {
                    // Should not happen if cache is hit, but fill zeros
                    for (int i = 0; i < wavelengthGrid.length; i++)
                        record.add(0.0);
                }
                printer.printRecord(record);
            }
        }
    }
}
