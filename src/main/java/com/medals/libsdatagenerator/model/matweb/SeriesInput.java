package com.medals.libsdatagenerator.model.matweb;

import com.medals.libsdatagenerator.model.Element;

import java.util.List;

/**
 * Data class for full set of materials including overview datasheet for a series
 * @author Siddharth Prince | 05:37
 */
public class SeriesInput {
    private String seriesKey;
    private List<String> individualMaterialGuids;
    private String overviewGuid;
    private Element coatingElement;

    public SeriesInput(String seriesKey, List<String> individualMaterialGuids, String overviewGuid) {
        this.seriesKey = seriesKey;
        this.individualMaterialGuids = individualMaterialGuids;
        this.overviewGuid = overviewGuid;
        this.coatingElement = null;
    }

    public SeriesInput(String seriesKey, List<String> individualMaterialGuids, String overviewGuid, Element coatingElement) {
        this.seriesKey = seriesKey;
        this.individualMaterialGuids = individualMaterialGuids;
        this.overviewGuid = overviewGuid;
        this.coatingElement = coatingElement;
    }

    public String getSeriesKey() {
        return seriesKey;
    }

    public void setSeriesKey(String seriesKey) {
        this.seriesKey = seriesKey;
    }

    public List<String> getIndividualMaterialGuids() {
        return individualMaterialGuids;
    }

    public void setIndividualMaterialGuids(List<String> individualMaterialGuids) {
        this.individualMaterialGuids = individualMaterialGuids;
    }

    public String getOverviewGuid() {
        return overviewGuid;
    }

    public void setOverviewGuid(String overviewGuid) {
        this.overviewGuid = overviewGuid;
    }

    public Element getCoatingElement() {
        return coatingElement;
    }

    public void setCoatingElement(Element coatingElement) {
        this.coatingElement = coatingElement;
    }

    public boolean isCoated() {
        return coatingElement != null;
    }

}
