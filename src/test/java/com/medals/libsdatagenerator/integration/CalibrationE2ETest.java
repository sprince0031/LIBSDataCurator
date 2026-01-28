package com.medals.libsdatagenerator.integration;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.InstrumentProfile;
import com.medals.libsdatagenerator.service.InstrumentProfileService;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-End Integration Test for Instrument Profile Calibration.
 * Uses real data as specified in the conductor track.
 */
@Tag("integration")
public class CalibrationE2ETest {

    @Test
    void testFullCalibrationFlow() throws IOException {
        // 1. Define Inputs
        String inputCsvPathStr = "/home/puser/Work/PhD/Datasets/Partner Data/LSA/Limerick Dataset Calibration Samples/Medals_CalibrationSamples/469_LSA_ava_S20251008173111_E20251008173212.csv";
        String composition = "C-0.279,Si-0.421,Mn-0.598,P-0.015,S-0.020,Cr-11.93,Ni-0.246,Fe-#";
        String instrumentName = "Test_Instrument_E2E";
        
        Path inputCsvPath = Paths.get(inputCsvPathStr);
        
        // Verify input exists (skip if running in environment without this file)
        if (!Files.exists(inputCsvPath)) {
            System.out.println("Skipping E2E test: Real input file not found at " + inputCsvPathStr);
            return;
        }

        System.out.println("Running E2E Calibration Test with: " + inputCsvPathStr);

        // 2. Define Output for Profile JSON (use a temp file or the real conf location?)
        // The spec says "saved to <TOOL_HOME>/conf". Let's use a temp output for the JSON 
        // to verify we CAN save it, but the Service generates side-effect files in data/calibration.
        Path outputProfilePath = Paths.get("target", "e2e_instrument_profile.json");
        
        // 3. Run Service
        InstrumentProfileService service = InstrumentProfileService.getInstance();
        InstrumentProfile profile = service.generateProfile(inputCsvPath, composition, instrumentName);
        
        // 4. Assertions on Profile Object
        assertNotNull(profile);
        assertEquals(instrumentName, profile.getInstrumentName());
        assertFalse(profile.getWavelengths().isEmpty(), "Wavelengths should not be empty");
        assertTrue(profile.getFitScore() > 0, "Fit score should be positive");
        assertTrue(profile.getFitScore() <= 1.0, "Fit score should be <= 1.0");
        
        // 5. Save and Verify JSON Output
        profile.saveToFile(outputProfilePath);
        assertTrue(Files.exists(outputProfilePath), "Profile JSON should be created");
        
        // 6. Verify Side-Effect Files in data/calibration
        Path calibDir = Paths.get(CommonUtils.DATA_PATH, LIBSDataGenConstants.CALIBRATION_DIR);
        Path targetCsv = calibDir.resolve("target_processed.csv");
        Path reportNb = calibDir.resolve(LIBSDataGenConstants.CALIBRATION_REPORT_OUTPUT_FILE);
        Path hotDir = calibDir.resolve(LIBSDataGenConstants.CALIBRATION_HOT_DIR);
        Path coolDir = calibDir.resolve(LIBSDataGenConstants.CALIBRATION_COOL_DIR);
        Path bestHotCsv = hotDir.resolve("best_hot.csv");
        Path bestCoolCsv = coolDir.resolve("best_cool.csv");
        
        assertTrue(Files.exists(targetCsv), "Target processed CSV should exist");
        assertTrue(Files.exists(reportNb), "Calibration Jupyter Report should exist");
        assertTrue(Files.exists(bestHotCsv), "Best Hot Core spectrum CSV should exist");
        assertTrue(Files.exists(bestCoolCsv), "Best Cool Periphery spectrum CSV should exist");
        
        // Optional: Verify PDF existence (might fail if pandoc/latex missing, so maybe just warn)
        // Path reportPdf = calibDir.resolve("calibration_report.pdf");
        // if (Files.exists(reportPdf)) {
        //     System.out.println("PDF Report verified.");
        // } else {
        //     System.out.println("PDF Report not found (PDF conversion might have failed).");
        // }
    }
}
