package org.districompute.core.scheduling;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.districompute.core.algorithm.AlgorithmId;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.metrics.BenchmarkReport;

/**
 * Measured throughput of an algorithm in one configuration, taken from benchmark history. This is the "historical
 * measurements" input of strategy assessment (shared rule 4). The application stores benchmark reports and passes
 * the relevant calibrations in.
 *
 * @param algorithm algorithm identifier
 * @param mode execution mode
 * @param threads threads used
 * @param operationsPerSecond {@code estimatedOperations / median execution seconds}
 * @param problemOperations estimated operations of the measured problem (calibration is most accurate near it)
 */
public record Calibration(AlgorithmId algorithm, ExecutionMode mode, int threads, double operationsPerSecond,
        double problemOperations) {

    /** Validates the components. */
    public Calibration {
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(mode, "mode");
        if (threads < 1 || !(operationsPerSecond > 0)) {
            throw new IllegalArgumentException("A calibration needs at least one thread and a positive throughput");
        }
    }

    /**
     * Extracts one calibration per case of a benchmark report.
     *
     * @param report benchmark report (only verified runs are ever included in reports)
     * @return the calibrations
     */
    public static List<Calibration> fromReport(BenchmarkReport report) {
        List<Calibration> calibrations = new ArrayList<>();
        for (BenchmarkReport.CaseResult c : report.cases()) {
            if (c.throughputPerSecond() > 0) {
                calibrations.add(new Calibration(c.algorithm().id(), c.mode(), c.threads(), c.throughputPerSecond(),
                        c.estimatedOperations()));
            }
        }
        return calibrations;
    }
}
