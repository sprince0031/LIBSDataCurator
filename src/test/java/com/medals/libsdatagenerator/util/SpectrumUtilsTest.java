package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.model.PlasmaZone;
import com.medals.libsdatagenerator.model.VoigtProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SpectrumUtilsTest {

    private SpectrumUtils spectrumUtils;

    @BeforeEach
    void setUp() {
        spectrumUtils = new SpectrumUtils();
    }

    @Test
    @DisplayName("combineNZones applies Beer-Lambert self-absorption correctly")
    void testCombineNZonesTwoZonesAbsorption() {
        // Zone 0: Core
        PlasmaZone core = new PlasmaZone(1.2, 2.0e17, 0.8, 0.0);
        // Zone 1: Periphery
        PlasmaZone periph = new PlasmaZone(0.6, 2.0e16, 0.2, 0.5);

        double[] coreSpec = {10.0, 20.0};
        double[] periphSpec = {2.0, 4.0};

        List<double[]> spectra = List.of(coreSpec, periphSpec);
        List<PlasmaZone> zones = List.of(core, periph);

        double[] combined = spectrumUtils.combineNZones(spectra, zones);
        assertNotNull(combined);
        assertEquals(2, combined.length);

        // Channel 0:
        // I_inner = 10.0 * 0.8 = 8.0
        // transmission = exp(-0.5 * 2.0) = exp(-1.0) ~= 0.367879
        // I_outer = 0.2 * 2.0 = 0.4
        // Expected = 8.0 * 0.367879 + 0.4 = 2.94303 + 0.4 = 3.34303
        double expected0 = (10.0 * 0.8) * Math.exp(-0.5 * 2.0) + (0.2 * 2.0);
        assertEquals(expected0, combined[0], 1e-4);

        // Channel 1:
        // I_inner = 20.0 * 0.8 = 16.0
        // transmission = exp(-0.5 * 4.0) = exp(-2.0) ~= 0.135335
        // I_outer = 0.2 * 4.0 = 0.8
        // Expected = 16.0 * 0.135335 + 0.8 = 2.16536 + 0.8 = 2.96536
        double expected1 = (20.0 * 0.8) * Math.exp(-0.5 * 4.0) + (0.2 * 4.0);
        assertEquals(expected1, combined[1], 1e-4);
    }

    @Test
    @DisplayName("applySpectralResponse correctly multiplies spectrum by r(lambda)")
    void testApplySpectralResponse() {
        double[] spec = {10.0, 20.0, 30.0};
        List<Double> resp = List.of(0.5, 1.0, 1.5);

        double[] result = spectrumUtils.applySpectralResponse(spec, resp);
        assertNotNull(result);
        assertEquals(3, result.length);
        assertEquals(5.0, result[0], 1e-6);
        assertEquals(20.0, result[1], 1e-6);
        assertEquals(45.0, result[2], 1e-6);
    }

    @Test
    @DisplayName("normaliseAndScaleSpectrum with spectral response scales normalized output")
    void testNormaliseAndScaleWithSpectralResponse() {
        double[] spec = {10.0, 20.0};
        List<Double> resp = List.of(0.5, 2.0); // -> [5.0, 40.0], max = 40.0 -> [0.125, 1.0]
        double scaleFactor = 1000.0;

        List<Double> scaled = spectrumUtils.normaliseAndScaleSpectrum(spec, scaleFactor, resp);
        assertNotNull(scaled);
        assertEquals(2, scaled.size());
        assertEquals(125.0, scaled.get(0), 1e-4);
        assertEquals(1000.0, scaled.get(1), 1e-4);
    }

    @Test
    @DisplayName("applyVoigtBroadening broadens a delta peak conserving energy")
    void testApplyVoigtBroadening() {
        int n = 51;
        double[] wavelengths = new double[n];
        double[] intensities = new double[n];
        for (int i = 0; i < n; i++) {
            wavelengths[i] = 300.0 + i * 0.1; // 0.1 nm spacing
        }
        intensities[25] = 100.0; // Sharp central line at 302.5 nm

        VoigtProfile profile = new VoigtProfile();
        profile.setNistResolvingPower(4000.0);
        List<Double> fwhmG = new ArrayList<>();
        List<Double> fwhmL = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fwhmG.add(0.3); // 3 channels Gaussian FWHM
            fwhmL.add(0.1); // 1 channel Lorentzian FWHM
        }
        profile.setFwhmGaussianNm(fwhmG);
        profile.setFwhmLorentzianNm(fwhmL);

        double[] broadened = spectrumUtils.applyVoigtBroadening(wavelengths, intensities, profile);
        assertNotNull(broadened);
        assertEquals(n, broadened.length);

        // Peak intensity at center should decrease as energy spreads
        assertTrue(broadened[25] < 100.0, "Central peak should decrease due to broadening");
        // Neighbors should now have non-zero intensity
        assertTrue(broadened[24] > 0.0);
        assertTrue(broadened[26] > 0.0);

        // Total energy / integral should be conserved approximately
        double originalSum = Arrays.stream(intensities).sum();
        double broadenedSum = Arrays.stream(broadened).sum();
        assertEquals(originalSum, broadenedSum, originalSum * 0.05);
    }
}
