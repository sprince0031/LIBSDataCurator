package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.service.Element;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
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
        series.setRequired(false); // The option itself is not required initially, validation logic will handle it
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
     * @param composition ArrayList of Element objects that make up a composition
     * @return A deep copy of the composition
     */
    public ArrayList<Element> deepCopy(ArrayList<Element> composition) {
        ArrayList<Element> copy = new ArrayList<>();
        for (Element e : composition) {
            copy.add(new Element(
                    e.getName(),
                    e.getSymbol(),
                    e.getPercentageComposition(),
                    e.getPercentageCompositionMin(),
                    e.getPercentageCompositionMax(),
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

    public String buildCompositionString(ArrayList<Element> composition) {
        StringBuilder compositionString = new StringBuilder();
        for(int i = 0; i < composition.size(); i++) {
            compositionString.append(composition.get(i).toString());
            if (i != composition.size()-1) {
                compositionString.append(";");
            }
        }
        return compositionString.toString();
    }

}
