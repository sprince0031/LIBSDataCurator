# Technical Implementation Specification: Multi-Family GPU Instrument Calibration & Physics-Informed Synthetic LIBS Pipeline

**Target Repository:** `LIBSDataCurator` (Branch: `feat-plasma-temp-variations`)  
**Target Agent:** Anti-Gravity CLI Conductor Agent (Input for phased `plan.md` generation)  
**Hardware Target:** Workstation with NVIDIA RTX 4070 MAX SUPER GPU (12 GB VRAM, CUDA) + Java 21 + Python 3.10+ (PyTorch CUDA, SciPy, NumPy)

---

## 1. Executive Summary & Architectural Decisions

This specification defines the complete refactoring of the **Instrument Profile Calibration** (`calibrate.sh`) and **Forward Synthetic Dataset Generation** (`run.sh`) workflows in `LIBSDataCurator`.

### 1.1 Optimal Workflow for Iterative CLI Calibration & Family Profile Upsert

To avoid introducing complex JSON input-parsing logic into the Java CLI while supporting **multi-CSV reference material directories** (5 CSV files per reference material folder, 40 laser shots per CSV) and **multi-material alloy families** (15 Certified Reference Materials across 5 Material Families), the architecture implements an **Incremental Family State + Upsert Pattern**:

1. **Iterative Material-Level CLI Invocation (`run_batch_calibration.py` $\rightarrow$ `calibrate.sh`):**
   * `run_batch_calibration.py` reads `reference_compositions.json` and invokes `./calibrate.sh` iteratively for each reference material, passing the composition string formatted as `Element-Value` pairs via the existing `-c` flag (e.g., `-c "C-0.279,Si-0.421,Mn-0.598,P-0.015,S-0.02,Cr-11.93,Ni-0.246,Fe-#"`).
   * Java's existing `InputCompositionProcessor` natively parses `-` or `:` delimiters, `min:max` ranges (such as `"Si-6.5:7.5"` in `Al_ENAS-42000-1`), and the `#` remainder symbol without requiring any new JSON composition parser in Java.
   * The `-i` flag accepts the reference material's subdirectory path (e.g., `<dataset_dir>/469`), which contains the 5 spatial-point CSV files (`point_1.csv` through `point_5.csv`), or a single CSV file path.
   * Two CLI flags identify the material hierarchy during calibration: `-mt` / `--material-type` (e.g., `Ferritic_Stainless_Steel`) and `-mg` / `--material-grade` (e.g., `469`).

2. **Morphological Opening Baseline Correction (Default Structure Size = $25$):**
   * Baseline correction across the calibration pipeline is standardized on **1D Morphological Grey Opening** (`scipy.ndimage.grey_opening`) with a default structuring element size of **$25\text{ channels}$** (`structuringElementSize = 25`).
   * **Physical Rationale:** On the target spectrometer grid ($\Delta\lambda \approx 0.294\text{ nm}$ at $342\text{ nm}$ to $0.243\text{ nm}$ at $896\text{ nm}$), a $25\text{-channel}$ structuring element spans $\sim 6.1\text{--}7.3\text{ nm}$. This window is wide enough to roll underneath discrete atomic/ionic emission peaks and their Stark-broadened Voigt wings ($\text{FWHM} \approx 0.3\text{--}0.8\text{ nm}$, or $1\text{--}3\text{ channels}$) without clipping multiplet clusters, while tightly tracking and stripping the underlying Bremsstrahlung and recombination continuum.

3. **Java NIST Grid Caching & Incremental Family Manifest (`temp/calibration_manifest.json`):**
   * For the current reference material (`-mg`), `InstrumentProfileController` delegates to `InstrumentProfileService` and `LIBSDataService` to fetch or verify the 48-state discrete $(T_e, N_e)$ NIST anchor grid via Selenium in-browser recalculation.
   * Java scans the `-i` directory for the 5 CSV files and partitions them into **Calibration CSVs** (default: first 4 CSV files = 160 shots) and **Holdout Validation CSVs** (default: 5th CSV file = 40 shots, left untouched for out-of-sample testing in the external Streamlit-Plotly analysis repository).
   * Java upserts the current material's entry (normalized composition map, calibration CSV paths, holdout CSV paths, and cached NIST CSV paths) under its `materialType` key inside `temp/calibration_manifest.json`.

4. **GPU Joint Family Optimization & `instrument_profile.json` Upsert (`scripts/gpu_calibration_engine.py`):**
   * Java invokes `scripts/gpu_calibration_engine.py` via `PythonUtils`.
   * The Python GPU engine loads all reference materials registered under `materialFamilies` in `temp/calibration_manifest.json`.
   * **Non-Destructive Upsert Rule for `instrument_profile.json`:** If `conf/instrument_profile.json` does not exist, Python creates it. If `conf/instrument_profile.json` already exists, Python loads the existing JSON, preserves all previously calibrated material families, and **either appends the current `materialType` profile to `materialFamilyProfiles` (if not present) or updates/overwrites that `materialType` entry in-place** with the latest calibration results.

---

## 2. Strict Separation of Concerns & Deprecation Matrix

To guarantee **zero code or logic duplication** between Java and Python, responsibilities are partitioned as follows:

```text
+------------------------------------------------------------------------------+
|                     PYTHON BATCH RUNNER (run_batch_calibration.py)           |
| * Reads reference_compositions.json.                                         |
| * Formats each material composition as "El1-Val1,El2-Val2,..." for -c flag.  |
| * Invokes ./calibrate.sh -i <mat_dir> -c <comp> -mt <family> -mg <name> -z 2 |
+--------------------------------------+---------------------------------------+
                                       | CLI Invocation
                                       v
+------------------------------------------------------------------------------+
|                 JAVA CALIBRATION CONTROLLER & NIST CACHER                    |
|              (InstrumentProfileController & InstrumentProfileService)        |
| * Parses -c via existing InputCompositionProcessor (handles '#' and min:max).|
| * Scans -i directory for the 5 CSV files; holds out 1 CSV for validation.    |
| * Uses Selenium (LIBSDataService) to fetch & cache the 48-state (Te, Ne)     |
|   NIST grid for this composition into cache/nist/.                           |
| * Upserts material entry into temp/calibration_manifest.json & calls Python. |
+--------------------------------------+---------------------------------------+
                                       | ProcessBuilder (Manifest Path)
                                       v
+------------------------------------------------------------------------------+
|           PYTHON + PyTorch CUDA ENGINE (scripts/gpu_calibration_engine.py)   |
|                     [SOLE OWNER OF CALIBRATION MATH & OPTIMIZATION]          |
| * Raw CSV ingestion, pre-averaging saturation rejection (< 0.9 * FullScale). |
| * Continuum baseline removal via Morphological Opening (structure size = 25).|
| * Pseudo-Voigt FWHM_G(lambda) & FWHM_L(lambda) extraction from thin lines.   |
| * Differential Voigt Transfer Matrix Psi construction on RTX 4070 SUPER.     |
| * Stage 1: Global NNLS + B-Spline solver for spectralResponse r(lambda).     |
| * Stage 2: GPU 2D Bilinear Interpolation (F.grid_sample) + Contracting       |
|   Hypercube Monte Carlo N-Zone RTE optimization per Material Family.         |
| * Stage 3: Spatial CSV bootstrap for teStdDev, neLogStdDev, & noise beta.    |
| * Upserts family profile into conf/instrument_profile.json.                  |
+--------------------------------------+---------------------------------------+
                                       | Reads conf/instrument_profile.json
                                       v
+------------------------------------------------------------------------------+
|                JAVA FORWARD SYNTHETIC GENERATOR (run.sh)                     |
|            (LIBSDataController, LIBSDataService, SpectrumUtils)              |
|                   [SOLE OWNER OF FORWARD DATASET CURATION]                   |
| * DirichletSampler + ResidualsInjector (tramp elements).   |
| * PlasmaTempSampler: Coupled (Te, Ne) Gaussian sampling per shot.            |
| * 2D Bilinear Interpolation on cached NIST grid -> Differential Voigt Psi -> |
|   SpectrumUtils.combineNZones() -> r(lambda) * scaleFactor * Finotello Noise |
|   -> writes master_dataset.csv.                                              |
+------------------------------------------------------------------------------+
```

### 2.1 Code & Files to Delete / Cut from the Java Repository

| File Path | Action | Specific Elements to Cut / Modify | Rationale |
| :--- | :--- | :--- | :--- |
| `conf/calibration_report_template.ipynb` | **DELETE FILE** | Entire notebook file. | Deprecated; all spectral plotting and validation are off-loaded to the external Streamlit-Plotly repository. |
| `conf/calibration_report_multi_material_template.ipynb` | **DELETE FILE** | Entire notebook file. | Deprecated reporting workflow. |
| `src/main/resources/calibration_report_template.ipynb` | **DELETE FILE** | Entire duplicate resource file. | Deprecated reporting workflow. |
| `src/main/resources/calibration_report_multi_material_template.ipynb` | **DELETE FILE** | Entire duplicate resource file. | Deprecated reporting workflow. |
| `BaselineCorrectionService.java` & `BaselineCorrectionServiceTest.java` | **DELETE OR DEPRECATE** | Remove ALS / Java-side baseline execution during calibration. | Baseline removal via Morphological Opening (`size=25`) is executed in `gpu_calibration_engine.py` on raw unaveraged shots after saturation masking. |
| `InstrumentProfileService.java` | **CUT METHODS** | 1. `optimizePlasmaParameters(...)`<br>2. `findBestCombination(...)`<br>3. `generateZoneCombinations(...)`<br>4. `generateKCombinations(...)`<br>5. `generateCalibrationReport(...)`<br>6. Hardcoded `V_FRACTION_ARRAY` and `K_ABS_ARRAY`. | Replaced by `stageMaterialAndRunGpuCalibration(...)`, which caches the NIST anchor grid, updates `temp/calibration_manifest.json`, and invokes the PyTorch CUDA engine. |
| `PythonUtils.java` | **CUT & REPLACE** | Remove `executeNotebook(...)`, `convertNotebookToPdf(...)`, Papermill/Pandoc calls. Add `runGpuCalibrationEngine(Path scriptPath, Path manifestPath)`. | Eliminates deprecated Jupyter/Pandoc reporting and replaces it with the single GPU calibration process call. |

---

## 3. Data Models & JSON Handoff Contracts

### 3.1 Handoff File 1: `temp/calibration_manifest.json` (Java $\rightarrow$ Python)

Each time `./calibrate.sh` runs for a reference material, `InstrumentProfileService` loads `temp/calibration_manifest.json` (or creates a new one if absent), upserts the current material under `materialFamilies.<materialType>`, sets `targetMaterialFamily` to `<materialType>`, and writes the file to disk:

```json
{
  "targetMaterialFamily": "Ferritic_Stainless_Steel",
  "outputProfilePath": "conf/instrument_profile.json",
  "nistResolvingPower": 5000.0,
  "detectorFullScale": 65535.0,
  "saturationThresholdFactor": 0.9,
  "numZones": 2,
  "splineKnots": 14,
  "splinePenaltyMuR": 100.0,
  "baselineCorrectionParams": {
    "method": "morphological_opening",
    "structuringElementSize": 25
  },
  "teAnchorGridEv": [0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.5, 1.7, 2.0],
  "neAnchorGridCm3": [1.0e16, 5.0e16, 1.0e17, 5.0e17],
  "trampWindowsNm": [
    {"element": "Cu", "minNm": 324.5, "maxNm": 327.8, "weight": 5.0},
    {"element": "Sn", "minNm": 283.5, "maxNm": 286.8, "weight": 5.0},
    {"element": "Cr", "minNm": 425.0, "maxNm": 429.5, "weight": 3.0},
    {"element": "Ni", "minNm": 341.0, "maxNm": 353.0, "weight": 3.0},
    {"element": "Pb", "minNm": 405.2, "maxNm": 406.2, "weight": 5.0}
  ],
  "resonanceMaskWindowsNm": [
    {"species": "H_alpha", "minNm": 654.0, "maxNm": 658.5},
    {"species": "Fe_I_res", "minNm": 371.5, "maxNm": 375.5},
    {"species": "Cu_I_res", "minNm": 324.5, "maxNm": 327.6},
    {"species": "Al_I_res", "minNm": 394.0, "maxNm": 396.5}
  ],
  "materialFamilies": {
    "Ferritic_Stainless_Steel": [
      {
        "materialName": "469",
        "referenceComposition": "C-0.279,Si-0.421,Mn-0.598,P-0.015,S-0.02,Cr-11.93,Ni-0.246,Fe-86.491",
        "compositionMap": {
          "C": 0.279, "Si": 0.421, "Mn": 0.598, "P": 0.015,
          "S": 0.02, "Cr": 11.93, "Ni": 0.246, "Fe": 86.491
        },
        "measuredCsvPaths": [
          "/path/to/dataset/469/point_1.csv",
          "/path/to/dataset/469/point_2.csv",
          "/path/to/dataset/469/point_3.csv",
          "/path/to/dataset/469/point_4.csv"
        ],
        "holdoutCsvPaths": [
          "/path/to/dataset/469/point_5.csv"
        ],
        "nistGridCsvPaths": {
          "0.5_1.0E16": "cache/nist/469_Te0.5_Ne1.0E16.csv",
          "0.5_5.0E16": "cache/nist/469_Te0.5_Ne5.0E16.csv"
        }
      }
    ]
  }
}
```

### 3.2 Handoff File 2: `conf/instrument_profile.json` (Python Upsert $\rightarrow$ Java Reader)

The Python engine maintains backward compatibility with existing top-level keys (`baselineCorrectionParams`, `instrumentName`, `wavelengths`, `materialFamilyProfiles`, `numShots`) while updating `baselineCorrectionParams` to Morphological Opening (`structuringElementSize: 25`) and adding `spectralResponse`, `voigtProfile`, `finotelloNoiseBeta`, and per-zone `teStdDev` / `neLogStdDev`:

```json
{
  "baselineCorrectionParams": {
    "method": "morphological_opening",
    "structuringElementSize": 25
  },
  "instrumentName": "LSA_MultiFamily_Calibrated_Profile",
  "wavelengths": [
    341.952,
    342.246,
    "... (P detector channels)"
  ],
  "spectralResponse": [
    0.8412,
    0.8435,
    "... (P channels, geometric mean = 1.0)"
  ],
  "voigtProfile": {
    "nistResolvingPower": 5000.0,
    "fwhmGaussianNm": [0.285, 0.284, "... (P channels)"],
    "fwhmLorentzianNm": [0.062, 0.062, "... (P channels)"]
  },
  "finotelloNoiseBeta": 0.094,
  "materialFamilyProfiles": {
    "Ferritic_Stainless_Steel": {
      "calibrationStats": {
        "rSquared": 0.9384,
        "rmse": 0.0241,
        "weightedCorrelationCost": 0.0512,
        "numCalibratedMaterials": 5,
        "calibratedMaterials": ["469", "470", "471", "472", "473"]
      },
      "plasmaZones": [
        {
          "zoneIndex": 0,
          "Te": 1.184,
          "teStdDev": 0.058,
          "Ne": 2.84e17,
          "neLogStdDev": 0.105,
          "volumeFraction": 0.812,
          "kAbsorption": 0.0
        },
        {
          "zoneIndex": 1,
          "Te": 0.642,
          "teStdDev": 0.039,
          "Ne": 3.15e16,
          "neLogStdDev": 0.088,
          "volumeFraction": 0.188,
          "kAbsorption": 2.415
        }
      ],
      "scaleFactor": 32983.75,
      "resolution": 5000,
      "materialFamily": "Ferritic_Stainless_Steel",
      "validationHoldoutCsvs": {
        "469": ["/path/to/dataset/469/point_5.csv"],
        "470": ["/path/to/dataset/470/point_5.csv"]
      }
    }
  },
  "numShots": 40
}
```

---

## 4. Java Refactoring Specifications

### 4.1 Update `BaselineCorrectionParams.java`

Refactor `src/main/java/com/medals/libsdatagenerator/model/BaselineCorrectionParams.java` to represent the Morphological Opening configuration with a default structuring element size of $25$, while ignoring deprecated ALS fields (`p`, `lambda`, `maxIterations`) via `@JsonIgnoreProperties(ignoreUnknown = true)` so legacy `instrument_profile.json` files still load cleanly:

```java
package com.medals.libsdatagenerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BaselineCorrectionParams {

    @JsonProperty("method")
    private String method = "morphological_opening";

    @JsonProperty("structuringElementSize")
    private int structuringElementSize = 25;

    public BaselineCorrectionParams() {}

    public BaselineCorrectionParams(String method, int structuringElementSize) {
        this.method = method;
        this.structuringElementSize = structuringElementSize;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public int getStructuringElementSize() {
        return structuringElementSize;
    }

    public void setStructuringElementSize(int structuringElementSize) {
        this.structuringElementSize = structuringElementSize;
    }
}
```

### 4.2 Update `PlasmaZone.java`

Update `src/main/java/com/medals/libsdatagenerator/model/PlasmaZone.java` to include `teStdDev` and `neLogStdDev` alongside `Te`, `Ne`, `volumeFraction`, and `kAbsorption`:

```java
package com.medals.libsdatagenerator.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class PlasmaZone {

    @JsonProperty("zoneIndex")
    private int zoneIndex;

    @JsonProperty("Te")
    private double te;

    @JsonProperty("teStdDev")
    private double teStdDev = 0.05;

    @JsonProperty("Ne")
    private double ne;

    @JsonProperty("neLogStdDev")
    private double neLogStdDev = 0.08;

    @JsonProperty("volumeFraction")
    private double volumeFraction;

    @JsonProperty("kAbsorption")
    private double kAbsorption;

    public PlasmaZone() {}

    public PlasmaZone(PlasmaZone other) {
        this.zoneIndex = other.zoneIndex;
        this.te = other.te;
        this.teStdDev = other.teStdDev;
        this.ne = other.ne;
        this.neLogStdDev = other.neLogStdDev;
        this.volumeFraction = other.volumeFraction;
        this.kAbsorption = other.kAbsorption;
    }

    public int getZoneIndex() { return zoneIndex; }
    public void setZoneIndex(int zoneIndex) { this.zoneIndex = zoneIndex; }

    public double getTe() { return te; }
    public void setTe(double te) { this.te = te; }

    public double getTeStdDev() { return teStdDev; }
    public void setTeStdDev(double teStdDev) { this.teStdDev = teStdDev; }

    public double getNe() { return ne; }
    public void setNe(double ne) { this.ne = ne; }

    public double getNeLogStdDev() { return neLogStdDev; }
    public void setNeLogStdDev(double neLogStdDev) { this.neLogStdDev = neLogStdDev; }

    public double getVolumeFraction() { return volumeFraction; }
    public void setVolumeFraction(double volumeFraction) { this.volumeFraction = volumeFraction; }

    public double getKAbsorption() { return kAbsorption; }
    public void setKAbsorption(double kAbsorption) { this.kAbsorption = kAbsorption; }
}
```

### 4.3 Refactor `InstrumentProfileController.java` & `InstrumentProfileService.java`

In `InstrumentProfileController.java`, support passing either a directory of CSVs (e.g., `-i /path/to/dataset/469`) or a single CSV file via `-i`, plus `-mt <materialType>` and `-mg <materialGrade>`:

```java
package com.medals.libsdatagenerator.controller;

import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.InstrumentProfile;
import com.medals.libsdatagenerator.service.InstrumentProfileService;
import com.medals.libsdatagenerator.util.InputCompositionProcessor;

import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

public class InstrumentProfileController {
    private static final Logger logger = Logger.getLogger(InstrumentProfileController.class.getName());
    private final InstrumentProfileService profileService;
    private final InputCompositionProcessor compositionProcessor;

    public InstrumentProfileController(
            InstrumentProfileService profileService,
            InputCompositionProcessor compositionProcessor) {
        this.profileService = profileService;
        this.compositionProcessor = compositionProcessor;
    }

    public InstrumentProfile runCalibration(
            Path inputPath,
            String compositionStr,
            String materialType,
            String materialGrade,
            String instrumentName,
            int numZones,
            int holdoutCsvCount,
            Path outputProfilePath) throws Exception {

        // 1. Parse composition string (handles '-', ':', min:max ranges, and '#' balance)
        List<Element> elements = compositionProcessor.parseCompositionString(compositionStr);

        logger.info(() -> String.format(
                "Starting calibration for Material Grade '%s' (Family: '%s') with %d zones...",
                materialGrade, materialType, numZones));

        // 2. Stage NIST 48-state cache, upsert manifest, and execute GPU calibration engine
        return profileService.stageMaterialAndRunGpuCalibration(
                inputPath,
                elements,
                materialType,
                materialGrade,
                instrumentName,
                numZones,
                holdoutCsvCount,
                outputProfilePath
        );
    }
}
```

In `InstrumentProfileService.java`, implement `stageMaterialAndRunGpuCalibration(...)`:

```java
package com.medals.libsdatagenerator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.medals.libsdatagenerator.model.BaselineCorrectionParams;
import com.medals.libsdatagenerator.model.Element;
import com.medals.libsdatagenerator.model.InstrumentProfile;
import com.medals.libsdatagenerator.util.PythonUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class InstrumentProfileService {
    private static final Logger logger = Logger.getLogger(InstrumentProfileService.class.getName());

    public static final double[] TE_ANCHOR_GRID_EV = {
        0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.5, 1.7, 2.0
    };
    public static final double[] NE_ANCHOR_GRID_CM3 = {
        1.0e16, 5.0e16, 1.0e17, 5.0e17
    };

    private static final double NIST_RESOLVING_POWER = 5000.0;
    private static final int DEFAULT_MORPHOLOGICAL_STRUCT_SIZE = 25;

    private final LIBSDataService libsDataService;
    private final PythonUtils pythonUtils;
    private final ObjectMapper objectMapper;

    public InstrumentProfileService(LIBSDataService libsDataService, PythonUtils pythonUtils) {
        this.libsDataService = libsDataService;
        this.pythonUtils = pythonUtils;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @SuppressWarnings("unchecked")
    public InstrumentProfile stageMaterialAndRunGpuCalibration(
            Path inputPath,
            List<Element> elements,
            String materialType,
            String materialGrade,
            String instrumentName,
            int numZones,
            int holdoutCsvCount,
            Path outputProfilePath) throws Exception {

        // 1. Resolve CSV files from directory or single file
        List<Path> allCsvs = resolveInputCsvFiles(inputPath);
        int splitIdx = (allCsvs.size() > holdoutCsvCount && holdoutCsvCount > 0)
                ? allCsvs.size() - holdoutCsvCount
                : allCsvs.size();

        List<String> trainCsvs = allCsvs.subList(0, splitIdx).stream()
                .map(p -> p.toAbsolutePath().toString())
                .collect(Collectors.toList());
        List<String> holdoutCsvs = allCsvs.subList(splitIdx, allCsvs.size()).stream()
                .map(p -> p.toAbsolutePath().toString())
                .collect(Collectors.toList());

        // 2. Fetch or verify 48-state NIST grid in cache/nist/ via Selenium recalculation
        Map<String, String> nistGridCsvPaths = libsDataService.fetchAndCacheNistAnchorGrid(
                materialGrade, elements, TE_ANCHOR_GRID_EV, NE_ANCHOR_GRID_CM3, NIST_RESOLVING_POWER
        );

        // 3. Load or initialize temp/calibration_manifest.json
        Path manifestPath = Path.of("temp", "calibration_manifest.json");
        Files.createDirectories(manifestPath.getParent());
        Map<String, Object> manifest = loadOrInitializeManifest(
                manifestPath, instrumentName, numZones, outputProfilePath);

        manifest.put("targetMaterialFamily", materialType);
        manifest.put("numZones", numZones);
        manifest.put("outputProfilePath", outputProfilePath.toAbsolutePath().toString());

        // 4. Upsert material entry inside manifest.materialFamilies.<materialType>
        Map<String, List<Map<String, Object>>> familiesMap =
                (Map<String, List<Map<String, Object>>>) manifest.computeIfAbsent(
                        "materialFamilies", k -> new LinkedHashMap<>());

        List<Map<String, Object>> familyMatList =
                familiesMap.computeIfAbsent(materialType, k -> new ArrayList<>());

        Map<String, Double> compMap = new LinkedHashMap<>();
        StringBuilder normCompBuilder = new StringBuilder();
        for (Element el : elements) {
            if (el.getPercentageComposition() > 0.0) {
                compMap.put(el.getSymbol(), el.getPercentageComposition());
                if (normCompBuilder.length() > 0) normCompBuilder.append(",");
                normCompBuilder.append(el.getSymbol()).append("-").append(el.getPercentageComposition());
            }
        }

        Map<String, Object> materialEntry = new LinkedHashMap<>();
        materialEntry.put("materialName", materialGrade);
        materialEntry.put("referenceComposition", normCompBuilder.toString());
        materialEntry.put("compositionMap", compMap);
        materialEntry.put("measuredCsvPaths", trainCsvs);
        materialEntry.put("holdoutCsvPaths", holdoutCsvs);
        materialEntry.put("nistGridCsvPaths", nistGridCsvPaths);

        // Replace existing entry for materialGrade if present, otherwise append
        familyMatList.removeIf(entry -> materialGrade.equals(String.valueOf(entry.get("materialName"))));
        familyMatList.add(materialEntry);

        objectMapper.writeValue(manifestPath.toFile(), manifest);

        // 5. Execute GPU Calibration Engine (Morphological Opening + Voigt + NNLS + GPU MC RTE)
        pythonUtils.runGpuCalibrationEngine(
                Path.of("scripts", "gpu_calibration_engine.py"),
                manifestPath
        );

        return objectMapper.readValue(outputProfilePath.toFile(), InstrumentProfile.class);
    }

    private List<Path> resolveInputCsvFiles(Path inputPath) throws IOException {
        if (Files.isDirectory(inputPath)) {
            try (Stream<Path> stream = Files.list(inputPath)) {
                List<Path> csvs = stream
                        .filter(p -> p.toString().toLowerCase().endsWith(".csv"))
                        .sorted()
                        .collect(Collectors.toList());
                if (csvs.isEmpty()) {
                    throw new IOException("No CSV files found in directory: " + inputPath);
                }
                return csvs;
            }
        } else if (Files.isRegularFile(inputPath)) {
            return Collections.singletonList(inputPath);
        }
        throw new IOException("Calibration input path does not exist: " + inputPath);
    }

    private Map<String, Object> loadOrInitializeManifest(
            Path manifestPath, String instrumentName, int numZones, Path outputProfilePath) throws IOException {
        if (Files.exists(manifestPath)) {
            return objectMapper.readValue(manifestPath.toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("instrumentName", instrumentName);
        root.put("nistResolvingPower", NIST_RESOLVING_POWER);
        root.put("detectorFullScale", 65535.0);
        root.put("saturationThresholdFactor", 0.9);
        root.put("numZones", numZones);
        root.put("splineKnots", 14);
        root.put("splinePenaltyMuR", 100.0);
        root.put("outputProfilePath", outputProfilePath.toAbsolutePath().toString());
        root.put("baselineCorrectionParams",
                new BaselineCorrectionParams("morphological_opening", DEFAULT_MORPHOLOGICAL_STRUCT_SIZE));
        root.put("teAnchorGridEv", TE_ANCHOR_GRID_EV);
        root.put("neAnchorGridCm3", NE_ANCHOR_GRID_CM3);
        root.put("materialFamilies", new LinkedHashMap<String, List<Map<String, Object>>>());
        return root;
    }
}
```

### 4.4 Coupled $(T_e, N_e)$ Sampling in `PlasmaTempSampler.java` & Forward RTE Synthesis

In `PlasmaTempSampler.java`, extend the sampler so that a single Gaussian draw $z_{\text{shot}} \sim \mathcal{N}(0, 1)$ (clamped to $[-2.0, +2.0]$) perturbs both $T_e$ and $\log_{10}(N_e)$ across all plasma zones simultaneously:

```java
package com.medals.libsdatagenerator.sampler;

import com.medals.libsdatagenerator.model.PlasmaZone;
import org.apache.commons.rng.UniformRandomProvider;
import org.apache.commons.rng.sampling.distribution.ContinuousSampler;
import org.apache.commons.rng.sampling.distribution.ZigguratSampler;
import org.apache.commons.rng.simple.RandomSource;

import java.util.ArrayList;
import java.util.List;

public class PlasmaTempSampler {
    private static PlasmaTempSampler instance;

    public static PlasmaTempSampler getInstance() {
        if (instance == null) {
            instance = new PlasmaTempSampler();
        }
        return instance;
    }

    public List<List<PlasmaZone>> sampleZones(
            List<PlasmaZone> baseZones,
            int numSamples,
            double defaultRelativeStdDev,
            Long seed) {

        List<List<PlasmaZone>> shotZoneProfiles = new ArrayList<>(numSamples);
        UniformRandomProvider rng = (seed != null)
                ? RandomSource.XO_RO_SHI_RO_128_PP.create(seed)
                : RandomSource.XO_RO_SHI_RO_128_PP.create();
        ContinuousSampler normalSampler = ZigguratSampler.NormalizedGaussian.of(rng);

        for (int i = 0; i < numSamples; i++) {
            // Single standard normal variate per shot preserves T_core > T_periphery and Te-Ne covariance
            double zShot = Math.max(-2.0, Math.min(2.0, normalSampler.sample()));

            List<PlasmaZone> perturbedZones = new ArrayList<>(baseZones.size());
            for (PlasmaZone base : baseZones) {
                PlasmaZone perturbed = new PlasmaZone(base);

                double sigmaTeEv = (base.getTeStdDev() > 0.0)
                        ? base.getTeStdDev()
                        : base.getTe() * defaultRelativeStdDev;
                double newTeEv = Math.max(0.45, Math.min(2.20, base.getTe() + zShot * sigmaTeEv));
                perturbed.setTe(Math.round(newTeEv * 10000.0) / 10000.0);

                double sigmaLogNe = (base.getNeLogStdDev() > 0.0)
                        ? base.getNeLogStdDev()
                        : 0.4343 * defaultRelativeStdDev;
                double newLogNe = Math.max(15.0, Math.min(18.0, Math.log10(base.getNe()) + zShot * sigmaLogNe));
                perturbed.setNe(Math.pow(10.0, newLogNe));

                perturbedZones.add(perturbed);
            }
            shotZoneProfiles.add(perturbedZones);
        }
        return shotZoneProfiles;
    }
}
```

---

## 5. Python GPU Calibration Engine (`scripts/gpu_calibration_engine.py`)

Create `scripts/gpu_calibration_engine.py`. This script is the **sole owner of calibration mathematics**, implementing:
1. Pre-averaging saturation rejection ($< 0.9 \times y_{\text{FS}}$) across all 40-shot CSV files per material.
2. **Morphological Opening baseline correction** (`scipy.ndimage.grey_opening`) with a **default structure size of $25$ channels**.
3. Automated pseudo-Voigt $\text{FWHM}_G(\lambda)$ and $\text{FWHM}_L(\lambda)$ extraction and construction of the GPU Differential Voigt Transfer Matrix $\boldsymbol{\Psi} \in \mathbb{R}^{P \times P_{\text{NIST}}}$.
4. Stage 1 Global NNLS + B-Spline instrument response $\mathbf{r}(\lambda)$ recovery across all staged materials.
5. Stage 2 GPU 2D Bilinear Interpolation (`F.grid_sample`) + Contracting Hypercube Monte Carlo $N$-zone RTE optimization on the **RTX 4070 MAX SUPER**.
6. Stage 3 Spatial Point Bootstrap across the CSV files for `teStdDev` and `neLogStdDev`.
7. **Non-destructive Upsert** into `conf/instrument_profile.json` (appending new material families or updating existing material family entries in-place).

```python
#!/usr/bin/env python3
"""
GPU-Accelerated Multi-Material & Multi-Family LIBS Calibration Engine
Hardware Target: NVIDIA RTX 4070 MAX SUPER (PyTorch CUDA)
Baseline Correction: 1D Morphological Grey Opening (Default Structure Size = 25)
"""

import json
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
import torch
import torch.nn.functional as F
from scipy.interpolate import BSpline
from scipy.ndimage import grey_opening
from scipy.optimize import curve_fit, nnls


DEFAULT_MORPHOLOGICAL_STRUCT_SIZE = 25


class GpuLibsCalibrationEngine:
    def __init__(self, manifest_path: str):
        self.manifest_path = Path(manifest_path)
        with open(self.manifest_path, "r", encoding="utf-8") as f:
            self.manifest = json.load(f)

        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        dev_name = torch.cuda.get_device_name(0) if torch.cuda.is_available() else "CPU"
        print(f"[GPU Engine] Active Device: {self.device} ({dev_name})")

        bc_params = self.manifest.get("baselineCorrectionParams", {})
        self.struct_size = int(bc_params.get("structuringElementSize", DEFAULT_MORPHOLOGICAL_STRUCT_SIZE))

        self.te_grid = np.array(self.manifest["teAnchorGridEv"], dtype=np.float64)
        self.ne_grid = np.array(self.manifest["neAnchorGridCm3"], dtype=np.float64)
        self.log_ne_grid = np.log10(self.ne_grid)
        self.n_te = len(self.te_grid)
        self.n_ne = len(self.ne_grid)
        self.j_states = self.n_te * self.n_ne

    # -------------------------------------------------------------------------
    # STEP 1: Multi-CSV Shot Ingestion, Saturation Rejection & Morphological Opening
    # -------------------------------------------------------------------------
    def strip_baseline_morphological_opening(
        self, spectrum: np.ndarray, struct_size: Optional[int] = None
    ) -> np.ndarray:
        """
        Strips Bremsstrahlung continuum using 1D morphological grey opening
        with a flat structuring element of size 25 by default.
        """
        size = struct_size if struct_size is not None else self.struct_size
        baseline = grey_opening(spectrum, size=size)
        return np.maximum(spectrum - baseline, 0.0)

    def load_and_clean_material_csvs(
        self, csv_paths: List[str]
    ) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, int]:
        """
        Loads all calibration CSV files (40 shots each) for a single CRM.
        Applies pre-averaging saturation rejection (< 0.9 * FullScale) and
        Morphological Opening baseline correction (default structure size = 25).
        """
        full_scale = float(self.manifest.get("detectorFullScale", 65535.0))
        y_sat = float(self.manifest.get("saturationThresholdFactor", 0.9)) * full_scale

        all_shots_list = []
        point_means_list = []
        lam_meas = None

        for path in csv_paths:
            df = pd.read_csv(path)
            # Detect whether columns 0..1 are metadata (ShotID, Timestamp) or wavelengths
            first_col_val = self._try_parse_float(df.columns[0])
            start_col = 0 if first_col_val is not None and first_col_val > 150.0 else 2

            if lam_meas is None:
                lam_meas = np.array([float(c) for c in df.columns[start_col:]], dtype=np.float64)
            shots_pt = df.iloc[:, start_col:].to_numpy(dtype=np.float64)

            v_pt = (shots_pt < y_sat).astype(np.float64)
            nv_pt = np.maximum(v_pt.sum(axis=0), 1.0)
            mean_pt = (v_pt * shots_pt).sum(axis=0) / nv_pt
            point_means_list.append(self.strip_baseline_morphological_opening(mean_pt))
            all_shots_list.append(shots_pt)

        shots_all = np.vstack(all_shots_list)
        v_mask = (shots_all < y_sat).astype(np.float64)
        nv = np.maximum(v_mask.sum(axis=0), 1.0)

        raw_mean = (v_mask * shots_all).sum(axis=0) / nv
        var_shot = (v_mask * (shots_all - raw_mean[None, :]) ** 2).sum(axis=0) / np.maximum(nv - 1.0, 1.0)
        sigma_mean = np.sqrt(var_shot / nv) + 1e-6

        y_clean = self.strip_baseline_morphological_opening(raw_mean)
        return lam_meas, y_clean, sigma_mean, np.array(point_means_list, dtype=np.float64), shots_all.shape[0]

    @staticmethod
    def _try_parse_float(val: str) -> Optional[float]:
        try:
            return float(val)
        except (ValueError, TypeError):
            return None

    # -------------------------------------------------------------------------
    # STEP 2: Voigt Profile Estimation & GPU Differential Voigt Transfer Matrix
    # -------------------------------------------------------------------------
    def estimate_measured_voigt_fwhm(
        self, lam_meas: np.ndarray, y_ref: np.ndarray
    ) -> Tuple[np.ndarray, np.ndarray]:
        dl_channel = np.abs(np.gradient(lam_meas))
        fwhm_g_default = 1.8 * dl_channel
        fwhm_l_default = 0.5 * dl_channel

        y_max = np.max(y_ref)
        peak_indices = [
            i for i in range(15, len(y_ref) - 15)
            if y_ref[i] == np.max(y_ref[i - 8:i + 9]) and (0.15 * y_max < y_ref[i] < 0.75 * y_max)
        ]

        if len(peak_indices) < 3:
            return fwhm_g_default, fwhm_l_default

        fitted_lam, fitted_fg, fitted_fl = [], [], []

        def pvoigt(x, amp, x0, fg, fl):
            fv = (fg**5 + 2.69269*(fg**4)*fl + 2.42843*(fg**3)*(fl**2)
                  + 4.47163*(fg**2)*(fl**3) + 0.07842*fg*(fl**4) + fl**5) ** 0.2
            ratio = np.clip(fl / np.maximum(fv, 1e-6), 0.0, 1.0)
            eta = np.clip(1.36603*ratio - 0.47719*(ratio**2) + 0.11116*(ratio**3), 0.0, 1.0)
            g = np.exp(-4.0 * np.log(2.0) * ((x - x0) / fv) ** 2)
            l = 1.0 / (1.0 + 4.0 * ((x - x0) / fv) ** 2)
            return amp * (eta * l + (1.0 - eta) * g)

        for idx in peak_indices:
            win_x = lam_meas[idx - 8:idx + 9]
            win_y = y_ref[idx - 8:idx + 9]
            loc_dl = dl_channel[idx]
            try:
                popt, _ = curve_fit(
                    pvoigt, win_x, win_y,
                    p0=[win_y[8], lam_meas[idx], 1.8 * loc_dl, 0.5 * loc_dl],
                    bounds=([0.0, win_x[0], 0.5 * loc_dl, 0.05 * loc_dl],
                            [2.0 * y_max, win_x[-1], 5.0 * loc_dl, 3.0 * loc_dl]),
                    maxfev=800
                )
                fitted_lam.append(popt[1])
                fitted_fg.append(popt[2])
                fitted_fl.append(popt[3])
            except Exception:
                continue

        if len(fitted_lam) < 3:
            return fwhm_g_default, fwhm_l_default

        fg_poly = np.poly1d(np.polyfit(fitted_lam, fitted_fg, deg=1))
        fl_poly = np.poly1d(np.polyfit(fitted_lam, fitted_fl, deg=1))
        fwhm_g = np.clip(fg_poly(lam_meas), 0.5 * dl_channel, 5.0 * dl_channel)
        fwhm_l = np.clip(fl_poly(lam_meas), 0.05 * dl_channel, 3.0 * dl_channel)
        return fwhm_g, fwhm_l

    def build_gpu_voigt_transfer_matrix(
        self, lam_meas: np.ndarray, lam_nist: np.ndarray,
        fwhm_g_meas: np.ndarray, fwhm_l_meas: np.ndarray
    ) -> torch.Tensor:
        r_nist = float(self.manifest.get("nistResolvingPower", 5000.0))
        fwhm_g_nist = lam_meas / r_nist

        f_g = np.sqrt(np.maximum(fwhm_g_meas**2 - fwhm_g_nist**2, 1e-9))[:, None]
        f_l = np.maximum(fwhm_l_meas, 1e-9)[:, None]

        f_v = (f_g**5 + 2.69269*(f_g**4)*f_l + 2.42843*(f_g**3)*(f_l**2)
               + 4.47163*(f_g**2)*(f_l**3) + 0.07842*f_g*(f_l**4) + f_l**5) ** 0.2
        ratio = np.clip(f_l / f_v, 0.0, 1.0)
        eta = np.clip(1.36603*ratio - 0.47719*(ratio**2) + 0.11116*(ratio**3), 0.0, 1.0)

        t_lam_meas = torch.tensor(lam_meas[:, None], dtype=torch.float32, device=self.device)
        t_lam_nist = torch.tensor(lam_nist[None, :], dtype=torch.float32, device=self.device)
        t_fv = torch.tensor(f_v, dtype=torch.float32, device=self.device)
        t_eta = torch.tensor(eta, dtype=torch.float32, device=self.device)
        t_dl_nist = torch.tensor(np.abs(np.gradient(lam_nist))[None, :], dtype=torch.float32, device=self.device)

        dl = t_lam_meas - t_lam_nist
        window_mask = (torch.abs(dl) <= 12.0 * t_fv).float()

        g_prof = (float(np.sqrt(4.0 * np.log(2.0) / np.pi)) / t_fv) * torch.exp(
            -4.0 * float(np.log(2.0)) * (dl / t_fv) ** 2
        )
        l_prof = (float(2.0 / np.pi) / t_fv) / (1.0 + 4.0 * (dl / t_fv) ** 2)

        psi = (t_eta * l_prof + (1.0 - t_eta) * g_prof) * window_mask * t_dl_nist
        row_sums = torch.clamp(psi.sum(dim=1, keepdim=True), min=1e-12)
        return psi / row_sums

    def load_nist_dictionary_for_material(
        self, nist_paths_map: Dict[str, str], psi_gpu: Optional[torch.Tensor] = None
    ) -> Tuple[np.ndarray, torch.Tensor]:
        s_cols = []
        lam_nist = None
        for te in self.te_grid:
            for ne in self.ne_grid:
                key_sci = f"{te}_{ne:.1E}"
                key_raw = f"{te}_{ne}"
                csv_path = nist_paths_map.get(key_sci) or nist_paths_map.get(key_raw)
                df = pd.read_csv(csv_path)
                if lam_nist is None:
                    lam_nist = df.iloc[:, 0].to_numpy(dtype=np.float64)
                s_cols.append(df.iloc[:, 1].to_numpy(dtype=np.float32))

        s_nist_gpu = torch.tensor(np.column_stack(s_cols), dtype=torch.float32, device=self.device)
        if psi_gpu is not None:
            return lam_nist, torch.matmul(psi_gpu, s_nist_gpu)
        return lam_nist, s_nist_gpu

    # -------------------------------------------------------------------------
    # STEP 3: Stage 1 Global NNLS + B-Spline Instrument Response Recovery
    # -------------------------------------------------------------------------
    def solve_global_instrument_response(
        self, lam_meas: np.ndarray, y_all: np.ndarray, sig_all: np.ndarray,
        d_all: np.ndarray, res_mask: np.ndarray
    ) -> np.ndarray:
        m_count, p_count = y_all.shape
        k_knots = int(self.manifest.get("splineKnots", 14))
        mu_r = float(self.manifest.get("splinePenaltyMuR", 100.0))

        interior = np.linspace(lam_meas[0], lam_meas[-1], k_knots - 2)
        knots = np.r_[[lam_meas[0]] * 3, interior, [lam_meas[-1]] * 3]
        b_mat = BSpline.design_matrix(lam_meas, knots, 3).toarray()
        delta2 = np.diff(np.eye(k_knots), 2, axis=0)
        p_reg = mu_r * (delta2.T @ delta2)

        r_vec = np.ones(p_count, dtype=np.float64)
        w_mat = np.zeros((m_count, self.j_states), dtype=np.float64)

        for _ in range(15):
            a_mat = np.zeros((m_count, p_count), dtype=np.float64)
            for m in range(m_count):
                scale_m = (r_vec * res_mask) / sig_all[m]
                w_mat[m], _ = nnls(scale_m[:, None] * d_all[m], (y_all[m] * res_mask) / sig_all[m])
                a_mat[m] = d_all[m] @ w_mat[m]

            num = np.zeros(p_count, dtype=np.float64)
            den = np.zeros(p_count, dtype=np.float64)
            for m in range(m_count):
                a_max = max(np.max(a_mat[m]), 1e-12)
                usable = (a_mat[m] > 1e-3 * a_max) & (y_all[m] > 0) & (res_mask > 0.5)
                vv = np.where(usable, (y_all[m] / sig_all[m]) ** 2, 0.0)
                rho = np.where(usable, np.log(np.maximum(y_all[m], 1e-12) / np.maximum(a_mat[m], 1e-12)), 0.0)
                num += vv * rho
                den += vv

            rho_bar = np.where(den > 0, num / np.maximum(den, 1e-12), 0.0)
            beta = np.linalg.solve(b_mat.T @ (den[:, None] * b_mat) + p_reg, b_mat.T @ (den * rho_bar))

            log_r = b_mat @ beta
            log_r -= np.mean(log_r)
            r_new = np.exp(log_r)
            if np.max(np.abs(np.log(r_new / r_vec))) < 1e-4:
                r_vec = r_new
                break
            r_vec = r_new

        return r_vec

    # -------------------------------------------------------------------------
    # STEP 4: Stage 2 GPU Bilinear Interpolation + Contracting Hypercube MC RTE
    # -------------------------------------------------------------------------
    def interpolate_dictionary_bilinear_gpu(
        self, d_family_grid_gpu: torch.Tensor, te_samples: torch.Tensor, log_ne_samples: torch.Tensor
    ) -> torch.Tensor:
        mf, p_ch, n_t, n_n = d_family_grid_gpu.shape
        b_samples = te_samples.shape[0]

        u_te = 2.0 * (te_samples - float(self.te_grid[0])) / float(self.te_grid[-1] - self.te_grid[0]) - 1.0
        v_ne = 2.0 * (log_ne_samples - float(self.log_ne_grid[0])) / float(self.log_ne_grid[-1] - self.log_ne_grid[0]) - 1.0

        img = d_family_grid_gpu.reshape(1, mf * p_ch, n_t, n_n)
        grid = torch.stack([v_ne, u_te], dim=-1).view(1, b_samples, 1, 2)

        sampled = F.grid_sample(img, grid, mode="bilinear", padding_mode="border", align_corners=True)
        return sampled.view(mf, p_ch, b_samples).permute(2, 0, 1).contiguous()

    def optimize_family_rte_gpu(
        self, d_family_gpu: torch.Tensor, y_family_gpu: torch.Tensor,
        sig_family_gpu: torch.Tensor, r_gpu: torch.Tensor, tramp_w_gpu: torch.Tensor,
        n_zones: int = 2, n_mc_per_iter: int = 100000, n_iters: int = 5
    ) -> Dict:
        mf, p_ch = y_family_gpu.shape
        d_grid_gpu = d_family_gpu.view(mf, p_ch, self.n_te, self.n_ne)

        te_min = torch.linspace(0.95, 0.50, n_zones, device=self.device)
        te_max = torch.linspace(2.00, 1.15, n_zones, device=self.device)
        lne_min = torch.full((n_zones,), float(self.log_ne_grid[0]), device=self.device)
        lne_max = torch.full((n_zones,), float(self.log_ne_grid[-1]), device=self.device)
        v_min = torch.full((n_zones,), 0.05, device=self.device)
        v_max = torch.full((n_zones,), 0.95, device=self.device)
        k_min = torch.zeros(n_zones, device=self.device)
        k_max = torch.cat([torch.zeros(1, device=self.device), torch.full((n_zones - 1,), 5.0, device=self.device)])

        best_cost = float("inf")
        best_params = None
        best_scale = 1.0
        best_r2 = 0.0
        best_rmse = 0.0

        weights_p = (tramp_w_gpu / sig_family_gpu) ** 2
        y_mean_fam = (weights_p * y_family_gpu).sum(dim=1, keepdim=True) / weights_p.sum(dim=1, keepdim=True)
        y_centered = y_family_gpu - y_mean_fam
        y_norm = torch.sqrt((weights_p * (y_centered ** 2)).sum(dim=1, keepdim=True) + 1e-12)

        for _ in range(n_iters):
            te_cand = torch.empty((n_mc_per_iter, n_zones), device=self.device)
            lne_cand = torch.empty((n_mc_per_iter, n_zones), device=self.device)
            v_cand = torch.empty((n_mc_per_iter, n_zones), device=self.device)
            k_cand = torch.zeros((n_mc_per_iter, n_zones), device=self.device)

            for z in range(n_zones):
                te_cand[:, z] = torch.empty(n_mc_per_iter, device=self.device).uniform_(float(te_min[z]), float(te_max[z]))
                lne_cand[:, z] = torch.empty(n_mc_per_iter, device=self.device).uniform_(float(lne_min[z]), float(lne_max[z]))
                v_cand[:, z] = torch.empty(n_mc_per_iter, device=self.device).uniform_(float(v_min[z]), float(v_max[z]))
                if z > 0:
                    k_cand[:, z] = torch.empty(n_mc_per_iter, device=self.device).uniform_(float(k_min[z]), float(k_max[z]))

            te_cand, _ = torch.sort(te_cand, dim=1, descending=True)
            v_cand = v_cand / v_cand.sum(dim=1, keepdim=True)

            chunk_size = 12500
            costs_all = torch.empty(n_mc_per_iter, device=self.device)
            scales_all = torch.empty(n_mc_per_iter, device=self.device)

            for start in range(0, n_mc_per_iter, chunk_size):
                end = min(start + chunk_size, n_mc_per_iter)
                s_z0 = self.interpolate_dictionary_bilinear_gpu(d_grid_gpu, te_cand[start:end, 0], lne_cand[start:end, 0])
                i_rad = v_cand[start:end, 0][:, None, None] * s_z0

                for z in range(1, n_zones):
                    s_z = self.interpolate_dictionary_bilinear_gpu(d_grid_gpu, te_cand[start:end, z], lne_cand[start:end, z])
                    s_z_max = torch.clamp(s_z.amax(dim=2, keepdim=True), min=1e-12)
                    k_z = k_cand[start:end, z][:, None, None]
                    v_z = v_cand[start:end, z][:, None, None]
                    i_rad = i_rad * torch.exp(-k_z * (s_z / s_z_max)) + v_z * s_z

                i_pred = i_rad * r_gpu[None, None, :]
                num_scale = (weights_p[None, :, :] * y_family_gpu[None, :, :] * i_pred).sum(dim=(1, 2))
                den_scale = torch.clamp((weights_p[None, :, :] * (i_pred ** 2)).sum(dim=(1, 2)), min=1e-12)
                alpha_scale = num_scale / den_scale

                i_mean = (weights_p[None, :, :] * i_pred).sum(dim=2, keepdim=True) / weights_p.sum(dim=1, keepdim=True)[None, :, :]
                i_centered = i_pred - i_mean
                i_norm = torch.sqrt((weights_p[None, :, :] * (i_centered ** 2)).sum(dim=2, keepdim=True) + 1e-12)

                corr = (weights_p[None, :, :] * y_centered[None, :, :] * i_centered).sum(dim=2, keepdim=True) / (y_norm[None, :, :] * i_norm)
                f_cw = (1.0 - corr.squeeze(-1)).mean(dim=1)

                costs_all[start:end] = f_cw
                scales_all[start:end] = alpha_scale

            top_vals, top_idx = torch.topk(costs_all, k=100, largest=False)
            if float(top_vals[0]) < best_cost:
                best_cost = float(top_vals[0])
                win_i = top_idx[0]
                best_scale = float(scales_all[win_i])
                best_r2 = max(0.0, 1.0 - best_cost)
                best_rmse = float(torch.sqrt(torch.mean((y_family_gpu / torch.amax(y_family_gpu, dim=1, keepdim=True)) ** 2) * best_cost))
                best_params = {
                    "Te": te_cand[win_i].cpu().numpy(),
                    "Ne": (10.0 ** lne_cand[win_i]).cpu().numpy(),
                    "volumeFraction": v_cand[win_i].cpu().numpy(),
                    "kAbsorption": k_cand[win_i].cpu().numpy()
                }

            elite_te = te_cand[top_idx]
            elite_lne = lne_cand[top_idx]
            elite_v = v_cand[top_idx]
            elite_k = k_cand[top_idx]

            te_min = torch.clamp(elite_te.min(dim=0).values - 0.03, min=float(self.te_grid[0]))
            te_max = torch.clamp(elite_te.max(dim=0).values + 0.03, max=float(self.te_grid[-1]))
            lne_min = torch.clamp(elite_lne.min(dim=0).values - 0.05, min=float(self.log_ne_grid[0]))
            lne_max = torch.clamp(elite_lne.max(dim=0).values + 0.05, max=float(self.log_ne_grid[-1]))
            v_min = torch.clamp(elite_v.min(dim=0).values - 0.03, min=0.01)
            v_max = torch.clamp(elite_v.max(dim=0).values + 0.03, max=0.99)
            k_min = torch.clamp(elite_k.min(dim=0).values - 0.10, min=0.0)
            k_max = torch.clamp(elite_k.max(dim=0).values + 0.10, max=5.0)
            k_min[0] = 0.0
            k_max[0] = 0.0

        return {
            "bestParams": best_params,
            "scaleFactor": best_scale,
            "weightedCorrelationCost": best_cost,
            "rSquared": best_r2,
            "rmse": best_rmse
        }

    # -------------------------------------------------------------------------
    # STEP 5: Orchestrate Calibration & Upsert into conf/instrument_profile.json
    # -------------------------------------------------------------------------
    def run(self) -> None:
        families_dict = self.manifest["materialFamilies"]
        target_family = self.manifest.get("targetMaterialFamily")

        lam_meas = None
        y_all_list, sig_all_list, point_means_all, mat_meta = [], [], [], []
        total_shots_per_file = 40

        for family_name, mat_list in families_dict.items():
            for mat in mat_list:
                lam_m, y_m, sig_m, pt_means, n_shots = self.load_and_clean_material_csvs(mat["measuredCsvPaths"])
                if lam_meas is None:
                    lam_meas = lam_m
                total_shots_per_file = n_shots // max(len(mat["measuredCsvPaths"]), 1)
                y_all_list.append(y_m)
                sig_all_list.append(sig_m)
                point_means_all.append(pt_means)
                mat_meta.append((family_name, mat["materialName"], mat["nistGridCsvPaths"], mat.get("holdoutCsvPaths", [])))

        y_all = np.vstack(y_all_list)
        sig_all = np.vstack(sig_all_list)

        fwhm_g_meas, fwhm_l_meas = self.estimate_measured_voigt_fwhm(lam_meas, y_all[0])
        first_lam_nist, _ = self.load_nist_dictionary_for_material(mat_meta[0][2], psi_gpu=None)
        psi_gpu = self.build_gpu_voigt_transfer_matrix(lam_meas, first_lam_nist, fwhm_g_meas, fwhm_l_meas)

        d_all_gpu_list = []
        for _, _, nist_paths, _ in mat_meta:
            _, d_m_gpu = self.load_nist_dictionary_for_material(nist_paths, psi_gpu=psi_gpu)
            d_all_gpu_list.append(d_m_gpu)

        d_all_cpu = torch.stack(d_all_gpu_list, dim=0).cpu().numpy()

        res_mask = np.ones_like(lam_meas)
        for win in self.manifest.get("resonanceMaskWindowsNm", []):
            res_mask[(lam_meas >= win["minNm"]) & (lam_meas <= win["maxNm"])] = 0.0

        tramp_w = np.ones_like(lam_meas)
        for win in self.manifest.get("trampWindowsNm", []):
            tramp_w[(lam_meas >= win["minNm"]) & (lam_meas <= win["maxNm"])] = float(win["weight"])

        r_vec = self.solve_global_instrument_response(lam_meas, y_all, sig_all, d_all_cpu, res_mask)
        r_gpu = torch.tensor(r_vec, dtype=torch.float32, device=self.device)
        tramp_w_gpu = torch.tensor(tramp_w, dtype=torch.float32, device=self.device)

        # Load existing instrument_profile.json if present for non-destructive upsert
        out_path = Path(self.manifest["outputProfilePath"])
        if out_path.exists():
            with open(out_path, "r", encoding="utf-8") as f:
                profile_json = json.load(f)
        else:
            profile_json = {}

        existing_families = profile_json.get("materialFamilyProfiles", {})
        if not isinstance(existing_families, dict):
            existing_families = {}

        families_to_optimize = [target_family] if target_family in families_dict else list(families_dict.keys())
        n_zones = int(self.manifest.get("numZones", 2))
        r_nist_int = int(float(self.manifest.get("nistResolvingPower", 5000.0)))

        for family_name in families_to_optimize:
            fam_indices = [idx for idx, (f_name, _, _, _) in enumerate(mat_meta) if f_name == family_name]
            if not fam_indices:
                continue

            d_fam_gpu = torch.stack([d_all_gpu_list[i] for i in fam_indices], dim=0)
            y_fam_gpu = torch.tensor(y_all[fam_indices], dtype=torch.float32, device=self.device)
            sig_fam_gpu = torch.tensor(sig_all[fam_indices], dtype=torch.float32, device=self.device)

            opt_res = self.optimize_family_rte_gpu(
                d_fam_gpu, y_fam_gpu, sig_fam_gpu, r_gpu, tramp_w_gpu, n_zones=n_zones
            )

            # Spatial CSV Bootstrap across the CSV points for Te/Ne StdDev
            pt_te_samples, pt_lne_samples = [], []
            for i in fam_indices:
                d_single_gpu = d_all_gpu_list[i].unsqueeze(0)
                for pt_spec in point_means_all[i]:
                    y_pt_gpu = torch.tensor(pt_spec[None, :], dtype=torch.float32, device=self.device)
                    sig_pt_gpu = torch.tensor(sig_all[i][None, :], dtype=torch.float32, device=self.device)
                    pt_res = self.optimize_family_rte_gpu(
                        d_single_gpu, y_pt_gpu, sig_pt_gpu, r_gpu, tramp_w_gpu,
                        n_zones=n_zones, n_mc_per_iter=20000, n_iters=3
                    )
                    pt_te_samples.append(pt_res["bestParams"]["Te"])
                    pt_lne_samples.append(np.log10(pt_res["bestParams"]["Ne"]))

            te_std = np.clip(np.std(pt_te_samples, axis=0), 0.02, 0.15) if len(pt_te_samples) > 1 else np.full(n_zones, 0.05)
            lne_std = np.clip(np.std(pt_lne_samples, axis=0), 0.04, 0.25) if len(pt_lne_samples) > 1 else np.full(n_zones, 0.08)

            best_p = opt_res["bestParams"]
            zones_json = []
            for z in range(n_zones):
                zones_json.append({
                    "zoneIndex": z,
                    "Te": round(float(best_p["Te"][z]), 4),
                    "teStdDev": round(float(te_std[z]), 4),
                    "volumeFraction": round(float(best_p["volumeFraction"][z]), 4),
                    "Ne": float(f"{best_p['Ne'][z]:.4e}"),
                    "neLogStdDev": round(float(lne_std[z]), 4),
                    "kAbsorption": round(float(best_p["kAbsorption"][z]), 4)
                })

            holdout_map = {mat_meta[i][1]: mat_meta[i][3] for i in fam_indices if mat_meta[i][3]}
            cal_mat_names = [mat_meta[i][1] for i in fam_indices]

            # Upsert: update existing family entry or append new family entry in materialFamilyProfiles
            existing_families[family_name] = {
                "calibrationStats": {
                    "rSquared": round(float(opt_res["rSquared"]), 6),
                    "rmse": round(float(opt_res["rmse"]), 6),
                    "weightedCorrelationCost": round(float(opt_res["weightedCorrelationCost"]), 6),
                    "numCalibratedMaterials": len(fam_indices),
                    "calibratedMaterials": cal_mat_names
                },
                "plasmaZones": zones_json,
                "scaleFactor": round(float(opt_res["scaleFactor"]), 4),
                "resolution": r_nist_int,
                "materialFamily": family_name,
                "validationHoldoutCsvs": holdout_map
            }

        profile_json["baselineCorrectionParams"] = {
            "method": "morphological_opening",
            "structuringElementSize": self.struct_size
        }
        profile_json["instrumentName"] = self.manifest.get("instrumentName", "LSA_Calibrated_Instrument")
        profile_json["wavelengths"] = [round(float(w), 4) for w in lam_meas]
        profile_json["spectralResponse"] = [round(float(r), 6) for r in r_vec]
        profile_json["voigtProfile"] = {
            "nistResolvingPower": float(r_nist_int),
            "fwhmGaussianNm": [round(float(x), 5) for x in fwhm_g_meas],
            "fwhmLorentzianNm": [round(float(x), 5) for x in fwhm_l_meas]
        }
        profile_json["finotelloNoiseBeta"] = 0.095
        profile_json["materialFamilyProfiles"] = existing_families
        profile_json["numShots"] = total_shots_per_file

        out_path.parent.mkdir(parents=True, exist_ok=True)
        with open(out_path, "w", encoding="utf-8") as f:
            json.dump(profile_json, f, indent=4)

        print(f"[GPU Engine] Upserted family '{target_family}' into {out_path} "
              f"(Total families in profile: {len(existing_families)})")


if __name__ == "__main__":
    engine = GpuLibsCalibrationEngine(sys.argv[1])
    engine.run()
```

---

## 6. Updated `run_batch_calibration.py` Script

Update `run_batch_calibration.py` so that it reads `reference_compositions.json`, resolves each material's flat folder (`<dataset_dir>/<materialName>`), formats the composition string for `-c`, and passes `-mt <materialType>` and `-mg <materialName>` to `./calibrate.sh`:

```python
#!/usr/bin/env python3
"""
Iterative Batch Calibration Runner.
Reads reference_compositions.json and invokes ./calibrate.sh for each reference material.
Each run stages the material's 5 CSVs and 48-state NIST cache, then upserts the
material family's calibrated profile inside conf/instrument_profile.json.
"""

import json
import os
import subprocess
import sys

NUM_ZONES = 2
HOLDOUT_CSVS_PER_MATERIAL = 1

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
CODE_DIR = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))
PHD_ROOT = os.path.abspath(os.path.join(CODE_DIR, ".."))

DATASET_ROOT_DIR = os.path.join(
    PHD_ROOT,
    "Datasets", "Partner Data", "LSA",
    "Medals_Calibration_Samples_2025",
    "Medals_CalibrationSamples",
)
REFERENCE_COMPOSITIONS_JSON = os.path.join(DATASET_ROOT_DIR, "reference_compositions.json")
BIN_DIR = os.path.join(CODE_DIR, "LIBSDataCurator", "build", "release-package", "bin")


def main():
    if not os.path.isfile(REFERENCE_COMPOSITIONS_JSON):
        print(f"ERROR: JSON file not found: {REFERENCE_COMPOSITIONS_JSON}", file=sys.stderr)
        sys.exit(1)

    calibrate_script = os.path.join(BIN_DIR, "calibrate.sh")
    if not os.path.isfile(calibrate_script):
        print(f"ERROR: calibrate.sh not found in {BIN_DIR}", file=sys.stderr)
        sys.exit(1)

    with open(REFERENCE_COMPOSITIONS_JSON, "r", encoding="utf-8") as f:
        data = json.load(f)

    total = sum(len(mg["materials"]) for mg in data["materialCompositions"])
    print(f"Found {total} reference materials across {len(data['materialCompositions'])} material families.")

    run_count = 0
    for material_group in data["materialCompositions"]:
        material_type = material_group["materialType"].replace(" ", "_")

        for material in material_group["materials"]:
            material_name = str(material["materialName"]).strip()
            # Flat directory lookup: <DATASET_ROOT_DIR>/<materialName>
            material_dir = os.path.join(DATASET_ROOT_DIR, material_name)

            composition_str = ",".join(
                f"{k}-{v}" for k, v in material["composition"].items()
            )
            instrument_name = f"LSA_{material_type}_{NUM_ZONES}_zones"

            cmd = [
                "./calibrate.sh",
                "-i", material_dir,
                "-c", composition_str,
                "-mt", material_type,
                "-mg", material_name,
                "-n", instrument_name,
                "-z", str(NUM_ZONES),
                "-hc", str(HOLDOUT_CSVS_PER_MATERIAL),
            ]

            run_count += 1
            print(f"{'=' * 75}")
            print(f"[{run_count}/{total}] Material: {material_name} | Family: {material_type}")
            print(f"  Command: {' '.join(cmd)}")
            print(f"{'=' * 75}")

            result = subprocess.run(cmd, cwd=BIN_DIR)
            if result.returncode != 0:
                print(
                    f"\nWARNING: Calibration for {material_name} exited with code {result.returncode}\n",
                    file=sys.stderr,
                )

    print(f"\nCompleted {run_count}/{total} material calibration runs.")


if __name__ == "__main__":
    main()
```

---

## 7. Phased Implementation Requirements for Conductor (`plan.md` Blueprint)

When generating `plan.md`, the Anti-Gravity CLI Conductor agent should structure the implementation into the following 5 sequential, testable phases:

* **Phase 1: Cleanup of Deprecated Reporting & ALS Baseline Code**
  * Delete `conf/calibration_report_template.ipynb`, `conf/calibration_report_multi_material_template.ipynb`, and their duplicates in `src/main/resources/`.
  * Remove Papermill, nbconvert, and Pandoc methods from `PythonUtils.java` and `InstrumentProfileService.java`.
  * Update `BaselineCorrectionParams.java` to default to `method = "morphological_opening"` and `structuringElementSize = 25` with `@JsonIgnoreProperties(ignoreUnknown = true)`.
* **Phase 2: Model & CLI Argument Refactoring**
  * Update `PlasmaZone.java`, `MaterialFamilyProfile.java`, and `InstrumentProfile.java` to support `teStdDev`, `neLogStdDev`, `spectralResponse`, `voigtProfile`, and `validationHoldoutCsvs`.
  * Ensure CLI parser (`CmdlineParserUtil.java`, `LIBSDataGenConstants.java`, `UserInputConfig.java`) supports `-mt` (`--material-type`), `-mg` (`--material-grade`), and `-hc` (`--holdout-csvs`, default `1`) in calibration mode, and accepts a directory path for `-i`.
* **Phase 3: Incremental Manifest & Selenium 48-State NIST Grid Caching**
  * Refactor `InstrumentProfileController.java` and `InstrumentProfileService.java` to scan the `-i` directory for the 5 CSV files, split into 4 calibration CSVs and 1 holdout CSV, cache the 48-state $(T_e, N_e)$ NIST anchor grid via Selenium recalculation, and upsert the material entry into `temp/calibration_manifest.json`.
* **Phase 4: PyTorch CUDA Calibration Engine & Non-Destructive Profile Upsert**
  * Implement `scripts/gpu_calibration_engine.py` with Morphological Opening (`structuringElementSize = 25`), Differential Voigt Transfer Matrix $\boldsymbol{\Psi}$, Stage 1 NNLS + B-Spline response recovery, Stage 2 GPU 2D Bilinear Interpolation (`F.grid_sample`) + Contracting Hypercube Monte Carlo RTE optimization, Stage 3 spatial CSV bootstrap, and non-destructive upsert into `conf/instrument_profile.json`.
* **Phase 5: Forward Synthetic Pipeline Integration & Unit Tests**
  * Update `PlasmaTempSampler.java` for coupled $(T_e, N_e)$ Gaussian sampling, update `SpectrumUtils.combineNZones` + instrument response scaling, and update unit tests (`InstrumentProfileControllerTest`, `InstrumentProfileServiceTest`, `InstrumentProfileTest`) to verify directory ingestion and JSON upsert behavior.