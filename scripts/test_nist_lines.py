import sys
from pathlib import Path
import pytest

from scripts.nist_lines import (
    DEFAULT_CACHE_DIR,
    parse_elements_from_composition,
    build_cache,
    species_list,
    parse_composition_dict,
    to_atomic_fractions,
    ACCURACY_UNCERTAINTY,
    load_model_lines,
    lte_strength,
)


def test_default_cache_dir():
    expected = Path(__file__).resolve().parent.parent / "data" / "nist_lines"
    assert DEFAULT_CACHE_DIR.resolve() == expected.resolve()


def test_parse_elements_from_composition_colon():
    comp = "C:0.279,Cr:11.93,Ni:0.246,Fe:86.491"
    elements = parse_elements_from_composition(comp)
    assert elements == ["C", "Cr", "Ni", "Fe"]


def test_parse_elements_from_composition_dash_and_ranges():
    comp = "C-0.279,Si-6.5:7.5,Fe-#"
    elements = parse_elements_from_composition(comp)
    assert elements == ["C", "Si", "Fe"]


def test_parse_elements_from_composition_semicolon_and_whitespace():
    comp = "  Fe:80.0 ;  C:10.0 ; Fe:10.0 ; "
    elements = parse_elements_from_composition(comp)
    assert elements == ["Fe", "C"]


def test_species_list_with_elements():
    species = species_list(set_names=[], elements=["Fe", "Cr"], include_common=False)
    assert "Fe I" in species
    assert "Fe II" in species
    assert "Cr I" in species
    assert "Cr II" in species
    assert "H I" not in species


def test_cli_argument_parsing(monkeypatch, tmp_path):
    from unittest.mock import patch
    import scripts.nist_lines as nist_mod

    captured_args = {}

    def mock_build_cache(sets, wl_range, cache_dir=None, force=False, progress=None, elements=None):
        captured_args["sets"] = sets
        captured_args["wl_range"] = wl_range
        captured_args["cache_dir"] = cache_dir
        captured_args["elements"] = elements
        import pandas as pd
        return pd.DataFrame({"species": ["Fe I"], "boltzmann_ok": [True]})

    with patch.object(nist_mod, "build_cache", side_effect=mock_build_cache):
        monkeypatch.setattr(
            sys,
            "argv",
            [
                "nist_lines.py",
                "-c", "C-0.28,Fe-#",
                "--elements", "Ni", "Cr",
                "--range", "350", "850",
                "--cache-dir", str(tmp_path)
            ],
        )
        exit_code = nist_mod.main()
        assert exit_code == 0
        assert "C" in captured_args["elements"]
        assert "Fe" in captured_args["elements"]
        assert "Ni" in captured_args["elements"]
        assert "Cr" in captured_args["elements"]
        assert captured_args["wl_range"] == (350.0, 850.0)


# =============================================================================
# Phase 1: Line Data and Flux-Conserving Resampling (R1) Unit Tests
# =============================================================================

def test_parse_composition_dict_string_delimiters():
    # Test hyphen delimiter
    c1 = parse_composition_dict("C-0.279,Cr-11.93,Ni-0.246,Fe-86.491")
    assert pytest.approx(sum(c1.values()), 1e-5) == 100.0
    assert "Fe" in c1 and "Cr" in c1 and "C" in c1 and "Ni" in c1

    # Test colon delimiter
    c2 = parse_composition_dict("C:0.28,Cr:11.9,Fe:87.82")
    assert pytest.approx(sum(c2.values()), 1e-5) == 100.0
    assert pytest.approx(c2["Cr"], 1e-4) == 11.9


def test_parse_composition_dict_ranges_and_remainder():
    # Test range midpoint and '#' remainder
    c = parse_composition_dict("C-0.28,Si-6.5:7.5,Fe-#")
    assert pytest.approx(c["Si"], 1e-4) == 7.0
    assert pytest.approx(c["C"], 1e-4) == 0.28
    assert pytest.approx(c["Fe"], 1e-4) == 92.72
    assert pytest.approx(sum(c.values()), 1e-5) == 100.0


def test_parse_composition_dict_dict_input_and_normalization():
    # Dict input that doesn't sum to 100 wt% should be normalized
    c = parse_composition_dict({"Fe": 40.0, "Cr": 10.0})
    assert pytest.approx(sum(c.values()), 1e-5) == 100.0
    assert pytest.approx(c["Fe"], 1e-4) == 80.0
    assert pytest.approx(c["Cr"], 1e-4) == 20.0


def test_to_atomic_fractions():
    # Fe (approx 55.845 g/mol) and C (approx 12.011 g/mol)
    comp_wt = {"Fe": 80.0, "C": 20.0}
    at_fracs = to_atomic_fractions(comp_wt)
    assert pytest.approx(sum(at_fracs.values()), 1e-6) == 1.0
    # Moles of Fe = 80 / 55.845 ~= 1.4325
    # Moles of C = 20 / 12.011 ~= 1.6651
    # Total moles ~= 3.0976
    # Expected C fraction ~= 1.6651 / 3.0976 ~= 0.5375
    assert pytest.approx(at_fracs["C"], 1e-3) == 0.5375
    assert pytest.approx(at_fracs["Fe"], 1e-3) == 0.4625


def test_accuracy_uncertainty_mapping():
    assert ACCURACY_UNCERTAINTY["AAA"] == 0.003
    assert ACCURACY_UNCERTAINTY["AA"] == 0.01
    assert ACCURACY_UNCERTAINTY["A+"] == 0.02
    assert ACCURACY_UNCERTAINTY["A"] == 0.03
    assert ACCURACY_UNCERTAINTY["B+"] == 0.07
    assert ACCURACY_UNCERTAINTY["B"] == 0.10
    assert ACCURACY_UNCERTAINTY["C+"] == 0.18
    assert ACCURACY_UNCERTAINTY["C"] == 0.25
    assert ACCURACY_UNCERTAINTY["D+"] == 0.40
    assert ACCURACY_UNCERTAINTY["D"] == 0.50
    assert ACCURACY_UNCERTAINTY["E"] == 1.00


def test_load_model_lines_and_unc_mapping(tmp_path):
    import pandas as pd
    cache_dir = tmp_path / "nist_lines"
    cache_dir.mkdir(parents=True)
    
    # Create mock parquet for Fe I with mixed boltzmann_ok and accuracy values
    df_fe = pd.DataFrame({
        "species": ["Fe I", "Fe I", "Fe I"],
        "element": ["Fe", "Fe", "Fe"],
        "stage": ["I", "I", "I"],
        "wavelength_nm": [400.0, 410.0, 420.0],
        "obs_wl_nm": [400.0, 410.0, 420.0],
        "ritz_wl_nm": [400.0, 410.0, 420.0],
        "rel_intensity_raw": ["100", "50", "20"],
        "rel_intensity": [100.0, 50.0, 20.0],
        "Aki_raw": ["1e7", "2e7", None],
        "Aki": [1e7, 2e7, float("nan")],
        "accuracy": ["B+", "C'", "D"],
        "Ei_raw": ["0.0", "1.0", "2.0"],
        "Ei_eV": [0.0, 1.0, 2.0],
        "Ek_raw": ["3.1", "4.0", "5.0"],
        "Ek_eV": [3.1, 4.0, 5.0],
        "g_i": [5.0, 5.0, 5.0],
        "g_k": [7.0, 3.0, 1.0],
        "term_k": ["", "", ""],
        "conf_k": ["", "", ""],
        "boltzmann_ok": [True, True, False],
    })
    df_fe.to_parquet(cache_dir / "Fe_I_340-900.parquet", index=False)
    
    # Load model lines
    lines = load_model_lines(["Fe"], wl_range=(350.0, 850.0), cache_dir=cache_dir, include_common=False)
    
    # Line 3 has boltzmann_ok=False so it must be excluded
    assert len(lines) == 2
    assert all(lines["boltzmann_ok"])
    # Verify added columns
    assert "wl_nm" in lines.columns
    assert "gA" in lines.columns
    assert "unc" in lines.columns
    
    # Check values
    assert pytest.approx(lines.loc[0, "wl_nm"]) == 400.0
    assert pytest.approx(lines.loc[0, "gA"]) == 7.0 * 1e7
    assert pytest.approx(lines.loc[0, "unc"]) == 0.07  # "B+"
    
    # "C'" has a trailing prime which should be stripped to "C" -> 0.25
    assert pytest.approx(lines.loc[1, "unc"]) == 0.25


def test_lte_strength():
    import numpy as np
    import pandas as pd
    
    df = pd.DataFrame({
        "wl_nm": [400.0, 500.0],
        "gA": [1.0e8, 2.0e8],
        "Ek_eV": [3.0, 4.0],
    })
    t_ev = 0.8
    strengths = lte_strength(df, t_ev=t_ev)
    
    expected_0 = (1.0e8 / 400.0) * np.exp(-3.0 / 0.8)
    expected_1 = (2.0e8 / 500.0) * np.exp(-4.0 / 0.8)
    
    assert isinstance(strengths, np.ndarray)
    assert pytest.approx(strengths[0], rel=1e-6) == expected_0
    assert pytest.approx(strengths[1], rel=1e-6) == expected_1


