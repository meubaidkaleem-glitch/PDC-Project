package org.districompute.core.algorithm.primes;

/**
 * Inclusive integer interval {@code [lower, upper]} whose primes are counted.
 *
 * @param lower inclusive lower bound, at least 0
 * @param upper inclusive upper bound, at least {@code lower}
 */
public record PrimeRange(long lower, long upper) {

    /** Validates the bounds. */
    public PrimeRange {
        if (lower < 0 || upper < lower) {
            throw new IllegalArgumentException("Invalid prime range [" + lower + ", " + upper + "]");
        }
    }

    /**
     * Number of integers in the range.
     *
     * @return {@code upper - lower + 1}
     */
    public long length() {
        return upper - lower + 1;
    }
}
