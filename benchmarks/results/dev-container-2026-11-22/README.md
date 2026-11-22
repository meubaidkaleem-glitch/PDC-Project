# Results: development container, 2026-11-22

These reports were produced by `BenchmarkCli` (Core `0.1.0-SNAPSHOT`, branch `feature/core-engine`) in the cloud
container used for development. Each `*.md` file is the generated report, and each `*.json` file holds every raw
sample. The figures below are copied from those files.

| | |
|---|---|
| CPU | Intel(R) Xeon(R) Processor @ 2.10GHz, 4 processors available to the JVM (shared cloud VM) |
| Memory | max heap 3420 MiB, physical 13674 MiB |
| JVM | OpenJDK 64-Bit Server VM 21.0.13 (Ubuntu build), default GC, no tuning flags |
| Method | 2 warm-up + 5 measured runs per case, cases run one after another, median of `executionNanos` |

## Summary (median execution time and speedup over the sequential baseline)

| Workload | Sequential | 1 thread | 2 threads | 4 threads |
|---|---|---|---|---|
| matrix.multiply 256³ | 14.8 ms | 17.6 ms (0.84×) | 11.4 ms (1.30×) | 7.9 ms (1.89×) |
| matrix.multiply 512³ | 118.8 ms | 117.5 ms (1.01×) | 72.3 ms (1.64×) | 37.0 ms (3.21×) |
| matrix.multiply 1024³ | 1032.2 ms | 917.6 ms (1.12×) | 476.1 ms (2.17×) | 292.6 ms (3.53×) |
| primes.count to 10⁸ | 100.2 ms | 98.9 ms (1.01×) | 56.3 ms (1.78×) | 35.2 ms (2.84×) |
| primes.count to 4·10⁸ | 477.2 ms | 435.2 ms (1.10×) | 264.7 ms (1.80×) | 138.8 ms (3.44×) |
| sort.merge 10⁶ | 115.8 ms | — | 94.5 ms (1.23×) | 79.4 ms (1.46×) |
| sort.merge 4·10⁶ | 528.3 ms | — | 390.2 ms (1.35×) | 332.0 ms (1.59×) |

## Observations

* **Compute-bound, independent work scales.** Matrix multiplication and prime counting reach 3.2–3.5× on 4
  processors for the larger inputs.
* **Small inputs don't benefit.** For 256³ and smaller, partitioning and scheduling overhead is a large part of the
  run time. One partitioned thread is slower than the sequential baseline (0.84×). The `quick-smoke` run (128³,
  2 threads) measured 0.88×. Sequential execution is the right choice for small workloads.
* **Sorting is bounded by its sequential merge.** Chunks are sorted in parallel, but the k-way merge
  (O(n log k)) runs on one thread, so speedup stays below 1.6× (Amdahl's law). A parallel merge would be needed to
  improve this.
* Every run of every case produced the **same result digest** as the sequential baseline.

## Caveats

* This is a **shared cloud VM**. Other tenants, CPU frequency changes and container CPU quotas add noise. Some
  sequential baselines show large dispersion. For matrix multiplication 1024³ the standard deviation is 151.8 ms
  around a median of 1032.2 ms, and for primes to 4·10⁸ it is 103.6 ms.
* A few speedups at one thread exceed 1.0 (up to 1.12×), and one efficiency exceeds 1.0 (1.08 at 2 threads,
  1024³). With the noisy baselines above, these are most plausibly **measurement noise and run-order effects**:
  the sequential case runs first, while the JIT and caches are still settling. They are not evidence of
  superlinear scaling. Interleaving the cases across rounds would reduce order effects. This is listed as future
  work in the methodology.
* "Processors" are vCPUs. Whether they map to physical cores or hyperthreads isn't visible from inside the VM.
  Efficiency is computed per vCPU.
* The numbers describe this machine on this day. Re-run the suites on the target hardware before drawing
  conclusions for the final demonstration.
