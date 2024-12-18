package com.medals.libsdatagenerator.service;


import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.util.CommonUtils;
import com.medals.libsdatagenerator.util.SeleniumUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service class for all NIST LIBS website related functionality.
 * @author Siddharth Prince | 17/12/24 13:21
 */

public class LIBSDataService {
    private static Logger logger = Logger.getLogger(LIBSDataService.class.getName());

    public static LIBSDataService instance = null;

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


    public void fetchLIBSData(ArrayList<Element> elements, String minWavelength, String maxWavelength, String savePath) {
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

            // Save to a file with a unique name
            String filename = "composition_" + queryParams.get(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_COMPOSITION) + ".csv";
            Files.write(Paths.get(savePath, filename), csvData.getBytes());
            System.out.println("Saved: " + filename);

            // Close the CSV tab
            seleniumUtils.getDriver().close();
            // Switch back to the original window
            seleniumUtils.getDriver().switchTo().window(originalWindow);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Unable to fetch data from NIST LIBS website", e);
        } finally {
            seleniumUtils.quitSelenium();
        }

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
            Properties elementProps = new CommonUtils().readProperties(elementPropsPath);

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
}
