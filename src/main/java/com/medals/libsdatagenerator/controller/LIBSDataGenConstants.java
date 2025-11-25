package com.medals.libsdatagenerator.controller;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * All constants used throughout the codebase.
 *
 * @author Siddharth Prince | 12/17/24 11:58
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
    public static final String CMD_OPT_MIN_WAVELENGTH_DESC = "Minimum wavelength(Nm). Default: 200 Nm";
    public static final String CMD_OPT_MAX_WAVELENGTH_SHORT = "max";
    public static final String CMD_OPT_MAX_WAVELENGTH_LONG = "max-wavelength";
    public static final String CMD_OPT_MAX_WAVELENGTH_DESC = "Maximum wavelength(Nm). Default: 800 Nm";
    public static final String CMD_OPT_ELECTRON_DENSITY_SHORT = "ne";
    public static final String CMD_OPT_ELECTRON_DENSITY_LONG = "electron-density";
    public static final String CMD_OPT_ELECTRON_DENSITY_DESC = "Electron density(cm^-3). Default: 1e17 cm^-3";
    public static final String CMD_OPT_PLASMA_TEMP_SHORT = "te";
    public static final String CMD_OPT_PLASMA_TEMP_LONG = "plasma-temperature";
    public static final String CMD_OPT_PLASMA_TEMP_DESC = "Plasma temperature(eV). Default: 1 eV";
    public static final String CMD_OPT_RESOLUTION_SHORT = "res";
    public static final String CMD_OPT_RESOLUTION_LONG = "resolution";
    public static final String CMD_OPT_RESOLUTION_DESC = "Wavelength resolution. Default: 1000";
    public static final String CMD_OPT_WAVELENGTH_UNIT_SHORT = "wu";
    public static final String CMD_OPT_WAVELENGTH_UNIT_LONG = "wavelength-unit";
    public static final String CMD_OPT_WAVELENGTH_UNIT_DESC = """
            Unit of wavelength. Options: \
            
            1 -> Angstrom\
            
            2 -> Nanometer (default)\
            
            3 -> Micrometer""";
    public static final String CMD_OPT_WAVELENGTH_CONDITION_SHORT = "wcon";
    public static final String CMD_OPT_WAVELENGTH_CONDITION_LONG = "wavelength-condition";
    public static final String CMD_OPT_WAVELENGTH_CONDITION_DESC = """
            Condition of measurement for wavelength. Options: \
            
            1 -> Vacuum (< 200 nm) Air (200 - 2000 nm) Vacuum (> 2000 nm) (default)\
            
            2 -> Vacuum (all wavelengths)""";
    public static final String CMD_OPT_MAX_ION_CHARGE_SHORT = "mic";
    public static final String CMD_OPT_MAX_ION_CHARGE_LONG = "max-ion-charge";
    public static final String CMD_OPT_MAX_ION_CHARGE_DESC = """
            Select maximum ion charge to be included. Options: \
            
            1 -> no limit\
            
            2 -> 2+ (default)\
            
            3 -> 3+\
            
            4 -> 4+""";
    public static final String CMD_OPT_MIN_RELATIVE_INTENSITY_SHORT = "mri";
    public static final String CMD_OPT_MIN_RELATIVE_INTENSITY_LONG = "min-relative-intensity";
    public static final String CMD_OPT_MIN_RELATIVE_INTENSITY_DESC = """
            Minimum relative intensity. Ref: https://physics.nist.gov/PhysRefData/ASD/Html/libshelp.html#ADVANCED_OPT\
            
            Options: \
            
            1 -> No limit (very slow)
            
            2 -> 0.1\
            
            3 -> 0.01 (default)\
            
            4 -> 0.001""";
    public static final String CMD_OPT_INTENSITY_SCALE_SHORT = "is";
    public static final String CMD_OPT_INTENSITY_SCALE_LONG = "intensity-scale";
    public static final String CMD_OPT_INTENSITY_SCALE_DESC = """
            Intensity scale. Ref: https://physics.nist.gov/PhysRefData/ASD/Html/libshelp.html#ADVANCED_OPT\
            
            Options: \
            
            1 -> Energy flux (default)\
            
            2 -> Photon flux""";
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
    public static final String CMD_OPT_SCALE_COATING_SHORT = "dsc";
    public static final String CMD_OPT_SCALE_COATING_LONG = "dont-scale-coating";
    public static final String CMD_OPT_SCALE_COATING_DESC = "Will scale down all other elements in the composition rather " +
            "than subtracting the coating element percentage from the dominant element's percentage by default. Include flag to disable.";
    @Deprecated
    public static final String CMD_OPT_VARY_BY_SHORT = "vb";
    @Deprecated
    public static final String CMD_OPT_VARY_BY_LONG = "vary-by";
    @Deprecated
    public static final String CMD_OPT_VARY_BY_DESC = "By how much each compositional variation for percentage weight" +
            " should be varied by.";
    @Deprecated
    public static final String CMD_OPT_MAX_DELTA_SHORT = "md";
    @Deprecated
    public static final String CMD_OPT_MAX_DELTA_LONG = "max-delta";
    @Deprecated
    public static final String CMD_OPT_MAX_DELTA_DESC = "Upper and lower (+-) limits to the variations.";
    public static final String CMD_OPT_VAR_MODE_SHORT = "vm";
    public static final String CMD_OPT_VAR_MODE_LONG = "variation-mode";
    public static final String CMD_OPT_VAR_MODE_DESC = """
            Chooses the variation mode: \
            
            1 -> Dirichlet sampling (default)\
            
            2 -> Gaussian sampling""";
    public static final String CMD_OPT_OVERVIEW_GUID_SHORT = "og";
    public static final String CMD_OPT_OVERVIEW_GUID_LONG = "overview-guid";
    public static final String CMD_OPT_OVERVIEW_GUID_DESC = "Matweb GUID for the series overview datasheet. " +
            "Required for Dirichlet sampling mode (mode 1) to get series average compositions.";

    public static final String CMD_OPT_CLASS_TYPE_SHORT = "ct";
    public static final String CMD_OPT_CLASS_TYPE_LONG = "class-type";
    public static final String CMD_OPT_CLASS_TYPE_DESC = """
            Chooses the class label type for dataset generation: \
            
            1 -> Composition percentages (default) - multi-output regression with element weight percentages\
            
            2 -> Material grade name - multi-class classification with specific material grades (e.g., AISI 4140)\
            
            3 -> Material type - multi-class classification with broader material categories (e.g., Carbon steel)\
            """;
    public static final String CMD_OPT_SERIES_SHORT = "s";
    public static final String CMD_OPT_SERIES_LONG = "series";
    public static final String CMD_OPT_SERIES_DESC = "Specify a steel series key, a comma-separated list of series keys (e.g., 'key1,key2'), or no argument to process all series from the properties file.";
    public static final String CMD_OPT_GEN_STATS_SHORT = "gs";
    public static final String CMD_OPT_GEN_STATS_LONG = "gen-stats";
    public static final String CMD_OPT_GEN_STATS_DESC = "Generate and save statistics (mean, std dev) for the dataset.";
    public static final String CMD_OPT_SEED_SHORT = "sd";
    public static final String CMD_OPT_SEED_LONG = "seed";
    public static final String CMD_OPT_SEED_DESC = "Seed for the samplers to ensure reproducibility.";
    public static final String MATERIALS_CATALOGUE_FILE_NAME = "materials_catalogue.properties";
    public static final String DATASET_STATISTICS_FILE_NAME = "dataset_stats.json";

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
    
    // HTML element selectors for recalculation form
    public static final String NIST_LIBS_RECALC_RESOLUTION_INPUT_NAME = "resolution";
    public static final String NIST_LIBS_RECALC_BUTTON_NAME = "recalc";
    public static final String NIST_LIBS_RECALC_ELEMENT_PREFIX = "myperc";  // For element percentage inputs

    /**
     * #### Matweb Constants ####
     */
    public static final String MATWEB_HOME_URL = "https://www.matweb.com";
    public static final String MATWEB_DATASHEET_URL_BASE = "https://www.matweb.com/search/DataSheet.aspx";
    public static final String MATWEB_DATASHEET_PARAM_GUID = "MatGUID";
    public static final String MATWEB_OVERVIEW_DATASHEET_PAGE_TITLE_PREFIX = "Overview of materials for ";
    public static final String MATWEB_GUID_REGEX = "^[0-9a-fA-F]{32}$"; // Regex to check a 32 bit GUID string
    // Regex to extract average value from comments like "Average value: 0.300 % Grade Count:681"
    public static final String MATWEB_AVG_REGEX = "Average value:\\s*(\\d+(?:\\.\\d+)?)\\s*%?\\s*.*?Grade Count:\\s*(\\d+)";
    public static final String MATWEB_ALT_AVG_REGEX = "Average.*?:\\s*(\\d+(?:\\.\\d+)?).*?Count.*?:\\s*(\\d+)";

    /**
     * #### Archive.org Constants ####
     */
    public static final String ARCHIVE_API_BASE_URL = "https://web.archive.org/cdx/search/cdx";
    public static final String ARCHIVE_BASE_URL = "https://web.archive.org/web/";

    /**
     * #### Miscellaneous Constants ####
     */
    public static final String MASTER_DATASET_FILENAME = "master_dataset.csv";
    public static final String INPUT_COMPOSITION_STRING_REGEX = "^([A-Za-z]{1,2}-((100(\\.0{1,5})?|[0-9]{1,2}(\\.\\d{1,5})?)%?|[#]))(?:,([A-Za-z]{1,2}-((100(\\.0{1,5})?|[0-9]{1,2}(\\.\\d{1,5})?)%?|[#])))*$";
    public static final String COATED_SERIES_KEY_PATTERN = "([A-Za-z]+)-([0-9]+(?:\\.[0-9]+)?)\\.coated\\.(.*?)";
    public static final String DIRECT_ENTRY = "Direct-entry"; // Used to mark MatGUID series list entry via -c option

    public static final String[] STD_ELEMENT_LIST = {
            "C", "Si", "Mn", "P", "S", "Cu", "Al", "Cr", "Mo", "Ni", "V",
            "Ti", "Nb", "Co", "W", "Sn", "Pb", "B", "As", "Zr", "Bi", "Cd",
            "Se", "Fe", "Zn", "N"
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
