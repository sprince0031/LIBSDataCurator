package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.controller.LIBSDataGenConstants;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.MaterialGrade;
import com.medals.libsdatagenerator.model.SeriesInput;
import com.medals.libsdatagenerator.service.MatwebDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

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
    void testGetMaterialsList_withCompositionString_singleElement() throws IOException {
        List<MaterialGrade> result = processor.getMaterialsList("Fe-100", true);
        
        assertNotNull(result);
        assertEquals(1, result.size());
        
        MaterialGrade grade = result.get(0);
        assertNotNull(grade.getComposition());
        assertEquals(1, grade.getComposition().size());
        
        Element element = grade.getComposition().get(0);
        assertEquals("Fe", element.getSymbol());
        assertEquals(100.0, element.getPercentageComposition(), 0.001);
        
        // No progress bar should appear for composition strings
        String output = outputStream.toString();
        assertFalse(output.contains("["), "Progress bar should not appear for composition strings");
    }

    @Test
    void testGetMaterialsList_withCompositionString_multipleElements() throws IOException {
        List<MaterialGrade> result = processor.getMaterialsList("Fe-80,C-20", true);
        
        assertNotNull(result);
        assertEquals(1, result.size());
        
        MaterialGrade grade = result.get(0);
        assertNotNull(grade.getComposition());
        assertEquals(2, grade.getComposition().size());
        
        // Verify elements
        boolean foundFe = false, foundC = false;
        for (Element element : grade.getComposition()) {
            if ("Fe".equals(element.getSymbol()) && element.getPercentageComposition() == 80.0) {
                foundFe = true;
            } else if ("C".equals(element.getSymbol()) && element.getPercentageComposition() == 20.0) {
                foundC = true;
            }
        }
        assertTrue(foundFe, "Fe element should be present with 80% composition");
        assertTrue(foundC, "C element should be present with 20% composition");
    }

    @Test
    void testGetMaterialsList_withCompositionString_overviewGuid() throws IOException {
        String overviewGuid = "12345678901234567890123456789012";
        List<MaterialGrade> result = processor.getMaterialsList("Fe-80,C-20", true, overviewGuid);
        
        assertNotNull(result);
        assertEquals(1, result.size());
        
        MaterialGrade grade = result.get(0);
        assertEquals(overviewGuid, grade.getOverviewGUID());
        assertNull(grade.getMatGUID());
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

    // ===== PROGRESS BAR TESTS =====

    @Test
    void testCompositionStringProcessingNoProgressBar() throws IOException {
        List<MaterialGrade> result = processor.getMaterialsList("Fe-80,C-20", true);
        
        assertNotNull(result);
        assertEquals(1, result.size());
        
        // Progress bar should not appear for composition strings
        String output = outputStream.toString();
        assertFalse(output.contains("["), "Progress bar should not appear for composition strings");
    }

    // ===== ERROR HANDLING TESTS =====

    @Test
    void testGetMaterialsList_invalidCompositionString() {
        // Test with invalid element symbol - this should throw an exception in LIBSDataService
        IOException exception = assertThrows(IOException.class, () -> {
            processor.getMaterialsList("InvalidElement-50,Fe-50", true);
        });
        
        assertTrue(exception.getMessage().contains("Invalid element InvalidElement"));
    }

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
    void testGetMaterialsList_withOverviewGuidAppending() throws IOException {
        String overviewGuid = "81a26031d1b44cbb911f70ab863281f5";
        
        try (MockedStatic<CommonUtils> mockedUtils = Mockito.mockStatic(CommonUtils.class)) {
            CommonUtils mockCommonUtils = mock(CommonUtils.class);
            Properties props = new Properties();
            props.setProperty("test.series", "3a9cc570fbb24d119f08db22a53e2421");
            
            mockedUtils.when(CommonUtils::getInstance).thenReturn(mockCommonUtils);
            when(mockCommonUtils.readProperties(anyString())).thenReturn(props);
            
            // Mock MatwebDataService
            try (MockedStatic<MatwebDataService> mockedMatweb = Mockito.mockStatic(MatwebDataService.class)) {
                MatwebDataService mockMatwebService = mock(MatwebDataService.class);
                mockedMatweb.when(MatwebDataService::getInstance).thenReturn(mockMatwebService);
                
                when(mockMatwebService.getMaterialComposition(anyString())).thenReturn(new String[]{"Fe-80", "C-20"});
                when(mockMatwebService.validateMatwebServiceOutput(any(), anyString())).thenReturn(true);
                when(mockMatwebService.getDatasheetName()).thenReturn("Test Material");
                
                List<MaterialGrade> result = processor.getMaterialsList("test.series", false, overviewGuid);
                
                assertNotNull(result);
                // This should trigger the processing path that appends overview GUID
                verify(mockMatwebService, atLeastOnce()).getMaterialComposition(anyString());
            }
        }
    }

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
}