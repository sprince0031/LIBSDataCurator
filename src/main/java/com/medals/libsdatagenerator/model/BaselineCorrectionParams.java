package com.medals.libsdatagenerator.model;

import org.json.JSONObject;

public class BaselineCorrectionParams {

    private double lambda;
    private double p;
    private int maxIterations;

    // Default parameters for ALS
    public static final double DEFAULT_LAMBDA = 10000; // 10^4 to 10^5 usually good
    public static final double DEFAULT_P = 0.001;
    public static final int MAX_ITERATIONS = 10;

    public BaselineCorrectionParams() {
        this.lambda = DEFAULT_LAMBDA;
        this.p = DEFAULT_P;
        this.maxIterations = MAX_ITERATIONS;
    }

    public BaselineCorrectionParams(double lambda, double p, int maxIterations) {
        this.lambda = lambda;
        this.p = p;
        this.maxIterations = maxIterations;
    }

    public double getLambda() {
        return lambda;
    }

    public void setLambda(double lambda) {
        this.lambda = lambda;
    }

    public double getP() {
        return p;
    }

    public void setP(double p) {
        this.p = p;
    }

    public int getMaxIterations() {
        return maxIterations;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("lambda", lambda);
        json.put("p", p);
        json.put("maxIterations", maxIterations);
        return json;
    }

    public static BaselineCorrectionParams fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        return new BaselineCorrectionParams(
                json.optDouble("lambda", DEFAULT_LAMBDA),
                json.optDouble("p", DEFAULT_P),
                json.optInt("maxIterations", MAX_ITERATIONS)
        );
    }
}
