package com.medals.libsdatagenerator.model;

import org.json.JSONObject;

/**
 * Represents statistical information for a single element from series overview data
 * @author Siddharth Prince | 02/06/25 15:00
 */
public class ElementStatistics implements JsonModel {
    private String elementSymbol;
    private double averagePercentage;
    private int gradeCount;
    private double minPercentage;
    private double maxPercentage;

    public ElementStatistics() {} // Default constructor to load from Json

    public ElementStatistics(String elementSymbol, double averagePercentage, int gradeCount) {
        this.elementSymbol = elementSymbol;
        this.averagePercentage = averagePercentage;
        this.gradeCount = gradeCount;
        this.minPercentage = 0.0; // Will be set from range data if available
        this.maxPercentage = 100.0; // Will be set from range data if available
    }

    public ElementStatistics(String elementSymbol, double averagePercentage, int gradeCount,
                             double minPercentage, double maxPercentage) {
        this.elementSymbol = elementSymbol;
        this.averagePercentage = averagePercentage;
        this.gradeCount = gradeCount;
        this.minPercentage = minPercentage;
        this.maxPercentage = maxPercentage;
    }

    public String getElementSymbol() {
        return elementSymbol;
    }

    public double getAveragePercentage() {
        return averagePercentage;
    }

    public int getGradeCount() {
        return gradeCount;
    }

    public double getMinPercentage() {
        return minPercentage;
    }

    public double getMaxPercentage() {
        return maxPercentage;
    }

    @Override
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("elementSymbol", this.elementSymbol);
        json.put("averagePercentage", this.averagePercentage);
        json.put("gradeCount", this.gradeCount);
        json.put("minPercentage", this.minPercentage);
        json.put("maxPercentage", this.maxPercentage);
        return json;
    }

    @Override
    public void fromJson(JSONObject json) {
        this.elementSymbol = json.getString("elementSymbol");
        this.averagePercentage = json.getDouble("averagePercentage");
        this.gradeCount = json.getInt("gradeCount");
        this.minPercentage = json.getDouble("minPercentage");
        this.maxPercentage = json.getDouble("maxPercentage");
    }

    @Override
    public String toString() {
        return String.format("ElementStatistics{element='%s', avg=%.3f%%, count=%d, range=[%.3f-%.3f]%%}",
                elementSymbol, averagePercentage, gradeCount, minPercentage, maxPercentage);
    }
}

