package com.medals.libsdatagenerator.model.matweb;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.JsonModel;
import com.medals.libsdatagenerator.model.SeriesStatistics;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Class to track input material and associated Matweb metadata
 * @author Siddharth Prince | 28/07/2025 05:37
 */
public class MaterialGrade implements JsonModel {

    private static final Logger logger = Logger.getLogger(MaterialGrade.class.getName());

    private List<Element> composition;
    private Integer remainderElementIdx;
    private String matGUID;
    private String materialName;
    private String[] materialAttributes;
    private SeriesInput parentSeries;
    private SeriesStatistics overviewStatistics;

    public MaterialGrade() {}; // For loading from Json file

    public MaterialGrade(List<Element> composition, String matGUID, SeriesInput parentSeries) {
        this.composition = composition;
        this.matGUID = matGUID;
        this.parentSeries = parentSeries;
        this.materialAttributes = null;
        this.materialName = null;
        this.remainderElementIdx = null;
        this.overviewStatistics = null;
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
        return parentSeries;
    }

    public void setParentSeries(SeriesInput parentSeries) {
        this.parentSeries = parentSeries;
    }

    public Integer getRemainderElementIdx() {
        return remainderElementIdx;
    }

    public void setRemainderElementIdx(Integer remainingElementIdx) {
        this.remainderElementIdx = remainingElementIdx;
    }

    public SeriesStatistics getOverviewStatistics() {
        return overviewStatistics;
    }

    public void setOverviewStatistics(SeriesStatistics overviewStatistics) {
        this.overviewStatistics = overviewStatistics;
    }

    @Override
    public JSONObject toJson() {
        JSONObject json = new JSONObject();
        JSONArray comp = new JSONArray();
        for (Element element: composition) {
            comp.put(element.toJson());
        }
        json.put("composition", comp);
        json.put("ParentSeries", parentSeries.toJson());
        if (matGUID != null) {
            json.put("matGUID", matGUID);
        }
        if (remainderElementIdx != null) {
            json.put("remainderElementIdx", remainderElementIdx);
        }
        if (materialName != null) {
            json.put("materialName", materialName);
        }
        if (materialAttributes != null) {
            json.put("materialAttributes", new JSONArray(materialAttributes));
        }
        if (overviewStatistics != null) {
            json.put("overviewStatistics", overviewStatistics.toJson());
        }
        return json;
    }

    @Override
    public void fromJson(JSONObject json) {
        JSONArray comp = json.getJSONArray("composition");
        this.composition = new ArrayList<>();
        for (Object elementJson: comp) {
            Element element = new Element();
            element.fromJson((JSONObject) elementJson);
            this.composition.add(element);
        }
        SeriesInput parentSeries = new SeriesInput();
        parentSeries.fromJson(json.getJSONObject("ParentSeries"));
        this.parentSeries = parentSeries;
        if (json.has("remainderElementIdx")) {
            this.remainderElementIdx = json.getInt("remainderElementIdx");
        }
        if (json.has("matGUID")) {
            this.matGUID = json.getString("matGUID");
        }
        if (json.has("materialName")) {
            this.materialName = json.getString("materialName");
        }
        if (json.has("materialAttributes")) {
            JSONArray attrs = json.getJSONArray("materialAttributes");
            this.materialAttributes = new String[attrs.length()];
            for (int i = 0; i < attrs.length(); i++) {
                this.materialAttributes[i] = attrs.getString(i);
            }
        }
        if (json.has("overviewStatistics")) {
            SeriesStatistics overviewStats = new SeriesStatistics();
            overviewStats.fromJson(json.getJSONObject("overviewStatistics"));
            this.overviewStatistics = overviewStats;
        }
    }

    @Override
    public String toString() {
        return materialName != null && !materialName.isEmpty() ? materialName : CommonUtils.getInstance().buildCompositionString(composition);
    }

}
