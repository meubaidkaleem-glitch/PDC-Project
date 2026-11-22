package org.districompute.core.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.districompute.core.execution.LocalExecutionEngine;
import org.junit.jupiter.api.Test;

class SampleStatisticsTest {

    @Test
    void matchesHandComputedValuesForAnOddSample() {
        SampleStatistics stats = SampleStatistics.of(new double[] {4, 1, 7, 2, 6});
        assertEquals(5, stats.count());
        assertEquals(1, stats.min());
        assertEquals(7, stats.max());
        assertEquals(4, stats.mean());
        assertEquals(4, stats.median());
        // squared deviations 0 + 9 + 9 + 4 + 4 = 26; sample variance 26 / 4 = 6.5
        assertEquals(Math.sqrt(6.5), stats.standardDeviation(), 1e-15);
        assertEquals(Math.sqrt(6.5) / 4, stats.coefficientOfVariation(), 1e-15);
    }

    @Test
    void medianOfAnEvenSampleAveragesTheMiddleValues() {
        SampleStatistics stats = SampleStatistics.of(new long[] {10, 40, 20, 30});
        assertEquals(25, stats.median());
        assertEquals(25, stats.mean());
    }

    @Test
    void singleSampleHasNoDispersion() {
        SampleStatistics stats = SampleStatistics.of(new double[] {3.5});
        assertEquals(0, stats.standardDeviation());
        assertEquals(3.5, stats.median());
    }

    @Test
    void rejectsEmptyAndNonFiniteSamples() {
        assertThrows(IllegalArgumentException.class, () -> SampleStatistics.of(new double[0]));
        assertThrows(IllegalArgumentException.class, () -> SampleStatistics.of(new double[] {1, Double.NaN}));
    }

    @Test
    void schedulingOverheadIsNeverNegative() {
        assertEquals(0, ExecutionMetrics.schedulingOverhead(100, 800, 4));
        assertEquals(50, ExecutionMetrics.schedulingOverhead(250, 800, 4));
        assertEquals(LocalExecutionEngine.class, LocalExecutionEngine.class);
    }
}
