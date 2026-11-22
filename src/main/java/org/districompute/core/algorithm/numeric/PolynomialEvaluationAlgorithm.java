package org.districompute.core.algorithm.numeric;

import java.math.BigDecimal;
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
 * {@code numeric.polynomial-eval}: evaluates a polynomial at many points with Horner's rule. The points are either a
 * uniform grid over {@code [xMin, xMax]} or supplied inline.
 *
 * <p>Points are independent, so chunks of points are evaluated in parallel and concatenated. Each value is computed
 * by the same operations in every mode, so results are bitwise identical. Verification evaluates a deterministic
 * sample of points exactly with {@link BigDecimal}. Each sample must be within twice Horner's rounding bound
 * {@code γ_2d·Σ|a_i||x|^i}.
 */
public class PolynomialEvaluationAlgorithm
        implements PartitionedAlgorithm<PolynomialEvaluationAlgorithm.Points, PolynomialEvaluationAlgorithm.Points,
                PolynomialEvaluationAlgorithm.Points, double[]> {

    /** Algorithm identifier. */
    public static final String ID = "numeric.polynomial-eval";

    private static final double UNIT_ROUNDOFF = 0x1.0p-53;
    private static final int SAMPLES = 512;
    private static final long MIN_CHUNK = 1L << 14;

    private static final AlgorithmDescriptor DESCRIPTOR = buildDescriptor();

    /**
     * Polynomial and points; also used as work unit (a chunk) and partial result (values of a chunk).
     *
     * @param coefficients coefficients a0, a1, …
     * @param offset index of {@code values[0]} among all points
     * @param values the points (input and work unit) or the evaluated values (partial result)
     */
    public record Points(double[] coefficients, long offset, double[] values) {
    }

    private static final PayloadCodec<Points> CODEC_WORK = codec("numeric-polynomial-points/1");
    private static final PayloadCodec<Points> CODEC_PARTIAL = codec("numeric-polynomial-values/1");

    private static PayloadCodec<Points> codec(String format) {
        return NumericPayload.codec(format,
                points -> new NumericPayload(new long[] {points.offset(), points.coefficients().length},
                        concat(points.coefficients(), points.values())),
                payload -> {
                    int degreePlusOne = Math.toIntExact(payload.integers()[1]);
                    double[] reals = payload.reals();
                    return new Points(Arrays.copyOf(reals, degreePlusOne), payload.integers()[0],
                            Arrays.copyOfRange(reals, degreePlusOne, reals.length));
                });
    }

    private static AlgorithmDescriptor buildDescriptor() {
        List<ParameterSpec> parameters = new ArrayList<>();
        parameters.add(ParameterSpec.decimalArray("coefficients", "Polynomial coefficients a0, a1, ...")
                .length(1, RealFunction.MAX_COEFFICIENTS).range(-1e6, 1e6).defaultValue(List.of(1.0, -3.0, 2.0))
                .build());
        parameters.add(ParameterSpec.choice("input", "Where the points come from", List.of("GENERATED", "INLINE"))
                .defaultValue("GENERATED").build());
        parameters.add(ParameterSpec.integer("points", "Number of grid points (input = GENERATED)")
                .range(1, 100_000_000).defaultValue(1_000_000).unit("points").build());
        parameters.add(ParameterSpec.decimal("xMin", "Grid start (input = GENERATED)").range(-1e6, 1e6)
                .defaultValue(-1.0).build());
        parameters.add(ParameterSpec.decimal("xMax", "Grid end (input = GENERATED)").range(-1e6, 1e6)
                .defaultValue(1.0).build());
        parameters.add(ParameterSpec.decimalArray("xValues", "Points to evaluate (input = INLINE)")
                .length(1, 1_000_000).range(-1e6, 1e6).build());
        return new AlgorithmDescriptor(AlgorithmId.of(ID), AlgorithmVersion.parse("1.0.0"),
                "Polynomial evaluation (Horner)", "Evaluates a polynomial of degree < 64 at many points.",
                AlgorithmCategory.NUMERICAL, parameters, "double-array",
                "512 deterministic sample points are re-evaluated exactly (BigDecimal) and must agree within twice "
                        + "Horner's rounding bound",
                List.of("Points not in the sample are covered by bitwise cross-mode equality, not by exact checks",
                        "Values whose magnitude would exceed 1e300 are rejected during validation"));
    }

    @Override
    public AlgorithmDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<Violation> validate(Parameters parameters) {
        boolean inline = "INLINE".equals(parameters.getString("input"));
        if (inline != parameters.has("xValues")) {
            return List.of(Violation.ofParameter("xValues", inline ? Violation.Codes.REQUIRED
                    : Violation.Codes.INCONSISTENT, inline ? "is required when input = INLINE"
                    : "is only allowed when input = INLINE"));
        }
        double m = inline ? Arrays.stream(parameters.getDoubleArray("xValues")).map(Math::abs).max().orElse(0)
                : Math.max(Math.abs(parameters.getDouble("xMin")), Math.abs(parameters.getDouble("xMax")));
        if (!(RealFunction.absPolynomial(parameters.getDoubleArray("coefficients"), m) <= 1e300)) {
            return List.of(Violation.ofParameter("coefficients", Violation.Codes.TOO_LARGE,
                    "values would overflow double precision on these points"));
        }
        return List.of();
    }

    private static long pointCount(Parameters parameters) {
        return "INLINE".equals(parameters.getString("input")) ? parameters.getDoubleArray("xValues").length
                : parameters.getLong("points");
    }

    @Override
    public WorkloadProfile analyze(Parameters parameters) {
        long n = pointCount(parameters);
        int degree = parameters.getDoubleArray("coefficients").length - 1;
        long bytes = Sizes.wordBytes(n);
        return new WorkloadProfile(DESCRIPTOR.ref(), Map.of("points", n, "degree", (long) degree),
                2.0 * Math.max(1, degree) * n, "floating-point operations", "O(n·d)", bytes, bytes,
                Sizes.saturatedProduct(bytes, 3), new ParallelismProfile(true, PartitionStrategyType.CHUNK,
                        Math.max(1, n / MIN_CHUNK), DataDependency.NONE, "O(n): concatenate value chunks"));
    }

    @Override
    public Points prepareInput(Parameters parameters, ExecutionContext context) {
        double[] coefficients = parameters.getDoubleArray("coefficients");
        if ("INLINE".equals(parameters.getString("input"))) {
            return new Points(coefficients, 0, parameters.getDoubleArray("xValues"));
        }
        int n = parameters.getInt("points");
        double xMin = parameters.getDouble("xMin");
        double xMax = parameters.getDouble("xMax");
        double[] xs = new double[n];
        double step = n == 1 ? 0 : (xMax - xMin) / (n - 1);
        for (int i = 0; i < n; i++) {
            xs[i] = i == n - 1 && n > 1 ? xMax : xMin + i * step;
        }
        return new Points(coefficients, 0, xs);
    }

    @Override
    public double[] computeSequential(Points input, ExecutionContext context) {
        context.progress().start(input.values().length, "points");
        return evaluate(input.coefficients(), input.values(), context);
    }

    private static double[] evaluate(double[] coefficients, double[] xs, ExecutionContext context) {
        double[] values = new double[xs.length];
        for (int i = 0; i < xs.length; i++) {
            if ((i & 0xFFFF) == 0xFFFF) {
                context.cancellation().throwIfCancelled();
                context.progress().advance(1L << 16);
            }
            values[i] = RealFunction.horner(coefficients, xs[i]);
        }
        context.progress().advance(xs.length & 0xFFFF);
        return values;
    }

    @Override
    public VerificationReport verify(Points input, double[] result, ExecutionContext context) {
        String method = "sampled exact evaluation (BigDecimal)";
        Map<String, Object> details = new LinkedHashMap<>();
        double[] xs = input.values();
        if (result.length != xs.length) {
            details.put("expectedLength", xs.length);
            details.put("actualLength", result.length);
            return VerificationReport.failed(method, VerificationKind.PROBABILISTIC, "Wrong number of values",
                    details);
        }
        double[] a = input.coefficients();
        double gamma = 2.0 * a.length * UNIT_ROUNDOFF / (1 - 2.0 * a.length * UNIT_ROUNDOFF);
        int samples = Math.min(SAMPLES, xs.length);
        double worstRatio = 0;
        for (int s = 0; s < samples; s++) {
            context.cancellation().throwIfCancelled();
            int i = samples == 1 ? 0 : (int) ((long) s * (xs.length - 1) / (samples - 1));
            double exact = exactHorner(a, xs[i]);
            double tolerance = 2 * gamma * RealFunction.absPolynomial(a, Math.abs(xs[i])) + Double.MIN_NORMAL;
            double error = Math.abs(result[i] - exact);
            if (!(error <= tolerance)) {
                details.put("index", i);
                details.put("x", xs[i]);
                details.put("expected", exact);
                details.put("actual", Double.isFinite(result[i]) ? result[i] : 0.0);
                return VerificationReport.failed(method, VerificationKind.PROBABILISTIC,
                        "Value at index " + i + " exceeds Horner's rounding bound", details);
            }
            worstRatio = Math.max(worstRatio, error / tolerance);
        }
        details.put("samplesChecked", samples);
        details.put("maxErrorToToleranceRatio", worstRatio);
        return VerificationReport.passed(method, VerificationKind.PROBABILISTIC,
                "All sampled values agree with exact evaluation within the rounding bound", details);
    }

    private static double exactHorner(double[] coefficients, double x) {
        BigDecimal exactX = new BigDecimal(x);
        BigDecimal result = BigDecimal.ZERO;
        for (int i = coefficients.length - 1; i >= 0; i--) {
            result = result.multiply(exactX).add(new BigDecimal(coefficients[i]));
        }
        return result.doubleValue();
    }

    @Override
    public ResultSummary summarize(double[] result) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("length", result.length);
        values.put("first", result.length > 0 ? result[0] : 0.0);
        values.put("last", result.length > 0 ? result[result.length - 1] : 0.0);
        return new ResultSummary("double-array", new DigestBuilder("double-array").putLong(result.length)
                .putDoubles(result, 0, result.length).finish(), values);
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
        return Partitioners.planLinear(0, pointCount(parameters), options, defaultPartitions, MIN_CHUNK).stream()
                .map(range -> (PartitionBounds) new ChunkBounds(range.start(), range.elementCount())).toList();
    }

    @Override
    public String workUnit() {
        return "points";
    }

    @Override
    public long workUnits(PartitionBounds bounds) {
        return bounds.elementCount();
    }

    @Override
    public Points extract(Points input, PartitionBounds bounds) {
        ChunkBounds chunk = (ChunkBounds) bounds;
        return new Points(input.coefficients(), chunk.offset(),
                Arrays.copyOfRange(input.values(), (int) chunk.offset(), (int) chunk.end()));
    }

    @Override
    public Points computePartition(Points work, ExecutionContext context) {
        return new Points(work.coefficients(), work.offset(), evaluate(work.coefficients(), work.values(), context));
    }

    @Override
    public String partialResultType() {
        return "value-chunk";
    }

    @Override
    public Optional<String> partialProblem(PartitionBounds bounds, Points partial) {
        ChunkBounds chunk = (ChunkBounds) bounds;
        return partial.offset() != chunk.offset() || partial.values().length != chunk.length()
                ? Optional.of("value chunk [offset " + partial.offset() + ", length " + partial.values().length
                        + "] does not match partition [offset " + chunk.offset() + ", length " + chunk.length() + "]")
                : Optional.empty();
    }

    @Override
    public double[] aggregate(List<PartitionBounds> plan, List<Points> partials, ExecutionContext context) {
        long total = plan.stream().mapToLong(PartitionBounds::elementCount).sum();
        double[] values = new double[Math.toIntExact(total)];
        for (Points partial : partials) {
            System.arraycopy(partial.values(), 0, values, (int) partial.offset(), partial.values().length);
        }
        return values;
    }

    @Override
    public PayloadCodec<Points> workCodec() {
        return CODEC_WORK;
    }

    @Override
    public PayloadCodec<Points> partialCodec() {
        return CODEC_PARTIAL;
    }

    private static double[] concat(double[] first, double[] second) {
        double[] all = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
