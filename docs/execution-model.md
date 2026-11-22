# Execution Model

This page describes how `LocalExecutionEngine` runs a task, which execution modes it supports today, and how
failures, cancellation and memory limits are handled.

## 1. Execution modes

| Mode | Executed by | Status on this branch |
|---|---|---|
| `SEQUENTIAL_CPU` | Core, `LocalExecutionEngine` | **Implemented** for all four priority-1 algorithms |
| `MULTITHREADED_CPU` | Core, bounded worker pool | Planned (Phases 4–5); rejected with `UNSUPPORTED_EXECUTION_MODE` until then |
| `DISTRIBUTED_CPU` | DistriCompute-Distributed, using Core's partition primitives | Core side planned (Phase 5); never executed by the local engine |
| `GPU`, `HYBRID` | — | Not implemented; always rejected |

An algorithm's supported modes are derived from the interfaces it implements. The registry never trusts a
hand-written list. The local engine rejects non-local modes with a message pointing to the responsible
component.

## 2. Lifecycle as driven by the engine

```
execute(spec)
  VALIDATING  resolve algorithm and version · check mode · schema validation · algorithm.validate
              · algorithm.analyze (WorkloadProfile) · memory guard
  VALIDATED → PLANNED (threads, partitions) → QUEUED
  RUNNING     algorithm.prepareInput (timed separately) · algorithm.computeSequential
  AGGREGATING (one partial result in sequential mode)
  VERIFYING   algorithm.verify · algorithm.summarize
  COMPLETED   only if verification passed
```

`execute` never throws for task-level problems. It always returns an `ExecutionOutcome` holding a serializable
`TaskResult`, with a terminal status, the status history, the profile, metrics, environment and an `ErrorInfo`
on failure. The full result value (for example the product matrix) is exposed only for `COMPLETED` tasks.

## 3. Failure handling

| Situation | Status | Error code | Recorded status |
|---|---|---|---|
| Unknown algorithm or incompatible version | `FAILED` | `UNKNOWN_ALGORITHM` / `UNSUPPORTED_ALGORITHM_VERSION` | `VALIDATING` |
| Mode not available locally or not implemented | `FAILED` | `UNSUPPORTED_EXECUTION_MODE` | `VALIDATING` |
| Invalid parameters or dimensions | `FAILED` | `VALIDATION_FAILED` with every violation | `VALIDATING` |
| Estimated memory above budget | `FAILED` | `RESOURCE_EXHAUSTED` (the profile is included) | `VALIDATING` |
| Deadline passed | `FAILED` | `TIMEOUT` (retryable) | where it was observed |
| Cancellation requested | `CANCELLED` | `CANCELLED` | where it was observed |
| Result fails verification | `FAILED` | `VERIFICATION_FAILED`; report and summary included, no value | `VERIFYING` |
| Exception thrown by algorithm code | `FAILED` | `EXECUTION_FAILED` (logged with stack trace) | `RUNNING` |
| `OutOfMemoryError` despite the guard | `FAILED` | `RESOURCE_EXHAUSTED` | where it occurred |

## 4. Memory guard

Before any input is allocated, the engine compares `WorkloadProfile.estimatedPeakMemoryBytes` with this budget:

```
budget = min(task cap (constraints.maxMemoryBytes), (maxHeap − usedHeap) × memoryHeadroom)
```

`memoryHeadroom` defaults to 0.8. Used heap includes garbage that hasn't been collected yet, so the guard is
conservative.

## 5. Cancellation and deadlines

* Pass a `CancellationSource` to `execute(spec, source)` and call `source.cancel(USER_REQUESTED)`. Use one source
  per task.
* `constraints.timeoutMillis` schedules a cancellation with reason `TIMEOUT` when `execute` starts. It covers the
  whole execution, including validation.
* Cancellation is cooperative. Kernels poll the token once per sieve segment, per tile pair of the matrix kernel,
  every 2^16 merged elements, and every 2^20 generated or added elements. Latency is bounded by that work.
* A cancellation that arrives after verification has finished does not change the outcome (`COMPLETED`).

## 6. Bounded worker pool (`WorkerPool`)

The engine owns one pool, created at construction and closed with the engine.

| Requirement | How it is met |
|---|---|
| Bounded threads, no unbounded creation | `ThreadPoolExecutor` with `core = max = EngineConfig.maxThreads`, started up front, bounded `ArrayBlockingQueue`, `AbortPolicy` (never caller-runs, never grows). Tests assert `largestPoolSize == threads`. |
| Configurable thread count | `EngineConfig.maxThreads`, plus a per-task `constraints.maxThreads` (clamped to the pool size) |
| Controlled submission | `runAll` keeps at most `parallelism` items of a batch in flight. The next item is submitted only when one completes. |
| Thread-safe result collection | Completions arrive through an `ExecutorCompletionService` (a blocking queue). The caller's callback runs on the calling thread, one item at a time. |
| Exception propagation | The first failure is rethrown unwrapped. The batch scope is cancelled with `FAILURE`, so running siblings stop at their next check and unsubmitted items are abandoned. |
| Cancellation and timeouts | The coordinating thread polls the completion queue every 20 ms and checks the scope. Items check the scope before starting. |
| Structured completion | `runAll` returns or throws only after every submitted item has finished. Tests assert that nothing is still running. |
| Orderly shutdown | `close()` stops accepting work, waits up to 30 s, then interrupts. `LocalExecutionEngine.close()` first cancels active tasks with `SHUTDOWN`. |
| Measurement | Each item records submission, start and finish times and its allocated bytes (`TaskRun`), from which busy time, queueing and scheduling overhead are derived. |

## 7. Concurrent task submissions (admission control)

At most `EngineConfig.maxConcurrentTasks` tasks (default 1) run at the same time. Others wait in `QUEUED` on a
fair semaphore while staying responsive to cancellation and deadlines. Running one task at a time is the right
default for measurements, because concurrent tasks compete for the same cores and memory bandwidth.

## 8. Progress events

`execute(spec, cancellation, listener)` reports `ProgressEvent`s. Every status change is reported. Kernel progress
is throttled to `EngineConfig.progressIntervalMillis` (default 100 ms). Events are monotonic, and the final event
of a completed task reports 100%. A task's listener is never called concurrently. A failing listener is logged
once and never affects the task.

## 9. Measurements

`ExecutionMetrics` separates validation, input preparation, compute, aggregation and verification time.
`executionNanos` (partitioning + compute + aggregation) is the speedup basis. Process CPU time, GC activity and
allocated bytes cover the execution interval only. Exact definitions are in the
[integration contract §10](integration-contract.md#10-execution-metrics). Tests never assert timings.
