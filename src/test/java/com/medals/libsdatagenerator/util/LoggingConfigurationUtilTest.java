package com.medals.libsdatagenerator.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the logging configuration utility
 */
class LoggingConfigurationUtilTest {

    private static final String TEST_LOGS_DIR = "test-logs";
    private Logger testLogger;

    @BeforeEach
    void setUp() {
        // Clean up any existing test logs directory
        cleanup();
        testLogger = Logger.getLogger(LoggingConfigurationUtilTest.class.getName());
    }

    @AfterEach
    void cleanup() {
        try {
            Path testPath = Paths.get(TEST_LOGS_DIR);
            if (Files.exists(testPath)) {
                Files.walk(testPath)
                    .map(Path::toFile)
                    .forEach(File::delete);
                Files.deleteIfExists(testPath);
            }
        } catch (IOException e) {
            // Ignore cleanup errors
        }
        
        // Clear system property
        System.clearProperty("app.logs.dir");
    }

    @Test
    void testLoggingConfigurationWithSystemProperty() {
        // Set system property to test directory
        System.setProperty("app.logs.dir", TEST_LOGS_DIR);
        
        // Configure logging
        LoggingConfigurationUtil.configureLogging();
        
        // Verify the logs directory was created
        File logsDir = new File(TEST_LOGS_DIR);
        assertTrue(logsDir.exists(), "Logs directory should be created");
        assertTrue(logsDir.isDirectory(), "Logs path should be a directory");
        
        // Test that logging works
        testLogger.info("Test log message");
        
        // Verify log file was created (there should be at least one .log file)
        File[] logFiles = logsDir.listFiles((dir, name) -> name.endsWith(".log"));
        assertNotNull(logFiles, "Log files array should not be null");
        assertTrue(logFiles.length > 0, "At least one log file should be created");
    }

    @Test
    void testLoggingConfigurationWithoutSystemProperty() {
        // Don't set system property, let it use fallback
        LoggingConfigurationUtil.configureLogging();
        
        // Verify the logs directory was created in current working directory
        File logsDir = new File("logs");
        assertTrue(logsDir.exists(), "Logs directory should be created in fallback location");
        assertTrue(logsDir.isDirectory(), "Logs path should be a directory");
        
        // Test that logging works
        testLogger.info("Test log message");
        
        // Verify log file was created
        File[] logFiles = logsDir.listFiles((dir, name) -> name.endsWith(".log"));
        assertNotNull(logFiles, "Log files array should not be null");
        assertTrue(logFiles.length > 0, "At least one log file should be created");
        
        // Clean up the fallback logs directory
        try {
            for (File logFile : logFiles) {
                logFile.delete();
            }
            logsDir.delete();
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    @Test
    void testLoggingConfigurationPreservesSystemProperty() {
        String originalValue = System.getProperty("app.logs.dir");
        String testValue = TEST_LOGS_DIR;
        
        // Set system property
        System.setProperty("app.logs.dir", testValue);
        
        // Configure logging
        LoggingConfigurationUtil.configureLogging();
        
        // Verify system property is preserved
        assertEquals(testValue, System.getProperty("app.logs.dir"), 
                     "System property should be preserved");
        
        // Restore original value if it existed
        if (originalValue != null) {
            System.setProperty("app.logs.dir", originalValue);
        }
    }
}