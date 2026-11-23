# DistriCompute-Distributed — Integration Contract

| | |
|---|---|
| Status | **Draft, implemented.** Proposed by Contributor 2. Needs review by Contributor 1 (Core) and Contributor 3 (Application). |
| Builds on | Core integration contract, schema `0.1` (`docs/integration-contract.md` at the repository root) |
| Wire protocol | [network-protocol.md](network-protocol.md) (coordinator ↔ workers; not used by the Application) |

> **Status markers.** Each section says whether it is implemented on this branch. They describe the code, not
> plans.

## 1. What the runtime takes from Core

The runtime has no task model, algorithm, partition plan, result type or error code of its own. It uses Core's
types unchanged ([architecture §1.3](architecture.md#13-what-core-already-provides-to-this-runtime)):

* Requests are Core `TaskSpec` documents. `constraints.mode` selects the execution mode.
* The task status is Core's `TaskStatus`. Partition status is Core's `PartitionStatus`. The runtime adds
  **attempt** states for dispatch ([§4](#4-task-snapshots--implemented)).
* Final results are Core `TaskResult` documents. The runtime adds `DistributedMetrics` next to them and never
  changes Core's metric definitions.
* Errors are Core `ErrorInfo` documents with Core `ErrorCode` values. Runtime-specific failure reasons
  (worker lost, attempt timed out, and so on) appear in attempt records and events, and map to Core codes
  (`TIMEOUT`, `PARTITION_FAILED`, `INVALID_PARTIAL_RESULT`, `EXECUTION_FAILED`) in the final `ErrorInfo`.

## 2. What the runtime offers the Application

Two equivalent ways to integrate:

| Option | When | Status |
|---|---|---|
| **Embedded:** the Application starts a `Coordinator` in its own JVM and calls its Java API | One deployment unit; events delivered in-process (for example forwarded to WebSockets or SSE) | Implemented: [§2.1](#21-starting-a-coordinator-and-listing-workers--implemented)–[§2.4](#24-embedding-in-the-application-spring-boot) |
| **Standalone:** the coordinator runs as its own process; the Application calls its HTTP API and reads its event stream | The coordinator runs on another machine, or independently of the Application | Implemented (Phase 8): [§2.5](#25-http-api-of-a-standalone-coordinator--implemented) |

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

The Java signatures, JSON documents and HTTP routes follow. The Application repository was not available, so
the integration was exercised by `ApplicationIntegrationTest` (Java API) and `MonitoringApiTest` (HTTP), which
drive the runtime the way the Application would. It has not been run against the Application itself.

### 2.1 Starting a coordinator and listing workers  *(implemented)*

```java
CoordinatorConfig config = CoordinatorConfig.builder(SharedSecret.fromFile(Path.of("cluster.secret")))
        .port(7650)                       // worker port
        .build();
try (Coordinator coordinator = Coordinator.start(config)) {
    List<WorkerSnapshot> workers = coordinator.workers();          // connected and recently disconnected
    Optional<WorkerSnapshot> one = coordinator.worker("lab-pc-07");

    // Administration (added for the Application)
    coordinator.deactivateWorker("lab-pc-07", "maintenance");  // DEACTIVATED: no new attempts, running ones finish;
                                                                // survives reconnection
    coordinator.activateWorker("lab-pc-07");                    // ACTIVE again
    coordinator.disconnectWorker("lab-pc-07", "moved", false);  // SHUTDOWN(reconnect) + close; attempts retried
    coordinator.disconnectWorker("lab-pc-07", "retired", true); // SHUTDOWN(no reconnect): the worker process stops
    coordinator.forgetWorker("lab-pc-07");                      // drop a DISCONNECTED record and its history
}
```

Each returns `false` when the worker is unknown (or, for `disconnectWorker`, not connected; for `forgetWorker`,
still connected). Status changes are published as `WORKER_STATUS_CHANGED`, disconnections as `WORKER_DISCONNECTED`.

A `WorkerSnapshot` serializes to:

| Field | Meaning |
|---|---|
| `workerId`, `hostname`, `endpoint` | Identity and the address the coordinator sees |
| `status`, `statusReason`, `statusSince` | `ACTIVE`, `SUSPECT` (heartbeats late), `DRAINING` (leaving), `QUARANTINED` (repeated invalid results), `DEACTIVATED` (by an operator), `DISCONNECTED` |
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

* **Modes.** `DISTRIBUTED_CPU` runs on workers. `SEQUENTIAL_CPU` and `MULTITHREADED_CPU` run on the
  coordinator's machine through Core's `LocalExecutionEngine`, one at a time, with the same snapshots and
  events: `metrics` is `null` and `partitions` is empty, and Core's own metrics are in the result. `GPU` and
  `HYBRID` are refused with `UNSUPPORTED_EXECUTION_MODE` and an explanation: Core has no GPU backend, no worker
  reports one, and hybrid execution is not implemented.
* **Manual overrides.** If the requested mode differs from the current recommendation ([§2.3](#23-execution-advice--implemented)),
  the task still runs. The snapshot's `override` records `recommended`, `chosen`, Core's `chosenVerdict` and
  `chosenReasons`, and the user's `reason` (`SubmitOptions.withOverrideReason`, HTTP `overrideReason`). A
  `STRATEGY_OVERRIDE` event is published.
* **Full result values.** Core's `TaskResult` carries a summary and a digest. To receive the value itself (for
  example the product matrix), pass a `ResultSink`: `coordinator.submit(spec, options, (result, value) -> ...)`.
  The sink is called once, only for a `COMPLETED` task (so Core has verified the value), on a runtime thread, and
  before the task's future completes. The coordinator does not keep the value. An exception from the sink is logged
  and does not change the task's outcome. The value's type is the algorithm's result type (`Matrix`, `long[]`,
  `double[]`, …), and Core's `summarize(value).digest()` equals `result.result().digest()`.
* `submit` throws only when a task is refused outright: an unknown algorithm (`UNKNOWN_ALGORITHM`), a mode the
  runtime does not run here (`UNSUPPORTED_EXECUTION_MODE`), too many running tasks (`RESOURCE_EXHAUSTED`), or a
  task id already in use (`IllegalArgumentException`).
* Everything found later (Core validation errors, exhausted retries, verification failure, cancellation) ends the
  task with a `FAILED` or `CANCELLED` `TaskResult` and Core's `ErrorInfo`. The future never completes
  exceptionally.
* A task fails clearly rather than reporting success when a partition cannot be completed: a non-retryable
  failure, a partition Core marks as not retry-safe, or an exhausted retry budget. The message names the partition
  and the reason.

### 2.3 Execution advice  *(implemented)*

```java
ExecutionAdvice advice = coordinator.advise(spec);      // spec.mode is ignored; nothing runs
advice.recommended();       // e.g. MULTITHREADED_CPU, or null if no mode is suitable
advice.explanation();       // one paragraph; see the real output below
advice.assessment();        // Core StrategyRecommendation: per mode verdict, threads, estimatedSeconds, reasons, assumptions
advice.resources();         // the Core ResourceSnapshot the runtime supplied (workers, network, local node)
advice.runtime();           // eligibleWorkers, ineligibleWorkers (with reasons), totalSlots, busySlots,
                            // pendingPartitions, transferBytesPerSecond, latencyMillis, estimatedTransferBytes,
                            // executableModes, notes
coordinator.addCalibrations(Calibration.fromReport(benchmarkReport));   // history from Core benchmark reports
```

Core supplies the cost model and the algorithm's workload profile. The runtime supplies:

* the eligible workers (`ACTIVE`, offering the algorithm in a compatible version), each as a Core
  `ResourceSnapshot.Node` with `min(cores, slots)` cores and `slots × maxTaskMemoryBytes` memory;
* the network: the median measured transfer rate and the smallest measured round-trip transport time, from
  completed distributed attempts (`null` until something has been measured, and Core says so);
* the history: calibrations supplied by the Application, plus throughput measured by this runtime from completed
  local and distributed tasks (at most 1000 each).

A recommendation is not a promise that a mode is best everywhere: Core lists its assumptions, and the runtime's
measurements describe the cluster at `createdAt` only.

**Real output** of the packaged jar on 2026-11-23 (one 4-vCPU machine: a coordinator and two workers with 2 slots
each; task `primes.count` with `lowerBound` 2 and `upperBound` 50 000 000), printed by the `advise` client:

```text
Recommended: MULTITHREADED_CPU (Independent partitions on 4 threads (REDUCTION aggregation: O(p): sum of partition counts)). Distributed: SUITABLE (No network estimate supplied; communication cost is unknown; 2 workers with 4 cores). 2 eligible workers, 0 of 4 slots busy. Estimates are uncalibrated (no measured history for this algorithm).
  SEQUENTIAL_CPU     SUITABLE     Reference implementation; no partitioning overhead
  MULTITHREADED_CPU  RECOMMENDED  Independent partitions on 4 threads (REDUCTION aggregation: O(p): sum of partition counts)
  DISTRIBUTED_CPU    SUITABLE     No network estimate supplied; communication cost is unknown; 2 workers with 4 cores
  GPU                UNSUPPORTED  No GPU implementation: No GPU backend is installed (DistriCompute-Core ships none)
  HYBRID             UNSUPPORTED  Hybrid CPU/GPU/distributed execution is not implemented
  note: GPU: No GPU backend is installed (DistriCompute-Core ships none); no worker reports a GPU backend
  note: Hybrid CPU/GPU/distributed execution is not implemented
  note: The network has not been measured yet (it is measured from completed distributed attempts), so Core cannot estimate communication time
```

After one distributed run of the same task (8 partitions on the two workers), the same request no longer reports an
unknown network or uncalibrated estimates. The `DISTRIBUTED_CPU` line reads `SUITABLE  2 workers with 4 cores`.

Submitting the task with `SEQUENTIAL_CPU` and an override reason produced this `override` in its snapshot (same
digest as the distributed run, `sha256:dd9bad62…`):

```json
{
  "recordedAt": "2026-11-23T15:01:51.460160215Z",
  "recommended": "MULTITHREADED_CPU",
  "chosen": "SEQUENTIAL_CPU",
  "chosenVerdict": "SUITABLE",
  "chosenReasons": ["Reference implementation; no partitioning overhead"],
  "reason": "baseline for the report"
}
```

### 2.4 Embedding in the Application (Spring Boot)

An illustration, not compiled in this repository (the runtime has no Spring dependency):

```java
@Bean(destroyMethod = "close")
Coordinator coordinator() throws IOException {
    return Coordinator.start(CoordinatorConfig.builder(SharedSecret.fromFile(Path.of("/etc/districompute/secret")))
            .port(7650).build());
}

@GetMapping(path = "/api/experiments/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
SseEmitter events(@RequestHeader(value = "Last-Event-ID", defaultValue = "0") long after) {
    SseEmitter emitter = new SseEmitter(0L);
    EventBus.Subscription subscription = coordinator.events().subscribe(event -> {
        try {
            emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name(event.type().name())
                    .data(event));                       // or forward to a WebSocket topic
        } catch (IOException e) {
            emitter.complete();
        }
    }, after);
    emitter.onCompletion(subscription::close);
    return emitter;
}

@PostMapping("/api/experiments/{id}/runs")
Map<String, Object> run(@RequestBody TaskSpec spec) {   // parse TaskSpec bodies with ContractJson
    TaskHandle handle = coordinator.submit(spec, SubmitOptions.DEFAULTS);
    handle.completion().thenAccept(outcome -> repository.save(outcome.result(), outcome.snapshot()));
    return Map.of("taskId", handle.taskId());
}
```

The Application keeps experiments, users and history in its database. The runtime keeps no persistent state;
finished tasks stay queryable for the last 1000 tasks (`TaskDefaults.retainedTasks`).

### 2.5 HTTP API of a standalone coordinator  *(implemented)*

Started with `coordinator --http-port 8650` (binds `127.0.0.1` unless `--http-bind` says otherwise). If a token
is configured (`--http-token-file` or `DISTRICOMPUTE_HTTP_TOKEN`), every request needs
`Authorization: Bearer <token>`. `--http-cors-origin` adds CORS headers for a browser client. Errors are
`{"error": {"code": "...", "message": "..."}}`.

| Method and path | Response |
|---|---|
| `GET /api/v1/health` | `status`, `coordinatorId`, `protocolVersion`, `runtimeVersion`, `coreVersion`, `workerPort`, `workersConnected` |
| `GET /api/v1/workers`, `GET /api/v1/workers/{id}` | `WorkerSnapshot` list or one (404 if unknown) |
| `POST /api/v1/tasks` | Body `{"task": <Core TaskSpec>, "policy": "DYNAMIC", "partitionsPerSlot": 2, "attemptTimeoutMillis": 60000, "maxAttempts": 3, "maxWorkers": 2, "overrideReason": "..."}` (all but `task` optional). **202** with `taskId`, `experimentId`, `location`. 400 for an invalid `TaskSpec` (Core's violations included) or an unknown algorithm or mode, 409 for a duplicate task id, 503 when too many tasks are running. |
| `GET /api/v1/tasks` | `TaskSummary` list (no per-partition detail) |
| `GET /api/v1/tasks/{taskId}` | `TaskSnapshot`, with Core's `TaskResult` once finished |
| `POST /api/v1/tasks/{taskId}/cancel` | `{"taskId": "...", "cancelled": true}` |
| `GET /api/v1/metrics` | `CoordinatorMetrics`: workers per status, slots, tasks, attempts, retries, bytes |
| `GET /api/v1/scheduling`, `PUT /api/v1/scheduling` | `{"policy": "LEAST_LOADED", "available": [...]}`; `PUT` with `{"policy": "DYNAMIC"}` |
| `GET /api/v1/catalog` | Core's catalog entries plus the connected workers that offer each algorithm |
| `POST /api/v1/advice` | Body: a Core `TaskSpec`, or `{"task": <TaskSpec>}`. Returns an `ExecutionAdvice` ([§2.3](#23-execution-advice--implemented)) |
| `GET /api/v1/calibrations`, `POST /api/v1/calibrations` | The throughput history used for advice; `POST` adds a JSON array of Core `Calibration` objects (`algorithm`, `mode`, `threads`, `operationsPerSecond`, `problemOperations`) |
| `GET /api/v1/events?after=<seq>&limit=<n>` | Events after a sequence number, from the history (10 000 events) |
| `GET /api/v1/events/stream?after=<seq>` | **Server-Sent Events**: `id: <sequence>`, `event: <type>`, `data: <event JSON>`. Resumes after `Last-Event-ID`. A `: keep-alive` comment every 15 s. At most 32 streams. |

## 3. Events  *(implemented)*

Java: `coordinator.events().subscribe(listener)` (or `subscribe(listener, afterSequence)` to replay the history
first), and `coordinator.events().recent(afterSequence, limit)`. HTTP: the two `events` routes above.

```json
{ "sequence": 42, "timestamp": "2026-11-22T15:30:01.123Z", "type": "ATTEMPT_ASSIGNED",
  "workerId": "lab-pc-07", "experimentId": "exp-42", "taskId": "t-1", "partitionId": "t-1/p3",
  "attemptId": "t-1/p3/a1", "message": "t-1/p3/a1 -> lab-pc-07",
  "details": { "attempt": 1, "policy": "LEAST_LOADED",
               "explanation": "least loaded: lab-pc-07 uses 0 of 4 slots (0%); the lowest of 3 eligible workers",
               "eligibleWorkers": 3, "rejectedWorkers": 0, "decisionNanos": 41200 } }
```

| Type | `details` keys |
|---|---|
| `COORDINATOR_STARTED`, `COORDINATOR_STOPPING` | `coordinatorId`, `port`, `tls`, `policy` |
| `WORKER_REGISTERED` | `hostname`, `cpuCores`, `maxConcurrentTasks`, `algorithms`, `gpuAvailable`, `faultSimulation`, `reconnection`, `sessionId` |
| `WORKER_REJECTED` | `reason` |
| `WORKER_STATUS_CHANGED` | `from`, `to`, `reason` |
| `WORKER_DISCONNECTED` | `reason`, `attemptsAffected` |
| `TASK_SUBMITTED` | `algorithm`, `policy`, `priority` |
| `TASK_STATUS_CHANGED` | `from`, `to` (Core `TaskStatus`: `VALIDATING`, `QUEUED`, `RUNNING`, `AGGREGATING`, terminal), `partitions` |
| `TASK_PROGRESS` | `completedPartitions`, `totalPartitions`, `percent` |
| `TASK_FINISHED` | `status`, `metrics` (`DistributedMetrics`), and `digest` and `verificationPassed`, or `errorCode` and `error` |
| `ATTEMPT_ASSIGNED` | `attempt`, `policy`, `explanation`, `eligibleWorkers`, `rejectedWorkers`, `decisionNanos` |
| `ATTEMPT_ACCEPTED` | — |
| `ATTEMPT_PROGRESS` | `completedUnits`, `totalUnits`, `unit` |
| `ATTEMPT_SUCCEEDED` | `status`, `attempt`, `computeNanos`, `transferNanos`, `roundTripNanos`, `bytes` |
| `ATTEMPT_FAILED` | `status` (`REJECTED`, `FAILED`, `TIMED_OUT`, `LOST`, `INVALID`, `CANCELLED`, `SUPERSEDED`), `attempt`, `failure`, `retryable` |
| `RESULT_IGNORED` | `classification` (`DUPLICATE`, `LATE`), `detail` |
| `PARTITION_RETRIED` | `failureKind`, `reason`, `countedFailures`, `nextAttempt` |
| `STRATEGY_OVERRIDE` | `recommended`, `chosen`, `chosenVerdict`, `chosenReasons`, `reason` |
| `SCHEDULING_POLICY_CHANGED` | `from`, `to` |
| `EVENTS_DROPPED` | `firstMissed`, `lastMissed`, `count`: this subscriber was too slow and should re-read the state |

**Ordering and delivery.** Sequence numbers are global and strictly increasing, and subscribers receive events
in that order. Publishing never blocks the runtime. A subscriber with a full queue (10 000 events) loses events
and then receives `EVENTS_DROPPED`. The events of a task are in causal order: an attempt is assigned before it
succeeds, and partitions complete before the task finishes.

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
