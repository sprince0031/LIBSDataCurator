package com.medals.libsdatagenerator.controller;


import java.util.Map;

/**
 * @author Siddharth Prince | 12/17/24 11:58 AM
 */

public class LIBSDataGenConstants {

    /**
     * #### Selenium Constants ####
     */
    public static final String SELENIUM_WEB_DRIVER = "https://selenium.dev";

    /**
     * #### Cmdline  options ####
     */
    public static final String CMD_OPT_COMPOSITION_SHORT = "c";
    public static final String CMD_OPT_COMPOSITION_LONG = "composition";
    public static final String CMD_OPT_COMPOSITION_DESC = "Chemical Composition. " +
            "Input comma separated values in the format <Chemical symbol>-<Percentage>. " +
            "Final element can be represented with a '*' and the remaining percentage will be calculated.";
    public static final String CMD_OPT_MIN_WAVELENGTH_SHORT = "mi";
    public static final String CMD_OPT_MIN_WAVELENGTH_LONG = "min-wavelength";
    public static final String CMD_OPT_MIN_WAVELENGTH_DESC = "Minimum wavelength (Nm)";
    public static final String CMD_OPT_MAX_WAVELENGTH_SHORT = "ma";
    public static final String CMD_OPT_MAX_WAVELENGTH_LONG = "max-wavelength";
    public static final String CMD_OPT_MAX_WAVELENGTH_DESC = "Maximum wavelength (Nm)";
    public static final String CMD_OPT_OUTPUT_PATH_SHORT = "o";
    public static final String CMD_OPT_OUTPUT_PATH_LONG = "output";
    public static final String CMD_OPT_OUTPUT_PATH_DESC = "Path to save CSV file";
    public static final String CMD_OPT_COMP_VAR_SHORT = "v";
    public static final String CMD_OPT_COMP_VAR_LONG = "compvar";
    public static final String CMD_OPT_COMP_VAR_DESC = "Perform compositional variations to input composition and save extensive data.";
    public static final String CMD_OPT_NO_APPEND_MODE_SHORT = "na";
    public static final String CMD_OPT_NO_APPEND_MODE_LONG = "no-append-mode";
    public static final String CMD_OPT_NO_APPEND_MODE_DESC = "Do not run utility in append mode where existing master csv will be appended to and not overwritten. Append mode is the default setting.";
    public static final String CMD_OPT_FORCE_FETCH_SHORT = "ff";
    public static final String CMD_OPT_FORCE_FETCH_LONG = "force-fetch";
    public static final String CMD_OPT_FORCE_FETCH_DESC = "Will force re-downloading of individual spectrum data for every composition even if data is available locally in the /data directory.";
    public static final String CMD_OPT_VARY_BY_SHORT = "vb";
    public static final String CMD_OPT_VARY_BY_LONG = "vary-by";
    public static final String CMD_OPT_VARY_BY_DESC = "By how much each compositional variation for percentage weight should be varied by.";
    public static final String CMD_OPT_MAX_DELTA_SHORT = "md";
    public static final String CMD_OPT_MAX_DELTA_LONG = "max-delta";
    public static final String CMD_OPT_MAX_DELTA_DESC = "Upper and lower (+-) limits to the variations.";
    public static final String CMD_OPT_VAR_MODE_SHORT = "vm";
    public static final String CMD_OPT_VAR_MODE_LONG = "variation-mode";
    public static final String CMD_OPT_VAR_MODE_DESC = "Chooses the variation mode: 0 - uniform dist, 1 - Gaussian sampling (default), 2 - Dirichlet sampling";

    /**
     * #### NIST LIBS Constants ####
     */
    public static final String NIST_LIBS_FORM_URL = "https://physics.nist.gov/PhysRefData/ASD/LIBS/libs-form.html";
    public static final String NIST_LIBS_DATA_DIR = "NIST LIBS";

    // Form URL String Components
    public static final String NIST_LIBS_QUERY_URL_BASE = "https://physics.nist.gov/cgi-bin/ASD/lines1.pl";
    public static final String NIST_LIBS_QUERY_PARAM_COMPOSITION = "composition";
    public static final String NIST_LIBS_QUERY_PARAM_MYTEXT = "mytext[]";
    public static final String NIST_LIBS_QUERY_PARAM_MYPERC = "myperc[]";
    public static final String NIST_LIBS_QUERY_PARAM_SPECTRA = "spectra";
    public static final String NIST_LIBS_QUERY_PARAM_LOW_W = "low_w";
    public static final String NIST_LIBS_QUERY_PARAM_LIMITS_TYPE = "limits_type";
    public static final String NIST_LIBS_QUERY_PARAM_UPP_W = "upp_w";
    public static final String NIST_LIBS_QUERY_PARAM_SHOW_AV = "show_av";
    public static final String NIST_LIBS_QUERY_PARAM_UNIT = "unit";
    public static final String NIST_LIBS_QUERY_PARAM_RESOLUTION = "resolution";
    public static final String NIST_LIBS_QUERY_PARAM_TEMP = "temp";
    public static final String NIST_LIBS_QUERY_PARAM_EDEN = "eden";
    public static final String NIST_LIBS_QUERY_PARAM_MAXCHARGE = "maxcharge";
    public static final String NIST_LIBS_QUERY_PARAM_MIN_REL_INT = "min_rel_int";
    public static final String NIST_LIBS_QUERY_PARAM_INT_SCALE = "int_scale";
    public static final String NIST_LIBS_QUERY_PARAM_LIBS = "libs";

    /**
     * #### Miscellaneous Constants ####
     */
    public static final int STAT_VAR_MODE_UNIFORM_DIST = 0; // Uniform distribution mode (longest and unnecessary)
    public static final int STAT_VAR_MODE_GAUSSIAN_DIST = 1; // Gaussian sampling mode
    public static final int STAT_VAR_MODE_DIRICHLET_DIST = 2; // Dirichlet sampling mode

    public static final String[] STD_ELEMENT_LIST = {
            "C",
            "Si",
            "Mn",
            "P",
            "S",
            "Cu",
            "Al",
            "Cr",
            "Mo",
            "Ni",
            "V",
            "Ti",
            "Nb",
            "Co",
            "W",
            "Sn",
            "Pb",
            "B",
            "As",
            "Zr",
            "Bi",
            "Cd",
            "Se",
            "Fe"
    };

    public static final Map<String, Double> ELEMENT_STD_DEVS = Map.of(
            "C", 0.113,
            "Mn", 0.396,
            "Si", 0.211,
            "Ni", 0.526,
            "Cr", 3.212,
            "V", 0.219,
            "Mo", 0.370,
            "Cu", 0.242,
            "Fe", 2.841
    );

    public static final Map<String, Double> ELEMENT_STD_DEVS_STAINLESS_STEEL = Map.of( // TODO:
            // Need to compile properly
            "C", 0.016,
            "Mn", 0.490,
            "Si", 0.000,
            "Cr", 2.752,
            "Ni", 4.956,
            "Mo", 1.000
    );

}
