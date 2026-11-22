package org.districompute.distributed.protocol;

/**
 * Durations measured on the worker's own clock for one attempt. Combined with the coordinator's round trip, they
 * separate communication from computation without comparing clocks of different machines.
 *
 * @param queueNanos from admission to the start of execution
 * @param executeNanos decoding the work unit, running the kernel and encoding the result
 * @param residenceNanos from receiving the {@code TASK} frame to having the result ready to send
 */
public record WorkerTiming(long queueNanos, long executeNanos, long residenceNanos) {

    /** Validates the components. */
    public WorkerTiming {
        Checks.nonNegative(queueNanos, "queueNanos");
        Checks.nonNegative(executeNanos, "executeNanos");
        Checks.nonNegative(residenceNanos, "residenceNanos");
    }
}
