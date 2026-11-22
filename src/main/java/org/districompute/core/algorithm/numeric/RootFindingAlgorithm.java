package org.districompute.core.algorithm.numeric;

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
 * {@code numeric.root-find}: finds the real roots of a polynomial in {@code [lowerLimit, upperLimit]}. The interval
 * is scanned on {@code subintervals} equal pieces, and each sign change is refined by bisection until the bracket is
 * narrower than {@code tolerance}.
 *
 * <p>Root <em>isolation</em> parallelizes: subinterval ranges are independent partitions. The bisection that refines
 * one root is inherently sequential, because each step depends on the previous one, so it is never split. Results
 * are bitwise identical across modes, since each subinterval is processed identically. Verification checks the
 * defining property of every reported root (a sign change within the tolerance, or a value indistinguishable from
 * zero). It cannot prove that no root was missed. See the limitations.
 */
public class RootFindingAlgorithm
        implements PartitionedAlgorithm<RootFindingAlgorithm.Problem, RootFindingAlgorithm.Work,
                RootFindingAlgorithm.Roots, double[]> {

    /** Algorithm identifier. */
    public static final String ID = "numeric.root-find";

    private static final double UNIT_ROUNDOFF = 0x1.0p-53;
    private static final int MAX_BISECTIONS = 200;
    private static final long MIN_PARTITION = 1L << 12;

    private static final AlgorithmDescriptor DESCRIPTOR = new AlgorithmDescriptor(AlgorithmId.of(ID),
            AlgorithmVersion.parse("1.0.0"), "Polynomial root finding (scan + bisection)",
            "Finds the real roots of a polynomial in [lowerLimit, upperLimit] where it changes sign.",
            AlgorithmCategory.NUMERICAL,
            List.of(ParameterSpec.decimalArray("coefficients", "Polynomial coefficients a0, a1, ...")
                            .length(2, RealFunction.MAX_COEFFICIENTS).range(-1e6, 1e6)
                            .defaultValue(List.of(-2.0, 0.0, 1.0)).build(),
                    ParameterSpec.decimal("lowerLimit", "Interval start").range(-1e6, 1e6).defaultValue(-10.0).build(),
                    ParameterSpec.decimal("upperLimit", "Interval end").range(-1e6, 1e6).defaultValue(10.0).build(),
                    ParameterSpec.integer("subintervals", "Scan resolution").range(1, 100_000_000)
                            .defaultValue(100_000).unit("subintervals").build(),
                    ParameterSpec.decimal("tolerance", "Width of the final bracket around each root")
                            .range(1e-15, 1e-3).defaultValue(1e-12).build()),
            "double-array", "Every reported root lies in [lowerLimit, upperLimit], roots are strictly increasing, and "
                    + "the polynomial changes sign within +/- tolerance of each root (or is zero within its rounding "
                    + "bound there)",
            List.of("Roots without a sign change (even multiplicity, e.g. x^2 at 0) are not found",
                    "Two roots closer than one scan subinterval cancel and are not found",
                    "Verification checks reported roots; it cannot prove that none were missed",
                    "Bisection of one root is sequential; only root isolation is parallelized",
                    "Polynomials only (degree 1 to 63)"));

    /**
     * Root-finding problem.
     *
     * @param coefficients coefficients a0, a1, …
     * @param a interval start
     * @param b interval end
     * @param subintervals scan resolution
     * @param tolerance final bracket width
     */
    public record Problem(double[] coefficients, double a, double b, long subintervals, double tolerance) {
        double node(long i) {
            return i == subintervals ? b : a + i * ((b - a) / subintervals);
        }
    }

    /**
     * Work unit: subintervals {@code [first, end)}.
     *
     * @param problem the problem
     * @param first first subinterval
     * @param end last subinterval, exclusive
     */
    public record Work(Problem problem, long first, long end) {
    }

    /**
     * Partial result: roots found in subintervals {@code [first, end)}, ascending.
     *
     * @param first first subinterval
     * @param end last subinterval, exclusive
     * @param roots the roots
     */
    public record Roots(long first, long end, double[] roots) {
    }

    private static final PayloadCodec<Work> WORK_CODEC = NumericPayload.codec("numeric-roots-work/1",
            work -> {
                Problem p = work.problem();
                double[] reals = Arrays.copyOf(new double[] {p.a(), p.b(), p.tolerance()}, 3 + p.coefficients().length);
                System.arraycopy(p.coefficients(), 0, reals, 3, p.coefficients().length);
                return new NumericPayload(new long[] {p.subintervals(), work.first(), work.end()}, reals);
            },
            payload -> {
                double[] r = payload.reals();
                Problem p = new Problem(Arrays.copyOfRange(r, 3, r.length), r[0], r[1], payload.integers()[0], r[2]);
                return new Work(p, payload.integers()[1], payload.integers()[2]);
            });

    private static final PayloadCodec<Roots> PARTIAL_CODEC = NumericPayload.codec("numeric-roots/1",
            roots -> new NumericPayload(new long[] {roots.first(), roots.end()}, roots.roots()),
            payload -> new Roots(payload.integers()[0], payload.integers()[1], payload.reals()));

    @Override
    public AlgorithmDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<Violation> validate(Parameters parameters) {
        if (!(parameters.getDouble("lowerLimit") < parameters.getDouble("upperLimit"))) {
            return List.of(Violation.ofParameter("lowerLimit", Violation.Codes.INCONSISTENT,
                    "must be below upperLimit"));
        }
        double m = Math.max(Math.abs(parameters.getDouble("lowerLimit")), Math.abs(parameters.getDouble("upperLimit")));
        if (!(RealFunction.absPolynomial(parameters.getDoubleArray("coefficients"), m) <= 1e300)) {
            return List.of(Violation.ofParameter("coefficients", Violation.Codes.TOO_LARGE,
                    "the polynomial overflows double precision on this interval"));
        }
        return List.of();
    }

    @Override
    public WorkloadProfile analyze(Parameters parameters) {
        long n = parameters.getLong("subintervals");
        int terms = parameters.getDoubleArray("coefficients").length;
        return new WorkloadProfile(DESCRIPTOR.ref(), Map.of("subintervals", n, "degree", terms - 1L),
                2.0 * terms * (n + 1), "floating-point operations (scan, excluding bisection)", "O(n·d + r·d·log)",
                8L * terms + 32, 8L * terms, 1 << 16, new ParallelismProfile(true, PartitionStrategyType.RANGE,
                        Math.max(1, n / MIN_PARTITION), DataDependency.ITERATIVE,
                        "O(r): concatenate roots; each bisection stays sequential"));
    }

    @Override
    public Problem prepareInput(Parameters parameters, ExecutionContext context) {
        return new Problem(parameters.getDoubleArray("coefficients"), parameters.getDouble("lowerLimit"),
                parameters.getDouble("upperLimit"), parameters.getLong("subintervals"),
                parameters.getDouble("tolerance"));
    }

    @Override
    public double[] computeSequential(Problem input, ExecutionContext context) {
        context.progress().start(input.subintervals(), "subintervals");
        return scan(input, 0, input.subintervals(), context);
    }

    private static double[] scan(Problem problem, long first, long end, ExecutionContext context) {
        double[] c = problem.coefficients();
        List<Double> roots = new ArrayList<>();
        double x0 = problem.node(first);
        double f0 = RealFunction.horner(c, x0);
        for (long i = first; i < end; i++) {
            if (((i - first) & 0xFFF) == 0xFFF) {
                context.cancellation().throwIfCancelled();
                context.progress().advance(1L << 12);
            }
            double x1 = problem.node(i + 1);
            double f1 = RealFunction.horner(c, x1);
            if (f0 == 0) {
                roots.add(x0);
            } else if (f1 != 0 && Math.signum(f0) != Math.signum(f1)) {
                roots.add(bisect(c, x0, f0, x1, problem.tolerance()));
            }
            if (i + 1 == problem.subintervals() && f1 == 0) {
                roots.add(x1);
            }
            x0 = x1;
            f0 = f1;
        }
        context.progress().advance((end - first) & 0xFFF);
        return roots.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static double bisect(double[] c, double low, double fLow, double high, double tolerance) {
        double lo = low;
        double hi = high;
        double fLo = fLow;
        for (int step = 0; step < MAX_BISECTIONS && hi - lo > tolerance; step++) {
            double mid = lo + (hi - lo) / 2;
            if (mid <= lo || mid >= hi) {
                break;
            }
            double fMid = RealFunction.horner(c, mid);
            if (fMid == 0) {
                return mid;
            }
            if (Math.signum(fMid) == Math.signum(fLo)) {
                lo = mid;
                fLo = fMid;
            } else {
                hi = mid;
            }
        }
        return lo + (hi - lo) / 2;
    }

    @Override
    public VerificationReport verify(Problem input, double[] result, ExecutionContext context) {
        double[] c = input.coefficients();
        double gamma = 2.0 * c.length * UNIT_ROUNDOFF;
        String method = "sign-change bracket of every reported root";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("roots", result.length);
        for (int i = 0; i < result.length; i++) {
            double r = result[i];
            if (r < input.a() || r > input.b() || (i > 0 && !(result[i - 1] < r))) {
                details.put("index", i);
                return VerificationReport.failed(method, VerificationKind.STRUCTURAL,
                        "Root " + i + " is outside the interval or out of order", details);
            }
            double delta = input.tolerance() + 4 * Math.ulp(r);
            double value = RealFunction.horner(c, r);
            boolean nearZero = Math.abs(value) <= 2 * gamma * RealFunction.absPolynomial(c, Math.abs(r));
            double left = RealFunction.horner(c, Math.max(input.a(), r - delta));
            double right = RealFunction.horner(c, Math.min(input.b(), r + delta));
            boolean bracketed = left == 0 || right == 0 || Math.signum(left) != Math.signum(right);
            if (!(nearZero || bracketed)) {
                details.put("index", i);
                details.put("root", r);
                details.put("value", value);
                return VerificationReport.failed(method, VerificationKind.STRUCTURAL,
                        "No sign change within the tolerance around root " + i, details);
            }
        }
        return VerificationReport.passed(method, VerificationKind.STRUCTURAL,
                "Every reported root is bracketed by a sign change within the tolerance", details);
    }

    @Override
    public ResultSummary summarize(double[] result) {
        List<Double> preview = new ArrayList<>();
        for (int i = 0; i < Math.min(10, result.length); i++) {
            preview.add(result[i]);
        }
        return new ResultSummary("double-array", new DigestBuilder("double-array").putLong(result.length)
                .putDoubles(result, 0, result.length).finish(), Map.of("roots", result.length, "first", preview));
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
        return List.copyOf(Partitioners.planLinear(0, parameters.getLong("subintervals"), options, defaultPartitions,
                MIN_PARTITION));
    }

    @Override
    public String workUnit() {
        return "subintervals";
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
    public Roots computePartition(Work work, ExecutionContext context) {
        return new Roots(work.first(), work.end(), scan(work.problem(), work.first(), work.end(), context));
    }

    @Override
    public String partialResultType() {
        return "roots";
    }

    @Override
    public Optional<String> partialProblem(PartitionBounds bounds, Roots partial) {
        RangeBounds range = (RangeBounds) bounds;
        if (partial.first() != range.start() || partial.end() != range.endExclusive()) {
            return Optional.of("roots cover subintervals [" + partial.first() + ", " + partial.end()
                    + ") instead of [" + range.start() + ", " + range.endExclusive() + ")");
        }
        double[] roots = partial.roots();
        for (int i = 1; i < roots.length; i++) {
            if (!(roots[i - 1] < roots[i])) {
                return Optional.of("roots are not strictly increasing");
            }
        }
        return Optional.empty();
    }

    @Override
    public double[] aggregate(List<PartitionBounds> plan, List<Roots> partials, ExecutionContext context) {
        return partials.stream().flatMapToDouble(partial -> Arrays.stream(partial.roots())).toArray();
    }

    @Override
    public PayloadCodec<Work> workCodec() {
        return WORK_CODEC;
    }

    @Override
    public PayloadCodec<Roots> partialCodec() {
        return PARTIAL_CODEC;
    }
}
