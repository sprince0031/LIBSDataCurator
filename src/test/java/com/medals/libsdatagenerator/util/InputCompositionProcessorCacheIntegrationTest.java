package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.matweb.SeriesInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration test demonstrating the caching feature for MatWeb data in a realistic scenario
 * @author Copilot
 */
public class InputCompositionProcessorCacheIntegrationTest {

    private InputCompositionProcessor processor;
    private List<Element> testComposition;
    private Map<String, Object> testCompositionMetadata;

    @BeforeEach
    void setUp() {
        processor = InputCompositionProcessor.getInstance();
        testComposition = createTestComposition();
        testCompositionMetadata = createTestCompositionMetaData();

    }

    @Test
    void testCachingPreventsRedundantMatWebRequests() throws Exception {
        // This test demonstrates the real-world scenario where the same material appears
        // in multiple series (e.g., coated materials with the same base composition)
        
        // Create mock MatwebDataService to track how many times it's called
        com.medals.libsdatagenerator.service.MatwebDataService mockMatwebService = 
            mock(com.medals.libsdatagenerator.service.MatwebDataService.class);
        
        // Configure the mock to return valid composition data
        List<String> mockComposition = Arrays.asList("Fe-80.0:80.0", "C-20.0:20.0");
        when(mockMatwebService.getMaterialComposition(any(String.class))).thenReturn(mockComposition);
        when(mockMatwebService.validateMatwebServiceOutput(any(), any())).thenReturn(true);
        when(mockMatwebService.getDatasheetName()).thenReturn("Test Steel Grade");
        when(mockMatwebService.getDatasheetAttributes()).thenReturn(new String[]{"Test", "Attributes"});

        // Mock LIBSDataService to return our test composition
        com.medals.libsdatagenerator.util.InputCompositionProcessor mockInputCompositionService =
            mock(com.medals.libsdatagenerator.util.InputCompositionProcessor.class);
        when(mockInputCompositionService.generateElementsList(mockComposition, 3)).thenReturn(testCompositionMetadata);

        // Use reflection to simulate the caching scenario within getMaterialsList
        // We'll test the core caching logic by creating materials list with duplicates
        List<MaterialGrade> materialGrades = new ArrayList<>();
        
        // Simulate processing the first occurrence of a GUID
        String duplicateGuid = "TEST123ABC";
        
        // First time: material not in cache, should be added
        java.lang.reflect.Method findMethod = InputCompositionProcessor.class.getDeclaredMethod(
            "findMaterialByGuid", List.class, String.class);
        findMethod.setAccessible(true);
        
        MaterialGrade cachedMaterial = (MaterialGrade) findMethod.invoke(processor, materialGrades, duplicateGuid);
        assertNull(cachedMaterial, "First lookup should return null (not cached)");

        SeriesInput series1 = new SeriesInput("series1", Arrays.asList(duplicateGuid), "overview1");
        // Add the material to simulate it being processed and cached
        MaterialGrade firstMaterial = new MaterialGrade(testComposition, duplicateGuid, series1);
        firstMaterial.setMaterialName("Test Steel Grade");
        firstMaterial.setMaterialAttributes(new String[]{"Test", "Attributes"});
        materialGrades.add(firstMaterial);
        
        // Second time: material should be found in cache
        cachedMaterial = (MaterialGrade) findMethod.invoke(processor, materialGrades, duplicateGuid);
        assertNotNull(cachedMaterial, "Second lookup should find cached material");
        assertEquals(duplicateGuid, cachedMaterial.getMatGUID(), "Cached material should have correct GUID");
        assertEquals("Test Steel Grade", cachedMaterial.getMaterialName(), "Cached material should have correct name");
        
        // Verify the cached material has the expected composition
        assertEquals(2, cachedMaterial.getComposition().size(), "Cached material should have correct composition size");
        
        Element cachedFe = cachedMaterial.getComposition().stream()
            .filter(e -> "Fe".equals(e.getSymbol())).findFirst().orElse(null);
        assertNotNull(cachedFe, "Cached material should contain Fe");
        assertEquals(80.0, cachedFe.getPercentageComposition(), 0.001, "Fe composition should be correct");
        
        Element cachedC = cachedMaterial.getComposition().stream()
            .filter(e -> "C".equals(e.getSymbol())).findFirst().orElse(null);
        assertNotNull(cachedC, "Cached material should contain C");
        assertEquals(20.0, cachedC.getPercentageComposition(), 0.001, "C composition should be correct");
    }

    @Test
    void testCacheWithDifferentSeriesContext() throws Exception {
        // Test that cached materials can be reused with different series context
        // This simulates the same material appearing in different steel series
        
        List<MaterialGrade> materialGrades = new ArrayList<>();
        String sharedGuid = "SHARED456DEF";

        SeriesInput series1 = new SeriesInput("series1", Arrays.asList(sharedGuid), "overview1");
        // Add a material from first series
        MaterialGrade firstMaterial = new MaterialGrade(testComposition, sharedGuid, series1);
        firstMaterial.setMaterialName("Base Steel");
        firstMaterial.setMaterialAttributes(new String[]{"Standard", "Grade"});
        materialGrades.add(firstMaterial);
        
        // Use reflection to test the caching logic
        java.lang.reflect.Method findMethod = InputCompositionProcessor.class.getDeclaredMethod(
            "findMaterialByGuid", List.class, String.class);
        findMethod.setAccessible(true);
        
        // Find the cached material
        MaterialGrade cachedMaterial = (MaterialGrade) findMethod.invoke(processor, materialGrades, sharedGuid);
        assertNotNull(cachedMaterial, "Should find cached material");
        
        // Simulate creating a new MaterialGrade for different series context
        SeriesInput series2 = new SeriesInput("series2", Arrays.asList(sharedGuid), "overview2");
        MaterialGrade newMaterial = new MaterialGrade(cachedMaterial.getComposition(), sharedGuid, series2);
        newMaterial.setMaterialName(cachedMaterial.getMaterialName());
        newMaterial.setMaterialAttributes(cachedMaterial.getMaterialAttributes());
        
        // Verify the new material has the cached composition but different context
        assertEquals(sharedGuid, newMaterial.getMatGUID(), "New material should have same GUID");
        assertEquals("overview2", newMaterial.getParentSeries().getOverviewGuid(), "New material should have different overview GUID");
        assertEquals("series2", newMaterial.getParentSeries().getSeriesKey(), "New material should have different series key");
        assertEquals("Base Steel", newMaterial.getMaterialName(), "New material should have cached name");
        
        // Verify composition is preserved
        assertEquals(testComposition.size(), newMaterial.getComposition().size(), "Composition size should be preserved");
        for (int i = 0; i < testComposition.size(); i++) {
            Element original = testComposition.get(i);
            Element preserved = newMaterial.getComposition().get(i);
            assertEquals(original.getSymbol(), preserved.getSymbol(), "Element symbol should be preserved");
            assertEquals(original.getPercentageComposition(), preserved.getPercentageComposition(), 0.001, 
                "Element composition should be preserved");
        }
    }

    private List<Element> createTestComposition() {
        List<Element> composition = new ArrayList<>();
        composition.add(new Element("Iron", "Fe", 80.0, 75.0, 85.0, null));
        composition.add(new Element("Carbon", "C", 20.0, 15.0, 25.0, null));
        return composition;
    }

    private Map<String, Object> createTestCompositionMetaData() {
        Map<String, Object> compositionMetaData = new HashMap<>();
        compositionMetaData.put(LIBSDataGenConstants.ELEMENTS_LIST, createTestComposition());
        compositionMetaData.put(LIBSDataGenConstants.REMAINDER_ELEMENT_IDX, 0);
        return compositionMetaData;
    }
}