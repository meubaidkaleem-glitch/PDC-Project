package org.districompute.distributed.protocol;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formats of the identifiers that appear in messages ({@code docs/architecture.md}, section 7). Task and
 * experiment ids follow Core's rules; partition ids are Core's {@code <taskId>/p<index>}; attempt ids are
 * {@code <partitionId>/a<attempt>}.
 */
public final class Ids {

    /** Worker ids: 1–64 characters. */
    public static final Pattern WORKER_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    /** Message ids: 1–64 characters (senders use random UUIDs). */
    public static final Pattern MESSAGE_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,64}$");
    /** Task and experiment ids, as in Core's {@code TaskSpec}. */
    public static final Pattern TASK_ID = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private static final Pattern PARTITION_SUFFIX = Pattern.compile("^/p(0|[1-9]\\d{0,8})$");
    private static final Pattern ATTEMPT_SUFFIX = Pattern.compile("^/a([1-9]\\d{0,4})$");

    private Ids() {
    }

    /**
     * Whether a value matches a pattern.
     *
     * @param pattern one of the patterns above
     * @param value candidate, possibly {@code null}
     * @return {@code true} if it matches
     */
    public static boolean matches(Pattern pattern, String value) {
        return value != null && pattern.matcher(value).matches();
    }

    /**
     * Builds an attempt id.
     *
     * @param partitionId Core partition id
     * @param attempt attempt number, starting at 1
     * @return {@code <partitionId>/a<attempt>}
     */
    public static String attemptId(String partitionId, int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be at least 1");
        }
        return partitionId + "/a" + attempt;
    }

    /**
     * Checks that a partition id belongs to a task.
     *
     * @param taskId parent task id
     * @param partitionId partition id
     * @return {@code true} if it is {@code <taskId>/p<index>}
     */
    public static boolean isPartitionOf(String taskId, String partitionId) {
        return taskId != null && partitionId != null && partitionId.startsWith(taskId)
                && PARTITION_SUFFIX.matcher(partitionId.substring(taskId.length())).matches();
    }

    /**
     * Extracts the attempt number from an attempt id.
     *
     * @param partitionId partition the attempt belongs to
     * @param attemptId attempt id
     * @return the attempt number, or {@code -1} if the id is not {@code <partitionId>/a<n>}
     */
    public static int attemptNumber(String partitionId, String attemptId) {
        if (partitionId == null || attemptId == null || !attemptId.startsWith(partitionId)) {
            return -1;
        }
        Matcher matcher = ATTEMPT_SUFFIX.matcher(attemptId.substring(partitionId.length()));
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : -1;
    }
}
