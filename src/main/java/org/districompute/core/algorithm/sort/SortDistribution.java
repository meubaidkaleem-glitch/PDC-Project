package org.districompute.core.algorithm.sort;

import org.districompute.core.algorithm.support.CounterRandom;
import org.districompute.core.execution.CancellationToken;

/**
 * Deterministic input distributions for sorting workloads. Generation is O(n) and reproducible from
 * {@code (seed, size)}, because it uses {@link CounterRandom} on stream 1, and stream 2 for swaps.
 */
public enum SortDistribution {
    /** Uniform over the full 64-bit range. */
    UNIFORM,
    /** Strictly increasing values. */
    SORTED,
    /** Strictly decreasing values. */
    REVERSED,
    /** Increasing values with about 1% of positions swapped at random. */
    NEARLY_SORTED,
    /** Only 16 distinct values (many duplicates). */
    FEW_UNIQUE;

    private static final int STEP = 1000;

    /**
     * Generates {@code size} values.
     *
     * @param size number of values
     * @param seed generator seed
     * @param cancellation polled every 2<sup>20</sup> elements
     * @return the generated values
     */
    public long[] generate(int size, long seed, CancellationToken cancellation) {
        long[] values = new long[size];
        for (int i = 0; i < size; i++) {
            if ((i & 0xFFFFF) == 0) {
                cancellation.throwIfCancelled();
            }
            values[i] = switch (this) {
                case UNIFORM -> CounterRandom.longAt(seed, 1, i);
                case SORTED, NEARLY_SORTED -> (long) i * STEP + CounterRandom.boundedAt(seed, 1, i, STEP);
                case REVERSED -> (long) (size - 1 - i) * STEP + CounterRandom.boundedAt(seed, 1, i, STEP);
                case FEW_UNIQUE -> CounterRandom.boundedAt(seed, 1, i, 16);
            };
        }
        if (this == NEARLY_SORTED && size > 1) {
            int swaps = Math.max(1, size / 100);
            for (int s = 0; s < swaps; s++) {
                int a = CounterRandom.boundedAt(seed, 2, 2L * s, size);
                int b = CounterRandom.boundedAt(seed, 2, 2L * s + 1, size);
                long tmp = values[a];
                values[a] = values[b];
                values[b] = tmp;
            }
        }
        return values;
    }
}
