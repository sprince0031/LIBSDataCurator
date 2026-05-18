package com.medals.libsdatagenerator.model;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Represents statistical information for a steel series from overview datasheet
 * @author Siddharth Prince | 02/06/25 15:00
 */
public class SeriesStatistics implements JsonModel {
    private Map<String, ElementStatistics> compositionStatistics;
    private String seriesName;
    private String matwebGuid;

    public SeriesStatistics() {}; // Default constructor to load from Json

    public SeriesStatistics(String seriesName, String matwebGuid) {
        this.seriesName = seriesName;
        this.matwebGuid = matwebGuid;
        this.compositionStatistics = new HashMap<>();
    }

    public SeriesStatistics(Map<String, ElementStatistics> compositionStatistics) {
        this.compositionStatistics = new HashMap<>(compositionStatistics);
        this.seriesName = "Unknown Series";
        this.matwebGuid = "Unknown GUID";
    }

    public void addElementStatisticsToComposition(ElementStatistics stats) {
        this.compositionStatistics.put(stats.getElementSymbol(), stats);
    }

    public ElementStatistics getElementStatisticsFromComposition(String elementSymbol) {
        return compositionStatistics.get(elementSymbol);
    }

    public Map<String, ElementStatistics> getAllElementStatistics() {
        return new HashMap<>(compositionStatistics);
    }

    public Set<String> getElementSymbols() {
        return compositionStatistics.keySet();
    }

    public boolean hasElement(String elementSymbol) {
        return compositionStatistics.containsKey(elementSymbol);
    }

    public int getElementCount() {
        return compositionStatistics.size();
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
    public double getEffectiveAveragePercentage() {
        double threshold = getMaximumGradeCount() * 0.5;
        return compositionStatistics.values().stream()
                .filter(stats -> stats.getGradeCount() > threshold)
                .mapToDouble(ElementStatistics::getAveragePercentage)
                .sum();
    }

    /**
     * Gets the minimum grade count across all elements (useful for reliability assessment)
     */
    public int getMinimumGradeCount() {
        return compositionStatistics.values().stream()
                .mapToInt(ElementStatistics::getGradeCount)
                .min()
                .orElse(0);
    }

    /**
     * Gets the maximum grade count across all elements
     */
    public int getMaximumGradeCount() {
        return compositionStatistics.values().stream()
                .mapToInt(ElementStatistics::getGradeCount)
                .max()
                .orElse(0);
    }

    /**
     * Validates that the series statistics are reasonable for Dirichlet parameter estimation
     */
    public boolean isValidForDirichletSampling() {
        if (compositionStatistics.isEmpty()) {
            return false;
        }

        // Check that total percentage is reasonable (between 95% and 105% to allow for rounding)
        double totalPercentage = getEffectiveAveragePercentage();
        if (totalPercentage < 95.0 || totalPercentage > 105.0) {
            return false;
        }

        // Check that all elements have reasonable grade counts
        int minCount = getMinimumGradeCount();
        if (minCount < 1) {
            return false;
        }

        // Check that all averages are positive
        return compositionStatistics.values().stream()
                .allMatch(stats -> stats.getAveragePercentage() > 0.0);
    }

    @Override
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        JSONObject compositionStatistics = new JSONObject();
        for (String element: this.compositionStatistics.keySet()) {
            JSONObject stats = this.compositionStatistics.get(element).toJson();
            compositionStatistics.put(element, stats);
        }
        json.put("compositionStatistics", compositionStatistics);
        json.put("seriesName", seriesName);
        json.put("matwebGuid", matwebGuid);
        return json;
    }

    @Override
    public void fromJson(JSONObject json) {
        JSONObject compositionStatistics = json.getJSONObject("compositionStatistics");
        this.compositionStatistics = new HashMap<>();
        for (String element: compositionStatistics.keySet()) {
            JSONObject stats = compositionStatistics.getJSONObject(element);
            ElementStatistics elementStatistics = new ElementStatistics();
            elementStatistics.fromJson(stats);
            addElementStatisticsToComposition(elementStatistics);
        }
        this.seriesName = json.getString("seriesName");
        this.matwebGuid = json.getString("matwebGuid");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("SeriesStatistics{series='%s', guid='%s', elements=%d, total=%.2f%%}\n",
                seriesName, matwebGuid, getElementCount(), getEffectiveAveragePercentage()));

        compositionStatistics.values().forEach(stats ->
                sb.append("  ").append(stats.toString()).append("\n"));

        return sb.toString();
    }
}

