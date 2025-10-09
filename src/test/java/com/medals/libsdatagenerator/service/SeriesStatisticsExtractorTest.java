package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.model.SeriesStatistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SeriesStatisticsExtractor compound filtering functionality
 */
class SeriesStatisticsExtractorTest {

    private SeriesStatisticsExtractor extractor;
    private Method extractElementSymbolMethod;

    @BeforeEach
    void setUp() throws Exception {
        extractor = new SeriesStatisticsExtractor();
        
        // Access the private extractElementSymbol method using reflection
        extractElementSymbolMethod = SeriesStatisticsExtractor.class.getDeclaredMethod(
                "extractElementSymbol", String.class);
        extractElementSymbolMethod.setAccessible(true);
    }

    @Test
    void testExtractElementSymbol_validElement() throws Exception {
        // Test valid single elements
        String result1 = (String) extractElementSymbolMethod.invoke(extractor, "Copper, Cu");
        assertEquals("Cu", result1);
        
        String result2 = (String) extractElementSymbolMethod.invoke(extractor, "Iron, Fe");
        assertEquals("Fe", result2);
        
        String result3 = (String) extractElementSymbolMethod.invoke(extractor, "Carbon C");
        assertEquals("C", result3);
    }

    @Test
    void testExtractElementSymbol_compoundWithPlus() throws Exception {
        // Test that compounds with "+" are filtered out
        String result = (String) extractElementSymbolMethod.invoke(extractor, "Cobalt + Nickel, Co + Ni");
        assertNull(result, "Compound with '+' should be filtered out");
    }

    @Test
    void testExtractElementSymbol_compoundWithDigits() throws Exception {
        // Test that compounds with digits (like oxides) are filtered out
        String result1 = (String) extractElementSymbolMethod.invoke(extractor, "Aluminum Oxide, Al2O3");
        assertNull(result1, "Oxide compound (Al2O3) should be filtered out");
        
        String result2 = (String) extractElementSymbolMethod.invoke(extractor, "Silicon Dioxide, SiO2");
        assertNull(result2, "Oxide compound (SiO2) should be filtered out");
    }

    @Test
    void testExtractElementSymbol_invalidCompound() throws Exception {
        // Test that invalid element symbols are filtered out
        String result = (String) extractElementSymbolMethod.invoke(extractor, "Tungsten Carbide, WC");
        // WC is not a valid element symbol in the periodic table, should be filtered
        assertNull(result, "Compound (WC) should be filtered out");
    }

    @Test
    void testExtractElementSymbol_emptyOrNull() throws Exception {
        // Test edge cases
        String result1 = (String) extractElementSymbolMethod.invoke(extractor, (String) null);
        assertNull(result1, "Null input should return null");
        
        String result2 = (String) extractElementSymbolMethod.invoke(extractor, "");
        assertNull(result2, "Empty string should return null");
        
        String result3 = (String) extractElementSymbolMethod.invoke(extractor, "   ");
        assertNull(result3, "Whitespace string should return null");
    }

    @Test
    void testExtractStatistics_filterCompounds() {
        // Test that extractStatistics filters out compound entries
        List<String> elementList = Arrays.asList(
            "Copper, Cu",               // Valid
            "Cobalt + Nickel, Co + Ni", // Compound - should be filtered
            "Zinc, Zn",                 // Valid
            "Aluminum Oxide, Al2O3",    // Compound - should be filtered
            "Iron, Fe"                  // Valid
        );
        
        List<String> compositionList = Arrays.asList(
            "78.8 - 98.9 %",
            "0.1 - 5.0 %",
            "0.250 - 18.0 %",
            "0.05 - 0.40 %",
            "0.5 - 2.0 %"
        );
        
        List<String> comments = Arrays.asList(
            "Average value: 88.85 % Grade Count:100",
            "Average value: 2.55 % Grade Count:100",
            "Average value: 9.125 % Grade Count:100",
            "Average value: 0.225 % Grade Count:100",
            "Average value: 1.25 % Grade Count:100"
        );
        
        SeriesStatistics stats = extractor.extractStatistics(
            elementList, compositionList, comments, "Test Series", "test-guid"
        );
        
        assertNotNull(stats);
        // Should only have 3 valid elements (Cu, Zn, Fe), compounds should be filtered out
        assertEquals(3, stats.getElementCount(), "Should only have 3 valid elements");
        
        // Verify that only valid elements are included
        assertTrue(stats.hasElement("Cu"));
        assertTrue(stats.hasElement("Zn"));
        assertTrue(stats.hasElement("Fe"));
        assertFalse(stats.hasElement("Co")); // Part of compound, should be filtered
        assertFalse(stats.hasElement("Ni")); // Part of compound, should be filtered
        assertFalse(stats.hasElement("Al")); // Part of oxide, should be filtered
    }

    @Test
    void testExtractStatistics_allValidElements() {
        // Test with all valid elements
        List<String> elementList = Arrays.asList("Copper, Cu", "Zinc, Zn", "Iron, Fe");
        List<String> compositionList = Arrays.asList(
            "78.8 - 98.9 %",
            "0.250 - 18.0 %",
            "0.5 - 2.0 %"
        );
        List<String> comments = Arrays.asList(
            "Average value: 88.85 % Grade Count:100",
            "Average value: 9.125 % Grade Count:100",
            "Average value: 1.25 % Grade Count:100"
        );
        
        SeriesStatistics stats = extractor.extractStatistics(
            elementList, compositionList, comments, "Test Series", "test-guid"
        );
        
        assertNotNull(stats);
        assertEquals(3, stats.getElementCount(), "All 3 elements should be included");
        assertTrue(stats.hasElement("Cu"));
        assertTrue(stats.hasElement("Zn"));
        assertTrue(stats.hasElement("Fe"));
    }

    @Test
    void testValidateStatistics_validData() {
        // Test validation of valid statistics
        List<String> elementList = Arrays.asList("Copper, Cu", "Zinc, Zn");
        List<String> compositionList = Arrays.asList("80.0 - 90.0 %", "10.0 - 20.0 %");
        List<String> comments = Arrays.asList(
            "Average value: 85.0 % Grade Count:50",
            "Average value: 15.0 % Grade Count:50"
        );
        
        SeriesStatistics stats = extractor.extractStatistics(
            elementList, compositionList, comments, "Test Series", "test-guid"
        );
        
        assertTrue(extractor.validateStatistics(stats), "Valid statistics should pass validation");
    }
}
