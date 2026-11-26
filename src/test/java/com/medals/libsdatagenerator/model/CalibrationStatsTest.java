package com.medals.libsdatagenerator.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CalibrationStatsTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private CalibrationStats buildFullyPopulatedStats() {
        CalibrationStats stats = new CalibrationStats(
                0.965, 0.032, 0.14, 2, List.of("469", "551")
        );
        stats.setRoiChi2Reduced(1.425);
        stats.setRoiRSquared(0.952314);
        stats.setRoiRmse(0.041234);
        stats.setRoiRmseRaw(182.456789);
        stats.setWholeSpectrumRSquared(0.891234);
        stats.setWholeSpectrumRmse(0.067891);
        stats.setWholeSpectrumRmseRaw(245.123456);
        stats.setZoneReductionPenalty(0.0185);
        stats.setFamilySharingPenalty(0.0421);

        Map<String, Double> parity = new LinkedHashMap<>();
        parity.put("Fe", 1.012);
        parity.put("Cr", 0.978);
        stats.setElementParity(parity);

        Map<String, Object> mat469 = new LinkedHashMap<>();
        mat469.put("roiChi2Reduced", 1.35);
        mat469.put("roiRSquared", 0.961);
        mat469.put("roiRmse", 0.038);
        mat469.put("roiRmseRaw", 160.25);
        mat469.put("wholeSpectrumRSquared", 0.905);
        mat469.put("wholeSpectrumRmse", 0.061);
        mat469.put("wholeSpectrumRmseRaw", 220.5);
        mat469.put("numSteadyShots", 42);

        Map<String, Map<String, Object>> perMaterial = new LinkedHashMap<>();
        perMaterial.put("469", mat469);
        stats.setPerMaterialStats(perMaterial);
        return stats;
    }

    @Test
    @DisplayName("Jackson ObjectMapper round-trips all 11 new and 5 legacy CalibrationStats fields without duplicate keys")
    void testJacksonRoundTripAllFieldsWithoutDuplicateKeys() throws Exception {
        CalibrationStats original = buildFullyPopulatedStats();

        String serialized = objectMapper.writeValueAsString(original);
        JsonNode root = objectMapper.readTree(serialized);

        assertTrue(root.has("rSquared"));
        assertFalse(root.has("rsquared"), "Jackson must not emit duplicate lowercased 'rsquared' key");
        assertTrue(root.has("roiRSquared"));
        assertFalse(root.has("roirsquared"), "Jackson must not emit duplicate lowercased 'roirsquared' key");
        assertTrue(root.has("roiChi2Reduced"));
        assertTrue(root.has("roiRmse"));
        assertTrue(root.has("roiRmseRaw"));
        assertTrue(root.has("wholeSpectrumRSquared"));
        assertTrue(root.has("wholeSpectrumRmse"));
        assertTrue(root.has("wholeSpectrumRmseRaw"));
        assertTrue(root.has("zoneReductionPenalty"));
        assertTrue(root.has("familySharingPenalty"));
        assertTrue(root.has("elementParity"));
        assertTrue(root.has("perMaterialStats"));

        CalibrationStats restored = objectMapper.readValue(serialized, CalibrationStats.class);
        assertEquals(0.965, restored.getRSquared(), 1e-12);
        assertEquals(0.032, restored.getRmse(), 1e-12);
        assertEquals(0.14, restored.getWeightedCorrelationCost(), 1e-12);
        assertEquals(2, restored.getNumCalibratedMaterials());
        assertEquals(List.of("469", "551"), restored.getCalibratedMaterials());

        assertEquals(1.425, restored.getRoiChi2Reduced(), 1e-12);
        assertEquals(0.952314, restored.getRoiRSquared(), 1e-12);
        assertEquals(0.041234, restored.getRoiRmse(), 1e-12);
        assertEquals(182.456789, restored.getRoiRmseRaw(), 1e-12);
        assertEquals(0.891234, restored.getWholeSpectrumRSquared(), 1e-12);
        assertEquals(0.067891, restored.getWholeSpectrumRmse(), 1e-12);
        assertEquals(245.123456, restored.getWholeSpectrumRmseRaw(), 1e-12);
        assertEquals(0.0185, restored.getZoneReductionPenalty(), 1e-12);
        assertEquals(0.0421, restored.getFamilySharingPenalty(), 1e-12);

        assertNotNull(restored.getElementParity());
        assertEquals(1.012, restored.getElementParity().get("Fe"), 1e-12);
        assertEquals(0.978, restored.getElementParity().get("Cr"), 1e-12);

        assertNotNull(restored.getPerMaterialStats());
        assertTrue(restored.getPerMaterialStats().containsKey("469"));
        Map<String, Object> mat469 = restored.getPerMaterialStats().get("469");
        assertEquals(1.35, ((Number) mat469.get("roiChi2Reduced")).doubleValue(), 1e-12);
        assertEquals(42, ((Number) mat469.get("numSteadyShots")).intValue());
    }

    @Test
    @DisplayName("org.json toJson/fromJson round-trips all 16 CalibrationStats fields cleanly")
    void testOrgJsonRoundTripAllFields() {
        CalibrationStats original = buildFullyPopulatedStats();

        JSONObject json = original.toJson();
        CalibrationStats restored = CalibrationStats.fromJson(json);
        assertNotNull(restored);

        assertEquals(0.965, restored.getRSquared(), 1e-12);
        assertEquals(0.032, restored.getRmse(), 1e-12);
        assertEquals(0.14, restored.getWeightedCorrelationCost(), 1e-12);
        assertEquals(2, restored.getNumCalibratedMaterials());
        assertEquals(List.of("469", "551"), restored.getCalibratedMaterials());

        assertEquals(1.425, restored.getRoiChi2Reduced(), 1e-12);
        assertEquals(0.952314, restored.getRoiRSquared(), 1e-12);
        assertEquals(0.041234, restored.getRoiRmse(), 1e-12);
        assertEquals(182.456789, restored.getRoiRmseRaw(), 1e-12);
        assertEquals(0.891234, restored.getWholeSpectrumRSquared(), 1e-12);
        assertEquals(0.067891, restored.getWholeSpectrumRmse(), 1e-12);
        assertEquals(245.123456, restored.getWholeSpectrumRmseRaw(), 1e-12);
        assertEquals(0.0185, restored.getZoneReductionPenalty(), 1e-12);
        assertEquals(0.0421, restored.getFamilySharingPenalty(), 1e-12);

        assertEquals(1.012, restored.getElementParity().get("Fe"), 1e-12);
        assertEquals(0.978, restored.getElementParity().get("Cr"), 1e-12);

        Map<String, Object> mat469 = restored.getPerMaterialStats().get("469");
        assertNotNull(mat469);
        assertEquals(1.35, ((Number) mat469.get("roiChi2Reduced")).doubleValue(), 1e-12);
        assertEquals(0.961, ((Number) mat469.get("roiRSquared")).doubleValue(), 1e-12);
        assertEquals(42, ((Number) mat469.get("numSteadyShots")).intValue());
    }

    @Test
    @DisplayName("Python gpu_calibration_engine.py calibrationStats schema binds via both Jackson and org.json")
    void testPythonCalibrationStatsSchemaBinding() throws Exception {
        String pythonJson = """
                {
                  "roiChi2Reduced": 2.1543,
                  "roiRSquared": 0.934512,
                  "roiRmse": 0.051234,
                  "roiRmseRaw": 144.876543,
                  "wholeSpectrumRSquared": 0.876543,
                  "wholeSpectrumRmse": 0.082345,
                  "wholeSpectrumRmseRaw": 231.54321,
                  "zoneReductionPenalty": 0.0234,
                  "familySharingPenalty": 0.0567,
                  "elementParity": {"Fe": 1.004, "Mn": 0.962},
                  "numCalibratedMaterials": 2,
                  "calibratedMaterials": ["SS469", "SS551"],
                  "perMaterialStats": {
                    "SS469": {
                      "roiChi2Reduced": 1.9876,
                      "roiRSquared": 0.941234,
                      "roiRmse": 0.049123,
                      "roiRmseRaw": 139.123456,
                      "wholeSpectrumRSquared": 0.881234,
                      "wholeSpectrumRmse": 0.079123,
                      "wholeSpectrumRmseRaw": 225.123456,
                      "numSteadyShots": 38
                    }
                  }
                }
                """;

        CalibrationStats viaJackson = objectMapper.readValue(pythonJson, CalibrationStats.class);
        CalibrationStats viaOrgJson = CalibrationStats.fromJson(new JSONObject(pythonJson));

        for (CalibrationStats stats : List.of(viaJackson, viaOrgJson)) {
            assertEquals(2.1543, stats.getRoiChi2Reduced(), 1e-12);
            assertEquals(0.934512, stats.getRoiRSquared(), 1e-12);
            assertEquals(0.051234, stats.getRoiRmse(), 1e-12);
            assertEquals(144.876543, stats.getRoiRmseRaw(), 1e-12);
            assertEquals(0.876543, stats.getWholeSpectrumRSquared(), 1e-12);
            assertEquals(0.082345, stats.getWholeSpectrumRmse(), 1e-12);
            assertEquals(231.54321, stats.getWholeSpectrumRmseRaw(), 1e-12);
            assertEquals(0.0234, stats.getZoneReductionPenalty(), 1e-12);
            assertEquals(0.0567, stats.getFamilySharingPenalty(), 1e-12);
            assertEquals(1.004, stats.getElementParity().get("Fe"), 1e-12);
            assertEquals(0.962, stats.getElementParity().get("Mn"), 1e-12);
            assertEquals(2, stats.getNumCalibratedMaterials());
            assertEquals(List.of("SS469", "SS551"), stats.getCalibratedMaterials());
            assertEquals(38, ((Number) stats.getPerMaterialStats().get("SS469").get("numSteadyShots")).intValue());
        }
    }

    @Test
    @DisplayName("Legacy JSON containing only rSquared and rmse remains backward compatible")
    void testLegacyJsonBackwardCompatibility() throws Exception {
        String legacyJson = "{\"rSquared\": 0.97, \"rmse\": 0.04, \"weightedCorrelationCost\": 0.12, "
                + "\"numCalibratedMaterials\": 2, \"calibratedMaterials\": [\"469\", \"551\"]}";

        CalibrationStats viaJackson = objectMapper.readValue(legacyJson, CalibrationStats.class);
        CalibrationStats viaOrgJson = CalibrationStats.fromJson(new JSONObject(legacyJson));

        for (CalibrationStats stats : List.of(viaJackson, viaOrgJson)) {
            assertEquals(0.97, stats.getRSquared(), 1e-12);
            assertEquals(0.04, stats.getRmse(), 1e-12);
            assertEquals(0.12, stats.getWeightedCorrelationCost(), 1e-12);
            assertEquals(2, stats.getNumCalibratedMaterials());
            assertEquals(List.of("469", "551"), stats.getCalibratedMaterials());
            assertEquals(0.0, stats.getRoiChi2Reduced(), 1e-12);
            assertEquals(0.0, stats.getRoiRSquared(), 1e-12);
            assertNotNull(stats.getElementParity());
            assertTrue(stats.getElementParity().isEmpty());
            assertNotNull(stats.getPerMaterialStats());
            assertTrue(stats.getPerMaterialStats().isEmpty());
        }

        assertNull(CalibrationStats.fromJson(null));
    }

    @Test
    @DisplayName("Null map setters safely default to empty maps without NPE during serialization")
    void testNullMapSettersDefaultToEmptyMaps() {
        CalibrationStats stats = new CalibrationStats();
        stats.setElementParity(null);
        stats.setPerMaterialStats(null);

        assertNotNull(stats.getElementParity());
        assertTrue(stats.getElementParity().isEmpty());
        assertNotNull(stats.getPerMaterialStats());
        assertTrue(stats.getPerMaterialStats().isEmpty());

        JSONObject json = stats.toJson();
        assertNotNull(json.getJSONObject("elementParity"));
        assertNotNull(json.getJSONObject("perMaterialStats"));
    }
}
