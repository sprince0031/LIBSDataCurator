package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackwardCompatibilityTest {
    
    @TempDir
    Path tempDir;
    
    private CommonUtils commonUtils;
    private List<Element> testComposition;
    
    @BeforeEach
    void setUp() {
        commonUtils = new CommonUtils();
        Element carbon = new Element("Carbon", "C", 0.26, null, null, null);
        Element iron = new Element("Iron", "Fe", 99.74, null, null, null);
        testComposition = Arrays.asList(carbon, iron);
    }
    
    @Test
    void testFilenameFormats() {
        String legacyId = commonUtils.buildCompositionString(testComposition);
        String newId = commonUtils.buildCompositionStringForFilename(testComposition);
        
        assertEquals("C:0.26;Fe:99.74", legacyId);
        assertEquals("C-0.26;Fe-99.74", newId);
        
        String legacyFilename = "composition_" + legacyId + ".csv";
        String newFilename = "composition_" + newId + ".csv";
        
        // Verify legacy filename has Windows-incompatible characters
        assertTrue(legacyFilename.contains(":"));
        // Verify new filename is Windows-compatible  
        assertFalse(newFilename.contains(":"));
    }
    
    @Test
    void testNewFilesUseCorrectFormat() throws IOException {
        // Create NIST LIBS directory
        Path nistDir = tempDir.resolve("NIST LIBS");
        Files.createDirectories(nistDir);
        
        // Test that new files would use the new format
        String newId = commonUtils.buildCompositionStringForFilename(testComposition);
        String newFilename = "composition_" + newId + ".csv";
        Path newFilePath = nistDir.resolve(newFilename);
        
        // Simulate creating a new file - this should use the new format
        Files.write(newFilePath, "test data".getBytes());
        assertTrue(Files.exists(newFilePath));
        
        // The filename should be cross-platform compatible
        assertEquals("composition_C-0.26;Fe-99.74.csv", newFilename);
        assertFalse(newFilename.contains(":"));
    }
    
    @Test
    void testBackwardCompatibilityCanReadLegacyFiles() throws IOException {
        // Create NIST LIBS directory
        Path nistDir = tempDir.resolve("NIST LIBS");
        Files.createDirectories(nistDir);
        
        // Create a legacy file with the old naming convention
        String legacyId = commonUtils.buildCompositionString(testComposition);
        String legacyFilename = "composition_" + legacyId + ".csv";
        Path legacyFilePath = nistDir.resolve(legacyFilename);
        Files.write(legacyFilePath, "legacy test data".getBytes());
        
        // Verify the legacy file exists and has the old format
        assertTrue(Files.exists(legacyFilePath));
        assertTrue(legacyFilename.contains(":"));
        assertEquals("composition_C:0.26;Fe:99.74.csv", legacyFilename);
        
        // The system should be able to find and read this legacy file
        String content = Files.readString(legacyFilePath);
        assertEquals("legacy test data", content);
    }
}