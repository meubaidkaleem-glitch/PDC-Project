package org.districompute.core.algorithm.sort;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.districompute.core.algorithm.ParameterValues;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MergeSortTest {

    private static long[] sorted(long[] values) {
        return MergeSort.sortedCopy(values, CancellationToken.NONE, ProgressReporter.NONE);
    }

    @Test
    void sortsEverySmallSizeLikeTheJdk() {
        SplittableRandom random = new SplittableRandom(7);
        for (int size = 0; size <= 200; size++) {
            long[] values = random.longs(size, -50, 50).toArray();
            long[] expected = values.clone();
            Arrays.sort(expected);
            long[] original = values.clone();
            assertArrayEquals(expected, sorted(values), "size " + size);
            assertArrayEquals(original, values, "input must not be modified");
        }
    }

    @Test
    void sortsLargeArraysAndExtremeValues() {
        long[] values = new SplittableRandom(11).longs(250_000).toArray();
        values[0] = Long.MIN_VALUE;
        values[1] = Long.MAX_VALUE;
        values[2] = Long.MIN_VALUE;
        long[] expected = values.clone();
        Arrays.sort(expected);
        assertArrayEquals(expected, sorted(values));
    }

    @ParameterizedTest
    @EnumSource(SortDistribution.class)
    void sortsEveryGeneratedDistribution(SortDistribution distribution) {
        long[] values = distribution.generate(100_003, 5, CancellationToken.NONE);
        long[] expected = values.clone();
        Arrays.sort(expected);
        assertArrayEquals(expected, sorted(values));
        assertArrayEquals(values, distribution.generate(100_003, 5, CancellationToken.NONE), "deterministic");
    }

    @Test
    void verificationAcceptsCorrectOutputAndRejectsCorruptions() {
        long[] input = SortDistribution.UNIFORM.generate(10_000, 3, CancellationToken.NONE);
        long[] output = sorted(input);
        assertTrue(MergeSortAlgorithm.verifySorted(input, output).passed());

        long[] unsorted = output.clone();
        long tmp = unsorted[10];
        unsorted[10] = unsorted[11];
        unsorted[11] = tmp;
        assertFalse(MergeSortAlgorithm.verifySorted(input, unsorted).passed());

        long[] replaced = output.clone();
        replaced[5000] = replaced[4999];
        assertFalse(MergeSortAlgorithm.verifySorted(input, replaced).passed(), "sorted but not a permutation");

        assertFalse(MergeSortAlgorithm.verifySorted(input, Arrays.copyOf(output, 9_999)).passed());
    }

    @Test
    void fingerprintsCombineAcrossDisjointParts() {
        long[] values = SortDistribution.FEW_UNIQUE.generate(1_000, 9, CancellationToken.NONE);
        MultisetFingerprint whole = MultisetFingerprint.of(values);
        MultisetFingerprint parts = MultisetFingerprint.of(values, 0, 400)
                .plus(MultisetFingerprint.of(values, 400, 1_000));
        assertEquals(whole, parts);
        assertEquals(whole, MultisetFingerprint.of(sorted(values)));
        assertEquals(MultisetFingerprint.EMPTY, MultisetFingerprint.of(new long[0]));
        assertNotEquals(whole, MultisetFingerprint.of(values, 1, 1_000));
    }

    @Test
    void inlineInputIsValidatedAndUsedVerbatim() {
        MergeSortAlgorithm algorithm = new MergeSortAlgorithm();
        Parameters inline = ParameterValidator.validate(algorithm.descriptor().parameters(),
                ParameterValues.normalizeAll(Map.of("input", "INLINE", "values", List.of(5, -1, 3))));
        assertTrue(algorithm.validate(inline).isEmpty());
        long[] input = algorithm.prepareInput(inline, ExecutionContext.unmanaged());
        assertArrayEquals(new long[] {-1, 3, 5}, algorithm.computeSequential(input, ExecutionContext.unmanaged()));

        Parameters missing = ParameterValidator.validate(algorithm.descriptor().parameters(),
                Map.of("input", "INLINE"));
        assertEquals(Violation.Codes.REQUIRED, algorithm.validate(missing).get(0).code());
        Parameters unexpected = ParameterValidator.validate(algorithm.descriptor().parameters(),
                ParameterValues.normalizeAll(Map.of("values", List.of(1))));
        assertEquals(Violation.Codes.INCONSISTENT, algorithm.validate(unexpected).get(0).code());
    }

    @Test
    void summaryDigestIdentifiesTheExactResult() {
        MergeSortAlgorithm algorithm = new MergeSortAlgorithm();
        long[] values = {3, 1, 2};
        assertEquals(algorithm.summarize(new long[] {1, 2, 3}).digest(), algorithm.summarize(sorted(values)).digest());
        assertNotEquals(algorithm.summarize(new long[] {1, 2, 4}).digest(), algorithm.summarize(sorted(values))
                .digest());
        assertEquals(Map.of("length", 0L), algorithm.summarize(new long[0]).values());
    }

    @Test
    void sortStopsWhenCancelled() {
        CancellationSource cancelled = new CancellationSource();
        cancelled.cancel(CancellationReason.USER_REQUESTED);
        long[] values = SortDistribution.UNIFORM.generate(200_000, 1, CancellationToken.NONE);
        assertThrows(TaskCancelledException.class,
                () -> MergeSort.sortedCopy(values, cancelled, ProgressReporter.NONE));
    }
}
