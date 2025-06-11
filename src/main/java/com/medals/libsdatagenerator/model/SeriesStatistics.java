package com.medals.libsdatagenerator.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Represents statistical information for a steel series from overview datasheet
 * @author Siddharth Prince | 02/06/25 15:00
 */
public class SeriesStatistics {
    private final Map<String, ElementStatistics> elementStatistics;
    private final String seriesName;
    private final String matwebGuid;

    public SeriesStatistics(String seriesName, String matwebGuid) {
        this.seriesName = seriesName;
        this.matwebGuid = matwebGuid;
        this.elementStatistics = new HashMap<>();
    }

    public SeriesStatistics(Map<String, ElementStatistics> elementStatistics) {
        this.elementStatistics = new HashMap<>(elementStatistics);
        this.seriesName = "Unknown Series";
        this.matwebGuid = "Unknown GUID";
    }

    public void addElementStatistics(ElementStatistics stats) {
        elementStatistics.put(stats.getElementSymbol(), stats);
    }

    public ElementStatistics getElementStatistics(String elementSymbol) {
        return elementStatistics.get(elementSymbol);
    }

    public Map<String, ElementStatistics> getAllElementStatistics() {
        return new HashMap<>(elementStatistics);
    }

    public Set<String> getElementSymbols() {
        return elementStatistics.keySet();
    }

    public boolean hasElement(String elementSymbol) {
        return elementStatistics.containsKey(elementSymbol);
    }

    public int getElementCount() {
        return elementStatistics.size();
    }

    public String getSeriesName() {
        return seriesName;
    }

    public String getMatwebGuid() {
        return matwebGuid;
    }

    /**
     * Calculates the total average percentage (should be close to 100% for complete compositions)
     */
    public double getTotalAveragePercentage() {
        return elementStatistics.values().stream()
                .mapToDouble(ElementStatistics::getAveragePercentage)
                .sum();
    }

    /**
     * Gets the minimum grade count across all elements (useful for reliability assessment)
     */
    public int getMinimumGradeCount() {
        return elementStatistics.values().stream()
                .mapToInt(ElementStatistics::getGradeCount)
                .min()
                .orElse(0);
    }

    /**
     * Gets the maximum grade count across all elements
     */
    public int getMaximumGradeCount() {
        return elementStatistics.values().stream()
                .mapToInt(ElementStatistics::getGradeCount)
                .max()
                .orElse(0);
    }

    /**
     * Validates that the series statistics are reasonable for Dirichlet parameter estimation
     */
    public boolean isValidForDirichletSampling() {
        if (elementStatistics.isEmpty()) {
            return false;
        }

        // Check that total percentage is reasonable (between 95% and 105% to allow for rounding)
        double totalPercentage = getTotalAveragePercentage();
        if (totalPercentage < 95.0 || totalPercentage > 105.0) {
            return false;
        }

        // Check that all elements have reasonable grade counts
        int minCount = getMinimumGradeCount();
        if (minCount < 1) {
            return false;
        }

        // Check that all averages are positive
        return elementStatistics.values().stream()
                .allMatch(stats -> stats.getAveragePercentage() > 0.0);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("SeriesStatistics{series='%s', guid='%s', elements=%d, total=%.2f%%}\n",
                seriesName, matwebGuid, getElementCount(), getTotalAveragePercentage()));

        elementStatistics.values().forEach(stats ->
                sb.append("  ").append(stats.toString()).append("\n"));

        return sb.toString();
    }
}

