package com.medals.libsdatagenerator.service;


import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.util.CommonUtils;
import com.medals.libsdatagenerator.util.SeleniumUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service class for all NIST LIBS website related functionality.
 * @author Siddharth Prince | 17/12/24 13:21
 */

public class LIBSDataService {
    private static Logger logger = Logger.getLogger(LIBSDataService.class.getName());

    public static LIBSDataService instance = null;
    private static Map<String, Map<Double, Double>> compositionalDataset = new HashMap<>();
    private CommonUtils commonUtils = new CommonUtils();

    public static LIBSDataService getInstance() {
        if (instance == null) {
            instance = new LIBSDataService();
        }
        return instance;
    }

    /**
     * Testing if the NIST LIBS website is reachable
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


    public String fetchLIBSData(ArrayList<Element> elements, String minWavelength, String maxWavelength, String savePath) {
        SeleniumUtils seleniumUtils = SeleniumUtils.getInstance();

        try {
            HashMap<String, String> queryParams = processLIBSQueryParams(elements, minWavelength, maxWavelength);
            seleniumUtils.connectToWebsite(LIBSDataGenConstants.NIST_LIBS_QUERY_URL_BASE, queryParams);

            WebElement csvButton = seleniumUtils.getDriver().findElement(By.name("ViewDataCSV"));
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
            String filename = "composition_" + queryParams.get(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_COMPOSITION) + ".csv";
            Path csvPath = Paths.get(String.valueOf(dataPath), filename);
            Files.write(csvPath, csvData.getBytes());
//            System.out.println("Saved: " + filename);
            logger.info("Saved: " + csvPath);

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

    public HashMap<String, String> processLIBSQueryParams(ArrayList<Element> elements, String minWavelength, String maxWavelength) {
        // Processing the information for each element and adding to the query params hashmap
        // Sample query params:
        // composition=C:0.26;Mn:0.65;Si:0.22;Fe:98.87&mytext[]=C&myperc[]=0.26&spectra=C0-2,Mn0-2,Si0-2,Fe0-2&mytext[]=Mn&myperc[]=0.65&mytext[]=Si&myperc[]=0.22&mytext[]=Fe&myperc[]=98.87&low_w=200&limits_type=0&upp_w=600&show_av=2&unit=1&resolution=1000&temp=1&eden=1e17&maxcharge=2&min_rel_int=0.01&int_scale=1&libs=1
        // https://physics.nist.gov/cgi-bin/ASD/lines1.pl?composition=C%3A0.26%3BMn%3A0.65%3BSi%3A0.22%3BFe%3A98.87&mytext%5B%5D=C&myperc%5B%5D=0.26&spectra=C0-2%2CMn0-2%2CSi0-2%2CFe0-2&mytext%5B%5D=Mn&myperc%5B%5D=0.65&mytext%5B%5D=Si&myperc%5B%5D=0.22&mytext%5B%5D=Fe&myperc%5B%5D=98.87&low_w=200&limits_type=0&upp_w=600&show_av=2&unit=1&resolution=1000&temp=1&eden=1e17&maxcharge=2&min_rel_int=0.01&int_scale=1&libs=1
        HashMap<String, String> queryParams = new HashMap<>();

        // Creating composition string
        String composition = "";
        String spectra = "";
        for (Element element : elements) {
            composition = String.join(";", composition, element.getQueryRepresentation());
            spectra = String.join(",", spectra, element.getSymbol() + "0-2");
        }

        // Adding composition
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_COMPOSITION, composition.substring(1));

        // Adding spectra
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_SPECTRA, spectra.substring(1));

        // Adding min wavelength
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_LOW_W, minWavelength);

        // Adding max wavelength
        queryParams.put(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_UPP_W, maxWavelength);

        // The rest of the query params are kept constant for now. Can update to take custom values as needed in the future.
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
        for (String elementString: composition) {
            // elementNamePercent[0] -> Symbol, elementNamePercent[1] -> Percentage of composition
            String[] elementNamePercent = elementString.split("-");
            String elementPropsPath = CommonUtils.CONF_PATH + File.separator + "elements.properties";
            Properties elementProps = commonUtils.readProperties(elementPropsPath);

            // Check if the current input element exists in the periodic table (element list stored in elements.properties)
            if (!elementProps.containsKey(elementNamePercent[0])) {
                logger.log(Level.SEVERE, "Invalid input. " + elementNamePercent[0] + " does not exist.");
                throw new IOException("Invalid element " + elementNamePercent[0] + " given as input");
            }

            double currentPercentage;
            // If the element percentage value is "*", consider as the remaining percentage composition
            if (!elementNamePercent[1].equals("#")) {
                currentPercentage = Double.parseDouble(elementNamePercent[1]);
                totalPercentage += currentPercentage;
            } else {
                currentPercentage = 100 - totalPercentage;
            }
            Element element = new Element(elementProps.getProperty(elementNamePercent[0]), elementNamePercent[0], currentPercentage);
            elementsList.add(element);
        }
        return elementsList;
    }

    public ArrayList<ArrayList<Element>> generateCompositionalVariations(ArrayList<Element> originalComposition, double varyBy, double limit) {
        ArrayList<ArrayList<Element>> compositions = new ArrayList<>();
        compositions.add(originalComposition); // Adding the original composition

        // Generate all combinations
        // Start backtracking with an empty "current combo" and a running sum of 0
        backtrackVariations(0, originalComposition, varyBy, limit, 0.0,
                new ArrayList<Element>(), compositions);

        return compositions;
    }

    private void backtrackVariations(
            int index,
            ArrayList<Element> original,
            double varyBy,
            double limit,
            double currentSum,
            ArrayList<Element> currentCombo,
            ArrayList<ArrayList<Element>> results) {

        // If this is the last element, we must "fix" it so total = 100%
        if (index == original.size() - 1) {
            double originalVal = original.get(index).getPercentageComposition();
            // Allowed range for this element:
            double low = Math.max(0, originalVal - limit);
            double high = Math.min(100, originalVal + limit);

            // The only possible value (if valid) to keep sum at 100
            double lastVal = 100 - currentSum;

            // Check if lastVal is within [low, high]
            if (lastVal >= low && lastVal <= high) {
                // Accept this composition
                currentCombo.add(new Element(
                        original.get(index).getName(),
                        original.get(index).getSymbol(),
                        lastVal
                ));
                ArrayList<Element> newComposition = commonUtils.deepCopy(currentCombo);
                results.add(newComposition);
                logger.info("New composition added: " + commonUtils.buildCompositionString(newComposition));
                currentCombo.remove(currentCombo.size() - 1);
            }
            // If lastVal not in range, no valid composition from this path
            return;
        }

        // Not the last element: try all valid values from (originalVal-limit) to (originalVal+limit)
        double originalVal = original.get(index).getPercentageComposition();
        double low = Math.max(0, originalVal - limit);
        double high = Math.min(100, originalVal + limit);

        for (double val = low; val <= high; val += varyBy) {
            // Only proceed if we won't exceed 100% so far
            if (currentSum + val <= 100) {
                currentCombo.add(new Element(
                        original.get(index).getName(),
                        original.get(index).getSymbol(),
                        val
                ));
                // Recurse for the next element
                backtrackVariations(index + 1, original, varyBy, limit,
                        currentSum + val, currentCombo, results);

                // Backtrack (undo)
                currentCombo.remove(currentCombo.size() - 1);
            }
        }
    }

    public void generateCompositionalVariationsDataset(
            ArrayList<ArrayList<Element>> compositions,
            String minWavelength,
            String maxWavelength,
            String savePath) {

        // 1) We'll keep track of all WAVELENGTHS across all comps:
        Set<Double> allWavelengths = new TreeSet<>();

        // 2) We'll keep track of all ELEMENT SYMBOLS across all comps:
        Set<String> allElementSymbols = new TreeSet<>();

        // 3) We'll store for each composition's *string ID* -> (wave -> intensity)
        Map<String, Map<Double, Double>> compWaveIntensity = new HashMap<>();
        // 4) We'll store for each composition's *string ID* -> (element symbol -> percentage)
        Map<String, Map<String, Double>> compElementPcts = new HashMap<>();

        int compositionsProcessed = 0;

        // 5) For each composition, fetch the CSV, parse it, store data
        for (ArrayList<Element> composition : compositions) {

            // Build a string like "Cu:50;Fe:50" for identifying this composition
            String compositionId = commonUtils.buildCompositionString(composition);

            if (compositionsProcessed % 5 == 0) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            // Fetch CSV data from NIST
            String csvData = fetchLIBSData(composition, minWavelength, maxWavelength, savePath);

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

            compositionsProcessed++;
        }

        // 6) Now, let's write a single "master CSV" of all results
        //    We'll build columns: composition, then each wavelength (sorted),
        //    then each element symbol (sorted).

        // Convert sets to sorted lists
        List<Double> sortedWaves = new ArrayList<>(allWavelengths);
        Collections.sort(sortedWaves);  // TreeSet is already sorted, but okay to be explicit
        List<String> sortedSymbols = new ArrayList<>(allElementSymbols);
        Collections.sort(sortedSymbols);

        // Build header
        List<String> header = new ArrayList<>();
        header.add("composition");
        // Add wave columns
        for (Double w : sortedWaves) {
            header.add(String.valueOf(w));
        }
        // Add element columns
        for (String sym : sortedSymbols) {
            header.add(sym);
        }

        // Write out to "master.csv" inside savePath
        Path masterCsvPath = Paths.get(savePath, "master_dataset.csv");
        try (CSVPrinter printer = new CSVPrinter(
                Files.newBufferedWriter(masterCsvPath),
                CSVFormat.DEFAULT.withHeader(header.toArray(new String[0])))
        ) {
            // Each composition => one row
            for (String compId : compWaveIntensity.keySet()) {
                Map<Double, Double> waveMap = compWaveIntensity.get(compId);
                Map<String, Double> elemMap = compElementPcts.get(compId);

                List<String> row = new ArrayList<>();
                row.add(compId);

                // For each wave, add intensity (or 0.0 if missing)
                for (Double w : sortedWaves) {
                    Double intensity = waveMap.getOrDefault(w, 0.0);
                    row.add(String.valueOf(intensity));
                }

                // For each element symbol, add the percentage (or 0.0 if missing)
                for (String sym : sortedSymbols) {
                    Double pct = elemMap.getOrDefault(sym, 0.0);
                    row.add(String.valueOf(pct));
                }

                printer.printRecord(row);
            }

            logger.info("Master dataset saved to: " + masterCsvPath);

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error writing master dataset CSV", e);
        }
    }


    /**
     * Parse in-memory CSV from NIST (with columns "Wavelength (nm)" and "Sum"),
     * @return a map: (wavelength -> intensity).
     * Also add each encountered wavelength to 'allWavelengths'.
     */
    private Map<Double, Double> parseNistCsv(String csvData, Set<Double> allWavelengths) throws IOException {
        Map<Double, Double> waveMap = new HashMap<>();

        StringReader sr = new StringReader(csvData);
        Iterable<CSVRecord> records = CSVFormat.DEFAULT
                .withFirstRecordAsHeader()
                .parse(sr);

        // Headers might be "Wavelength (nm)" and "Sum"—confirm with a real example.
        for (CSVRecord record : records) {
            String waveStr = record.get("Wavelength (nm)");  // or "Wavelength"
            String sumStr  = record.get("Sum");              // or "Sum"

            if (waveStr != null && sumStr != null) {
                double wavelength = Double.parseDouble(waveStr);
                double intensity  = Double.parseDouble(sumStr);

                waveMap.put(wavelength, intensity);
                allWavelengths.add(wavelength);
            }
        }
        return waveMap;
    }

}
