package com.medals.libsdatagenerator.model;

import org.json.JSONObject;

public class PlasmaZone {
    private double te;
    private double ne;
    private double weight;

    public PlasmaZone(double te, double ne) {
        this(te, ne, 0.0);
    }

    public PlasmaZone(double te, double ne, double weight) {
        this.te = te;
        this.ne = ne;
        this.weight = weight;
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

    public double getWeight() {
        return weight;
    }

    public void setWeight(double weight) {
        this.weight = weight;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("Te", te);
        json.put("Ne", ne);
        json.put("weight", weight);
        return json;
    }

    public static PlasmaZone fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new PlasmaZone(
                json.getDouble("Te"),
                json.getDouble("Ne"),
                json.optDouble("weight", 0.0));
    }
}