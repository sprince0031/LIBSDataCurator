"""Unit tests for calibration inspector interactive notebook and visualization modules (R13.3, AC13)."""

import json
from pathlib import Path
import numpy as np
import pytest

from scripts.calibration_inspector import (
    list_families,
    list_materials_for_family,
    load_debug_data,
    compute_bspline_basis,
    compute_response_from_beta,
    apply_morphological_baseline_correction,
    plot_spectral_overlay,
    plot_solver_convergence,
    plot_element_parity,
    launch_dashboard,
    create_interactive_response_tuner,
)


@pytest.fixture
def sample_debug_dir(tmp_path):
    fam_dir = tmp_path / "Ferritic_Stainless_Steel"
    fam_dir.mkdir(parents=True)

    cond_data = {
        "wavelengths": [350.0, 360.0, 370.0, 380.0, 390.0],
        "mean_spectrum": [100.0, 150.0, 200.0, 250.0, 180.0],
        "sigma_mean": [5.0, 6.0, 7.0, 8.0, 6.0],
        "dark_floor": 45.0,
        "lit_mask": [True, True, True, True, True],
    }
    (fam_dir / "conditioned_469.json").write_text(json.dumps(cond_data), encoding="utf-8")

    rois_data = {
        "rois": [
            {
                "roiId": "ROI_001_Fe",
                "minNm": 355.0,
                "maxNm": 365.0,
                "channelIndices": [1],
                "subBins": [[1]],
                "dominantElement": "Fe",
                "selfAbsorptionProne": False,
                "usedInResponseFit": True,
                "modelLines": [
                    {"species": "Fe I", "wlNm": 358.12, "EiEv": 0.0, "EkEv": 3.46}
                ],
            },
            {
                "roiId": "ROI_002_Cr",
                "minNm": 375.0,
                "maxNm": 385.0,
                "channelIndices": [3],
                "subBins": [[3]],
                "dominantElement": "Cr",
                "selfAbsorptionProne": True,
                "usedInResponseFit": False,
                "modelLines": [
                    {"species": "Cr I", "wlNm": 379.50, "EiEv": 0.96, "EkEv": 4.22}
                ],
            },
        ]
    }
    (fam_dir / "rois_Ferritic_Stainless_Steel.json").write_text(json.dumps(rois_data), encoding="utf-8")

    trace_data = {
        "iterations": 3,
        "objective_phi": [150.0, 75.0, 32.5],
        "beta": [[0.0, 0.0, 0.0, 0.0, 0.0], [0.1, -0.05, 0.02, 0.01, -0.01], [0.12, -0.06, 0.025, 0.01, -0.02]],
    }
    (fam_dir / "solver_trace_Ferritic_Stainless_Steel.json").write_text(json.dumps(trace_data), encoding="utf-8")

    fit_data = {
        "materialName": "469",
        "synthetic_spectrum": [98.0, 152.0, 195.0, 248.0, 185.0],
        "elementParity": {"Fe": 0.02, "Cr": -0.04, "Mn": 0.12, "Ni": -0.35},
    }
    (fam_dir / "fit_comparison_469.json").write_text(json.dumps(fit_data), encoding="utf-8")

    return tmp_path


def test_notebook_file_exists_and_is_valid():
    """AC13: notebooks/calibration_inspector.ipynb exists and is valid nbformat v4."""
    nb_path = Path("notebooks/calibration_inspector.ipynb")
    assert nb_path.exists(), "notebooks/calibration_inspector.ipynb must exist"

    nb_json = json.loads(nb_path.read_text(encoding="utf-8"))
    assert nb_json.get("nbformat") == 4
    assert "cells" in nb_json
    assert len(nb_json["cells"]) >= 4

    # Check for expected interactive widgets and plotly invocations
    all_code = "".join(
        "".join(c.get("source", []))
        for c in nb_json["cells"]
        if c.get("cell_type") == "code"
    )
    assert "launch_dashboard" in all_code
    assert "plot_spectral_overlay" in all_code
    assert "plot_solver_convergence" in all_code
    assert "plot_element_parity" in all_code


def test_list_families_and_materials(sample_debug_dir):
    """Debug dir discovery lists families and materials accurately."""
    fams = list_families(sample_debug_dir)
    assert "Ferritic_Stainless_Steel" in fams

    mats = list_materials_for_family("Ferritic_Stainless_Steel", sample_debug_dir)
    assert mats == ["469"]


def test_load_debug_data(sample_debug_dir):
    """Load JSON debug dumps into dictionaries."""
    cond, rois, trace, fit = load_debug_data("Ferritic_Stainless_Steel", "469", sample_debug_dir)
    assert "mean_spectrum" in cond
    assert len(rois.get("rois", [])) == 2
    assert trace.get("iterations") == 3
    assert "elementParity" in fit


def test_plot_spectral_overlay(sample_debug_dir):
    """Plotly spectral overlay builds figure with measured, synthetic, and ROI shapes."""
    cond, rois, trace, fit = load_debug_data("Ferritic_Stainless_Steel", "469", sample_debug_dir)
    fig = plot_spectral_overlay(cond, rois, fit)

    assert fig is not None
    trace_names = [t.name for t in fig.data]
    assert "Measured" in trace_names
    assert "Synthetic Fit" in trace_names
    assert len(fig.layout.shapes) == 2


def test_plot_solver_convergence(sample_debug_dir):
    """Plotly convergence trace creates subplots for Phi and beta."""
    _, _, trace, _ = load_debug_data("Ferritic_Stainless_Steel", "469", sample_debug_dir)
    fig = plot_solver_convergence(trace)

    assert fig is not None
    trace_names = [t.name for t in fig.data]
    assert "Φ (Objective)" in trace_names
    assert any("β" in name for name in trace_names)


def test_plot_element_parity(sample_debug_dir):
    """Plotly element parity creates bar chart with reference threshold lines."""
    _, _, _, fit = load_debug_data("Ferritic_Stainless_Steel", "469", sample_debug_dir)
    fig = plot_element_parity(fit)

    assert fig is not None
    assert len(fig.data) == 1
    bar = fig.data[0]
    assert set(bar.x) == {"Fe", "Cr", "Mn", "Ni"}
    ni_idx = list(bar.x).index("Ni")
    assert bar.marker.color[ni_idx] == "#d62728"


def test_compute_bspline_basis_and_response(sample_debug_dir):
    """B-spline basis evaluation and response curve calculation."""
    cond, rois, trace, _ = load_debug_data("Ferritic_Stainless_Steel", "469", sample_debug_dir)
    wls = np.array(cond["wavelengths"])
    B, span = compute_bspline_basis(wls, rois, k_coeffs=5)
    assert B.shape == (len(wls), 5)
    assert span[0] <= span[1]

    beta = [0.1, -0.05, 0.02, 0.01, -0.02]
    resp = compute_response_from_beta(wls, rois, beta)
    assert len(resp) == len(wls)
    assert np.all(resp > 0.0)


def test_plot_spectral_overlay_with_response_curve(sample_debug_dir):
    """Spectral overlay supports response curve trace on secondary y-axis."""
    cond, rois, trace, fit = load_debug_data("Ferritic_Stainless_Steel", "469", sample_debug_dir)
    wls = np.array(cond["wavelengths"])
    resp = [1.0] * len(wls)
    fig = plot_spectral_overlay(cond, rois, fit, response_curve=resp)

    trace_names = [t.name for t in fig.data]
    assert "Response R(λ)" in trace_names
    resp_trace = next(t for t in fig.data if t.name == "Response R(λ)")
    assert resp_trace.yaxis == "y2"
    assert "yaxis2" in fig.layout


def test_create_interactive_response_tuner(sample_debug_dir):
    """Interactive response tuner builds control panel and responds to slider events."""
    panel = create_interactive_response_tuner("Ferritic_Stainless_Steel", "469", sample_debug_dir)
    assert panel is not None
    assert hasattr(panel, "children")


def test_apply_morphological_baseline_correction():
    """Baseline stripping with morphological opening removes continuum background while preserving sharp peaks."""
    x = np.linspace(350, 450, 100)
    raw = 300.0 + 50.0 * np.sin(x / 20.0)
    raw[50] += 5000.0  # Sharp emission peak

    clean, baseline = apply_morphological_baseline_correction(raw, struct_size=25)
    assert len(clean) == len(raw)
    assert np.all(clean >= 0.0)
    assert np.mean(clean[:40]) < 20.0
    assert clean[50] > 4500.0
    assert np.mean(baseline) > 200.0

