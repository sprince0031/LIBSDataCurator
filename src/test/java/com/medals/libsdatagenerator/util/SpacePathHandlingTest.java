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
        
        // Verify that JAVA_OPTS is quoted in the final java command
        assertTrue(scriptContent.contains("\"$JAVA_HOME/bin/java\" \"$JAVA_OPTS\" -jar"), 
                "Linux run script should quote $JAVA_OPTS to handle spaces in paths");
    }

    @Test
    void testWindowsRunScriptQuotesJavaOpts() throws IOException {
        // Read the Windows build script
        Path scriptPath = Paths.get("build/scripts/build-local.bat");
        String scriptContent = Files.readString(scriptPath);
        
        // Verify that JAVA_OPTS is quoted in the final java command
        assertTrue(scriptContent.contains("\"%%JAVA_HOME%%\\bin\\java.exe\" \"%%JAVA_OPTS%%\" -jar"), 
                "Windows run script should quote %%JAVA_OPTS%% to handle spaces in paths");
    }
}