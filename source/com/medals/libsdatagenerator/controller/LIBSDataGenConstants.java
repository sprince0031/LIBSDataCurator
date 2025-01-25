package com.medals.libsdatagenerator.controller;


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

    /**
     * #### NIST LIBS Constants ####
     */
    public static final String NIST_LIBS_FORM_URL = "https://physics.nist.gov/PhysRefData/ASD/LIBS/libs-form.html";

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

}
