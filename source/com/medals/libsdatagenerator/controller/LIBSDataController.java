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

        LIBSDataService libsDataService = LIBSDataService.getInstance();

        try {
            // Check if the NIST LIBS portal is reachable
            if (libsDataService.isNISTLIBSReachable()) {
                logger.info("Initialising LIBS Data extraction...");

                CommandLine cmd = new CommonUtils().getTerminalArgHandler(args);

                if (cmd != null) {
                    // Parsing user inputs
                    String[] composition = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT)
                            .split(",");
                    ArrayList<Element> elements = libsDataService.generateElementsList(composition); // Parses the input composition string into an array list of Element objects.

                    String minWavelength = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_SHORT,
                            "200");
                    String maxWavelength = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_SHORT,
                            "800");
                    String csvDirPath = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_SHORT, CommonUtils.DATA_PATH);
                    logger.info("Processed input data");

                    // Fetching data from NIST LIBS database
                    System.out.println("\nFetching data record from NIST LIBS database...");
                    if (cmd.hasOption(LIBSDataGenConstants.CMD_OPT_COMP_VAR_SHORT)) {
                        ArrayList<ArrayList<Element>> compositions = libsDataService.generateCompositionalVariations(elements, 0.2, 2); // TODO: Provide input args to set varyBy and limit

                        libsDataService.generateCompositionalVariationsDataset(compositions, minWavelength, maxWavelength, csvDirPath);

                    } else {
                        libsDataService.fetchLIBSData(elements, minWavelength, maxWavelength, csvDirPath);
                    }

                }

            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred!", e);
            System.out.println("Error occurred: " + e);
        }

    }
}
