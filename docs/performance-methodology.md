# Performance Methodology

This document defines how DistriCompute-Core measures performance, so every reported number can be interpreted
and reproduced. It implements shared rule 7: performance claims must rest on recorded measurements, with the
environment recorded and overheads kept separate.

## 1. What one execution measures (`ExecutionMetrics`)

| Metric | Definition | Unit |
|---|---|---|
| `validationNanos` | Schema and semantic validation, workload analysis, memory guard | ns |
| `inputPreparationNanos` | Generating or decoding the input. **Excluded** from execution time. | ns |
| `partitioningNanos` | Building the partition plan and extracting work units (plus encoding, for distributed sessions) | ns |
| `computeNanos` | First partition submitted to last partial collected (sequential: the reference implementation) | ns |
| `aggregationNanos` | Validating, ordering and combining partial results | ns |
| `executionNanos` | `partitioning + compute + aggregation`. **The basis for speedup.** | ns |
| `verificationNanos` | Independent verification. **Excluded** from execution time. | ns |
| `busyNanos` | Sum of the time spent inside partition kernels, across threads | ns |
| `schedulingOverheadNanos` | `max(0, computeNanos − busyNanos / threads)`: dispatch, queueing, load imbalance and idle time in the compute phase | ns |
| `cpuNanos` | Process CPU time during execution, **including JIT and GC threads** (−1 if unavailable) | ns |
| `gcCount`, `gcMillis` | Garbage collections during execution | count, ms |
| `allocatedBytes` | Heap allocated by the coordinating and worker threads during execution (−1 if unavailable) | bytes |

All durations come from `System.nanoTime()` on the executing node. Wall-clock timestamps (status history) are
used only for ordering events, never for durations.

For distributed sessions (`PartitionedTaskSession`), `computeNanos` is the wall time the coordinator observes from
the first dispatch to the last accepted partial result. It therefore **includes** network transfer and remote
scheduling. `busyNanos` is the sum of the kernel times the workers report. The distributed runtime must report its
own communication metrics (transfer time, bytes, serialization) separately, and must never fold them into
compute time.

## 2. Repeated trials and statistics (`BenchmarkRunner`)

* **Warm-up.** Each case first runs `warmupRuns` unmeasured times, so the JIT compiles the hot code. Measured runs
  follow.
* **Repetition.** Each case runs `measuredRuns` times. Reports keep every raw sample (`executionNanosSamples`).
* **Statistics.** `SampleStatistics` gives the count, minimum, maximum, mean, **median** and sample standard
  deviation (`n − 1`). The median is the headline figure, because it is robust against occasional outliers such as
  a GC pause or a noisy neighbour. Always read it together with the dispersion.
* **Correctness gate.** A run counts only if the task ended `COMPLETED`, meaning its result passed independent
  verification. Each case also records the distinct result digests, which must be identical for deterministic
  algorithms.
* **Isolation.** Cases run one at a time on a dedicated engine. The engine's worker threads start up front, so
  thread creation is never measured.

## 3. Derived metrics

| Metric | Definition | Assumptions |
|---|---|---|
| Speedup | `median(executionNanos of the sequential baseline) / median(executionNanos of the case)` | Same algorithm version, same parameter set (same input, from the same seed), same engine and same machine. The baseline is `SEQUENTIAL_CPU`, a dedicated sequential implementation without partitioning overhead, **not** the parallel code run with one thread. |
| Efficiency | `speedup / threads` | `threads` is the number of worker threads used, which may be vCPUs or hyperthreads. Efficiency above 1 with noisy baselines indicates measurement noise or order effects, not proof of superlinearity. |
| Throughput | `estimatedOperations / median(executionNanos)` | `estimatedOperations` comes from the workload profile's formula. It is an estimate, labelled with its unit. |
| CPU utilization | `cpuNanos / (executionNanos × availableProcessors)` | Process-wide, so it includes JIT and GC activity. Use it as a rough indicator only. |

This is **strong scaling**: a fixed problem size with more threads. Weak scaling (a problem size that grows with
the thread count) can be measured by listing proportional parameter sets in a suite.

## 4. Environment

Every result embeds an `EnvironmentInfo`: Core version, Java version, vendor and VM, OS, architecture, available
processors (which respects container CPU limits), maximum heap, physical memory, CPU model, and `-X`/`-XX` options
with any paths removed. Host names, user names and paths are deliberately not recorded.

## 5. Rules

1. Never compare results from different environments or Core versions without naming the differences.
2. Never publish a number that was not produced by the benchmark tooling. Committed results include their raw
   JSON.
3. Report communication and scheduling overheads separately from computation.
4. Tests assert correctness only. Timing is never asserted, and CI never benchmarks.
5. Profile before optimizing, and keep optimizations only if a benchmark shows an improvement while correctness
   tests still pass.

## 6. Known limitations and future work

* Cases run in a fixed order. Interleaving cases across rounds (or randomizing their order) would reduce drift and
  order effects. The committed development-container results show such effects (see their README).
* No CPU pinning or frequency control. Run important measurements on a quiet, dedicated machine.
* JMH microbenchmarks are not used. The harness measures whole executions as the platform runs them, so its
  results reflect end-to-end behaviour, not isolated kernels.
* There is no confidence interval on speedup. Raw samples are kept, so bootstrap intervals can be computed later.
