package org.districompute.core.execution;

/**
 * Kernel-facing progress sink. Implementations are thread-safe and cheap: kernels may call {@link #advance} from
 * several worker threads, typically once per segment or row block. The engine turns these calls into throttled,
 * monotonic progress events.
 */
public interface ProgressReporter {

    /** A reporter that ignores all progress. */
    ProgressReporter NONE = new ProgressReporter() {
        @Override
        public void start(long totalUnits, String unit) {
        }

        @Override
        public void advance(long units) {
        }
    };

    /**
     * Declares the total amount of work for the current stage. Call it once before {@link #advance}.
     *
     * @param totalUnits total work units, at least 0
     * @param unit unit name shown to users, for example {@code "rows"}
     */
    void start(long totalUnits, String unit);

    /**
     * Reports that {@code units} more work units are done.
     *
     * @param units completed units since the last call, at least 0
     */
    void advance(long units);
}
