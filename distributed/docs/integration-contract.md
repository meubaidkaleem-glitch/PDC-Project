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
| **Embedded:** the Application starts a `Coordinator` in its own JVM and calls its Java API | One deployment unit; events delivered in-process (for example forwarded to WebSockets or SSE) | Planned (Phase 3–8, 11) |
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

## 3. Events

Planned (Phase 8). Every event has a global `sequence`, a `timestamp`, a `type` and the ids that apply
(`experimentId`, `taskId`, `partitionId`, `attemptId`, `workerId`). Planned types: worker registered, status
changed and disconnected; task submitted, status changed, progress, completed, failed and cancelled; partition
assigned (with the scheduling decision), accepted, progress, completed, failed, timed out and retried; result
rejected (with Core's classification); scheduling policy changed; strategy override recorded.

## 4. Task snapshots

Planned (Phase 5). A snapshot contains Core's task status, one entry per partition (Core partition status plus
the attempts), and for each attempt: `attemptId`, worker, attempt state, scheduling decision, timings and failure
reason.

Attempt states: `DISPATCHED`, `ACCEPTED`, `RUNNING`, `SUCCEEDED`, `REJECTED`, `FAILED`, `TIMED_OUT`, `LOST`,
`CANCELLED`.

## 5. Distributed metrics

Planned (Phase 8). Computation, communication and scheduling are reported separately, as Core's contract
(§10) requires.

## 6. Versioning

The Java API follows the runtime version (`0.y.z` until the contracts reach `1.0`). JSON documents carry
`schemaVersion` with Core's rules: a different major version is rejected; while the major version is `0`, the
minor version must match too. The wire protocol has its own `protocolVersion` with the same rules.
