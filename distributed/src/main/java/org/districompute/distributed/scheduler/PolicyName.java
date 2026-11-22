package org.districompute.distributed.scheduler;

/** The scheduling policies offered by the runtime ({@code docs/scheduling.md}). */
public enum PolicyName {
    /** Eligible workers in a fixed rotation. */
    ROUND_ROBIN,
    /** The eligible worker with the lowest slot utilization. */
    LEAST_LOADED,
    /** The eligible worker with the most spare resources for this task. */
    CAPABILITY_AWARE,
    /** The eligible worker with the shortest estimated completion time from observed performance. */
    PERFORMANCE_AWARE,
    /** Demand-driven: the eligible worker that has waited longest for work. */
    DYNAMIC
}
