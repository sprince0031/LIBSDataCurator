package com.medals.libsdatagenerator.model;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InstrumentProfileTest {

    @Test
    void testToJsonAndFromJson() {
        String instrumentName = "Test Instrument";
        double[] wavelengths = { 200.0, 201.5, 203.0 };
        String materialFamilyName = "testMaterial";
        PlasmaZone hotCore = new PlasmaZone(1.5, 1e17, 0.7, 2.5);
        PlasmaZone coolPeriphery = new PlasmaZone(0.8, 1e16, 0.3, 2.5);
        List<PlasmaZone> plasmaZones = Arrays.asList(hotCore, coolPeriphery);
        CalibrationStats calibrationStats = new CalibrationStats(0.99, 0.05);
        MaterialFamilyProfile familyProfile = new MaterialFamilyProfile(materialFamilyName, plasmaZones, calibrationStats);
        Map<String, MaterialFamilyProfile> materialFamilyProfiles = new HashMap<>();
        materialFamilyProfiles.put(familyProfile.getMaterialFamilyName(), familyProfile);
        BaselineCorrectionParams baselineParams = new BaselineCorrectionParams();

        InstrumentProfile profile = new InstrumentProfile(instrumentName, wavelengths, materialFamilyProfiles, baselineParams);

        // Serialize to JSON
        JSONObject json = profile.toJson();
        assertNotNull(json);
        assertEquals(instrumentName, json.getString("instrumentName"));
        assertEquals(3, json.getJSONArray("wavelengths").length());
        assertEquals(1.5, json.getJSONObject("materialFamilyProfiles").getJSONObject("testMaterial")
                .getJSONArray("plasmaZones")
                .getJSONObject(0).getDouble("Te"));

        // Deserialize from JSON
        InstrumentProfile deserializedProfile = new InstrumentProfile();
        deserializedProfile.fromJson(json);
        assertNotNull(deserializedProfile);
        assertEquals(instrumentName, deserializedProfile.getInstrumentName());
        assertArrayEquals(wavelengths, deserializedProfile.getWavelengthGrid());
        MaterialFamilyProfile deserializedMaterialFamilyProfile = deserializedProfile.getMaterialFamilyProfiles()
                .get(materialFamilyName);
        assertNotNull(deserializedMaterialFamilyProfile);
        assertEquals(1.5, deserializedMaterialFamilyProfile.getPlasmaZones().get(0).getTe());
        assertEquals(0.99, deserializedMaterialFamilyProfile.getCalibrationStats().getRSquared());
    }

    @Test
    void testNewProfileOptionalFieldsRoundTrip() {
        InstrumentProfile profile = new InstrumentProfile();
        profile.setInstrumentName("Parity_Instrument");
        profile.setWavelengthGrid(new double[]{350.0, 500.0, 850.0});
        profile.setEmissionScale("absolute");
        profile.setLineSpreadAddSigmaNm(List.of(0.12, 0.14, 0.16));
        profile.setLineSpreadFwhmNm(List.of(0.35, 0.37, 0.39));
        profile.setSpectralResponseValidRangeNm(List.of(352.4, 688.1));
        profile.setValidChannelRangeNm(List.of(346.655, 896.916));

        JSONObject json = profile.toJson();
        assertNotNull(json);
        assertEquals("absolute", json.getString("emissionScale"));
        assertEquals(3, json.getJSONArray("lineSpreadAddSigmaNm").length());
        assertEquals(3, json.getJSONArray("lineSpreadFwhmNm").length());
        assertEquals(2, json.getJSONArray("spectralResponseValidRangeNm").length());
        assertEquals(2, json.getJSONArray("validChannelRangeNm").length());

        InstrumentProfile deserialized = new InstrumentProfile();
        deserialized.fromJson(json);
        assertEquals("absolute", deserialized.getEmissionScale());
        assertEquals(List.of(0.12, 0.14, 0.16), deserialized.getLineSpreadAddSigmaNm());
        assertEquals(List.of(0.35, 0.37, 0.39), deserialized.getLineSpreadFwhmNm());
        assertEquals(List.of(352.4, 688.1), deserialized.getSpectralResponseValidRangeNm());
        assertEquals(List.of(346.655, 896.916), deserialized.getValidChannelRangeNm());

        // Backward compatibility: loading from legacy JSON without new fields
        JSONObject legacyJson = new JSONObject();
        legacyJson.put("instrumentName", "Legacy_Instrument");
        legacyJson.put("wavelengths", new double[]{400.0, 500.0});
        InstrumentProfile legacyProfile = new InstrumentProfile();
        legacyProfile.fromJson(legacyJson);
        assertEquals("Legacy_Instrument", legacyProfile.getInstrumentName());
        assertEquals("absolute", legacyProfile.getEmissionScale());
    }
}
