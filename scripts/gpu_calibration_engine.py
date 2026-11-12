#!/usr/bin/env python3
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
from typing import Dict, List, Optional, Tuple

import numpy as np
import pandas as pd
import torch
import torch.nn.functional as F
from scipy.interpolate import BSpline
from scipy.ndimage import grey_opening

# Import NIST ASD line fetcher & loader from scripts/nist_lines.py
SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

from nist_lines import build_cache, load_lines  # noqa: E402

DEFAULT_MORPHOLOGICAL_STRUCT_SIZE = 65
DEFAULT_NIST_LINES_DIR = SCRIPT_DIR.parent / "data" / "nist_lines"

ACCURACY_WEIGHTS = {
    "AAA": 1.0, "AA": 1.0, "A+": 0.95, "A": 0.90,
    "B+": 0.85, "B": 0.80, "C+": 0.70, "C": 0.65,
    "D+": 0.50, "D": 0.45, "E": 0.25
}

logger = logging.getLogger("gpu_calibration_engine")


def parse_composition_dict(raw_comp) -> Dict[str, float]:
    """
    Normalizes a material composition (either a dict {'Fe': 86.5, 'Cr': 11.9} or a string
    'C:0.28,Cr:11.9,Fe:#' / 'C-0.279,Si-6.5:7.5,Fe-#') into a clean Dict[str, float] summing to 100%.
    """
    if isinstance(raw_comp, str):
        items = []
        for t in re.split(r"[,;]", raw_comp.strip()):
            if not t.strip():
                continue
            parts = re.split(r"[-:]", t.strip(), maxsplit=1)
            if len(parts) == 2:
                items.append((parts[0].strip(), parts[1].strip()))
        raw_dict = dict(items)
    elif isinstance(raw_comp, dict):
        raw_dict = raw_comp
    else:
        return {}

    parsed: Dict[str, float] = {}
    rem_key = None
    for el, val in raw_dict.items():
        if not re.fullmatch(r"[A-Z][a-z]?", str(el).strip()):
            continue
        s_val = str(val).strip()
        if s_val == "#":
            rem_key = el
        elif ":" in s_val:
            parts = s_val.split(":")
            parsed[el] = 0.5 * (float(parts[0]) + float(parts[1]))
        else:
            try:
                parsed[el] = float(s_val)
            except ValueError:
                continue

    explicit_sum = sum(parsed.values())
    if rem_key is not None:
        parsed[rem_key] = max(0.0, 100.0 - explicit_sum)
    elif explicit_sum > 0:
        for el in list(parsed.keys()):
            parsed[el] = 100.0 * parsed[el] / explicit_sum
    return parsed


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
        acc_w = df["accuracy"].map(lambda a: ACCURACY_WEIGHTS.get(str(a).strip(), 0.50)).to_numpy()
        emissivity = np.where(
            df["boltzmann_ok"].fillna(False),
            df["g_k"].fillna(1.0) * df["Aki"].fillna(0.0) * np.exp(-df["Ek_eV"].fillna(4.0) / t_ref),
            df["rel_intensity"].fillna(0.0) * 1e4
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

        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        dev_name = torch.cuda.get_device_name(0) if torch.cuda.is_available() else "CPU"
        logger.info(f"[GPU Engine] Active Device: {self.device} ({dev_name})")

        bc_params = self.manifest.get("baselineCorrectionParams", {})
        self.struct_size = max(int(bc_params.get("structuringElementSize", DEFAULT_MORPHOLOGICAL_STRUCT_SIZE)), 55)

        self.te_grid = np.array(self.manifest["teAnchorGridEv"], dtype=np.float64)
        self.ne_grid = np.array(self.manifest["neAnchorGridCm3"], dtype=np.float64)
        self.log_ne_grid = np.log10(self.ne_grid)
        self.n_te = len(self.te_grid)
        self.n_ne = len(self.ne_grid)

        nist_dir_str = self.manifest.get("nistLinesDir")
        self.nist_lines_dir = Path(nist_dir_str) if nist_dir_str else DEFAULT_NIST_LINES_DIR
        self.asd_advisor: Optional[AsdLineAdvisor] = None

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

        return torch.tensor(np.column_stack(d_cols), dtype=torch.float32, device=self.device)

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

    def run(self) -> None:
        families_dict = self.manifest["materialFamilies"]

        lam_meas = None
        y_all_list, point_means_all, mat_meta, family_labels = [], [], [], []
        family_comp_accum: Dict[str, Dict[str, List[float]]] = {}
        total_shots_per_file = 40

        for family_name, mat_list in families_dict.items():
            for mat in mat_list:
                lam_m, y_m, sig_m, pt_means, n_shots = self.load_and_clean_material_csvs(mat["measuredCsvPaths"])
                if lam_meas is None:
                    lam_meas = lam_m
                total_shots_per_file = n_shots // max(len(mat["measuredCsvPaths"]), 1)
                y_all_list.append(y_m)
                point_means_all.append(pt_means)
                family_labels.append(family_name)
                mat_meta.append((family_name, mat["materialName"], mat["nistGridCsvPaths"], mat.get("holdoutCsvPaths", [])))

                raw_c = mat.get("composition") or mat.get("compositionMap") or {}
                parsed_c = parse_composition_dict(raw_c)
                for el, pct in parsed_c.items():
                    family_comp_accum.setdefault(family_name, {}).setdefault(el, []).append(pct)

        family_mean_compositions: Dict[str, Dict[str, float]] = {
            fam: {el: float(np.mean(vals)) for el, vals in el_map.items()}
            for fam, el_map in family_comp_accum.items()
        }

        y_all = np.vstack(y_all_list)
        p_channels = len(lam_meas)

        # Initialize ASD Line Advisor (fetches & caches Parquet files in data/nist_lines/ via nist_lines.py)
        self.asd_advisor = AsdLineAdvisor(
            self.nist_lines_dir, family_mean_compositions, float(lam_meas[0]), float(lam_meas[-1])
        )
        res_mask = self.asd_advisor.build_resonance_mask(
            lam_meas, self.manifest.get("resonanceMaskWindowsNm", [])
        )

        d_all_gpu_list = []
        for _, _, nist_paths, _ in mat_meta:
            d_all_gpu_list.append(self.load_nist_dictionary_for_material(nist_paths, lam_meas))

        n_zones = int(self.manifest.get("numZones", 2))
        r_nist_int = int(float(self.manifest.get("nistResolvingPower", 3000.0)))

        # PASS 1: Initial RTE Optimization per Family with Flat Response R^(0) = 1.0
        r_gpu_init = torch.ones(p_channels, dtype=torch.float32, device=self.device)
        i_rte_all_numpy = np.zeros_like(y_all)

        for family_name in families_dict.keys():
            fam_indices = [idx for idx, (f_name, _, _, _) in enumerate(mat_meta) if f_name == family_name]
            if not fam_indices:
                continue
            d_fam_gpu = torch.stack([d_all_gpu_list[i] for i in fam_indices], dim=0)
            y_fam_gpu = torch.tensor(y_all[fam_indices], dtype=torch.float32, device=self.device)

            tramp_w_fam = self.asd_advisor.build_family_tramp_weights(
                family_name, lam_meas, self.manifest.get("trampWindowsNm", [])
            )
            tramp_w_gpu = torch.tensor(tramp_w_fam, dtype=torch.float32, device=self.device)

            pass1_res = self.optimize_family_rte_gpu(
                d_fam_gpu, y_fam_gpu, r_gpu_init, tramp_w_gpu,
                n_zones=n_zones, n_mc_per_iter=60000, n_iters=4
            )
            d_grid_gpu = d_fam_gpu.view(len(fam_indices), p_channels, self.n_te, self.n_ne)
            i_rte_fam = self.evaluate_single_rte_spectrum_gpu(d_grid_gpu, pass1_res["bestParams"]).cpu().numpy()
            for local_i, global_i in enumerate(fam_indices):
                i_rte_all_numpy[global_i] = i_rte_fam[local_i]

        # GLOBAL RESPONSE FIT: ASD-Anchored Material-Agnostic Peak-Envelope P-Spline R(lambda)
        r_vec = self.solve_material_agnostic_spectral_response(
            lam_meas, y_all, i_rte_all_numpy, family_labels, res_mask
        )
        r_gpu = torch.tensor(r_vec, dtype=torch.float32, device=self.device)

        # PASS 2: Final RTE Optimization & Spatial Bootstrap for ALL Staged Families Using Locked R(lambda)
        out_path = Path(self.manifest["outputProfilePath"])
        profile_json = json.loads(out_path.read_text(encoding="utf-8")) if out_path.exists() else {}
        # Explicitly remove any legacy voigtProfile object
        profile_json.pop("voigtProfile", None)

        existing_families = profile_json.get("materialFamilyProfiles", {})
        if not isinstance(existing_families, dict):
            existing_families = {}

        for family_name in families_dict.keys():
            fam_indices = [idx for idx, (f_name, _, _, _) in enumerate(mat_meta) if f_name == family_name]
            if not fam_indices:
                continue

            d_fam_gpu = torch.stack([d_all_gpu_list[i] for i in fam_indices], dim=0)
            y_fam_gpu = torch.tensor(y_all[fam_indices], dtype=torch.float32, device=self.device)
            tramp_w_fam = self.asd_advisor.build_family_tramp_weights(
                family_name, lam_meas, self.manifest.get("trampWindowsNm", [])
            )
            tramp_w_gpu = torch.tensor(tramp_w_fam, dtype=torch.float32, device=self.device)

            opt_res = self.optimize_family_rte_gpu(
                d_fam_gpu, y_fam_gpu, r_gpu, tramp_w_gpu,
                n_zones=n_zones, n_mc_per_iter=100000, n_iters=5
            )

            pt_te_samples, pt_lne_samples = [], []
            for i in fam_indices:
                d_single_gpu = d_all_gpu_list[i].unsqueeze(0)
                for pt_spec in point_means_all[i]:
                    y_pt_gpu = torch.tensor(pt_spec[None, :], dtype=torch.float32, device=self.device)
                    pt_res = self.optimize_family_rte_gpu(
                        d_single_gpu, y_pt_gpu, r_gpu, tramp_w_gpu,
                        n_zones=n_zones, n_mc_per_iter=20000, n_iters=3
                    )
                    pt_te_samples.append(pt_res["bestParams"]["Te"])
                    pt_lne_samples.append(np.log10(pt_res["bestParams"]["Ne"]))

            te_std = np.clip(np.std(pt_te_samples, axis=0), 0.02, 0.12) if len(pt_te_samples) > 1 else np.full(n_zones, 0.04)
            lne_std = np.clip(np.std(pt_lne_samples, axis=0), 0.04, 0.20) if len(pt_lne_samples) > 1 else np.full(n_zones, 0.08)

            best_p = opt_res["bestParams"]
            zones_json = [
                {
                    "zoneIndex": z,
                    "Te": round(float(best_p["Te"][z]), 4),
                    "teStdDev": round(float(te_std[z]), 4),
                    "volumeFraction": round(float(best_p["volumeFraction"][z]), 4),
                    "Ne": float(f"{best_p['Ne'][z]:.4e}"),
                    "neLogStdDev": round(float(lne_std[z]), 4),
                    "kAbsorption": round(float(best_p["kAbsorption"][z]), 4)
                }
                for z in range(n_zones)
            ]

            holdout_map = {mat_meta[i][1]: mat_meta[i][3] for i in fam_indices if mat_meta[i][3]}
            cal_mat_names = [mat_meta[i][1] for i in fam_indices]

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
        profile_json["finotelloNoiseBeta"] = 0.095
        profile_json["materialFamilyProfiles"] = existing_families
        profile_json["numShots"] = total_shots_per_file

        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(profile_json, indent=4), encoding="utf-8")
        logger.info(f"[GPU Engine] Calibration complete (Voigt-free) -> {out_path}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: gpu_calibration_engine.py <path_to_manifest.json>")
        sys.exit(1)
    engine = GpuLibsCalibrationEngine(sys.argv[1])
    engine.run()
