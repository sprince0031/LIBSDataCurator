package com.medals.libsdatagenerator.service;

import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.CommonOps_DDRM;
import org.ejml.dense.row.factory.LinearSolverFactory_DDRM;
import org.ejml.interfaces.linsol.LinearSolverDense;

import java.util.logging.Logger;

/**
 * Service to apply baseline correction algorithms to spectra.
 * Implements Asymmetric Least Squares (ALS) smoothing.
 *
 * @author Siddharth Prince, Antigravity | 08/02/26 22:22
 */
public class BaselineCorrectionService {

    private static final Logger logger = Logger.getLogger(BaselineCorrectionService.class.getName());

    // Default parameters for ALS
    private static final double DEFAULT_LAMBDA = 10000; // 10^4 to 10^5 usually good
    private static final double DEFAULT_P = 0.001;
    private static final int MAX_ITERATIONS = 10;

    private static BaselineCorrectionService instance;

    private BaselineCorrectionService() {
    }

    public static synchronized BaselineCorrectionService getInstance() {
        if (instance == null) {
            instance = new BaselineCorrectionService();
        }
        return instance;
    }

    /**
     * Applies baseline correction using Asymmetric Least Squares (ALS).
     * 
     * @param spectrum Intensity values of the spectrum
     * @return Baseline corrected spectrum (original - baseline)
     */
    public double[] correctBaseline(double[] spectrum) {
        return correctBaseline(spectrum, DEFAULT_LAMBDA, DEFAULT_P);
    }

    /**
     * Applies baseline correction using Asymmetric Least Squares (ALS).
     * 
     * @param spectrum Intensity values
     * @param lambda   Smoothness parameter (typically 10^2 to 10^9)
     * @param p        Asymmetry parameter (typically 0.001 to 0.1)
     * @return Baseline corrected spectrum
     */
    public double[] correctBaseline(double[] spectrum, double lambda, double p) {
        logger.info("Correcting baseline of averaged input reference spectrum using Asymmetric Least Squares method...");
        if (spectrum == null || spectrum.length == 0) {
            return new double[0];
        }

        int n = spectrum.length;
        if (n < 3) {
            return spectrum.clone(); // Cannot compute 2nd derivative
        }

        // Create y vector
        DMatrixRMaj y = new DMatrixRMaj(n, 1);
        for (int i = 0; i < n; i++) {
            y.set(i, 0, spectrum[i]);
        }

        // Initialize weights to 1.0 (vector w)
        double[] w = new double[n];
        for (int i = 0; i < n; i++)
            w[i] = 1.0;

        // Construct D (n-2 x n), second difference matrix
        DMatrixRMaj D = new DMatrixRMaj(n - 2, n);
        for (int i = 0; i < n - 2; i++) {
            D.set(i, i, 1);
            D.set(i, i + 1, -2);
            D.set(i, i + 2, 1);
        }

        // H = lambda * D' * D
        DMatrixRMaj Dt = new DMatrixRMaj(n, n - 2);
        CommonOps_DDRM.transpose(D, Dt);

        DMatrixRMaj H = new DMatrixRMaj(n, n);
        CommonOps_DDRM.mult(Dt, D, H);
        CommonOps_DDRM.scale(lambda, H);

        // Pre-allocate matrices
        DMatrixRMaj A = new DMatrixRMaj(n, n);
        DMatrixRMaj Wy = new DMatrixRMaj(n, 1);
        DMatrixRMaj z = new DMatrixRMaj(n, 1); // Baseline

        LinearSolverDense<DMatrixRMaj> solver = LinearSolverFactory_DDRM.chol(n);

        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            logger.info("Iteration #" + iter);
            // A = H + W (diagonal)
            CommonOps_DDRM.extract(H, 0, n, 0, n, A, 0, 0);

            // Add weights to diagonal of A and compute Wy = W * y
            // Note: W is diagonal, so W*y is just element-wise multiplication
            for (int i = 0; i < n; i++) {
                double weight = w[i];
                // A[i][i] += weight
                double currentVal = A.get(i, i);
                A.set(i, i, currentVal + weight);

                // Wy[i] = weight * y[i]
                Wy.set(i, 0, weight * y.get(i, 0));
            }

            // Solve Az = Wy
            if (!solver.setA(A)) {
                logger.warning("Matrix solver failed (singularity?) at iteration " + iter);
                break;
            }
            solver.solve(Wy, z);

            // Update weights
            for (int i = 0; i < n; i++) {
                double observed = y.get(i, 0);
                double baseline = z.get(i, 0);
                if (observed > baseline) {
                    w[i] = p;
                } else {
                    w[i] = 1.0 - p;
                }
            }
        }

        // Compute corrected spectrum: max(0, y - z)
        double[] corrected = new double[n];
        for (int i = 0; i < n; i++) {
            corrected[i] = Math.max(0.0, y.get(i, 0) - z.get(i, 0));
        }

        return corrected;
    }
}
