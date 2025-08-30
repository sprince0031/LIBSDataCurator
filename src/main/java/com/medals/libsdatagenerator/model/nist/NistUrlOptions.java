package com.medals.libsdatagenerator.model.nist;

import java.util.stream.Stream;

/**
 * Helper class for mapping all user inputs with multi-choice values to corresponding url params for NIST LIBS DB
 * @author Siddharth Prince | 27/08/2025 20:01
 */
public class NistUrlOptions {

    public interface UserSelectable {
        int getUserOption();
    }

    private static <T extends Enum<T> & UserSelectable> T fromOption(T[] enumValues, int option, T defaultOption) {
        return Stream.of(enumValues)
                .filter(p -> p.getUserOption() == option)
                .findFirst()
                .orElse(defaultOption);
    }

    public enum WavelengthUnit implements UserSelectable {
        ANGSTROM(1, "0", "Å"),
        NANOMETER(2, "1", "Nm"), // Default
        MICROMETER(3, "2", "µm");

        private final int userOption;
        private final String urlParam;
        private final String unitString;

        WavelengthUnit(int userOption, String urlParam, String unitString) {
            this.userOption = userOption;
            this.urlParam = urlParam;
            this.unitString = unitString;
        }

        public String getUrlParam() { return urlParam; }

        @Override
        public int getUserOption() { return userOption; }

        public String getUnitString() { return unitString; }

        public static WavelengthUnit fromOption(int option) {
            return NistUrlOptions.fromOption(values(), option, NANOMETER);
        }
    }

    public enum WavelengthCondition implements UserSelectable {

        DEFAULT(1, "2"), // Vacuum (< 200 nm) Air (200 - 2000 nm) Vacuum (> 2000 nm)
        VACUUM(2, "3"); // All wavelengths measured in vacuum

        private final int userOption;
        private final String urlParam;

        WavelengthCondition(int userOption, String urlParam) {
            this.userOption = userOption;
            this.urlParam = urlParam;
        }

        public String getUrlParam() { return urlParam; }

        @Override
        public int getUserOption() { return userOption; }

        public static WavelengthCondition fromOption(int option) {
            return NistUrlOptions.fromOption(values(), option, DEFAULT);
        }

    }

    public enum MaxIonCharge implements UserSelectable {

        NO_LIMIT(1, "109"),
        TWO_PLUS(2, "2"), // Default
        THREE_PLUS(3, "3"),
        FOUR_PLUS(4, "4");

        private final int userOption;
        private final String urlParam;

        MaxIonCharge(int userOption, String urlParam) {
            this.userOption = userOption;
            this.urlParam = urlParam;
        }

        public String getUrlParam() { return urlParam; }

        @Override
        public int getUserOption() { return userOption; }

        public static MaxIonCharge fromOption(int option) {
            return NistUrlOptions.fromOption(values(), option, TWO_PLUS);
        }

    }

    public enum MinRelativeIntensity implements UserSelectable {

        ZERO(1, "0"),
        POINT_ONE(2, "0.1"),
        POINT_ZERO_ONE(3, "0.01"), // Default
        POINT_ZERO_ZERO_ONE(4, "0.001");

        private final int userOption;
        private final String urlParam;

        MinRelativeIntensity(int userOption, String urlParam) {
            this.userOption = userOption;
            this.urlParam = urlParam;
        }

        public String getUrlParam() { return urlParam; }

        @Override
        public int getUserOption() { return userOption; }

        public static MinRelativeIntensity fromOption(int option) {
            return NistUrlOptions.fromOption(values(), option, POINT_ZERO_ONE);
        }

    }

    public enum IntensityScale implements UserSelectable {

        ENERGY_FLUX(1), // Default
        PHOTON_FLUX(2);

        private final int userOption;

        IntensityScale(int userOption) {
            this.userOption = userOption;
        }

        public String getUrlParam() { return String.valueOf(userOption); }

        @Override
        public int getUserOption() { return userOption; }

        public static IntensityScale fromOption(int option) {
            return NistUrlOptions.fromOption(values(), option, ENERGY_FLUX);
        }

    }

    public enum VariationMode implements UserSelectable {

        DIRICHLET(1), // Default
        GAUSSIAN(2);

        private final int userOption;

        VariationMode(int userOption) {
            this.userOption = userOption;
        }

        @Override
        public int getUserOption() { return userOption; }

        public static VariationMode fromOption(int option) {
            return NistUrlOptions.fromOption(values(), option, DIRICHLET);
        }

    }
}
