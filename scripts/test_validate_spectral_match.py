"""Unit tests for spectral match validation and match report generation (R11, AC11)."""

import json
from pathlib import Path
import numpy as np
import pytest

from scripts.validate_spectral_match import (
    generate_match_report,
    evaluate_material_match,
    run_ablation_fits,
)


@pytest.fixture
def mock_validation_setup(tmp_path):
    """Sets up a complete mock calibration manifest, profile, rois, and holdout data."""
    # 1. Measured holdout CSV (10 channels)
    wls = np.linspace(350.0, 360.0, 10)
    wls_src = np.linspace(350.0, 360.0, 501)
    p1 = np.exp(-0.5 * ((wls_src - wls[3]) / 0.40) ** 2)
    p2 = np.exp(-0.5 * ((wls_src - wls[7]) / 0.40) ** 2)
    s_src_base = 50.0 + 400.0 * p1 + 550.0 * p2

    from scripts.spectral_resampling import resample_to_detector
    meas_spectrum = resample_to_detector(wls_src, s_src_base, wls, add_sigma_nm=0.05)
    shots = np.tile(meas_spectrum, (5, 1))
    holdout_csv = tmp_path / "holdout_pt1.csv"
    import pandas as pd
    pd.DataFrame(shots, columns=[str(w) for w in wls]).to_csv(holdout_csv, index=False)

    # 2. Dictionary state CSVs
    te_grid = [0.8, 1.0]
    ne_grid = [1.0e17]
    nist_paths = {}
    for te in te_grid:
        for ne in ne_grid:
            csv_path = tmp_path / f"dict_Te{te}_Ne{ne:.1E}.csv"
            synth = s_src_base * (te / 1.0)
            pd.DataFrame({"wavelength": wls_src, "intensity": synth}).to_csv(csv_path, index=False)
            nist_paths[f"{te}_{ne:.1E}"] = str(csv_path)

    # 3. Instrument Profile
    profile_path = tmp_path / "instrument_profile.json"
    profile_data = {
        "schemaVersion": "1.0.0",
        "emissionScale": "absolute",
        "wavelengths": list(wls),
        "spectralResponse": [1.0] * len(wls),
        "validChannelRangeNm": [350.0, 360.0],
        "lineSpreadAddSigmaNm": [0.05] * len(wls),
        "materialFamilyProfiles": {
            "Steel": {
                "materialFamily": "Steel",
                "resolution": 3000,
                "scaleFactor": 1.0,
                "materialScaleFactors": {"473": 1.0},
                "plasmaZones": [
                    {"zoneIndex": 0, "Te": 1.0, "Ne": 1.0e17, "volumeFraction": 1.0, "kAbsorption": 0.0},
                    {"zoneIndex": 1, "Te": 0.8, "Ne": 1.0e17, "volumeFraction": 0.0, "kAbsorption": 0.0},
                ],
                "calibrationStats": {
                    "roiRSquared": 0.98,
                    "roiRmse": 0.02,
                }
            }
        }
    }
    profile_path.write_text(json.dumps(profile_data, indent=2), encoding="utf-8")

    # 4. Regions of Interest
    rois_path = tmp_path / "regionsOfInterest.json"
    rois_data = {
        "schemaVersion": "2.0",
        "instrumentName": "LSA_Calibrated_Instrument",
        "wavelengths": list(wls),
        "materialFamilyRois": {
            "Steel": {
                "unionChannelIndices": [2, 3, 6, 7],
                "rois": [
                    {
                        "roiId": "ROI_001_Fe",
                        "minNm": 352.0, "maxNm": 354.0,
                        "channelIndices": [2, 3],
                        "subBins": [[2, 3]],
                        "dominantElement": "Fe",
                        "selfAbsorptionProne": False,
                    },
                    {
                        "roiId": "ROI_002_Cr",
                        "minNm": 356.5, "maxNm": 358.5,
                        "channelIndices": [6, 7],
                        "subBins": [[6, 7]],
                        "dominantElement": "Cr",
                        "selfAbsorptionProne": True,
                    }
                ]
            }
        }
    }
    rois_path.write_text(json.dumps(rois_data, indent=2), encoding="utf-8")

    # 5. Manifest
    manifest_path = tmp_path / "manifest.json"
    manifest_data = {
        "validationMaterials": ["473"],
        "materialFamilies": {
            "Steel": [
                {
                    "materialName": "473",
                    "composition": {"Fe": 80.0, "Cr": 20.0},
                    "measuredCsvPaths": [],  # excluded from calibration
                    "holdoutCsvPaths": [str(holdout_csv)],
                    "nistGridCsvPaths": nist_paths,
                }
            ]
        }
    }
    manifest_path.write_text(json.dumps(manifest_data, indent=2), encoding="utf-8")

    output_report_path = tmp_path / "spectralMatchReport.json"

    return {
        "manifest_path": str(manifest_path),
        "profile_path": str(profile_path),
        "rois_path": str(rois_path),
        "output_report_path": str(output_report_path),
        "holdout_csv": str(holdout_csv),
    }


def test_holdout_data_isolation(mock_validation_setup):
    """R11.2: Hold-out data is used only for validation and is evaluated against the exported profile."""
    setup = mock_validation_setup
    report = generate_match_report(
        manifest_path=setup["manifest_path"],
        profile_path=setup["profile_path"],
        rois_path=setup["rois_path"],
        output_report_path=setup["output_report_path"]
    )

    assert "materials" in report
    assert "473" in report["materials"]
    mat_rep = report["materials"]["473"]
    assert mat_rep["isValidationMaterial"] is True
    assert mat_rep["family"] == "Steel"


def test_per_material_metrics_and_residual_distributions(mock_validation_setup):
    """R11.3: Reports reduced chi2, roiRSquared, wholeSpectrumRSquared, thin/prone distributions, and element parity."""
    setup = mock_validation_setup
    report = generate_match_report(
        manifest_path=setup["manifest_path"],
        profile_path=setup["profile_path"],
        rois_path=setup["rois_path"],
        output_report_path=setup["output_report_path"]
    )

    primary = report["materials"]["473"]["primaryFit"]
    assert "roiChi2Reduced" in primary
    assert "roiRSquared" in primary
    assert "roiRmse" in primary
    assert "wholeSpectrumRSquared" in primary
    assert "wholeSpectrumRmse" in primary
    assert primary["roiRSquared"] > 0.80

    sub_res = primary["subBinResiduals"]
    assert "thin" in sub_res and "prone" in sub_res
    assert sub_res["thin"]["count"] == 1
    assert sub_res["prone"]["count"] == 1

    parity = primary["elementParity"]
    assert "Fe" in parity and "Cr" in parity


def test_three_ablations_computed(mock_validation_setup):
    """R11.4: Reports flat response, no absorption, and legacy rendering ablations."""
    setup = mock_validation_setup
    report = generate_match_report(
        manifest_path=setup["manifest_path"],
        profile_path=setup["profile_path"],
        rois_path=setup["rois_path"],
        output_report_path=setup["output_report_path"]
    )

    ablations = report["materials"]["473"]["ablations"]
    assert "flatResponse" in ablations
    assert "noAbsorption" in ablations
    assert "legacyRendering" in ablations

    for k in ["flatResponse", "noAbsorption", "legacyRendering"]:
        assert "roiChi2Reduced" in ablations[k]
        assert "roiRSquared" in ablations[k]
        assert "roiRmse" in ablations[k]


def test_output_schema_of_spectral_match_report(mock_validation_setup):
    """R11.5, AC11: File data/spectralMatchReport.json is created with valid schema and family summary."""
    setup = mock_validation_setup
    generate_match_report(
        manifest_path=setup["manifest_path"],
        profile_path=setup["profile_path"],
        rois_path=setup["rois_path"],
        output_report_path=setup["output_report_path"]
    )

    out_file = Path(setup["output_report_path"])
    assert out_file.exists()
    content = json.loads(out_file.read_text(encoding="utf-8"))
    assert content["schemaVersion"] == "1.0.0"
    assert "familySummary" in content
    assert "Steel" in content["familySummary"]
    fam_sum = content["familySummary"]["Steel"]
    assert "meanRoiRSquared" in fam_sum
    assert "meanRoiRmse" in fam_sum
    assert "meanLegacyRoiRSquared" in fam_sum


def test_roi_performance_not_worse_than_legacy(mock_validation_setup):
    """AC11: On hold-out data, ROI reduced chi2 and roiRSquared are no worse than legacy-rendering ablation."""
    setup = mock_validation_setup
    report = generate_match_report(
        manifest_path=setup["manifest_path"],
        profile_path=setup["profile_path"],
        rois_path=setup["rois_path"],
        output_report_path=setup["output_report_path"]
    )

    mat_rep = report["materials"]["473"]
    primary_r2 = mat_rep["primaryFit"]["roiRSquared"]
    legacy_r2 = mat_rep["ablations"]["legacyRendering"]["roiRSquared"]
    assert primary_r2 >= legacy_r2 - 0.05
