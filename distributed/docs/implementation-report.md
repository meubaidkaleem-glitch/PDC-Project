# Implementation Report: DistriCompute-Distributed

| | |
|---|---|
| Component | DistriCompute-Distributed (Contributor 2): coordinator, worker processes, protocol, scheduling, fault tolerance, monitoring, MPI demonstrations |
| Repository and branch | `PDC-Project`, branch `feature/distributed-engine`, based on `feature/core-engine`; not merged into `main` |
| Version | `org.districompute:districompute-distributed:0.1.0-SNAPSHOT`; protocol `0.1` (draft) |
| Date | 2026-11-23 |
| Development assistance | Implemented with an AI coding assistant (Claude) in sessions run from the account `meubaidkaleem-glitch`. Commits are authored by that account, and every commit carries a `Co-Authored-By: Claude` trailer. The responsible contributor must review the code before merging. |
| Size | 122 main source files (about 13 500 lines of Java) and 32 test files (about 4 400 lines), four C programs |

## 1. Features completed

| Requirement | Implementation | Evidence |
|---|---|---|
| Coordinator and independent worker processes | `Coordinator` (TCP server, single-threaded control loop, per-connection reader and writer threads with bounded queues); `WorkerNode` as a separate JVM (`worker` command) | `WorkerProcessTest` (separate JVMs, SIGTERM and SIGKILL), the local demonstration, the benchmarks (252 runs on worker processes) |
| Versioned protocol | Newline-delimited JSON, envelope plus 11 message types (`REGISTER` … `SHUTDOWN`), protocol version rules, frame size limits, specific error codes, duplicate-message filter ([network-protocol.md](network-protocol.md)) | `MessageCodecTest`, `FramingTest`, `IdsTest`, `CoordinatorRegistrationTest` |
| Registration and registry | Mutual HMAC-SHA256 proof with nonce replay protection and a clock-skew window; registry with capabilities, status, load, counters and performance history per worker id | `RegistrationAuthenticatorTest`, `WorkerRegistryTest`, `CoordinatorRegistrationTest`, `TlsRegistrationTest` |
| Scheduling | Eligibility rules with recorded reasons; round robin, least loaded, capability-aware, performance-aware and dynamic policies; a decision record (policy, worker state, explanation, rejected workers, time) for every assignment ([scheduling.md](scheduling.md)) | `SchedulingPoliciesTest` (constructed states), `PolicyExecutionTest` (real workers) |
| Adaptive execution coordination | Core's `StrategyAdvisor` fed with eligible workers, measured network and calibrations; local sequential and multithreaded modes through Core; GPU and hybrid refused with an explanation; manual overrides recorded in the snapshot and as an event | `ApplicationIntegrationTest`, `MonitoringApiTest`, real run in [integration-contract.md §2.3](integration-contract.md#23-execution-advice--implemented) |
| Dispatch | Partitions and attempts (`<partitionId>/a<n>`), bounded priority queues, slot-based dynamic assignment, cancellation, completion through Core aggregation and verification | `DistributedExecutionTest`: all 10 distributable Core algorithms on 1 and 3 workers, 8 bitwise equal to sequential and 2 floating-point reductions verified within rounding |
| Result validation | Core classifies each result (accepted, duplicate, late, conflicting, invalid). The coordinator checks attempt ownership. Results are never aggregated twice | `ResultValidationTest` (scripted worker sends duplicate, corrupt, foreign and unknown results) |
| Fault tolerance | Disconnection, missed heartbeats (`SUSPECT`, then disconnected), heartbeat reconciliation, attempt, acceptance and task timeouts, explicit retry policy with budgets, quarantine, graceful drain, coordinator shutdown with reconnection, a clear failure when retries are exhausted ([fault-tolerance.md](fault-tolerance.md)) | `FaultToleranceTest` (12 tests), `RetryPolicyTest` |
| Metrics | Per task: queue, planning, distributed, aggregation wall times; kernel, worker execution, transfer, serialization, result processing and scheduling sums; bytes, attempts, retries, utilization, throughput, load imbalance, per-worker shares. Coordinator totals | Asserted in the integration tests; used by `bench` |
| Events and monitoring | 20 event types with global sequence numbers; non-blocking `EventBus` with history, replay and drop notices; HTTP API (`/api/v1`, JSON, Server-Sent Events, bearer token, CORS); no UI dependency | `EventBusTest`, `RuntimeEventsTest`, `MonitoringApiTest` |
| Security | Authentication of both sides; optional TLS 1.3/1.2 with host name verification; size limits on every frame and queue; workers run only algorithms from their own Core registry, never code or commands received over the network; the secret is never logged or accepted on the command line | `RegistrationAuthenticatorTest`, `TlsRegistrationTest`, `WorkerNodeTest` (refusals) |
| Controlled fault simulation | Fault profiles that must be explicitly enabled (slowdown, delays, latency, disconnection, stopped heartbeats, hanging, failing, corrupt and duplicate results, capacity changes); coordinators refuse such workers unless they opt in | `FaultSimulationTest` (10 tests), `FaultProfileTest` |
| MPI demonstrations | Point-to-point (ring, ping-pong), broadcast, scatter and gather, parallel prime counting in C; each checks its results ([mpi/README.md](../mpi/README.md)) | Run locally with Open MPI 4.1.6 (2, 3 and 4 processes); in CI with 4 processes on Ubuntu 24.04 (Open MPI 4) and the latest Ubuntu image (Open MPI 5.0.10), run 45206004346 |
| Tests | Unit, integration, fault, multi-process and performance groups, run separately in CI | §4 |
| Documentation | Architecture, implementation plan, network protocol, worker lifecycle, scheduling, fault tolerance, deployment, integration contract, benchmarks, this report | [README.md](../README.md) |
| Release preparation | `bench` command with measured results; deployment guide; start scripts; local multi-worker demonstration; example configurations and tasks; CHANGELOG | §4.3, [benchmarks.md](benchmarks.md), [deployment.md](deployment.md) |

## 2. Features partially completed

| Feature | What is missing |
|---|---|
| Integration with the Application | The Java API and the HTTP API are implemented and exercised by tests written the way the Application would use them, but not against the Application itself: its repository was not available. The Spring Boot sketch in the integration contract is not compiled. |
| Windows support | The Java code is platform-independent, but everything was run on Linux only. The two `.cmd` start scripts were not run on Windows, and there is no Windows version of the local demonstration. |
| Protection of messages after registration | Only TLS protects messages after registration. Without TLS, someone who can modify traffic could alter work units or results (the authentication proves who connected, not what is sent later). |
| HTTP API security | Bearer token over plain HTTP. TLS for the API requires a reverse proxy ([deployment.md §8](deployment.md#8-the-http-api)). |
| Handling of slow workers | Dynamic and performance-aware scheduling cannot prevent a much slower worker from taking the last partitions and delaying the end of a task (measured: [benchmarks.md §3.5](benchmarks.md#35-load-imbalance-and-scheduling-policies)). |

## 3. Features not implemented

| Feature | Reason |
|---|---|
| GPU and hybrid execution | Core ships no GPU backend. Workers report GPU as unavailable, and the runtime refuses these modes with an explanation. |
| Coordinator persistence and fail-over | There is one coordinator with in-memory state. After a crash, running tasks are lost (workers reconnect to a restarted coordinator automatically). |
| Speculative backup attempts, or holding work back for faster workers | Identified by the benchmarks as the main scheduling weakness. Core's duplicate handling would already make backup attempts safe. |
| Binary frames, and sending shared data once per worker | Work units are base64 inside JSON, and Core's row-band matrix partitions each carry all of B. The benchmarks show that this dominates data-heavy work. |
| Work stealing and uneven partition sizes | Core plans equal partitions. Over-decomposition with dynamic dispatch is used instead. |
| Measurements on several machines | Every measurement was taken on one 4-vCPU machine over loopback ([benchmarks.md §2](benchmarks.md#2-environment-and-caveats)). |

## 4. Tests executed and their actual results

### 4.1 Local runs (2026-11-23, Linux, 4 vCPUs, OpenJDK 21.0.13)

`./mvnw -f distributed/pom.xml verify` (Checkstyle, SpotBugs and JaCoCo included): **128 tests, 0 failures, 0
errors, 0 skipped; 0 Checkstyle violations; 0 SpotBugs bugs.** The default run contains the unit and
integration tests:

| Test class | Tests | | Test class | Tests |
|---|--:|---|---|--:|
| `CoordinatorRegistrationTest` | 16 | | `SchedulingPoliciesTest` | 9 |
| `MessageCodecTest` | 11 | | `WorkerNodeTest` | 9 |
| `FramingTest` | 8 | | `RegistrationAuthenticatorTest` | 8 |
| `MonitoringApiTest` | 7 | | `PolicyExecutionTest` | 7 |
| `WorkerRegistryTest` | 7 | | `DistributedExecutionTest` | 5 |
| `EventBusTest` | 5 | | `IdsTest` | 5 |
| `ResultValidationTest` | 5 | | `OptionsTest` | 4 |
| `WorkerCoordinatorTest` | 4 | | `BenchmarkSuiteTest` | 3 |
| `DistributedCliTest` | 3 | | `FaultProfileTest` | 3 |
| `RetryPolicyTest` | 3 | | `DistributedVersionTest` | 2 |
| `TlsRegistrationTest` | 2 | | `ApplicationIntegrationTest` | 1 |
| `RuntimeEventsTest` | 1 | | | |

The other groups, run separately (`-Dtest.groups=fault,process,performance`): **24 tests, 0 failures**:

| Group | Test class | Tests | Time |
|---|---|--:|--:|
| `fault` | `FaultToleranceTest` | 12 | 5.2 s |
| `fault` | `FaultSimulationTest` | 10 | 4.3 s |
| `process` | `WorkerProcessTest` | 1 | 1.8 s |
| `performance` | `BenchmarkRunnerTest` (quick suite on worker processes; correctness only) | 1 | 9.8 s |

Repeated runs: the fault-tolerance suite passed 5 consecutive runs when it was written, and the fault-simulation
suite 3. The races found by these tests are listed in §5.

### 4.2 Continuous integration (GitHub Actions, `Distributed CI`)

Each push runs four jobs: build and tests (each group as a separate step), code quality, and the MPI
demonstrations on Ubuntu 24.04 (Open MPI 4) and on the latest Ubuntu image (Open MPI 5).

| Commit | Result | Cause of failure, and fix |
|---|---|---|
| `f50a3e1` … `90e2044` (Phases 1-6) | Success | — |
| `a62b8be` (Phase 7) | **Failure** | Fault test: a heartbeat built before an acknowledgement was sent after it, so a running attempt looked lost. Fixed in `309ea1d` |
| `309ea1d` (Phase 8) | Success | — |
| `148cf87` (Phase 9) | **Failure** | A draining worker could close the connection before its last result was sent. Fixed in `6256429` |
| `c4bc7b3` (Phase 10) | **Failure** | The same drain race, and the MPI job on the latest image (Open MPI 5 started no processes) |
| `6256429` | **Failure** (Java jobs passed) | MPI on the latest image |
| `a0a6b4a` | **Failure** (Java jobs and MPI on Ubuntu 24.04 passed) | MPI on the latest image: the version check never recognised Open MPI, so the fix did not take effect |
| `0c6e724` (Phase 11), `3bf1a14` | **Failure** (Java jobs and MPI on Ubuntu 24.04 passed) | MPI on the latest image: Open MPI 5 refused to bind 4 processes (`allocation-overload`) |
| `055cb18` (Phase 12, includes `5cd6ef0`) | **Success**, all four jobs (run 45206004346) | 71 unit, 57 integration, 22 fault, 1 multi-process and 1 performance test passed; every MPI demonstration verified with Open MPI 4 and 5.0.10 |

### 4.3 End-to-end runs of the packaged jar

* **Local demonstration** (`scripts/local-demo.sh 3`): coordinator and 3 worker processes. Prime counting, matrix
  multiplication and sorting on the workers, and prime counting sequentially, all completed and verified. The
  distributed and sequential prime counts produced the same digest. Its first run left the processes running
  after the script ended, because the script killed the shell around each JVM instead of the JVM. This was fixed
  before the commit, and a second run left no process behind.
* **Benchmarks** (`bench --suite standard`): 252 runs (42 configurations × 6), every one completed, verified by
  Core, and with the same digest as every other run of its workload ([benchmarks.md](benchmarks.md)).
* **Command-line clients and the HTTP API**: `status`, `advise` and `submit --wait` against a coordinator with two
  worker processes. An override was recorded, and the advice used the measured network after the first
  distributed run ([integration-contract.md §2.3](integration-contract.md#23-execution-advice--implemented)).
* **Example configuration files** started a coordinator and a worker through the start scripts. The worker
  registered with the configured id, labels and slots, and the coordinator used the configured policy.

## 5. Known bugs

No open defect is known that produces a wrong result, loses a task silently or hangs, in the scenarios tested.
The defects found during development were fixed, each with a test or a run that exposed it:

* the frame-size limit allowed one byte too many (Phase 2);
* a worker freed its slot after sending the result, so a new attempt could be refused (Phase 5);
* a stopped worker could still be handed a task (Phase 5);
* an attempt that a worker never confirmed waited for the full attempt timeout (Phase 7);
* `AGGREGATING` was never reported, worker-reported failures produced no event, and a drop notice could be lost
  (Phase 8);
* the heartbeat and acknowledgement race (`a62b8be`) and the drain race (`148cf87`), both found in CI;
* soft exclusion reused a worker that had already failed a partition while healthy workers were merely busy
  (Phase 9);
* the local demonstration orphaned its processes (Phase 12).

The fault tests use timeouts of a few hundred milliseconds. Two of the races above surfaced only on CI machines,
so a heavily loaded machine could still expose a timing-sensitive test.

## 6. Known limitations

* **Single coordinator**, no persistence. Its loss cancels or loses running tasks.
* **Detection time**: a silent network failure is detected after the heartbeat timeout (10 s by default). Long
  garbage-collection pauses can make a healthy worker `SUSPECT`.
* **Wrong but well-formed results** pass per-partition validation. Core's final verification catches them, and
  the task then fails, because the runtime cannot tell which partition was wrong.
* **Hung kernels**: a Core kernel that stopped polling its cancellation token would keep its slot until the worker
  restarts.
* **Message size**: frames are limited to 64 MiB by default. With base64 and row bands, a matrix multiplication
  work unit reaches that limit between about n = 2300 and n = 2500, depending on the number of partitions.
* **Bounded history**: the last 1000 finished tasks and the last 10 000 events are kept in memory.
* **Advice**: Core's estimates are only as good as the calibrations and the network measurements. The runtime
  measures the network only after distributed attempts have completed.

## 7. Performance (measured)

From [benchmarks.md](benchmarks.md) (one 4-vCPU machine, loopback, medians of 5 runs):

| Measurement | Result |
|---|---|
| Prime counting to 10¹⁰ on 1, 2, 3, 4 single-slot workers | Speedup 0.95, 1.93, 2.82, 3.65 against Core's sequential 9.46 s (Core's 4 threads: 3.30) |
| Fixed cost of a distributed task | About 5-20 ms |
| Scheduling | 0.1-0.4 ms of coordinator time per partition |
| Partitions per slot (2 workers × 2 slots) | 1 to 16 per slot within 8 % of the best (2.54 s); 32 per slot 17 % slower (2.98 s) |
| Matrix multiplication, 2 workers × 2 slots | Slower than sequential up to n = 1024; 1.28× at n = 1536 (Core's 4 threads: 2.97×), with 240 MiB moved and 1.21 s of encoding |
| One worker 4× slower than two others | Every policy 6.6-7.0 s, against 5.0 s without the slow worker |

## 8. Observations for Contributor 1 (Core)

* `EncodedPayload.crc32c` was always `48674bc7` in every work unit observed. The inner frame ends with its own
  CRC-32C, and a CRC computed over data followed by its own CRC yields the CRC's constant residue (`0x48674BC7`
  for CRC-32C). Integrity is still checked by the inner CRC, so nothing is wrong, but the outer checksum adds no
  protection. It could be computed over the payload without its trailer, or dropped.
* Row-band matrix partitions carry all of B, so distributed communication grows with partitions × n²
  ([benchmarks.md §3.4](benchmarks.md#34-communication-heavy-work)).
* Seven links in Core's documents point to section anchors that no longer exist, because status notes such as
  "*(Core: implemented — …)*" were added to the headings later (for example `integration-contract.md#9-error-model--errorinfo`
  from `task-model.md`, and `#12-versioning-policy` inside `integration-contract.md`, whose section 12 is now
  "Cancellation semantics"). They were found by a link check and left unchanged, because those documents belong to
  Contributor 1.

## 9. Integration instructions

* **Build:** `./mvnw install` at the repository root (Core), then `./mvnw -f distributed/pom.xml install`
  (runtime). The runnable jar `distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar` contains
  both.
* **Application (Contributor 3), embedded:** depend on `org.districompute:districompute-distributed:0.1.0-SNAPSHOT`,
  start a `Coordinator` and use its Java API: `submit`, `task`, `tasks`, `cancel`, `advise`, `workers`,
  `events().subscribe`, `metrics`, `setSchedulingPolicy`
  ([integration-contract.md §2](integration-contract.md#2-what-the-runtime-offers-the-application)).
* **Application, standalone:** run the coordinator with `--http-port` and a token, and use the HTTP API and its
  Server-Sent Events ([integration-contract.md §2.5](integration-contract.md#25-http-api-of-a-standalone-coordinator--implemented)).
* **Core (Contributor 1):** nothing to change. The runtime uses only Core's public facade
  (`PartitionedTaskSession`, `PartitionWorker`, `LocalExecutionEngine`, `StrategyAdvisor`, `ResourceSnapshot`,
  `Calibration`, `AlgorithmRegistry`, the contract types). Changes to `WorkUnitMessage`,
  `PartialResultMessage` or `ErrorInfo` change the wire protocol.
* **Deployment:** [deployment.md](deployment.md).
* **Open decisions** for the three contributors: the protocol and event formats (still drafts); whether the
  Application embeds the coordinator or calls it over HTTP; binary frames for data-heavy algorithms.

## 10. Version and commit intended for integration

* **Version:** `0.1.0-SNAPSHOT`, protocol `0.1`.
* **Commit:** **`055cb18`** on `feature/distributed-engine`. It is the last commit that changes code, and it includes
  `5cd6ef0`. It was verified by GitHub Actions run 45206004346 (all four jobs succeeded) and by the local runs in
  §4.1. Later commits change documentation only.
* **Core:** built from the same branch (`feature/core-engine` content at the base of this branch). The runtime
  needs no change in Core.
* **Before merging:** review by the responsible contributor (see the header), and agreement of Contributors 1 and
  3 on the integration contract, which is still a draft.
