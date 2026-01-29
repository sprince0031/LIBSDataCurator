package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.model.InstrumentProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InstrumentProfileService.
 * Tests wavelength extraction, spectrum averaging, and profile generation.
 * 
 * @author Siddharth Prince | Generated for issue #84
 */
public class InstrumentProfileServiceTest {

    private InstrumentProfileService service;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = InstrumentProfileService.getInstance();
    }

    @Test
    void testExtractWavelengthGrid() throws IOException {
        // Create a sample CSV with wavelength headers
        String csvContent = "Shot;250.5;251.0;251.5;252.0;252.5;Label\n" +
                "1;100.5;120.3;130.2;110.8;105.1;Sample1\n" +
                "2;105.2;122.1;128.9;112.3;106.7;Sample2\n";
        
        Path csvPath = tempDir.resolve("sample_spectra.csv");
        Files.writeString(csvPath, csvContent);
        
        List<Double> wavelengths = service.extractWavelengthGrid(csvPath);
        
        assertNotNull(wavelengths);
        assertEquals(5, wavelengths.size());
        assertEquals(250.5, wavelengths.get(0), 0.01);
        assertEquals(252.5, wavelengths.get(4), 0.01);
    }

    @Test
    void testExtractWavelengthGridFiltersNonNumeric() throws IOException {
        // CSV with mixed numeric and non-numeric headers
        String csvContent = "ID;Name;300.0;350.0;400.0;Type\n" +
                "1;Sample1;50.0;60.0;70.0;A\n";
        
        Path csvPath = tempDir.resolve("mixed_headers.csv");
        Files.writeString(csvPath, csvContent);
        
        List<Double> wavelengths = service.extractWavelengthGrid(csvPath);
        
        assertNotNull(wavelengths);
        assertEquals(3, wavelengths.size());
        assertTrue(wavelengths.contains(300.0));
        assertTrue(wavelengths.contains(350.0));
        assertTrue(wavelengths.contains(400.0));
    }

    @Test
    void testExtractWavelengthGridFiltersOutOfRange() throws IOException {
        // CSV with some wavelengths outside typical LIBS range
        String csvContent = "50.0;250.0;500.0;1500.0\n" +
                "10;100;200;300\n";
        
        Path csvPath = tempDir.resolve("range_test.csv");
        Files.writeString(csvPath, csvContent);
        
        List<Double> wavelengths = service.extractWavelengthGrid(csvPath);
        
        // Should only include wavelengths in 100-1000nm range
        assertNotNull(wavelengths);
        assertEquals(2, wavelengths.size());
        assertTrue(wavelengths.contains(250.0));
        assertTrue(wavelengths.contains(500.0));
    }

    @Test
    void testCalculateAverageSpectrum() {
        List<double[]> spectra = Arrays.asList(
                new double[]{10.0, 20.0, 30.0},
                new double[]{20.0, 40.0, 60.0},
                new double[]{30.0, 60.0, 90.0}
        );
        
        double[] avg = service.calculateAverageSpectrum(spectra);
        
        assertNotNull(avg);
        assertEquals(3, avg.length);
        assertEquals(20.0, avg[0], 0.01);
        assertEquals(40.0, avg[1], 0.01);
        assertEquals(60.0, avg[2], 0.01);
    }

    @Test
    void testCalculateAverageSpectrumEmpty() {
        List<double[]> spectra = Arrays.asList();
        
        double[] avg = service.calculateAverageSpectrum(spectra);
        
        assertNotNull(avg);
        assertEquals(0, avg.length);
    }

    @Test
    void testExtractMeasuredSpectra() throws IOException {
        String csvContent = "250.0;300.0;350.0\n" +
                "100.0;150.0;200.0\n" +
                "110.0;160.0;210.0\n" +
                "120.0;170.0;220.0\n";
        
        Path csvPath = tempDir.resolve("spectra.csv");
        Files.writeString(csvPath, csvContent);
        
        List<double[]> spectra = service.extractMeasuredSpectra(csvPath, 3);
        
        assertNotNull(spectra);
        assertEquals(3, spectra.size()); // 3 data rows
        assertEquals(3, spectra.get(0).length); // 3 wavelengths each
        assertEquals(100.0, spectra.get(0)[0], 0.01);
    }

    @Test
    void testProfileSaveAndLoad() throws IOException {
        // Create a profile
        List<Double> wavelengths = Arrays.asList(250.0, 260.0, 270.0, 280.0, 290.0);
        InstrumentProfile profile = new InstrumentProfile(wavelengths, "test.csv", "Fe-98.0,C-2.0");
        profile.setInstrumentName("Test Spectrometer");
        profile.setNumShots(5);
        profile.setHotCoreTe(1.2);
        profile.setHotCoreNe(5e16);
        profile.setCoolPeripheryTe(0.7);
        profile.setCoolPeripheryNe(2e16);
        profile.setFitScore(0.95);
        profile.setRmse(0.05);
        
        // Save to file
        Path outputPath = tempDir.resolve("test_profile.json");
        profile.saveToFile(outputPath);
        
        assertTrue(Files.exists(outputPath));
        
        // Load from file
        InstrumentProfile loaded = InstrumentProfile.loadFromFile(outputPath);
        
        assertNotNull(loaded);
        assertEquals("Test Spectrometer", loaded.getInstrumentName());
        assertEquals("Fe-98.0,C-2.0", loaded.getComposition());
        assertEquals(5, loaded.getNumShots());
        assertEquals(1.2, loaded.getHotCoreTe(), 0.01);
        assertEquals(5e16, loaded.getHotCoreNe(), 1e15);
        assertEquals(0.7, loaded.getCoolPeripheryTe(), 0.01);
        assertEquals(2e16, loaded.getCoolPeripheryNe(), 1e15);
        assertEquals(0.95, loaded.getFitScore(), 0.01);
        assertEquals(0.05, loaded.getRmse(), 0.01);
    }

    @Test
    void testProfileToJson() {
        List<Double> wavelengths = Arrays.asList(250.0, 260.0, 270.0);
        InstrumentProfile profile = new InstrumentProfile(wavelengths, "test.csv", "Fe-100");
        profile.setInstrumentName("Test");
        
        org.json.JSONObject json = profile.toJson();
        
        assertNotNull(json);
        assertTrue(json.has("instrumentName"));
        assertTrue(json.has("wavelengths"));
        assertTrue(json.has("plasmaParameters"));
        assertTrue(json.has("calibrationStats"));
        
        // Check plasma parameters structure
        org.json.JSONObject params = json.getJSONObject("plasmaParameters");
        assertTrue(params.has("hotCore"));
        assertTrue(params.has("coolPeriphery"));
    }

    @Test
    void testDefaultProfileParameters() {
        InstrumentProfile profile = new InstrumentProfile();
        
        // Verify default two-zone parameters
        assertEquals(1.5, profile.getHotCoreTe(), 0.01);
        assertEquals(1e17, profile.getHotCoreNe(), 1e16);
        assertEquals(0.4, profile.getHotCoreWeight(), 0.01);
        
        assertEquals(0.8, profile.getCoolPeripheryTe(), 0.01);
        assertEquals(5e16, profile.getCoolPeripheryNe(), 1e15);
        assertEquals(0.6, profile.getCoolPeripheryWeight(), 0.01);
    }

    @Test
    void testApplyBaselineCorrection() {
        double[] spectrum = {105.0, 110.0, 150.0, 110.0, 105.0}; // Peak on a baseline of ~100
        
        // Simple baseline correction (subtraction of min value for now, or more complex)
        // Let's assume the implementation subtracts the minimum value
        double[] corrected = service.applyBaselineCorrection(spectrum);
        
        assertNotNull(corrected);
        assertEquals(5, corrected.length);
        assertEquals(0.0, corrected[0], 0.01); // 105 - 105
        assertEquals(5.0, corrected[1], 0.01); // 110 - 105
        assertEquals(45.0, corrected[2], 0.01); // 150 - 105
    }

    @Test
    void testGenerateJupyterReport() throws IOException {
        // Create a dummy profile
        List<Double> wavelengths = Arrays.asList(200.0, 300.0, 400.0);
        InstrumentProfile profile = new InstrumentProfile(wavelengths, "dummy.csv", "Fe-100");
        profile.setHotCoreTe(1.0);
        profile.setInstrumentName("Test Spectrometer");
        
        Path reportPath = tempDir.resolve("calibration_report.ipynb");
        Path dummyPath = tempDir.resolve("dummy.csv");
        Files.writeString(dummyPath, "Wavelength,Intensity\n200,100");
        
        service.generateJupyterReport(profile, reportPath, dummyPath, dummyPath, dummyPath);
        
        assertTrue(Files.exists(reportPath));
        String content = Files.readString(reportPath);
        assertTrue(content.contains("\"cells\""));
        assertTrue(content.contains("metadata"));
        assertTrue(content.contains("nbformat"));
        // Check for plotting code presence
        assertTrue(content.contains("import matplotlib.pyplot as plt"));
        assertTrue(content.contains("plt.plot"));
    }
}