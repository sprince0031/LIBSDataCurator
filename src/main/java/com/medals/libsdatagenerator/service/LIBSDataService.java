package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.matweb.SeriesInput;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.ClassLabelType;
import com.medals.libsdatagenerator.model.UserInputConfig;
import com.medals.libsdatagenerator.util.CommonUtils;
import com.medals.libsdatagenerator.util.InputCompositionProcessor;
import com.medals.libsdatagenerator.util.NISTUtils;
import com.medals.libsdatagenerator.util.SeleniumUtils;
import com.medals.libsdatagenerator.util.CSVUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.openqa.selenium.*;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;

import java.io.IOException;
import java.io.StringReader;
import java.io.PrintStream;
import java.io.BufferedReader;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays; // Added import
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Service class for all NIST LIBS website related functionality.
 * 
 * @author Siddharth Prince | 17/12/24 13:21
 */

public class LIBSDataService {
    private static Logger logger = Logger.getLogger(LIBSDataService.class.getName());

    public static LIBSDataService instance = null;
    private final CommonUtils commonUtils = new CommonUtils();
    private boolean firstComposition = true;

    public static LIBSDataService getInstance() {
        if (instance == null) {
            instance = new LIBSDataService();
        }
        return instance;
    }

    public Map<String, String> processLIBSQueryParams(List<Element> elements, UserInputConfig config) {
        // Processing the information for each element and adding to the query params
        // hashmap
        // Sample query params:
        // composition=C:0.26;Mn:0.65;Si:0.22;Fe:98.87&mytext[]=C&myperc[]=0.26&spectra=C0-2,Mn0-2,Si0-2,Fe0-2&mytext[]=Mn&myperc[]=0.65&mytext[]=Si&myperc[]=0.22&mytext[]=Fe&myperc[]=98.87&low_w=200&limits_type=0&upp_w=600&show_av=2&unit=1&resolution=1000&temp=1&eden=1e17&maxcharge=2&min_rel_int=0.01&int_scale=1&libs=1
        // https://physics.nist.gov/cgi-bin/ASD/lines1.pl?composition=C%3A0.26%3BMn%3A0.65%3BSi%3A0.22%3BFe%3A98.87&mytext%5B%5D=C&myperc%5B%5D=0.26&spectra=C0-2%2CMn0-2%2CSi0-2%2CFe0-2&mytext%5B%5D=Mn&myperc%5B%5D=0.65&mytext%5B%5D=Si&myperc%5B%5D=0.22&mytext%5B%5D=Fe&myperc%5B%5D=98.87&low_w=200&limits_type=0&upp_w=600&show_av=2&unit=1&resolution=1000&temp=1&eden=1e17&maxcharge=2&min_rel_int=0.01&int_scale=1&libs=1
        Map<String, String> queryParams = new HashMap<>();

        // Creating composition string
        String composition = "";
        String spectra = "";
        for (Element element : elements) {
            composition = String.join(";", composition, element.toString());
            spectra = String.join(",", spectra, element.getSymbol() + "0-2");
        }

        // Adding remaining elements from full composition list
        for (String element : LIBSDataGenConstants.STD_ELEMENT_LIST) {
            if (!composition.contains(element)) {
                composition = String.join(";", composition, element + ":0");
                spectra = String.join(",", spectra, element + "0-2");
            }
        }

        // Add composition
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_COMPOSITION, composition.substring(1));

        // Add spectra
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_SPECTRA, spectra.substring(1));

        // Add min wavelength
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_LOW_W, config.minWavelength);

        // Add max wavelength
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_UPP_W, config.maxWavelength);

        // Add wavelength resolution
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_RESOLUTION, config.resolution);

        // Add plasma temperature
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_TEMP, config.plasmaTemp);

        // Add electron density
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_EDEN, config.electronDensity);

        // Add wavelength unit - default Nm (1)
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_UNIT, config.wavelengthUnit.getUrlParam());

        // Show wavelengths in:
        // Vacuum (< 200 nm) Air (200 - 2000 nm) Vacuum (> 2000 nm) - 2 (default)
        // Vacuum (all wavelengths) - 3
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_SHOW_AV, config.wavelengthCondition.getUrlParam());

        // Advanced input params
        // Add max ion charge
        // 2+, 3+, 4+, no limit (value set to 109 in URL)
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_MAXCHARGE, config.maxIonCharge.getUrlParam());

        // Add min relative intensity
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_MIN_REL_INT, config.minRelativeIntensity.getUrlParam());

        // Add intensity scale
        // Energy flux - 1 (default)
        // Photon flux - 2
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_INT_SCALE, config.intensityScale.getUrlParam());

        // The rest of the query params are kept constant. No direct input field mapped for these in NIST LIBS form.
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_LIMITS_TYPE, "0");
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_LIBS, "1");

        return queryParams;
    }

    /**
     * Composes NIST LIBS URL (query) for fetching spectrum data for given input
     * @param elements list of Elements in composition
     * @param config User input configuration object containing all user input data
     * @return csv content if successful; HTTP_NOT_FOUND (404) error status string if failure.
     */
    public String fetchLIBSData(List<Element> elements, UserInputConfig config, int remainderElementIdx) {
        Path compositionFilePath = null;
        try {
            compositionFilePath = commonUtils.getCompositionCsvFilePath(config.csvDirPath, elements);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return fetchLIBSData(elements, config, compositionFilePath, true, remainderElementIdx);
    }
    
    /**
     * Composes NIST LIBS URL (query) for fetching spectrum data for given input
     * @param composition list of Elements in composition
     * @param config User input configuration object containing all user input data
     * @param quitDriver whether to quit the Selenium driver after fetching (false to keep session alive)
     * @return csv content if successful; HTTP_NOT_FOUND (404) error status string if failure.
     */
    public String fetchLIBSData(List<Element> composition, UserInputConfig config, Path csvFilePath, boolean quitDriver, int remainderElementIdx) {
        SeleniumUtils seleniumUtils = SeleniumUtils.getInstance();

        try {
            if (!seleniumUtils.isDriverOnline()) {
                logger.warning("Driver not online - falling back to server request");
                Map<String, String> queryParams = processLIBSQueryParams(composition, config);
                seleniumUtils.connectToWebsite(
                        commonUtils.getUrl(LIBSDataGenConstants.NIST_LIBS_QUERY_URL_BASE, queryParams)
                );
            }
            NISTUtils nistUtils = new NISTUtils(seleniumUtils);

            // First composition: make initial server request and keep browser session alive
            if (firstComposition) {
                // Perform client-side recalculation with user's desired resolution
                nistUtils.setCorrectResolution(config.resolution);
                firstComposition = false;
                logger.info("First composition fetched - browser session kept alive for variations");
            } else { // Subsequent compositions: use client-side recalculation with existing browser session
                // Update element percentages in the form
                nistUtils.updateElementPercentages(composition);
                logger.info("Variation fetched using client-side recalculation");
            }

            // Find and click the Recalculate button
            WebElement recalcButton = seleniumUtils.waitForElementClickable(
                    By.name(LIBSDataGenConstants.NIST_LIBS_RECALC_BUTTON_NAME)
            );

            recalcButton.click();
            logger.info("Clicked Recalculate button for variation");

            try {
                // Wait for recalculation to complete
                WebDriverWait wait = seleniumUtils.getWait(2);
                wait.until(ExpectedConditions.alertIsPresent());

                logger.info("Alert detected");
                nistUtils.handleRecalculateAlert(composition, remainderElementIdx);

            } catch (TimeoutException e) {
                // No alert appeared within 2 seconds. Assume success.
                logger.info("No alert detected, proceeding to download.");
            }

            seleniumUtils.waitForElementPresent(By.name(LIBSDataGenConstants.NIST_LIBS_GET_CSV_BUTTON_HTML_TEXT));
            logger.info("Recalculation completed");

            return nistUtils.downloadCsvData(composition, csvFilePath);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unable to fetch data from NIST LIBS website", e);
        } finally {
            if (quitDriver) {
                seleniumUtils.quitSelenium();
            }
        }
        return String.valueOf(HttpURLConnection.HTTP_NOT_FOUND);
    }

    private void processCompositionsForNIST_LIBS(Map<String, Object> fetchedSpectralData, List<List<Element>> compositions, UserInputConfig config, MaterialGrade sourceMaterial) {

        // Keeping track of all wavelength across all comps:
        Set<Double> allWavelengths = (Set<Double>) fetchedSpectralData.get(LIBSDataGenConstants.SPECTRAL_DATA_MAP_KEY_WAVELENGTHS);
        // Store for each composition's *string ID* -> (wave -> intensity) & (element symbol -> percentage)
        Map<String, Object> compWaveIntensityMap = new HashMap<>();

        int compositionsProcessed = 0;
        PrintStream out = System.out;

        // Get selenium instance for reuse across variations
        SeleniumUtils seleniumUtils = SeleniumUtils.getInstance();
        firstComposition = true;
        try {
            // For each composition, fetch the CSV, parse it, store data
            for (List<Element> composition : compositions) {
                // Fetch CSV data from NIST
                String csvData;
                Path compositionFilePath = commonUtils.getCompositionCsvFilePath(config.csvDirPath, composition);
                String compositionId = commonUtils.buildCompositionStringForFilename(composition);

                logger.info("Checking for existing LIBS data file at: " + compositionFilePath.toAbsolutePath());
                boolean compositionFileExists = Files.exists(compositionFilePath);

                if (config.forceFetch || !compositionFileExists) {
                    logger.info("Fetching LIBS data for " + compositionId + " (forceFetch=" + config.forceFetch + ", fileExists=" + compositionFileExists + ")");
                    csvData = fetchLIBSData(composition, config, compositionFilePath, false, sourceMaterial.getRemainderElementIdx());
                } else {
                    logger.info("Reading cached composition data for " + compositionId + " from: " + compositionFilePath.toAbsolutePath());
                    try (BufferedReader csvReader = Files.newBufferedReader(compositionFilePath)) {
                        csvData = csvReader.lines().collect(Collectors.joining("\n")); // Ensure newlines are preserved
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }

                // If fetch failed, skip
                if (csvData.equals(String.valueOf(HttpURLConnection.HTTP_NOT_FOUND))) {
                    logger.severe("Failed to fetch data for composition " + compositionId);
                    continue;
                }

                // Parse wave->intensity
                Map<Double, Double> waveMap;
                try {
                    waveMap = parseNistCsv(csvData, allWavelengths);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Error parsing CSV for " + compositionId, e);
                    continue;
                }
                compWaveIntensityMap.put(LIBSDataGenConstants.SPECTRAL_DATA_MAP_KEY_SPECTRA, waveMap);

                // Also store element symbols + their percentages
                Map<String, Double> elemMap = new HashMap<>();
                for (Element elem : composition) {
                    elemMap.put(elem.getSymbol(), elem.getPercentageComposition());
                }
                compWaveIntensityMap.put(LIBSDataGenConstants.SPECTRAL_DATA_MAP_KEY_COMPOSITIONS, elemMap);

                // Add class label columns based on configuration
                // If user explicitly specified a class type, only add that specific column
                // Otherwise, add both material grade name and material type columns by default
                if (config.classLabelTypeExplicitlySet) {
                    // User explicitly selected a class type
                    if (config.classLabelType != ClassLabelType.COMPOSITION_PERCENTAGE) {
                        // Add only the specific class column requested
                        String classLabelColumnName = getClassLabelColumnName(config.classLabelType);
                        String classLabel = generateClassLabel(config.classLabelType, sourceMaterial);
                        compWaveIntensityMap.put(classLabelColumnName, classLabel);
                    }
                    // For composition percentages (type 1), no additional class column is needed as the individual element columns serve as the class labels
                } else {
                    // Default behavior: add both material columns
                    String gradeLabel = generateClassLabel(ClassLabelType.MATERIAL_GRADE_NAME, sourceMaterial);
                    String typeLabel = generateClassLabel(ClassLabelType.MATERIAL_TYPE, sourceMaterial);
                    compWaveIntensityMap.put(LIBSDataGenConstants.CSV_HEADER_MATERIAL_GRADE_NAME, gradeLabel);
                    compWaveIntensityMap.put(LIBSDataGenConstants.CSV_HEADER_MATERIAL_TYPE, typeLabel);
                }
                fetchedSpectralData.put(compositionId, compWaveIntensityMap);

                // Calculate progress
                CommonUtils.printProgressBar(compositionsProcessed + 1, compositions.size(), "samples completed", out);
                compositionsProcessed++;
            }

            // Print newline after progress bar completion
            CommonUtils.finishProgressBar(compositions.size(), out);
            fetchedSpectralData.put(LIBSDataGenConstants.SPECTRAL_DATA_MAP_KEY_WAVELENGTHS, allWavelengths);
        } catch (Exception e) {
            throw new RuntimeException("Error while processing compositions for NIST website", e);
        } finally {
            // Clean up: close the browser session if it's still open
            if (seleniumUtils.isDriverOnline()) {
                seleniumUtils.quitSelenium();
                logger.info("Browser session closed after processing all compositions");
            }
        }
    }

    public void generateDataset(List<MaterialGrade> materialGrades, UserInputConfig config) {

        Set<Double> allWavelengths = new TreeSet<>();
        Map<String, Object> fetchedSpectralData = new HashMap<>();
        fetchedSpectralData.put(LIBSDataGenConstants.SPECTRAL_DATA_MAP_KEY_WAVELENGTHS, allWavelengths); // Initialise wavelength TreeSet to be updated for each composition

        for (MaterialGrade materialGrade : materialGrades) {
            if (config.performVariations) {
                if (config.variationMode == NistUrlOptions.VariationMode.DIRICHLET) {
                    if (materialGrade.getParentSeries().getOverviewGuid() == null) {
                        System.out.println("Please provide an overview GUID to generate variations.");
                        logger.severe("Overview GUID not present for Dirichlet sampling for "
                                + commonUtils.buildCompositionString(materialGrade.getComposition()) + ". Skipping!");
                        continue;
                    }
                }

                List<List<Element>> compositions = CompositionalVariations.getInstance()
                        .generateCompositionalVariations(materialGrade, config);

                if (compositions != null && !compositions.isEmpty()) {
                    // Apply coating to all variations of material if this is a coated series
                    if (materialGrade.getParentSeries().isCoated()) {
                        SeriesInput series = materialGrade.getParentSeries();
                        compositions = InputCompositionProcessor.getInstance().applyCoating(compositions,
                                series.getCoatingElement(), config.scaleCoating);
                    }
                    System.out.println("Fetching LIBS spectra from NIST for all variations of " + materialGrade.getMaterialName());
                    processCompositionsForNIST_LIBS(fetchedSpectralData, compositions, config, materialGrade);
                    logger.info("Successfully fetched LIBS spectra for all variations of " + materialGrade);
                } else {
                    logger.warning("No compositions generated for input: " + materialGrade);
                }
            } else {
                // This is the original non-variation path for -c
                String csvData = fetchLIBSData(materialGrade.getComposition(), config, materialGrade.getRemainderElementIdx());
                List<List<Element>> compositions = new ArrayList<>(); // Dummy list of list just to hold one composition for compatability
                compositions.add(materialGrade.getComposition());
                processCompositionsForNIST_LIBS(fetchedSpectralData, compositions, config, materialGrade);
                logger.info("Successfully fetched LIBS data for composition: " + materialGrade);
            }
        }

        writeSpectralDataToMasterCsv(fetchedSpectralData, config);

    }

    private void writeSpectralDataToMasterCsv(Map<String, Object> fetchedSpectralData, UserInputConfig config) {
        Set<Double> allWavelengths = (Set<Double>) fetchedSpectralData.remove(LIBSDataGenConstants.SPECTRAL_DATA_MAP_KEY_WAVELENGTHS);

        try {
            // Write a single "master CSV" with all results
            // columns: composition, each wavelength (sorted), each element symbol (sorted).

            // Convert sets to sorted lists
            List<Double> sortedWaves = new ArrayList<>(allWavelengths);
            Collections.sort(sortedWaves); // TreeSet is already sorted, but okay to be explicit
            // Use STD_ELEMENT_LIST for header and row structure for elements
            List<String> sortedSymbols = new ArrayList<>(Arrays.asList(LIBSDataGenConstants.STD_ELEMENT_LIST));
            Collections.sort(sortedSymbols); // Ensure canonical order

            // Build header
            List<String> header = buildHeader(config, sortedWaves, sortedSymbols);

            // Write out to "master.csv" inside savePath
            Path masterCsvPath = Paths.get(config.csvDirPath, LIBSDataGenConstants.MASTER_DATASET_FILENAME);
            // Ensure the 'header' List<String> is converted to String[] for getCsvPrinter

            String[] headerArray = header.toArray(new String[0]);
            try (CSVPrinter printer = CSVUtils.getCsvPrinter(masterCsvPath, config.appendMode, headerArray)) {
                // If appending, and the file might have already existed and had data (and thus headers),
                // CSVUtils.getCsvPrinter when appendMode=true opens without writing new headers.
                // If not appending, or if appending and file is new, headers are written by CSVUtils.

                // Each composition => one row
                for (String compId : fetchedSpectralData.keySet()) {
                    // Get the specific element maps for the row
                    Map<String, Object> compSpectralData = (Map<String, Object>) fetchedSpectralData.get(compId);
                    Map<Double, Double> waveMap = (Map<Double, Double>) compSpectralData.get(LIBSDataGenConstants.SPECTRAL_DATA_MAP_KEY_SPECTRA);
                    Map<String, Double> elemMap = (Map<String, Double>) compSpectralData.get(LIBSDataGenConstants.SPECTRAL_DATA_MAP_KEY_COMPOSITIONS);
                    String gradeLabel = (String) compSpectralData.get(LIBSDataGenConstants.CSV_HEADER_MATERIAL_GRADE_NAME);
                    String typeLabel = (String) compSpectralData.get(LIBSDataGenConstants.CSV_HEADER_MATERIAL_TYPE);

                    List<String> row = new ArrayList<>();
                    row.add(compId);

                    // For each wave, add intensity (or 0.0 if missing)
                    for (Double w : sortedWaves) {
                        Double intensity = waveMap.getOrDefault(w, 0.0);
                        row.add(String.valueOf(intensity));
                    }

                    // For each element symbol FROM STD_ELEMENT_LIST, add the percentage
                    for (String sym : sortedSymbols) { // Iterates using STD_ELEMENT_LIST
                        // Correctly gets 0 if element not in this specific compId's map
                        Double pct = elemMap.getOrDefault(sym, 0.0);
                        row.add(String.valueOf(pct));
                    }

                    if (gradeLabel != null) {
                        row.add(gradeLabel);
                    }
                    if (typeLabel != null) {
                        row.add(typeLabel);
                    }

                    printer.printRecord(row);
                }

                logger.info("Master dataset saved to: " + masterCsvPath.toAbsolutePath()); // Enhanced log

            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error writing master dataset CSV", e);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Error writing master dataset CSV", e);
        }
    }

    private List<String> buildHeader(UserInputConfig config, List<Double> sortedWaves, List<String> sortedSymbols) {
        List<String> header = new ArrayList<>();
        header.add("composition");
        // Add wave columns
        for (Double w : sortedWaves) {
            header.add(String.valueOf(w));
        }
        // Add element columns
        for (String sym : sortedSymbols) { // Now uses STD_ELEMENT_LIST
            header.add(sym);
        }

        // Add class label columns based on configuration
        // If user explicitly specified a class type, only add that specific column
        // Otherwise, add both material grade name and material type columns by default
        if (config.classLabelTypeExplicitlySet) {
            // User explicitly selected a class type
            if (config.classLabelType != ClassLabelType.COMPOSITION_PERCENTAGE) {
                // Add only the specific class column requested
                String classLabelColumnName = getClassLabelColumnName(config.classLabelType);
                header.add(classLabelColumnName);
            }
            // For composition percentages (type 1), no additional class column is needed as the individual element columns serve as the class labels
        } else {
            // Default behavior: add both material columns
            header.add(LIBSDataGenConstants.CSV_HEADER_MATERIAL_GRADE_NAME);
            header.add(LIBSDataGenConstants.CSV_HEADER_MATERIAL_TYPE);
        }
        return header;
    }

    /**
     * Parse in-memory CSV from NIST (with columns "Wavelength (nm)" and "Sum"),
     * 
     * @return a map: (wavelength -> intensity).
     *         Also add each encountered wavelength to 'allWavelengths'.
     */
    private Map<Double, Double> parseNistCsv(String csvData, Set<Double> allWavelengths) throws IOException, IllegalArgumentException {
        Map<Double, Double> waveMap = new HashMap<>();

        // Check if CSV string is correctly parsed

        StringReader sr = new StringReader(csvData);
        Iterable<CSVRecord> records = CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .parse(sr);

        // Headers might be "Wavelength (nm)" and "Sum"—confirm with a real example.
        for (CSVRecord record : records) {
            String waveStr = record.get(0); // "Wavelength (<UNIT>)"
            String sumStr = record.get(1); // "Sum" or "Sum(calc)" if using recalculation form

            if (waveStr != null && sumStr != null) {
                double wavelength = Double.parseDouble(waveStr);
                double intensity = Double.parseDouble(sumStr);

                waveMap.put(wavelength, intensity);
                allWavelengths.add(wavelength);
            }
        }
        return waveMap;
    }

    /**
     * Gets the column name for the class label based on the class label type
     */
    private String getClassLabelColumnName(ClassLabelType classLabelType) {
        return switch (classLabelType) {
            case MATERIAL_GRADE_NAME -> LIBSDataGenConstants.CSV_HEADER_MATERIAL_GRADE_NAME;
            case MATERIAL_TYPE -> LIBSDataGenConstants.CSV_HEADER_MATERIAL_TYPE;
            default -> throw new IllegalStateException("Unexpected value: " + classLabelType);
        };
    }

    /**
     * Generates the class label value for a given composition
     */
    private String generateClassLabel(ClassLabelType classLabelType, MaterialGrade sourceMaterial) {
        return switch (classLabelType) {
            case MATERIAL_GRADE_NAME -> {
                if (sourceMaterial != null && sourceMaterial.getMaterialName() != null && !sourceMaterial.getMaterialName().isEmpty()) {
                    yield sourceMaterial.getMaterialName();
                } else {
                    yield "Unknown Grade"; // Fallback for missing material names
                }
            }
            case MATERIAL_TYPE -> {
                if (sourceMaterial != null && sourceMaterial.getParentSeries() != null) {
                    yield processSeriesKeyToMaterialType(sourceMaterial.getParentSeries().getSeriesKey());
                } else {
                    yield "Unknown Type"; // Fallback for missing series information
                }
            }
            default -> throw new IllegalStateException("Unexpected value: " + classLabelType);
        };
    }

    /**
     * Converts series key to readable material type by replacing dots and underscores with spaces
     */
    public String processSeriesKeyToMaterialType(String seriesKey) {
        if (seriesKey == null || seriesKey.equals(LIBSDataGenConstants.DIRECT_ENTRY)) {
            return "Direct Entry";
        }
        
        // Convert dots and underscores to spaces
        return seriesKey.replace('.', ' ').replace('_', ' ');
    }
}
