package org.districompute.core.algorithm.support;

/**
 * Counter-based pseudo-random generator for deterministic input generation.
 *
 * <p>{@code longAt(seed, stream, index)} is a pure function, so any element of a generated input can be produced
 * independently on any node, in any order. Generated inputs are therefore reproducible across runs, threads,
 * partitions and machines, and the formula is simple enough to re-implement in another language.
 *
 * <p>Formula, with all arithmetic modulo 2<sup>64</sup> ({@code >>>} is an unsigned shift):
 * <pre>
 *   z = seed + (index + 1) * 0x9E3779B97F4A7C15 + stream * 0xD1B54A32D192ED03
 *   z = (z ^ (z &gt;&gt;&gt; 30)) * 0xBF58476D1CE4E5B9
 *   z = (z ^ (z &gt;&gt;&gt; 27)) * 0x94D049BB133111EB
 *   result = z ^ (z &gt;&gt;&gt; 31)
 * </pre>
 * The mixing function is the well-known SplitMix64 finalizer. This generator is for workloads and tests, never for
 * security.
 */
public final class CounterRandom {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;
    private static final long STREAM_GAMMA = 0xD1B54A32D192ED03L;
    private static final double UNIT = 0x1.0p-53;

    private CounterRandom() {
    }

    /**
     * Returns the 64-bit value at a position.
     *
     * @param seed user seed
     * @param stream independent stream number (for example 1 for matrix A, 2 for matrix B)
     * @param index position within the stream
     * @return a pseudo-random 64-bit value
     */
    public static long longAt(long seed, long stream, long index) {
        long z = seed + (index + 1) * GOLDEN_GAMMA + stream * STREAM_GAMMA;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Returns a double uniformly distributed in {@code [0, 1)}, built from the top 53 bits of {@link #longAt}.
     *
     * @param seed user seed
     * @param stream stream number
     * @param index position
     * @return a value in {@code [0, 1)}
     */
    public static double unitAt(long seed, long stream, long index) {
        return (longAt(seed, stream, index) >>> 11) * UNIT;
    }

    /**
     * Returns a double uniformly distributed in {@code [-1, 1)}.
     *
     * @param seed user seed
     * @param stream stream number
     * @param index position
     * @return a value in {@code [-1, 1)}
     */
    public static double symmetricAt(long seed, long stream, long index) {
        return 2.0 * unitAt(seed, stream, index) - 1.0;
    }

    /**
     * Returns an integer in {@code [0, bound)} (multiply-shift reduction of the top 32 bits; the bias is below
     * {@code bound / 2^32}).
     *
     * @param seed user seed
     * @param stream stream number
     * @param index position
     * @param bound exclusive upper bound, at least 1
     * @return a value in {@code [0, bound)}
     */
    public static int boundedAt(long seed, long stream, long index, int bound) {
        if (bound < 1) {
            throw new IllegalArgumentException("bound must be at least 1");
        }
        long top32 = longAt(seed, stream, index) >>> 32;
        return (int) ((top32 * bound) >>> 32);
    }
}
