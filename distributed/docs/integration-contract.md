# DistriCompute-Distributed — Integration Contract

| | |
|---|---|
| Status | **Draft.** Proposed by Contributor 2. Needs review by Contributor 1 (Core) and Contributor 3 (Application). |
| Builds on | Core integration contract, schema `0.1` (`docs/integration-contract.md` at the repository root) |
| Wire protocol | [network-protocol.md](network-protocol.md) (coordinator ↔ workers; not used by the Application) |

> **Status markers.** Each section says whether it is implemented on this branch. They describe the code, not
> plans.

## 1. What the runtime takes from Core

The runtime has no task model, algorithm, partition plan, result type or error code of its own. It uses Core's
types unchanged ([architecture §1.3](architecture.md#13-what-core-already-provides-to-this-runtime)):

* Requests are Core `TaskSpec` documents. `constraints.mode` selects the execution mode.
* The task status is Core's `TaskStatus`. Partition status is Core's `PartitionStatus`. The runtime adds
  **attempt** states for dispatch ([§4](#4-task-snapshots)).
* Final results are Core `TaskResult` documents. The runtime adds `DistributedMetrics` next to them and never
  changes Core's metric definitions.
* Errors are Core `ErrorInfo` documents with Core `ErrorCode` values. Runtime-specific failure reasons
  (worker lost, attempt timed out, and so on) appear in attempt records and events, and map to Core codes
  (`TIMEOUT`, `PARTITION_FAILED`, `INVALID_PARTIAL_RESULT`, `EXECUTION_FAILED`) in the final `ErrorInfo`.

## 2. What the runtime offers the Application

Two equivalent ways to integrate:

| Option | When | Status |
|---|---|---|
| **Embedded:** the Application starts a `Coordinator` in its own JVM and calls its Java API | One deployment unit; events delivered in-process (for example forwarded to WebSockets or SSE) | Partly implemented: workers (Phase 3), tasks, results, snapshots, metrics, cancellation, default policy (Phase 5) |
| **Standalone:** the coordinator runs as its own process; the Application calls its HTTP API and reads its event stream | The coordinator runs on another machine, or independently of the Application | Planned (Phase 8, 11) |

Both expose the same operations:

| Operation | Purpose |
|---|---|
| Worker list and details | Registration events, status, capabilities, load, history |
| Execution advice | Core's strategy assessment with the runtime's resources (workers, network, load) |
| Task submission | A `TaskSpec` plus runtime options (scheduling policy, worker limit, timeouts, manual strategy override) |
| Task status | Core status, partitions, attempts, scheduling decisions, metrics |
| Result retrieval | Core `TaskResult` and `DistributedMetrics` |
| Cancellation | Cooperative, propagated to workers |
| Scheduling configuration | Read and change the default policy |
| Events | Worker, task, retry and metrics events, in order, with sequence numbers |

The exact Java signatures, JSON documents and HTTP routes are added to this document as each phase implements
them.

### 2.1 Starting a coordinator and listing workers  *(implemented)*

```java
CoordinatorConfig config = CoordinatorConfig.builder(SharedSecret.fromFile(Path.of("cluster.secret")))
        .port(7650)                       // worker port
        .build();
try (Coordinator coordinator = Coordinator.start(config)) {
    List<WorkerSnapshot> workers = coordinator.workers();          // connected and recently disconnected
    Optional<WorkerSnapshot> one = coordinator.worker("lab-pc-07");
}
```

A `WorkerSnapshot` serializes to:

| Field | Meaning |
|---|---|
| `workerId`, `hostname`, `endpoint` | Identity and the address the coordinator sees |
| `status`, `statusReason`, `statusSince` | `ACTIVE`, `SUSPECT` (heartbeats late), `DRAINING` (leaving), `QUARANTINED` (repeated invalid results), `DISCONNECTED` |
| `sessionId`, `registeredAt`, `registrations` | Current registration; how often this id has registered |
| `lastHeartbeatAt`, `load` | Latest heartbeat: active tasks, free heap, load average, process CPU load, completed and failed totals |
| `capabilities` | As reported at registration ([network-protocol.md §3.1](network-protocol.md#31-payload-rules)) |
| `maxConcurrentTasks`, `assignedTasks` | Current limit (may change through heartbeats) and the attempts the coordinator has assigned |
| `counters` | `assigned`, `succeeded`, `failed`, `rejected`, `timedOut`, `lost`, `invalidResults`, `duplicateResults` |
| `performance` | Per algorithm: `samples`, `operationsPerSecond`, `transferBytesPerSecond`, `meanKernelMillis` |

### 2.2 Submitting tasks and reading results  *(implemented)*

```java
TaskSpec spec = TaskSpec.builder("matrix.multiply").taskId(uuid).experimentId("exp-42")
        .parameter("rowsA", 1024).parameter("colsA", 1024).parameter("colsB", 1024)
        .mode(ExecutionMode.DISTRIBUTED_CPU).build();
TaskHandle handle = coordinator.submit(spec, SubmitOptions.DEFAULTS
        .withPolicy(PolicyName.PERFORMANCE_AWARE)      // optional; the coordinator default otherwise
        .withMaxAttempts(3)                           // attempts per partition, counting failures
        .withAttemptTimeout(Duration.ofMinutes(5))
        .withMaxWorkers(2));                          // limited worker availability (experiments)
TaskSnapshot progress = handle.snapshot();             // any time
TaskOutcome outcome = handle.await(Duration.ofMinutes(10));
TaskResult result = outcome.result();                  // Core's document: status, digest, verification, metrics
TaskSnapshot detail = outcome.snapshot();              // partitions, attempts, decisions, DistributedMetrics
coordinator.cancel(spec.taskId());                     // or handle.cancel()
```

* `submit` throws only when a task is refused outright: an unknown algorithm (`UNKNOWN_ALGORITHM`), a mode the
  runtime does not run here (`UNSUPPORTED_EXECUTION_MODE`), too many running tasks (`RESOURCE_EXHAUSTED`), or a
  task id already in use (`IllegalArgumentException`).
* Everything found later (Core validation errors, exhausted retries, verification failure, cancellation) ends the
  task with a `FAILED` or `CANCELLED` `TaskResult` and Core's `ErrorInfo`. The future never completes
  exceptionally.
* A task fails clearly rather than reporting success when a partition cannot be completed: a non-retryable
  failure, a partition Core marks as not retry-safe, or an exhausted retry budget. The message names the partition
  and the reason.

## 3. Events

Planned (Phase 8). Every event has a global `sequence`, a `timestamp`, a `type` and the ids that apply
(`experimentId`, `taskId`, `partitionId`, `attemptId`, `workerId`). Planned types: worker registered, status
changed and disconnected; task submitted, status changed, progress, completed, failed and cancelled; partition
assigned (with the scheduling decision), accepted, progress, completed, failed, timed out and retried; result
rejected (with Core's classification); scheduling policy changed; strategy override recorded.

## 4. Task snapshots  *(implemented)*

`TaskSnapshot` (JSON field names as listed):

| Field | Meaning |
|---|---|
| `taskId`, `experimentId`, `algorithm`, `mode` | From the `TaskSpec` |
| `status` | Core `TaskStatus` (`VALIDATING` while Core plans, then Core's session status) |
| `policy` | Scheduling policy used |
| `submittedAt`, `finishedAt` | Coordinator times |
| `totalPartitions`, `completedPartitions`, `attempts`, `retries` | Progress counters |
| `error` | Core `ErrorInfo` once the task failed or was cancelled |
| `metrics` | `DistributedMetrics` ([§5](#5-distributed-metrics--implemented)) |
| `partitions[]` | `index`, `partitionId`, `status` (Core `PartitionStatus`) and `attempts[]` |
| `result` | Core's `TaskResult` once the task has ended |

Each attempt (`AttemptSnapshot`) has `attemptId` (`<partitionId>/a<n>`), `attempt`, `workerId`, `status`, the
`assignment` (scheduling decision record, [scheduling.md](scheduling.md)), `dispatchedAt`, `acceptedAt`,
`finishedAt`, the timings `serializationNanos`, `roundTripNanos`, `transferNanos`, `workerQueueNanos`,
`workerExecuteNanos`, `computeNanos` and `resultProcessingNanos` (`-1` = not measured), `bytesSent`,
`bytesReceived`, progress (`progressUnits`, `progressTotal`, `progressUnit`), and `failure` with `retryable`.

Attempt states: `DISPATCHING`, `DISPATCHED`, `ACCEPTED`, `RUNNING`, then exactly one of `SUCCEEDED`, `REJECTED`,
`FAILED`, `TIMED_OUT`, `LOST`, `INVALID` (Core rejected the result), `CANCELLED` or `SUPERSEDED` (another attempt
of the same partition delivered first).

## 5. Distributed metrics  *(implemented)*

Core's `ExecutionMetrics` inside `TaskResult` keep Core's definitions. For a distributed task, Core's
`computeNanos` is the coordinator's wall time from the first dispatch to the last accepted result, so it
*includes* communication. `DistributedMetrics` separates the parts:

| Field | Clock | Meaning |
|---|---|---|
| `totalNanos` | coordinator | Submission to end of task |
| `queueNanos` | coordinator | Submission to first dispatch (includes planning) |
| `planningNanos` | coordinator | Core session opening: validation, workload analysis, partition plan, input preparation |
| `distributedNanos` | coordinator | First dispatch to last accepted result |
| `aggregationNanos` | coordinator | Core aggregation and verification after the last result |
| `kernelNanos` | workers | Σ kernel time of accepted attempts (**computation**) |
| `workerExecuteNanos` | workers | Σ decode + kernel + encode of all attempts that reported timings |
| `transferNanos` | both, never compared | Σ (coordinator round trip − worker residence) of accepted attempts (**communication**) |
| `serializationNanos`, `resultProcessingNanos` | coordinator | Σ encoding of work units and frames, Σ decoding and classifying results |
| `schedulingNanos` | coordinator | Σ time in scheduling decisions and their bookkeeping (**scheduling overhead**) |
| `bytesSent`, `bytesReceived` | coordinator | Σ `TASK` and `RESULT` frame sizes |
| `partitions`, `attempts`, `succeededAttempts`, `failedAttempts`, `retries`, `workersUsed` | | Counts |
| `workerUtilization` | | `workerExecuteNanos / (distributedNanos × slots of the workers used)` |
| `throughputPartitionsPerSecond` | | Completed partitions per second of `distributedNanos` |
| `loadImbalance` | | Largest per-worker kernel time ÷ mean (1.0 = balanced) |
| `perWorker[]` | | `workerId`, `attempts`, `succeeded`, `kernelNanos`, `executeNanos`, `transferNanos`, `bytes` |

Sums run over attempts, so with parallel workers they can exceed wall time. Comparisons with sequential and local
parallel execution should use Core's `executionNanos` (local) against `distributedNanos` plus
`aggregationNanos` (distributed), and report communication and scheduling from this table.

## 6. Versioning

The Java API follows the runtime version (`0.y.z` until the contracts reach `1.0`). JSON documents carry
`schemaVersion` with Core's rules: a different major version is rejected; while the major version is `0`, the
minor version must match too. The wire protocol has its own `protocolVersion` with the same rules.
