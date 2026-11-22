package org.districompute.distributed.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.execution.ExecutionMode;
import org.districompute.distributed.protocol.AlgorithmSupport;
import org.districompute.distributed.protocol.WorkerCapabilities;
import org.districompute.distributed.registry.WorkerStatus;

/**
 * The rules a worker must pass before any policy may choose it. Reported capabilities are not trusted as proof
 * that an attempt will succeed: they only rule workers out.
 */
public final class Eligibility {

    private Eligibility() {
    }

    /**
     * Why a worker can never run attempts of this task (independent of its current load and status).
     *
     * @param capabilities the worker's capabilities
     * @param requirements what an attempt needs
     * @return the reason, or empty if the worker is capable
     */
    public static Optional<String> incapable(WorkerCapabilities capabilities, TaskRequirements requirements) {
        AlgorithmRef needed = requirements.algorithm();
        Optional<AlgorithmSupport> support = capabilities.algorithm(needed.id());
        if (support.isEmpty()) {
            return Optional.of("does not offer " + needed.id());
        }
        if (!support.get().supports(ExecutionMode.DISTRIBUTED_CPU)) {
            return Optional.of("does not run " + needed.id() + " partitions");
        }
        if (!support.get().algorithm().version().satisfies(needed.version())) {
            return Optional.of("offers " + needed.id() + "@" + support.get().algorithm().version() + ", needs "
                    + needed.version());
        }
        if (requirements.memoryBytes() > capabilities.maxTaskMemoryBytes()) {
            return Optional.of(String.format(Locale.ROOT, "needs about %.1f MiB per attempt, worker allows %.1f MiB",
                    requirements.memoryBytes() / 1048576.0, capabilities.maxTaskMemoryBytes() / 1048576.0));
        }
        if (requirements.payloadBytes() > capabilities.maxMessageBytes()) {
            return Optional.of(String.format(Locale.ROOT, "work unit of about %.1f MiB exceeds the worker's %.1f MiB "
                    + "message limit", requirements.payloadBytes() / 1048576.0,
                    capabilities.maxMessageBytes() / 1048576.0));
        }
        return Optional.empty();
    }

    /**
     * Splits workers into eligible candidates and rejections.
     *
     * @param request the attempt to place
     * @param candidates all connected workers with their state
     * @param capabilitiesOf capabilities by worker id
     * @param eligible receives eligible candidates
     * @return rejections with reasons
     */
    public static List<Rejection> filter(SchedulingRequest request, List<Candidate> candidates,
            java.util.function.Function<String, WorkerCapabilities> capabilitiesOf, List<Candidate> eligible) {
        List<Rejection> rejected = new ArrayList<>();
        List<Candidate> excluded = new ArrayList<>();
        for (Candidate candidate : candidates) {
            Optional<String> reason = reason(request, candidate, capabilitiesOf.apply(candidate.workerId()));
            if (reason.isPresent()) {
                rejected.add(new Rejection(candidate.workerId(), reason.get()));
            } else if (request.excludedWorkers().contains(candidate.workerId())) {
                excluded.add(candidate);
            } else {
                eligible.add(candidate);
            }
        }
        if (eligible.isEmpty()) {
            // A worker that failed this partition before is better than none: the failure may have been transient.
            eligible.addAll(excluded);
        } else {
            excluded.forEach(c -> rejected.add(new Rejection(c.workerId(), "already failed this partition")));
        }
        return rejected;
    }

    private static Optional<String> reason(SchedulingRequest request, Candidate candidate,
            WorkerCapabilities capabilities) {
        if (candidate.status() != WorkerStatus.ACTIVE) {
            return Optional.of("status " + candidate.status());
        }
        if (!request.allowedWorkers().isEmpty() && !request.allowedWorkers().contains(candidate.workerId())) {
            return Optional.of("outside the task's worker limit");
        }
        Optional<String> incapable = incapable(capabilities, request.requirements());
        if (incapable.isPresent()) {
            return incapable;
        }
        if (candidate.freeSlots() == 0) {
            return Optional.of("no free slot (" + candidate.assigned() + " of " + candidate.maxSlots() + " busy)");
        }
        return Optional.empty();
    }
}
