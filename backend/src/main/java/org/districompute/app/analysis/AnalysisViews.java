package org.districompute.app.analysis;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Request and response records of the comparison and performance analysis API ({@code docs/analysis.md}). */
public final class AnalysisViews {

    private AnalysisViews() {
    }

    /**
     * A comparison request.
     *
     * @param experimentIds two to {@value AnalysisService#MAX_COMPARED} experiments, in display order
     * @param baselineId the experiment speedups are relative to; default: a sequential one, else the first
     * @param basis EXECUTION_TIME (Core's execution time; the default when every experiment reports it) or WALL_TIME
     */
    public record CompareRequest(
            @NotNull @Size(min = 2, max = AnalysisService.MAX_COMPARED) List<@NotBlank String> experimentIds,
            String baselineId, @Pattern(regexp = "EXECUTION_TIME|WALL_TIME") String basis) {
    }

    /**
     * Descriptive statistics of a measured quantity over completed runs.
     *
     * @param count values
     * @param median median
     * @param mean arithmetic mean
     * @param min smallest value
     * @param max largest value
     * @param stddev sample standard deviation (0 for one value)
     * @param cv coefficient of variation (stddev / mean); null for fewer than two values
     */
    public record Stats(int count, double median, double mean, double min, double max, double stddev, Double cv) {
    }

    /**
     * One completed run, for charts.
     *
     * @param runIndex run number
     * @param wallTimeMs wall time
     * @param resultDigest digest of the verified result
     */
    public record RunPoint(int runIndex, double wallTimeMs, String resultDigest) {
    }

    /**
     * One compared experiment.
     *
     * @param id experiment id
     * @param name name
     * @param owner owner's user name
     * @param algorithm algorithm id
     * @param algorithmVersion algorithm version
     * @param mode execution mode
     * @param status experiment status
     * @param inputSource GENERATED, INLINE or UPLOAD
     * @param dimensions Core's workload dimensions at submission
     * @param estimatedOperations Core's estimate of the work
     * @param operationUnit unit of the estimate
     * @param parameters parameters given at submission
     * @param repetitions planned runs
     * @param completedRuns runs that completed
     * @param threads threads of the completed runs (median), for local runs
     * @param workers workers of the completed runs (median), for distributed runs
     * @param partitions partitions of the completed runs (median)
     * @param schedulingPolicy policy, distributed runs only
     * @param wallTime wall time of the completed runs, ms; null without completed runs
     * @param executionTime Core's execution time, ms; null if not reported
     * @param waitingTime time from submission until Core started the run (validation and queueing), ms
     * @param runs completed runs
     * @param speedup baseline median / this median, of the comparison's basis; null if not comparable
     * @param speedupRange baseline median over this experiment's slowest and fastest run
     * @param efficiency speedup per processing unit; null for sequential runs or when not comparable
     * @param efficiencyBasis what a processing unit is ("thread" or "worker")
     * @param breakdown median milliseconds per phase: waiting to start, Core's phases, and the rest of the wall time
     * @param indicators medians of further metrics, by metric name
     * @param resultDigest the result digest if all completed runs agree, else null
     * @param resultsConsistent whether all completed runs produced the same digest
     */
    public record Compared(String id, String name, String owner, String algorithm, String algorithmVersion,
            String mode, String status, String inputSource, Map<String, Object> dimensions,
            Double estimatedOperations, String operationUnit, Map<String, Object> parameters, int repetitions,
            int completedRuns, Integer threads, Integer workers, Integer partitions, String schedulingPolicy,
            Stats wallTime, Stats executionTime, Stats waitingTime, List<RunPoint> runs, Double speedup,
            List<Double> speedupRange,
            Double efficiency, String efficiencyBasis, Map<String, Double> breakdown, Map<String, Double> indicators,
            String resultDigest, boolean resultsConsistent) {
    }

    /**
     * Something the reader of a comparison must know.
     *
     * @param severity ERROR (the figures are not comparable), WARNING (comparable with a caveat) or INFO
     * @param code stable identifier
     * @param message explanation
     * @param experimentIds experiments concerned; empty for the whole comparison
     */
    public record Issue(String severity, String code,
            String message, List<String> experimentIds) {
    }

    /**
     * The result of a comparison.
     *
     * @param computedAt when it was computed
     * @param comparable whether speedups are meaningful (no ERROR issue)
     * @param baselineId the baseline experiment
     * @param baselineReason why it is the baseline
     * @param basis EXECUTION_TIME or WALL_TIME: the time speedups, variation and findings use
     * @param basisDescription what that time covers
     * @param experiments the compared experiments, in request order
     * @param issues compatibility problems and caveats
     * @param summary one-sentence findings, in plain language
     */
    public record Comparison(Instant computedAt, boolean comparable, String baselineId, String baselineReason,
            String basis, String basisDescription, List<Compared> experiments, List<Issue> issues,
            List<String> summary) {
    }

    /**
     * Completed experiments of one workload: the same algorithm, version and dimensions.
     *
     * @param key stable key of the group
     * @param algorithm algorithm id
     * @param algorithmVersion algorithm version
     * @param dimensions Core's dimensions
     * @param experiments experiments in the group
     * @param modes experiments per execution mode
     * @param lastCompletedAt newest completion
     * @param experimentIds the newest experiments, at most {@value AnalysisService#MAX_COMPARED}
     */
    public record WorkloadGroup(String key, String algorithm, String algorithmVersion, Map<String, Object> dimensions,
            int experiments, Map<String, Integer> modes, Instant lastCompletedAt, List<String> experimentIds) {
    }
}
