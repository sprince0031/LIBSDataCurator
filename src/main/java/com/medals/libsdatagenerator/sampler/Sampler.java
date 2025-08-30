package com.medals.libsdatagenerator.sampler;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.matweb.MaterialGrade;

import java.util.List;
import java.util.Map;

public interface Sampler {

    void sample(MaterialGrade material, int numSamples, List<List<Element>> variations);

}
