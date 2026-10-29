package com.medals.libsdatagenerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CalibrationStats {

    @JsonProperty("rSquared")
    private double rSquared;

    @JsonProperty("rmse")
    private double rmse;

    @JsonProperty("weightedCorrelationCost")
    private double weightedCorrelationCost;

    @JsonProperty("numCalibratedMaterials")
    private int numCalibratedMaterials;

    @JsonProperty("calibratedMaterials")
    private List<String> calibratedMaterials = new ArrayList<>();

    public CalibrationStats() {}

    public CalibrationStats(double rSquared, double rmse) {
        this.rSquared = rSquared;
        this.rmse = rmse;
    }

    public CalibrationStats(double rSquared, double rmse, double weightedCorrelationCost,
                            int numCalibratedMaterials, List<String> calibratedMaterials) {
        this.rSquared = rSquared;
        this.rmse = rmse;
        this.weightedCorrelationCost = weightedCorrelationCost;
        this.numCalibratedMaterials = numCalibratedMaterials;
        this.calibratedMaterials = calibratedMaterials != null ? calibratedMaterials : new ArrayList<>();
    }

    public double getRSquared() {
        return rSquared;
    }

    public void setRSquared(double rSquared) {
        this.rSquared = rSquared;
    }

    public double getRmse() {
        return rmse;
    }

    public void setRmse(double rmse) {
        this.rmse = rmse;
    }

    public double getWeightedCorrelationCost() {
        return weightedCorrelationCost;
    }

    public void setWeightedCorrelationCost(double weightedCorrelationCost) {
        this.weightedCorrelationCost = weightedCorrelationCost;
    }

    public int getNumCalibratedMaterials() {
        return numCalibratedMaterials;
    }

    public void setNumCalibratedMaterials(int numCalibratedMaterials) {
        this.numCalibratedMaterials = numCalibratedMaterials;
    }

    public List<String> getCalibratedMaterials() {
        return calibratedMaterials;
    }

    public void setCalibratedMaterials(List<String> calibratedMaterials) {
        this.calibratedMaterials = calibratedMaterials != null ? calibratedMaterials : new ArrayList<>();
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("rSquared", rSquared);
        json.put("rmse", rmse);
        json.put("weightedCorrelationCost", weightedCorrelationCost);
        json.put("numCalibratedMaterials", numCalibratedMaterials);
        JSONArray arr = new JSONArray();
        if (calibratedMaterials != null) {
            for (String mat : calibratedMaterials) {
                arr.put(mat);
            }
        }
        json.put("calibratedMaterials", arr);
        return json;
    }

    public static CalibrationStats fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        CalibrationStats stats = new CalibrationStats(
                json.optDouble("rSquared", 0.0),
                json.optDouble("rmse", 0.0)
        );
        stats.setWeightedCorrelationCost(json.optDouble("weightedCorrelationCost", 0.0));
        stats.setNumCalibratedMaterials(json.optInt("numCalibratedMaterials", 0));
        if (json.has("calibratedMaterials")) {
            JSONArray arr = json.optJSONArray("calibratedMaterials");
            if (arr != null) {
                List<String> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    list.add(arr.optString(i));
                }
                stats.setCalibratedMaterials(list);
            }
        }
        return stats;
    }
}
