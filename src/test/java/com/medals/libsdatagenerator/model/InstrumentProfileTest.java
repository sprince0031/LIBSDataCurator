package com.medals.libsdatagenerator.model;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class InstrumentProfileTest {

    @Test
    void testToJsonAndFromJson() {
        String instrumentName = "Test Instrument";
        List<Double> wavelengths = Arrays.asList(200.0, 201.5, 203.0);
        
        PlasmaZone hotCore = new PlasmaZone(1.5, 1e17, 0.7);
        PlasmaZone coolPeriphery = new PlasmaZone(0.8, 1e16, 0.3);
        PlasmaParameters plasmaParameters = new PlasmaParameters(hotCore, coolPeriphery);
        
        CalibrationStats calibrationStats = new CalibrationStats(0.99, 0.05, 0.0025);

        InstrumentProfile profile = new InstrumentProfile(instrumentName, wavelengths, plasmaParameters, calibrationStats);

        // Serialize to JSON
        JSONObject json = profile.toJson();
        assertNotNull(json);
        assertEquals(instrumentName, json.getString("instrumentName"));
        assertEquals(3, json.getJSONArray("wavelengths").length());
        assertEquals(1.5, json.getJSONObject("plasmaParameters").getJSONObject("hotCore").getDouble("Te"));

        // Deserialize from JSON
        InstrumentProfile deserializedProfile = InstrumentProfile.fromJson(json);
        assertNotNull(deserializedProfile);
        assertEquals(instrumentName, deserializedProfile.getInstrumentName());
        assertEquals(wavelengths, deserializedProfile.getWavelengths());
        assertEquals(1.5, deserializedProfile.getPlasmaParameters().getHotCore().getTe());
        assertEquals(0.99, deserializedProfile.getCalibrationStats().getRSquared());
    }
}
