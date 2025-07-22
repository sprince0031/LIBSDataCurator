package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.service.LIBSDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for composition parsing functionality in the LIBSDataGenerator.
 * These tests verify that the application correctly parses composition strings.
 */
class CompositionParserTest {

    private LIBSDataService libsDataService;

    @BeforeEach
    void setUp() {
        libsDataService = LIBSDataService.getInstance();
    }

    @Test
    void testParseValidComposition() throws Exception {
        // Test parsing a simple composition string
        String[] compositionArray = { "C-0.2", "Fe-99.8" };
        ArrayList<Element> elements = libsDataService.generateElementsList(compositionArray);

        assertNotNull(elements);
        assertEquals(2, elements.size());

        // Verify each element is parsed correctly
        assertEquals("C", elements.get(0).getSymbol());
        assertEquals(0.2, elements.get(0).getPercentageComposition());

        assertEquals("Fe", elements.get(1).getSymbol());
        assertEquals(99.8, elements.get(1).getPercentageComposition());
    }

    @Test
    void testParseCompositionWithRanges() throws Exception {
        // Test parsing composition with ranges (min:max)
        String[] compositionArray = { "C-0.1:0.3", "Fe-99.7:99.9" };
        ArrayList<Element> elements = libsDataService.generateElementsList(compositionArray);

        assertNotNull(elements);
        assertEquals(2, elements.size());

        // Verify ranges are handled correctly (midpoint value set as composition)
        assertEquals("C", elements.get(0).getSymbol());
        assertEquals(0.2, elements.get(0).getPercentageComposition());
        assertEquals(0.1, elements.get(0).getPercentageCompositionMin());
        assertEquals(0.3, elements.get(0).getPercentageCompositionMax());

        assertEquals("Fe", elements.get(1).getSymbol());
        assertEquals(99.8, elements.get(1).getPercentageComposition());
        assertEquals(99.7, elements.get(1).getPercentageCompositionMin());
        assertEquals(99.9, elements.get(1).getPercentageCompositionMax());
    }

    @Test
    void testParseRemainingPercentage() throws Exception {
        // Test that "#" symbol is handled correctly for remaining percentage
        String[] compositionArray = { "C-0.2", "Fe-#" };
        ArrayList<Element> elements = libsDataService.generateElementsList(compositionArray);

        assertNotNull(elements);
        assertEquals(2, elements.size());
        assertEquals(0.2, elements.get(0).getPercentageComposition());
        assertEquals(99.8, elements.get(1).getPercentageComposition()); // Should be 100 - 0.2 = 99.8
    }

    @Test
    void testQueryParamGeneration() {
        // Test that elements are properly converted to query parameters
        ArrayList<Element> elements = new ArrayList<>();
        elements.add(new Element("Carbon", "C", 0.2, null, null, null));
        elements.add(new Element("Iron", "Fe", 99.8, null, null, null));

        HashMap<String, String> params = libsDataService.processLIBSQueryParams(elements, "200", "800");

        // Verify composition string contains both elements in correct format
        String composition = params.get(LIBSDataGenConstants.NIST_LIBS_QUERY_PARAM_COMPOSITION);
        assertTrue(composition.contains("C:0.2"));
        assertTrue(composition.contains("Fe:99.8"));
    }

    @Test
    void testInvalidElementParsing() {
        // Test handling of invalid element symbols
        String[] compositionArray = { "XX-0.2", "Fe-99.8" };

        assertThrows(IOException.class, () -> {
            libsDataService.generateElementsList(compositionArray);
        });
    }
}
