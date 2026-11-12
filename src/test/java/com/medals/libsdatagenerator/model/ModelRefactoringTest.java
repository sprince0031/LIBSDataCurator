package com.medals.libsdatagenerator.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ModelRefactoringTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("PlasmaZone should support teStdDev and neLogStdDev with defaults")
    void testPlasmaZoneFields() {
        PlasmaZone zone = new PlasmaZone(1.2, 1e17, 0.8, 0.0);
        assertEquals(0.05, zone.getTeStdDev());
        assertEquals(0.08, zone.getNeLogStdDev());
        assertEquals(0.8, zone.getVolumeFraction());
        assertEquals(0.8, zone.getVFraction());

        zone.setTeStdDev(0.06);
        zone.setNeLogStdDev(0.12);
        zone.setZoneIndex(1);
        assertEquals(0.06, zone.getTeStdDev());
        assertEquals(0.12, zone.getNeLogStdDev());
        assertEquals(1, zone.getZoneIndex());

        // Test copy constructor
        PlasmaZone copy = new PlasmaZone(zone);
        assertEquals(zone.getZoneIndex(), copy.getZoneIndex());
        assertEquals(zone.getTe(), copy.getTe());
        assertEquals(zone.getTeStdDev(), copy.getTeStdDev());
        assertEquals(zone.getNe(), copy.getNe());
        assertEquals(zone.getNeLogStdDev(), copy.getNeLogStdDev());
    }

    @Test
    @DisplayName("Legacy VoigtProfile should be ignored on deserialization")
    void testLegacyVoigtProfileIgnoredOnDeserialization() throws Exception {
        String legacyJson = "{\n" +
                "  \"instrumentName\": \"Legacy_Inst\",\n" +
                "  \"wavelengths\": [200.0, 300.0],\n" +
                "  \"voigtProfile\": {\n" +
                "    \"nistResolvingPower\": 4000.0,\n" +
                "    \"fwhmGaussianNm\": [0.28],\n" +
                "    \"fwhmLorentzianNm\": [0.06]\n" +
                "  }\n" +
                "}";

        InstrumentProfile profile = objectMapper.readValue(legacyJson, InstrumentProfile.class);
        assertNotNull(profile);
        assertEquals("Legacy_Inst", profile.getInstrumentName());
        assertFalse(profile.toJson().has("voigtProfile"), "Exported JSON must not contain voigtProfile");
    }

    @Test
    @DisplayName("InstrumentProfile should support spectralResponse, finotelloNoiseBeta and holdout CSVs without Voigt")
    void testInstrumentProfileNewFields() throws Exception {
        InstrumentProfile profile = new InstrumentProfile();
        profile.setInstrumentName("Test_Inst");
        profile.setSpectralResponse(new double[]{1.0, 1.1, 0.95});
        profile.setFinotelloNoiseBeta(0.095);

        MaterialFamilyProfile familyProfile = new MaterialFamilyProfile("Ferritic");
        Map<String, List<String>> holdouts = new HashMap<>();
        holdouts.put("469", List.of("/path/point_5.csv"));
        familyProfile.setValidationHoldoutCsvs(holdouts);
        profile.addMaterialFamilyProfile(familyProfile);

        // Test org.json roundtrip
        JSONObject json = profile.toJson();
        assertEquals(0.095, json.getDouble("finotelloNoiseBeta"));
        assertEquals(3, json.getJSONArray("spectralResponse").length());
        assertFalse(json.has("voigtProfile"), "Profile JSON must not have voigtProfile");

        InstrumentProfile restored = new InstrumentProfile();
        restored.fromJson(json);
        assertEquals("Test_Inst", restored.getInstrumentName());
        assertEquals(0.095, restored.getFinotelloNoiseBeta());
        assertNotNull(restored.getSpectralResponse());
        assertEquals(3, restored.getSpectralResponse().length);
        assertNotNull(restored.getMaterialFamilyProfile("Ferritic").getValidationHoldoutCsvs());
        assertTrue(restored.getMaterialFamilyProfile("Ferritic").getValidationHoldoutCsvs().containsKey("469"));
    }
}
