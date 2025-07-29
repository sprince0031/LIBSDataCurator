package com.medals.libsdatagenerator.model;

import java.util.List;

public class SeriesInput {
    private String seriesKey;
    private List<String> individualMaterialGuids;
    private String overviewGuid;

    public SeriesInput(String seriesKey, List<String> individualMaterialGuids, String overviewGuid) {
        this.seriesKey = seriesKey;
        this.individualMaterialGuids = individualMaterialGuids;
        this.overviewGuid = overviewGuid;
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

}
