"""Planted-truth recovery tests for joint plasma-response calibration (R12, AC7).

Simulates multi-element LIBS spectra using real NIST Atomic Spectra Database (ASD)
line transitions, real reference material compositions from Medals_Samples_Feb26,
and real spectrometer detector channels with known ground-truth analytic response
curves and off-grid plasma states. Verifies that the joint solver (R6, R7, R9)
accurately recovers the instrument response and correctly raises diagnostics
under stress scenarios:
1. Clean: RMS response error <= 10%, converged, no flags.
2. Harsh: 30% missing lines, unmodelled lines > 560 nm, low-Ei self-absorption, 5% perturbed Aki -> RMS error <= 20% in 350-550 nm, max/min r <= 3x truth.
3. Edge: Te = 0.42 eV below grid minimum -> TE_GRID_EDGE raised.
4. Split: Iron-based vs other families -> familyGroupResponseRmsDiff <= 0.05, no RESPONSE_FAMILY_INCONSISTENT.
"""

from __future__ import annotations

import json
from pathlib import Path
import time
import numpy as np
import pandas as pd
import pytest
from scipy.signal import find_peaks

from scripts.measured_conditioning import ConditionedMaterial
from scripts.nist_lines import load_lines, accuracy_to_uncertainty
from scripts.roi_catalogue import RoiWindow, SubBin
from scripts.roi_joint_calibration import (
    CalibrationResult,
    run_joint_solver,
    CubicBSplineBasis,
)


class PlantedTruthSimulation:
    """Deterministic simulation generator using real ASD lines, detector channels, and compositions."""

    def __init__(self, seed: int = 42):
        self.rng = np.random.default_rng(seed)

        # 1. Real spectrometer detector channels from Medals_Samples_Feb26 AvaSpec data
        det_csv = Path("data/LSA/Medals_Samples_Feb26/469/LSA_ava_S20260311162055_E20260311162058.csv")
        if det_csv.exists():
            df_hdr = pd.read_csv(det_csv, sep=";", nrows=1)
            self.det_wl = np.array(
                [float(c) for c in df_hdr.columns if c not in ("MeasCtr", "TimeStamp")],
                dtype=np.float64,
            )
        else:
            self.det_wl = np.linspace(341.952, 896.916, 2048)

        # 2. Known analytic ground truth response with 20x dynamic range
        # Matches realistic spectrometer efficiency (cut-on, peak, fall-off)
        x = (self.det_wl - self.det_wl[0]) / (self.det_wl[-1] - self.det_wl[0])
        log_r_true = 1.8 * np.sin(np.pi * x) - 1.0 * (x - 0.2) ** 2
        log_r_true = log_r_true * (np.log(20.0) / (np.max(log_r_true) - np.min(log_r_true)))
        log_r_true -= np.mean(log_r_true)
        self.true_response = np.exp(log_r_true)

        # 3. Real reference material compositions from reference_compositions.json
        self.compositions = {
            "469": {"Fe": 86.49, "Cr": 11.93, "Ni": 0.25, "Mn": 0.60, "Si": 0.42, "C": 0.28},
            "470": {"Fe": 81.17, "Cr": 17.68, "Ni": 0.37, "Mn": 0.24, "Si": 0.34, "C": 0.15},
            "471": {"Fe": 74.72, "Cr": 23.85, "Ni": 0.96, "Mn": 0.42, "Si": 0.33, "C": 0.09},
            "551": {"Cu": 84.5, "Sn": 6.0, "Pb": 4.5, "Zn": 4.5, "Ni": 0.5},
            "552": {"Cu": 88.0, "Sn": 8.0, "Zn": 3.0, "Pb": 1.0},
        }
        self.material_families = {
            "469": "Steel", "470": "Steel", "471": "Steel",
            "551": "Bronze", "552": "Bronze",
        }

        # 4. Plasma states grid
        self.te_grid = [0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0]
        self.ne_grid = [1e16, 5e16, 1e17, 5e17]
        self.n_states = len(self.te_grid) * len(self.ne_grid)

        # 5. Real ASD lines from NIST line cache for alloy constituent elements
        raw_lines = load_lines(["steel", "bronze"], wl_range=(float(self.det_wl[0]), float(self.det_wl[-1])))
        valid = (raw_lines["Aki"] > 0) & (raw_lines["Ek_eV"] > 0) & (raw_lines["g_k"] > 0) & raw_lines["wavelength_nm"].notna()
        df_valid = raw_lines[valid].copy()
        comp_elements = {"Fe", "Cr", "Ni", "Mn", "Si", "C", "Mo", "Cu", "Sn", "Pb", "Zn"}
        df_valid = df_valid[df_valid["element"].isin(comp_elements)].copy()

        # Select prominent lines per constituent element
        sel = []
        for el, grp in df_valid.groupby("element"):
            top_n = min(len(grp), 250 if el in ("Fe", "Cu", "Cr") else 60)
            sel.append(grp.sort_values("Aki", ascending=False).head(top_n))

        lines_df = pd.concat(sel).drop_duplicates().reset_index(drop=True)
        lines_df["wl_nm"] = lines_df["wavelength_nm"]
        lines_df["unc"] = lines_df["accuracy"].apply(accuracy_to_uncertainty)
        lines_df["gA"] = lines_df["g_k"] * lines_df["Aki"]

        p_centers = np.searchsorted(self.det_wl, lines_df["wl_nm"].to_numpy())
        valid_p = (p_centers >= 2) & (p_centers < len(self.det_wl) - 3)
        self.lines = lines_df[valid_p].reset_index(drop=True)
        self.p_centers = p_centers[valid_p]

    def build_synthetic_setup(self, scenario: str = "Clean"):
        """Build dictionary, ROIs, and conditioned measurements for a given scenario."""
        dict_lines = self.lines.copy()
        dict_lines["Aki"] = dict_lines["Aki"] * np.exp(self.rng.normal(0.0, 0.15, len(dict_lines)))
        dict_lines["gA"] = dict_lines["g_k"] * dict_lines["Aki"]

        if scenario == "Harsh":
            # 30% of lines absent from fitting dictionary
            keep_mask = self.rng.uniform(0.0, 1.0, len(dict_lines)) > 0.30
            dict_lines = dict_lines[keep_mask].reset_index(drop=True)
            p_dict_centers = np.searchsorted(self.det_wl, dict_lines["wl_nm"].to_numpy())
            # 5% of A-values wrong by 2x
            wrong_idx = self.rng.choice(len(dict_lines), size=int(0.05 * len(dict_lines)), replace=False)
            dict_lines.loc[wrong_idx, "Aki"] *= self.rng.choice([0.5, 2.0], size=len(wrong_idx))
            dict_lines["gA"] = dict_lines["g_k"] * dict_lines["Aki"]
        else:
            p_dict_centers = self.p_centers

        # Build fitting dictionary D_m for each material
        dictionary = {}
        for mat_name, comp in self.compositions.items():
            D = np.zeros((len(self.det_wl), self.n_states), dtype=np.float64)
            st_idx = 0
            el_wts = np.array([comp.get(el, 0.0) for el in dict_lines["element"]])
            for te in self.te_grid:
                for ne in self.ne_grid:
                    em = (dict_lines["gA"] / dict_lines["wl_nm"]) * np.exp(-dict_lines["Ek_eV"] / te) * el_wts * 1e-4
                    col = np.zeros(len(self.det_wl), dtype=np.float64)
                    for p, val in zip(p_dict_centers, em):
                        if val > 0:
                            col[p - 2 : p + 3] += val * 0.2
                    D[:, st_idx] = col
                    st_idx += 1
            dictionary[mat_name] = D

        # Generate synthetic measured spectra from ground truth
        conditioned = {}
        ref_spec = np.zeros(len(self.det_wl), dtype=np.float64)

        for mat_name, comp in self.compositions.items():
            # True plasma: two off-grid zones in interior
            if scenario == "Edge" and mat_name == "469":
                te1, te2 = 0.42, 0.44  # strictly below grid min 0.50 eV
            else:
                te1, te2 = (1.05, 0.85) if mat_name in ("551", "552") else (0.85, 0.65)

            el_wts = np.array([comp.get(el, 0.0) for el in self.lines["element"]])
            em1 = (self.lines["gA"] / self.lines["wl_nm"]) * np.exp(-self.lines["Ek_eV"] / te1) * el_wts * 1e-4
            em2 = (self.lines["gA"] / self.lines["wl_nm"]) * np.exp(-self.lines["Ek_eV"] / te2) * el_wts * 1e-4

            if scenario == "Harsh":
                # Unmodelled lines above 560 nm
                unmod_mask = (self.lines["wl_nm"] > 560.0) & (self.rng.uniform(size=len(self.lines)) < 0.15)
                em1[unmod_mask] *= 2.5
                em2[unmod_mask] *= 2.5

                # Self-absorption on low-Ei major lines
                low_ei_mask = (self.lines["Ei_eV"] <= 2.0) & (el_wts >= 50.0)
                em1[low_ei_mask] *= np.exp(-1.5)

            comb = 0.7 * em1 + 0.3 * em2
            y_synth = np.zeros(len(self.det_wl), dtype=np.float64)
            for p, val in zip(self.p_centers, comb):
                if val > 0:
                    y_synth[p - 2 : p + 3] += val * 0.2

            y_meas = y_synth * self.true_response * 5000.0
            ref_spec += y_meas

            # Noise: Poisson shot noise + 5 counts read noise
            noise_std = np.sqrt(np.maximum(y_meas, 10.0)) + 5.0
            y_noisy = np.maximum(y_meas + self.rng.normal(0.0, noise_std), 0.0)

            lit = np.ones(len(self.det_wl), dtype=bool)
            conditioned[mat_name] = ConditionedMaterial(
                wavelengths=self.det_wl,
                mean_spectrum=y_noisy,
                preprocessed_mean=y_noisy,
                sigma_mean=noise_std,
                valid_mask=lit,
                lit_mask=lit,
                dark_level=50.0,
                point_means={"P1": y_noisy},
                discarded_shots_per_point={"P1": 0},
            )

        # Build clean ROIs from prominent emission peaks across spectral bands
        selected_peaks = []
        for b_lo, b_hi in [(345, 450), (450, 550), (550, 650), (650, 750), (750, 890)]:
            mask = (self.det_wl >= b_lo) & (self.det_wl <= b_hi)
            indices = np.where(mask)[0]
            pks, _ = find_peaks(ref_spec[indices], height=20.0, distance=12)
            if len(pks) > 12:
                pks = pks[np.linspace(0, len(pks) - 1, 12, dtype=int)]
            selected_peaks.extend(indices[pks])

        rois = []
        for i, c_idx in enumerate(selected_peaks):
            w_lo = max(0, c_idx - 6)
            w_hi = min(len(self.det_wl) - 1, c_idx + 6)
            chans = list(range(w_lo, w_hi + 1))
            sub_bins = [chans[j : j + 4] for j in range(0, len(chans), 4)]
            if len(sub_bins) > 1 and len(sub_bins[-1]) < 3:
                sub_bins[-2].extend(sub_bins.pop())

            is_prone = (scenario == "Harsh" and i % 5 == 0)
            rois.append(
                RoiWindow(
                    roi_id=f"ROI_{i:03d}_{int(self.det_wl[c_idx])}",
                    min_nm=float(self.det_wl[w_lo]),
                    max_nm=float(self.det_wl[w_hi]),
                    channel_indices=chans,
                    sub_bins=sub_bins,
                    self_absorption_prone=is_prone,
                    used_in_response_fit=(not is_prone),
                    dominant_element="Fe" if i % 2 == 0 else "Cu",
                    model_lines=[
                        {
                            "species": "Fe I" if i % 2 == 0 else "Cu I",
                            "wlNm": self.det_wl[c_idx],
                            "EiEv": 2.5,
                            "EkEv": 5.0,
                            "unc": 0.10,
                        }
                    ],
                )
            )

        return dictionary, conditioned, rois


def test_planted_truth_clean_scenario():
    """R12 Clean Scenario: Response RMS error <= 10% over the span, converged, no flags."""
    sim = PlantedTruthSimulation(seed=101)
    dictionary, conditioned, rois = sim.build_synthetic_setup(scenario="Clean")

    t0 = time.time()
    result: CalibrationResult = run_joint_solver(
        rois=rois,
        dictionary=dictionary,
        conditioned=conditioned,
        detector_wavelengths=sim.det_wl,
        material_families=sim.material_families,
        te_grid=sim.te_grid,
        ne_grid=sim.ne_grid,
    )
    elapsed = time.time() - t0
    assert elapsed < 60.0, f"Clean scenario took {elapsed:.1f}s (must be < 60s)"

    assert result.converged is True
    assert len(result.diagnostics_flags) == 0, f"Expected 0 flags, got: {result.diagnostics_flags}"

    # Evaluate response accuracy over response set span
    resp_sub_bins = [sb for r in rois if r.used_in_response_fit for sb in r.sub_bins]
    span_chans = [ch for sb in resp_sub_bins for ch in sb]
    idx_min, idx_max = min(span_chans), max(span_chans)

    true_r_span = sim.true_response[idx_min : idx_max + 1]
    est_r_span = result.spectral_response[idx_min : idx_max + 1]

    # Align gauge (log mean) before comparison
    true_log = np.log(true_r_span) - np.mean(np.log(true_r_span))
    est_log = np.log(est_r_span) - np.mean(np.log(est_r_span))

    rms_err = np.sqrt(np.mean((est_log - true_log) ** 2))
    assert rms_err <= 0.10, f"Clean response RMS error {rms_err:.4f} exceeds 10% threshold"


def test_planted_truth_harsh_scenario():
    """R12 Harsh Scenario: Missing lines, self-absorption, perturbed Aki.
    Response RMS error <= 20% in 350-550 nm; max r / min r <= 3x truth; converged.
    """
    sim = PlantedTruthSimulation(seed=202)
    dictionary, conditioned, rois = sim.build_synthetic_setup(scenario="Harsh")

    t0 = time.time()
    result = run_joint_solver(
        rois=rois,
        dictionary=dictionary,
        conditioned=conditioned,
        detector_wavelengths=sim.det_wl,
        material_families=sim.material_families,
        te_grid=sim.te_grid,
        ne_grid=sim.ne_grid,
    )
    elapsed = time.time() - t0
    assert elapsed < 60.0

    assert result.converged is True

    # Check 350-550 nm range
    mask_350_550 = (sim.det_wl >= 350.0) & (sim.det_wl <= 550.0)
    true_log = np.log(sim.true_response[mask_350_550])
    true_log -= np.mean(true_log)

    est_log = np.log(result.spectral_response[mask_350_550])
    est_log -= np.mean(est_log)

    rms_err = np.sqrt(np.mean((est_log - true_log) ** 2))
    assert rms_err <= 0.20, f"Harsh response RMS error in 350-550 nm {rms_err:.4f} exceeds 20% threshold"

    # Dynamic range max/min <= 3x truth
    true_ratio = np.max(sim.true_response) / np.min(sim.true_response)
    est_ratio = np.max(result.spectral_response) / np.min(result.spectral_response)
    assert est_ratio <= 3.0 * true_ratio, f"Estimated dynamic range {est_ratio:.1f} exceeds 3x truth {3*true_ratio:.1f}"


def test_planted_truth_edge_scenario():
    """R12 Edge Scenario: Material 469 with Te = 0.42 eV below grid min -> TE_GRID_EDGE flag raised."""
    sim = PlantedTruthSimulation(seed=303)
    dictionary, conditioned, rois = sim.build_synthetic_setup(scenario="Edge")

    result = run_joint_solver(
        rois=rois,
        dictionary=dictionary,
        conditioned=conditioned,
        detector_wavelengths=sim.det_wl,
        material_families=sim.material_families,
        te_grid=sim.te_grid,
        ne_grid=sim.ne_grid,
    )
    assert any("TE_GRID_EDGE" in f for f in result.diagnostics_flags), (
        f"TE_GRID_EDGE flag must be raised, got: {result.diagnostics_flags}"
    )


def test_planted_truth_split_scenario():
    """R12 Split Scenario: Clean scenario, iron-based vs others -> familyGroupResponseRmsDiff <= 0.05, no RESPONSE_FAMILY_INCONSISTENT."""
    sim = PlantedTruthSimulation(seed=404)
    dictionary, conditioned, rois = sim.build_synthetic_setup(scenario="Clean")

    result = run_joint_solver(
        rois=rois,
        dictionary=dictionary,
        conditioned=conditioned,
        detector_wavelengths=sim.det_wl,
        material_families=sim.material_families,
        te_grid=sim.te_grid,
        ne_grid=sim.ne_grid,
    )
    assert not any("RESPONSE_FAMILY_INCONSISTENT" in f for f in result.diagnostics_flags)
    assert result.family_group_response_rms_diff <= 0.05, (
        f"familyGroupResponseRmsDiff {result.family_group_response_rms_diff} exceeds 0.05"
    )
