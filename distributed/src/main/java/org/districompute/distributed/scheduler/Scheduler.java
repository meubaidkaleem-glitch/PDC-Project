package org.districompute.distributed.scheduler;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.districompute.distributed.protocol.WorkerCapabilities;

/**
 * Applies the eligibility rules, then the selected policy, and records the decision. Not thread-safe: used on the
 * coordinator's control loop (policies such as round robin keep state between decisions).
 */
public final class Scheduler {

    /** Outcome of a scheduling question. */
    public sealed interface Decision permits Assign, Wait {
    }

    /**
     * Run the attempt on this worker.
     *
     * @param record the decision record
     */
    public record Assign(AssignmentRecord record) implements Decision {
    }

    /**
     * No worker can take the attempt now; it stays pending.
     *
     * @param reason summary
     * @param rejected per-worker reasons
     */
    public record Wait(String reason, List<Rejection> rejected) implements Decision {

        /** Copies the list. */
        public Wait {
            rejected = List.copyOf(rejected);
        }
    }

    private final Map<PolicyName, SchedulingPolicy> policies = new EnumMap<>(PolicyName.class);
    private PolicyName defaultPolicy;

    /**
     * Creates a scheduler.
     *
     * @param defaultPolicy policy for tasks that do not choose one
     * @param available the policy implementations
     */
    public Scheduler(PolicyName defaultPolicy, Collection<SchedulingPolicy> available) {
        available.forEach(p -> policies.put(p.name(), p));
        setDefaultPolicy(defaultPolicy);
    }

    /**
     * The default policy.
     *
     * @return the policy name
     */
    public PolicyName defaultPolicy() {
        return defaultPolicy;
    }

    /**
     * Changes the default policy.
     *
     * @param policy the new default
     * @throws IllegalArgumentException if the policy is not available
     */
    public void setDefaultPolicy(PolicyName policy) {
        requireAvailable(policy);
        this.defaultPolicy = policy;
    }

    /**
     * The available policies.
     *
     * @return their names
     */
    public List<PolicyName> available() {
        return List.copyOf(policies.keySet());
    }

    /**
     * Checks that a policy is available.
     *
     * @param policy the policy
     * @throws IllegalArgumentException if it is not
     */
    public void requireAvailable(PolicyName policy) {
        if (!policies.containsKey(Objects.requireNonNull(policy, "policy"))) {
            throw new IllegalArgumentException("Scheduling policy " + policy + " is not available; available: "
                    + policies.keySet());
        }
    }

    /**
     * Decides where an attempt runs.
     *
     * @param request the attempt
     * @param attemptId the attempt id the decision is for
     * @param candidates connected workers, in worker-id order
     * @param capabilitiesOf capabilities by worker id
     * @param policy policy to use, or {@code null} for the default
     * @param now current time
     * @return the decision
     */
    public Decision decide(SchedulingRequest request, String attemptId, List<Candidate> candidates,
            Function<String, WorkerCapabilities> capabilitiesOf, PolicyName policy, Instant now) {
        long start = System.nanoTime();
        List<Candidate> eligible = new ArrayList<>();
        List<Rejection> rejected = Eligibility.filter(request, candidates, capabilitiesOf, eligible);
        if (eligible.isEmpty()) {
            String reason = candidates.isEmpty() ? "no workers are connected"
                    : "no eligible worker has a free slot (" + candidates.size() + " connected)";
            return new Wait(reason, rejected);
        }
        PolicyName name = policy == null ? defaultPolicy : policy;
        SchedulingPolicy chosenPolicy = policies.get(name);
        if (chosenPolicy == null) {
            throw new IllegalArgumentException("Scheduling policy " + name + " is not available");
        }
        SchedulingPolicy.Choice choice = chosenPolicy.choose(request, List.copyOf(eligible), start);
        return new Assign(new AssignmentRecord(now, request.taskId(), request.partitionId(), attemptId, name,
                choice.selected().workerId(), choice.selected(), choice.explanation(), eligible.size(), rejected,
                System.nanoTime() - start));
    }
}
