package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import org.apache.commons.cli.*;

import java.util.logging.Level;
import java.util.logging.Logger;

public class CmdlineParserUtil {
    private final Logger logger = Logger.getLogger(CmdlineParserUtil.class.getName());
    private String osSpecificScriptExtension = ".sh";

    public CmdlineParserUtil() {
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("win")) {
            osSpecificScriptExtension = ".bat";
        }
    }

    public CommandLine getTerminalArgHandler(String[] args) {
        Options options = new Options();

        // Composition flag
        Option composition = new Option(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT,
                LIBSDataGenConstants.CMD_OPT_COMPOSITION_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_COMPOSITION_DESC);
        composition.setRequired(false);
        options.addOption(composition);

        // Series flag
        Option series = new Option(LIBSDataGenConstants.CMD_OPT_SERIES_SHORT,
                LIBSDataGenConstants.CMD_OPT_SERIES_LONG,
                true, // This enables the optional argument value
                LIBSDataGenConstants.CMD_OPT_SERIES_DESC);
        series.setOptionalArg(true); // Actually make the argument value optional
        series.setRequired(false);
        options.addOption(series);

        // Number of variations
        options.addOption(LIBSDataGenConstants.CMD_OPT_NUM_VARS_SHORT,
                LIBSDataGenConstants.CMD_OPT_NUM_VARS_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_NUM_VARS_DESC);

        // Min Wavelength
        options.addOption(LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_SHORT,
                LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_DESC);

        // Max Wavelength
        options.addOption(LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_SHORT,
                LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_DESC);

        // Wavelength resolution
        options.addOption(LIBSDataGenConstants.CMD_OPT_RESOLUTION_SHORT,
                LIBSDataGenConstants.CMD_OPT_RESOLUTION_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_RESOLUTION_DESC);

        // Plasma temperature
        options.addOption(LIBSDataGenConstants.CMD_OPT_PLASMA_TEMP_SHORT,
                LIBSDataGenConstants.CMD_OPT_PLASMA_TEMP_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_PLASMA_TEMP_DESC);

        // Electron density
        options.addOption(LIBSDataGenConstants.CMD_OPT_ELECTRON_DENSITY_SHORT,
                LIBSDataGenConstants.CMD_OPT_ELECTRON_DENSITY_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_ELECTRON_DENSITY_DESC);

        // Wavelength unit
        options.addOption(LIBSDataGenConstants.CMD_OPT_WAVELENGTH_UNIT_SHORT,
                LIBSDataGenConstants.CMD_OPT_WAVELENGTH_UNIT_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_WAVELENGTH_UNIT_DESC);

        // Wavelength condition
        options.addOption(LIBSDataGenConstants.CMD_OPT_WAVELENGTH_CONDITION_SHORT,
                LIBSDataGenConstants.CMD_OPT_WAVELENGTH_CONDITION_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_WAVELENGTH_CONDITION_DESC);

        // Maximum ion charge
        options.addOption(LIBSDataGenConstants.CMD_OPT_MAX_ION_CHARGE_SHORT,
                LIBSDataGenConstants.CMD_OPT_MAX_ION_CHARGE_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_MAX_ION_CHARGE_DESC);

        // Minimum relative intensity
        options.addOption(LIBSDataGenConstants.CMD_OPT_MIN_RELATIVE_INTENSITY_SHORT,
                LIBSDataGenConstants.CMD_OPT_MIN_RELATIVE_INTENSITY_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_MIN_RELATIVE_INTENSITY_DESC);

        // Intensity scale
        options.addOption(LIBSDataGenConstants.CMD_OPT_INTENSITY_SCALE_SHORT,
                LIBSDataGenConstants.CMD_OPT_INTENSITY_SCALE_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_INTENSITY_SCALE_DESC);

        // Data output path
        options.addOption(LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_SHORT,
                LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_DESC);

        // Perform compositional variations
        options.addOption(LIBSDataGenConstants.CMD_OPT_COMP_VAR_SHORT,
                LIBSDataGenConstants.CMD_OPT_COMP_VAR_LONG,
                false,
                LIBSDataGenConstants.CMD_OPT_COMP_VAR_DESC);

        // Force fetch
        options.addOption(LIBSDataGenConstants.CMD_OPT_FORCE_FETCH_SHORT,
                LIBSDataGenConstants.CMD_OPT_FORCE_FETCH_LONG,
                false,
                LIBSDataGenConstants.CMD_OPT_FORCE_FETCH_DESC);

        // Scale coating
        options.addOption(LIBSDataGenConstants.CMD_OPT_SCALE_COATING_SHORT,
                LIBSDataGenConstants.CMD_OPT_SCALE_COATING_LONG,
                false,
                LIBSDataGenConstants.CMD_OPT_SCALE_COATING_DESC);

        // Append mode
        options.addOption(LIBSDataGenConstants.CMD_OPT_NO_APPEND_MODE_SHORT,
                LIBSDataGenConstants.CMD_OPT_NO_APPEND_MODE_LONG,
                false,
                LIBSDataGenConstants.CMD_OPT_NO_APPEND_MODE_DESC);

        // vary by (for compositions)
        options.addOption(LIBSDataGenConstants.CMD_OPT_VARY_BY_SHORT,
                LIBSDataGenConstants.CMD_OPT_VARY_BY_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_VARY_BY_DESC);

        // Max delta value
        options.addOption(LIBSDataGenConstants.CMD_OPT_MAX_DELTA_SHORT,
                LIBSDataGenConstants.CMD_OPT_MAX_DELTA_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_MAX_DELTA_DESC);

        // Variation mode
        options.addOption(LIBSDataGenConstants.CMD_OPT_VAR_MODE_SHORT,
                LIBSDataGenConstants.CMD_OPT_VAR_MODE_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_VAR_MODE_DESC);

        // Class label type
        options.addOption(LIBSDataGenConstants.CMD_OPT_CLASS_TYPE_SHORT,
                LIBSDataGenConstants.CMD_OPT_CLASS_TYPE_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_CLASS_TYPE_DESC);

        // Overview GUID
        options.addOption(LIBSDataGenConstants.CMD_OPT_OVERVIEW_GUID_SHORT,
                LIBSDataGenConstants.CMD_OPT_OVERVIEW_GUID_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_OVERVIEW_GUID_DESC);

        // Generate statistics
        options.addOption(LIBSDataGenConstants.CMD_OPT_GEN_STATS_SHORT,
                LIBSDataGenConstants.CMD_OPT_GEN_STATS_LONG,
                false,
                LIBSDataGenConstants.CMD_OPT_GEN_STATS_DESC);

        // Activate debug mode
        options.addOption(LIBSDataGenConstants.CMD_OPT_DEBUG_MODE_SHORT,
                LIBSDataGenConstants.CMD_OPT_DEBUG_MODE_LONG,
                false,
                LIBSDataGenConstants.CMD_OPT_DEBUG_MODE_DESC);

        // RNG Seed
        options.addOption(LIBSDataGenConstants.CMD_OPT_SEED_SHORT,
                LIBSDataGenConstants.CMD_OPT_SEED_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_SEED_DESC);

        // No. of decimal places to round comp% to
        options.addOption(LIBSDataGenConstants.CMD_OPT_N_DECIMAL_PLACES_SHORT,
                LIBSDataGenConstants.CMD_OPT_N_DECIMAL_PLACES_LONG,
                true,
                LIBSDataGenConstants.CMD_OPT_N_DECIMAL_PLACES_DESC);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter helpFormatter = new HelpFormatter();

        try {
            // Check for help first
            if (args.length == 0 || containsHelp(args)) {
                printHelpLIBSGeneration(helpFormatter, options);
                return null;
            }
            CommandLine cmd = parser.parse(options, args);

            boolean hasComposition = cmd.hasOption(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT);
            boolean hasSeries = cmd.hasOption(LIBSDataGenConstants.CMD_OPT_SERIES_SHORT);

            if (hasComposition && hasSeries) {
                System.out.println("Error: Cannot use both -c and -s options simultaneously. Please choose one.");
                System.out.println();
                printHelpLIBSGeneration(helpFormatter, options);
                return null;
            }

            if (!hasComposition && !hasSeries) {
                System.out.println("Error: Either -c (composition) or -s (series) option must be provided.");
                System.out.println();
                printHelpLIBSGeneration(helpFormatter, options);
                return null;
            }

            return cmd;
        } catch (ParseException e) {
            logger.log(Level.SEVERE, "Commandline arg parse error", e);
            System.out.println("Error: invalid command line arguments");
            System.out.println();
            printHelpLIBSGeneration(helpFormatter, options);
            return null;
        }
    }

    public void printHelpLIBSGeneration(HelpFormatter helpFormatter, Options options) {
        String header = "LIBS Data Generator\n" +
                "Version: " + this.getClass().getPackage().getImplementationVersion() + "\n\n";
        String footer = "Examples\n" +
                "# Simple composition analysis\n" +
                "run" + osSpecificScriptExtension + " -c \"Fe-80,C-20\" --min-wavelength 200 --max-wavelength 300\n" +
                "\n" +
                "# Generate compositional variations with advanced parameters\n" +
                "run" + osSpecificScriptExtension + " -c \"Fe-70,C-1.5,Mn-1,Cr-#\" -v -n 20 \\\n" +
                "--max-delta 2.0 --plasma-temperature 1.5 --electron-density 1e18\n" +
                "\n" +
                "# Use different wavelength units (Angstrom)\n" +
                "run" + osSpecificScriptExtension + " -c \"Fe-80,C-20\" --wavelength-unit 1 --resolution 2000\n" +
                "\n" +
                "# Use MatWeb GUID for composition\n" +
                "run" + osSpecificScriptExtension + " -c a1d2f3e4c5b6a7f8e9d0c1b2a3f4e5d6 -o steel_data.csv" +
                "\n" +
                "# Use the series catalog for bulk generation for multiple materials\n" +
                "run" + osSpecificScriptExtension + " -s Cr-Mo.alloy.steels -v -n 10" +
                "\n" +
                "# Provide a seed value to reproduce the same variations for each material of all series entries in the materials catalog\n" +
                "run" + osSpecificScriptExtension + " -s -v -n 10 -sd 42\n";

        helpFormatter.printHelp("run" + osSpecificScriptExtension, header, options, footer, true);
    }

    public static boolean containsHelp(String[] args) {
        for (String arg : args) {
            if ("-h".equals(arg) || "--help".equals(arg)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses command-line arguments for calibration mode.
     *
     * @param args Command-line arguments
     * @return Parsed CommandLine object, or null if parsing fails
     */
    public CommandLine parseCommandLineArgsForCalibration(String[] args) {
        Options options = new Options();

        // Input file (required)
        Option input = new Option(LIBSDataGenConstants.CMD_OPT_INPUT_SHORT,
                LIBSDataGenConstants.CMD_OPT_INPUT_LONG,
                true, LIBSDataGenConstants.CMD_OPT_INPUT_DESC);
        input.setRequired(true);
        options.addOption(input);

        // Composition (required)
        Option composition = new Option(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT,
                LIBSDataGenConstants.CMD_OPT_COMPOSITION_LONG,
                true, LIBSDataGenConstants.CMD_OPT_COMPOSITION_DESC);
        composition.setRequired(true);
        options.addOption(composition);

        // Output path (optional)
        Option output = new Option(LIBSDataGenConstants.CMD_OPT_OUTPUT_SHORT,
                LIBSDataGenConstants.CMD_OPT_OUTPUT_LONG,
                true, LIBSDataGenConstants.CMD_OPT_OUTPUT_DESC);
        output.setRequired(false);
        options.addOption(output);

        // Instrument name (optional)
        Option name = new Option(LIBSDataGenConstants.CMD_OPT_NAME_SHORT,
                LIBSDataGenConstants.CMD_OPT_NAME_LONG,
                true, LIBSDataGenConstants.CMD_OPT_NAME_DESC);
        name.setRequired(false);
        options.addOption(name);

        // Help (optional)
        Option help = new Option(LIBSDataGenConstants.CMD_OPT_HELP_SHORT,
                LIBSDataGenConstants.CMD_OPT_HELP_LONG,
                false, LIBSDataGenConstants.CMD_OPT_HELP_DESC);
        help.setRequired(false);
        options.addOption(help);

        CommandLineParser parser = new DefaultParser();
        HelpFormatter helpFormatter = new HelpFormatter();

        try {
            // Check for help first
            if (args.length == 0 || containsHelp(args)) {
                printHelpCalibration(helpFormatter, options);
                return null;
            }

            return parser.parse(options, args);

        } catch (ParseException e) {
            logger.log(Level.SEVERE, "Failed to parse command line arguments", e);
            System.out.println("Error: invalid command line arguments");
            System.out.println();
            printHelpCalibration(helpFormatter, options);
            return null;
        }
    }

    public void printHelpCalibration(HelpFormatter helpFormatter, Options options) {
        String header = """
                
                Generates an instrument profile from real LIBS measurement data.
                The profile contains wavelength grid and two-zone plasma parameters
                (Te, Ne for hot core and cool periphery) optimized to match measured spectra.
                
                """;

        String footer = "\nExamples:\n" +
                "  ./calibrate" + osSpecificScriptExtension + " \\\n" +
                "    -i sample_readings.csv -c \"Fe-98.0,C-0.5,Mn-1.0,Si-0.5\"\n\n" +
                "  ./calibrate" + osSpecificScriptExtension + " \\\n" +
                "    -i sample_readings.csv -c \"Fe-98.0,C-0.5,Mn-1.0,Si-0.5\" \\\n" +
                "    -o my_instrument_profile.json -n \"Ocean Optics HR2000\"\n\n" +
                "Note: The input CSV should have wavelength values as column headers\n" +
                "and each row should represent one shot/measurement.\n";

        helpFormatter.printHelp("calibrate" + osSpecificScriptExtension, header, options, footer, true);
    }
}
