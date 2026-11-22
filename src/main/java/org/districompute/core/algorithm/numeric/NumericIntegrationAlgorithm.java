package org.districompute.core.algorithm.numeric;

import java.util.ArrayList;
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
 * {@code numeric.integrate}: definite integral of a catalog function ({@link RealFunction}) over
 * {@code [lowerLimit, upperLimit]} with the composite Simpson rule on {@code panels} equal panels.
 *
 * <p>Panels are independent, so partitions are ranges of panel indices. Their compensated partial sums are combined
 * with Neumaier summation. Results from different partitionings agree within rounding but are not necessarily
 * bitwise identical, because floating-point addition is not associative. Verification compares with the
 * closed-form antiderivative, within the Simpson truncation bound plus a rounding bound.
 */
public class NumericIntegrationAlgorithm
        implements PartitionedAlgorithm<NumericIntegrationAlgorithm.Problem, NumericIntegrationAlgorithm.Work,
                NumericIntegrationAlgorithm.Partial, Double> {

    /** Algorithm identifier. */
    public static final String ID = "numeric.integrate";

    private static final double UNIT_ROUNDOFF = 0x1.0p-53;
    private static final long MIN_PARTITION_PANELS = 1L << 16;

    private static final AlgorithmDescriptor DESCRIPTOR = descriptor(new ArrayList<>(RealFunction.parameters()));

    /**
     * Integration problem.
     *
     * @param function integrand
     * @param a lower limit
     * @param b upper limit
     * @param panels number of Simpson panels
     */
    public record Problem(RealFunction function, double a, double b, long panels) {
        double node(long i) {
            return i == panels ? b : a + i * ((b - a) / panels);
        }
    }

    /**
     * Work unit: panels {@code [first, end)} of the problem.
     *
     * @param problem the problem
     * @param first first panel, inclusive
     * @param end last panel, exclusive
     */
    public record Work(Problem problem, long first, long end) {
    }

    /**
     * Partial result: compensated sum over panels {@code [first, end)}.
     *
     * @param first first panel
     * @param end last panel, exclusive
     * @param sum raw sum
     * @param compensation compensation term
     */
    public record Partial(long first, long end, double sum, double compensation) {
    }

    private static final PayloadCodec<Work> WORK_CODEC = NumericPayload.codec("numeric-integrate-work/1",
            work -> new NumericPayload(new long[] {work.problem().function().kind().ordinal(), work.problem().panels(),
                work.first(), work.end()}, concat(new double[] {work.problem().a(), work.problem().b()},
                        work.problem().function().coefficients())),
            payload -> {
                RealFunction.Kind kind = RealFunction.Kind.values()[(int) payload.integers()[0]];
                double[] reals = payload.reals();
                double[] coefficients = java.util.Arrays.copyOfRange(reals, 2, reals.length);
                Problem problem = new Problem(new RealFunction(kind, kind == RealFunction.Kind.POLYNOMIAL
                        ? coefficients : null), reals[0], reals[1], payload.integers()[1]);
                return new Work(problem, payload.integers()[2], payload.integers()[3]);
            });

    private static final PayloadCodec<Partial> PARTIAL_CODEC = NumericPayload.codec("numeric-integrate-partial/1",
            partial -> new NumericPayload(new long[] {partial.first(), partial.end()},
                    new double[] {partial.sum(), partial.compensation()}),
            payload -> new Partial(payload.integers()[0], payload.integers()[1], payload.reals()[0],
                    payload.reals()[1]));

    private static AlgorithmDescriptor descriptor(List<ParameterSpec> parameters) {
        parameters.add(ParameterSpec.decimal("lowerLimit", "Lower integration limit")
                .range(-1e6, 1e6).defaultValue(0.0).build());
        parameters.add(ParameterSpec.decimal("upperLimit", "Upper integration limit")
                .range(-1e6, 1e6).defaultValue(1.0).build());
        parameters.add(ParameterSpec.integer("panels", "Number of Simpson panels")
                .range(1, 1_000_000_000L).defaultValue(1_000_000).unit("panels").build());
        return new AlgorithmDescriptor(AlgorithmId.of(ID), AlgorithmVersion.parse("1.0.0"),
                "Numerical integration (composite Simpson)",
                "Integrates a catalog function (polynomial, sin, cos, exp) over [lowerLimit, upperLimit].",
                AlgorithmCategory.NUMERICAL, parameters, "scalar",
                "Closed-form antiderivative F(b) - F(a), within the Simpson truncation bound plus a rounding bound",
                List.of("Only catalog functions; arbitrary expressions are not supported",
                        "Simpson's rule is exact only up to cubic polynomials; otherwise error is O(h^4)",
                        "Different partitionings agree within rounding, not bit for bit",
                        "Each interior node is evaluated twice (3 function evaluations per panel)"));
    }

    @Override
    public AlgorithmDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<Violation> validate(Parameters parameters) {
        double a = parameters.getDouble("lowerLimit");
        double b = parameters.getDouble("upperLimit");
        if (!(a < b)) {
            return List.of(Violation.ofParameter("lowerLimit", Violation.Codes.INCONSISTENT,
                    "must be below upperLimit"));
        }
        RealFunction function = RealFunction.from(parameters);
        if (!Double.isFinite(function.maxAbs(a, b)) || !Double.isFinite(function.maxAbsFourthDerivative(a, b))) {
            return List.of(Violation.ofParameter("upperLimit", Violation.Codes.TOO_LARGE,
                    "the function overflows double precision on this interval"));
        }
        return List.of();
    }

    @Override
    public WorkloadProfile analyze(Parameters parameters) {
        long panels = parameters.getLong("panels");
        Map<String, Long> dimensions = new LinkedHashMap<>();
        dimensions.put("panels", panels);
        return new WorkloadProfile(DESCRIPTOR.ref(), dimensions, 3.0 * panels, "function evaluations",
                "O(n)", 16, 8, 4096, new ParallelismProfile(true, PartitionStrategyType.RANGE,
                        Math.max(1, panels / MIN_PARTITION_PANELS), DataDependency.REDUCTION,
                        "O(p): compensated sum of partition sums"));
    }

    @Override
    public Problem prepareInput(Parameters parameters, ExecutionContext context) {
        return new Problem(RealFunction.from(parameters), parameters.getDouble("lowerLimit"),
                parameters.getDouble("upperLimit"), parameters.getLong("panels"));
    }

    @Override
    public Double computeSequential(Problem input, ExecutionContext context) {
        context.progress().start(input.panels(), "panels");
        return integrate(input, 0, input.panels(), context).value();
    }

    private static CompensatedSum integrate(Problem problem, long first, long end, ExecutionContext context) {
        CompensatedSum sum = new CompensatedSum();
        RealFunction f = problem.function();
        for (long i = first; i < end; i++) {
            if (((i - first) & 0xFFFF) == 0xFFFF) {
                context.cancellation().throwIfCancelled();
                context.progress().advance(1L << 16);
            }
            double x0 = problem.node(i);
            double x1 = problem.node(i + 1);
            double mid = x0 + (x1 - x0) / 2;
            sum.add((x1 - x0) / 6 * (f.value(x0) + 4 * f.value(mid) + f.value(x1)));
        }
        context.progress().advance((end - first) & 0xFFFF);
        return sum;
    }

    @Override
    public VerificationReport verify(Problem input, Double result, ExecutionContext context) {
        RealFunction f = input.function();
        double a = input.a();
        double b = input.b();
        double fa = f.antiderivative(a);
        double fb = f.antiderivative(b);
        double exact = fb - fa;
        double h = (b - a) / input.panels();
        double truncation = (b - a) * Math.pow(h, 4) / 2880 * f.maxAbsFourthDerivative(a, b);
        int degree = Math.max(1, f.coefficients().length);
        double evaluation = f.kind() == RealFunction.Kind.POLYNOMIAL
                ? 4.0 * degree * UNIT_ROUNDOFF * RealFunction.absPolynomial(f.coefficients(),
                        Math.max(Math.abs(a), Math.abs(b)))
                : 4.0 * UNIT_ROUNDOFF * f.maxAbs(a, b);
        double rounding = (b - a) * (32 * UNIT_ROUNDOFF * f.maxAbs(a, b) + evaluation)
                + 16 * UNIT_ROUNDOFF * (Math.abs(fa) + Math.abs(fb));
        double tolerance = 2 * (truncation + rounding) + Double.MIN_NORMAL;
        double error = Math.abs(result - exact);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("analytic", exact);
        details.put("absoluteError", error);
        details.put("tolerance", tolerance);
        details.put("truncationBound", truncation);
        String method = "closed-form antiderivative";
        return error <= tolerance
                ? VerificationReport.passed(method, VerificationKind.ANALYTIC_REFERENCE,
                        "Within the Simpson truncation and rounding bound", details)
                : VerificationReport.failed(method, VerificationKind.ANALYTIC_REFERENCE,
                        "Error " + error + " exceeds tolerance " + tolerance, details);
    }

    @Override
    public ResultSummary summarize(Double result) {
        return new ResultSummary("scalar", new DigestBuilder("scalar").putDouble(result).finish(),
                Map.of("value", result));
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
        return List.copyOf(Partitioners.planLinear(0, parameters.getLong("panels"), options, defaultPartitions,
                MIN_PARTITION_PANELS));
    }

    @Override
    public String workUnit() {
        return "panels";
    }

    @Override
    public long workUnits(PartitionBounds bounds) {
        return bounds.elementCount();
    }

    @Override
    public Work extract(Problem input, PartitionBounds bounds) {
        RangeBounds range = (RangeBounds) bounds;
        return new Work(input, range.start(), range.endExclusive());
    }

    @Override
    public Partial computePartition(Work work, ExecutionContext context) {
        CompensatedSum sum = integrate(work.problem(), work.first(), work.end(), context);
        return new Partial(work.first(), work.end(), sum.rawSum(), sum.compensation());
    }

    @Override
    public String partialResultType() {
        return "compensated-sum";
    }

    @Override
    public Optional<String> partialProblem(PartitionBounds bounds, Partial partial) {
        RangeBounds range = (RangeBounds) bounds;
        if (partial.first() != range.start() || partial.end() != range.endExclusive()) {
            return Optional.of("partial sum covers panels [" + partial.first() + ", " + partial.end()
                    + ") instead of [" + range.start() + ", " + range.endExclusive() + ")");
        }
        if (!Double.isFinite(partial.sum()) || !Double.isFinite(partial.compensation())) {
            return Optional.of("partial sum is not finite");
        }
        return Optional.empty();
    }

    @Override
    public Double aggregate(List<PartitionBounds> plan, List<Partial> partials, ExecutionContext context) {
        CompensatedSum total = new CompensatedSum();
        for (Partial partial : partials) {
            total.add(CompensatedSum.of(partial.sum(), partial.compensation()));
        }
        return total.value();
    }

    @Override
    public PayloadCodec<Work> workCodec() {
        return WORK_CODEC;
    }

    @Override
    public PayloadCodec<Partial> partialCodec() {
        return PARTIAL_CODEC;
    }

    private static double[] concat(double[] first, double[] second) {
        double[] all = java.util.Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
