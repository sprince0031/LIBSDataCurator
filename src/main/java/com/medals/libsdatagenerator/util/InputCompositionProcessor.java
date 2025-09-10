package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.matweb.SeriesInput;
import com.medals.libsdatagenerator.service.LIBSDataService;
import com.medals.libsdatagenerator.service.MatwebDataService;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class InputCompositionProcessor {

    private static final Logger logger = Logger.getLogger(InputCompositionProcessor.class.getName());
    private static final Pattern MATWEB_GUID_PATTERN = Pattern.compile(LIBSDataGenConstants.MATWEB_GUID_REGEX);
    private static final Pattern COMPOSITION_STRING_PATTERN = Pattern.compile(LIBSDataGenConstants.INPUT_COMPOSITION_STRING_REGEX);
    private static boolean hasIndividualGuidsToProcess = false;

    private static InputCompositionProcessor instance = null;

    public static InputCompositionProcessor getInstance() {
        if (instance == null) {
            instance = new InputCompositionProcessor();
        }
        return instance;
    }

    private SeriesInput parseSeriesEntry(String key, String entryString) {
        if (entryString == null || entryString.trim().isEmpty()) {
            logger.warning("Empty entry string for series key: " + key + ". Skipping.");
            return null;
        }

        List<String> individualGuids = new ArrayList<>();
        String overviewGuid = null;

        String[] parts = entryString.split(",");
        for (String part : parts) {
            String trimmedPart = part.trim();
            if (trimmedPart.isEmpty()) { // skipping empty stings
                continue;
            }

            // Handling individual data sheet GUIDs
            if (!trimmedPart.startsWith("og-")) {
                if (InputCompositionProcessor.MATWEB_GUID_PATTERN.matcher(trimmedPart).matches()) {
                    individualGuids.add(trimmedPart);
                    hasIndividualGuidsToProcess = true;
                } else {
                    logger.warning("Invalid individual material GUID format in series: " + key +
                            ". Value: '" + trimmedPart + "'. Skipping this GUID.");
                }
                continue;
            }

            String potentialOgGuid = trimmedPart.substring(3);
            if (potentialOgGuid.isEmpty()) { // Handling empty overview datasheet GUID string
                logger.warning("Empty overview GUID (after 'og-') in series: " + key +
                        ". Part: '" + trimmedPart + "'. Skipping.");
                continue;
            }

            if (!InputCompositionProcessor.MATWEB_GUID_PATTERN.matcher(potentialOgGuid).matches()) { // Handling invalid GUID string
                logger.warning("Invalid overview GUID format in series: " + key +
                        ". Value: '" + potentialOgGuid + "'. Skipping.");
                continue;
            }

            // Set value for overviewGUID if empty
            if (overviewGuid == null) {
                overviewGuid = potentialOgGuid;
            } else {
                logger.warning("Multiple overview GUIDs found for series: " + key +
                        ". Using first one ('" + overviewGuid + "'), ignoring '" + potentialOgGuid + "'.");
            }
        }

        // If no individual GUIDs are present, SeriesInput object will be created with empty list
        // If no Overview GUID present, SeriesInput object will be created with null OG.

        return new SeriesInput(key, individualGuids, overviewGuid);
    }

    private List<SeriesInput> processSeriesList(List<String> seriesKeys, Properties catalogue) {
        List<SeriesInput> processedSeries =  new ArrayList<>();
        for (String key : seriesKeys) {
            String trimmedKey = key.trim();
            if (trimmedKey.isEmpty()) {
                logger.warning("Empty series key found. Skipping.");
                continue;
            }

            if (!catalogue.containsKey(trimmedKey)) {
                logger.warning("Series key: " + trimmedKey + " not found in materials_catalogue.properties. Skipping.");
                continue;
            }

            String seriesEntry = catalogue.getProperty(trimmedKey);
            SeriesInput sData = parseSeriesEntry(trimmedKey, seriesEntry);
            if (sData != null) {
                processedSeries.add(sData);
            }
        }
        return processedSeries;
    }

    public List<SeriesInput> parseMaterialsCatalogue(String seriesKeyValueInput) throws IOException {
        // Load series properties only if -s option is used
        Properties catalogue = CommonUtils.getInstance().readProperties(CommonUtils.MATERIALS_CATALOGUE_PATH);
        if (catalogue.isEmpty()) {
            throw new IOException("materials_catalogue.properties file is empty or not found. Cannot process series option.");
        }

        List<String> seriesKeys;
        List<SeriesInput> processedSeries;

        if (seriesKeyValueInput != null) { // User provided one or more series keys
            logger.info("Processing series key(s): " + seriesKeyValueInput);
            seriesKeys = Arrays.stream(seriesKeyValueInput.split(",")).toList();

        } else { // Process all series from the properties file (-s without a value)
            logger.info("Processing all series from materials_catalogue.properties.");

            seriesKeys = catalogue.stringPropertyNames()
                    .stream().toList();
        }

        processedSeries = processSeriesList(seriesKeys, catalogue);


        if (!hasIndividualGuidsToProcess) {
            logger.severe("No individual material GUIDs found within the specified series entries to process. Aborting.");
            throw new IOException("No individual material GUIDs found within the specified series entries to process.");
        }
        logger.info("Found " + processedSeries.size() + " series entries with potential materials to process.");

        return processedSeries;
    }

    public List<MaterialGrade> getMaterialsList(String userInput) throws IOException {

        MatwebDataService matwebService = MatwebDataService.getInstance(); // Initialize MatwebDataService

        List<MaterialGrade> materialGrades = new ArrayList<>();

        List<SeriesInput> processedSeriesData = parseMaterialsCatalogue(userInput);

        // Calculate total number of materials to process for progress tracking
        int totalMaterials = 0;
        for (SeriesInput series : processedSeriesData) {
            totalMaterials += series.getIndividualMaterialGuids().size();
        }

        int materialsProcessed = 0;
        PrintStream out = System.out;

        for (SeriesInput series : processedSeriesData) {
            if (series.getIndividualMaterialGuids().isEmpty()) {
                logger.info("No individual material GUIDs found for series: " + series.getSeriesKey() + ". Skipping this series entry.");
                continue;
            }

            logger.info("Processing series: " + series.getSeriesKey() + " with " + series.getIndividualMaterialGuids().size()
                    + " individual material(s). Overview GUID for variations: "
                    + (series.getOverviewGuid() != null ? series.getOverviewGuid() : "N/A"));

            for (String individualGuid : series.getIndividualMaterialGuids()) {
                logger.info("Processing material GUID: " + individualGuid + " from series: " + series.getSeriesKey());

                String[] compositionArray = matwebService.getMaterialComposition(individualGuid);

                if (!matwebService.validateMatwebServiceOutput(compositionArray, individualGuid)) {
                    materialsProcessed++;
                    // Update progress bar even for failed materials
                    CommonUtils.printProgressBar(materialsProcessed, totalMaterials, "materials processed", out);
                    continue;
                }
                List<Element> baseComposition = LIBSDataService.getInstance().generateElementsList(compositionArray);
                MaterialGrade materialGrade = new MaterialGrade(baseComposition, individualGuid, series.getOverviewGuid(), series.getSeriesKey());
                materialGrade.setMaterialName(matwebService.getDatasheetName());
                materialGrade.setMaterialAttributes(matwebService.getDatasheetAttributes());
                materialGrades.add(materialGrade);

                materialsProcessed++;

                // Calculate and display progress
                CommonUtils.printProgressBar(materialsProcessed, totalMaterials, "materials processed", out);
            }

            
            // Print newline after progress bar completion
            CommonUtils.finishProgressBar(totalMaterials, out);
        }
        return materialGrades;
    }

    /**
     * Parses only single composition
     * @param userInput either a direct composition string or a single matGUID
     * @param overviewGUID overviewGUID if given, null if not
     * @return
     * @throws IOException
     */
    public MaterialGrade getMaterial(String userInput, String overviewGUID) throws IOException {
        MaterialGrade materialGrade;
        String[] compositionArray;
        if (COMPOSITION_STRING_PATTERN.matcher(userInput).matches()) {
            compositionArray = userInput.split(",");
            List<Element> baseComposition = LIBSDataService.getInstance().generateElementsList(compositionArray);
            materialGrade = new MaterialGrade(baseComposition, null, overviewGUID, LIBSDataGenConstants.DIRECT_ENTRY);
        } else if (MATWEB_GUID_PATTERN.matcher(userInput).matches()) {
            compositionArray = MatwebDataService.getInstance().getMaterialComposition(userInput);
            List<Element> baseComposition = LIBSDataService.getInstance().generateElementsList(compositionArray);
            materialGrade = new MaterialGrade(baseComposition, userInput, overviewGUID, LIBSDataGenConstants.DIRECT_ENTRY);
        } else {
            throw new IOException("Invalid command line arguments. Aborting.");
        }
        return materialGrade;
    }

}
