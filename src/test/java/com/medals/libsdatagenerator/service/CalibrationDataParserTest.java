package com.medals.libsdatagenerator.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CalibrationDataParserTest {

    private InstrumentProfileService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = InstrumentProfileService.getInstance();
    }

    @Test
    void testParseAndAverageSpectra() throws IOException {
        // Create a sample CSV
        String csvContent = "Shot;200.0;201.0;202.0\n" +
                "1;10.0;20.0;30.0\n" +
                "2;20.0;40.0;60.0\n" +
                "3;30.0;60.0;90.0\n";
        Path csvPath = tempDir.resolve("test_calibration.csv");
        Files.writeString(csvPath, csvContent);

        // 1. Extract Wavelengths
        List<Double> wavelengths = service.extractWavelengthGrid(csvPath);
        assertEquals(3, wavelengths.size());
        assertEquals(200.0, wavelengths.get(0));

        // 2. Extract Spectra
        List<double[]> spectra = service.extractMeasuredSpectra(csvPath, wavelengths.size());
        assertEquals(3, spectra.size());

        // 3. Average
        double[] averageSpectrum = service.calculateAverageSpectrum(spectra);
        assertEquals(3, averageSpectrum.length);
        assertEquals(20.0, averageSpectrum[0], 0.001);
        assertEquals(40.0, averageSpectrum[1], 0.001);
        assertEquals(60.0, averageSpectrum[2], 0.001);
    }
}
