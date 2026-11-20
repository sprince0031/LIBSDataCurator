"""Interactive diagnostic inspector for LIBS calibration debug dumps (R13.3, AC13).

Provides Plotly interactive figures and ipywidgets selectors for inspecting:
- Measured vs. synthetic spectrum overlays with ROI windows, line tooltips, and response curve overlay
- Morphological opening baseline correction with configurable structuring element size (default 25)
- Solver convergence traces (objective Phi and spline beta)
- Element parity log-ratios (log10(synthetic/measured))
- Interactive 5-point response curve tuner with real-time R^2 and reduced chi^2 recalculation
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List, Optional, Tuple, Any, Union

import numpy as np
import plotly.graph_objects as go
from plotly.subplots import make_subplots
from scipy.interpolate import BSpline
from scipy.ndimage import grey_opening

try:
    from scripts.match_metrics import (
        calculate_roi_metrics,
        calculate_reduced_chi2,
        calculate_whole_spectrum_metrics,
    )
except ImportError:
    from match_metrics import (
        calculate_roi_metrics,
        calculate_reduced_chi2,
        calculate_whole_spectrum_metrics,
    )

try:
    import ipywidgets as widgets
    from IPython.display import display, clear_output
except ImportError:
    widgets = None
    display = None
    clear_output = None


DEFAULT_DEBUG_DIR = Path("temp/calibration_debug")
DEFAULT_BASELINE_STRUCT_SIZE = 25


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


def apply_morphological_baseline_correction(
    spectrum: Union[List[float], np.ndarray],
    struct_size: int = DEFAULT_BASELINE_STRUCT_SIZE
) -> Tuple[np.ndarray, np.ndarray]:
    """Apply morphological grey opening baseline subtraction followed by non-negative clamping.

    Args:
        spectrum: 1D array of spectral counts.
        struct_size: Structuring element size in detector channels (default: 25).
            If struct_size <= 1, baseline subtraction is disabled and unclamped raw counts are returned.

    Returns:
        (corrected_spectrum, estimated_baseline)
    """
    spec = np.asarray(spectrum, dtype=np.float64)
    if struct_size is None or struct_size <= 1:
        return np.maximum(spec, 0.0), np.zeros_like(spec)
    baseline = grey_opening(spec, size=int(struct_size))
    corrected = np.maximum(spec - baseline, 0.0)
    return corrected, baseline


def compute_bspline_basis(
    wavelengths: np.ndarray,
    rois_data: Dict[str, Any],
    k_coeffs: int = 5,
    span: Optional[Tuple[float, float]] = None
) -> Tuple[np.ndarray, Tuple[float, float]]:
    """Construct clamped uniform cubic B-spline basis matrix and span on [span_lo, span_hi]."""
    wls = np.asarray(wavelengths, dtype=np.float64)
    if span is None:
        resp_chans: List[int] = []
        for r in rois_data.get("rois", []):
            if r.get("usedInResponseFit", False):
                for sb in r.get("subBins", []):
                    resp_chans.extend(sb)
        if resp_chans:
            span = (float(wls[min(resp_chans)]), float(wls[max(resp_chans)]))
        else:
            span = (float(wls[0]), float(wls[-1]))

    span_lo, span_hi = float(span[0]), float(span[1])
    n_interior = max(0, k_coeffs - 4)
    if n_interior > 0:
        interior_knots = np.linspace(span_lo, span_hi, n_interior + 2)[1:-1]
    else:
        interior_knots = np.empty(0, dtype=np.float64)

    knots = np.concatenate([
        np.full(4, span_lo),
        interior_knots,
        np.full(4, span_hi),
    ])

    wls_clamped = np.clip(wls, span_lo, span_hi)
    B = BSpline.design_matrix(wls_clamped, knots, 3).toarray()
    return B, (span_lo, span_hi)


def compute_response_from_beta(
    wavelengths: np.ndarray,
    rois_data: Dict[str, Any],
    beta: Union[List[float], np.ndarray],
    span: Optional[Tuple[float, float]] = None
) -> np.ndarray:
    """Evaluate response curve R(lambda) = exp(B(lambda) @ beta)."""
    wls = np.asarray(wavelengths, dtype=np.float64)
    beta_arr = np.asarray(beta, dtype=np.float64)
    B, _ = compute_bspline_basis(wls, rois_data, k_coeffs=len(beta_arr), span=span)
    return np.exp(B @ beta_arr)


def plot_spectral_overlay(
    cond_data: Dict[str, Any],
    rois_data: Dict[str, Any],
    fit_data: Dict[str, Any],
    title: str = "LIBS Measured vs. Synthetic Spectral Overlay",
    response_curve: Optional[Union[List[float], np.ndarray]] = None,
    baseline_struct_size: int = DEFAULT_BASELINE_STRUCT_SIZE,
    show_baseline: bool = False
) -> go.Figure:
    """Create interactive Plotly figure with spectrum overlays, ROI regions, line tooltips, response curve,
    and morphological opening baseline correction.
    """
    fig = go.Figure()

    # Measured spectrum (raw)
    raw_y = cond_data.get("mean_spectrum") or cond_data.get("y_mean") or cond_data.get("measuredCounts")
    wls = cond_data.get("wavelengths")
    if raw_y is not None and len(raw_y) > 0:
        if wls is None or len(wls) != len(raw_y):
            wls = list(range(len(raw_y)))

        # Apply morphological opening baseline correction (default size 25)
        meas_y, baseline = apply_morphological_baseline_correction(raw_y, struct_size=baseline_struct_size)

        sig = cond_data.get("sigma_mean")
        if sig is not None and len(sig) == len(meas_y):
            upper = meas_y + np.array(sig)
            lower = np.maximum(meas_y - np.array(sig), 0.0)
            fig.add_trace(go.Scatter(
                x=list(wls) + list(wls)[::-1],
                y=list(upper) + list(lower)[::-1],
                fill="toself",
                fillcolor="rgba(31, 119, 180, 0.2)",
                line=dict(color="rgba(255,255,255,0)"),
                name="Measured ± 1σ",
                hoverinfo="skip"
            ))

        meas_label = f"Measured (Clean, size={baseline_struct_size})" if baseline_struct_size > 1 else "Measured (Raw)"
        fig.add_trace(go.Scatter(
            x=wls,
            y=meas_y,
            mode="lines",
            name="Measured",
            line=dict(color="#1f77b4", width=1.5),
            hovertemplate=f"λ: %{{x:.3f}} nm<br>Counts: %{{y:.1f}}<extra>{meas_label}</extra>"
        ))

        # Continuum baseline trace if requested
        if show_baseline and baseline_struct_size > 1:
            fig.add_trace(go.Scatter(
                x=wls,
                y=baseline,
                mode="lines",
                name=f"Baseline (size={baseline_struct_size})",
                line=dict(color="#7f7f7f", width=1.2, dash="dot"),
                hovertemplate="λ: %{x:.3f} nm<br>Baseline: %{y:.1f}<extra>Continuum Baseline</extra>"
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

    # Response curve overlay on secondary y-axis if provided
    if response_curve is not None and len(response_curve) == len(wls):
        fig.add_trace(go.Scatter(
            x=wls,
            y=list(response_curve),
            mode="lines",
            name="Response R(λ)",
            yaxis="y2",
            line=dict(color="#9b59b6", width=2.0, dash="dashdot"),
            hovertemplate="λ: %{x:.3f} nm<br>Response: %{y:.4f}<extra>Response R(λ)</extra>"
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
    y_max = float(np.max(meas_y)) * 1.05 if 'meas_y' in locals() and len(meas_y) > 0 else 1000.0

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

    layout_dict = dict(
        title=title,
        xaxis_title="Wavelength (nm)",
        yaxis_title="Intensity (counts)",
        shapes=shapes,
        annotations=annotations[:40],
        hovermode="x unified",
        template="plotly_white",
        legend=dict(x=0.01, y=0.99, bgcolor="rgba(255,255,255,0.7)"),
        margin=dict(l=60, r=60, t=60, b=50),
    )

    if response_curve is not None:
        layout_dict["yaxis2"] = dict(
            title=dict(text="Response R(λ)", font=dict(color="#9b59b6")),
            tickfont=dict(color="#9b59b6"),
            overlaying="y",
            side="right",
            showgrid=False,
            zeroline=False
        )

    fig.update_layout(**layout_dict)
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
    """Launch interactive ipywidgets calibration inspector dashboard with baseline correction control."""
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

    struct_size_slider = widgets.IntSlider(
        value=DEFAULT_BASELINE_STRUCT_SIZE,
        min=1,
        max=101,
        step=2,
        description="Baseline Size:",
        style={"description_width": "initial"},
        tooltip="Morphological opening structuring element size (1 = raw/disabled)",
        layout=widgets.Layout(width="240px")
    )

    show_baseline_check = widgets.Checkbox(
        value=False,
        description="Show Baseline",
        style={"description_width": "initial"},
        tooltip="Display estimated continuum baseline trace"
    )

    show_resp_check = widgets.Checkbox(
        value=True,
        description="Show Response R(λ)",
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

            # Compute response curve if trace has beta and user opted in
            resp_curve = None
            if show_resp_check.value:
                wls = cond_data.get("wavelengths")
                beta_hist = trace_data.get("beta", [])
                if beta_hist and wls:
                    beta_final = np.array(beta_hist[-1])
                    resp_curve = compute_response_from_beta(np.array(wls), rois_data, beta_final)

            # Display spectral overlay with baseline correction
            fig_spec = plot_spectral_overlay(
                cond_data, rois_data, fit_data,
                title=f"Spectral Fit: {fam} / Material {mat}",
                response_curve=resp_curve,
                baseline_struct_size=struct_size_slider.value,
                show_baseline=show_baseline_check.value
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
    struct_size_slider.observe(lambda ch: update_view() if ch["name"] == "value" else None, names="value")
    show_baseline_check.observe(lambda ch: update_view() if ch["name"] == "value" else None, names="value")
    show_resp_check.observe(lambda ch: update_view() if ch["name"] == "value" else None, names="value")

    ui = widgets.VBox([
        widgets.HBox([family_dropdown, material_dropdown, struct_size_slider, show_baseline_check, show_resp_check]),
        out
    ])
    display(ui)
    update_view()


def create_interactive_response_tuner(
    family: str = "Ferritic_Stainless_Steel",
    material: str = "469",
    debug_dir: Optional[Path] = None,
    default_struct_size: int = DEFAULT_BASELINE_STRUCT_SIZE
) -> widgets.VBox:
    """Interactive Plotly + ipywidgets tuner to dynamically adjust the 5 B-spline response knobs
    AND the morphological opening baseline structuring element size, observing real-time changes
    to the synthetic spectrum fit, ROI R^2, and reduced chi^2 metrics.
    """
    if widgets is None:
        raise ImportError("ipywidgets and IPython are required for interactive tuning.")

    d = find_debug_dir(debug_dir)
    families = list_families(d)
    if not families:
        print(f"No debug families found in {d.resolve()}")
        return widgets.VBox()

    if family not in families:
        family = families[0]
    available_mats = list_materials_for_family(family, d)
    if material not in available_mats and available_mats:
        material = available_mats[0]

    fam_selector = widgets.Dropdown(
        options=families,
        value=family,
        description="Family:",
        style={"description_width": "initial"}
    )
    mat_selector = widgets.Dropdown(
        options=available_mats,
        value=material,
        description="Material:",
        style={"description_width": "initial"}
    )

    tuner_container = widgets.VBox()

    def rebuild_tuner():
        cur_fam = fam_selector.value
        cur_mat = mat_selector.value
        cond_data, rois_data, trace_data, fit_data = load_debug_data(cur_fam, cur_mat, d)

        if not cond_data or not fit_data:
            tuner_container.children = [widgets.HTML(f"<p style='color:red;'>No debug data found for {cur_fam} / {cur_mat}.</p>")]
            return

        wls = np.array(cond_data.get("wavelengths", []))
        raw_meas_y = np.array(cond_data.get("mean_spectrum", []))
        sig_y = np.array(cond_data.get("sigma_mean", np.ones_like(raw_meas_y)))
        synth_y = np.array(fit_data.get("synthetic_spectrum", []))
        lit_mask = np.array(cond_data.get("lit_mask", [True] * len(wls)))

        # Extract ROI channels
        all_roi_chans: List[int] = []
        thin_roi_chans: List[int] = []
        for r in rois_data.get("rois", []):
            chans = r.get("channelIndices", [])
            all_roi_chans.extend(chans)
            if r.get("usedInResponseFit", False):
                thin_roi_chans.extend(chans)
        all_roi_chans = sorted(list(set(all_roi_chans)))
        thin_roi_chans = sorted(list(set(thin_roi_chans)))

        # B-spline basis and baseline fitted response
        beta_hist = trace_data.get("beta", [])
        if beta_hist:
            beta_fit = np.array(beta_hist[-1], dtype=np.float64)
        else:
            beta_fit = np.zeros(5, dtype=np.float64)

        B, span = compute_bspline_basis(wls, rois_data, k_coeffs=len(beta_fit))
        r_fit = np.exp(B @ beta_fit)

        # Baseline un-weighted emission: I_base = synth_y / r_fit
        i_base = synth_y / np.maximum(r_fit, 1e-6)

        # Baseline structuring element size control
        struct_size_slider = widgets.IntSlider(
            value=default_struct_size,
            min=1,
            max=101,
            step=2,
            description="Baseline Size (Opening):",
            style={"description_width": "180px"},
            tooltip="Morphological opening structuring element size (1 = raw/disabled)",
            layout=widgets.Layout(width="380px")
        )

        show_baseline_check = widgets.Checkbox(
            value=False,
            description="Show Continuum Baseline",
            tooltip="Display the estimated continuum baseline curve as a dotted trace",
            style={"description_width": "initial"}
        )

        autoscale_check = widgets.Checkbox(
            value=False,
            description="Auto-scale intensity (α)",
            tooltip="Compute optimal global scale factor alpha to re-align intensity"
        )

        # Initial baseline correction
        meas_y_init, baseline_init = apply_morphological_baseline_correction(raw_meas_y, struct_size=default_struct_size)

        # Baseline fitted metrics with initial baseline correction
        baseline_r2_roi, baseline_rmse_roi = calculate_roi_metrics(meas_y_init, synth_y, all_roi_chans)
        baseline_chi2_roi = calculate_reduced_chi2(
            meas_y_init[all_roi_chans], synth_y[all_roi_chans],
            sig_y[all_roi_chans] ** 2, num_params=len(beta_fit)
        )

        baseline_r2_lit, baseline_rmse_lit = calculate_whole_spectrum_metrics(meas_y_init, synth_y, lit_mask)
        baseline_chi2_lit = calculate_reduced_chi2(
            meas_y_init[lit_mask], synth_y[lit_mask],
            sig_y[lit_mask] ** 2, num_params=len(beta_fit)
        )

        # Peak wavelength of each basis function
        peak_wls = [wls[np.argmax(B[:, k])] for k in range(len(beta_fit))]
        knob_labels = [
            f"β₁ (UV ~{peak_wls[0]:.0f}nm)",
            f"β₂ (Blue ~{peak_wls[1]:.0f}nm)",
            f"β₃ (Cyan ~{peak_wls[2]:.0f}nm)",
            f"β₄ (Green ~{peak_wls[3]:.0f}nm)",
            f"β₅ (NIR ~{peak_wls[4]:.0f}nm)",
        ]

        sliders = []
        for k in range(len(beta_fit)):
            init_val = float(beta_fit[k])
            sl = widgets.FloatSlider(
                value=init_val,
                min=-5.0,
                max=5.0,
                step=0.05,
                description=knob_labels[k] + ":",
                continuous_update=True,
                readout=True,
                readout_format=".2f",
                style={"description_width": "150px"},
                layout=widgets.Layout(width="400px")
            )
            sliders.append(sl)

        reset_btn = widgets.Button(
            description="Reset to Fitted β",
            button_style="info",
            icon="undo",
            tooltip="Revert sliders to solver's optimal coefficients"
        )

        metrics_html = widgets.HTML()

        # Build interactive Plotly FigureWidget
        fig = go.FigureWidget()

        # Trace 0: Error ribbon (around baseline-corrected measurement)
        upper_init = meas_y_init + sig_y
        lower_init = np.maximum(meas_y_init - sig_y, 0.0)
        fig.add_trace(go.Scatter(
            x=list(wls) + list(wls)[::-1],
            y=list(upper_init) + list(lower_init)[::-1],
            fill="toself",
            fillcolor="rgba(31, 119, 180, 0.15)",
            line=dict(color="rgba(255,255,255,0)"),
            name="Measured ± 1σ",
            hoverinfo="skip"
        ))

        # Trace 1: Measured (clean)
        fig.add_trace(go.Scatter(
            x=wls,
            y=meas_y_init,
            mode="lines",
            name="Measured (Clean)",
            line=dict(color="#1f77b4", width=1.5),
            hovertemplate="λ: %{x:.3f} nm<br>Counts: %{y:.1f}<extra>Measured (Clean)</extra>"
        ))

        # Trace 2: Continuum Baseline (dotted trace)
        fig.add_trace(go.Scatter(
            x=wls,
            y=baseline_init,
            mode="lines",
            name="Continuum Baseline",
            visible=False,
            line=dict(color="#7f7f7f", width=1.2, dash="dot"),
            hovertemplate="λ: %{x:.3f} nm<br>Baseline: %{y:.1f}<extra>Baseline</extra>"
        ))

        # Trace 3: Synthetic Fit (dynamic)
        fig.add_trace(go.Scatter(
            x=wls,
            y=synth_y,
            mode="lines",
            name="Synthetic Fit",
            line=dict(color="#ff7f0e", width=1.6, dash="dash"),
            hovertemplate="λ: %{x:.3f} nm<br>Counts: %{y:.1f}<extra>Synthetic</extra>"
        ))

        # Trace 4: Response curve (dynamic, secondary y-axis)
        fig.add_trace(go.Scatter(
            x=wls,
            y=r_fit,
            mode="lines",
            name="Response R(λ)",
            yaxis="y2",
            line=dict(color="#9b59b6", width=2.0, dash="dashdot"),
            hovertemplate="λ: %{x:.3f} nm<br>Response: %{y:.4f}<extra>Response R(λ)</extra>"
        ))

        # Shapes for ROIs
        rois = rois_data.get("rois", [])
        shapes = []
        annotations = []
        y_max = float(np.max(meas_y_init)) * 1.05 if len(meas_y_init) > 0 else 1000.0
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

            for line in r.get("modelLines", []):
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
            title=f"Dynamic Response & Baseline Tuner: {cur_fam} / {cur_mat}",
            xaxis_title="Wavelength (nm)",
            yaxis_title="Intensity (counts)",
            yaxis2=dict(
                title=dict(text="Response R(λ)", font=dict(color="#9b59b6")),
                tickfont=dict(color="#9b59b6"),
                overlaying="y",
                side="right",
                showgrid=False,
                zeroline=False
            ),
            shapes=shapes,
            annotations=annotations[:40],
            hovermode="x unified",
            template="plotly_white",
            legend=dict(x=0.01, y=0.99, bgcolor="rgba(255,255,255,0.7)"),
            margin=dict(l=60, r=60, t=60, b=50),
            height=540,
        )

        def update_metrics_and_figure(*args):
            cur_struct_sz = struct_size_slider.value
            meas_y_curr, baseline_curr = apply_morphological_baseline_correction(raw_meas_y, struct_size=cur_struct_sz)

            current_beta = np.array([sl.value for sl in sliders], dtype=np.float64)
            r_curr = np.exp(B @ current_beta)
            y_synth_curr = i_base * r_curr

            alpha = 1.0
            if autoscale_check.value and len(all_roi_chans) > 0:
                denom = np.sum(y_synth_curr[all_roi_chans] ** 2)
                if denom > 1e-12:
                    alpha = float(np.sum(meas_y_curr[all_roi_chans] * y_synth_curr[all_roi_chans]) / denom)
                    y_synth_curr = y_synth_curr * alpha

            # Recalculate metrics on baseline-corrected measured spectrum
            r2_roi, rmse_roi = calculate_roi_metrics(meas_y_curr, y_synth_curr, all_roi_chans)
            chi2_roi = calculate_reduced_chi2(
                meas_y_curr[all_roi_chans], y_synth_curr[all_roi_chans],
                sig_y[all_roi_chans] ** 2, num_params=len(current_beta)
            )

            r2_lit, rmse_lit = calculate_whole_spectrum_metrics(meas_y_curr, y_synth_curr, lit_mask)
            chi2_lit = calculate_reduced_chi2(
                meas_y_curr[lit_mask], y_synth_curr[lit_mask],
                sig_y[lit_mask] ** 2, num_params=len(current_beta)
            )

            delta_r2 = r2_roi - baseline_r2_roi
            delta_chi2 = chi2_roi - baseline_chi2_roi

            r2_color = "#27ae60" if delta_r2 >= 0 else "#c0392b"
            chi2_color = "#27ae60" if delta_chi2 <= 0 else "#c0392b"

            alpha_html = f'<div style="border-left: 1px solid #ced4da; height: 40px;"></div><div><div style="font-size: 11px; text-transform: uppercase; color: #6c757d; font-weight: bold;">Intensity Scale α</div><div style="font-size: 16px; font-weight: bold; color: #2980b9;">{alpha:.3f}×</div></div>' if autoscale_check.value else ""
            baseline_status_label = f"Size: {cur_struct_sz}" if cur_struct_sz > 1 else "RAW (Disabled)"

            html_content = f"""
            <div style="display: flex; gap: 20px; align-items: center; padding: 12px 18px; background: #f8f9fa; border: 1px solid #dee2e6; border-radius: 8px; margin: 10px 0; font-family: sans-serif;">
                <div style="flex: 1;">
                    <div style="font-size: 11px; text-transform: uppercase; color: #6c757d; font-weight: bold;">ROI R² (Fit vs. Baseline)</div>
                    <div style="font-size: 20px; font-weight: bold; color: #212529;">{r2_roi:.4f} <span style="font-size: 13px; color: {r2_color}; font-weight: normal;">({delta_r2:+.4f})</span></div>
                    <div style="font-size: 11px; color: #888;">Base: {baseline_r2_roi:.4f} | RMSE: {rmse_roi:.4f}</div>
                </div>
                <div style="border-left: 1px solid #ced4da; height: 40px;"></div>
                <div style="flex: 1;">
                    <div style="font-size: 11px; text-transform: uppercase; color: #6c757d; font-weight: bold;">ROI Reduced χ²</div>
                    <div style="font-size: 20px; font-weight: bold; color: #212529;">{chi2_roi:.2e} <span style="font-size: 13px; color: {chi2_color}; font-weight: normal;">({delta_chi2:+.2e})</span></div>
                    <div style="font-size: 11px; color: #888;">Base: {baseline_chi2_roi:.2e}</div>
                </div>
                <div style="border-left: 1px solid #ced4da; height: 40px;"></div>
                <div style="flex: 1;">
                    <div style="font-size: 11px; text-transform: uppercase; color: #6c757d; font-weight: bold;">Lit Channels R² & χ²</div>
                    <div style="font-size: 16px; font-weight: bold; color: #212529;">R²: {r2_lit:.4f} | χ²: {chi2_lit:.2e}</div>
                    <div style="font-size: 11px; color: #888;">Base: R²={baseline_r2_lit:.4f}, χ²={baseline_chi2_lit:.2e}</div>
                </div>
                <div style="border-left: 1px solid #ced4da; height: 40px;"></div>
                <div>
                    <div style="font-size: 11px; text-transform: uppercase; color: #6c757d; font-weight: bold;">Morph. Opening</div>
                    <div style="font-size: 14px; font-weight: bold; color: #8e44ad;">{baseline_status_label}</div>
                </div>
                {alpha_html}
            </div>
            """
            metrics_html.value = html_content

            # Batch update FigureWidget
            with fig.batch_update():
                # Trace 0: Error ribbon
                up = meas_y_curr + sig_y
                lo = np.maximum(meas_y_curr - sig_y, 0.0)
                fig.data[0].y = list(up) + list(lo)[::-1]
                # Trace 1: Measured
                fig.data[1].y = meas_y_curr
                meas_name = f"Measured (Clean, size={cur_struct_sz})" if cur_struct_sz > 1 else "Measured (Raw)"
                fig.data[1].name = meas_name
                # Trace 2: Baseline
                fig.data[2].y = baseline_curr
                fig.data[2].visible = show_baseline_check.value
                # Trace 3: Synthetic
                fig.data[3].y = y_synth_curr
                # Trace 4: Response
                fig.data[4].y = r_curr

        for sl in sliders:
            sl.observe(update_metrics_and_figure, names="value")
        struct_size_slider.observe(update_metrics_and_figure, names="value")
        show_baseline_check.observe(update_metrics_and_figure, names="value")
        autoscale_check.observe(update_metrics_and_figure, names="value")

        def on_reset_clicked(b):
            for k, sl in enumerate(sliders):
                sl.value = float(beta_fit[k])
            struct_size_slider.value = default_struct_size

        reset_btn.on_click(on_reset_clicked)

        update_metrics_and_figure()

        controls_row1 = widgets.HBox([
            fam_selector,
            mat_selector,
            struct_size_slider,
            show_baseline_check
        ])
        controls_row2 = widgets.HBox([
            reset_btn,
            autoscale_check
        ])

        slider_grid = widgets.VBox([
            widgets.HBox([sliders[0], sliders[1]]),
            widgets.HBox([sliders[2], sliders[3]]),
            widgets.HBox([sliders[4]]),
        ])

        tuner_container.children = [
            controls_row1,
            controls_row2,
            slider_grid,
            metrics_html,
            fig
        ]

    def on_fam_change(change):
        if change["type"] == "change" and change["name"] == "value":
            new_fam = change["new"]
            mats = list_materials_for_family(new_fam, d)
            mat_selector.options = mats
            mat_selector.value = mats[0] if mats else None
            rebuild_tuner()

    fam_selector.observe(on_fam_change, names="value")
    mat_selector.observe(lambda ch: rebuild_tuner() if ch["name"] == "value" else None, names="value")

    rebuild_tuner()

    return widgets.VBox([
        widgets.HTML(
            "<h3>Interactive 5-Point Response Curve & Baseline Tuner</h3>"
            "<p style='color: #555; margin-top: -6px;'>"
            "Adjust the morphological opening structuring element size (default 25) to remove continuum background, "
            "and tweak the 5 B-spline response knobs β₁…β₅ to adjust spectrometer efficiency across UV, visible, and NIR bands. "
            "Metrics and spectral fit update dynamically in real time."
            "</p>"
        ),
        tuner_container
    ])
