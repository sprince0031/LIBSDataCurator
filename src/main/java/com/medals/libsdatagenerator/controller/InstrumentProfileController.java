package com.medals.libsdatagenerator.controller;

import com.medals.libsdatagenerator.model.BaselineCorrectionParams;
import com.medals.libsdatagenerator.model.InstrumentProfile;
import com.medals.libsdatagenerator.model.MaterialFamilyProfile;
import com.medals.libsdatagenerator.model.PlasmaZone;
import com.medals.libsdatagenerator.service.InstrumentProfileService;
import com.medals.libsdatagenerator.util.CSVUtils;
import com.medals.libsdatagenerator.util.CmdlineParserUtil;
import org.apache.commons.cli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller for instrument profile calibration mode.
 * Standalone entry point for generating instrument profiles from real LIBS
 * data.
 *
 * <p>Two modes of operation are supported:
 * <ul>
 *   <li><b>Single-file mode</b> – {@code -i} points to a CSV file and {@code -c}
 *       supplies the reference composition string (e.g. {@code "Fe-80,C-20"}).
 *       This is the original behaviour.</li>
 *   <li><b>Directory mode</b> – {@code -i} points to a directory containing
 *       measurement CSVs (optionally organised in per-material sub-directories).
 *       {@code -c} optionally supplies the path to a
 *       {@code reference_compositions.json} file; if omitted the file is
 *       expected at {@code <input_dir>/reference_compositions.json}.</li>
 * </ul>
 *
 * @author Siddharth Prince | 13/01/26 08:30
 */
public class InstrumentProfileController {

    private static final Logger logger = Logger.getLogger(InstrumentProfileController.class.getName());

    public static void main(String[] args) {
        logger.info("Starting LIBS Instrument Profile Calibration...");

        CommandLine cmd = new CmdlineParserUtil().parseCommandLineArgsForCalibration(args);
        if (cmd == null) {
            System.exit(1);
            return;
        }

        try {
            // ----------------------------------------------------------------
            // Shared parameters
            // ----------------------------------------------------------------
            String inputPath = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_INPUT_SHORT);
            String materialFamilyName = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MATERIAL_FAMILY_NAME_SHORT);
            String delimiter = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_DELIMITER_SHORT, ";");
            if (!CSVUtils.isValidDelimiter(delimiter)) {
                throw new IOException("Invalid delimiter specified");
            }
            String compositionOrRefPath = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT);
            String instrumentName = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_NAME_SHORT, "Unknown");
            String outputPath = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_OUTPUT_SHORT, InstrumentProfile.INSTRUMENT_PROFILE_PATH);
            int plasmaZones = Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_PLASMA_ZONES_SHORT, "2"));
            double lambda = Double.parseDouble(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_BASELINE_LAMBDA_SHORT, "10000"));
            double p = Double.parseDouble(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_BASELINE_P_SHORT,"0.001"));
            int maxIterations = Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_BASELINE_ITER_SHORT,"10"));
            BaselineCorrectionParams baselineCorrectionParams = new BaselineCorrectionParams(lambda, p, maxIterations);
            boolean debugMode = cmd.hasOption(LIBSDataGenConstants.CMD_OPT_DEBUG_MODE_SHORT);

            // ----------------------------------------------------------------
            // Determine mode: single-file vs. directory
            // ----------------------------------------------------------------
            File inputFile = new File(inputPath);
            if (!inputFile.exists()) {
                logger.severe("Input path does not exist: " + inputPath);
                System.out.println("Error: Input path not found: " + inputPath);
                System.exit(1);
                return;
            }

            InstrumentProfileService profileService = InstrumentProfileService.getInstance();
            InstrumentProfile profile;
            Path outputFilePath = Paths.get(outputPath);

            if (inputFile.isDirectory()) {
                // ---- Directory mode ----------------------------------------
                Path dirPath = inputFile.toPath();

                // -c may be a path to reference_compositions.json, or absent
                Path refCompositionsPath = null;
                if (compositionOrRefPath != null && !compositionOrRefPath.isBlank()) {
                    refCompositionsPath = Paths.get(compositionOrRefPath);
                }

                System.out.println("=== LIBS Instrument Profile Generator (Directory Mode) ===");
                System.out.println("Input directory: " + inputPath);
                System.out.println("Reference compositions: " + (refCompositionsPath != null
                        ? refCompositionsPath
                        : dirPath.resolve(LIBSDataGenConstants.REFERENCE_COMPOSITIONS_DEFAULT_FILE)));
                System.out.println("Instrument name: " + instrumentName);
                System.out.printf("Baseline Correction: lambda=%.1f, p=%.4f, maxIter=%d%n",
                        lambda, p, maxIterations);
                System.out.println("Plasma Zones: " + plasmaZones);
                System.out.println();

                profile = profileService.generateProfileFromDirectory(
                        dirPath, refCompositionsPath, delimiter,
                        instrumentName, baselineCorrectionParams, plasmaZones, debugMode, outputFilePath);

            } else if (inputFile.isFile()) {
                // ---- Single-file mode (backward compatible) -----------------
                if (compositionOrRefPath == null || compositionOrRefPath.isBlank()) {
                    logger.severe("Composition string (-c) is required when -i specifies a single CSV file.");
                    System.out.println("Error: -c <composition> is required in single-file mode.");
                    System.exit(1);
                    return;
                }

                Path inputFilePath = inputFile.toPath();
                System.out.println("=== LIBS Instrument Profile Generator ===");
                System.out.println("Input file: " + inputPath);
                System.out.println("Reference composition: " + compositionOrRefPath);
                System.out.println("Instrument name: " + instrumentName);
                System.out.printf("Baseline Correction: lambda=%.1f, p=%.4f, maxIter=%d%n",
                        lambda, p, maxIterations);
                System.out.println("Plasma Zones: " + plasmaZones);
                System.out.println();

                profile = profileService.generateProfile(inputFilePath, delimiter,
                        compositionOrRefPath, instrumentName,
                        baselineCorrectionParams, plasmaZones, debugMode, materialFamilyName, outputFilePath);

            } else {
                logger.severe("Input path is neither a file nor a directory: " + inputPath);
                System.out.println("Error: Input path is neither a file nor a directory: " + inputPath);
                System.exit(1);
                return;
            }

            // ----------------------------------------------------------------
            // Print summary
            // ----------------------------------------------------------------

            System.out.println();
            System.out.println("=== Profile Generation Complete ===");
            System.out.println("Profile saved to: " + outputFilePath.toAbsolutePath());
            System.out.println();
            System.out.println("Profile Summary:");
            if (profile.getWavelengthGrid() != null && profile.getWavelengthGrid().length > 0) {
                System.out.println("  Wavelength range: " + profile.getMinWavelength() + " - " +
                        profile.getMaxWavelength() + " nm");
                System.out.println("  Wavelength points: " + profile.getWavelengthGrid().length);
            }
            System.out.println("  Number of shots analyzed: " + profile.getNumShots());
            System.out.println();

            if (profile.getMaterialFamilyProfiles() != null) {
                for (MaterialFamilyProfile familyProfile: profile.getMaterialFamilyProfiles().values()) {
                    System.out.println("  Optimized Plasma Zones for " + familyProfile.getMaterialFamilyName() + ":");
                    int zoneIdx = 0;
                    for (PlasmaZone zone : familyProfile.getPlasmaZones()) {
                        System.out.println("    Zone " + zoneIdx + " (Te=" + String.format("%.3f", zone.getTe()) + " eV):");
                        System.out.printf("      Electron Density: %.3e cm^-3%n", zone.getNe());
                        System.out.printf("      Weight: %.3f%n", zone.getWeight());
                        zoneIdx++;
                    }
                    System.out.println();
                    System.out.printf("  R^2: %.4f%n", familyProfile.getRSquaredValue());
                    System.out.printf("  RMSE: %.4f%n", familyProfile.getRmse());
                }
            }

            logger.info("Profile generation complete. Output: " + outputFilePath.toAbsolutePath());

        } catch (Exception e) {
            System.out.println("Unable to generate profile. Please check log for details.");
            logger.log(Level.SEVERE, "Failed to generate instrument profile", e);
            System.out.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

}
