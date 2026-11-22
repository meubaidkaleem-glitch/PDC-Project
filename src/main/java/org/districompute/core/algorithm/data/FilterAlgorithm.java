package org.districompute.core.algorithm.data;

import java.util.ArrayList;
import java.util.Arrays;
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
import org.districompute.core.algorithm.sort.SortDistribution;
import org.districompute.core.algorithm.support.NumericPayload;
import org.districompute.core.algorithm.support.Sizes;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.partition.ChunkBounds;
import org.districompute.core.partition.PartitionBounds;
import org.districompute.core.partition.PartitionStrategyType;
import org.districompute.core.partition.Partitioners;
import org.districompute.core.partition.PartitioningOptions;
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
 * {@code data.filter}: searches 64-bit integers for values in {@code [minValue, maxValue]}. It returns how many match
 * and the indices of the first {@code limit} matches in input order. This covers filtering (count and selection)
 * and searching (positions of matching values).
 *
 * <p>Chunks are scanned independently. Counts are summed, and index lists are concatenated in chunk order and
 * truncated, so results are exact and bitwise identical in every mode. Verification rescans the input.
 */
public class FilterAlgorithm
        implements PartitionedAlgorithm<FilterAlgorithm.Query, FilterAlgorithm.Query, FilterAlgorithm.Matches,
                FilterAlgorithm.Matches> {

    /** Algorithm identifier. */
    public static final String ID = "data.filter";

    private static final long MIN_CHUNK = 1L << 15;

    private static final AlgorithmDescriptor DESCRIPTOR = buildDescriptor();

    /**
     * A query over values; as work unit, {@code values} is the chunk and {@code offset} its position.
     *
     * @param values values to scan
     * @param offset index of {@code values[0]} in the whole input
     * @param minValue inclusive lower bound
     * @param maxValue inclusive upper bound
     * @param limit maximum number of indices to return
     */
    public record Query(long[] values, long offset, long minValue, long maxValue, int limit) {
    }

    /**
     * Matches found.
     *
     * @param offset start of the scanned range (chunk offset, or 0 for the whole input)
     * @param scanned number of values scanned
     * @param count number of matches
     * @param limit the query's limit on returned indices
     * @param indices indices (in the whole input) of the first matches, at most {@code limit}
     */
    public record Matches(long offset, long scanned, long count, int limit, long[] indices) {
    }

    private static final PayloadCodec<Query> WORK_CODEC = NumericPayload.codec("data-filter-chunk/1",
            q -> {
                long[] ints = Arrays.copyOf(new long[] {q.offset(), q.minValue(), q.maxValue(), q.limit()},
                        4 + q.values().length);
                System.arraycopy(q.values(), 0, ints, 4, q.values().length);
                return new NumericPayload(ints, new double[0]);
            },
            p -> new Query(Arrays.copyOfRange(p.integers(), 4, p.integers().length), p.integers()[0],
                    p.integers()[1], p.integers()[2], Math.toIntExact(p.integers()[3])));

    private static final PayloadCodec<Matches> PARTIAL_CODEC = NumericPayload.codec("data-filter-matches/1",
            m -> {
                long[] ints = Arrays.copyOf(new long[] {m.offset(), m.scanned(), m.count(), m.limit()},
                        4 + m.indices().length);
                System.arraycopy(m.indices(), 0, ints, 4, m.indices().length);
                return new NumericPayload(ints, new double[0]);
            },
            p -> new Matches(p.integers()[0], p.integers()[1], p.integers()[2], Math.toIntExact(p.integers()[3]),
                    Arrays.copyOfRange(p.integers(), 4, p.integers().length)));

    private static AlgorithmDescriptor buildDescriptor() {
        List<ParameterSpec> parameters = new ArrayList<>();
        parameters.add(ParameterSpec.choice("input", "Where the values come from", List.of("GENERATED", "INLINE"))
                .defaultValue("GENERATED").build());
        parameters.add(ParameterSpec.integer("size", "Number of generated values (input = GENERATED)")
                .range(0, 100_000_000).defaultValue(1_000_000).unit("values").build());
        parameters.add(ParameterSpec.choice("distribution", "Distribution of generated values",
                Arrays.stream(SortDistribution.values()).map(Enum::name).toList()).defaultValue("FEW_UNIQUE").build());
        parameters.add(ParameterSpec.integer("seed", "Generator seed").range(0, Long.MAX_VALUE).defaultValue(42)
                .build());
        parameters.add(ParameterSpec.integerArray("values", "Values (input = INLINE)").length(0, 1_000_000).build());
        parameters.add(ParameterSpec.integer("minValue", "Inclusive lower bound of matching values")
                .defaultValue(3).build());
        parameters.add(ParameterSpec.integer("maxValue", "Inclusive upper bound of matching values")
                .defaultValue(5).build());
        parameters.add(ParameterSpec.integer("limit", "Maximum number of matching indices to return")
                .range(0, 10_000).defaultValue(100).build());
        return new AlgorithmDescriptor(AlgorithmId.of(ID), AlgorithmVersion.parse("1.0.0"), "Filter and search",
                "Counts the values in [minValue, maxValue] and returns the indices of the first matches.",
                AlgorithmCategory.DATA_PROCESSING, parameters, "matches",
                "Exact sequential rescan: count and returned indices must match exactly",
                List.of("Predicate is an inclusive value range over 64-bit integers",
                        "At most 10,000 matching indices are returned; the count is always complete"));
    }

    @Override
    public AlgorithmDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<Violation> validate(Parameters parameters) {
        List<Violation> violations = new ArrayList<>();
        boolean inline = "INLINE".equals(parameters.getString("input"));
        if (inline != parameters.has("values")) {
            violations.add(Violation.ofParameter("values", inline ? Violation.Codes.REQUIRED
                    : Violation.Codes.INCONSISTENT, inline ? "is required when input = INLINE"
                    : "is only allowed when input = INLINE"));
        }
        if (parameters.getLong("minValue") > parameters.getLong("maxValue")) {
            violations.add(Violation.ofParameter("minValue", Violation.Codes.INCONSISTENT,
                    "must not exceed maxValue"));
        }
        return violations;
    }

    private static long size(Parameters parameters) {
        return "INLINE".equals(parameters.getString("input")) ? parameters.getLongArray("values").length
                : parameters.getLong("size");
    }

    @Override
    public WorkloadProfile analyze(Parameters parameters) {
        long n = size(parameters);
        long bytes = Sizes.wordBytes(n);
        return new WorkloadProfile(DESCRIPTOR.ref(), Map.of("size", n), 2.0 * n, "comparisons", "O(n)", bytes,
                8L * parameters.getLong("limit") + 16, Sizes.saturatedProduct(bytes, 2), new ParallelismProfile(true,
                        PartitionStrategyType.CHUNK, Math.max(1, n / MIN_CHUNK), DataDependency.ORDERED_MERGE,
                        "O(p·limit): sum counts and concatenate the first indices in chunk order"));
    }

    @Override
    public Query prepareInput(Parameters parameters, ExecutionContext context) {
        long[] values = "INLINE".equals(parameters.getString("input")) ? parameters.getLongArray("values")
                : parameters.getEnum("distribution", SortDistribution.class).generate(parameters.getInt("size"),
                        parameters.getLong("seed"), context.cancellation());
        return new Query(values, 0, parameters.getLong("minValue"), parameters.getLong("maxValue"),
                parameters.getInt("limit"));
    }

    @Override
    public Matches computeSequential(Query input, ExecutionContext context) {
        context.progress().start(input.values().length, "values");
        return scan(input, context);
    }

    private static Matches scan(Query query, ExecutionContext context) {
        long[] values = query.values();
        long count = 0;
        long[] indices = new long[Math.min(query.limit(), values.length)];
        int kept = 0;
        for (int i = 0; i < values.length; i++) {
            if ((i & 0xFFFF) == 0xFFFF) {
                context.cancellation().throwIfCancelled();
                context.progress().advance(1L << 16);
            }
            long v = values[i];
            if (v >= query.minValue() && v <= query.maxValue()) {
                if (kept < indices.length) {
                    indices[kept++] = query.offset() + i;
                }
                count++;
            }
        }
        context.progress().advance(values.length & 0xFFFF);
        return new Matches(query.offset(), values.length, count, query.limit(), Arrays.copyOf(indices, kept));
    }

    @Override
    public VerificationReport verify(Query input, Matches result, ExecutionContext context) {
        Matches expected = scan(input, org.districompute.core.execution.ExecutionContext.of(context.cancellation(),
                org.districompute.core.execution.ProgressReporter.NONE));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("expectedCount", expected.count());
        details.put("actualCount", result.count());
        boolean equal = expected.count() == result.count() && Arrays.equals(expected.indices(), result.indices());
        return equal ? VerificationReport.passed("sequential rescan", VerificationKind.EXACT_RECOMPUTATION,
                        "Count and indices match a sequential rescan", details)
                : VerificationReport.failed("sequential rescan", VerificationKind.EXACT_RECOMPUTATION,
                        "Count or indices differ from a sequential rescan", details);
    }

    @Override
    public ResultSummary summarize(Matches result) {
        List<Long> first = new ArrayList<>();
        for (int i = 0; i < Math.min(10, result.indices().length); i++) {
            first.add(result.indices()[i]);
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", result.count());
        values.put("returnedIndices", result.indices().length);
        values.put("firstIndices", first);
        return new ResultSummary("matches", new DigestBuilder("matches").putLong(result.count())
                .putLong(result.indices().length).putLongs(result.indices(), 0, result.indices().length).finish(),
                values);
    }

    @Override
    public PartitionStrategyType partitionStrategy() {
        return PartitionStrategyType.CHUNK;
    }

    @Override
    public List<Violation> validatePartitioning(Parameters parameters, PartitioningOptions options) {
        return Partitioners.validateLinear(options);
    }

    @Override
    public List<PartitionBounds> plan(Parameters parameters, PartitioningOptions options, int defaultPartitions) {
        return Partitioners.planLinear(0, size(parameters), options, defaultPartitions, MIN_CHUNK).stream()
                .map(range -> (PartitionBounds) new ChunkBounds(range.start(), range.elementCount())).toList();
    }

    @Override
    public String workUnit() {
        return "values";
    }

    @Override
    public long workUnits(PartitionBounds bounds) {
        return bounds.elementCount();
    }

    @Override
    public Query extract(Query input, PartitionBounds bounds) {
        ChunkBounds chunk = (ChunkBounds) bounds;
        return new Query(Arrays.copyOfRange(input.values(), (int) chunk.offset(), (int) chunk.end()), chunk.offset(),
                input.minValue(), input.maxValue(), input.limit());
    }

    @Override
    public Matches computePartition(Query work, ExecutionContext context) {
        return scan(work, context);
    }

    @Override
    public String partialResultType() {
        return "matches";
    }

    @Override
    public Optional<String> partialProblem(PartitionBounds bounds, Matches partial) {
        ChunkBounds chunk = (ChunkBounds) bounds;
        if (partial.offset() != chunk.offset() || partial.scanned() != chunk.length()) {
            return Optional.of("matches cover [offset " + partial.offset() + ", " + partial.scanned()
                    + " values] instead of [offset " + chunk.offset() + ", " + chunk.length() + " values]");
        }
        if (partial.count() < partial.indices().length || partial.count() > chunk.length()
                || partial.indices().length > partial.limit()) {
            return Optional.of("match count " + partial.count() + " is inconsistent");
        }
        for (int i = 0; i < partial.indices().length; i++) {
            long index = partial.indices()[i];
            if (index < chunk.offset() || index >= chunk.end() || (i > 0 && index <= partial.indices()[i - 1])) {
                return Optional.of("match index " + index + " is outside the chunk or out of order");
            }
        }
        return Optional.empty();
    }

    @Override
    public Matches aggregate(List<PartitionBounds> plan, List<Matches> partials, ExecutionContext context) {
        long count = 0;
        long scanned = 0;
        int limit = partials.isEmpty() ? 0 : partials.get(0).limit();
        long[] indices = new long[limit];
        int kept = 0;
        for (Matches partial : partials) {
            if (partial.limit() != limit) {
                throw new org.districompute.core.DistriComputeException(
                        org.districompute.core.ErrorCode.AGGREGATION_FAILED, "Partial results disagree on the limit");
            }
            count += partial.count();
            scanned += partial.scanned();
            int take = Math.min(partial.indices().length, limit - kept);
            System.arraycopy(partial.indices(), 0, indices, kept, take);
            kept += take;
        }
        return new Matches(0, scanned, count, limit, Arrays.copyOf(indices, kept));
    }

    @Override
    public PayloadCodec<Query> workCodec() {
        return WORK_CODEC;
    }

    @Override
    public PayloadCodec<Matches> partialCodec() {
        return PARTIAL_CODEC;
    }
}
