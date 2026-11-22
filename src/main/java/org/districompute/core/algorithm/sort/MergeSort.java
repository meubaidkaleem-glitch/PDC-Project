package org.districompute.core.algorithm.sort;

import org.districompute.core.execution.CancellationToken;
import org.districompute.core.execution.ProgressReporter;

/**
 * Top-down merge sort for {@code long} arrays.
 *
 * <ul>
 *   <li>It alternates between two buffers ("ping-pong"), so it copies the input once instead of once per
 *       level.</li>
 *   <li>Runs of up to {@value #INSERTION_THRESHOLD} elements are sorted by insertion sort.</li>
 *   <li>It skips the merge when two adjacent runs are already in order. This makes sorted input linear-time.</li>
 *   <li>The sort is stable (irrelevant for primitives, but it keeps merges deterministic).</li>
 * </ul>
 * Progress is reported in "element passes": every element is counted once per merge level.
 */
public final class MergeSort {

    /** Runs of this size or smaller are sorted by insertion sort. */
    public static final int INSERTION_THRESHOLD = 32;

    private static final int CANCELLATION_INTERVAL = 1 << 16;

    private MergeSort() {
    }

    /**
     * Returns a sorted copy and leaves {@code values} unchanged.
     *
     * @param values values to sort
     * @param cancellation polled at least once per {@value #CANCELLATION_INTERVAL} merged elements
     * @param progress advanced by elements merged
     * @return a new, sorted array
     */
    public static long[] sortedCopy(long[] values, CancellationToken cancellation, ProgressReporter progress) {
        long[] result = values.clone();
        sortInPlace(result, cancellation, progress);
        return result;
    }

    /**
     * Sorts an array in place, using one auxiliary array of the same length.
     *
     * @param values values to sort
     * @param cancellation polled periodically
     * @param progress advanced by elements merged
     */
    public static void sortInPlace(long[] values, CancellationToken cancellation, ProgressReporter progress) {
        if (values.length < 2) {
            progress.advance(values.length);
            return;
        }
        long[] auxiliary = values.clone();
        sortInto(auxiliary, values, 0, values.length, cancellation, progress);
    }

    /**
     * Estimated progress units for sorting {@code n} elements: {@code n} times (merge levels + 1).
     *
     * @param n element count
     * @return estimated element passes
     */
    public static long progressUnits(long n) {
        long runs = Math.max(1, (n + INSERTION_THRESHOLD - 1) / INSERTION_THRESHOLD);
        int levels = 64 - Long.numberOfLeadingZeros(runs - 1);
        return n * (levels + 1L);
    }

    /**
     * Sorts {@code source[low, high)} into {@code target[low, high)}. Precondition: both arrays hold the same values
     * in that range. {@code source} is used as scratch space.
     */
    private static void sortInto(long[] source, long[] target, int low, int high, CancellationToken cancellation,
            ProgressReporter progress) {
        int length = high - low;
        if (length <= INSERTION_THRESHOLD) {
            insertionSort(target, low, high);
            progress.advance(length);
            return;
        }
        int middle = (low + high) >>> 1;
        sortInto(target, source, low, middle, cancellation, progress);
        sortInto(target, source, middle, high, cancellation, progress);
        if (source[middle - 1] <= source[middle]) {
            System.arraycopy(source, low, target, low, length);
        } else {
            merge(source, low, middle, high, target, cancellation);
        }
        progress.advance(length);
    }

    private static void merge(long[] source, int low, int middle, int high, long[] target,
            CancellationToken cancellation) {
        int left = low;
        int right = middle;
        int out = low;
        while (left < middle && right < high) {
            if ((out & (CANCELLATION_INTERVAL - 1)) == 0) {
                cancellation.throwIfCancelled();
            }
            target[out++] = source[right] < source[left] ? source[right++] : source[left++];
        }
        System.arraycopy(source, left, target, out, middle - left);
        out += middle - left;
        System.arraycopy(source, right, target, out, high - right);
    }

    private static void insertionSort(long[] values, int low, int high) {
        for (int i = low + 1; i < high; i++) {
            long value = values[i];
            int j = i - 1;
            while (j >= low && values[j] > value) {
                values[j + 1] = values[j];
                j--;
            }
            values[j + 1] = value;
        }
    }
}
