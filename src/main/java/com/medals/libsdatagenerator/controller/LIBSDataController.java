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

            List<MaterialGrade> materialGrades = new ArrayList<>();

            if (userInputs.isSeriesMode) {
                // Process input for -s (series) option
                logger.info("Processing with -s (series) option.");
                materialGrades = compositionProcessor.getMaterialsList(userInputs.compositionInput);
            }

            if (userInputs.isCompositionMode) {
                // Process input for -c (composition) option
                logger.info("Processing with -c (composition) option.");
                materialGrades.add(compositionProcessor.getMaterial(userInputs.compositionInput, userInputs.overviewGuid));
            }

            // Note: The case where neither -s nor -c is provided is handled by CommonUtils.getTerminalArgHandler

            for (MaterialGrade materialGrade : materialGrades) {
                if (userInputs.performVariations) {
                    VariationMode variationMode = userInputs.variationMode;
                    if (variationMode == VariationMode.DIRICHLET) {
                        if (materialGrade.getParentSeries().getOverviewGuid() == null) {
                            logger.severe("Overview GUID not present for Dirichlet sampling for "
                                    + commonUtils.buildCompositionString(materialGrade.getComposition()) + ". Skipping!");
                            continue;
                        }
                    }

                    List<List<Element>> compositions = CompositionalVariations.getInstance()
                            .generateCompositionalVariations(materialGrade, userInputs);

                    if (compositions != null && !compositions.isEmpty()) {
                        // Apply coating to all variations of material if this is a coated series
                        if (materialGrade.getParentSeries().isCoated()) {
                            SeriesInput series = materialGrade.getParentSeries();
                            compositions = InputCompositionProcessor.getInstance().applyCoating(compositions,
                                    series.getCoatingElement(), userInputs.scaleCoating);
                        }
                        libsDataService.generateDataset(compositions, userInputs, materialGrade);
                        logger.info("Successfully generated dataset for composition: " + materialGrade);
                    } else {
                        logger.warning("No compositions generated for input: " + materialGrade);
                    }

                } else {
                    // This is the original non-variation path for -c
                    libsDataService.fetchLIBSData(materialGrade.getComposition(), userInputs);
                    logger.info("Successfully fetched LIBS data for composition: " + materialGrade);
                }
            }

            // After dataset generation, calculate statistics if requested
            if (userInputs.genStats) {
                logger.info("Calculating dataset statistics...");
                DatasetStatisticsService statsService = new DatasetStatisticsService();
                statsService.calculateAndSaveStatistics(userInputs.csvDirPath);
            }

        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred!", e);
            System.out.println("Error occurred: " + e);
        }

    }
}
