"""NIST Atomic Spectra Database (ASD) line-list acquisition, caching and lookup.

Provides the reference line data needed for species identification and
Boltzmann-plot analysis of LIBS spectra.

Why the raw ASD endpoint rather than ``astroquery.nist``
-------------------------------------------------------
The Boltzmann relation needs the upper-level statistical weight ``g_k``:

    ln( I·λ / (g_k · A_ki) )  =  −E_k / kT  +  const

``astroquery.nist`` does not surface ``g_k`` as a clean column — it must be
scraped back out of the level-designation strings.  The ASD CGI accepts
``g_out=on`` and returns ``g_i``/``g_k`` as their own fields, so querying it
directly is both more reliable and avoids an astropy dependency.

Air vs vacuum
-------------
This module requests ``show_av=2`` (air wavelengths) and asserts that the
returned header names the columns ``*_air``.  This matters more than it looks:
the air/vacuum difference is ~0.14 nm at 500 nm, which is over half a pixel on
a 0.27 nm/channel spectrometer.  A silent vacuum/air mix-up would shift every
identification by roughly one channel.  Wavelengths returned by this module are
always **in air**, matching a spectrometer calibrated against ambient-air lines.

Usage
-----
Build the cache once (network required)::

    python utils/nist_lines.py --sets steel bronze aluminium --range 340 900

then look lines up offline::

    from utils.nist_lines import load_lines
    lines = load_lines(["steel"], wl_range=(400, 460))
"""

from __future__ import annotations

import argparse
import io
import re
import sys
import time
from pathlib import Path

import pandas as pd
import requests

ASD_URL = "https://physics.nist.gov/cgi-bin/ASD/lines1.pl"

#: Default cache location (repo-root/data/nist_lines).
DEFAULT_CACHE_DIR = Path(__file__).resolve().parent.parent / "data" / "nist_lines"

#: Candidate elements per matrix.  Restricting the search space is essential —
#: matching against every element produces a candidate for any peak and makes
#: identification meaningless.
ELEMENT_SETS: dict[str, list[str]] = {
    # Ferritic/stainless steels: matrix plus the certified alloying elements
    # and the usual minor constituents.
    "steel": ["Fe", "Cr", "Ni", "Mn", "Mo", "Si", "V", "Ti", "Co", "Cu", "Nb", "W", "C"],
    # Phosphor bronzes: Cu matrix plus certified alloying elements.
    "bronze": ["Cu", "Sn", "Pb", "Zn", "P", "Ni", "Fe", "Sb", "As", "Si", "Al"],
    # Aluminium casting alloys (e.g. EN AC-42000 / AlSi7Mg).
    "aluminium": ["Al", "Si", "Mg", "Cu", "Mn", "Zn", "Ti", "Fe"],
    # Elements currently included in the MEDALS CF-LIBS atomic-line analysis.
    "cf_libs": [
        "Al",
        "C",
        "Cr",
        "Cu",
        "Fe",
        "Mn",
        "Mo",
        "Ni",
        "P",
        "Pb",
        "S",
        "Si",
        "Sn",
        "Zn",
    ],
    # Always worth including: ambient air entrainment (N, O), surface
    # contamination (Ca, Na, K, Mg), and hydrogen for the Stark n_e diagnostic.
    "common": ["H", "C", "N", "O", "Na", "K", "Ca", "Mg", "Li"],
}

#: Ionisation stages to query.  Neutral and singly-ionised covers essentially
#: everything emitting in a LIBS plasma at 5,000-15,000 K.
DEFAULT_STAGES = ("I", "II")

_NUM_RE = re.compile(
    r"[-+]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][-+]?\d+)?"
)


def species_list(
    set_names: list[str] | str = (),
    stages: tuple[str, ...] = DEFAULT_STAGES,
    include_common: bool = True,
    elements: list[str] | None = None,
) -> list[str]:
    """Expand element-set names and/or direct element lists into ASD species strings like ``"Fe I"``."""
    if isinstance(set_names, str):
        set_names = [set_names] if set_names else []
    all_elements: list[str] = []
    for name in set_names:
        if name not in ELEMENT_SETS:
            raise KeyError(f"Unknown element set {name!r}. "
                           f"Available: {sorted(ELEMENT_SETS)}")
        all_elements.extend(ELEMENT_SETS[name])
    if elements:
        all_elements.extend(elements)
    if include_common:
        all_elements.extend(ELEMENT_SETS["common"])
    # Preserve order, drop duplicates
    seen, ordered = set(), []
    for el in all_elements:
        if el not in seen:
            seen.add(el)
            ordered.append(el)
    return [f"{el} {stage}" for el in ordered for stage in stages]


def _first_number(value) -> float:
    """Extract the leading numeric token from a messy ASD field.

    ASD intensity fields carry qualifier suffixes and brackets — ``"500 h"``
    (hazy), ``"(150)"`` (uncertain), ``"2000 *"``. Only the magnitude is useful
    for ranking candidates, so pull the first number and discard the rest.
    """
    if value is None:
        return float("nan")
    text = str(value).strip().strip('"')
    if not text or text in {"-", "—"}:
        return float("nan")
    match = _NUM_RE.search(text)
    return float(match.group()) if match else float("nan")


def _clean_asd_text(value) -> str:
    """Return an ASD field as clean text while preserving annotations."""
    if value is None or pd.isna(value):
        return ""
    text = str(value).strip().strip('"')
    if text.lower() == "nan":
        return ""
    return text


def _parse_asd_tsv(text: str, species: str) -> pd.DataFrame:
    """Parse the tab-delimited ASD response into a tidy frame."""
    stripped = text.strip()
    if not stripped:
        return pd.DataFrame()
    # An HTML body means an error page or an empty result, not line data.
    if stripped.lstrip().lower().startswith(("<!doctype", "<html")):
        return pd.DataFrame()
    if "obs_wl" not in stripped.split("\n", 1)[0]:
        return pd.DataFrame()

    raw = pd.read_csv(io.StringIO(stripped), sep="\t", dtype=str,
                      quotechar='"', engine="python", on_bad_lines="skip")
    # The response header ends with a tab, producing a trailing unnamed column.
    raw = raw.loc[:, [c for c in raw.columns if c and not c.startswith("Unnamed")]]
    if raw.empty:
        return pd.DataFrame()

    def col(*candidates: str):
        for c in candidates:
            if c in raw.columns:
                return raw[c]
        return pd.Series([None] * len(raw), index=raw.index)

    # Guard the air/vacuum convention: the request asked for air, so the header
    # must say air. Anything else means the CGI parameters drifted.
    wl_cols = [c for c in raw.columns if c.startswith(("obs_wl", "ritz_wl"))]
    if wl_cols and not all("_air" in c for c in wl_cols):
        raise RuntimeError(
            f"ASD returned non-air wavelengths for {species!r} ({wl_cols}). "
            "Expected air wavelengths (show_av=2)."
        )

    obs  = col("obs_wl_air(nm)").map(_first_number)
    ritz = col("ritz_wl_air(nm)").map(_first_number)
    aki_raw = col("Aki(s^-1)").map(_clean_asd_text)
    ei_raw = col("Ei(eV)").map(_clean_asd_text)
    ek_raw = col("Ek(eV)").map(_clean_asd_text)
    intensity_raw = col("intens").map(_clean_asd_text)

    element, _, stage = species.partition(" ")
    out = pd.DataFrame({
        "species":       species,
        "element":       element,
        "stage":         stage,
        # Prefer the observed wavelength; fall back to Ritz where absent.
        "wavelength_nm": obs.fillna(ritz),
        "obs_wl_nm":     obs,
        "ritz_wl_nm":    ritz,
        "rel_intensity_raw": intensity_raw,
        "rel_intensity":     intensity_raw.map(_first_number),
        "Aki_raw":       aki_raw,
        "Aki":           aki_raw.map(_first_number),
        "accuracy":      col("Acc").astype(str).str.strip().str.strip('"'),
        "Ei_raw":        ei_raw,
        "Ei_eV":         ei_raw.map(_first_number),
        "Ek_raw":        ek_raw,
        "Ek_eV":         ek_raw.map(_first_number),
        "g_i":           col("g_i").map(_first_number),
        "g_k":           col("g_k").map(_first_number),
        "term_k":        col("term_k").astype(str).str.strip().str.strip('"'),
        "conf_k":        col("conf_k").astype(str).str.strip().str.strip('"'),
    })
    out = out[out["wavelength_nm"].notna()].reset_index(drop=True)
    # Boltzmann-usable lines need all three of A_ki, g_k and E_k.
    out["boltzmann_ok"] = (
        out["Aki"].notna() & out["g_k"].notna() & out["Ek_eV"].notna()
        & (out["Aki"] > 0) & (out["g_k"] > 0)
    )
    return out


def fetch_species(
    species: str,
    wl_range: tuple[float, float] = (340.0, 900.0),
    timeout: float = 60.0,
) -> pd.DataFrame:
    """Query NIST ASD for one species. Requires network access."""
    params = {
        "spectra":      species,
        "low_w":        f"{wl_range[0]:g}",
        "upp_w":        f"{wl_range[1]:g}",
        "unit":         "1",     # nm
        "en_unit":      "1",     # eV
        "format":       "3",     # tab-delimited
        "output":       "0",     # all results on one page
        "line_out":     "0",     # all lines
        "show_av":      "2",     # air wavelengths
        "show_obs_wl":  "1",
        "show_calc_wl": "1",
        "A_out":        "0",
        "intens_out":   "on",
        "g_out":        "on",    # statistical weights — required for Boltzmann
        "enrg_out":     "on",
        "conf_out":     "on",
        "term_out":     "on",
        "J_out":        "on",
        "allowed_out":  "1",
        "forbid_out":   "1",
        "submit":       "Retrieve Data",
    }
    resp = requests.get(ASD_URL, params=params, timeout=timeout)
    resp.raise_for_status()
    return _parse_asd_tsv(resp.text, species)


def _cache_path(species: str, wl_range: tuple[float, float], cache_dir: Path) -> Path:
    safe = species.replace(" ", "_")
    return cache_dir / f"{safe}_{wl_range[0]:g}-{wl_range[1]:g}.parquet"


def parse_elements_from_composition(comp_str: str) -> list[str]:
    """Extract element symbols from a LIBSDataCurator composition string.
    
    Supports both '-' and ':' key-value delimiters and range syntax (e.g. 'C-0.279,Si-6.5:7.5,Fe-#' or 'C:0.28,Cr:11.9,Fe:87.8').
    """
    elements: list[str] = []
    for token in re.split(r"[,;]", comp_str.strip()):
        if not token.strip():
            continue
        parts = re.split(r"[-:]", token.strip(), maxsplit=1)
        el = parts[0].strip()
        if re.fullmatch(r"[A-Z][a-z]?", el) and el not in elements:
            elements.append(el)
    return elements


def build_cache(
    set_names: list[str] | str = (),
    wl_range: tuple[float, float] = (340.0, 900.0),
    cache_dir: Path | None = None,
    stages: tuple[str, ...] = DEFAULT_STAGES,
    delay_s: float = 0.4,
    force: bool = False,
    progress=None,
    elements: list[str] | None = None,
    include_common: bool = True,
) -> pd.DataFrame:
    """Fetch and cache every species in the requested sets.

    One parquet file per species, so adding an element later re-fetches only
    that element. ``delay_s`` throttles requests out of politeness to NIST.
    """
    cache_dir = Path(cache_dir or DEFAULT_CACHE_DIR)
    cache_dir.mkdir(parents=True, exist_ok=True)

    targets = species_list(
        set_names, stages=stages, include_common=include_common, elements=elements
    )
    frames: list[pd.DataFrame] = []
    for i, sp in enumerate(targets):
        path = _cache_path(sp, wl_range, cache_dir)
        if path.exists() and not force:
            frames.append(pd.read_parquet(path))
        else:
            try:
                df = fetch_species(sp, wl_range)
            except Exception as exc:  # noqa: BLE001 - report and continue
                print(f"  [warn] {sp}: {exc}", file=sys.stderr)
                df = pd.DataFrame()
            # Cache empty results too: many species genuinely have no lines in
            # range, and re-querying them every run wastes time and NIST's.
            df.to_parquet(path, index=False)
            frames.append(df)
            time.sleep(delay_s)
        if progress is not None:
            progress((i + 1) / len(targets), sp)

    combined = (pd.concat([f for f in frames if not f.empty], ignore_index=True)
                if any(not f.empty for f in frames) else pd.DataFrame())
    return combined


def load_lines(
    set_names: list[str] | str = (),
    wl_range: tuple[float, float] | None = None,
    cache_range: tuple[float, float] = (340.0, 900.0),
    cache_dir: Path | None = None,
    min_intensity: float | None = None,
    boltzmann_only: bool = False,
    stages: tuple[str, ...] = DEFAULT_STAGES,
    include_common: bool = True,
    elements: list[str] | None = None,
) -> pd.DataFrame:
    """Load cached NIST ASD lines for one or more element sets or element lists.

    Parameters
    ----------
    set_names : element set name(s) from ``ELEMENT_SETS`` (e.g. ``["steel"]``).
    elements : optional direct list of element symbols (e.g. ``["Fe", "Cr"]``).
    wl_range : restrict the returned lines to this window (nm, air).
    min_intensity : drop lines below this NIST relative intensity.
    boltzmann_only : keep only lines carrying A_ki, g_k and E_k.

    Raises
    ------
    FileNotFoundError
        If the cache has not been built yet.
    """
    cache_dir = Path(cache_dir or DEFAULT_CACHE_DIR)
    targets = species_list(
        set_names,
        stages=stages,
        include_common=include_common,
        elements=elements,
    )

    frames, missing = [], []
    for sp in targets:
        path = _cache_path(sp, cache_range, cache_dir)
        if not path.exists():
            missing.append(sp)
            continue
        df = pd.read_parquet(path)
        if not df.empty:
            frames.append(df)

    if missing and not frames:
        set_desc = ' '.join(set_names) if isinstance(set_names, list) else str(set_names)
        raise FileNotFoundError(
            f"No cached NIST lines found in {cache_dir}. Build the cache first:\n"
            f"  python utils/nist_lines.py --sets {set_desc}"
        )

    out = pd.concat(frames, ignore_index=True) if frames else pd.DataFrame()
    if out.empty:
        return out
    if wl_range is not None:
        out = out[(out["wavelength_nm"] >= wl_range[0])
                  & (out["wavelength_nm"] <= wl_range[1])]
    if min_intensity is not None:
        out = out[out["rel_intensity"].fillna(0) >= min_intensity]
    if boltzmann_only:
        out = out[out["boltzmann_ok"]]
    return out.sort_values("wavelength_nm").reset_index(drop=True)


def cache_status(
    set_names: list[str] | str = (),
    cache_range: tuple[float, float] = (340.0, 900.0),
    cache_dir: Path | None = None,
    stages: tuple[str, ...] = DEFAULT_STAGES,
    include_common: bool = True,
    elements: list[str] | None = None,
) -> dict:
    """Report which species are cached, for UI display."""
    cache_dir = Path(cache_dir or DEFAULT_CACHE_DIR)
    targets = species_list(
        set_names,
        stages=stages,
        include_common=include_common,
        elements=elements,
    )
    present = [s for s in targets
               if _cache_path(s, cache_range, cache_dir).exists()]
    return {
        "total":   len(targets),
        "cached":  len(present),
        "missing": [s for s in targets if s not in present],
        "complete": len(present) == len(targets),
        "cache_dir": str(cache_dir),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="Build the local NIST ASD line cache.")
    ap.add_argument("--sets", nargs="*", default=[], choices=sorted(ELEMENT_SETS),
                    help="named element sets to fetch")
    ap.add_argument("--elements", nargs="*", default=None,
                    help="explicit element symbols to fetch (e.g. Fe Cr Ni Cu)")
    ap.add_argument("-c", "--composition", default=None,
                    help="raw -c composition string (e.g. 'C-0.28,Cr-11.9,Fe-#' or 'C:0.28,Cr:11.9,Fe:87.8')")
    ap.add_argument("--range", nargs=2, type=float, default=[340.0, 900.0],
                    metavar=("LOW_NM", "HIGH_NM"))
    ap.add_argument("--cache-dir", default=None)
    ap.add_argument("--force", action="store_true", help="re-fetch cached species")
    args = ap.parse_args()

    elements = list(args.elements) if args.elements else []
    if args.composition:
        for el in parse_elements_from_composition(args.composition):
            if el not in elements:
                elements.append(el)

    sets = list(args.sets)
    if not sets and not elements:
        sets = ["steel", "bronze", "aluminium"]

    wl_range = (args.range[0], args.range[1])
    print(f"Fetching NIST ASD lines for sets={sets}, elements={elements} over "
          f"{wl_range[0]:g}-{wl_range[1]:g} nm (air)")

    def _prog(frac, sp):
        print(f"  [{frac*100:5.1f}%] {sp}")

    df = build_cache(sets, wl_range, cache_dir=args.cache_dir,
                     force=args.force, progress=_prog, elements=elements)
    if df.empty:
        print("No lines retrieved.", file=sys.stderr)
        return 1

    print(f"\n{len(df)} lines cached across {df['species'].nunique()} species")
    print(f"  Boltzmann-usable (have A_ki, g_k, E_k): {int(df['boltzmann_ok'].sum())}")
    print(f"  Cache directory: {Path(args.cache_dir or DEFAULT_CACHE_DIR)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
