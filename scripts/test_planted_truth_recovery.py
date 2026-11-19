"""Planted-truth recovery tests for joint plasma-response calibration (R12, AC7).

Simulates synthetic multi-element LIBS spectra with known ground-truth
analytic response curves and off-grid plasma states, verifying that the
joint solver (R6, R7, R9) accurately recovers the instrument response and
correctly raises diagnostics under stress scenarios:
1. Clean: RMS response error <= 10%, converged, no flags.
2. Harsh: 30% missing lines, unmodelled lines > 560 nm, low-Ei self-absorption, 5% perturbed Aki -> RMS error <= 20% in 350-550 nm, max/min r <= 3x truth.
3. Edge: Te = 0.42 eV below grid minimum -> TE_GRID_EDGE raised.
4. Split: Iron-based vs other families -> familyGroupResponseRmsDiff <= 0.05, no RESPONSE_FAMILY_INCONSISTENT.
"""

from __future__ import annotations

import time
import numpy as np
import pandas as pd
import pytest

from scripts.measured_conditioning import ConditionedMaterial
from scripts.roi_catalogue import RoiWindow, SubBin
from scripts.roi_joint_calibration import (
    CalibrationResult,
    run_joint_solver,
    CubicBSplineBasis,
)


class PlantedTruthSimulation:
    """Fast, deterministic synthetic generator with known ground truth."""

    def __init__(self, seed: int = 42):
        self.rng = np.random.default_rng(seed)
        # 2032 lit detector channels
        self.det_wl = np.linspace(350.0, 850.0, 2032)
        self.elements = ["Fe", "Cr", "Ni", "Mn", "Cu", "Al", "Si", "Mo"]

        # Known analytic ground truth response with 20x dynamic range
        # Matches realistic spectrometer efficiency (cut-on, peak, fall-off)
        x = (self.det_wl - 350.0) / (850.0 - 350.0)
        log_r_true = 1.8 * np.sin(np.pi * x) - 1.0 * (x - 0.2) ** 2
        log_r_true = log_r_true * (np.log(20.0) / (np.max(log_r_true) - np.min(log_r_true)))
        log_r_true -= np.mean(log_r_true)
        self.true_response = np.exp(log_r_true)

        # 7 materials in 3 families (Fe-based, Cu-based, Al-based)
        self.compositions = {
            "Fe_Mat1": {"Fe": 80.0, "Cr": 15.0, "Ni": 5.0},
            "Fe_Mat2": {"Fe": 70.0, "Cr": 20.0, "Mn": 10.0},
            "Fe_Mat3": {"Fe": 90.0, "Mo": 5.0, "Si": 5.0},
            "Cu_Mat1": {"Cu": 85.0, "Al": 10.0, "Ni": 5.0},
            "Cu_Mat2": {"Cu": 90.0, "Si": 5.0, "Fe": 5.0},
            "Al_Mat1": {"Al": 92.0, "Si": 6.0, "Cu": 2.0},
            "Al_Mat2": {"Al": 95.0, "Mn": 3.0, "Fe": 2.0},
        }
        self.material_families = {
            "Fe_Mat1": "Ferritic_Steel", "Fe_Mat2": "Ferritic_Steel", "Fe_Mat3": "Ferritic_Steel",
            "Cu_Mat1": "Bronze", "Cu_Mat2": "Bronze",
            "Al_Mat1": "Aluminium", "Al_Mat2": "Aluminium",
        }

        # Plasma states grid
        self.te_grid = [0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0]
        self.ne_grid = [1e16, 5e16, 1e17, 5e17]
        self.n_states = len(self.te_grid) * len(self.ne_grid)

        # Build synthetic line library: 1,600 lines
        self.lines = self._generate_synthetic_lines(n_lines=1600)

    def _generate_synthetic_lines(self, n_lines: int) -> pd.DataFrame:
        wls = np.sort(self.rng.uniform(352.0, 848.0, n_lines))
        elems = self.rng.choice(self.elements, size=n_lines, p=[0.35, 0.15, 0.10, 0.10, 0.12, 0.08, 0.05, 0.05])
        ei = self.rng.uniform(0.0, 5.0, n_lines)
        ek = ei + (1239.841984 / wls)
        aki = 10.0 ** self.rng.uniform(6.5, 8.2, n_lines)
        gk = self.rng.choice([1, 3, 5, 7, 9], size=n_lines)
        unc = self.rng.choice([0.03, 0.07, 0.10, 0.18, 0.25], size=n_lines)
        accuracy = ["B"] * n_lines

        return pd.DataFrame({
            "species": [f"{el} I" for el in elems],
            "element": elems,
            "wavelength_nm": wls,
            "wl_nm": wls,
            "Ei_eV": ei,
            "Ek_eV": ek,
            "Aki": aki,
            "g_k": gk,
            "gA": gk * aki,
            "unc": unc,
            "accuracy": accuracy,
            "boltzmann_ok": True,
        })

    def build_synthetic_setup(self, scenario: str = "Clean"):
        """Build dictionary, ROIs, and conditioned measurements for a given scenario."""
        # Perturb dictionary Aki by 15% log-normal for fitting dictionary
        dict_lines = self.lines.copy()
        dict_lines["Aki"] = dict_lines["Aki"] * np.exp(self.rng.normal(0.0, 0.15, len(dict_lines)))
        dict_lines["gA"] = dict_lines["g_k"] * dict_lines["Aki"]

        if scenario == "Harsh":
            # 30% of lines absent from fitting dictionary
            keep_mask = self.rng.uniform(0.0, 1.0, len(dict_lines)) > 0.30
            dict_lines = dict_lines[keep_mask].reset_index(drop=True)
            # 5% of A-values wrong by 2x
            wrong_idx = self.rng.choice(len(dict_lines), size=int(0.05 * len(dict_lines)), replace=False)
            dict_lines.loc[wrong_idx, "Aki"] *= self.rng.choice([0.5, 2.0], size=len(wrong_idx))
            dict_lines["gA"] = dict_lines["g_k"] * dict_lines["Aki"]

        # Build fitting dictionary D_m for each material
        # Map each line to detector channels with Gaussian spread
        sigma_inst = 0.15  # nm
        channel_pitch = (self.det_wl[-1] - self.det_wl[0]) / (len(self.det_wl) - 1)

        dictionary = {}
        for mat_name, comp in self.compositions.items():
            D = np.zeros((len(self.det_wl), self.n_states), dtype=np.float64)
            st_idx = 0
            for te in self.te_grid:
                for ne in self.ne_grid:
                    col = np.zeros(len(self.det_wl), dtype=np.float64)
                    for _, line in dict_lines.iterrows():
                        el = line["element"]
                        wt = comp.get(el, 0.0)
                        if wt <= 0:
                            continue
                        em = (line["gA"] / line["wl_nm"]) * np.exp(-line["Ek_eV"] / te) * wt * 1e-4
                        p_center = np.searchsorted(self.det_wl, line["wl_nm"])
                        if 0 <= p_center < len(self.det_wl):
                            # Distribute over 5 channels
                            lo = max(0, p_center - 2)
                            hi = min(len(self.det_wl), p_center + 3)
                            diff = (self.det_wl[lo:hi] - line["wl_nm"]) / sigma_inst
                            prof = np.exp(-0.5 * diff ** 2)
                            col[lo:hi] += em * (prof / np.sum(prof))
                    D[:, st_idx] = col
                    st_idx += 1
            dictionary[mat_name] = D

        # Generate synthetic measured spectra from ground truth
        conditioned = {}
        true_weights = {}

        for mat_name, comp in self.compositions.items():
            # True plasma: two off-grid zones
            if scenario == "Edge" and mat_name == "Fe_Mat1":
                te1, te2 = 0.42, 0.44  # below grid min 0.50 eV
            else:
                te1, te2 = 0.85, 0.65  # off-grid between 0.8 and 0.9, 0.6 and 0.7

            # Forward emission from generating lines
            y_synth = np.zeros(len(self.det_wl), dtype=np.float64)
            for _, line in self.lines.iterrows():
                el = line["element"]
                wt = comp.get(el, 0.0)
                if wt <= 0:
                    continue
                # Unmodelled lines above 560 nm in Harsh scenario
                if scenario == "Harsh" and line["wl_nm"] > 560.0 and self.rng.uniform() < 0.15:
                    wt *= 2.5

                em1 = (line["gA"] / line["wl_nm"]) * np.exp(-line["Ek_eV"] / te1) * wt * 1e-4
                em2 = (line["gA"] / line["wl_nm"]) * np.exp(-line["Ek_eV"] / te2) * wt * 1e-4

                # Self absorption on low-Ei major lines in Harsh scenario
                k_abs = 0.0
                if scenario == "Harsh" and wt >= 50.0 and line["Ei_eV"] <= 2.0:
                    k_abs = 1.5

                comb_em = (0.7 * em1) * np.exp(-k_abs) + 0.3 * em2

                p_center = np.searchsorted(self.det_wl, line["wl_nm"])
                if 0 <= p_center < len(self.det_wl):
                    lo = max(0, p_center - 2)
                    hi = min(len(self.det_wl), p_center + 3)
                    diff = (self.det_wl[lo:hi] - line["wl_nm"]) / sigma_inst
                    prof = np.exp(-0.5 * diff ** 2)
                    y_synth[lo:hi] += comb_em * (prof / np.sum(prof))

            # Apply true instrument response and scaling
            y_meas = y_synth * self.true_response * 5000.0
            # Add realistic Poisson + detector read noise
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

        # Build clean disjoint ROIs covering significant peaks
        rois = []
        # Sample ~60 peaks across detector for clean response recovery
        cand_indices = np.linspace(40, len(self.det_wl) - 40, 65, dtype=int)
        for i, c_idx in enumerate(cand_indices):
            w_lo_idx = max(0, c_idx - 6)
            w_hi_idx = min(len(self.det_wl) - 1, c_idx + 6)
            chans = list(range(w_lo_idx, w_hi_idx + 1))
            sub_bins = [chans[j : j + 4] for j in range(0, len(chans), 4)]
            if len(sub_bins) > 1 and len(sub_bins[-1]) < 3:
                sub_bins[-2].extend(sub_bins.pop())

            is_prone = (scenario == "Harsh" and i % 5 == 0)
            rois.append(RoiWindow(
                roi_id=f"ROI_{i:03d}_Fe_{int(self.det_wl[c_idx])}",
                min_nm=float(self.det_wl[w_lo_idx]),
                max_nm=float(self.det_wl[w_hi_idx]),
                channel_indices=chans,
                sub_bins=sub_bins,
                self_absorption_prone=is_prone,
                used_in_response_fit=(not is_prone),
                dominant_element="Fe" if i % 2 == 0 else "Cu",
                model_lines=[{"species": "Fe I", "wlNm": self.det_wl[c_idx], "EiEv": 2.5, "EkEv": 5.0, "unc": 0.10}],
            ))

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
    assert len(result.diagnostics_flags) == 0

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
    """R12 Edge Scenario: Material with Te = 0.42 eV below grid min -> TE_GRID_EDGE flag raised."""
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
