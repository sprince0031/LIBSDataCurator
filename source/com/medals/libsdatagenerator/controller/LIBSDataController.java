package com.medals.libsdatagenerator.controller;


import com.medals.libsdatagenerator.service.Element;
import com.medals.libsdatagenerator.service.LIBSDataService;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.apache.commons.cli.CommandLine;

import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Siddharth Prince | 16/12/24 18:22
 */

public class LIBSDataController {
    private static Logger logger = Logger.getLogger(LIBSDataController.class.getName());

    public static void main(String[] args) {
        logger.info("Starting LIBS Data Curator...");

        LIBSDataService libsDataService = new LIBSDataService();

        try {
            // Check if the NIST LIBS portal is reachable
            if (libsDataService.isNISTLIBSReachable()) {
                logger.info("Initialising LIBS Data extraction...");

                CommandLine cmd = new CommonUtils().getTerminalArgHandler(args);

                if (cmd != null) {
                    // Parsing user inputs
                    String[] composition = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT)
                            .split(",");
                    ArrayList<Element> elements = libsDataService.generateElementsList(composition);
                    String minWavelength = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_SHORT,
                            "200");
                    String maxWavelength = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_SHORT,
                            "800");
                    String csvPath = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_SHORT, CommonUtils.CONF_PATH);
                    logger.info("Processed input data");

                    // Fetching data from NIST LIBS database
                    System.out.println("\nFetching data record from NIST LIBS database...");
                    libsDataService.fetchLIBSData(elements, minWavelength, maxWavelength, csvPath);

                }

            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred!", e);
            System.out.println("Error occurred: " + e);
        }

    }
}
