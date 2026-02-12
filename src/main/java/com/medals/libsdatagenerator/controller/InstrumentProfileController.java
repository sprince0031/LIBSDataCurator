package com.medals.libsdatagenerator.controller;

import com.medals.libsdatagenerator.model.BaselineCorrectionParams;
import com.medals.libsdatagenerator.model.InstrumentProfile;
import com.medals.libsdatagenerator.service.InstrumentProfileService;
import com.medals.libsdatagenerator.util.CmdlineParserUtil;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.apache.commons.cli.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller for instrument profile calibration mode.
 * Standalone entry point for generating instrument profiles from real LIBS
 * data.
 * 
 * Usage: java -cp LIBSDataCurator.jar
 * com.medals.libsdatagenerator.controller.InstrumentProfileController
 * -i <sample_csv_path> -c <composition> [-o <output_path>] [-n
 * <instrument_name>]
 * 
 * @author Siddharth Prince | 13/01/26 08:30
 */
public class InstrumentProfileController {

    private static final Logger logger = Logger.getLogger(InstrumentProfileController.class.getName());

    private static String outputDir = CommonUtils.CONF_PATH;

    public static void main(String[] args) {
        logger.info("Starting LIBS Instrument Profile Calibration...");

        CommandLine cmd = new CmdlineParserUtil().parseCommandLineArgsForCalibration(args);
        if (cmd == null) {
            System.exit(1);
            return;
        }

        try {
            // Get input parameters
            String inputPath = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_INPUT_SHORT);
            String delimiter = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_DELIMITER_SHORT, ";");
            String composition = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT);
            String instrumentName = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_NAME_SHORT, "Unknown");
            String outputPath = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_OUTPUT_SHORT,
                    outputDir + File.separator + "instrument_profile.json");
            // Baseline correction parameters
            double lambda = Double.parseDouble(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_BASELINE_LAMBDA_SHORT,
                    "10000"));
            double p = Double.parseDouble(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_BASELINE_P_SHORT,
                    "0.001"));
            int maxIterations = Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_BASELINE_ITER_SHORT,
                    "10"));
            BaselineCorrectionParams baselineCorrectionParams = new BaselineCorrectionParams(lambda, p, maxIterations);

            // Validate input file exists
            File inputFile = new File(inputPath);
            if (!inputFile.exists() || !inputFile.isFile()) {
                logger.severe("Input file does not exist or is not a file: " + inputPath);
                System.out.println("Error: Input file not found: " + inputPath);
                System.exit(1);
                return;
            }
            
            // Generate instrument profile
            InstrumentProfileService profileService = InstrumentProfileService.getInstance();
            Path inputFilePath = Paths.get(inputPath);
            
            System.out.println("=== LIBS Instrument Profile Generator ===");
            System.out.println("Input file: " + inputPath);
            System.out.println("Reference composition: " + composition);
            System.out.println("Instrument name: " + instrumentName);
            System.out.printf("Baseline Correction: lambda=%.1f, p=%.4f, maxIter=%d%n", lambda, p, maxIterations);
            System.out.println();

            InstrumentProfile profile = profileService.generateProfile(inputFilePath, delimiter, composition,
                    instrumentName, baselineCorrectionParams);

            // Save profile
            Path outputFilePath = Paths.get(outputPath);
            profile.saveToFile(outputFilePath);
            
            System.out.println();
            System.out.println("=== Profile Generation Complete ===");
            System.out.println("Profile saved to: " + outputFilePath.toAbsolutePath());
            System.out.println();
            System.out.println("Profile Summary:");
            System.out.println("  Wavelength range: " + profile.getMinWavelength() + " - " + 
                    profile.getMaxWavelength() + " nm");
            System.out.println("  Wavelength points: " + profile.getWavelengthGrid().length);
            System.out.println("  Number of shots analyzed: " + profile.getNumShots());
            System.out.println();
            System.out.println("  Two-Zone Plasma Parameters:");
            System.out.println("    Hot Core:");
            System.out.printf("      Temperature: %.3f eV%n", profile.getHotCoreTe());
            System.out.printf("      Electron Density: %.3e cm^-3%n", profile.getHotCoreNe());
            System.out.printf("      Weight: %.3f%n", profile.getHotCoreWeight());
            System.out.println("    Cool Periphery:");
            System.out.printf("      Temperature: %.3f eV%n", profile.getCoolPeripheryTe());
            System.out.printf("      Electron Density: %.3e cm^-3%n", profile.getCoolPeripheryNe());
            System.out.printf("      Weight: %.3f%n", profile.getCoolPeripheryWeight());
            System.out.println();
            System.out.printf("  R^2: %.4f%n", profile.getRSquaredValue());
            System.out.printf("  RMSE: %.4f%n", profile.getRmse());
            
            logger.info("Profile generation complete. Output: " + outputFilePath.toAbsolutePath());
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to generate instrument profile", e);
            System.out.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

}
