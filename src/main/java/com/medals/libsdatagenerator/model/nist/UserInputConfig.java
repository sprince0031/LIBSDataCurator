package com.medals.libsdatagenerator.model.nist;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.WavelengthUnit;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.WavelengthCondition;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.MaxIonCharge;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.MinRelativeIntensity;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.IntensityScale;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.ClassLabelType;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.VariationMode;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.apache.commons.cli.CommandLine;

/**
 * A single, immutable configuration object that holds all user-provided parameters.
 * @author Siddharth Prince | 02:47
 */
public class UserInputConfig {

    // --- Input Modes ---
    public final String compositionInput;
    public final String overviewGuid;
    public final boolean isCompositionMode;
    public final boolean isSeriesMode;

    // --- Variation Parameters ---
    public final boolean performVariations;
    public final int numSamples;
    public final VariationMode variationMode;
    public final ClassLabelType classLabelType;
    @Deprecated public final double varyBy;
    @Deprecated public final double maxDelta;


    // --- NIST API Parameters ---
    public final String minWavelength;
    public final String maxWavelength;
    public final String resolution;
    public final String plasmaTemp;
    public final String electronDensity;
    public final WavelengthUnit wavelengthUnit;
    public final WavelengthCondition wavelengthCondition;
    public final MaxIonCharge maxIonCharge;
    public final MinRelativeIntensity minRelativeIntensity;
    public final IntensityScale intensityScale;

    // --- File/Execution Parameters ---
    public final String csvDirPath;
    public final boolean appendMode;
    public final boolean forceFetch;

    /**
     * Constructs the configuration object by parsing the command-line arguments.
     * This is the single source of truth for all runtime parameters.
     */
    public UserInputConfig(CommandLine cmd) {
        // Determine input mode
        this.isCompositionMode = cmd.hasOption(LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT);
        this.isSeriesMode = cmd.hasOption(LIBSDataGenConstants.CMD_OPT_SERIES_SHORT);
        this.compositionInput = cmd.getOptionValue(isCompositionMode ? LIBSDataGenConstants.CMD_OPT_COMPOSITION_SHORT : LIBSDataGenConstants.CMD_OPT_SERIES_SHORT);
        this.overviewGuid = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_OVERVIEW_GUID_SHORT);

        // Variation parameters
        this.performVariations = cmd.hasOption(LIBSDataGenConstants.CMD_OPT_COMP_VAR_SHORT);
        this.numSamples = Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_NUM_VARS_SHORT, "20"));
        this.variationMode = VariationMode.fromOption(Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_VAR_MODE_SHORT, "1")));
        this.classLabelType = ClassLabelType.fromOption(Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_CLASS_TYPE_SHORT, "1")));
        this.varyBy = Double.parseDouble(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_VARY_BY_SHORT, "0.1"));
        this.maxDelta = Double.parseDouble(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MAX_DELTA_SHORT, "0.05"));


        // NIST API parameters
        this.minWavelength = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MIN_WAVELENGTH_SHORT, "200");
        this.maxWavelength = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MAX_WAVELENGTH_SHORT, "800");
        this.resolution = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_RESOLUTION_SHORT, "1000");
        this.plasmaTemp = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_PLASMA_TEMP_SHORT, "1");
        this.electronDensity = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_ELECTRON_DENSITY_SHORT, "1e17");
        this.wavelengthUnit = WavelengthUnit.fromOption(Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_WAVELENGTH_UNIT_SHORT, "1")));
        this.wavelengthCondition = WavelengthCondition.fromOption(Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_WAVELENGTH_CONDITION_SHORT, "1")));
        this.maxIonCharge = MaxIonCharge.fromOption(Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MAX_ION_CHARGE_SHORT, "2")));
        this.minRelativeIntensity = MinRelativeIntensity.fromOption(Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_MIN_RELATIVE_INTENSITY_SHORT, "3")));
        this.intensityScale = IntensityScale.fromOption(Integer.parseInt(cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_INTENSITY_SCALE_SHORT, "1")));

        // File/Execution parameters
        this.csvDirPath = cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_OUTPUT_PATH_SHORT, CommonUtils.DATA_PATH);
        this.appendMode = !cmd.hasOption(LIBSDataGenConstants.CMD_OPT_NO_APPEND_MODE_SHORT);
        this.forceFetch = cmd.hasOption(LIBSDataGenConstants.CMD_OPT_FORCE_FETCH_SHORT);
    }

}
