# Release Notes

## 0.1.0 (unreleased, on `feature/core-engine`)

The first complete version of DistriCompute-Core. Integration contract schema: **0.1 (draft)**, pending agreement
with Contributors 2 and 3.

### Added

* **Task model and lifecycle.**
  * `TaskSpec`, with a schema version, normalized parameters, constraints and priority.
  * An 11-state `TaskStatus` machine with enforced transitions, where `COMPLETED` is reachable only after
    verification.
  * `TaskLifecycle`, which keeps the status history.
* **Algorithms.** There are 10 verified algorithms, all of them partitionable:
  * `primes.count`
  * `sort.merge`
  * `matrix.add`
  * `matrix.multiply` (cache-blocked, configurable tile size, inline operands with dimension checks)
  * `numeric.integrate`
  * `numeric.polynomial-eval`
  * `numeric.root-find`
  * `numeric.monte-carlo`
  * `data.statistics`
  * `data.filter`
* **Extensibility.** Algorithms implement `ComputeAlgorithm` and, optionally, `PartitionedAlgorithm`. The
  `AlgorithmRegistry` discovers them with `ServiceLoader` and derives their execution modes from the implemented
  interfaces.
* **Validation.**
  * A parameter schema with typed validation that reports every violation.
  * Cross-field and dimension rules.
  * A memory guard that runs before any allocation.
* **Local execution.** `LocalExecutionEngine` runs `SEQUENTIAL_CPU` and `MULTITHREADED_CPU`.
  * The bounded `WorkerPool` uses fixed, pre-started threads, a bounded queue and controlled submission. It fails
    fast and completes structurally.
  * It supports admission control, deadlines, cooperative cancellation and orderly shutdown.
  * Progress events are throttled and monotonic.
* **Partitioning.**
  * Deterministic, remainder-aware range, chunk and 2-D block planners, with exact-cover checks.
  * `PartialResultCollector` classifies partial results as accepted, duplicate, conflicting, invalid, unknown or
    late, and tracks missing and failed partitions.
  * Binary payload codecs are versioned, little-endian and checked with CRC-32C.
* **Distributed integration (Core side).** `PartitionedTaskSession` (coordinator) and `PartitionWorker` (worker),
  with JSON message envelopes and retry eligibility.
* **Verification.** Every algorithm has an independent verifier:
  * Lucy–Hedgehog π(x) for prime counting;
  * Freivalds with a floating-point error bound for matrix multiplication;
  * a multiset fingerprint for sorting;
  * analytic, `BigDecimal` and statistical references for the numerical operations.
* **Metrics and benchmarks.**
  * `ExecutionMetrics` separates partitioning, compute, aggregation and scheduling overhead.
  * `EnvironmentInfo`, `SampleStatistics`, `BenchmarkRunner` and `BenchmarkCli`.
  * Reports come out as JSON, CSV and Markdown, with six suites and measured results committed.
* **Strategy assessment.** `StrategyAdvisor` gives every mode a verdict with reasons, and can be calibrated from
  benchmark reports.
* **GPU.** A `GpuBackend` SPI with capability detection. No backend ships, so GPU is reported as unavailable.
* **Tooling.**
  * `CoreCli` (`catalog`, `run`, `advise`, `benchmark`).
  * Example tasks and real outputs, checked by `ExamplesTest`.
  * The Maven Wrapper, Enforcer, `-Werror`, Checkstyle, SpotBugs and JaCoCo.
  * GitHub Actions CI with separate unit, concurrency, performance-scenario and code-quality steps.

### Known limitations

See the [implementation report](docs/implementation-report.md#known-limitations). The main ones:
* no GPU execution;
* the contract is still a draft;
* the merge-sort aggregation is sequential;
* benchmark results come from a shared cloud VM;
* root finding misses roots that have no sign change.
