package org.districompute.core.algorithm.numeric;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.districompute.core.algorithm.AlgorithmCategory;
import org.districompute.core.algorithm.AlgorithmDescriptor;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.algorithm.AlgorithmVersion;
import org.districompute.core.algorithm.ParameterSpec;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.algorithm.PartitionedAlgorithm;
import org.districompute.core.algorithm.support.CounterRandom;
import org.districompute.core.algorithm.support.NumericPayload;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.partition.PartitionBounds;
import org.districompute.core.partition.PartitionStrategyType;
import org.districompute.core.partition.Partitioners;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.partition.RangeBounds;
import org.districompute.core.resource.DataDependency;
import org.districompute.core.resource.ParallelismProfile;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.result.DigestBuilder;
import org.districompute.core.result.ResultSummary;
import org.districompute.core.serialization.PayloadCodec;
import org.districompute.core.validation.VerificationKind;
import org.districompute.core.validation.VerificationReport;
import org.districompute.core.validation.Violation;

/**
 * {@code numeric.monte-carlo}: estimates π by sampling points uniformly in the unit square and counting those inside
 * the quarter circle.
 *
 * <p>Sample {@code i} uses {@code CounterRandom(seed, 1, i)} for x and {@code CounterRandom(seed, 2, i)} for y, so
 * every sample is fixed by its index. The hit count is therefore exactly the same however the samples are
 * partitioned: sequential, multithreaded and distributed runs agree bit for bit. Verification is statistical: the
 * estimate must lie within five standard errors of π.
 */
public class MonteCarloPiAlgorithm
        implements PartitionedAlgorithm<MonteCarloPiAlgorithm.Problem, MonteCarloPiAlgorithm.Work, Long,
                MonteCarloPiAlgorithm.Estimate> {

    /** Algorithm identifier. */
    public static final String ID = "numeric.monte-carlo";

    private static final long MIN_PARTITION_SAMPLES = 1L << 20;
    private static final double Z_LIMIT = 5.0;

    private static final AlgorithmDescriptor DESCRIPTOR = new AlgorithmDescriptor(AlgorithmId.of(ID),
            AlgorithmVersion.parse("1.0.0"), "Monte Carlo estimation of pi",
            "Estimates pi from the fraction of random points in the unit square that fall inside the quarter circle.",
            AlgorithmCategory.NUMERICAL,
            List.of(ParameterSpec.integer("samples", "Number of random points")
                            .range(1, 10_000_000_000L).defaultValue(10_000_000).unit("samples").build(),
                    ParameterSpec.integer("seed", "Generator seed").range(0, Long.MAX_VALUE).defaultValue(42).build()),
            "estimate", "Statistical: |estimate - pi| <= 5 standard errors (false-alarm probability ~6e-7)",
            List.of("Accuracy improves only as 1/sqrt(samples)",
                    "Verification is statistical, not exact; a correct run fails with probability ~6e-7",
                    "Pseudo-random numbers come from a counter-based generator, not a cryptographic one"));

    /**
     * Sampling problem.
     *
     * @param seed generator seed
     * @param samples number of samples
     */
    public record Problem(long seed, long samples) {
    }

    /**
     * Work unit: samples {@code [first, end)}.
     *
     * @param seed generator seed
     * @param first first sample index
     * @param end last sample index, exclusive
     */
    public record Work(long seed, long first, long end) {
    }

    /**
     * Final estimate.
     *
     * @param samples number of samples
     * @param hits samples inside the quarter circle
     */
    public record Estimate(long samples, long hits) {

        /**
         * The estimate of π.
         *
         * @return {@code 4 · hits / samples}
         */
        public double value() {
            return 4.0 * hits / samples;
        }

        /**
         * Standard error of the estimate, using π/4 as the true hit probability.
         *
         * @return the standard error
         */
        public double standardError() {
            double p = Math.PI / 4;
            return 4.0 * Math.sqrt(p * (1 - p) / samples);
        }
    }

    private static final PayloadCodec<Work> WORK_CODEC = NumericPayload.codec("numeric-monte-carlo-work/1",
            work -> new NumericPayload(new long[] {work.seed(), work.first(), work.end()}, new double[0]),
            payload -> new Work(payload.integers()[0], payload.integers()[1], payload.integers()[2]));

    private static final PayloadCodec<Long> PARTIAL_CODEC = NumericPayload.codec("numeric-hit-count/1",
            hits -> new NumericPayload(new long[] {hits}, new double[0]), payload -> payload.integers()[0]);

    @Override
    public AlgorithmDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public WorkloadProfile analyze(Parameters parameters) {
        long samples = parameters.getLong("samples");
        return new WorkloadProfile(DESCRIPTOR.ref(), Map.of("samples", samples), 2.0 * samples,
                "random draws", "O(n)", 16, 16, 4096, new ParallelismProfile(true, PartitionStrategyType.RANGE,
                        Math.max(1, samples / MIN_PARTITION_SAMPLES), DataDependency.REDUCTION,
                        "O(p): sum of hit counts"));
    }

    @Override
    public Problem prepareInput(Parameters parameters, ExecutionContext context) {
        return new Problem(parameters.getLong("seed"), parameters.getLong("samples"));
    }

    @Override
    public Estimate computeSequential(Problem input, ExecutionContext context) {
        context.progress().start(input.samples(), "samples");
        return new Estimate(input.samples(), countHits(input.seed(), 0, input.samples(), context));
    }

    private static long countHits(long seed, long first, long end, ExecutionContext context) {
        long hits = 0;
        for (long i = first; i < end; i++) {
            if (((i - first) & 0xFFFFF) == 0xFFFFF) {
                context.cancellation().throwIfCancelled();
                context.progress().advance(1L << 20);
            }
            double x = CounterRandom.unitAt(seed, 1, i);
            double y = CounterRandom.unitAt(seed, 2, i);
            if (x * x + y * y < 1.0) {
                hits++;
            }
        }
        context.progress().advance((end - first) & 0xFFFFF);
        return hits;
    }

    @Override
    public VerificationReport verify(Problem input, Estimate result, ExecutionContext context) {
        double error = Math.abs(result.value() - Math.PI);
        double z = error / result.standardError();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("estimate", result.value());
        details.put("absoluteError", error);
        details.put("standardError", result.standardError());
        details.put("zScore", z);
        details.put("confidence95Low", result.value() - 1.96 * result.standardError());
        details.put("confidence95High", result.value() + 1.96 * result.standardError());
        String method = "5-sigma statistical check against pi";
        boolean valid = result.samples() == input.samples() && result.hits() >= 0 && result.hits() <= input.samples()
                && z <= Z_LIMIT;
        return valid ? VerificationReport.passed(method, VerificationKind.STATISTICAL,
                        "Estimate within " + Z_LIMIT + " standard errors of pi", details)
                : VerificationReport.failed(method, VerificationKind.STATISTICAL,
                        "Estimate is " + z + " standard errors from pi", details);
    }

    @Override
    public ResultSummary summarize(Estimate result) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("estimate", result.value());
        values.put("hits", result.hits());
        values.put("samples", result.samples());
        values.put("standardError", result.standardError());
        return new ResultSummary("estimate",
                new DigestBuilder("hit-count").putLong(result.samples()).putLong(result.hits()).finish(), values);
    }

    @Override
    public PartitionStrategyType partitionStrategy() {
        return PartitionStrategyType.RANGE;
    }

    @Override
    public List<Violation> validatePartitioning(Parameters parameters, PartitioningOptions options) {
        return Partitioners.validateLinear(options);
    }

    @Override
    public List<PartitionBounds> plan(Parameters parameters, PartitioningOptions options, int defaultPartitions) {
        return List.copyOf(Partitioners.planLinear(0, parameters.getLong("samples"), options, defaultPartitions,
                MIN_PARTITION_SAMPLES));
    }

    @Override
    public String workUnit() {
        return "samples";
    }

    @Override
    public long workUnits(PartitionBounds bounds) {
        return bounds.elementCount();
    }

    @Override
    public Work extract(Problem input, PartitionBounds bounds) {
        RangeBounds range = (RangeBounds) bounds;
        return new Work(input.seed(), range.start(), range.endExclusive());
    }

    @Override
    public Long computePartition(Work work, ExecutionContext context) {
        return countHits(work.seed(), work.first(), work.end(), context);
    }

    @Override
    public String partialResultType() {
        return "hit-count";
    }

    @Override
    public Optional<String> partialProblem(PartitionBounds bounds, Long partial) {
        return partial < 0 || partial > bounds.elementCount()
                ? Optional.of(partial + " hits is impossible for " + bounds.elementCount() + " samples")
                : Optional.empty();
    }

    @Override
    public Estimate aggregate(List<PartitionBounds> plan, List<Long> partials, ExecutionContext context) {
        long samples = 0;
        long hits = 0;
        for (int i = 0; i < plan.size(); i++) {
            samples += plan.get(i).elementCount();
            hits = Math.addExact(hits, partials.get(i));
        }
        return new Estimate(samples, hits);
    }

    @Override
    public PayloadCodec<Work> workCodec() {
        return WORK_CODEC;
    }

    @Override
    public PayloadCodec<Long> partialCodec() {
        return PARTIAL_CODEC;
    }
}
