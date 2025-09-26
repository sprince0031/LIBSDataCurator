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
import java.util.Objects;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class InputCompositionProcessor {

    private static final Logger logger = Logger.getLogger(InputCompositionProcessor.class.getName());
    private static final Pattern MATWEB_GUID_PATTERN = Pattern.compile(LIBSDataGenConstants.MATWEB_GUID_REGEX);
    private static final Pattern COMPOSITION_STRING_PATTERN = Pattern.compile(LIBSDataGenConstants.INPUT_COMPOSITION_STRING_REGEX);
    private static final Pattern COATED_SERIES_PATTERN = Pattern.compile(LIBSDataGenConstants.COATED_SERIES_KEY_PATTERN);
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
        String coatingElement = null;
        Double coatingPercentage = null;

        // Check if this is a coated material series
        if (key.contains("coated.")) {
            // Use regex to extract coating information from the key
            // Expected format: element-percentage.coated.series.name
            // Example: Sn-1.2.coated.steels
            Matcher matcher = COATED_SERIES_PATTERN.matcher(key);
            if (matcher.matches()) {
                try {
                    coatingElement = matcher.group(1); // "Sn"
                    coatingPercentage = Double.parseDouble(matcher.group(2)); // 1.2
                    logger.info("Detected coating material: " + coatingElement + " at " + coatingPercentage + "% for series: " + key);
                } catch (NumberFormatException e) {
                    logger.warning("Invalid coating percentage format in series key: " + key + ". Coating: " + matcher.group(2));
                }
            } else {
                logger.warning("Invalid coated series key format: " + key + ". Expected format: Element-Percentage.coated.series.name");
            }
        }

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

        return new SeriesInput(key, individualGuids, overviewGuid, coatingElement, coatingPercentage);
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

                // Check if this GUID has already been processed
                MaterialGrade cachedMaterial = findMaterialByGuid(materialGrades, individualGuid);
                List<Element> baseComposition;
                String materialName;
                String[] materialAttributes;

                if (cachedMaterial != null) {
                    logger.info("Material GUID: " + individualGuid + " already processed. Using cached data.");
                    // Create a new MaterialGrade with the cached composition but current series context
                    baseComposition = cachedMaterial.getComposition();
                    materialName = cachedMaterial.getMaterialName();
                    materialAttributes = cachedMaterial.getMaterialAttributes();
                } else {
                    String[] compositionArray = matwebService.getMaterialComposition(individualGuid);

                    if (!matwebService.validateMatwebServiceOutput(compositionArray, individualGuid)) {
                        materialsProcessed++;
                        // Update progress bar even for failed materials
                        CommonUtils.printProgressBar(materialsProcessed, totalMaterials, "materials processed", out);
                        continue;
                    }
                    baseComposition = LIBSDataService.getInstance().generateElementsList(compositionArray);
                    materialName = matwebService.getDatasheetName();
                    materialAttributes = matwebService.getDatasheetAttributes();
                }
                // Apply coating if this is a coated series
                if (series.isCoated()) {
                    baseComposition = applyCoating(baseComposition, series.getCoatingElement(), series.getCoatingPercentage());
                }

                MaterialGrade materialGrade = new MaterialGrade(baseComposition, individualGuid, series.getOverviewGuid(), series.getSeriesKey());
                materialGrade.setMaterialName(materialName);
                materialGrade.setMaterialAttributes(materialAttributes);
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

    /**
     * Helper method to find a MaterialGrade by GUID in the cached materials list
     * @param materialGrades List of already processed MaterialGrade objects
     * @param guid Material GUID to search for
     * @return MaterialGrade if found, null otherwise
     */
    private MaterialGrade findMaterialByGuid(List<MaterialGrade> materialGrades, String guid) {
        // If GUID is null, we can't find a meaningful match
        if (guid == null) {
            return null;
        }

        for (MaterialGrade material : materialGrades) {
            if (guid.equals(material.getMatGUID())) {
                return material;
            }
        }
        return null;
    }

    /**
     * Applies coating composition to the base material composition.
     * The coating percentage is added as a new element, and existing elements are scaled down proportionally.
     *
     * @param baseComposition The original material composition
     * @param coatingElement The element symbol for the coating (e.g., "Zn" for galvanized)
     * @param coatingPercentage The percentage of coating to apply
     * @return Updated composition with coating applied
     */
    private List<Element> applyCoating(List<Element> baseComposition, String coatingElement, Double coatingPercentage,
                                       Boolean scaleMode) {
        if (coatingElement == null || coatingPercentage == null || coatingPercentage <= 0) {
            logger.warning("Invalid coating parameters. Returning original composition.");
            return baseComposition;
        }

        // Validate coating element
        if (!PeriodicTable.isValidElement(coatingElement)) {
            logger.warning("Invalid coating element: " + coatingElement + ". Returning original composition.");
            return baseComposition;
        }

        logger.info("Applying coating: " + coatingElement + " at " + coatingPercentage + "% to base composition");

        List<Element> coatedComposition = new ArrayList<>();

        if (!scaleMode) {
            Element maxPercentElement = baseComposition.get(0);
            for (Element element : baseComposition) {
                if (element.getPercentageComposition() > maxPercentElement.getPercentageComposition()) {
                    maxPercentElement = element;
                }
            }
            
        }
        // Scale down existing elements by (100 - coatingPercentage) / 100
        double scaleFactor = (100.0 - coatingPercentage) / 100.0;

        // Check if coating element already exists in base composition
        boolean coatingElementExists = false;
        for (Element element : baseComposition) {
            if (element.getSymbol().equals(coatingElement)) {
                // Coating element exists, add coating percentage to it and scale
                double newPercentage = (element.getPercentageComposition() * scaleFactor) + coatingPercentage;
                Element modifiedElement = new Element(
                    element.getName(),
                    element.getSymbol(),
                    newPercentage,
                    element.getMin(),
                    element.getMax(),
                    element.getAverageComposition()
                );
                coatedComposition.add(modifiedElement);
                coatingElementExists = true;
            } else {
                // Scale down other elements
                double newPercentage = element.getPercentageComposition() * scaleFactor;
                Element scaledElement = new Element(
                    element.getName(),
                    element.getSymbol(),
                    newPercentage,
                    element.getMin(),
                    element.getMax(),
                    element.getAverageComposition()
                );
                coatedComposition.add(scaledElement);
            }
        }

        // If coating element doesn't exist in base composition, add it as new element
        if (!coatingElementExists) {
            Element coatingElementObj = new Element(
                PeriodicTable.getElementName(coatingElement),
                coatingElement,
                coatingPercentage,
                null,
                null,
                null
            );
            coatedComposition.add(coatingElementObj);
        }

        // Log the coating application
        double totalPercentage = coatedComposition.stream()
            .mapToDouble(Element::getPercentageComposition)
            .sum();
        logger.info("Coating applied successfully. Total composition: " +
            String.format("%.3f", totalPercentage) + "%");

        return coatedComposition;
    }

}
