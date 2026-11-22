package org.districompute.core.algorithm.primes;

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
 * {@code primes.count}: counts the primes in {@code [lowerBound, upperBound]} with an odd-only segmented sieve and
 * verifies the count with the independent prime-counting function π(x) ({@link PrimeCountingFunction}).
 */
public class PrimeCountingAlgorithm implements PartitionedAlgorithm<PrimeRange, PrimeRange, PrimeCount, Long> {

    /** Algorithm identifier. */
    public static final String ID = "primes.count";

    /** Largest supported upper bound (keeps sieve time and the O(x^3/4) verification practical). */
    public static final long MAX_BOUND = 100_000_000_000L;

    /** Smallest default partition, so base-prime setup is amortized over enough sieving work. */
    public static final long MIN_PARTITION_LENGTH = 1L << 20;

    private static final double MERTENS_CONSTANT = 0.2615;

    private static final AlgorithmDescriptor DESCRIPTOR = new AlgorithmDescriptor(
            AlgorithmId.of(ID), AlgorithmVersion.parse("1.0.0"), "Prime counting (segmented sieve)",
            "Counts the prime numbers in the inclusive range [lowerBound, upperBound] using an odd-only segmented "
                    + "sieve of Eratosthenes.",
            AlgorithmCategory.NUMBER_THEORY,
            List.of(
                    ParameterSpec.integer("lowerBound", "Inclusive lower bound of the range")
                            .range(0, MAX_BOUND).defaultValue(0).unit("integer").build(),
                    ParameterSpec.integer("upperBound", "Inclusive upper bound of the range")
                            .range(0, MAX_BOUND).defaultValue(10_000_000).unit("integer").build()),
            "count",
            "Independent prime-counting function (Lucy-Hedgehog, O(x^3/4)): count = pi(upperBound) - "
                    + "pi(lowerBound - 1), compared exactly",
            List.of("upperBound is limited to 10^11",
                    "Cancellation and progress granularity is one segment (2^19 integers)",
                    "Throughput depends on cache size; no performance figures are claimed without measurement"));

    @Override
    public AlgorithmDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<Violation> validate(Parameters parameters) {
        if (parameters.getLong("lowerBound") > parameters.getLong("upperBound")) {
            return List.of(Violation.ofParameter("lowerBound", Violation.Codes.INCONSISTENT,
                    "must not exceed upperBound"));
        }
        return List.of();
    }

    @Override
    public WorkloadProfile analyze(Parameters parameters) {
        long lower = parameters.getLong("lowerBound");
        long upper = parameters.getLong("upperBound");
        long length = upper - lower + 1;
        long root = SegmentedSieve.isqrt(upper);
        double reciprocalSum = root >= 3
                ? Math.max(0, Math.log(Math.log(Math.max(root, 3))) + MERTENS_CONSTANT - 0.5) : 0;
        double marks = length / 2.0 * reciprocalSum;
        long basePrimeBytes = 13L * (root / 2 + 1);
        long segmentBytes = Math.min(SegmentedSieve.SEGMENT_ODDS, length / 2 + 1);
        long verificationBytes = 16L * (SegmentedSieve.isqrt(upper) + 1);
        Map<String, Long> dimensions = new LinkedHashMap<>();
        dimensions.put("lowerBound", lower);
        dimensions.put("upperBound", upper);
        dimensions.put("rangeLength", length);
        return new WorkloadProfile(DESCRIPTOR.ref(), dimensions, marks, "composite marks (estimate)",
                "O(n log log n)", 16, 8, basePrimeBytes + segmentBytes + verificationBytes,
                parallelism(length));
    }

    /**
     * Parallelism characteristics of a range of the given length.
     *
     * @param length number of integers in the range
     * @return the parallelism profile
     */
    protected ParallelismProfile parallelism(long length) {
        return new ParallelismProfile(true, PartitionStrategyType.RANGE,
                Math.max(1, length / MIN_PARTITION_LENGTH), DataDependency.REDUCTION, "O(p): sum of partition counts");
    }

    @Override
    public PrimeRange prepareInput(Parameters parameters, ExecutionContext context) {
        return new PrimeRange(parameters.getLong("lowerBound"), parameters.getLong("upperBound"));
    }

    @Override
    public Long computeSequential(PrimeRange input, ExecutionContext context) {
        context.progress().start(input.length(), "integers");
        int[] basePrimes = SegmentedSieve.oddPrimesUpTo(SegmentedSieve.isqrt(input.upper()));
        return SegmentedSieve.countPrimes(input.lower(), input.upper(), basePrimes, context.cancellation(),
                context.progress());
    }

    @Override
    public VerificationReport verify(PrimeRange input, Long result, ExecutionContext context) {
        long expected = PrimeCountingFunction.primePi(input.upper(), context.cancellation())
                - PrimeCountingFunction.primePi(input.lower() - 1, context.cancellation());
        Map<String, Object> details = Map.of("expected", expected, "actual", result);
        String method = "pi(upper) - pi(lower - 1) via Lucy-Hedgehog";
        return expected == result
                ? VerificationReport.passed(method, VerificationKind.INDEPENDENT_ALGORITHM,
                        "Count matches the independent prime-counting function", details)
                : VerificationReport.failed(method, VerificationKind.INDEPENDENT_ALGORITHM,
                        "Count " + result + " differs from the independent value " + expected, details);
    }

    @Override
    public ResultSummary summarize(Long result) {
        String digest = new DigestBuilder("count").putLong(result).finish();
        return new ResultSummary("count", digest, Map.of("count", result));
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
        return List.copyOf(Partitioners.planLinear(parameters.getLong("lowerBound"),
                parameters.getLong("upperBound") + 1, options, defaultPartitions, MIN_PARTITION_LENGTH));
    }

    @Override
    public String workUnit() {
        return "integers";
    }

    @Override
    public long workUnits(PartitionBounds bounds) {
        return bounds.elementCount();
    }

    @Override
    public PrimeRange extract(PrimeRange input, PartitionBounds bounds) {
        RangeBounds range = (RangeBounds) bounds;
        return new PrimeRange(range.start(), range.endExclusive() - 1);
    }

    @Override
    public PrimeCount computePartition(PrimeRange work, ExecutionContext context) {
        int[] basePrimes = SegmentedSieve.oddPrimesUpTo(SegmentedSieve.isqrt(work.upper()));
        long count = SegmentedSieve.countPrimes(work.lower(), work.upper(), basePrimes, context.cancellation(),
                context.progress());
        return new PrimeCount(work.lower(), work.upper(), count);
    }

    @Override
    public String partialResultType() {
        return "prime-count";
    }

    @Override
    public Optional<String> partialProblem(PartitionBounds bounds, PrimeCount partial) {
        if (!(bounds instanceof RangeBounds range)) {
            return Optional.of("prime partitions need RANGE bounds");
        }
        if (partial.lower() != range.start() || partial.upper() != range.endExclusive() - 1) {
            return Optional.of("count is for [" + partial.lower() + ", " + partial.upper() + "] but the partition is ["
                    + range.start() + ", " + (range.endExclusive() - 1) + "]");
        }
        long maxPossible = range.elementCount() / 2 + 2;
        if (partial.count() < 0 || partial.count() > maxPossible) {
            return Optional.of("count " + partial.count() + " is impossible for " + range.elementCount()
                    + " integers");
        }
        return Optional.empty();
    }

    @Override
    public Long aggregate(List<PartitionBounds> plan, List<PrimeCount> partials, ExecutionContext context) {
        long total = 0;
        for (PrimeCount partial : partials) {
            total = Math.addExact(total, partial.count());
        }
        return total;
    }

    @Override
    public PayloadCodec<PrimeRange> workCodec() {
        return PrimeCount.RANGE_CODEC;
    }

    @Override
    public PayloadCodec<PrimeCount> partialCodec() {
        return PrimeCount.CODEC;
    }
}
