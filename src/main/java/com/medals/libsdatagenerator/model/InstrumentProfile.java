package com.medals.libsdatagenerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InstrumentProfile implements JsonModel {

    @JsonProperty("instrumentName")
    private String instrumentName;

    @JsonProperty("wavelengths")
    private double[] wavelengths;

    @JsonProperty("spectralResponse")
    private double[] spectralResponse;

    @JsonProperty("finotelloNoiseBeta")
    private double finotelloNoiseBeta;

    @JsonProperty("materialFamilyProfiles")
    private Map<String, MaterialFamilyProfile> materialFamilyProfiles = new HashMap<>();

    @JsonProperty("baselineCorrectionParams")
    private BaselineCorrectionParams baselineParams = new BaselineCorrectionParams();

    @JsonProperty("numShots")
    private int numShots;

    @JsonProperty("sourceFile")
    private String sourceFile;

    @JsonProperty("referenceComposition")
    private String referenceComposition;

    @JsonProperty("emissionScale")
    private String emissionScale = "absolute";

    @JsonProperty("lineSpreadAddSigmaNm")
    private java.util.List<Double> lineSpreadAddSigmaNm;

    @JsonProperty("lineSpreadFwhmNm")
    private java.util.List<Double> lineSpreadFwhmNm;

    @JsonProperty("spectralResponseValidRangeNm")
    private java.util.List<Double> spectralResponseValidRangeNm;

    @JsonProperty("validChannelRangeNm")
    private java.util.List<Double> validChannelRangeNm;

    public static final String INSTRUMENT_PROFILE_PATH = CommonUtils.CONF_PATH + File.separator +
            LIBSDataGenConstants.INSTRUMENT_PROFILE_JSON_FILE;

    // Constructor to load profile from JSON
    public InstrumentProfile() {}

    // Constructor used in tests and spec
    public InstrumentProfile(String instrumentName, double[] wavelengths,
                             Map<String, MaterialFamilyProfile> materialFamilyProfiles, BaselineCorrectionParams baselineParams) {
        this.instrumentName = instrumentName;
        this.wavelengths = wavelengths;
        this.materialFamilyProfiles = materialFamilyProfiles != null ? materialFamilyProfiles : new HashMap<>();
        this.baselineParams = baselineParams != null ? baselineParams : new BaselineCorrectionParams();
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

    public double[] getWavelengths() {
        return wavelengths;
    }

    public void setWavelengths(double[] wavelengths) {
        this.wavelengths = wavelengths;
    }

    public double[] getSpectralResponse() {
        return spectralResponse;
    }

    public void setSpectralResponse(double[] spectralResponse) {
        this.spectralResponse = spectralResponse;
    }

    public double getFinotelloNoiseBeta() {
        return finotelloNoiseBeta;
    }

    public void setFinotelloNoiseBeta(double finotelloNoiseBeta) {
        this.finotelloNoiseBeta = finotelloNoiseBeta;
    }

    public Map<String, MaterialFamilyProfile> getMaterialFamilyProfiles() {
        return materialFamilyProfiles;
    }

    public void setMaterialFamilyProfiles(Map<String, MaterialFamilyProfile> profiles) {
        this.materialFamilyProfiles = profiles != null ? profiles : new HashMap<>();
    }

    public MaterialFamilyProfile getMaterialFamilyProfile(String familyName) {
        return materialFamilyProfiles != null ? materialFamilyProfiles.get(familyName) : null;
    }

    public void addMaterialFamilyProfile(MaterialFamilyProfile materialFamilyProfile) {
        if (this.materialFamilyProfiles == null) {
            this.materialFamilyProfiles = new HashMap<>();
        }
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
    @Deprecated
    public double getLambda() {
        if (baselineParams == null)
            return 0;
        return baselineParams.getLambda();
    }

    @Deprecated
    public double getP() {
        if (baselineParams == null)
            return 0;
        return baselineParams.getP();
    }

    @Deprecated
    public int getMaxIterations() {
        if (baselineParams == null)
            return 0;
        return baselineParams.getMaxIterations();
    }

    public String getEmissionScale() {
        return emissionScale != null ? emissionScale : "absolute";
    }

    public void setEmissionScale(String emissionScale) {
        this.emissionScale = emissionScale;
    }

    public java.util.List<Double> getLineSpreadAddSigmaNm() {
        return lineSpreadAddSigmaNm;
    }

    public void setLineSpreadAddSigmaNm(java.util.List<Double> lineSpreadAddSigmaNm) {
        this.lineSpreadAddSigmaNm = lineSpreadAddSigmaNm;
    }

    public java.util.List<Double> getLineSpreadFwhmNm() {
        return lineSpreadFwhmNm;
    }

    public void setLineSpreadFwhmNm(java.util.List<Double> lineSpreadFwhmNm) {
        this.lineSpreadFwhmNm = lineSpreadFwhmNm;
    }

    public java.util.List<Double> getSpectralResponseValidRangeNm() {
        return spectralResponseValidRangeNm;
    }

    public void setSpectralResponseValidRangeNm(java.util.List<Double> spectralResponseValidRangeNm) {
        this.spectralResponseValidRangeNm = spectralResponseValidRangeNm;
    }

    public java.util.List<Double> getValidChannelRangeNm() {
        return validChannelRangeNm;
    }

    public void setValidChannelRangeNm(java.util.List<Double> validChannelRangeNm) {
        this.validChannelRangeNm = validChannelRangeNm;
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

        if (spectralResponse != null) {
            JSONArray srArr = new JSONArray();
            for (double d : spectralResponse) {
                srArr.put(d);
            }
            json.put("spectralResponse", srArr);
        }

        json.put("finotelloNoiseBeta", finotelloNoiseBeta);

        JSONObject materialFamilyProfilesJson = new JSONObject();
        if (materialFamilyProfiles != null) {
            for (String key : materialFamilyProfiles.keySet()) {
                materialFamilyProfilesJson.put(key, materialFamilyProfiles.get(key).toJson());
            }
        }
        json.put("materialFamilyProfiles", materialFamilyProfilesJson);

        json.put("numShots", numShots);
        json.put("sourceFile", sourceFile);
        json.put("referenceComposition", referenceComposition);
        if (baselineParams != null) {
            json.put("baselineCorrectionParams", baselineParams.toJson());
        }

        if (emissionScale != null) {
            json.put("emissionScale", emissionScale);
        }
        if (lineSpreadAddSigmaNm != null) {
            json.put("lineSpreadAddSigmaNm", new JSONArray(lineSpreadAddSigmaNm));
        }
        if (lineSpreadFwhmNm != null) {
            json.put("lineSpreadFwhmNm", new JSONArray(lineSpreadFwhmNm));
        }
        if (spectralResponseValidRangeNm != null) {
            json.put("spectralResponseValidRangeNm", new JSONArray(spectralResponseValidRangeNm));
        }
        if (validChannelRangeNm != null) {
            json.put("validChannelRangeNm", new JSONArray(validChannelRangeNm));
        }
        return json;
    }

    @Override
    public void fromJson(JSONObject json) {
        if (json == null) return;
        this.instrumentName = json.optString("instrumentName", "");
        JSONArray wavelengthsArray = json.optJSONArray("wavelengths");
        this.wavelengths = new double[wavelengthsArray != null ? wavelengthsArray.length() : 0];
        if (wavelengthsArray != null) {
            for (int i = 0; i < wavelengthsArray.length(); i++) {
                wavelengths[i] = wavelengthsArray.getDouble(i);
            }
        }

        JSONArray srArray = json.optJSONArray("spectralResponse");
        if (srArray != null) {
            this.spectralResponse = new double[srArray.length()];
            for (int i = 0; i < srArray.length(); i++) {
                this.spectralResponse[i] = srArray.getDouble(i);
            }
        }

        this.finotelloNoiseBeta = json.optDouble("finotelloNoiseBeta", 0.0);

        JSONObject materialFamilyProfilesJson = json.optJSONObject("materialFamilyProfiles");
        this.materialFamilyProfiles = new HashMap<>();
        if (materialFamilyProfilesJson != null) {
            for (String key : materialFamilyProfilesJson.keySet()) {
                MaterialFamilyProfile materialFamilyProfile = new MaterialFamilyProfile(key);
                materialFamilyProfile.fromJson(materialFamilyProfilesJson.optJSONObject(key));
                this.materialFamilyProfiles.put(key, materialFamilyProfile);
            }
        }
        this.baselineParams = BaselineCorrectionParams.fromJson(json.optJSONObject("baselineCorrectionParams"));
        this.numShots = json.optInt("numShots");
        this.sourceFile = json.optString("sourceFile");
        this.referenceComposition = json.optString("referenceComposition");

        this.emissionScale = json.optString("emissionScale", "absolute");
        if (this.emissionScale.isEmpty()) {
            this.emissionScale = "absolute";
        }
        JSONArray addSigmaArr = json.optJSONArray("lineSpreadAddSigmaNm");
        if (addSigmaArr != null) {
            this.lineSpreadAddSigmaNm = new java.util.ArrayList<>();
            for (int i = 0; i < addSigmaArr.length(); i++) {
                this.lineSpreadAddSigmaNm.add(addSigmaArr.getDouble(i));
            }
        }
        JSONArray fwhmArr = json.optJSONArray("lineSpreadFwhmNm");
        if (fwhmArr != null) {
            this.lineSpreadFwhmNm = new java.util.ArrayList<>();
            for (int i = 0; i < fwhmArr.length(); i++) {
                this.lineSpreadFwhmNm.add(fwhmArr.getDouble(i));
            }
        }
        JSONArray respRangeArr = json.optJSONArray("spectralResponseValidRangeNm");
        if (respRangeArr != null) {
            this.spectralResponseValidRangeNm = new java.util.ArrayList<>();
            for (int i = 0; i < respRangeArr.length(); i++) {
                this.spectralResponseValidRangeNm.add(respRangeArr.getDouble(i));
            }
        }
        JSONArray chanRangeArr = json.optJSONArray("validChannelRangeNm");
        if (chanRangeArr != null) {
            this.validChannelRangeNm = new java.util.ArrayList<>();
            for (int i = 0; i < chanRangeArr.length(); i++) {
                this.validChannelRangeNm.add(chanRangeArr.getDouble(i));
            }
        }
    }
}
