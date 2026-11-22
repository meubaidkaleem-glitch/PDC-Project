package org.districompute.distributed.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.districompute.core.algorithm.AlgorithmRef;
import org.districompute.core.task.TaskPriority;
import org.districompute.distributed.protocol.AlgorithmSupport;
import org.districompute.distributed.protocol.WorkerCapabilities;
import org.districompute.distributed.registry.WorkerStatus;
import org.districompute.distributed.testing.CoreFixtures;
import org.junit.jupiter.api.Test;

/** Every policy's choice on constructed worker states, and the eligibility rules that precede it. */
class SchedulingPoliciesTest {

    private static final TaskRequirements PRIMES = new TaskRequirements(AlgorithmRef.of("primes.count", "1.0.0"),
            1e9, 4096, 1 << 20, TaskPriority.NORMAL);

    @Test
    void roundRobinRotatesAndSkipsUnavailableWorkers() {
        RoundRobinPolicy policy = new RoundRobinPolicy();
        List<Candidate> all = List.of(worker("a", 2, 0), worker("b", 2, 0), worker("c", 2, 0));
        assertEquals(List.of("a", "b", "c", "a"), List.of(pick(policy, all), pick(policy, all), pick(policy, all),
                pick(policy, all)));
        assertEquals("c", pick(policy, List.of(worker("a", 2, 0), worker("c", 2, 0))), "b is skipped, not awaited");
        assertTrue(policy.choose(request(), all, 0).explanation().startsWith("round robin: a follows c"));
    }

    @Test
    void leastLoadedComparesUtilizationNotCounts() {
        // a: 1 of 4 slots (25 %); b: 1 of 2 slots (50 %).
        assertEquals("a", pick(new LeastLoadedPolicy(), List.of(worker("a", 4, 1), worker("b", 2, 1))));
        assertEquals("b", pick(new LeastLoadedPolicy(), List.of(worker("a", 4, 2), worker("b", 4, 1))));
        String explanation = new LeastLoadedPolicy().choose(request(), List.of(worker("a", 4, 1)), 0).explanation();
        assertEquals("least loaded: a uses 1 of 4 slots (25%)", explanation);
    }

    @Test
    void capabilityAwarePrefersSpareCoresThenMemory() {
        Candidate small = new Candidate("small", WorkerStatus.ACTIVE, 2, 0, 2, 1L << 30, 1 << 24, -1, 0, 0, 0, 0, 0);
        Candidate big = new Candidate("big", WorkerStatus.ACTIVE, 4, 1, 16, 1L << 30, 1 << 24, -1, 0, 0, 0, 0, 0);
        assertEquals("big", pick(new CapabilityAwarePolicy(), List.of(big, small)), "12 idle cores beat 2");
        Candidate roomy = new Candidate("roomy", WorkerStatus.ACTIVE, 2, 0, 2, 8L << 30, 1 << 24, -1, 0, 0, 0, 0, 0);
        assertEquals("roomy", pick(new CapabilityAwarePolicy(), List.of(small, roomy)), "equal cores: more memory");
        assertEquals(12.0, CapabilityAwarePolicy.idleCores(big), 1e-9);
    }

    @Test
    void performanceAwareMeasuresUnknownWorkersFirstThenPicksTheFastest() {
        Candidate fast = measured("fast", 2e9, 0);
        Candidate slow = measured("slow", 5e8, 0);
        Candidate fresh = measured("fresh", 0, 0);
        PerformanceAwarePolicy policy = new PerformanceAwarePolicy();
        SchedulingPolicy.Choice explore = policy.choose(request(), List.of(fast, fresh, slow), 0);
        assertEquals("fresh", explore.selected().workerId());
        assertTrue(explore.explanation().contains("no observations"), explore.explanation());
        SchedulingPolicy.Choice choice = policy.choose(request(), List.of(slow, fast), 0);
        assertEquals("fast", choice.selected().workerId());
        assertEquals(0.5, PerformanceAwarePolicy.estimate(request(), fast), 1e-9, "1e9 ops at 2e9 ops/s");
        assertTrue(choice.explanation().contains("slow 2.000 s"), choice.explanation());
    }

    @Test
    void performanceAwareCountsTransferTime() {
        TaskRequirements heavy = new TaskRequirements(PRIMES.algorithm(), 1e9, 400_000_000, 1, TaskPriority.NORMAL);
        SchedulingRequest request = new SchedulingRequest("t", "t/p0", 1, heavy, Set.of(), Set.of());
        // Same kernel speed, but a 10x slower link: the 400 MB work unit decides.
        Candidate nearby = measured("nearby", 1e9, 1e9);
        Candidate remote = measured("remote", 1e9, 1e8);
        assertEquals("nearby", new PerformanceAwarePolicy().choose(request, List.of(remote, nearby), 0).selected()
                .workerId());
        assertEquals(5.0, PerformanceAwarePolicy.estimate(request, remote), 1e-9);
    }

    @Test
    void dynamicServesTheLongestWaitingWorker() {
        Candidate waitedLong = new Candidate("x", WorkerStatus.ACTIVE, 2, 0, 2, 1 << 30, 1 << 24, -1, 0, 0, 0, 900, 1);
        Candidate waitedShort = new Candidate("y", WorkerStatus.ACTIVE, 2, 0, 2, 1 << 30, 1 << 24, -1, 0, 0, 0, 10, 2);
        assertEquals("x", pick(new DynamicPolicy(), List.of(waitedShort, waitedLong)));
        Candidate busyOld = new Candidate("p", WorkerStatus.ACTIVE, 2, 1, 2, 1 << 30, 1 << 24, -1, 0, 0, 0, 0, 100);
        Candidate busyNew = new Candidate("q", WorkerStatus.ACTIVE, 2, 1, 2, 1 << 30, 1 << 24, -1, 0, 0, 0, 0, 500);
        SchedulingPolicy.Choice choice = new DynamicPolicy().choose(request(), List.of(busyNew, busyOld), 0);
        assertEquals("p", choice.selected().workerId(), "no idle worker: the least recently assigned");
        assertTrue(choice.explanation().contains("least recently"), choice.explanation());
    }

    @Test
    void eligibilityRulesOutWorkersWithReasons() {
        Map<String, WorkerCapabilities> capabilities = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        add(candidates, capabilities, "ok", WorkerStatus.ACTIVE, CoreFixtures.capabilities(2), 0);
        add(candidates, capabilities, "suspect", WorkerStatus.SUSPECT, CoreFixtures.capabilities(2), 0);
        add(candidates, capabilities, "busy", WorkerStatus.ACTIVE, CoreFixtures.capabilities(2), 2);
        add(candidates, capabilities, "no-primes", WorkerStatus.ACTIVE, withAlgorithms(List.of()), 0);
        add(candidates, capabilities, "old", WorkerStatus.ACTIVE, withAlgorithms(List.of(new AlgorithmSupport(
                AlgorithmRef.of("primes.count", "0.9.0"), List.of(org.districompute.core.execution.ExecutionMode
                        .DISTRIBUTED_CPU)))), 0);
        TaskRequirements hungry = new TaskRequirements(PRIMES.algorithm(), 1, 32 << 20, 1L << 40,
                TaskPriority.NORMAL);
        List<Candidate> eligible = new ArrayList<>();
        List<Rejection> rejected = Eligibility.filter(request(PRIMES, Set.of(), Set.of()), candidates,
                capabilities::get, eligible);
        assertEquals(List.of("ok"), eligible.stream().map(Candidate::workerId).toList());
        assertEquals(Map.of("suspect", "status SUSPECT", "busy", "no free slot (2 of 2 busy)",
                "no-primes", "does not offer primes.count", "old", "offers primes.count@0.9.0, needs 1.0.0"),
                reasons(rejected));
        assertTrue(Eligibility.incapable(capabilities.get("ok"), hungry).orElseThrow().startsWith("needs about"));
        TaskRequirements bulky = new TaskRequirements(PRIMES.algorithm(), 1, 1L << 30, 1, TaskPriority.NORMAL);
        assertTrue(Eligibility.incapable(capabilities.get("ok"), bulky).orElseThrow().contains("message limit"));
    }

    @Test
    void excludedWorkersAreUsedOnlyWhenNoOtherWorkerQualifies() {
        Map<String, WorkerCapabilities> capabilities = new HashMap<>();
        List<Candidate> candidates = new ArrayList<>();
        add(candidates, capabilities, "a", WorkerStatus.ACTIVE, CoreFixtures.capabilities(1), 0);
        add(candidates, capabilities, "b", WorkerStatus.ACTIVE, CoreFixtures.capabilities(1), 0);
        List<Candidate> eligible = new ArrayList<>();
        List<Rejection> rejected = Eligibility.filter(request(PRIMES, Set.of("a"), Set.of()), candidates,
                capabilities::get, eligible);
        assertEquals(List.of("b"), eligible.stream().map(Candidate::workerId).toList());
        assertEquals("already failed this partition", rejected.get(0).reason());
        eligible.clear();
        Eligibility.filter(request(PRIMES, Set.of("a", "b"), Set.of()), candidates, capabilities::get, eligible);
        assertEquals(2, eligible.size(), "a failed worker is better than none");
        eligible.clear();
        List<Candidate> bBusy = List.of(candidates.get(0), new Candidate("b", WorkerStatus.ACTIVE, 1, 1, 4,
                1L << 30, 16 << 20, -1, 0, 0, 0, 0, 0));
        Eligibility.filter(request(PRIMES, Set.of("a"), Set.of()), bBusy, capabilities::get, eligible);
        assertTrue(eligible.isEmpty(), "while a healthy capable worker is only busy, the partition waits for it");
        eligible.clear();
        rejected = Eligibility.filter(request(PRIMES, Set.of(), Set.of("b")), candidates, capabilities::get, eligible);
        assertEquals(List.of("b"), eligible.stream().map(Candidate::workerId).toList());
        assertEquals("outside the task's worker limit", rejected.get(0).reason());
    }

    @Test
    void theSchedulerRecordsDecisionsAndExplainsWaiting() {
        Scheduler scheduler = new Scheduler(PolicyName.LEAST_LOADED, Policies.builtIn());
        assertEquals(List.of(PolicyName.values()), scheduler.available());
        Map<String, WorkerCapabilities> capabilities = Map.of("a", CoreFixtures.capabilities(2),
                "b", CoreFixtures.capabilities(2));
        List<Candidate> candidates = List.of(worker("a", 2, 2), worker("b", 2, 1));
        Instant now = Instant.parse("2026-11-22T12:00:00Z");
        Scheduler.Assign assign = assertInstanceOf(Scheduler.Assign.class, scheduler.decide(request(), "t/p0/a1",
                candidates, capabilities::get, PolicyName.DYNAMIC, now));
        AssignmentRecord record = assign.record();
        assertEquals("b", record.workerId());
        assertEquals(PolicyName.DYNAMIC, record.policy());
        assertEquals("t/p0/a1", record.attemptId());
        assertEquals(now, record.decidedAt());
        assertEquals(1, record.eligibleWorkers());
        assertEquals("a", record.rejected().get(0).workerId());
        assertEquals(1, record.workerState().assigned(), "the worker's state when it was chosen");
        assertTrue(record.decisionNanos() >= 0);

        Scheduler.Wait wait = assertInstanceOf(Scheduler.Wait.class, scheduler.decide(request(), "t/p0/a1",
                List.of(worker("a", 2, 2)), capabilities::get, null, now));
        assertTrue(wait.reason().contains("no eligible worker"), wait.reason());
        assertEquals("no workers are connected", ((Scheduler.Wait) scheduler.decide(request(), "t/p0/a1", List.of(),
                capabilities::get, null, now)).reason());
        assertThrows(IllegalArgumentException.class, () -> new Scheduler(PolicyName.DYNAMIC,
                List.of(new LeastLoadedPolicy())));
    }

    private static String pick(SchedulingPolicy policy, List<Candidate> eligible) {
        return policy.choose(request(), eligible, 0).selected().workerId();
    }

    private static Candidate worker(String id, int slots, int assigned) {
        return new Candidate(id, WorkerStatus.ACTIVE, slots, assigned, 4, 1L << 30, 16 << 20, -1, 0, 0, 0, 0, 0);
    }

    private static Candidate measured(String id, double opsPerSecond, double bytesPerSecond) {
        return new Candidate(id, WorkerStatus.ACTIVE, 2, 0, 4, 1L << 30, 1 << 24, -1, opsPerSecond, bytesPerSecond,
                opsPerSecond > 0 ? 5 : 0, 0, 0);
    }

    private static SchedulingRequest request() {
        return request(PRIMES, Set.of(), Set.of());
    }

    private static SchedulingRequest request(TaskRequirements requirements, Set<String> excluded,
            Set<String> allowed) {
        return new SchedulingRequest("t", "t/p0", 1, requirements, excluded, allowed);
    }

    private static void add(List<Candidate> candidates, Map<String, WorkerCapabilities> capabilities, String id,
            WorkerStatus status, WorkerCapabilities caps, int assigned) {
        capabilities.put(id, caps);
        candidates.add(new Candidate(id, status, caps.maxConcurrentTasks(), assigned, caps.cpuCores(),
                caps.maxTaskMemoryBytes(), caps.maxMessageBytes(), -1, 0, 0, 0, 0, 0));
    }

    private static WorkerCapabilities withAlgorithms(List<AlgorithmSupport> algorithms) {
        WorkerCapabilities c = CoreFixtures.capabilities(2);
        return new WorkerCapabilities(c.schemaVersion(), c.hostname(), c.osName(), c.osArch(), c.javaVersion(),
                c.runtimeVersion(), c.coreVersion(), c.cpuCores(), c.maxHeapBytes(), c.totalMemoryBytes(),
                c.maxConcurrentTasks(), c.maxTaskMemoryBytes(), c.maxMessageBytes(), algorithms, c.gpu(), c.labels(),
                c.faultSimulation());
    }

    private static Map<String, String> reasons(List<Rejection> rejected) {
        Map<String, String> map = new HashMap<>();
        rejected.forEach(r -> map.put(r.workerId(), r.reason()));
        return map;
    }
}
