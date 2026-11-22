package org.districompute.core.result;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.districompute.core.DistriComputeException;
import org.districompute.core.ErrorCode;
import org.districompute.core.ErrorInfo;
import org.districompute.core.partition.PartitionBounds;
import org.districompute.core.partition.PartitionStatus;

/**
 * Thread-safe collector of partial results for one task (integration contract, section 7). Every offered partial
 * result is classified, and nothing is silently dropped:
 * <ul>
 *   <li>{@code ACCEPTED}: valid, and the first result for its partition;</li>
 *   <li>{@code DUPLICATE}: a valid result already exists, and this one is identical (by fingerprint);</li>
 *   <li>{@code CONFLICTING_DUPLICATE}: a valid result already exists, but this one differs (a faulty
 *       worker);</li>
 *   <li>{@code INVALID}: the partial failed validation, or belongs to another task;</li>
 *   <li>{@code UNKNOWN_PARTITION}: the index is not in the plan;</li>
 *   <li>{@code LATE}: the collector is already closed.</li>
 * </ul>
 * Failures can be recorded per partition. {@link #orderedResults()} refuses to return while any partition is
 * missing, and the exception names the missing and failed indices.
 *
 * @param <P> partial-result type
 */
public final class PartialResultCollector<P> {

    /** Classification of an offered partial result. */
    public enum Classification {
        /** Valid and first for its partition. */
        ACCEPTED,
        /** Identical to the already accepted result. */
        DUPLICATE,
        /** Differs from the already accepted result. */
        CONFLICTING_DUPLICATE,
        /** Failed validation or belongs to another task. */
        INVALID,
        /** Index not in the plan. */
        UNKNOWN_PARTITION,
        /** Offered after the collector was closed. */
        LATE
    }

    /**
     * Outcome of an offer.
     *
     * @param classification classification
     * @param detail explanation for anything other than {@code ACCEPTED}
     */
    public record Offer(Classification classification, String detail) {

        /**
         * Whether the partial result was stored.
         *
         * @return {@code true} for {@code ACCEPTED}
         */
        public boolean accepted() {
            return classification == Classification.ACCEPTED;
        }
    }

    private final String taskId;
    private final List<PartitionBounds> plan;
    private final BiFunction<PartitionBounds, P, Optional<String>> validator;
    private final Function<P, String> fingerprint;
    private final List<P> accepted;
    private final PartitionStatus[] statuses;
    private final ErrorInfo[] failures;
    private final int[] attempts;
    private int acceptedCount;
    private int duplicates;
    private int conflicts;
    private int invalid;
    private boolean closed;

    /**
     * Creates a collector for a plan.
     *
     * @param taskId task the partial results must belong to
     * @param plan bounds of every partition, in index order
     * @param validator returns a problem description for an invalid partial result
     * @param fingerprint content fingerprint used to tell duplicates from conflicting duplicates
     */
    public PartialResultCollector(String taskId, List<PartitionBounds> plan,
            BiFunction<PartitionBounds, P, Optional<String>> validator, Function<P, String> fingerprint) {
        this.taskId = Objects.requireNonNull(taskId, "taskId");
        this.plan = List.copyOf(plan);
        this.validator = Objects.requireNonNull(validator, "validator");
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.accepted = new ArrayList<>(java.util.Collections.nCopies(plan.size(), null));
        this.statuses = new PartitionStatus[plan.size()];
        java.util.Arrays.fill(statuses, PartitionStatus.PENDING);
        this.failures = new ErrorInfo[plan.size()];
        this.attempts = new int[plan.size()];
    }

    /**
     * Offers a partial result.
     *
     * @param fromTask task id the sender claims
     * @param index partition index
     * @param partial the partial result
     * @return the classification
     */
    public synchronized Offer offer(String fromTask, int index, P partial) {
        if (closed) {
            return new Offer(Classification.LATE, "collector already closed");
        }
        if (index < 0 || index >= plan.size()) {
            invalid++;
            return new Offer(Classification.UNKNOWN_PARTITION, "index " + index + " not in plan of " + plan.size());
        }
        attempts[index]++;
        if (!taskId.equals(fromTask)) {
            invalid++;
            return new Offer(Classification.INVALID, "belongs to task " + fromTask + ", expected " + taskId);
        }
        if (partial == null) {
            invalid++;
            return new Offer(Classification.INVALID, "missing partial result value");
        }
        Optional<String> problem = validator.apply(plan.get(index), partial);
        if (problem.isPresent()) {
            invalid++;
            return new Offer(Classification.INVALID, problem.get());
        }
        P existing = accepted.get(index);
        if (existing != null) {
            if (fingerprint.apply(existing).equals(fingerprint.apply(partial))) {
                duplicates++;
                return new Offer(Classification.DUPLICATE, "identical result already accepted");
            }
            conflicts++;
            return new Offer(Classification.CONFLICTING_DUPLICATE,
                    "differs from the result already accepted for partition " + index);
        }
        accepted.set(index, partial);
        statuses[index] = PartitionStatus.COMPLETED;
        failures[index] = null;
        acceptedCount++;
        return new Offer(Classification.ACCEPTED, "");
    }

    /**
     * Records a failed attempt of a partition that has no accepted result yet.
     *
     * @param index partition index
     * @param error failure description
     */
    public synchronized void markFailed(int index, ErrorInfo error) {
        Objects.checkIndex(index, plan.size());
        if (statuses[index] != PartitionStatus.COMPLETED) {
            statuses[index] = PartitionStatus.FAILED;
            failures[index] = Objects.requireNonNull(error, "error");
        }
    }

    /**
     * Returns the status of a partition.
     *
     * @param index partition index
     * @return the status
     */
    public synchronized PartitionStatus status(int index) {
        return statuses[Objects.checkIndex(index, plan.size())];
    }

    /**
     * Returns the recorded failure of a partition, if it is currently failed.
     *
     * @param index partition index
     * @return the failure
     */
    public synchronized Optional<ErrorInfo> failure(int index) {
        return Optional.ofNullable(failures[Objects.checkIndex(index, plan.size())]);
    }

    /**
     * Partitions without an accepted result, including failed ones.
     *
     * @return indices in ascending order
     */
    public synchronized List<Integer> missing() {
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            if (statuses[i] != PartitionStatus.COMPLETED) {
                missing.add(i);
            }
        }
        return missing;
    }

    /**
     * Partitions whose latest attempt failed.
     *
     * @return indices in ascending order
     */
    public synchronized List<Integer> failed() {
        List<Integer> failed = new ArrayList<>();
        for (int i = 0; i < plan.size(); i++) {
            if (statuses[i] == PartitionStatus.FAILED) {
                failed.add(i);
            }
        }
        return failed;
    }

    /**
     * Whether every partition has an accepted result.
     *
     * @return {@code true} if complete
     */
    public synchronized boolean isComplete() {
        return acceptedCount == plan.size();
    }

    /**
     * Returns the accepted results in partition order, for aggregation.
     *
     * @return the partial results
     * @throws DistriComputeException with {@link ErrorCode#MISSING_PARTIAL_RESULT} if any partition is missing
     */
    public synchronized List<P> orderedResults() {
        if (!isComplete()) {
            throw new DistriComputeException(ErrorCode.MISSING_PARTIAL_RESULT, "Cannot aggregate: missing partitions "
                    + missing() + (failed().isEmpty() ? "" : ", of which failed " + failed()));
        }
        return List.copyOf(accepted);
    }

    /** Rejects all further offers as {@code LATE}. */
    public synchronized void close() {
        closed = true;
    }

    /**
     * Counters for diagnostics: accepted, duplicates, conflicting duplicates and invalid offers.
     *
     * @return {@code [accepted, duplicates, conflicts, invalid]}
     */
    public synchronized int[] counters() {
        return new int[] {acceptedCount, duplicates, conflicts, invalid};
    }

    /**
     * Number of offers received for a partition (valid or not).
     *
     * @param index partition index
     * @return the attempt count
     */
    public synchronized int attempts(int index) {
        return attempts[Objects.checkIndex(index, plan.size())];
    }
}
