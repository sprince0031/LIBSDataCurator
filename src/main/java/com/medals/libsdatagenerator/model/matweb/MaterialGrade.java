package com.medals.libsdatagenerator.model.matweb;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.util.CommonUtils;

import java.util.List;
import java.util.logging.Logger;

/**
 * Class to track input material and associated Matweb metadata
 * @author Siddharth Prince | 28/07/2025 05:37
 */
public class MaterialGrade {

    private static final Logger logger = Logger.getLogger(MaterialGrade.class.getName());

    private List<Element> composition;
    private String remainingElement;
    private String matGUID;
    private String materialName;
    private String[] materialAttributes;
    private SeriesInput ParentSeries;

    public MaterialGrade(List<Element> composition, String matGUID, SeriesInput ParentSeries) {
        this.composition = composition;
        this.matGUID = matGUID;
        this.ParentSeries = ParentSeries;
        this.materialAttributes = null;
        this.materialName = null;
        this.remainingElement = null;
    }

    public List<Element> getComposition() {
        return composition;
    }

    public void setComposition(List<Element> composition) {
        this.composition = composition;
    }

    public String getMatGUID() {
        return matGUID;
    }

    public void setMatGUID(String matGUID) {
        this.matGUID = matGUID;
    }

    public String getMaterialName() {
        return materialName;
    }

    public void setMaterialName(String materialName) {
        this.materialName = materialName;
    }

    public String[] getMaterialAttributes() {
        return materialAttributes == null ? null : java.util.Arrays.copyOf(materialAttributes, materialAttributes.length);
    }

    public void setMaterialAttributes(String[] materialAttributes) {
        this.materialAttributes = materialAttributes == null ? null : java.util.Arrays.copyOf(materialAttributes, materialAttributes.length);
    }

    public SeriesInput getParentSeries() {
        return ParentSeries;
    }

    public void setParentSeries(SeriesInput parentSeries) {
        this.ParentSeries = parentSeries;
    }

    public String getRemainderElement() {
        return remainingElement;
    }

    public void setRemainderElement(String remainingElement) {
        this.remainingElement = remainingElement;
    }

    @Override
    public String toString() {
        return materialName != null && !materialName.isEmpty() ? materialName : CommonUtils.getInstance().buildCompositionString(composition);
    }

}
