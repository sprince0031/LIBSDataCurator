package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.ElementStatistics;
import com.medals.libsdatagenerator.model.SeriesStatistics;

import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts statistical information from Matweb overview sheet comments
 * @author Siddharth Prince | 02/06/25 15:00
 */
public class SeriesStatisticsExtractor {
    private static final Logger logger = Logger.getLogger(SeriesStatisticsExtractor.class.getName());

    // Pattern to match "Average value: X.XX % Grade Count:NNN" or "Average value: X.XX unit Grade Count:NNN"
    private static final Pattern AVERAGE_PATTERN = Pattern.compile(LIBSDataGenConstants.MATWEB_AVG_REGEX,
                    Pattern.CASE_INSENSITIVE);

    // Alternative pattern for cases where format might vary slightly
    private static final Pattern ALTERNATIVE_PATTERN = Pattern.compile(LIBSDataGenConstants.MATWEB_ALT_AVG_REGEX,
                    Pattern.CASE_INSENSITIVE);

    /**
     * Extracts series statistics from overview sheet data
     *
     * @param elementList List of element names from the composition table
     * @param compositionList List of composition ranges from the table
     * @param comments List of comments containing average values and grade counts
     * @param seriesName Name of the steel series (optional)
     * @param matwebGuid GUID of the overview sheet
     * @return SeriesStatistics object containing extracted statistical information
     */
    public SeriesStatistics extractStatistics(List<String> elementList,
                                              List<String> compositionList,
                                              List<String> comments,
                                              String seriesName,
                                              String matwebGuid) {

        SeriesStatistics seriesStats = new SeriesStatistics(
                seriesName != null ? seriesName : "Unknown Series",
                matwebGuid != null ? matwebGuid : "Unknown GUID"
        );

        logger.info("Extracting series statistics for " + elementList.size() + " elements");

        for (int i = 0; i < elementList.size() && i < comments.size(); i++) {
            String elementString = elementList.get(i);
            String compositionRange = compositionList.get(i);
            String comment = comments.get(i);

            // Extract element symbol
            String elementSymbol = extractElementSymbol(elementString);
            if (elementSymbol == null) {
                logger.warning("Could not extract element symbol from: " + elementString);
                continue;
            }

            // Extract statistical information from comment
            ElementStatistics elementStats = extractElementStatistics(
                    elementSymbol, compositionRange, comment);

            if (elementStats != null) {
                seriesStats.addElementStatistics(elementStats);
                logger.info("Extracted statistics for " + elementSymbol + ": " + elementStats);
            } else {
                logger.warning("Could not extract statistics for element: " + elementSymbol +
                        " from comment: " + comment);
            }
        }

        logger.info("Extracted statistics for " + seriesStats.getElementCount() + " elements");
        logger.info("Total average percentage: " + seriesStats.getTotalAveragePercentage() + "%");

        return seriesStats;
    }

    /**
     * Extracts element symbol from element string (handles "Element, Symbol" format)
     */
    private String extractElementSymbol(String elementString) {
        if (elementString == null || elementString.trim().isEmpty()) {
            return null;
        }

        // Handle "Element, Symbol" format
        if (elementString.contains(",")) {
            String[] parts = elementString.split(",");
            if (parts.length >= 2) {
                return parts[1].trim();
            }
        }

        // Handle "Element Symbol" format - take the last word
        String[] parts = elementString.trim().split("\\s+");
        if (parts.length > 0) {
            return parts[parts.length - 1];
        }

        return null;
    }

    /**
     * Extracts statistical information for a single element
     */
    private ElementStatistics extractElementStatistics(String elementSymbol,
                                                       String compositionRange,
                                                       String comment) {
        if (comment == null || comment.trim().isEmpty()) {
            return null;
        }

        // Try to extract average value and grade count from comment
        Matcher matcher = AVERAGE_PATTERN.matcher(comment);
        if (!matcher.find()) {
            // Try alternative pattern
            matcher = ALTERNATIVE_PATTERN.matcher(comment);
            if (!matcher.find()) {
                logger.warning("Could not parse comment: " + comment);
                return null;
            }
        }

        try {
            double averageValue = Double.parseDouble(matcher.group(1));
            int gradeCount = Integer.parseInt(matcher.group(2));

            // Extract min and max from composition range if available
            double minPercentage = 0.0;
            double maxPercentage = 100.0;

            if (compositionRange != null && !compositionRange.trim().isEmpty()) {
                String[] rangeParts = parseCompositionRange(compositionRange);
                if (rangeParts != null && rangeParts.length == 2) {
                    try {
                        minPercentage = Double.parseDouble(rangeParts[0]);
                        maxPercentage = Double.parseDouble(rangeParts[1]);
                    } catch (NumberFormatException e) {
                        logger.warning("Could not parse composition range: " + compositionRange);
                    }
                }
            }

            return new ElementStatistics(elementSymbol, averageValue, gradeCount,
                    minPercentage, maxPercentage);

        } catch (NumberFormatException e) {
            logger.warning("Could not parse numeric values from comment: " + comment);
            return null;
        }
    }

    /**
     * Parses composition range string to extract min and max values
     * Handles formats like "0.250 - 18.0 %" or "78.8 - 98.9 %"
     */
    private String[] parseCompositionRange(String compositionRange) {
        if (compositionRange == null) {
            return null;
        }

        // Remove % symbol and extra whitespace
        String cleaned = compositionRange.replaceAll("%", "").trim();

        // Look for range pattern "X - Y"
        if (cleaned.contains(" - ")) {
            String[] parts = cleaned.split(" - ");
            if (parts.length == 2) {
                return new String[]{parts[0].trim(), parts[1].trim()};
            }
        }

        // Handle single value (treat as both min and max)
        try {
            Double.parseDouble(cleaned);
            return new String[]{cleaned, cleaned};
        } catch (NumberFormatException e) {
            // Not a simple numeric value
        }

        return null;
    }

    /**
     * Validates that extracted statistics are reasonable
     */
    public boolean validateStatistics(SeriesStatistics statistics) {
        if (statistics == null || statistics.getElementCount() == 0) {
            logger.warning("No statistics extracted");
            return false;
        }

        if (!statistics.isValidForDirichletSampling()) {
            logger.warning("Statistics are not valid for Dirichlet sampling: " + statistics);
            return false;
        }

        logger.info("Statistics validation passed for " + statistics.getElementCount() + " elements");
        return true;
    }
}

