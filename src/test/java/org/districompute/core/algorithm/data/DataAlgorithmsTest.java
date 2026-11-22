package org.districompute.core.algorithm.data;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.districompute.core.algorithm.ComputeAlgorithm;
import org.districompute.core.algorithm.ParameterValues;
import org.districompute.core.algorithm.Parameters;
import org.districompute.core.execution.ExecutionContext;
import org.districompute.core.partition.PartitionBounds;
import org.districompute.core.partition.PartitioningOptions;
import org.districompute.core.validation.ParameterValidator;
import org.districompute.core.validation.Violation;
import org.junit.jupiter.api.Test;

class DataAlgorithmsTest {

    private static final ExecutionContext CONTEXT = ExecutionContext.unmanaged();

    private static Parameters parameters(ComputeAlgorithm<?, ?> algorithm, Map<String, Object> raw) {
        return ParameterValidator.validate(algorithm.descriptor().parameters(), ParameterValues.normalizeAll(raw));
    }

    @Test
    void statisticsOfASmallKnownDataSet() {
        DescriptiveStatisticsAlgorithm statistics = new DescriptiveStatisticsAlgorithm();
        double[] values = statistics.prepareInput(parameters(statistics, Map.of("input", "INLINE",
                "values", List.of(1.0, 2.0, 3.0, 4.0))), CONTEXT);
        DescriptiveStatisticsAlgorithm.Summary summary = statistics.computeSequential(values, CONTEXT);
        assertEquals(4, summary.count());
        assertEquals(2.5, summary.mean(), 1e-15);
        assertEquals(5.0 / 3.0, summary.variance(), 1e-15);
        assertEquals(1.0, summary.min());
        assertEquals(4.0, summary.max());
        assertEquals(10.0, summary.sum() + summary.sumCompensation(), 0.0);
        assertTrue(statistics.verify(values, summary, CONTEXT).passed());
        DescriptiveStatisticsAlgorithm.Summary wrong = new DescriptiveStatisticsAlgorithm.Summary(0, 4, 2.6,
                summary.m2(), 1, 4, summary.sum(), summary.sumCompensation());
        assertFalse(statistics.verify(values, wrong, CONTEXT).passed());
    }

    @Test
    void chunkSummariesMergeToTheSequentialSummary() {
        DescriptiveStatisticsAlgorithm statistics = new DescriptiveStatisticsAlgorithm();
        Parameters parameters = parameters(statistics, Map.of("size", 100_003, "distribution", "NORMAL"));
        double[] values = statistics.prepareInput(parameters, CONTEXT);
        DescriptiveStatisticsAlgorithm.Summary sequential = statistics.computeSequential(values, CONTEXT);
        List<PartitionBounds> plan = statistics.plan(parameters, PartitioningOptions.ofPartitions(7), 4);
        List<DescriptiveStatisticsAlgorithm.Summary> partials = new ArrayList<>();
        for (PartitionBounds bounds : plan) {
            partials.add(statistics.computePartition(statistics.extract(values, bounds), CONTEXT));
            assertTrue(statistics.partialProblem(bounds, partials.get(partials.size() - 1)).isEmpty());
        }
        DescriptiveStatisticsAlgorithm.Summary merged = statistics.aggregate(plan, partials, CONTEXT);
        assertEquals(sequential.count(), merged.count());
        assertEquals(sequential.min(), merged.min());
        assertEquals(sequential.max(), merged.max());
        assertEquals(sequential.mean(), merged.mean(), 1e-12);
        assertEquals(sequential.variance(), merged.variance(), 1e-12);
        assertTrue(statistics.verify(values, merged, CONTEXT).passed());
        assertEquals(1.0, merged.variance(), 0.02, "standard normal data");
    }

    @Test
    void filterFindsMatchesAndRespectsTheLimitAcrossPartitions() {
        FilterAlgorithm filter = new FilterAlgorithm();
        FilterAlgorithm.Query small = filter.prepareInput(parameters(filter, Map.of("input", "INLINE",
                "values", List.of(5, 3, 9, 3, 4), "minValue", 3, "maxValue", 4)), CONTEXT);
        FilterAlgorithm.Matches matches = filter.computeSequential(small, CONTEXT);
        assertEquals(3, matches.count());
        assertArrayEquals(new long[] {1, 3, 4}, matches.indices());
        assertTrue(filter.verify(small, matches, CONTEXT).passed());
        assertFalse(filter.verify(small, new FilterAlgorithm.Matches(0, 5, 2, 100, new long[] {1, 3}), CONTEXT)
                .passed());

        Parameters parameters = parameters(filter, Map.of("size", 200_000, "limit", 7));
        FilterAlgorithm.Query query = filter.prepareInput(parameters, CONTEXT);
        FilterAlgorithm.Matches sequential = filter.computeSequential(query, CONTEXT);
        List<PartitionBounds> plan = filter.plan(parameters, PartitioningOptions.ofPartitions(9), 4);
        List<FilterAlgorithm.Matches> partials = new ArrayList<>();
        for (PartitionBounds bounds : plan) {
            partials.add(filter.computePartition(filter.extract(query, bounds), CONTEXT));
        }
        FilterAlgorithm.Matches merged = filter.aggregate(plan, partials, CONTEXT);
        assertEquals(sequential.count(), merged.count());
        assertArrayEquals(sequential.indices(), merged.indices());
        assertEquals(7, merged.indices().length);
        assertEquals(filter.summarize(sequential).digest(), filter.summarize(merged).digest());
    }

    @Test
    void filterValidatesItsPredicateAndInputSource() {
        FilterAlgorithm filter = new FilterAlgorithm();
        assertEquals(Violation.Codes.INCONSISTENT, filter.validate(parameters(filter,
                Map.of("minValue", 10, "maxValue", 1))).get(0).code());
        assertEquals(Violation.Codes.REQUIRED, filter.validate(parameters(filter, Map.of("input", "INLINE")))
                .get(0).code());
    }
}
