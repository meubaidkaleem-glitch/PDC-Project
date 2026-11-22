# DistriCompute-Distributed — Architecture

| | |
|---|---|
| Component | DistriCompute-Distributed (Contributor 2): coordinator, workers, protocol, scheduling, recovery |
| Location | `distributed/` on branch `feature/distributed-engine` of the GitHub repository `PDC-Project` |
| Depends on | DistriCompute-Core `0.1.0-SNAPSHOT` (contract schema `0.1`, draft), built from the same commit |
| Used by | DistriCompute-Application (Contributor 3), through a Java API or the coordinator's HTTP API |

## 1. Phase 1 inspection

### 1.1 What the repository contains

| Branch | Content at the start of this work |
|---|---|
| `main` | Only the initial commit (`bf0a6c1`) |
| `feature/core-engine` | DistriCompute-Core: task model, 10 verified algorithms, local engine, partitioning, aggregation, payload codecs, metrics, strategy advisor. CI green. |
| `feature/distributed-engine` | Created from `main`, empty (this work) |
| `feature/application-gui` | Created from `main`, empty |

The three components of the specification ("three repositories") live in **one** GitHub repository with one
branch per component. Core sits at the repository root.

### 1.2 Layout decision

`feature/distributed-engine` starts from `feature/core-engine` and adds the runtime as a separate Maven
project in `distributed/`.

* The runtime needs Core's code to compile and run. Starting from `main` would mean re-implementing or vendoring
  it, which the shared rules forbid.
* A subdirectory leaves every Core file untouched, so merging Core and then Distributed into `main` cannot
  conflict.
* One checkout builds everything: `./mvnw install` (Core), then `./mvnw -f distributed/pom.xml verify`.
* If the team later splits the repositories, `distributed/` becomes the root of DistriCompute-Distributed and
  depends on a pinned Core tag. That is the build mechanism Core's contract proposes (§17, question 1).

### 1.3 What Core already provides to this runtime

Core's integration contract (`docs/integration-contract.md` at the repository root) defines everything
algorithm-specific. The runtime uses it and never duplicates it:

| Need | Core type | Used for |
|---|---|---|
| Task model | `TaskSpec`, `TaskStatus`, `ExecutionConstraints`, `ExecutionMode` | Requests from the Application; the task status reported by the runtime is Core's status |
| Algorithm catalog | `AlgorithmRegistry`, `AlgorithmInfo`, `AlgorithmCapabilities`, `AlgorithmRef` | Worker capability reports; eligibility checks; validation |
| Partitioning (coordinator) | `PartitionedTaskSession` | Validation, planning, work-unit encoding, partial-result classification, aggregation, verification |
| Execution (worker) | `PartitionWorker` | Decoding a work unit, running the kernel, encoding the partial result |
| Messages | `WorkUnitMessage`, `PartialResultMessage`, `PartitionDescriptor`, `EncodedPayload` | Embedded unchanged in the protocol's `TASK` and `RESULT` messages |
| Results and errors | `TaskResult`, `ExecutionMetrics`, `ErrorInfo`, `ErrorCode` | Final results; failure reasons; retryability |
| Strategy | `ResourceSnapshot`, `StrategyAdvisor`, `Calibration` | The runtime supplies workers, network and history; Core assesses the modes |
| Local modes | `LocalExecutionEngine` | `SEQUENTIAL_CPU` and `MULTITHREADED_CPU` requests routed through the runtime |
| Cancellation | `CancellationSource` | Cooperative cancellation of running partitions on workers |
| GPU | `GpuStatus` | Workers report GPU only if Core detects a working backend (none exists today) |

### 1.4 Answers proposed to Core's open questions (contract §17)

1. **Build integration.** Same repository and commit (§1.2). After a split: a pinned Core tag, installed in CI.
2. **Payload transport.** Base64 inside the JSON envelope for the first release, so that every message is one
   self-describing line. Cost: base64 adds a third to the payload size. The `bytesSent` and `bytesReceived`
   metrics make this cost visible. Binary frames remain a possible optimization.
3. **Task ids.** Opaque strings, used unchanged. The runtime derives attempt ids from Core's partition ids.
4. **Jackson.** The runtime uses Jackson 3 from the same BOM as Core.
5. **Strategy history.** The runtime records per-worker throughput observations and also accepts Core benchmark
   reports supplied by the Application.

## 2. Responsibilities

| Concern | Core | Distributed (this component) | Application |
|---|---|---|---|
| Task model, validation, algorithms, partition plans, aggregation, verification | ✔ | uses | uses |
| Processes, sockets, protocol, authentication | | ✔ | |
| Worker registration, capabilities, health | | ✔ | displays |
| Scheduling, dispatch, retries, timeouts | | ✔ | configures |
| Distributed metrics (communication, scheduling, load) | defines compute metrics | ✔ | stores, displays |
| Strategy assessment | ✔ (cost model) | supplies resources | displays, overrides |
| Experiments database, REST API for users, GUI | | | ✔ |

## 3. Overview

```
            DistriCompute-Application (Spring Boot, React)
                 │  Java API (embedded)  or  HTTP + Server-Sent Events (standalone coordinator)
┌────────────────▼─────────────────────────────────────────────────────────────────┐
│ Coordinator process                                                                │
│  ┌──────────────┐   ┌──────────────────────────── control loop (one thread) ─────┐ │
│  │ HTTP API     │   │ WorkerRegistry   Experiments   Dispatcher   Recovery       │ │
│  │ (optional)   │──▶│ SchedulingPolicy  RetryPolicy   heartbeat and timeout ticks │ │
│  └──────────────┘   └──────▲────────────────────┬────────────────────────────────┘ │
│  EventBus ◀── events ──────┘                    │ work units (Core session)         │
│  LocalExecutor (Core LocalExecutionEngine)       │                                  │
│  Connection per worker: reader + writer threads  │                                  │
└───────────────────────────────────────▲──────────┼──────────────────────────────────┘
                                        │ TCP: newline-delimited JSON, versioned, authenticated
           ┌────────────────────────────┼─────────────────────────────┐
┌──────────┴───────────┐     ┌──────────┴───────────┐      ┌──────────┴───────────┐
│ Worker process       │     │ Worker process       │      │ Worker process       │
│ connector, heartbeat │     │ ...                  │      │ ...                  │
│ bounded executor     │     │                      │      │                      │
│ Core PartitionWorker │     │                      │      │                      │
└──────────────────────┘     └──────────────────────┘      └──────────────────────┘
```

## 4. Packages

| Package | Responsibility |
|---|---|
| `protocol` | Envelope, message types and payload records, protocol version, validation, JSON codec |
| `communication` | Framing with size limits, connections with bounded outbound queues, TLS sockets |
| `security` | Shared-secret registration authentication (HMAC-SHA256, both directions) |
| `registry` | Worker records, capabilities, status, load, performance history |
| `resource` | Capability detection on workers; resource snapshots for Core's strategy advisor |
| `scheduler` | Eligibility rules, scheduling policies, decision records |
| `task` | Distributed state of a task: partitions, attempts, attempt ids |
| `coordinator` | Server, control loop, dispatcher, experiments, local-mode execution, the public facade |
| `worker` | Worker process: connector, registration, heartbeats, bounded execution through Core |
| `recovery` | Failure classification, retry policy, timeouts, heartbeat monitoring |
| `metrics` | Attempt and task metrics that separate computation, communication and scheduling |
| `monitoring` | Runtime events, event bus, HTTP API with Server-Sent Events |
| `simulation` | Fault injection, active only when explicitly enabled |

## 5. Threading model

* **Control loop (coordinator).** One thread owns all mutable coordinator state: registry, tasks, attempts,
  worker slots. Connection threads parse messages and post commands; a ticker posts timeout checks. This makes
  scheduling decisions sequential and observable, and removes lock-ordering problems.
* **Connections.** Each connection has a reader thread and a writer thread (virtual threads) and a bounded
  outbound queue. A peer that stops reading cannot make the coordinator buffer without limit.
* **Heavy Core calls off the loop.** Opening a session (validation, planning, input generation), encoding work
  units and final aggregation and verification run on small bounded executors. They post their outcome back
  to the loop.
* **Workers.** A fixed pool of platform threads, sized by `maxConcurrentTasks`, runs Core kernels. Network I/O
  runs on separate threads, so heartbeats continue while kernels run.

## 6. End-to-end flow

| Step (specification §2) | Component |
|---|---|
| 1–2. The Application submits a task and may ask for an execution plan | Facade or HTTP API; `StrategyService` builds a `ResourceSnapshot` and calls Core's advisor |
| 3. The runtime receives the task and its constraints | `Coordinator.submit(TaskSpec, SubmitOptions)` |
| 4. Eligible workers are discovered | `WorkerRegistry` and eligibility rules |
| 5. The scheduler selects workers | `SchedulingPolicy`, one decision per attempt |
| 6. The workload is partitioned | Core `PartitionedTaskSession.open` |
| 7. Tasks are sent to workers | Dispatcher: `TASK` messages |
| 8. Workers execute through Core | `WorkerNode`, Core `PartitionWorker` |
| 9. Workers return structured results | `RESULT` messages carrying Core `PartialResultMessage` |
| 10. The coordinator validates and aggregates | Metadata checks, then Core `accept` and `complete` (aggregation and verification) |
| 11. Failures are handled and eligible work retried | `recovery`: heartbeats, timeouts, retry policy, attempt ids |
| 12. Progress and final metrics reach the Application | `EventBus`, task snapshots, `DistributedMetrics` |

## 7. Identifiers

| Id | Created by | Format |
|---|---|---|
| `experimentId` | Application | Copied from `TaskSpec.experimentId` (groups repeated runs) |
| `taskId` | Application | Copied from `TaskSpec.taskId` |
| `partitionId` | Core | `<taskId>/p<index>` |
| `attemptId` | Distributed | `<partitionId>/a<attempt>`; a new id for every retry |
| `workerId` | Worker configuration | `[A-Za-z0-9._:-]{1,64}`; unique among connected workers |
| `sessionId` | Coordinator | A random UUID per accepted registration |
| `messageId` | Sender | A random UUID per message; receivers drop duplicates |

The same ids appear in protocol messages, events, results and metrics, so every component can correlate them.

## 8. Design decisions

| Decision | Alternatives considered | Reason |
|---|---|---|
| TCP with newline-delimited JSON | gRPC, HTTP, Java RMI, a message broker | Required by the specification; readable on the wire; no extra infrastructure. Java object serialization is never used. |
| Single control-loop thread | Locks around shared maps | Deterministic, observable scheduling; no lock ordering. The heavy work runs elsewhere. |
| Workers connect to the coordinator | The coordinator connects to workers | Workers can start in any order, behind NAT, and register themselves. |
| HMAC challenge-free registration proof | A plain shared token | The secret never travels over the network; both sides prove knowledge of it. TLS is optional. |
| Retries only for retryable, side-effect-free partitions | Retry everything | Core marks partitions `retryable`; other work fails clearly instead of being repeated. |
| No coordinator persistence in the first release | A database | The Application owns persistence. After a coordinator restart, workers re-register, but running tasks are lost and reported as such. |

## 9. Risks

| Risk | Mitigation |
|---|---|
| Large payloads (base64 adds a third) | Size limits on both sides; measured `bytesSent` and `bytesReceived`; tune partition counts |
| Clock differences between machines | Durations are measured on one clock each (coordinator round trip, worker residence); never subtract timestamps from different machines |
| Slow or faulty workers | Per-attempt timeouts, retries elsewhere, quarantine after repeated invalid results, final verification by Core |
| A single coordinator | Accepted for the first release and documented; workers reconnect automatically after a restart |
| Measurements on one machine misrepresent a LAN | Benchmarks record the environment, and reports say how workers were deployed |
