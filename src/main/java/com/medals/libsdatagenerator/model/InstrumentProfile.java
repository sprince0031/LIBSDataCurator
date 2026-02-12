package com.medals.libsdatagenerator.model;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;

public class InstrumentProfile {
    private String instrumentName;
    private double[] wavelengths;
    private PlasmaParameters plasmaParameters;
    private CalibrationStats calibrationStats;
    private BaselineCorrectionParams baselineParams;
    private int numShots;
    private String sourceFile;
    private String referenceComposition;

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
        this.plasmaParameters = new PlasmaParameters(new PlasmaZone(0, 0), new PlasmaZone(0, 0));
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

    public double getMinWavelength() {
        if (wavelengths == null || wavelengths.length == 0) return 0.0;
        return wavelengths[0];
    }

    public double getMaxWavelength() {
        if (wavelengths == null || wavelengths.length == 0) return 0.0;
        return wavelengths[wavelengths.length - 1];
    }

    // Delegation methods for PlasmaParameters
    public void setHotCoreNe(double ne) {
        plasmaParameters.getHotCore().setNe(ne);
    }

    public double getHotCoreWeight() {
        return plasmaParameters.getHotCore().getWeight();
    }

    public void setHotCoreWeight(double weight) {
        plasmaParameters.getHotCore().setWeight(weight);
    }

    public double getCoolPeripheryTe() {
        return plasmaParameters.getCoolPeriphery().getTe();
    }

    public void setCoolPeripheryTe(double te) {
        plasmaParameters.getCoolPeriphery().setTe(te);
    }

    public double getCoolPeripheryNe() {
        return plasmaParameters.getCoolPeriphery().getNe();
    }

    public void setCoolPeripheryNe(double ne) {
        plasmaParameters.getCoolPeriphery().setNe(ne);
    }

    public double getCoolPeripheryWeight() {
        return plasmaParameters.getCoolPeriphery().getWeight();
    }
    
    public void setCoolPeripheryWeight(double weight) {
        plasmaParameters.getCoolPeriphery().setWeight(weight);
    }

    public double getHotCoreTe() {
        return plasmaParameters.getHotCore().getTe();
    }

    public void setHotCoreTe(double te) {
        plasmaParameters.getHotCore().setTe(te);
    }

    public double getHotCoreNe() {
        return plasmaParameters.getHotCore().getNe();
    }

    // Delegation for Baseline Correction Params
    public double getLambda() {
        return baselineParams.getLambda();
    }

    public double getP() {
        return baselineParams.getP();
    }

    public int getMaxIterations() {
        return baselineParams.getMaxIterations();
    }

    // Delegation for CalibrationStats
    public double getRSquaredValue() {
        return calibrationStats.getRSquared();
    }

    public void setRSquaredValue(double score) {
        calibrationStats.setRSquared(score);
    }

    public double getRmse() {
        return calibrationStats.getRmse();
    }

    public void setRmse(double rmse) {
        calibrationStats.setRmse(rmse);
    }

    public void saveToFile(Path path) throws IOException {
        try (FileWriter writer = new FileWriter(path.toFile())) {
            writer.write(this.toJson().toString(2));
        }
    }

    public static InstrumentProfile loadFromFile(Path path) throws IOException {
        try (FileReader reader = new FileReader(path.toFile())) {
            JSONTokener tokener = new JSONTokener(reader);
            JSONObject json = new JSONObject(tokener);
            return fromJson(json);
        }
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("instrumentName", instrumentName);
        JSONArray wavelengthGrid =  new JSONArray();
        wavelengthGrid.putAll(wavelengths);
        json.put("wavelengths", wavelengthGrid);
        json.put("numShots", numShots);
        json.put("sourceFile", sourceFile);
        json.put("referenceComposition", referenceComposition);
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

    public static InstrumentProfile fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        String name = json.optString("instrumentName", "");
        
        JSONArray wavelengthsArray = json.optJSONArray("wavelengths");
        double[] wavelengths = new double[wavelengthsArray.length()];
        if (!wavelengthsArray.isEmpty()) {
            for (int i = 0; i < wavelengthsArray.length(); i++) {
                wavelengths[i] = wavelengthsArray.getDouble(i);
            }
        }

        PlasmaParameters params = PlasmaParameters.fromJson(json.optJSONObject("plasmaParameters"));
        CalibrationStats stats = CalibrationStats.fromJson(json.optJSONObject("calibrationStats"));
        BaselineCorrectionParams baselineParams =  BaselineCorrectionParams.fromJson(json.optJSONObject("baselineCorrectionParams"));
        
        InstrumentProfile profile = new InstrumentProfile(name, wavelengths, params, stats, baselineParams);
        profile.setNumShots(json.optInt("numShots"));
        profile.sourceFile = json.optString("sourceFile");
        profile.referenceComposition = json.optString("referenceComposition");
        
        return profile;
    }
}