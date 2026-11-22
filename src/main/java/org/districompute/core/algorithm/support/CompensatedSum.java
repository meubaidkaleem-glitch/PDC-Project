package org.districompute.core.algorithm.support;

/**
 * Neumaier (improved Kahan–Babuška) compensated summation. The error is bounded by about
 * {@code 2u · Σ|x_i|}, independent of the number of terms. Partial sums from different partitions can be
 * {@linkplain #add(CompensatedSum) combined}.
 */
public final class CompensatedSum {

    private double sum;
    private double compensation;

    /**
     * Adds a value.
     *
     * @param value value to add
     */
    public void add(double value) {
        double t = sum + value;
        if (Math.abs(sum) >= Math.abs(value)) {
            compensation += (sum - t) + value;
        } else {
            compensation += (value - t) + sum;
        }
        sum = t;
    }

    /**
     * Adds another partial sum, including its compensation.
     *
     * @param other partial sum
     */
    public void add(CompensatedSum other) {
        add(other.sum);
        add(other.compensation);
    }

    /**
     * Running sum without compensation.
     *
     * @return the raw sum
     */
    public double rawSum() {
        return sum;
    }

    /**
     * Accumulated compensation term.
     *
     * @return the compensation
     */
    public double compensation() {
        return compensation;
    }

    /**
     * Compensated result.
     *
     * @return {@code sum + compensation}
     */
    public double value() {
        return sum + compensation;
    }

    /**
     * Rebuilds a partial sum from its two parts (for example after decoding).
     *
     * @param rawSum raw sum
     * @param compensation compensation term
     * @return the partial sum
     */
    public static CompensatedSum of(double rawSum, double compensation) {
        CompensatedSum result = new CompensatedSum();
        result.sum = rawSum;
        result.compensation = compensation;
        return result;
    }
}
