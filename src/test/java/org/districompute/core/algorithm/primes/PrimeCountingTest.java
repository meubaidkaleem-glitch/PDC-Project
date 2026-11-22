package org.districompute.core.algorithm.primes;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.execution.CancellationReason;
import org.districompute.core.execution.CancellationSource;
import org.districompute.core.execution.CancellationToken;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.execution.ProgressReporter;
import org.districompute.core.execution.TaskCancelledException;
import org.districompute.core.validation.ParameterValidator;
import org.districompute.core.validation.Violation;
import org.junit.jupiter.api.Test;

class PrimeCountingTest {

    /** Published values of π(10^k), k = 0..12. */
    private static final long[] PI_POWERS_OF_TEN = {0, 4, 25, 168, 1_229, 9_592, 78_498, 664_579, 5_761_455,
        50_847_534, 455_052_511L, 4_118_054_813L, 37_607_912_018L};

    private static boolean isPrimeByTrialDivision(long n) {
        if (n < 2) {
            return false;
        }
        for (long d = 2; d * d <= n; d++) {
            if (n % d == 0) {
                return false;
            }
        }
        return true;
    }

    private static long sieveCount(long lower, long upper) {
        int[] base = SegmentedSieve.oddPrimesUpTo(SegmentedSieve.isqrt(upper));
        return SegmentedSieve.countPrimes(lower, upper, base, CancellationToken.NONE, ProgressReporter.NONE);
    }

    @Test
    void sieveMatchesTrialDivisionOnEverySmallRange() {
        long[] prefix = new long[302];
        for (int n = 0; n <= 300; n++) {
            prefix[n + 1] = prefix[n] + (isPrimeByTrialDivision(n) ? 1 : 0);
        }
        for (int lower = 0; lower <= 300; lower++) {
            for (int upper = lower; upper <= 300; upper++) {
                assertEquals(prefix[upper + 1] - prefix[lower], sieveCount(lower, upper), "[" + lower + ", "
                        + upper + "]");
            }
        }
    }

    @Test
    void sieveReproducesKnownPrimeCounts() {
        for (int k = 0; k <= 8; k++) {
            long upper = (long) Math.pow(10, k);
            assertEquals(PI_POWERS_OF_TEN[k], sieveCount(0, upper), "pi(10^" + k + ")");
        }
    }

    @Test
    void sieveAgreesWithPrimeCountingFunctionOnRandomRanges() {
        SplittableRandom random = new SplittableRandom(2026);
        for (int trial = 0; trial < 200; trial++) {
            long lower = random.nextLong(0, 3_000_000);
            long upper = lower + random.nextLong(0, 2_000_000);
            long expected = PrimeCountingFunction.primePi(upper, CancellationToken.NONE)
                    - PrimeCountingFunction.primePi(lower - 1, CancellationToken.NONE);
            assertEquals(expected, sieveCount(lower, upper), "[" + lower + ", " + upper + "]");
        }
    }

    @Test
    void sieveSpansManySegmentsWithoutLosingOrDoubleCountingBoundaries() {
        long segmentSpan = 2L * SegmentedSieve.SEGMENT_ODDS;
        long lower = 7 * segmentSpan - 11;
        long upper = 12 * segmentSpan + 13;
        long expected = PrimeCountingFunction.primePi(upper, CancellationToken.NONE)
                - PrimeCountingFunction.primePi(lower - 1, CancellationToken.NONE);
        assertEquals(expected, sieveCount(lower, upper));
    }

    @Test
    void primeCountingFunctionMatchesPublishedValues() {
        for (int k = 0; k <= 11; k++) {
            long n = (long) Math.pow(10, k);
            assertEquals(PI_POWERS_OF_TEN[k], PrimeCountingFunction.primePi(n, CancellationToken.NONE), "pi(10^" + k
                    + ")");
        }
        assertEquals(0, PrimeCountingFunction.primePi(-5, CancellationToken.NONE));
        assertEquals(1, PrimeCountingFunction.primePi(2, CancellationToken.NONE));
    }

    @Test
    void basePrimesAndIntegerSquareRootAreExact() {
        assertArrayEquals(new int[] {3, 5, 7, 11, 13, 17, 19, 23, 29}, SegmentedSieve.oddPrimesUpTo(30));
        assertEquals(0, SegmentedSieve.oddPrimesUpTo(2).length);
        assertEquals(99_998, SegmentedSieve.isqrt(9_999_800_000L), "just below 99999^2");
        assertEquals(99_999, SegmentedSieve.isqrt(9_999_800_001L), "exactly 99999^2");
        assertEquals(3_037_000_499L, SegmentedSieve.isqrt(Long.MAX_VALUE));
        for (long r = 0; r < 2_000; r++) {
            assertEquals(r, SegmentedSieve.isqrt(r * r));
            assertEquals(r, SegmentedSieve.isqrt(r * r + 2 * r));
        }
    }

    @Test
    void progressCoversTheWholeRangeExactlyOnce() {
        AtomicLong reported = new AtomicLong();
        ProgressReporter progress = new ProgressReporter() {
            @Override
            public void start(long totalUnits, String unit) {
            }

            @Override
            public void advance(long units) {
                assertTrue(units >= 0);
                reported.addAndGet(units);
            }
        };
        int[] base = SegmentedSieve.oddPrimesUpTo(SegmentedSieve.isqrt(3_000_000));
        SegmentedSieve.countPrimes(17, 3_000_000, base, CancellationToken.NONE, progress);
        assertEquals(3_000_000 - 17 + 1, reported.get());
    }

    @Test
    void sieveStopsWhenCancelled() {
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel(CancellationReason.USER_REQUESTED);
        int[] base = SegmentedSieve.oddPrimesUpTo(SegmentedSieve.isqrt(10_000_000));
        assertThrows(TaskCancelledException.class,
                () -> SegmentedSieve.countPrimes(0, 10_000_000, base, cancelled, ProgressReporter.NONE));
    }

    @Test
    void algorithmValidatesBoundsAndDetectsWrongCounts() {
        PrimeCountingAlgorithm algorithm = new PrimeCountingAlgorithm();
        Parameters inverted = ParameterValidator.validate(algorithm.descriptor().parameters(),
                Map.of("lowerBound", 10, "upperBound", 5));
        List<Violation> violations = algorithm.validate(inverted);
        assertEquals(Violation.Codes.INCONSISTENT, violations.get(0).code());

        PrimeRange range = new PrimeRange(100, 1_000);
        ExecutionContext context = ExecutionContext.unmanaged();
        long count = algorithm.computeSequential(range, context);
        assertEquals(143, count);
        assertTrue(algorithm.verify(range, count, context).passed());
        assertFalse(algorithm.verify(range, count + 1, context).passed());
        assertEquals(algorithm.summarize(count).digest(), algorithm.summarize(143L).digest());
    }
}
