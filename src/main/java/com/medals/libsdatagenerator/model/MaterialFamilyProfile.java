package com.medals.libsdatagenerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MaterialFamilyProfile implements JsonModel {

    @JsonProperty("materialFamily")
    private String materialFamilyName;

    @JsonProperty("plasmaZones")
    private List<PlasmaZone> plasmaZones = new ArrayList<>();

    @JsonProperty("calibrationStats")
    private CalibrationStats calibrationStats;

    @JsonProperty("scaleFactor")
    private double scaleFactor;

    @JsonProperty("resolution")
    private long resolution;

    @JsonProperty("validationHoldoutCsvs")
    private Map<String, List<String>> validationHoldoutCsvs = new HashMap<>();

    public MaterialFamilyProfile() {}

    public MaterialFamilyProfile(String materialFamilyName, List<PlasmaZone> plasmaZones,
                                 CalibrationStats calibrationStats) {
        this.materialFamilyName = materialFamilyName;
        this.plasmaZones = plasmaZones != null ? plasmaZones : new ArrayList<>();
        this.calibrationStats = calibrationStats;
    }

    public MaterialFamilyProfile(String materialFamilyName) {
        this.materialFamilyName = materialFamilyName;
        this.plasmaZones = new ArrayList<>();
        this.calibrationStats = new CalibrationStats(0, 0);
        this.scaleFactor = 1.0;
        this.validationHoldoutCsvs = new HashMap<>();
    }

    public String getMaterialFamilyName() {
        return materialFamilyName;
    }

    public void setMaterialFamilyName(String materialFamilyName) {
        this.materialFamilyName = materialFamilyName;
    }

    public CalibrationStats getCalibrationStats() {
        return calibrationStats;
    }

    public void setCalibrationStats(CalibrationStats calibrationStats) {
        this.calibrationStats = calibrationStats;
    }

    public double getScaleFactor() {
        return scaleFactor;
    }

    public void setScaleFactor(double scaleFactor) {
        this.scaleFactor = scaleFactor;
    }

    public long getResolution() {
        return resolution;
    }

    public void setResolution(long resolution) {
        this.resolution = resolution;
    }

    public List<PlasmaZone> getPlasmaZones() {
        return plasmaZones;
    }

    public void setPlasmaZones(List<PlasmaZone> zones) {
        this.plasmaZones = zones;
    }

    public Map<String, List<String>> getValidationHoldoutCsvs() {
        return validationHoldoutCsvs;
    }

    public void setValidationHoldoutCsvs(Map<String, List<String>> validationHoldoutCsvs) {
        this.validationHoldoutCsvs = validationHoldoutCsvs != null ? validationHoldoutCsvs : new HashMap<>();
    }

    // Delegation for CalibrationStats
    public double getRSquaredValue() {
        if (calibrationStats == null)
            return 0;
        return calibrationStats.getRSquared();
    }

    public void setRSquaredValue(double score) {
        if (calibrationStats == null)
            calibrationStats = new CalibrationStats(0, 0);
        calibrationStats.setRSquared(score);
    }

    public double getRmse() {
        if (calibrationStats == null)
            return 0;
        return calibrationStats.getRmse();
    }

    public void setRmse(double rmse) {
        if (calibrationStats == null)
            calibrationStats = new CalibrationStats(0, 0);
        calibrationStats.setRmse(rmse);
    }

    @Override
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("materialFamily", materialFamilyName);
        json.put("scaleFactor", scaleFactor);
        json.put("resolution", resolution);
        JSONArray zonesArray = new JSONArray();
        if (plasmaZones != null) {
            for (PlasmaZone zone : plasmaZones) {
                zonesArray.put(zone.toJson());
            }
        }
        json.put("plasmaZones", zonesArray);
        if (calibrationStats != null) {
            json.put("calibrationStats", calibrationStats.toJson());
        }
        if (validationHoldoutCsvs != null && !validationHoldoutCsvs.isEmpty()) {
            JSONObject holdoutsJson = new JSONObject();
            for (Map.Entry<String, List<String>> entry : validationHoldoutCsvs.entrySet()) {
                JSONArray csvArr = new JSONArray();
                for (String path : entry.getValue()) {
                    csvArr.put(path);
                }
                holdoutsJson.put(entry.getKey(), csvArr);
            }
            json.put("validationHoldoutCsvs", holdoutsJson);
        }
        return json;
    }

    @Override
    public void fromJson(JSONObject json) {
        if (json == null) return;
        this.materialFamilyName = json.optString("materialFamily", "");
        this.scaleFactor = json.optDouble("scaleFactor");
        this.resolution = json.optLong("resolution");
        this.plasmaZones = new ArrayList<>();
        JSONArray zonesArray = json.optJSONArray("plasmaZones");
        if (zonesArray != null) {
            for (int i = 0; i < zonesArray.length(); i++) {
                this.plasmaZones.add(PlasmaZone.fromJson(zonesArray.optJSONObject(i)));
            }
        }
        this.calibrationStats = CalibrationStats.fromJson(json.optJSONObject("calibrationStats"));
        this.validationHoldoutCsvs = new HashMap<>();
        JSONObject holdoutsJson = json.optJSONObject("validationHoldoutCsvs");
        if (holdoutsJson != null) {
            for (String key : holdoutsJson.keySet()) {
                JSONArray arr = holdoutsJson.optJSONArray(key);
                List<String> paths = new ArrayList<>();
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        paths.add(arr.getString(i));
                    }
                }
                this.validationHoldoutCsvs.put(key, paths);
            }
        }
    }
}
