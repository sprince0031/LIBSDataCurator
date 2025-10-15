package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DatasetStatisticsServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void testCalculateAndSaveStatistics() throws IOException {
        // Create a dummy master_dataset.csv
        String csvContent = "composition,Fe,C\n" +
                "Fe-90;C-10,90.0,10.0\n" +
                "Fe-80;C-20,80.0,20.0\n" +
                "Fe-85;C-15,85.0,15.0\n";
        Path csvPath = tempDir.resolve("master_dataset.csv");
        Files.writeString(csvPath, csvContent);

        // Run the service
        DatasetStatisticsService statsService = new DatasetStatisticsService();
        statsService.calculateAndSaveStatistics(tempDir.toString());

        // Check if the statistics file was created
        Path statsPath = tempDir.resolve(LIBSDataGenConstants.DATASET_STATISTICS_FILE_NAME);
        assertTrue(Files.exists(statsPath));

        // Read and validate the statistics file content
        String statsContent = Files.readString(statsPath);
        JSONObject statsJson = new JSONObject(statsContent);

        assertTrue(statsJson.has("Fe"));
        JSONObject feStats = statsJson.getJSONObject("Fe");
        assertEquals(85.0, feStats.getDouble("mean"), 0.001);
        assertEquals(5.0, feStats.getDouble("std_dev"), 0.001);
        assertEquals(3, feStats.getLong("count"));

        assertTrue(statsJson.has("C"));
        JSONObject cStats = statsJson.getJSONObject("C");
        assertEquals(15.0, cStats.getDouble("mean"), 0.001);
        assertEquals(5.0, cStats.getDouble("std_dev"), 0.001);
        assertEquals(3, cStats.getLong("count"));
    }
}
