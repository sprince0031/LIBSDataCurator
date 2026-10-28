#!/usr/bin/env python3
"""
GPU-Accelerated Multi-Material & Multi-Family LIBS Calibration Engine
Hardware Target: NVIDIA RTX 4070 MAX SUPER (PyTorch CUDA) / CPU fallback
Baseline Correction: 1D Morphological Grey Opening (Default Structure Size = 25)
"""

import json
import logging
import os
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
logger = logging.getLogger("gpu_calibration_engine")


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
            if candidate1.exists():
                self.logs_dir = candidate1
            elif candidate2.exists():
                self.logs_dir = candidate2
            else:
                self.logs_dir = candidate1
        self.logs_dir.mkdir(parents=True, exist_ok=True)
        self.log_file = (self.logs_dir / "gpu_calibration_engine.log").resolve()
        self._init_logger()

        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        dev_name = torch.cuda.get_device_name(0) if torch.cuda.is_available() else "CPU"
        logger.info(f"[GPU Engine] Active Device: {self.device} ({dev_name})")
        logger.info(f"[GPU Engine] Manifest: {self.manifest_path.resolve()}")
        logger.info(f"[GPU Engine] Log file: {self.log_file}")

        bc_params = self.manifest.get("baselineCorrectionParams", {})
        self.struct_size = int(bc_params.get("structuringElementSize", DEFAULT_MORPHOLOGICAL_STRUCT_SIZE))

        self.te_grid = np.array(self.manifest["teAnchorGridEv"], dtype=np.float64)
        self.ne_grid = np.array(self.manifest["neAnchorGridCm3"], dtype=np.float64)
        self.log_ne_grid = np.log10(self.ne_grid)
        self.n_te = len(self.te_grid)
        self.n_ne = len(self.ne_grid)
        self.j_states = self.n_te * self.n_ne

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
            delim = self.manifest.get("csvDelimiter")
            if not delim:
                with open(path, "r", encoding="utf-8") as f:
                    first_line = f.readline()
                if ";" in first_line:
                    delim = ";"
                elif "\t" in first_line:
                    delim = "\t"
                else:
                    delim = ","
            df = pd.read_csv(path, sep=delim)
            # Detect whether columns 0..1 are metadata (ShotID, Timestamp) or wavelengths
            first_col_val = self._try_parse_float(df.columns[0])
            start_col = 0 if first_col_val is not None and first_col_val > 150.0 else 2

            if len(df.columns) <= start_col:
                raise ValueError(
                    f"CSV file '{path}' has insufficient columns ({len(df.columns)}) with delimiter '{delim}'."
                )

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
                if csv_path is None:
                    raise KeyError(f"Missing NIST grid path for Te={te}, Ne={ne} in dictionary")
                p = Path(csv_path)
                if not p.is_absolute() and not p.exists():
                    candidate = self.manifest_path.resolve().parent.parent / p
                    if candidate.exists():
                        p = candidate
                df = pd.read_csv(p)
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
        n_zones: int = 2, n_mc_per_iter: int = 50000, n_iters: int = 5
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

            top_vals, top_idx = torch.topk(costs_all, k=min(100, n_mc_per_iter), largest=False)
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

            gamma = 0.55
            for z in range(n_zones):
                te_span = float(elite_te[:, z].max() - elite_te[:, z].min())
                lne_span = float(elite_lne[:, z].max() - elite_lne[:, z].min())
                v_span = float(elite_v[:, z].max() - elite_v[:, z].min())

                te_min[z] = max(0.40, float(elite_te[:, z].mean() - 0.5 * gamma * te_span))
                te_max[z] = min(2.50, float(elite_te[:, z].mean() + 0.5 * gamma * te_span))
                lne_min[z] = max(float(self.log_ne_grid[0]), float(elite_lne[:, z].mean() - 0.5 * gamma * lne_span))
                lne_max[z] = min(float(self.log_ne_grid[-1]), float(elite_lne[:, z].mean() + 0.5 * gamma * lne_span))
                v_min[z] = max(0.01, float(elite_v[:, z].mean() - 0.5 * gamma * v_span))
                v_max[z] = min(0.99, float(elite_v[:, z].mean() + 0.5 * gamma * v_span))

                if z > 0:
                    k_span = float(elite_k[:, z].max() - elite_k[:, z].min())
                    k_min[z] = max(0.0, float(elite_k[:, z].mean() - 0.5 * gamma * k_span))
                    k_max[z] = min(8.0, float(elite_k[:, z].mean() + 0.5 * gamma * k_span))

        return {
            "bestParams": best_params,
            "scaleFactor": best_scale,
            "weightedCorrelationCost": best_cost,
            "rSquared": best_r2,
            "rmse": best_rmse
        }

    # -------------------------------------------------------------------------
    # STEP 5: Main Orchestration & Non-Destructive Profile Upsert
    # -------------------------------------------------------------------------
    def run(self):
        mat_meta = []
        y_list, sig_list, point_means_all = [], [], []
        lam_meas = None
        total_shots_per_file = 40

        for family_name, materials in self.manifest.get("materialFamilies", {}).items():
            for mat in materials:
                mat_name = mat["materialName"]
                meas_csvs = mat["measuredCsvPaths"]
                hold_csvs = mat.get("holdoutCsvPaths", [])
                nist_paths = mat["nistGridCsvPaths"]

                l_m, y_c, s_m, pt_means, n_shots = self.load_and_clean_material_csvs(meas_csvs)
                total_shots_per_file = n_shots // max(len(meas_csvs), 1)
                if lam_meas is None:
                    lam_meas = l_m

                mat_meta.append((family_name, mat_name, nist_paths, hold_csvs))
                y_list.append(y_c)
                sig_list.append(s_m)
                point_means_all.append(pt_means)

        y_all = np.vstack(y_list)
        sig_all = np.vstack(sig_list)

        ref_y = np.mean(y_all, axis=0)
        fwhm_g_meas, fwhm_l_meas = self.estimate_measured_voigt_fwhm(lam_meas, ref_y)

        d_all_list = []
        d_all_gpu_list = []
        psi_cache = {}
        for _, mat_name, nist_paths, _ in mat_meta:
            lam_mat_nist, s_mat_gpu = self.load_nist_dictionary_for_material(nist_paths, psi_gpu=None)
            nist_len = len(lam_mat_nist)
            if nist_len not in psi_cache:
                logger.info(
                    f"[GPU Engine] Building Voigt transfer matrix for material '{mat_name}' "
                    f"(Meas: {len(lam_meas)}, NIST: {nist_len})..."
                )
                psi_cache[nist_len] = self.build_gpu_voigt_transfer_matrix(
                    lam_meas, lam_mat_nist, fwhm_g_meas, fwhm_l_meas
                )
            d_mat_gpu = torch.matmul(psi_cache[nist_len], s_mat_gpu)
            d_all_gpu_list.append(d_mat_gpu)
            d_all_list.append(d_mat_gpu.cpu().numpy())
        d_all = np.stack(d_all_list, axis=0)
        logger.info(f"[GPU Engine] Transformed NIST dictionary to measurement grid: d_all shape {d_all.shape}")

        res_mask = np.ones_like(lam_meas)
        for win in self.manifest.get("resonanceMaskWindowsNm", []):
            res_mask[(lam_meas >= win["minNm"]) & (lam_meas <= win["maxNm"])] = 0.0

        r_vec = self.solve_global_instrument_response(lam_meas, y_all, sig_all, d_all, res_mask)
        r_gpu = torch.tensor(r_vec, dtype=torch.float32, device=self.device)

        tramp_w = np.ones_like(lam_meas)
        for win in self.manifest.get("trampWindowsNm", []):
            tramp_w[(lam_meas >= win["minNm"]) & (lam_meas <= win["maxNm"])] = float(win["weight"])
        tramp_w_gpu = torch.tensor(tramp_w, dtype=torch.float32, device=self.device)

        out_path = Path(self.manifest.get("outputProfilePath", "conf/instrument_profile.json"))
        if out_path.exists():
            with open(out_path, "r", encoding="utf-8") as f:
                profile_json = json.load(f)
        else:
            profile_json = {}

        existing_families = profile_json.get("materialFamilyProfiles", {})

        target_family = self.manifest.get("targetMaterialFamily")
        families_to_optimize = [target_family] if target_family else list(self.manifest.get("materialFamilies", {}).keys())
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
                        n_zones=n_zones, n_mc_per_iter=5000, n_iters=3
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

        logger.info(f"[GPU Engine] Upserted family '{target_family}' into {out_path} "
                    f"(Total families in profile: {len(existing_families)})")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: gpu_calibration_engine.py <manifest_path>", file=sys.stderr)
        sys.exit(1)
    manifest_arg = sys.argv[1]
    if manifest_arg == "--manifest" and len(sys.argv) > 2:
        manifest_arg = sys.argv[2]
    try:
        engine = GpuLibsCalibrationEngine(manifest_arg)
        engine.run()
    except Exception as e:
        logger.exception(f"Fatal error executing GPU Calibration Engine: {e}")
        sys.exit(1)
