"""Generate notebooks/calibration_inspector.ipynb."""

import sys
from pathlib import Path

try:
    from scripts.calibration_inspector import generate_inspector_notebook
except ImportError:
    from calibration_inspector import generate_inspector_notebook

if __name__ == "__main__":
    out = generate_inspector_notebook()
    print(f"Generated {out.resolve()}")
