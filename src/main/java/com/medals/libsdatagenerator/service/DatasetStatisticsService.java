package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.util.CSVUtils;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service to calculate and save statistics for the generated dataset.
 *
 * @author Siddharth Prince | 19/09/2025 15:21
 */
public class DatasetStatisticsService {
    private static final Logger logger = Logger.getLogger(DatasetStatisticsService.class.getName());
    private static final int HISTOGRAM_BINS = 20; // Number of bins for the histogram

    public void calculateAndSaveStatistics(String csvDirPath) {
        Path masterCsvPath = Paths.get(csvDirPath, "master_dataset.csv");

        try {
            List<CSVRecord> records = CSVUtils.readCsvWithHeader(masterCsvPath);
            Map<String, DescriptiveStatistics> elementStats = new HashMap<>();
            Map<String, List<Double>> elementValues = new HashMap<>();

            // Initialize stats and value lists for each standard element
            for (String element : LIBSDataGenConstants.STD_ELEMENT_LIST) {
                elementStats.put(element, new DescriptiveStatistics());
                elementValues.put(element, new ArrayList<>());
            }

            // First pass: Populate stats and collect all values
            for (CSVRecord record : records) {
                for (String element : LIBSDataGenConstants.STD_ELEMENT_LIST) {
                    if (record.isMapped(element)) {
                        try {
                            double value = Double.parseDouble(record.get(element));
                            elementStats.get(element).addValue(value);
                            elementValues.get(element).add(value);
                        } catch (NumberFormatException e) {
                            // Ignore non-numeric values
                        }
                    }
                }
            }

            // Second pass: Build JSON object with stats and histogram data
            JSONObject statsJson = new JSONObject();
            for (String element : LIBSDataGenConstants.STD_ELEMENT_LIST) {
                DescriptiveStatistics stats = elementStats.get(element);
                if (stats.getN() > 0) { // Only process elements present in the dataset
                    JSONObject elementJson = new JSONObject();
                    elementJson.put("mean", stats.getMean());
                    elementJson.put("std_dev", stats.getStandardDeviation());
                    elementJson.put("count", stats.getN());
                    elementJson.put("min", stats.getMin());
                    elementJson.put("max", stats.getMax());

                    // Calculate and add histogram data
                    JSONObject histogram = calculateHistogram(elementValues.get(element), HISTOGRAM_BINS, stats.getMin(), stats.getMax());
                    elementJson.put("histogram", histogram);

                    statsJson.put(element, elementJson);
                }
            }

            // Save JSON to file
            Path statsFilePath = Paths.get(csvDirPath, LIBSDataGenConstants.DATASET_STATISTICS_FILE_NAME);
            try (FileWriter file = new FileWriter(statsFilePath.toFile())) {
                file.write(statsJson.toString(4)); // Indent for readability
                logger.info("Dataset statistics saved to: " + statsFilePath.toAbsolutePath());
            }

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error calculating or saving dataset statistics", e);
        }
    }

    /**
     * Calculates histogram data for a list of values.
     *
     * @param values The list of concentration values for an element.
     * @param numBins The number of bins to create.
     * @param min The minimum value in the dataset.
     * @param max The maximum value in the dataset.
     * @return A JSONObject containing the bin edges and the counts for each bin.
     */
    private JSONObject calculateHistogram(List<Double> values, int numBins, double min, double max) {
        JSONObject histogramJson = new JSONObject();
        if (min == max) { // Handle case where all values are the same
            histogramJson.put("bins", new JSONArray(new double[]{min, max}));
            histogramJson.put("counts", new JSONArray(new int[]{values.size()}));
            return histogramJson;
        }

        double binWidth = (max - min) / numBins;
        double[] binEdges = new double[numBins + 1];
        int[] counts = new int[numBins];

        for (int i = 0; i <= numBins; i++) {
            binEdges[i] = min + i * binWidth;
        }

        for (double value : values) {
            int binIndex = (int) Math.floor((value - min) / binWidth);
            // Handle edge case where value is exactly the max
            if (binIndex >= numBins) {
                binIndex = numBins - 1;
            }
            if (binIndex >= 0) {
                counts[binIndex]++;
            }
        }

        histogramJson.put("bins", new JSONArray(binEdges));
        histogramJson.put("counts", new JSONArray(counts));
        return histogramJson;
    }
}