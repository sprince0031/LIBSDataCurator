package com.medals.libsdatagenerator.model;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class InstrumentProfile implements JsonModel {
    private String instrumentName;
    private double[] wavelengths;
    private Map<String, MaterialFamilyProfile> materialFamilyProfiles;
    private BaselineCorrectionParams baselineParams;
    private int numShots;
    private String sourceFile;
    private String referenceComposition;
    public static final String INSTRUMENT_PROFILE_PATH = CommonUtils.CONF_PATH + File.separator +
            LIBSDataGenConstants.INSTRUMENT_PROFILE_JSON_FILE;

    // Constructor to load profile from JSON
    public InstrumentProfile() {}

    // Constructor used in tests and spec
    public InstrumentProfile(String instrumentName, double[] wavelengths,
                             Map<String, MaterialFamilyProfile> materialFamilyProfiles, BaselineCorrectionParams baselineParams) {
        this.instrumentName = instrumentName;
        this.wavelengths = wavelengths;
        this.materialFamilyProfiles = materialFamilyProfiles;
        this.baselineParams = baselineParams;
    }

    // Constructor used in InstrumentProfileService
    public InstrumentProfile(double[] wavelengths, String sourceFile, String referenceComposition) {
        this.wavelengths = wavelengths;
        this.sourceFile = sourceFile;
        this.referenceComposition = referenceComposition;
        this.baselineParams = new BaselineCorrectionParams();
        this.materialFamilyProfiles = new HashMap<>();
    }

    public String getInstrumentName() {
        return instrumentName;
    }

    public void setInstrumentName(String instrumentName) {
        this.instrumentName = instrumentName;
    }

    public void setWavelengthGrid(double[] wavelengths) {
        this.wavelengths = wavelengths;
    }

    // Alias for getWavelengths to match existing code
    public double[] getWavelengthGrid() {
        return wavelengths;
    }

    public Map<String, MaterialFamilyProfile> getMaterialFamilyProfiles() {
        return materialFamilyProfiles;
    }

    public MaterialFamilyProfile getMaterialFamilyProfile(String familyName) {
        return materialFamilyProfiles.get(familyName);
    }

    public void addMaterialFamilyProfile(MaterialFamilyProfile materialFamilyProfile) {
        materialFamilyProfiles.put(materialFamilyProfile.getMaterialFamilyName(), materialFamilyProfile);
    }

    public BaselineCorrectionParams getBaselineParams() {
        return baselineParams;
    }

    public void setBaselineParams(BaselineCorrectionParams baselineParams) {
        this.baselineParams = baselineParams;
    }

    public int getNumShots() {
        return numShots;
    }

    public void setNumShots(int numShots) {
        this.numShots = numShots;
    }

    public String getComposition() {
        return referenceComposition;
    }

    public void setComposition(String composition) {
        this.referenceComposition = composition;
    }

    public String getSourceFile() {
        return sourceFile;
    }

    public void setSourceFile(String sourceFile) {
        this.sourceFile = sourceFile;
    }

    public double getMinWavelength() {
        if (wavelengths == null || wavelengths.length == 0)
            return 0.0;
        return wavelengths[0];
    }

    public double getMaxWavelength() {
        if (wavelengths == null || wavelengths.length == 0)
            return 0.0;
        return wavelengths[wavelengths.length - 1];
    }

    // Delegation for Baseline Correction Params
    public double getLambda() {
        if (baselineParams == null)
            return 0;
        return baselineParams.getLambda();
    }

    public double getP() {
        if (baselineParams == null)
            return 0;
        return baselineParams.getP();
    }

    public int getMaxIterations() {
        if (baselineParams == null)
            return 0;
        return baselineParams.getMaxIterations();
    }

    @Override
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("instrumentName", instrumentName);
        JSONArray wavelengthGrid = new JSONArray();
        if (wavelengths != null) {
            for (double d : wavelengths) {
                wavelengthGrid.put(d);
            }
        }
        JSONObject materialFamilyProfilesJson = new JSONObject();
        for (String key : materialFamilyProfiles.keySet()) {
            materialFamilyProfilesJson.put(key, materialFamilyProfiles.get(key).toJson());
        }
        json.put("materialFamilyProfiles", materialFamilyProfilesJson);
        json.put("wavelengths", wavelengthGrid);
        json.put("numShots", numShots);
        json.put("sourceFile", sourceFile);
        json.put("referenceComposition", referenceComposition);
        if (baselineParams != null) {
            json.put("baselineCorrectionParams", baselineParams.toJson());
        }
        return json;
    }

    @Override
    public void fromJson(JSONObject json) {
        this.instrumentName = json.optString("instrumentName", "");
        JSONArray wavelengthsArray = json.optJSONArray("wavelengths");
        this.wavelengths = new double[wavelengthsArray != null ? wavelengthsArray.length() : 0];
        if (wavelengthsArray != null) {
            for (int i = 0; i < wavelengthsArray.length(); i++) {
                wavelengths[i] = wavelengthsArray.getDouble(i);
            }
        }
        JSONObject materialFamilyProfilesJson = json.optJSONObject("materialFamilyProfiles");
        if (materialFamilyProfilesJson != null) {
            for (String key : materialFamilyProfilesJson.keySet()) {
                MaterialFamilyProfile materialFamilyProfile = new MaterialFamilyProfile();
                materialFamilyProfile.fromJson(materialFamilyProfilesJson.getJSONObject(key));
                this.materialFamilyProfiles.put(key, materialFamilyProfile);
            }
        }
        this.baselineParams = BaselineCorrectionParams.fromJson(json.optJSONObject("baselineCorrectionParams"));
        this.numShots = json.optInt("numShots");
        this.sourceFile = json.optString("sourceFile");
        this.referenceComposition = json.optString("referenceComposition");
    }
}