"""Interactive diagnostic inspector for LIBS calibration debug dumps (R13.3, AC13).

Provides Plotly interactive figures and ipywidgets selectors for inspecting:
- Measured vs. synthetic spectrum overlays with ROI windows and line tooltips
- Solver convergence traces (objective Phi and spline beta)
- Element parity log-ratios (log10(synthetic/measured))
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Any

import numpy as np
import plotly.graph_objects as go
from plotly.subplots import make_subplots

try:
    import ipywidgets as widgets
    from IPython.display import display, clear_output
except ImportError:
    widgets = None
    display = None
    clear_output = None


DEFAULT_DEBUG_DIR = Path("temp/calibration_debug")


def find_debug_dir(base_dir: Optional[Path] = None) -> Path:
    """Resolve debug directory, checking relative locations."""
    if base_dir and base_dir.exists():
        return base_dir
    candidates = [
        DEFAULT_DEBUG_DIR,
        Path("../temp/calibration_debug"),
        Path.cwd() / "temp" / "calibration_debug",
    ]
    for c in candidates:
        if c.exists():
            return c
    return DEFAULT_DEBUG_DIR


def list_families(debug_dir: Optional[Path] = None) -> List[str]:
    """List material families with debug data available."""
    d = find_debug_dir(debug_dir)
    if not d.exists():
        return []
    return sorted([p.name for p in d.iterdir() if p.is_dir()])


def list_materials_for_family(family: str, debug_dir: Optional[Path] = None) -> List[str]:
    """List materials belonging to family with debug data available."""
    d = find_debug_dir(debug_dir) / family
    if not d.exists():
        return []
    materials = []
    for p in d.glob("conditioned_*.json"):
        mat_name = p.stem.replace("conditioned_", "")
        materials.append(mat_name)
    return sorted(materials)


def load_debug_data(
    family: str,
    material: str,
    debug_dir: Optional[Path] = None
) -> Tuple[Dict[str, Any], Dict[str, Any], Dict[str, Any], Dict[str, Any]]:
    """Load conditioned data, ROIs data, solver trace, and fit comparison for family & material."""
    fam_dir = find_debug_dir(debug_dir) / family

    cond_path = fam_dir / f"conditioned_{material}.json"
    cond_data = json.loads(cond_path.read_text(encoding="utf-8")) if cond_path.exists() else {}

    rois_path = fam_dir / f"rois_{family}.json"
    rois_data = json.loads(rois_path.read_text(encoding="utf-8")) if rois_path.exists() else {}

    trace_path = fam_dir / f"solver_trace_{family}.json"
    trace_data = json.loads(trace_path.read_text(encoding="utf-8")) if trace_path.exists() else {}

    fit_path = fam_dir / f"fit_comparison_{material}.json"
    fit_data = json.loads(fit_path.read_text(encoding="utf-8")) if fit_path.exists() else {}

    return cond_data, rois_data, trace_data, fit_data


def plot_spectral_overlay(
    cond_data: Dict[str, Any],
    rois_data: Dict[str, Any],
    fit_data: Dict[str, Any],
    title: str = "LIBS Measured vs. Synthetic Spectral Overlay"
) -> go.Figure:
    """Create interactive Plotly figure with spectrum overlays, ROI regions, and line tooltips."""
    fig = go.Figure()

    # Measured spectrum
    meas_y = cond_data.get("mean_spectrum") or cond_data.get("y_mean") or cond_data.get("measuredCounts")
    wls = cond_data.get("wavelengths")
    if meas_y is not None and len(meas_y) > 0:
        if wls is None or len(wls) != len(meas_y):
            wls = list(range(len(meas_y)))

        sig = cond_data.get("sigma_mean")
        if sig is not None and len(sig) == len(meas_y):
            # Error ribbon
            upper = np.array(meas_y) + np.array(sig)
            lower = np.maximum(np.array(meas_y) - np.array(sig), 0.0)
            fig.add_trace(go.Scatter(
                x=list(wls) + list(wls)[::-1],
                y=list(upper) + list(lower)[::-1],
                fill="toself",
                fillcolor="rgba(31, 119, 180, 0.2)",
                line=dict(color="rgba(255,255,255,0)"),
                name="Measured ± 1σ",
                hoverinfo="skip"
            ))

        fig.add_trace(go.Scatter(
            x=wls,
            y=meas_y,
            mode="lines",
            name="Measured",
            line=dict(color="#1f77b4", width=1.5),
            hovertemplate="λ: %{x:.3f} nm<br>Counts: %{y:.1f}<extra>Measured</extra>"
        ))

    # Synthetic spectrum
    synth_y = fit_data.get("synthetic_spectrum") or fit_data.get("syntheticCounts")
    if synth_y is not None and len(synth_y) > 0:
        synth_wls = fit_data.get("wavelengths") or wls
        fig.add_trace(go.Scatter(
            x=synth_wls,
            y=synth_y,
            mode="lines",
            name="Synthetic Fit",
            line=dict(color="#ff7f0e", width=1.5, dash="dash"),
            hovertemplate="λ: %{x:.3f} nm<br>Counts: %{y:.1f}<extra>Synthetic</extra>"
        ))

    # Sub-bin points if available in fit_data
    sub_meas = fit_data.get("sub_bin_measured")
    sub_synth = fit_data.get("sub_bin_synthetic")
    sub_wls = fit_data.get("sub_bin_wavelengths")
    if sub_meas is not None and sub_synth is not None and sub_wls is not None:
        fig.add_trace(go.Scatter(
            x=sub_wls,
            y=sub_meas,
            mode="markers",
            name="Sub-bin Measured",
            marker=dict(color="#1f77b4", size=6, symbol="circle"),
            hovertemplate="λ: %{x:.3f} nm<br>Sub-bin Meas: %{y:.1f}<extra></extra>"
        ))
        fig.add_trace(go.Scatter(
            x=sub_wls,
            y=sub_synth,
            mode="markers",
            name="Sub-bin Synthetic",
            marker=dict(color="#ff7f0e", size=6, symbol="x"),
            hovertemplate="λ: %{x:.3f} nm<br>Sub-bin Synth: %{y:.1f}<extra></extra>"
        ))

    # Highlight ROI windows with shapes
    rois = rois_data.get("rois", [])
    y_max = float(np.max(meas_y)) * 1.05 if meas_y is not None and len(meas_y) > 0 else 1000.0

    shapes = []
    annotations = []
    for r in rois:
        min_nm = r.get("minNm") or r.get("minWavelengthNm")
        max_nm = r.get("maxNm") or r.get("maxWavelengthNm")
        if min_nm is not None and max_nm is not None:
            is_prone = r.get("selfAbsorptionProne", False)
            color = "rgba(255, 99, 71, 0.15)" if is_prone else "rgba(46, 204, 113, 0.15)"
            shapes.append(dict(
                type="rect",
                xref="x", yref="paper",
                x0=min_nm, x1=max_nm,
                y0=0, y1=1,
                fillcolor=color,
                line=dict(width=0),
                layer="below"
            ))

        # Add line assignment hover points if modelLines present
        model_lines = r.get("modelLines", [])
        for line in model_lines:
            wl = line.get("wlNm")
            species = line.get("species", "")
            ei = line.get("EiEv", "")
            ek = line.get("EkEv", "")
            if wl is not None:
                annotations.append(dict(
                    x=wl,
                    y=y_max * 0.95,
                    xref="x", yref="y",
                    text=f"{species} {wl:.2f}nm",
                    showarrow=True,
                    arrowhead=2,
                    arrowsize=1,
                    arrowwidth=1,
                    arrowcolor="#888888",
                    ax=0, ay=-30,
                    font=dict(size=9, color="#555555"),
                    hovertext=f"{species} {wl:.3f} nm<br>Ei={ei} eV, Ek={ek} eV"
                ))

    fig.update_layout(
        title=title,
        xaxis_title="Wavelength (nm)",
        yaxis_title="Intensity (counts)",
        shapes=shapes,
        annotations=annotations[:40],  # Limit to top 40 annotations to avoid clutter
        hovermode="x unified",
        template="plotly_white",
        legend=dict(x=0.01, y=0.99, bgcolor="rgba(255,255,255,0.7)"),
        margin=dict(l=60, r=30, t=60, b=50),
    )
    return fig


def plot_solver_convergence(
    trace_data: Dict[str, Any],
    title: str = "Joint Calibration Solver Convergence"
) -> go.Figure:
    """Create Plotly figure with objective Phi history and response spline beta trajectories."""
    iterations = trace_data.get("iterations", 0)
    phi_history = trace_data.get("objective_phi", [])
    beta_history = trace_data.get("beta", [])

    iters = list(range(1, len(phi_history) + 1)) if phi_history else list(range(1, iterations + 1))

    fig = make_subplots(
        rows=1, cols=2,
        subplot_titles=("Objective Function Φ", "B-Spline Coefficients β"),
        horizontal_spacing=0.12
    )

    if phi_history:
        fig.add_trace(
            go.Scatter(
                x=iters,
                y=phi_history,
                mode="lines+markers",
                name="Φ (Objective)",
                line=dict(color="#2ca02c", width=2),
                marker=dict(size=6),
                hovertemplate="Iter %{x}: Φ = %{y:.4e}<extra></extra>"
            ),
            row=1, col=1
        )
        fig.update_yaxes(type="log", title="Objective Φ (log scale)", row=1, col=1)
        fig.update_xaxes(title="Iteration", row=1, col=1)

    if beta_history:
        beta_arr = np.array(beta_history)
        if beta_arr.ndim == 2:
            num_knots = beta_arr.shape[1]
            for k in range(num_knots):
                fig.add_trace(
                    go.Scatter(
                        x=iters,
                        y=beta_arr[:, k],
                        mode="lines",
                        name=f"β_{k+1}",
                        hovertemplate=f"Iter %{{x}}: β_{k+1} = %{{y:.4f}}<extra></extra>"
                    ),
                    row=1, col=2
                )
        fig.update_yaxes(title="Spline Coefficient Value", row=1, col=2)
        fig.update_xaxes(title="Iteration", row=1, col=2)

    fig.update_layout(
        title=title,
        template="plotly_white",
        margin=dict(l=60, r=30, t=60, b=50),
        legend=dict(x=1.02, y=0.5),
    )
    return fig


def plot_element_parity(
    fit_data: Dict[str, Any],
    title: str = "Element Parity log10(synthetic/measured)"
) -> go.Figure:
    """Create Plotly horizontal/vertical bar chart of element parity with bias threshold bands."""
    parity = fit_data.get("elementParity") or fit_data.get("parity") or {}
    elements = sorted(parity.keys())
    values = [parity[el] for el in elements]

    colors = []
    for v in values:
        if abs(v) > 0.30:
            colors.append("#d62728")  # Red: ELEMENT_BIAS violation
        elif abs(v) > 0.10:
            colors.append("#ff7f0e")  # Orange: Moderate bias
        else:
            colors.append("#2ca02c")  # Green: Clean parity

    fig = go.Figure()
    fig.add_trace(go.Bar(
        x=elements,
        y=values,
        marker_color=colors,
        text=[f"{v:+.3f}" for v in values],
        textposition="auto",
        hovertemplate="Element: %{x}<br>log10(synth/meas): %{y:+.4f}<extra></extra>"
    ))

    # Reference lines at 0, ±0.10, ±0.30
    fig.add_hline(y=0.0, line_dash="solid", line_color="black", line_width=1)
    fig.add_hline(y=0.10, line_dash="dash", line_color="#888888", line_width=1)
    fig.add_hline(y=-0.10, line_dash="dash", line_color="#888888", line_width=1)
    fig.add_hline(y=0.30, line_dash="dot", line_color="#d62728", line_width=1.5,
                  annotation_text="Bias threshold (+0.30)", annotation_position="top right")
    fig.add_hline(y=-0.30, line_dash="dot", line_color="#d62728", line_width=1.5,
                  annotation_text="Bias threshold (-0.30)", annotation_position="bottom right")

    fig.update_layout(
        title=title,
        xaxis_title="Certified Element",
        yaxis_title="Median log10(synthetic / measured)",
        template="plotly_white",
        yaxis=dict(range=[min(-0.35, min(values) - 0.05 if values else -0.35),
                          max(0.35, max(values) + 0.05 if values else 0.35)]),
        margin=dict(l=60, r=30, t=60, b=50),
    )
    return fig


def launch_dashboard(debug_dir: Optional[Path] = None):
    """Launch interactive ipywidgets calibration inspector dashboard."""
    if widgets is None:
        raise ImportError("ipywidgets and IPython are required to launch the interactive dashboard.")

    d = find_debug_dir(debug_dir)
    families = list_families(d)
    if not families:
        print(f"No calibration debug data found under {d.resolve()}")
        return

    family_dropdown = widgets.Dropdown(
        options=families,
        value=families[0],
        description="Family:",
        style={"description_width": "initial"}
    )

    initial_mats = list_materials_for_family(families[0], d)
    material_dropdown = widgets.Dropdown(
        options=initial_mats,
        value=initial_mats[0] if initial_mats else None,
        description="Material:",
        style={"description_width": "initial"}
    )

    out = widgets.Output()

    def update_view(*args):
        with out:
            clear_output(wait=True)
            fam = family_dropdown.value
            mat = material_dropdown.value
            if not fam or not mat:
                print("Select a family and material.")
                return

            cond_data, rois_data, trace_data, fit_data = load_debug_data(fam, mat, d)

            # Display spectral overlay
            fig_spec = plot_spectral_overlay(
                cond_data, rois_data, fit_data,
                title=f"Spectral Fit: {fam} / Material {mat}"
            )
            fig_spec.show()

            # Display convergence
            fig_conv = plot_solver_convergence(
                trace_data,
                title=f"Solver Convergence Trace: {fam}"
            )
            fig_conv.show()

            # Display element parity
            fig_par = plot_element_parity(
                fit_data,
                title=f"Element Parity: {fam} / Material {mat}"
            )
            fig_par.show()

    def on_family_change(change):
        if change["type"] == "change" and change["name"] == "value":
            new_fam = change["new"]
            mats = list_materials_for_family(new_fam, d)
            material_dropdown.options = mats
            material_dropdown.value = mats[0] if mats else None
            update_view()

    family_dropdown.observe(on_family_change, names="value")
    material_dropdown.observe(lambda ch: update_view() if ch["name"] == "value" else None, names="value")

    ui = widgets.VBox([
        widgets.HBox([family_dropdown, material_dropdown]),
        out
    ])
    display(ui)
    update_view()
