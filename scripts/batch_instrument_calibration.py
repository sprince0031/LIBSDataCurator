#!/usr/bin/env python3
"""
batch_instrument_calibration.py
-------------------------
Automates LIBS instrument profile calibration for reference materials.

Reads reference material compositions from `data/reference/reference_compositions.json`,
builds the composition string (-c flag) for each material, and runs
`build/release-package/bin/calibrate.bat` on each material's dataset in
`data/LSA/Medals_Samples_Feb26`.

Features:
- Configurable `calibrate_args` list to customize arguments passed to `calibrate.bat`.
- Configurable `excluded_materials` list to skip specific materials by materialName.
- Automatic composition string construction (format: 'Elem1-Val1,Elem2-Val2,...').
- Real-time progression print statements to track execution status and timing.
- Optional CLI arguments including --dry-run, --only, and --stop-on-error.
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional


# ==============================================================================
# CONFIGURATION
# ==============================================================================

# List of materialName values from reference_compositions.json that should be skipped.
# Example: excluded_materials = ["469", "551"]
excluded_materials: List[str] = [
    # Add materialName values to skip here, e.g.:
    "473",
    "556"
]

# List of additional arguments to be passed to calibrate.bat.
# You can easily edit, uncomment, add, or remove flags here.
# Commonly used flags:
#   "-in", "<name>"        : Instrument name (e.g. "LSA")
#   "-dl", "<delim>"       : CSV delimiter (default: ";")
#   "-z", "<zones>"        : Number of plasma zones (default: 2)
#   "-res", "<power>"      : NIST resolving power (default: 3000.0)
#   "-hc", "<count>"       : Number of holdout CSV files (default: 1)
#   "-bl", "<lambda>"      : Baseline lambda (default: 10000)
#   "-bp", "<p-value>"     : Baseline p-value (default: 0.001)
#   "-bi", "<max-iter>"    : Baseline max iterations (default: 10)
#   "-db"                  : Disable baseline correction
#   "-d"                   : Enable debug mode (shows selenium browser)
#   "-o", "<path>"         : Custom output path for instrument profile JSON
calibrate_args: List[str] = [
    "-in", "LSA",
    "-z", "2"
    # "-d"
]

# Set to True to preview commands without executing calibrate.bat.
# Can also be triggered via the --dry-run CLI argument.
DRY_RUN: bool = False

# Whether to stop the entire run immediately if any material fails.
STOP_ON_ERROR: bool = False


# ==============================================================================
# DEFAULT PATH RESOLUTION
# ==============================================================================

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent

DEFAULT_JSON_PATH = REPO_ROOT / "data" / "reference" / "reference_compositions.json"
DEFAULT_DATA_DIR = REPO_ROOT / "data" / "LSA" / "Medals_Samples_Feb26"
DEFAULT_CALIBRATE_BAT = REPO_ROOT / "build" / "release-package" / "bin" / "calibrate.bat"


# ==============================================================================
# COMPOSITION STRING BUILDER
# ==============================================================================

def build_composition_string(composition: Dict[str, Any]) -> str:
    """
    Builds a composition string suitable for calibrate.bat's -c flag.

    Iterates through each element in the composition dictionary and joins them
    as 'Element-Value' pairs separated by commas.

    Examples:
        {"C": 0.279, "Si": 0.421, "Fe": "#"}
        -> "C-0.279,Si-0.421,Fe-#"

        {"Fe": "0:0.55", "Si": "6.5:7.5", "Al": "#"}
        -> "Fe-0:0.55,Si-6.5:7.5,Al-#"
    """
    elements = []
    for element, val in composition.items():
        element_clean = str(element).strip()
        val_clean = str(val).strip()
        elements.append(f"{element_clean}-{val_clean}")
    return ",".join(elements)


# ==============================================================================
# JSON LOADER
# ==============================================================================

def load_reference_materials(json_path: Path) -> List[Dict[str, Any]]:
    """
    Loads all reference materials and their compositions from the specified JSON file.

    Returns a flat list of dictionaries, each containing:
        - materialName (str)
        - materialType (str)
        - composition (dict)
        - compositionString (str)
        - altNames (str)
    """
    if not json_path.exists():
        raise FileNotFoundError(f"Reference compositions JSON file not found: {json_path}")

    with open(json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    materials: List[Dict[str, Any]] = []
    for category in data.get("materialCompositions", []):
        material_type = category.get("materialType", "Unknown")
        for mat in category.get("materials", []):
            name = mat.get("materialName")
            comp = mat.get("composition", {})
            comp_str = build_composition_string(comp)
            materials.append({
                "materialName": str(name),
                "materialType": str(material_type),
                "composition": comp,
                "compositionString": comp_str,
                "altNames": mat.get("altNames", ""),
            })

    return materials


# ==============================================================================
# COMMAND RUNNER
# ==============================================================================

def format_duration(seconds: float) -> str:
    """Formats duration in seconds to a human-readable string."""
    mins, secs = divmod(int(seconds), 60)
    millis = int((seconds - int(seconds)) * 1000)
    if mins > 0:
        return f"{mins}m {secs:02d}.{millis//100:01d}s"
    return f"{secs}.{millis:03d}s"


def run_calibration_for_material(
    material: Dict[str, Any],
    calibrate_bat: Path,
    data_dir: Path,
    extra_args: List[str],
    dry_run: bool = False,
) -> Dict[str, Any]:
    """
    Constructs and executes calibrate.bat for a single material.

    Returns an execution report dictionary with status, exit code, and duration.
    """
    mat_name = material["materialName"]
    mat_type = material["materialType"]
    comp_str = material["compositionString"]

    # Locate input path for this material (directory or file)
    input_path = data_dir / mat_name
    if not input_path.exists():
        print(f"  [!] Warning: Input directory does not exist: {input_path}")

    # Build calibrate.bat command
    cmd: List[str] = [
        str(calibrate_bat.resolve()),
        "-i", str(input_path.resolve()),
        "-c", comp_str,
    ]

    # Include material-type and material-grade if not overridden in extra_args
    if "-mt" not in extra_args and "--material-type" not in extra_args:
        cmd.extend(["-mt", mat_type])
    if "-mg" not in extra_args and "--material-grade" not in extra_args:
        cmd.extend(["-mg", mat_name])

    # Append user-configurable arguments
    cmd.extend(extra_args)

    cmd_line_str = subprocess.list2cmdline(cmd)
    print(f"  Input Path:  {input_path}")
    print(f"  Composition: {comp_str}")
    print(f"  Command:     {cmd_line_str}")

    if dry_run:
        print("  Status:      DRY RUN (command not executed)")
        return {
            "materialName": mat_name,
            "status": "DRY_RUN",
            "exitCode": 0,
            "duration": 0.0,
            "command": cmd_line_str,
        }

    print("  Status:      Running calibration...")
    print("  " + "-" * 68)
    start_time = time.time()

    try:
        # Stream output directly to terminal so user can monitor progress
        proc = subprocess.run(cmd, shell=(os.name == "nt"), check=False)
        duration = time.time() - start_time
        print("  " + "-" * 68)

        if proc.returncode == 0:
            status = "SUCCESS"
            print(f"  Result:      COMPLETED successfully in {format_duration(duration)}")
        else:
            status = "FAILED"
            print(f"  Result:      FAILED with exit code {proc.returncode} after {format_duration(duration)}")

        return {
            "materialName": mat_name,
            "status": status,
            "exitCode": proc.returncode,
            "duration": duration,
            "command": cmd_line_str,
        }

    except Exception as e:
        duration = time.time() - start_time
        print("  " + "-" * 68)
        print(f"  Result:      EXCEPTION occurred: {e}")
        return {
            "materialName": mat_name,
            "status": "ERROR",
            "exitCode": -1,
            "duration": duration,
            "error": str(e),
            "command": cmd_line_str,
        }


# ==============================================================================
# MAIN WORKFLOW
# ==============================================================================

def parse_cli_args() -> argparse.Namespace:
    """Parses command-line arguments, with fallback to in-script defaults."""
    parser = argparse.ArgumentParser(
        description="Run LIBS instrument calibration for reference materials."
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        default=DRY_RUN,
        help="Preview commands and composition strings without running calibrate.bat."
    )
    parser.add_argument(
        "--stop-on-error",
        action="store_true",
        default=STOP_ON_ERROR,
        help="Halt processing immediately if any material calibration fails."
    )
    parser.add_argument(
        "--json-file",
        type=Path,
        default=DEFAULT_JSON_PATH,
        help=f"Path to reference compositions JSON file (default: {DEFAULT_JSON_PATH})."
    )
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=DEFAULT_DATA_DIR,
        help=f"Base directory containing measurement folders (default: {DEFAULT_DATA_DIR})."
    )
    parser.add_argument(
        "--calibrate-bat",
        type=Path,
        default=DEFAULT_CALIBRATE_BAT,
        help=f"Path to calibrate.bat script (default: {DEFAULT_CALIBRATE_BAT})."
    )
    parser.add_argument(
        "--exclude",
        nargs="*",
        default=None,
        help="Material names to exclude (overrides / extends in-script excluded_materials)."
    )
    parser.add_argument(
        "--only",
        nargs="*",
        default=None,
        help="Only process these specific material names (skips all others)."
    )
    return parser.parse_args()


def main() -> int:
    cli_args = parse_cli_args()

    json_path = cli_args.json_file
    data_dir = cli_args.data_dir
    calibrate_bat = cli_args.calibrate_bat
    dry_run = cli_args.dry_run
    stop_on_error = cli_args.stop_on_error

    # Merge excluded materials: in-script excluded_materials + CLI --exclude
    active_excluded = set(excluded_materials)
    if cli_args.exclude:
        active_excluded.update(cli_args.exclude)

    print("=" * 80)
    print("LIBS Instrument Profile Calibration Automation")
    print("=" * 80)
    print(f"Reference JSON:  {json_path}")
    print(f"Data Directory:  {data_dir}")
    print(f"Calibrate Bat:   {calibrate_bat}")
    print(f"Dry Run Mode:    {'ENABLED' if dry_run else 'DISABLED'}")
    print(f"Configured Args: {calibrate_args}")
    print(f"Excluded List:   {sorted(list(active_excluded)) if active_excluded else 'None'}")
    print("=" * 80)

    # Validate calibrate.bat existence
    if not calibrate_bat.exists() and not dry_run:
        print(f"Error: calibrate.bat not found at: {calibrate_bat}")
        print("Please check the path or build the release package first.")
        return 1

    # Load materials from JSON
    try:
        materials = load_reference_materials(json_path)
    except Exception as e:
        print(f"Error reading reference compositions JSON: {e}")
        return 1

    # Filter by --only if specified
    if cli_args.only:
        only_set = set(cli_args.only)
        materials = [m for m in materials if m["materialName"] in only_set]
        print(f"Filtered by --only: {len(materials)} material(s) selected: {sorted(list(only_set))}")

    total_materials = len(materials)
    print(f"\nLoaded {total_materials} reference material(s) to process from JSON.")

    # Track progress and results
    results: List[Dict[str, Any]] = []
    skipped_count = 0
    success_count = 0
    failed_count = 0
    overall_start_time = time.time()

    for idx, mat in enumerate(materials, start=1):
        mat_name = mat["materialName"]
        mat_type = mat["materialType"]

        print(f"\n[{idx}/{total_materials}] Material: '{mat_name}' (Family: '{mat_type}')")

        # Check if material is in excluded list
        if mat_name in active_excluded:
            print(f"  Status:      SKIPPED (materialName found in excluded_materials)")
            skipped_count += 1
            results.append({
                "materialName": mat_name,
                "status": "SKIPPED",
                "exitCode": 0,
                "duration": 0.0,
            })
            continue

        # Execute calibration
        report = run_calibration_for_material(
            material=mat,
            calibrate_bat=calibrate_bat,
            data_dir=data_dir,
            extra_args=calibrate_args,
            dry_run=dry_run,
        )
        results.append(report)

        if report["status"] in ("SUCCESS", "DRY_RUN"):
            success_count += 1
        else:
            failed_count += 1
            if stop_on_error:
                print(f"\n[!] Halting further execution due to --stop-on-error flag.")
                break

    # Summary Report
    total_duration = time.time() - overall_start_time
    print("\n" + "=" * 80)
    print("CALIBRATION EXECUTION SUMMARY")
    print("=" * 80)
    print(f"Total materials:     {total_materials}")
    print(f"Processed / Succeeded: {success_count}")
    print(f"Skipped (excluded):  {skipped_count}")
    print(f"Failed / Errors:     {failed_count}")
    print(f"Total elapsed time:  {format_duration(total_duration)}")
    print("-" * 80)

    # Detailed status list
    for res in results:
        status_symbol = {
            "SUCCESS": "[OK]  ",
            "DRY_RUN": "[DRY] ",
            "SKIPPED": "[SKIP]",
            "FAILED":  "[FAIL]",
            "ERROR":   "[ERR] ",
        }.get(res["status"], "[?]   ")
        dur_str = f"({format_duration(res['duration'])})" if res["duration"] > 0 else ""
        print(f"  {status_symbol} {res['materialName']:<20} Status: {res['status']:<8} {dur_str}")

    print("=" * 80)

    return 1 if failed_count > 0 else 0


if __name__ == "__main__":
    sys.exit(main())
