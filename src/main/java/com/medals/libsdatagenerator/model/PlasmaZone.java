package com.medals.libsdatagenerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.json.JSONObject;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PlasmaZone {

    @JsonProperty("zoneIndex")
    private int zoneIndex;

    @JsonProperty("Te")
    private double te;

    @JsonProperty("teStdDev")
    private double teStdDev = 0.05;

    @JsonProperty("Ne")
    private double ne;

    @JsonProperty("neLogStdDev")
    private double neLogStdDev = 0.08;

    @JsonProperty("volumeFraction")
    private double volumeFraction;

    @JsonProperty("kAbsorption")
    private double kAbsorption;

    public PlasmaZone() {}

    public PlasmaZone(PlasmaZone other) {
        this.zoneIndex = other.zoneIndex;
        this.te = other.te;
        this.teStdDev = other.teStdDev;
        this.ne = other.ne;
        this.neLogStdDev = other.neLogStdDev;
        this.volumeFraction = other.volumeFraction;
        this.kAbsorption = other.kAbsorption;
    }

    public PlasmaZone(double te, double ne) {
        this(0, te, 0.05, ne, 0.08, 0.0, 2.5);
    }

    public PlasmaZone(double te, double ne, double volumeFraction, double kAbsorption) {
        this(0, te, 0.05, ne, 0.08, volumeFraction, kAbsorption);
    }

    public PlasmaZone(int zoneIndex, double te, double teStdDev, double ne, double neLogStdDev,
                      double volumeFraction, double kAbsorption) {
        this.zoneIndex = zoneIndex;
        this.te = te;
        this.teStdDev = teStdDev;
        this.ne = ne;
        this.neLogStdDev = neLogStdDev;
        this.volumeFraction = volumeFraction;
        this.kAbsorption = kAbsorption;
    }

    public int getZoneIndex() {
        return zoneIndex;
    }

    public void setZoneIndex(int zoneIndex) {
        this.zoneIndex = zoneIndex;
    }

    public double getTe() {
        return te;
    }

    public void setTe(double te) {
        this.te = te;
    }

    public double getTeStdDev() {
        return teStdDev;
    }

    public void setTeStdDev(double teStdDev) {
        this.teStdDev = teStdDev;
    }

    public double getNe() {
        return ne;
    }

    public void setNe(double ne) {
        this.ne = ne;
    }

    public double getNeLogStdDev() {
        return neLogStdDev;
    }

    public void setNeLogStdDev(double neLogStdDev) {
        this.neLogStdDev = neLogStdDev;
    }

    public double getVolumeFraction() {
        return volumeFraction;
    }

    public void setVolumeFraction(double volumeFraction) {
        this.volumeFraction = volumeFraction;
    }

    // Alias for getVolumeFraction for compatibility
    public double getVFraction() {
        return volumeFraction;
    }

    public void setVFraction(double vFraction) {
        this.volumeFraction = vFraction;
    }

    public double getKAbsorption() {
        return kAbsorption;
    }

    public void setKAbsorption(double kAbsorption) {
        this.kAbsorption = kAbsorption;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("zoneIndex", zoneIndex);
        json.put("Te", te);
        json.put("teStdDev", teStdDev);
        json.put("Ne", ne);
        json.put("neLogStdDev", neLogStdDev);
        json.put("volumeFraction", volumeFraction);
        json.put("kAbsorption", kAbsorption);
        return json;
    }

    public static PlasmaZone fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        PlasmaZone zone = new PlasmaZone();
        zone.setZoneIndex(json.optInt("zoneIndex", 0));
        zone.setTe(json.getDouble("Te"));
        zone.setTeStdDev(json.optDouble("teStdDev", 0.05));
        zone.setNe(json.getDouble("Ne"));
        zone.setNeLogStdDev(json.optDouble("neLogStdDev", 0.08));
        double vf = json.has("volumeFraction") ? json.getDouble("volumeFraction") : json.optDouble("vFraction", 0.0);
        zone.setVolumeFraction(vf);
        zone.setKAbsorption(json.optDouble("kAbsorption", 2.5));
        return zone;
    }
}
