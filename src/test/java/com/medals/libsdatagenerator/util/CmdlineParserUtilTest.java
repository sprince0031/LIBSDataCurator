package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.model.UserInputConfig;
import org.apache.commons.cli.CommandLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CmdlineParserUtilTest {

    private final CmdlineParserUtil parserUtil = new CmdlineParserUtil();

    @Test
    @DisplayName("Calibration parser should accept -mt, -mg, and -hc options")
    void testCalibrationParserNewOptions() {
        String[] args = {
                "-i", "/path/to/dataset/469",
                "-c", "C-0.279,Cr-11.93,Fe-87.791",
                "-mt", "Ferritic_Stainless_Steel",
                "-mg", "469",
                "-hc", "2",
                "-z", "2"
        };

        CommandLine cmd = parserUtil.parseCommandLineArgsForCalibration(args);
        assertNotNull(cmd);
        assertEquals("/path/to/dataset/469", cmd.getOptionValue("i"));
        assertEquals("Ferritic_Stainless_Steel", cmd.getOptionValue("mt"));
        assertEquals("469", cmd.getOptionValue("mg"));
        assertEquals("2", cmd.getOptionValue("hc"));
        assertEquals("2", cmd.getOptionValue("z"));
    }

    @Test
    @DisplayName("Forward generator parser and UserInputConfig should support --inject-residuals and --residuals-prob")
    void testForwardGeneratorResidualsFlags() {
        String[] args = {
                "-c", "Fe-98,C-2",
                "-ir",
                "-rp", "0.35"
        };

        CommandLine cmd = parserUtil.parseCommandLineArgsForLIBSGeneration(args);
        assertNotNull(cmd);
        assertTrue(cmd.hasOption("ir"));
        assertEquals("0.35", cmd.getOptionValue("rp"));

        UserInputConfig config = new UserInputConfig(cmd);
        assertTrue(config.injectResiduals);
        assertEquals(0.35, config.residualsProbability, 1e-6);
    }

    @Test
    @DisplayName("Forward generator defaults injectResiduals to false and residualsProbability to 0.3 when omitted")
    void testForwardGeneratorResidualsDefaults() {
        String[] args = {"-c", "Fe-98,C-2"};
        CommandLine cmd = parserUtil.parseCommandLineArgsForLIBSGeneration(args);
        assertNotNull(cmd);
        assertFalse(cmd.hasOption("ir"));

        UserInputConfig config = new UserInputConfig(cmd);
        assertFalse(config.injectResiduals);
        assertEquals(0.30, config.residualsProbability, 1e-6);
    }
}
