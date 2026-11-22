# Fault Tolerance

This document describes what the runtime detects, how it recovers, and what it deliberately does not recover
from. Every behaviour here is covered by `FaultToleranceTest` (tag `fault`) or `ResultValidationTest`, against
real workers or scripted protocol peers over TCP.

## 1. Detection

| Failure | Detected by | Default timing | Reaction |
|---|---|---|---|
| Worker disconnects (crash, kill, reset) | The connection's reader sees end of stream or an I/O error | Immediate | Worker `DISCONNECTED`. Its live attempts become `LOST` and are retried. |
| Worker stops sending heartbeats (hung process, cut network) | Control-loop tick compares the last heartbeat with the limits | `SUSPECT` after 5 s, `DISCONNECTED` after 10 s (heartbeat every 2 s) | `SUSPECT`: no new attempts; a heartbeat restores `ACTIVE`. Timeout: the connection is closed, then as above. |
| Worker loses an accepted attempt | Its heartbeat no longer lists the attempt, and no final message arrived | Next heartbeat | Attempt `LOST` ("no longer listed in the heartbeat"), retried. |
| Worker never confirms an attempt | No `TASK_ACCEPTED` | 30 s (`acceptTimeout`) | Attempt `LOST`, `CANCEL` sent, retried. |
| Attempt takes too long | The worker cancels the kernel at `timeoutMillis` and reports Core `TIMEOUT`; the coordinator enforces the same limit plus max(200 ms, 10 %) for workers that hang | 10 min (`attemptTimeout`, per task) | Attempt `TIMED_OUT`, `CANCEL` sent, retried. The worker's slot stays occupied until the worker answers or disconnects. |
| Task exceeds its deadline | `TaskSpec.constraints.timeoutMillis` | Per task | Task `FAILED` with Core `TIMEOUT`. Its attempts are cancelled on the workers. |
| Invalid result (checksum, decoding, bounds, wrong partition) | Core's `PartialResultCollector` classification | On arrival | Attempt `INVALID`, partition retried, counted for quarantine. |
| Conflicting duplicate (a second result that differs) | Core classification | On arrival | Attempt `INVALID`, counted for quarantine. The first accepted result is kept. |
| Identical duplicate, or a result after the task ended | Core classification (`DUPLICATE`, `LATE`) | On arrival | Ignored and counted. Never aggregated twice. |
| Result for an attempt assigned to another worker | Coordinator metadata check | On arrival | Refused (`INVALID_MESSAGE`), counted as invalid. |
| Result for an unknown attempt | Coordinator metadata check | On arrival | Refused (`UNKNOWN_TASK`). |
| Worker refuses an attempt | `ERROR TASK_REJECTED` | On arrival | Attempt `REJECTED`, retried on another worker (separate budget). |
| Worker shuts down gracefully during execution | `SHUTDOWN` with `drain` | On arrival | Worker `DRAINING`: no new attempts. Running attempts finish and deliver their results; any left at disconnection are retried. |
| Coordinator shuts down | `Coordinator.close()` | — | Running tasks end `CANCELLED` (reason `SHUTDOWN`). Workers get `SHUTDOWN` with `reconnect: true` and keep reconnecting with backoff. |
| No connected worker can run the task | Control-loop tick | 30 s (`noWorkerTimeout`) | Task `FAILED`, listing each connected worker and why it cannot run the task. While capable workers are only busy, the task simply waits. |

## 2. Recovery

The specification's recovery requirements and how they are met:

| Requirement | Implementation |
|---|---|
| 1. Mark unavailable workers accurately | `WorkerStatus` with the reason and time of the last change (`SUSPECT`, `DRAINING`, `QUARANTINED`, `DISCONNECTED`) |
| 2. Identify affected tasks | Each worker record holds the attempts occupying its slots, and each attempt knows its task and partition |
| 3. Preserve known task state | Accepted partial results stay in Core's collector. Only the partitions without a result are redone. |
| 4. Decide whether a task is safe to retry | `RetryPolicy` ([§3](#3-retry-policy)): the failure must be retryable, Core must mark the partition retry-safe, and budgets must remain |
| 5. Retry only eligible work | The partition goes back into the pending queue. The failed worker is avoided (soft exclusion, [scheduling.md §2](scheduling.md#2-eligibility-always-applied-first)). |
| 6. Prevent duplicate aggregation | Core accepts the first valid result per partition. Later copies are `DUPLICATE`, `LATE` or `CONFLICTING_DUPLICATE`. Other live attempts of a completed partition are `SUPERSEDED` and cancelled. |
| 7. Track retry count | Every retry has a new attempt id (`…/a2`, `…/a3`). Snapshots and metrics report `attempts`, `retries` and `failedAttempts`. |
| 8. Record the failure reason | Each attempt has `status`, `failure` and `retryable`; the task's `ErrorInfo` names the partition and the reason |
| 9. Notify the Application | Task snapshots now; runtime events in Phase 8 |
| 10. Mark unrecoverable tasks clearly | The task ends `FAILED` with Core's error code and a message such as `Partition t/p0 failed: …; the retry budget is exhausted (3 failed attempts, limit 3)` |

**Quarantine.** A worker that returns `quarantineThreshold` (default 3) invalid or conflicting results within one
registration becomes `QUARANTINED`. It stays connected (for diagnosis), receives no attempts, and its running
attempts are cancelled and rescheduled elsewhere, because its results are no longer trusted. Registering again
starts a new registration with an `ACTIVE` status; the counters keep the history.

## 3. Retry policy

A failed partition (with no other live attempt) is attempted again only if:

1. the failure is **retryable**, as reported by the worker or by Core's `ErrorCode` (for example
   `PARTITION_FAILED` is retryable, `VALIDATION_FAILED` is not);
2. the partition is **retry-safe**: Core's descriptor marks it `retryable` (deterministic and free of side
   effects), and it has no accepted result. An algorithm with side effects must mark its partitions not
   retryable, and the runtime will then never re-execute them;
3. the **budgets** remain.

| Failure kind | Counts against `maxAttempts` (default 3) |
|---|---|
| `WORKER_LOST`, `MISSING_FROM_HEARTBEAT`, `NOT_ACCEPTED`, `TIMED_OUT`, `TASK_FAILED`, `INVALID_RESULT` | yes |
| `REJECTED`, `DISPATCH_FAILED` | no; separate limit `maxRejectionsPerPartition` (default 20) |
| `WORKER_STOPPED` (the worker cancelled because it is stopping), `WORKER_QUARANTINED` | no |

Otherwise the task fails. Repeated failures are never turned into success. Core's final verification is a
second line of defence: a result that passes per-partition validation but is wrong fails verification, and the
task ends `FAILED` (`VERIFICATION_FAILED`). It is not retried automatically.

## 4. Slot accounting under failures

A worker's slot is freed when its final message for the attempt arrives (`RESULT`, `TASK_FAILED`,
`TASK_CANCELLED`, `TASK_REJECTED`), when the worker disconnects, or when its heartbeat shows that it no longer
has the attempt. An attempt the coordinator has given up on (timed out, superseded, cancelled) keeps its slot
until then, so a hung kernel never leads to over-subscription. The worker frees its own slot before sending the
final message, so a new attempt sent right after the result is never refused for lack of capacity.

## 5. Defaults

| Setting | Default | Where |
|---|---|---|
| Heartbeat interval / suspect / timeout | 2 s / 5 s / 10 s | `CoordinatorConfig.heartbeat` |
| Attempt timeout | 10 min | `TaskDefaults.attemptTimeout`, per task `SubmitOptions.withAttemptTimeout` |
| Acceptance timeout | 30 s | `TaskDefaults.acceptTimeout` |
| Attempts per partition | 3 | `TaskDefaults.maxAttempts`, per task `SubmitOptions.withMaxAttempts` |
| Refusals per partition | 20 | `TaskDefaults.maxRejectionsPerPartition` |
| Quarantine threshold | 3 invalid results per registration | `TaskDefaults.quarantineThreshold` |
| No capable worker | 30 s | `TaskDefaults.noWorkerTimeout` |
| Worker reconnection backoff | 0.5 s doubling to 10 s, with jitter | `WorkerConfig.backoff` |

## 6. Not handled (known limitations)

* **Coordinator crash.** There is no persistence and no standby coordinator. Running tasks are lost: the
  Application sees them end `CANCELLED` on a clean shutdown, or must time them out itself after a crash.
  Workers reconnect to a restarted coordinator automatically.
* **Wrong results that look valid.** A worker that returns a well-formed but wrong partial result passes
  per-partition validation. Core's independent verification of the final result catches it, but the runtime
  cannot tell which partition was wrong, so the task fails instead of being repaired.
* **Detection time.** A silent network failure is detected only after the heartbeat timeout (10 s by default).
  Shorter timeouts detect faster but risk false alarms during long garbage-collection pauses.
* **Hung kernels.** Core kernels poll their cancellation token. A kernel that stopped polling would keep its
  slot until the worker process is restarted.
