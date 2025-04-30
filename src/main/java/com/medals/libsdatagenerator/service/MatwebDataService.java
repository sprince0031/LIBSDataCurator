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
                    return parseCompositionData(compositionTableData.get(0), compositionTableData.get(1), compositionTableData.get(3));
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unable to fetch data from matweb.com", e);
        } finally {
            seleniumUtils.quitSelenium();
        }
        return new String[] {String.valueOf(HttpURLConnection.HTTP_NOT_FOUND)};
    }

    private List<List<String>> fetchCompositionTableData() {
        List<List<String>> compositionTableData = new ArrayList<>();

        List<WebElement> tables = seleniumUtils.getDriver().findElements(By.cssSelector("table.tabledataformat")); // Find the main table by class name
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
            System.err.println("No table containing 'Component Elements Properties' was found.");
            return compositionTableData;
        }

        // Locate the row with the heading "Component Elements Properties"
        // This XPath finds a <tr> that has a <th> containing that text
        WebElement componentHeadingRow = targetTable.findElement(
                By.xpath(".//tr[th[contains(normalize-space(.), 'Component Elements Properties')]]")
        );

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
            if (dataCells.isEmpty()) {
                // If no <td> found, might be an empty or separator row—skip it
                continue;
            }

            // Gather the text of each cell into a list
            List<String> rowValues = new ArrayList<>();
            for (WebElement td : dataCells) {
                rowValues.add(td.getText().trim());
            }

            // Add to resultS
            compositionTableData.add(rowValues);
        }
        return compositionTableData;
    }

    private String[] parseCompositionData(List<String> elementList, List<String> compositionList, List<String> comments) {
        String[] parsedElementString = new String[elementList.size()];
        for (int i = 0; i < elementList.size(); i++) {
            logger.info("Scraped matweb table output: " + elementList.get(i));
            String element = elementList.get(i).split(",")[1].trim(); // Get the element symbol alone and discard name

            // Parse and extract data from weight percentage possibilities
            // Possible options:
            //  1. Direct percentage value
            //  2. Percentage range separated by " - "
            //  3. <= max percentage value
            //  4. >= min percentage value OR comment specifies that the element makes up the remainder
            String composition = compositionList.get(i); // Option 1
            composition = composition.substring(0, composition.length()-2);
            if (composition.contains(" - ")) { // Option 2
                String[] compositionRange = composition.split(" - ");
                composition = compositionRange[0] + ":" + compositionRange[1];
            }
            if (composition.contains("<=")) { // Option 3
                composition = composition.substring(3);
            }
            if (composition.contains(">=") || comments.get(i).contains("remainder")) { // Option 4
                composition = "#";
            }

            parsedElementString[i] = element + "-" + composition;
        }

        return parsedElementString;
    }
}
