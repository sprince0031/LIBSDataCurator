package com.medals.libsdatagenerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.json.JSONObject;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BaselineCorrectionParams {

    public static final String DEFAULT_METHOD = "morphological_opening";
    public static final int DEFAULT_STRUCTURING_ELEMENT_SIZE = 65;

    @JsonProperty("method")
    private String method = DEFAULT_METHOD;

    @JsonProperty("structuringElementSize")
    private int structuringElementSize = DEFAULT_STRUCTURING_ELEMENT_SIZE;

    // Deprecated legacy ALS fields maintained for backward compatibility
    @Deprecated
    public static final double DEFAULT_LAMBDA = 10000;
    @Deprecated
    public static final double DEFAULT_P = 0.001;
    @Deprecated
    public static final int MAX_ITERATIONS = 10;

    @Deprecated
    private double lambda = DEFAULT_LAMBDA;
    @Deprecated
    private double p = DEFAULT_P;
    @Deprecated
    private int maxIterations = MAX_ITERATIONS;

    public BaselineCorrectionParams() {
        this.method = DEFAULT_METHOD;
        this.structuringElementSize = DEFAULT_STRUCTURING_ELEMENT_SIZE;
    }

    public BaselineCorrectionParams(String method, int structuringElementSize) {
        this.method = method;
        this.structuringElementSize = structuringElementSize;
    }

    @Deprecated
    public BaselineCorrectionParams(double lambda, double p, int maxIterations) {
        this.method = DEFAULT_METHOD;
        this.structuringElementSize = DEFAULT_STRUCTURING_ELEMENT_SIZE;
        this.lambda = lambda;
        this.p = p;
        this.maxIterations = maxIterations;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public int getStructuringElementSize() {
        return structuringElementSize;
    }

    public void setStructuringElementSize(int structuringElementSize) {
        this.structuringElementSize = structuringElementSize;
    }

    @Deprecated
    public double getLambda() {
        return lambda;
    }

    @Deprecated
    public void setLambda(double lambda) {
        this.lambda = lambda;
    }

    @Deprecated
    public double getP() {
        return p;
    }

    @Deprecated
    public void setP(double p) {
        this.p = p;
    }

    @Deprecated
    public int getMaxIterations() {
        return maxIterations;
    }

    @Deprecated
    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations;
    }

    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("method", method);
        json.put("structuringElementSize", structuringElementSize);
        return json;
    }

    public static BaselineCorrectionParams fromJson(JSONObject json) {
        if (json == null) {
            return null;
        }
        BaselineCorrectionParams params = new BaselineCorrectionParams();
        params.setMethod(json.optString("method", DEFAULT_METHOD));
        params.setStructuringElementSize(json.optInt("structuringElementSize", DEFAULT_STRUCTURING_ELEMENT_SIZE));
        if (json.has("lambda")) {
            params.setLambda(json.optDouble("lambda", DEFAULT_LAMBDA));
        }
        if (json.has("p")) {
            params.setP(json.optDouble("p", DEFAULT_P));
        }
        if (json.has("maxIterations")) {
            params.setMaxIterations(json.optInt("maxIterations", MAX_ITERATIONS));
        }
        return params;
    }
}
