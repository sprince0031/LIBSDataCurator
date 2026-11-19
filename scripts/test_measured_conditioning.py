"""Unit tests for measured data conditioning module (R3)."""

import numpy as np
import pandas as pd
import pytest
from pathlib import Path

from scripts.measured_conditioning import (
    condition_material,
    preprocess,
    ConditionedMaterial,
)


@pytest.fixture
def synthetic_material_csvs(tmp_path):
    p = 500  # 500 channels
    n_shots = 30  # 30 shots per point
    wls = np.linspace(340.0, 890.0, p)
    
    # Base spectrum with continuous baseline and peaks
    base = 200.0 + 100.0 * np.sin(wls / 50.0)
    base[100] += 5000.0
    base[250] += 8000.0
    base[400] += 3000.0
    
    # Channels 0..11 are unlit with dark noise ~50 counts
    base[:12] = 50.0
    
    # Point 1: 30 shots
    # Shots 0..3 have surface contamination (anomalous)
    shots_pt1 = []
    for s in range(n_shots):
        shot = base.copy()
        if s < 4:
            # Anomalous leading shot
            shot[50] += 4000.0  # Na / surface line
            scale = 0.5 + 0.1 * s
        else:
            scale = 1.0 + 0.2 * np.sin(s)  # Common-mode shot-to-shot variation
            
        noise = np.random.normal(0, 10.0, p)
        shot = shot * scale + noise
        shot[:12] = 50.0 + np.random.normal(0, 2.0, 12)  # Dark unlit channels
        shots_pt1.append(shot)
        
    df1 = pd.DataFrame(shots_pt1, columns=[f"{w:.3f}" for w in wls])
    csv1 = tmp_path / "point_1.csv"
    df1.to_csv(csv1, index=False)
    
    # Point 2: 30 shots with planted saturation in channel 250
    shots_pt2 = []
    for s in range(n_shots):
        shot = base.copy()
        if s < 3:
            shot[60] += 3000.0
            scale = 0.6
        else:
            scale = 1.0 + 0.15 * np.cos(s)
        noise = np.random.normal(0, 10.0, p)
        shot = shot * scale + noise
        shot[:12] = 50.0 + np.random.normal(0, 2.0, 12)
        # Plant saturation on shot 20..28
        if s >= 10:
            shot[250] = 62000.0  # Above 0.9 * 65535 = 58981.5
        shots_pt2.append(shot)
        
    df2 = pd.DataFrame(shots_pt2, columns=[f"{w:.3f}" for w in wls])
    csv2 = tmp_path / "point_2.csv"
    df2.to_csv(csv2, index=False)
    
    return [str(csv1), str(csv2)], wls, base


def test_lit_mask_and_dark_subtraction(synthetic_material_csvs):
    csv_paths, wls, base = synthetic_material_csvs
    manifest = {"detectorFullScale": 65535.0, "saturationThresholdFactor": 0.9}
    
    cond = condition_material(csv_paths, manifest)
    assert isinstance(cond, ConditionedMaterial)
    
    # Channels 0..11 were planted unlit (~50 counts)
    # The first channel with mean > 3 * m0 should be channel 12
    assert not cond.lit_mask[0]
    assert not cond.lit_mask[11]
    assert cond.lit_mask[12]
    
    # Dark level should be ~50 counts
    assert pytest.approx(cond.dark_level, abs=5.0) == 50.0
    # Mean of unlit channels after dark subtraction should be near 0
    assert abs(np.mean(cond.mean_spectrum[:12])) < 5.0


def test_conditioning_shots_and_clamps(synthetic_material_csvs):
    csv_paths, wls, base = synthetic_material_csvs
    manifest = {}
    cond = condition_material(csv_paths, manifest)
    
    # Planted 4 anomalous shots in pt1, 3 in pt2. Clamp is [3, 15]
    for pt, n0 in cond.discarded_shots_per_point.items():
        assert 3 <= n0 <= 15
        
    # Test manifest override
    manifest_override = {"discardLeadingShots": 7}
    cond_override = condition_material(csv_paths, manifest_override)
    for pt, n0 in cond_override.discarded_shots_per_point.items():
        assert n0 == 7


def test_saturation_masking_without_zeroing(synthetic_material_csvs):
    csv_paths, wls, base = synthetic_material_csvs
    manifest = {"detectorFullScale": 65535.0, "saturationThresholdFactor": 0.9}
    cond = condition_material(csv_paths, manifest)
    
    # Channel 250 had frequent saturation (> 58981.5) in point 2
    # In point 1 it was unsaturated (~8000 counts)
    # Total valid shots in channel 250 across steady state:
    # Point 1 has ~26 valid shots, point 2 has only ~6 valid shots.
    # Total valid shots >= max(5, 0.25 * total_steady_shots), so if valid it retains valid counts
    # It must NEVER be set to 0.0 in mean_spectrum!
    assert cond.mean_spectrum[250] > 1000.0
    
    # If a channel has saturation on almost all shots, it is masked in valid_mask, not set to 0.0
    p = 20
    assert cond.valid_mask[p] == cond.lit_mask[p]


def test_shot_scale_normalization_removes_common_mode(tmp_path):
    p = 100
    n_shots = 20
    wls = np.linspace(400.0, 500.0, p)
    base = 1000.0 + 500.0 * np.sin(wls / 10.0)
    
    # Large common-mode fluctuations (factor of 0.5 to 2.0)
    scales = np.linspace(0.5, 2.0, n_shots)
    shots = [base * s for s in scales]
    
    df = pd.DataFrame(shots, columns=[f"{w:.3f}" for w in wls])
    csv_path = tmp_path / "scaling_test.csv"
    df.to_csv(csv_path, index=False)
    
    cond = condition_material([str(csv_path)], manifest={"discardLeadingShots": 3})
    
    # After normalization, variance should be near 0 (pure shot scale with no noise)
    # sigma_mean should be extremely small compared to raw standard error
    raw_sem = np.std(shots[3:], axis=0) / np.sqrt(len(shots[3:]))
    assert np.mean(cond.sigma_mean) < 0.05 * np.mean(raw_sem)


def test_preprocess_morphological_opening_idempotency():
    x = np.linspace(340.0, 900.0, 1000)
    spectrum = 500.0 + 200.0 * np.sin(x / 50.0)  # broad continuum
    spectrum[200] += 5000.0  # line 1
    spectrum[500] += 7000.0  # line 2
    
    p1 = preprocess(spectrum, struct_size=65)
    p2 = preprocess(p1, struct_size=65)
    
    # Idempotent: morphological opening of line-only spectrum leaves it invariant
    np.testing.assert_allclose(p1, p2, atol=1e-5)
    assert np.all(p1 >= 0.0)
