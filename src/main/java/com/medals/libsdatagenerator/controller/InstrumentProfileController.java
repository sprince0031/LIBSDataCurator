package com.medals.libsdatagenerator.controller;

import com.medals.libsdatagenerator.model.InstrumentProfile;
import com.medals.libsdatagenerator.service.InstrumentProfileService;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.apache.commons.cli.*;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controller for instrument profile calibration mode.
 * Standalone entry point for generating instrument profiles from real LIBS data.
 * 
 * Usage: java -cp LIBSDataCurator.jar com.medals.libsdatagenerator.controller.InstrumentProfileController
 *        -i <sample_csv_path> -c <composition> [-o <output_path>] [-n <instrument_name>]
 * 
 * @author Siddharth Prince | Generated for issue #84
 */
public class InstrumentProfileController {

    private static final Logger logger = Logger.getLogger(InstrumentProfileController.class.getName());

    // CLI option constants
    private static final String CMD_OPT_INPUT_SHORT = "i";
    private static final String CMD_OPT_INPUT_LONG = "input";
    private static final String CMD_OPT_INPUT_DESC = "Path to sample LIBS measurement CSV file containing real instrument readings";

    private static final String CMD_OPT_COMPOSITION_SHORT = "c";
    private static final String CMD_OPT_COMPOSITION_LONG = "composition";
    private static final String CMD_OPT_COMPOSITION_DESC = "Exact composition of the reference material in format 'Element1-Percentage1,Element2-Percentage2,...'";

    private static final String CMD_OPT_OUTPUT_SHORT = "o";
    private static final String CMD_OPT_OUTPUT_LONG = "output";
    private static final String CMD_OPT_OUTPUT_DESC = "Output path for the instrument profile JSON file (default: ./instrument_profile.json)";

    private static final String CMD_OPT_NAME_SHORT = "n";
    private static final String CMD_OPT_NAME_LONG = "name";
    private static final String CMD_OPT_NAME_DESC = "Name or identifier for the instrument";

    private static final String CMD_OPT_HELP_SHORT = "h";
    private static final String CMD_OPT_HELP_LONG = "help";
    private static final String CMD_OPT_HELP_DESC = "Show this help message";

    private static String outputDir = CommonUtils.HOME_PATH + File.separator + ".." + File.separator + "conf";

    public static void main(String[] args) {
        logger.info("Starting LIBS Instrument Profile Calibration...");
        
        CommandLine cmd = parseCommandLineArgs(args);
        if (cmd == null) {
            System.exit(1);
            return;
        }
        
        try {
            // Get input parameters
            String inputPath = cmd.getOptionValue(CMD_OPT_INPUT_SHORT);
            String composition = cmd.getOptionValue(CMD_OPT_COMPOSITION_SHORT);
            String outputPath = cmd.getOptionValue(CMD_OPT_OUTPUT_SHORT,  outputDir + File.separator + "instrument_profile.json");
            String instrumentName = cmd.getOptionValue(CMD_OPT_NAME_SHORT, "Unknown");
            
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
            System.out.println();
            
            InstrumentProfile profile = profileService.generateProfile(inputFilePath, composition, instrumentName);
            
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
            System.out.println("  Wavelength points: " + profile.getWavelengthGrid().size());
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
            System.out.printf("  Fit Score: %.4f%n", profile.getFitScore());
            System.out.printf("  RMSE: %.4f%n", profile.getRmse());
            
            logger.info("Profile generation complete. Output: " + outputFilePath.toAbsolutePath());
            
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to generate instrument profile", e);
            System.out.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Parses command-line arguments for calibration mode.
     * 
     * @param args Command-line arguments
     * @return Parsed CommandLine object, or null if parsing fails
     */
    private static CommandLine parseCommandLineArgs(String[] args) {
        Options options = new Options();
        
        // Input file (required)
        Option input = new Option(CMD_OPT_INPUT_SHORT, CMD_OPT_INPUT_LONG, true, CMD_OPT_INPUT_DESC);
        input.setRequired(true);
        options.addOption(input);
        
        // Composition (required)
        Option composition = new Option(CMD_OPT_COMPOSITION_SHORT, CMD_OPT_COMPOSITION_LONG, true, CMD_OPT_COMPOSITION_DESC);
        composition.setRequired(true);
        options.addOption(composition);
        
        // Output path (optional)
        Option output = new Option(CMD_OPT_OUTPUT_SHORT, CMD_OPT_OUTPUT_LONG, true, CMD_OPT_OUTPUT_DESC);
        output.setRequired(false);
        options.addOption(output);
        
        // Instrument name (optional)
        Option name = new Option(CMD_OPT_NAME_SHORT, CMD_OPT_NAME_LONG, true, CMD_OPT_NAME_DESC);
        name.setRequired(false);
        options.addOption(name);
        
        // Help (optional)
        Option help = new Option(CMD_OPT_HELP_SHORT, CMD_OPT_HELP_LONG, false, CMD_OPT_HELP_DESC);
        help.setRequired(false);
        options.addOption(help);
        
        CommandLineParser parser = new DefaultParser();
        HelpFormatter helpFormatter = new HelpFormatter();
        
        try {
            // Check for help first
            if (args.length == 0 || containsHelp(args)) {
                printHelp(helpFormatter, options);
                return null;
            }
            
            return parser.parse(options, args);
            
        } catch (ParseException e) {
            logger.log(Level.SEVERE, "Failed to parse command line arguments", e);
            System.out.println("Error: " + e.getMessage());
            System.out.println();
            printHelp(helpFormatter, options);
            return null;
        }
    }

    private static boolean containsHelp(String[] args) {
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static void printHelp(HelpFormatter helpFormatter, Options options) {
        String header = "\nGenerates an instrument profile from real LIBS measurement data.\n" +
                "The profile contains wavelength grid and two-zone plasma parameters\n" +
                "(Te, Ne for hot core and cool periphery) optimized to match measured spectra.\n\n";
        
        String footer = "\nExamples:\n" +
                "  java -cp LIBSDataCurator.jar " + InstrumentProfileController.class.getName() + " \\\n" +
                "    -i sample_readings.csv -c \"Fe-98.0,C-0.5,Mn-1.0,Si-0.5\"\n\n" +
                "  java -cp LIBSDataCurator.jar " + InstrumentProfileController.class.getName() + " \\\n" +
                "    -i sample_readings.csv -c \"Fe-98.0,C-0.5,Mn-1.0,Si-0.5\" \\\n" +
                "    -o my_instrument_profile.json -n \"Ocean Optics HR2000\"\n\n" +
                "Note: The input CSV should have wavelength values as column headers\n" +
                "and each row should represent one shot/measurement.\n";
        
        helpFormatter.printHelp("java -cp LIBSDataCurator.jar " + InstrumentProfileController.class.getName(),
                header, options, footer, true);
    }
}
