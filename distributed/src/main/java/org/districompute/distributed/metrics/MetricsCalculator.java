package org.districompute.distributed.metrics;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import org.districompute.distributed.protocol.WorkerTiming;
import org.districompute.distributed.task.Attempt;
import org.districompute.distributed.task.AttemptStatus;
import org.districompute.distributed.task.DistributedTask;

/** Computes {@link DistributedMetrics} from a task's attempts. Pure: it only reads the task's state. */
public final class MetricsCalculator {

    private static final Set<AttemptStatus> FAILED = EnumSet.of(AttemptStatus.REJECTED, AttemptStatus.FAILED,
            AttemptStatus.TIMED_OUT, AttemptStatus.LOST, AttemptStatus.INVALID);

    private final ToIntFunction<String> slotsOf;

    /**
     * Creates a calculator.
     *
     * @param slotsOf current concurrency limit of a worker, for utilization
     */
    public MetricsCalculator(ToIntFunction<String> slotsOf) {
        this.slotsOf = slotsOf;
    }

    /**
     * Computes the metrics of a task so far (or final, once it has ended).
     *
     * @param task the task
     * @return the metrics
     */
    public DistributedMetrics compute(DistributedTask task) {
        long end = task.finishedNanos() >= 0 ? task.finishedNanos() : System.nanoTime();
        long first = task.firstDispatchNanos();
        long distributed = first < 0 ? 0 : (task.lastAcceptedNanos() >= 0 ? task.lastAcceptedNanos() : end) - first;
        long kernel = 0;
        long execute = 0;
        long transfer = 0;
        long serialization = 0;
        long processing = 0;
        long sent = 0;
        long received = 0;
        int succeeded = 0;
        int failed = 0;
        int retries = 0;
        Map<String, long[]> perWorker = new LinkedHashMap<>();
        for (Attempt attempt : task.attempts()) {
            long[] share = perWorker.computeIfAbsent(attempt.workerId(), id -> new long[6]);
            share[0]++;
            WorkerTiming timing = attempt.workerTiming();
            if (timing != null) {
                execute += timing.executeNanos();
                share[4] += timing.executeNanos();
            }
            if (attempt.status() == AttemptStatus.SUCCEEDED) {
                succeeded++;
                share[1]++;
                kernel += Math.max(0, attempt.computeNanos());
                share[2] += Math.max(0, attempt.computeNanos());
                long t = attempt.transferNanos();
                if (t >= 0) {
                    transfer += t;
                    share[3] += t;
                }
            } else if (FAILED.contains(attempt.status())) {
                failed++;
            }
            if (attempt.number() > 1) {
                retries++;
            }
            serialization += Math.max(0, attempt.serializationNanos());
            processing += Math.max(0, attempt.resultProcessingNanos());
            sent += attempt.bytesSent();
            received += attempt.bytesReceived();
            share[5] += attempt.bytesSent() + attempt.bytesReceived();
        }
        List<WorkerShare> shares = new ArrayList<>();
        perWorker.forEach((id, s) -> shares.add(new WorkerShare(id, (int) s[0], (int) s[1], s[2], s[4], s[3], s[5])));
        int slots = task.workersUsed().stream().mapToInt(slotsOf).map(n -> Math.max(1, n)).sum();
        double utilization = distributed <= 0 || slots == 0 ? 0 : execute / ((double) distributed * slots);
        long completed = task.partitions().stream()
                .filter(p -> p.status() == org.districompute.core.partition.PartitionStatus.COMPLETED).count();
        double throughput = distributed <= 0 ? 0 : completed / (distributed / 1e9);
        return new DistributedMetrics(end - task.submittedNanos(),
                (first < 0 ? end : first) - task.submittedNanos(), task.planningNanos(), distributed,
                task.aggregationNanos(), kernel, execute, transfer, serialization, processing, task.schedulingNanos(),
                sent, received, task.partitions().size(), task.attempts().size(), succeeded, failed, retries,
                task.workersUsed().size(), utilization, throughput, imbalance(shares), shares);
    }

    private static double imbalance(List<WorkerShare> shares) {
        double[] kernels = shares.stream().filter(s -> s.succeeded() > 0).mapToDouble(WorkerShare::kernelNanos)
                .toArray();
        if (kernels.length == 0) {
            return 0;
        }
        double max = 0;
        double sum = 0;
        for (double k : kernels) {
            max = Math.max(max, k);
            sum += k;
        }
        return sum == 0 ? 0 : max / (sum / kernels.length);
    }
}
