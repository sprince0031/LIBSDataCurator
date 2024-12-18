package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import org.apache.commons.cli.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
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

        Option composition = new Option(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT,
                LIBSDataGenConstants.CMD_OPT_COMPOSITION_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_COMPOSITION_DESC);
        composition.setRequired(true);
        options.addOption(composition);

        options.addOption(LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_SHORT,
                LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_DESC);

        options.addOption(LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_SHORT,
                LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_DESC);
        options.addOption(LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_SHORT,
                LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_DESC);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter helpFormatter = new HelpFormatter();

        try {
            return parser.parse(options, args);
        } catch (ParseException e) {
            logger.log(Level.SEVERE, "Commandline arg parse error", e);
            helpFormatter.printHelp("java LIBSDataGenerator", options);
            return null;
        }
    }

}
