import sys
import unittest
from pathlib import Path
from unittest.mock import patch, MagicMock

# Ensure repo root is on sys.path
REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from scripts.batch_nist_pull import (
    build_composition_string,
    load_reference_materials,
    run_batch_pull_for_material,
    format_duration,
    parse_cli_args,
    DEFAULT_JSON_PATH,
    DEFAULT_RUN_BAT,
)


class TestBatchNistPull(unittest.TestCase):

    def test_build_composition_string_numbers_and_remainder(self):
        comp = {
            "C": 0.279,
            "Si": 0.421,
            "Mn": 0.598,
            "P": 0.015,
            "S": 0.02,
            "Cr": 11.93,
            "Ni": 0.246,
            "Fe": "#"
        }
        result = build_composition_string(comp)
        expected = "C-0.279,Si-0.421,Mn-0.598,P-0.015,S-0.02,Cr-11.93,Ni-0.246,Fe-#"
        self.assertEqual(result, expected)

    def test_build_composition_string_with_ranges(self):
        comp = {
            "Fe": "0:0.55",
            "Si": "6.5:7.5",
            "Mn": "0:0.35",
            "Al": "#"
        }
        result = build_composition_string(comp)
        expected = "Fe-0:0.55,Si-6.5:7.5,Mn-0:0.35,Al-#"
        self.assertEqual(result, expected)

    def test_load_reference_materials_counts_and_keys(self):
        if not Path(DEFAULT_JSON_PATH).exists():
            self.skipTest(f"External reference JSON not present: {DEFAULT_JSON_PATH}")
        materials = load_reference_materials(DEFAULT_JSON_PATH)
        self.assertEqual(len(materials), 15)

        names = [m["materialName"] for m in materials]
        self.assertIn("469", names)
        self.assertIn("551", names)
        self.assertIn("SS405", names)
        self.assertIn("SS465-1", names)
        self.assertIn("SS467", names)
        self.assertIn("Al_ENAS-42000-1", names)

        for mat in materials:
            self.assertIn("materialName", mat)
            self.assertIn("materialType", mat)
            self.assertIn("composition", mat)
            self.assertIn("compositionString", mat)
            self.assertGreater(len(mat["compositionString"]), 0)

    def test_format_duration(self):
        self.assertEqual(format_duration(0.5), "0.500s")
        self.assertEqual(format_duration(65.2), "1m 05.2s")

    def test_run_batch_pull_dry_run(self):
        sample_mat = {
            "materialName": "469",
            "materialType": "Ferritic_Stainless_Steel",
            "compositionString": "C-0.279,Fe-#",
        }
        report = run_batch_pull_for_material(
            material=sample_mat,
            run_bat=DEFAULT_RUN_BAT,
            extra_args=["-min", "200", "-max", "900"],
            dry_run=True,
        )
        self.assertEqual(report["status"], "DRY_RUN")
        self.assertEqual(report["exitCode"], 0)
        self.assertIn("-c C-0.279,Fe-#", report["command"])
        self.assertIn("-mt Ferritic_Stainless_Steel", report["command"])
        self.assertIn("-mg 469", report["command"])
        self.assertIn("-min 200", report["command"])
        self.assertIn("-max 900", report["command"])

    @patch("scripts.batch_nist_pull.subprocess.run")
    def test_run_batch_pull_mock_execution(self, mock_subprocess_run):
        mock_proc = MagicMock()
        mock_proc.returncode = 0
        mock_subprocess_run.return_value = mock_proc

        sample_mat = {
            "materialName": "469",
            "materialType": "Ferritic_Stainless_Steel",
            "compositionString": "C-0.279,Fe-#",
        }
        report = run_batch_pull_for_material(
            material=sample_mat,
            run_bat=DEFAULT_RUN_BAT,
            extra_args=["-res", "1000"],
            dry_run=False,
        )

        self.assertEqual(report["status"], "SUCCESS")
        self.assertEqual(report["exitCode"], 0)
        self.assertTrue(mock_subprocess_run.called)

    def test_cli_parsing_material_and_exclude(self):
        with patch.object(sys, "argv", ["batch_nist_pull.py", "--material", "SS405", "--exclude", "469", "--dry-run"]):
            args = parse_cli_args()
            self.assertEqual(args.only, ["SS405"])
            self.assertEqual(args.exclude, ["469"])
            self.assertTrue(args.dry_run)


if __name__ == "__main__":
    unittest.main()
