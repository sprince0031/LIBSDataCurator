import sys
from pathlib import Path
import pytest

from scripts.nist_lines import (
    DEFAULT_CACHE_DIR,
    parse_elements_from_composition,
    build_cache,
    species_list,
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

