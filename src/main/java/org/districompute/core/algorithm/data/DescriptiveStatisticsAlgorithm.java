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
import org.districompute.core.algorithm.support.CompensatedSum;
import org.districompute.core.algorithm.support.CounterRandom;
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
 * {@code data.statistics}: count, sum, mean, sample variance, standard deviation, minimum and maximum of a data set.
 *
 * <p>Each chunk is summarized in one numerically stable pass (Welford's update). Chunk summaries are merged with the
 * parallel formula of Chan, Golub and LeVeque:
 * {@code M2 = M2a + M2b + δ²·na·nb/n}. Count, minimum and maximum are exact. Mean and variance from different
 * partitionings agree within rounding. Verification recomputes everything with an independent two-pass algorithm.
 */
public class DescriptiveStatisticsAlgorithm
        implements PartitionedAlgorithm<double[], DescriptiveStatisticsAlgorithm.Chunk,
                DescriptiveStatisticsAlgorithm.Summary, DescriptiveStatisticsAlgorithm.Summary> {

    /** Algorithm identifier. */
    public static final String ID = "data.statistics";

    private static final long MIN_CHUNK = 1L << 15;
    private static final double RELATIVE_TOLERANCE = 1e-9;

    private static final AlgorithmDescriptor DESCRIPTOR = buildDescriptor();

    /**
     * Work unit: {@code values[from, to)}, located at {@code offset} in the data set.
     *
     * @param values source array
     * @param from first index
     * @param to last index, exclusive
     * @param offset position of {@code values[from]} in the data set
     */
    public record Chunk(double[] values, int from, int to, long offset) {
    }

    /**
     * Mergeable summary.
     *
     * @param offset position of the summarized chunk (0 for whole data sets)
     * @param count number of values
     * @param mean arithmetic mean
     * @param m2 sum of squared deviations from the mean
     * @param min minimum
     * @param max maximum
     * @param sum compensated sum (raw part)
     * @param sumCompensation compensated sum (compensation part)
     */
    public record Summary(long offset, long count, double mean, double m2, double min, double max, double sum,
            double sumCompensation) {

        /**
         * Sample variance ({@code n − 1} denominator), or 0 for fewer than two values.
         *
         * @return the variance
         */
        public double variance() {
            return count < 2 ? 0 : m2 / (count - 1);
        }

        Summary merge(Summary other) {
            if (other.count == 0) {
                return this;
            }
            long n = count + other.count;
            double delta = other.mean - mean;
            double mergedMean = mean + delta * other.count / n;
            double mergedM2 = m2 + other.m2 + delta * delta * ((double) count * other.count / n);
            CompensatedSum total = CompensatedSum.of(sum, sumCompensation);
            total.add(CompensatedSum.of(other.sum, other.sumCompensation));
            return new Summary(offset, n, mergedMean, mergedM2, Math.min(min, other.min), Math.max(max, other.max),
                    total.rawSum(), total.compensation());
        }
    }

    private static final PayloadCodec<Chunk> WORK_CODEC = NumericPayload.codec("data-statistics-chunk/1",
            chunk -> new NumericPayload(new long[] {chunk.offset()},
                    Arrays.copyOfRange(chunk.values(), chunk.from(), chunk.to())),
            payload -> new Chunk(payload.reals(), 0, payload.reals().length, payload.integers()[0]));

    private static final PayloadCodec<Summary> PARTIAL_CODEC = NumericPayload.codec("data-statistics-summary/1",
            s -> new NumericPayload(new long[] {s.offset(), s.count()},
                    new double[] {s.mean(), s.m2(), s.min(), s.max(), s.sum(), s.sumCompensation()}),
            p -> new Summary(p.integers()[0], p.integers()[1], p.reals()[0], p.reals()[1], p.reals()[2],
                    p.reals()[3], p.reals()[4], p.reals()[5]));

    private static AlgorithmDescriptor buildDescriptor() {
        List<ParameterSpec> parameters = new ArrayList<>();
        parameters.add(ParameterSpec.choice("input", "Where the values come from", List.of("GENERATED", "INLINE"))
                .defaultValue("GENERATED").build());
        parameters.add(ParameterSpec.integer("size", "Number of generated values (input = GENERATED)")
                .range(1, 100_000_000).defaultValue(1_000_000).unit("values").build());
        parameters.add(ParameterSpec.choice("distribution", "Distribution of generated values",
                List.of("UNIFORM", "NORMAL")).defaultValue("NORMAL").build());
        parameters.add(ParameterSpec.integer("seed", "Generator seed").range(0, Long.MAX_VALUE).defaultValue(42)
                .build());
        parameters.add(ParameterSpec.decimalArray("values", "Values (input = INLINE)").length(1, 1_000_000)
                .range(-1e150, 1e150).build());
        return new AlgorithmDescriptor(AlgorithmId.of(ID), AlgorithmVersion.parse("1.0.0"),
                "Descriptive statistics", "Count, sum, mean, sample variance, standard deviation, min and max.",
                AlgorithmCategory.DATA_PROCESSING, parameters, "statistics",
                "Independent two-pass recomputation; count, min and max exact, sum, mean and variance within a "
                        + "relative tolerance of 1e-9 of the data scale",
                List.of("Statistics of an empty data set are undefined; at least one value is required",
                        "Mean and variance agree across partitionings within rounding, not bit for bit",
                        "The 1e-9 relative verification tolerance is deliberately generous, not a proven bound",
                        "NORMAL values use the Box-Muller transform of the counter-based generator"));
    }

    @Override
    public AlgorithmDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<Violation> validate(Parameters parameters) {
        boolean inline = "INLINE".equals(parameters.getString("input"));
        if (inline != parameters.has("values")) {
            return List.of(Violation.ofParameter("values", inline ? Violation.Codes.REQUIRED
                    : Violation.Codes.INCONSISTENT, inline ? "is required when input = INLINE"
                    : "is only allowed when input = INLINE"));
        }
        return List.of();
    }

    private static long size(Parameters parameters) {
        return "INLINE".equals(parameters.getString("input")) ? parameters.getDoubleArray("values").length
                : parameters.getLong("size");
    }

    @Override
    public WorkloadProfile analyze(Parameters parameters) {
        long n = size(parameters);
        long bytes = Sizes.wordBytes(n);
        return new WorkloadProfile(DESCRIPTOR.ref(), Map.of("size", n), 8.0 * n, "floating-point operations",
                "O(n)", bytes, 64, Sizes.saturatedProduct(bytes, 2), new ParallelismProfile(true,
                        PartitionStrategyType.CHUNK, Math.max(1, n / MIN_CHUNK), DataDependency.REDUCTION,
                        "O(p): pairwise merge of chunk summaries"));
    }

    @Override
    public double[] prepareInput(Parameters parameters, ExecutionContext context) {
        if ("INLINE".equals(parameters.getString("input"))) {
            return parameters.getDoubleArray("values");
        }
        int n = parameters.getInt("size");
        long seed = parameters.getLong("seed");
        boolean normal = "NORMAL".equals(parameters.getString("distribution"));
        double[] values = new double[n];
        for (int i = 0; i < n; i++) {
            if (normal) {
                double u1 = 1.0 - CounterRandom.unitAt(seed, 1, i);
                double u2 = CounterRandom.unitAt(seed, 2, i);
                values[i] = Math.sqrt(-2 * Math.log(u1)) * Math.cos(2 * Math.PI * u2);
            } else {
                values[i] = CounterRandom.unitAt(seed, 1, i);
            }
        }
        return values;
    }

    @Override
    public Summary computeSequential(double[] input, ExecutionContext context) {
        context.progress().start(input.length, "values");
        return summarize(input, 0, input.length, 0, context);
    }

    private static Summary summarize(double[] values, int from, int to, long offset, ExecutionContext context) {
        long n = 0;
        double mean = 0;
        double m2 = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        CompensatedSum sum = new CompensatedSum();
        for (int i = from; i < to; i++) {
            if (((i - from) & 0xFFFF) == 0xFFFF) {
                context.cancellation().throwIfCancelled();
                context.progress().advance(1L << 16);
            }
            double x = values[i];
            n++;
            double delta = x - mean;
            mean += delta / n;
            m2 += delta * (x - mean);
            min = Math.min(min, x);
            max = Math.max(max, x);
            sum.add(x);
        }
        context.progress().advance((to - from) & 0xFFFF);
        return new Summary(offset, n, mean, m2, min, max, sum.rawSum(), sum.compensation());
    }

    @Override
    public VerificationReport verify(double[] input, Summary result, ExecutionContext context) {
        CompensatedSum sum = new CompensatedSum();
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double scale = 0;
        for (double x : input) {
            sum.add(x);
            min = Math.min(min, x);
            max = Math.max(max, x);
            scale = Math.max(scale, Math.abs(x));
        }
        double mean = sum.value() / input.length;
        CompensatedSum squares = new CompensatedSum();
        for (double x : input) {
            squares.add((x - mean) * (x - mean));
        }
        double variance = input.length < 2 ? 0 : squares.value() / (input.length - 1);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("twoPassMean", mean);
        details.put("twoPassVariance", variance);
        String method = "independent two-pass recomputation";
        boolean exactParts = result.count() == input.length && result.min() == min && result.max() == max;
        boolean sumOk = Math.abs(sum.value() - (result.sum() + result.sumCompensation()))
                <= RELATIVE_TOLERANCE * scale * Math.max(1, Math.sqrt(input.length));
        boolean meanOk = Math.abs(result.mean() - mean) <= RELATIVE_TOLERANCE * (Math.abs(mean) + scale);
        boolean varianceOk = Math.abs(result.variance() - variance)
                <= RELATIVE_TOLERANCE * (variance + scale * scale * 1e-6);
        if (exactParts && sumOk && meanOk && varianceOk) {
            return VerificationReport.passed(method, VerificationKind.INDEPENDENT_ALGORITHM,
                    "Count, min and max exact; sum, mean and variance within tolerance", details);
        }
        details.put("countMinMaxExact", exactParts);
        details.put("sumWithinTolerance", sumOk);
        details.put("meanWithinTolerance", meanOk);
        details.put("varianceWithinTolerance", varianceOk);
        return VerificationReport.failed(method, VerificationKind.INDEPENDENT_ALGORITHM,
                "Statistics disagree with the independent two-pass computation", details);
    }

    @Override
    public ResultSummary summarize(Summary result) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("count", result.count());
        values.put("sum", result.sum() + result.sumCompensation());
        values.put("mean", result.mean());
        values.put("variance", result.variance());
        values.put("standardDeviation", Math.sqrt(result.variance()));
        values.put("min", result.min());
        values.put("max", result.max());
        return new ResultSummary("statistics", new DigestBuilder("statistics").putLong(result.count())
                .putDouble(result.mean()).putDouble(result.m2()).putDouble(result.min()).putDouble(result.max())
                .finish(), values);
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
    public Chunk extract(double[] input, PartitionBounds bounds) {
        ChunkBounds chunk = (ChunkBounds) bounds;
        return new Chunk(input, (int) chunk.offset(), (int) chunk.end(), chunk.offset());
    }

    @Override
    public Summary computePartition(Chunk work, ExecutionContext context) {
        return summarize(work.values(), work.from(), work.to(), work.offset(), context);
    }

    @Override
    public String partialResultType() {
        return "statistics-summary";
    }

    @Override
    public Optional<String> partialProblem(PartitionBounds bounds, Summary partial) {
        ChunkBounds chunk = (ChunkBounds) bounds;
        if (partial.offset() != chunk.offset() || partial.count() != chunk.length()) {
            return Optional.of("summary of [offset " + partial.offset() + ", count " + partial.count()
                    + "] does not match chunk [offset " + chunk.offset() + ", length " + chunk.length() + "]");
        }
        if (partial.count() > 0 && (!(partial.min() <= partial.mean() + Math.ulp(partial.mean()) * 4)
                || !(partial.mean() <= partial.max() + Math.ulp(partial.mean()) * 4) || !(partial.m2() >= 0))) {
            return Optional.of("summary is internally inconsistent (mean outside [min, max] or negative M2)");
        }
        return Optional.empty();
    }

    @Override
    public Summary aggregate(List<PartitionBounds> plan, List<Summary> partials, ExecutionContext context) {
        Summary total = new Summary(0, 0, 0, 0, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0, 0);
        for (Summary partial : partials) {
            total = total.count() == 0 ? new Summary(0, partial.count(), partial.mean(), partial.m2(), partial.min(),
                    partial.max(), partial.sum(), partial.sumCompensation()) : total.merge(partial);
        }
        return total;
    }

    @Override
    public PayloadCodec<Chunk> workCodec() {
        return WORK_CODEC;
    }

    @Override
    public PayloadCodec<Summary> partialCodec() {
        return PARTIAL_CODEC;
    }
}
