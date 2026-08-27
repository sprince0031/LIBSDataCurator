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
        PlasmaZone hotCore = new PlasmaZone(1.5, 1e17, 0.7);
        PlasmaZone coolPeriphery = new PlasmaZone(0.8, 1e16, 0.3);
        List<PlasmaZone> plasmaZones = Arrays.asList(hotCore, coolPeriphery);
//        PlasmaParameters plasmaParameters = new PlasmaParameters(zones);
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
}
