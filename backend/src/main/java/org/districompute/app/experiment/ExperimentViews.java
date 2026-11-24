package org.districompute.app.experiment;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.districompute.app.task.TaskDraft;

/** Request and response bodies of the experiment API ({@code docs/api.md}). */
public final class ExperimentViews {

    private ExperimentViews() {
    }

    /**
     * A new experiment.
     *
     * @param name name
     * @param description description
     * @param task the task
     * @param overrideReason why the user chose a mode other than the recommendation (optional)
     */
    public record CreateExperiment(@NotBlank @Size(max = 200) String name, @Size(max = 2000) String description,
            @NotNull @Valid TaskDraft task, @Size(max = 500) String overrideReason) {
    }

    /**
     * An experiment in lists.
     *
     * @param id id (also Core's experimentId)
     * @param name name
     * @param owner owner's user name
     * @param category application category
     * @param algorithm Core algorithm id
     * @param algorithmVersion Core algorithm version
     * @param executionMode chosen mode
     * @param recommendedMode recommended mode at submission, or {@code null}
     * @param recommendationAccepted whether the chosen mode was the recommendation
     * @param schedulingPolicy scheduling policy of distributed runs
     * @param status status
     * @param repetitions planned runs
     * @param completedRuns completed runs
     * @param workersUsed largest number of workers a run used
     * @param medianWallTimeMs median wall time of completed runs
     * @param createdAt creation time
     * @param startedAt start of the first run
     * @param completedAt end
     * @param durationMs {@code completedAt - startedAt}
     * @param errorMessage error, if failed
     * @param batchId batch, or {@code null}
     */
    public record ExperimentSummary(String id, String name, String owner, String category, String algorithm,
            String algorithmVersion, String executionMode, String recommendedMode, Boolean recommendationAccepted,
            String schedulingPolicy, String status, int repetitions, int completedRuns, Integer workersUsed,
            Double medianWallTimeMs, Instant createdAt, Instant startedAt, Instant completedAt, Long durationMs,
            String errorMessage, String batchId) {
    }

    /**
     * One run.
     *
     * @param id run id
     * @param runIndex 1-based index
     * @param coreTaskId Core task id
     * @param executionMode mode
     * @param status status
     * @param threads threads
     * @param partitions partitions
     * @param workersUsed workers used
     * @param submittedAt submission
     * @param startedAt start
     * @param completedAt end
     * @param wallTimeMs submission to completion
     * @param resultDigest Core's result digest
     * @param verificationPassed whether Core's verification passed
     * @param verificationMethod Core's verification method
     * @param errorCode error code
     * @param errorMessage error message
     */
    public record RunView(Long id, int runIndex, String coreTaskId, String executionMode, String status, Integer threads,
            Integer partitions, Integer workersUsed, Instant submittedAt, Instant startedAt, Instant completedAt,
            Double wallTimeMs, String resultDigest, Boolean verificationPassed, String verificationMethod,
            String errorCode, String errorMessage) {
    }

    /**
     * The result of a run.
     *
     * @param runIndex run
     * @param resultType Core result type
     * @param summary Core's summary values
     * @param digest Core's digest
     * @param validationStatus {@code VERIFIED}, {@code VERIFICATION_FAILED} or {@code NOT_VERIFIED}
     * @param validationMethod Core's method
     * @param validationMessage Core's message
     * @param stored whether the full value is stored
     * @param format stored format
     * @param sizeBytes stored size
     * @param storedDigestMatch whether the stored value has Core's verified digest
     * @param storageNote why not stored, if not
     */
    public record ResultView(int runIndex, String resultType, Map<String, Object> summary, String digest,
            String validationStatus, String validationMethod, String validationMessage, boolean stored, String format,
            Long sizeBytes, Boolean storedDigestMatch, String storageNote) {
    }

    /**
     * Statistics of one metric over the completed runs.
     *
     * @param name metric name
     * @param unit unit
     * @param source source
     * @param count runs with a value
     * @param median median
     * @param mean mean
     * @param min minimum
     * @param max maximum
     * @param stddev sample standard deviation (0 for one run)
     */
    public record MetricStats(String name, String unit, String source, int count, double median, double mean,
            double min, double max, double stddev) {
    }

    /**
     * One metric value.
     *
     * @param runIndex run
     * @param name name
     * @param value value
     * @param unit unit
     * @param source source
     */
    public record MetricValue(int runIndex, String name, double value, String unit, String source) {
    }

    /**
     * An experiment with everything stored about it.
     *
     * @param summary list view
     * @param description description
     * @param parameters Core parameters (uploaded arrays as references)
     * @param inputSource {@code GENERATED}, {@code INLINE} or {@code UPLOAD}
     * @param inputSummary dimensions, workload estimate, uploads
     * @param configuration execution settings
     * @param adviceBasis basis of the recommendation at submission
     * @param overrideReason the user's reason for a different mode
     * @param errorCode error code, if failed
     * @param runs runs
     * @param results results
     * @param metrics statistics per metric
     */
    public record ExperimentDetail(ExperimentSummary summary, String description, Map<String, Object> parameters,
            String inputSource, Map<String, Object> inputSummary, Map<String, Object> configuration,
            String adviceBasis, String overrideReason, String errorCode, List<RunView> runs,
            List<ResultView> results, List<MetricStats> metrics) {
    }

    /**
     * A partition of a run in the live view.
     *
     * @param index index
     * @param partitionId Core partition id
     * @param status partition status
     * @param attempts attempts, oldest first
     */
    public record LivePartition(int index, String partitionId, String status, List<LiveAttempt> attempts) {
    }

    /**
     * One attempt in the live view.
     *
     * @param attemptId attempt id
     * @param workerId worker
     * @param status attempt status
     * @param dispatchedAt dispatch time
     * @param acceptedAt acceptance time
     * @param finishedAt end
     * @param failure failure reason
     * @param explanation the scheduler's reason for choosing the worker
     * @param transferMs transport time
     * @param computeMs kernel time
     * @param bytes bytes sent and received
     */
    public record LiveAttempt(String attemptId, String workerId, String status, Instant dispatchedAt,
            Instant acceptedAt, Instant finishedAt, String failure, String explanation, double transferMs,
            double computeMs, long bytes) {
    }

    /**
     * The state of a run as the runtime reports it now.
     *
     * @param experimentId experiment
     * @param experimentStatus experiment status
     * @param runIndex run index
     * @param coreTaskId Core task id
     * @param available whether the runtime still knows the run (finished runs are forgotten eventually)
     * @param status Core status
     * @param mode mode
     * @param algorithm algorithm
     * @param policy scheduling policy
     * @param submittedAt submission
     * @param finishedAt end
     * @param totalPartitions partitions
     * @param completedPartitions completed
     * @param failedPartitions failed
     * @param pendingPartitions not started
     * @param runningPartitions with a live attempt
     * @param attempts attempts so far
     * @param retries retries so far
     * @param activeWorkers workers with a live attempt
     * @param partitions partitions with attempts
     * @param metrics runtime metrics so far
     * @param errors failure messages of attempts and the task
     */
    public record LiveRun(String experimentId, String experimentStatus, int runIndex, String coreTaskId,
            boolean available, String status, String mode, String algorithm, String policy, Instant submittedAt,
            Instant finishedAt, int totalPartitions, int completedPartitions, int failedPartitions,
            int pendingPartitions, int runningPartitions, int attempts, int retries, List<String> activeWorkers,
            List<LivePartition> partitions, Map<String, Object> metrics, List<String> errors) {
    }
}
