package org.districompute.core.algorithm.sort;

import org.districompute.core.algorithm.support.CounterRandom;

/**
 * Order-independent fingerprint of a multiset of {@code long} values: the element count plus two sums of
 * independent 64-bit hashes (modulo 2<sup>64</sup>). Two arrays are permutations of each other only if their
 * fingerprints are equal. A non-permutation passes only if both hash sums collide.
 *
 * <p>Fingerprints of disjoint parts can be {@linkplain #plus combined}, so partitioned sorts can be checked
 * piece by piece.
 *
 * @param count number of elements
 * @param hashSum1 sum of the first hash over all elements
 * @param hashSum2 sum of the second hash over all elements
 */
public record MultisetFingerprint(long count, long hashSum1, long hashSum2) {

    private static final long SEED_1 = 0x6A09E667F3BCC908L;
    private static final long SEED_2 = 0xBB67AE8584CAA73BL;

    /** Fingerprint of the empty multiset. */
    public static final MultisetFingerprint EMPTY = new MultisetFingerprint(0, 0, 0);

    /**
     * Fingerprints a range of an array.
     *
     * @param values array
     * @param from first index, inclusive
     * @param to last index, exclusive
     * @return the fingerprint
     */
    public static MultisetFingerprint of(long[] values, int from, int to) {
        long sum1 = 0;
        long sum2 = 0;
        for (int i = from; i < to; i++) {
            sum1 += CounterRandom.longAt(SEED_1, 1, values[i]);
            sum2 += CounterRandom.longAt(SEED_2, 2, values[i]);
        }
        return new MultisetFingerprint(to - from, sum1, sum2);
    }

    /**
     * Fingerprints a whole array.
     *
     * @param values array
     * @return the fingerprint
     */
    public static MultisetFingerprint of(long[] values) {
        return of(values, 0, values.length);
    }

    /**
     * Combines the fingerprints of two disjoint multisets.
     *
     * @param other fingerprint of the other part
     * @return fingerprint of the union
     */
    public MultisetFingerprint plus(MultisetFingerprint other) {
        return new MultisetFingerprint(count + other.count, hashSum1 + other.hashSum1, hashSum2 + other.hashSum2);
    }
}
