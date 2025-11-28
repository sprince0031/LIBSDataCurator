package com.medals.libsdatagenerator.controller;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.matweb.SeriesInput;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.VariationMode;
import com.medals.libsdatagenerator.model.UserInputConfig;
import com.medals.libsdatagenerator.service.CompositionalVariations;
import com.medals.libsdatagenerator.service.DatasetStatisticsService;
import com.medals.libsdatagenerator.service.LIBSDataService;
import com.medals.libsdatagenerator.util.CommonUtils;
import com.medals.libsdatagenerator.util.InputCompositionProcessor;
import org.apache.commons.cli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Siddharth Prince | 16/12/24 18:22
 */

public class LIBSDataController {
    private static Logger logger = Logger.getLogger(LIBSDataController.class.getName());

    public static void main(String[] args) {
        logger.info("Starting LIBS Data Curator...");

        LIBSDataService libsDataService = LIBSDataService.getInstance(); // Data interfacing class for NIST LIBS
        CommonUtils commonUtils = CommonUtils.getInstance(); // Instance for various utility functions
        InputCompositionProcessor compositionProcessor = InputCompositionProcessor.getInstance();

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

           // Read and build user input configuration
            UserInputConfig userInputs = new UserInputConfig(cmd);

            if (userInputs.seed != null) {
                logger.info("Seed: " + userInputs.seed);
            } else {
                logger.info("No seed specified.");
            }

            List<MaterialGrade> materialGrades = new ArrayList<>();

            if (userInputs.isSeriesMode) {
                // Process input for -s (series) option
                logger.info("Processing with -s (series) option.");
                materialGrades = compositionProcessor.getMaterialsList(userInputs.compositionInput, userInputs.numDecimalPlaces);
                System.out.println("\n--Finished fetching material grade compositions from Matweb--");
            }

            if (userInputs.isCompositionMode) {
                // Process input for -c (composition) option
                logger.info("Processing with -c (composition) option.");
                materialGrades.add(compositionProcessor.getMaterial(userInputs.compositionInput, userInputs.overviewGuid, userInputs.numDecimalPlaces));
            }

            // Note: The case where neither -s nor -c is provided is handled by CommonUtils.getTerminalArgHandler

            libsDataService.generateDataset(materialGrades, userInputs);

            // After dataset generation, calculate statistics if requested
            if (userInputs.genStats) {
                logger.info("Calculating dataset statistics...");
                DatasetStatisticsService statsService = new DatasetStatisticsService();
                statsService.calculateAndSaveStatistics(userInputs.csvDirPath);
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred!", e);
            System.out.println("Could not process request. Please check logs for details or try again.");
        }

    }
}
