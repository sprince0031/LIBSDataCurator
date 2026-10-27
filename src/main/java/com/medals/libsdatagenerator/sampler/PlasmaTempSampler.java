package com.medals.libsdatagenerator.sampler;

import com.medals.libsdatagenerator.model.PlasmaZone;
import org.apache.commons.rng.UniformRandomProvider;
import org.apache.commons.rng.sampling.distribution.ContinuousSampler;
import org.apache.commons.rng.sampling.distribution.ZigguratSampler;
import org.apache.commons.rng.simple.RandomSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public class PlasmaTempSampler implements PlasmaSampler {

    private static final Logger logger = Logger.getLogger(PlasmaTempSampler.class.getName());
    private static final double K_TO_EV_CONVERSION_FACTOR = 11604.525;

    private static PlasmaTempSampler instance = null;

    public static PlasmaTempSampler getInstance() {
        if (instance == null) {
            instance = new PlasmaTempSampler();
        }
        return instance;
    }

    /**
     * Generates perturbed 2-zone configurations per synthetic shot.
     *
     * @param baseZones    Base zones from instrument profile (e.g., core and periphery)
     * @param numSamples   Number of synthetic spectra to generate
     * @param relativeStdDev Relative standard deviation (e.g., 0.05 for 5% pulse-to-pulse fluctuation)
     * @param seed         PRNG seed for reproducibility
     * @return List of shot configurations, each containing perturbed PlasmaZones
     */
    @Override
    public List<List<PlasmaZone>> sample(List<PlasmaZone> baseZones, int numSamples, double relativeStdDev, Long seed) {

        Set<String> plasmaKeySet = new HashSet<>();
        List<List<PlasmaZone>> shotZoneProfiles = new ArrayList<>(numSamples);
        UniformRandomProvider rng = seed != null ?
                RandomSource.XO_RO_SHI_RO_128_PP.create(seed) :
                RandomSource.XO_RO_SHI_RO_128_PP.create();

        ContinuousSampler gaussianSampler = ZigguratSampler.NormalizedGaussian.of(rng);
        logger.info("Starting Gaussian sampling of Te with " + (seed != null ? "seed: " + seed : "random seed"));

        int successfulSamples = 0;
        int currentSample = 0;
        int maxAttempts = numSamples * 10;
        while (successfulSamples < numSamples-1 && currentSample < maxAttempts) {
            currentSample++;
            // Sample a common laser energy / ablation fluctuation factor per shot
            double shotDeltaFactor = gaussianSampler.sample() * relativeStdDev;
            // Clamp shot fluctuation to the physical pulse-to-pulse bound (+/- 10%)
//            shotDeltaFactor = Math.clamp(shotDeltaFactor, -0.20, 0.20);

            String plasmaKey = "";
            List<PlasmaZone> sampledZones = new ArrayList<>();
            for (PlasmaZone base : baseZones) {
                PlasmaZone perturbed = createPerturbed(base, shotDeltaFactor);
                plasmaKey += String.format("_%.3f_%.3e", perturbed.getTe(), perturbed.getNe());
                sampledZones.add(perturbed);
            }
            if (!plasmaKeySet.contains(plasmaKey)) {
                shotZoneProfiles.add(sampledZones);
                plasmaKeySet.add(plasmaKey);
                successfulSamples++;
            }
        }
        return shotZoneProfiles;
    }

    private PlasmaZone createPerturbed(PlasmaZone base, double shotDeltaFactor) {
        PlasmaZone perturbed = new PlasmaZone(base.getTe(), base.getNe(), base.getVFraction(), base.getKAbsorption());
        double baseTempK = perturbed.getTe() * K_TO_EV_CONVERSION_FACTOR;

        // Correlated perturbation maintains T_core > T_periphery
        double newTempK = baseTempK * (1.0 + shotDeltaFactor);

        // Clamp within analytical window bounds [7,500 K, 12,000 K]
        newTempK = Math.clamp(newTempK, 7500.0, 12000.0);
        double tempElectronVolts = Math.round((newTempK/K_TO_EV_CONVERSION_FACTOR) * 1000.0) / 1000.0;

        perturbed.setTe(tempElectronVolts);
        return perturbed;
    }

}
