package com.medals.libsdatagenerator.controller;

import com.medals.libsdatagenerator.service.Element;
import com.medals.libsdatagenerator.service.LIBSDataService;
import com.medals.libsdatagenerator.service.MatwebDataService;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.apache.commons.cli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.net.HttpURLConnection; // Added for HTTP status codes
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import com.medals.libsdatagenerator.service.SeriesStatistics;

/**
 * @author Siddharth Prince | 16/12/24 18:22
 */

public class LIBSDataController {
    private static Logger logger = Logger.getLogger(LIBSDataController.class.getName());
    private static final Pattern MATWEB_GUID_PATTERN = Pattern.compile(LIBSDataGenConstants.MATWEB_GUID_REGEX);

    private static class SeriesData {
        String seriesKey;
        List<String> individualMaterialGuids;
        String overviewGuid;

        SeriesData(String seriesKey, List<String> individualMaterialGuids, String overviewGuid) {
            this.seriesKey = seriesKey;
            this.individualMaterialGuids = individualMaterialGuids;
            this.overviewGuid = overviewGuid;
        }
    }

    private static SeriesData parseSeriesEntry(String key, String entryString, Pattern guidPattern) {
        if (entryString == null || entryString.trim().isEmpty()) {
            logger.warning("Empty entry string for series key: " + key + ". Skipping.");
            return null;
        }

        List<String> individualGuids = new ArrayList<>();
        String overviewGuid = null;
        boolean hasValidGuid = false;

        String[] parts = entryString.split(",");
        for (String part : parts) {
            String trimmedPart = part.trim();
            if (trimmedPart.isEmpty()) {
                continue;
            }

            if (trimmedPart.startsWith("og-")) {
                String potentialOgGuid = trimmedPart.substring(3);
                if (!potentialOgGuid.isEmpty()) {
                    if (guidPattern.matcher(potentialOgGuid).matches()) {
                        if (overviewGuid == null) {
                            overviewGuid = potentialOgGuid;
                            hasValidGuid = true; // An overview GUID is a valid GUID for the entry
                        } else {
                            logger.warning("Multiple overview GUIDs found for series: " + key +
                                           ". Using first one ('" + overviewGuid + "'), ignoring '" + potentialOgGuid + "'.");
                        }
                    } else {
                        logger.warning("Invalid overview GUID format in series: " + key +
                                       ". Value: '" + potentialOgGuid + "'. Skipping this overview GUID part.");
                    }
                } else {
                     logger.warning("Empty overview GUID (after 'og-') in series: " + key +
                                   ". Part: '" + trimmedPart + "'. Skipping.");
                }
            } else { // Individual material GUID
                if (guidPattern.matcher(trimmedPart).matches()) {
                    individualGuids.add(trimmedPart);
                    hasValidGuid = true; // An individual GUID is a valid GUID for the entry
                } else {
                    logger.warning("Invalid individual material GUID format in series: " + key +
                                   ". Value: '" + trimmedPart + "'. Skipping this GUID.");
                }
            }
        }

        // Only return a SeriesData object if there's at least one valid GUID (overview or individual)
        // and an overview GUID is present, as per current logic focusing on overview GUIDs for processing.
        // This condition might be adjusted if individualMaterialGuids become primary.
        if (overviewGuid != null) { // Modified this condition to primarily care about overviewGuid for now
            return new SeriesData(key, individualGuids, overviewGuid);
        } else if (hasValidGuid) { // Entry has individual guids but no overview_guid
            logger.warning("Series entry for key '" + key + "' has individual GUIDs but no valid overview GUID. Skipping processing for this series as overview GUID is currently required.");
            return null;
        }
        else {
            logger.warning("No valid GUIDs (overview or individual) found for series key: " + key + " in entry: '" + entryString + "'. Skipping.");
            return null;
        }
    }

    public static void main(String[] args) {
        logger.info("Starting LIBS Data Curator...");

        LIBSDataService libsDataService = LIBSDataService.getInstance();
        CommonUtils commonUtils = new CommonUtils(); // Instance for various utilities

        try {
            // Check if the NIST LIBS portal is reachable
            if (libsDataService.isNISTLIBSReachable()) {
                logger.info("Initialising LIBS Data extraction...");

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

                // Logic for -s (series) option
                if (cmd.hasOption(LIBSDataGenConstants.CMD_OPT_SERIES_SHORT)) {
                    logger.info("Processing with -s (series) option.");

                    // Load series properties only if -s option is used
                    Properties seriesProperties = commonUtils.readProperties(LIBSDataGenConstants.STEEL_SERIES_CATALOG_PATH);
                    if (seriesProperties.isEmpty()) {
                        logger.warning("steel_series_catalog.properties file is empty or not found. Cannot process series option.");
                        // Subsequent checks for processedSeriesList.isEmpty() or its contents will handle the abort if no valid series are found.
                    }

                    ArrayList<SeriesData> processedSeriesList = new ArrayList<>();
                    String seriesKeyValueInput = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_SERIES_SHORT);

                    if (seriesKeyValueInput != null) { // User provided one or more series keys
                        logger.info("Processing series key(s): " + seriesKeyValueInput);
                        String[] seriesKeys = seriesKeyValueInput.split(",");
                        for (String singleKey : seriesKeys) {
                            String trimmedKey = singleKey.trim();
                            if (trimmedKey.isEmpty()) {
                                logger.warning("Empty series key found in comma-separated list. Skipping.");
                                continue;
                            }
                            if (!seriesProperties.isEmpty() && seriesProperties.containsKey(trimmedKey)) {
                                String seriesEntry = seriesProperties.getProperty(trimmedKey);
                                SeriesData sData = parseSeriesEntry(trimmedKey, seriesEntry, MATWEB_GUID_PATTERN);
                                if (sData != null) {
                                    processedSeriesList.add(sData);
                                }
                            } else if (seriesProperties.isEmpty()) {
                                logger.warning("Cannot look up series key: " + trimmedKey + " because steel_series_catalog.properties is empty or not found.");
                            } else {
                                logger.warning("Series key: " + trimmedKey + " not found in steel_series_catalog.properties. Skipping.");
                            }
                        }
                    } else { // Process all series from the properties file (-s without a value)
                        logger.info("Processing all series from steel_series_catalog.properties.");
                        if (!seriesProperties.isEmpty()) {
                            for (String key : seriesProperties.stringPropertyNames()) {
                                String seriesEntry = seriesProperties.getProperty(key);
                                SeriesData sData = parseSeriesEntry(key, seriesEntry, MATWEB_GUID_PATTERN);
                                if (sData != null) {
                                    processedSeriesList.add(sData);
                                }
                            }
                        } else {
                            logger.warning("Cannot process all series because steel_series_catalog.properties is empty or not found.");
                        }
                    }

                    // Check if any series contain individual material GUIDs to process
                    boolean hasIndividualGuidsToProcess = false;
                    for (SeriesData sd : processedSeriesList) {
                        if (sd.individualMaterialGuids != null && !sd.individualMaterialGuids.isEmpty()) {
                            hasIndividualGuidsToProcess = true;
                            break;
                        }
                    }

                    if (!hasIndividualGuidsToProcess) {
                        logger.severe("No individual material GUIDs found within the specified series entries to process. Aborting.");
                        return;
                    }
                    logger.info("Found " + processedSeriesList.size() + " series entries with potential materials to process.");

                    MatwebDataService matwebService = MatwebDataService.getInstance(); // Initialize MatwebDataService

                    for (SeriesData currentSeriesData : processedSeriesList) {
                        String seriesKey = currentSeriesData.seriesKey;
                        List<String> individualMaterialGuids = currentSeriesData.individualMaterialGuids;
                        String seriesOverviewGuid = currentSeriesData.overviewGuid; // This is the OG for the current series

                        if (individualMaterialGuids.isEmpty()) {
                            logger.info("No individual material GUIDs found for series: " + seriesKey + ". Skipping this series entry.");
                            continue;
                        }

                        logger.info("Processing series: " + seriesKey + " with " + individualMaterialGuids.size() + " individual material(s). Overview GUID for variations: " + (seriesOverviewGuid != null ? seriesOverviewGuid : "N/A"));

                        for (String individualGuid : individualMaterialGuids) {
                            logger.info("Processing material GUID: " + individualGuid + " from series: " + seriesKey);

                            String[] compositionArray = matwebService.getMaterialComposition(individualGuid);

                            if (compositionArray == null || compositionArray.length == 0) {
                                logger.warning("Failed to fetch composition (null or empty array) for material GUID: " + individualGuid + ". Skipping this material.");
                                continue;
                            }
                            if (compositionArray.length == 1) {
                                if (compositionArray[0].equals(String.valueOf(HttpURLConnection.HTTP_NOT_FOUND))) {
                                    logger.warning("Material GUID: " + individualGuid + " not found (404) on MatWeb. Skipping this material.");
                                    continue;
                                }
                                if (compositionArray[0].equals(String.valueOf(HttpURLConnection.HTTP_INTERNAL_ERROR))) {
                                    logger.warning("MatWeb internal server error (500) for material GUID: " + individualGuid + ". Skipping this material.");
                                    continue;
                                }
                            }

                            ArrayList<Element> baseElements = libsDataService.generateElementsList(compositionArray);
                            if (baseElements == null || baseElements.isEmpty()) {
                                logger.warning("Failed to generate elements for material GUID: " + individualGuid + " from composition: " + String.join(",", compositionArray) + ". Skipping this material.");
                                continue;
                            }
                            logger.info("Base elements for " + individualGuid + ": " + commonUtils.buildCompositionString(baseElements));

                            if (cmd.hasOption(LIBSDataGenConstants.CMD_OPT_COMP_VAR_SHORT)) {
                                double varyBy = Double.parseDouble(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_VARY_BY_SHORT, "0.1"));
                                double maxDelta = Double.parseDouble(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MAX_DELTA_SHORT, "0.5"));
                                int variationMode = Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_VAR_MODE_SHORT, String.valueOf(LIBSDataGenConstants.STAT_VAR_MODE_GAUSSIAN_DIST)));
                                int numSamples = Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_NUM_VARS_SHORT, "50"));
                                String effectiveOverviewGuidForVariations = seriesOverviewGuid;

                                if (variationMode == LIBSDataGenConstants.STAT_VAR_MODE_DIRICHLET_DIST) {
                                    if (effectiveOverviewGuidForVariations == null || effectiveOverviewGuidForVariations.isEmpty()) {
                                        logger.warning("Dirichlet sampling selected for material " + individualGuid + " in series " + seriesKey +
                                                       ", but a valid series overview GUID is missing for this series. Skipping variations for this material.");
                                        continue;
                                    }
                                    logger.info("Using series overview GUID " + effectiveOverviewGuidForVariations + " for Dirichlet sampling of material " + individualGuid);
                                }

                                logger.info("Generating " + numSamples + " compositional variations for " + individualGuid + "...");
                                ArrayList<ArrayList<Element>> compositions = libsDataService.generateCompositionalVariations(
                                    baseElements, varyBy, maxDelta, variationMode, numSamples, effectiveOverviewGuidForVariations
                                );

                                if (compositions == null || compositions.isEmpty()) {
                                    logger.warning("No compositions generated from variations for material GUID: " + individualGuid + ". Nothing to write to dataset.");
                                    continue;
                                }

                                logger.info("Generating dataset for " + compositions.size() + " varied compositions of " + individualGuid + "...");
                                libsDataService.generateDataset(compositions, minWavelength, maxWavelength, csvDirPath, appendMode, forceFetch);
                            } else {
                                logger.info("Fetching base LIBS data for material: " + individualGuid + " (no variations requested).");
                                libsDataService.fetchLIBSData(baseElements, minWavelength, maxWavelength, csvDirPath, appendMode, forceFetch);
                            }
                        } // End loop over individualMaterialGuids
                    } // End loop over processedSeriesList
                } else if (cmd.hasOption(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT)) {
                    // This is the existing -c option logic, ensure matwebService is available if it uses it.
                    MatwebDataService matwebService = MatwebDataService.getInstance(); // Ensure it's available for -c path too
                    logger.info("Processing with -c (composition) option.");
                    String compositionInput = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT);

                    // Check if input is a MatWeb datasheet GUID or a composition string
                    Pattern pattern = Pattern.compile(LIBSDataGenConstants.MATWEB_GUID_REGEX);
                    String[] compositionArray;
                    if (compositionInput == null || compositionInput.trim().isEmpty()) {
                        logger.severe("Composition input is null or empty. Aborting.");
                        return;
                    }

                    if (!pattern.matcher(compositionInput).matches()) {
                        compositionArray = compositionInput.split(",");
                    } else {
                        compositionArray = new MatwebDataService().getMaterialComposition(compositionInput);
                    }

                    if (compositionArray == null || compositionArray.length == 0) {
                        logger.severe("Failed to parse composition or retrieve from Matweb. Aborting.");
                        return;
                    }

                    ArrayList<Element> elements = libsDataService.generateElementsList(compositionArray);
                    if (elements == null || elements.isEmpty()) {
                        logger.severe("Generated elements list is null or empty. Aborting.");
                        return;
                    }
                    logger.info("Elements: " + commonUtils.buildCompositionString(elements));
                    logger.info("Processed input data for -c option.");

                    if (cmd.hasOption(LIBSDataGenConstants.CMD_OPT_COMP_VAR_SHORT)) {
                        double varyBy = Double.parseDouble(
                                cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_VARY_BY_SHORT, "0.1")
                        );
                        double maxDelta = Double.parseDouble(
                                cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MAX_DELTA_SHORT, "0.5")
                        );

                        int variationMode = Integer.parseInt(cmd.getOptionValue(
                                LIBSDataGenConstants.CMD_OPT_VAR_MODE_SHORT,
                                String.valueOf(LIBSDataGenConstants.STAT_VAR_MODE_GAUSSIAN_DIST)
                        ));
                        String overGuid = ""; // For -c, this is different from the series overviewGuid
                        if (variationMode == LIBSDataGenConstants.STAT_VAR_MODE_DIRICHLET_DIST) {
                            if (!cmd.hasOption(LIBSDataGenConstants.CMD_OPT_OVERVIEW_GUID_SHORT)) {
                                logger.severe("Overview GUID (-og) not present for Dirichlet sampling with -c option. Aborting!");
                                System.err.println("Error: Overview GUID (-og) must be provided when using variation mode 2 (Dirichlet) with the -c option.");
                                return;
                            }
                            overGuid = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_OVERVIEW_GUID_SHORT);
                        }
                        int numSamples = Integer.parseInt(
                                cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_NUM_VARS_SHORT, "50"));

                        ArrayList<ArrayList<Element>> compositions = libsDataService.generateCompositionalVariations(
                                elements, varyBy, maxDelta, variationMode, numSamples, overGuid);

                        if (compositions != null && !compositions.isEmpty()) {
                            libsDataService.generateDataset(compositions, minWavelength, maxWavelength, csvDirPath,
                                    appendMode, forceFetch);
                            logger.info("Successfully generated dataset for composition: " + compositionInput);
                        } else {
                             logger.warning("No compositions generated for input: " + compositionInput);
                        }

                    } else {
                        // This is the original non-variation path for -c
                        libsDataService.fetchLIBSData(elements, minWavelength, maxWavelength, csvDirPath);
                         logger.info("Successfully fetched LIBS data for composition: " + compositionInput);
                    }
                }
                // Note: The case where neither -s nor -c is provided is handled by CommonUtils.getTerminalArgHandler
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception occurred!", e);
            System.out.println("Error occurred: " + e);
        }

    }
}
