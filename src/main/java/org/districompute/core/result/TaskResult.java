package org.districompute.core.result;

import java.util.List;
import java.util.Objects;
import org.districompute.core.ErrorInfo;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.core.metrics.EnvironmentInfo;
import org.districompute.core.metrics.ExecutionMetrics;
import org.districompute.core.resource.WorkloadProfile;
import org.districompute.core.serialization.SchemaVersion;
import org.districompute.core.task.StatusChange;
import org.districompute.core.task.TaskStatus;
import org.districompute.core.validation.VerificationReport;

/**
 * Final, serializable outcome of a task (integration contract, section 8).
 *
 * <p>Invariants, checked on construction: the status is terminal. {@code COMPLETED} requires a result summary, a
 * passed verification and no error. {@code FAILED} and {@code CANCELLED} require an error.
 *
 * @param schemaVersion contract schema version
 * @param taskId task identifier
 * @param experimentId experiment identifier, or {@code null}
 * @param algorithm algorithm with the resolved version (requested reference if resolution failed)
 * @param status terminal status
 * @param mode requested execution mode
 * @param threads threads used (0 if execution never started)
 * @param partitions partitions executed (0 if execution never started)
 * @param result summary of the result, or {@code null} if none was produced
 * @param verification verification report, or {@code null} if verification did not run
 * @param profile workload profile, or {@code null} if analysis did not run
 * @param metrics measurements
 * @param environment environment of the executing node
 * @param statusHistory status history, oldest first
 * @param error error description, or {@code null} for {@code COMPLETED}
 */
public record TaskResult(
        String schemaVersion,
        String taskId,
        String experimentId,
        AlgorithmRef algorithm,
        TaskStatus status,
        ExecutionMode mode,
        int threads,
        int partitions,
        ResultSummary result,
        VerificationReport verification,
        WorkloadProfile profile,
        ExecutionMetrics metrics,
        EnvironmentInfo environment,
        List<StatusChange> statusHistory,
        ErrorInfo error) {

    /** Validates the components and the status invariants. */
    public TaskResult {
        SchemaVersion.requireCompatible(schemaVersion);
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(environment, "environment");
        statusHistory = List.copyOf(statusHistory);
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("A task result needs a terminal status, got " + status);
        }
        if (status == TaskStatus.COMPLETED) {
            if (result == null || verification == null || !verification.passed() || error != null) {
                throw new IllegalArgumentException("COMPLETED requires a result, a passed verification and no error");
            }
        } else if (error == null) {
            throw new IllegalArgumentException(status + " requires an error description");
        }
    }
}
