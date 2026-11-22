package org.districompute.distributed.protocol;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers the most recent message ids of one connection, so a repeated message is noticed and ignored instead of
 * being processed twice. Bounded: the oldest ids are forgotten first.
 */
public final class DuplicateMessageFilter {

    private final Map<String, Boolean> recent;

    /**
     * Creates a filter.
     *
     * @param capacity number of ids remembered
     */
    public DuplicateMessageFilter(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1");
        }
        this.recent = new LinkedHashMap<>(Math.min(capacity, 1024), 0.75f, false) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                return size() > capacity;
            }
        };
    }

    /**
     * Records a message id.
     *
     * @param messageId the id
     * @return {@code true} the first time an id is seen, {@code false} for a repeat
     */
    public synchronized boolean firstSeen(String messageId) {
        return recent.putIfAbsent(messageId, Boolean.TRUE) == null;
    }
}
