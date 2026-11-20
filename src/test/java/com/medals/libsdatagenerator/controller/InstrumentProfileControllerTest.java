package com.medals.libsdatagenerator.controller;

import com.medals.libsdatagenerator.util.CmdlineParserUtil;
import org.apache.commons.cli.CommandLine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentProfileControllerTest {

    @Test
    void testParseCommandLineArgsValid() {
        String[] args = {"-i", "data.csv", "-c", "Fe-100", "-o", "out.json", "-in", "TestInst"};
        CommandLine cmd = new CmdlineParserUtil().parseCommandLineArgsForCalibration(args);
        
        assertNotNull(cmd);
        assertEquals("data.csv", cmd.getOptionValue("i"));
        assertEquals("Fe-100", cmd.getOptionValue("c"));
        assertEquals("out.json", cmd.getOptionValue("o"));
        assertEquals("TestInst", cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_NAME_SHORT));
    }

    /**
     * -c is now optional at the parser level.  In single-file mode the
     * controller validates its presence; at parse time -i alone is sufficient.
     */
    @Test
    void testParseCommandLineArgsMissingComposition() {
        // -c omitted: parser should succeed (returns non-null)
        String[] args = {"-i", "data.csv"};
        CommandLine cmd = new CmdlineParserUtil().parseCommandLineArgsForCalibration(args);
        assertNotNull(cmd);
        assertNull(cmd.getOptionValue("c")); // composition not provided
    }

    @Test
    void testParseCommandLineArgsHelp() {
        String[] args = {"--help"};
        CommandLine cmd = new CmdlineParserUtil().parseCommandLineArgsForCalibration(args);
        assertNull(cmd); // Returns null for help
    }

    @Test
    void testParseCommandLineArgsDirectoryMode() {
        // Directory mode: -i is a directory path, -c optionally gives the
        // reference_compositions.json path
        String[] args = {"-i", "/data/measurements", "-c", "/data/reference_compositions.json",
                "-in", "MyInstrument"};
        CommandLine cmd = new CmdlineParserUtil().parseCommandLineArgsForCalibration(args);

        assertNotNull(cmd);
        assertEquals("/data/measurements", cmd.getOptionValue("i"));
        assertEquals("/data/reference_compositions.json", cmd.getOptionValue("c"));
        assertEquals("MyInstrument", cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_NAME_SHORT));
    }

    @Test
    void testParseCommandLineArgsDirectoryModeNoRefPath() {
        // Directory mode without explicit -c: parser should still succeed
        String[] args = {"-i", "/data/measurements", "-in", "MyInstrument"};
        CommandLine cmd = new CmdlineParserUtil().parseCommandLineArgsForCalibration(args);

        assertNotNull(cmd);
        assertEquals("/data/measurements", cmd.getOptionValue("i"));
        assertNull(cmd.getOptionValue("c"));
    }

    @Test
    void testIsCompositionStringWithRangesContainingColons() {
        String rangeComp = "Fe-0:0.55,Si-6.5:7.5,Mn-0:0.35,Ni-0:0.15,Ti-0:0.25,Cu-0:0.2,Pb-0:0.15,Mg-0.2:0.65,Zn-0:0.15,Sn-0:0.05,Ca-0.05,V-0.05,Zr-0.05,Al-#";
        assertTrue(InstrumentProfileController.isCompositionString(rangeComp),
                "Composition string with colon ranges should be identified as a composition string without throwing InvalidPathException");
    }

    @Test
    void testIsCompositionStringWithSimpleComposition() {
        String simpleComp = "Fe-80,C-20";
        assertTrue(InstrumentProfileController.isCompositionString(simpleComp),
                "Simple composition string should be identified as a composition string");
    }

    @Test
    void testIsCompositionStringWithJsonPath() {
        assertFalse(InstrumentProfileController.isCompositionString("conf/reference_compositions.json"));
        assertFalse(InstrumentProfileController.isCompositionString("C:\\path\\to\\reference.json"));
    }

    @Test
    void testIsCompositionStringWithNullOrBlank() {
        assertFalse(InstrumentProfileController.isCompositionString(null));
        assertFalse(InstrumentProfileController.isCompositionString(""));
        assertFalse(InstrumentProfileController.isCompositionString("   "));
    }
}
