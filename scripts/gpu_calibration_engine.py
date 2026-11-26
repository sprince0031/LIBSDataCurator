#!/usr/bin/env python3
from __future__ import annotations
"""
GPU-Accelerated Multi-Material & Multi-Family LIBS Calibration Engine (v0.9.8)
Hardware Target: NVIDIA RTX 4070 MAX SUPER (PyTorch CUDA) / CPU Fallback
Key Features:
  1. Complete removal of post-NIST Voigt broadening & voigtProfile JSON objects.
  2. Automatic NIST ASD line acquisition & caching into data/nist_lines/ via scripts/nist_lines.py
     based on the material compositions passed via -c during calibrate.sh.
  3. Morphological Opening continuum removal (default structuringElementSize = 65 channels).
  4. 1:1 Normalized 2-Zone Radiative Transfer Equation (RTE) matching Java SpectrumUtils.
  5. ASD-Anchored Peak-Envelope P-Spline Spectral Response R(lambda) with Inverse-Family-Size
     Balanced Pooling, Huber Loss, and 2-Pass Alternating RTE Synchronization across all families.
"""

import json
import logging
import os
import re
import sys
from pathlib import Path

_script_dir = Path(__file__).resolve().parent
_repo_root = _script_dir.parent
if str(_repo_root) not in sys.path:
    sys.path.insert(0, str(_repo_root))
if str(_script_dir) not in sys.path:
    sys.path.insert(0, str(_script_dir))
from typing import Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
try:
    import torch
    import torch.nn.functional as F
except ImportError:
    torch = None
    F = None
from scipy.interpolate import BSpline
from scipy.ndimage import grey_opening

# Import NIST ASD line fetcher & loader from scripts/nist_lines.py
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from nist_lines import (  # noqa: E402
    build_cache,
    load_lines,
    parse_composition_dict,
    accuracy_to_uncertainty,
    ACCURACY_UNCERTAINTY,
)

DEFAULT_MORPHOLOGICAL_STRUCT_SIZE = 65
DEFAULT_NIST_LINES_DIR = SCRIPT_DIR.parent / "data" / "nist_lines"

logger = logging.getLogger("gpu_calibration_engine")


class AsdLineAdvisor:
    """
    Ensures all elements present in the -c calibration compositions are cached in
    data/nist_lines/ via nist_lines.py, and computes ASD-informed resonance masks,
    tramp weights, and P-spline anchor windows.
    """

    def __init__(
        self,
        cache_dir: Path,
        family_compositions: Dict[str, Dict[str, float]],
        lam_min: float,
        lam_max: float
    ):
        self.cache_dir = cache_dir
        self.family_compositions = family_compositions
        self.lam_min = lam_min
        self.lam_max = lam_max

        # Collect union of all chemical elements across all staged -c compositions
        all_elements = sorted({
            el for comp in family_compositions.values() for el, pct in comp.items() if pct > 0.0
        })
        if not all_elements:
            all_elements = ["Fe", "Cr", "Ni", "Mn", "Si", "Cu", "Sn", "Pb", "Zn", "Al", "Mg", "Ti", "Mo", "V", "C"]

        logger.info(f"[ASD Advisor] Ensuring NIST ASD line cache in {self.cache_dir} for elements: {all_elements}")
        build_cache(
            set_names=[],
            wl_range=(340.0, 900.0),
            cache_dir=self.cache_dir,
            elements=all_elements,
            include_common=True,
            force=False
        )
        self.lines_df = self._load_scored_lines(all_elements)

    def _load_scored_lines(self, elements: List[str]) -> pd.DataFrame:
        df = load_lines(
            set_names=[],
            wl_range=(self.lam_min, self.lam_max),
            cache_range=(340.0, 900.0),
            cache_dir=self.cache_dir,
            include_common=True,
            elements=elements
        )
        if df.empty:
            return df

        df = df.copy()
        df["wl_nm"] = df["wavelength_nm"].fillna(df.get("obs_wl_nm", np.nan)).fillna(df.get("ritz_wl_nm", np.nan))
        t_ref = 0.95
        acc_unc = df["accuracy"].map(accuracy_to_uncertainty).to_numpy()
        acc_w = 1.0 / (1.0 + acc_unc)
        emissivity = np.where(
            df["boltzmann_ok"].fillna(False),
            df["g_k"].fillna(1.0) * df["Aki"].fillna(0.0) * np.exp(-df["Ek_eV"].fillna(4.0) / t_ref),
            0.0
        )
        df["lte_score"] = np.maximum(emissivity, 0.0) * acc_w
        return df

    def build_resonance_mask(self, lam_meas: np.ndarray, manifest_masks: Optional[List[Dict]] = None) -> np.ndarray:
        res_mask = np.ones_like(lam_meas, dtype=np.float64)
        if manifest_masks:
            for win in manifest_masks:
                res_mask[(lam_meas >= win["minNm"]) & (lam_meas <= win["maxNm"])] = 0.0

        if self.lines_df.empty:
            return res_mask

        # Mask ambient air H_alpha (656.28 nm) and O I / N I strong lines
        atmos = self.lines_df[
            (self.lines_df["element"].isin(["H", "O", "N"])) &
            (self.lines_df["rel_intensity"].fillna(0) > 100)
        ]
        for wl in atmos["wl_nm"].to_numpy():
            res_mask[np.abs(lam_meas - wl) <= 1.2] = 0.0

        # Mask strong ground-state resonance transitions (Ei_eV <= 0.50 eV) of major matrix elements
        matrix_els = sorted({
            el for comp in self.family_compositions.values() for el, pct in comp.items() if pct >= 5.0
        }) or ["Fe", "Cr", "Cu", "Al", "Ni", "Sn"]

        for el in matrix_els:
            el_df = self.lines_df[
                (self.lines_df["element"] == el) &
                (self.lines_df["Ei_eV"].fillna(10.0) <= 0.50) &
                (self.lines_df["lte_score"] > 0)
            ]
            if el_df.empty:
                continue
            thresh = np.percentile(el_df["lte_score"], 90)
            top_res = el_df[el_df["lte_score"] >= thresh].nlargest(8, "lte_score")
            for wl in top_res["wl_nm"].to_numpy():
                res_mask[np.abs(lam_meas - wl) <= 0.60] = 0.0

        return res_mask

    def build_family_tramp_weights(
        self, family_name: str, lam_meas: np.ndarray, manifest_tramp: Optional[List[Dict]] = None
    ) -> np.ndarray:
        weights = np.ones_like(lam_meas, dtype=np.float64)
        if manifest_tramp:
            for win in manifest_tramp:
                weights[(lam_meas >= win["minNm"]) & (lam_meas <= win["maxNm"])] = float(win["weight"])

        comp = self.family_compositions.get(family_name, {})
        if self.lines_df.empty or not comp:
            return weights

        matrix_els = [el for el, pct in comp.items() if pct >= 40.0]
        tramp_els = [el for el, pct in comp.items() if 0.0 < pct < 25.0]

        matrix_wls = self.lines_df[
            (self.lines_df["element"].isin(matrix_els)) &
            (self.lines_df["lte_score"] > np.percentile(self.lines_df["lte_score"].dropna(), 75))
        ]["wl_nm"].to_numpy() if matrix_els else np.array([])

        for el in tramp_els:
            el_lines = self.lines_df[
                (self.lines_df["element"] == el) & (self.lines_df["lte_score"] > 0)
            ].copy()
            if el_lines.empty:
                continue

            if len(matrix_wls) > 0:
                min_dist = np.min(np.abs(el_lines["wl_nm"].to_numpy()[:, None] - matrix_wls[None, :]), axis=1)
                isolation_factor = np.where(min_dist > 0.35, 1.0, 0.25)
            else:
                isolation_factor = 1.0

            el_lines["rank_score"] = el_lines["lte_score"] * isolation_factor
            top_lines = el_lines.nlargest(6, "rank_score")

            pct = comp[el]
            el_boost = 3.5 if pct < 0.5 else (2.8 if pct < 2.0 else 2.2)
            for wl in top_lines["wl_nm"].to_numpy():
                mask = np.abs(lam_meas - wl) <= 0.45
                weights[mask] = np.maximum(weights[mask], el_boost)

        return weights

    def get_asd_anchor_windows(
        self, family_name: str, lam_meas: np.ndarray, res_mask: np.ndarray
    ) -> List[np.ndarray]:
        comp = self.family_compositions.get(family_name, {})
        active_els = [el for el, pct in comp.items() if pct >= 0.05] or ["Fe", "Cr", "Cu", "Al", "Ni", "Sn", "Si", "Mn"]

        if not self.lines_df.empty:
            cand = self.lines_df[
                (self.lines_df["element"].isin(active_els)) &
                (self.lines_df["Ei_eV"].fillna(0.0) > 0.40) &
                (self.lines_df["lte_score"] > 0)
            ].sort_values("lte_score", ascending=False)

            selected_wls: List[float] = []
            for wl in cand["wl_nm"].to_numpy():
                if all(abs(wl - s_wl) >= 2.5 for s_wl in selected_wls):
                    selected_wls.append(float(wl))
                if len(selected_wls) >= 85:
                    break

            windows = []
            for wl in sorted(selected_wls):
                idx = np.where((np.abs(lam_meas - wl) <= 1.5) & (res_mask > 0.5))[0]
                if len(idx) >= 3:
                    windows.append(idx)
            if windows:
                return windows

        win_edges = np.arange(lam_meas[0], lam_meas[-1] + 4.0, 4.0)
        return [
            np.where((lam_meas >= win_edges[i]) & (lam_meas < win_edges[i + 1]) & (res_mask > 0.5))[0]
            for i in range(len(win_edges) - 1)
        ]


class GpuLibsCalibrationEngine:
    def __init__(self, manifest_path: str):
        self.manifest_path = Path(manifest_path)
        with open(self.manifest_path, "r", encoding="utf-8") as f:
            self.manifest = json.load(f)

        # Configure logging directory and log file
        logs_dir_str = self.manifest.get("logsDir")
        if logs_dir_str:
            self.logs_dir = Path(logs_dir_str)
        else:
            candidate1 = self.manifest_path.resolve().parent.parent / "logs"
            candidate2 = Path.cwd() / "logs"
            self.logs_dir = candidate1 if candidate1.exists() else candidate2
        self.logs_dir.mkdir(parents=True, exist_ok=True)
        self.log_file = (self.logs_dir / "gpu_calibration_engine.log").resolve()
        self._init_logger()

        if torch is not None:
            self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
            dev_name = torch.cuda.get_device_name(0) if torch.cuda.is_available() else "CPU"
            logger.info(f"[GPU Engine] Active Device: {self.device} ({dev_name})")
        else:
            self.device = None
            logger.info("[GPU Engine] Active Device: CPU (torch not installed)")

        bc_params = self.manifest.get("baselineCorrectionParams", {})
        self.struct_size = max(int(bc_params.get("structuringElementSize", DEFAULT_MORPHOLOGICAL_STRUCT_SIZE)), 55)

        default_te_grid = [0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.5, 1.7, 2.0]
        default_ne_grid = [1.0e16, 5.0e16, 1.0e17, 5.0e17]
        self.te_grid = np.array(self.manifest.get("teAnchorGridEv", default_te_grid), dtype=np.float64)
        self.ne_grid = np.array(self.manifest.get("neAnchorGridCm3", default_ne_grid), dtype=np.float64)
        self.log_ne_grid = np.log10(self.ne_grid)
        self.n_te = len(self.te_grid)
        self.n_ne = len(self.ne_grid)

        self.strict = bool(self.manifest.get("strict", False))
        nist_dir_str = self.manifest.get("nistLinesDir")
        self.nist_lines_dir = Path(nist_dir_str) if nist_dir_str else DEFAULT_NIST_LINES_DIR
        self.asd_advisor: Optional[AsdLineAdvisor] = None

    def get_calibrated_materials(self, family: str) -> List[str]:
        """R10.2: Materials listed under manifest key validationMaterials are loaded but excluded from fitting."""
        validation_mats = set(str(m) for m in self.manifest.get("validationMaterials", []))
        family_mats = self.manifest.get("materialFamilies", {}).get(family, [])
        cal_mats = []
        for m in family_mats:
            mat_name = str(m.get("materialName", ""))
            if mat_name and mat_name not in validation_mats:
                cal_mats.append(mat_name)
        return cal_mats

    def check_strict_flags(self, flags: List[str]) -> None:
        """R9, R10: With --strict, any diagnostic flag causes exit with status 2."""
        if self.manifest.get("strict", False) or getattr(self, "strict", False):
            if flags:
                logger.error(f"[Strict Mode] Diagnostic flags raised: {flags}")
                sys.exit(2)

    def export_debug_json(
        self,
        family: str,
        conditioned_data: dict,
        rois_data: dict,
        solver_trace: dict,
        fit_comparison: dict
    ) -> None:
        """R13.1: Intermediate debug outputs dumped as structured JSONs under temp/calibration_debug/<materialFamily>/."""
        base_dir = Path(self.manifest.get("debugOutputDir", "temp/calibration_debug"))
        fam_dir = base_dir / family
        fam_dir.mkdir(parents=True, exist_ok=True)

        def _clean(obj):
            if isinstance(obj, np.ndarray):
                return obj.tolist()
            if isinstance(obj, (np.floating, float)):
                return float(obj)
            if isinstance(obj, (np.integer, int)):
                return int(obj)
            if isinstance(obj, (np.bool_, bool)):
                return bool(obj)
            if isinstance(obj, dict):
                return {k: _clean(v) for k, v in obj.items()}
            if isinstance(obj, (list, tuple)):
                return [_clean(v) for v in obj]
            return obj

        for mat_name, c_data in conditioned_data.items():
            out_p = fam_dir / f"conditioned_{mat_name}.json"
            out_p.write_text(json.dumps(_clean(c_data), indent=2), encoding="utf-8")

        (fam_dir / f"rois_{family}.json").write_text(
            json.dumps(_clean(rois_data), indent=2), encoding="utf-8"
        )
        (fam_dir / f"solver_trace_{family}.json").write_text(
            json.dumps(_clean(solver_trace), indent=2), encoding="utf-8"
        )

        for mat_name, f_data in fit_comparison.items():
            out_p = fam_dir / f"fit_comparison_{mat_name}.json"
            out_p.write_text(json.dumps(_clean(f_data), indent=2), encoding="utf-8")

    def upsert_family_profile(
        self,
        family: str,
        family_profile: dict,
        family_rois: Union[List[dict], dict]
    ) -> None:
        """R10.3, R13.2: Calibrating a single family upserts instrument_profile.json and regionsOfInterest.json without wiping existing families."""
        out_profile_path = Path(self.manifest.get("outputProfilePath", "conf/instrument_profile.json"))
        if out_profile_path.exists():
            profile_json = json.loads(out_profile_path.read_text(encoding="utf-8"))
        else:
            profile_json = {
                "schemaVersion": "1.0.0",
                "emissionScale": "absolute",
                "materialFamilyProfiles": {},
            }
        if "materialFamilyProfiles" not in profile_json or not isinstance(profile_json["materialFamilyProfiles"], dict):
            profile_json["materialFamilyProfiles"] = {}
        profile_json["materialFamilyProfiles"][family] = family_profile
        out_profile_path.parent.mkdir(parents=True, exist_ok=True)
        out_profile_path.write_text(json.dumps(profile_json, indent=2), encoding="utf-8")

        out_rois_path = Path(self.manifest.get("outputRoisPath", "data/regionsOfInterest.json"))
        if out_rois_path.exists():
            rois_json = json.loads(out_rois_path.read_text(encoding="utf-8"))
        else:
            rois_json = {
                "schemaVersion": "2.0",
                "instrumentName": self.manifest.get("instrumentName", "LSA_Calibrated_Instrument"),
            }

        has_families_key = "families" in rois_json
        has_mat_fam_key = "materialFamilyRois" in rois_json

        if not has_families_key and not has_mat_fam_key:
            rois_json["materialFamilyRois"] = {}
            has_mat_fam_key = True

        if has_mat_fam_key:
            if isinstance(family_rois, dict):
                rois_json["materialFamilyRois"][family] = family_rois
            else:
                rois_json["materialFamilyRois"][family] = {
                    "calibratedMaterials": self.get_calibrated_materials(family),
                    "rois": family_rois,
                }

        if has_families_key:
            if isinstance(family_rois, dict):
                rois_json["families"][family] = family_rois
            else:
                rois_json["families"][family] = {
                    "regionsOfInterest": family_rois,
                    "rois": family_rois,
                }

        out_rois_path.parent.mkdir(parents=True, exist_ok=True)
        out_rois_path.write_text(json.dumps(rois_json, indent=2), encoding="utf-8")

    def _init_logger(self):
        logger.setLevel(logging.DEBUG)
        if not logger.handlers:
            fmt = logging.Formatter(
                "[%(asctime)s]|[%(name)s]|[%(levelname)s]: %(message)s",
                datefmt="%d-%m-%Y %H:%M:%S"
            )
            fh = logging.FileHandler(self.log_file, mode="a", encoding="utf-8")
            fh.setLevel(logging.DEBUG)
            fh.setFormatter(fmt)
            logger.addHandler(fh)

            ch = logging.StreamHandler(sys.stdout)
            ch.setLevel(logging.INFO)
            ch.setFormatter(fmt)
            logger.addHandler(ch)

    def strip_baseline_morphological_opening(
        self, spectrum: np.ndarray, struct_size: Optional[int] = None
    ) -> np.ndarray:
        size = struct_size if struct_size is not None else self.struct_size
        baseline = grey_opening(spectrum, size=size)
        return np.maximum(spectrum - baseline, 0.0)

    def load_and_clean_material_csvs(
        self, csv_paths: List[str]
    ) -> Tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, int]:
        full_scale = float(self.manifest.get("detectorFullScale", 65535.0))
        y_sat = float(self.manifest.get("saturationThresholdFactor", 0.9)) * full_scale

        all_shots_list, point_means_list = [], []
        lam_meas = None

        for path in csv_paths:
            delim = self.manifest.get("csvDelimiter")
            if not delim:
                with open(path, "r", encoding="utf-8") as f:
                    first_line = f.readline()
                delim = ";" if ";" in first_line else ("\t" if "\t" in first_line else ",")
            df = pd.read_csv(path, sep=delim)

            first_col_val = self._try_parse_float(df.columns[0])
            start_col = 0 if (first_col_val is not None and first_col_val > 150.0) else 2

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
        sigma_mean = np.maximum(np.sqrt(var_shot / nv), 1.0)

        y_clean = self.strip_baseline_morphological_opening(raw_mean)
        return lam_meas, y_clean, sigma_mean, np.array(point_means_list, dtype=np.float64), shots_all.shape[0]

    @staticmethod
    def _try_parse_float(val: str) -> Optional[float]:
        try:
            return float(val)
        except (ValueError, TypeError):
            return None

    def load_nist_dictionary_for_material(
        self, nist_paths_map: Dict[str, str], lam_meas: np.ndarray
    ) -> torch.Tensor:
        d_cols = []
        for te in self.te_grid:
            for ne in self.ne_grid:
                key_sci = f"{te}_{ne:.1E}"
                key_raw = f"{te}_{ne}"
                csv_path = nist_paths_map.get(key_sci) or nist_paths_map.get(key_raw)
                df = pd.read_csv(csv_path)
                lam_nist = df.iloc[:, 0].to_numpy(dtype=np.float64)
                s_nist = df.iloc[:, 1].to_numpy(dtype=np.float64)

                s_interp = np.interp(lam_meas, lam_nist, s_nist, left=0.0, right=0.0)
                s_max = np.max(s_interp)
                if s_max > 1e-12:
                    s_interp = s_interp / s_max
                d_cols.append(s_interp.astype(np.float32))

        arr = np.column_stack(d_cols)
        if torch is not None and self.device is not None:
            return torch.tensor(arr, dtype=torch.float32, device=self.device)
        return arr

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
        s_interp = sampled.view(mf, p_ch, b_samples).permute(2, 0, 1).contiguous()
        s_max = torch.clamp(s_interp.amax(dim=2, keepdim=True), min=1e-12)
        return s_interp / s_max

    def evaluate_single_rte_spectrum_gpu(
        self, d_grid_gpu: torch.Tensor, params: Dict
    ) -> torch.Tensor:
        n_zones = len(params["Te"])
        te_t = torch.tensor(params["Te"], dtype=torch.float32, device=self.device)
        lne_t = torch.tensor(np.log10(params["Ne"]), dtype=torch.float32, device=self.device)
        v_t = torch.tensor(params["volumeFraction"], dtype=torch.float32, device=self.device)
        k_t = torch.tensor(params["kAbsorption"], dtype=torch.float32, device=self.device)

        s_z0 = self.interpolate_dictionary_bilinear_gpu(d_grid_gpu, te_t[0:1], lne_t[0:1]).squeeze(0)
        i_rad = v_t[0] * s_z0

        for z in range(1, n_zones):
            s_z = self.interpolate_dictionary_bilinear_gpu(d_grid_gpu, te_t[z:z+1], lne_t[z:z+1]).squeeze(0)
            i_rad = i_rad * torch.exp(-k_t[z] * s_z) + v_t[z] * s_z

        i_max = torch.clamp(i_rad.amax(dim=1, keepdim=True), min=1e-12)
        return i_rad / i_max

    def solve_material_agnostic_spectral_response(
        self,
        lam_meas: np.ndarray,
        y_all: np.ndarray,
        i_rte_all: np.ndarray,
        family_labels: List[str],
        res_mask: np.ndarray
    ) -> np.ndarray:
        m_count, p_count = y_all.shape
        k_knots = 8
        mu_r = 2500.0
        ridge_prior = 0.25

        interior = np.linspace(lam_meas[0], lam_meas[-1], k_knots - 2)
        knots = np.r_[[lam_meas[0]] * 3, interior, [lam_meas[-1]] * 3]
        b_mat = BSpline.design_matrix(lam_meas, knots, 3).toarray()
        delta2 = np.diff(np.eye(k_knots), 2, axis=0)
        p_reg = mu_r * (delta2.T @ delta2) + ridge_prior * np.eye(k_knots)

        fam_counts: Dict[str, int] = {}
        for fam in family_labels:
            fam_counts[fam] = fam_counts.get(fam, 0) + 1
        mat_family_weights = np.array([1.0 / fam_counts[fam] for fam in family_labels], dtype=np.float64)

        num_p = np.zeros(p_count, dtype=np.float64)
        den_p = np.zeros(p_count, dtype=np.float64)

        for m in range(m_count):
            fam_name = family_labels[m]
            y_m = y_all[m]
            i_m = i_rte_all[m]
            y_norm = y_m / np.maximum(np.max(y_m), 1e-12)
            i_norm = i_m / np.maximum(np.max(i_m), 1e-12)

            asd_windows = self.asd_advisor.get_asd_anchor_windows(fam_name, lam_meas, res_mask)
            valid_ratios, valid_win_indices, valid_win_weights = [], [], []

            for idx_arr in asd_windows:
                if len(idx_arr) == 0:
                    continue
                y_w_max = np.max(y_norm[idx_arr])
                i_w_max = np.max(i_norm[idx_arr])
                if y_w_max > 0.04 and i_w_max > 0.04:
                    log_ratio = np.log(y_w_max / i_w_max)
                    prom_weight = mat_family_weights[m] * np.sqrt(y_w_max * i_w_max)
                    valid_ratios.append(log_ratio)
                    valid_win_indices.append(idx_arr)
                    valid_win_weights.append(prom_weight)

            if not valid_ratios:
                continue

            median_offset = np.median(valid_ratios)
            for idx_arr, raw_lr, w_val in zip(valid_win_indices, valid_ratios, valid_win_weights):
                centered_lr = np.clip(raw_lr - median_offset, -0.85, 0.85)
                num_p[idx_arr] += w_val * centered_lr
                den_p[idx_arr] += w_val

        rho_bar = np.where(den_p > 0, num_p / np.maximum(den_p, 1e-12), 0.0)
        lhs = b_mat.T @ (den_p[:, None] * b_mat) + p_reg
        rhs = b_mat.T @ (den_p * rho_bar)
        beta = np.linalg.solve(lhs, rhs)

        log_r = b_mat @ beta
        active_channels = den_p > 0
        if np.any(active_channels):
            log_r -= np.median(log_r[active_channels])
        else:
            log_r -= np.mean(log_r)

        return np.clip(np.exp(log_r), 0.25, 2.50)

    def optimize_family_rte_gpu(
        self, d_family_gpu: torch.Tensor, y_family_gpu: torch.Tensor,
        r_gpu: torch.Tensor, tramp_w_gpu: torch.Tensor,
        n_zones: int = 2, n_mc_per_iter: int = 100000, n_iters: int = 5
    ) -> Dict:
        mf, p_ch = y_family_gpu.shape
        d_grid_gpu = d_family_gpu.view(mf, p_ch, self.n_te, self.n_ne)

        te_min = torch.tensor([0.60] + [0.50] * (n_zones - 1), dtype=torch.float32, device=self.device)
        te_max = torch.tensor([1.45] + [0.95] * (n_zones - 1), dtype=torch.float32, device=self.device)
        lne_min = torch.full((n_zones,), float(self.log_ne_grid[0]), dtype=torch.float32, device=self.device)
        lne_max = torch.full((n_zones,), float(self.log_ne_grid[-1]), dtype=torch.float32, device=self.device)
        v0_min, v0_max = 0.75, 0.95
        k_min = torch.tensor([0.0] + [0.50] * (n_zones - 1), dtype=torch.float32, device=self.device)
        k_max = torch.tensor([0.0] + [5.00] * (n_zones - 1), dtype=torch.float32, device=self.device)

        best_cost = float("inf")
        best_params = None
        best_scale = 1.0
        best_r2 = 0.0
        best_rmse = 1.0

        weights_p = tramp_w_gpu[None, :]
        y_max_fam = torch.clamp(y_family_gpu.amax(dim=1, keepdim=True), min=1e-12)
        y_mean_fam = y_family_gpu.mean(dim=1, keepdim=True)
        ss_tot = torch.clamp(((y_family_gpu - y_mean_fam) ** 2).sum(dim=1), min=1e-12)

        for _ in range(n_iters):
            te_cand = torch.empty((n_mc_per_iter, n_zones), device=self.device)
            lne_cand = torch.empty((n_mc_per_iter, n_zones), device=self.device)
            v_cand = torch.empty((n_mc_per_iter, n_zones), device=self.device)
            k_cand = torch.zeros((n_mc_per_iter, n_zones), device=self.device)

            for z in range(n_zones):
                te_cand[:, z] = torch.empty(n_mc_per_iter, device=self.device).uniform_(float(te_min[z]), float(te_max[z]))
                lne_cand[:, z] = torch.empty(n_mc_per_iter, device=self.device).uniform_(float(lne_min[z]), float(lne_max[z]))
                if z > 0:
                    k_cand[:, z] = torch.empty(n_mc_per_iter, device=self.device).uniform_(float(k_min[z]), float(k_max[z]))

            te_cand, _ = torch.sort(te_cand, dim=1, descending=True)
            if n_zones >= 2:
                te_cand[:, 1] = torch.minimum(te_cand[:, 1], te_cand[:, 0] - 0.12)
                te_cand[:, 1] = torch.clamp(te_cand[:, 1], min=float(self.te_grid[0]))

            v0_draws = torch.empty(n_mc_per_iter, device=self.device).uniform_(float(v0_min), float(v0_max))
            v_cand[:, 0] = v0_draws
            if n_zones == 2:
                v_cand[:, 1] = 1.0 - v0_draws
            else:
                rem = (1.0 - v0_draws)[:, None]
                outer_raw = torch.rand((n_mc_per_iter, n_zones - 1), device=self.device)
                v_cand[:, 1:] = rem * (outer_raw / outer_raw.sum(dim=1, keepdim=True))

            chunk_size = 12500
            costs_all = torch.empty(n_mc_per_iter, device=self.device)
            scales_all = torch.empty(n_mc_per_iter, device=self.device)
            r2_all = torch.empty(n_mc_per_iter, device=self.device)
            rmse_all = torch.empty(n_mc_per_iter, device=self.device)

            for start in range(0, n_mc_per_iter, chunk_size):
                end = min(start + chunk_size, n_mc_per_iter)
                s_z0 = self.interpolate_dictionary_bilinear_gpu(d_grid_gpu, te_cand[start:end, 0], lne_cand[start:end, 0])
                i_rad = v_cand[start:end, 0][:, None, None] * s_z0

                for z in range(1, n_zones):
                    s_z = self.interpolate_dictionary_bilinear_gpu(d_grid_gpu, te_cand[start:end, z], lne_cand[start:end, z])
                    k_z = k_cand[start:end, z][:, None, None]
                    v_z = v_cand[start:end, z][:, None, None]
                    i_rad = i_rad * torch.exp(-k_z * s_z) + v_z * s_z

                i_resp = i_rad * r_gpu[None, None, :]
                i_resp_max = torch.clamp(i_resp.amax(dim=2, keepdim=True), min=1e-12)
                i_normed = i_resp / i_resp_max

                num_s = (weights_p[None, :, :] * y_family_gpu[None, :, :] * i_normed).sum(dim=2)
                den_s = torch.clamp((weights_p[None, :, :] * (i_normed ** 2)).sum(dim=2), min=1e-12)
                alpha_mat = num_s / den_s
                alpha_family = alpha_mat.mean(dim=1)

                y_hat = alpha_mat[:, :, None] * i_normed
                residuals = y_family_gpu[None, :, :] - y_hat

                w_mse = (weights_p[None, :, :] * (residuals / y_max_fam[None, :, :]) ** 2).mean(dim=(1, 2))
                dot_prod = (weights_p[None, :, :] * y_family_gpu[None, :, :] * y_hat).sum(dim=2)
                norm_y = torch.sqrt((weights_p[None, :, :] * (y_family_gpu[None, :, :] ** 2)).sum(dim=2) + 1e-12)
                norm_h = torch.sqrt((weights_p[None, :, :] * (y_hat ** 2)).sum(dim=2) + 1e-12)
                cos_loss = (1.0 - (dot_prod / (norm_y * norm_h))).mean(dim=1)

                costs_all[start:end] = 0.5 * w_mse + 0.5 * cos_loss
                scales_all[start:end] = alpha_family

                ss_res = (residuals ** 2).sum(dim=2)
                r2_all[start:end] = (1.0 - (ss_res / ss_tot[None, :])).mean(dim=1)
                rmse_all[start:end] = torch.sqrt(((residuals / y_max_fam[None, :, :]) ** 2).mean(dim=(1, 2)))

            top_vals, top_idx = torch.topk(costs_all, k=100, largest=False)
            if float(top_vals[0]) < best_cost:
                best_cost = float(top_vals[0])
                win_i = top_idx[0]
                best_scale = float(scales_all[win_i])
                best_r2 = float(r2_all[win_i])
                best_rmse = float(rmse_all[win_i])
                best_params = {
                    "Te": te_cand[win_i].cpu().numpy(),
                    "Ne": (10.0 ** lne_cand[win_i]).cpu().numpy(),
                    "volumeFraction": v_cand[win_i].cpu().numpy(),
                    "kAbsorption": k_cand[win_i].cpu().numpy()
                }

            elite_te = te_cand[top_idx]
            elite_lne = lne_cand[top_idx]
            elite_v0 = v_cand[top_idx, 0]
            elite_k = k_cand[top_idx]

            te_min = torch.clamp(elite_te.min(dim=0).values - 0.03, min=torch.tensor([0.60] + [0.50]*(n_zones-1), device=self.device))
            te_max = torch.clamp(elite_te.max(dim=0).values + 0.03, max=torch.tensor([1.45] + [0.95]*(n_zones-1), device=self.device))
            lne_min = torch.clamp(elite_lne.min(dim=0).values - 0.05, min=float(self.log_ne_grid[0]))
            lne_max = torch.clamp(elite_lne.max(dim=0).values + 0.05, max=float(self.log_ne_grid[-1]))
            v0_min = max(0.75, float(elite_v0.min()) - 0.02)
            v0_max = min(0.95, float(elite_v0.max()) + 0.02)
            k_min = torch.clamp(elite_k.min(dim=0).values - 0.15, min=torch.tensor([0.0] + [0.50]*(n_zones-1), device=self.device))
            k_max = torch.clamp(elite_k.max(dim=0).values + 0.15, max=torch.tensor([0.0] + [5.00]*(n_zones-1), device=self.device))

        return {
            "bestParams": best_params,
            "scaleFactor": best_scale,
            "weightedCorrelationCost": best_cost,
            "rSquared": best_r2,
            "rmse": best_rmse
        }

    def compute_line_spread_add_sigma(
        self,
        det_wl: np.ndarray,
        r_nist: float,
    ) -> np.ndarray:
        """Compute per-channel additional Gaussian broadening sigma (nm) from manifest FWHM / R_nist.

        Uses spectral_resampling.calculate_add_sigma_nm when instrumentFwhmNm or
        instrumentResolvingPower is configured; defaults to 0.0 when absent or narrower than R_nist.
        """
        from spectral_resampling import calculate_add_sigma_nm

        det = np.asarray(det_wl, dtype=np.float64)
        inst_fwhm_cfg = self.manifest.get("instrumentFwhmNm")
        inst_r_cfg = self.manifest.get("instrumentResolvingPower")

        if inst_fwhm_cfg is not None:
            if np.isscalar(inst_fwhm_cfg):
                fwhm_inst = np.full(len(det), float(inst_fwhm_cfg), dtype=np.float64)
            else:
                fwhm_inst = np.asarray(inst_fwhm_cfg, dtype=np.float64)
                if len(fwhm_inst) != len(det):
                    raise ValueError(
                        f"instrumentFwhmNm length {len(fwhm_inst)} != detector channels {len(det)}"
                    )
        elif inst_r_cfg is not None and float(inst_r_cfg) > 0.0:
            fwhm_inst = det / float(inst_r_cfg)
        else:
            return np.zeros(len(det), dtype=np.float64)

        fwhm_src = det / max(float(r_nist), 1.0)
        return np.asarray(calculate_add_sigma_nm(fwhm_inst, fwhm_src), dtype=np.float64)

    def verify_gate_g1(
        self,
        fam_mats: List[str],
        dictionary: Dict[str, np.ndarray],
        comp_map: Dict[str, Dict[str, float]],
        fam_rois: List[Any],
    ) -> Tuple[bool, Optional[Dict[str, Any]]]:
        """Execute Gate G1 composition-scale check across two materials in a family."""
        from forward_model import composition_scale_check

        if len(fam_mats) < 2 or not fam_rois:
            return False, None

        m1, m2 = fam_mats[0], fam_mats[1]
        if m1 not in dictionary or m2 not in dictionary:
            return False, None
        c1 = comp_map.get(m1, {})
        c2 = comp_map.get(m2, {})
        D1 = dictionary[m1]
        D2 = dictionary[m2]
        if D1.shape[1] == 0 or D2.shape[1] == 0:
            return False, None

        n_cols = min(D1.shape[1], D2.shape[1])
        mid_col = n_cols // 2
        col_order = [mid_col] + [c for c in range(n_cols) if c != mid_col]
        for r in fam_rois:
            if getattr(r, "self_absorption_prone", False):
                continue
            el = getattr(r, "dominant_element", "")
            f1 = float(c1.get(el, 0.0))
            f2 = float(c2.get(el, 0.0))
            if f1 <= 0.0 or f2 <= 0.0:
                continue
            chans = getattr(r, "channel_indices", [])
            if not chans:
                continue
            ch_arr = np.asarray(chans, dtype=np.int64)
            for state_idx in col_order:
                peak_1 = float(np.max(D1[ch_arr, state_idx]))
                peak_2 = float(np.max(D2[ch_arr, state_idx]))
                if peak_1 > 0.0 and peak_2 > 0.0:
                    res = composition_scale_check(
                        peak_1=peak_1,
                        species_frac_1=1.0,
                        at_frac_1=f1,
                        peak_2=peak_2,
                        species_frac_2=1.0,
                        at_frac_2=f2,
                    )
                    return bool(res["passed"]), res

        return False, None

    def compute_family_calibration_stats(
        self,
        fam_mats: List[str],
        dictionary: Dict[str, np.ndarray],
        cond_map: Dict[str, Any],
        fam_rois: List[Any],
        union_chans: List[int],
        plasma_zones_json: List[Dict[str, Any]],
        nz_indices: List[int],
        scale_factor_final: float,
        spectral_response: np.ndarray,
        red_penalty: float,
        sharing_penalty: float,
        element_parity: Dict[str, float],
        n_params: int = 2,
    ) -> Tuple[Dict[str, Any], Dict[str, Any]]:
        """Compute family-wide and per-material calibrationStats using canonical RTE and metrics."""
        from forward_model import (
            PlasmaZoneSpec,
            apply_multi_zone_rte,
            apply_response_and_scale,
        )
        from match_metrics import compute_spectral_metrics
        from roi_joint_calibration import compute_sub_bin_variances

        zone_specs = [PlasmaZoneSpec.from_dict(z) for z in plasma_zones_json]
        thin_roi_sbs = [
            (r, sb) for r in fam_rois if not getattr(r, "self_absorption_prone", False) for sb in getattr(r, "sub_bins", [])
        ]
        if not thin_roi_sbs:
            thin_roi_sbs = [
                (r, sb) for r in fam_rois for sb in getattr(r, "sub_bins", [])
            ]

        per_material_stats: Dict[str, Any] = {}
        fit_comparison: Dict[str, Any] = {}

        chi2_list: List[float] = []
        roi_r2_list: List[float] = []
        roi_rmse_list: List[float] = []
        roi_rmse_raw_list: List[float] = []
        whole_r2_list: List[float] = []
        whole_rmse_list: List[float] = []
        whole_rmse_raw_list: List[float] = []

        for m in fam_mats:
            D_m = dictionary[m]
            cond = cond_map[m]
            y_meas_m = cond.preprocessed_mean
            n_ch = len(y_meas_m)

            if zone_specs and nz_indices:
                zone_spectra = [D_m[:, nz_indices[z["zoneIndex"]]] for z in plasma_zones_json]
                i_rad_m = apply_multi_zone_rte(zone_spectra, zone_specs)
                y_synth_m = apply_response_and_scale(
                    i_rad_m, spectral_response, scale_factor_final, emission_scale="absolute"
                )
            else:
                y_synth_m = np.zeros(n_ch, dtype=np.float64)

            if cond.lit_mask is not None and not np.all(cond.lit_mask):
                unlit_sig = cond.sigma_mean[~cond.lit_mask]
                dark_std = float(np.median(unlit_sig)) if len(unlit_sig) > 0 else 30.0
            else:
                dark_std = 30.0

            y_meas_sb: List[float] = []
            y_pred_sb: List[float] = []
            var_sb: List[float] = []
            for r, sb in thin_roi_sbs:
                sb_idx = np.asarray(sb, dtype=np.int64)
                var_info = compute_sub_bin_variances(
                    channels=list(sb),
                    mean_spectrum=y_meas_m,
                    sigma_mean=cond.sigma_mean,
                    model_lines=getattr(r, "model_lines", []),
                    model_error_fraction=0.10,
                    dark_std=dark_std,
                )
                y_meas_sb.append(float(var_info["Y"]))
                y_pred_sb.append(float(np.sum(y_synth_m[sb_idx])))
                var_sb.append(float(var_info.get("sigma_sq", float(var_info["sigma"]) ** 2)))

            if y_meas_sb:
                chi2_m = float(
                    compute_spectral_metrics(
                        np.asarray(y_meas_sb, dtype=np.float64),
                        np.asarray(y_pred_sb, dtype=np.float64),
                        variance=np.asarray(var_sb, dtype=np.float64),
                        normalization="none",
                        num_params=max(int(n_params), 1),
                    )["reduced_chi2"]
                )
            else:
                chi2_m = 0.0

            roi_mask = np.zeros(n_ch, dtype=bool)
            if union_chans:
                roi_mask[np.asarray(union_chans, dtype=np.int64)] = True
            if np.any(roi_mask):
                roi_met = compute_spectral_metrics(
                    y_meas_m, y_synth_m, mask=roi_mask, normalization="none"
                )
                roi_r2_m = float(roi_met["r2"])
                roi_rmse_m = float(roi_met["nrmse_max"])
                roi_rmse_raw_m = float(roi_met["rmse"])
            else:
                roi_r2_m, roi_rmse_m, roi_rmse_raw_m = 0.0, 0.0, 0.0

            lit_mask = np.asarray(cond.lit_mask, dtype=bool)
            if np.any(lit_mask):
                whole_met = compute_spectral_metrics(
                    y_meas_m, y_synth_m, mask=lit_mask, normalization="none"
                )
                whole_r2_m = float(whole_met["r2"])
                whole_rmse_m = float(whole_met["nrmse_max"])
                whole_rmse_raw_m = float(whole_met["rmse"])
            else:
                whole_r2_m, whole_rmse_m, whole_rmse_raw_m = 0.0, 0.0, 0.0

            chi2_list.append(chi2_m)
            roi_r2_list.append(roi_r2_m)
            roi_rmse_list.append(roi_rmse_m)
            roi_rmse_raw_list.append(roi_rmse_raw_m)
            whole_r2_list.append(whole_r2_m)
            whole_rmse_list.append(whole_rmse_m)
            whole_rmse_raw_list.append(whole_rmse_raw_m)

            per_material_stats[m] = {
                "roiChi2Reduced": round(chi2_m, 4),
                "roiRSquared": round(roi_r2_m, 6),
                "roiRmse": round(roi_rmse_m, 6),
                "roiRmseRaw": round(roi_rmse_raw_m, 6),
                "wholeSpectrumRSquared": round(whole_r2_m, 6),
                "wholeSpectrumRmse": round(whole_rmse_m, 6),
                "wholeSpectrumRmseRaw": round(whole_rmse_raw_m, 6),
                "numSteadyShots": int(getattr(cond, "num_steady_shots", 0)),
            }
            fit_comparison[m] = {
                "roiChi2Reduced": chi2_m,
                "roiRSquared": roi_r2_m,
                "roiRmse": roi_rmse_m,
                "roiRmseRaw": roi_rmse_raw_m,
                "elementParity": element_parity,
                "synthetic_spectrum": y_synth_m.tolist(),
            }

        stats = {
            "roiChi2Reduced": round(float(np.mean(chi2_list)) if chi2_list else 0.0, 4),
            "roiRSquared": round(float(np.mean(roi_r2_list)) if roi_r2_list else 0.0, 6),
            "roiRmse": round(float(np.mean(roi_rmse_list)) if roi_rmse_list else 0.0, 6),
            "roiRmseRaw": round(float(np.mean(roi_rmse_raw_list)) if roi_rmse_raw_list else 0.0, 6),
            "wholeSpectrumRSquared": round(float(np.mean(whole_r2_list)) if whole_r2_list else 0.0, 6),
            "wholeSpectrumRmse": round(float(np.mean(whole_rmse_list)) if whole_rmse_list else 0.0, 6),
            "wholeSpectrumRmseRaw": round(float(np.mean(whole_rmse_raw_list)) if whole_rmse_raw_list else 0.0, 6),
            "zoneReductionPenalty": round(red_penalty, 4),
            "familySharingPenalty": round(sharing_penalty, 4),
            "elementParity": element_parity,
            "numCalibratedMaterials": len(fam_mats),
            "calibratedMaterials": fam_mats,
            "perMaterialStats": per_material_stats,
        }
        return stats, fit_comparison

    def run(self) -> None:
        from measured_conditioning import condition_material
        from forward_model import NIST_UNIT_SCALE, resample_to_detector
        from roi_catalogue import build_catalogue
        from roi_joint_calibration import (
            run_joint_solver,
            solve_family_plasma,
            reduce_zones_to_num_zones,
            scan_absorption,
            compute_family_scales_and_sharing_penalty,
            compute_plasma_dispersion,
            compute_element_parity,
            compute_sub_bin_variances,
            evaluate_diagnostics,
        )

        families_dict = self.manifest.get("materialFamilies", {})
        n_zones = int(self.manifest.get("numZones", 2))
        r_nist_int = int(float(self.manifest.get("nistResolvingPower", 3000.0)))

        # 1. Condition measured data and parse compositions
        cond_map = {}
        comp_map = {}
        fam_map = {}
        mat_meta = []
        det_wl = None

        conditioned_debug_all = {}
        for family_name, mat_list in families_dict.items():
            cal_mats = set(self.get_calibrated_materials(family_name))
            for mat in mat_list:
                mat_name = str(mat.get("materialName", ""))
                if mat_name not in cal_mats:
                    continue
                csv_paths = mat.get("measuredCsvPaths", [])
                if not csv_paths:
                    continue

                cond = condition_material(csv_paths, manifest=self.manifest)
                cond_map[mat_name] = cond
                if det_wl is None:
                    det_wl = cond.wavelengths

                raw_c = mat.get("composition") or mat.get("compositionMap") or {}
                parsed_c = parse_composition_dict(raw_c)
                comp_map[mat_name] = parsed_c
                fam_map[mat_name] = family_name
                mat_meta.append((family_name, mat_name, mat.get("nistGridCsvPaths", {}), mat.get("holdoutCsvPaths", [])))

                conditioned_debug_all.setdefault(family_name, {})[mat_name] = {
                    "wavelengths": cond.wavelengths.tolist(),
                    "mean_spectrum": cond.mean_spectrum.tolist(),
                    "preprocessed_mean": cond.preprocessed_mean.tolist(),
                    "sigma_mean": cond.sigma_mean.tolist(),
                    "dark_level": float(cond.dark_level),
                    "lit_mask": cond.lit_mask.tolist(),
                    "num_steady_shots": int(cond.num_steady_shots),
                }

        if not cond_map or det_wl is None:
            logger.warning("[Engine] No calibrated materials found with valid measured CSVs.")
            return

        total_shots = int(sum(cond.num_steady_shots for cond in cond_map.values()))
        add_sigma_nm = self.compute_line_spread_add_sigma(det_wl, r_nist=float(r_nist_int))

        # 2. Load NIST ASD lines
        all_elements = sorted({el for comp in comp_map.values() for el, pct in comp.items() if pct > 0.0})
        if not all_elements:
            all_elements = ["Fe", "Cr", "Ni", "Mn", "Si", "Cu", "Sn", "Pb", "Zn", "Al", "C"]

        try:
            build_cache(
                set_names=[],
                wl_range=(float(det_wl[0]), float(det_wl[-1])),
                cache_dir=self.nist_lines_dir,
                elements=all_elements,
                include_common=True,
                force=False
            )
            lines_df = load_lines([], wl_range=(float(det_wl[0]), float(det_wl[-1])), cache_dir=self.nist_lines_dir, elements=all_elements)
            valid_l = (lines_df["Aki"] > 0) & (lines_df["Ek_eV"] > 0) & (lines_df["g_k"] > 0) & lines_df["wavelength_nm"].notna()
            model_lines = lines_df[valid_l].copy().sort_values("Aki", ascending=False).head(1500).reset_index(drop=True)
            model_lines["wl_nm"] = model_lines["wavelength_nm"]
            model_lines["unc"] = model_lines["accuracy"].apply(accuracy_to_uncertainty)
            model_lines["gA"] = model_lines["g_k"] * model_lines["Aki"]
        except Exception as e:
            logger.warning(f"[Engine] Could not load ASD lines: {e}")
            model_lines = pd.DataFrame()

        # 3. Load and resample NIST dictionaries (N_det, N_states)
        dictionary = {}
        for family_name, mat_name, nist_paths, _ in mat_meta:
            state_keys = sorted(nist_paths.keys())
            if not state_keys:
                continue
            D = np.zeros((len(det_wl), len(state_keys)), dtype=np.float64)
            for i, k in enumerate(state_keys):
                p = nist_paths[k]
                df = pd.read_csv(p)
                w_col = df.columns[0]
                i_col = df.columns[1]
                w_src = df[w_col].to_numpy(dtype=np.float64)
                i_src = df[i_col].to_numpy(dtype=np.float64) * NIST_UNIT_SCALE
                if len(w_src) == len(det_wl) and np.allclose(w_src, det_wl) and not np.any(add_sigma_nm > 1e-9):
                    D[:, i] = i_src
                else:
                    D[:, i] = resample_to_detector(w_src, i_src, det_wl, add_sigma_nm=add_sigma_nm)
            dictionary[mat_name] = D

        # 4. Build ROI Catalogue across families
        all_rois = []
        rois_by_family = {}
        if not model_lines.empty:
            for fam in families_dict.keys():
                fam_mats = [m for m, f in fam_map.items() if f == fam and m in dictionary]
                if not fam_mats:
                    continue
                ref_mat = fam_mats[0]
                fam_rois = build_catalogue(
                    family=fam,
                    compositions={m: comp_map[m] for m in fam_mats},
                    model_lines=model_lines,
                    dictionary_ref=dictionary[ref_mat],
                    conditioned=cond_map,
                    detector_wavelengths=det_wl,
                )
                rois_by_family[fam] = fam_rois
                all_rois.extend(fam_rois)

        # 5. Execute Solver Pipeline (ROI Joint Solver if ROIs available, else legacy fallback)
        if all_rois:
            result = run_joint_solver(
                rois=all_rois,
                dictionary=dictionary,
                conditioned=cond_map,
                detector_wavelengths=det_wl,
                material_families=fam_map,
                te_grid=self.te_grid.tolist(),
                ne_grid=self.ne_grid.tolist(),
            )

            spectral_response = result.spectral_response
            trace_data = {
                "iterations": int(result.iterations),
                "objective_phi": [float(p) for p in getattr(result, "history_phi", [result.objective_value])],
                "beta": [b.tolist() if hasattr(b, "tolist") else list(b) for b in getattr(result, "history_beta", [result.response_beta])],
            }

            all_flags = list(result.flags)
            for family_name in families_dict.keys():
                fam_mats = [m for m, f in fam_map.items() if f == family_name and m in dictionary]
                if not fam_mats:
                    continue

                fam_rois = rois_by_family.get(family_name, [])
                thin_roi_sbs = [(r, sb) for r in fam_rois if not r.self_absorption_prone for sb in r.sub_bins]
                if not thin_roi_sbs:
                    thin_roi_sbs = [(r, sb) for r in fam_rois for sb in r.sub_bins]
                thin_sub_bins = [sb for _, sb in thin_roi_sbs]

                # Gate G1 verification across materials in this family
                g1_passed, _ = self.verify_gate_g1(fam_mats, dictionary, comp_map, fam_rois)
                if not g1_passed:
                    all_flags.append(f"COMPOSITION_SCALE_UNVERIFIED:{family_name}")

                A_dict = {}
                y_dict = {}
                sigma_dict = {}
                for m in fam_mats:
                    D_m = dictionary[m]
                    cond_m = cond_map[m]
                    y_m_vec = cond_m.preprocessed_mean
                    if cond_m.lit_mask is not None and not np.all(cond_m.lit_mask):
                        unlit_sig = cond_m.sigma_mean[~cond_m.lit_mask]
                        dark_std = float(np.median(unlit_sig)) if len(unlit_sig) > 0 else 30.0
                    else:
                        dark_std = 30.0

                    A_m = np.zeros((len(thin_roi_sbs), D_m.shape[1]), dtype=np.float64)
                    y_m_sb = np.zeros(len(thin_roi_sbs), dtype=np.float64)
                    sig_m_sb = np.zeros(len(thin_roi_sbs), dtype=np.float64)
                    for b_idx, (r_obj, sb) in enumerate(thin_roi_sbs):
                        sb_idx = np.array(sb, dtype=np.int64)
                        A_m[b_idx, :] = np.sum(D_m[sb_idx, :] * spectral_response[sb_idx, None], axis=0)
                        var_info = compute_sub_bin_variances(
                            channels=list(sb),
                            mean_spectrum=y_m_vec,
                            sigma_mean=cond_m.sigma_mean,
                            model_lines=getattr(r_obj, "model_lines", []),
                            model_error_fraction=0.10,
                            dark_std=dark_std,
                        )
                        y_m_sb[b_idx] = float(var_info["Y"])
                        sig_m_sb[b_idx] = float(var_info["sigma"])
                    A_dict[m] = A_m
                    y_dict[m] = y_m_sb
                    sigma_dict[m] = sig_m_sb

                w_family, scales = solve_family_plasma(A_dict, y_dict, sigma_dict=sigma_dict)
                reduced_weights, red_penalty = reduce_zones_to_num_zones(
                    A_dict, y_dict, w_family, num_zones=n_zones, sigma_dict=sigma_dict
                )

                non_zero_states = np.where(reduced_weights > 0)[0]
                k_absorption = 0.0
                if len(non_zero_states) >= 2:
                    te_vals = [self.te_grid[i // self.n_ne] for i in non_zero_states]
                    coolest_local_idx = int(np.argmin(te_vals))
                    coolest_state_idx = non_zero_states[coolest_local_idx]

                    all_roi_sbs = [(r, sb) for r in fam_rois for sb in r.sub_bins]
                    prone_mask_list = [r.self_absorption_prone for r, _ in all_roi_sbs]
                    if any(prone_mask_list):
                        A_all = {}
                        y_all_sb = {}
                        sigma_all_sb = {}
                        d_tilde_cool_all = {}
                        for m in fam_mats:
                            D_m = dictionary[m]
                            cond_m = cond_map[m]
                            y_m_vec = cond_m.preprocessed_mean
                            if cond_m.lit_mask is not None and not np.all(cond_m.lit_mask):
                                unlit_sig = cond_m.sigma_mean[~cond_m.lit_mask]
                                dark_std = float(np.median(unlit_sig)) if len(unlit_sig) > 0 else 30.0
                            else:
                                dark_std = 30.0

                            cool_full = D_m[:, coolest_state_idx]
                            cool_full_max = max(float(np.max(cool_full)), 1e-12)

                            A_m_full = np.zeros((len(all_roi_sbs), D_m.shape[1]), dtype=np.float64)
                            y_m_full = np.zeros(len(all_roi_sbs), dtype=np.float64)
                            sig_m_full = np.zeros(len(all_roi_sbs), dtype=np.float64)
                            d_tilde_m = np.zeros(len(all_roi_sbs), dtype=np.float64)
                            for b_idx, (r_obj, sb) in enumerate(all_roi_sbs):
                                sb_idx = np.array(sb, dtype=np.int64)
                                A_m_full[b_idx, :] = np.sum(D_m[sb_idx, :] * spectral_response[sb_idx, None], axis=0)
                                var_info = compute_sub_bin_variances(
                                    channels=list(sb),
                                    mean_spectrum=y_m_vec,
                                    sigma_mean=cond_m.sigma_mean,
                                    model_lines=getattr(r_obj, "model_lines", []),
                                    model_error_fraction=0.10,
                                    dark_std=dark_std,
                                )
                                y_m_full[b_idx] = float(var_info["Y"])
                                sig_m_full[b_idx] = float(var_info["sigma"])
                                # Effective cool-zone normalized opacity matching apply_multi_zone_rte
                                d_tilde_m[b_idx] = float(np.mean(cool_full[sb_idx]) / cool_full_max)
                            A_all[m] = A_m_full
                            y_all_sb[m] = y_m_full
                            sigma_all_sb[m] = sig_m_full
                            d_tilde_cool_all[m] = d_tilde_m

                        k_absorption, k_flag = scan_absorption(
                            A_dict=A_all,
                            y_dict=y_all_sb,
                            sigma_dict=sigma_all_sb,
                            d_tilde_cool_dict=d_tilde_cool_all,
                            reduced_weights=reduced_weights,
                            coolest_state_idx=coolest_state_idx,
                            prone_mask=prone_mask_list,
                        )
                        if k_flag:
                            all_flags.append(f"K_ABSORPTION_AT_BOUND:{family_name}")

                scale_factor, mat_scales, sharing_penalty = compute_family_scales_and_sharing_penalty(
                    A_dict, y_dict, reduced_weights
                )

                point_solutions = []
                for m in fam_mats:
                    pt_dict = cond_map[m].preprocessed_point_means or cond_map[m].point_means
                    for pt_name, pt_spec in pt_dict.items():
                        y_pt_sb = np.array([np.sum(pt_spec[np.array(sb, dtype=np.int64)]) for sb in thin_sub_bins])
                        w_pt, _ = solve_family_plasma(
                            {m: A_dict[m]}, {m: y_pt_sb}, sigma_dict={m: sigma_dict[m]}
                        )
                        point_solutions.append(w_pt)

                te_std, ne_log_std = compute_plasma_dispersion(
                    point_solutions=point_solutions,
                    te_grid=self.te_grid.tolist(),
                    ne_grid=self.ne_grid.tolist(),
                )

                nz_indices = sorted(non_zero_states, key=lambda idx: self.te_grid[idx // self.n_ne], reverse=True)
                v_total = float(sum(reduced_weights[idx] for idx in nz_indices))
                scale_factor_final = float(scale_factor * v_total)
                mat_scales_final = {m: float(sc * v_total) for m, sc in mat_scales.items()}

                plasma_zones_json = []
                for z_i, idx in enumerate(nz_indices):
                    te_val = float(self.te_grid[idx // self.n_ne])
                    ne_val = float(self.ne_grid[idx % self.n_ne])
                    vf = float(reduced_weights[idx] / v_total) if v_total > 0 else 1.0 / len(nz_indices)
                    kz = k_absorption if (z_i == len(nz_indices) - 1 and k_absorption > 0) else 0.0
                    plasma_zones_json.append({
                        "zoneIndex": z_i,
                        "Te": round(te_val, 4),
                        "teStdDev": round(te_std, 4),
                        "Ne": float(f"{ne_val:.4e}"),
                        "neLogStdDev": round(ne_log_std, 4),
                        "volumeFraction": round(vf, 4),
                        "kAbsorption": round(kz, 4),
                    })

                element_parity, bias_flag = compute_element_parity(
                    rois=fam_rois,
                    comp_map={m: comp_map[m] for m in fam_mats},
                    dictionary={m: dictionary[m] for m in fam_mats},
                    conditioned={m: cond_map[m].preprocessed_mean for m in fam_mats},
                    plasma_zones=plasma_zones_json,
                    scale_factor=scale_factor_final,
                    spectral_response=spectral_response,
                    nz_indices=nz_indices,
                    te_grid=self.te_grid.tolist(),
                    ne_grid=self.ne_grid.tolist(),
                )
                if bias_flag:
                    all_flags.append(f"ELEMENT_BIAS:{family_name}")

                union_chans = sorted(list({c for r in fam_rois for sb in r.sub_bins for c in sb}))
                n_fitted_params = max(len(nz_indices) + (1 if k_absorption > 0.0 else 0) + 1, 1)
                cal_stats, fit_comparison = self.compute_family_calibration_stats(
                    fam_mats=fam_mats,
                    dictionary=dictionary,
                    cond_map=cond_map,
                    fam_rois=fam_rois,
                    union_chans=union_chans,
                    plasma_zones_json=plasma_zones_json,
                    nz_indices=nz_indices,
                    scale_factor_final=scale_factor_final,
                    spectral_response=spectral_response,
                    red_penalty=red_penalty,
                    sharing_penalty=sharing_penalty,
                    element_parity=element_parity,
                    n_params=n_fitted_params,
                )

                holdout_map = {m: [mat[3] for mat in mat_meta if mat[1] == m][0] for m in fam_mats if [mat[3] for mat in mat_meta if mat[1] == m and mat[3]]}

                fam_profile = {
                    "materialFamily": family_name,
                    "resolution": r_nist_int,
                    "scaleFactor": round(scale_factor_final, 8),
                    "materialScaleFactors": {m: round(float(sc), 8) for m, sc in mat_scales_final.items()},
                    "plasmaZones": plasma_zones_json,
                    "calibrationStats": cal_stats,
                    "validationHoldoutCsvs": holdout_map,
                }

                fam_rois_json = [r.to_dict() if hasattr(r, "to_dict") else r for r in fam_rois]
                self.upsert_family_profile(family_name, fam_profile, fam_rois_json)

                self.export_debug_json(
                    family=family_name,
                    conditioned_data=conditioned_debug_all.get(family_name, {}),
                    rois_data={"rois": fam_rois_json, "unionChannelIndices": union_chans},
                    solver_trace=trace_data,
                    fit_comparison=fit_comparison,
                )

            # Update global profile fields
            out_path = Path(self.manifest.get("outputProfilePath", "conf/instrument_profile.json"))
            if out_path.exists():
                prof_json = json.loads(out_path.read_text(encoding="utf-8"))
            else:
                prof_json = {"schemaVersion": "1.0.0", "materialFamilyProfiles": {}}
            prof_json["emissionScale"] = "absolute"
            prof_json["wavelengths"] = [round(float(w), 4) for w in det_wl]
            prof_json["spectralResponse"] = [round(float(r), 6) for r in spectral_response]
            prof_json["spectralResponseValidRangeNm"] = [round(float(det_wl[0]), 2), round(float(det_wl[-1]), 2)]
            prof_json["validChannelRangeNm"] = [round(float(det_wl[0]), 4), round(float(det_wl[-1]), 4)]
            prof_json["lineSpreadAddSigmaNm"] = [round(float(s), 6) for s in add_sigma_nm]
            prof_json["calibrationDiagnostics"] = {
                "flags": all_flags,
                "iterations": int(result.iterations),
                "responseRestartSpread": round(float(result.response_restart_spread), 6),
            }
            prof_json["numShots"] = total_shots
            out_path.parent.mkdir(parents=True, exist_ok=True)
            out_path.write_text(json.dumps(prof_json, indent=2), encoding="utf-8")

            self.check_strict_flags(all_flags)
            logger.info(f"[Engine] Calibration complete -> {out_path}")

            # Generate / refresh interactive diagnostic inspector notebook in notebooks/
            try:
                try:
                    from scripts.calibration_inspector import generate_inspector_notebook
                except ImportError:
                    from calibration_inspector import generate_inspector_notebook
                nb_dest = Path("notebooks/calibration_inspector.ipynb")
                generate_inspector_notebook(nb_dest)
                logger.info(f"[Engine] Generated calibration inspector notebook -> {nb_dest}")
            except Exception as e:
                logger.warning(f"[Engine] Could not generate calibration inspector notebook: {e}")

            return
        logger.info(f"[GPU Engine] Calibration complete (Voigt-free) -> {out_path}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: gpu_calibration_engine.py <path_to_manifest.json>")
        sys.exit(1)
    engine = GpuLibsCalibrationEngine(sys.argv[1])
    engine.run()
