package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
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
     * Testing if the NIST LIBS website is reachable
     * 
     * @return boolean - True if website live, false otherwise
     */
    public boolean isNISTLIBSReachable() {
        SeleniumUtils seleniumUtils = SeleniumUtils.getInstance();

        try {
            if (seleniumUtils.connectToWebsite(LIBSDataGenConstants.NIST_LIBS_FORM_URL, null)) {
                logger.info(seleniumUtils.getDriver().getTitle() + " is reachable.");
                return true;
            } else {
                logger.log(Level.SEVERE, "NIST LIBS website not reachable.");
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred while trying to connect to NIST LIBS website.", e);
            return false;
        } finally {
            seleniumUtils.quitSelenium();
        }
    }

    // Fetching data from NIST LIBS database
    public String fetchLIBSData(ArrayList<Element> elements, String minWavelength, String maxWavelength,
                                String savePath) {
        SeleniumUtils seleniumUtils = SeleniumUtils.getInstance();

        try {
            HashMap<String, String> queryParams = processLIBSQueryParams(elements, minWavelength, maxWavelength);
            seleniumUtils.connectToWebsite(LIBSDataGenConstants.NIST_LIBS_QUERY_URL_BASE, queryParams);

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
            Path dataPath = Paths.get(savePath, "NIST LIBS");
            if (!Files.exists(dataPath)) {
                Files.createDirectories(dataPath);
            }

            // Save to a file with a unique name
            // Build the composition string ID using the same utility as in generateDataset
            String compositionId = commonUtils.buildCompositionString(elements); // 'elements' is the parameter to fetchLIBSData
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

    public HashMap<String, String> processLIBSQueryParams(ArrayList<Element> elements, String minWavelength,
            String maxWavelength) {
        // Processing the information for each element and adding to the query params
        // hashmap
        // Sample query params:
        // composition=C:0.26;Mn:0.65;Si:0.22;Fe:98.87&mytext[]=C&myperc[]=0.26&spectra=C0-2,Mn0-2,Si0-2,Fe0-2&mytext[]=Mn&myperc[]=0.65&mytext[]=Si&myperc[]=0.22&mytext[]=Fe&myperc[]=98.87&low_w=200&limits_type=0&upp_w=600&show_av=2&unit=1&resolution=1000&temp=1&eden=1e17&maxcharge=2&min_rel_int=0.01&int_scale=1&libs=1
        // https://physics.nist.gov/cgi-bin/ASD/lines1.pl?composition=C%3A0.26%3BMn%3A0.65%3BSi%3A0.22%3BFe%3A98.87&mytext%5B%5D=C&myperc%5B%5D=0.26&spectra=C0-2%2CMn0-2%2CSi0-2%2CFe0-2&mytext%5B%5D=Mn&myperc%5B%5D=0.65&mytext%5B%5D=Si&myperc%5B%5D=0.22&mytext%5B%5D=Fe&myperc%5B%5D=98.87&low_w=200&limits_type=0&upp_w=600&show_av=2&unit=1&resolution=1000&temp=1&eden=1e17&maxcharge=2&min_rel_int=0.01&int_scale=1&libs=1
        HashMap<String, String> queryParams = new HashMap<>();

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

        // Adding composition
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_COMPOSITION, composition.substring(1));

        // Adding spectra
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_SPECTRA, spectra.substring(1));

        // Adding min wavelength
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_LOW_W, minWavelength);

        // Adding max wavelength
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_UPP_W, maxWavelength);

        // The rest of the query params are kept constant for now. Can update to take
        // custom values as needed in the future.
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_LIMITS_TYPE, "0");
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_SHOW_AV, "2");
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_UNIT, "1");
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_RESOLUTION, "1000");
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_TEMP, "1");
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_EDEN, "1e17");
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_MAXCHARGE, "2");
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_MIN_REL_INT, "0.01");
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_INT_SCALE, "1");
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_LIBS, "1");

        return queryParams;
    }

    public ArrayList<Element> generateElementsList(String[] composition) throws IOException {
        ArrayList<Element> elementsList = new ArrayList<>();
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

    public ArrayList<ArrayList<Element>> generateCompositionalVariations(ArrayList<Element> originalComposition,
            double varyBy, double maxDelta, int variationMode,
            int samples, String overviewGuid) {
        ArrayList<ArrayList<Element>> compositions = new ArrayList<>();
        if (originalComposition == null || originalComposition.isEmpty()) {
            logger.warning("Original composition is null or empty. Cannot generate variations.");
            return compositions; // Return empty list, or perhaps add the (empty) originalComposition if that's desired.
        }
        compositions.add(originalComposition); // Adding the original composition

        boolean allElementsAreFixed = true;
        // No need to check originalComposition.isEmpty() again here as it's handled above.
        for (Element el : originalComposition) {
            Double minComp = el.getPercentageCompositionMin();
            Double maxComp = el.getPercentageCompositionMax();
            if (!(minComp != null && maxComp != null && minComp.equals(maxComp))) {
                allElementsAreFixed = false;
                break;
            }
        }

        ArrayList<Element> effectiveComposition = originalComposition;
        if (allElementsAreFixed) {
            // originalComposition is not empty here due to the check at the beginning.
            logger.info("All elements in the input composition are fixed. Applying fallback variation logic, ignoring X:X constraints for sampling.");
            effectiveComposition = new ArrayList<>();
            for (Element el : originalComposition) {
                effectiveComposition.add(new Element(
                    el.getName(),
                    el.getSymbol(),
                    el.getPercentageComposition(),
                    null, // Effectively remove min/max for variation generation
                    null,
                    el.getAverageComposition()
                ));
            }
        }

        // Generate all combinations by Uniform distribution
        // System.out.println("\nGenerating different combinations for the input composition (refer log for list)...");
        logger.info("\nGenerating different combinations for the input composition (refer log for list)...");


        if (variationMode == LIBSDataGenConstants.STAT_VAR_MODE_GAUSSIAN_DIST) {
            CompositionalVariations.getInstance().gaussianSampling(
                effectiveComposition,
                maxDelta,
                samples,
                compositions);

        } else if (variationMode == LIBSDataGenConstants.STAT_VAR_MODE_DIRICHLET_DIST) {
            CompositionalVariations.getInstance().dirichletSampling(
                effectiveComposition,
                overviewGuid,
                samples,
                compositions
            );

        } else { // For uniform distribution
            // Start backtracking with an empty "current combo" and a running sum of 0
            CompositionalVariations.getInstance().getUniformDistribution(
                0,
                effectiveComposition,
                varyBy,
                maxDelta, // 'limit'
                0.0,
                new ArrayList<Element>(),
                compositions);
        }

        return compositions;
    }

    public void generateDataset(ArrayList<ArrayList<Element>> compositions, String minWavelength, String maxWavelength,
            String savePath, boolean appendMode, boolean forceFetch) {

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

        int progressBarWidth = 50; // Width of the progress bar

        // For each composition, fetch the CSV, parse it, store data
        for (ArrayList<Element> composition : compositions) {

            // Build a string like "Cu:50;Fe:50" for identifying this composition
            String compositionId = commonUtils.buildCompositionString(composition);

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
            Path compositionFilePath = Paths.get(savePath, LIBSDataGenConstants.NIST_LIBS_DATA_DIR,
                    compositionFileName);
            logger.info("Checking for existing LIBS data file at: " + compositionFilePath.toAbsolutePath());
            boolean compositionFileExists = Files.exists(compositionFilePath);
            if (forceFetch || !compositionFileExists) {
                logger.info("Fetching LIBS data for " + compositionId + " (forceFetch=" + forceFetch + ", fileExists=" + compositionFileExists + ")");
                csvData = fetchLIBSData(composition, minWavelength, maxWavelength, savePath);
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
            int progress = (compositionsProcessed + 1) * progressBarWidth / compositions.size();
            String bar = "=".repeat(progress) + ">" + " ".repeat(progressBarWidth - progress);
            out.printf("\r[%s] %d/%d samples completed", bar, compositionsProcessed + 1, compositions.size());

            compositionsProcessed++;
        }

        // Write a single "master CSV" of all results
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

        // Write out to "master.csv" inside savePath
        Path masterCsvPath = Paths.get(savePath, "master_dataset.csv");
        // Ensure the 'header' List<String> is converted to String[] for getCsvPrinter

        String[] headerArray = header.toArray(new String[0]);
        try (CSVPrinter printer = com.medals.libsdatagenerator.util.CSVUtils.getCsvPrinter(masterCsvPath, appendMode, headerArray)) {
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
    private Map<Double, Double> parseNistCsv(String csvData, Set<Double> allWavelengths) throws IOException {
        Map<Double, Double> waveMap = new HashMap<>();

        // Check if CSV string is correctly parsed

        StringReader sr = new StringReader(csvData);
        Iterable<CSVRecord> records = CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .parse(sr);

        // Headers might be "Wavelength (nm)" and "Sum"—confirm with a real example.
        for (CSVRecord record : records) {
            String waveStr = record.get("Wavelength (nm)"); // or "Wavelength"
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

}
