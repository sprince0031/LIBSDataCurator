package com.medals.libsdatagenerator.model.matweb;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.JsonModel;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Data class for full set of materials including overview datasheet for a series
 * @author Siddharth Prince | 05:37
 */
public class SeriesInput implements JsonModel {
    private String seriesKey;
    private List<String> individualMaterialGuids;
    private String overviewGuid;
    private Element coatingElement;

    public SeriesInput() {}; // Default constructor to load from Json file

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

    @Override
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        json.put("seriesKey", seriesKey);
        JSONArray materialGuids = new JSONArray();
        for (String guid: individualMaterialGuids) {
            materialGuids.put(guid);
        }
        json.put("individualMaterialGuids", materialGuids);
        json.put("overviewGuid", overviewGuid);
        if (coatingElement != null) {
            json.put("coatingElement", coatingElement.toJson());
        }
        return json;
    }

    @Override
    public void fromJson(JSONObject json) {
        this.seriesKey = json.getString("seriesKey");
        JSONArray materialGuids = json.getJSONArray("individualMaterialGuids");
        this.individualMaterialGuids = new ArrayList<>();
        for (int i = 0; i < materialGuids.length(); i++) {
            this.individualMaterialGuids.add(materialGuids.getString(i));
        }
        this.overviewGuid = json.getString("overviewGuid");
        if (json.has("coatingElement")) {
            this.coatingElement = new Element();
            this.coatingElement.fromJson(
                    json.getJSONObject("coatingElement")
            );
        }
    }

}
