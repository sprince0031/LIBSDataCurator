package com.medals.libsdatagenerator.model;

import org.json.JSONObject;

public class PlasmaParameters {
    private PlasmaZone hotCore;
    private PlasmaZone coolPeriphery;

    public PlasmaParameters(PlasmaZone hotCore, PlasmaZone coolPeriphery) {
        this.hotCore = hotCore;
        this.coolPeriphery = coolPeriphery;
    }

    public PlasmaZone getHotCore() {
        return hotCore;
    }

    public void setHotCore(PlasmaZone hotCore) {
        this.hotCore = hotCore;
    }

    public PlasmaZone getCoolPeriphery() {
        return coolPeriphery;
    }

    public void setCoolPeriphery(PlasmaZone coolPeriphery) {
        this.coolPeriphery = coolPeriphery;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        if (hotCore != null) {
            json.put("hotCore", hotCore.toJson());
        }
        if (coolPeriphery != null) {
            json.put("coolPeriphery", coolPeriphery.toJson());
        }
        return json;
    }

    public static PlasmaParameters fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        PlasmaZone hotCore = PlasmaZone.fromJson(json.optJSONObject("hotCore"));
        PlasmaZone coolPeriphery = PlasmaZone.fromJson(json.optJSONObject("coolPeriphery"));
        return new PlasmaParameters(hotCore, coolPeriphery);
    }
}
