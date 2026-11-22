package org.districompute.core.algorithm.matrix;

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
import org.districompute.core.algorithm.support.Sizes;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.partition.BlockBounds;
import org.districompute.core.partition.PartitionBounds;
import org.districompute.core.partition.PartitionStrategyType;
import org.districompute.core.partition.Partitioners;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.resource.DataDependency;
import org.districompute.core.resource.ParallelismProfile;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.result.ResultSummary;
import org.districompute.core.serialization.PayloadCodec;
import org.districompute.core.validation.VerificationReport;
import org.districompute.core.validation.Violation;

/**
 * {@code matrix.multiply}: dense product {@code C = A × B} with A of shape {@code rowsA × colsA} and B of shape
 * {@code rowsB × colsB}. The inner dimensions must match ({@code rowsB = colsA}). The result has shape
 * {@code rowsA × colsB}.
 *
 * <p>The kernel is cache-blocked with a configurable tile edge ({@code blockSize}). The per-element accumulation
 * order does not depend on the tile size or the row and column decomposition, so every configuration produces
 * bitwise-identical results. This holds across execution modes too, which allows exact cross-mode comparison.
 * Verification uses {@link FreivaldsVerifier}.
 *
 * <p>Partitioning: 2-D result blocks ({@code blockRows × blockCols}) or row bands (default). A block needs its row
 * band of A and its column band of B, so the distributed communication volume grows with the number of blocks.
 */
public class MatrixMultiplyAlgorithm
        implements PartitionedAlgorithm<MatrixMultiplyInput, MultiplyWork, MatrixBlock, Matrix> {

    /** Algorithm identifier. */
    public static final String ID = "matrix.multiply";

    /** Largest supported dimension. */
    public static final int MAX_DIMENSION = 20_000;

    /** Number of Freivalds rounds used for verification. */
    public static final int VERIFICATION_ROUNDS = 3;

    private static final long VERIFICATION_SEED = 0x5EED_F00DL;

    private static final AlgorithmDescriptor DESCRIPTOR = buildDescriptor();

    private static AlgorithmDescriptor buildDescriptor() {
        List<ParameterSpec> parameters = new ArrayList<>();
        parameters.add(ParameterSpec.integer("rowsA", "Rows of A (and of the result)")
                .range(1, MAX_DIMENSION).defaultValue(512).unit("rows").build());
        parameters.add(ParameterSpec.integer("colsA", "Columns of A; must equal the rows of B")
                .range(1, MAX_DIMENSION).defaultValue(512).unit("columns").build());
        parameters.add(ParameterSpec.integer("rowsB", "Rows of B; required for INLINE input, must equal colsA")
                .range(1, MAX_DIMENSION).unit("rows").build());
        parameters.add(ParameterSpec.integer("colsB", "Columns of B (and of the result)")
                .range(1, MAX_DIMENSION).defaultValue(512).unit("columns").build());
        parameters.add(ParameterSpec.integer("blockSize", "Cache tile edge of the blocked kernel")
                .range(8, 4096).defaultValue(64).unit("elements").build());
        parameters.addAll(MatrixInputs.sourceParameters());
        return new AlgorithmDescriptor(AlgorithmId.of(ID), AlgorithmVersion.parse("1.0.0"),
                "Matrix multiplication (cache-blocked)",
                "Computes C = A x B for dense double matrices with a cache-blocked kernel.",
                AlgorithmCategory.LINEAR_ALGEBRA, parameters, "matrix",
                "Freivalds' algorithm (" + VERIFICATION_ROUNDS + " random sign vectors) with a rigorous "
                        + "floating-point error bound",
                List.of("Dense double-precision matrices only; dimensions up to 20,000",
                        "Each of A, B and C must fit in one Java array",
                        "O(m·k·n) classical algorithm; Strassen-type algorithms are not used",
                        "Verification cannot detect errors smaller than the floating-point rounding bound",
                        "Inline operands are limited to 10^6 elements each"));
    }

    @Override
    public AlgorithmDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<Violation> validate(Parameters parameters) {
        long rowsA = parameters.getLong("rowsA");
        long colsA = parameters.getLong("colsA");
        long colsB = parameters.getLong("colsB");
        List<Violation> violations = new ArrayList<>();
        if (parameters.has("rowsB") && parameters.getLong("rowsB") != colsA) {
            violations.add(Violation.ofParameter("rowsB", Violation.Codes.DIMENSION_MISMATCH,
                    "inner dimensions differ: A is " + rowsA + "x" + colsA + " but B has "
                            + parameters.getLong("rowsB") + " rows"));
        } else if (MatrixInputs.isInline(parameters) && !parameters.has("rowsB")) {
            violations.add(Violation.ofParameter("rowsB", Violation.Codes.REQUIRED,
                    "is required when input = INLINE"));
        }
        if (!violations.isEmpty()) {
            return violations;
        }
        violations.addAll(MatrixInputs.validate(parameters, rowsA, colsA, colsA, colsB));
        MatrixInputs.requireFits(violations, "colsB", rowsA * colsB, rowsA + "x" + colsB);
        return violations;
    }

    @Override
    public WorkloadProfile analyze(Parameters parameters) {
        long m = parameters.getLong("rowsA");
        long k = parameters.getLong("colsA");
        long n = parameters.getLong("colsB");
        long inputBytes = Sizes.saturatedSum(Sizes.wordBytes(m * k), Sizes.wordBytes(k * n));
        long outputBytes = Sizes.wordBytes(m * n);
        long verificationBytes = Sizes.wordBytes(4 * (m + k + n));
        Map<String, Long> dimensions = new LinkedHashMap<>();
        dimensions.put("rowsA", m);
        dimensions.put("colsA", k);
        dimensions.put("colsB", n);
        return new WorkloadProfile(DESCRIPTOR.ref(), dimensions, 2.0 * m * k * n, "floating-point operations",
                "O(m·k·n)", inputBytes, outputBytes,
                Sizes.saturatedSum(inputBytes, outputBytes, outputBytes, verificationBytes), parallelism(m, n));
    }

    /**
     * Parallelism characteristics for an {@code m × n} result.
     *
     * @param m rows of the result
     * @param n columns of the result
     * @return the parallelism profile
     */
    protected ParallelismProfile parallelism(long m, long n) {
        return new ParallelismProfile(true, PartitionStrategyType.BLOCK_2D, Sizes.saturatedProduct(m, n),
                DataDependency.NONE, "O(m·n): copy result blocks into C");
    }

    @Override
    public MatrixMultiplyInput prepareInput(Parameters parameters, ExecutionContext context) {
        int rowsA = parameters.getInt("rowsA");
        int colsA = parameters.getInt("colsA");
        MatrixPair operands = MatrixInputs.prepare(parameters, rowsA, colsA, colsA, parameters.getInt("colsB"),
                context.cancellation());
        return new MatrixMultiplyInput(operands.a(), operands.b(), parameters.getInt("blockSize"));
    }

    @Override
    public Matrix computeSequential(MatrixMultiplyInput input, ExecutionContext context) {
        context.progress().start(input.a().rows(), "rows");
        return MatrixOps.multiply(input.a(), input.b(), input.blockSize(), context.cancellation(),
                context.progress());
    }

    @Override
    public VerificationReport verify(MatrixMultiplyInput input, Matrix result, ExecutionContext context) {
        return FreivaldsVerifier.verify(input.a(), input.b(), result, VERIFICATION_ROUNDS, VERIFICATION_SEED,
                context.cancellation());
    }

    @Override
    public ResultSummary summarize(Matrix result) {
        return MatrixSummaries.summarize(result);
    }

    @Override
    public PartitionStrategyType partitionStrategy() {
        return PartitionStrategyType.BLOCK_2D;
    }

    @Override
    public List<Violation> validatePartitioning(Parameters parameters, PartitioningOptions options) {
        return Partitioners.validateBlocks(options);
    }

    @Override
    public List<PartitionBounds> plan(Parameters parameters, PartitioningOptions options, int defaultPartitions) {
        return List.copyOf(Partitioners.planBlocks(parameters.getInt("rowsA"), parameters.getInt("colsB"), options,
                defaultPartitions));
    }

    @Override
    public String workUnit() {
        return "block rows";
    }

    @Override
    public long workUnits(PartitionBounds bounds) {
        return ((BlockBounds) bounds).rows();
    }

    @Override
    public MultiplyWork extract(MatrixMultiplyInput input, PartitionBounds bounds) {
        BlockBounds block = (BlockBounds) bounds;
        return new MultiplyWork(input.a(), block.rowStart(), input.b(), block.colStart(), block, input.blockSize());
    }

    @Override
    public MatrixBlock computePartition(MultiplyWork work, ExecutionContext context) {
        BlockBounds block = work.block();
        int inner = work.a().cols();
        Matrix out = Matrix.zeros(block.rows(), block.cols());
        MatrixKernels.multiplyAdd(work.a().data(), work.aRowOffset() * inner, inner, work.b().data(),
                work.bColOffset(), work.b().cols(), out.data(), 0, block.cols(), block.rows(), inner, block.cols(),
                work.blockSize(), context.cancellation(), context.progress());
        return new MatrixBlock(block.rowStart(), block.colStart(), out);
    }

    @Override
    public String partialResultType() {
        return "matrix-block";
    }

    @Override
    public Optional<String> partialProblem(PartitionBounds bounds, MatrixBlock partial) {
        return MatrixBlock.problem(bounds, partial);
    }

    @Override
    public Matrix aggregate(List<PartitionBounds> plan, List<MatrixBlock> partials, ExecutionContext context) {
        return MatrixBlock.assemble(plan, partials, context.cancellation());
    }

    @Override
    public PayloadCodec<MultiplyWork> workCodec() {
        return MultiplyWork.CODEC;
    }

    @Override
    public PayloadCodec<MatrixBlock> partialCodec() {
        return MatrixBlock.CODEC;
    }
}
