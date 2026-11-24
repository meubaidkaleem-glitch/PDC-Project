# Fault tolerance

What happens when something fails, at each level, and how it was tested. Failures inside a distributed run are
handled by DistriCompute-Distributed; the application records them and handles its own failures.

## 1. Inside a distributed run (DistriCompute-Distributed)

| Failure | Detection | Recovery | Tested by |
|---|---|---|---|
| Worker process dies or its connection breaks | Connection closed | Its attempts are marked lost and the partitions retried on other workers | End-to-end test (worker process killed with SIGKILL during a run); fault lab `WORKER_DISCONNECT` |
| Worker stops sending heartbeats | Suspect after `suspect-after`, lost after `heartbeat-timeout` | No new work while suspect; partitions retried when lost; late results ignored | Fault lab `MISSED_HEARTBEATS` |
| Attempt never answers | Attempt timeout | Retried elsewhere | Fault lab `HANGING_ATTEMPTS` |
| Kernel failure on a worker | Reported by the worker | Retried within the attempt budget | Fault lab `KERNEL_FAILURES` |
| Corrupted result | Size or checksum mismatch | Rejected as invalid, retried; repeated invalid results quarantine the worker | Fault lab `CORRUPTED_RESULTS` |
| Duplicate result | Already accepted | Ignored | Fault lab `DUPLICATE_RESULTS` |
| Slow worker or link | Not a failure | The run completes; load imbalance and transfer time show it | Fault lab `SLOW_WORKER`, `SLOW_NETWORK` |
| No worker available | No eligible worker | The run waits for one, then fails with a clear error | Advisor warning before submission |

A partition that fails more often than its attempt budget (`maxAttempts`, default 3) fails the run; the
experiment is then marked failed with the reason, and the remaining repetitions are not run.

## 2. Results

A run counts as completed only when the runtime reported success. Core verifies the final result (method per
algorithm, for example Freivalds' check for matrix products). The application stores a result value only if the
digest of the value it received equals the digest of the verified result, and records the verification and the
storage outcome with the run. A result too large to store (`storage.max-result-bytes`) keeps Core's summary and
digest, and the page says so.

## 3. The application

| Failure | Behaviour |
|---|---|
| The application stops or crashes during runs | Runs and their in-memory state are lost with the embedded coordinator. At the next start, experiments that were queued or running are marked failed with the reason ("the application restarted…"); batches do not continue; workers reconnect by themselves. Tested end to end (backend killed with SIGKILL during a run) |
| A database deadlock between two state changes of one experiment | State changes of an experiment are serialised in the application and transactions are retried after a lock conflict (found by CI, fixed and stress-tested with 25 immediate batch cancellations) |
| Many runtime events at once | Events are stored in batches from a bounded queue; if it ever overflows, the number of dropped events is counted and shown on the overview |
| A browser loses the live stream | It reconnects and the server replays the events it missed (`Last-Event-ID`) |
| An upload referenced by a template no longer exists | Core's validation fails when the template is used; the form asks for the data again |

## 4. Not provided

- Runs are not resumed after a restart of the application (the coordinator and its task state live in memory);
  they are reported as failed and can be run again ("Run again" keeps the configuration).
- One application instance: there is no failover of the coordinator.
