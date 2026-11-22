package org.districompute.distributed.metrics;

/**
 * One worker's share of a task (load distribution).
 *
 * @param workerId the worker
 * @param attempts attempts sent to it
 * @param succeeded attempts whose result Core accepted
 * @param kernelNanos kernel time of its accepted attempts
 * @param executeNanos decode, kernel and encode time of all its reported attempts
 * @param transferNanos transport time of its accepted attempts
 * @param bytes bytes sent to and received from it
 */
public record WorkerShare(String workerId, int attempts, int succeeded, long kernelNanos, long executeNanos,
        long transferNanos, long bytes) {
}
