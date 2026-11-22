package org.districompute.core.algorithm.matrix;

import java.util.LinkedHashMap;
import java.util.Map;
import org.districompute.core.algorithm.support.CounterRandom;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.validation.VerificationKind;
import org.districompute.core.validation.VerificationReport;

/**
 * Verifies {@code C = A × B} in O(mk + kn + mn) time with Freivalds' algorithm, using a floating-point error bound
 * instead of exact equality.
 *
 * <p>Each round draws a random sign vector {@code r ∈ {−1, +1}^n} and compares {@code z = C·r} with
 * {@code y = A·(B·r)}. Standard rounding-error analysis gives, with {@code u = 2^−53} and
 * {@code γ_j = j·u / (1 − j·u)}:
 * <pre>
 *   |z − y| ≤ γ_n·(|C|·1) + (2·γ_k + γ_n + γ_k·γ_n)·(|A|·(|B|·1))
 * </pre>
 * The verifier accepts a row if the difference is within twice this bound, a safety factor that also covers
 * rounding in the bound itself. Consequences:
 * <ul>
 *   <li>A correct result always passes, apart from overflow and underflow (inputs are finite and moderate).</li>
 *   <li>A single wrong element whose error exceeds three times the bound for its row is detected with
 *       certainty.</li>
 *   <li>Several wrong elements in one row can cancel for a particular {@code r} with probability at most 1/2 per
 *       round, so {@code rounds} rounds miss them with probability at most 2<sup>−rounds</sup>.</li>
 *   <li>Errors smaller than the rounding bound are, by design, indistinguishable from rounding.</li>
 * </ul>
 */
public final class FreivaldsVerifier {

    private static final double UNIT_ROUNDOFF = 0x1.0p-53;
    private static final double SAFETY_FACTOR = 2.0;

    private FreivaldsVerifier() {
    }

    /**
     * Verifies a product.
     *
     * @param a left operand ({@code m × k})
     * @param b right operand ({@code k × n})
     * @param c claimed product
     * @param rounds number of independent random vectors, at least 1
     * @param seed seed for the random sign vectors
     * @param cancellation polled once per round
     * @return the verification report
     */
    public static VerificationReport verify(Matrix a, Matrix b, Matrix c, int rounds, long seed,
            CancellationToken cancellation) {
        String method = "Freivalds (" + rounds + " rounds, floating-point error bound)";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("rounds", rounds);
        details.put("seed", seed);
        if (a.cols() != b.rows() || c.rows() != a.rows() || c.cols() != b.cols()) {
            details.put("expectedShape", a.rows() + "x" + b.cols());
            details.put("actualShape", c.rows() + "x" + c.cols());
            return VerificationReport.failed(method, VerificationKind.PROBABILISTIC,
                    "Result shape does not match the operands", details);
        }
        int m = a.rows();
        int k = a.cols();
        int n = b.cols();
        double[] rowBound = boundWeights(a, b);
        double[] cAbsRowSums = absRowSums(c);
        double gammaK = gamma(k);
        double gammaN = gamma(n);
        double productFactor = 2 * gammaK + gammaN + gammaK * gammaN;
        double tiny = 4.0 * (k + n) * Double.MIN_NORMAL;
        double worstRatio = 0;
        double[] signs = new double[n];
        for (int round = 0; round < rounds; round++) {
            cancellation.throwIfCancelled();
            for (int j = 0; j < n; j++) {
                signs[j] = (CounterRandom.longAt(seed, round, j) & 1) == 0 ? 1.0 : -1.0;
            }
            double[] y = multiplyVector(a, multiplyVector(b, signs));
            double[] z = multiplyVector(c, signs);
            for (int i = 0; i < m; i++) {
                double tolerance = SAFETY_FACTOR * (gammaN * cAbsRowSums[i] + productFactor * rowBound[i]) + tiny;
                double difference = Math.abs(z[i] - y[i]);
                double ratio = difference / tolerance;
                if (!(difference <= tolerance)) {
                    details.put("failedRound", round);
                    details.put("failedRow", i);
                    details.put("difference", Double.isFinite(difference) ? difference : -1.0);
                    details.put("tolerance", tolerance);
                    return VerificationReport.failed(method, VerificationKind.PROBABILISTIC,
                            "Row " + i + " differs from A(Br) beyond the rounding bound", details);
                }
                worstRatio = Math.max(worstRatio, ratio);
            }
        }
        details.put("maxDifferenceToToleranceRatio", worstRatio);
        details.put("missProbabilityBound", Math.pow(0.5, rounds));
        return VerificationReport.passed(method, VerificationKind.PROBABILISTIC,
                "C*r agrees with A*(B*r) within the floating-point error bound in every round", details);
    }

    /** Computes {@code |A| · (|B| · 1) · 1.01}; the factor covers rounding in the bound computation itself. */
    private static double[] boundWeights(Matrix a, Matrix b) {
        double[] bAbsRowSums = absRowSums(b);
        int m = a.rows();
        int k = a.cols();
        double[] aData = a.data();
        double[] weights = new double[m];
        for (int i = 0; i < m; i++) {
            double sum = 0;
            int row = i * k;
            for (int p = 0; p < k; p++) {
                sum += Math.abs(aData[row + p]) * bAbsRowSums[p];
            }
            weights[i] = sum * 1.01;
        }
        return weights;
    }

    private static double[] absRowSums(Matrix matrix) {
        int rows = matrix.rows();
        int cols = matrix.cols();
        double[] data = matrix.data();
        double[] sums = new double[rows];
        for (int i = 0; i < rows; i++) {
            double sum = 0;
            int row = i * cols;
            for (int j = 0; j < cols; j++) {
                sum += Math.abs(data[row + j]);
            }
            sums[i] = sum * 1.01;
        }
        return sums;
    }

    private static double[] multiplyVector(Matrix matrix, double[] vector) {
        int rows = matrix.rows();
        int cols = matrix.cols();
        double[] data = matrix.data();
        double[] result = new double[rows];
        for (int i = 0; i < rows; i++) {
            double sum = 0;
            int row = i * cols;
            for (int j = 0; j < cols; j++) {
                sum += data[row + j] * vector[j];
            }
            result[i] = sum;
        }
        return result;
    }

    private static double gamma(int j) {
        double ju = j * UNIT_ROUNDOFF;
        return ju / (1 - ju);
    }
}
