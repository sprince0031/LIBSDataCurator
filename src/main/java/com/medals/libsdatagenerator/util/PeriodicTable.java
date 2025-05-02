package com.medals.libsdatagenerator.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Utility class containing element symbols and names in a map.
 */
public class PeriodicTable {

    public static final Map<String, String> ELEMENTS;
    
    static {
        Map<String, String> elements = new HashMap<>();
        elements.put("H", "Hydrogen");
        elements.put("He", "Helium");
        elements.put("Li", "Lithium");
        elements.put("Be", "Beryllium");
        elements.put("B", "Boron");
        elements.put("C", "Carbon");
        elements.put("N", "Nitrogen");
        elements.put("O", "Oxygen");
        elements.put("F", "Fluorine");
        elements.put("Ne", "Neon");
        elements.put("Na", "Sodium");
        elements.put("Mg", "Magnesium");
        elements.put("Al", "Aluminum");
        elements.put("Si", "Silicon");
        elements.put("P", "Phosphorus");
        elements.put("S", "Sulfur");
        elements.put("Cl", "Chlorine");
        elements.put("Ar", "Argon");
        elements.put("K", "Potassium");
        elements.put("Ca", "Calcium");
        elements.put("Sc", "Scandium");
        elements.put("Ti", "Titanium");
        elements.put("V", "Vanadium");
        elements.put("Cr", "Chromium");
        elements.put("Mn", "Manganese");
        elements.put("Fe", "Iron");
        elements.put("Co", "Cobalt");
        elements.put("Ni", "Nickel");
        elements.put("Cu", "Copper");
        elements.put("Zn", "Zinc");
        elements.put("Ga", "Gallium");
        elements.put("Ge", "Germanium");
        elements.put("As", "Arsenic");
        elements.put("Se", "Selenium");
        elements.put("Br", "Bromine");
        elements.put("Kr", "Krypton");
        elements.put("Rb", "Rubidium");
        elements.put("Sr", "Strontium");
        elements.put("Y", "Yttrium");
        elements.put("Zr", "Zirconium");
        elements.put("Nb", "Niobium");
        elements.put("Mo", "Molybdenum");
        elements.put("Tc", "Technetium");
        elements.put("Ru", "Ruthenium");
        elements.put("Rh", "Rhodium");
        elements.put("Pd", "Palladium");
        elements.put("Ag", "Silver");
        elements.put("Cd", "Cadmium");
        elements.put("In", "Indium");
        elements.put("Sn", "Tin");
        elements.put("Sb", "Antimony");
        elements.put("Te", "Tellurium");
        elements.put("I", "Iodine");
        elements.put("Xe", "Xenon");
        elements.put("Cs", "Cesium");
        elements.put("Ba", "Barium");
        elements.put("La", "Lanthanum");
        elements.put("Ce", "Cerium");
        elements.put("Pr", "Praseodymium");
        elements.put("Nd", "Neodymium");
        elements.put("Pm", "Promethium");
        elements.put("Sm", "Samarium");
        elements.put("Eu", "Europium");
        elements.put("Gd", "Gadolinium");
        elements.put("Tb", "Terbium");
        elements.put("Dy", "Dysprosium");
        elements.put("Ho", "Holmium");
        elements.put("Er", "Erbium");
        elements.put("Tm", "Thulium");
        elements.put("Yb", "Ytterbium");
        elements.put("Lu", "Lutetium");
        elements.put("Hf", "Hafnium");
        elements.put("Ta", "Tantalum");
        elements.put("W", "Tungsten");
        elements.put("Re", "Rhenium");
        elements.put("Os", "Osmium");
        elements.put("Ir", "Iridium");
        elements.put("Pt", "Platinum");
        elements.put("Au", "Gold");
        elements.put("Hg", "Mercury");
        elements.put("Tl", "Thallium");
        elements.put("Pb", "Lead");
        elements.put("Bi", "Bismuth");
        elements.put("Po", "Polonium");
        elements.put("At", "Astatine");
        elements.put("Rn", "Radon");
        elements.put("Fr", "Francium");
        elements.put("Ra", "Radium");
        elements.put("Ac", "Actinium");
        elements.put("Th", "Thorium");
        elements.put("Pa", "Protactinium");
        elements.put("U", "Uranium");
        elements.put("Np", "Neptunium");
        elements.put("Pu", "Plutonium");
        elements.put("Am", "Americium");
        elements.put("Cm", "Curium");
        elements.put("Bk", "Berkelium");
        elements.put("Cf", "Californium");
        elements.put("Es", "Einsteinium");
        elements.put("Fm", "Fermium");
        elements.put("Md", "Mendelevium");
        elements.put("No", "Nobelium");
        elements.put("Lr", "Lawrencium");
        elements.put("Rf", "Rutherfordium");
        elements.put("Db", "Dubnium");
        elements.put("Sg", "Seaborgium");
        elements.put("Bh", "Bohrium");
        elements.put("Hs", "Hassium");
        elements.put("Mt", "Meitnerium");
        elements.put("Ds", "Darmstadtium");
        elements.put("Rg", "Roentgenium");
        elements.put("Cn", "Copernicium");
        elements.put("Nh", "Nihonium");
        elements.put("Fl", "Flerovium");
        elements.put("Mc", "Moscovium");
        elements.put("Lv", "Livermorium");
        elements.put("Ts", "Tennessine");
        elements.put("Og", "Oganesson");
        
        ELEMENTS = Collections.unmodifiableMap(elements);
    }
    
    /**
     * Checks if the provided element symbol exists in the periodic table.
     * 
     * @param symbol The chemical symbol to check
     * @return true if the element exists, false otherwise
     */
    public static boolean isValidElement(String symbol) {
        return ELEMENTS.containsKey(symbol);
    }
    
    /**
     * Gets the name of an element from its symbol.
     * 
     * @param symbol The chemical symbol
     * @return The element name or null if the element doesn't exist
     */
    public static String getElementName(String symbol) {
        return ELEMENTS.get(symbol);
    }
}
