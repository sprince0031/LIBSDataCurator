package com.medals.libsdatagenerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class VoigtProfile {

    @JsonProperty("nistResolvingPower")
    private double nistResolvingPower = 5000.0;

    @JsonProperty("fwhmGaussianNm")
    private List<Double> fwhmGaussianNm = new ArrayList<>();

    @JsonProperty("fwhmLorentzianNm")
    private List<Double> fwhmLorentzianNm = new ArrayList<>();

    public VoigtProfile() {}

    public VoigtProfile(double nistResolvingPower, List<Double> fwhmGaussianNm, List<Double> fwhmLorentzianNm) {
        this.nistResolvingPower = nistResolvingPower;
        this.fwhmGaussianNm = fwhmGaussianNm != null ? fwhmGaussianNm : new ArrayList<>();
        this.fwhmLorentzianNm = fwhmLorentzianNm != null ? fwhmLorentzianNm : new ArrayList<>();
    }

    public double getNistResolvingPower() {
        return nistResolvingPower;
    }

    public void setNistResolvingPower(double nistResolvingPower) {
        this.nistResolvingPower = nistResolvingPower;
    }

    public List<Double> getFwhmGaussianNm() {
        return fwhmGaussianNm;
    }

    public void setFwhmGaussianNm(List<Double> fwhmGaussianNm) {
        this.fwhmGaussianNm = fwhmGaussianNm;
    }

    public List<Double> getFwhmLorentzianNm() {
        return fwhmLorentzianNm;
    }

    public void setFwhmLorentzianNm(List<Double> fwhmLorentzianNm) {
        this.fwhmLorentzianNm = fwhmLorentzianNm;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("nistResolvingPower", nistResolvingPower);
        JSONArray gArr = new JSONArray();
        if (fwhmGaussianNm != null) {
            for (Double v : fwhmGaussianNm) {
                gArr.put(v);
            }
        }
        json.put("fwhmGaussianNm", gArr);
        JSONArray lArr = new JSONArray();
        if (fwhmLorentzianNm != null) {
            for (Double v : fwhmLorentzianNm) {
                lArr.put(v);
            }
        }
        json.put("fwhmLorentzianNm", lArr);
        return json;
    }

    public static VoigtProfile fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        VoigtProfile vp = new VoigtProfile();
        vp.setNistResolvingPower(json.optDouble("nistResolvingPower", 5000.0));
        JSONArray gArr = json.optJSONArray("fwhmGaussianNm");
        if (gArr != null) {
            List<Double> gList = new ArrayList<>();
            for (int i = 0; i < gArr.length(); i++) {
                gList.add(gArr.getDouble(i));
            }
            vp.setFwhmGaussianNm(gList);
        }
        JSONArray lArr = json.optJSONArray("fwhmLorentzianNm");
        if (lArr != null) {
            List<Double> lList = new ArrayList<>();
            for (int i = 0; i < lArr.length(); i++) {
                lList.add(lArr.getDouble(i));
            }
            vp.setFwhmLorentzianNm(lList);
        }
        return vp;
    }
}
