package org.districompute.distributed.registry;

/**
 * Attempt counters kept by the coordinator for one worker (across reconnections).
 *
 * @param assigned attempts sent to the worker
 * @param succeeded attempts whose result Core accepted
 * @param failed attempts that failed on the worker
 * @param rejected attempts the worker refused
 * @param timedOut attempts that exceeded their timeout
 * @param lost attempts lost with the worker's connection or missing from its heartbeat
 * @param invalidResults results Core classified as invalid or conflicting
 * @param duplicateResults results Core classified as duplicates
 */
public record WorkerCounters(long assigned, long succeeded, long failed, long rejected, long timedOut, long lost,
        long invalidResults, long duplicateResults) {
}
