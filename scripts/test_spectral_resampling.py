"""Unit tests for spectral resampling and line spread measurement (R2, AC4)."""

import numpy as np
import pytest
from scipy.integrate import trapezoid

from scripts.spectral_resampling import (
    pixel_edges,
    resample_to_detector,
    measure_source_fwhm,
    measure_instrument_fwhm,
    calculate_add_sigma_nm,
)


@pytest.fixture
def real_detector_grid():
    # 2048 channels matching the real instrument grid (~341.952 to 896.916 nm, pitch ~0.271 nm)
    return np.linspace(341.952, 896.916, 2048)


def test_pixel_edges_length_and_monotonicity(real_detector_grid):
    edges = pixel_edges(real_detector_grid)
    p = len(real_detector_grid)
    assert len(edges) == p + 1
    # Strictly monotonically increasing
    diffs = np.diff(edges)
    assert np.all(diffs > 0)
    # Internal midpoints
    assert np.allclose(edges[1:p], 0.5 * (real_detector_grid[:-1] + real_detector_grid[1:]))
    # Extrapolated boundaries by half pitch
    assert pytest.approx(edges[0]) == real_detector_grid[0] - 0.5 * (real_detector_grid[1] - real_detector_grid[0])
    assert pytest.approx(edges[-1]) == real_detector_grid[-1] + 0.5 * (real_detector_grid[-1] - real_detector_grid[-2])


def test_resample_flux_conservation_non_uniform_grid_with_gaps(real_detector_grid):
    # Create non-uniform source grid with gaps well inside detector range
    wl_src = np.sort(np.concatenate([
        np.linspace(400.0, 450.0, 500),
        np.linspace(450.0, 480.0, 150),  # different step
        np.linspace(490.0, 550.0, 700),  # gap between 480 and 490
    ]))
    # Several Gaussian emission peaks
    centers = [420.0, 440.0, 465.0, 510.0, 530.0]
    s_src = np.zeros_like(wl_src)
    for c in centers:
        s_src += 1000.0 * np.exp(-0.5 * ((wl_src - c) / 0.15) ** 2)
    
    source_flux = trapezoid(s_src, wl_src)
    
    # Resample with realistic added broadening sigma = 0.14 nm
    add_sigma = np.full_like(real_detector_grid, 0.14)
    out = resample_to_detector(wl_src, s_src, real_detector_grid, add_sigma)
    
    edges = pixel_edges(real_detector_grid)
    pixel_widths = np.diff(edges)
    detector_flux = np.sum(out * pixel_widths)
    
    # Flux conservation within 0.1% (AC4)
    rel_diff = abs(detector_flux - source_flux) / source_flux
    assert rel_diff < 0.001, f"Flux difference {rel_diff * 100:.3f}% exceeds 0.1%"


def test_single_line_pixel_sum_stability_across_subpixel_shifts(real_detector_grid):
    # Select a detector pixel around 500 nm
    idx = 580
    edges = pixel_edges(real_detector_grid)
    e_lo = edges[idx]
    e_hi = edges[idx + 1]
    
    add_sigma = np.full_like(real_detector_grid, 0.14)
    pixel_widths = np.diff(edges)
    
    sums = []
    # Test line center moving across pixel from left edge to right edge in 11 positions
    for shift in np.linspace(e_lo, e_hi, 11):
        wl_fine = np.linspace(shift - 2.0, shift + 2.0, 2000)
        s_fine = np.exp(-0.5 * ((wl_fine - shift) / 0.08) ** 2)
        out = resample_to_detector(wl_fine, s_fine, real_detector_grid, add_sigma)
        # Sum counts around the line (+- 5 pixels)
        flux = np.sum(out[idx - 5: idx + 6] * pixel_widths[idx - 5: idx + 6])
        sums.append(flux)
    
    sums = np.array(sums)
    max_variation = (np.max(sums) - np.min(sums)) / np.mean(sums)
    # Pixel sum changes by less than 2% as line center moves across a pixel (AC4)
    assert max_variation < 0.02, f"Subpixel variation {max_variation * 100:.2f}% exceeds 2%"


def test_resample_zero_sigma_reduces_to_pixel_box_averaging(real_detector_grid):
    # Source spectrum with quadratic variation over pixels
    wl_src = np.linspace(450.0, 460.0, 1000)
    s_src = 100.0 + (wl_src - 455.0) ** 2
    
    # Resample with zero broadening
    add_sigma = np.zeros_like(real_detector_grid)
    out = resample_to_detector(wl_src, s_src, real_detector_grid, add_sigma)
    
    edges = pixel_edges(real_detector_grid)
    # Check pixels that fall inside [451, 459]
    in_range = (real_detector_grid >= 451.0) & (real_detector_grid <= 459.0)
    indices = np.where(in_range)[0]
    
    for i in indices[:5]:
        e_p = edges[i]
        e_next = edges[i + 1]
        mask = (wl_src >= e_p) & (wl_src <= e_next)
        if np.sum(mask) >= 2:
            expected_mean = trapezoid(s_src[mask], wl_src[mask]) / (e_next - e_p)
            assert pytest.approx(out[i], rel=1e-2) == expected_mean


def test_measure_source_fwhm():
    # Source spectrum on fine grid with known Gaussian peaks of FWHM = 0.16 nm
    # FWHM = 2 * sqrt(2 * ln(2)) * sigma ~= 2.35482 * sigma -> sigma = 0.16 / 2.35482 ~= 0.06795 nm
    sigma_true = 0.16 / (2.0 * np.sqrt(2.0 * np.log(2.0)))
    wl = np.linspace(350.0, 850.0, 50000)
    s = np.zeros_like(wl)
    peaks = [400.0, 500.0, 600.0, 700.0, 800.0]
    for p in peaks:
        s += 5000.0 * np.exp(-0.5 * ((wl - p) / sigma_true) ** 2)
    
    measured_fwhm = measure_source_fwhm(wl, s)
    # Recovers known Gaussian width within 5%
    for p in peaks:
        fwhm_at_p = np.interp(p, wl, measured_fwhm) if isinstance(measured_fwhm, np.ndarray) else measured_fwhm
        assert pytest.approx(fwhm_at_p, rel=0.05) == 0.16


def test_measure_instrument_fwhm_and_clamps(real_detector_grid):
    # Planted instrument peaks with FWHM = 0.37 nm
    sigma_inst = 0.37 / (2.0 * np.sqrt(2.0 * np.log(2.0)))
    y_mean = np.zeros_like(real_detector_grid)
    # Add peaks in each band
    peaks = [400.0, 500.0, 620.0, 800.0]
    for p in peaks:
        y_mean += 10000.0 * np.exp(-0.5 * ((real_detector_grid - p) / sigma_inst) ** 2)
    
    fwhm_inst = measure_instrument_fwhm(real_detector_grid, y_mean)
    assert isinstance(fwhm_inst, np.ndarray)
    assert len(fwhm_inst) == len(real_detector_grid)
    
    # Recovers planted 0.37 nm width within 15%
    median_val = np.median(fwhm_inst)
    assert pytest.approx(median_val, rel=0.15) == 0.37
    
    # Verify clamping in pixels [1.0, 4.0]
    pitch = (real_detector_grid[-1] - real_detector_grid[0]) / (len(real_detector_grid) - 1)
    min_nm = 1.0 * pitch
    max_nm = 4.0 * pitch
    assert np.all(fwhm_inst >= min_nm - 1e-6)
    assert np.all(fwhm_inst <= max_nm + 1e-6)


def test_calculate_add_sigma_nm():
    fwhm_inst = np.array([0.37, 0.30, 0.10])
    fwhm_src = np.array([0.15, 0.15, 0.20])
    
    add_sigma = calculate_add_sigma_nm(fwhm_inst, fwhm_src)
    
    # When inst > src: sqrt(0.37^2 - 0.15^2) / 2.354820
    expected_0 = np.sqrt(0.37 ** 2 - 0.15 ** 2) / 2.354820045
    assert pytest.approx(add_sigma[0], rel=1e-4) == expected_0
    
    # When inst < src: max(..., 0) -> 0.0
    assert add_sigma[2] == 0.0
