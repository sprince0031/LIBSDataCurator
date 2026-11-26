package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.UserInputConfig;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;
import com.medals.libsdatagenerator.model.matweb.SeriesInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Comprehensive tests for InputCompositionProcessor
 */
class InputCompositionProcessorTest {

    private InputCompositionProcessor processor;
    private ByteArrayOutputStream outputStream;
    private PrintStream originalOut;
    private PrintStream originalErr;
    
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        processor = InputCompositionProcessor.getInstance();
        outputStream = new ByteArrayOutputStream();
        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(outputStream));
        System.setErr(new PrintStream(new ByteArrayOutputStream())); // Suppress error logs in tests
        
        // Reset the static hasIndividualGuidsToProcess field for clean test state
        resetHasIndividualGuidsToProcess();
    }
    
    private void resetHasIndividualGuidsToProcess() throws Exception {
        java.lang.reflect.Field field = InputCompositionProcessor.class.getDeclaredField("hasIndividualGuidsToProcess");
        field.setAccessible(true);
        field.setBoolean(null, false);
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    // ===== SINGLETON PATTERN TESTS =====

    @Test
    void testGetInstance_returnsSingleton() {
        InputCompositionProcessor processor1 = InputCompositionProcessor.getInstance();
        InputCompositionProcessor processor2 = InputCompositionProcessor.getInstance();
        
        assertNotNull(processor1);
        assertNotNull(processor2);
        assertSame(processor1, processor2, "getInstance should return the same instance");
    }

    // ===== COMPOSITION STRING PROCESSING TESTS =====

    @Test
    void testGetMaterial_withCompositionString_overviewGuid() throws IOException {
        String overviewGuid = "12345678901234567890123456789012";
        UserInputConfig userInputs = new UserInputConfig();
        userInputs.compositionInput = "Fe-80,C-20";
        userInputs.overviewGuid = overviewGuid;
        MaterialGrade result = processor.getMaterial(userInputs);
        
        assertNotNull(result);

        assertEquals(overviewGuid, result.getParentSeries().getOverviewGuid());
        assertNull(result.getMatGUID());
    }

    // ===== SERIES PARSING TESTS =====

    @Test
    void testParseSeriesEntry_validEntry() throws Exception {
        Method parseMethod = InputCompositionProcessor.class.getDeclaredMethod("parseSeriesEntry", String.class, String.class);
        parseMethod.setAccessible(true);
        
        String entry = "3a9cc570fbb24d119f08db22a53e2421,a2eed65d6e5e4b66b7315a1b30f4b391,og-81a26031d1b44cbb911f70ab863281f5";
        SeriesInput result = (SeriesInput) parseMethod.invoke(processor, "test.series", entry);
        
        assertNotNull(result);
        assertEquals("test.series", result.getSeriesKey());
        assertEquals(2, result.getIndividualMaterialGuids().size());
        assertEquals("81a26031d1b44cbb911f70ab863281f5", result.getOverviewGuid());
        
        assertTrue(result.getIndividualMaterialGuids().contains("3a9cc570fbb24d119f08db22a53e2421"));
        assertTrue(result.getIndividualMaterialGuids().contains("a2eed65d6e5e4b66b7315a1b30f4b391"));
    }

    @Test
    void testParseSeriesEntry_onlyIndividualGuids() throws Exception {
        Method parseMethod = InputCompositionProcessor.class.getDeclaredMethod("parseSeriesEntry", String.class, String.class);
        parseMethod.setAccessible(true);
        
        String entry = "3a9cc570fbb24d119f08db22a53e2421,a2eed65d6e5e4b66b7315a1b30f4b391";
        SeriesInput result = (SeriesInput) parseMethod.invoke(processor, "test.series", entry);
        
        assertNotNull(result);
        assertEquals("test.series", result.getSeriesKey());
        assertEquals(2, result.getIndividualMaterialGuids().size());
        assertNull(result.getOverviewGuid());
    }

    @Test
    void testParseSeriesEntry_onlyOverviewGuid() throws Exception {
        Method parseMethod = InputCompositionProcessor.class.getDeclaredMethod("parseSeriesEntry", String.class, String.class);
        parseMethod.setAccessible(true);
        
        String entry = "og-81a26031d1b44cbb911f70ab863281f5";
        SeriesInput result = (SeriesInput) parseMethod.invoke(processor, "test.series", entry);
        
        assertNotNull(result);
        assertEquals("test.series", result.getSeriesKey());
        assertEquals(0, result.getIndividualMaterialGuids().size());
        assertEquals("81a26031d1b44cbb911f70ab863281f5", result.getOverviewGuid());
    }

    @Test
    void testParseSeriesEntry_invalidGuidFormat() throws Exception {
        Method parseMethod = InputCompositionProcessor.class.getDeclaredMethod("parseSeriesEntry", String.class, String.class);
        parseMethod.setAccessible(true);
        
        String entry = "invalid-guid,another-invalid-guid";
        SeriesInput result = (SeriesInput) parseMethod.invoke(processor, "test.series", entry);
        
        assertNotNull(result);
        assertEquals("test.series", result.getSeriesKey());
        assertEquals(0, result.getIndividualMaterialGuids().size());
        assertNull(result.getOverviewGuid());
    }

    @Test
    void testParseSeriesEntry_mixedValidInvalidGuids() throws Exception {
        Method parseMethod = InputCompositionProcessor.class.getDeclaredMethod("parseSeriesEntry", String.class, String.class);
        parseMethod.setAccessible(true);
        
        String entry = "3a9cc570fbb24d119f08db22a53e2421,invalid-guid,a2eed65d6e5e4b66b7315a1b30f4b391";
        SeriesInput result = (SeriesInput) parseMethod.invoke(processor, "test.series", entry);
        
        assertNotNull(result);
        assertEquals("test.series", result.getSeriesKey());
        assertEquals(2, result.getIndividualMaterialGuids().size());
        assertTrue(result.getIndividualMaterialGuids().contains("3a9cc570fbb24d119f08db22a53e2421"));
        assertTrue(result.getIndividualMaterialGuids().contains("a2eed65d6e5e4b66b7315a1b30f4b391"));
        assertFalse(result.getIndividualMaterialGuids().contains("invalid-guid"));
    }

    @Test
    void testParseSeriesEntry_emptyEntry() throws Exception {
        Method parseMethod = InputCompositionProcessor.class.getDeclaredMethod("parseSeriesEntry", String.class, String.class);
        parseMethod.setAccessible(true);
        
        SeriesInput result = (SeriesInput) parseMethod.invoke(processor, "test.series", "");
        assertNull(result);
        
        result = (SeriesInput) parseMethod.invoke(processor, "test.series", null);
        assertNull(result);
        
        result = (SeriesInput) parseMethod.invoke(processor, "test.series", "   ");
        assertNull(result);
    }

    @Test
    void testParseSeriesEntry_multipleOverviewGuids() throws Exception {
        Method parseMethod = InputCompositionProcessor.class.getDeclaredMethod("parseSeriesEntry", String.class, String.class);
        parseMethod.setAccessible(true);
        
        String entry = "3a9cc570fbb24d119f08db22a53e2421,og-81a26031d1b44cbb911f70ab863281f5,og-a2eed65d6e5e4b66b7315a1b30f4b391";
        SeriesInput result = (SeriesInput) parseMethod.invoke(processor, "test.series", entry);
        
        assertNotNull(result);
        assertEquals("test.series", result.getSeriesKey());
        assertEquals(1, result.getIndividualMaterialGuids().size());
        assertEquals("81a26031d1b44cbb911f70ab863281f5", result.getOverviewGuid());
        // Only the first overview GUID should be used
    }

    @Test
    void testParseSeriesEntry_emptyOverviewGuid() throws Exception {
        Method parseMethod = InputCompositionProcessor.class.getDeclaredMethod("parseSeriesEntry", String.class, String.class);
        parseMethod.setAccessible(true);
        
        String entry = "3a9cc570fbb24d119f08db22a53e2421,og-";
        SeriesInput result = (SeriesInput) parseMethod.invoke(processor, "test.series", entry);
        
        assertNotNull(result);
        assertEquals("test.series", result.getSeriesKey());
        assertEquals(1, result.getIndividualMaterialGuids().size());
        assertNull(result.getOverviewGuid());
    }

    // ===== MATERIALS CATALOGUE TESTS =====

    @Test
    void testParseMaterialsCatalogue_invalidPropertiesFile() {
        // Test with null input when materials_catalogue.properties doesn't exist or is empty
        try (MockedStatic<CommonUtils> mockedUtils = Mockito.mockStatic(CommonUtils.class)) {
            CommonUtils mockCommonUtils = mock(CommonUtils.class);
            Properties emptyProps = new Properties();
            
            mockedUtils.when(CommonUtils::getInstance).thenReturn(mockCommonUtils);
            when(mockCommonUtils.readProperties(anyString())).thenReturn(emptyProps);
            
            IOException exception = assertThrows(IOException.class, () -> {
                processor.parseMaterialsCatalogue("test.series");
            });
            
            assertTrue(exception.getMessage().contains("materials_catalogue.properties file is empty"));
        }
    }

    @Test
    void testParseMaterialsCatalogue_validSingleSeries() throws Exception {
        // Create temporary properties file
        File propertiesFile = tempDir.resolve("materials_catalogue.properties").toFile();
        try (FileWriter writer = new FileWriter(propertiesFile)) {
            writer.write("test.series=3a9cc570fbb24d119f08db22a53e2421,a2eed65d6e5e4b66b7315a1b30f4b391,og-81a26031d1b44cbb911f70ab863281f5\n");
        }
        
        try (MockedStatic<CommonUtils> mockedUtils = Mockito.mockStatic(CommonUtils.class)) {
            CommonUtils mockCommonUtils = mock(CommonUtils.class);
            Properties props = new Properties();
            props.setProperty("test.series", "3a9cc570fbb24d119f08db22a53e2421,a2eed65d6e5e4b66b7315a1b30f4b391,og-81a26031d1b44cbb911f70ab863281f5");
            
            mockedUtils.when(CommonUtils::getInstance).thenReturn(mockCommonUtils);
            when(mockCommonUtils.readProperties(anyString())).thenReturn(props);
            
            List<SeriesInput> result = processor.parseMaterialsCatalogue("test.series");
            
            assertNotNull(result);
            assertEquals(1, result.size());
            
            SeriesInput series = result.get(0);
            assertEquals("test.series", series.getSeriesKey());
            assertEquals(2, series.getIndividualMaterialGuids().size());
            assertEquals("81a26031d1b44cbb911f70ab863281f5", series.getOverviewGuid());
        }
    }

    @Test
    void testParseMaterialsCatalogue_multipleSeries() throws Exception {
        try (MockedStatic<CommonUtils> mockedUtils = Mockito.mockStatic(CommonUtils.class)) {
            CommonUtils mockCommonUtils = mock(CommonUtils.class);
            Properties props = new Properties();
            props.setProperty("series1", "3a9cc570fbb24d119f08db22a53e2421,og-81a26031d1b44cbb911f70ab863281f5");
            props.setProperty("series2", "a2eed65d6e5e4b66b7315a1b30f4b391,og-cbe4fd0a73cf4690853935f52d910784");
            
            mockedUtils.when(CommonUtils::getInstance).thenReturn(mockCommonUtils);
            when(mockCommonUtils.readProperties(anyString())).thenReturn(props);
            
            List<SeriesInput> result = processor.parseMaterialsCatalogue("series1,series2");
            
            assertNotNull(result);
            assertEquals(2, result.size());
            
            // Verify both series are processed
            boolean foundSeries1 = false, foundSeries2 = false;
            for (SeriesInput series : result) {
                if ("series1".equals(series.getSeriesKey())) {
                    foundSeries1 = true;
                    assertEquals(1, series.getIndividualMaterialGuids().size());
                    assertEquals("81a26031d1b44cbb911f70ab863281f5", series.getOverviewGuid());
                } else if ("series2".equals(series.getSeriesKey())) {
                    foundSeries2 = true;
                    assertEquals(1, series.getIndividualMaterialGuids().size());
                    assertEquals("cbe4fd0a73cf4690853935f52d910784", series.getOverviewGuid());
                }
            }
            assertTrue(foundSeries1, "series1 should be processed");
            assertTrue(foundSeries2, "series2 should be processed");
        }
    }

    @Test
    void testParseMaterialsCatalogue_allSeries() throws Exception {
        try (MockedStatic<CommonUtils> mockedUtils = Mockito.mockStatic(CommonUtils.class)) {
            CommonUtils mockCommonUtils = mock(CommonUtils.class);
            Properties props = new Properties();
            props.setProperty("series1", "3a9cc570fbb24d119f08db22a53e2421,og-81a26031d1b44cbb911f70ab863281f5");
            props.setProperty("series2", "a2eed65d6e5e4b66b7315a1b30f4b391,og-cbe4fd0a73cf4690853935f52d910784");
            props.setProperty("series3", "42f0179c4d5d4d49b20feb5ad9370f08");
            
            mockedUtils.when(CommonUtils::getInstance).thenReturn(mockCommonUtils);
            when(mockCommonUtils.readProperties(anyString())).thenReturn(props);
            
            // Test processing all series (null input)
            List<SeriesInput> result = processor.parseMaterialsCatalogue(null);
            
            assertNotNull(result);
            assertEquals(3, result.size());
        }
    }

    @Test
    void testParseMaterialsCatalogue_nonExistentSeries() throws Exception {
        try (MockedStatic<CommonUtils> mockedUtils = Mockito.mockStatic(CommonUtils.class)) {
            CommonUtils mockCommonUtils = mock(CommonUtils.class);
            Properties props = new Properties();
            props.setProperty("existing.series", "3a9cc570fbb24d119f08db22a53e2421,og-81a26031d1b44cbb911f70ab863281f5");
            
            mockedUtils.when(CommonUtils::getInstance).thenReturn(mockCommonUtils);
            when(mockCommonUtils.readProperties(anyString())).thenReturn(props);
            
            // If no valid series entries are found, an IOException should be thrown
            IOException exception = assertThrows(IOException.class, () -> {
                processor.parseMaterialsCatalogue("nonexistent.series");
            });
            
            assertTrue(exception.getMessage().contains("No individual material GUIDs found"));
        }
    }

    @Test
    void testParseMaterialsCatalogue_noIndividualGuids() throws Exception {
        try (MockedStatic<CommonUtils> mockedUtils = Mockito.mockStatic(CommonUtils.class)) {
            CommonUtils mockCommonUtils = mock(CommonUtils.class);
            Properties props = new Properties();
            props.setProperty("overview.only", "og-81a26031d1b44cbb911f70ab863281f5");
            
            mockedUtils.when(CommonUtils::getInstance).thenReturn(mockCommonUtils);
            when(mockCommonUtils.readProperties(anyString())).thenReturn(props);
            
            // This should not throw an exception anymore since the series is parsed but hasIndividualGuidsToProcess remains false
            // The exception is thrown when hasIndividualGuidsToProcess is false after processing all series
            IOException exception = assertThrows(IOException.class, () -> {
                processor.parseMaterialsCatalogue("overview.only");
            });
            
            assertTrue(exception.getMessage().contains("No individual material GUIDs found"));
        }
    }

    @Test
    void testParseMaterialsCatalogue_emptySeriesKey() throws Exception {
        try (MockedStatic<CommonUtils> mockedUtils = Mockito.mockStatic(CommonUtils.class)) {
            CommonUtils mockCommonUtils = mock(CommonUtils.class);
            Properties props = new Properties();
            props.setProperty("valid.series", "3a9cc570fbb24d119f08db22a53e2421,og-81a26031d1b44cbb911f70ab863281f5");
            
            mockedUtils.when(CommonUtils::getInstance).thenReturn(mockCommonUtils);
            when(mockCommonUtils.readProperties(anyString())).thenReturn(props);
            
            List<SeriesInput> result = processor.parseMaterialsCatalogue(" , ,valid.series");
            
            assertNotNull(result);
            assertEquals(1, result.size());
            assertEquals("valid.series", result.get(0).getSeriesKey());
        }
    }

    // ===== GUID VALIDATION TESTS =====

    @Test
    void testGuidPatternValidation() {
        Pattern guidPattern = Pattern.compile(LIBSDataGenConstants.MATWEB_GUID_REGEX);
        
        // Valid GUIDs
        assertTrue(guidPattern.matcher("3a9cc570fbb24d119f08db22a53e2421").matches());
        assertTrue(guidPattern.matcher("A2EED65D6E5E4B66B7315A1B30F4B391").matches());
        assertTrue(guidPattern.matcher("00000000000000000000000000000000").matches());
        assertTrue(guidPattern.matcher("ffffffffffffffffffffffffffffffff").matches());
        
        // Invalid GUIDs
        assertFalse(guidPattern.matcher("3a9cc570fbb24d119f08db22a53e242").matches()); // Too short
        assertFalse(guidPattern.matcher("3a9cc570fbb24d119f08db22a53e24213").matches()); // Too long
        assertFalse(guidPattern.matcher("3a9cc570-fbb2-4d11-9f08-db22a53e2421").matches()); // With dashes
        assertFalse(guidPattern.matcher("3a9cc570fbb24d119f08db22a53e2g21").matches()); // Invalid character 'g'
        assertFalse(guidPattern.matcher("").matches()); // Empty
        assertFalse(guidPattern.matcher("invalid-guid").matches()); // Completely invalid
    }

    // ===== ERROR HANDLING TESTS =====

    @Test
    void testParseSeriesEntry_withCommasAndSpaces() throws Exception {
        Method parseMethod = InputCompositionProcessor.class.getDeclaredMethod("parseSeriesEntry", String.class, String.class);
        parseMethod.setAccessible(true);
        
        String entry = " 3a9cc570fbb24d119f08db22a53e2421 , , a2eed65d6e5e4b66b7315a1b30f4b391 , og-81a26031d1b44cbb911f70ab863281f5 ";
        SeriesInput result = (SeriesInput) parseMethod.invoke(processor, "test.series", entry);
        
        assertNotNull(result);
        assertEquals("test.series", result.getSeriesKey());
        assertEquals(2, result.getIndividualMaterialGuids().size());
        assertEquals("81a26031d1b44cbb911f70ab863281f5", result.getOverviewGuid());
    }

    // ===== INTEGRATION TESTS =====

    @Test
    void testProcessSeriesList_withEmptyAndValidKeys() throws Exception {
        Method processMethod = InputCompositionProcessor.class.getDeclaredMethod("processSeriesList", List.class, Properties.class);
        processMethod.setAccessible(true);
        
        Properties props = new Properties();
        props.setProperty("valid.series", "3a9cc570fbb24d119f08db22a53e2421,og-81a26031d1b44cbb911f70ab863281f5");
        
        List<String> seriesKeys = List.of("", "  ", "valid.series", "nonexistent.series");
        
        @SuppressWarnings("unchecked")
        List<SeriesInput> result = (List<SeriesInput>) processMethod.invoke(processor, seriesKeys, props);
        
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("valid.series", result.get(0).getSeriesKey());
    }

    // ===== COATING TESTS =====

    @Test
    void testParseSeriesEntry_coatedSeries() throws Exception {
        Method parseMethod = InputCompositionProcessor.class.getDeclaredMethod("parseSeriesEntry", String.class, String.class);
        parseMethod.setAccessible(true);
        
        String entry = "3a9cc570fbb24d119f08db22a53e2421,a2eed65d6e5e4b66b7315a1b30f4b391,og-81a26031d1b44cbb911f70ab863281f5";
        SeriesInput result = (SeriesInput) parseMethod.invoke(processor, "Zn-2.5.coated.aisi.10xx.series", entry);
        
        assertNotNull(result);
        assertEquals("Zn-2.5.coated.aisi.10xx.series", result.getSeriesKey());
        assertEquals(2, result.getIndividualMaterialGuids().size());
        assertEquals("81a26031d1b44cbb911f70ab863281f5", result.getOverviewGuid());
        assertTrue(result.isCoated());
        assertEquals("Zn", result.getCoatingElement().getSymbol());
        assertEquals(2.5, result.getCoatingElement().getPercentageComposition(), 0.001);
    }

    @Test
    void testParseSeriesEntry_coatedSeriesInvalidFormat() throws Exception {
        Method parseMethod = InputCompositionProcessor.class.getDeclaredMethod("parseSeriesEntry", String.class, String.class);
        parseMethod.setAccessible(true);
        
        String entry = "3a9cc570fbb24d119f08db22a53e2421,og-81a26031d1b44cbb911f70ab863281f5";
        SeriesInput result = (SeriesInput) parseMethod.invoke(processor, "invalid.coated.format", entry);
        
        assertNotNull(result);
        assertEquals("invalid.coated.format", result.getSeriesKey());
        assertFalse(result.isCoated());
        assertNull(result.getCoatingElement());
    }

    @Test
    void testParseSeriesEntry_coatedSeriesInvalidPercentage() throws Exception {
        Method parseMethod = InputCompositionProcessor.class.getDeclaredMethod("parseSeriesEntry", String.class, String.class);
        parseMethod.setAccessible(true);
        
        String entry = "3a9cc570fbb24d119f08db22a53e2421,og-81a26031d1b44cbb911f70ab863281f5";
        SeriesInput result = (SeriesInput) parseMethod.invoke(processor, "Zn-invalid.coated.aisi.10xx.series", entry);
        
        assertNotNull(result);
        assertEquals("Zn-invalid.coated.aisi.10xx.series", result.getSeriesKey());
        assertFalse(result.isCoated());
        assertNull(result.getCoatingElement());
    }

    @Test
    void testApplyCoating_newElement() throws Exception {
        Method applyCoatingMethod = InputCompositionProcessor.class.getDeclaredMethod("applyCoating", List.class, Element.class, Boolean.class);
        applyCoatingMethod.setAccessible(true);

        // Create list of base compositions because coating process will be executed after generation of variations.
        List<List<Element>> baseCompositions = new ArrayList<>();

        // Create base composition: Fe-80, C-20
        List<Element> baseComposition = new ArrayList<>();
        baseComposition.add(new Element("Iron", "Fe", 80.0, null, null, null));
        baseComposition.add(new Element("Carbon", "C", 20.0, null, null, null));

        // Adding sample base composition to list of base compositions
        baseCompositions.add(baseComposition);

        // Create coating element, Zn-2.5%
        Element coatingElement = new Element(PeriodicTable.getElementName("Zn"), "Zn", 2.5,
                null, null, null);

        // Apply 2.5% Zinc coating
        @SuppressWarnings("unchecked")
        List<List<Element>> result = (List<List<Element>>) applyCoatingMethod.invoke(processor, baseCompositions, coatingElement, true);
        
        assertNotNull(result);
        assertEquals(1, result.size());
        List<Element> coatedMaterial =  result.getFirst();
        assertEquals(3, coatedMaterial.size());

        // Check that Fe and C are scaled down
        Element fe = coatedMaterial.stream().filter(e -> e.getSymbol().equals("Fe")).findFirst().orElse(null);
        Element c = coatedMaterial.stream().filter(e -> e.getSymbol().equals("C")).findFirst().orElse(null);
        Element zn = coatedMaterial.stream().filter(e -> e.getSymbol().equals("Zn")).findFirst().orElse(null);
        
        assertNotNull(fe);
        assertNotNull(c);
        assertNotNull(zn);
        
        // Fe should be 80 * 0.975 = 78.0
        assertEquals(78.0, fe.getPercentageComposition(), 0.01);
        // C should be 20 * 0.975 = 19.5
        assertEquals(19.5, c.getPercentageComposition(), 0.01);
        // Zn should be 2.5
        assertEquals(2.5, zn.getPercentageComposition(), 0.01);
        
        // Total should be ~100%
        double total = coatedMaterial.stream().mapToDouble(Element::getPercentageComposition).sum();
        assertEquals(100.0, total, 0.1);
    }

    @Test
    void testApplyCoating_existingElement() throws Exception {
        Method applyCoatingMethod = InputCompositionProcessor.class.getDeclaredMethod("applyCoating", List.class, Element.class, Boolean.class);
        applyCoatingMethod.setAccessible(true);

        // Create list of base compositions because coating process will be executed after generation of variations.
        List<List<Element>> baseCompositions = new ArrayList<>();

        // Create base composition: Fe-80, C-18, Cr-2
        List<Element> baseComposition = new ArrayList<>();
        baseComposition.add(new Element("Iron", "Fe", 80.0, null, null, null));
        baseComposition.add(new Element("Carbon", "C", 18.0, null, null, null));
        baseComposition.add(new Element("Chromium", "Cr", 2.0, null, null, null));

        // Adding sample base composition to list of base compositions
        baseCompositions.add(baseComposition);

        // Create coating element, Cr-1.0%
        Element coatingElement = new Element(PeriodicTable.getElementName("Cr"), "Cr", 1.0,
                null, null, null);

        // Apply 1.0% Chromium coating (Cr already exists)
        @SuppressWarnings("unchecked")
        List<List<Element>> result = (List<List<Element>>) applyCoatingMethod.invoke(processor, baseCompositions, coatingElement, true);
        
        assertNotNull(result);
        assertEquals(1, result.size()); // Should still be 1 composition
        List<Element> coatedElements = result.getFirst();
        assertEquals(3, coatedElements.size()); // Should still be 3 elements

        Element fe = coatedElements.stream().filter(e -> e.getSymbol().equals("Fe")).findFirst().orElse(null);
        Element c = coatedElements.stream().filter(e -> e.getSymbol().equals("C")).findFirst().orElse(null);
        Element cr = coatedElements.stream().filter(e -> e.getSymbol().equals("Cr")).findFirst().orElse(null);
        
        assertNotNull(fe);
        assertNotNull(c);
        assertNotNull(cr);
        
        // Fe should be 80 * 0.99 = 79.2
        assertEquals(79.2, fe.getPercentageComposition(), 0.01);
        // C should be 18 * 0.99 = 17.82
        assertEquals(17.82, c.getPercentageComposition(), 0.01);
        // Cr should be (2 * 0.99) + 1.0 = 2.98
        assertEquals(2.98, cr.getPercentageComposition(), 0.01);
        
        // Total should be ~100%
        double total = coatedElements.stream().mapToDouble(Element::getPercentageComposition).sum();
        assertEquals(100.0, total, 0.1);
    }

    @Test
    void testApplyCoating_invalidParameters() throws Exception {
        Method applyCoatingMethod = InputCompositionProcessor.class.getDeclaredMethod("applyCoating", List.class, Element.class, Boolean.class);
        applyCoatingMethod.setAccessible(true);

        // Create list of base compositions because coating process will be executed after generation of variations.
        List<List<Element>> baseCompositions = new ArrayList<>();

        List<Element> baseComposition = new ArrayList<>();
        baseComposition.add(new Element("Iron", "Fe", 100.0, null, null, null));

        // Adding sample base composition to list of base compositions
        baseCompositions.add(baseComposition);


        // Test with null coating element
        @SuppressWarnings("unchecked")
        List<List<Element>> result1 = (List<List<Element>>) applyCoatingMethod.invoke(processor, baseCompositions, null, false);
        assertEquals(baseCompositions, result1); // Should return original composition

        // Test with zero coating percentage
        // Create coating element, Cr-1.0%
        Element coatingElement = new Element(PeriodicTable.getElementName("Zn"), "Zn", 0.0,
                null, null, null);

        @SuppressWarnings("unchecked")
        List<List<Element>> result3 = (List<List<Element>>) applyCoatingMethod.invoke(processor, baseCompositions, coatingElement, false);
        assertEquals(baseCompositions, result3); // Should return original composition
    }

    @Test
    void testApplyCoating_subtractStrategy_doesNotMutateInputsOrShareReferences() {
        Element fe1 = new Element("Iron", "Fe", 80.0, 75.0, 85.0, 80.0);
        Element c1 = new Element("Carbon", "C", 18.0, 15.0, 20.0, 18.0);
        Element zn1 = new Element("Zinc", "Zn", 2.0, 1.0, 3.0, 2.0);
        List<Element> comp1 = List.of(fe1, c1, zn1);

        Element fe2 = new Element("Iron", "Fe", 85.0, 80.0, 90.0, 85.0);
        Element c2 = new Element("Carbon", "C", 15.0, 10.0, 20.0, 15.0);
        List<Element> comp2 = List.of(fe2, c2);

        Element coatingElement = new Element("Zinc", "Zn", 5.0, null, null, null);

        List<List<Element>> result = processor.applyCoating(List.of(comp1, comp2), coatingElement, false);
        assertEquals(2, result.size());

        // Caller inputs and coatingElement must remain completely unmutated
        assertEquals(80.0, fe1.getPercentageComposition(), 1e-6);
        assertEquals(75.0, fe1.getMin(), 1e-6);
        assertEquals(85.0, fe1.getMax(), 1e-6);
        assertEquals(2.0, zn1.getPercentageComposition(), 1e-6);
        assertEquals(1.0, zn1.getMin(), 1e-6);
        assertEquals(3.0, zn1.getMax(), 1e-6);
        assertEquals(85.0, fe2.getPercentageComposition(), 1e-6);
        assertEquals(5.0, coatingElement.getPercentageComposition(), 1e-6);

        // Output composition 1 (existing Zn)
        List<Element> out1 = result.get(0);
        Element outFe1 = out1.stream().filter(e -> e.getSymbol().equals("Fe")).findFirst().orElseThrow();
        Element outZn1 = out1.stream().filter(e -> e.getSymbol().equals("Zn")).findFirst().orElseThrow();
        assertNotSame(fe1, outFe1);
        assertNotSame(zn1, outZn1);
        assertEquals(75.0, outFe1.getPercentageComposition(), 1e-6);
        assertEquals(75.0, outFe1.getMin(), 1e-6);
        assertEquals(80.0, outFe1.getMax(), 1e-6);
        assertEquals(7.0, outZn1.getPercentageComposition(), 1e-6);
        assertEquals(6.0, outZn1.getMin(), 1e-6);
        assertEquals(7.0, outZn1.getMax(), 1e-6);

        // Output composition 2 (newly added Zn) must not share coatingElement reference
        List<Element> out2 = result.get(1);
        Element outFe2 = out2.stream().filter(e -> e.getSymbol().equals("Fe")).findFirst().orElseThrow();
        Element outZn2 = out2.stream().filter(e -> e.getSymbol().equals("Zn")).findFirst().orElseThrow();
        assertNotSame(fe2, outFe2);
        assertNotSame(coatingElement, outZn2);
        assertNotSame(outZn1, outZn2);
        assertEquals(80.0, outFe2.getPercentageComposition(), 1e-6);
        assertEquals(5.0, outZn2.getPercentageComposition(), 1e-6);
    }

    @Test
    void testApplyCoating_scaleStrategy_doesNotMutateCoatingElementAcrossIterations() {
        Element fe1 = new Element("Iron", "Fe", 80.0, null, null, null);
        Element cr1 = new Element("Chromium", "Cr", 20.0, 18.0, 22.0, 20.0);

        Element fe2 = new Element("Iron", "Fe", 90.0, null, null, null);
        Element cr2 = new Element("Chromium", "Cr", 10.0, 8.0, 12.0, 10.0);

        Element fe3 = new Element("Iron", "Fe", 95.0, null, null, null);
        Element c3 = new Element("Carbon", "C", 5.0, null, null, null);

        Element coatingElement = new Element("Chromium", "Cr", 5.0, null, null, null);

        List<List<Element>> result = processor.applyCoating(
                List.of(List.of(fe1, cr1), List.of(fe2, cr2), List.of(fe3, c3)),
                coatingElement,
                true
        );

        assertEquals(3, result.size());
        // coatingElement must not be mutated
        assertEquals(5.0, coatingElement.getPercentageComposition(), 1e-6);
        assertEquals(20.0, cr1.getPercentageComposition(), 1e-6);
        assertEquals(10.0, cr2.getPercentageComposition(), 1e-6);

        Element outCr1 = result.get(0).stream().filter(e -> e.getSymbol().equals("Cr")).findFirst().orElseThrow();
        Element outCr2 = result.get(1).stream().filter(e -> e.getSymbol().equals("Cr")).findFirst().orElseThrow();
        Element outCr3 = result.get(2).stream().filter(e -> e.getSymbol().equals("Cr")).findFirst().orElseThrow();

        // Distinct instances with independent percentages
        assertNotSame(coatingElement, outCr1);
        assertNotSame(coatingElement, outCr2);
        assertNotSame(coatingElement, outCr3);
        assertNotSame(outCr1, outCr2);
        assertNotSame(outCr2, outCr3);

        assertEquals(24.0, outCr1.getPercentageComposition(), 1e-6); // 20 * 0.95 + 5
        assertEquals(14.5, outCr2.getPercentageComposition(), 1e-6); // 10 * 0.95 + 5
        assertEquals(5.0, outCr3.getPercentageComposition(), 1e-6);
    }

    @Test
    void testApplyCoating_subtractStrategy_noNpeWhenMaxHasBoundsAndCoatedHasNullBounds() {
        Element fe = new Element("Iron", "Fe", 90.0, 85.0, 95.0, 90.0);
        Element cr = new Element("Chromium", "Cr", 10.0, null, null, null);
        Element coatingElement = new Element("Chromium", "Cr", 2.0, null, null, null);

        List<List<Element>> result = assertDoesNotThrow(() ->
                processor.applyCoating(List.of(List.of(fe, cr)), coatingElement, false));

        List<Element> out = result.get(0);
        Element outFe = out.stream().filter(e -> e.getSymbol().equals("Fe")).findFirst().orElseThrow();
        Element outCr = out.stream().filter(e -> e.getSymbol().equals("Cr")).findFirst().orElseThrow();
        assertEquals(88.0, outFe.getPercentageComposition(), 1e-6);
        assertEquals(12.0, outCr.getPercentageComposition(), 1e-6);
        assertNull(outCr.getMin());
        assertNull(outCr.getMax());
    }

    @Test
    void testApplyCoating_subtractStrategy_updatesCoatedBoundsWhenMaxHasNullBounds() {
        Element fe = new Element("Iron", "Fe", 90.0, null, null, null);
        Element cr = new Element("Chromium", "Cr", 10.0, 8.0, 11.0, 10.0);
        Element coatingElement = new Element("Chromium", "Cr", 2.0, null, null, null);

        List<List<Element>> result = processor.applyCoating(List.of(List.of(fe, cr)), coatingElement, false);

        List<Element> out = result.get(0);
        Element outCr = out.stream().filter(e -> e.getSymbol().equals("Cr")).findFirst().orElseThrow();
        assertEquals(12.0, outCr.getPercentageComposition(), 1e-6);
        assertEquals(10.0, outCr.getMin(), 1e-6);
        assertEquals(12.0, outCr.getMax(), 1e-6);
        // Original caller bounds remain untouched
        assertEquals(8.0, cr.getMin(), 1e-6);
        assertEquals(11.0, cr.getMax(), 1e-6);
    }
}