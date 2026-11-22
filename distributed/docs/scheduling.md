# Scheduling

The coordinator decides, for every attempt of every partition, which worker runs it. Decisions are made one at a
time on the control loop (`scheduler` package), and each decision is recorded.

## 1. Dispatch model

* **Partitions.** Core plans the partitions. Unless the task sets its own partitioning, the runtime asks for
  `partitionsPerSlot × free capable slots` (default 2 per slot). Having more partitions than slots
  (over-decomposition) lets faster workers take more of them.
* **Slots.** A worker has `maxConcurrentTasks` slots. The coordinator never sends a worker more attempts than it
  has free slots, so attempts never queue on a worker. A slot stays occupied until the worker's final message
  for the attempt arrives, or until the worker is lost. That holds even after the coordinator has cancelled or
  timed out the attempt.
* **Pending queue.** Partitions that have no worker yet wait in a bounded queue on the coordinator (default
  100 000 entries across all tasks), ordered by task priority (`HIGH`, `NORMAL`, `LOW`) and then by submission
  order. A task that does not fit is refused with `RESOURCE_EXHAUSTED`.
* **Dynamic assignment.** Scheduling runs whenever capacity may have appeared: a task was planned, a worker
  registered or recovered, a result or failure freed a slot, or a heartbeat changed a worker's capacity.
  Pending partitions are assigned as soon as a suitable worker becomes available. If one task's next partition
  cannot be placed, the scheduler moves on to the next task, so a blocked task does not hold up the others.

## 2. Eligibility (always applied first)

A policy only ranks workers that pass all of these rules (`Eligibility`). Every rejected worker is listed in the
decision record with its reason.

| Rule | Rejection reason recorded |
|---|---|
| Worker status is `ACTIVE` | `status SUSPECT`, `status DRAINING`, `status QUARANTINED` |
| Worker is inside the task's worker limit (`maxWorkers`) | `outside the task's worker limit` |
| Worker offers the algorithm | `does not offer primes.count` |
| …in `DISTRIBUTED_CPU` mode | `does not run … partitions` |
| …in a compatible version (same major version, minor version at least the coordinator's) | `offers primes.count@0.9.0, needs 1.0.0` |
| Estimated memory per attempt ≤ worker's `maxTaskMemoryBytes` | `needs about … MiB per attempt, worker allows … MiB` |
| Estimated work-unit size ≤ worker's `maxMessageBytes` | `work unit of about … MiB exceeds the worker's … MiB message limit` |
| A free slot | `no free slot (2 of 2 busy)` |
| Not a worker that already failed this partition | `already failed this partition` (*soft rule*: such a worker is used again only when no other capable worker exists, because the failure may have been transient. While another capable worker is merely busy, the partition waits for it.) |

The requirements come from Core's workload profile divided by the number of partitions. They are estimates.
The worker re-checks the real work unit, and a frame that is really too large fails the attempt, which is then
retried elsewhere.

## 3. Policies

Select a policy per task (`SubmitOptions.withPolicy`), or change the coordinator default
(`Coordinator.setSchedulingPolicy`, default `LEAST_LOADED`).

| Policy | Chooses | Explanation recorded (example) |
|---|---|---|
| `ROUND_ROBIN` | The next eligible worker after the last one chosen, in worker-id order. The rotation continues across tasks; ineligible workers are skipped, not waited for. | `round robin: w2 follows w1 among [w1, w2, w3]` |
| `LEAST_LOADED` | The lowest slot utilization (`assigned / maxConcurrentTasks`), so a machine with more slots takes proportionally more. Ties: fewer assigned attempts, then worker id. | `least loaded: w1 uses 1 of 4 slots (25%); the lowest of 3 eligible workers` |
| `CAPABILITY_AWARE` | The most spare capacity: idle cores (`freeSlots × cores / slots`), then memory headroom over the attempt's estimated need. | `capability-aware: w3 has 6.0 idle cores (8 cores, 1 of 4 slots busy) and 12.5x the memory the attempt needs; best of 3 capable workers` |
| `PERFORMANCE_AWARE` | The shortest estimated time: `operations / observed ops/s + work-unit bytes / observed transfer rate`, from that worker's moving averages for the same algorithm. A worker with no observations for the algorithm is tried first (exploration). | `performance-aware: w1 estimated 0.420 s (2.38e+08 ops/s over 6 attempts, 95.0 MB/s transfer); w2 0.950 s` |
| `DYNAMIC` | Demand-driven self-scheduling: the worker that has waited longest for work, then the one assigned least recently. With over-decomposition, fast workers come back for work more often and so take more partitions, without any performance model. | `dynamic: w2 has waited longest for work (idle 412 ms)` |

All policies are deterministic for the same worker states, except that the round-robin rotation depends on
earlier decisions.

**Honest limitations.**

* No policy is optimal everywhere. Performance-aware estimates are only as good as recent history and ignore
  load changes since then. Dynamic scheduling relies on having enough partitions.
* All policies choose among workers that have a free slot *now*. None of them holds an attempt back to wait for
  a faster busy worker.
* Core plans equal-sized partitions, so no policy can give a slow worker a smaller piece.

## 4. Decision records

Every assignment produces an `AssignmentRecord`. It is stored in the attempt's snapshot, and Phase 8 also
publishes it as an event.

| Field | Meaning |
|---|---|
| `decidedAt` | When the decision was made |
| `taskId`, `partitionId`, `attemptId` | What was scheduled |
| `policy` | The policy that decided |
| `workerId` | The selected worker |
| `workerState` | The selected worker's state at that moment: status, slots, assigned, cores, memory limits, observed throughput and transfer rate, idle time |
| `explanation` | The policy's reason, as in the table above |
| `eligibleWorkers` | How many workers passed the eligibility rules |
| `rejected` | Every other connected worker with the reason it was not eligible |
| `decisionNanos` | Time spent deciding (part of the task's `schedulingNanos`) |

## 5. Performance history

When a result is accepted, the coordinator updates the worker's history for that algorithm:

* kernel throughput, `operations of the partition / kernel seconds`;
* transfer rate, `bytes of the TASK and RESULT frames / transfer seconds`.

Both are exponentially weighted moving averages with α = 0.3, so they follow changes in a worker's load within
a few attempts. The history is kept across reconnections of the same worker id, and it appears in the worker's
snapshot (`performance`).

## 6. Adding a policy

1. Implement `SchedulingPolicy`: `name()` and
   `choose(SchedulingRequest, List<Candidate> eligible, long nowNanos)`. Return the chosen candidate and an
   explanation. `eligible` is never empty and is sorted by worker id.
2. Add a value to `PolicyName`, and register the policy in `Policies.builtIn()`.
3. Add unit tests on constructed `Candidate` states (see `SchedulingPoliciesTest`), and an entry to the table above.

Policies run on the control loop, so they must be fast and must not block. A policy may keep state between
decisions (round robin does), because each coordinator owns its policy instances.
