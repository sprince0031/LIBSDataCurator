package com.medals.libsdatagenerator.controller;

import com.medals.libsdatagenerator.util.CmdlineParserUtil;
import org.apache.commons.cli.CommandLine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentProfileControllerTest {

    @Test
    void testParseCommandLineArgsValid() {
        String[] args = {"-i", "data.csv", "-c", "Fe-100", "-o", "out.json", "-n", "TestInst"};
        CommandLine cmd = new CmdlineParserUtil().parseCommandLineArgsForCalibration(args);
        
        assertNotNull(cmd);
        assertEquals("data.csv", cmd.getOptionValue("i"));
        assertEquals("Fe-100", cmd.getOptionValue("c"));
        assertEquals("out.json", cmd.getOptionValue("o"));
        assertEquals("TestInst", cmd.getOptionValue("n"));
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
                "-n", "MyInstrument"};
        CommandLine cmd = new CmdlineParserUtil().parseCommandLineArgsForCalibration(args);

        assertNotNull(cmd);
        assertEquals("/data/measurements", cmd.getOptionValue("i"));
        assertEquals("/data/reference_compositions.json", cmd.getOptionValue("c"));
        assertEquals("MyInstrument", cmd.getOptionValue("n"));
    }

    @Test
    void testParseCommandLineArgsDirectoryModeNoRefPath() {
        // Directory mode without explicit -c: parser should still succeed
        String[] args = {"-i", "/data/measurements", "-n", "MyInstrument"};
        CommandLine cmd = new CmdlineParserUtil().parseCommandLineArgsForCalibration(args);

        assertNotNull(cmd);
        assertEquals("/data/measurements", cmd.getOptionValue("i"));
        assertNull(cmd.getOptionValue("c"));
    }
}
