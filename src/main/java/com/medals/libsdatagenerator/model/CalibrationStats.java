package com.medals.libsdatagenerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @JsonProperty("roiChi2Reduced")
    private double roiChi2Reduced;

    @JsonProperty("roiRSquared")
    private double roiRSquared;

    @JsonProperty("roiRmse")
    private double roiRmse;

    @JsonProperty("roiRmseRaw")
    private double roiRmseRaw;

    @JsonProperty("wholeSpectrumRSquared")
    private double wholeSpectrumRSquared;

    @JsonProperty("wholeSpectrumRmse")
    private double wholeSpectrumRmse;

    @JsonProperty("wholeSpectrumRmseRaw")
    private double wholeSpectrumRmseRaw;

    @JsonProperty("zoneReductionPenalty")
    private double zoneReductionPenalty;

    @JsonProperty("familySharingPenalty")
    private double familySharingPenalty;

    @JsonProperty("elementParity")
    private Map<String, Double> elementParity = new LinkedHashMap<>();

    @JsonProperty("perMaterialStats")
    private Map<String, Map<String, Object>> perMaterialStats = new LinkedHashMap<>();

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

    @JsonProperty("rSquared")
    public double getRSquared() {
        return rSquared;
    }

    @JsonProperty("rSquared")
    public void setRSquared(double rSquared) {
        this.rSquared = rSquared;
    }

    @JsonProperty("rmse")
    public double getRmse() {
        return rmse;
    }

    @JsonProperty("rmse")
    public void setRmse(double rmse) {
        this.rmse = rmse;
    }

    @JsonProperty("weightedCorrelationCost")
    public double getWeightedCorrelationCost() {
        return weightedCorrelationCost;
    }

    @JsonProperty("weightedCorrelationCost")
    public void setWeightedCorrelationCost(double weightedCorrelationCost) {
        this.weightedCorrelationCost = weightedCorrelationCost;
    }

    @JsonProperty("numCalibratedMaterials")
    public int getNumCalibratedMaterials() {
        return numCalibratedMaterials;
    }

    @JsonProperty("numCalibratedMaterials")
    public void setNumCalibratedMaterials(int numCalibratedMaterials) {
        this.numCalibratedMaterials = numCalibratedMaterials;
    }

    @JsonProperty("calibratedMaterials")
    public List<String> getCalibratedMaterials() {
        return calibratedMaterials;
    }

    @JsonProperty("calibratedMaterials")
    public void setCalibratedMaterials(List<String> calibratedMaterials) {
        this.calibratedMaterials = calibratedMaterials != null ? calibratedMaterials : new ArrayList<>();
    }

    @JsonProperty("roiChi2Reduced")
    public double getRoiChi2Reduced() {
        return roiChi2Reduced;
    }

    @JsonProperty("roiChi2Reduced")
    public void setRoiChi2Reduced(double roiChi2Reduced) {
        this.roiChi2Reduced = roiChi2Reduced;
    }

    @JsonProperty("roiRSquared")
    public double getRoiRSquared() {
        return roiRSquared;
    }

    @JsonProperty("roiRSquared")
    public void setRoiRSquared(double roiRSquared) {
        this.roiRSquared = roiRSquared;
    }

    @JsonProperty("roiRmse")
    public double getRoiRmse() {
        return roiRmse;
    }

    @JsonProperty("roiRmse")
    public void setRoiRmse(double roiRmse) {
        this.roiRmse = roiRmse;
    }

    @JsonProperty("roiRmseRaw")
    public double getRoiRmseRaw() {
        return roiRmseRaw;
    }

    @JsonProperty("roiRmseRaw")
    public void setRoiRmseRaw(double roiRmseRaw) {
        this.roiRmseRaw = roiRmseRaw;
    }

    @JsonProperty("wholeSpectrumRSquared")
    public double getWholeSpectrumRSquared() {
        return wholeSpectrumRSquared;
    }

    @JsonProperty("wholeSpectrumRSquared")
    public void setWholeSpectrumRSquared(double wholeSpectrumRSquared) {
        this.wholeSpectrumRSquared = wholeSpectrumRSquared;
    }

    @JsonProperty("wholeSpectrumRmse")
    public double getWholeSpectrumRmse() {
        return wholeSpectrumRmse;
    }

    @JsonProperty("wholeSpectrumRmse")
    public void setWholeSpectrumRmse(double wholeSpectrumRmse) {
        this.wholeSpectrumRmse = wholeSpectrumRmse;
    }

    @JsonProperty("wholeSpectrumRmseRaw")
    public double getWholeSpectrumRmseRaw() {
        return wholeSpectrumRmseRaw;
    }

    @JsonProperty("wholeSpectrumRmseRaw")
    public void setWholeSpectrumRmseRaw(double wholeSpectrumRmseRaw) {
        this.wholeSpectrumRmseRaw = wholeSpectrumRmseRaw;
    }

    @JsonProperty("zoneReductionPenalty")
    public double getZoneReductionPenalty() {
        return zoneReductionPenalty;
    }

    @JsonProperty("zoneReductionPenalty")
    public void setZoneReductionPenalty(double zoneReductionPenalty) {
        this.zoneReductionPenalty = zoneReductionPenalty;
    }

    @JsonProperty("familySharingPenalty")
    public double getFamilySharingPenalty() {
        return familySharingPenalty;
    }

    @JsonProperty("familySharingPenalty")
    public void setFamilySharingPenalty(double familySharingPenalty) {
        this.familySharingPenalty = familySharingPenalty;
    }

    @JsonProperty("elementParity")
    public Map<String, Double> getElementParity() {
        return elementParity;
    }

    @JsonProperty("elementParity")
    public void setElementParity(Map<String, Double> elementParity) {
        this.elementParity = elementParity != null ? new LinkedHashMap<>(elementParity) : new LinkedHashMap<>();
    }

    @JsonProperty("perMaterialStats")
    public Map<String, Map<String, Object>> getPerMaterialStats() {
        return perMaterialStats;
    }

    @JsonProperty("perMaterialStats")
    public void setPerMaterialStats(Map<String, Map<String, Object>> perMaterialStats) {
        if (perMaterialStats == null) {
            this.perMaterialStats = new LinkedHashMap<>();
            return;
        }
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : perMaterialStats.entrySet()) {
            copy.put(entry.getKey(), entry.getValue() != null ? new LinkedHashMap<>(entry.getValue()) : new LinkedHashMap<>());
        }
        this.perMaterialStats = copy;
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

        json.put("roiChi2Reduced", roiChi2Reduced);
        json.put("roiRSquared", roiRSquared);
        json.put("roiRmse", roiRmse);
        json.put("roiRmseRaw", roiRmseRaw);
        json.put("wholeSpectrumRSquared", wholeSpectrumRSquared);
        json.put("wholeSpectrumRmse", wholeSpectrumRmse);
        json.put("wholeSpectrumRmseRaw", wholeSpectrumRmseRaw);
        json.put("zoneReductionPenalty", zoneReductionPenalty);
        json.put("familySharingPenalty", familySharingPenalty);

        JSONObject parityJson = new JSONObject();
        if (elementParity != null) {
            for (Map.Entry<String, Double> entry : elementParity.entrySet()) {
                parityJson.put(entry.getKey(), entry.getValue());
            }
        }
        json.put("elementParity", parityJson);

        JSONObject perMatJson = new JSONObject();
        if (perMaterialStats != null) {
            for (Map.Entry<String, Map<String, Object>> entry : perMaterialStats.entrySet()) {
                JSONObject matObj = new JSONObject();
                if (entry.getValue() != null) {
                    for (Map.Entry<String, Object> metricEntry : entry.getValue().entrySet()) {
                        matObj.put(metricEntry.getKey(), metricEntry.getValue() != null ? metricEntry.getValue() : JSONObject.NULL);
                    }
                }
                perMatJson.put(entry.getKey(), matObj);
            }
        }
        json.put("perMaterialStats", perMatJson);
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

        stats.setRoiChi2Reduced(json.optDouble("roiChi2Reduced", 0.0));
        stats.setRoiRSquared(json.optDouble("roiRSquared", 0.0));
        stats.setRoiRmse(json.optDouble("roiRmse", 0.0));
        stats.setRoiRmseRaw(json.optDouble("roiRmseRaw", 0.0));
        stats.setWholeSpectrumRSquared(json.optDouble("wholeSpectrumRSquared", 0.0));
        stats.setWholeSpectrumRmse(json.optDouble("wholeSpectrumRmse", 0.0));
        stats.setWholeSpectrumRmseRaw(json.optDouble("wholeSpectrumRmseRaw", 0.0));
        stats.setZoneReductionPenalty(json.optDouble("zoneReductionPenalty", 0.0));
        stats.setFamilySharingPenalty(json.optDouble("familySharingPenalty", 0.0));

        JSONObject parityJson = json.optJSONObject("elementParity");
        if (parityJson != null) {
            Map<String, Double> parityMap = new LinkedHashMap<>();
            for (String key : parityJson.keySet()) {
                if (!parityJson.isNull(key)) {
                    parityMap.put(key, parityJson.optDouble(key, 0.0));
                }
            }
            stats.setElementParity(parityMap);
        }

        JSONObject perMatJson = json.optJSONObject("perMaterialStats");
        if (perMatJson != null) {
            Map<String, Map<String, Object>> perMatMap = new LinkedHashMap<>();
            for (String matKey : perMatJson.keySet()) {
                JSONObject matObj = perMatJson.optJSONObject(matKey);
                if (matObj != null) {
                    Map<String, Object> metrics = new LinkedHashMap<>();
                    for (String metricKey : matObj.keySet()) {
                        Object rawVal = matObj.get(metricKey);
                        metrics.put(metricKey, JSONObject.NULL.equals(rawVal) ? null : fromJsonValue(rawVal));
                    }
                    perMatMap.put(matKey, metrics);
                }
            }
            stats.setPerMaterialStats(perMatMap);
        }
        return stats;
    }

    private static Object fromJsonValue(Object rawVal) {
        if (rawVal instanceof JSONObject nestedObj) {
            return nestedObj.toMap();
        }
        if (rawVal instanceof JSONArray nestedArr) {
            return nestedArr.toList();
        }
        return rawVal;
    }
}
