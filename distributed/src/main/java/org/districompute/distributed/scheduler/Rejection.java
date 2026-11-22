package org.districompute.distributed.scheduler;

/**
 * Why a worker was not eligible for an attempt.
 *
 * @param workerId the worker
 * @param reason the reason
 */
public record Rejection(String workerId, String reason) {
}
