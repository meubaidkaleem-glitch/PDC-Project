package org.districompute.core.fixtures;

import java.util.List;
import java.util.Map;
import org.districompute.core.algorithm.AlgorithmCategory;
import org.districompute.core.algorithm.AlgorithmDescriptor;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.algorithm.AlgorithmVersion;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.ParameterSpec;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.resource.DataDependency;
import org.districompute.core.resource.ParallelismProfile;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.result.ResultSummary;
import org.districompute.core.validation.VerificationKind;
import org.districompute.core.validation.VerificationReport;

/**
 * Minimal test algorithm: sum of squares 1² + … + n², verified against the closed form n(n+1)(2n+1)/6.
 * Used to exercise registry, validation and engine code independently of the real algorithms.
 */
public class SumOfSquaresAlgorithm implements ComputeAlgorithm<long[], Long> {

    /** Identifier of this fixture. */
    public static final String ID = "test.sum-of-squares";

    private final AlgorithmDescriptor descriptor;

    /** Creates the fixture with version 1.2.0. */
    public SumOfSquaresAlgorithm() {
        this(ID, "1.2.0");
    }

    /**
     * Creates the fixture with a custom id and version.
     *
     * @param id algorithm id
     * @param version algorithm version
     */
    public SumOfSquaresAlgorithm(String id, String version) {
        this.descriptor = new AlgorithmDescriptor(AlgorithmId.of(id), AlgorithmVersion.parse(version),
                "Sum of squares (test fixture)", "Computes 1^2 + ... + n^2.", AlgorithmCategory.NUMERICAL,
                List.of(ParameterSpec.integer("n", "Upper bound").range(1, 1_000_000).defaultValue(10).build()),
                "count", "Closed form n(n+1)(2n+1)/6", List.of("Test fixture only"));
    }

    @Override
    public AlgorithmDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public WorkloadProfile analyze(Parameters parameters) {
        long n = parameters.getLong("n");
        return new WorkloadProfile(descriptor.ref(), Map.of("n", n), n, "multiply-adds", "O(n)", 8 * n, 8, 8 * n,
                ParallelismProfile.sequentialOnly(DataDependency.REDUCTION));
    }

    @Override
    public long[] prepareInput(Parameters parameters, ExecutionContext context) {
        long n = parameters.getLong("n");
        long[] values = new long[(int) n];
        for (int i = 0; i < n; i++) {
            values[i] = i + 1;
        }
        return values;
    }

    @Override
    public Long computeSequential(long[] input, ExecutionContext context) {
        context.progress().start(input.length, "elements");
        long sum = 0;
        for (long value : input) {
            context.cancellation().throwIfCancelled();
            sum += value * value;
            context.progress().advance(1);
        }
        return sum;
    }

    @Override
    public VerificationReport verify(long[] input, Long result, ExecutionContext context) {
        long n = input.length;
        long expected = n * (n + 1) * (2 * n + 1) / 6;
        Map<String, Object> details = Map.of("expected", expected, "actual", result);
        return expected == result
                ? VerificationReport.passed("closed form", VerificationKind.ANALYTIC_REFERENCE, "matches", details)
                : VerificationReport.failed("closed form", VerificationKind.ANALYTIC_REFERENCE, "mismatch", details);
    }

    @Override
    public ResultSummary summarize(Long result) {
        return new ResultSummary("count", "sha256:" + "0".repeat(64), Map.of("value", result));
    }
}
