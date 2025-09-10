package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.nist.NistUrlOptions.ClassLabelType;
import com.medals.libsdatagenerator.model.nist.UserInputConfig;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.apache.commons.cli.CommandLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ClassLabelTypeTest {

    private LIBSDataService libsDataService;
    private CommonUtils commonUtils;

    @BeforeEach
    void setUp() {
        libsDataService = LIBSDataService.getInstance();
        commonUtils = CommonUtils.getInstance();
    }

    @Test
    void testClassLabelTypeEnumValues() {
        // Test enum values and their properties
        assertEquals(1, ClassLabelType.COMPOSITION_PERCENTAGE.getUserOption());
        assertEquals(2, ClassLabelType.STEEL_GRADE_NAME.getUserOption());
        assertEquals(3, ClassLabelType.STEEL_TYPE.getUserOption());
        
        assertEquals("Composition percentages", ClassLabelType.COMPOSITION_PERCENTAGE.getDescription());
        assertEquals("Steel grade name", ClassLabelType.STEEL_GRADE_NAME.getDescription());
        assertEquals("Steel type", ClassLabelType.STEEL_TYPE.getDescription());
    }

    @Test
    void testClassLabelTypeFromOption() {
        // Test default behavior
        assertEquals(ClassLabelType.COMPOSITION_PERCENTAGE, ClassLabelType.fromOption(1));
        assertEquals(ClassLabelType.STEEL_GRADE_NAME, ClassLabelType.fromOption(2));
        assertEquals(ClassLabelType.STEEL_TYPE, ClassLabelType.fromOption(3));
        
        // Test invalid option defaults to COMPOSITION_PERCENTAGE
        assertEquals(ClassLabelType.COMPOSITION_PERCENTAGE, ClassLabelType.fromOption(999));
        assertEquals(ClassLabelType.COMPOSITION_PERCENTAGE, ClassLabelType.fromOption(0));
    }

    @Test
    void testUserInputConfigWithClassLabelType() {
        // Test default class label type (option 1)
        String[] args1 = {"-c", "Fe-80,C-20"};
        CommandLine cmd1 = commonUtils.getTerminalArgHandler(args1);
        UserInputConfig config1 = new UserInputConfig(cmd1);
        assertEquals(ClassLabelType.COMPOSITION_PERCENTAGE, config1.classLabelType);

        // Test steel grade name (option 2)
        String[] args2 = {"-c", "Fe-80,C-20", "-ct", "2"};
        CommandLine cmd2 = commonUtils.getTerminalArgHandler(args2);
        UserInputConfig config2 = new UserInputConfig(cmd2);
        assertEquals(ClassLabelType.STEEL_GRADE_NAME, config2.classLabelType);

        // Test steel type (option 3)
        String[] args3 = {"-c", "Fe-80,C-20", "-ct", "3"};
        CommandLine cmd3 = commonUtils.getTerminalArgHandler(args3);
        UserInputConfig config3 = new UserInputConfig(cmd3);
        assertEquals(ClassLabelType.STEEL_TYPE, config3.classLabelType);
    }

    @Test
    void testCLIOptionParsing() {
        // Test that the new CLI option is recognized
        String[] args = {"-c", "Fe-80,C-20", "--class-type", "2"};
        CommandLine cmd = commonUtils.getTerminalArgHandler(args);
        assertNotNull(cmd);
        assertTrue(cmd.hasOption(LIBSDataGenConstants.CMD_OPT_CLASS_TYPE_SHORT));
        assertEquals("2", cmd.getOptionValue(LIBSDataGenConstants.CMD_OPT_CLASS_TYPE_SHORT));
    }

    @Test 
    void testMaterialGradeWithSeriesKey() {
        // Test MaterialGrade constructor with series key
        List<Element> composition = createTestComposition();
        MaterialGrade grade1 = new MaterialGrade(composition, "testGuid", "overviewGuid", "aisi.10xx.series");
        
        assertEquals("aisi.10xx.series", grade1.getSeriesKey());
        assertNull(grade1.getMaterialName()); // Initially null
        
        // Test setter
        grade1.setMaterialName("AISI 1018 Steel");
        assertEquals("AISI 1018 Steel", grade1.getMaterialName());
        
        // Test backward compatibility constructor
        MaterialGrade grade2 = new MaterialGrade(composition, "testGuid", "overviewGuid");
        assertNull(grade2.getSeriesKey()); // Should be null for backward compatibility
    }

    @Test
    void testSteelTypeExtraction() {
        // Test steel type extraction from series keys (using reflection to access private method)
        try {
            java.lang.reflect.Method method = LIBSDataService.class.getDeclaredMethod("extractSteelTypeFromSeriesKey", String.class);
            method.setAccessible(true);
            
            assertEquals("AISI 1000 Series Carbon Steel", method.invoke(libsDataService, "aisi.10xx.series"));
            assertEquals("AISI 4000 Series Chromium-Molybdenum Steel", method.invoke(libsDataService, "aisi.41xx.series"));
            assertEquals("300 Series Austenitic Stainless Steel", method.invoke(libsDataService, "t.30x.series"));
            assertEquals("ASTM Structural Steel", method.invoke(libsDataService, "astm.structural.series"));
            assertEquals("Direct Entry", method.invoke(libsDataService, LIBSDataGenConstants.DIRECT_ENTRY));
            assertEquals("Direct Entry", method.invoke(libsDataService, (String)null));
            
            // Test generic parsing
            assertEquals("CUSTOM SERIES Series Steel", method.invoke(libsDataService, "custom.series.series"));
            assertEquals("UNKNOWN FORMAT Series Steel", method.invoke(libsDataService, "unknown.format"));
            assertEquals("single", method.invoke(libsDataService, "single")); // No dots, returns as-is
            
        } catch (Exception e) {
            fail("Failed to test steel type extraction: " + e.getMessage());
        }
    }

    @Test
    void testClassLabelGeneration() {
        // Test class label generation (using reflection to access private method)
        try {
            java.lang.reflect.Method method = LIBSDataService.class.getDeclaredMethod("generateClassLabel", 
                ClassLabelType.class, MaterialGrade.class, String.class);
            method.setAccessible(true);
            
            List<Element> composition = createTestComposition();
            MaterialGrade testGrade = new MaterialGrade(composition, "testGuid", "overviewGuid", "aisi.10xx.series");
            testGrade.setMaterialName("AISI 1018 Steel");
            String compositionId = "Fe-80;C-20";
            
            // Test composition percentage mode
            assertEquals(compositionId, method.invoke(libsDataService, ClassLabelType.COMPOSITION_PERCENTAGE, testGrade, compositionId));
            
            // Test steel grade name mode
            assertEquals("AISI 1018 Steel", method.invoke(libsDataService, ClassLabelType.STEEL_GRADE_NAME, testGrade, compositionId));
            
            // Test steel type mode
            assertEquals("AISI 1000 Series Carbon Steel", method.invoke(libsDataService, ClassLabelType.STEEL_TYPE, testGrade, compositionId));
            
            // Test fallbacks for missing data
            MaterialGrade emptyGrade = new MaterialGrade(composition, "testGuid", "overviewGuid");
            assertEquals("Unknown Grade", method.invoke(libsDataService, ClassLabelType.STEEL_GRADE_NAME, emptyGrade, compositionId));
            assertEquals("Unknown Type", method.invoke(libsDataService, ClassLabelType.STEEL_TYPE, emptyGrade, compositionId));
            
        } catch (Exception e) {
            fail("Failed to test class label generation: " + e.getMessage());
        }
    }

    @Test
    void testClassLabelColumnName() {
        // Test column name generation (using reflection to access private method)
        try {
            java.lang.reflect.Method method = LIBSDataService.class.getDeclaredMethod("getClassLabelColumnName", ClassLabelType.class);
            method.setAccessible(true);
            
            assertEquals("class_composition_percentage", method.invoke(libsDataService, ClassLabelType.COMPOSITION_PERCENTAGE));
            assertEquals("class_steel_grade_name", method.invoke(libsDataService, ClassLabelType.STEEL_GRADE_NAME));
            assertEquals("class_steel_type", method.invoke(libsDataService, ClassLabelType.STEEL_TYPE));
            
        } catch (Exception e) {
            fail("Failed to test class label column name generation: " + e.getMessage());
        }
    }

    private List<Element> createTestComposition() {
        List<Element> composition = new ArrayList<>();
        composition.add(new Element("Iron", "Fe", 80.0, 0.0, 0.0, null));
        composition.add(new Element("Carbon", "C", 20.0, 0.0, 0.0, null));
        return composition;
    }
}