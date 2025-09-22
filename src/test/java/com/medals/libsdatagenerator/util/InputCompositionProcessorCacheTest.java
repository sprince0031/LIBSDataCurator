package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for verifying the caching functionality in InputCompositionProcessor
 * @author Copilot
 */
public class InputCompositionProcessorCacheTest {

    private InputCompositionProcessor processor;
    private List<MaterialGrade> materialGrades;

    @BeforeEach
    void setUp() {
        processor = InputCompositionProcessor.getInstance();
        materialGrades = new ArrayList<>();
    }

    @Test
    void testFindMaterialByGuid_ExistingGuid() throws Exception {
        // Create test composition
        List<Element> testComposition = createTestComposition();
        
        // Create a test MaterialGrade with a specific GUID
        String testGuid = "12345ABC";
        MaterialGrade testGrade = new MaterialGrade(testComposition, testGuid, "overviewGuid", "seriesKey");
        testGrade.setMaterialName("Test Material");
        testGrade.setMaterialAttributes(new String[]{"Attribute1", "Attribute2"});
        materialGrades.add(testGrade);

        // Add another material with different GUID
        MaterialGrade otherGrade = new MaterialGrade(testComposition, "67890XYZ", "overviewGuid2", "seriesKey2");
        materialGrades.add(otherGrade);

        // Use reflection to access the private method
        Method findMethod = InputCompositionProcessor.class.getDeclaredMethod("findMaterialByGuid", List.class, String.class);
        findMethod.setAccessible(true);

        // Test finding the existing GUID
        MaterialGrade found = (MaterialGrade) findMethod.invoke(processor, materialGrades, testGuid);
        
        assertNotNull(found, "Should find the material with existing GUID");
        assertEquals(testGuid, found.getMatGUID(), "Found material should have the correct GUID");
        assertEquals("Test Material", found.getMaterialName(), "Found material should have the correct name");
        assertArrayEquals(new String[]{"Attribute1", "Attribute2"}, found.getMaterialAttributes(), "Found material should have the correct attributes");
    }

    @Test
    void testFindMaterialByGuid_NonExistingGuid() throws Exception {
        // Create test composition
        List<Element> testComposition = createTestComposition();
        
        // Create a test MaterialGrade with a specific GUID
        MaterialGrade testGrade = new MaterialGrade(testComposition, "12345ABC", "overviewGuid", "seriesKey");
        materialGrades.add(testGrade);

        // Use reflection to access the private method
        Method findMethod = InputCompositionProcessor.class.getDeclaredMethod("findMaterialByGuid", List.class, String.class);
        findMethod.setAccessible(true);

        // Test finding a non-existing GUID
        MaterialGrade found = (MaterialGrade) findMethod.invoke(processor, materialGrades, "NONEXISTENT");
        
        assertNull(found, "Should return null for non-existing GUID");
    }

    @Test
    void testFindMaterialByGuid_EmptyList() throws Exception {
        // Use reflection to access the private method
        Method findMethod = InputCompositionProcessor.class.getDeclaredMethod("findMaterialByGuid", List.class, String.class);
        findMethod.setAccessible(true);

        // Test finding in empty list
        MaterialGrade found = (MaterialGrade) findMethod.invoke(processor, materialGrades, "12345ABC");
        
        assertNull(found, "Should return null when searching in empty list");
    }

    @Test
    void testFindMaterialByGuid_NullGuid() throws Exception {
        // Create test composition
        List<Element> testComposition = createTestComposition();
        
        // Create a test MaterialGrade with a non-null GUID
        MaterialGrade testGrade = new MaterialGrade(testComposition, "12345ABC", "overviewGuid", "seriesKey");
        materialGrades.add(testGrade);

        // Use reflection to access the private method
        Method findMethod = InputCompositionProcessor.class.getDeclaredMethod("findMaterialByGuid", List.class, String.class);
        findMethod.setAccessible(true);

        // Test finding with null GUID
        MaterialGrade found = (MaterialGrade) findMethod.invoke(processor, materialGrades, (String) null);
        
        assertNull(found, "Should return null when searching for null GUID");
    }

    @Test
    void testFindMaterialByGuid_MaterialWithNullGuid() throws Exception {
        // Create test composition
        List<Element> testComposition = createTestComposition();
        
        // Create a test MaterialGrade with null GUID (edge case)
        MaterialGrade testGrade = new MaterialGrade(testComposition, null, "overviewGuid", "seriesKey");
        materialGrades.add(testGrade);

        // Use reflection to access the private method
        Method findMethod = InputCompositionProcessor.class.getDeclaredMethod("findMaterialByGuid", List.class, String.class);
        findMethod.setAccessible(true);

        // Test finding with various GUIDs
        MaterialGrade found1 = (MaterialGrade) findMethod.invoke(processor, materialGrades, "12345ABC");
        MaterialGrade found2 = (MaterialGrade) findMethod.invoke(processor, materialGrades, (String) null);
        
        assertNull(found1, "Should return null when material has null GUID but searching for valid GUID");
        assertNull(found2, "Should return null when searching for null GUID (can't cache materials without GUID)");
    }

    private List<Element> createTestComposition() {
        List<Element> composition = new ArrayList<>();
        
        Element iron = new Element("Iron", "Fe", 80.0, 75.0, 85.0, null);
        composition.add(iron);
        
        Element carbon = new Element("Carbon", "C", 20.0, 15.0, 25.0, null);
        composition.add(carbon);
        
        return composition;
    }
}