"""Flux-conserving spectral resampling and line spread measurement (R2).

Implements:
1. pixel_edges: detector pixel edges with half-pitch boundary extrapolation.
2. resample_to_detector: analytical error-function convolution integrated over pixels.
3. measure_source_fwhm: automated FWHM measurement of NIST atomic lines.
4. measure_instrument_fwhm: band-stratified 25th-percentile HWHM peak measurement.
5. calculate_add_sigma_nm: differential Gaussian standard deviation to match detector resolution.
"""

from __future__ import annotations

import numpy as np
from scipy.integrate import trapezoid
from scipy.signal import find_peaks
from scipy.special import erf

#: Conversion factor between FWHM and Gaussian standard deviation: 2 * sqrt(2 * ln 2)
FWHM_TO_SIGMA = 2.0 * np.sqrt(2.0 * np.log(2.0))  # ~2.354820045


def pixel_edges(lam_det: np.ndarray) -> np.ndarray:
    """Compute detector channel pixel edges.
    
    Returns an array of length P + 1 where internal boundaries are midpoints
    between channel centres, and the outer boundaries are extrapolated by half a pitch.
    """
    lam = np.asarray(lam_det, dtype=np.float64)
    p = len(lam)
    if p < 2:
        raise ValueError("Detector wavelength grid must have at least 2 channels")
    
    edges = np.empty(p + 1, dtype=np.float64)
    edges[1:p] = 0.5 * (lam[:-1] + lam[1:])
    edges[0] = lam[0] - 0.5 * (lam[1] - lam[0])
    edges[p] = lam[-1] + 0.5 * (lam[-1] - lam[-2])
    return edges


def resample_to_detector(
    wl_src: np.ndarray,
    s_src: np.ndarray,
    lam_det: np.ndarray,
    add_sigma_nm: np.ndarray | float,
) -> np.ndarray:
    """Resample high-resolution source spectrum to detector channels conserving flux.
    
    Parameters
    ----------
    wl_src : 1D array of source wavelengths (nm, monotonically increasing).
    s_src : 1D array of source spectral intensities.
    lam_det : 1D array of detector channel center wavelengths.
    add_sigma_nm : scalar or 1D array of additional Gaussian broadening standard deviation (nm).
    
    Returns
    -------
    out : 1D array of mean spectral density per detector pixel (counts / nm).
    """
    wl = np.asarray(wl_src, dtype=np.float64)
    s = np.asarray(s_src, dtype=np.float64)
    det = np.asarray(lam_det, dtype=np.float64)
    p_len = len(det)
    
    edges = pixel_edges(det)
    
    if np.isscalar(add_sigma_nm):
        sigmas = np.full(p_len, float(add_sigma_nm), dtype=np.float64)
    else:
        sigmas = np.asarray(add_sigma_nm, dtype=np.float64)
        if len(sigmas) != p_len:
            raise ValueError(f"add_sigma_nm length {len(sigmas)} must match detector channels {p_len}")
    
    out = np.zeros(p_len, dtype=np.float64)
    sqrt2 = np.sqrt(2.0)
    
    for p in range(p_len):
        e_lo = edges[p]
        e_hi = edges[p + 1]
        width = e_hi - e_lo
        if width <= 0:
            continue
        
        sigma = sigmas[p]
        
        if sigma > 1e-9:
            # Restricted to [e_lo - 5*sigma, e_hi + 5*sigma]
            win_lo = e_lo - 5.0 * sigma
            win_hi = e_hi + 5.0 * sigma
            
            i_lo = max(0, np.searchsorted(wl, win_lo, side="left") - 1)
            i_hi = min(len(wl), np.searchsorted(wl, win_hi, side="right") + 1)
            
            if i_hi - i_lo < 2:
                continue
            
            w_sub = wl[i_lo:i_hi]
            s_sub = s[i_lo:i_hi]
            
            # W_p(lambda) = 0.5 * [erf((e_hi - lambda) / (sqrt(2)*sigma)) - erf((e_lo - lambda) / (sqrt(2)*sigma))]
            arg_hi = (e_hi - w_sub) / (sqrt2 * sigma)
            arg_lo = (e_lo - w_sub) / (sqrt2 * sigma)
            w_kernel = 0.5 * (erf(arg_hi) - erf(arg_lo))
            
            integral = trapezoid(s_sub * w_kernel, w_sub)
            out[p] = integral / width
        else:
            # Box-car integration over [e_lo, e_hi]
            i_lo = np.searchsorted(wl, e_lo, side="left")
            i_hi = np.searchsorted(wl, e_hi, side="right")
            
            # Sub-grid including boundary interpolations
            sub_wl_list = [e_lo]
            sub_s_list = [float(np.interp(e_lo, wl, s))]
            
            if i_hi > i_lo:
                sub_wl_list.extend(wl[i_lo:i_hi].tolist())
                sub_s_list.extend(s[i_lo:i_hi].tolist())
            
            sub_wl_list.append(e_hi)
            sub_s_list.append(float(np.interp(e_hi, wl, s)))
            
            sub_wl = np.array(sub_wl_list, dtype=np.float64)
            sub_s = np.array(sub_s_list, dtype=np.float64)
            
            integral = trapezoid(sub_s, sub_wl)
            out[p] = integral / width
            
    return out


def measure_source_fwhm(
    wl_src: np.ndarray,
    s_src: np.ndarray,
    min_peak_height_fraction: float = 0.05,
) -> np.ndarray:
    """Measure the FWHM of isolated emission peaks in the source spectrum.
    
    Returns an array of FWHM values matching wl_src via linear interpolation across peaks.
    """
    wl = np.asarray(wl_src, dtype=np.float64)
    s = np.asarray(s_src, dtype=np.float64)
    
    max_h = np.max(s)
    if max_h <= 0:
        return np.full_like(wl, 0.15)
    
    # Identify prominent peaks separated by at least 0.5 nm
    pitch = (wl[-1] - wl[0]) / max(len(wl) - 1, 1)
    min_dist = max(int(0.5 / pitch), 3)
    
    peak_indices, _ = find_peaks(
        s,
        height=max_h * min_peak_height_fraction,
        distance=min_dist,
    )
    
    peak_wls = []
    peak_fwhms = []
    
    for p_idx in peak_indices:
        lam_0 = wl[p_idx]
        h_0 = s[p_idx]
        h_half = 0.5 * h_0
        
        # Search left
        left_idx = p_idx
        while left_idx > 0 and s[left_idx] > h_half:
            left_idx -= 1
        if s[left_idx] > h_half:
            continue
        # Linear interpolation for left crossing
        lam_left = wl[left_idx] + (h_half - s[left_idx]) * (wl[left_idx + 1] - wl[left_idx]) / (s[left_idx + 1] - s[left_idx] + 1e-15)
        
        # Search right
        right_idx = p_idx
        while right_idx < len(s) - 1 and s[right_idx] > h_half:
            right_idx += 1
        if s[right_idx] > h_half:
            continue
        # Linear interpolation for right crossing
        lam_right = wl[right_idx - 1] + (h_half - s[right_idx - 1]) * (wl[right_idx] - wl[right_idx - 1]) / (s[right_idx] - s[right_idx - 1] + 1e-15)
        
        fwhm = lam_right - lam_left
        if 0.02 < fwhm < 2.0:
            peak_wls.append(lam_0)
            peak_fwhms.append(fwhm)
            
    if not peak_fwhms:
        return np.full_like(wl, 0.15)
    
    if len(peak_fwhms) == 1:
        return np.full_like(wl, peak_fwhms[0])
    
    return np.interp(wl, peak_wls, peak_fwhms)


def measure_instrument_fwhm(
    lam_det: np.ndarray,
    y_mean: np.ndarray,
    y_sem: np.ndarray | None = None,
    line_spread_fwhm_nm: np.ndarray | float | None = None,
) -> np.ndarray:
    """Measure instrument line spread FWHM per channel across 4 spectral bands.
    
    Bands:
    1: 347–450 nm (center 398.5 nm)
    2: 450–550 nm (center 500.0 nm)
    3: 550–700 nm (center 625.0 nm)
    4: 700–897 nm (center 798.5 nm)
    
    Clamped to [1.0, 4.0] pixels.
    """
    det = np.asarray(lam_det, dtype=np.float64)
    y = np.asarray(y_mean, dtype=np.float64)
    p_len = len(det)
    pitch = (det[-1] - det[0]) / max(p_len - 1, 1)
    
    if line_spread_fwhm_nm is not None:
        if np.isscalar(line_spread_fwhm_nm):
            return np.full(p_len, float(line_spread_fwhm_nm), dtype=np.float64)
        arr = np.asarray(line_spread_fwhm_nm, dtype=np.float64)
        if len(arr) == p_len:
            return arr
    
    if y_sem is not None:
        median_sem = float(np.median(y_sem))
    else:
        # Robust noise estimate from successive channel differences
        median_sem = float(np.median(np.abs(np.diff(y))) / 1.4826)
        
    thresh = max(8.0 * median_sem, 0.05 * np.max(y))
    min_dist_px = max(int(0.6 / pitch), 2)
    
    peak_indices, _ = find_peaks(y, height=thresh, distance=min_dist_px)
    
    peak_wls = []
    peak_fwhms = []
    
    for p_idx in peak_indices:
        # Parabolic interpolation for sub-pixel peak center and height
        if 0 < p_idx < len(det) - 1:
            y_m1 = y[p_idx - 1]
            y_0 = y[p_idx]
            y_p1 = y[p_idx + 1]
            denom = y_m1 - 2.0 * y_0 + y_p1
            if abs(denom) > 1e-12:
                delta = 0.5 * (y_m1 - y_p1) / denom
                delta = np.clip(delta, -0.7, 0.7)
                lam_0 = det[p_idx] + delta * pitch
                h_0 = y_0 - 0.25 * (y_m1 - y_p1) * delta
            else:
                lam_0 = det[p_idx]
                h_0 = y_0
        else:
            lam_0 = det[p_idx]
            h_0 = y[p_idx]

        h_half = 0.5 * h_0
        
        # Left half-max
        l_idx = p_idx
        while l_idx > 0 and y[l_idx] > h_half:
            l_idx -= 1
        if y[l_idx] > h_half:
            continue
        lam_left = det[l_idx] + (h_half - y[l_idx]) * (det[l_idx + 1] - det[l_idx]) / (y[l_idx + 1] - y[l_idx] + 1e-15)
        half_w_left = max(lam_0 - lam_left, 1e-6)
        
        # Right half-max
        r_idx = p_idx
        while r_idx < len(y) - 1 and y[r_idx] > h_half:
            r_idx += 1
        if y[r_idx] > h_half:
            continue
        lam_right = det[r_idx - 1] + (h_half - y[r_idx - 1]) * (det[r_idx] - det[r_idx - 1]) / (y[r_idx] - y[r_idx - 1] + 1e-15)
        half_w_right = max(lam_right - lam_0, 1e-6)
        
        # Twice the smaller half-width at half maximum
        fwhm = 2.0 * min(half_w_left, half_w_right)
        if 0.05 < fwhm < 3.0:
            peak_wls.append(lam_0)
            peak_fwhms.append(fwhm)
            
    # Bands definition: (lo, hi, center)
    bands = [
        (347.0, 450.0, 398.5),
        (450.0, 550.0, 500.0),
        (550.0, 700.0, 625.0),
        (700.0, 897.0, 798.5),
    ]
    
    overall_p25 = np.percentile(peak_fwhms, 25) if peak_fwhms else 0.37
    
    band_centers = []
    band_fwhm_q25 = []
    
    for b_lo, b_hi, b_center in bands:
        b_vals = [f for w, f in zip(peak_wls, peak_fwhms) if b_lo <= w <= b_hi]
        q25 = float(np.percentile(b_vals, 25)) if b_vals else overall_p25
        band_centers.append(b_center)
        band_fwhm_q25.append(q25)
        
    fwhm_per_channel = np.interp(det, band_centers, band_fwhm_q25)
    
    # Clamp to [1.0, 4.0] pixels
    min_nm = 1.0 * pitch
    max_nm = 4.0 * pitch
    return np.clip(fwhm_per_channel, min_nm, max_nm)


def calculate_add_sigma_nm(
    fwhm_inst: np.ndarray | float,
    fwhm_src: np.ndarray | float,
) -> np.ndarray | float:
    """Calculate the differential Gaussian standard deviation to match source to instrument.
    
    add_sigma_nm = sqrt(max(fwhm_inst^2 - fwhm_src^2, 0)) / 2.354820045
    """
    diff_sq = np.maximum(np.square(fwhm_inst) - np.square(fwhm_src), 0.0)
    return np.sqrt(diff_sq) / FWHM_TO_SIGMA
