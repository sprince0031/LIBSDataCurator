package com.medals.libsdatagenerator.model;

import org.json.JSONObject;

public class CalibrationStats {
    private double rSquared;
    private double rmse;

    public CalibrationStats(double rSquared, double rmse) {
        this.rSquared = rSquared;
        this.rmse = rmse;
    }

    public double getRSquared() {
        return rSquared;
    }

    public void setRSquared(double rSquared) {
        this.rSquared = rSquared;
    }

    public double getRmse() {
        return rmse;
    }

    public void setRmse(double rmse) {
        this.rmse = rmse;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("rSquared", rSquared);
        json.put("rmse", rmse);
        return json;
    }

    public static CalibrationStats fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new CalibrationStats(
                json.optDouble("rSquared", 0.0),
                json.optDouble("rmse", 0.0)
        );
    }
}
