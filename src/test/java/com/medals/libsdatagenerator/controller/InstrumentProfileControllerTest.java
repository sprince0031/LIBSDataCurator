package com.medals.libsdatagenerator.controller;

import org.apache.commons.cli.CommandLine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstrumentProfileControllerTest {

    @Test
    void testParseCommandLineArgsValid() {
        String[] args = {"-i", "data.csv", "-c", "Fe-100", "-o", "out.json", "-n", "TestInst"};
        CommandLine cmd = InstrumentProfileController.parseCommandLineArgs(args);
        
        assertNotNull(cmd);
        assertEquals("data.csv", cmd.getOptionValue("i"));
        assertEquals("Fe-100", cmd.getOptionValue("c"));
        assertEquals("out.json", cmd.getOptionValue("o"));
        assertEquals("TestInst", cmd.getOptionValue("n"));
    }

    @Test
    void testParseCommandLineArgsMissingRequired() {
        String[] args = {"-i", "data.csv"}; // Missing -c
        
        // Should catch ParseException inside and return null
        CommandLine cmd = InstrumentProfileController.parseCommandLineArgs(args);
        assertNull(cmd);
    }

    @Test
    void testParseCommandLineArgsHelp() {
        String[] args = {"--help"};
        CommandLine cmd = InstrumentProfileController.parseCommandLineArgs(args);
        assertNull(cmd); // Returns null for help
    }
}
