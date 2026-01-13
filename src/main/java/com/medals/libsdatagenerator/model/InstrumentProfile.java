package com.medals.libsdatagenerator.model;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Model class representing an instrument profile for LIBS data calibration.
 * Contains wavelength grid and plasma parameters for two-zone temperature model.
 * 
 * @author Siddharth Prince | Generated for issue #84
 */
public class InstrumentProfile {

    // Metadata
    private String instrumentName;
    private String generatedAt;
    private String sampleFile;
    private String composition;
    private int numShots;

    // Wavelength grid
    private List<Double> wavelengthGrid;
    private double minWavelength;
    private double maxWavelength;
    private double wavelengthStep;

    // Two-zone plasma parameters
    // Hot core zone
    private double hotCoreTe;  // Plasma temperature in eV
    private double hotCoreNe;  // Electron density in cm^-3
    private double hotCoreWeight;  // Contribution weight (0.0 to 1.0)

    // Cool periphery zone  
    private double coolPeripheryTe;  // Plasma temperature in eV
    private double coolPeripheryNe;  // Electron density in cm^-3
    private double coolPeripheryWeight;  // Contribution weight (0.0 to 1.0)

    // Fit quality metrics
    private double fitScore;  // R-squared or similar metric
    private double rmse;  // Root mean square error

    /**
     * Default constructor with default plasma parameters.
     */
    public InstrumentProfile() {
        this.wavelengthGrid = new ArrayList<>();
        this.generatedAt = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        
        // Default two-zone parameters (typical LIBS plasma values)
        // Hot core: higher temperature, higher density
        this.hotCoreTe = 1.5;  // eV
        this.hotCoreNe = 1e17; // cm^-3
        this.hotCoreWeight = 0.4;
        
        // Cool periphery: lower temperature, lower density
        this.coolPeripheryTe = 0.8;  // eV
        this.coolPeripheryNe = 5e16; // cm^-3
        this.coolPeripheryWeight = 0.6;
    }

    /**
     * Creates an InstrumentProfile from extracted wavelength grid.
     * 
     * @param wavelengthGrid List of wavelengths from instrument
     * @param sampleFile Path to the sample CSV file
     * @param composition Composition string for the reference material
     */
    public InstrumentProfile(List<Double> wavelengthGrid, String sampleFile, String composition) {
        this();
        this.wavelengthGrid = new ArrayList<>(wavelengthGrid);
        this.sampleFile = sampleFile;
        this.composition = composition;
        
        if (!wavelengthGrid.isEmpty()) {
            this.minWavelength = wavelengthGrid.get(0);
            this.maxWavelength = wavelengthGrid.get(wavelengthGrid.size() - 1);
            if (wavelengthGrid.size() > 1) {
                this.wavelengthStep = wavelengthGrid.get(1) - wavelengthGrid.get(0);
            }
        }
    }

    /**
     * Converts this profile to a JSON object for serialization.
     * 
     * @return JSONObject representation of the profile
     */
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        
        // Metadata section
        JSONObject metadata = new JSONObject();
        metadata.put("instrumentName", instrumentName != null ? instrumentName : "Unknown");
        metadata.put("generatedAt", generatedAt);
        metadata.put("sampleFile", sampleFile != null ? sampleFile : "");
        metadata.put("composition", composition != null ? composition : "");
        metadata.put("numShots", numShots);
        json.put("metadata", metadata);
        
        // Wavelength grid section
        JSONObject wavelengthSection = new JSONObject();
        wavelengthSection.put("minWavelength", minWavelength);
        wavelengthSection.put("maxWavelength", maxWavelength);
        wavelengthSection.put("wavelengthStep", wavelengthStep);
        wavelengthSection.put("numPoints", wavelengthGrid.size());
        
        // Only store a sample of the grid for readability (first 10, last 10)
        JSONArray gridSample = new JSONArray();
        if (wavelengthGrid.size() <= 20) {
            for (Double w : wavelengthGrid) {
                gridSample.put(w);
            }
        } else {
            for (int i = 0; i < 10; i++) {
                gridSample.put(wavelengthGrid.get(i));
            }
            gridSample.put("...");
            for (int i = wavelengthGrid.size() - 10; i < wavelengthGrid.size(); i++) {
                gridSample.put(wavelengthGrid.get(i));
            }
        }
        wavelengthSection.put("gridSample", gridSample);
        json.put("wavelengthGrid", wavelengthSection);
        
        // Two-zone plasma parameters section
        JSONObject plasmaParams = new JSONObject();
        
        // Hot core zone
        JSONObject hotCore = new JSONObject();
        hotCore.put("plasmaTemperature_eV", hotCoreTe);
        hotCore.put("electronDensity_cm3", hotCoreNe);
        hotCore.put("weight", hotCoreWeight);
        plasmaParams.put("hotCore", hotCore);
        
        // Cool periphery zone
        JSONObject coolPeriphery = new JSONObject();
        coolPeriphery.put("plasmaTemperature_eV", coolPeripheryTe);
        coolPeriphery.put("electronDensity_cm3", coolPeripheryNe);
        coolPeriphery.put("weight", coolPeripheryWeight);
        plasmaParams.put("coolPeriphery", coolPeriphery);
        
        json.put("plasmaParameters", plasmaParams);
        
        // Fit quality section
        JSONObject fitQuality = new JSONObject();
        fitQuality.put("fitScore", fitScore);
        fitQuality.put("rmse", rmse);
        json.put("fitQuality", fitQuality);
        
        // Future enhancement placeholders
        JSONObject futureEnhancements = new JSONObject();
        futureEnhancements.put("voigtProfileEnabled", false);
        futureEnhancements.put("additionalZones", JSONObject.NULL);
        futureEnhancements.put("note", "Voigt profile and n-zone support planned for future releases");
        json.put("futureEnhancements", futureEnhancements);
        
        return json;
    }

    /**
     * Saves this profile to a JSON file.
     * 
     * @param outputPath Path to save the JSON file
     * @throws IOException if file cannot be written
     */
    public void saveToFile(Path outputPath) throws IOException {
        JSONObject json = toJson();
        Files.writeString(outputPath, json.toString(2));
    }

    /**
     * Loads an InstrumentProfile from a JSON file.
     * 
     * @param filePath Path to the JSON file
     * @return InstrumentProfile populated from file
     * @throws IOException if file cannot be read
     */
    public static InstrumentProfile loadFromFile(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        JSONObject json = new JSONObject(content);
        
        InstrumentProfile profile = new InstrumentProfile();
        
        // Parse metadata
        if (json.has("metadata")) {
            JSONObject metadata = json.getJSONObject("metadata");
            profile.setInstrumentName(metadata.optString("instrumentName", "Unknown"));
            profile.setGeneratedAt(metadata.optString("generatedAt", ""));
            profile.setSampleFile(metadata.optString("sampleFile", ""));
            profile.setComposition(metadata.optString("composition", ""));
            profile.setNumShots(metadata.optInt("numShots", 0));
        }
        
        // Parse wavelength grid info
        if (json.has("wavelengthGrid")) {
            JSONObject wavelengthSection = json.getJSONObject("wavelengthGrid");
            profile.setMinWavelength(wavelengthSection.optDouble("minWavelength", 0));
            profile.setMaxWavelength(wavelengthSection.optDouble("maxWavelength", 0));
            profile.setWavelengthStep(wavelengthSection.optDouble("wavelengthStep", 0));
        }
        
        // Parse plasma parameters
        if (json.has("plasmaParameters")) {
            JSONObject params = json.getJSONObject("plasmaParameters");
            
            if (params.has("hotCore")) {
                JSONObject hotCore = params.getJSONObject("hotCore");
                profile.setHotCoreTe(hotCore.optDouble("plasmaTemperature_eV", 1.5));
                profile.setHotCoreNe(hotCore.optDouble("electronDensity_cm3", 1e17));
                profile.setHotCoreWeight(hotCore.optDouble("weight", 0.4));
            }
            
            if (params.has("coolPeriphery")) {
                JSONObject coolPeriphery = params.getJSONObject("coolPeriphery");
                profile.setCoolPeripheryTe(coolPeriphery.optDouble("plasmaTemperature_eV", 0.8));
                profile.setCoolPeripheryNe(coolPeriphery.optDouble("electronDensity_cm3", 5e16));
                profile.setCoolPeripheryWeight(coolPeriphery.optDouble("weight", 0.6));
            }
        }
        
        // Parse fit quality
        if (json.has("fitQuality")) {
            JSONObject fitQuality = json.getJSONObject("fitQuality");
            profile.setFitScore(fitQuality.optDouble("fitScore", 0));
            profile.setRmse(fitQuality.optDouble("rmse", 0));
        }
        
        return profile;
    }

    // Getters and Setters

    public String getInstrumentName() {
        return instrumentName;
    }

    public void setInstrumentName(String instrumentName) {
        this.instrumentName = instrumentName;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(String generatedAt) {
        this.generatedAt = generatedAt;
    }

    public String getSampleFile() {
        return sampleFile;
    }

    public void setSampleFile(String sampleFile) {
        this.sampleFile = sampleFile;
    }

    public String getComposition() {
        return composition;
    }

    public void setComposition(String composition) {
        this.composition = composition;
    }

    public int getNumShots() {
        return numShots;
    }

    public void setNumShots(int numShots) {
        this.numShots = numShots;
    }

    public List<Double> getWavelengthGrid() {
        return wavelengthGrid;
    }

    public void setWavelengthGrid(List<Double> wavelengthGrid) {
        this.wavelengthGrid = wavelengthGrid;
    }

    public double getMinWavelength() {
        return minWavelength;
    }

    public void setMinWavelength(double minWavelength) {
        this.minWavelength = minWavelength;
    }

    public double getMaxWavelength() {
        return maxWavelength;
    }

    public void setMaxWavelength(double maxWavelength) {
        this.maxWavelength = maxWavelength;
    }

    public double getWavelengthStep() {
        return wavelengthStep;
    }

    public void setWavelengthStep(double wavelengthStep) {
        this.wavelengthStep = wavelengthStep;
    }

    public double getHotCoreTe() {
        return hotCoreTe;
    }

    public void setHotCoreTe(double hotCoreTe) {
        this.hotCoreTe = hotCoreTe;
    }

    public double getHotCoreNe() {
        return hotCoreNe;
    }

    public void setHotCoreNe(double hotCoreNe) {
        this.hotCoreNe = hotCoreNe;
    }

    public double getHotCoreWeight() {
        return hotCoreWeight;
    }

    public void setHotCoreWeight(double hotCoreWeight) {
        this.hotCoreWeight = hotCoreWeight;
    }

    public double getCoolPeripheryTe() {
        return coolPeripheryTe;
    }

    public void setCoolPeripheryTe(double coolPeripheryTe) {
        this.coolPeripheryTe = coolPeripheryTe;
    }

    public double getCoolPeripheryNe() {
        return coolPeripheryNe;
    }

    public void setCoolPeripheryNe(double coolPeripheryNe) {
        this.coolPeripheryNe = coolPeripheryNe;
    }

    public double getCoolPeripheryWeight() {
        return coolPeripheryWeight;
    }

    public void setCoolPeripheryWeight(double coolPeripheryWeight) {
        this.coolPeripheryWeight = coolPeripheryWeight;
    }

    public double getFitScore() {
        return fitScore;
    }

    public void setFitScore(double fitScore) {
        this.fitScore = fitScore;
    }

    public double getRmse() {
        return rmse;
    }

    public void setRmse(double rmse) {
        this.rmse = rmse;
    }

    @Override
    public String toString() {
        return String.format("InstrumentProfile{instrument=%s, wavelengths=[%.1f-%.1f nm, step=%.3f], " +
                "hotCore(Te=%.2f eV, Ne=%.2e), coolPeriphery(Te=%.2f eV, Ne=%.2e), fitScore=%.4f}",
                instrumentName, minWavelength, maxWavelength, wavelengthStep,
                hotCoreTe, hotCoreNe, coolPeripheryTe, coolPeripheryNe, fitScore);
    }
}
