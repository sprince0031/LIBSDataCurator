package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.model.InstrumentProfile;
import com.medals.libsdatagenerator.model.MaterialFamilyProfile;
import com.medals.libsdatagenerator.model.PlasmaZone;
import com.medals.libsdatagenerator.util.CommonUtils;
import com.medals.libsdatagenerator.util.SpectrumUtils;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for InstrumentProfileService.
 * Tests wavelength extraction, spectrum averaging, and profile generation.
 * 
 * @author Siddharth Prince | Generated for issue #84
 */
public class InstrumentProfileServiceTest {

    private InstrumentProfileService service;
    private SpectrumUtils spectrumUtils;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        service = InstrumentProfileService.getInstance();
        spectrumUtils = new SpectrumUtils();
    }

    @Test
    void testExtractWavelengthGrid() throws IOException {
        // Create a sample CSV with wavelength headers
        String csvContent = "Shot;250.5;251.0;251.5;252.0;252.5;Label\n" +
                "1;100.5;120.3;130.2;110.8;105.1;Sample1\n" +
                "2;105.2;122.1;128.9;112.3;106.7;Sample2\n";

        Path csvPath = tempDir.resolve("sample_spectra.csv");
        Files.writeString(csvPath, csvContent);

        double[] wavelengths = service.extractWavelengthGrid(csvPath, ";");

        assertNotNull(wavelengths);
        assertEquals(5, wavelengths.length);
        assertEquals(250.5, wavelengths[0], 0.01);
        assertEquals(252.5, wavelengths[4], 0.01);
    }

    @Test
    void testExtractWavelengthGridFiltersOutOfRange() throws IOException {
        // CSV with some wavelengths outside typical LIBS range
        String csvContent = "50.0;250.0;500.0;1500.0\n" +
                "10;100;200;300\n";

        Path csvPath = tempDir.resolve("range_test.csv");
        Files.writeString(csvPath, csvContent);

        double[] wavelengths = service.extractWavelengthGrid(csvPath, ";");

        // Should only include wavelengths in 100-1000nm range
        assertNotNull(wavelengths);
        assertEquals(2, wavelengths.length);
        assertEquals(250.0, wavelengths[0]);
        assertEquals(500.0, wavelengths[1]);
    }

    @Test
    void testCalculateAverageSpectrum() {
        List<double[]> spectra = Arrays.asList(
                new double[] { 10.0, 20.0, 30.0 },
                new double[] { 20.0, 40.0, 60.0 },
                new double[] { 30.0, 60.0, 90.0 });

        double[] avg = spectrumUtils.calculateAverageSpectrum(spectra);

        assertNotNull(avg);
        assertEquals(3, avg.length);
        assertEquals(20.0, avg[0], 0.01);
        assertEquals(40.0, avg[1], 0.01);
        assertEquals(60.0, avg[2], 0.01);
    }

    @Test
    void testCalculateAverageSpectrumEmpty() {
        List<double[]> spectra = Arrays.asList();

        double[] avg = spectrumUtils.calculateAverageSpectrum(spectra);

        assertNotNull(avg);
        assertEquals(0, avg.length);
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
        double[] wavelengths = service.extractWavelengthGrid(csvPath, ";");
        assertEquals(3, wavelengths.length);
        assertEquals(200.0, wavelengths[0]);

        // 2. Extract Spectra
        List<double[]> spectra = service.extractMeasuredSpectra(csvPath, wavelengths, ";");
        assertEquals(3, spectra.size());

        // 3. Average
        double[] averageSpectrum = spectrumUtils.calculateAverageSpectrum(spectra);
        assertEquals(3, averageSpectrum.length);
        assertEquals(20.0, averageSpectrum[0], 0.001);
        assertEquals(40.0, averageSpectrum[1], 0.001);
        assertEquals(60.0, averageSpectrum[2], 0.001);
    }

    @Test
    void testExtractMeasuredSpectraWithIntegerFormattedHeaders() throws IOException {
        // CSV headers use integer format ("200") while extractWavelengthGrid produces
        // double values (200.0). This test verifies that extractMeasuredSpectra correctly
        // resolves columns by matching parsed header values rather than by regenerating
        // strings from doubles (which would produce "200.0" and miss the "200" column).
        String csvContent = "Shot;200;201;202\n" +
                "1;10.0;20.0;30.0\n" +
                "2;20.0;40.0;60.0\n";
        Path csvPath = tempDir.resolve("test_integer_headers.csv");
        Files.writeString(csvPath, csvContent);

        double[] wavelengths = service.extractWavelengthGrid(csvPath, ";");
        assertEquals(3, wavelengths.length);
        assertEquals(200.0, wavelengths[0]);

        List<double[]> spectra = service.extractMeasuredSpectra(csvPath, wavelengths, ";");
        assertEquals(2, spectra.size());
        assertEquals(10.0, spectra.get(0)[0], 0.001);
        assertEquals(20.0, spectra.get(0)[1], 0.001);
        assertEquals(30.0, spectra.get(0)[2], 0.001);
        assertEquals(20.0, spectra.get(1)[0], 0.001);
    }

    @Test
    void testProfileSaveAndLoad() throws Exception {
        // Create a profile
        double[] wavelengths = new double[] { 250.0, 260.0, 270.0, 280.0, 290.0 };
        InstrumentProfile profile = new InstrumentProfile(wavelengths, "test.csv", "Fe-98.0,C-2.0");
        profile.setInstrumentName("Test Spectrometer");
        profile.setNumShots(5);

        List<PlasmaZone> zones = new ArrayList<>();
        zones.add(new PlasmaZone(1.2, 5e16, 0.6));
        zones.add(new PlasmaZone(0.7, 2e16, 0.4));
        MaterialFamilyProfile mfProfile = new MaterialFamilyProfile("testMaterialFamily");
        mfProfile.setPlasmaZones(zones);
        mfProfile.setRSquaredValue(0.95);
        mfProfile.setRmse(0.05);
        profile.addMaterialFamilyProfile(mfProfile);

        // Save to file
        Path outputPath = tempDir.resolve("test_profile.json");
        CommonUtils.getInstance().saveModelToFile(outputPath, profile);

        assertTrue(Files.exists(outputPath));

        // Load from file
        InstrumentProfile loaded = CommonUtils.getInstance().loadModelFromFile(outputPath, InstrumentProfile.class);
        MaterialFamilyProfile loadedFamilyProfile = loaded.getMaterialFamilyProfiles().get("testMaterialFamily");

        assertNotNull(loaded);
        assertNotNull(loadedFamilyProfile);
        assertEquals("Test Spectrometer", loaded.getInstrumentName());
        assertEquals("Fe-98.0,C-2.0", loaded.getComposition());
        assertEquals(5, loaded.getNumShots());
        assertEquals(2, loadedFamilyProfile.getPlasmaZones().size());
        assertEquals(1.2, loadedFamilyProfile.getPlasmaZones().get(0).getTe(), 0.01);
        assertEquals(5e16, loadedFamilyProfile.getPlasmaZones().get(0).getNe(), 1e15);
        assertEquals(0.7, loadedFamilyProfile.getPlasmaZones().get(1).getTe(), 0.01);
        assertEquals(2e16, loadedFamilyProfile.getPlasmaZones().get(1).getNe(), 1e15);
        assertEquals(0.95, loadedFamilyProfile.getRSquaredValue(), 0.01);
        assertEquals(0.05, loadedFamilyProfile.getRmse(), 0.01);
    }

    @Test
    void testProfileToJson() {
        double[] wavelengths = new double[] { 250.0, 260.0, 270.0 };
        InstrumentProfile profile = new InstrumentProfile(wavelengths, "test.csv", "Fe-100");
        profile.setInstrumentName("Test");
        MaterialFamilyProfile mfProfile = new MaterialFamilyProfile("testMaterialFamily");
        profile.addMaterialFamilyProfile(mfProfile);
        org.json.JSONObject json = profile.toJson();

        assertNotNull(json);
        assertTrue(json.has("instrumentName"));
        assertTrue(json.has("wavelengths"));
        assertTrue(json.has("materialFamilyProfiles"));

        org.json.JSONObject materialFamilyProfiles = json.getJSONObject("materialFamilyProfiles");
        org.json.JSONObject familyProfile = materialFamilyProfiles.getJSONObject("testMaterialFamily");

        // Check plasma parameters structure
        org.json.JSONArray  jsonPlasmaZones = familyProfile.getJSONArray("plasmaZones");
        assertEquals(0, jsonPlasmaZones.length());
    }

    @Test
    void testGenerateJupyterReport() throws IOException {
        // Create a dummy profile
        double[] wavelengths = { 200.0, 300.0, 400.0 };
        InstrumentProfile profile = new InstrumentProfile(wavelengths, "dummy.csv", "Fe-100");
        List<PlasmaZone> zones = new ArrayList<>();
        zones.add(new PlasmaZone(1.0, 1e16, 1.0));
        String materialFamilyName = "testMaterialFamily";
        MaterialFamilyProfile mfProfile = new MaterialFamilyProfile(materialFamilyName);
        mfProfile.setPlasmaZones(zones);
        profile.addMaterialFamilyProfile(mfProfile);
        profile.setInstrumentName("Test Spectrometer");

        Path reportPath = tempDir.resolve("calibration_report.ipynb");
        Path dummyPath = tempDir.resolve("dummy.csv");
        Files.writeString(dummyPath, "Wavelength,Intensity\n200,100");

        service.generateJupyterReport(profile, reportPath, dummyPath, dummyPath, materialFamilyName);

        assertTrue(Files.exists(reportPath));
        String content = Files.readString(reportPath);
        assertTrue(content.contains("\"cells\""));
        assertTrue(content.contains("metadata"));
        assertTrue(content.contains("nbformat"));
        // Check for plotting code presence
        assertTrue(content.contains("import matplotlib.pyplot as plt"));
        assertTrue(content.contains("plt.plot"));
    }

    @Test
    void testBuildCompositionStringFromJson() {
        // "#" value should produce "Fe-#"
        JSONObject composition = new JSONObject();
        composition.put("C", 0.279);
        composition.put("Fe", "#");

        String result = service.buildCompositionStringFromJson(composition);

        assertNotNull(result);
        // Both key-value pairs must be present
        assertTrue(result.contains("C-0.279"), "Expected C-0.279 in: " + result);
        assertTrue(result.contains("Fe-#"), "Expected Fe-# in: " + result);
    }

    @Test
    void testBuildCompositionStringFromJsonMultipleElements() {
        JSONObject composition = new JSONObject();
        composition.put("C", 0.279);
        composition.put("Si", 0.421);
        composition.put("Cr", 11.93);
        composition.put("Fe", "#");

        String result = service.buildCompositionStringFromJson(composition);

        assertNotNull(result);
        assertTrue(result.contains("C-0.279"));
        assertTrue(result.contains("Si-0.421"));
        assertTrue(result.contains("Cr-11.93"));
        assertTrue(result.contains("Fe-#"));
        // Should be comma-separated without leading/trailing commas
        assertFalse(result.startsWith(","));
        assertFalse(result.endsWith(","));
    }

    // -----------------------------------------------------------------------
    // findMaterialCsvFiles – flat layout
    // -----------------------------------------------------------------------

    @Test
    void testFindMaterialCsvFilesFlat() throws IOException {
        // Flat layout: CSV files named <materialName>_LSA_*.csv
        Files.writeString(tempDir.resolve("469_LSA_point1.csv"), "dummy");
        Files.writeString(tempDir.resolve("469_LSA_point2.csv"), "dummy");
        Files.writeString(tempDir.resolve("470_LSA_point1.csv"), "dummy");
        Files.writeString(tempDir.resolve("readme.txt"), "ignore me");

        List<Path> files469 = service.findMaterialCsvFiles(tempDir, "469", false);
        List<Path> files470 = service.findMaterialCsvFiles(tempDir, "470", false);
        List<Path> filesUnknown = service.findMaterialCsvFiles(tempDir, "999", false);

        assertEquals(2, files469.size(), "Should find 2 CSVs for material 469");
        assertEquals(1, files470.size(), "Should find 1 CSV for material 470");
        assertEquals(0, filesUnknown.size(), "Should find 0 CSVs for unknown material");
        // Non-csv file should be ignored
        files469.forEach(p -> assertTrue(p.getFileName().toString().endsWith(".csv")));
    }

    @Test
    void testFindMaterialCsvFilesFlatIgnoresNonCsv() throws IOException {
        Files.writeString(tempDir.resolve("469_LSA_point1.csv"), "dummy");
        Files.writeString(tempDir.resolve("469_LSA_readme.txt"), "ignore");
        Files.writeString(tempDir.resolve("469_LSA_data.json"), "ignore");

        List<Path> files = service.findMaterialCsvFiles(tempDir, "469", false);

        assertEquals(1, files.size(), "Only the .csv file should be returned");
    }

    // -----------------------------------------------------------------------
    // findMaterialCsvFiles – subdirectory layout
    // -----------------------------------------------------------------------

    @Test
    void testFindMaterialCsvFilesSubdir() throws IOException {
        // Subdirectory layout: <sourceDir>/<materialName>/<anything>.csv
        Path matDir = tempDir.resolve("469");
        Files.createDirectories(matDir);
        Files.writeString(matDir.resolve("shot1.csv"), "dummy");
        Files.writeString(matDir.resolve("shot2.csv"), "dummy");
        Files.writeString(matDir.resolve("notes.txt"), "ignore");

        Path otherDir = tempDir.resolve("470");
        Files.createDirectories(otherDir);
        Files.writeString(otherDir.resolve("shot1.csv"), "dummy");

        List<Path> files469 = service.findMaterialCsvFiles(tempDir, "469", true);
        List<Path> files470 = service.findMaterialCsvFiles(tempDir, "470", true);
        List<Path> filesUnknown = service.findMaterialCsvFiles(tempDir, "999", true);

        assertEquals(2, files469.size(), "Should find 2 CSVs inside 469/ subdir");
        assertEquals(1, files470.size(), "Should find 1 CSV inside 470/ subdir");
        assertEquals(0, filesUnknown.size(), "Should find 0 CSVs for absent material");
        files469.forEach(p -> assertTrue(p.getFileName().toString().endsWith(".csv")));
    }

    @Test
    void testFindMaterialCsvFilesSubdirIgnoresNonCsv() throws IOException {
        Path matDir = tempDir.resolve("551");
        Files.createDirectories(matDir);
        Files.writeString(matDir.resolve("reading.csv"), "dummy");
        Files.writeString(matDir.resolve("metadata.json"), "ignore");

        List<Path> files = service.findMaterialCsvFiles(tempDir, "551", true);

        assertEquals(1, files.size(), "Only the .csv file should be returned");
    }

    // -----------------------------------------------------------------------
    // generateProfileFromDirectory – reference_compositions.json missing
    // -----------------------------------------------------------------------

    @Test
    void testGenerateProfileFromDirectoryMissingRefFile() {
        // No reference_compositions.json → should throw IOException
        assertThrows(IOException.class, () ->
            service.generateProfileFromDirectory(
                tempDir, null, ";", "Test",
                new com.medals.libsdatagenerator.model.BaselineCorrectionParams(10000, 0.001, 10),
                2, false, Paths.get("instrument_profile.json")));
    }

    @Test
    void testGenerateProfileFromDirectoryExplicitRefPathMissing() {
        Path nonExistent = tempDir.resolve("custom_ref.json");
        assertThrows(IOException.class, () ->
            service.generateProfileFromDirectory(
                tempDir, nonExistent, ";", "Test",
                new com.medals.libsdatagenerator.model.BaselineCorrectionParams(10000, 0.001, 10),
                2, false, Paths.get("instrument_profile.json")));
    }

    // -----------------------------------------------------------------------
    // generateJupyterReportForDirectory
    // -----------------------------------------------------------------------

//    @Test
//    void testGenerateJupyterReportForDirectory() throws Exception {
//        // Build a minimal profile
//        double[] wavelengths = { 200.0, 300.0, 400.0 };
//        InstrumentProfile profile = new InstrumentProfile(wavelengths, "/data/measurements", "directory:ref.json");
//        profile.setInstrumentName("Test Spectrometer");
//        List<PlasmaZone> zones = new ArrayList<>();
//        zones.add(new PlasmaZone(1.2, 1e16, 0.6));
//        zones.add(new PlasmaZone(0.8, 5e15, 0.4));
//        String materialFamilyProfile = "testProfile";
//        MaterialFamilyProfile mfProfile = new MaterialFamilyProfile(materialFamilyProfile);
//        mfProfile.setPlasmaZones(zones);
//        mfProfile.setRSquaredValue(0.92);
//        mfProfile.setRmse(0.08);
//        profile.addMaterialFamilyProfile(mfProfile);
//
//        // Write a dummy averaged_best_zones.csv and per-material zones CSVs
//        Path avgZonesCsv = tempDir.resolve("averaged_best_zones.csv");
//        Files.writeString(avgZonesCsv, "Te,Ne,Weight\n1.0,1e16,0.5\n0.7,5e15,0.5\n");
//
//        Path matZonesCsv = tempDir.resolve("469_best_zones.csv");
//        Files.writeString(matZonesCsv, "Te,Ne,Weight\n1.2,1e16,0.6\n0.8,5e15,0.4\n");
//
//        List<String> matNames = List.of("469");
//        Path reportPath = tempDir.resolve("calibration_report_multi_material.ipynb");
//
//        service.generateJupyterReportForDirectory(profile, reportPath, avgZonesCsv, tempDir, matNames);
//
//        assertTrue(Files.exists(reportPath), "Report notebook should have been written");
//        String content = Files.readString(reportPath);
//
//        // Verify placeholders were substituted
//        assertFalse(content.contains("<INSTRUMENT_NAME>"),        "INSTRUMENT_NAME placeholder must be replaced");
//        assertFalse(content.contains("<RSQUARE_SCORE>"),          "RSQUARE_SCORE placeholder must be replaced");
//        assertFalse(content.contains("<RMSE>"),                   "RMSE placeholder must be replaced");
//        assertFalse(content.contains("<AVERAGED_ZONES_CSV_PATH>"),"AVERAGED_ZONES_CSV_PATH placeholder must be replaced");
//        assertFalse(content.contains("<PER_MATERIAL_ZONES_CSV_DIR>"), "PER_MATERIAL_ZONES_CSV_DIR placeholder must be replaced");
//        assertFalse(content.contains("<NUM_MATERIALS_PROCESSED>"),"NUM_MATERIALS_PROCESSED placeholder must be replaced");
//        assertFalse(content.contains("<MATERIAL_NAMES_LIST>"),    "MATERIAL_NAMES_LIST placeholder must be replaced");
//
//        // Verify expected values are in the notebook
//        assertTrue(content.contains("Test Spectrometer"),  "Instrument name should be present");
//        assertTrue(content.contains("[\"469\"]"),          "Material names list should be present");
//        assertTrue(content.contains("1"),                  "Num materials should be present");
//        assertTrue(content.contains("\"cells\""),          "Valid notebook JSON structure required");
//        assertTrue(content.contains("import matplotlib.pyplot as plt"), "Plotting import required");
//    }
}