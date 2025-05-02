package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.service.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests element properties loading and handling functionality
 */
class ElementPropertiesTest {

    private static final String TEST_PROPERTIES_PATH = "src/test/resources/test_elements.properties";
    private CommonUtils commonUtils;

    @BeforeEach
    void setUp() throws IOException {
        commonUtils = new CommonUtils();

        // Create a test resources directory if it doesn't exist
        Path testResources = Paths.get("src/test/resources");
        if (!Files.exists(testResources)) {
            Files.createDirectories(testResources);
        }

        // Create a test properties file with sample element data
        Properties props = new Properties();
        props.setProperty("Fe", "Iron");
        props.setProperty("C", "Carbon");
        props.setProperty("Si", "Silicon");

        try (java.io.OutputStream out = Files.newOutputStream(Paths.get(TEST_PROPERTIES_PATH))) {
            props.store(out, "Test Element Properties");
        }
    }

    @Test
    void testReadProperties() {
        Properties props = commonUtils.readProperties(TEST_PROPERTIES_PATH);

        assertNotNull(props);
        assertEquals(3, props.size());
        assertEquals("Iron", props.getProperty("Fe"));
        assertEquals("Carbon", props.getProperty("C"));
        assertEquals("Silicon", props.getProperty("Si"));
        assertNull(props.getProperty("Xx")); // Non-existent element
    }

    @Test
    void testElementCreation() {
        // Test that Element objects can be created with correct properties
        Element iron = new Element("Iron", "Fe", 98.5, 98.0, 99.0, null);

        assertEquals("Iron", iron.getName());
        assertEquals("Fe", iron.getSymbol());
        assertEquals(98.5, iron.getPercentageComposition());
        assertEquals(98.0, iron.getPercentageCompositionMin());
        assertEquals(99.0, iron.getPercentageCompositionMax());
        assertEquals("Fe:98.5", iron.toString());
    }

    @Test
    void testElementPercentageRounding() {
        // Test that percentage values are properly rounded
        Element carbon = new Element("Carbon", "C", 0.1234, null, null, null);
        assertEquals(0.123, carbon.getPercentageComposition()); // Should round to 3 decimal places by default

        // Test setting new percentage
        carbon.setPercentageComposition(0.9876);
        assertEquals(0.988, carbon.getPercentageComposition()); // Should round to 3 decimal places
    }

    @Test
    void testElementDecimalPrecision() {
        // Test customizing decimal precision
        Element silicon = new Element("Silicon", "Si", 1.23456, null, null, null);
        assertEquals(1.235, silicon.getPercentageComposition()); // Default 3 places

        silicon.setNumberDecimalPlaces(2);
        silicon.setPercentageComposition(1.23456);
        assertEquals(1.23, silicon.getPercentageComposition()); // Now 2 places
    }

    @Test
    void testMissingPropertiesFile() {
        // Test behavior when properties file doesn't exist
        Properties props = commonUtils.readProperties("non_existent_file.properties");
        assertNotNull(props);
        assertTrue(props.isEmpty());
    }
}
