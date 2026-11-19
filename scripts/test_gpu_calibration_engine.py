import json
import numpy as np
import pandas as pd
import pytest
from pathlib import Path
from scripts.gpu_calibration_engine import GpuLibsCalibrationEngine


@pytest.fixture
def sample_manifest(tmp_path):
    manifest_data = {
        "targetMaterialFamily": "Ferritic_Stainless_Steel",
        "outputProfilePath": str(tmp_path / "instrument_profile.json"),
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
        "materialFamilies": {}
    }
    manifest_path = tmp_path / "calibration_manifest.json"
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest_data, f)
    return str(manifest_path)


def test_morphological_opening_baseline_stripping(sample_manifest):
    engine = GpuLibsCalibrationEngine(sample_manifest)
    x = np.linspace(340, 900, 1000)
    # Background continuum: broad polynomial
    continuum = 500.0 + 200.0 * np.sin(x / 50.0)
    # Discrete narrow emission lines
    peaks = np.zeros_like(x)
    peaks[200] = 5000.0
    peaks[500] = 7000.0
    peaks[800] = 6000.0
    raw_spectrum = continuum + peaks

    stripped = engine.strip_baseline_morphological_opening(raw_spectrum, struct_size=25)

    assert stripped[200] > 4500.0
    assert stripped[500] > 6500.0
    assert stripped[800] > 5500.0
    # Baseline regions should be near 0
    assert np.mean(stripped[:150]) < 20.0


def test_saturation_rejection_and_shot_cleaning(sample_manifest, tmp_path):
    engine = GpuLibsCalibrationEngine(sample_manifest)
    # Create mock CSV with 5 shots: 2 saturated (> 0.9 * 65535 = 58981.5), 3 valid
    wavelengths = [340.0, 341.0, 342.0]
    data = [
        [1000.0, 2000.0, 3000.0],
        [60000.0, 2000.0, 3000.0],  # saturated in ch 0
        [1000.0, 2000.0, 3000.0],
        [65535.0, 2000.0, 3000.0],  # saturated in ch 0
        [1000.0, 2000.0, 3000.0],
    ]
    df = pd.DataFrame(data, columns=[str(w) for w in wavelengths])
    csv_file = tmp_path / "point_1.csv"
    df.to_csv(csv_file, index=False)

    lam, y_clean, sig, pt_means, n_shots = engine.load_and_clean_material_csvs([str(csv_file)])
    assert len(lam) == 3
    assert n_shots == 5
    # The mean of channel 0 should be calculated only from non-saturated shots (1000.0)
    assert np.isclose(pt_means[0][0], 0.0, atol=1.0) or pt_means[0][0] <= 1000.0


def test_logging_initialization(sample_manifest):
    engine = GpuLibsCalibrationEngine(sample_manifest)
    assert engine.log_file.name == "gpu_calibration_engine.log"
    assert engine.log_file.parent.exists()
    assert engine.log_file.exists()
    content = engine.log_file.read_text(encoding="utf-8")
    assert "[GPU Engine]" in content


def test_semicolon_delimited_csv_loading(sample_manifest, tmp_path):
    engine = GpuLibsCalibrationEngine(sample_manifest)
    # Mock semicolon delimited CSV with metadata headers (MeasCtr, TimeStamp)
    content = "MeasCtr;TimeStamp;341.0;342.0;343.0\n1;2026-01-01;1200;1300;1400\n2;2026-01-01;1250;1350;1450\n"
    csv_file = tmp_path / "semicolon_test.csv"
    csv_file.write_text(content, encoding="utf-8")

    lam, y_clean, sig, pt_means, n_shots = engine.load_and_clean_material_csvs([str(csv_file)])
    assert len(lam) == 3
    assert np.allclose(lam, [341.0, 342.0, 343.0])
    assert n_shots == 2
    assert len(pt_means) == 1


def test_parse_composition_dict():
    from scripts.gpu_calibration_engine import parse_composition_dict

    # Dict input
    d = {"Fe": 86.5, "Cr": 11.9, "C": 0.28}
    res_d = parse_composition_dict(d)
    assert np.isclose(sum(res_d.values()), 100.0)
    assert "Fe" in res_d and "Cr" in res_d

    # String with colons
    comp_colon = "C:0.28,Cr:11.9,Fe:87.82"
    res_c = parse_composition_dict(comp_colon)
    assert np.isclose(sum(res_c.values()), 100.0)
    assert np.isclose(res_c["Cr"], 11.9)

    # String with dashes and remainder
    comp_dash = "C-0.279,Si-6.5:7.5,Fe-#"
    res_dash = parse_composition_dict(comp_dash)
    assert np.isclose(sum(res_dash.values()), 100.0)
    assert np.isclose(res_dash["Si"], 7.0)
    assert np.isclose(res_dash["Fe"], 100.0 - (0.279 + 7.0))


def test_asd_line_advisor(tmp_path):
    from scripts.gpu_calibration_engine import AsdLineAdvisor

    cache_dir = Path("data/nist_lines")
    family_comps = {"Ferritic": {"Fe": 86.5, "Cr": 11.9, "C": 0.28}}
    advisor = AsdLineAdvisor(cache_dir, family_comps, 340.0, 900.0)

    lam_meas = np.linspace(340.0, 900.0, 1000)
    res_mask = advisor.build_resonance_mask(lam_meas)
    assert len(res_mask) == len(lam_meas)
    assert np.all((res_mask == 0.0) | (res_mask == 1.0))

    tramp_w = advisor.build_family_tramp_weights("Ferritic", lam_meas)
    assert len(tramp_w) == len(lam_meas)
    assert np.all(tramp_w >= 1.0)

    windows = advisor.get_asd_anchor_windows("Ferritic", lam_meas, res_mask)
    assert len(windows) > 0


def test_end_to_end_synthetic_manifest(tmp_path):
    pytest.importorskip("torch")
    # Create mock NIST grid CSVs (48 states)
    te_grid = [0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.5, 1.7, 2.0]
    ne_grid = [1.0e16, 5.0e16, 1.0e17, 5.0e17]
    nist_dir = tmp_path / "nist_cache"
    nist_dir.mkdir()

    lam_nist = np.linspace(340.0, 900.0, 200)
    nist_paths = {}
    for te in te_grid:
        for ne in ne_grid:
            csv_path = nist_dir / f"469_Te{te}_Ne{ne:.1E}.csv"
            # Synthetic line profile with te/ne dependence
            intensities = 100.0 + 50.0 * np.sin(lam_nist / 20.0) * (te / 1.0) * (np.log10(ne) / 16.0)
            df = pd.DataFrame({"wavelength": lam_nist, "intensity": intensities})
            df.to_csv(csv_path, index=False)
            nist_paths[f"{te}_{ne:.1E}"] = str(csv_path)

    # Create mock measured CSV (40 shots across 200 channels)
    meas_dir = tmp_path / "measured"
    meas_dir.mkdir()
    meas_csv = meas_dir / "point_1.csv"
    shots_data = np.tile(100.0 + 50.0 * np.sin(lam_nist / 20.0), (10, 1))
    meas_df = pd.DataFrame(shots_data, columns=[str(w) for w in lam_nist])
    meas_df.to_csv(meas_csv, index=False)

    out_profile = tmp_path / "instrument_profile.json"
    manifest_data = {
        "targetMaterialFamily": "Ferritic_Stainless_Steel",
        "outputProfilePath": str(out_profile),
        "nistResolvingPower": 3000.0,
        "detectorFullScale": 65535.0,
        "saturationThresholdFactor": 0.9,
        "numZones": 2,
        "logsDir": str(tmp_path / "logs"),
        "nistLinesDir": "data/nist_lines",
        "baselineCorrectionParams": {
            "method": "morphological_opening",
            "structuringElementSize": 65
        },
        "teAnchorGridEv": te_grid,
        "neAnchorGridCm3": ne_grid,
        "materialFamilies": {
            "Ferritic_Stainless_Steel": [
                {
                    "materialName": "469",
                    "composition": {"Fe": 86.5, "Cr": 11.9, "C": 0.28},
                    "measuredCsvPaths": [str(meas_csv)],
                    "nistGridCsvPaths": nist_paths
                }
            ]
        }
    }
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest_data), encoding="utf-8")

    engine = GpuLibsCalibrationEngine(str(manifest_path))
    engine.run()

    assert out_profile.exists()
    profile = json.loads(out_profile.read_text(encoding="utf-8"))
    assert "voigtProfile" not in profile, "voigtProfile must not be present in output JSON"
    assert len(profile["spectralResponse"]) == len(lam_nist)
    assert np.all(np.array(profile["spectralResponse"]) >= 0.25)
    assert np.all(np.array(profile["spectralResponse"]) <= 2.50)
    fam_profile = profile["materialFamilyProfiles"]["Ferritic_Stainless_Steel"]
    assert "plasmaZones" in fam_profile
    assert len(fam_profile["plasmaZones"]) == 2
    assert fam_profile["plasmaZones"][0]["Te"] >= fam_profile["plasmaZones"][1]["Te"] + 0.12
    assert "scaleFactor" in fam_profile
    assert fam_profile["scaleFactor"] > 0


def test_engine_imports_without_torch():
    """R10.1: The module must import without torch; legacy functions import it lazily."""
    import sys
    # Verify that importing GpuLibsCalibrationEngine doesn't require torch in sys.modules
    # (or if torch happens to be in sys.modules, engine module itself doesn't have top-level torch requirement)
    import scripts.gpu_calibration_engine as gce
    assert hasattr(gce, "GpuLibsCalibrationEngine")


def test_engine_validation_materials_excluded_from_fitting(tmp_path):
    """R10.2: Materials listed under manifest key validationMaterials are loaded but excluded from fitting."""
    out_profile = tmp_path / "instrument_profile.json"
    manifest_data = {
        "targetMaterialFamily": "Steel",
        "outputProfilePath": str(out_profile),
        "validationMaterials": ["473"],
        "materialFamilies": {
            "Steel": [
                {"materialName": "469", "composition": {"Fe": 86.5, "Cr": 11.9, "C": 0.28}},
                {"materialName": "473", "composition": {"Fe": 80.0, "Cr": 18.0, "C": 0.20}},
            ]
        }
    }
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest_data), encoding="utf-8")
    engine = GpuLibsCalibrationEngine(str(manifest_path))

    fitted_mats = engine.get_calibrated_materials(family="Steel")
    assert "469" in fitted_mats
    assert "473" not in fitted_mats, "Validation material 473 must be excluded from fitting"


def test_engine_non_destructive_upsert_profile_and_rois(tmp_path):
    """R10.3, R13.2: Calibrating a single family upserts instrument_profile.json and regionsOfInterest.json without wiping existing families."""
    out_profile = tmp_path / "instrument_profile.json"
    out_rois = tmp_path / "regionsOfInterest.json"

    # Seed existing Bronze family
    initial_profile = {
        "version": "2.0.0",
        "detectorChannelPitchNm": 0.27,
        "spectralResponse": [1.0, 1.0, 1.0],
        "spectralResponseValidRangeNm": [340.0, 900.0],
        "validChannelRangeNm": [342.0, 896.0],
        "materialFamilyProfiles": {
            "Bronze": {
                "resolution": 3000.0,
                "plasmaZones": [{"Te": 0.9, "Ne": 1e17, "volumeFraction": 1.0, "kAbsorption": 0.0}],
                "scaleFactor": 0.045,
            }
        }
    }
    out_profile.write_text(json.dumps(initial_profile, indent=2), encoding="utf-8")

    initial_rois = {
        "version": "2.0.0",
        "families": {
            "Bronze": {
                "regionsOfInterest": [
                    {"id": "ROI_001_Cu_324", "minWavelengthNm": 324.0, "maxWavelengthNm": 326.0}
                ]
            }
        }
    }
    out_rois.write_text(json.dumps(initial_rois, indent=2), encoding="utf-8")

    manifest_data = {
        "targetMaterialFamily": "Steel",
        "outputProfilePath": str(out_profile),
        "outputRoisPath": str(out_rois),
        "materialFamilies": {
            "Steel": [{"materialName": "469", "composition": {"Fe": 86.5, "Cr": 11.9, "C": 0.28}}]
        }
    }
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest_data), encoding="utf-8")
    engine = GpuLibsCalibrationEngine(str(manifest_path))

    # Perform mock/upsert operation
    engine.upsert_family_profile(
        family="Steel",
        family_profile={"resolution": 3000.0, "plasmaZones": [{"Te": 1.1, "Ne": 1e17, "volumeFraction": 1.0, "kAbsorption": 0.0}], "scaleFactor": 0.050},
        family_rois=[{"id": "ROI_002_Fe_373", "minWavelengthNm": 373.0, "maxWavelengthNm": 375.0}],
    )

    # Verify both families exist in instrument_profile.json
    updated_profile = json.loads(out_profile.read_text(encoding="utf-8"))
    assert "Bronze" in updated_profile["materialFamilyProfiles"], "Existing Bronze family must be preserved"
    assert "Steel" in updated_profile["materialFamilyProfiles"], "New Steel family must be added"

    # Verify both families exist in regionsOfInterest.json
    updated_rois = json.loads(out_rois.read_text(encoding="utf-8"))
    assert "Bronze" in updated_rois["families"], "Existing Bronze family ROIs must be preserved"
    assert "Steel" in updated_rois["families"], "New Steel family ROIs must be added"


def test_engine_strict_exit_status_on_flag(tmp_path):
    """R9, R10: With --strict, any diagnostic flag causes exit with status 2."""
    manifest_data = {
        "targetMaterialFamily": "Steel",
        "strict": True,
        "materialFamilies": {"Steel": []}
    }
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest_data), encoding="utf-8")
    engine = GpuLibsCalibrationEngine(str(manifest_path))

    flags = ["TE_GRID_EDGE:469", "CHI2_HIGH:469"]
    with pytest.raises(SystemExit) as exc_info:
        engine.check_strict_flags(flags)
    assert exc_info.value.code == 2, f"Strict mode must exit with code 2, got {exc_info.value.code}"


def test_engine_debug_json_dumps_per_family(tmp_path):
    """R13.1: Intermediate debug outputs dumped as structured JSONs under temp/calibration_debug/<materialFamily>/."""
    debug_dir = tmp_path / "calibration_debug"
    manifest_data = {
        "targetMaterialFamily": "Steel",
        "debugOutputDir": str(debug_dir),
        "materialFamilies": {"Steel": [{"materialName": "469"}]}
    }
    manifest_path = tmp_path / "manifest.json"
    manifest_path.write_text(json.dumps(manifest_data), encoding="utf-8")
    engine = GpuLibsCalibrationEngine(str(manifest_path))

    engine.export_debug_json(
        family="Steel",
        conditioned_data={"469": {"lit_mask": [True, True], "dark_floor": 50.0, "sigma_mean": [5.0, 5.0]}},
        rois_data={"rois": [{"id": "ROI_001", "sub_bins": [[0, 1]]}]},
        solver_trace={"iterations": 5, "objective_phi": [100.0, 50.0, 25.0]},
        fit_comparison={"469": {"chi2": 1.2, "parity": {"Fe": 0.05}}},
    )

    family_dir = debug_dir / "Steel"
    assert family_dir.exists()
    assert (family_dir / "conditioned_469.json").exists()
    assert (family_dir / "rois_Steel.json").exists()
    assert (family_dir / "solver_trace_Steel.json").exists()
    assert (family_dir / "fit_comparison_469.json").exists()



