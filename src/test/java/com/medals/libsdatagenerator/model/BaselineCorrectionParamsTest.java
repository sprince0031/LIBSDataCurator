package com.medals.libsdatagenerator.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class BaselineCorrectionParamsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("Default constructor should initialize method as morphological_opening and structuringElementSize as 65")
    void testDefaultValues() {
        BaselineCorrectionParams params = new BaselineCorrectionParams();
        assertEquals("morphological_opening", params.getMethod());
        assertEquals(65, params.getStructuringElementSize());
    }

    @Test
    @DisplayName("Parameterized constructor and setters should update values correctly")
    void testParameterizedConstructorAndSetters() {
        BaselineCorrectionParams params = new BaselineCorrectionParams("custom_opening", 30);
        assertEquals("custom_opening", params.getMethod());
        assertEquals(30, params.getStructuringElementSize());

        params.setMethod("morphological_opening");
        params.setStructuringElementSize(20);
        assertEquals("morphological_opening", params.getMethod());
        assertEquals(20, params.getStructuringElementSize());
    }

    @Test
    @DisplayName("Jackson should ignore unknown legacy ALS parameters when deserializing")
    void testLegacyAlsJsonDeserialization() throws Exception {
        String legacyJson = "{\"lambda\": 10000.0, \"p\": 0.001, \"maxIterations\": 10}";
        BaselineCorrectionParams params = objectMapper.readValue(legacyJson, BaselineCorrectionParams.class);

        assertNotNull(params);
        assertEquals("morphological_opening", params.getMethod());
        assertEquals(65, params.getStructuringElementSize());
    }

    @Test
    @DisplayName("Jackson should deserialize new morphological opening parameters correctly")
    void testMorphologicalOpeningJsonDeserialization() throws Exception {
        String json = "{\"method\": \"morphological_opening\", \"structuringElementSize\": 35}";
        BaselineCorrectionParams params = objectMapper.readValue(json, BaselineCorrectionParams.class);

        assertNotNull(params);
        assertEquals("morphological_opening", params.getMethod());
        assertEquals(35, params.getStructuringElementSize());
    }
}
