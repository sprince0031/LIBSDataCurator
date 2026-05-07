package com.medals.libsdatagenerator.model;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class InstrumentProfile implements JsonModel {
    private String instrumentName;
    private double[] wavelengths;
    private PlasmaParameters plasmaParameters;
    private CalibrationStats calibrationStats;
    private BaselineCorrectionParams baselineParams;
    private int numShots;
    private String sourceFile;
    private String referenceComposition;
    private double scaleFactor; // Max intensity of averaged measured spectrum
    public static final String INSTRUMENT_PROFILE_PATH = CommonUtils.CONF_PATH + File.separator +
            LIBSDataGenConstants.INSTRUMENT_PROFILE_JSON_FILE;

    // Constructor to load profile from JSON
    public InstrumentProfile() {}

    // Constructor used in tests and spec
    public InstrumentProfile(String instrumentName, double[] wavelengths, PlasmaParameters plasmaParameters,
            CalibrationStats calibrationStats, BaselineCorrectionParams baselineParams) {
        this.instrumentName = instrumentName;
        this.wavelengths = wavelengths;
        this.plasmaParameters = plasmaParameters;
        this.calibrationStats = calibrationStats;
        this.baselineParams = baselineParams;
    }

    // Constructor used in InstrumentProfileService
    public InstrumentProfile(double[] wavelengths, String sourceFile, String referenceComposition) {
        this.wavelengths = wavelengths;
        this.sourceFile = sourceFile;
        this.referenceComposition = referenceComposition;
        this.baselineParams = new BaselineCorrectionParams();
        this.plasmaParameters = new PlasmaParameters(new ArrayList<>());
        this.calibrationStats = new CalibrationStats(0, 0);
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

    public PlasmaParameters getPlasmaParameters() {
        return plasmaParameters;
    }

    public void setPlasmaParameters(PlasmaParameters plasmaParameters) {
        this.plasmaParameters = plasmaParameters;
    }

    public CalibrationStats getCalibrationStats() {
        return calibrationStats;
    }

    public void setCalibrationStats(CalibrationStats calibrationStats) {
        this.calibrationStats = calibrationStats;
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

    public double getScaleFactor() {
        return scaleFactor;
    }

    public void setScaleFactor(double scaleFactor) {
        this.scaleFactor = scaleFactor;
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

    public List<PlasmaZone> getZones() {
        return plasmaParameters.getZones();
    }

    public void setZones(List<PlasmaZone> zones) {
        this.plasmaParameters.setZones(zones);
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
        json.put("instrumentName", instrumentName);
        JSONArray wavelengthGrid = new JSONArray();
        if (wavelengths != null) {
            for (double d : wavelengths) {
                wavelengthGrid.put(d);
            }
        }
        json.put("wavelengths", wavelengthGrid);
        json.put("numShots", numShots);
        json.put("sourceFile", sourceFile);
        json.put("referenceComposition", referenceComposition);
        json.put("scaleFactor", scaleFactor);
        if (plasmaParameters != null) {
            json.put("plasmaParameters", plasmaParameters.toJson());
        }
        if (calibrationStats != null) {
            json.put("calibrationStats", calibrationStats.toJson());
        }
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

        this.plasmaParameters = PlasmaParameters.fromJson(json.optJSONObject("plasmaParameters"));
        this.calibrationStats = CalibrationStats.fromJson(json.optJSONObject("calibrationStats"));
        this.baselineParams = BaselineCorrectionParams.fromJson(json.optJSONObject("baselineCorrectionParams"));
        this.numShots = json.optInt("numShots");
        this.sourceFile = json.optString("sourceFile");
        this.referenceComposition = json.optString("referenceComposition");
        this.scaleFactor = json.optDouble("scaleFactor");
    }
}