package com.medals.libsdatagenerator.model;

import org.json.JSONObject;

public interface JsonModel {

    JSONObject toJson();

    void fromJson(JSONObject json);

}
