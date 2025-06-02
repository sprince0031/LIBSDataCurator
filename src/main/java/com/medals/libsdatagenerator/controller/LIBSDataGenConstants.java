package com.medals.libsdatagenerator.controller;

import java.util.Collections;
import java.util.HashMap;
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
    public static final String CMD_OPT_COMPOSITION_DESC = "Composition of the material in the format " +
            "\"Element1-Percentage1,Element2-Percentage2,...\". " +
            "Percentage can be a single value or a range in the format min:max. " +
            "Use # for the last element to auto-calculate to 100%." +
            "OR input a GUID from the URL of a material's datasheet from matweb.com";
    public static final String CMD_OPT_NUM_VARS_SHORT = "n";
    public static final String CMD_OPT_NUM_VARS_LONG = "num-vars";
    public static final String CMD_OPT_NUM_VARS_DESC = "Number of variations to generate.";
    public static final String CMD_OPT_MIN_WAVELENGTH_SHORT = "min";
    public static final String CMD_OPT_MIN_WAVELENGTH_LONG = "min-wavelength";
    public static final String CMD_OPT_MIN_WAVELENGTH_DESC = "Minimum wavelength (Nm)";
    public static final String CMD_OPT_MAX_WAVELENGTH_SHORT = "max";
    public static final String CMD_OPT_MAX_WAVELENGTH_LONG = "max-wavelength";
    public static final String CMD_OPT_MAX_WAVELENGTH_DESC = "Maximum wavelength (Nm)";
    public static final String CMD_OPT_OUTPUT_PATH_SHORT = "o";
    public static final String CMD_OPT_OUTPUT_PATH_LONG = "output";
    public static final String CMD_OPT_OUTPUT_PATH_DESC = "Path to save CSV file";
    public static final String CMD_OPT_COMP_VAR_SHORT = "v";
    public static final String CMD_OPT_COMP_VAR_LONG = "compvar";
    public static final String CMD_OPT_COMP_VAR_DESC = "Perform compositional variations to input composition " +
            "and save extensive data.";
    public static final String CMD_OPT_NO_APPEND_MODE_SHORT = "na";
    public static final String CMD_OPT_NO_APPEND_MODE_LONG = "no-append-mode";
    public static final String CMD_OPT_NO_APPEND_MODE_DESC = "Do not run utility in append mode where " +
            "existing master csv will be appended to and not overwritten. Append mode is the default setting.";
    public static final String CMD_OPT_FORCE_FETCH_SHORT = "ff";
    public static final String CMD_OPT_FORCE_FETCH_LONG = "force-fetch";
    public static final String CMD_OPT_FORCE_FETCH_DESC = "Will force re-downloading of individual spectrum data " +
            "for every composition even if data is available locally in the /data directory.";
    public static final String CMD_OPT_VARY_BY_SHORT = "vb";
    public static final String CMD_OPT_VARY_BY_LONG = "vary-by";
    public static final String CMD_OPT_VARY_BY_DESC = "By how much each compositional variation for percentage weight" +
            " should be varied by.";
    public static final String CMD_OPT_MAX_DELTA_SHORT = "md";
    public static final String CMD_OPT_MAX_DELTA_LONG = "max-delta";
    public static final String CMD_OPT_MAX_DELTA_DESC = "Upper and lower (+-) limits to the variations.";
    public static final String CMD_OPT_VAR_MODE_SHORT = "vm";
    public static final String CMD_OPT_VAR_MODE_LONG = "variation-mode";
    public static final String CMD_OPT_VAR_MODE_DESC = "Chooses the variation mode: " +
            "0 - uniform dist, 1 - Gaussian sampling (default), 2 - Dirichlet sampling";
    static final String CMD_OPT_OVERVIEW_GUID_SHORT = "og";
    public static final String CMD_OPT_OVERVIEW_GUID_LONG = "overview-guid";
    public static final String CMD_OPT_OVERVIEW_GUID_DESC = "Matweb GUID for the series overview datasheet. " +
            "Required for Dirichlet sampling mode (mode 2) to get series average compositions.";

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

    public static final String NIST_LIBS_GET_CSV_BUTTON_HTML_TEXT = "ViewDataCSV";

    /**
     * #### Matweb Constants ####
     */
    public static final String MATWEB_DATASHEET_URL_BASE = "https://www.matweb.com/search/DataSheet.aspx";
    public static final String MATWEB_DATASHEET_PARAM_GUID = "MatGUID";
    // Regex to extract average value from comments like "Average value: 0.300 % Grade Count:681"
    public static final String MATWEB_AVG_REGEX = "Average value:\\s*(\\d*\\.?\\d*)\\s*%";

    /**
     * #### Miscellaneous Constants ####
     */
    public static final String MATWEB_GUID_REGEX = "^[0-9a-fA-F]{32}$"; // Regex to check a 32 bit GUID string
    public static final int STAT_VAR_MODE_UNIFORM_DIST = 0; // Uniform distribution mode (longest and unnecessary)
    public static final int STAT_VAR_MODE_GAUSSIAN_DIST = 1; // Gaussian sampling mode
    public static final int STAT_VAR_MODE_DIRICHLET_DIST = 2; // Dirichlet sampling mode

    // Default concentration parameter for Dirichlet distribution. Higher = less variance.
    public static final double DIRICHLET_BASE_CONCENTRATION = 100.0;

    // Default alpha value for elements missing an average in the overview sheet
    public static final double DIRICHLET_DEFAULT_ALPHA = 0.5; // Low value to allow wide variance

    public static final String[] STD_ELEMENT_LIST = {
            "C", "Si", "Mn", "P", "S", "Cu", "Al", "Cr", "Mo", "Ni", "V",
            "Ti", "Nb", "Co", "W", "Sn", "Pb", "B", "As", "Zr", "Bi", "Cd",
            "Se", "Fe"
    };

    // Fallback to use if Gaussian sampling chosen over Dirichlet sampling.
    @Deprecated
    public static final Map<String, Double> ELEMENT_STD_DEVS_FALLBACK;

    static {
        Map<String, Double> elements = new HashMap<>();
        elements.put("C", 0.113);
        elements.put("Mn", 0.396); 
        elements.put("Si", 0.211); 
        elements.put("Ni", 0.526); 
        elements.put("Cr", 3.212);
        elements.put("V", 0.219); 
        elements.put("Mo", 0.370); 
        elements.put("Cu", 0.242); 
        elements.put("Fe", 2.841); 
        elements.put("S", 0.05);
        elements.put("P", 0.05);
        // Add other elements with estimated SDs if needed for Gaussian fallback

        ELEMENT_STD_DEVS_FALLBACK = Collections.unmodifiableMap(elements);

    }


}
