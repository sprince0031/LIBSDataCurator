package com.medals.libsdatagenerator.service;


import com.medals.libsdatagenerator.util.CommonUtils;

import java.util.Objects;
import java.util.logging.Logger;

/**
 * Represents an element in the periodic table
 * @author Siddharth Prince | 17/12/24 16:11
 */

public class Element {

    private static Logger logger = Logger.getLogger(Element.class.getName());

    private final String name;
    private final String symbol;
    private Double percentageComposition;
    private Double percentageCompositionMin;
    private Double percentageCompositionMax;
    public int numberDecimalPlaces = 3;

    public Element(String name, String symbol, Double percentageComposition, Double min, Double max) {
        Objects.requireNonNull(name);
        this.name = name;
        Objects.requireNonNull(symbol);
        this.symbol = symbol;
        if (percentageComposition != null) {
            percentageComposition = CommonUtils.roundToNDecimals(percentageComposition, numberDecimalPlaces);
        }
        if (min != null) {
            percentageCompositionMin = CommonUtils.roundToNDecimals(min, numberDecimalPlaces);
        }
        if (max != null) {
            percentageCompositionMax = CommonUtils.roundToNDecimals(max, numberDecimalPlaces);
        }
        this.percentageComposition = Objects.requireNonNullElse(percentageComposition, 0.0);
    }

    @Override
    public String toString() {
        return symbol + ":" + percentageComposition;
    }

    public String getName() {
        return this.name;
    }

    public String getSymbol() {
        return this.symbol;
    }

    public Double getPercentageComposition() {
        return this.percentageComposition;
    }

    public void setPercentageComposition(Double percentageComposition) {
        this.percentageComposition = CommonUtils.roundToNDecimals(percentageComposition, numberDecimalPlaces);
    }

    public String getQueryRepresentation() {
        return this.symbol + ":" + this.percentageComposition;
    }

    public Double getPercentageCompositionMin() {
        return this.percentageCompositionMin;
    }

    public void setPercentageCompositionMin(Double percentageCompositionMin) {
        this.percentageCompositionMin = percentageCompositionMin;
    }

    public Double getPercentageCompositionMax() {
        return this.percentageCompositionMax;
    }

    public void setPercentageCompositionMax(Double percentageCompositionMax) {
        this.percentageCompositionMax = percentageCompositionMax;
    }

    public int getNumberDecimalPlaces() {
        return numberDecimalPlaces;
    }

    public void setNumberDecimalPlaces(int numberDecimalPlaces) {
        this.numberDecimalPlaces = numberDecimalPlaces;
    }
}
