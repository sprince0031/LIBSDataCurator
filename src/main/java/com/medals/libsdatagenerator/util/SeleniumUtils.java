package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import org.apache.http.client.utils.URIBuilder;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility class for all Selenium functions
 * @author Siddharth Prince | 16/12/24 20:36
 */
public class SeleniumUtils {

    private static Logger logger = Logger.getLogger(SeleniumUtils.class.getName());
    public static SeleniumUtils instance = null;
    private WebDriver driver;
    private ChromeOptions options;
    private boolean isDriverOnline = false;

    public SeleniumUtils() {
        options = new ChromeOptions();
        options.addArguments("--headless");  // headless mode
    }

    public static SeleniumUtils getInstance() {
        if (instance == null) {
            instance = new SeleniumUtils();
        }
        return instance;
    }

    /**
     * Initialises Selenium Chrome Web Driver
     */
    public void initDriver() {
        driver = new ChromeDriver(options);
//        driver.get(LIBSDataGenConstants.SELENIUM_WEB_DRIVER);
        isDriverOnline = true;
    }

    /**
     * Closes the Chrome Web Driver
     */
    public void quitSelenium() {
        if (isDriverOnline && driver != null) {
            driver.quit();
            isDriverOnline = false;
        }
    }

    /**
     * Connects to the url provided
     * @param url Base URL string
     * @param queryParams Map of all URL query parameters
     * @return True if connection established, false otherwise
     * @throws URISyntaxException
     */
    public boolean connectToWebsite(String url, HashMap<String, String> queryParams) throws URISyntaxException {
        if (!isDriverOnline) {
            initDriver();
        }

        URIBuilder uriBuilder = new URIBuilder(url);

        if (queryParams != null) {
            for (Map.Entry<String, String> entry: queryParams.entrySet()) {
                if (entry.getKey().equals(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_COMPOSITION)) {
                    logger.info("Query param composition:- " + entry.getValue());
                    for (String element: entry.getValue().split(";")) {
                        logger.info("Component element:- " + element);
                        String[] temp = element.split(":");
                        uriBuilder.addParameter(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_MYTEXT, temp[0]);
                        uriBuilder.addParameter(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_MYPERC, temp[1]);
                    }
                }
                uriBuilder.addParameter(entry.getKey(), entry.getValue()); // Automatically encodes parameters!
            }
        }

        try {
            logger.info("Connecting to " + uriBuilder);
            driver.get(uriBuilder.toString());
            return true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to connect to " + uriBuilder, e);
        }

        return false;

    }

    // Getter-Setter methods
    public WebDriver getDriver() {
        if (!isDriverOnline) {
            initDriver();
        }
        return driver;
    }



}
