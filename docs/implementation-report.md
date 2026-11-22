# Implementation Report: DistriCompute-Core

| | |
|---|---|
| Component | DistriCompute-Core (Contributor 1); GitHub repository `PDC-Project` |
| Branch | `feature/core-engine` (not yet merged into `main`) |
| Version | `org.districompute:districompute-core:0.1.0-SNAPSHOT`; contract schema `0.1` (draft) |
| Date | 2026-11-22 |
| Development assistance | Implemented with an AI coding assistant. Every commit carries a `Co-Authored-By` trailer. The responsible contributor must review the code before merging. |

## 1. Features completed

| Area | What exists | Evidence |
|---|---|---|
| Task model | `TaskSpec` (ids, experiment id, algorithm and version, parameters, constraints, priority, timestamp, schema version), `TaskStatus` with 11 states and enforced transitions, `TaskLifecycle` with history | `TaskStatusTest` (exhaustive transition matrix), `TaskSpecTest`, `TaskLifecycleConcurrencyTest` |
| Validation | Typed parameter schema; every violation reported with a field path and code; cross-field and dimension rules; memory guard before allocation | `ParameterValidatorTest`, `MatrixAlgorithmsTest`, `LocalExecutionEngineTest` |
| Registry | `ServiceLoader` discovery, duplicate and default checks, SemVer resolution, capabilities derived from implemented interfaces | `AlgorithmRegistryTest`, `BuiltInCatalogTest` (every advertised local mode of all 10 algorithms completes) |
| Algorithms | Prime counting, merge sort, matrix addition, matrix multiplication (cache-blocked, configurable tiles, inline operands), numerical integration, polynomial evaluation, root finding, Monte Carlo π, descriptive statistics, filter and search. Each has a sequential and a partitioned implementation and an independent verifier. | `PrimeCountingTest` (π(10^k) up to 10^11), `MergeSortTest`, `MatrixAlgorithmsTest` (bitwise equality with the naive product), `NumericAlgorithmsTest`, `DataAlgorithmsTest` |
| Local parallel engine | Bounded `WorkerPool` (fixed threads, bounded queue, controlled submission, completion queue, fail-fast, structured completion, shutdown); admission control; deadlines; cooperative cancellation; progress events | `WorkerPoolTest`, `LocalExecutionEngineConcurrencyTest`, `TaskProgressTest` |
| Partitioning and aggregation | Range, chunk and 2-D block planners (deterministic, remainder-aware, exact cover); `PartialResultCollector` (accepted, duplicate, conflicting, invalid, unknown, late, missing, failed); versioned CRC-32C codecs | `PartitionersTest`, `PartialResultCollectorTest`, `PayloadCodecTest`, `PartitionedExecutionTest`, `NumericPartitionedExecutionTest` |
| Cross-mode correctness | Multithreaded results are **bitwise identical** to sequential for matrix multiplication and addition, primes, sort, polynomial evaluation, root finding, Monte Carlo and filter. Integration and statistics agree within rounding and pass verification. | the two partitioned-execution test classes above |
| Distributed (Core side) | `PartitionedTaskSession` and `PartitionWorker`, with JSON message envelopes, retry eligibility and verification on completion | `DistributedSessionTest`: a simulated runtime exchanging JSON; lost-worker recovery; duplicate, corrupted, misplaced and foreign messages classified |
| Metrics and benchmarks | `ExecutionMetrics` (partitioning, compute, aggregation, busy, scheduling overhead, CPU, GC, allocation), `EnvironmentInfo`, `SampleStatistics`, `BenchmarkRunner` (warm-ups, verified repeats, speedup and efficiency), JSON, CSV and Markdown reports, six suites | `SampleStatisticsTest`, `BenchmarkRunnerTest`, committed results in `benchmarks/results/` |
| Strategy assessment | `StrategyAdvisor` with explicit verdicts and reasons; measured small-workload threshold; memory, worker and communication rules; calibration from benchmark reports | `StrategyAdvisorTest` |
| GPU abstraction | `GpuBackend` SPI and detection; GPU advertised only for algorithms a detected backend supports | `GpuStatusTest` |
| Tooling | `CoreCli` (`catalog`, `run`, `advise`, `benchmark`); examples checked end to end; Maven Wrapper; Enforcer; `-Xlint:all -Werror`; Checkstyle; SpotBugs; JaCoCo; GitHub Actions CI | `ExamplesTest`; CI runs (section 4) |

## 2. Features partially completed

| Feature | State | What is missing |
|---|---|---|
| Distributed execution (`DISTRIBUTED_CPU`) | The Core side is complete and tested in-process | Networking, worker registration and scheduling belong to Contributor 2. Joint integration tests with DistriCompute-Distributed have not been run, because that repository wasn't available. |
| Shared integration contract | A complete draft (`0.1`) with JSON examples produced by real runs | Agreement by Contributors 2 and 3, and moving it to the Application repository |
| Strategy advisor | Working and tested | Calibration history must be supplied by the Application. Without it there are no time estimates, only rules. Traffic estimates use input plus output bytes, which is a lower bound. |
| Data input | Generated and inline inputs | Dataset references (uploaded files) are postponed until the Application defines storage |

## 3. Features not implemented

* **GPU execution.** There was no GPU to validate a backend, and GPU must never be advertised without a working
  implementation. The SPI, detection, technology review and integration path are in
  [gpu-backend.md](gpu-backend.md).
* **Hybrid CPU/GPU/distributed execution.** It depends on GPU execution.
* **Priority 3 workloads** (image and signal processing, simulations), and Strassen-type matrix multiplication.
* **A parallel k-way merge** for sorting. The merge is sequential, which bounds the speedup (see section 6).
* **Interleaved or randomized benchmark case order** (listed as future work in the methodology).

## 4. Tests executed and their actual results

Run on the development container (OpenJDK 21.0.13, Maven 3.9.11):

| Suite | Command | Result |
|---|---|---|
| Unit (default) | `./mvnw verify` | **149 tests, 0 failures, 0 errors**; Checkstyle 0 violations; SpotBugs 0 bugs |
| Concurrency | `./mvnw test -Dtest.groups=concurrency -Dtest.excludedGroups=` | **30 tests, 0 failures**. Repeated 5 times in Phase 4 and 3 times in Phase 5 with no flaky failure. |
| Performance scenarios (correctness only) | `./mvnw test -Dtest.groups=performance -Dtest.excludedGroups=` | **4 tests, 0 failures** |
| Coverage (unit suite only) | JaCoCo | instructions 85.1%, lines 86.4%, branches 73.8% |

GitHub Actions results for this branch (from the public Actions API), job "Build, unit tests, concurrency tests"
plus job "Code quality":

| Commit | Content | CI |
|---|---|---|
| `1a55cd3` | Phase 1 | success |
| `b489d13` | Phase 2 | success |
| `1f34de8` | Phase 3 | success |
| `00ef401` | Phase 4 | success |
| `192aaa6` | Phase 5 | success |
| `1960745` | Phase 6 | success |
| `7be463f` | Phase 7 | success (every step, including concurrency and performance-scenario tests) |
| `1e70c0c` | Phase 8 | success |
| `6e20065` | Phase 10 (CLI, examples, end-to-end test, release docs) | **success**: run 45010972566, every step of both jobs, including concurrency and performance-scenario tests |

Defects found and fixed during development (by tests and static analysis, not reported afterwards):
* SpotBugs found a visibility race in `PartitionedTaskSession.value()` (an unsynchronized read of a field written
  under lock). It is fixed.
* SpotBugs flagged a `Boolean` method that returned `null` in the parameter validator. It is fixed.
* The filter aggregation did not truncate merged indices to the query limit. Found in review and fixed before
  testing, and now covered by `DataAlgorithmsTest`.
* Three test assertions were wrong and were corrected: an isqrt boundary, a collector attempt count, and a
  timestamp-based overlap check that was replaced by a direct concurrency probe.

## 5. Known bugs

No known open bugs. The limitations below are deliberate, documented behaviour.

## 6. Known limitations

* **The contract is a draft (`0.1`).** Readers reject other minor versions while the major version is 0. Any field
  may still change by team agreement.
* **Merge sort speedup is bounded** by its sequential k-way merge. It measured at most 1.59× on 4 vCPUs.
* **Floating-point reductions** (integration, statistics) agree across partitionings only within rounding, not bit
  for bit.
* **Root finding** misses roots without a sign change (even multiplicity) and roots closer together than one scan
  subinterval. Verification checks the reported roots, not completeness.
* **Polynomial evaluation verification** is exact for 512 sampled points. The other points rely on cross-mode
  equality.
* **Statistics verification** uses a generous relative tolerance (1e-9), not a proven bound.
* **Monte Carlo verification** is statistical, so a correct run fails with probability ≈ 6·10⁻⁷.
* **Matrix sizes** are limited by the heap: the memory guard rejects oversized requests. The maximum dimension is
  20,000 for multiplication and 100,000 for addition.
* **Distributed metrics.** `computeNanos` from the session includes communication. The distributed runtime must
  report transfer separately.
* **Repository name.** `PDC-Project` differs from `DistriCompute-Core` in the specification. Consider renaming it
  before the Application adds the submodule.
* **License.** MIT was chosen as a default and still needs the team's confirmation.

## 7. Performance limitations (measured)

Measured on a shared 4-vCPU cloud VM (raw data and caveats in
[benchmarks/results/dev-container-2026-11-22](../benchmarks/results/dev-container-2026-11-22)):
* **Matrix multiplication:** 3.53× at 1024³, 3.21× at 512³ and 1.89× at 256³ with 4 threads.
* **Prime counting to 4·10⁸:** 3.44× with 4 threads.
* **Merge sort of 4·10⁶ elements:** 1.59× with 4 threads.
* **Small workloads** are slower in parallel: 128³ matrix multiplication ran 0.88× with 2 threads, and 256³ ran
  0.84× with one partitioned thread.
* **Noise:** sequential baselines showed high dispersion (up to SD 152 ms for a median of 1032 ms). Some
  efficiencies slightly above 1 are attributed to noise and run-order effects. These numbers say nothing about
  the final demonstration hardware, where the suites should be re-run.

## 8. Integration instructions

* **Application (Contributor 3).**
  1. Add this repository as a Git submodule pinned to the reviewed commit, or to tag `v0.1.0` once tagged.
  2. Add the submodule as a module in the Application's Maven aggregator, and depend on
     `org.districompute:districompute-core:0.1.0-SNAPSHOT`.
  3. The JSON formats and examples are in [integration-contract.md](integration-contract.md) and
     [examples/](../examples).
* **Distributed (Contributor 2).**
  1. Build Core from the pinned commit or tag (`./mvnw install`).
  2. Use `PartitionedTaskSession` on the coordinator and `PartitionWorker` on workers (integration contract §14).
  3. Supply a `ResourceSnapshot` for strategy assessment (§15).
  4. Report communication metrics separately.
* **Open decisions** are listed in integration contract §17: the build mechanism for Distributed, payload
  transport (base64 versus binary frames), task id format, Jackson version alignment, and the format of history
  data.

## 9. Version and commit intended for integration

* **Version:** `0.1.0-SNAPSHOT`. Tag `v0.1.0` after review and merge.
* **Commit:** **`6e20065`** on `feature/core-engine`. This is the last commit that changes code, and it is
  verified by GitHub Actions run 45010972566 (all steps succeeded) and by a clean local run (149 unit, 30
  concurrency and 4 performance-scenario tests, 0 Checkstyle violations, 0 SpotBugs bugs). Later commits on the
  branch change documentation only.
