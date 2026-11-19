"""Generate notebooks/calibration_inspector.ipynb."""

import json
from pathlib import Path

notebook = {
    "cells": [
        {
            "cell_type": "markdown",
            "metadata": {},
            "source": [
                "# LIBS Calibration Diagnostic Inspector (R13.3, AC13)\n",
                "Interactive diagnostic tool for exploring intermediate calibration dumps under `temp/calibration_debug/<materialFamily>/`.\n",
                "\n",
                "### Features:\n",
                "- **Family & Material Selectors:** Interactive dropdowns to toggle between calibrated families and reference materials.\n",
                "- **Spectral Fit Viewer:** Plotly zoom/pan overlay of measured vs. synthetic spectra with ROI window boundaries, $1\\sigma$ measurement ribbon, and line species annotations.\n",
                "- **Solver Convergence:** Evolution of objective function $\\Phi$ and B-spline response coefficients $\\beta$ across alternating iterations.\n",
                "- **Element Parity Chart:** Bar chart of $\\log_{10}(\\text{synthetic}/\\text{measured})$ across certified elements with bias threshold reference bands ($\\pm 0.10$, $\\pm 0.30$)."
            ]
        },
        {
            "cell_type": "code",
            "execution_count": None,
            "metadata": {},
            "outputs": [],
            "source": [
                "# Initialize environment and dependencies\n",
                "import sys\n",
                "from pathlib import Path\n",
                "\n",
                "# Ensure scripts directory is on PATH\n",
                "repo_root = Path.cwd().parent if Path.cwd().name == \"notebooks\" else Path.cwd()\n",
                "if str(repo_root) not in sys.path:\n",
                "    sys.path.insert(0, str(repo_root))\n",
                "\n",
                "from scripts.calibration_inspector import (\n",
                "    launch_dashboard,\n",
                "    plot_spectral_overlay,\n",
                "    plot_solver_convergence,\n",
                "    plot_element_parity,\n",
                "    load_debug_data,\n",
                "    list_families,\n",
                "    list_materials_for_family\n",
                ")\n",
                "print(\"Calibration Inspector loaded successfully!\")"
            ]
        },
        {
            "cell_type": "code",
            "execution_count": None,
            "metadata": {},
            "outputs": [],
            "source": [
                "# Launch Interactive Dashboard\n",
                "launch_dashboard()"
            ]
        },
        {
            "cell_type": "markdown",
            "metadata": {},
            "source": [
                "## Manual Inspection & Custom Visualization\n",
                "You can also extract and customize individual plots directly using the helper functions below."
            ]
        },
        {
            "cell_type": "code",
            "execution_count": None,
            "metadata": {},
            "outputs": [],
            "source": [
                "# Example: Manually load and display a specific family and material\n",
                "families = list_families()\n",
                "if families:\n",
                "    fam = families[0]\n",
                "    mats = list_materials_for_family(fam)\n",
                "    if mats:\n",
                "        mat = mats[0]\n",
                "        cond_data, rois_data, trace_data, fit_data = load_debug_data(fam, mat)\n",
                "        \n",
                "        fig_spec = plot_spectral_overlay(cond_data, rois_data, fit_data, title=f\"Spectrum: {fam} / {mat}\")\n",
                "        fig_spec.show()\n",
                "        \n",
                "        fig_conv = plot_solver_convergence(trace_data, title=f\"Solver Convergence: {fam}\")\n",
                "        fig_conv.show()\n",
                "        \n",
                "        fig_par = plot_element_parity(fit_data, title=f\"Element Parity: {fam} / {mat}\")\n",
                "        fig_par.show()"
            ]
        }
    ],
    "metadata": {
        "language_info": {
            "name": "python",
            "version": "3.12"
        },
        "kernelspec": {
            "display_name": "Python 3",
            "language": "python",
            "name": "python3"
        }
    },
    "nbformat": 4,
    "nbformat_minor": 5
}

out_path = Path("notebooks/calibration_inspector.ipynb")
out_path.parent.mkdir(parents=True, exist_ok=True)
out_path.write_text(json.dumps(notebook, indent=2), encoding="utf-8")
print(f"Generated {out_path.resolve()}")
