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
import org.districompute.core.validation.VerificationKind;
import org.districompute.core.validation.VerificationReport;
import org.districompute.core.validation.Violation;

/**
 * {@code matrix.add}: element-wise sum of two equally shaped matrices. The workload is memory-bound (one addition
 * per 24 bytes moved), so parallel speedup is limited by memory bandwidth. Verification recomputes every element
 * exactly.
 */
public class MatrixAddAlgorithm implements PartitionedAlgorithm<MatrixPair, AddWork, MatrixBlock, Matrix> {

    /** Algorithm identifier. */
    public static final String ID = "matrix.add";

    /** Largest supported dimension. */
    public static final int MAX_DIMENSION = 100_000;

    private static final AlgorithmDescriptor DESCRIPTOR = buildDescriptor();

    private static AlgorithmDescriptor buildDescriptor() {
        List<ParameterSpec> parameters = new ArrayList<>();
        parameters.add(ParameterSpec.integer("rows", "Rows of A, B and the result")
                .range(1, MAX_DIMENSION).defaultValue(1024).unit("rows").build());
        parameters.add(ParameterSpec.integer("cols", "Columns of A, B and the result")
                .range(1, MAX_DIMENSION).defaultValue(1024).unit("columns").build());
        parameters.addAll(MatrixInputs.sourceParameters());
        return new AlgorithmDescriptor(AlgorithmId.of(ID), AlgorithmVersion.parse("1.0.0"), "Matrix addition",
                "Computes C = A + B for two dense rows x cols matrices of doubles.", AlgorithmCategory.LINEAR_ALGEBRA,
                parameters, "matrix", "Exact recomputation of every element, compared bit for bit",
                List.of("Dense double-precision matrices only",
                        "rows x cols must fit in one Java array (about 2.1 billion elements)",
                        "Memory-bound: speedup is limited by memory bandwidth, not core count",
                        "Inline operands are limited to 10^6 elements each"));
    }

    @Override
    public AlgorithmDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<Violation> validate(Parameters parameters) {
        long rows = parameters.getLong("rows");
        long cols = parameters.getLong("cols");
        return MatrixInputs.validate(parameters, rows, cols, rows, cols);
    }

    @Override
    public WorkloadProfile analyze(Parameters parameters) {
        long rows = parameters.getLong("rows");
        long cols = parameters.getLong("cols");
        long elements = rows * cols;
        long matrixBytes = Sizes.wordBytes(elements);
        Map<String, Long> dimensions = new LinkedHashMap<>();
        dimensions.put("rows", rows);
        dimensions.put("cols", cols);
        return new WorkloadProfile(DESCRIPTOR.ref(), dimensions, elements, "floating-point additions",
                "O(m·n)", Sizes.saturatedProduct(matrixBytes, 2), matrixBytes, Sizes.saturatedProduct(matrixBytes, 4),
                parallelism(rows));
    }

    /**
     * Parallelism characteristics for a matrix with the given number of rows.
     *
     * @param rows number of rows
     * @return the parallelism profile
     */
    protected ParallelismProfile parallelism(long rows) {
        return new ParallelismProfile(true, PartitionStrategyType.BLOCK_2D, rows, DataDependency.NONE,
                "O(m·n): copy blocks into the result");
    }

    @Override
    public MatrixPair prepareInput(Parameters parameters, ExecutionContext context) {
        int rows = parameters.getInt("rows");
        int cols = parameters.getInt("cols");
        return MatrixInputs.prepare(parameters, rows, cols, rows, cols, context.cancellation());
    }

    @Override
    public Matrix computeSequential(MatrixPair input, ExecutionContext context) {
        context.progress().start(input.a().rows(), "rows");
        return MatrixOps.add(input.a(), input.b(), context.cancellation(), context.progress());
    }

    @Override
    public VerificationReport verify(MatrixPair input, Matrix result, ExecutionContext context) {
        String method = "exact recomputation";
        Map<String, Object> details = new LinkedHashMap<>();
        Matrix a = input.a();
        Matrix b = input.b();
        if (result.rows() != a.rows() || result.cols() != a.cols()) {
            details.put("expectedShape", a.rows() + "x" + a.cols());
            details.put("actualShape", result.rows() + "x" + result.cols());
            return VerificationReport.failed(method, VerificationKind.EXACT_RECOMPUTATION,
                    "Result shape does not match the operands", details);
        }
        double[] aData = a.data();
        double[] bData = b.data();
        double[] cData = result.data();
        long mismatches = 0;
        int first = -1;
        for (int i = 0; i < cData.length; i++) {
            if ((i & 0xFFFFF) == 0) {
                context.cancellation().throwIfCancelled();
            }
            if (Double.doubleToRawLongBits(aData[i] + bData[i]) != Double.doubleToRawLongBits(cData[i])) {
                mismatches++;
                if (first < 0) {
                    first = i;
                }
            }
        }
        details.put("elementsChecked", cData.length);
        details.put("mismatches", mismatches);
        if (mismatches == 0) {
            return VerificationReport.passed(method, VerificationKind.EXACT_RECOMPUTATION,
                    "Every element equals A + B bit for bit", details);
        }
        details.put("firstMismatchRow", first / a.cols());
        details.put("firstMismatchCol", first % a.cols());
        return VerificationReport.failed(method, VerificationKind.EXACT_RECOMPUTATION,
                mismatches + " elements differ from A + B", details);
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
        return List.copyOf(Partitioners.planBlocks(parameters.getInt("rows"), parameters.getInt("cols"), options,
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
    public AddWork extract(MatrixPair input, PartitionBounds bounds) {
        BlockBounds block = (BlockBounds) bounds;
        return new AddWork(input.a(), input.b(), block.rowStart(), block.colStart(), block);
    }

    @Override
    public MatrixBlock computePartition(AddWork work, ExecutionContext context) {
        BlockBounds block = work.block();
        int cols = block.cols();
        Matrix out = Matrix.zeros(block.rows(), cols);
        double[] a = work.a().data();
        double[] b = work.b().data();
        double[] c = out.data();
        int stride = work.a().cols();
        for (int r = 0; r < block.rows(); r++) {
            if ((r & 63) == 0) {
                context.cancellation().throwIfCancelled();
            }
            int source = (work.rowOffset() + r) * stride + work.colOffset();
            int target = r * cols;
            for (int j = 0; j < cols; j++) {
                c[target + j] = a[source + j] + b[source + j];
            }
            context.progress().advance(1);
        }
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
    public PayloadCodec<AddWork> workCodec() {
        return AddWork.CODEC;
    }

    @Override
    public PayloadCodec<MatrixBlock> partialCodec() {
        return MatrixBlock.CODEC;
    }
}
