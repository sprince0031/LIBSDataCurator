package com.medals.libsdatagenerator.service;

import com.medals.libsdatagenerator.model.InstrumentProfile;
import com.medals.libsdatagenerator.model.PlasmaZone;
import com.medals.libsdatagenerator.util.NISTUtils;
import com.medals.libsdatagenerator.util.SpectrumUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class LIBSDataServiceTest {

    private final LIBSDataService libsDataService = LIBSDataService.getInstance();

    private static double[] toDoubleArray(JSONArray arr) {
        double[] res = new double[arr.length()];
        for (int i = 0; i < arr.length(); i++) {
            res[i] = arr.getDouble(i);
        }
        return res;
    }

    private static List<Double> toDoubleList(double[] arr) {
        List<Double> list = new ArrayList<>(arr.length);
        for (double v : arr) {
            list.add(v);
        }
        return list;
    }

    private static String buildNistCsv(double[] wavelengths, double[] intensities, double scale, String sumHeader) {
        StringBuilder sb = new StringBuilder();
        sb.append("Wavelength (nm),").append(sumHeader).append("\n");
        for (int i = 0; i < wavelengths.length; i++) {
            sb.append(Double.toString(wavelengths[i]))
              .append(",")
              .append(Double.toString(intensities[i] * scale))
              .append("\n");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("End-to-end Java synthesis chain matches forward_model_fixture.json within 1e-9 (M4)")
    void testEndToEndSynthesisChainMatchesPythonForwardFixture() throws Exception {
        InputStream is = getClass().getResourceAsStream("/forward_model_fixture.json");
        assertNotNull(is, "forward_model_fixture.json must exist in test resources");
        String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        JSONObject json = new JSONObject(content);

        double[] srcWl = toDoubleArray(json.getJSONArray("srcWl"));
        double[] srcI = toDoubleArray(json.getJSONArray("srcI"));
        double[] detWl = toDoubleArray(json.getJSONArray("detWl"));
        double[] addSigma = toDoubleArray(json.getJSONArray("addSigmaNm"));
        double[] response = toDoubleArray(json.getJSONArray("spectralResponse"));
        double scaleFactor = json.getDouble("scaleFactor");
        double[] finalAbsExp = toDoubleArray(json.getJSONArray("finalAbsoluteExpected"));
        double[] finalNormExp = toDoubleArray(json.getJSONArray("finalMaxNormalisedExpected"));

        // Match scripts/test_forward_model.py: d0 = resampled * 1.5, d1 = resampled * 0.8
        String zone0Csv = buildNistCsv(srcWl, srcI, 1.5, "Sum(calc)");
        String zone1Csv = buildNistCsv(srcWl, srcI, 0.8, "Sum(calc)");

        JSONArray zonesJson = json.getJSONArray("zones");
        List<PlasmaZone> zones = new ArrayList<>();
        for (int i = 0; i < zonesJson.length(); i++) {
            JSONObject zObj = zonesJson.getJSONObject(i);
            zones.add(new PlasmaZone(
                    zObj.getDouble("te"),
                    zObj.getDouble("ne"),
                    zObj.getDouble("volumeFraction"),
                    zObj.getDouble("kAbsorption")
            ));
        }

        InstrumentProfile profile = new InstrumentProfile();
        assertEquals(0.0, profile.getFinotelloNoiseBeta(), 0.0,
                "finotelloNoiseBeta must default to 0.0 (Python calibration deferred)");
        profile.setWavelengthGrid(detWl);
        profile.setSpectralResponse(response);
        profile.setLineSpreadAddSigmaNm(toDoubleList(addSigma));

        // 1. Absolute emissionScale
        profile.setEmissionScale("absolute");
        double[] absActual = libsDataService.synthesizeSpectrumFromZoneCsvs(
                List.of(zone0Csv, zone1Csv), zones, profile, scaleFactor, "nm", null);
        assertEquals(finalAbsExp.length, absActual.length);
        for (int i = 0; i < finalAbsExp.length; i++) {
            assertEquals(finalAbsExp[i], absActual[i], Math.abs(finalAbsExp[i]) * 1e-9 + 1e-12,
                    "End-to-end absolute channel " + i + " mismatch");
        }

        // 2. Max-normalised emissionScale
        profile.setEmissionScale("max_normalised");
        double[] normActual = libsDataService.synthesizeSpectrumFromZoneCsvs(
                List.of(zone0Csv, zone1Csv), zones, profile, scaleFactor, "nm", null);
        assertEquals(finalNormExp.length, normActual.length);
        for (int i = 0; i < finalNormExp.length; i++) {
            assertEquals(finalNormExp[i], normActual[i], Math.abs(finalNormExp[i]) * 1e-9 + 1e-12,
                    "End-to-end max_normalised channel " + i + " mismatch");
        }
    }

    @Test
    @DisplayName("Null or empty lineSpreadAddSigmaNm uses flux-conserving zero-broadening boxcar resampling (H5)")
    void testNullAndEmptyLineSpreadAddSigmaUsesFluxConservingBoxcar() throws Exception {
        // Dense source spectrum with a narrow emission peak centered halfway between detector pixels 355.0 and 356.0
        int nSrc = 501;
        double[] srcWl = new double[nSrc];
        double[] srcI = new double[nSrc];
        for (int i = 0; i < nSrc; i++) {
            srcWl[i] = 350.0 + i * 0.02;
            double dx = (srcWl[i] - 355.5) / 0.08;
            srcI[i] = 1000.0 * Math.exp(-0.5 * dx * dx);
        }
        double[] detWl = new double[11];
        double[] unitResponse = new double[11];
        for (int i = 0; i < detWl.length; i++) {
            detWl[i] = 350.0 + i;
            unitResponse[i] = 1.0;
        }

        String nistCsv = buildNistCsv(srcWl, srcI, 1.0, "Sum");
        List<PlasmaZone> singleZone = List.of(new PlasmaZone(1.0, 1e17, 1.0, 0.0));

        InstrumentProfile nullSigmaProfile = new InstrumentProfile();
        nullSigmaProfile.setWavelengthGrid(detWl);
        nullSigmaProfile.setSpectralResponse(unitResponse);
        nullSigmaProfile.setEmissionScale("absolute");
        nullSigmaProfile.setLineSpreadAddSigmaNm(null);

        InstrumentProfile emptySigmaProfile = new InstrumentProfile();
        emptySigmaProfile.setWavelengthGrid(detWl);
        emptySigmaProfile.setSpectralResponse(unitResponse);
        emptySigmaProfile.setEmissionScale("absolute");
        emptySigmaProfile.setLineSpreadAddSigmaNm(Collections.emptyList());

        double[] nullResult = libsDataService.synthesizeSpectrumFromZoneCsvs(
                List.of(nistCsv), singleZone, nullSigmaProfile, 1.0, "nm", null);
        double[] emptyResult = libsDataService.synthesizeSpectrumFromZoneCsvs(
                List.of(nistCsv), singleZone, emptySigmaProfile, 1.0, "nm", null);
        double[] boxcarRef = SpectrumUtils.resampleToDetector(srcWl, srcI, detWl, new double[detWl.length]);

        assertArrayEquals(boxcarRef, nullResult, 1e-12,
                "null lineSpreadAddSigmaNm must match zero-array flux-conserving resampleToDetector");
        assertArrayEquals(boxcarRef, emptyResult, 1e-12,
                "empty lineSpreadAddSigmaNm must match zero-array flux-conserving resampleToDetector");

        // Verify flux conservation vs non-flux-conserving pointwise linear interpolation
        double srcFlux = 0.0;
        for (int i = 0; i < nSrc - 1; i++) {
            srcFlux += 0.5 * (srcI[i] + srcI[i + 1]) * (srcWl[i + 1] - srcWl[i]);
        }
        double boxcarFlux = Arrays.stream(nullResult).sum(); // uniform 1.0 nm pixel width
        assertEquals(srcFlux, boxcarFlux, srcFlux * 1e-3,
                "Boxcar resampling must conserve integrated line flux");

        Map<Double, Double> waveMap = NISTUtils.parseNistCsv(nistCsv, "nm");
        double[] pointwiseInterp = new SpectrumUtils().interpolateSpectrum(waveMap, detWl);
        double interpFlux = Arrays.stream(pointwiseInterp).sum();
        assertTrue(interpFlux < srcFlux * 0.05,
                "Sanity check: pointwise linear interpolation misses sub-pixel peak flux");
        assertTrue(Math.abs(boxcarFlux - interpFlux) > srcFlux * 0.9,
                "Flux-conserving fallback must diverge dramatically from pointwise linear interpolation on narrow peaks");
    }

    @Test
    @DisplayName("Deterministic seed propagation produces bit-exact noisy spectra for identical (seed, sampleIndex)")
    void testDeterministicSeedPropagationWithFinotelloNoise() throws Exception {
        double[] srcWl = {350.0, 352.0, 354.0, 356.0, 358.0, 360.0};
        double[] srcI = {100.0, 300.0, 800.0, 600.0, 200.0, 100.0};
        double[] detWl = {350.0, 352.5, 355.0, 357.5, 360.0};
        double[] unitResp = {1.0, 1.0, 1.0, 1.0, 1.0};

        String csv = buildNistCsv(srcWl, srcI, 1.0, "Sum(calc)");
        List<PlasmaZone> zones = List.of(new PlasmaZone(1.0, 1e17, 1.0, 0.0));

        InstrumentProfile profile = new InstrumentProfile();
        profile.setWavelengthGrid(detWl);
        profile.setSpectralResponse(unitResp);
        profile.setEmissionScale("absolute");
        profile.setFinotelloNoiseBeta(0.095);

        double[] run1 = libsDataService.synthesizeSpectrumFromZoneCsvs(
                List.of(csv), zones, profile, 1.0, "nm", LIBSDataService.deriveSampleNoiseRandom(42L, 0));
        double[] run2 = libsDataService.synthesizeSpectrumFromZoneCsvs(
                List.of(csv), zones, profile, 1.0, "nm", LIBSDataService.deriveSampleNoiseRandom(42L, 0));
        double[] diffIndex = libsDataService.synthesizeSpectrumFromZoneCsvs(
                List.of(csv), zones, profile, 1.0, "nm", LIBSDataService.deriveSampleNoiseRandom(42L, 1));
        double[] diffSeed = libsDataService.synthesizeSpectrumFromZoneCsvs(
                List.of(csv), zones, profile, 1.0, "nm", LIBSDataService.deriveSampleNoiseRandom(99L, 0));

        assertArrayEquals(run1, run2, 0.0,
                "Repeated synthesis runs with identical (seed, sampleIndex) must be bit-exact");
        assertFalse(Arrays.equals(run1, diffIndex),
                "Different sampleIndex with same seed must yield distinct noise realization");
        assertFalse(Arrays.equals(run1, diffSeed),
                "Different seed with same sampleIndex must yield distinct noise realization");

        for (double val : run1) {
            assertTrue(val >= 0.0, "Noisy counts must remain non-negative");
        }

        Random unseeded = LIBSDataService.deriveSampleNoiseRandom(null, 0);
        assertNotNull(unseeded);
    }

    @Test
    @DisplayName("synthesizeSpectrumFromZoneCsvs initializes null wavelengthGrid from first parsed NIST spectrum")
    void testSynthesizeInitializesNullWavelengthGrid() throws Exception {
        double[] srcWl = {400.0, 401.0, 402.0, 403.0};
        double[] srcI = {10.0, 50.0, 20.0, 10.0};
        String csv = buildNistCsv(srcWl, srcI, 1.0, "Sum(calc)");

        InstrumentProfile profile = new InstrumentProfile();
        assertNull(profile.getWavelengthGrid());

        double[] result = libsDataService.synthesizeSpectrumFromZoneCsvs(
                List.of(csv),
                List.of(new PlasmaZone(1.0, 1e17, 1.0, 0.0)),
                profile,
                1.0,
                "nm",
                null
        );

        assertNotNull(profile.getWavelengthGrid());
        assertArrayEquals(srcWl, profile.getWavelengthGrid(), 1e-12);
        assertEquals(srcWl.length, result.length);
    }

    @Test
    @DisplayName("synthesizeSpectrumFromZoneCsvs validates mismatched inputs and handles null profile safely")
    void testSynthesizeValidationAndNullProfile() throws Exception {
        double[] srcWl = {400.0, 401.0, 402.0};
        double[] srcI = {10.0, 20.0, 10.0};
        String csv = buildNistCsv(srcWl, srcI, 1.0, "Sum(calc)");
        List<PlasmaZone> oneZone = List.of(new PlasmaZone(1.0, 1e17, 1.0, 0.0));

        assertThrows(IllegalArgumentException.class, () ->
                libsDataService.synthesizeSpectrumFromZoneCsvs(null, oneZone, null, 1.0, "nm", null));
        assertThrows(IllegalArgumentException.class, () ->
                libsDataService.synthesizeSpectrumFromZoneCsvs(Collections.emptyList(), oneZone, null, 1.0, "nm", null));
        assertThrows(IllegalArgumentException.class, () ->
                libsDataService.synthesizeSpectrumFromZoneCsvs(List.of(csv), Collections.emptyList(), null, 1.0, "nm", null));

        double[] nullProfileResult = libsDataService.synthesizeSpectrumFromZoneCsvs(
                List.of(csv), oneZone, null, 2.0, "nm", null);
        assertEquals(srcWl.length, nullProfileResult.length);
        assertTrue(nullProfileResult[1] > 0.0);
    }
}
