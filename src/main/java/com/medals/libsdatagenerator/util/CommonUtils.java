package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import org.apache.http.client.utils.URIBuilder;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Class with common utility functions
 * @author Siddharth Prince | 17/12/24 16:40
 */

public class CommonUtils {

    private static final Logger logger = Logger.getLogger(CommonUtils.class.getName());

    public static final String HOME_PATH = System.getProperty("user.dir");
    public static final String CONF_PATH = CommonUtils.HOME_PATH + File.separator + "conf";
    public static final String DATA_PATH = CommonUtils.HOME_PATH + File.separator + "data";
    public static final String MATERIALS_CATALOGUE_PATH = CommonUtils.CONF_PATH + File.separator + LIBSDataGenConstants.MATERIALS_CATALOGUE_FILE_NAME;

    public static CommonUtils instance = null;

    public static CommonUtils getInstance() {
        if (instance == null) {
            instance = new CommonUtils();
        }
        return instance;
    }

    /**
     * Reads data from a properties file
     * @return Properties object with values read from given file path
     */
    public Properties readProperties(String filePath) {
        Properties properties = new Properties();
        try {
            if (new File(filePath).exists()) {
                InputStream inputStream = new FileInputStream(filePath);
                properties.load(inputStream);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Unable to read properties file", e);
        }
        return properties;
    }

    /**
     * Utility to deep-copy a composition list.
     * @param composition List of Element objects that make up a composition
     * @return A deep copy of the composition
     */
    public List<Element> deepCopy(List<Element> composition) {
        List<Element> copy = new ArrayList<>(composition.size());
        for (Element e : composition) {
            copy.add(new Element(
                    e.getName(),
                    e.getSymbol(),
                    e.getPercentageComposition(),
                    e.getMin(),
                    e.getMax(),
                    e.getAverageComposition()
                    )
            );
        }
        return copy;
    }

    /**
     * Util method to round to specified decimal positions
     * @param value Double value to round
     * @param numDecimals number of decimal places to round to
     * @return rounded double value
     */
    public static double roundToNDecimals(double value, int numDecimals) {
        BigDecimal bd = BigDecimal.valueOf(value);
        bd = bd.setScale(numDecimals, RoundingMode.HALF_UP);
        return bd.doubleValue();
    }

    public String buildCompositionString(List<Element> composition) {
        StringBuilder compositionString = new StringBuilder();
        for(int i = 0; i < composition.size(); i++) {
            compositionString.append(composition.get(i).toString());
            if (i != composition.size()-1) {
                compositionString.append(";");
            }
        }
        return compositionString.toString();
    }

    /**
     * Builds a cross-platform compatible composition string for filenames.
     * Replaces ':' with '-' to ensure compatibility with Windows filesystem.
     * 
     * @param composition List of Element objects that make up a composition
     * @return A filename-safe composition string
     */
    public String buildCompositionStringForFilename(List<Element> composition) {
        StringBuilder compositionString = new StringBuilder();
        for(int i = 0; i < composition.size(); i++) {
            Element element = composition.get(i);
            // Use symbol + "-" + percentage instead of toString() which uses ":"
            compositionString.append(element.getSymbol())
                             .append("-")
                             .append(element.getPercentageComposition());
            if (i != composition.size()-1) {
                compositionString.append(";");
            }
        }
        return compositionString.toString();
    }

    public Path getCompositionCsvFilePath(String csvDirPath, List<Element> composition) throws IOException {

        Path compositionDirPath = Paths.get(csvDirPath, LIBSDataGenConstants.NIST_LIBS_DATA_DIR);
        if (!Files.exists(compositionDirPath)) {
            Files.createDirectories(compositionDirPath);
        }
        // Build a string like "Cu-50;Fe-50" for cross-platform compatible filename
        String compositionId = buildCompositionStringForFilename(composition);
        String compositionFileName = "composition_" + compositionId + ".csv";
        return Paths.get(compositionDirPath.toString(), compositionFileName);
    }

    /**
     * TODO: refactor to SeleniumUtils and use wait() with timeout for reachability
     * Testing if the target website is reachable
     *
     * @return boolean - True if website live, false otherwise
     */
    public int isWebsiteReachable(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                logger.info(urlString + " is reachable.");
            } else {
                logger.log(Level.SEVERE, url + " not reachable. Status code: " + connection.getResponseCode());
            }
            return connection.getResponseCode();
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred while trying to connect to " + urlString, e);
            return HttpURLConnection.HTTP_INTERNAL_ERROR;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public String getUrl(String url, Map<String, String> queryParams) throws URISyntaxException {
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

        return uriBuilder.toString();
    }

    public String getArchivedWebpageUrl(String urlString) throws RuntimeException {
        try {
            Map<String, String> queryParams = new HashMap<>();
            queryParams.put("url", urlString);
            queryParams.put("output", "json");
            queryParams.put("fl", "timestamp,original,mimetype,statuscode,digest");
            queryParams.put("filter", "statuscode:200");
            queryParams.put("limit", "-1");

            String apiUrl = CommonUtils.getInstance().getUrl(LIBSDataGenConstants.ARCHIVE_API_BASE_URL, queryParams);
            URL url = new URL(apiUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");

            if (conn.getResponseCode() != 200) {
                logger.severe("Failed to query archive.org CDX API. Status code: " + conn.getResponseCode());
                throw new RuntimeException("Failed to query archive.org CDX API. Status code: " + conn.getResponseCode());
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String inputLine;
            StringBuilder content = new StringBuilder();
            while ((inputLine = in.readLine()) != null) {
                content.append(inputLine);
            }
            in.close();
            conn.disconnect();

            JSONArray snapshots = new JSONArray(content.toString());
            if (snapshots.length() <= 1) { // The first entry is the header
                logger.severe("No successful (200 OK) snapshots found on archive.org for GUID: " + url);
                throw new RuntimeException("No successful (200 OK) snapshots found on archive.org for GUID: " + url);
            }

            // The last entry in the list is the most recent one
            JSONArray latestSnapshot = snapshots.getJSONArray(snapshots.length() - 1);
            String timestamp = latestSnapshot.getString(0);
            String archivedUrl = LIBSDataGenConstants.ARCHIVE_BASE_URL + timestamp + "/" + latestSnapshot.getString(1);

            logger.info("Found most recent snapshot from " + timestamp + ". Using URL: " + archivedUrl);
            return archivedUrl;

        } catch (Exception e) {
            logger.log(Level.SEVERE, "An error occurred while trying to fetch data from archive.org.", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Displays a progress bar for tracking completion of tasks.
     * 
     * @param current The current number of completed items
     * @param total The total number of items to process
     * @param message The message to display (e.g., "materials processed", "samples completed")
     * @param out The PrintStream to output to (typically System.out)
     * @param progressBarWidth The width of the progress bar in characters (default: 50)
     */
    public static void printProgressBar(int current, int total, String message, PrintStream out, int progressBarWidth) {
        if (total <= 1) {
            return; // Don't show progress bar for single items
        }
        
        int progress = (BigInteger.valueOf((long) current * progressBarWidth).divide(BigInteger.valueOf(total))).intValue();
        String bar = "=".repeat(progress) + ">" + " ".repeat(progressBarWidth - progress);
        out.printf("\r[%s] %d/%d %s", bar, current, total, message);
    }

    /**
     * Displays a progress bar with default width of 50 characters.
     * 
     * @param current The current number of completed items
     * @param total The total number of items to process
     * @param message The message to display (e.g., "materials processed", "samples completed")
     * @param out The PrintStream to output to (typically System.out)
     */
    public static void printProgressBar(int current, int total, String message, PrintStream out) {
        printProgressBar(current, total, message, out, 50);
    }

    /**
     * Prints a newline to complete the progress bar display.
     * Call this after the progress bar reaches completion.
     * 
     * @param total The total number of items (used to determine if progress bar was shown)
     * @param out The PrintStream to output to (typically System.out)
     */
    public static void finishProgressBar(int total, PrintStream out) {
        if (total > 1) {
            out.println();
        }
    }

}
