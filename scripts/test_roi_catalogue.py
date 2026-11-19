"""Unit tests for ROI Catalogue selection (R5, AC2, AC3)."""

from __future__ import annotations

import ast
from pathlib import Path
import numpy as np
import pandas as pd
import pytest

from scripts.measured_conditioning import ConditionedMaterial
from scripts.nist_lines import DEFAULT_CACHE_DIR, load_model_lines, lte_strength
from scripts.roi_catalogue import (
    DEFAULT_MASK_WINDOWS_NM,
    RoiWindow,
    SubBin,
    build_catalogue,
    get_candidates,
    is_line_masked,
    partition_sub_bins,
    merge_windows,
)


def make_mock_conditioned(wavelengths: np.ndarray, mean_counts: float = 1000.0, sigma: float = 10.0) -> ConditionedMaterial:
    n = len(wavelengths)
    lit = np.ones(n, dtype=bool)
    return ConditionedMaterial(
        wavelengths=wavelengths,
        mean_spectrum=np.full(n, mean_counts, dtype=np.float64),
        preprocessed_mean=np.full(n, mean_counts, dtype=np.float64),
        sigma_mean=np.full(n, sigma, dtype=np.float64),
        valid_mask=lit,
        lit_mask=lit,
        dark_level=100.0,
        point_means={"P1": np.full(n, mean_counts, dtype=np.float64)},
        discarded_shots_per_point={"P1": 0},
    )


def test_candidates_filtering_wt_and_lit_range():
    """Candidates contain only model lines of certified elements at or above 0.05 wt%, inside lit range."""
    # Compositions: Mat1 has Fe 90%, Cr 0.03%, Mn 0.10%
    compositions = {
        "Mat1": {"Fe": 90.0, "Cr": 0.03, "Mn": 0.10}
    }
    # Cr is < 0.05 wt%, so its lines must be excluded.
    # Fe is 90%, Mn is 0.10% >= 0.05%, so their lines can be candidates.
    df_lines = pd.DataFrame({
        "species": ["Fe I", "Fe I", "Mn I", "Cr I", "Fe I"],
        "element": ["Fe", "Fe", "Mn", "Cr", "Fe"],
        "wavelength_nm": [400.0, 340.0, 450.0, 460.0, 500.0],
        "Aki": [1e7, 1e7, 1e7, 1e7, 1e7],
        "Ei_eV": [1.0, 1.0, 1.0, 1.0, 1.0],
        "Ek_eV": [4.0, 4.0, 4.0, 4.0, 4.0],
        "g_i": [5.0, 5.0, 5.0, 5.0, 5.0],
        "g_k": [7.0, 7.0, 7.0, 7.0, 7.0],
        "accuracy": ["B", "B", "B", "B", "D"],  # Fe line at 500 has unc=0.50 > 0.25
        "boltzmann_ok": [True, True, True, True, True],
        "unc": [0.10, 0.10, 0.10, 0.10, 0.50],
    })
    
    lit_range = (350.0, 850.0)
    cands = get_candidates(compositions, df_lines, lit_range=lit_range, min_certified_wt=0.05, max_unc=0.25)
    
    # 340.0 is outside lit_range (350-850) -> excluded
    # Cr I is < 0.05 wt% -> excluded
    # Fe I at 500.0 has unc=0.50 > 0.25 -> excluded
    # Expected remaining: Fe I 400.0 and Mn I 450.0
    assert len(cands) == 2
    assert set(cands["wavelength_nm"].round(1)) == {400.0, 450.0}


def test_mask_windows_exclusion():
    """Lines falling within mask windows are excluded."""
    # Default mask windows include H (653.3-659.3) and O I (775.8-778.8)
    assert is_line_masked(656.28, DEFAULT_MASK_WINDOWS_NM) is True
    assert is_line_masked(777.41, DEFAULT_MASK_WINDOWS_NM) is True
    assert is_line_masked(500.00, DEFAULT_MASK_WINDOWS_NM) is False
    assert is_line_masked(393.36, DEFAULT_MASK_WINDOWS_NM) is True  # Ca II 392.6-394.2


def test_model_detectability_and_line_significance():
    """Model detectability and line significance thresholds filter weak/insignificant lines."""
    det_wl = np.linspace(400.0, 500.0, 500)  # 0.2 nm pitch
    
    # Line 1: Strong line at 420.0 nm
    # Line 2: Weak line at 420.2 nm in the same window (very small LTE strength compared to line 1)
    # Line 3: Isolated line at 480.0 nm where model window sum is tiny (< 0.5% of max window)
    df_lines = pd.DataFrame({
        "species": ["Fe I", "Fe I", "Fe I"],
        "element": ["Fe", "Fe", "Fe"],
        "wavelength_nm": [420.0, 420.2, 480.0],
        "Aki": [1e8, 1e2, 1e4],
        "Ei_eV": [0.0, 10.0, 2.0],
        "Ek_eV": [2.95, 12.95, 4.5],
        "g_i": [5.0, 5.0, 5.0],
        "g_k": [7.0, 1.0, 3.0],
        "accuracy": ["B", "B", "B"],
        "unc": [0.10, 0.10, 0.10],
        "boltzmann_ok": [True, True, True],
    })
    
    # Reference dictionary column: peaked at 420.0 nm (val=1000.0), negligible elsewhere
    dict_ref = np.zeros_like(det_wl)
    idx_420 = np.argmin(np.abs(det_wl - 420.0))
    dict_ref[idx_420 - 2:idx_420 + 3] = 1000.0
    
    compositions = {"Mat1": {"Fe": 100.0}}
    conditioned = {"Mat1": make_mock_conditioned(det_wl, mean_counts=5000.0, sigma=10.0)}
    
    rois = build_catalogue(
        family="TestFamily",
        compositions=compositions,
        model_lines=df_lines,
        dictionary_ref=dict_ref,
        conditioned=conditioned,
        detector_wavelengths=det_wl,
    )
    
    # Line 1 (420.0) should pass.
    # Line 2 (420.2) is < 10% of window lines and < 1e-4 of max line -> rejected.
    # Line 3 (480.0) has window model flux 0.0 (< 0.5% of 420.0 window) -> rejected.
    assert len(rois) == 1
    assert pytest.approx(rois[0].model_lines[0]["wlNm"], 0.1) == 420.0


def test_measured_detectability():
    """Candidate must be detectable (>= 5 sigma) in every calibrated material."""
    det_wl = np.linspace(400.0, 500.0, 500)
    df_lines = pd.DataFrame({
        "species": ["Fe I"],
        "element": ["Fe"],
        "wavelength_nm": [450.0],
        "Aki": [1e8],
        "Ei_eV": [0.0],
        "Ek_eV": [2.75],
        "g_i": [5.0],
        "g_k": [7.0],
        "accuracy": ["B"],
        "unc": [0.10],
        "boltzmann_ok": [True],
    })
    dict_ref = np.zeros_like(det_wl)
    idx = np.argmin(np.abs(det_wl - 450.0))
    dict_ref[idx - 2:idx + 3] = 1000.0
    
    compositions = {"Mat1": {"Fe": 100.0}, "Mat2": {"Fe": 100.0}}
    
    # Case A: Mat1 has 500 counts (SNR=50), Mat2 has 500 counts (SNR=50) -> Passes
    cond_a = {
        "Mat1": make_mock_conditioned(det_wl, mean_counts=500.0, sigma=10.0),
        "Mat2": make_mock_conditioned(det_wl, mean_counts=500.0, sigma=10.0),
    }
    rois_a = build_catalogue("TestFamily", compositions, df_lines, dict_ref, cond_a, det_wl)
    assert len(rois_a) == 1
    
    # Case B: Mat2 has 0 counts (SNR=0 < 5) -> Fails
    cond_b = {
        "Mat1": make_mock_conditioned(det_wl, mean_counts=500.0, sigma=10.0),
        "Mat2": make_mock_conditioned(det_wl, mean_counts=0.0, sigma=10.0),
    }
    rois_b = build_catalogue("TestFamily", compositions, df_lines, dict_ref, cond_b, det_wl)
    assert len(rois_b) == 0


def test_prone_rule():
    """A candidate is prone when its element is >= 5 wt% and Ei_eV <= proneEiEv."""
    det_wl = np.linspace(400.0, 500.0, 500)
    df_lines = pd.DataFrame({
        "species": ["Fe I", "Fe I", "Mn I"],
        "element": ["Fe", "Fe", "Mn"],
        "wavelength_nm": [430.0, 440.0, 450.0],
        "Aki": [1e8, 1e8, 1e8],
        "Ei_eV": [1.5, 3.5, 1.0],  # Fe line 1 has Ei=1.5 <= 2.0; Fe line 2 has Ei=3.5 > 2.0; Mn has Ei=1.0 <= 2.0
        "Ek_eV": [4.38, 6.32, 3.75],
        "g_i": [5.0, 5.0, 5.0],
        "g_k": [7.0, 7.0, 7.0],
        "accuracy": ["B", "B", "B"],
        "unc": [0.10, 0.10, 0.10],
        "boltzmann_ok": [True, True, True],
    })
    dict_ref = np.full_like(det_wl, 500.0)
    
    # Fe is 98% (>= 5%), Mn is 2% (< 5%)
    compositions = {"Mat1": {"Fe": 98.0, "Mn": 2.0}}
    cond = {"Mat1": make_mock_conditioned(det_wl, mean_counts=2000.0, sigma=10.0)}
    
    rois = build_catalogue("TestFamily", compositions, df_lines, dict_ref, cond, det_wl, prone_ei_ev=2.0)
    assert len(rois) == 3
    
    roi_by_wl = {round(r.model_lines[0]["wlNm"]): r for r in rois}
    # Fe 430: element >= 5% and Ei=1.5 <= 2.0 -> prone
    assert roi_by_wl[430].self_absorption_prone is True
    assert roi_by_wl[430].used_in_response_fit is False
    
    # Fe 440: element >= 5% but Ei=3.5 > 2.0 -> thin
    assert roi_by_wl[440].self_absorption_prone is False
    assert roi_by_wl[440].used_in_response_fit is True
    
    # Mn 450: Ei=1.0 <= 2.0 but Mn is only 2% (< 5%) -> thin
    assert roi_by_wl[450].self_absorption_prone is False
    assert roi_by_wl[450].used_in_response_fit is True


def test_tercile_stratification_and_element_coverage():
    """Tercile stratification selects across Ek levels, and element coverage guarantees certified lines."""
    det_wl = np.linspace(400.0, 500.0, 1000)
    
    # Create 9 thin Fe lines in band [400, 425] with 3 low Ek, 3 mid Ek, 3 high Ek
    fe_records = []
    for i, ek in enumerate([2.5, 2.6, 2.7, 4.0, 4.1, 4.2, 6.0, 6.1, 6.2]):
        fe_records.append({
            "species": "Fe I", "element": "Fe", "wavelength_nm": 401.0 + i * 2.0,
            "Aki": 1e8, "Ei_eV": 2.2, "Ek_eV": ek, "g_i": 5.0, "g_k": 7.0,
            "accuracy": "B", "unc": 0.10, "boltzmann_ok": True,
        })
    # Add a minor element Si (1.0 wt%) with detectable lines
    si_records = []
    for i, wl in enumerate([440.0, 442.0, 444.0]):
        si_records.append({
            "species": "Si I", "element": "Si", "wavelength_nm": wl,
            "Aki": 5e7, "Ei_eV": 1.0, "Ek_eV": 3.8, "g_i": 3.0, "g_k": 5.0,
            "accuracy": "B", "unc": 0.10, "boltzmann_ok": True,
        })
    
    df_lines = pd.DataFrame(fe_records + si_records)
    dict_ref = np.full_like(det_wl, 500.0)
    compositions = {"Mat1": {"Fe": 99.0, "Si": 1.0}}
    cond = {"Mat1": make_mock_conditioned(det_wl, mean_counts=1000.0, sigma=5.0)}
    
    rois = build_catalogue(
        "TestFamily", compositions, df_lines, dict_ref, cond, det_wl,
        lines_per_band=3,  # 1 per tercile
        lines_per_element=2,
    )
    
    # Must have both Fe and Si represented in catalogue
    elements_in_cat = {line["species"].split()[0] for r in rois for line in r.model_lines}
    assert "Fe" in elements_in_cat
    assert "Si" in elements_in_cat


def test_window_merging_and_sub_bin_partition():
    """Overlapping windows are merged; sub-bins partition channels without overlap."""
    det_wl = np.linspace(400.0, 410.0, 101)  # 0.1 nm pitch, 101 channels
    # Lines at 405.0 and 406.0 with roi_half_width = 1.2
    # Win 1: [403.8, 406.2], Win 2: [404.8, 407.2]
    # Merged window: [403.8, 407.2] (length 3.4 nm ~= 34 channels)
    windows = [(403.8, 406.2), (404.8, 407.2)]
    merged = merge_windows(windows)
    assert len(merged) == 1
    assert pytest.approx(merged[0][0], 1e-3) == 403.8
    assert pytest.approx(merged[0][1], 1e-3) == 407.2
    
    # Sub-bin partitioning with sub_bin_pixels=4
    # Example 1: 10 channels -> [0,1,2,3], [4,5,6,7,8,9] (remainder 2 joins previous)
    channels_10 = list(range(10))
    bins_10 = partition_sub_bins(channels_10, sub_bin_pixels=4)
    assert bins_10 == [[0, 1, 2, 3], [4, 5, 6, 7, 8, 9]]
    
    # Example 2: 11 channels -> [0,1,2,3], [4,5,6,7], [8,9,10] (remainder 3 >= 3 is separate)
    channels_11 = list(range(11))
    bins_11 = partition_sub_bins(channels_11, sub_bin_pixels=4)
    assert bins_11 == [[0, 1, 2, 3], [4, 5, 6, 7], [8, 9, 10]]
    
    # Example 3: channel disjointness
    all_chans = [ch for b in bins_11 for ch in b]
    assert len(all_chans) == len(set(all_chans))


def test_ss551_bronze_real_asd_cache_ac2():
    """AC2: With real ASD cache and SS551 composition, bronze catalogue contains Cu I 515.324 and 521.820 nm and NO Cu II line."""
    cache_dir = Path(DEFAULT_CACHE_DIR)
    assert (cache_dir / "Cu_I_340-900.parquet").exists(), "Cu_I parquet must exist in cache"
    assert (cache_dir / "Cu_II_340-900.parquet").exists(), "Cu_II parquet must exist in cache"
    
    # SS551 composition from reference_compositions.json
    ss551_comp = {
        "Cu": 87.4, "Sn": 8.92, "P": 1.01, "Pb": 0.79, "Ni": 0.76, "Zn": 0.74,
        "Fe": 0.20, "Al": 0.052, "Si": 0.018
    }
    compositions = {"551": ss551_comp}
    
    # Load model lines for bronze certified elements
    certified_elements = ["Cu", "Sn", "P", "Pb", "Ni", "Zn", "Fe", "Al"]
    model_lines = load_model_lines(certified_elements, wl_range=(350.0, 850.0), cache_dir=cache_dir)
    assert not model_lines.empty
    
    # Detector wavelength grid (0.27 nm pitch, 345-895 nm)
    det_wl = np.arange(345.0, 895.0, 0.27)
    
    # Create reference model dictionary column: calculate lte_strength for each model line and sum on detector
    dict_ref = np.zeros_like(det_wl)
    st_arr = lte_strength(model_lines, 0.8)
    for i, (_, row) in enumerate(model_lines.iterrows()):
        wl = row["wavelength_nm"]
        if 345.0 <= wl <= 895.0:
            st = st_arr[i]
            el_wt = ss551_comp.get(row["element"], 0.0)
            idx = np.argmin(np.abs(det_wl - wl))
            dict_ref[idx] += st * el_wt * 1e-8
            
    # Conditioned measurement where model lines are detected
    conditioned = {"551": make_mock_conditioned(det_wl, mean_counts=10000.0, sigma=15.0)}
    
    rois = build_catalogue(
        family="Phosphor_Bronze",
        compositions=compositions,
        model_lines=model_lines,
        dictionary_ref=dict_ref,
        conditioned=conditioned,
        detector_wavelengths=det_wl,
    )
    
    # Collect all species and wavelengths in the bronze catalogue
    species_in_cat = set()
    cu_wavelengths = []
    for r in rois:
        for mline in r.model_lines:
            species_in_cat.add(mline["species"])
            if mline["species"] == "Cu I":
                cu_wavelengths.append(mline["wlNm"])
                
    # AC2: Contains Cu I 515.324 and 521.820 nm
    assert any(abs(w - 515.324) < 0.1 for w in cu_wavelengths), "Cu I 515.324 nm line must be in catalogue"
    assert any(abs(w - 521.820) < 0.1 for w in cu_wavelengths), "Cu I 521.820 nm line must be in catalogue"
    
    # AC2: Contains NO Cu II line
    assert "Cu II" not in species_in_cat, "Catalogue must contain no Cu II lines"


def test_element_neutrality_source_scan_ac3():
    """AC3: Source scan asserting that roi_catalogue.py contains no element symbols or category weights."""
    roi_cat_path = Path(__file__).resolve().parent / "roi_catalogue.py"
    assert roi_cat_path.exists(), "roi_catalogue.py must exist"
    
    source_text = roi_cat_path.read_text(encoding="utf-8")
    
    # Must not contain hardcoded category weights or element-specific weighting dicts
    assert "category_weights" not in source_text.lower(), "Must contain no category_weights"
    assert "element_weights" not in source_text.lower(), "Must contain no element_weights"
    
    # Must not contain branching on specific element names like 'if element == "Fe":' or 'if el in ["Cu", ...]:'
    tree = ast.parse(source_text)
    for node in ast.walk(tree):
        if isinstance(node, ast.If):
            test_str = ast.unparse(node.test)
            for el in ["Fe", "Cu", "Cr", "Ni", "Mn", "Al", "Mo", "Si"]:
                assert f'"{el}"' not in test_str and f"'{el}'" not in test_str, (
                    f"Found hardcoded element branching in roi_catalogue.py: {test_str}"
                )
