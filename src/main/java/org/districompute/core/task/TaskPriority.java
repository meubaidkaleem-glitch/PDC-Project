package org.districompute.core.task;

/**
 * Scheduling hint for queues. Core's local engine runs tasks in submission order and does not reorder them by
 * priority. The distributed runtime and the application may use this hint.
 */
public enum TaskPriority {
    /** Background work. */
    LOW,
    /** Default priority. */
    NORMAL,
    /** Interactive work that should run first. */
    HIGH
}
