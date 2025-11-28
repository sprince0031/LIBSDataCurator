package com.medals.libsdatagenerator.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MatwebDataService composition parsing functionality
 */
class MatwebDataServiceTest {

    private MatwebDataService service;
    private Method parseCompositionDataMethod;

    @BeforeEach
    void setUp() throws Exception {
        service = MatwebDataService.getInstance();
        
        // Access the private parseCompositionData method using reflection
        parseCompositionDataMethod = MatwebDataService.class.getDeclaredMethod(
                "parseCompositionData", List.class, List.class, List.class);
        parseCompositionDataMethod.setAccessible(true);
    }

    @Test
    void testParseCompositionData_withRemainderComment() throws Exception {
        // Test that explicit remainder comment is respected
        List<String> elementList = Arrays.asList("Copper, Cu", "Zinc, Zn", "Lead, Pb");
        List<String> compositionList = Arrays.asList("80.0 - 85.0 %", "15.0 - 20.0 %", "0.5 %");
        List<String> comments = Arrays.asList("", "", "remainder");
        
        List<String> result = (List<String>) parseCompositionDataMethod.invoke(service, elementList, compositionList, comments);
        
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("Cu-80.0:85.0", result.get(0));
        assertEquals("Zn-15.0:20.0", result.get(1));
        assertEquals("Pb-#", result.get(2)); // Should be marked as remainder
    }

    @Test
    void testParseCompositionData_autoDetectHighestPercentage() throws Exception {
        // Test that highest percentage element is automatically marked as remainder
        // when no explicit remainder comment exists
        List<String> elementList = Arrays.asList("Copper, Cu", "Zinc, Zn", "Lead, Pb");
        List<String> compositionList = Arrays.asList("85.0 %", "10.0 %", "0.5 %");
        List<String> comments = Arrays.asList("", "", "");
        
        List<String> result = (List<String>) parseCompositionDataMethod.invoke(service, elementList, compositionList, comments);
        
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("Cu-85.0:85.0-#", result.get(0)); // Cu has highest percentage, should be marked as remainder
        assertEquals("Zn-10.0:10.0", result.get(1));
        assertEquals("Pb-0.5:0.5", result.get(2));
    }

    @Test
    void testParseCompositionData_autoDetectHighestPercentageWithRange() throws Exception {
        // Test with range values - should calculate average for comparison
        List<String> elementList = Arrays.asList("Copper, Cu", "Zinc, Zn", "Lead, Pb");
        List<String> compositionList = Arrays.asList("78.8 - 98.9 %", "0.250 - 18.0 %", "0.05 - 0.40 %");
        List<String> comments = Arrays.asList("", "", "");
        
        List<String> result = (List<String>) parseCompositionDataMethod.invoke(service, elementList, compositionList, comments);
        
        assertNotNull(result);
        assertEquals(3, result.size());
        // Cu has highest average percentage (88.85%), should be marked as remainder
        assertEquals("Cu-78.8:98.9-#", result.get(0));
        assertEquals("Zn-0.250:18.0", result.get(1));
        assertEquals("Pb-0.05:0.40", result.get(2));
    }

    @Test
    void testParseCompositionData_balanceKeyword() throws Exception {
        // Test that "balance" keyword also triggers remainder marking
        List<String> elementList = Arrays.asList("Copper, Cu", "Zinc, Zn");
        List<String> compositionList = Arrays.asList("80.0 %", "20.0 %");
        List<String> comments = Arrays.asList("balance", "");
        
        List<String> result = (List<String>) parseCompositionDataMethod.invoke(service, elementList, compositionList, comments);
        
        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("Cu-#", result.get(0)); // Should be marked as remainder due to "balance" comment
        assertEquals("Zn-20.0:20.0", result.get(1));
    }

    @Test
    void testParseCompositionData_mixedFormats() throws Exception {
        // Test with various formats including <=, >=, and ranges
        List<String> elementList = Arrays.asList("Iron, Fe", "Carbon, C", "Manganese, Mn");
        List<String> compositionList = Arrays.asList("98.0 %", "<= 0.5 %", "0.5 - 1.5 %");
        List<String> comments = Arrays.asList("", "", "");
        
        List<String> result = (List<String>) parseCompositionDataMethod.invoke(service, elementList, compositionList, comments);
        
        assertNotNull(result);
        assertEquals(3, result.size());
        assertEquals("Fe-98.0:98.0-#", result.get(0)); // Fe has highest percentage, should be marked as remainder
        assertEquals("C-0:0.5", result.get(1)); // <= format
        assertEquals("Mn-0.5:1.5", result.get(2)); // Range format
    }

    @Test
    void testParseCompositionData_elementSymbolExtraction() throws Exception {
        // Test various element string formats
        List<String> elementList = Arrays.asList(
            "Copper, Cu",
            "Zinc Zn",
            "Lead, Pb"
        );
        List<String> compositionList = Arrays.asList("80.0 %", "15.0 %", "5.0 %");
        List<String> comments = Arrays.asList("", "", "");
        
        List<String> result = (List<String>) parseCompositionDataMethod.invoke(service, elementList, compositionList, comments);
        
        assertNotNull(result);
        assertEquals(3, result.size());
        assertTrue(result.get(0).startsWith("Cu-"));
        assertTrue(result.get(1).startsWith("Zn-"));
        assertTrue(result.get(2).startsWith("Pb-"));
    }
}
