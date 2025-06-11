package com.medals.libsdatagenerator.model;

/**
 * Represents statistical information for a single element from series overview data
 * @author Siddharth Prince | 02/06/25 15:00
 */
public class ElementStatistics {
    private final String elementSymbol;
    private final double averagePercentage;
    private final int gradeCount;
    private final double minPercentage;
    private final double maxPercentage;

    public ElementStatistics(String elementSymbol, double averagePercentage, int gradeCount) {
        this.elementSymbol = elementSymbol;
        this.averagePercentage = averagePercentage;
        this.gradeCount = gradeCount;
        this.minPercentage = 0.0; // Will be set from range data if available
        this.maxPercentage = 100.0; // Will be set from range data if available
    }

    public ElementStatistics(String elementSymbol, double averagePercentage, int gradeCount,
                             double minPercentage, double maxPercentage) {
        this.elementSymbol = elementSymbol;
        this.averagePercentage = averagePercentage;
        this.gradeCount = gradeCount;
        this.minPercentage = minPercentage;
        this.maxPercentage = maxPercentage;
    }

    public String getElementSymbol() {
        return elementSymbol;
    }

    public double getAveragePercentage() {
        return averagePercentage;
    }

    public int getGradeCount() {
        return gradeCount;
    }

    public double getMinPercentage() {
        return minPercentage;
    }

    public double getMaxPercentage() {
        return maxPercentage;
    }

    /**
     * Estimates variance based on grade count using empirical relationship
     * Higher grade counts typically indicate lower variance
     */
    public double getEstimatedVariance() {
        double proportion = averagePercentage / 100.0;
        // Use empirical relationship: variance decreases with sample size
        // Base variance for proportion data: p(1-p), adjusted by sample count
        double baseVariance = proportion * (1 - proportion);
        return baseVariance / (gradeCount + 1);
    }

    @Override
    public String toString() {
        return String.format("ElementStatistics{element='%s', avg=%.3f%%, count=%d, range=[%.3f-%.3f]%%}",
                elementSymbol, averagePercentage, gradeCount, minPercentage, maxPercentage);
    }
}

