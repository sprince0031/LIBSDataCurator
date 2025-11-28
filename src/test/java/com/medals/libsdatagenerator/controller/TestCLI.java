package com.medals.libsdatagenerator.controller;

import com.medals.libsdatagenerator.util.CommonUtils;
import org.apache.commons.cli.CommandLine;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestCLI {

    @Test
    void cliTest() {
        CommonUtils commonUtils = new CommonUtils();
        String[][] testCases = {
            {"-c", "some_guid"}, // should pass
            {"-s"}, // should pass
            {"-s", "some_series_key"}, // should pass
            {}, // should fail, neither -c nor -s
            {"-c", "some_guid", "-s", "some_series_key"}, // should fail, both -c and -s
            {"-c", "some_guid", "-s"} // should fail, both -c and -s
        };

        String[] descriptions = {
            "Test Case 1: -c some_guid (should pass)",
            "Test Case 2: -s (should pass)",
            "Test Case 3: -s some_series_key (should pass)",
            "Test Case 4: (empty args) (should fail)",
            "Test Case 5: -c some_guid -s some_series_key (should fail)",
            "Test Case 6: -c some_guid -s (should fail)"
        };

        boolean[] expectedResults = {
            true, // pass
            true, // pass
            true, // pass
            false, // fail
            false, // fail
            false  // fail
        };

        System.out.println("Starting CLI argument handler tests...");

        for (int i = 0; i < testCases.length; i++) {
            System.out.println("\n" + descriptions[i]);
            String[] currentArgs = testCases[i];
            CommandLine cmd = commonUtils.getTerminalArgHandler(currentArgs);
            boolean result = (cmd != null);

            if (result == expectedResults[i]) {
                System.out.println("Result: PASS");
            } else {
                System.out.println("Result: FAIL");
                System.out.println("  Expected: " + (expectedResults[i] ? "CommandLine object" : "null"));
                System.out.println("  Actual:   " + (result ? "CommandLine object" : "null"));
            }
        }
        System.out.println("\nCLI argument handler tests finished.");
    }

    @Test
    void testDecimalPlacesOption() {
        CommonUtils commonUtils = new CommonUtils();
        
        // Test with decimal places option
        String[] args = {"-c", "Fe-80,C-20", "-nd", "5"};
        CommandLine cmd = commonUtils.getTerminalArgHandler(args);
        
        assertNotNull(cmd, "Command line should be parsed successfully with -nd option");
        assertTrue(cmd.hasOption(LIBSDataGenConstants.CMD_OPT_N_DECIMAL_PLACES_SHORT), 
            "Should have decimal places option");
        assertEquals("5", cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_N_DECIMAL_PLACES_SHORT),
            "Decimal places value should be 5");
    }

    @Test
    void testDebugModeOption() {
        CommonUtils commonUtils = new CommonUtils();
        
        // Test with debug mode option
        String[] args = {"-c", "Fe-80,C-20", "-d"};
        CommandLine cmd = commonUtils.getTerminalArgHandler(args);
        
        assertNotNull(cmd, "Command line should be parsed successfully with -d option");
        assertTrue(cmd.hasOption(LIBSDataGenConstants.CMD_OPT_DEBUG_MODE_SHORT), 
            "Should have debug mode option");
    }

    @Test
    void testSeedOption() {
        CommonUtils commonUtils = new CommonUtils();
        
        // Test with seed option
        String[] args = {"-c", "Fe-80,C-20", "-sd", "12345"};
        CommandLine cmd = commonUtils.getTerminalArgHandler(args);
        
        assertNotNull(cmd, "Command line should be parsed successfully with -sd option");
        assertTrue(cmd.hasOption(LIBSDataGenConstants.CMD_OPT_SEED_SHORT), 
            "Should have seed option");
        assertEquals("12345", cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_SEED_SHORT),
            "Seed value should be 12345");
    }

    @Test
    void testCombinedNewOptions() {
        CommonUtils commonUtils = new CommonUtils();
        
        // Test with all new v0.9.0 options combined
        String[] args = {"-c", "Fe-80,C-20", "-nd", "4", "-d", "-sd", "42"};
        CommandLine cmd = commonUtils.getTerminalArgHandler(args);
        
        assertNotNull(cmd, "Command line should be parsed successfully with combined new options");
        assertTrue(cmd.hasOption(LIBSDataGenConstants.CMD_OPT_N_DECIMAL_PLACES_SHORT), 
            "Should have decimal places option");
        assertTrue(cmd.hasOption(LIBSDataGenConstants.CMD_OPT_DEBUG_MODE_SHORT), 
            "Should have debug mode option");
        assertTrue(cmd.hasOption(LIBSDataGenConstants.CMD_OPT_SEED_SHORT), 
            "Should have seed option");
        assertEquals("4", cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_N_DECIMAL_PLACES_SHORT));
        assertEquals("42", cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_SEED_SHORT));
    }
}
