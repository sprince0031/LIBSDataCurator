package com.medals.libsdatagenerator.controller;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.MaterialGrade;
import com.medals.libsdatagenerator.service.LIBSDataService;
import com.medals.libsdatagenerator.util.CommonUtils;
import com.medals.libsdatagenerator.util.InputCompositonProcessor;
import org.apache.commons.cli.CommandLine;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * @author Siddharth Prince | 16/12/24 18:22
 */

public class LIBSDataController {
    private static Logger logger = Logger.getLogger(LIBSDataController.class.getName());
    private static final Pattern COMPOSITION_STRING_PATTERN = Pattern.compile(LIBSDataGenConstants.INPUT_COMPOSITION_STRING_REGEX);

    public static void main(String[] args) {
        logger.info("Starting LIBS Data Curator...");

        LIBSDataService libsDataService = LIBSDataService.getInstance(); // Data interfacing class for NIST LIBS
        CommonUtils commonUtils = CommonUtils.getInstance(); // Instance for various utility functions
        InputCompositonProcessor compositonProcessor = InputCompositonProcessor.getInstance();

        try {
            logger.info("Initialising LIBS Data extraction...");

            // Check if the NIST LIBS portal is reachable
            if (!commonUtils.isWebsiteReachable(LIBSDataGenConstants.NIST_LIBS_FORM_URL)) {
                logger.log(Level.WARNING, "NIST LIBS reachable is not available");
            }

            CommandLine cmd = commonUtils.getTerminalArgHandler(args);

            if (cmd == null) {
                logger.severe("Failed to parse command line arguments. Aborting.");
                return;
            }

            // Common parameters
            String minWavelength = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_SHORT, "200");
            String maxWavelength = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_SHORT, "800");
            String csvDirPath = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_SHORT, CommonUtils.DATA_PATH);
            boolean appendMode = !cmd.hasOption(LIBSDataGenConstants.CMD_OPT_NO_APPEND_MODE_SHORT);
            boolean forceFetch = cmd.hasOption(LIBSDataGenConstants.CMD_OPT_FORCE_FETCH_SHORT);

            List<MaterialGrade> materialGrades;

            if (cmd.hasOption(LIBSDataGenConstants.CMD_OPT_SERIES_SHORT)) {
                // Process input for -s (series) option
                logger.info("Processing with -s (series) option.");

                String seriesKeyValueInput = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_SERIES_SHORT);
                materialGrades = compositonProcessor.getMaterialsList(seriesKeyValueInput, false);
            } else if (cmd.hasOption(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT)) {
                // Process input for -c (composition) option
                logger.info("Processing with -c (composition) option.");

                String compositionInput = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT);
                String overviewGUID = cmd.hasOption(LIBSDataGenConstants.CMD_OPT_OVERVIEW_GUID_SHORT)?
                        cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_OVERVIEW_GUID_SHORT) : null;
                if (COMPOSITION_STRING_PATTERN.matcher(compositionInput).matches()) {
                    materialGrades = compositonProcessor.getMaterialsList(compositionInput, true, overviewGUID);
                } else {
                    materialGrades = compositonProcessor.getMaterialsList(compositionInput, false, overviewGUID);
                }
            } else {
                // Note: The case where neither -s nor -c is provided is handled by CommonUtils.getTerminalArgHandler
                logger.warning("Invalid command line arguments. Aborting.");
                return;
            }

            for (MaterialGrade materialGrade : materialGrades) {
                if (cmd.hasOption(LIBSDataGenConstants.CMD_OPT_COMP_VAR_SHORT)) {
                    int variationMode = Integer.parseInt(cmd.getOptionValue(
                            LIBSDataGenConstants.CMD_OPT_VAR_MODE_SHORT,
                            String.valueOf(LIBSDataGenConstants.STAT_VAR_MODE_DIRICHLET_DIST)
                    ));
                    if (variationMode == LIBSDataGenConstants.STAT_VAR_MODE_DIRICHLET_DIST) {
                        if (materialGrade.getOverviewGUID() == null) {
                            logger.severe("Overview GUID not present for Dirichlet sampling for "
                                    + commonUtils.buildCompositionString(materialGrade.getComposition()) + ". Skipping!");
                            continue;
                        }
                    }

                    double varyBy = Double.parseDouble(
                            cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_VARY_BY_SHORT, "0.1")
                    );
                    double maxDelta = Double.parseDouble(
                            cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MAX_DELTA_SHORT, "0.05")
                    );
                    int numSamples = Integer.parseInt(
                            cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_NUM_VARS_SHORT, "50"));

                    List<List<Element>> compositions = libsDataService.generateCompositionalVariations(
                            materialGrade.getComposition(), varyBy, maxDelta, variationMode, numSamples,
                            materialGrade.getOverviewGUID());

                    if (compositions != null && !compositions.isEmpty()) {
                        libsDataService.generateDataset(compositions, minWavelength, maxWavelength, csvDirPath,
                                appendMode, forceFetch);
                        logger.info("Successfully generated dataset for composition: " + materialGrade);
                    } else {
                        logger.warning("No compositions generated for input: " + materialGrade);
                    }

                } else {
                    // This is the original non-variation path for -c
                    libsDataService.fetchLIBSData(materialGrade.getComposition(), minWavelength, maxWavelength, csvDirPath);
                    logger.info("Successfully fetched LIBS data for composition: " + materialGrade);
                }
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred!", e);
            System.out.println("Error occurred: " + e);
        }

    }
}
