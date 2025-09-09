package com.medals.libsdatagenerator.util;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for space handling in file paths for self-contained packages
 */
public class SpacePathHandlingTest {

    @Test
    void testLinuxRunScriptQuotesJavaOpts() throws IOException {
        // Read the Linux build script
        Path scriptPath = Paths.get("build/scripts/build-local.sh");
        String scriptContent = Files.readString(scriptPath);
        
        // Verify that JAVA_OPTS is properly handled as an array with individual quoted arguments
        assertTrue(scriptContent.contains("JAVA_OPTS=(\"-Djava.util.logging.config.file=$LOG_PROPERTIES\" \"-Duser.dir=$MAIN_DIR\")"), 
                "Linux run script should use array for JAVA_OPTS to handle spaces in paths");
        assertTrue(scriptContent.contains("\"$JAVA_HOME/bin/java\" \"${JAVA_OPTS[@]}\" -jar"), 
                "Linux run script should expand JAVA_OPTS array to handle spaces in paths");
    }

    @Test
    void testWindowsRunScriptQuotesJavaOpts() throws IOException {
        // Read the Windows build script
        Path scriptPath = Paths.get("build/scripts/build-local.bat");
        String scriptContent = Files.readString(scriptPath);
        
        // Verify that individual Java options are quoted separately for Windows
        assertTrue(scriptContent.contains("set \"JAVA_OPT1=-Djava.util.logging.config.file=%%LOG_PROPERTIES%%\""), 
                "Windows run script should use separate variables for Java options to handle spaces in paths");
        assertTrue(scriptContent.contains("set \"JAVA_OPT2=-Duser.dir=%%MAIN_DIR%%\""), 
                "Windows run script should use separate variables for Java options to handle spaces in paths");
        assertTrue(scriptContent.contains("\"%%JAVA_HOME%%\\bin\\java.exe\" \"%%JAVA_OPT1%%\" \"%%JAVA_OPT2%%\" -jar"), 
                "Windows run script should quote individual Java options to handle spaces in paths");
    }
}