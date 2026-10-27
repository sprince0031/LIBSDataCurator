package com.medals.libsdatagenerator.sampler;

import com.medals.libsdatagenerator.model.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ResidualsInjectorTest {

    private List<Element> createBaseSteelComposition() {
        List<Element> elements = new ArrayList<>();
        elements.add(new Element("Iron", "Fe", 98.0, 97.0, 99.0, 98.0));
        elements.add(new Element("Carbon", "C", 0.5, 0.4, 0.6, 0.5));
        elements.add(new Element("Manganese", "Mn", 1.5, 1.0, 2.0, 1.5));
        return elements;
    }

    private List<Element> createStainlessSteelComposition() {
        List<Element> elements = new ArrayList<>();
        elements.add(new Element("Iron", "Fe", 70.0, 68.0, 72.0, 70.0));
        elements.add(new Element("Chromium", "Cr", 18.0, 17.0, 19.0, 18.0));
        elements.add(new Element("Nickel", "Ni", 10.0, 9.0, 11.0, 10.0));
        elements.add(new Element("Carbon", "C", 0.08, 0.05, 0.1, 0.08));
        elements.add(new Element("Manganese", "Mn", 1.92, 1.5, 2.0, 1.92));
        return elements;
    }

    @Test
    @DisplayName("ResidualsInjector with probability 0.0 does not inject any tramp elements")
    void testNoInjectionWhenProbabilityZero() {
        ResidualsInjector injector = ResidualsInjector.getInstance();
        List<Element> base = createBaseSteelComposition();
        List<Element> result = injector.injectResiduals(base, 0.0, 42L);

        assertEquals(3, result.size());
        assertEquals(98.0, result.get(0).getPercentageComposition(), 1e-4);
        assertEquals(0.5, result.get(1).getPercentageComposition(), 1e-4);
        assertEquals(1.5, result.get(2).getPercentageComposition(), 1e-4);
    }

    @Test
    @DisplayName("ResidualsInjector with probability 1.0 injects tramp elements and rebalances Fe to 100%")
    void testAlwaysInjectWhenProbabilityOne() {
        ResidualsInjector injector = ResidualsInjector.getInstance();
        List<Element> base = createBaseSteelComposition();
        List<Element> result = injector.injectResiduals(base, 1.0, 42L);

        assertTrue(result.size() > 3, "Tramp elements should be added");

        // Verify total sum is exactly 100.0%
        double sum = result.stream().mapToDouble(Element::getPercentageComposition).sum();
        assertEquals(100.0, sum, 1e-3);

        // Verify Fe was reduced
        Element fe = result.stream().filter(e -> "Fe".equals(e.getSymbol())).findFirst().orElse(null);
        assertNotNull(fe);
        assertTrue(fe.getPercentageComposition() < 98.0);
        assertTrue(fe.getPercentageComposition() > 95.0);

        // Verify tramp elements are present with valid ranges
        Element cu = result.stream().filter(e -> "Cu".equals(e.getSymbol())).findFirst().orElse(null);
        assertNotNull(cu);
        assertTrue(cu.getPercentageComposition() >= 0.02 && cu.getPercentageComposition() <= 0.40);
    }

    @Test
    @DisplayName("ResidualsInjector preserves major alloying elements in stainless steel (e.g., Cr 18%, Ni 10%)")
    void testPreserveMajorAlloyElements() {
        ResidualsInjector injector = ResidualsInjector.getInstance();
        List<Element> base = createStainlessSteelComposition();
        List<Element> result = injector.injectResiduals(base, 1.0, 42L);

        Element cr = result.stream().filter(e -> "Cr".equals(e.getSymbol())).findFirst().orElse(null);
        assertNotNull(cr);
        assertEquals(18.0, cr.getPercentageComposition(), 1e-4, "Cr > 0.3% is major alloying and must not be reduced");

        Element ni = result.stream().filter(e -> "Ni".equals(e.getSymbol())).findFirst().orElse(null);
        assertNotNull(ni);
        assertEquals(10.0, ni.getPercentageComposition(), 1e-4, "Ni > 0.3% is major alloying and must not be reduced");

        double sum = result.stream().mapToDouble(Element::getPercentageComposition).sum();
        assertEquals(100.0, sum, 1e-3);
    }

    @Test
    @DisplayName("ResidualsInjector is deterministic with same seed")
    void testDeterministicWithSameSeed() {
        ResidualsInjector injector = ResidualsInjector.getInstance();
        List<Element> base = createBaseSteelComposition();

        List<Element> res1 = injector.injectResiduals(base, 0.5, 12345L);
        List<Element> res2 = injector.injectResiduals(base, 0.5, 12345L);

        assertEquals(res1.size(), res2.size());
        for (int i = 0; i < res1.size(); i++) {
            assertEquals(res1.get(i).getSymbol(), res2.get(i).getSymbol());
            assertEquals(res1.get(i).getPercentageComposition(), res2.get(i).getPercentageComposition(), 1e-5);
        }
    }

    @Test
    @DisplayName("ResidualsInjector batch injection handles List<List<Element>>")
    void testBatchInjection() {
        ResidualsInjector injector = ResidualsInjector.getInstance();
        List<List<Element>> batch = List.of(createBaseSteelComposition(), createBaseSteelComposition());

        List<List<Element>> result = injector.injectResidualsBatch(batch, 0.5, 999L);
        assertEquals(2, result.size());
        for (List<Element> comp : result) {
            double sum = comp.stream().mapToDouble(Element::getPercentageComposition).sum();
            assertEquals(100.0, sum, 1e-3);
        }
    }
}
