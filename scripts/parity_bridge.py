"""Read-only parity bridge exposing canonical compute_spectral_metrics for paper-harness.

Delegates directly to scripts/match_metrics.py without requiring LIBSDataCurator
to be pre-installed on sys.path (supports importlib.util.spec_from_file_location).
"""

from __future__ import annotations

import importlib.util
from pathlib import Path
import sys

_SCRIPTS_DIR = Path(__file__).resolve().parent
_MATCH_METRICS_PATH = _SCRIPTS_DIR / "match_metrics.py"


def _load_match_metrics():
    if "scripts.match_metrics" in sys.modules:
        return sys.modules["scripts.match_metrics"]
    spec = importlib.util.spec_from_file_location("_libs_match_metrics", _MATCH_METRICS_PATH)
    if spec is None or spec.loader is None:
        raise ImportError(f"Unable to load match_metrics module from {_MATCH_METRICS_PATH}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


_match_metrics = _load_match_metrics()
compute_spectral_metrics = _match_metrics.compute_spectral_metrics

__all__ = ["compute_spectral_metrics"]
