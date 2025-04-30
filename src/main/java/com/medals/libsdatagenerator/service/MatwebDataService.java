package com.medals.libsdatagenerator.service;

/**
 * @author Siddharth Prince | 20/03/25 06:41
 */

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.util.SeleniumUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fetches data from matweb.com
 */
public class MatwebDataService {
    public static Logger logger = Logger.getLogger(MatwebDataService.class.getName());
    private final SeleniumUtils seleniumUtils = SeleniumUtils.getInstance();

    public String[] getMaterialComposition(String guid) {
        try {
            HashMap<String, String> queryParams = new HashMap<>();
            queryParams.put(LIBSDataGenConstants.MATWEB_DATASHEET_PARAM_GUID, guid);
            if (seleniumUtils.connectToWebsite(LIBSDataGenConstants.MATWEB_DATASHEET_URL_BASE, queryParams)) {
                List<List<String>> compositionTableData = fetchCompositionTableData();
                if (!compositionTableData.isEmpty()) {
                    return parseCompositionData(compositionTableData.get(0), compositionTableData.get(1),
                            compositionTableData.get(3));
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unable to fetch data from matweb.com", e);
        } finally {
            seleniumUtils.quitSelenium();
        }
        return new String[] { String.valueOf(HttpURLConnection.HTTP_NOT_FOUND) };
    }

    private List<List<String>> fetchCompositionTableData() {
        List<List<String>> compositionTableData = new ArrayList<>();

        // Initialize lists for each column
        List<String> elementNames = new ArrayList<>();
        List<String> metricValues = new ArrayList<>();
        List<String> englishValues = new ArrayList<>();
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

            // Remove trailing '%' and any whitespace
            if (composition.endsWith("%")) {
                composition = composition.substring(0, composition.length() - 1).trim();
            }

            // Handle composition formats
            if (composition.contains(" - ")) { // Percentage range
                String[] compositionRange = composition.split(" - ");
                composition = compositionRange[0] + ":" + compositionRange[1];
            } else if (composition.contains("<=")) { // Max percentage
                composition = composition.substring(2).trim();
            } else if (composition.contains(">=") ||
                    (i < comments.size() && comments.get(i).contains("remainder"))) { // Min or remainder
                composition = "#";
            }

            parsedElementString[i] = element + "-" + composition;
        }

        return parsedElementString;
    }
}
