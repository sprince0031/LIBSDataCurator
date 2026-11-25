package com.medals.libsdatagenerator.util;

import com.medals.libsdatagenerator.model.PlasmaZone;
import com.medals.libsdatagenerator.model.Spectrum;
import org.apache.commons.math3.analysis.interpolation.LinearInterpolator;
import org.apache.commons.math3.analysis.polynomials.PolynomialSplineFunction;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;

public class SpectrumUtils {
    private Logger logger = Logger.getLogger(SpectrumUtils.class.getName());

    /**
     * Calculates the average spectrum from multiple shots.
     *
     * @param spectra List of intensity arrays
     * @return Average intensity array
     */
    public double[] calculateAverageSpectrum(List<double[]> spectra) {
        if (spectra.isEmpty()) {
            return new double[0];
        }

        int length = spectra.get(0).length;
        double[] avg = new double[length];

        for (double[] spectrum : spectra) {
            for (int i = 0; i < Math.min(length, spectrum.length); i++) {
                avg[i] += spectrum[i];
            }
        }

        for (int i = 0; i < length; i++) {
            avg[i] /= spectra.size();
        }

        return avg;
    }

    /**
     * Performs linear interpolation on a spectrum
     * @param waveMap TreeMap of wavelength -> intensity i.e., spectrum to interpolate on
     * @param targetGrid List of target wavelength arrays
     * @return Array of intensities aligned to the target wavelength grid.
     */
    public double[] interpolateSpectrum(Map<Double, Double> waveMap, double[] targetGrid) {
        logger.info("Interpolating to target wavelength grid...");

        Spectrum spectrum = new Spectrum(waveMap);
        double[] originalGrid = spectrum.getWavelengths();
        double[] originalIntensities = spectrum.getIntensities();
        LinearInterpolator interpolator = new LinearInterpolator();
        PolynomialSplineFunction interpolationFunction = interpolator.interpolate(originalGrid, originalIntensities);

        double[] aligned = new double[targetGrid.length];
        double minX = originalGrid[0];
        double maxX = originalGrid[originalGrid.length - 1];
        double firstY = originalIntensities[0];
        double lastY = originalIntensities[originalIntensities.length - 1];

        // 2. Evaluate for every point in the new grid
        for (int i = 0; i < targetGrid.length; i++) {
            double x = targetGrid[i];

            if (x < minX) {
                // numpy.interp default: fill with the first value
                aligned[i] = firstY;
            } else if (x > maxX) {
                // numpy.interp default: fill with the last value
                aligned[i] = lastY;
            } else {
                // Standard interpolation
                aligned[i] = interpolationFunction.value(x);
            }
        }
        return aligned;
    }

    public List<Double> normaliseAndScaleSpectrum(double[] spectrum, double scaleFactor) {
        return normaliseAndScaleSpectrum(spectrum, scaleFactor, (double[]) null);
    }

    public List<Double> normaliseAndScaleSpectrum(double[] spectrum, double scaleFactor, double[] spectralResponse) {
        double[] responseCorrected = applySpectralResponse(spectrum, spectralResponse);
        List<Double> scaledSpectrum = new ArrayList<>();
        for (double val : normaliseSpectrum(responseCorrected)) {
            scaledSpectrum.add(val * scaleFactor);
        }
        return scaledSpectrum;
    }

    public List<Double> normaliseAndScaleSpectrum(double[] spectrum, double scaleFactor, List<Double> spectralResponse) {
        double[] respArray = (spectralResponse != null) ? spectralResponse.stream().mapToDouble(Double::doubleValue).toArray() : null;
        return normaliseAndScaleSpectrum(spectrum, scaleFactor, respArray);
    }

    /**
     * Multiplies the spectrum point-by-point by the instrument spectral response r(lambda).
     */
    public double[] applySpectralResponse(double[] spectrum, double[] spectralResponse) {
        if (spectrum == null) {
            return null;
        }
        if (spectralResponse == null || spectralResponse.length == 0) {
            return Arrays.copyOf(spectrum, spectrum.length);
        }
        if (spectralResponse.length != spectrum.length) {
            throw new IllegalArgumentException(String.format(
                    "Spectral response size (%d) does not match spectrum length (%d)",
                    spectralResponse.length, spectrum.length));
        }
        double[] result = new double[spectrum.length];
        for (int i = 0; i < spectrum.length; i++) {
            result[i] = spectrum[i] * spectralResponse[i];
        }
        return result;
    }

    public double[] applySpectralResponse(double[] spectrum, List<Double> spectralResponse) {
        double[] respArray = (spectralResponse != null) ? spectralResponse.stream().mapToDouble(Double::doubleValue).toArray() : null;
        return applySpectralResponse(spectrum, respArray);
    }

    /**
     * Resamples high-resolution source spectrum to detector channels conserving flux.
     * Analytical error-function convolution integrated over pixel edges.
     */
    public static double[] resampleToDetector(
            double[] srcWl,
            double[] srcI,
            double[] detWl,
            double[] addSigmaNm) {
        if (srcWl == null || srcI == null || detWl == null) {
            return new double[0];
        }
        int pLen = detWl.length;
        if (pLen < 2) {
            throw new IllegalArgumentException("Detector wavelength grid must have at least 2 channels");
        }
        double[] edges = pixelEdges(detWl);
        double[] out = new double[pLen];
        double sqrt2 = Math.sqrt(2.0);

        for (int p = 0; p < pLen; p++) {
            double eLo = edges[p];
            double eHi = edges[p + 1];
            double width = eHi - eLo;
            if (width <= 0) {
                continue;
            }
            double sigma = (addSigmaNm != null && p < addSigmaNm.length) ? addSigmaNm[p] : 0.0;
            if (sigma > 1e-9) {
                double winLo = eLo - 5.0 * sigma;
                double winHi = eHi + 5.0 * sigma;

                int iLo = Math.max(0, searchsorted(srcWl, winLo, true) - 1);
                int iHi = Math.min(srcWl.length, searchsorted(srcWl, winHi, false) + 1);

                if (iHi - iLo < 2) {
                    continue;
                }

                double integral = 0.0;
                double prevW = srcWl[iLo];
                double argHiPrev = (eHi - prevW) / (sqrt2 * sigma);
                double argLoPrev = (eLo - prevW) / (sqrt2 * sigma);
                double prevY = srcI[iLo] * 0.5 * (org.apache.commons.math3.special.Erf.erf(argHiPrev) - org.apache.commons.math3.special.Erf.erf(argLoPrev));

                for (int i = iLo + 1; i < iHi; i++) {
                    double currW = srcWl[i];
                    double argHiCurr = (eHi - currW) / (sqrt2 * sigma);
                    double argLoCurr = (eLo - currW) / (sqrt2 * sigma);
                    double currY = srcI[i] * 0.5 * (org.apache.commons.math3.special.Erf.erf(argHiCurr) - org.apache.commons.math3.special.Erf.erf(argLoCurr));

                    integral += 0.5 * (prevY + currY) * (currW - prevW);
                    prevW = currW;
                    prevY = currY;
                }
                out[p] = integral / width;
            } else {
                int iLo = searchsorted(srcWl, eLo, true);
                int iHi = searchsorted(srcWl, eHi, false);

                List<Double> subWlList = new ArrayList<>();
                List<Double> subSList = new ArrayList<>();

                subWlList.add(eLo);
                subSList.add(interpLinear(eLo, srcWl, srcI));

                for (int i = iLo; i < iHi; i++) {
                    subWlList.add(srcWl[i]);
                    subSList.add(srcI[i]);
                }

                subWlList.add(eHi);
                subSList.add(interpLinear(eHi, srcWl, srcI));

                double integral = 0.0;
                for (int i = 0; i < subWlList.size() - 1; i++) {
                    double w0 = subWlList.get(i);
                    double w1 = subWlList.get(i + 1);
                    double y0 = subSList.get(i);
                    double y1 = subSList.get(i + 1);
                    integral += 0.5 * (y0 + y1) * (w1 - w0);
                }
                out[p] = integral / width;
            }
        }
        return out;
    }

    public static double[] pixelEdges(double[] lamDet) {
        int p = lamDet.length;
        if (p < 2) {
            throw new IllegalArgumentException("Detector wavelength grid must have at least 2 channels");
        }
        double[] edges = new double[p + 1];
        for (int i = 0; i < p - 1; i++) {
            edges[i + 1] = 0.5 * (lamDet[i] + lamDet[i + 1]);
        }
        edges[0] = lamDet[0] - 0.5 * (lamDet[1] - lamDet[0]);
        edges[p] = lamDet[p - 1] + 0.5 * (lamDet[p - 1] - lamDet[p - 2]);
        return edges;
    }

    private static int searchsorted(double[] a, double v, boolean left) {
        int low = 0;
        int high = a.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (left) {
                if (a[mid] < v) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            } else {
                if (a[mid] <= v) {
                    low = mid + 1;
                } else {
                    high = mid;
                }
            }
        }
        return low;
    }

    private static double interpLinear(double x, double[] xp, double[] fp) {
        if (x <= xp[0]) return fp[0];
        if (x >= xp[xp.length - 1]) return fp[fp.length - 1];
        int idx = searchsorted(xp, x, false);
        int i0 = Math.max(0, idx - 1);
        int i1 = Math.min(xp.length - 1, idx);
        if (i0 == i1) return fp[i0];
        double t = (x - xp[i0]) / (xp[i1] - xp[i0]);
        return fp[i0] + t * (fp[i1] - fp[i0]);
    }

    /**
     * Combines N zones using the recursive Radiative Transfer Equation (R4):
     *   Zones sorted by Te descending.
     *   I_0 = V_0 * d_0
     *   I_z = I_{z-1} * exp(-k_z * d_tilde_z) + V_z * d_z
     * where d_tilde_z = d_z / max(d_z) is used only for opacity.
     */
    public static double[] combineNZones(List<double[]> zoneSpectra, List<PlasmaZone> zones) {
        if (zoneSpectra == null || zones == null || zoneSpectra.isEmpty() || zoneSpectra.size() != zones.size()) {
            throw new IllegalArgumentException("Mismatch between spectra data and zone definitions.");
        }

        class ZonePair {
            PlasmaZone zone;
            double[] spectrum;
            ZonePair(PlasmaZone zone, double[] spectrum) {
                this.zone = zone;
                this.spectrum = spectrum;
            }
        }
        List<ZonePair> pairs = new ArrayList<>();
        for (int i = 0; i < zones.size(); i++) {
            pairs.add(new ZonePair(zones.get(i), zoneSpectra.get(i)));
        }
        pairs.sort((a, b) -> Double.compare(b.zone.getTe(), a.zone.getTe()));

        int numChannels = pairs.get(0).spectrum.length;
        double[] combined = new double[numChannels];

        // Zone 0 (Core)
        PlasmaZone z0 = pairs.get(0).zone;
        double[] d0 = pairs.get(0).spectrum;
        double v0 = z0.getVolumeFraction();
        for (int p = 0; p < numChannels; p++) {
            combined[p] = v0 * d0[p];
        }

        // Outer zones
        for (int z = 1; z < pairs.size(); z++) {
            PlasmaZone zone = pairs.get(z).zone;
            double[] d = pairs.get(z).spectrum;
            double v = zone.getVolumeFraction();
            double kAbs = Math.max(0.0, zone.getKAbsorption());

            double maxD = 0.0;
            for (double val : d) {
                if (val > maxD) maxD = val;
            }

            for (int p = 0; p < numChannels; p++) {
                double dTilde = (maxD > 1e-12) ? (d[p] / maxD) : 0.0;
                double opacity = Math.exp(-kAbs * dTilde);
                combined[p] = combined[p] * opacity + v * d[p];
            }
        }
        return combined;
    }

    /**
     * Applies spectralResponse R(λ), scales, and injects Finotello multiplicative shot noise N(1, β).
     * Backwards-compatible default using max_normalised emissionScale.
     */
    public static double[] applyInstrumentResponseAndNoise(
            double[] combinedRteSpectrum,
            double[] spectralResponse,
            double scaleFactor,
            double finotelloBeta,
            Random rng) {
        return applyInstrumentResponseAndNoise(
                combinedRteSpectrum, spectralResponse, scaleFactor, finotelloBeta, rng, "max_normalised");
    }

    /**
     * Applies spectralResponse R(λ), scales, and injects Finotello multiplicative shot noise N(1, β).
     *
     * @param emissionScale "absolute" (default) or "max_normalised"
     */
    public static double[] applyInstrumentResponseAndNoise(
            double[] combinedRteSpectrum,
            double[] spectralResponse,
            double scaleFactor,
            double finotelloBeta,
            Random rng,
            String emissionScale) {

        if (combinedRteSpectrum == null) {
            return null;
        }
        int n = combinedRteSpectrum.length;
        double[] respWeighted = new double[n];
        double maxResp = 0.0;

        for (int p = 0; p < n; p++) {
            double r = (spectralResponse != null && p < spectralResponse.length) ? spectralResponse[p] : 1.0;
            respWeighted[p] = combinedRteSpectrum[p] * r;
            if (respWeighted[p] > maxResp) {
                maxResp = respWeighted[p];
            }
        }

        boolean isMaxNorm = "max_normalised".equalsIgnoreCase(emissionScale);
        if (isMaxNorm && maxResp <= 1e-12) {
            maxResp = 1.0;
        }

        double[] finalCounts = new double[n];
        for (int p = 0; p < n; p++) {
            double noiseMultiplier = (rng != null && finotelloBeta > 0.0)
                    ? Math.max(0.0, 1.0 + finotelloBeta * rng.nextGaussian())
                    : 1.0;
            if (isMaxNorm) {
                finalCounts[p] = (respWeighted[p] / maxResp) * scaleFactor * noiseMultiplier;
            } else {
                finalCounts[p] = respWeighted[p] * scaleFactor * noiseMultiplier;
            }
        }
        return finalCounts;
    }

    /**
     * Normalises a spectrum to [0, 1] range.
     * If spectrum is empty or has no positive values, returns array of zeros.
     *
     * @param spectrum Input intensity array
     * @return Normalised array with values in [0, 1] range
     */
    public double[] normaliseSpectrum(double[] spectrum) {
        if (spectrum == null || spectrum.length == 0) {
            logger.warning("Empty spectrum provided for normalization, returning empty array");
            return new double[0];
        }

        double max = Arrays.stream(spectrum).max().orElse(0.0);
        if (max <= 0) {
            logger.fine("Spectrum has no positive values, returning zeros");
            return new double[spectrum.length]; // Returns array of zeros
        }

        double[] normalized = new double[spectrum.length];
        for (int i = 0; i < spectrum.length; i++) {
            normalized[i] = spectrum[i] / max;
        }
        return normalized;
    }

    /** Default numerical guard matching metric_contract_v1.json. */
    public static final double DEFAULT_METRIC_EPS = 1e-12;

    /** Default wavelength grid equality tolerance in nm matching metric_contract_v1.json. */
    public static final double DEFAULT_WAVELENGTH_TOL = 1e-6;

    /**
     * Canonical normalization modes defined in metric_contract_v1.json.
     */
    public enum NormalizationMode {
        NONE,
        MAX,
        L2,
        SUM;

        /** Returns the lowercase contract identifier (none, max, l2, sum). */
        public String contractName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        /** Parses a contract normalization name (case-insensitive) into a NormalizationMode. */
        public static NormalizationMode fromContractName(String name) {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Normalization mode must not be null or blank");
            }
            return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "none" -> NONE;
                case "max" -> MAX;
                case "l2" -> L2;
                case "sum" -> SUM;
                default -> throw new IllegalArgumentException(
                        "Unsupported normalization mode '" + name + "'; expected one of none, max, l2, sum");
            };
        }
    }

    /**
     * Canonical spectral comparison metric payload matching metric_contract_v1.json.
     */
    public record SpectralMetrics(
            double rmse,
            double nrmseMax,
            double mae,
            double r2,
            double pearsonR2,
            double cosineSimilarity,
            Double reducedChi2,
            NormalizationMode normalization,
            int nValid
    ) {}

    /**
     * Computes canonical spectral metrics with default settings (no normalization, no mask, no variance).
     */
    public static SpectralMetrics computeSpectralMetrics(double[] yRef, double[] yPred) {
        return computeSpectralMetrics(
                yRef, yPred, null, null, null, null,
                NormalizationMode.NONE, 0, DEFAULT_METRIC_EPS, DEFAULT_WAVELENGTH_TOL);
    }

    /**
     * Computes canonical spectral metrics with optional ROI mask, normalization mode, and eps guard.
     */
    public static SpectralMetrics computeSpectralMetrics(
            double[] yRef,
            double[] yPred,
            boolean[] mask,
            NormalizationMode normalization,
            double eps) {
        return computeSpectralMetrics(
                yRef, yPred, null, null, null, mask,
                normalization, 0, eps, DEFAULT_WAVELENGTH_TOL);
    }

    /**
     * Computes canonical spectral metrics with optional mask, normalization mode, empirical variance,
     * model parameter count, and eps guard.
     */
    public static SpectralMetrics computeSpectralMetrics(
            double[] yRef,
            double[] yPred,
            boolean[] mask,
            NormalizationMode normalization,
            double[] variance,
            int numParams,
            double eps) {
        return computeSpectralMetrics(
                yRef, yPred, null, null, variance, mask,
                normalization, numParams, eps, DEFAULT_WAVELENGTH_TOL);
    }

    /**
     * Pure, stateless IEEE 754 double-precision spectral metric evaluator conforming to
     * {@code metric_contract_v1.json}.
     *
     * @param yRef            Measured / reference intensity array
     * @param yPred           Predicted / synthetic intensity array
     * @param wavelengthsRef  Optional reference wavelength grid (nm)
     * @param wavelengthsPred Optional predicted wavelength grid (nm)
     * @param variance        Optional per-channel empirical variance array (null yields null reducedChi2)
     * @param mask            Optional boolean validity / ROI mask
     * @param normalization   Normalization mode (NONE, MAX, L2, SUM)
     * @param numParams       Number of fitted model parameters for reduced chi-squared DoF
     * @param eps             Numerical floor for division and zero-variance guards (default 1e-12)
     * @param wavelengthTol   Strict wavelength grid equality tolerance in nm (default 1e-6)
     * @return Immutable {@link SpectralMetrics} record
     * @throws IllegalArgumentException on null/mismatched array lengths, wavelength mismatch, or nValid == 0
     */
    public static SpectralMetrics computeSpectralMetrics(
            double[] yRef,
            double[] yPred,
            double[] wavelengthsRef,
            double[] wavelengthsPred,
            double[] variance,
            boolean[] mask,
            NormalizationMode normalization,
            int numParams,
            double eps,
            double wavelengthTol) {
        if (yRef == null || yPred == null) {
            throw new IllegalArgumentException("yRef and yPred must not be null");
        }
        if (normalization == null) {
            throw new IllegalArgumentException("normalization must not be null");
        }
        int nTotal = yRef.length;
        if (yPred.length != nTotal) {
            throw new IllegalArgumentException(
                    "yRef and yPred length mismatch: " + nTotal + " != " + yPred.length);
        }
        if (wavelengthsRef != null && wavelengthsRef.length != nTotal) {
            throw new IllegalArgumentException(
                    "wavelengthsRef length mismatch: " + wavelengthsRef.length + " != " + nTotal);
        }
        if (wavelengthsPred != null && wavelengthsPred.length != nTotal) {
            throw new IllegalArgumentException(
                    "wavelengthsPred length mismatch: " + wavelengthsPred.length + " != " + nTotal);
        }
        if (wavelengthsRef != null && wavelengthsPred != null && nTotal > 0) {
            double maxWlDiff = 0.0;
            for (int i = 0; i < nTotal; i++) {
                double d = Math.abs(wavelengthsRef[i] - wavelengthsPred[i]);
                if (d > maxWlDiff) {
                    maxWlDiff = d;
                }
            }
            if (maxWlDiff > wavelengthTol) {
                throw new IllegalArgumentException(
                        "wavelength grid mismatch: max |wavelengthsRef - wavelengthsPred| = "
                                + maxWlDiff + " nm exceeds wavelengthTol=" + wavelengthTol + " nm");
            }
        }
        if (variance != null && variance.length != nTotal) {
            throw new IllegalArgumentException(
                    "variance length mismatch: " + variance.length + " != " + nTotal);
        }
        if (mask != null && mask.length != nTotal) {
            throw new IllegalArgumentException(
                    "mask length mismatch: " + mask.length + " != " + nTotal);
        }

        int nValid = 0;
        if (mask == null) {
            nValid = nTotal;
        } else {
            for (boolean b : mask) {
                if (b) {
                    nValid++;
                }
            }
        }
        if (nValid == 0) {
            throw new IllegalArgumentException(
                    "Cannot compute spectral metrics with nValid == 0 (empty input or mask)");
        }

        double[] ref = new double[nValid];
        double[] pred = new double[nValid];
        double[] var = variance != null ? new double[nValid] : null;
        int idx = 0;
        for (int i = 0; i < nTotal; i++) {
            if (mask == null || mask[i]) {
                ref[idx] = yRef[i];
                pred[idx] = yPred[i];
                if (var != null) {
                    var[idx] = variance[i];
                }
                idx++;
            }
        }

        double scaleRef = normalizationScale(ref, normalization, eps);
        double scalePred = normalizationScale(pred, normalization, eps);

        double[] refN = new double[nValid];
        double[] predN = new double[nValid];
        double ssRes = 0.0;
        double sumAbsDiff = 0.0;
        double maxAbsDiff = 0.0;
        double maxAbsRefN = 0.0;
        double sumRefN = 0.0;
        double sumPredN = 0.0;
        double dot = 0.0;
        double normSqRef = 0.0;
        double normSqPred = 0.0;

        for (int i = 0; i < nValid; i++) {
            double r = ref[i] / scaleRef;
            double p = pred[i] / scalePred;
            refN[i] = r;
            predN[i] = p;

            double diff = r - p;
            double absDiff = Math.abs(diff);
            ssRes += diff * diff;
            sumAbsDiff += absDiff;
            if (absDiff > maxAbsDiff) {
                maxAbsDiff = absDiff;
            }
            double absR = Math.abs(r);
            if (absR > maxAbsRefN) {
                maxAbsRefN = absR;
            }
            sumRefN += r;
            sumPredN += p;
            dot += r * p;
            normSqRef += r * r;
            normSqPred += p * p;
        }

        double rmse = Math.sqrt(ssRes / nValid);
        double nrmseMax = rmse / Math.max(maxAbsRefN, eps);
        double mae = sumAbsDiff / nValid;

        double meanRef = sumRefN / nValid;
        double meanPred = sumPredN / nValid;
        double ssTot = 0.0;
        double ssPred = 0.0;
        double covNum = 0.0;
        for (int i = 0; i < nValid; i++) {
            double rc = refN[i] - meanRef;
            double pc = predN[i] - meanPred;
            ssTot += rc * rc;
            ssPred += pc * pc;
            covNum += rc * pc;
        }

        double r2;
        if (ssTot > eps) {
            r2 = 1.0 - (ssRes / ssTot);
        } else {
            r2 = (ssRes < eps) ? 1.0 : 0.0;
        }

        double pearsonR2;
        if (ssTot <= eps || ssPred <= eps) {
            pearsonR2 = (maxAbsDiff <= eps) ? 1.0 : 0.0;
        } else {
            double corr = covNum / Math.sqrt(ssTot * ssPred);
            pearsonR2 = Math.min(Math.max(corr * corr, 0.0), 1.0);
        }

        double normRef = Math.sqrt(normSqRef);
        double normPred = Math.sqrt(normSqPred);
        double cosineSimilarity;
        if (normRef <= eps || normPred <= eps) {
            cosineSimilarity = (maxAbsDiff <= eps) ? 1.0 : 0.0;
        } else {
            cosineSimilarity = dot / (normRef * normPred);
        }

        Double reducedChi2 = null;
        if (var != null) {
            double scaleRefSq = (normalization == NormalizationMode.NONE) ? 1.0 : (scaleRef * scaleRef);
            double chi2Sum = 0.0;
            for (int i = 0; i < nValid; i++) {
                double diff = refN[i] - predN[i];
                double varScaled = var[i] / scaleRefSq;
                double varSafe = Math.max(varScaled, eps);
                chi2Sum += (diff * diff) / varSafe;
            }
            int dof = Math.max(nValid - numParams, 1);
            reducedChi2 = chi2Sum / dof;
        }

        return new SpectralMetrics(
                rmse,
                nrmseMax,
                mae,
                r2,
                pearsonR2,
                cosineSimilarity,
                reducedChi2,
                normalization,
                nValid);
    }

    private static double normalizationScale(double[] values, NormalizationMode mode, double eps) {
        return switch (mode) {
            case NONE -> 1.0;
            case MAX -> {
                double maxAbs = 0.0;
                for (double v : values) {
                    double a = Math.abs(v);
                    if (a > maxAbs) {
                        maxAbs = a;
                    }
                }
                yield Math.max(maxAbs, eps);
            }
            case L2 -> {
                double sumSq = 0.0;
                for (double v : values) {
                    sumSq += v * v;
                }
                yield Math.max(Math.sqrt(sumSq), eps);
            }
            case SUM -> {
                double sumAbs = 0.0;
                for (double v : values) {
                    sumAbs += Math.abs(v);
                }
                yield Math.max(sumAbs, eps);
            }
        };
    }

    /** Calculates canonical coefficient of determination R^2 = 1 - SS_res / SS_tot. */
    public static double calculateR2(double[] yRef, double[] yPred) {
        return calculateR2(yRef, yPred, DEFAULT_METRIC_EPS);
    }

    /** Calculates canonical coefficient of determination R^2 with custom zero-variance guard eps. */
    public static double calculateR2(double[] yRef, double[] yPred, double eps) {
        return computeSpectralMetrics(yRef, yPred, null, NormalizationMode.NONE, eps).r2();
    }

    /** Calculates canonical squared Pearson correlation coefficient r^2 in [0, 1] (never NaN). */
    public static double calculatePearsonR2(double[] yRef, double[] yPred) {
        return calculatePearsonR2(yRef, yPred, DEFAULT_METRIC_EPS);
    }

    /** Calculates canonical squared Pearson correlation coefficient r^2 with custom eps guard. */
    public static double calculatePearsonR2(double[] yRef, double[] yPred, double eps) {
        return computeSpectralMetrics(yRef, yPred, null, NormalizationMode.NONE, eps).pearsonR2();
    }

    /** Calculates canonical Mean Absolute Error (MAE). */
    public static double calculateMAE(double[] yRef, double[] yPred) {
        return computeSpectralMetrics(yRef, yPred).mae();
    }

    /** Calculates canonical cosine similarity (never NaN on zero-norm vectors). */
    public static double calculateCosineSimilarity(double[] yRef, double[] yPred) {
        return calculateCosineSimilarity(yRef, yPred, DEFAULT_METRIC_EPS);
    }

    /** Calculates canonical cosine similarity with custom zero-norm guard eps. */
    public static double calculateCosineSimilarity(double[] yRef, double[] yPred, double eps) {
        return computeSpectralMetrics(yRef, yPred, null, NormalizationMode.NONE, eps).cosineSimilarity();
    }

    /**
     * Calculates Root Mean Square Error (RMSE) between two spectra.
     * Preserves legacy Double.MAX_VALUE return on length mismatch or empty arrays.
     * @param spectrum1 First spectrum
     * @param spectrum2 Second spectrum
     * @return RMSE value
     */
    public double calculateRMSE(double[] spectrum1, double[] spectrum2) {
        if (spectrum1 == null || spectrum2 == null || spectrum1.length != spectrum2.length || spectrum1.length == 0) {
            return Double.MAX_VALUE;
        }
        return computeSpectralMetrics(spectrum1, spectrum2).rmse();
    }

    /**
     * Calculates the square of the Pearson correlation coefficient between two spectra to compare their similarities.
     * Delegates to {@link #calculatePearsonR2(double[], double[])} for canonical zero-variance handling.
     * @param synthetic Array representing synthetic spectrum
     * @param measured Array representing real and measured LIBS spectrum
     * @return r^2
     */
    public double calculateSpectralSimilarity(double[] synthetic, double[] measured) {
        return calculatePearsonR2(measured, synthetic);
    }

    /**
     * Clip spectrum of sharp drop-offs at either ends
     */
    public Spectrum clipSpectrum(double[] wavelengths, double[] intensities) {
        int startindex = 0;
        double lastAvgIntensity = intensities[0];
        double sumIntensity = intensities[0];
        for (int i = 1; i < 100; i++) {
            sumIntensity += intensities[i];
            double diff = Math.abs(sumIntensity/(i+1) - lastAvgIntensity);
            if (diff > 100) {
                startindex = i;
                break;
            }
            lastAvgIntensity = sumIntensity/(i+1);
        }

        int endIndex = wavelengths.length - 1;
        lastAvgIntensity = intensities[wavelengths.length - 1];
        sumIntensity = intensities[wavelengths.length - 1];
        for (int i = wavelengths.length - 2; i > wavelengths.length - 100; i--) {
            sumIntensity += intensities[i];
            int count = wavelengths.length - i;
            double diff = Math.abs(sumIntensity/count - lastAvgIntensity);
            if (diff > 100) {
                endIndex = i;
                break;
            }
            lastAvgIntensity = sumIntensity/count;
        }

        double[] clippedWavelengths = Arrays.copyOfRange(wavelengths, startindex, endIndex);
        double[] clippedIntensities = Arrays.copyOfRange(intensities, startindex, endIndex);

        return new Spectrum(clippedWavelengths, clippedIntensities);
    }
}
