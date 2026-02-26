package com.medals.libsdatagenerator.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class PlasmaParameters {
    private List<PlasmaZone> zones;

    public PlasmaParameters(List<PlasmaZone> zones) {
        this.zones = zones;
    }

    public List<PlasmaZone> getZones() {
        return zones;
    }

    public void setZones(List<PlasmaZone> zones) {
        this.zones = zones;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        JSONArray zonesArray = new JSONArray();
        if (zones != null) {
            for (PlasmaZone zone : zones) {
                zonesArray.put(zone.toJson());
            }
        }
        json.put("zones", zonesArray);
        return json;
    }

    public static PlasmaParameters fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        List<PlasmaZone> zones = new ArrayList<>();
        JSONArray zonesArray = json.optJSONArray("zones");
        if (zonesArray != null) {
            for (int i = 0; i < zonesArray.length(); i++) {
                zones.add(PlasmaZone.fromJson(zonesArray.getJSONObject(i)));
            }
        }
        return new PlasmaParameters(zones);
    }
}
