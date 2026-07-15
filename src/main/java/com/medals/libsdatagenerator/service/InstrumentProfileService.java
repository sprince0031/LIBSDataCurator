package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.BaselineCorrectionParams;
import com.medals.libsdatagenerator.model.InstrumentProfile;
import com.medals.libsdatagenerator.model.MaterialFamilyProfile;
import com.medals.libsdatagenerator.model.PlasmaZone;
import com.medals.libsdatagenerator.model.Spectrum;
import com.medals.libsdatagenerator.model.UserInputConfig;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.WavelengthUnit;
import com.medals.libsdatagenerator.util.CommonUtils;
import com.medals.libsdatagenerator.util.InputCompositionProcessor;
import com.medals.libsdatagenerator.util.NISTUtils;
import com.medals.libsdatagenerator.util.PythonUtils;
import com.medals.libsdatagenerator.util.SeleniumUtils;
import com.medals.libsdatagenerator.util.SpectrumUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.json.JSONArray;
import org.json.JSONObject;

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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

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
     * @param sampleCsvPath      Path to the sample CSV file with real LIBS readings
     * @param delimiter          The delimiter character used by the sample CSV file
     * @param compositionString  Composition of the reference material (e.g.: "Fe-80,C-20")
     * @param instrumentName     Optional name for the instrument
     * @param baselineParams     Object containing lambda, p and maxIter values for baseline correction
     * @param noPlasmaZones      Number of plasma zones to consider and combine when comparing fit of synthetic spectrum
     * @param debugMode          Enable debug mode which shows browser actions in a browser window
     * @param materialFamilyName
     * @param outputPath
     * @return Generated InstrumentProfile
     * @throws IOException if file cannot be read
     */
    public InstrumentProfile generateProfile(Path sampleCsvPath, String delimiter, String compositionString,
                                             String instrumentName, BaselineCorrectionParams baselineParams,
                                             int noPlasmaZones, boolean debugMode, String materialFamilyName, Path outputPath) throws IOException {

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
        UserInputConfig userInputs =new UserInputConfig();
        userInputs.compositionInput = compositionString;
        MaterialGrade materialGrade = InputCompositionProcessor.getInstance().getMaterial(userInputs);
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
        logger.info("Starting " + noPlasmaZones + "-zone plasma parameter optimization...");
        MaterialFamilyProfile materialFamilyProfile = new MaterialFamilyProfile(materialFamilyName);
        // Setup output directories
        Path calibrationDir = Paths.get(CommonUtils.DATA_PATH, LIBSDataGenConstants.CALIBRATION_DIR);
        Files.createDirectories(calibrationDir);
        Path targetCsv = calibrationDir.resolve("target_processed.csv");
        Path zonesCsv = calibrationDir.resolve("best_zones.csv");
        optimizePlasmaParameters(materialFamilyProfile, processedMeasuredSpectrum, materialGrade, noPlasmaZones,
                debugMode, targetCsv, zonesCsv);
        profile.addMaterialFamilyProfile(materialFamilyProfile);
        CommonUtils.getInstance().saveModelToFile(outputPath, profile);
        // 7. Generate Jupyter Report
        if (PythonUtils.getInstance().setupPythonEnvironment()) {
            try {
                Path jupyterPath = PythonUtils.getInstance().getVenvJupyterPath();
                if (jupyterPath == null) {
                    throw new IOException("Jupyter executable not found in virtual environment.");
                }

                Path reportPath = calibrationDir
                        .resolve(LIBSDataGenConstants.CALIBRATION_REPORT_OUTPUT_FILE + ".ipynb");

                generateJupyterReport(profile, reportPath, targetCsv, zonesCsv, materialFamilyName);
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
     * @param profile            The instrument profile containing data and parameters
     * @param outputPath         Path to save the .ipynb file
     * @param materialFamilyName
     * @throws IOException if writing fails
     */
    public void generateJupyterReport(InstrumentProfile profile, Path outputPath, Path targetCsv, Path zonesCsv,
                                      String materialFamilyName) throws IOException {
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
        String content = getProcessedNotebook(profile, zonesCsv, templateContent, materialFamilyName);

        // Save filled notebook
        Files.write(outputPath, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        logger.info("Jupyter notebook report generated: " + outputPath);
    }

    private String getProcessedNotebook(InstrumentProfile profile, Path zonesCsv, String templateContent,
                                        String materialFamilyName) {
        // Use forward slashes for paths to ensure cross-platform compatibility in
        // Jupyter/Python without needing escaping
        String inputCsvPath = profile.getSourceFile().replace("\\", "/");
        String zonesCsvPath = zonesCsv.toAbsolutePath().toString().replace("\\", "/");
        MaterialFamilyProfile mfProfile = profile.getMaterialFamilyProfile(materialFamilyName);
        // Replace placeholders
        return templateContent
                .replace(LIBSDataGenConstants.INSTRUMENT_NAME, profile.getInstrumentName())
                .replace(LIBSDataGenConstants.RSQUARE_SCORE, String.format("%.4f", mfProfile.getRSquaredValue()))
                .replace(LIBSDataGenConstants.RMSE, String.format("%.4f", mfProfile.getRmse()))
                .replace(LIBSDataGenConstants.INPUT_CSV_PATH, inputCsvPath)
                .replace(LIBSDataGenConstants.ZONES_CSV_PATH, zonesCsvPath)
                .replace(LIBSDataGenConstants.LAMBDA, String.valueOf(profile.getLambda()))
                .replace(LIBSDataGenConstants.P, String.valueOf(profile.getP()))
                .replace(LIBSDataGenConstants.MAX_ITERATIONS, String.valueOf(profile.getMaxIterations()));
    }

    /**
     * Generates a Jupyter Notebook calibration report for the multi-material
     * (directory) flow.
     *
     * @param profile              Final profile with averaged zone parameters
     * @param outputPath           Where to write the {@code .ipynb} file
     * @param avgZonesCsvPath      Path to {@code averaged_best_zones.csv}
     * @param calibDir             Calibration directory that holds per-material
     *                             zones CSVs (used for {@code <PER_MATERIAL_ZONES_CSV_DIR>})
     * @param materialNames        Ordered list of successfully processed material names
     * @throws IOException if the template cannot be found or the notebook cannot be saved
     */
    public void generateJupyterReportForDirectory(InstrumentProfile profile,
            Path outputPath, Path avgZonesCsvPath, Path calibDir,
            List<String> materialNames) throws IOException {

        // Load multi-material template
        Path templatePath = Paths.get(CommonUtils.CONF_PATH,
                LIBSDataGenConstants.CALIBRATION_REPORT_MULTI_MATERIAL_TEMPLATE_FILE);
        String templateContent;
        if (!Files.exists(templatePath)) {
            logger.warning("Multi-material template not found in conf: " + templatePath + ". Checking resources.");
            try (java.io.InputStream is = getClass().getResourceAsStream(
                    "/" + LIBSDataGenConstants.CALIBRATION_REPORT_MULTI_MATERIAL_TEMPLATE_FILE)) {
                if (is == null) {
                    throw new IOException("Multi-material template file not found in conf or resources");
                }
                templateContent = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IOException("Failed to load multi-material template", e);
            }
        } else {
            templateContent = Files.readString(templatePath);
        }

        // Build Python list literal for material names, e.g. ["469", "470", "551"]
        StringBuilder matListBuilder = new StringBuilder("[");
        for (int i = 0; i < materialNames.size(); i++) {
            if (i > 0) matListBuilder.append(", ");
            matListBuilder.append("\"").append(materialNames.get(i)).append("\"");
        }
        matListBuilder.append("]");

        String avgZonesPath = avgZonesCsvPath.toAbsolutePath().toString().replace("\\", "/");
        String perMatDir = calibDir.toAbsolutePath().toString().replace("\\", "/");
        MaterialFamilyProfile mfProfile = profile.getMaterialFamilyProfile(materialNames.get(0));

        String content = templateContent
                .replace(LIBSDataGenConstants.INSTRUMENT_NAME, profile.getInstrumentName())
                .replace(LIBSDataGenConstants.RSQUARE_SCORE, String.format("%.4f", mfProfile.getRSquaredValue()))
                .replace(LIBSDataGenConstants.RMSE, String.format("%.4f", mfProfile.getRmse()))
                .replace(LIBSDataGenConstants.LAMBDA, String.valueOf(profile.getLambda()))
                .replace(LIBSDataGenConstants.P, String.valueOf(profile.getP()))
                .replace(LIBSDataGenConstants.MAX_ITERATIONS, String.valueOf(profile.getMaxIterations()))
                .replace(LIBSDataGenConstants.AVERAGED_ZONES_CSV_PATH, avgZonesPath)
                .replace(LIBSDataGenConstants.PER_MATERIAL_ZONES_CSV_DIR, perMatDir)
                .replace(LIBSDataGenConstants.NUM_MATERIALS_PROCESSED, String.valueOf(materialNames.size()))
                .replace(LIBSDataGenConstants.MATERIAL_NAMES_LIST, matListBuilder.toString());

        Files.write(outputPath, content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        logger.info("Multi-material Jupyter notebook report generated: " + outputPath);
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
     * @param profile InstrumentProfile to update with optimized parameters
     * @param processedMeasuredSpectrum Average measured spectrum
     * @param composition Material composition
     * @param plasmaZones Number of plasma zones to combine
     * @param debugMode Setting to true ensures Selenium doesn't run in headless mode and the browser window is visible
     * @param targetCsvPath Path to save target measured spectrum
     * @param zonesCsvPath Path to save spectra corresponding to optimised plasma zone parameters
     */
    private void optimizePlasmaParameters(MaterialFamilyProfile profile, Spectrum processedMeasuredSpectrum,
                                          MaterialGrade composition, int plasmaZones, boolean debugMode,
                                          Path targetCsvPath, Path zonesCsvPath) {

        double[] wavelengthGrid = processedMeasuredSpectrum.getWavelengths();
        double[] measuredIntensities = processedMeasuredSpectrum.getIntensities();

        logger.info("Starting Grid Search optimization for " + plasmaZones + " plasma zones...");

        // Configuration for fetching
        UserInputConfig config = new UserInputConfig();
        config.minWavelength = String.valueOf(wavelengthGrid[0]);
        config.maxWavelength = String.valueOf(wavelengthGrid[wavelengthGrid.length - 1]);
        config.resolution = "1000";
        UserInputConfig.setDebugMode(debugMode);

        // Define Grid Search Space
        double[] teValues = { 0.5, 0.8, 1.0, 1.2, 1.5, 1.7, 2.0 };
        double[] neValues = { 1e15, 5e15, 1e16, 5e16, 1e17, 5e17 };
        double[] vFractionValues = { 0.1, 0.3, 0.5, 0.7, 0.9 };
        double[] kAbsValues = { 0.0, 0.5, 1.0, 2.0, 3.5, 5.0 };

        // Normalization for RMSE calculation
        double maxMeasuredIntensity = Arrays.stream(measuredIntensities).max().orElse(1.0);
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
            // Save Target Spectrum
            saveSpectrumToCsv(targetCsvPath, wavelengthGrid, measuredIntensities);

            // Pre-fetch all necessary spectra
            logger.info("Starting grid search...");
            int i = 0;
            int gridSize = teValues.length * neValues.length;
            for (double te : teValues) {
                for (double ne : neValues) {
                    String key = String.format("%.2f_%.2e", te, ne);

                    if (spectrumCache.containsKey(key))
                        continue;

                    logger.info("Fetching spectrum for " + key);
                    String csvData = LIBSDataService.getInstance().fetchPlasmaZoneSpectrum(
                            composition.getComposition(), config, te, ne, composition.getRemainderElementIdx());

                    if (!csvData.equals(String.valueOf(java.net.HttpURLConnection.HTTP_NOT_FOUND))) {
                        Map<Double, Double> waveMap = NISTUtils.parseNistCsv(csvData, WavelengthUnit.NANOMETER.getUnitString());
                        double[] spectrum = spectrumUtils.interpolateSpectrum(waveMap, wavelengthGrid);
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
                normalizedSpectrumCache.put(entry.getKey(), spectrumUtils.normaliseSpectrum(entry.getValue()));
                CommonUtils.printProgressBar(i + 1, gridSize, "Spectra normalised", out);
                i++;
            }
            CommonUtils.finishProgressBar(gridSize, out);

            // Recursive Grid Search
            OptimizationResult bestResult = findBestCombination(plasmaZones, teValues, neValues,
                    normalizedSpectrumCache, normalisedMeasuredSpectrum);

            profile.setPlasmaZones(bestResult.plasmaZones);
            profile.setRmse(bestResult.rmse);
            profile.setRSquaredValue(bestResult.rSquared);
            profile.setScaleFactor(maxMeasuredIntensity);

            logger.info("Optimization complete. Best RMSE: " + bestResult.rmse + ", R^2: " + bestResult.rSquared);

            // Save Best Zones and Spectra to CSV
            if (zonesCsvPath != null) {
                saveZonesToCsv(zonesCsvPath, bestResult.plasmaZones, wavelengthGrid, spectrumCache, maxMeasuredIntensity);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Grid search optimization failed", e);
        } finally {
            SeleniumUtils.getInstance().quitSelenium();
        }
    }

    // -------------------------------------------------------------------------
    // Directory-based profile generation
    // -------------------------------------------------------------------------

    /**
     * Generates an instrument profile by recursively processing all CSV files
     * found under {@code dirPath}.  The reference compositions for each material
     * are read from a JSON file whose location is determined by
     * {@code refCompositionsPath} (defaults to
     * {@code <dirPath>/reference_compositions.json} when {@code null}).
     *
     * <p>The data directory structure can be either:
     * <ul>
     *   <li><b>Flat</b> – CSV files live directly in {@code dirPath} and are
     *       named {@code <materialName>_LSA_*.csv}.</li>
     *   <li><b>Subdirectory</b> – {@code dirPath} contains one sub-directory
     *       per material (named after the material).  All CSVs inside a
     *       sub-directory represent different measurement points on the same
     *       material.</li>
     * </ul>
     *
     * <p>For each material the full current single-material flow is executed
     * (averaging → baseline correction → grid search).  Per-material best-zones
     * CSVs are saved as {@code <materialName>_best_zones.csv} inside the
     * calibration output directory.  At the end, plasma parameters are averaged
     * <em>per zone</em> to produce the final profile.
     *
     * @param dirPath             Root data directory
     * @param refCompositionsPath Path to {@code reference_compositions.json},
     *                            or {@code null} to use the default location
     * @param delimiter           CSV delimiter used in the measurement files
     * @param instrumentName      Name/identifier for the instrument
     * @param baselineParams      Baseline-correction parameters
     * @param numPlasmaZones      Number of plasma zones
     * @param debugMode           Show Selenium browser window if true
     * @param outputPath
     * @return The generated {@link InstrumentProfile} with zone-averaged plasma parameters
     * @throws IOException if the reference-compositions file is missing, or no
     *                     material could be processed
     */
    public InstrumentProfile generateProfileFromDirectory(Path dirPath, Path refCompositionsPath, String delimiter,
                                                          String instrumentName, BaselineCorrectionParams baselineParams,
                                                          int numPlasmaZones, boolean debugMode, Path outputPath) throws IOException {

        logger.info("Generating instrument profile from directory: " + dirPath);

        // Resolve reference compositions file
        if (refCompositionsPath == null) {
            refCompositionsPath = dirPath.resolve(LIBSDataGenConstants.REFERENCE_COMPOSITIONS_DEFAULT_FILE);
        }
        if (!Files.exists(refCompositionsPath)) {
            String msg = "Reference compositions file not found: " + refCompositionsPath
                    + ". Cannot proceed without material composition data.";
            logger.severe(msg);
            System.out.println("Error: " + msg);
            throw new IOException(msg);
        }

        // Parse reference_compositions.json
        String jsonContent = Files.readString(refCompositionsPath);
        JSONObject rootJson = new JSONObject(jsonContent);
        JSONArray materialCompositions = rootJson.optJSONArray("materialCompositions");
        if (materialCompositions == null || materialCompositions.isEmpty()) {
            throw new IOException("No 'materialCompositions' array found in " + refCompositionsPath);
        }

        // Decide which discovery strategy to use for this data directory
        boolean hasSubdirs = hasSubdirectories(dirPath);
        logger.info("Data directory " + (hasSubdirs ? "has" : "does not have") + " material subdirectories");

        // Initialise instrument profile
        InstrumentProfile profile = new InstrumentProfile(null, dirPath.toString(),
                "directory:" + refCompositionsPath.toAbsolutePath());
        profile.setInstrumentName(instrumentName != null ? instrumentName : "Unknown");
        profile.setBaselineParams(baselineParams);
        int totalShots = 0;

        SpectrumUtils spectrumUtils = new SpectrumUtils();
        Path calibDir = Paths.get(CommonUtils.DATA_PATH, LIBSDataGenConstants.CALIBRATION_DIR);
        Files.createDirectories(calibDir);

        // Iterate over material types
        for (int typeIdx = 0; typeIdx < materialCompositions.length(); typeIdx++) {
            JSONObject materialTypeEntry = materialCompositions.getJSONObject(typeIdx);
            String materialType = materialTypeEntry.optString("materialType", "Unknown"+typeIdx);
            JSONArray materials = materialTypeEntry.optJSONArray("materials");

            if (materials == null || materials.isEmpty()) {
                logger.warning("No 'materials' array found in material type '" + materialType + "'. Skipping.");
                continue;
            }

            // Master state across all materials in a family
            Map<Integer, List<PlasmaZone>> zonesPerIndex = new HashMap<>();
            List<Double> rmseValues = new ArrayList<>();
            List<Double> rSquaredValues = new ArrayList<>();
            List<String> processedMaterialNames = new ArrayList<>();
            double scaleFactor = Double.MIN_VALUE;
            int materialsProcessed = 0;
            MaterialFamilyProfile materialFamilyProfile = new MaterialFamilyProfile(materialType);
            Path targetPath = calibDir.resolve(materialType + "_target_processed.csv");

            // Iterate over individual materials within this type
            for (int matIdx = 0; matIdx < materials.length(); matIdx++) {
                JSONObject materialEntry = materials.getJSONObject(matIdx);
                String materialName = materialEntry.optString("materialName", null);

                if (materialName == null || materialName.isBlank()) {
                    logger.warning("Material entry at index " + matIdx + " in type '"
                            + materialType + "' has no materialName. Skipping.");
                    continue;
                }

                logger.info("Processing material: " + materialName + " (" + materialType + ")");
                System.out.println("Processing material: " + materialName + " ...");

                try {
                    // Discover CSV files for this material
                    List<Path> csvFiles = findMaterialCsvFiles(dirPath, materialName, hasSubdirs);

                    if (csvFiles.isEmpty()) {
                        logger.warning("No CSV files found for material '" + materialName + "'. Skipping.");
                        System.out.println("  Warning: no CSV files found for '" + materialName + "'.");
                        continue;
                    }
                    logger.info("Found " + csvFiles.size() + " CSV file(s) for material: " + materialName);

                    // Extract wavelength grid from first CSV
                    double[] wavelengthGrid = extractWavelengthGrid(csvFiles.get(0), delimiter);
                    if (wavelengthGrid.length == 0) {
                        logger.warning("Failed to extract wavelength grid for material '"
                                + materialName + "'. Skipping.");
                        continue;
                    }
                    if (profile.getWavelengthGrid() == null) {
                        profile.setWavelengthGrid(wavelengthGrid);
                    }

                    // Collect all spectra from every CSV belonging to this material
                    List<double[]> allSpectra = new ArrayList<>();
                    for (Path csvFile : csvFiles) {
                        try {
                            allSpectra.addAll(extractMeasuredSpectra(csvFile, wavelengthGrid, delimiter));
                        } catch (IOException e) {
                            logger.warning("Could not read '" + csvFile + "' for material '"
                                    + materialName + "': " + e.getMessage());
                        }
                    }
                    if (allSpectra.isEmpty()) {
                        logger.warning("No spectra could be extracted for material '"
                                + materialName + "'. Skipping.");
                        continue;
                    }
                    totalShots += allSpectra.size();

                    // Average all spectra for this material
                    double[] avgSpectrum = spectrumUtils.calculateAverageSpectrum(allSpectra);

                    // Clip spectrum to valid LIBS range
                    Spectrum clippedSpectrum = spectrumUtils.clipSpectrum(wavelengthGrid, avgSpectrum);

                    // Baseline correction
                    double[] baselineCorrected = BaselineCorrectionService.getInstance().correctBaseline(
                            clippedSpectrum.getIntensities(), baselineParams.getLambda(),
                            baselineParams.getP(), baselineParams.getMaxIterations());
                    Spectrum processedSpectrum = new Spectrum(
                            clippedSpectrum.getWavelengths(), baselineCorrected);

                    // Build composition string from JSON and parse it
                    JSONObject compositionJson = materialEntry.optJSONObject("composition");
                    if (compositionJson == null) {
                        logger.warning("No 'composition' object for material '"
                                + materialName + "'. Skipping.");
                        continue;
                    }
                    String compositionString = buildCompositionStringFromJson(compositionJson);

                    UserInputConfig userInputConfig = new UserInputConfig();
                    userInputConfig.compositionInput = compositionString;
                    MaterialGrade materialGrade = InputCompositionProcessor.getInstance()
                            .getMaterial(userInputConfig);
                    materialGrade.setMaterialName(materialName);

                    if (materialGrade.getComposition() == null) {
                        logger.warning("Could not parse composition for material '"
                                + materialName + "'. Skipping.");
                        continue;
                    }

                    // Run grid-search optimisation; save per-material zones CSV
                    Path matZonesCsvPath = calibDir.resolve(materialName + "_best_zones.csv");
                    optimizePlasmaParameters(materialFamilyProfile, processedSpectrum, materialGrade, numPlasmaZones,
                            debugMode, targetPath, matZonesCsvPath);

                    // Accumulate zones per zone index (never mix zones from different indices)
                    for (int zoneIdx = 0; zoneIdx < materialFamilyProfile.getPlasmaZones().size(); zoneIdx++) {
                        zonesPerIndex
                                .computeIfAbsent(zoneIdx, k -> new ArrayList<>())
                                .add(materialFamilyProfile.getPlasmaZones().get(zoneIdx));
                    }
                    rmseValues.add(materialFamilyProfile.getRmse());
                    rSquaredValues.add(materialFamilyProfile.getRSquaredValue());
                    scaleFactor = Math.max(scaleFactor, materialFamilyProfile.getScaleFactor());
                    processedMaterialNames.add(materialName);
                    materialsProcessed++;

                    logger.info("Successfully processed material: " + materialName);
                    System.out.println("  Done: " + materialName);

                } catch (Exception e) {
                    String msg = "Error processing material '" + materialName + "': " + e.getMessage();
                    logger.log(Level.WARNING, msg, e);
                    System.out.println("  Warning: " + msg + ". Continuing with next material.");
                } finally {
                    SeleniumUtils.getInstance().quitSelenium();
                }
            }
            if (materialsProcessed == 0) {
                throw new IOException(
                        "No materials could be processed from directory: " + dirPath
                                + ". Check that reference_compositions.json matches the data files.");
            }

            // Average plasma parameters per zone
            List<PlasmaZone> averagedZones = new ArrayList<>();
            for (int zoneIdx = 0; zoneIdx < numPlasmaZones; zoneIdx++) {
                List<PlasmaZone> zoneList = zonesPerIndex.get(zoneIdx);
                if (zoneList != null && !zoneList.isEmpty()) {
                    double avgTe = zoneList.stream().mapToDouble(PlasmaZone::getTe).average().orElse(0.0);
                    double avgNe = zoneList.stream().mapToDouble(PlasmaZone::getNe).average().orElse(0.0);
                    double avgWeight = zoneList.stream().mapToDouble(PlasmaZone::getVFraction).average().orElse(0.0);
                    averagedZones.add(new PlasmaZone(avgTe, avgNe, avgWeight, 2.5));
                }
            }

            double avgRmse = rmseValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double avgRSquared = rSquaredValues.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);

            materialFamilyProfile.setPlasmaZones(averagedZones);
            materialFamilyProfile.setRmse(avgRmse);
            materialFamilyProfile.setRSquaredValue(avgRSquared);
            materialFamilyProfile.setScaleFactor(scaleFactor);
            profile.addMaterialFamilyProfile(materialFamilyProfile);

            // Save averaged zones CSV
            Path avgZonesCsvPath = calibDir.resolve(materialType + "_averaged_best_zones.csv");
            if (profile.getWavelengthGrid() != null && !averagedZones.isEmpty()) {
                // Use an empty spectrum cache since we have no per-zone raw spectra to write
                saveZonesToCsv(avgZonesCsvPath, averagedZones, profile.getWavelengthGrid(),
                        Collections.emptyMap(), 1.0);
            }
            logger.info("Profile generation for " + materialType + " complete. Processed " + materialsProcessed
                    + " materials, averaged " + averagedZones.size() + " plasma zone(s).");
        }
        profile.setNumShots(totalShots);
        CommonUtils.getInstance().saveModelToFile(outputPath, profile);

        // Generate Jupyter calibration report for directory mode
        if (PythonUtils.getInstance().setupPythonEnvironment()) {
            try {
                Path jupyterPath = PythonUtils.getInstance().getVenvJupyterPath();
                if (jupyterPath == null) {
                    throw new IOException("Jupyter executable not found in virtual environment.");
                }

                Path reportPath = calibDir.resolve(
                        LIBSDataGenConstants.CALIBRATION_REPORT_OUTPUT_FILE + "_multi_material.ipynb");

                // TODO: Refactor notebook to use saved instrument profile JSON rather than piecing everything together
                //  from the CSVs
//                generateJupyterReportForDirectory(
//                        profile, reportPath, avgZonesCsvPath, calibDir,
//                        processedMaterialNames);
//                executeNotebook(reportPath, jupyterPath);
//                convertNotebookToPdf(reportPath, jupyterPath,
//                        (instrumentName != null ? instrumentName : "Unknown") + "_multi_material");

            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to generate or execute multi-material calibration report", e);
            }
        } else {
            System.out.println(
                    "Warning: Calibration report could not be generated because Python 3 is not installed or "
                    + "environment setup failed.");
            logger.warning("Python environment setup failed. Skipping report generation.");
        }

        return profile;
    }

    // -------------------------------------------------------------------------
    // Private helpers for directory-based flow
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if {@code dir} contains at least one subdirectory.
     */
    private boolean hasSubdirectories(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.anyMatch(Files::isDirectory);
        }
    }

    /**
     * Discovers the CSV files associated with a given material.
     *
     * <p>If {@code hasSubdirs} is {@code true} the method looks for a
     * subdirectory of {@code sourceDir} whose name equals {@code materialName}
     * and returns all {@code .csv} files inside it.
     *
     * <p>If {@code hasSubdirs} is {@code false} the method scans {@code sourceDir}
     * directly for files whose names begin with
     * {@code <materialName>}{@value LIBSDataGenConstants#MATERIAL_NAME_CSV_SEPARATOR}.
     *
     * @param sourceDir    Root data directory
     * @param materialName Material name to search for
     * @param hasSubdirs   Whether the root directory uses the subdirectory layout
     * @return Sorted list of matching CSV paths (may be empty)
     */
    List<Path> findMaterialCsvFiles(Path sourceDir, String materialName,
            boolean hasSubdirs) throws IOException {
        List<Path> csvFiles = new ArrayList<>();
        if (hasSubdirs) {
            Path materialDir = sourceDir.resolve(materialName);
            if (Files.isDirectory(materialDir)) {
                try (Stream<Path> stream = Files.list(materialDir)) {
                    stream.filter(p -> !Files.isDirectory(p))
                          .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                          .sorted()
                          .forEach(csvFiles::add);
                }
            }
        } else {
            String prefix;
            if (sourceDir.endsWith(materialName)) {
                prefix = LIBSDataGenConstants.MATERIAL_NAME_CSV_SEPARATOR.substring(1);
            } else {
                prefix = materialName + LIBSDataGenConstants.MATERIAL_NAME_CSV_SEPARATOR;
            }
            try (Stream<Path> stream = Files.list(sourceDir)) {
                stream.filter(p -> !Files.isDirectory(p))
                      .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                      .filter(p -> p.getFileName().toString().startsWith(prefix))
                      .sorted()
                      .forEach(csvFiles::add);
            }
        }
        return csvFiles;
    }

    /**
     * Converts a JSON composition object from {@code reference_compositions.json}
     * to the composition-string format expected by
     * {@link InputCompositionProcessor} (e.g. {@code "Fe-80.0,C-2.5,Si-#"}).
     *
     * @param compositionJson  JSONObject whose keys are element symbols and
     *                         values are either numeric percentages or the
     *                         remainder marker {@code "#"}
     * @return Composition string
     */
    String buildCompositionStringFromJson(JSONObject compositionJson) {
        StringBuilder sb = new StringBuilder();
        Iterator<String> keys = compositionJson.keys();
        while (keys.hasNext()) {
            String element = keys.next();
            if (sb.length() > 0) {
                sb.append(",");
            }
            Object value = compositionJson.get(element);
            sb.append(element).append("-").append(value.toString());
        }
        return sb.toString();
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

//    private static class ZoneParams {
//        double te;
//        double ne;
//
//        public ZoneParams(double te, double ne) {
//            this.te = te;
//            this.ne = ne;
//        }
//    }

    private static class OptimizationResult {
        List<PlasmaZone> plasmaZones = new ArrayList<>();
//        List<Double> weights = new ArrayList<>();
        double rmse = Double.MAX_VALUE;
        double rSquared = Double.MIN_VALUE;
    }

    private OptimizationResult findBestCombination(int numZones, double[] teValues, double[] neValues,
            Map<String, double[]> normalizedCache, double[] targetSpectrum) {

        PrintStream out = System.out;
        OptimizationResult bestResult = new OptimizationResult();

        out.println("Generating parameter and weight combinations for grid search...");
        // Generate parameter combinations
        List<List<PlasmaZone>> allPlasmaZoneCombinations = new ArrayList<>();
        generateParamCombinations(numZones, teValues, neValues, new ArrayList<>(), allPlasmaZoneCombinations);

        // Generate weight combinations (simplex steps of 0.1)
        List<List<Double>> allWeightCombinations = new ArrayList<>();
        generateWeightCombinations(numZones, 1.0, new ArrayList<>(), allWeightCombinations);

        // Iterate and find best
        int progress = 0;
        int totalCombinations = allPlasmaZoneCombinations.size() * allWeightCombinations.size();
        for (List<PlasmaZone> plasmaZones : allPlasmaZoneCombinations) {
            for (List<Double> weights : allWeightCombinations) {
                // Combine spectra
                double[] combined = new double[targetSpectrum.length];
                boolean possible = true;

                for (int i = 0; i < numZones; i++) {
                    PlasmaZone pz = plasmaZones.get(i);
                    String key = String.format("%.2f_%.2e", pz.getTe(), pz.getNe());
                    double[] s = normalizedCache.get(key);
                    if (s == null) {
                        possible = false;
                        break;
                    }
                    pz.setVFraction(weights.get(i));
                    for (int j = 0; j < combined.length; j++) {
                        combined[j] += s[j] * pz.getVFraction();
                    }
                }

                if (!possible)
                    continue;

                SpectrumUtils spectrumUtils = new SpectrumUtils();
                // Normalize combined result for comparison against normalized target
                double[] finalCombined = spectrumUtils.normaliseSpectrum(combined);

                double rmse = spectrumUtils.calculateRMSE(targetSpectrum, finalCombined);
                double rSquared = spectrumUtils.calculateSpectralSimilarity(targetSpectrum, finalCombined);

                // Selection Criteria: Strictly better RMSE and R^2, or improvement in R^2
                if (rmse < bestResult.rmse && rSquared > bestResult.rSquared) {
                    bestResult.rmse = rmse;
                    bestResult.rSquared = rSquared;
                    bestResult.plasmaZones = plasmaZones;
//                    bestResult.weights = weights;
                } else if (rmse == bestResult.rmse && rSquared > bestResult.rSquared) {
                    bestResult.rSquared = rSquared;
                    bestResult.plasmaZones = plasmaZones;
//                    bestResult.weights = weights;
                } else if (rSquared == bestResult.rSquared && rmse < bestResult.rmse) {
                    bestResult.rmse = rmse;
                    bestResult.plasmaZones = plasmaZones;
//                    bestResult.weights = weights;
                }
                CommonUtils.printProgressBar(progress + 1, totalCombinations, "combinations processed", out);
                progress++;
            }
        }
        CommonUtils.finishProgressBar(totalCombinations, out);

        return bestResult;
    }

    private void generateParamCombinations(int zonesLeft, double[] teValues, double[] neValues,
            List<PlasmaZone> current, List<List<PlasmaZone>> results) {
        if (zonesLeft == 0) {
            results.add(new ArrayList<>(current));
            return;
        }

        double lastTe = current.isEmpty() ? Double.MAX_VALUE : current.get(current.size() - 1).getTe();

        for (double te : teValues) {
            // Constraint: Te must be <= previous Te (Hot to Cool ordering)
            if (te > lastTe)
                continue;

            for (double ne : neValues) {
                current.add(new PlasmaZone(te, ne));
                generateParamCombinations(zonesLeft - 1, teValues, neValues, current, results);
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

        // Step size 0.05, up to remaining weight
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
                record.add(zone.getVFraction());

                if (rawSpectrum != null) {
                    // Normalize and then scale
                    record.addAll(spectrumUtils.normaliseAndScaleSpectrum(rawSpectrum, scaleFactor));
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
