package org.districompute.core.algorithm.matrix;

import java.util.LinkedHashMap;
import java.util.Map;
import org.districompute.core.result.ResultSummary;

/** Builds the serializable summary of a matrix result. */
public final class MatrixSummaries {

    private MatrixSummaries() {
    }

    /**
     * Summarizes a matrix: its shape, the element sum (Neumaier-compensated), the largest absolute element and the
     * canonical digest.
     *
     * @param matrix the result
     * @return the summary
     */
    public static ResultSummary summarize(Matrix matrix) {
        double sum = 0;
        double compensation = 0;
        double maxAbs = 0;
        for (double value : matrix.data()) {
            double t = sum + value;
            compensation += Math.abs(sum) >= Math.abs(value) ? (sum - t) + value : (value - t) + sum;
            sum = t;
            maxAbs = Math.max(maxAbs, Math.abs(value));
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rows", matrix.rows());
        values.put("cols", matrix.cols());
        values.put("sum", sum + compensation);
        values.put("maxAbs", maxAbs);
        return new ResultSummary("matrix", matrix.digest(), values);
    }
}
