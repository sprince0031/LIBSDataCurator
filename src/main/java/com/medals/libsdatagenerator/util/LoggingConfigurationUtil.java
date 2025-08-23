package com.medals.libsdatagenerator.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Utility class to configure logging with proper file paths for different deployment scenarios
 * @author Siddharth Prince | 23/08/25 15:00
 */
public class LoggingConfigurationUtil {

    /**
     * Configures the logging system to use the correct log directory based on system properties
     * or fallback to default locations.
     */
    public static void configureLogging() {
        try {
            // Get the logs directory from system property set by run scripts
            String logsDir = System.getProperty("app.logs.dir");
            
            if (logsDir == null || logsDir.trim().isEmpty()) {
                // Fallback: try to detect if we're running from a packaged distribution
                String userDir = System.getProperty("user.dir");
                Path userPath = Paths.get(userDir);
                
                // Check if we're in a packaged distribution structure
                Path logsPath = userPath.resolve("logs");
                if (logsPath.toFile().exists() || userPath.resolve("conf").toFile().exists()) {
                    logsDir = logsPath.toString();
                } else {
                    // Default fallback - create logs directory in current working directory
                    logsDir = userPath.resolve("logs").toString();
                }
            }
            
            // Ensure the logs directory exists
            File logsDirFile = new File(logsDir);
            if (!logsDirFile.exists()) {
                logsDirFile.mkdirs();
            }
            
            // Set the system property so any existing logging configuration can use it
            System.setProperty("app.logs.dir", logsDir);
            
            // Configure the root logger to use a FileHandler with the correct path
            Logger rootLogger = Logger.getLogger("");
            
            // Remove any existing FileHandlers to avoid conflicts
            rootLogger.getHandlers();
            
            // Create new FileHandler with the correct path
            String logFilePattern = logsDir + File.separator + "LIBSDataGenerator%g.log";
            FileHandler fileHandler = new FileHandler(logFilePattern, 3000000, 10, true);
            fileHandler.setFormatter(new CustomLogFormatter());
            fileHandler.setLevel(Level.ALL);
            
            // Add the file handler to the root logger
            rootLogger.addHandler(fileHandler);
            rootLogger.setLevel(Level.INFO);
            
        } catch (IOException e) {
            // If file logging fails, continue with console logging only
            System.err.println("Warning: Could not set up file logging: " + e.getMessage());
            System.err.println("Continuing with console logging only.");
        }
    }
}