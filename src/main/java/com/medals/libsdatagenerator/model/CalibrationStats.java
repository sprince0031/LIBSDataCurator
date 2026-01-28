package com.medals.libsdatagenerator.model;

import org.json.JSONObject;

public class CalibrationStats {
    private double rSquared;
    private double rmse;
    private double mse;

    public CalibrationStats(double rSquared, double rmse, double mse) {
        this.rSquared = rSquared;
        this.rmse = rmse;
        this.mse = mse;
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

    public double getMse() {
        return mse;
    }

    public void setMse(double mse) {
        this.mse = mse;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("rSquared", rSquared);
        json.put("rmse", rmse);
        json.put("mse", mse);
        return json;
    }

    public static CalibrationStats fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new CalibrationStats(
                json.optDouble("rSquared", 0.0),
                json.optDouble("rmse", 0.0),
                json.optDouble("mse", 0.0)
        );
    }
}
