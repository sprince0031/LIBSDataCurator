package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.WavelengthUnit;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.ClassLabelType;
import com.medals.libsdatagenerator.model.nist.UserInputConfig;
import com.medals.libsdatagenerator.util.CommonUtils;
import com.medals.libsdatagenerator.util.PeriodicTable;
import com.medals.libsdatagenerator.util.SeleniumUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

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
//    private static Map<String, Map<Double, Double>> compositionalDataset = new HashMap<>();
    private final CommonUtils commonUtils = new CommonUtils();

    public static LIBSDataService getInstance() {
        if (instance == null) {
            instance = new LIBSDataService();
        }
        return instance;
    }

    /**
     * Composes NIST LIBS URL (query) for fetching spectrum data for given input
     * @param elements list of Elements in composition
     * @param config User input configuration object containing all user input data
     * @return csv save path if successful; HTTP_NOT_FOUND (404) error status string if failure.
     */
    public String fetchLIBSData(List<Element> elements, UserInputConfig config) {
        SeleniumUtils seleniumUtils = SeleniumUtils.getInstance();

        try {
            Map<String, String> queryParams = processLIBSQueryParams(elements, config);
            seleniumUtils.connectToWebsite(
                    commonUtils.getUrl(LIBSDataGenConstants.NIST_LIBS_QUERY_URL_BASE, queryParams)
            );

            WebElement csvButton = seleniumUtils.getDriver()
                    .findElement(By.name(LIBSDataGenConstants.NIST_LIBS_GET_CSV_BUTTON_HTML_TEXT));
            csvButton.click();

            // Switch to the new tab/window
            String originalWindow = seleniumUtils.getDriver().getWindowHandle();
            for (String windowHandle : seleniumUtils.getDriver().getWindowHandles()) {
                if (!windowHandle.equals(originalWindow)) {
                    seleniumUtils.getDriver().switchTo().window(windowHandle);
                    break;
                }
            }

            // Grab the CSV content (likely a <pre> block in the page source)
            String csvData = seleniumUtils.getDriver().findElement(By.tagName("pre")).getText();

            // Create data folder if it doesn't exist
            Path dataPath = Paths.get(config.csvDirPath, "NIST LIBS");
            if (!Files.exists(dataPath)) {
                Files.createDirectories(dataPath);
            }

            // Save to a file with a unique name
            // Build the composition string ID using the cross-platform compatible method
            String compositionId = commonUtils.buildCompositionStringForFilename(elements);
            String filename = "composition_" + compositionId + ".csv";
            Path csvPath = Paths.get(String.valueOf(dataPath), filename);
            logger.info("Saving fetched LIBS data to: " + csvPath.toAbsolutePath()); // New log
            Files.write(csvPath, csvData.getBytes());
            // System.out.println("Saved: " + filename);
            logger.info("Saved: " + csvPath); // Existing log, kept for consistency with potential existing log parsing

            // Close the CSV tab
            seleniumUtils.getDriver().close();
            // Switch back to the original window
            seleniumUtils.getDriver().switchTo().window(originalWindow);
            return csvData;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unable to fetch data from NIST LIBS website", e);
        } finally {
            seleniumUtils.quitSelenium();
        }
        return String.valueOf(HttpURLConnection.HTTP_NOT_FOUND);
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

    public List<Element> generateElementsList(String[] composition) throws IOException {
        List<Element> elementsList = new ArrayList<>();
        double totalPercentage = 0.0;
        for (String elementString : composition) {
            // elementNamePercent[0] -> Symbol, elementNamePercent[1] -> Percentage of
            // composition
            String[] elementNamePercent = elementString.split("-");

            // Check if the current input element exists in the periodic table
            if (!PeriodicTable.isValidElement(elementNamePercent[0])) {
                logger.log(Level.SEVERE, "Invalid input. " + elementNamePercent[0] + " does not exist.");
                throw new IOException("Invalid element " + elementNamePercent[0] + " given as input");
            }

            double currentPercentage;
            double minPercentage = 0;
            double maxPercentage = 0;
            if (elementNamePercent[1].contains(":")) {
                String[] compositionRange = elementNamePercent[1].split(":");
                minPercentage = Double.parseDouble(compositionRange[0]);
                maxPercentage = Double.parseDouble(compositionRange[1]);
            }
            // If the element percentage value is "#", consider as the remaining percentage
            // composition
            if (!elementNamePercent[1].equals("#")) {
                if (minPercentage == 0 && maxPercentage == 0) {
                    currentPercentage = Double.parseDouble(elementNamePercent[1]);
                } else {
                    currentPercentage = (minPercentage + maxPercentage) / 2;
                }
                totalPercentage += currentPercentage;
            } else {
                currentPercentage = 100 - totalPercentage;
            }
            Element element = new Element(
                    PeriodicTable.getElementName(elementNamePercent[0]),
                    elementNamePercent[0],
                    currentPercentage,
                    minPercentage,
                    maxPercentage,
                    null);
            elementsList.add(element);
        }
        return elementsList;
    }

    public void generateDataset(List<List<Element>> compositions, UserInputConfig config) {
        generateDataset(compositions, config, null);
    }

    public void generateDataset(List<List<Element>> compositions, UserInputConfig config, MaterialGrade sourceMaterial) {

        // Keeping track of all wavelength across all comps:
        Set<Double> allWavelengths = new TreeSet<>();

        // all Element symbols across all comps (can be kept if used elsewhere, or removed if only for header):
        Set<String> allElementSymbols = new TreeSet<>();

        // Store for each composition's *string ID* -> (wave -> intensity)
        Map<String, Map<Double, Double>> compWaveIntensity = new HashMap<>();
        // Store for each composition's *string ID* -> (element symbol -> percentage)
        Map<String, Map<String, Double>> compElementPcts = new HashMap<>();

        int compositionsProcessed = 0;

        PrintStream out = System.out;

        // For each composition, fetch the CSV, parse it, store data
        for (List<Element> composition : compositions) {

            // Build a string like "Cu-50;Fe-50" for cross-platform compatible filename
            String compositionId = commonUtils.buildCompositionStringForFilename(composition);

            // Sleep for 5 seconds after every 5 requests to the NIST LIBS server
            if (compositionsProcessed % 5 == 0) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            // Fetch CSV data from NIST
            String csvData;
            String compositionFileName = "composition_" + compositionId + ".csv";
            Path compositionFilePath = Paths.get(config.csvDirPath, LIBSDataGenConstants.NIST_LIBS_DATA_DIR,
                    compositionFileName);
            
            logger.info("Checking for existing LIBS data file at: " + compositionFilePath.toAbsolutePath());
            boolean compositionFileExists = Files.exists(compositionFilePath);
            
            if (config.forceFetch || !compositionFileExists) {
                logger.info("Fetching LIBS data for " + compositionId + " (forceFetch=" + config.forceFetch + ", fileExists=" + compositionFileExists + ")");
                csvData = fetchLIBSData(composition, config);
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
                waveMap = parseNistCsv(csvData, allWavelengths, config.wavelengthUnit);
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Error parsing CSV for " + compositionId, e);
                continue;
            }

            compWaveIntensity.put(compositionId, waveMap);

            // Also store element symbols + their percentages
            Map<String, Double> elemMap = new HashMap<>();
            for (Element elem : composition) {
                elemMap.put(elem.getSymbol(), elem.getPercentageComposition());
                allElementSymbols.add(elem.getSymbol());
            }
            compElementPcts.put(compositionId, elemMap);

            // Calculate progress
            CommonUtils.printProgressBar(compositionsProcessed + 1, compositions.size(), "samples completed", out);

            compositionsProcessed++;
        }

        // Print newline after progress bar completion
        CommonUtils.finishProgressBar(compositions.size(), out);

        // Write a single "master CSV" with all results
        // columns: composition, each wavelength (sorted), each element symbol (sorted).

        // Convert sets to sorted lists
        List<Double> sortedWaves = new ArrayList<>(allWavelengths);
        Collections.sort(sortedWaves); // TreeSet is already sorted, but okay to be explicit
        // Use STD_ELEMENT_LIST for header and row structure for elements
        List<String> sortedSymbols = new ArrayList<>(Arrays.asList(LIBSDataGenConstants.STD_ELEMENT_LIST));
        Collections.sort(sortedSymbols); // Ensure canonical order

        // Build header
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
            // For composition percentages (type 1), no additional class column is needed
            // as the individual element columns serve as the class labels
        } else {
            // Default behavior: add both material columns
            header.add("material_grade_name");
            header.add("material_type");
        }

        // Write out to "master.csv" inside savePath
        Path masterCsvPath = Paths.get(config.csvDirPath, "master_dataset.csv");
        // Ensure the 'header' List<String> is converted to String[] for getCsvPrinter

        String[] headerArray = header.toArray(new String[0]);
        try (CSVPrinter printer = com.medals.libsdatagenerator.util.CSVUtils.getCsvPrinter(masterCsvPath, config.appendMode, headerArray)) {
            // If appending, and the file might have already existed and had data (and thus headers),
            // CSVUtils.getCsvPrinter when appendMode=true opens without writing new headers.
            // If not appending, or if appending and file is new, headers are written by CSVUtils.

            // Each composition => one row
            for (String compId : compWaveIntensity.keySet()) {
                Map<Double, Double> waveMap = compWaveIntensity.get(compId);
                // Get the specific element map for the row
                Map<String, Double> elemMap = compElementPcts.get(compId);

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
                
                // Add class label columns based on configuration
                // If user explicitly specified a class type, only add that specific column
                // Otherwise, add both material grade name and material type columns by default
                if (config.classLabelTypeExplicitlySet) {
                    // User explicitly selected a class type
                    if (config.classLabelType != ClassLabelType.COMPOSITION_PERCENTAGE) {
                        // Add only the specific class column requested
                        String classLabel = generateClassLabel(config.classLabelType, sourceMaterial, compId);
                        row.add(classLabel);
                    }
                    // For composition percentages (type 1), no additional class column is needed
                    // as the individual element columns serve as the class labels
                } else {
                    // Default behavior: add both material columns
                    String gradeLabel = generateClassLabel(ClassLabelType.MATERIAL_GRADE_NAME, sourceMaterial, compId);
                    String typeLabel = generateClassLabel(ClassLabelType.MATERIAL_TYPE, sourceMaterial, compId);
                    row.add(gradeLabel);
                    row.add(typeLabel);
                }

                printer.printRecord(row);
            }

            logger.info("Master dataset saved to: " + masterCsvPath.toAbsolutePath()); // Enhanced log

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error writing master dataset CSV", e);
        }
    }

    /**
     * Parse in-memory CSV from NIST (with columns "Wavelength (nm)" and "Sum"),
     * 
     * @return a map: (wavelength -> intensity).
     *         Also add each encountered wavelength to 'allWavelengths'.
     */
    private Map<Double, Double> parseNistCsv(String csvData, Set<Double> allWavelengths, WavelengthUnit wavelengthUnit) throws IOException {
        Map<Double, Double> waveMap = new HashMap<>();

        // Check if CSV string is correctly parsed

        StringReader sr = new StringReader(csvData);
        Iterable<CSVRecord> records = CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .parse(sr);

        // Headers might be "Wavelength (nm)" and "Sum"—confirm with a real example.
        for (CSVRecord record : records) {
            String waveStr = record.get("Wavelength (" + wavelengthUnit.getUnitString() + ")"); // or "Wavelength"
            String sumStr = record.get("Sum"); // or "Sum"

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
            case COMPOSITION_PERCENTAGE -> "class_composition_percentage";
            case MATERIAL_GRADE_NAME -> "material_grade_name";
            case MATERIAL_TYPE -> "material_type";
        };
    }

    /**
     * Generates the class label value for a given composition
     */
    private String generateClassLabel(ClassLabelType classLabelType, MaterialGrade sourceMaterial, String compositionId) {
        return switch (classLabelType) {
            case COMPOSITION_PERCENTAGE -> compositionId; // Use composition ID for multi-output regression
            case MATERIAL_GRADE_NAME -> {
                if (sourceMaterial != null && sourceMaterial.getMaterialName() != null && !sourceMaterial.getMaterialName().isEmpty()) {
                    yield sourceMaterial.getMaterialName();
                } else {
                    yield "Unknown Grade"; // Fallback for missing material names
                }
            }
            case MATERIAL_TYPE -> {
                if (sourceMaterial != null && sourceMaterial.getSeriesKey() != null) {
                    yield processSeriesKeyToMaterialType(sourceMaterial.getSeriesKey());
                } else {
                    yield "Unknown Type"; // Fallback for missing series information
                }
            }
        };
    }

    /**
     * Converts series key to readable material type by replacing dots and underscores with spaces
     */
    private String processSeriesKeyToMaterialType(String seriesKey) {
        if (seriesKey == null || seriesKey.equals(LIBSDataGenConstants.DIRECT_ENTRY)) {
            return "Direct Entry";
        }
        
        // Convert dots and underscores to spaces
        return seriesKey.replace('.', ' ').replace('_', ' ');
    }

}
