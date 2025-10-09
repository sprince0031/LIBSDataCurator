package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.SeriesStatistics;
import com.medals.libsdatagenerator.util.CommonUtils;
import com.medals.libsdatagenerator.util.SeleniumUtils;
import org.json.JSONArray;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fetches data from matweb.com
 * @author Siddharth Prince | 20/03/25 06:41
 */
public class MatwebDataService {
    public static Logger logger = Logger.getLogger(MatwebDataService.class.getName());
    private static MatwebDataService instance = null;
    private final SeleniumUtils seleniumUtils = SeleniumUtils.getInstance();
    private final SeriesStatisticsExtractor statisticsExtractor = new SeriesStatisticsExtractor();
    private String datasheetName;
    private String[] datasheetAttributes;

    public static MatwebDataService getInstance() {
        if (instance == null) {
            instance = new MatwebDataService();
        }
        return instance;
    }

    private String matwebSanityChecker(String datasheetUrl) throws RuntimeException {

        // Check if matweb.com is reachable
        if (CommonUtils.getInstance().isWebsiteReachable(LIBSDataGenConstants.MATWEB_HOME_URL)) {
            return datasheetUrl;
        }
        logger.warning("Matweb.com is not online. Attempting to find a snapshot on archive.org...");
        return CommonUtils.getInstance().getArchivedWebpageUrl(datasheetUrl);
    }

    public String[] getMaterialComposition(String guid) {
        try {
            HashMap<String, String> queryParams = new HashMap<>();
            queryParams.put(LIBSDataGenConstants.MATWEB_DATASHEET_PARAM_GUID, guid);

            String datasheetUrl = CommonUtils.getInstance()
                    .getUrl(LIBSDataGenConstants.MATWEB_DATASHEET_URL_BASE,  queryParams);

            if (seleniumUtils.connectToWebsite(matwebSanityChecker(datasheetUrl))) {
                List<List<String>> compositionTableData = fetchCompositionTableData();
                if (!compositionTableData.isEmpty()) {
                    datasheetName = extractDatasheetName(false);
                    return parseCompositionData(
                            compositionTableData.get(0), // Element names
                            compositionTableData.get(1), // Metric values (% or range)
                            compositionTableData.get(3) // Comments for average %
                    );
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unable to fetch data from matweb.com", e);
        } finally {
            seleniumUtils.quitSelenium();
        }
        return new String[] { String.valueOf(HttpURLConnection.HTTP_NOT_FOUND) };
    }

    /**
     * Gets series statistics from overview datasheet for Dirichlet sampling
     *
     * @param overviewGuid GUID of the overview datasheet (e.g., AISI 4000 series)
     * @return SeriesStatistics object containing statistical information for Dirichlet parameter estimation
     */
    public SeriesStatistics getSeriesStatistics(String overviewGuid) {
        try {
            HashMap<String, String> queryParams = new HashMap<>();
            queryParams.put(LIBSDataGenConstants.MATWEB_DATASHEET_PARAM_GUID, overviewGuid);

            logger.info("Fetching series statistics from overview sheet: " + overviewGuid);

            String datasheetUrl = CommonUtils.getInstance()
                    .getUrl(LIBSDataGenConstants.MATWEB_DATASHEET_URL_BASE,  queryParams);

            if (seleniumUtils.connectToWebsite(matwebSanityChecker(datasheetUrl))) {
                // Check if this is an overview sheet
                if (!isOverviewSheet()) {
                    logger.warning("The provided GUID does not appear to be an overview sheet");
                    return null;
                }

                String seriesName = extractDatasheetName(true);
                List<List<String>> compositionTableData = fetchCompositionTableData();

                if (!compositionTableData.isEmpty()) {
                    SeriesStatistics statistics = statisticsExtractor.extractStatistics(
                            compositionTableData.get(0), // Element names
                            compositionTableData.get(1), // Metric values (ranges)
                            compositionTableData.get(3), // Comments with averages and grade counts
                            seriesName,
                            overviewGuid
                    );

                    if (statisticsExtractor.validateStatistics(statistics)) {
                        logger.info("Successfully extracted series statistics: " + statistics);
                        return statistics;
                    } else {
                        logger.warning("Extracted statistics failed validation");
                        return null;
                    }
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unable to fetch series statistics from matweb.com", e);
        } finally {
            seleniumUtils.quitSelenium();
        }

        return null;
    }

    /**
     * Checks if the current page is an overview sheet by looking for characteristic text
     */
    private boolean isOverviewSheet() {
        try {
            // Look for "Overview of materials for" in the page title or content
            String pageTitle = seleniumUtils.getDriver().getTitle();
            if (pageTitle.toLowerCase().contains("overview of materials")) {
                return true;
            }

            // Also check for the characteristic text in the material notes
            List<WebElement> elements = seleniumUtils.getDriver().findElements(
                    By.xpath("//*[contains(text(), 'This property data is a summary of similar materials')]"));

            return !elements.isEmpty();

        } catch (Exception e) {
            logger.warning("Error checking if page is overview sheet: " + e.getMessage());
            return false;
        }
    }

    /**
     * Extracts the series name from the overview sheet title
     */
    private String extractDatasheetName(boolean isOverviewSheet) {
        try {
            String pageTitle = seleniumUtils.getDriver().getTitle();
            String overviewPageTitlePrefix = LIBSDataGenConstants.MATWEB_OVERVIEW_DATASHEET_PAGE_TITLE_PREFIX;

            if (pageTitle == null) {
                return "Unknown name";
            }

            // Extract series name from title like "Overview of materials for AISI 4000 Series Steel"
            if (isOverviewSheet && pageTitle.contains(overviewPageTitlePrefix)) {
                pageTitle = pageTitle.substring(overviewPageTitlePrefix.length()).trim();
                datasheetAttributes = null;

            } else {
                String[] titleParts = pageTitle.split(", ");
                pageTitle = titleParts[0].trim();
                datasheetAttributes = Arrays.copyOfRange(titleParts, 1, titleParts.length);
            }

            logger.info("Page title: " + pageTitle);
            return pageTitle;

        } catch (Exception e) {
            logger.warning("Error extracting datasheet name: " + e.getMessage());
            return "Unknown name";
        }
    }

    /**
     * Fetches the raw string data from the composition table on the Matweb page.
     *
     * @return A List of Lists, where each inner list represents a column (Element Name, Metric, English, Comments).
     * Returns an empty list if the table is not found or parsing fails.
     */
    private List<List<String>> fetchCompositionTableData() {
        List<List<String>> compositionTableData = new ArrayList<>();

        // Initialize lists for each column
        List<String> elementNames = new ArrayList<>();
        List<String> metricValues = new ArrayList<>();
        List<String> englishValues = new ArrayList<>(); // Keep for potential future use
        List<String> commentsValues = new ArrayList<>();

        // Find the main table by class name
        List<WebElement> tables = seleniumUtils.getDriver().findElements(By.cssSelector("table.tabledataformat"));
        WebElement targetTable = null;

        // Iterate through the tables and select the one that contains the desired header text
        for (WebElement table : tables) {
            if (table.getText().contains("Component Elements Properties")) {
                targetTable = table;
                break;
            }
        }

        // If no table contains the header, log and return empty data.
        if (targetTable == null) {
            logger.severe("No table containing 'Component Elements Properties' was found.");
            return compositionTableData;
        }

        // Locate the row with the heading "Component Elements Properties"
        // This XPath finds a <tr> that has a <th> containing that text
        WebElement componentHeadingRow = targetTable.findElement(
                By.xpath(".//tr[th[contains(normalize-space(.), 'Component Elements Properties')]]"));

        // From that row, get all subsequent sibling <tr> elements
        // 'following-sibling::tr' gets the rows that come after the heading row
        List<WebElement> subsequentRows = componentHeadingRow.findElements(By.xpath("following-sibling::tr"));

        for (WebElement row : subsequentRows) {
            // Check if this row is another heading row (i.e., it has <th> elements).
            List<WebElement> headingCells = row.findElements(By.tagName("th"));
            if (!headingCells.isEmpty()) {
                // Reached a new section heading, so stop parsing data rows.
                break;
            }

            // Otherwise, this row should contain <td> cells with composition data
            List<WebElement> dataCells = row.findElements(By.tagName("td"));
            if (dataCells.isEmpty() || dataCells.size() < 4) {
                // If no <td> found or not enough cells, might be an empty or separator row—skip it
                continue;
            }

            // Add each cell to its respective column list
            elementNames.add(dataCells.get(0).getText().trim());
            metricValues.add(dataCells.get(1).getText().trim());
            englishValues.add(dataCells.get(2).getText().trim());
            commentsValues.add(dataCells.get(3).getText().trim());
        }

        // Add all column lists to the result
        if (!elementNames.isEmpty()) {
            compositionTableData.add(elementNames);
            compositionTableData.add(metricValues);
            compositionTableData.add(englishValues);
            compositionTableData.add(commentsValues);
        }

        return compositionTableData;
    }

    private String[] parseCompositionData(List<String> elementList, List<String> compositionList,
                                          List<String> comments) {
        String[] parsedElementString = new String[elementList.size()];
        boolean hasRemainderElement = false;
        
        for (int i = 0; i < elementList.size(); i++) {
            String elementString = elementList.get(i);
            logger.info("Scraped matweb table output: " + elementString);

            // Extract element symbol - handle both formats "Element, Symbol" and "Element Symbol"
            String element;
            if (elementString.contains(",")) {
                element = elementString.split(",")[1].trim(); // Get the element symbol after comma
            } else {
                // If no comma, try to extract the element symbol another way
                // Most element symbols are 1-2 characters at the end of the string
                String[] parts = elementString.split("\\s+");
                element = parts[parts.length - 1]; // Take the last part as the element symbol
            }

            // Parse and extract data from weight percentage possibilities
            String composition = compositionList.get(i);
            logger.info("Composition value: " + composition);

            String parsedCompositionValue;
            // Remove trailing '%' and any whitespace first
            if (composition.endsWith("%")) {
                composition = composition.substring(0, composition.length() - 1).trim();
            }

            if (composition.contains(" - ")) { // Percentage range "A - B"
                String[] compositionRange = composition.split(" - ");
                parsedCompositionValue = compositionRange[0].trim() + ":" + compositionRange[1].trim();
            } else if (composition.startsWith("<=")) { // Max percentage "<=X"
                String val = composition.substring(2).trim();
                parsedCompositionValue = "0:" + val; // Assumes min is 0
            } else if (composition.startsWith(">=")) { // Min percentage ">=X"
                String val = composition.substring(2).trim();
                parsedCompositionValue = val + ":100"; // Assumes max is 100
            } else if (i < comments.size() && (
                    comments.get(i).toLowerCase().contains("remainder") ||
                            comments.get(i).toLowerCase().contains("balance"))) { // Remainder, check before single value
                parsedCompositionValue = "#";
                hasRemainderElement = true;
            } else { // Single value "X" (e.g., "0.5", "12.3")
                // Treat as a fixed point: min = X, max = X
                parsedCompositionValue = composition.trim() + ":" + composition.trim();
            }
            parsedElementString[i] = element + "-" + parsedCompositionValue;
        }

        // If no remainder element was found, automatically mark the element with the highest percentage
        if (!hasRemainderElement) {
            int maxIndex = -1;
            double maxPercentage = -1.0;
            
            for (int i = 0; i < parsedElementString.length; i++) {
                String[] parts = parsedElementString[i].split("-");
                if (parts.length >= 2) {
                    String percentageStr = parts[1];
                    double percentage = 0.0;
                    
                    // Calculate average percentage from range or single value
                    if (percentageStr.contains(":")) {
                        String[] range = percentageStr.split(":");
                        try {
                            double min = Double.parseDouble(range[0]);
                            double max = Double.parseDouble(range[1]);
                            percentage = (min + max) / 2.0;
                        } catch (NumberFormatException e) {
                            // Skip if parsing fails
                            continue;
                        }
                    } else {
                        try {
                            percentage = Double.parseDouble(percentageStr);
                        } catch (NumberFormatException e) {
                            // Skip if parsing fails
                            continue;
                        }
                    }
                    
                    if (percentage > maxPercentage) {
                        maxPercentage = percentage;
                        maxIndex = i;
                    }
                }
            }
            
            // Mark the element with highest percentage as remainder
            if (maxIndex >= 0) {
                String[] parts = parsedElementString[maxIndex].split("-");
                parsedElementString[maxIndex] = parts[0] + "-#";
                logger.info("Automatically marked element " + parts[0] + " as remainder (highest percentage: " + maxPercentage + "%)");
            }
        }

        return parsedElementString;
    }

    /**
     * Utility method to check for validity of matweb service output
     */
    public boolean validateMatwebServiceOutput(String[] compositionArray, String guid) {
        if (compositionArray == null || compositionArray.length == 0) {
            logger.warning("Failed to fetch composition (null or empty array) for material GUID: " + guid + ". Skipping this material.");
            return false;
        }
        if (compositionArray.length == 1) {
            if (compositionArray[0].equals(String.valueOf(HttpURLConnection.HTTP_NOT_FOUND))) {
                logger.warning("Material GUID: " + guid + " not found (404) on MatWeb. Skipping this material.");
                return false;
            }
            if (compositionArray[0].equals(String.valueOf(HttpURLConnection.HTTP_INTERNAL_ERROR))) {
                logger.warning("MatWeb internal server error (500) for material GUID: " + guid + ". Skipping this material.");
                return false;
            }
        }
        return true;
    }

    public String getDatasheetName() {
        return datasheetName;
    }

    public String[] getDatasheetAttributes() {
        return datasheetAttributes;
    }

}
