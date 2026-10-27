package com.medals.libsdatagenerator.sampler;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.util.CommonUtils;
import org.apache.commons.rng.UniformRandomProvider;
import org.apache.commons.rng.simple.RandomSource;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Injects tramp / residual elements into steel compositions via independent Bernoulli trials.
 * Typical scrap residual elements include Cu, Sn, Pb, As, Sb, Cr, Ni, Zn.
 * When an element is injected, the matrix element (Fe) is rebalanced to preserve 100% total mass.
 */
public class ResidualsInjector {

    private static final Logger logger = Logger.getLogger(ResidualsInjector.class.getName());
    private static ResidualsInjector instance = null;

    public static class TrampElementDef {
        private final String symbol;
        private final String name;
        private final double minConcentration;
        private final double maxConcentration;

        public TrampElementDef(String symbol, String name, double minConcentration, double maxConcentration) {
            this.symbol = symbol;
            this.name = name;
            this.minConcentration = minConcentration;
            this.maxConcentration = maxConcentration;
        }

        public String getSymbol() { return symbol; }
        public String getName() { return name; }
        public double getMinConcentration() { return minConcentration; }
        public double getMaxConcentration() { return maxConcentration; }
    }

    public static final List<TrampElementDef> DEFAULT_TRAMP_ELEMENTS = List.of(
            new TrampElementDef("Cu", "Copper", 0.02, 0.40),
            new TrampElementDef("Sn", "Tin", 0.005, 0.05),
            new TrampElementDef("Pb", "Lead", 0.002, 0.03),
            new TrampElementDef("As", "Arsenic", 0.005, 0.03),
            new TrampElementDef("Sb", "Antimony", 0.002, 0.02),
            new TrampElementDef("Cr", "Chromium", 0.02, 0.30),
            new TrampElementDef("Ni", "Nickel", 0.02, 0.30),
            new TrampElementDef("Zn", "Zinc", 0.005, 0.05)
    );

    private ResidualsInjector() {}

    public static synchronized ResidualsInjector getInstance() {
        if (instance == null) {
            instance = new ResidualsInjector();
        }
        return instance;
    }

    /**
     * Injects residuals across a batch of compositions.
     */
    public List<List<Element>> injectResidualsBatch(List<List<Element>> compositions, double probability, Long seed) {
        if (compositions == null || compositions.isEmpty()) {
            return compositions;
        }
        UniformRandomProvider rng = (seed != null)
                ? RandomSource.XO_RO_SHI_RO_128_PP.create(seed)
                : RandomSource.XO_RO_SHI_RO_128_PP.create();

        List<List<Element>> result = new ArrayList<>(compositions.size());
        for (List<Element> comp : compositions) {
            result.add(injectResiduals(comp, probability, rng));
        }
        return result;
    }

    /**
     * Injects residuals for a single composition using a provided seed.
     */
    public List<Element> injectResiduals(List<Element> composition, double probability, Long seed) {
        UniformRandomProvider rng = (seed != null)
                ? RandomSource.XO_RO_SHI_RO_128_PP.create(seed)
                : RandomSource.XO_RO_SHI_RO_128_PP.create();
        return injectResiduals(composition, probability, rng);
    }

    /**
     * Injects residuals for a single composition using the specified random provider.
     */
    public List<Element> injectResiduals(List<Element> composition, double probability, UniformRandomProvider rng) {
        if (composition == null || composition.isEmpty() || probability <= 0.0) {
            return copyComposition(composition);
        }

        List<Element> modifiedComp = copyComposition(composition);

        // Find matrix element (Fe, or highest concentration)
        int matrixIdx = findMatrixElementIndex(modifiedComp);
        if (matrixIdx < 0) {
            logger.warning("No suitable matrix element found for rebalancing. Skipping residuals injection.");
            return modifiedComp;
        }

        double totalInjected = 0.0;

        for (TrampElementDef tramp : DEFAULT_TRAMP_ELEMENTS) {
            double u = rng.nextDouble();
            if (u < probability) {
                // Find if element already exists in composition
                Element existing = null;
                for (Element el : modifiedComp) {
                    if (tramp.getSymbol().equalsIgnoreCase(el.getSymbol())) {
                        existing = el;
                        break;
                    }
                }

                if (existing != null) {
                    // If existing concentration > max scrap range, it's a primary alloying element (e.g. Cr 18%, Ni 10%)
                    if (existing.getPercentageComposition() != null &&
                            existing.getPercentageComposition() > tramp.getMaxConcentration()) {
                        continue;
                    }

                    // Otherwise, sample in tramp range
                    double sampledConc = tramp.getMinConcentration() +
                            rng.nextDouble() * (tramp.getMaxConcentration() - tramp.getMinConcentration());
                    double currentConc = existing.getPercentageComposition() != null ? existing.getPercentageComposition() : 0.0;
                    if (sampledConc > currentConc) {
                        double delta = sampledConc - currentConc;
                        existing.setPercentageComposition(sampledConc);
                        totalInjected += delta;
                    }
                } else {
                    // Add new tramp element
                    double sampledConc = tramp.getMinConcentration() +
                            rng.nextDouble() * (tramp.getMaxConcentration() - tramp.getMinConcentration());
                    Element newEl = new Element(
                            tramp.getName(),
                            tramp.getSymbol(),
                            sampledConc,
                            tramp.getMinConcentration(),
                            tramp.getMaxConcentration(),
                            sampledConc
                    );
                    modifiedComp.add(newEl);
                    totalInjected += sampledConc;
                }
            }
        }

        if (totalInjected > 0.0) {
            Element matrixEl = modifiedComp.get(matrixIdx);
            double newMatrixPct = Math.max(0.0, matrixEl.getPercentageComposition() - totalInjected);
            matrixEl.setPercentageComposition(newMatrixPct);

            // Rebalance exact sum to 100.0%
            double currentSum = 0.0;
            for (Element el : modifiedComp) {
                currentSum += (el.getPercentageComposition() != null) ? el.getPercentageComposition() : 0.0;
            }
            double residual = 100.0 - currentSum;
            matrixEl.setPercentageComposition(matrixEl.getPercentageComposition() + residual);
        }

        return modifiedComp;
    }

    private int findMatrixElementIndex(List<Element> composition) {
        for (int i = 0; i < composition.size(); i++) {
            if ("Fe".equalsIgnoreCase(composition.get(i).getSymbol())) {
                return i;
            }
        }
        int maxIdx = -1;
        double maxPct = -1.0;
        for (int i = 0; i < composition.size(); i++) {
            Double pct = composition.get(i).getPercentageComposition();
            if (pct != null && pct > maxPct) {
                maxPct = pct;
                maxIdx = i;
            }
        }
        return maxIdx;
    }

    private List<Element> copyComposition(List<Element> composition) {
        if (composition == null) return null;
        List<Element> copy = new ArrayList<>(composition.size());
        for (Element el : composition) {
            copy.add(new Element(
                    el.getName(),
                    el.getSymbol(),
                    el.getPercentageComposition(),
                    el.getMin(),
                    el.getMax(),
                    el.getAverageComposition()
            ));
        }
        return copy;
    }
}
