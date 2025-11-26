package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import org.apache.http.client.utils.URIBuilder;
import org.openqa.selenium.UnexpectedAlertBehaviour;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import java.net.URISyntaxException;
import java.time.Duration;
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
    private static final int DEFAULT_WAIT_TIMEOUT_SECONDS = 30;

    public SeleniumUtils() {
        options = new ChromeOptions();
        options.addArguments("--headless");  // headless mode; comment out for visual browser based debugging

        options.setCapability(CapabilityType.UNHANDLED_PROMPT_BEHAVIOUR, UnexpectedAlertBehaviour.IGNORE); // Added to not let Selenium ignore alert prompts
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
     * @return True if connection established, false otherwise
     * @throws URISyntaxException
     */
    public boolean connectToWebsite(String url) {
        if (!isDriverOnline) {
            initDriver();
        }

        try {
            logger.info("Connecting to " + url);
            driver.get(url);
            return true;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to connect to " + url, e);
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
    
    public boolean isDriverOnline() {
        return isDriverOnline;
    }

    /**
     * Creates a WebDriverWait instance with default timeout
     * @return WebDriverWait instance
     */
    public WebDriverWait getWait() {
        return new WebDriverWait(getDriver(), Duration.ofSeconds(DEFAULT_WAIT_TIMEOUT_SECONDS));
    }
    
    /**
     * Creates a WebDriverWait instance with custom timeout
     * @param timeoutSeconds timeout in seconds
     * @return WebDriverWait instance
     */
    public WebDriverWait getWait(int timeoutSeconds) {
        return new WebDriverWait(getDriver(), Duration.ofSeconds(timeoutSeconds));
    }
    
    /**
     * Waits for an element to be present and clickable
     * @param by locator for the element
     * @return the WebElement once it's clickable
     */
    public WebElement waitForElementClickable(By by) {
        return getWait().until(ExpectedConditions.elementToBeClickable(by));
    }
    
    /**
     * Waits for an element to be present
     * @param by locator for the element
     * @return the WebElement once it's present
     */
    public WebElement waitForElementPresent(By by) {
        return getWait().until(ExpectedConditions.presenceOfElementLocated(by));
    }

}
