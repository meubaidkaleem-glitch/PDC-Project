package org.districompute.core.metrics;

import java.util.Arrays;

/**
 * Summary statistics of repeated measurements. The median is the primary measure, because it is robust to
 * occasional outliers such as a GC pause or a noisy neighbour. The standard deviation is the sample standard
 * deviation ({@code n − 1}), or 0 for a single sample.
 *
 * @param count number of samples
 * @param min smallest sample
 * @param max largest sample
 * @param mean arithmetic mean
 * @param median middle sample (mean of the two middle samples for an even count)
 * @param standardDeviation sample standard deviation
 */
public record SampleStatistics(int count, double min, double max, double mean, double median,
        double standardDeviation) {

    /**
     * Computes the statistics of a non-empty sample.
     *
     * @param samples measurements
     * @return the statistics
     * @throws IllegalArgumentException for an empty sample or a non-finite value
     */
    public static SampleStatistics of(double[] samples) {
        if (samples.length == 0) {
            throw new IllegalArgumentException("At least one sample is required");
        }
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        double sum = 0;
        for (double value : sorted) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Samples must be finite");
            }
            sum += value;
        }
        int n = sorted.length;
        double mean = sum / n;
        double squares = 0;
        for (double value : sorted) {
            squares += (value - mean) * (value - mean);
        }
        double median = n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
        double deviation = n < 2 ? 0 : Math.sqrt(squares / (n - 1));
        return new SampleStatistics(n, sorted[0], sorted[n - 1], mean, median, deviation);
    }

    /**
     * Computes the statistics of integer samples, for example nanoseconds.
     *
     * @param samples measurements
     * @return the statistics
     */
    public static SampleStatistics of(long[] samples) {
        return of(Arrays.stream(samples).asDoubleStream().toArray());
    }

    /**
     * Relative dispersion: standard deviation divided by the mean.
     *
     * @return the coefficient of variation, or 0 if the mean is 0
     */
    public double coefficientOfVariation() {
        return mean == 0 ? 0 : standardDeviation / mean;
    }
}
