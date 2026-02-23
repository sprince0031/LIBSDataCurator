package com.medals.libsdatagenerator.model;

import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

public class Spectrum {
    Logger logger = Logger.getLogger(Spectrum.class.getName());

    double[] wavelengths;
    double[] intensities;

    public Spectrum() {}

    public Spectrum(double[] wavelengths, double[] intensities) {
        this.wavelengths = wavelengths;
        this.intensities = intensities;
    }

    public Spectrum(Map<Double, Double> waveMap) {
        // Ensuring waveMap is sorted
        TreeMap<Double, Double> sortedWaveMap = new TreeMap<>(waveMap);
        int i = 0;
        this.wavelengths = new double[waveMap.size()];
        this.intensities = new double[waveMap.size()];

        // Unzipping waveMap
        for (Map.Entry<Double, Double> entry : sortedWaveMap.entrySet()) {
            wavelengths[i] = entry.getKey();
            intensities[i] = entry.getValue();
            i++;
        }
    }

    public double[] getIntensities() {
        return intensities;
    }

    public void setIntensities(double[] intensities) {
        this.intensities = intensities;
    }

    public double[] getWavelengths() {
        return wavelengths;
    }

    public void setWavelengths(double[] wavelengths) {
        this.wavelengths = wavelengths;
    }
}
