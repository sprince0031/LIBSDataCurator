package com.medals.libsdatagenerator.sampler;

import com.medals.libsdatagenerator.model.PlasmaZone;
import org.apache.commons.rng.UniformRandomProvider;
import org.apache.commons.rng.sampling.distribution.ContinuousSampler;
import org.apache.commons.rng.sampling.distribution.ZigguratSampler;
import org.apache.commons.rng.simple.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Generates perturbed N-zone plasma configurations per synthetic shot using coupled (Te, Ne) Gaussian sampling.
 * A single standard normal draw per shot perturbs both Te and log10(Ne) across all plasma zones simultaneously,
 * preserving T_core > T_periphery physical hierarchy and Te-Ne covariance.
 */
public class PlasmaTempSampler implements PlasmaSampler {

    private static final Logger logger = Logger.getLogger(PlasmaTempSampler.class.getName());
    private static PlasmaTempSampler instance = null;

    public static synchronized PlasmaTempSampler getInstance() {
        if (instance == null) {
            instance = new PlasmaTempSampler();
        }
        return instance;
    }

    /**
     * Interface implementation delegating to coupled multi-zone sampling.
     */
    @Override
    public List<List<PlasmaZone>> sample(List<PlasmaZone> baseZones, int numSamples, double relativeStdDeviation, Long seed) {
        return sampleZones(baseZones, numSamples, relativeStdDeviation, seed);
    }

    /**
     * Samples coupled multi-zone plasma parameters across synthetic shots.
     *
     * @param baseZones Base plasma zones from calibrated profile
     * @param numSamples Number of shots to simulate
     * @param defaultRelativeStdDev Default relative standard deviation if profile lacks stddevs
     * @param seed Optional PRNG seed
     * @return List of perturbed plasma zone configurations per shot
     */
    public List<List<PlasmaZone>> sampleZones(
            List<PlasmaZone> baseZones,
            int numSamples,
            double defaultRelativeStdDev,
            Long seed) {

        List<List<PlasmaZone>> shotZoneProfiles = new ArrayList<>(numSamples);
        UniformRandomProvider rng = (seed != null)
                ? RandomSource.XO_RO_SHI_RO_128_PP.create(seed)
                : RandomSource.XO_RO_SHI_RO_128_PP.create();
        ContinuousSampler normalSampler = ZigguratSampler.NormalizedGaussian.of(rng);

        logger.info("Starting coupled Gaussian sampling of (Te, Ne) with " + (seed != null ? "seed: " + seed : "random seed"));

        for (int i = 0; i < numSamples; i++) {
            // Single standard normal variate per shot preserves T_core > T_periphery and Te-Ne covariance
            double zShot = Math.max(-2.0, Math.min(2.0, normalSampler.sample()));

            List<PlasmaZone> perturbedZones = new ArrayList<>(baseZones.size());
            for (PlasmaZone base : baseZones) {
                PlasmaZone perturbed = new PlasmaZone(base);

                double sigmaTeEv = (base.getTeStdDev() > 0.0)
                        ? base.getTeStdDev()
                        : base.getTe() * defaultRelativeStdDev;
                double newTeEv = Math.max(0.45, Math.min(2.20, base.getTe() + zShot * sigmaTeEv));
                perturbed.setTe(Math.round(newTeEv * 10000.0) / 10000.0);

                double sigmaLogNe = (base.getNeLogStdDev() > 0.0)
                        ? base.getNeLogStdDev()
                        : 0.4343 * defaultRelativeStdDev;
                double newLogNe = Math.max(15.0, Math.min(18.0, Math.log10(base.getNe()) + zShot * sigmaLogNe));
                perturbed.setNe(Math.pow(10.0, newLogNe));

                perturbedZones.add(perturbed);
            }
            shotZoneProfiles.add(perturbedZones);
        }
        return shotZoneProfiles;
    }
}
