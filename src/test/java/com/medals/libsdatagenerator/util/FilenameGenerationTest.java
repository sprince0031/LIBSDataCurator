package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.model.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FilenameGenerationTest {
    private CommonUtils commonUtils;
    
    @BeforeEach
    void setUp() {
        commonUtils = new CommonUtils();
    }
    
    @Test
    void testCrossPlatformFilenameGeneration() {
        Element carbon = new Element("Carbon", "C", 0.26, null, null, null);
        Element manganese = new Element("Manganese", "Mn", 0.65, null, null, null);
        Element silicon = new Element("Silicon", "Si", 0.22, null, null, null);
        Element iron = new Element("Iron", "Fe", 98.87, null, null, null);
        
        List<Element> composition = Arrays.asList(carbon, manganese, silicon, iron);
        String compositionId = commonUtils.buildCompositionStringForFilename(composition);
        String filename = "composition_" + compositionId + ".csv";
        
        // Print new output
        System.out.println("Composition ID: " + compositionId);
        System.out.println("Filename: " + filename);
        
        // Check that filename is Windows compatible
        assertFalse(filename.contains(":"), "Filename should not contain colons");
        assertTrue(isValidWindowsFilename(filename), "Filename should be valid on Windows");
        
        // Expected format: C-0.26;Mn-0.65;Si-0.22;Fe-98.87
        assertEquals("C-0.26;Mn-0.65;Si-0.22;Fe-98.87", compositionId);
        assertEquals("composition_C-0.26;Mn-0.65;Si-0.22;Fe-98.87.csv", filename);
    }
    
    @Test
    void testSimpleComposition() {
        Element carbon = new Element("Carbon", "C", 0.26, null, null, null);
        Element iron = new Element("Iron", "Fe", 99.74, null, null, null);
        
        List<Element> composition = Arrays.asList(carbon, iron);
        String compositionId = commonUtils.buildCompositionStringForFilename(composition);
        
        assertEquals("C-0.26;Fe-99.74", compositionId);
        assertFalse(compositionId.contains(":"));
    }
    
    private boolean isValidWindowsFilename(String filename) {
        // Windows reserved characters: < > : " | ? * \ /
        String reservedChars = "<>:\"|?*\\/";
        for (char c : filename.toCharArray()) {
            if (reservedChars.indexOf(c) != -1) {
                return false;
            }
        }
        return true;
    }
}