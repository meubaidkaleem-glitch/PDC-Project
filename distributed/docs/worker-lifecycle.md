# Worker Lifecycle

A worker is an independent JVM process (`org.districompute.distributed.worker.WorkerNode`). It needs no GUI, no
database and no Application. It needs only the coordinator's address and the cluster's shared secret.

## 1. Starting a worker

```bash
java -jar districompute-distributed-0.1.0-SNAPSHOT-all.jar worker --coordinator 192.168.1.10:7650 --id lab-pc-07 --secret-file cluster.secret
```

| Option | Default | Meaning |
|---|---|---|
| `--coordinator host:port` | required | Coordinator address |
| `--id` | `<hostname>-<pid>` | Worker id, 1–64 characters `[A-Za-z0-9._:-]`. Use a stable id per machine: the coordinator keeps counters and performance history per id. |
| `--secret-file` | `DISTRICOMPUTE_SECRET` | Shared secret (≥ 16 bytes). There is no option for the secret itself, because command lines are visible to other users. |
| `--max-concurrent-tasks` | available processors | Attempts executed at the same time |
| `--max-task-memory-mb` | 70 % of the heap ÷ concurrency | Work units larger than this are refused |
| `--max-message-bytes` | 64 MiB | Largest frame accepted from the coordinator |
| `--labels k=v,...` | none | Free-form labels shown to the coordinator and the Application |
| `--algorithms id,...` | all distributable | Offer only these Core algorithms |
| `--tls-truststore`, `--tls-truststore-password` | TLS off | Trust store with the coordinator's certificate |
| `--tls-no-hostname-verification` | off | Accept a certificate that does not name the coordinator's host (laboratory use only) |
| `--drain-timeout-ms` | 30000 | Graceful shutdown: how long running attempts may take |
| `--config file.properties` | none | Any of the options above, without the dashes |

The worker can also be embedded: `WorkerNode.start(WorkerConfig.builder(host, port, secret)...build())`.

## 2. Capabilities

Reported once in `REGISTER` ([network-protocol.md §3.1](network-protocol.md#31-payload-rules)) and **detected,
not configured**, except for the limits:

| Capability | Source |
|---|---|
| CPU cores, maximum heap | `Runtime.availableProcessors()`, `Runtime.maxMemory()` |
| Physical memory | `com.sun.management.OperatingSystemMXBean` (`-1` if the JVM cannot tell) |
| OS, architecture, Java version | System properties |
| Algorithms and exact versions | Core's `AlgorithmRegistry`: every algorithm with `DISTRIBUTED_CPU` support, optionally restricted by `--algorithms` |
| GPU | Core's `GpuStatus`. `available` is `true` only if Core detected a working backend. Today Core ships none, so every worker reports `false`, with Core's reasons. |
| Runtime and Core versions | Build metadata |
| Fault simulation | `true` only when fault injection is explicitly enabled ([fault-tolerance.md](fault-tolerance.md)) |

The coordinator does not treat capabilities as a guarantee. It checks compatibility for every attempt, and the
worker re-validates every work unit.

## 3. States

```
            connect ok           REGISTER_ACK verified
CONNECTING ───────────▶ REGISTERING ─────────────────▶ REGISTERED
    ▲   ▲                    │ refused / timeout             │ connection lost, coordinator silent,
    │   └────── backoff ─────┘                               │ SHUTDOWN(reconnect = true)
    └────────────────────── backoff ◀────────────────────────┘
                                                             │ close(), SHUTDOWN(reconnect = false)
                                                             ▼
                                                          STOPPED
```

* **Backoff.** It starts at 500 ms and doubles up to 10 s, with up to 25 % random jitter, so that workers do not
  reconnect in lockstep after a coordinator restart. A successful registration resets it.
* **Registration.** Each attempt uses a fresh proof (new nonce). If `REGISTER_ACK` does not arrive within 10 s,
  or the coordinator's proof is wrong, the worker hangs up and tries again later. It never executes work for a
  coordinator that cannot prove the secret.

The coordinator's view of a worker (`ACTIVE`, `SUSPECT`, `DRAINING`, `QUARANTINED`, `DISCONNECTED`) is described in
[integration-contract.md §2.1](integration-contract.md#21-starting-a-coordinator-and-listing-workers--implemented).

## 4. Heartbeats

* The worker sends `HEARTBEAT` at the interval announced in `REGISTER_ACK` (default 2 s). It reports active
  attempts, the current concurrency limit, free heap, load average, process CPU load, completed and failed totals,
  and the ids of admitted attempts.
* Heartbeats run on their own thread, so they continue while kernels keep every core busy.
* If the coordinator sends nothing (not even `HEARTBEAT_ACK`) for the announced heartbeat timeout, the worker
  treats the connection as dead and reconnects.

## 5. Executing attempts

For each `TASK`, on the connection's reader thread:

1. **Admission.** The attempt is refused with `TASK_REJECTED` when the worker is shutting down (retryable), when
   the attempt id was already received (not retryable, because executing twice is never correct), when the
   algorithm is not offered, unknown or of an incompatible version (retryable on another worker), when the work
   unit is larger than `maxTaskMemoryBytes` (retryable), or when all slots are busy (retryable).
2. **Acceptance.** `TASK_ACCEPTED` is queued **before** execution starts, so it always precedes the attempt's
   final message.
3. **Execution.** It runs on a fixed pool of platform threads, one per slot, through Core's `PartitionWorker`
   (decode, kernel, encode). The worker has no algorithm code of its own.
4. **Progress.** Core kernels report progress. The worker forwards it as `TASK_PROGRESS`, at most once per 500 ms
   per attempt. Core's partition kernels do not know their total, so `totalUnits` is 0 (unknown).
5. **End.** Every admitted attempt ends with exactly one of:
   * `RESULT`: Core's `PartialResultMessage`, plus `queueNanos`, `executeNanos` and `residenceNanos`;
   * `ERROR TASK_FAILED`: Core's `ErrorInfo`, whose `retryable` comes from Core's error code (for example
     `PARTITION_FAILED` is retryable, `VALIDATION_FAILED` is not). Unexpected exceptions become
     `EXECUTION_FAILED` with a generic message; the details stay in the worker's log;
   * `ERROR TASK_CANCELLED`: after `CANCEL`.

**Timeouts.** `TASK.timeoutMillis` starts when the attempt is admitted. When it expires, the worker cancels the
kernel and reports `TASK_FAILED` with Core's `TIMEOUT` code, which is retryable.

**Cancellation.** `CANCEL` cancels the attempt's Core `CancellationSource`. Kernels poll it per segment, row
block or chunk, so they stop promptly. If the kernel had already finished, the `RESULT` is sent anyway, and the
coordinator decides whether to use it. A `CANCEL` for an attempt the worker never had is answered with
`UNKNOWN_TASK`.

**Results too large for the coordinator.** If an encoded `RESULT` exceeds the coordinator's frame limit, the
worker sends `TASK_FAILED` (not retryable: the result is the same size on any worker) instead of a frame the
coordinator would have to reject.

## 6. Losing the connection

Running attempts are cancelled, because their results could no longer be delivered on that connection. The
coordinator has already counted them as lost and may retry them elsewhere. Then the worker reconnects and
registers again with the same id. The coordinator keeps the worker's history.

## 7. Stopping

| How | What happens |
|---|---|
| SIGTERM or Ctrl+C (`close()`) | The worker stops accepting attempts and sends `SHUTDOWN` with `drain: true` (the coordinator marks it `DRAINING` and sends nothing new). It lets running attempts finish and send their results for up to the drain timeout, then disconnects. |
| SIGKILL, crash, power loss (`abort()`) | Nothing is sent. The coordinator detects the closed connection, or the missing heartbeats if the network is cut, and treats the running attempts as lost. |
| `SHUTDOWN` from the coordinator | `reconnect: true` (a coordinator stop or restart): the worker cancels its attempts and keeps trying to reconnect. `reconnect: false`: the worker stops. |

## 8. Changing capacity at run time

`WorkerNode.setMaxConcurrentTasks(n)` changes the limit. The next heartbeat reports it, and the coordinator then
schedules against the new value. Fault experiments use this to simulate machines whose capacity changes.

## 9. What a worker never does

* It never runs code or shell commands received over the network. Only algorithms registered in its own Core
  registry can execute, and the work unit is validated by Core.
* It never claims a GPU that Core has not detected.
* It never logs the shared secret.
