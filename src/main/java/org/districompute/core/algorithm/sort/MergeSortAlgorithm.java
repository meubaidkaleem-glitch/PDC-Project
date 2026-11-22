package org.districompute.core.algorithm.sort;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.districompute.core.algorithm.AlgorithmCategory;
import org.districompute.core.algorithm.AlgorithmDescriptor;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.algorithm.AlgorithmVersion;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.ParameterSpec;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.algorithm.support.Sizes;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.resource.DataDependency;
import org.districompute.core.resource.ParallelismProfile;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.result.DigestBuilder;
import org.districompute.core.result.ResultSummary;
import org.districompute.core.validation.VerificationKind;
import org.districompute.core.validation.VerificationReport;
import org.districompute.core.validation.Violation;

/**
 * {@code sort.merge}: sorts 64-bit signed integers with merge sort. The input is either generated deterministically
 * ({@code input = GENERATED}) or supplied inline ({@code input = INLINE}). Verification checks that the output is
 * in order and is a permutation of the input ({@link MultisetFingerprint}).
 */
public class MergeSortAlgorithm implements ComputeAlgorithm<long[], long[]> {

    /** Algorithm identifier. */
    public static final String ID = "sort.merge";

    /** Largest generated input size. */
    public static final long MAX_SIZE = 100_000_000L;

    /** Largest inline input size. */
    public static final int MAX_INLINE = 1_000_000;

    private static final int PREVIEW = 5;

    private static final AlgorithmDescriptor DESCRIPTOR = new AlgorithmDescriptor(
            AlgorithmId.of(ID), AlgorithmVersion.parse("1.0.0"), "Merge sort",
            "Sorts 64-bit signed integers in ascending order with a top-down merge sort.",
            AlgorithmCategory.SORTING,
            List.of(
                    ParameterSpec.choice("input", "Where the values come from", List.of("GENERATED", "INLINE"))
                            .defaultValue("GENERATED").build(),
                    ParameterSpec.integer("size", "Number of generated values (input = GENERATED)")
                            .range(0, MAX_SIZE).defaultValue(1_000_000).unit("elements").build(),
                    ParameterSpec.choice("distribution", "Distribution of generated values (input = GENERATED)",
                            Arrays.stream(SortDistribution.values()).map(Enum::name).toList())
                            .defaultValue("UNIFORM").build(),
                    ParameterSpec.integer("seed", "Generator seed (input = GENERATED)")
                            .range(0, Long.MAX_VALUE).defaultValue(42).build(),
                    ParameterSpec.integerArray("values", "Values to sort (input = INLINE)")
                            .length(0, MAX_INLINE).build()),
            "long-array",
            "Output is checked to be non-decreasing and to be a permutation of the input "
                    + "(element count and two 64-bit order-independent hash sums)",
            List.of("Elements are 64-bit signed integers only",
                    "Generated inputs are limited to 10^8 elements and inline inputs to 10^6",
                    "size, distribution and seed are ignored for INLINE input",
                    "Needs about 3 x 8 bytes per element (input, output and merge buffer)"));

    @Override
    public AlgorithmDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<Violation> validate(Parameters parameters) {
        boolean inline = "INLINE".equals(parameters.getString("input"));
        if (inline && !parameters.has("values")) {
            return List.of(Violation.ofParameter("values", Violation.Codes.REQUIRED,
                    "is required when input = INLINE"));
        }
        if (!inline && parameters.has("values")) {
            return List.of(Violation.ofParameter("values", Violation.Codes.INCONSISTENT,
                    "is only allowed when input = INLINE"));
        }
        return List.of();
    }

    @Override
    public WorkloadProfile analyze(Parameters parameters) {
        long n = elementCount(parameters);
        double comparisons = n < 2 ? 0 : n * (Math.log(n) / Math.log(2));
        long bytes = Sizes.wordBytes(n);
        return new WorkloadProfile(DESCRIPTOR.ref(), Map.of("size", n), comparisons, "comparisons (estimate)",
                "O(n log n)", bytes, bytes, Sizes.saturatedProduct(bytes, 3), parallelism(n));
    }

    /**
     * Parallelism characteristics for {@code n} elements.
     *
     * @param n element count
     * @return the parallelism profile
     */
    protected ParallelismProfile parallelism(long n) {
        return new ParallelismProfile(false, null, Math.max(1, n / MergeSort.INSERTION_THRESHOLD),
                DataDependency.ORDERED_MERGE, "O(n log k): k-way merge of k sorted runs");
    }

    @Override
    public long[] prepareInput(Parameters parameters, ExecutionContext context) {
        if ("INLINE".equals(parameters.getString("input"))) {
            return parameters.getLongArray("values");
        }
        SortDistribution distribution = parameters.getEnum("distribution", SortDistribution.class);
        return distribution.generate(parameters.getInt("size"), parameters.getLong("seed"), context.cancellation());
    }

    @Override
    public long[] computeSequential(long[] input, ExecutionContext context) {
        context.progress().start(MergeSort.progressUnits(input.length), "element passes");
        return MergeSort.sortedCopy(input, context.cancellation(), context.progress());
    }

    @Override
    public VerificationReport verify(long[] input, long[] result, ExecutionContext context) {
        return verifySorted(input, result);
    }

    /**
     * Verifies that {@code result} is a sorted permutation of {@code input}.
     *
     * @param input original values
     * @param result values to check
     * @return the verification report
     */
    public static VerificationReport verifySorted(long[] input, long[] result) {
        String method = "sortedness + multiset fingerprint";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("inputLength", input.length);
        details.put("outputLength", result.length);
        if (input.length != result.length) {
            return VerificationReport.failed(method, VerificationKind.PROBABILISTIC,
                    "Output length differs from input length", details);
        }
        for (int i = 1; i < result.length; i++) {
            if (result[i - 1] > result[i]) {
                details.put("firstDescentIndex", i);
                return VerificationReport.failed(method, VerificationKind.PROBABILISTIC,
                        "Output is not sorted at index " + i, details);
            }
        }
        boolean permutation = MultisetFingerprint.of(input).equals(MultisetFingerprint.of(result));
        details.put("permutation", permutation);
        return permutation
                ? VerificationReport.passed(method, VerificationKind.PROBABILISTIC,
                        "Output is sorted and is a permutation of the input", details)
                : VerificationReport.failed(method, VerificationKind.PROBABILISTIC,
                        "Output is sorted but is not a permutation of the input", details);
    }

    @Override
    public ResultSummary summarize(long[] result) {
        String digest = new DigestBuilder("long-array").putLong(result.length).putLongs(result, 0, result.length)
                .finish();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("length", result.length);
        if (result.length > 0) {
            values.put("min", result[0]);
            values.put("max", result[result.length - 1]);
            values.put("head", preview(result, 0));
            values.put("tail", preview(result, Math.max(0, result.length - PREVIEW)));
        }
        return new ResultSummary("long-array", digest, values);
    }

    private static List<Long> preview(long[] values, int from) {
        List<Long> preview = new ArrayList<>();
        for (int i = from; i < Math.min(values.length, from + PREVIEW); i++) {
            preview.add(values[i]);
        }
        return preview;
    }

    private static long elementCount(Parameters parameters) {
        return "INLINE".equals(parameters.getString("input")) && parameters.has("values")
                ? parameters.getLongArray("values").length : parameters.getLong("size");
    }
}
