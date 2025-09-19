package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.util.CSVUtils;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.json.JSONObject;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Service to calculate and save statistics for the generated dataset.
 */
public class DatasetStatisticsService {
    private static final Logger logger = Logger.getLogger(DatasetStatisticsService.class.getName());

    /**
     * Calculates and saves statistics (mean, std dev) for the generated dataset.
     *
     * @param csvDirPath The directory where the master_dataset.csv is located.
     */
    public void calculateAndSaveStatistics(String csvDirPath) {
        Path masterCsvPath = Paths.get(csvDirPath, "master_dataset.csv");

        try {
            List<CSVRecord> records = CSVUtils.readCsvWithHeader(masterCsvPath);
            Map<String, DescriptiveStatistics> elementStats = new HashMap<>();

            // Initialize stats for each standard element
            for (String element : LIBSDataGenConstants.STD_ELEMENT_LIST) {
                elementStats.put(element, new DescriptiveStatistics());
            }

            // Populate stats from CSV records
            for (CSVRecord record : records) {
                for (String element : LIBSDataGenConstants.STD_ELEMENT_LIST) {
                    if (record.isMapped(element)) {
                        try {
                            double value = Double.parseDouble(record.get(element));
                            elementStats.get(element).addValue(value);
                        } catch (NumberFormatException e) {
                            // Ignore non-numeric values
                        }
                    }
                }
            }

            // Build JSON object with statistics
            JSONObject statsJson = new JSONObject();
            for (String element : LIBSDataGenConstants.STD_ELEMENT_LIST) {
                DescriptiveStatistics stats = elementStats.get(element);
                if (stats.getN() > 0) {
                    JSONObject elementJson = new JSONObject();
                    elementJson.put("mean", stats.getMean());
                    elementJson.put("std_dev", stats.getStandardDeviation());
                    elementJson.put("count", stats.getN());
                    statsJson.put(element, elementJson);
                }
            }

            // Save JSON to file
            Path statsFilePath = Paths.get(csvDirPath, LIBSDataGenConstants.DATASET_STATISTICS_FILE_NAME);
            try (FileWriter file = new FileWriter(statsFilePath.toFile())) {
                file.write(statsJson.toString(4)); // Indent with 4 spaces for readability
                logger.info("Dataset statistics saved to: " + statsFilePath.toAbsolutePath());
            }

        } catch (IOException e) {
            logger.log(Level.SEVERE, "Error calculating or saving dataset statistics", e);
        }
    }
}
