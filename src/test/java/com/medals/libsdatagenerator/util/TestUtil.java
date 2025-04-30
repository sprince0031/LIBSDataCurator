package com.medals.libsdatagenerator.util;

import java.io.File;
import java.lang.reflect.Field;

/**
 * Helper class for test configurations
 */
public class TestUtil {

    /**
     * Sets up the test environment by modifying paths to use test resources
     */
    public static void setupTestEnvironment() {
        try {
            // Get the test resources directory path
            String testResourcesPath = "src" + File.separator + "test" + File.separator + "resources";

            // Create a File object for verification
            File testResources = new File(testResourcesPath);
            if (!testResources.exists()) {
                testResources.mkdirs();
            }

            // Use reflection to change the static final fields in CommonUtils
            Field confPathField = CommonUtils.class.getDeclaredField("CONF_PATH");
            confPathField.setAccessible(true);

            // Remove the final modifier
            java.lang.reflect.Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(confPathField, confPathField.getModifiers() & ~java.lang.reflect.Modifier.FINAL);

            // Change the value to point to test resources
            confPathField.set(null, testResourcesPath);

        } catch (Exception e) {
            System.err.println("Failed to setup test environment: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
