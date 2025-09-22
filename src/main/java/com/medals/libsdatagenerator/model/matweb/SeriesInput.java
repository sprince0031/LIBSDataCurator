package com.medals.libsdatagenerator.model.matweb;

import java.util.List;

/**
 * Data class for full set of materials including overview datasheet for a series
 * @author Siddharth Prince | 05:37
 */
public class SeriesInput {
    private String seriesKey;
    private List<String> individualMaterialGuids;
    private String overviewGuid;
    private String coatingElement;
    private Double coatingPercentage;

    public SeriesInput(String seriesKey, List<String> individualMaterialGuids, String overviewGuid) {
        this.seriesKey = seriesKey;
        this.individualMaterialGuids = individualMaterialGuids;
        this.overviewGuid = overviewGuid;
        this.coatingElement = null;
        this.coatingPercentage = null;
    }

    public SeriesInput(String seriesKey, List<String> individualMaterialGuids, String overviewGuid, String coatingElement, Double coatingPercentage) {
        this.seriesKey = seriesKey;
        this.individualMaterialGuids = individualMaterialGuids;
        this.overviewGuid = overviewGuid;
        this.coatingElement = coatingElement;
        this.coatingPercentage = coatingPercentage;
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

    public String getCoatingElement() {
        return coatingElement;
    }

    public void setCoatingElement(String coatingElement) {
        this.coatingElement = coatingElement;
    }

    public Double getCoatingPercentage() {
        return coatingPercentage;
    }

    public void setCoatingPercentage(Double coatingPercentage) {
        this.coatingPercentage = coatingPercentage;
    }

    public boolean isCoated() {
        return coatingElement != null && coatingPercentage != null;
    }

}
