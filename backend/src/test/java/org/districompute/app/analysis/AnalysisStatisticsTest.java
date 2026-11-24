package org.districompute.app.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.districompute.app.analysis.AnalysisViews.Stats;
import org.junit.jupiter.api.Test;

class AnalysisStatisticsTest {

    @Test
    void statisticsUseTheSampleStandardDeviation() {
        Stats s = AnalysisService.stats(new double[] {4, 1, 3, 2});
        assertEquals(4, s.count());
        assertEquals(2.5, s.median(), 1e-12);
        assertEquals(2.5, s.mean(), 1e-12);
        assertEquals(1, s.min());
        assertEquals(4, s.max());
        assertEquals(Math.sqrt(5.0 / 3), s.stddev(), 1e-12);
        assertEquals(Math.sqrt(5.0 / 3) / 2.5, s.cv(), 1e-12);
    }

    @Test
    void oneValueHasNoVariation() {
        Stats s = AnalysisService.stats(new double[] {7});
        assertEquals(7, s.median());
        assertEquals(0, s.stddev());
        assertNull(s.cv(), "the variation of one run is unknown, not zero");
        assertNull(AnalysisService.stats(new double[0]));
    }

    @Test
    void workloadsAreEqualRegardlessOfKeyOrderAndNumberFormat() {
        assertEquals(AnalysisService.canonical(Map.of("rowsA", 512, "colsA", 512.0)),
                AnalysisService.canonical(Map.of("colsA", 512L, "rowsA", 512.00)));
        assertNotEquals(AnalysisService.canonical(Map.of("rowsA", 512)), AnalysisService.canonical(Map.of("rowsA", 513)));
        assertEquals("[1,2.5]", AnalysisService.canonical(List.of(1, 2.50)));
    }
}
