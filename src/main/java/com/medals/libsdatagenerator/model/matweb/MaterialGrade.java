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
    private String matGUID;
    private String overviewGUID;
    private String materialName;
    private String seriesKey;

    public MaterialGrade(List<Element> composition, String matGUID, String overviewGUID) {
        this.composition = composition;
        this.matGUID = matGUID;
        this.overviewGUID = overviewGUID;
        this.seriesKey = null; // Will be set separately if available
    }

    public MaterialGrade(List<Element> composition, String matGUID, String overviewGUID, String seriesKey) {
        this.composition = composition;
        this.matGUID = matGUID;
        this.overviewGUID = overviewGUID;
        this.seriesKey = seriesKey;
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

    public String getOverviewGUID() {
        return overviewGUID;
    }

    public void setOverviewGUID(String overviewGUID) {
        this.overviewGUID = overviewGUID;
    }

    public String getMaterialName() {
        return materialName;
    }

    public void setMaterialName(String materialName) {
        this.materialName = materialName;
    }

    public String getSeriesKey() {
        return seriesKey;
    }

    public void setSeriesKey(String seriesKey) {
        this.seriesKey = seriesKey;
    }

    @Override
    public String toString() {
        return materialName != null && !materialName.isEmpty() ? materialName : CommonUtils.getInstance().buildCompositionString(composition);
    }

}
