package org.districompute.distributed.coordinator;

import java.util.LinkedHashMap;
import java.util.Map;
import org.districompute.core.result.TaskResult;
import org.districompute.core.task.TaskStatus;
import org.districompute.distributed.metrics.DistributedMetrics;
import org.districompute.distributed.monitoring.EventBus;
import org.districompute.distributed.monitoring.EventIds;
import org.districompute.distributed.monitoring.EventType;
import org.districompute.distributed.protocol.AttemptRef;
import org.districompute.distributed.protocol.WorkerCapabilities;
import org.districompute.distributed.recovery.FailureKind;
import org.districompute.distributed.registry.WorkerRecord;
import org.districompute.distributed.registry.WorkerStatus;
import org.districompute.distributed.scheduler.AssignmentRecord;
import org.districompute.distributed.scheduler.PolicyName;
import org.districompute.distributed.task.Attempt;
import org.districompute.distributed.task.DistributedTask;
import org.districompute.distributed.task.PartitionState;

/** Builds and publishes the runtime's events with consistent ids, messages and details. */
final class RuntimeEvents implements WorkerManager.Listener {

    private final EventBus bus;

    RuntimeEvents(EventBus bus) {
        this.bus = bus;
    }

    EventBus bus() {
        return bus;
    }

    void coordinator(EventType type, String coordinatorId, String message, Map<String, Object> details) {
        bus.publish(type, EventIds.NONE, message, details(details, "coordinatorId", coordinatorId));
    }

    @Override
    public void workerRegistered(WorkerRecord worker, boolean reconnection) {
        WorkerCapabilities c = worker.capabilities();
        bus.publish(EventType.WORKER_REGISTERED, EventIds.worker(worker.workerId()), worker.workerId()
                + (reconnection ? " registered again" : " registered") + " (" + c.cpuCores() + " cores, "
                + c.maxConcurrentTasks() + " slots)", details(null, "hostname", c.hostname(), "cpuCores", c.cpuCores(),
                "maxConcurrentTasks", c.maxConcurrentTasks(), "algorithms", c.algorithms().size(),
                "gpuAvailable", c.gpu().available(), "faultSimulation", c.faultSimulation(),
                "reconnection", reconnection, "sessionId", worker.sessionId()));
    }

    void workerRejected(String workerId, String reason) {
        bus.publish(EventType.WORKER_REJECTED, EventIds.worker(workerId), "registration of " + workerId
                + " refused: " + reason, details(null, "reason", reason));
    }

    @Override
    public void workerStatusChanged(WorkerRecord worker, WorkerStatus previous, String reason) {
        bus.publish(EventType.WORKER_STATUS_CHANGED, EventIds.worker(worker.workerId()), worker.workerId() + ": "
                + previous + " -> " + worker.status() + " (" + reason + ")", details(null, "from", previous.name(),
                "to", worker.status().name(), "reason", reason));
    }

    @Override
    public void workerDisconnected(WorkerRecord worker, java.util.List<String> assignedAttempts, String reason) {
        bus.publish(EventType.WORKER_DISCONNECTED, EventIds.worker(worker.workerId()), worker.workerId()
                + " disconnected: " + reason, details(null, "reason", reason, "attemptsAffected",
                assignedAttempts.size()));
    }

    void taskSubmitted(DistributedTask task) {
        bus.publish(EventType.TASK_SUBMITTED, ids(task), "task " + task.taskId() + " (" + task.spec().algorithm()
                + ") submitted with policy " + task.policy(), details(null, "algorithm",
                task.spec().algorithm().toString(), "policy", task.policy().name(), "priority",
                task.spec().priority().name()));
    }

    void taskStatus(DistributedTask task, TaskStatus from, TaskStatus to) {
        bus.publish(EventType.TASK_STATUS_CHANGED, ids(task), "task " + task.taskId() + ": " + from + " -> " + to,
                details(null, "from", from == null ? null : from.name(), "to", to.name(), "partitions",
                        task.partitions().size()));
    }

    void taskProgress(DistributedTask task, int completed) {
        int total = task.partitions().size();
        bus.publish(EventType.TASK_PROGRESS, ids(task), "task " + task.taskId() + ": " + completed + " of " + total
                + " partitions", details(null, "completedPartitions", completed, "totalPartitions", total,
                "percent", total == 0 ? 0.0 : 100.0 * completed / total));
    }

    void taskFinished(DistributedTask task, TaskResult result, DistributedMetrics metrics) {
        Map<String, Object> values = details(null, "status", result.status().name(), "metrics", metrics);
        if (result.result() != null) {
            values.put("digest", result.result().digest());
            values.put("verificationPassed", result.verification() != null && result.verification().passed());
        }
        if (result.error() != null) {
            values.put("errorCode", result.error().code().name());
            values.put("error", result.error().message());
        }
        bus.publish(EventType.TASK_FINISHED, ids(task), "task " + task.taskId() + " " + result.status(), values);
    }

    void attemptAssigned(DistributedTask task, Attempt attempt) {
        AssignmentRecord record = attempt.assignment();
        bus.publish(EventType.ATTEMPT_ASSIGNED, ids(task, attempt), attempt.ref().attemptId() + " -> "
                + attempt.workerId(), details(null, "attempt", attempt.number(), "policy", record.policy().name(),
                "explanation", record.explanation(), "eligibleWorkers", record.eligibleWorkers(),
                "rejectedWorkers", record.rejected().size(), "decisionNanos", record.decisionNanos()));
    }

    void attemptAccepted(DistributedTask task, Attempt attempt) {
        bus.publish(EventType.ATTEMPT_ACCEPTED, ids(task, attempt), attempt.ref().attemptId() + " accepted by "
                + attempt.workerId(), details(null));
    }

    void attemptProgress(DistributedTask task, Attempt attempt, long completed, long total, String unit) {
        bus.publish(EventType.ATTEMPT_PROGRESS, ids(task, attempt), attempt.ref().attemptId() + ": " + completed
                + (total > 0 ? " of " + total : "") + (unit == null ? "" : " " + unit), details(null,
                "completedUnits", completed, "totalUnits", total, "unit", unit));
    }

    void attemptEnded(DistributedTask task, Attempt attempt) {
        var snapshot = attempt.snapshot();
        boolean succeeded = snapshot.status() == org.districompute.distributed.task.AttemptStatus.SUCCEEDED;
        Map<String, Object> values = details(null, "status", snapshot.status().name(), "attempt", attempt.number());
        if (succeeded) {
            values.put("computeNanos", snapshot.computeNanos());
            values.put("transferNanos", snapshot.transferNanos());
            values.put("roundTripNanos", snapshot.roundTripNanos());
            values.put("bytes", snapshot.bytesSent() + snapshot.bytesReceived());
        } else {
            values.put("failure", snapshot.failure());
            values.put("retryable", snapshot.retryable());
        }
        bus.publish(succeeded ? EventType.ATTEMPT_SUCCEEDED : EventType.ATTEMPT_FAILED, ids(task, attempt),
                attempt.ref().attemptId() + " " + snapshot.status() + (succeeded ? "" : ": " + snapshot.failure()),
                values);
    }

    void resultIgnored(DistributedTask task, Attempt attempt, String classification, String detail) {
        bus.publish(EventType.RESULT_IGNORED, ids(task, attempt), classification + " result for "
                + attempt.ref().attemptId() + " ignored", details(null, "classification", classification,
                "detail", detail));
    }

    void partitionRetried(DistributedTask task, PartitionState partition, FailureKind kind, String reason) {
        bus.publish(EventType.PARTITION_RETRIED, new EventIds(null, task.spec().experimentId(), task.taskId(),
                partition.descriptor().partitionId(), null), partition.descriptor().partitionId() + " queued again "
                + "after " + kind + ": " + reason, details(null, "failureKind", kind.name(), "reason", reason,
                "countedFailures", partition.countedFailures(), "nextAttempt", partition.nextAttemptNumber()));
    }

    void policyChanged(PolicyName from, PolicyName to) {
        bus.publish(EventType.SCHEDULING_POLICY_CHANGED, EventIds.NONE, "default scheduling policy: " + from
                + " -> " + to, details(null, "from", from.name(), "to", to.name()));
    }

    private static EventIds ids(DistributedTask task) {
        return new EventIds(null, task.spec().experimentId(), task.taskId(), null, null);
    }

    private static EventIds ids(DistributedTask task, Attempt attempt) {
        AttemptRef ref = attempt.ref();
        return new EventIds(attempt.workerId(), task.spec().experimentId(), ref.taskId(), ref.partitionId(),
                ref.attemptId());
    }

    private static Map<String, Object> details(Map<String, Object> base, Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (base != null) {
            map.putAll(base);
        }
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            if (keyValues[i + 1] != null) {
                map.put((String) keyValues[i], keyValues[i + 1]);
            }
        }
        return map;
    }
}
