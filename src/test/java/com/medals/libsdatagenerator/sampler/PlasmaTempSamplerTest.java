package com.medals.libsdatagenerator.sampler;

import com.medals.libsdatagenerator.model.PlasmaZone;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class PlasmaTempSamplerTest {

    private List<PlasmaZone> createTwoZoneBase() {
        List<PlasmaZone> zones = new ArrayList<>();
        // Zone 0: Core
        PlasmaZone core = new PlasmaZone(1.20, 2.5e17, 0.8, 0.0);
        core.setZoneIndex(0);
        core.setTeStdDev(0.06);
        core.setNeLogStdDev(0.10);
        zones.add(core);

        // Zone 1: Periphery
        PlasmaZone periphery = new PlasmaZone(0.65, 3.0e16, 0.2, 2.5);
        periphery.setZoneIndex(1);
        periphery.setTeStdDev(0.04);
        periphery.setNeLogStdDev(0.08);
        zones.add(periphery);

        return zones;
    }

    @Test
    @DisplayName("sampleZones preserves core > periphery temperature hierarchy across all shots")
    void testSampleZonesPreservesTeOrdering() {
        PlasmaTempSampler sampler = PlasmaTempSampler.getInstance();
        List<PlasmaZone> base = createTwoZoneBase();

        List<List<PlasmaZone>> sampled = sampler.sampleZones(base, 100, 0.05, 42L);
        assertEquals(100, sampled.size());

        for (List<PlasmaZone> shot : sampled) {
            assertEquals(2, shot.size());
            PlasmaZone core = shot.get(0);
            PlasmaZone periphery = shot.get(1);

            assertTrue(core.getTe() > periphery.getTe(),
                    "Core Te (" + core.getTe() + ") must exceed periphery Te (" + periphery.getTe() + ")");
            assertTrue(core.getNe() > periphery.getNe(),
                    "Core Ne (" + core.getNe() + ") must exceed periphery Ne (" + periphery.getNe() + ")");
        }
    }

    @Test
    @DisplayName("Coupled sampling maintains Te and Ne covariance")
    void testCoupledTeNeCovariance() {
        PlasmaTempSampler sampler = PlasmaTempSampler.getInstance();
        List<PlasmaZone> base = createTwoZoneBase();

        List<List<PlasmaZone>> sampled = sampler.sampleZones(base, 50, 0.05, 123L);

        for (List<PlasmaZone> shot : sampled) {
            PlasmaZone core = shot.get(0);
            double baseTe = base.get(0).getTe();
            double baseNe = base.get(0).getNe();

            // When Te increases, Ne must also increase (same zShot variate)
            if (core.getTe() > baseTe) {
                assertTrue(core.getNe() >= baseNe, "Ne should increase when Te increases");
            } else if (core.getTe() < baseTe) {
                assertTrue(core.getNe() <= baseNe, "Ne should decrease when Te decreases");
            }
        }
    }

    @Test
    @DisplayName("Perturbed values strictly respect physical boundary clamping [0.45, 2.20] eV and [10^15, 10^18] cm^-3")
    void testClampingBounds() {
        PlasmaTempSampler sampler = PlasmaTempSampler.getInstance();
        List<PlasmaZone> base = createTwoZoneBase();

        List<List<PlasmaZone>> sampled = sampler.sampleZones(base, 200, 0.15, 9999L);
        for (List<PlasmaZone> shot : sampled) {
            for (PlasmaZone z : shot) {
                assertTrue(z.getTe() >= 0.45 && z.getTe() <= 2.20,
                        "Te " + z.getTe() + " must be in [0.45, 2.20]");
                assertTrue(z.getNe() >= 1.0e15 && z.getNe() <= 1.0e18,
                        "Ne " + z.getNe() + " must be in [1e15, 1e18]");
            }
        }
    }

    @Test
    @DisplayName("sampleZones is reproducible with same random seed")
    void testReproducibilityWithSeed() {
        PlasmaTempSampler sampler = PlasmaTempSampler.getInstance();
        List<PlasmaZone> base = createTwoZoneBase();

        List<List<PlasmaZone>> run1 = sampler.sampleZones(base, 10, 0.05, 777L);
        List<List<PlasmaZone>> run2 = sampler.sampleZones(base, 10, 0.05, 777L);

        assertEquals(run1.size(), run2.size());
        for (int i = 0; i < run1.size(); i++) {
            for (int j = 0; j < run1.get(i).size(); j++) {
                assertEquals(run1.get(i).get(j).getTe(), run2.get(i).get(j).getTe(), 1e-6);
                assertEquals(run1.get(i).get(j).getNe(), run2.get(i).get(j).getNe(), 1e-6);
            }
        }
    }

    @Test
    @DisplayName("sample() interface method delegates cleanly to sampleZones()")
    void testSampleInterfaceMethod() {
        PlasmaTempSampler sampler = PlasmaTempSampler.getInstance();
        List<PlasmaZone> base = createTwoZoneBase();

        List<List<PlasmaZone>> direct = sampler.sampleZones(base, 5, 0.05, 888L);
        List<List<PlasmaZone>> delegated = sampler.sample(base, 5, 0.05, 888L);

        assertEquals(direct.size(), delegated.size());
        for (int i = 0; i < direct.size(); i++) {
            for (int j = 0; j < direct.get(i).size(); j++) {
                assertEquals(direct.get(i).get(j).getTe(), delegated.get(i).get(j).getTe(), 1e-6);
                assertEquals(direct.get(i).get(j).getNe(), delegated.get(i).get(j).getNe(), 1e-6);
            }
        }
    }
}
