# Fault lab

The fault lab (`/fault-lab`, administrators only) injects a fault into a worker and shows how
DistriCompute-Distributed detects it and recovers. It uses the runtime's own fault simulation
(`org.districompute.distributed.simulation.FaultProfile`, documented in the Distributed component); the application
only chooses a profile, runs a task and reports what happened.

## 1. Isolation

Everything in the lab is simulated, and the page and the API say so (`simulated: true` on every lab run and event).

- **Separate coordinator.** Each lab run starts its own Distributed coordinator on a loopback port (`127.0.0.1`,
  port chosen by the system) with a freshly generated secret, and stops it when the run ends. Only this coordinator
  accepts fault-simulating workers (`allowFaultSimulationWorkers`); the application's coordinator keeps refusing them.
- **Simulated workers.** Three `WorkerNode`s run inside the application process: one with the scenario's fault
  profile (`lab-<run>-faulty`) and two healthy ones. They never connect to the application's coordinator, so they
  do not appear on the Workers page and cannot receive experiment work.
- **Separate records.** Lab runs live in `fault_lab_runs`; their runtime events are stored in `execution_events`
  with `simulated = TRUE` and `lab_run_id` set (a check constraint enforces that a lab event is simulated). The
  application's event list, the overview and worker histories exclude simulated events.
- **Switched off by default.** The lab runs only when the server is started with
  `districompute.faults.enabled=true`; otherwise the page explains how to enable it and the API answers 409
  `FAULT_LAB_DISABLED`. One lab run at a time (409 `FAULT_LAB_BUSY`).
- **Audited.** Starting and finishing a lab run write `FAULT_LAB_STARTED` and `FAULT_LAB_FINISHED` audit events.

Limitation: the workers are threads of the application process, not separate processes, so a lab run cannot kill
an operating-system process. Crashes are represented by the profile's abrupt disconnection and by hanging
attempts.

## 2. What a run does

1. Start the lab coordinator (heartbeat every 200 ms, suspect after 800 ms, lost after 2 s; scheduling policy
   round robin, so the faulty worker receives partitions) and the three workers (two slots each).
2. Submit a 192 × 192 × 192 matrix multiplication (generated input with the run's seed) in distributed mode with
   12 partitions, the scenario's attempt timeout and attempt budget.
3. Run the same task sequentially on the lab coordinator as the **fault-free reference**.
4. Store the runtime events as they arrive (every 200 ms) and, at the end, the outcome.

The **seed** drives both the generated input and the fault profile's random choices, so a scenario can be repeated.
Timing still varies between runs.

## 3. Scenarios

| Scenario | Faulty worker's profile | Expected detection | Expected recovery |
|---|---|---|---|
| `WORKER_DISCONNECT` | drops its connection after its first task (results delayed 300 ms) | connection closed; its attempts lost | lost partitions retried on connected workers |
| `MISSED_HEARTBEATS` | stops heartbeats 300 ms after registering; answers after 4 s | suspect, then lost after the 2 s heartbeat timeout | partitions retried elsewhere; late results ignored |
| `CORRUPTED_RESULTS` | every result corrupted in transit | checksum mismatch; results rejected as invalid | partitions retried on other workers |
| `DUPLICATE_RESULTS` | every result sent twice | the second copy is recognised | duplicates ignored |
| `KERNEL_FAILURES` | every attempt fails (retryable) | failures reported with their reason | retried on workers that have not failed the partition, up to 6 attempts each |
| `HANGING_ATTEMPTS` | every attempt hangs, ignoring cancellation | attempt timeout (2 s) | timed-out partitions retried elsewhere |
| `SLOW_WORKER` | computes 5 times slower | nothing fails; load imbalance | completes |
| `SLOW_NETWORK` | every message delayed 150 ms | nothing fails; transfer time | completes, later |

The expected behaviour is what the runtime is designed to do; each run records what actually happened.

## 4. Outcome

| Field | Meaning |
|---|---|
| `verdict` | `RECOVERED` (a failure event concerned the faulty worker and the task completed with the reference's result), `UNAFFECTED` (no failure event; same result), `NOT_RECOVERED` (the task failed), `DIFFERENT_RESULT` (completed with another result) |
| `detection` | The first failure event about the faulty worker after submission (`ATTEMPT_FAILED`, `RESULT_IGNORED`, `WORKER_DISCONNECTED` or `WORKER_STATUS_CHANGED`), with its delay since submission |
| `recovery` | Retries, partitions that completed after a failed attempt, the first `PARTITION_RETRIED` event, time to finish |
| `task` | Status, error, digest and the reference's digest, whether they match, Core's verification, durations, partitions, attempts, retries |
| `attemptsByStatus`, `workers` | Attempts by status overall and per worker (role, statuses, summed compute time) |
| `metrics` | The runtime's distributed time, load imbalance, worker utilization, transfer time, communication share, attempts, failed attempts, retries, workers used |

A lab run's own `status` is `COMPLETED` when the scenario ran (whatever the verdict) and `FAILED` only when the lab
itself could not run it (for example a worker did not register); runs interrupted by a restart are marked `FAILED`.

## 5. Measured behaviour

All eight scenarios were run with seed 7 against MySQL 8.4 while writing this page and in the integration tests: the
six failure scenarios ended `RECOVERED` and the two slowdown scenarios `UNAFFECTED`, every result equal to the
fault-free reference. Examples from one run: corrupted results were rejected as `INVALID` within 30 ms and 4
partitions recovered; missed heartbeats made the worker suspect after about 1.1 s and the task finished after
about 2.3 s; hanging attempts timed out after 2 s.

## 6. API

| Method and path | Purpose |
|---|---|
| `GET /api/v1/fault-lab` | Enabled, busy, scenarios, the run setup |
| `POST /api/v1/fault-lab/runs` | Run `{scenario, seed?}` in the background (202) |
| `GET /api/v1/fault-lab/runs?limit=` | Recent lab runs |
| `GET /api/v1/fault-lab/runs/{id}` | One lab run with its outcome |
| `GET /api/v1/fault-lab/runs/{id}/events` | Its events, oldest first, all `simulated` |

All require the `ADMIN` role.
