# Specification: ASD & Composition-Informed ROI-Only Plasma Optimisation & `regionsOfInterest.json` Export

## Track Metadata
- **Track ID:** `asd_roi_plasma_optimisation_20261112`
- **Git Branch Policy:** Stay on the existing branch `feat-instrument-profile-optimisation` (DO NOT create or checkout a new git branch).
- **Prerequisite Track:** `gpu_calibration_pipeline_20261027` (`v0.9.8` Voigt-free calibration, `structuringElementSize = 65`, `data/nist_lines/` caching via `scripts/nist_lines.py`, and 8-knot ASD-anchored peak-envelope P-spline `spectralResponse` solver).

---

## 1. Overview & Architectural Constraints

### 1.1 Scientific & Engineering Goal
Broadband LIBS spectra span nearly $2,000$ detector channels (`341.952–896.916 nm`), most of which contain inter-line baseline noise rather than diagnostic atomic emission[cite: 16, 24]. Optimizing 2-zone plasma parameters (`Te`, `Ne`, `volumeFraction`, `kAbsorption`) and `scaleFactor` across all channels dilutes peak-matching fidelity on the diagnostic matrix, minor alloying, and trace tramp lines needed for steel scrap classification[cite: 24].

This track updates the calibration engine so that:
1. **Global `spectralResponse` ($R(\lambda)$) Estimation Remains Unchanged:** Pass 1 (flat $R^{(0)}(\lambda) = \mathbf{1}$ warm-up) and `solve_material_agnostic_spectral_response(...)` (8-knot ASD-anchored peak-envelope P-spline with Huber clipping and inverse-family-size balanced pooling) remain identical to `v0.9.8`[cite: 22].
2. **Pass-2 Plasma & `scaleFactor` Optimization Runs Exclusively on Diagnostic ROIs:** Using each material family's `-c` elemental compositions from `temp/calibration_manifest.json` and the cached NIST ASD Parquet tables in `data/nist_lines/`, a dedicated discovery module (`scripts/asd_roi_discovery.py`) identifies non-overlapping, channel-disjoint **Regions of Interest (ROIs)** $\Omega_{f,k}$[cite: 18, 22]. Pass 2 (`optimize_family_rte_gpu`) optimizes `Te`, `Ne`, `volumeFraction`, `kAbsorption`, and `scaleFactor` **strictly on the union of ROI channels** ($w_p = 0.0$ outside ROIs)[cite: 22].
3. **Viewer & Metrics Export (`data/regionsOfInterest.json`):** Calibration non-destructively upserts `<repo_root>/data/regionsOfInterest.json` with per-family ROI wavelength bounds (`minNm`, `maxNm`), disjoint detector channel indices (`channelIndices`, `unionChannelIndices`), element/species metadata, and ROI-only vs. whole-spectrum $R^2$ and RMSE metrics for use in the external Streamlit-Plotly viewer repository[cite: 20, 24].
4. **Zero Java Changes Required:** `./run.sh` continues reading `conf/instrument_profile.json` and synthesizing full-wavelength spectra via `SpectrumUtils.combineNZones` + `applyInstrumentResponseAndNoise` using the ROI-optimized `plasmaZones`, `scaleFactor`, and unchanged `spectralResponse`[cite: 20, 22].

### 1.2 Single-Source-of-Truth Rule (Zero Duplication)
- `scripts/nist_lines.py` is the **sole owner** of `-c` composition parsing (`parse_composition_dict`, `parse_elements_from_composition`), NIST ASD Parquet caching/loading (`build_cache`, `load_lines` in `<repo_root>/data/nist_lines/`), and Saha-Boltzmann LTE transition scoring (`ACCURACY_WEIGHTS`, `load_scored_asd_lines`)[cite: 18, 22].
- `scripts/asd_roi_discovery.py` and `scripts/gpu_calibration_engine.py` MUST import these functions from `scripts/nist_lines.py` rather than duplicating composition parsers, Parquet file loops, or LTE scoring formulas[cite: 18, 22].
- Remove `build_family_tramp_weights` from `AsdLineAdvisor` in `scripts/gpu_calibration_engine.py`; `AsdLineAdvisor` retains only `build_resonance_mask` and `get_asd_anchor_windows` for `spectralResponse` fitting, while `AsdRoiDiscoveryEngine` is the sole owner of Pass-2 ROI masks and weights[cite: 22].

---

## 2. Mathematical Specification

### 2.1 Stage A: Composition- & ASD-Driven ROI Discovery (`scripts/asd_roi_discovery.py`)
For each Material Family $f$ in `temp/calibration_manifest.json`[cite: 20]:
1. Aggregate the normalized `-c` compositions across all standards in family $f$ to obtain the maximum weight percentage $C^{\max}_{f,E}$ per alloy element $E$, explicitly excluding ambient air elements $\mathcal{E}_{\text{air}} = \{\text{H}, \text{O}, \text{N}\}$[cite: 18, 22]. Classify active elements into:
   - **Matrix Elements ($\mathcal{E}_{\text{matrix}}$):** $C^{\max}_{f,E} \ge 5.0\text{ wt.}\%$ (category weight $W_{\text{cat}} = 1.0$, up to $14$ lines)
   - **Minor Alloying Elements ($\mathcal{E}_{\text{minor}}$):** $0.5 \le C^{\max}_{f,E} < 5.0\text{ wt.}\%$ (category weight $W_{\text{cat}} = 1.5$, up to $8$ lines)
   - **Trace / Tramp Elements ($\mathcal{E}_{\text{tramp}}$):** $0 < C^{\max}_{f,E} < 0.5\text{ wt.}\%$ (category weight $W_{\text{cat}} = 2.0$, up to $6$ lines)
2. Load cached NIST ASD transitions ($l \in \{\text{I}, \text{II}\}$) strictly within `[lam_meas[0], lam_meas[-1]]` via `load_scored_asd_lines(...)` and rank each transition $l$ using concentration scaling and matrix-interference isolation[cite: 18, 22]:
   $$S_{f,l} = \left(C^{\max}_{f,E}\right)^{0.65} \cdot \text{lte\_score}_l \cdot \mathcal{I}_{\text{iso}}(\lambda_l)$$
   where for non-matrix elements ($E \notin \mathcal{E}_{\text{matrix}}$), $\mathcal{I}_{\text{iso}}(\lambda_l) = \left(1.0 + 4.0 \sum_{m \in \mathcal{L}_{\text{matrix}}, \vert{}\lambda_m - \lambda_l\vert{} \le 0.35\text{ nm}} \tilde{I}_m\right)^{-1}$ penalizes lines overlapped by strong matrix peaks ($> 2\%$ of max matrix LTE score)[cite: 22].
3. Deduplicate transitions within $0.60\text{ nm}$ per element, bracket each selected transition by $\pm 0.85\text{ nm}$, and merge clusters separated by $\le 0.40\text{ nm}$ (capped at max width $6.0\text{ nm}$) into $K_f$ discrete ROIs $\Omega_{f,k}$[cite: 22].
4. Map each ROI onto `lam_meas` with **strict channel disjointness** (`startChannelIdx_k > endChannelIdx_{k-1}`) and a **minimum width of 3 detector channels** (so Pearson correlation inside narrow ROIs never divides by zero variance), producing:
   - `channelIndices` $\mathcal{I}_k$ (disjoint integer pixel indices per ROI)
   - `unionChannelIndices` $\mathcal{P}_{\text{ROI}, f} = \bigsqcup_{k=1}^{K_f} \mathcal{I}_k$
   - Channel weight vector $\mathbf{w}_{\text{ROI}, f} \in \mathbb{R}_{\ge 0}^P$, where $w_{\text{ROI}, f}[p] = 0.0$ for $p \notin \mathcal{P}_{\text{ROI}, f}$ and $w_{\text{ROI}, f}[p] = W_{\text{cat}, k}$ for $p \in \mathcal{I}_k$.

### 2.2 Stage B: ROI-Exclusive Pass-2 RTE Optimization & Metrics (`scripts/gpu_calibration_engine.py`)
In Pass 2 (with global $R(\lambda)$ locked), `optimize_family_rte_gpu` evaluates every Monte Carlo candidate strictly over $\mathcal{P}_{\text{ROI}, f}$ (`roi_mask = (roi_weights_gpu > 0.0)`):
1. **ROI-Only OLS `scaleFactor`:**
   $$\alpha_{\text{scale}, m} = \frac{\sum_{p \in \mathcal{P}_{\text{ROI}, f}} w_p \, y_m(p) \, \tilde{I}_{\text{resp}, m}(p)}{\sum_{p \in \mathcal{P}_{\text{ROI}, f}} w_p \, \tilde{I}_{\text{resp}, m}(p)^2 + 10^{-12}}$$
2. **ROI-Only Hybrid Objective Function:**
   $$\mathcal{L}_{\text{ROI}}(\vec{\theta}) = 0.5 \cdot \frac{\sum_{p \in \mathcal{P}_{\text{ROI}, f}} w_p \left(\frac{y_m(p) - \hat{y}_m(p)}{\max_{q \in \mathcal{P}_{\text{ROI}, f}} y_m(q)}\right)^2}{\sum_{p \in \mathcal{P}_{\text{ROI}, f}} w_p} + 0.5 \cdot \left(1 - \frac{\sum_{p \in \mathcal{P}_{\text{ROI}, f}} w_p \, y_m(p) \, \hat{y}_m(p)}{\sqrt{\sum_{p \in \mathcal{P}_{\text{ROI}, f}} w_p \, y_m(p)^2} \sqrt{\sum_{p \in \mathcal{P}_{\text{ROI}, f}} w_p \, \hat{y}_m(p)^2}}\right)$$
3. **ROI-Scoped $R^2$ & Normalized RMSE:**
   Reported as primary `rSquared` and `rmse` in `calibrationStats` (matching the Streamlit-Plotly viewer when sliced by `unionChannelIndices`), alongside `wholeSpectrumRSquared` and `wholeSpectrumRmse` for comparison:
   $$R^2_{\text{ROI}} = 1 - \frac{\sum_{p \in \mathcal{P}_{\text{ROI}, f}} (y_m(p) - \hat{y}_m(p))^2}{\sum_{p \in \mathcal{P}_{\text{ROI}, f}} (y_m(p) - \bar{y}_{m,\text{ROI}})^2}, \qquad \text{RMSE}_{\text{ROI}} = \sqrt{\frac{1}{\vert{}\mathcal{P}_{\text{ROI}, f}\vert{}} \sum_{p \in \mathcal{P}_{\text{ROI}, f}} \left(\frac{y_m(p) - \hat{y}_m(p)}{\max_{q \in \mathcal{P}_{\text{ROI}, f}} y_m(q)}\right)^2}$$

---

## 3. Output Schema: `<repo_root>/data/regionsOfInterest.json`

Non-destructively upserted by `GpuLibsCalibrationEngine.evaluate_and_export_rois_json(...)` so iterative batch runs preserve all calibrated families:

```json
{
  "schemaVersion": "1.0",
  "instrumentName": "LSA_Calibrated_Instrument",
  "wavelengthRangeNm": [341.952, 896.916],
  "totalChannels": 1984,
  "wavelengths": [341.952, 342.246, "..."],
  "materialFamilyRois": {
    "Ferritic_Stainless_Steel": {
      "materialFamily": "Ferritic_Stainless_Steel",
      "calibratedMaterials": ["469", "470", "471", "472", "473"],
      "numRois": 24,
      "totalRoiChannels": 318,
      "coveragePercent": 16.03,
      "unionChannelIndices": [0, 1, 2, 12, 13, "..."],
      "calibrationMetrics": {
        "roiRSquared": 0.8942,
        "roiRmse": 0.0485,
        "wholeSpectrumRSquared": 0.7814,
        "wholeSpectrumRmse": 0.0612
      },
      "rois": [
        {
          "roiId": "ROI_01_Ni_Fe_342_347",
          "minNm": 341.952,
          "maxNm": 347.242,
          "centerNm": 344.597,
          "startChannelIdx": 0,
          "endChannelIdx": 18,
          "channelIndices": [0, 1, 2, "..."],
          "targetElements": ["Ni", "Fe", "Cr"],
          "dominantSpecies": "Ni I",
          "elementCategory": "TRAMP_DIAGNOSTIC",
          "meanEiEv": 0.21,
          "meanEkEv": 3.81,
          "importanceWeight": 0.052632,
          "roiRSquared": 0.9124,
          "roiRmse": 0.0411
        }
      ]
    }
  }
}
```

---

## 4. Code Implementation Specifications

### 4.1 Centralize Helpers in `scripts/nist_lines.py`
Add `ACCURACY_WEIGHTS`, `parse_composition_dict`, `parse_elements_from_composition`, and `load_scored_asd_lines` to `scripts/nist_lines.py`[cite: 18, 22]:

```python
ACCURACY_WEIGHTS: dict[str, float] = {
    "AAA": 1.0, "AA": 1.0, "A+": 0.95, "A": 0.90,
    "B+": 0.85, "B": 0.80, "C+": 0.70, "C": 0.65,
    "D+": 0.50, "D": 0.45, "E": 0.25,
}


def parse_composition_dict(raw_comp) -> dict[str, float]:
    """
    Single source of truth for parsing LIBSDataCurator compositions (either a dict
    {'Fe': 86.5, 'Cr': 11.9} or a -c string 'C-0.28,Si-6.5:7.5,Fe-#') into a
    normalized Dict[str, float] summing to 100.0 wt.%.
    """
    if isinstance(raw_comp, str):
        raw_dict: dict[str, str] = {}
        for token in re.split(r"[,;]", raw_comp.strip()):
            parts = re.split(r"[-:]", token.strip(), maxsplit=1)
            if len(parts) == 2:
                raw_dict[parts[0].strip()] = parts[1].strip()
    elif isinstance(raw_comp, dict):
        raw_dict = {str(k): str(v) for k, v in raw_comp.items()}
    else:
        return {}

    parsed: dict[str, float] = {}
    rem_key: str | None = None
    for el, val in raw_dict.items():
        el_clean = str(el).strip()
        if not re.fullmatch(r"[A-Z][a-z]?", el_clean):
            continue
        s_val = str(val).strip()
        if s_val == "#":
            rem_key = el_clean
        elif ":" in s_val:
            lo, hi = s_val.split(":", 1)
            parsed[el_clean] = 0.5 * (float(lo) + float(hi))
        else:
            try:
                parsed[el_clean] = float(s_val)
            except ValueError:
                continue

    explicit_sum = sum(parsed.values())
    if rem_key is not None:
        parsed[rem_key] = max(0.0, 100.0 - explicit_sum)
    elif explicit_sum > 0:
        for el_k in list(parsed.keys()):
            parsed[el_k] = 100.0 * parsed[el_k] / explicit_sum
    return parsed


def parse_elements_from_composition(comp_str: str) -> list[str]:
    """Extract ordered element symbols from a -c composition string or dict."""
    return list(parse_composition_dict(comp_str).keys())


def load_scored_asd_lines(
    elements: list[str],
    wl_range: tuple[float, float],
    cache_dir: Path | None = None,
    include_common: bool = True,
    t_ref_ev: float = 0.95,
) -> pd.DataFrame:
    """
    Loads cached NIST ASD lines for `elements` strictly within `wl_range` (air nm)
    and attaches `wl_nm` and Saha-Boltzmann LTE observability score `lte_score`.
    """
    df = load_lines(
        set_names=[],
        wl_range=wl_range,
        cache_range=(340.0, 900.0),
        cache_dir=cache_dir,
        include_common=include_common,
        elements=elements,
    )
    if df.empty:
        return df

    out = df.copy()
    out["wl_nm"] = (
        out["wavelength_nm"]
        .fillna(out.get("obs_wl_nm", float("nan")))
        .fillna(out.get("ritz_wl_nm", float("nan")))
    )
    acc_w = out["accuracy"].map(lambda a: ACCURACY_WEIGHTS.get(str(a).strip(), 0.50)).to_numpy()
    b_ok = out["boltzmann_ok"].fillna(False)
    emissivity = (
        out["g_k"].fillna(1.0)
        * out["Aki"].fillna(0.0)
        * (2.718281828459045 ** (-out["Ek_eV"].fillna(4.0) / t_ref_ev))
    ).where(b_ok, out["rel_intensity"].fillna(0.0) * 1e4)
    out["lte_score"] = emissivity.clip(lower=0.0) * acc_w
    return out
```

### 4.2 Create `scripts/asd_roi_discovery.py`

```python
#!/usr/bin/env python3
"""
ASD & Composition-Informed Spectral Region-of-Interest (ROI) Discovery Engine.
Uses scripts/nist_lines.py (data/nist_lines/) as the single source of truth for
NIST ASD line loading and LTE scoring.
"""

import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import pandas as pd

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from nist_lines import DEFAULT_CACHE_DIR, build_cache, load_scored_asd_lines  # noqa: E402


DEFAULT_ROI_OUTPUT_PATH = SCRIPT_DIR.parent / "data" / "regionsOfInterest.json"
AMBIENT_AIR_ELEMENTS = {"H", "O", "N"}


@dataclass
class SpectralRoiSpec:
    roiId: str
    minNm: float
    maxNm: float
    centerNm: float
    startChannelIdx: int
    endChannelIdx: int
    channelIndices: List[int]
    targetElements: List[str]
    dominantSpecies: str
    elementCategory: str
    meanEiEv: float
    meanEkEv: float
    importanceWeight: float
    roiRSquared: float = 0.0
    roiRmse: float = 0.0


class AsdRoiDiscoveryEngine:
    def __init__(
        self,
        family_max_compositions: Dict[str, Dict[str, float]],
        lam_grid: np.ndarray,
        cache_dir: Path = DEFAULT_CACHE_DIR
    ):
        self.family_max_comp = family_max_compositions
        self.lam_grid = lam_grid
        self.lam_min = float(lam_grid[0])
        self.lam_max = float(lam_grid[-1])
        self.cache_dir = cache_dir

        all_elements = sorted({
            el for comp in family_max_compositions.values()
            for el, pct in comp.items()
            if pct > 0.0 and el not in AMBIENT_AIR_ELEMENTS
        })
        if not all_elements:
            all_elements = ["Fe", "Cr", "Ni", "Mn", "Si", "Cu", "Sn", "Pb", "Zn", "Al", "Mg", "Ti", "Mo", "V", "C"]

        build_cache(
            set_names=[],
            wl_range=(340.0, 900.0),
            cache_dir=self.cache_dir,
            elements=all_elements,
            include_common=True,
            force=False
        )
        df = load_scored_asd_lines(
            elements=all_elements,
            wl_range=(self.lam_min, self.lam_max),
            cache_dir=self.cache_dir,
            include_common=False
        )
        self.lines_df = df[~df["element"].isin(AMBIENT_AIR_ELEMENTS)].copy() if not df.empty else df

    def discover_rois_for_family(
        self, family_name: str
    ) -> Tuple[List[SpectralRoiSpec], np.ndarray, List[int]]:
        p_channels = len(self.lam_grid)
        comp = self.family_max_comp.get(family_name, {})
        if not comp or self.lines_df.empty:
            all_idx = list(range(p_channels))
            return [], np.ones(p_channels, dtype=np.float64), all_idx

        matrix_els = [el for el, c in comp.items() if c >= 5.0 and el not in AMBIENT_AIR_ELEMENTS]
        minor_els = [el for el, c in comp.items() if 0.5 <= c < 5.0 and el not in AMBIENT_AIR_ELEMENTS]
        tramp_els = [el for el, c in comp.items() if 0.0 < c < 0.5 and el not in AMBIENT_AIR_ELEMENTS]

        mat_df = self.lines_df[self.lines_df["element"].isin(matrix_els)].copy()
        if not mat_df.empty:
            mat_df["norm_em"] = mat_df["lte_score"] / max(mat_df["lte_score"].max(), 1e-12)
            strong_mat = mat_df[mat_df["norm_em"] > 0.02]
            mat_wls = strong_mat["wl_nm"].to_numpy()
            mat_ems = strong_mat["norm_em"].to_numpy()
        else:
            mat_wls, mat_ems = np.array([]), np.array([])

        selected_rows = []
        for el, c_max in comp.items():
            if c_max <= 0.0 or el in AMBIENT_AIR_ELEMENTS:
                continue
            el_df = self.lines_df[(self.lines_df["element"] == el) & (self.lines_df["lte_score"] > 0)].copy()
            if el_df.empty:
                continue

            if el in matrix_els:
                iso = np.ones(len(el_df))
                n_keep = 14
                cat_w = 1.0
            else:
                if len(mat_wls) > 0:
                    dist = np.abs(el_df["wl_nm"].to_numpy()[:, None] - mat_wls[None, :])
                    overlap = (dist <= 0.35) * mat_ems[None, :]
                    iso = 1.0 / (1.0 + 4.0 * overlap.sum(axis=1))
                else:
                    iso = np.ones(len(el_df))
                n_keep = 8 if el in minor_els else 6
                cat_w = 1.5 if el in minor_els else 2.0

            el_df["roi_score"] = (c_max ** 0.65) * el_df["lte_score"] * iso
            el_df["cat_weight"] = cat_w
            el_df = el_df.sort_values("roi_score", ascending=False)
            kept = []
            for _, row in el_df.iterrows():
                if all(abs(row["wl_nm"] - r["wl_nm"]) >= 0.60 for r in kept):
                    kept.append(row)
                if len(kept) >= n_keep:
                    break
            selected_rows.extend(kept)

        if not selected_rows:
            all_idx = list(range(p_channels))
            return [], np.ones(p_channels, dtype=np.float64), all_idx

        sel_df = pd.DataFrame(selected_rows).sort_values("wl_nm").reset_index(drop=True)

        pad_nm = 0.85
        clusters: List[List[pd.Series]] = []
        cur_cluster = [sel_df.iloc[0]]
        cur_min = float(sel_df.iloc[0]["wl_nm"]) - pad_nm
        cur_max = float(sel_df.iloc[0]["wl_nm"]) + pad_nm

        for idx in range(1, len(sel_df)):
            row = sel_df.iloc[idx]
            r_min = float(row["wl_nm"]) - pad_nm
            r_max = float(row["wl_nm"]) + pad_nm
            if r_min <= cur_max + 0.40 and (r_max - cur_min) <= 6.0:
                cur_cluster.append(row)
                cur_max = max(cur_max, r_max)
            else:
                clusters.append(cur_cluster)
                cur_cluster = [row]
                cur_min, cur_max = r_min, r_max
        clusters.append(cur_cluster)

        rois: List[SpectralRoiSpec] = []
        raw_weights = []
        roi_weights_p = np.zeros(p_channels, dtype=np.float64)
        last_assigned_idx = -1

        for cl in clusters:
            cl_df = pd.DataFrame(cl)
            w_min = max(self.lam_min, float(cl_df["wl_nm"].min()) - pad_nm)
            w_max = min(self.lam_max, float(cl_df["wl_nm"].max()) + pad_nm)
            raw_idx = np.where((self.lam_grid >= w_min) & (self.lam_grid <= w_max))[0]
            if len(raw_idx) == 0:
                continue

            start_idx = max(int(raw_idx[0]), last_assigned_idx + 1)
            end_idx = int(raw_idx[-1])
            if end_idx - start_idx + 1 < 3:
                end_idx = min(p_channels - 1, start_idx + 2)
            if end_idx - start_idx + 1 < 2:
                continue

            ch_idx = list(range(start_idx, end_idx + 1))
            last_assigned_idx = end_idx

            els = list(dict.fromkeys(cl_df.sort_values("roi_score", ascending=False)["element"].tolist()))
            dom_sp = str(cl_df.sort_values("roi_score", ascending=False).iloc[0]["species"])
            mean_ei = float(cl_df["Ei_eV"].dropna().mean()) if cl_df["Ei_eV"].notna().any() else 0.5
            mean_ek = float(cl_df["Ek_eV"].dropna().mean()) if cl_df["Ek_eV"].notna().any() else 4.0

            has_tramp = any(e in tramp_els for e in els)
            has_minor = any(e in minor_els for e in els)
            cat_str = "TRAMP_DIAGNOSTIC" if has_tramp else ("MINOR_ALLOY" if has_minor else "MATRIX")

            w_val = float(cl_df["cat_weight"].max())
            raw_weights.append(w_val)
            for p in ch_idx:
                roi_weights_p[p] = w_val

            actual_min_nm = float(self.lam_grid[start_idx])
            actual_max_nm = float(self.lam_grid[end_idx])
            roi_id = f"ROI_{len(rois)+1:02d}_{'_'.join(els[:2])}_{int(round(actual_min_nm))}_{int(round(actual_max_nm))}"
            rois.append(SpectralRoiSpec(
                roiId=roi_id,
                minNm=round(actual_min_nm, 4),
                maxNm=round(actual_max_nm, 4),
                centerNm=round(0.5 * (actual_min_nm + actual_max_nm), 4),
                startChannelIdx=start_idx,
                endChannelIdx=end_idx,
                channelIndices=ch_idx,
                targetElements=els,
                dominantSpecies=dom_sp,
                elementCategory=cat_str,
                meanEiEv=round(mean_ei, 4),
                meanEkEv=round(mean_ek, 4),
                importanceWeight=w_val
            ))

        total_w = sum(raw_weights) or 1.0
        for r in rois:
            r.importanceWeight = round(r.importanceWeight / total_w, 6)

        union_indices = [p for r in rois for p in r.channelIndices]
        if not union_indices:
            return [], np.ones(p_channels, dtype=np.float64), list(range(p_channels))

        return rois, roi_weights_p, union_indices
```

### 4.3 Update `scripts/gpu_calibration_engine.py`
1. **Refactor Imports & `AsdLineAdvisor`:**
   - Import `DEFAULT_CACHE_DIR`, `build_cache`, `load_scored_asd_lines`, and `parse_composition_dict` from `nist_lines.py`[cite: 18, 22].
   - Import `AsdRoiDiscoveryEngine`, `DEFAULT_ROI_OUTPUT_PATH`, and `asdict` from `asd_roi_discovery.py` / `dataclasses`.
   - Remove `build_family_tramp_weights` from `AsdLineAdvisor`; keep `build_resonance_mask` and `get_asd_anchor_windows` unchanged for `solve_material_agnostic_spectral_response`[cite: 22].
2. **Pass 1 vs. Pass 2 Masking in `GpuLibsCalibrationEngine.run()`:**
   - **Pass 1:** Run `optimize_family_rte_gpu` with `torch.ones(p_channels)` (uniform full-spectrum weights) and `r_gpu_init = torch.ones(p_channels)` so `solve_material_agnostic_spectral_response` receives the exact same Pass-1 RTE warm-up spectra as `v0.9.8`[cite: 22].
   - **Global Response Fit:** Run `r_vec = self.solve_material_agnostic_spectral_response(...)` unchanged[cite: 22].
   - **Pass 2 (ROI-Exclusive Optimization):** Compute `family_max_compositions` (element-wise max wt.% per family across staged standards), instantiate `roi_engine = AsdRoiDiscoveryEngine(family_max_compositions, lam_meas, self.nist_lines_dir)`, and for each family call `rois, roi_weights_p, union_indices = roi_engine.discover_rois_for_family(family_name)`. Pass `roi_weights_gpu` into `optimize_family_rte_gpu` (both for the family fit and the spatial point bootstrap).
3. **ROI-Scoped `optimize_family_rte_gpu` & `evaluate_and_export_rois_json`:**
   - Inside `optimize_family_rte_gpu`, compute `alpha_scale` (`scaleFactor`), `w_mse`, `cos_loss`, `rSquared` (`roiRSquared`), and `rmse` (`roiRmse`) strictly over `roi_mask = (roi_weights_gpu > 0.0)`, while also computing `wholeSpectrumRSquared` and `wholeSpectrumRmse`[cite: 22].
   - After Pass 2 converges for family $f$, evaluate each `SpectralRoiSpec`'s `roiRSquared` and `roiRmse` on `y_hat_fam = scaleFactor * i_normed` and upsert `<repo_root>/data/regionsOfInterest.json` via `evaluate_and_export_rois_json(...)`.

---

## 5. Acceptance Criteria & Verification

1. **Git Branch Integrity:** All commits for this track are made directly on `feat-instrument-profile-optimisation` (`git branch --show-current == feat-instrument-profile-optimisation`)[cite: 23].
2. **Zero Code Duplication:** `parse_composition_dict` and `load_scored_asd_lines` exist solely in `scripts/nist_lines.py` and are imported by both `scripts/asd_roi_discovery.py` and `scripts/gpu_calibration_engine.py`[cite: 18, 22].
3. **Unchanged Global `spectralResponse`:** `solve_material_agnostic_spectral_response` produces the exact same 8-knot P-spline $R(\lambda) \in [0.25, 2.50]$ as `v0.9.8`[cite: 22].
4. **ROI-Only Plasma Fit & `data/regionsOfInterest.json` Export:**
   - Running `./calibrate.sh` updates `conf/instrument_profile.json` with ROI-optimized `plasmaZones` and `scaleFactor`, and writes `<repo_root>/data/regionsOfInterest.json`.
   - Every ROI in `data/regionsOfInterest.json` has `len(channelIndices) >= 3`, no ambient air elements (`H`, `O`, `N`), and strict channel disjointness (`sum(len(r["channelIndices"])) == len(unionChannelIndices)`).
   - `calibrationStats.rSquared` and `rmse` in `conf/instrument_profile.json` match `calibrationMetrics.roiRSquared` and `roiRmse` in `data/regionsOfInterest.json` to 6 decimal places.
5. **End-to-End Parity:** `./run.sh` executes without error and generates synthetic spectra whose ROI-sliced $R^2$ and RMSE match `data/regionsOfInterest.json` in the Streamlit-Plotly viewer app[cite: 20, 24].