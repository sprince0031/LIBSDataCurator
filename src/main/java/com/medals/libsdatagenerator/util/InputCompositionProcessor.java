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
        String coatingElementName = null;
        Double coatingPercentage = null;
        Element coatingElement = null;

        // Check if this is a coated material series
        if (key.contains("coated.")) {
            // Use regex to extract coating information from the key
            // Expected format: element-percentage.coated.series.name
            // Example: Sn-1.2.coated.steels
            Matcher matcher = COATED_SERIES_PATTERN.matcher(key);
            if (matcher.matches()) {
                try {
                    coatingElementName = matcher.group(1); // "Sn"
                    coatingPercentage = Double.parseDouble(matcher.group(2)); // 1.2
                    logger.info("Detected coating material: " + coatingElementName + " at " + coatingPercentage + "% for series: " + key);
                } catch (NumberFormatException e) {
                    logger.warning("Invalid coating percentage format in series key: " + key + ". Coating: " + matcher.group(2));
                }
            } else {
                logger.warning("Invalid coated series key format: " + key + ". Expected format: Element-Percentage.coated.<series_name>");
            }

            if (PeriodicTable.getElementName(coatingElementName) != null) {
                coatingElement = new Element(PeriodicTable.getElementName(coatingElementName), coatingElementName,
                        coatingPercentage, null, null, null);
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

        return new SeriesInput(key, individualGuids, overviewGuid, coatingElement);
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

                MaterialGrade materialGrade = new MaterialGrade(baseComposition, individualGuid, series);
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
     * @param overviewGUID user provided overviewGUID value, null if not provided
     * @return materialGrade
     * @throws IOException Exception for invalid command line arguments
     */
    public MaterialGrade getMaterial(String userInput, String overviewGUID) throws IOException {
        MaterialGrade materialGrade;
        String[] compositionArray;
        SeriesInput seriesInput = new SeriesInput(LIBSDataGenConstants.DIRECT_ENTRY, null, overviewGUID);
        if (COMPOSITION_STRING_PATTERN.matcher(userInput).matches()) {
            compositionArray = userInput.split(",");
            List<Element> baseComposition = LIBSDataService.getInstance().generateElementsList(compositionArray);
            materialGrade = new MaterialGrade(baseComposition, null, seriesInput);
        } else if (MATWEB_GUID_PATTERN.matcher(userInput).matches()) {
            seriesInput.setIndividualMaterialGuids(Arrays.asList(userInput.split(",")));
            compositionArray = MatwebDataService.getInstance().getMaterialComposition(userInput);
            List<Element> baseComposition = LIBSDataService.getInstance().generateElementsList(compositionArray);
            materialGrade = new MaterialGrade(baseComposition, userInput, seriesInput);
        } else {
            throw new IOException("Invalid command line arguments. Aborting.");
        }
        return materialGrade;
    }

    /**
     * Searches the provided list of cached {@link MaterialGrade} objects for a material with the specified GUID.
     * <p>
     * This method is a key part of the caching feature: it allows efficient lookup of a material by its unique identifier
     * within the already-processed (cached) materials list, avoiding redundant data retrieval or processing.
     * </p>
     *
     * <b>Caching strategy:</b> The method assumes that {@code materialGrades} is a cache of previously processed materials.
     * It performs a linear search for the given {@code guid}. If {@code guid} is {@code null}, the method returns {@code null}
     * immediately, as no valid match can be found.
     *
     * @param materialGrades the list of cached {@link MaterialGrade} objects to search
     * @param guid the material GUID to search for; if {@code null}, no match is possible
     * @return the {@link MaterialGrade} with the matching GUID if found; {@code null} otherwise
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
     * @param baseCompositions List of original material compositions
     * @param coatingElement The element symbol for the coating (e.g., "Zn" for galvanized)
     * @param scaleCoating Boolean to determine if the coating element application scales down all elements or
     *                     just subtracts from dominant element
     * @return Updated composition with coating applied
     */
    public List<List<Element>> applyCoating(List<List<Element>> baseCompositions, Element coatingElement, Boolean scaleCoating) {

        if (coatingElement == null || coatingElement.getPercentageComposition() <= 0) {
            logger.warning("Invalid coating parameters. Returning original composition.");
            return baseCompositions;
        }

        double coatingPercentage = coatingElement.getPercentageComposition();
        logger.info("Applying coating: " + coatingElement + " at " + coatingPercentage + "% to base compositions");

        List<List<Element>> coatedCompositions = new ArrayList<>();

        for (List<Element> baseComposition : baseCompositions) {

            List<Element> coatedComposition = new ArrayList<>();

            if (!scaleCoating) {
                // Subtract coating element percentage from dominant element and add coating element to composition

                coatedComposition.addAll(baseComposition);
                Element maxPercentElement = coatedComposition.getFirst();
                int indexOfCoatingElement = -1;
                for (Element element : coatedComposition) {
                    if (element.getPercentageComposition() > maxPercentElement.getPercentageComposition()) {
                        maxPercentElement = element;
                    }

                    if (element.getSymbol().equals(coatingElement.getSymbol())) {
                        indexOfCoatingElement = coatedComposition.indexOf(element);
                    }
                }

                int indexOfMaxElement = coatedComposition.indexOf(maxPercentElement);
                Double reducedPercentage = maxPercentElement.getPercentageComposition() - coatingPercentage;
                maxPercentElement.setPercentageComposition(reducedPercentage);
                if (maxPercentElement.getMax() != null && maxPercentElement.getMin() != null) {
                    maxPercentElement.setMin(maxPercentElement.getMin() > reducedPercentage ? reducedPercentage : maxPercentElement.getMin());
                    maxPercentElement.setMax(maxPercentElement.getMax() - coatingPercentage);
                }
                coatedComposition.set(indexOfMaxElement, maxPercentElement);

                if (indexOfCoatingElement >= 0) {
                    Element coatedElement = coatedComposition.get(indexOfCoatingElement);
                    Double increasedPercentage = coatedElement.getPercentageComposition() + coatingPercentage;
                    coatedElement.setPercentageComposition(increasedPercentage);
                    if (maxPercentElement.getMax() != null && maxPercentElement.getMin() != null) {
                        coatedElement.setMax(coatedElement.getMax() < increasedPercentage ? increasedPercentage : coatedElement.getMax());
                        coatedElement.setMin(coatedElement.getMin() + coatingPercentage);
                    }
                    coatedComposition.set(indexOfCoatingElement, coatedElement);
                } else {
                    coatedComposition.add(coatingElement);
                }
                logger.info("Coated composition created: " + CommonUtils.getInstance().buildCompositionString(coatedComposition));

            } else {

                // Scale down existing elements by (100 - coatingPercentage) / 100
                double scaleFactor = (100.0 - coatingPercentage) / 100.0;

                // Check if coating element already exists in base composition
                boolean coatingElementExists = false;
                for (Element element : baseComposition) {
                    if (element.getSymbol().equals(coatingElement.getSymbol())) {
                        // Coating element exists, add coating percentage to it and scale
                        double newPercentage = (element.getPercentageComposition() * scaleFactor) + coatingPercentage;
                        coatingElement.setPercentageComposition(newPercentage);
                        coatedComposition.add(coatingElement);
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
                    coatedComposition.add(coatingElement);
                }
            }
            coatedCompositions.add(coatedComposition);

            // Log the coating application
            double totalPercentage = coatedComposition.stream()
                    .mapToDouble(Element::getPercentageComposition)
                    .sum();
            logger.info("Coating applied successfully. Total composition: " +
                    String.format("%.3f", totalPercentage) + "%");
            logger.info("Coating composition created: " + CommonUtils.getInstance().buildCompositionString(coatedComposition));
        }

        return coatedCompositions;
    }

}
