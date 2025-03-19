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

                    if (cmd.hasOption(LIBSDataGenConstants.CMD_OPT_COMP_VAR_SHORT)) {
                        double varyBy = Double.parseDouble(
                                cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_VARY_BY_SHORT, "0.1")
                        );
                        double maxDelta = Double.parseDouble(
                                cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MAX_DELTA_SHORT, "0.5")
                        );
                        boolean appendMode = !cmd.hasOption(LIBSDataGenConstants.CMD_OPT_NO_APPEND_MODE_SHORT);
                        boolean forceFetch = cmd.hasOption(LIBSDataGenConstants.CMD_OPT_FORCE_FETCH_SHORT);

                        int variationMode = Integer.parseInt(cmd.getOptionValue(
                                LIBSDataGenConstants.CMD_OPT_VAR_MODE_SHORT,
                                String.valueOf(LIBSDataGenConstants.STAT_VAR_MODE_GAUSSIAN_DIST)
                        ));
                        int numSamples = 50;
                        ArrayList<ArrayList<Element>> compositions = libsDataService.generateCompositionalVariations(
                                elements, varyBy, maxDelta, variationMode, numSamples);

                        libsDataService.generateDataset(compositions, minWavelength, maxWavelength, csvDirPath, appendMode, forceFetch);

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
