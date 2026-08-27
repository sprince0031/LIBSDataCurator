package com.medals.libsdatagenerator.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MaterialFamilyProfile implements JsonModel {
    private String materialFamilyName;
    private List<PlasmaZone> plasmaZones;
    private CalibrationStats calibrationStats;
    private double scaleFactor; // Max intensity of averaged measured spectrum

    // Constructor to load profile from JSON
    public MaterialFamilyProfile() {}

    // Constructor used in tests and spec
    public MaterialFamilyProfile(String materialFamilyName, List<PlasmaZone> plasmaZones,
                                 CalibrationStats calibrationStats) {
        this.materialFamilyName = materialFamilyName;
        this.plasmaZones = plasmaZones;
        this.calibrationStats = calibrationStats;
    }

    // Constructor used in InstrumentProfileService
    public MaterialFamilyProfile(String materialFamilyName) {
        this.materialFamilyName = materialFamilyName;
        this.plasmaZones = new ArrayList<>();
        this.calibrationStats = new CalibrationStats(0, 0);
        this.scaleFactor = 1.0;
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

    public List<PlasmaZone> getPlasmaZones() {
        return plasmaZones;
    }

    public void setPlasmaZones(List<PlasmaZone> zones) {
        this.plasmaZones = zones;
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
        return json;
    }

    @Override
    public void fromJson(JSONObject json) {
        this.materialFamilyName = json.optString("materialFamily", "");
        this.scaleFactor = json.optDouble("scaleFactor");
        JSONArray zonesArray = json.optJSONArray("plasmaZones");
        if (zonesArray != null) {
            for (int i = 0; i < zonesArray.length(); i++) {
                this.plasmaZones.add(PlasmaZone.fromJson(zonesArray.optJSONObject(i)));
            }
        }
        this.calibrationStats = CalibrationStats.fromJson(json.optJSONObject("calibrationStats"));
    }
}
