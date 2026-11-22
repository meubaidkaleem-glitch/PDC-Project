package org.districompute.core.algorithm.primes;

import java.util.Arrays;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.execution.ProgressReporter;

/**
 * Odd-only segmented sieve of Eratosthenes. It counts primes in an arbitrary range {@code [lower, upper]} using
 * O(√upper) memory for base primes plus one fixed-size segment.
 *
 * <p>Each segment holds {@value #SEGMENT_ODDS} odd candidates (256 KiB, chosen to fit in a typical L2 cache).
 * The sieve keeps the next multiple of every base prime between segments, so it needs no division per prime per
 * segment. Independent sub-ranges can be counted concurrently, because each call allocates its own segment.
 */
public final class SegmentedSieve {

    /** Odd candidates per segment. */
    public static final int SEGMENT_ODDS = 1 << 18;

    private static final long MAX_ISQRT = 3_037_000_499L;

    private SegmentedSieve() {
    }

    /**
     * Integer square root: the largest {@code r} with {@code r * r <= n}.
     *
     * @param n non-negative value
     * @return floor(√n)
     */
    public static long isqrt(long n) {
        if (n < 0) {
            throw new IllegalArgumentException("n must be non-negative");
        }
        long r = (long) Math.sqrt((double) n);
        while (r * r > n) {
            r--;
        }
        while (r + 1 <= MAX_ISQRT && (r + 1) * (r + 1) <= n) {
            r++;
        }
        return r;
    }

    /**
     * Returns the odd primes {@code 3 <= p <= limit}, in ascending order, using a simple odd-only sieve.
     *
     * @param limit inclusive upper bound, at most {@link Integer#MAX_VALUE} - 1
     * @return the odd primes up to {@code limit}
     */
    public static int[] oddPrimesUpTo(long limit) {
        if (limit < 3) {
            return new int[0];
        }
        if (limit >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("limit too large for base primes: " + limit);
        }
        int size = (int) ((limit - 1) / 2) + 1;
        boolean[] composite = new boolean[size];
        composite[0] = true;
        for (int i = 1; ; i++) {
            long p = 2L * i + 1;
            if (p * p > limit) {
                break;
            }
            if (!composite[i]) {
                for (long multiple = p * p; multiple <= limit; multiple += 2 * p) {
                    composite[(int) (multiple >>> 1)] = true;
                }
            }
        }
        int count = 0;
        for (boolean isComposite : composite) {
            if (!isComposite) {
                count++;
            }
        }
        int[] primes = new int[count];
        int next = 0;
        for (int i = 1; i < size; i++) {
            if (!composite[i]) {
                primes[next++] = 2 * i + 1;
            }
        }
        return primes;
    }

    /**
     * Counts the primes in {@code [lower, upper]}.
     *
     * @param lower inclusive lower bound, at least 0
     * @param upper inclusive upper bound
     * @param basePrimes every odd prime up to {@code isqrt(upper)}, ascending (see {@link #oddPrimesUpTo})
     * @param cancellation polled once per segment
     * @param progress advanced by the number of integers covered, once per segment
     * @return the number of primes in the range
     */
    public static long countPrimes(long lower, long upper, int[] basePrimes, CancellationToken cancellation,
            ProgressReporter progress) {
        if (lower < 0 || upper < lower) {
            throw new IllegalArgumentException("Invalid range [" + lower + ", " + upper + "]");
        }
        long count = lower <= 2 && 2 <= upper ? 1 : 0;
        long reportedUpTo = lower - 1;
        long start = Math.max(lower, 3);
        if ((start & 1) == 0) {
            start++;
        }
        if (start <= upper) {
            long[] next = new long[basePrimes.length];
            for (int i = 0; i < basePrimes.length; i++) {
                long p = basePrimes[i];
                long first = Math.max(p * p, Math.ceilDiv(start, p) * p);
                if ((first & 1) == 0) {
                    first += p;
                }
                next[i] = first;
            }
            long totalOdds = (upper - start) / 2 + 1;
            byte[] segment = new byte[(int) Math.min(SEGMENT_ODDS, totalOdds)];
            for (long segmentLow = start; segmentLow <= upper; segmentLow += 2L * SEGMENT_ODDS) {
                cancellation.throwIfCancelled();
                long segmentHigh = Math.min(upper, segmentLow + 2L * (SEGMENT_ODDS - 1));
                int length = (int) ((segmentHigh - segmentLow) / 2) + 1;
                Arrays.fill(segment, 0, length, (byte) 0);
                crossOff(segment, length, segmentLow, segmentHigh, basePrimes, next);
                int composites = 0;
                for (int j = 0; j < length; j++) {
                    composites += segment[j];
                }
                count += length - composites;
                progress.advance(segmentHigh - reportedUpTo);
                reportedUpTo = segmentHigh;
            }
        }
        if (upper > reportedUpTo) {
            progress.advance(upper - reportedUpTo);
        }
        return count;
    }

    private static void crossOff(byte[] segment, int length, long segmentLow, long segmentHigh, int[] basePrimes,
            long[] next) {
        for (int i = 0; i < basePrimes.length; i++) {
            int p = basePrimes[i];
            if ((long) p * p > segmentHigh) {
                break;
            }
            long multiple = next[i];
            if (multiple > segmentHigh) {
                continue;
            }
            int index = (int) ((multiple - segmentLow) >>> 1);
            for (; index < length; index += p) {
                segment[index] = 1;
            }
            next[i] = segmentLow + 2L * index;
        }
    }
}
