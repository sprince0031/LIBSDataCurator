package com.medals.libsdatagenerator.sampler;

import com.medals.libsdatagenerator.model.Element;

import java.util.List;
import java.util.Map;

public interface Sampler {

    void sample(List<Element> baseComp, int numSamples, List<List<Element>> variations,
                Map<String, Object> metadata);

}
