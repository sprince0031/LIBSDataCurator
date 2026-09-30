package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.JsonModel;
import com.medals.libsdatagenerator.model.SeriesStatistics;
import com.medals.libsdatagenerator.model.UserInputConfig;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.matweb.SeriesInput;
import com.medals.libsdatagenerator.service.CompositionalVariations;
import com.medals.libsdatagenerator.service.LIBSDataService;
import com.medals.libsdatagenerator.service.MatwebDataService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class InputCompositionProcessor {

    private static final Logger logger = Logger.getLogger(InputCompositionProcessor.class.getName());
    private static final Pattern MATWEB_GUID_PATTERN = Pattern.compile(LIBSDataGenConstants.MATWEB_GUID_REGEX);
    private static final Pattern COMPOSITION_STRING_PATTERN = Pattern.compile(LIBSDataGenConstants.INPUT_COMPOSITION_STRING_REGEX);
    private static final Pattern COATED_SERIES_PATTERN = Pattern.compile(LIBSDataGenConstants.COATED_SERIES_KEY_PATTERN);
    Path matwebCachePath = Paths.get(CommonUtils.DATA_PATH, LIBSDataGenConstants.MATWEB_LOCAL_CACHE_FOLDER);
    private static boolean hasIndividualGuidsToProcess = false;
    private int totalMaterials;
    private int materialsProcessed;
    private static InputCompositionProcessor instance = null;

    public InputCompositionProcessor() {

    }

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

    public List<MaterialGrade> getMaterialsFromCatalogue(String seriesKey) throws Exception {
        List<SeriesInput> processedSeriesData = parseMaterialsCatalogue(seriesKey);
        List<MaterialGrade> materialGradesFromCatalogue = new ArrayList<>();
        // Calculate total number of materials to process for progress tracking
        for (SeriesInput series : processedSeriesData) {
            totalMaterials += series.getIndividualMaterialGuids().size();
        }

        if(!Files.exists(matwebCachePath)) {
            Files.createDirectories(matwebCachePath);
        }

        materialsProcessed = 0;
        for (SeriesInput series : processedSeriesData) {
            if (series.getIndividualMaterialGuids().isEmpty()) {
                logger.info("No individual material GUIDs found for series: " + series.getSeriesKey() + ". Skipping this series entry.");
                continue;
            }

            logger.info("Processing series: " + series.getSeriesKey() + " with " + series.getIndividualMaterialGuids().size()
                    + " individual material(s). Overview GUID for variations: "
                    + (series.getOverviewGuid() != null ? series.getOverviewGuid() : "N/A"));

            // Fetch series statistics from overview datasheet if not cached already
            SeriesStatistics seriesStatistics = loadCachedMaterialByGuid(matwebCachePath, series.getOverviewGuid(),
                    SeriesStatistics.class);

            if (seriesStatistics == null) { // reading cached stats failed
                seriesStatistics = MatwebDataService.getInstance().getSeriesStatistics(series.getOverviewGuid());
                // Cache stats to Json file
                String cacheFile = String.format("%s.json", series.getOverviewGuid());
                Path outputPath = matwebCachePath.resolve(cacheFile);
                CommonUtils.getInstance().saveModelToFile(outputPath, seriesStatistics);
            }

            for (MaterialGrade material: getMaterialsList(series)) {
                material.setOverviewStatistics(seriesStatistics);

                // Caching new material's datasheet
                String cacheFile = String.format("%s.json", material.getMatGUID());
                Path outputPath = matwebCachePath.resolve(cacheFile);
                CommonUtils.getInstance().saveModelToFile(outputPath, material);
                materialGradesFromCatalogue.add(material);
                materialsProcessed++;

                // Calculate and display progress
                CommonUtils.printProgressBar(materialsProcessed, totalMaterials,
                        "materials processed. Current: " + LIBSDataService.getInstance()
                                .processSeriesKeyToMaterialType(series.getSeriesKey()), System.out);
            }
        }
        return materialGradesFromCatalogue;
    }

    public List<MaterialGrade> getMaterialsList(SeriesInput series) throws IOException {
        MatwebDataService matwebService = MatwebDataService.getInstance(); // Initialize MatwebDataService
        List<MaterialGrade> materialGrades = new ArrayList<>();

        for (String individualGuid : series.getIndividualMaterialGuids()) {
            logger.info("Processing material GUID: " + individualGuid + " from series: " + series.getSeriesKey());

            // Check if this GUID has already been processed
            MaterialGrade materialGrade = loadCachedMaterialByGuid(matwebCachePath, individualGuid, MaterialGrade.class);
            if (materialGrade == null) {
                List<String> compositionArray = matwebService.getMaterialComposition(individualGuid);
                if (!matwebService.validateMatwebServiceOutput(compositionArray, individualGuid)) {
                    materialsProcessed++;
                    // Update progress bar even for failed materials
                    CommonUtils.printProgressBar(materialsProcessed, totalMaterials,
                            "materials processed. Current: " + LIBSDataService.getInstance()
                                    .processSeriesKeyToMaterialType(series.getSeriesKey()), System.out);
                    continue;
                }

                Map<String, Object> compositionMetaData = generateElementsList(compositionArray);
                List<Element> baseComposition = (List<Element>) compositionMetaData.get(LIBSDataGenConstants.ELEMENTS_LIST);
                materialGrade = new MaterialGrade(baseComposition, individualGuid, series);

                int remainderElement = (int) compositionMetaData.get(LIBSDataGenConstants.REMAINDER_ELEMENT_IDX);
                materialGrade.setRemainderElementIdx(remainderElement);

                String materialName = matwebService.getDatasheetName();
                materialGrade.setMaterialName(materialName);

                String[] materialAttributes = matwebService.getDatasheetAttributes();
                materialGrade.setMaterialAttributes(materialAttributes);

            }
            materialGrades.add(materialGrade);
        }
        return materialGrades;
    }

    /**
     * Parses only single composition
     *
     * @param userInput full user input config
     * @return materialGrade
     * @throws IOException Exception for invalid command line arguments
     */
    public MaterialGrade getMaterial(UserInputConfig userInput) throws IOException, RuntimeException {
        MaterialGrade materialGrade;
        String compositionInput = userInput.compositionInput;
        String overviewGUID = userInput.overviewGuid;
        if(!Files.exists(matwebCachePath)) {
            Files.createDirectories(matwebCachePath);
        }
        String materialType = userInput.materialType == null ? LIBSDataGenConstants.DIRECT_ENTRY : userInput.materialType;
        if (MATWEB_GUID_PATTERN.matcher(compositionInput).matches()) {
            List<String> materialGuids = new ArrayList<>();
            materialGuids.add(compositionInput);
            SeriesInput seriesInput = new SeriesInput(materialType, materialGuids, overviewGUID);
            materialGrade = getMaterialsList(seriesInput).getFirst();
        } else if (COMPOSITION_STRING_PATTERN.matcher(compositionInput).matches()) {
            List<String> compositionArray = Arrays.asList(compositionInput.split(","));
            String matGuid = null;
            String materialName = userInput.materialGrade;
            Map<String, Object> compositionMetaData = generateElementsList(compositionArray);
            List<Element> baseComposition = (List<Element>) compositionMetaData.get(LIBSDataGenConstants.ELEMENTS_LIST);
            int remainderElementIdx = (Integer) compositionMetaData.get(LIBSDataGenConstants.REMAINDER_ELEMENT_IDX);
            SeriesInput seriesInput = new SeriesInput(materialType, null, overviewGUID);
            materialGrade = new MaterialGrade(baseComposition, matGuid, seriesInput);
            materialGrade.setRemainderElementIdx(remainderElementIdx);
            materialGrade.setMaterialName(materialName);
            materialGrade.setMaterialAttributes(null);
        } else {
            throw new IOException("Invalid command line arguments. Aborting.");
        }
        return materialGrade;
    }

    /**
     * Searches the {@code data/datasheets} directory for previously cached JSON files with datasheet information
     * from matweb and loads it from file if found.
     *
     * @param path Path to cache directory.
     * @param guid the material GUID to search for; cache file name is saved as <GUID>.json
     * @param jsonModel The specific class that implements {@link JsonModel} which can be loaded from file.
     * @return the {@link JsonModel} type object with data loaded from JSON file if found; {@code null} otherwise.
     */
    private <T extends JsonModel> T loadCachedMaterialByGuid(Path path, String guid, Class<T> jsonModel) {
        if (guid == null) {
            return null;
        }

        try {
            logger.info("Loading cached datasheet for GUID: " + guid);
            String cacheFile = String.format("%s.json", guid);
            Path outputPath = path.resolve(cacheFile);
            return CommonUtils.getInstance().loadModelFromFile(outputPath, jsonModel);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Unable to read cached datasheet. Attempting to fetch from datasheet.", e);
        }

        return null;
    }

    public Map<String, Object> generateElementsList(List<String> composition) throws IOException {
        List<Element> elementsList = new ArrayList<>();
        double totalPercentage = 0.0;
        String remainderElementData = "";
        int maxCurrentPercentageIdx = 0;
        for (int i = 0; i < composition.size(); i++) {
            // elementNamePercent[0] -> Symbol, elementNamePercent[1] -> Percentage of
            // composition
            String[] elementNamePercent = composition.get(i).split("-");

            // Check if the current input element exists in the periodic table
            if (!PeriodicTable.isValidElement(elementNamePercent[0])) {
                logger.log(Level.SEVERE, "Invalid input. " + elementNamePercent[0] + " does not exist.");
                throw new IOException("Invalid element " + elementNamePercent[0] + " given as input");
            }

            double avgPercentage = -1;
            double minPercentage = -1;
            double maxPercentage = -1;
            // If the element percentage value is "#", consider as the remaining percentage composition
            if (!composition.get(i).contains("#")) {
                if (elementNamePercent[1].contains(":")) {
                    String[] compositionRange = elementNamePercent[1].split(":");
                    minPercentage = Double.parseDouble(compositionRange[0]);
                    maxPercentage = Double.parseDouble(compositionRange[1]);
                } else {
                    minPercentage = Double.parseDouble(elementNamePercent[1]);
                    maxPercentage = minPercentage;
                }
                avgPercentage = (minPercentage + maxPercentage) / 2;
                totalPercentage += minPercentage; // Min value in the range is the true percentage rather than the avg in reality

                Element element = new Element(
                        PeriodicTable.getElementName(elementNamePercent[0]),
                        elementNamePercent[0],
                        minPercentage, // Min value in the range is the true percentage rather than the avg in reality
                        minPercentage,
                        maxPercentage,
                        avgPercentage);
                elementsList.add(element);
                maxCurrentPercentageIdx = minPercentage > elementsList.get(maxCurrentPercentageIdx).getPercentageComposition() ? i : maxCurrentPercentageIdx;
            } else {
                remainderElementData = composition.get(i);
            }
        }

        Map<String, Object> compositionMetaData = new HashMap<>();

        // Handle dominant/remainder element composition
        if  (!remainderElementData.isEmpty()) {
            double currentPercentage = 100 - totalPercentage;
            String[] data =  remainderElementData.split("-");
            double minPercentage;
            double maxPercentage;
            if (data.length == 2) {
                minPercentage = currentPercentage - CompositionalVariations.POST_NORM_CHECK_DELTA;
                maxPercentage = currentPercentage + CompositionalVariations.POST_NORM_CHECK_DELTA;
            } else {
                String[] compositionRange = data[1].split(":");
                minPercentage = Double.parseDouble(compositionRange[0]);
                maxPercentage = Double.parseDouble(compositionRange[1]);
            }

            Element element = new Element(
                    PeriodicTable.getElementName(data[0]),
                    data[0],
                    currentPercentage,
                    minPercentage,
                    maxPercentage,
                    currentPercentage);
            elementsList.add(element);
            compositionMetaData.put(LIBSDataGenConstants.REMAINDER_ELEMENT_IDX, elementsList.size() - 1);
        } else {
            compositionMetaData.put(LIBSDataGenConstants.REMAINDER_ELEMENT_IDX, maxCurrentPercentageIdx);
        }

        compositionMetaData.put(LIBSDataGenConstants.ELEMENTS_LIST, elementsList);
        return compositionMetaData;
    }

    /**
     * Applies a coating composition to each base material composition in the provided list.
     * <p>
     * There are two strategies for applying the coating:
     * <ul>
     *   <li><b>Scaling strategy</b> (<code>scaleCoating == true</code>): The coating percentage is added as a new element,
     *       and all existing elements' percentage compositions are scaled down proportionally so that the total composition
     *       (including the coating) sums to 100%.</li>
     *   <li><b>Subtraction from dominant element</b> (<code>scaleCoating == false</code>): The coating percentage is added as a new element,
     *       and the percentage is subtracted only from the element with the highest composition (the dominant element),
     *       leaving other elements unchanged.</li>
     * </ul>
     * <p>
     * <b>Expected input formats:</b>
     * <ul>
     *   <li><code>baseCompositions</code>: A list of material compositions, where each composition is a <code>List&lt;Element&gt;</code>
     *       representing the elements and their percentage compositions. Each <code>Element</code> should have a valid symbol and percentage.</li>
     *   <li><code>coatingElement</code>: An <code>Element</code> object representing the coating to apply (e.g., symbol "Zn" and percentage 5.0).</li>
     *   <li><code>scaleCoating</code>: Boolean flag to select the coating strategy (see above).</li>
     * </ul>
     * <p>
     * <b>Side effects:</b>
     * This method does <i>not</i> mutate the original <code>baseCompositions</code> or their contained <code>Element</code> objects.
     * It returns a new <code>List&lt;List&lt;Element&gt;&gt;</code> with updated compositions.
     *
     * @param baseCompositions List of original material compositions; each is a list of {@link Element} objects with percentage compositions.
     * @param coatingElement The {@link Element} representing the coating to apply (e.g., symbol "Zn", percentage 5.0).
     * @param scaleCoating If true, scales down all elements proportionally; if false, subtracts coating percentage from dominant element only.
     * @return A new list of compositions with the coating applied according to the selected strategy.
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
