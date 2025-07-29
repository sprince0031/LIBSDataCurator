package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import org.apache.commons.cli.*;
import org.apache.http.client.utils.URIBuilder;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
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

    private static Logger logger = Logger.getLogger(CommonUtils.class.getName());

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

    public CommandLine getTerminalArgHandler(String[] args) {
        Options options = new Options();

        // Composition flag
        Option composition = new Option(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT,
                LIBSDataGenConstants.CMD_OPT_COMPOSITION_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_COMPOSITION_DESC);
        composition.setRequired(false);
        options.addOption(composition);

        // Series flag
        Option series = new Option(LIBSDataGenConstants.CMD_OPT_SERIES_SHORT,
                LIBSDataGenConstants.CMD_OPT_SERIES_LONG,
                true, // This enables the optional argument value
                LIBSDataGenConstants.CMD_OPT_SERIES_DESC);
        series.setOptionalArg(true); // Actually make the argument value optional
        series.setRequired(false);
        options.addOption(series);

        // Number of variations
        options.addOption(LIBSDataGenConstants.CMD_OPT_NUM_VARS_SHORT,
                LIBSDataGenConstants.CMD_OPT_NUM_VARS_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_NUM_VARS_DESC);

        // Min Wavelength
        options.addOption(LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_SHORT,
                LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_DESC);

        // Max Wavelength
        options.addOption(LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_SHORT,
                LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_DESC);

        // Data output path
        options.addOption(LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_SHORT,
                LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_DESC);

        // Perform compositional variations
        options.addOption(LIBSDataGenConstants.CMD_OPT_COMP_VAR_SHORT,
                LIBSDataGenConstants.CMD_OPT_COMP_VAR_LONG,
                false,
                LIBSDataGenConstants.CMD_OPT_COMP_VAR_DESC);

        // Force fetch
        options.addOption(LIBSDataGenConstants.CMD_OPT_FORCE_FETCH_SHORT,
                LIBSDataGenConstants.CMD_OPT_FORCE_FETCH_LONG,
                false,
                LIBSDataGenConstants.CMD_OPT_FORCE_FETCH_DESC);

        // Append mode
        options.addOption(LIBSDataGenConstants.CMD_OPT_NO_APPEND_MODE_SHORT,
                LIBSDataGenConstants.CMD_OPT_NO_APPEND_MODE_LONG,
                false,
                LIBSDataGenConstants.CMD_OPT_NO_APPEND_MODE_DESC);

        // vary by (for compositions)
        options.addOption(LIBSDataGenConstants.CMD_OPT_VARY_BY_SHORT,
                LIBSDataGenConstants.CMD_OPT_VARY_BY_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_VARY_BY_DESC);

        // Max delta value
        options.addOption(LIBSDataGenConstants.CMD_OPT_MAX_DELTA_SHORT,
                LIBSDataGenConstants.CMD_OPT_MAX_DELTA_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_MAX_DELTA_DESC);

        // Variation mode
        options.addOption(LIBSDataGenConstants.CMD_OPT_VAR_MODE_SHORT,
                LIBSDataGenConstants.CMD_OPT_VAR_MODE_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_VAR_MODE_DESC);

        // Overview GUID
        options.addOption(LIBSDataGenConstants.CMD_OPT_OVERVIEW_GUID_SHORT,
                LIBSDataGenConstants.CMD_OPT_OVERVIEW_GUID_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_OVERVIEW_GUID_DESC);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter helpFormatter = new HelpFormatter();

        try {
            CommandLine cmd = parser.parse(options, args);

            boolean hasComposition = cmd.hasOption(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT);
            boolean hasSeries = cmd.hasOption(LIBSDataGenConstants.CMD_OPT_SERIES_SHORT);

            if (hasComposition && hasSeries) {
                logger.log(Level.SEVERE, "Error: Cannot use both -c and -s options simultaneously. Please choose one.");
                helpFormatter.printHelp("java LIBSDataGenerator", options);
                return null;
            }

            if (!hasComposition && !hasSeries) {
                logger.log(Level.SEVERE, "Error: Either -c (composition) or -s (series) option must be provided.");
                helpFormatter.printHelp("java LIBSDataGenerator", options);
                return null;
            }

            return cmd;
        } catch (ParseException e) {
            logger.log(Level.SEVERE, "Commandline arg parse error", e);
            helpFormatter.printHelp("java LIBSDataGenerator", options);
            return null;
        }
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
     * Testing if the target website is reachable
     *
     * @return boolean - True if website live, false otherwise
     */
    public boolean isWebsiteReachable(String urlString) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(urlString);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                logger.info(urlString + " is reachable.");
                return true;
            } else {
                logger.log(Level.SEVERE, url + " not reachable. Status code: " + connection.getResponseCode());
                return false;
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred while trying to connect to " + urlString, e);
            return false;
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

}
