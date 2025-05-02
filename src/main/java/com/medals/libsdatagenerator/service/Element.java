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
    private Double averageComposition; // Average value from series overview (if available)
    public int numberDecimalPlaces = 3;

    /**
     * Constructor for Element.
     *
     * @param name                 Full name of the element (e.g., "Carbon").
     * @param symbol               Chemical symbol (e.g., "C").
     * @param percentageComposition The specific percentage for this material, or an initial estimate. Can be null initially.
     * @param min                  Minimum percentage allowed (can be from specific or overview). Can be null.
     * @param max                  Maximum percentage allowed (can be from specific or overview). Can be null.
     * @param average              Average percentage from an overview sheet. Can be null.
     */
    public Element(String name, String symbol, Double percentageComposition, Double min, Double max, Double average) {
        this.name = Objects.requireNonNull(name, "Element name cannot be null");
        this.symbol = Objects.requireNonNull(symbol, "Element symbol cannot be null");

        // Round values if they are not null
        this.percentageComposition = roundIfNotNull(percentageComposition);
        this.percentageCompositionMin = roundIfNotNull(min);
        this.percentageCompositionMax = roundIfNotNull(max);
        this.averageComposition = roundIfNotNull(average);

        // If percentageComposition is still null, maybe default based on min/max or average?
        if (this.percentageComposition == null) {
            if (this.percentageCompositionMin != null && this.percentageCompositionMax != null) {
                // Default to midpoint of specific range if available
                this.percentageComposition = roundIfNotNull((this.percentageCompositionMin + this.percentageCompositionMax) / 2.0);
            } else if (this.averageComposition != null) {
                // Default to series average if specific range/value is missing
                this.percentageComposition = this.averageComposition;
            } else {
                // Last resort default
                this.percentageComposition = 0.0;
                logger.warning("Element " + symbol + " initialized with 0.0% composition due to missing data.");
            }
        }
    }

    private Double roundIfNotNull(Double value) {
        return (value != null) ? CommonUtils.roundToNDecimals(value, this.numberDecimalPlaces) : null;
    }

    @Override
    public String toString() {
        return this.symbol + ":" + this.percentageComposition;
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

    public Double getAverageComposition() {
        return this.averageComposition;
    }

    public void setAverageComposition(Double averageComposition) {
        this.averageComposition = averageComposition;
    }

    public int getNumberDecimalPlaces() {
        return numberDecimalPlaces;
    }

    public void setNumberDecimalPlaces(int numberDecimalPlaces) {
        // Ensure rounding happens if decimal places change
        int oldDecimalPlaces = this.numberDecimalPlaces;
        this.numberDecimalPlaces = numberDecimalPlaces;
        if (oldDecimalPlaces != numberDecimalPlaces) {
            this.percentageComposition = roundIfNotNull(this.percentageComposition);
            this.percentageCompositionMin = roundIfNotNull(this.percentageCompositionMin);
            this.percentageCompositionMax = roundIfNotNull(this.percentageCompositionMax);
            this.averageComposition = roundIfNotNull(this.averageComposition);
        }
    }
}
