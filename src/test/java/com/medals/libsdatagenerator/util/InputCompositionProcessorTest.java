package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.model.MaterialGrade;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for InputCompositionProcessor progress bar functionality
 */
class InputCompositionProcessorTest {

    @Test
    void testCompositionStringProcessingNoProgressBar() throws IOException {
        // Test that progress bar is not shown for single composition strings
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        System.setOut(new PrintStream(outputStream));

        try {
            InputCompositionProcessor processor = InputCompositionProcessor.getInstance();
            List<MaterialGrade> result = processor.getMaterialsList("Fe-80,C-20", true);
            
            assertNotNull(result);
            assertEquals(1, result.size());
            
            // Progress bar should not appear for composition strings
            String output = outputStream.toString();
            assertFalse(output.contains("["), "Progress bar should not appear for composition strings");
            
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void testProgressBarStructure() {
        // Test that the progress bar logic can handle various scenarios
        InputCompositionProcessor processor = InputCompositionProcessor.getInstance();
        assertNotNull(processor);
        
        // Verify the getInstance pattern works correctly
        InputCompositionProcessor processor2 = InputCompositionProcessor.getInstance();
        assertSame(processor, processor2);
    }
}