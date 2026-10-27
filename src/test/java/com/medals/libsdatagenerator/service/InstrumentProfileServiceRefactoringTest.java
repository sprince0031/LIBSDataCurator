package com.medals.libsdatagenerator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.util.InputCompositionProcessor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class InstrumentProfileServiceRefactoringTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("InputCompositionProcessor.parseCompositionString should correctly parse compositions with ranges and remainder")
    void testParseCompositionString() throws IOException {
        String comp = "C-0.279,Si-0.421,Cr-11.93,Fe-#";
        List<Element> elements = InputCompositionProcessor.getInstance().parseCompositionString(comp);

        assertNotNull(elements);
        assertEquals(4, elements.size());
        Optional<Element> fe = elements.stream().filter(e -> "Fe".equals(e.getSymbol())).findFirst();
        assertTrue(fe.isPresent());
        assertTrue(fe.get().getPercentageComposition() > 80.0);
    }

    @Test
    @DisplayName("resolveInputCsvFiles should list and sort CSVs from directory and partition holdout")
    void testResolveAndPartitionCsvFiles(@TempDir Path tempDir) throws IOException {
        Path matDir = tempDir.resolve("469");
        Files.createDirectories(matDir);

        for (int i = 1; i <= 5; i++) {
            Files.writeString(matDir.resolve("point_" + i + ".csv"), "340.0,341.0\n100,200\n");
        }
        Files.writeString(matDir.resolve("readme.txt"), "not a csv");

        InstrumentProfileService service = InstrumentProfileService.getInstance();
        List<Path> csvs = service.resolveInputCsvFiles(matDir);

        assertEquals(5, csvs.size());
        assertEquals("point_1.csv", csvs.get(0).getFileName().toString());
        assertEquals("point_5.csv", csvs.get(4).getFileName().toString());

        int holdoutCount = 1;
        int splitIdx = csvs.size() - holdoutCount;
        List<Path> trainCsvs = csvs.subList(0, splitIdx);
        List<Path> holdoutCsvs = csvs.subList(splitIdx, csvs.size());

        assertEquals(4, trainCsvs.size());
        assertEquals(1, holdoutCsvs.size());
        assertEquals("point_5.csv", holdoutCsvs.get(0).getFileName().toString());
    }

    @Test
    @DisplayName("loadOrInitializeManifest should create valid manifest with 48-state anchor grid")
    void testLoadOrInitializeManifest(@TempDir Path tempDir) throws IOException {
        Path manifestPath = tempDir.resolve("temp/calibration_manifest.json");
        Path profilePath = tempDir.resolve("conf/instrument_profile.json");

        InstrumentProfileService service = InstrumentProfileService.getInstance();
        Map<String, Object> manifest = service.loadOrInitializeManifest(manifestPath, "TestInst", 2, profilePath);

        assertNotNull(manifest);
        assertEquals("TestInst", manifest.get("instrumentName"));
        assertEquals(2, manifest.get("numZones"));
        assertEquals(14, manifest.get("splineKnots"));

        double[] teGrid = (double[]) manifest.get("teAnchorGridEv");
        assertEquals(12, teGrid.length);

        double[] neGrid = (double[]) manifest.get("neAnchorGridCm3");
        assertEquals(4, neGrid.length);
    }
}
