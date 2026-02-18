package com.medals.libsdatagenerator.model;

import java.util.Arrays;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InstrumentProfileTest {

    @Test
    void testToJsonAndFromJson() {
        String instrumentName = "Test Instrument";
        double[] wavelengths = { 200.0, 201.5, 203.0 };

        PlasmaZone hotCore = new PlasmaZone(1.5, 1e17, 0.7);
        PlasmaZone coolPeriphery = new PlasmaZone(0.8, 1e16, 0.3);
        List<PlasmaZone> zones = Arrays.asList(hotCore, coolPeriphery);
        PlasmaParameters plasmaParameters = new PlasmaParameters(zones);

        CalibrationStats calibrationStats = new CalibrationStats(0.99, 0.05);
        BaselineCorrectionParams baselineParams = new BaselineCorrectionParams();

        InstrumentProfile profile = new InstrumentProfile(instrumentName, wavelengths, plasmaParameters,
                calibrationStats, baselineParams);

        // Serialize to JSON
        JSONObject json = profile.toJson();
        assertNotNull(json);
        assertEquals(instrumentName, json.getString("instrumentName"));
        assertEquals(3, json.getJSONArray("wavelengths").length());
        assertEquals(1.5,
                json.getJSONObject("plasmaParameters").getJSONArray("zones").getJSONObject(0).getDouble("Te"));

        // Deserialize from JSON
        InstrumentProfile deserializedProfile = InstrumentProfile.fromJson(json);
        assertNotNull(deserializedProfile);
        assertEquals(instrumentName, deserializedProfile.getInstrumentName());
        assertArrayEquals(wavelengths, deserializedProfile.getWavelengthGrid());
        assertEquals(1.5, deserializedProfile.getPlasmaParameters().getZones().get(0).getTe());
        assertEquals(0.99, deserializedProfile.getCalibrationStats().getRSquared());
    }
}
