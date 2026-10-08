package com.medals.libsdatagenerator.sampler;

import com.medals.libsdatagenerator.model.PlasmaZone;

import java.util.List;

public interface PlasmaSampler {

    List<List<PlasmaZone>> sample(List<PlasmaZone> baseZones, int numSamples, double relativeStdDeviation, Long seed);

}
