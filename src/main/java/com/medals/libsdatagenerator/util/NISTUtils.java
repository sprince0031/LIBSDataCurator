package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebElement;

import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NISTUtils {

    Logger logger = Logger.getLogger(NISTUtils.class.getName());

    private final SeleniumUtils seleniumUtils;

    public NISTUtils(SeleniumUtils seleniumUtils) {
        this.seleniumUtils = seleniumUtils;
    }

    /**
     * Updates the resolution field in the recalculation form and clicks recalculate button
     * @param expectedResolution desired resolution value
     */
    public void setCorrectResolution(String expectedResolution) {
        logger.info("Performing client-side recalculation with resolution: " + expectedResolution);

        // Wait for the resolution input field to be present
        WebElement resolutionInput = seleniumUtils.waitForElementPresent(
                By.name(LIBSDataGenConstants.NIST_LIBS_RECALC_RESOLUTION_INPUT_NAME)
        );

        // Clear existing value and enter new resolution if there is mismatch
        String currentResolution = String.valueOf(resolutionInput.getDomAttribute("value"));
        if  (!currentResolution.equals(expectedResolution)) {
            resolutionInput.clear();
            resolutionInput.sendKeys(expectedResolution);
            logger.info("Updated resolution field from " + currentResolution + " to: " + expectedResolution);
        } else {
            logger.info("Resolution already correct: " + currentResolution);
        }

    }

    /**
     * Updates element percentages in the recalculation form
     * @param elements list of elements with their percentages
     */
    public void updateElementPercentages(List<Element> elements) {
        logger.info("Updating element percentages in recalculation form");

        // Create a map of element symbol to percentage for easy lookup
        Map<String, Double> elementPercentageMap = new HashMap<>();
        for (Element elem : elements) {
            elementPercentageMap.put(elem.getSymbol(), elem.getPercentageComposition());
        }

        List<WebElement> elementInputLabels = seleniumUtils.getDriver().findElements(
                By.xpath(LIBSDataGenConstants.NIST_LIBS_RECALC_ELEMENT_INPUT_LABELS_XPATH)
        );
        List<WebElement> elementInputFields = seleniumUtils.getDriver().findElements(
                By.xpath(LIBSDataGenConstants.NIST_LIBS_RECALC_ELEMENT_INPUT_FIELDS_XPATH)
        );

        // Update each percentage field based on matching element
        for (int i = 0; i < elementInputFields.size(); i++) {
            String elementSymbol = elementInputLabels.get(i).getText(); // Get the text from within the label element i.e., the Symbol

            if (elementPercentageMap.containsKey(elementSymbol)) {
                WebElement percentInput = elementInputFields.get(i);
                percentInput.clear();
                percentInput.sendKeys(String.valueOf(elementPercentageMap.get(elementSymbol)));
                logger.fine("Updated " + elementSymbol + " to " + elementPercentageMap.get(elementSymbol) + "%");
            }
        }

        logger.info("Element percentages updated");
    }

    public void handleRecalculateAlert(List<Element> composition, int remainderElementIdx) {
        try {
            Alert alert = seleniumUtils.getDriver().switchTo().alert();
            String alertText = alert.getText();
            logger.warning("Alert text: " + alertText); // Example: ...Current value: 100.001
            alert.accept();

            String[] parts = alertText.split("Current value: ");
            if (parts.length < 2) {
                logger.severe("Unexpected alert format: " + alertText);
                throw new RuntimeException("Unexpected alert format: " + alertText);
            }
            double delta = 100 - Double.parseDouble(parts[1]);

            Element newRemainderElement = composition.get(remainderElementIdx);
            String old = newRemainderElement.toString();
            newRemainderElement.updatePercentageComposition(delta);
            logger.info("Updated " + old + " to " + newRemainderElement);

            // Identify auto filled element
            WebElement lastInput = seleniumUtils.getDriver().findElement(
                    By.xpath(LIBSDataGenConstants.NIST_LIBS_RECALC_ELEMENT_INPUT_FIELDS_XPATH+"[1]")
            );
            String id = lastInput.getAttribute("id"); // e.g., "perc25"
            String number = id.replace("perc", "");
            WebElement labelSpan = seleniumUtils.getDriver().findElement(By.id("elem" + number));

            if (!labelSpan.getText().trim().equals(newRemainderElement.getSymbol())) {
                logger.warning("Mismatch! DOM last element is " + labelSpan.getText()
                        + " but Java remainder is " + newRemainderElement.getSymbol());
                // TODO: reset or find the correct input field here -> but might not be relevant when getting rid of zero value elements with interpolation added
            }

            JavascriptExecutor js = (JavascriptExecutor) seleniumUtils.getDriver();
            String liveValue = (String) js.executeScript("return arguments[0].value;", lastInput);

            logger.info("Checked last input value: " + liveValue);

            // TODO: Remove when interpolation is incorporated and zero-value elements are eliminated
            // Check if it's non-zero (or equal to the delta) and reset
            try {
                double value = Double.parseDouble(liveValue);

                // If the value is not 0 (meaning NIST JS autofilled it), reset it
                if (Math.abs(value) > 0) {
                    logger.info("NIST autofill detected in last element (" + value + "). Resetting to 0.");

                    // Clear and set to 0
                    lastInput.clear();
                    lastInput.sendKeys("0");

                    // Double check update with JS to ensure the event triggered
                    js.executeScript("arguments[0].value = '0';", lastInput);
                }
            } catch (NumberFormatException e) {
                logger.warning("Could not parse input value: " + liveValue);
            }

            // Pass only element with updated value and recalculate
            List<Element> updatedElements = new ArrayList<>();
            updatedElements.add(newRemainderElement);
            updateElementPercentages(updatedElements);

            // Click recalculate button again
            WebElement recalcButton = seleniumUtils.waitForElementClickable(
                    By.name(LIBSDataGenConstants.NIST_LIBS_RECALC_BUTTON_NAME)
            );
            recalcButton.click();
            logger.info("Clicked Recalculate button for variation after fixing composition.");

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred when trying to handle alert box event.", e);
        }
    }

    public String downloadCsvData(List<Element> elements, Path csvFilePath) {
        String csvData = String.valueOf(HttpURLConnection.HTTP_NOT_FOUND);
        try {
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

            // Grab the CSV content (<pre> block in the page source)
            csvData = seleniumUtils.getDriver().findElement(By.tagName("pre")).getText();

            // Saving to CSV file
            logger.info("Saving fetched LIBS data to: " + csvFilePath.toAbsolutePath()); // New log
            Files.write(csvFilePath, csvData.getBytes());
            // System.out.println("Saved: " + filename);
            logger.info("Saved: " + csvFilePath); // Existing log, kept for consistency with potential existing log parsing

            // Close the CSV tab
            seleniumUtils.getDriver().close();
            // Switch back to the original window
            seleniumUtils.getDriver().switchTo().window(originalWindow);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unable to fetch data from loaded NIST LIBS page", e);
        }
        return csvData;
    }

}
