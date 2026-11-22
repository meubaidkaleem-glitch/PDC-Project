package org.districompute.core.algorithm.primes;

import org.districompute.core.execution.CancellationToken;

/**
 * Computes the prime-counting function π(n) with the Lucy–Hedgehog method in O(n<sup>3/4</sup>) time and
 * O(√n) memory. It is algorithmically independent of the sieve, so it serves as the verifier for
 * {@code primes.count}: the count in {@code [a, b]} equals {@code π(b) − π(a − 1)}.
 *
 * <p>Let S(v) be the number of integers in {@code [2, v]} that survive sieving by every prime below the current
 * {@code p}. Processing a prime {@code p} applies {@code S(v) -= S(v / p) − S(p − 1)} to every tracked value
 * {@code v >= p²}. Only the O(√n) distinct values {@code n / i} are tracked.
 */
public final class PrimeCountingFunction {

    private PrimeCountingFunction() {
    }

    /**
     * Returns π(n), the number of primes {@code <= n}.
     *
     * @param n argument, at most about 10<sup>13</sup> for reasonable running time
     * @param cancellation polled once per prime
     * @return π(n), or 0 for {@code n < 2}
     */
    public static long primePi(long n, CancellationToken cancellation) {
        if (n < 2) {
            return 0;
        }
        int root = (int) SegmentedSieve.isqrt(n);
        long[] small = new long[root + 1];
        long[] large = new long[root + 1];
        for (int v = 1; v <= root; v++) {
            small[v] = v - 1L;
            large[v] = n / v - 1;
        }
        for (int p = 2; p <= root; p++) {
            if (small[p] == small[p - 1]) {
                continue;
            }
            cancellation.throwIfCancelled();
            long primesBelow = small[p - 1];
            long square = (long) p * p;
            long limit = Math.min(root, n / square);
            for (int i = 1; i <= limit; i++) {
                long divisor = (long) i * p;
                long survivors = divisor <= root ? large[(int) divisor] : small[(int) (n / divisor)];
                large[i] -= survivors - primesBelow;
            }
            for (int v = root; v >= square; v--) {
                small[v] -= small[v / p] - primesBelow;
            }
        }
        return large[1];
    }
}
