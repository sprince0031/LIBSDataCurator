package com.medals.libsdatagenerator.model;

import org.json.JSONObject;

public class PlasmaZone {
    private double te;
    private double ne;
    private double vFraction;
    private double kAbsorption;

    public PlasmaZone(double te, double ne) {
        this(te, ne, 0.0, 2.5);
    }

    public PlasmaZone(double te, double ne, double vFraction, double kAbsorption) {
        this.te = te;
        this.ne = ne;
        this.vFraction = vFraction;
        this.kAbsorption = kAbsorption;
    }

    public double getTe() {
        return te;
    }

    public void setTe(double te) {
        this.te = te;
    }

    public double getNe() {
        return ne;
    }

    public void setNe(double ne) {
        this.ne = ne;
    }

    public double getVFraction() {
        return vFraction;
    }

    public void setVFraction(double vFraction) {
        this.vFraction = vFraction;
    }

    public double getKAbsorption() {
        return kAbsorption;
    }

    public void setKAbsorption(double kAbsorption) {
        this.kAbsorption = kAbsorption;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("Te", te);
        json.put("Ne", ne);
        json.put("weight", vFraction);
        return json;
    }

    public static PlasmaZone fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new PlasmaZone(
                json.getDouble("Te"),
                json.getDouble("Ne"),
                json.optDouble("weight", 0.0), 2.5);
    }
}