"""Validation of spectral match against hold-out and validation materials (R11, AC11).

Evaluates model fit, thin/prone residual distributions, and element parity on independent
hold-out measurements, and compares against three ablations:
1. Flat response (R ≡ 1)
2. No absorption (k ≡ 0)
3. Legacy rendering (linear interpolation without added line spread)
"""

from __future__ import annotations

import argparse
import datetime
import json
import logging
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd

# Add repo root to path if needed
REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

try:
    from scripts.measured_conditioning import condition_material, ConditionedMaterial
    from scripts.forward_model import (
        NIST_UNIT_SCALE,
        PlasmaZoneSpec,
        apply_multi_zone_rte,
        apply_response_and_scale,
        resample_to_detector,
    )
    from scripts.roi_joint_calibration import (
        compute_sub_bin_variances,
        solve_family_plasma,
        scan_absorption,
        compute_family_scales_and_sharing_penalty,
    )
    from scripts.match_metrics import (
        compute_spectral_metrics,
        calculate_reduced_chi2,
        calculate_roi_metrics,
        calculate_whole_spectrum_metrics,
        calculate_sub_bin_residuals,
        calculate_element_parity_table,
    )
except ImportError:
    from measured_conditioning import condition_material, ConditionedMaterial
    from forward_model import (
        NIST_UNIT_SCALE,
        PlasmaZoneSpec,
        apply_multi_zone_rte,
        apply_response_and_scale,
        resample_to_detector,
    )
    from roi_joint_calibration import (
        compute_sub_bin_variances,
        solve_family_plasma,
        scan_absorption,
        compute_family_scales_and_sharing_penalty,
    )
    from match_metrics import (
        compute_spectral_metrics,
        calculate_reduced_chi2,
        calculate_roi_metrics,
        calculate_whole_spectrum_metrics,
        calculate_sub_bin_residuals,
        calculate_element_parity_table,
    )

logger = logging.getLogger("validate_spectral_match")


def load_nist_dictionary(
    nist_paths: Dict[str, str],
    detector_wls: np.ndarray,
    legacy_rendering: bool = False,
    line_spread_sigma: Optional[np.ndarray] = None,
) -> Tuple[np.ndarray, List[Tuple[float, float]]]:
    """Load and resample NIST dictionary states into a (N_states, N_channels) matrix."""
    states = []
    state_keys = sorted(nist_paths.keys())
    for k in state_keys:
        parts = k.split("_")
        te = float(parts[0])
        ne = float(parts[1])
        states.append((te, ne))

    d_cols = []
    for k in state_keys:
        csv_p = nist_paths[k]
        df = pd.read_csv(csv_p)
        lam_src = df.iloc[:, 0].to_numpy(dtype=np.float64)
        s_src = df.iloc[:, 1].to_numpy(dtype=np.float64) * NIST_UNIT_SCALE

        if legacy_rendering:
            s_res = np.interp(detector_wls, lam_src, s_src, left=0.0, right=0.0)
        else:
            sigma_val = line_spread_sigma if line_spread_sigma is not None else 0.0
            s_res = resample_to_detector(lam_src, s_src, detector_wls, add_sigma_nm=sigma_val)

        d_cols.append(s_res)

    return np.array(d_cols, dtype=np.float64), states


def synthesize_spectrum(
    dictionary: np.ndarray,
    states: List[Tuple[float, float]],
    plasma_zones: List[Dict[str, Any]],
    scale_factor: float,
    spectral_response: np.ndarray,
) -> np.ndarray:
    """Synthesize RTE spectrum from dictionary, zones, scale, and response."""
    p_channels = dictionary.shape[1]
    if len(plasma_zones) == 0:
        return np.zeros(p_channels, dtype=np.float64)

    zone_spectra: List[np.ndarray] = []
    zone_specs: List[PlasmaZoneSpec] = []
    for z in plasma_zones:
        tez = float(z["Te"])
        nez = float(z["Ne"])
        diffs = [abs(s[0] - tez) + abs(np.log10(s[1]) - np.log10(nez)) for s in states]
        idxz = int(np.argmin(diffs))
        zone_spectra.append(dictionary[idxz])
        zone_specs.append(PlasmaZoneSpec.from_dict(z))

    i_rad = apply_multi_zone_rte(zone_spectra, zone_specs)
    i_det = apply_response_and_scale(i_rad, spectral_response, scale_factor, emission_scale="absolute")
    return np.maximum(i_det, 0.0)


def evaluate_material_match(
    conditioned: ConditionedMaterial,
    dictionary: np.ndarray,
    states: List[Tuple[float, float]],
    plasma_zones: List[Dict[str, Any]],
    scale_factor: float,
    spectral_response: np.ndarray,
    rois: List[Dict[str, Any]],
    union_channel_indices: List[int],
) -> Dict[str, Any]:
    """Evaluate spectral match metrics on a conditioned measurement."""
    y_meas = conditioned.preprocessed_mean if hasattr(conditioned, "preprocessed_mean") else (conditioned.mean_spectrum if hasattr(conditioned, "mean_spectrum") else conditioned.y_mean)
    lit_mask = conditioned.lit_mask if hasattr(conditioned, "lit_mask") else conditioned.lit_channel_mask
    sigma_mean = conditioned.sigma_mean
    y_synth = synthesize_spectrum(
        dictionary=dictionary,
        states=states,
        plasma_zones=plasma_zones,
        scale_factor=scale_factor,
        spectral_response=spectral_response,
    )

    # Sub-bin sums
    sub_meas = []
    sub_synth = []
    sub_vars = []
    prone_flags = []
    dominant_elements = []

    for r in rois:
        is_prone = bool(r.get("selfAbsorptionProne", False))
        dom_el = str(r.get("dominantElement", ""))
        sub_bins = r.get("subBins") or [r.get("channelIndices", [])]

        for sb in sub_bins:
            if len(sb) == 0:
                continue
            idx = np.array(sb, dtype=np.int64)
            c_meas = float(np.sum(y_meas[idx]))
            c_synth = float(np.sum(y_synth[idx]))

            # Variances
            var_dict = compute_sub_bin_variances(
                channels=list(sb),
                mean_spectrum=y_meas,
                sigma_mean=sigma_mean,
            )
            v_b = float(var_dict.get("sigma_sq", var_dict.get("total_var", 1.0)))

            sub_meas.append(c_meas)
            sub_synth.append(c_synth)
            sub_vars.append(v_b)
            prone_flags.append(is_prone)
            dominant_elements.append(dom_el)

    sub_meas_arr = np.array(sub_meas, dtype=np.float64)
    sub_synth_arr = np.array(sub_synth, dtype=np.float64)
    sub_vars_arr = np.array(sub_vars, dtype=np.float64)

    # Reduced chi2 via canonical compute_spectral_metrics (normalization="none")
    if len(sub_meas_arr) > 0:
        chi2_res = compute_spectral_metrics(
            sub_meas_arr,
            sub_synth_arr,
            variance=sub_vars_arr,
            normalization="none",
            num_params=0,
        )
        chi2_red = float(chi2_res["reduced_chi2"] or 0.0)
    else:
        chi2_red = 0.0

    # ROI R^2 and RMSE (nrmse_max under normalization="none" + raw rmse)
    roi_mask = np.zeros(len(y_meas), dtype=bool)
    if union_channel_indices:
        roi_mask[np.asarray(union_channel_indices, dtype=np.int64)] = True
    if np.any(roi_mask):
        roi_metrics = compute_spectral_metrics(
            y_meas, y_synth, mask=roi_mask, normalization="none"
        )
        roi_r2 = float(roi_metrics["r2"])
        roi_rmse = float(roi_metrics["nrmse_max"])
        roi_rmse_raw = float(roi_metrics["rmse"])
    else:
        roi_r2, roi_rmse, roi_rmse_raw = 0.0, 0.0, 0.0

    # Whole-spectrum R^2 and RMSE (nrmse_max under normalization="none" + raw rmse)
    lit_mask_arr = np.asarray(lit_mask, dtype=bool)
    if np.any(lit_mask_arr):
        whole_metrics = compute_spectral_metrics(
            y_meas, y_synth, mask=lit_mask_arr, normalization="none"
        )
        whole_r2 = float(whole_metrics["r2"])
        whole_rmse = float(whole_metrics["nrmse_max"])
        whole_rmse_raw = float(whole_metrics["rmse"])
    else:
        whole_r2, whole_rmse, whole_rmse_raw = 0.0, 0.0, 0.0

    # Sub-bin residuals
    residuals_dist = calculate_sub_bin_residuals(sub_meas_arr, sub_synth_arr, prone_flags)

    # Element parity
    parity_table = calculate_element_parity_table(dominant_elements, sub_meas_arr, sub_synth_arr)

    return {
        "roiChi2Reduced": round(chi2_red, 4),
        "roiRSquared": round(roi_r2, 6),
        "roiRmse": round(roi_rmse, 6),
        "roiRmseRaw": round(roi_rmse_raw, 6),
        "wholeSpectrumRSquared": round(whole_r2, 6),
        "wholeSpectrumRmse": round(whole_rmse, 6),
        "wholeSpectrumRmseRaw": round(whole_rmse_raw, 6),
        "subBinResiduals": residuals_dist,
        "elementParity": parity_table,
        "syntheticSpectrum": y_synth,
    }


def run_ablation_fits(
    conditioned: ConditionedMaterial,
    dictionary: np.ndarray,
    dict_legacy: np.ndarray,
    states: List[Tuple[float, float]],
    plasma_zones: List[Dict[str, Any]],
    scale_factor: float,
    spectral_response: np.ndarray,
    rois: List[Dict[str, Any]],
    union_channel_indices: List[int],
) -> Dict[str, Dict[str, float]]:
    """Run three ablations: flat response, no absorption, legacy rendering."""
    # 1. Flat response (R ≡ 1.0)
    flat_response = np.ones_like(spectral_response)
    flat_fit = evaluate_material_match(
        conditioned=conditioned,
        dictionary=dictionary,
        states=states,
        plasma_zones=plasma_zones,
        scale_factor=scale_factor,
        spectral_response=flat_response,
        rois=rois,
        union_channel_indices=union_channel_indices,
    )

    # 2. No absorption (k ≡ 0.0)
    zones_no_abs = []
    for z in plasma_zones:
        z_copy = dict(z)
        z_copy["kAbsorption"] = 0.0
        zones_no_abs.append(z_copy)

    no_abs_fit = evaluate_material_match(
        conditioned=conditioned,
        dictionary=dictionary,
        states=states,
        plasma_zones=zones_no_abs,
        scale_factor=scale_factor,
        spectral_response=spectral_response,
        rois=rois,
        union_channel_indices=union_channel_indices,
    )

    # 3. Legacy rendering (linear interpolation without added line spread)
    legacy_fit = evaluate_material_match(
        conditioned=conditioned,
        dictionary=dict_legacy,
        states=states,
        plasma_zones=plasma_zones,
        scale_factor=scale_factor,
        spectral_response=spectral_response,
        rois=rois,
        union_channel_indices=union_channel_indices,
    )

    return {
        "flatResponse": {
            "roiChi2Reduced": flat_fit["roiChi2Reduced"],
            "roiRSquared": flat_fit["roiRSquared"],
            "roiRmse": flat_fit["roiRmse"],
            "wholeSpectrumRSquared": flat_fit["wholeSpectrumRSquared"],
            "wholeSpectrumRmse": flat_fit["wholeSpectrumRmse"],
        },
        "noAbsorption": {
            "roiChi2Reduced": no_abs_fit["roiChi2Reduced"],
            "roiRSquared": no_abs_fit["roiRSquared"],
            "roiRmse": no_abs_fit["roiRmse"],
            "wholeSpectrumRSquared": no_abs_fit["wholeSpectrumRSquared"],
            "wholeSpectrumRmse": no_abs_fit["wholeSpectrumRmse"],
        },
        "legacyRendering": {
            "roiChi2Reduced": legacy_fit["roiChi2Reduced"],
            "roiRSquared": legacy_fit["roiRSquared"],
            "roiRmse": legacy_fit["roiRmse"],
            "wholeSpectrumRSquared": legacy_fit["wholeSpectrumRSquared"],
            "wholeSpectrumRmse": legacy_fit["wholeSpectrumRmse"],
        },
    }


def generate_match_report(
    manifest_path: str,
    profile_path: str,
    rois_path: str,
    output_report_path: Optional[str] = None,
) -> Dict[str, Any]:
    """Generate spectralMatchReport.json covering holdout and validation materials with ablations."""
    manifest = json.loads(Path(manifest_path).read_text(encoding="utf-8"))
    profile = json.loads(Path(profile_path).read_text(encoding="utf-8"))
    rois_json = json.loads(Path(rois_path).read_text(encoding="utf-8"))

    detector_wls = np.array(profile["wavelengths"], dtype=np.float64)
    spectral_response = np.array(profile["spectralResponse"], dtype=np.float64)
    line_spread_sigma = np.array(profile.get("lineSpreadAddSigmaNm", [0.0] * len(detector_wls)), dtype=np.float64)

    validation_mats = set(str(m) for m in manifest.get("validationMaterials", []))
    family_profiles = profile.get("materialFamilyProfiles", {})
    family_rois_map = rois_json.get("materialFamilyRois") or rois_json.get("families") or {}

    materials_report: Dict[str, Any] = {}
    family_r2_scores: Dict[str, List[float]] = {}
    family_rmse_scores: Dict[str, List[float]] = {}
    family_legacy_r2: Dict[str, List[float]] = {}

    for family_name, mat_list in manifest.get("materialFamilies", {}).items():
        fam_profile = family_profiles.get(family_name, {})
        plasma_zones = fam_profile.get("plasmaZones", [])
        fam_scale = float(fam_profile.get("scaleFactor", 1.0))
        fam_rois_obj = family_rois_map.get(family_name, {})

        if isinstance(fam_rois_obj, dict):
            rois_list = fam_rois_obj.get("rois") or fam_rois_obj.get("regionsOfInterest", [])
            union_chans = fam_rois_obj.get("unionChannelIndices", [])
        else:
            rois_list = fam_rois_obj
            union_chans = []

        if not union_chans and rois_list:
            union_set = set()
            for r in rois_list:
                for idx in r.get("channelIndices", []):
                    union_set.add(idx)
            union_chans = sorted(union_set)

        for mat in mat_list:
            mat_name = str(mat.get("materialName", ""))
            holdout_csvs = mat.get("holdoutCsvPaths", [])
            is_val = mat_name in validation_mats

            # Process only materials that have holdout CSVs or are validation materials
            if not holdout_csvs and not is_val:
                continue

            csvs_to_test = holdout_csvs if holdout_csvs else mat.get("measuredCsvPaths", [])
            if not csvs_to_test:
                continue

            # Load conditioned holdout measurement
            cond = condition_material(csv_paths=csvs_to_test)

            # Load dictionaries (normal and legacy)
            nist_paths = mat.get("nistGridCsvPaths", {})
            dictionary, states = load_nist_dictionary(
                nist_paths=nist_paths,
                detector_wls=detector_wls,
                legacy_rendering=False,
                line_spread_sigma=line_spread_sigma,
            )
            dict_legacy, _ = load_nist_dictionary(
                nist_paths=nist_paths,
                detector_wls=detector_wls,
                legacy_rendering=True,
            )

            # Evaluate Primary Fit
            mat_scale = float(fam_profile.get("materialScaleFactors", {}).get(mat_name, fam_scale))
            primary_eval = evaluate_material_match(
                conditioned=cond,
                dictionary=dictionary,
                states=states,
                plasma_zones=plasma_zones,
                scale_factor=mat_scale,
                spectral_response=spectral_response,
                rois=rois_list,
                union_channel_indices=union_chans,
            )

            # Remove synthetic spectrum numpy array from exported json
            primary_eval.pop("syntheticSpectrum", None)

            # Evaluate Ablations
            ablations = run_ablation_fits(
                conditioned=cond,
                dictionary=dictionary,
                dict_legacy=dict_legacy,
                states=states,
                plasma_zones=plasma_zones,
                scale_factor=mat_scale,
                spectral_response=spectral_response,
                rois=rois_list,
                union_channel_indices=union_chans,
            )

            materials_report[mat_name] = {
                "family": family_name,
                "isValidationMaterial": is_val,
                "numHoldoutPoints": len(csvs_to_test),
                "primaryFit": primary_eval,
                "ablations": ablations,
            }

            family_r2_scores.setdefault(family_name, []).append(primary_eval["roiRSquared"])
            family_rmse_scores.setdefault(family_name, []).append(primary_eval["roiRmse"])
            family_legacy_r2.setdefault(family_name, []).append(ablations["legacyRendering"]["roiRSquared"])

    # Family summary
    family_summary = {}
    for fam, r2s in family_r2_scores.items():
        family_summary[fam] = {
            "meanRoiRSquared": round(float(np.mean(r2s)), 6),
            "meanRoiRmse": round(float(np.mean(family_rmse_scores[fam])), 6),
            "meanLegacyRoiRSquared": round(float(np.mean(family_legacy_r2[fam])), 6),
        }

    report = {
        "schemaVersion": "1.0.0",
        "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "materials": materials_report,
        "familySummary": family_summary,
    }

    if output_report_path:
        out_p = Path(output_report_path)
        out_p.parent.mkdir(parents=True, exist_ok=True)
        out_p.write_text(json.dumps(report, indent=2), encoding="utf-8")
        logger.info(f"Match report written to {out_p.resolve()}")

    return report


def main():
    parser = argparse.ArgumentParser(description="Generate LIBS Spectral Match Validation Report (R11)")
    parser.add_argument("--manifest", default="temp/calibration_manifest.json", help="Path to manifest JSON")
    parser.add_argument("--profile", default="conf/instrument_profile.json", help="Path to instrument profile JSON")
    parser.add_argument("--rois", default="data/regionsOfInterest.json", help="Path to ROIs JSON")
    parser.add_argument("--output", default="data/spectralMatchReport.json", help="Output path for match report")
    args = parser.parse_args()

    generate_match_report(
        manifest_path=args.manifest,
        profile_path=args.profile,
        rois_path=args.rois,
        output_report_path=args.output,
    )


if __name__ == "__main__":
    main()
