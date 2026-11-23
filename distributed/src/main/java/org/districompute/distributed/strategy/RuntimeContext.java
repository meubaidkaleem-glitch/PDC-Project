package org.districompute.distributed.strategy;

import java.util.List;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.distributed.scheduler.Rejection;

/**
 * What the distributed runtime knows that Core's strategy advisor cannot: which workers could run the task now,
 * their load, the measured network, and which modes this runtime can actually execute.
 *
 * @param eligibleWorkers connected, active workers that offer the algorithm in a compatible version
 * @param ineligibleWorkers other connected workers, with the reason
 * @param totalSlots slots of the eligible workers
 * @param busySlots slots of the eligible workers occupied by attempts
 * @param pendingPartitions partitions of other tasks waiting for a worker
 * @param transferBytesPerSecond measured transfer rate between coordinator and workers, or {@code null} if not yet
 *     measured
 * @param latencyMillis lower bound of the measured round-trip transport time, or {@code null} if not yet measured
 * @param estimatedTransferBytes bytes a distributed run would move (Core's input plus output size, with base64
 *     overhead): a lower bound
 * @param executableModes modes this runtime can execute for the task now
 * @param notes plain-language remarks for the user (why a mode is unavailable, what the estimates assume)
 */
public record RuntimeContext(List<String> eligibleWorkers, List<Rejection> ineligibleWorkers, int totalSlots,
        int busySlots, int pendingPartitions, Double transferBytesPerSecond, Double latencyMillis,
        long estimatedTransferBytes, List<ExecutionMode> executableModes, List<String> notes) {

    /** Copies the lists. */
    public RuntimeContext {
        eligibleWorkers = List.copyOf(eligibleWorkers);
        ineligibleWorkers = List.copyOf(ineligibleWorkers);
        executableModes = List.copyOf(executableModes);
        notes = List.copyOf(notes);
    }
}
