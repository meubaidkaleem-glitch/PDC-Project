package org.districompute.core.algorithm.support;

/**
 * Size arithmetic for memory estimates and array limits. Every product is overflow-checked: an overflow means the
 * request is too large for this platform, never a negative size.
 */
public final class Sizes {

    /** Largest array length the JVM reliably supports (a few header words below {@code Integer.MAX_VALUE}). */
    public static final long MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8L;

    /** Bytes per {@code double} or {@code long}. */
    public static final long BYTES_PER_WORD = 8L;

    private Sizes() {
    }

    /**
     * Multiplies non-negative values, saturating at {@link Long#MAX_VALUE} instead of overflowing.
     *
     * @param factors non-negative factors
     * @return the product, or {@link Long#MAX_VALUE} if it does not fit
     */
    public static long saturatedProduct(long... factors) {
        long product = 1;
        for (long factor : factors) {
            if (factor < 0) {
                throw new IllegalArgumentException("factors must be non-negative");
            }
            try {
                product = Math.multiplyExact(product, factor);
            } catch (ArithmeticException e) {
                return Long.MAX_VALUE;
            }
        }
        return product;
    }

    /**
     * Adds non-negative values, saturating at {@link Long#MAX_VALUE}.
     *
     * @param terms non-negative terms
     * @return the sum, or {@link Long#MAX_VALUE} if it does not fit
     */
    public static long saturatedSum(long... terms) {
        long sum = 0;
        for (long term : terms) {
            if (term < 0) {
                throw new IllegalArgumentException("terms must be non-negative");
            }
            sum += term;
            if (sum < 0) {
                return Long.MAX_VALUE;
            }
        }
        return sum;
    }

    /**
     * Bytes needed for an array of 8-byte words.
     *
     * @param elements element count
     * @return size in bytes, saturated
     */
    public static long wordBytes(long elements) {
        return saturatedProduct(elements, BYTES_PER_WORD);
    }

    /**
     * Whether an array with this many elements can be allocated.
     *
     * @param elements element count
     * @return {@code true} if within {@link #MAX_ARRAY_LENGTH}
     */
    public static boolean fitsInArray(long elements) {
        return elements >= 0 && elements <= MAX_ARRAY_LENGTH;
    }
}
