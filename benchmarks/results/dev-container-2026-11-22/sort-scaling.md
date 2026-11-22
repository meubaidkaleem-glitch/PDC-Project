# Benchmark report: sort-scaling

Merge sort of uniform 64-bit integers: parallel chunk sorts followed by a sequential k-way merge, so speedup is bounded by the merge (Amdahl).

| Environment | |
|---|---|
| Measured | 2026-11-22T09:55:43.727554627Z to 2026-11-22T09:55:57.163795488Z |
| CPU | Intel(R) Xeon(R) Processor @ 2.10GHz (4 processors available to the JVM) |
| Memory | max heap 3420 MiB, physical 13674 MiB |
| JVM | Ubuntu 21.0.13.0.3 (OpenJDK 64-Bit Server VM) |
| OS | Linux 6.18.44-fc-v726 amd64 |
| DistriCompute-Core | 0.1.0-SNAPSHOT |
| Engine worker threads | 4 |
| Runs per case | 2 warm-up + 5 measured |

Times are medians of `executionNanos` (partitioning + compute + aggregation) in milliseconds. Input generation and verification are excluded. Speedup = median sequential time / median time for the same parameter set. Efficiency = speedup / threads. Every measured run passed independent result verification.

| Case | Partitions | Median ms | Min ms | Max ms | SD ms | Partitioning ms | Aggregation ms | Sched. overhead ms | CPU util. | Speedup | Efficiency |
|---|---|---|---|---|---|---|---|---|---|---|---|
| set 0 sequential | 1 | 115.816 | 112.197 | 120.538 | 3.593 | 0.000 | 0.042 | 0.000 | 0.2620 | baseline |  |
| set 0 threads=2 default partitions | 8 | 94.488 | 86.260 | 99.840 | 6.009 | 0.080 | 32.740 | 6.971 | 0.3982 | 1.226 | 0.6129 |
| set 0 threads=4 default partitions | 16 | 79.419 | 63.776 | 86.511 | 8.430 | 0.099 | 39.270 | 6.195 | 0.4722 | 1.458 | 0.3646 |
| set 1 sequential | 1 | 528.282 | 526.301 | 551.356 | 10.337 | 0.000 | 0.035 | 0.000 | 0.2508 | baseline |  |
| set 1 threads=2 default partitions | 8 | 390.186 | 367.576 | 422.097 | 20.321 | 0.105 | 118.234 | 6.594 | 0.4210 | 1.354 | 0.6770 |
| set 1 threads=4 default partitions | 16 | 331.958 | 278.802 | 351.690 | 33.600 | 0.120 | 184.378 | 15.819 | 0.5272 | 1.591 | 0.3979 |

Parameter sets:

* set 0: `sort.merge@1.0.0` {size=1000000}
* set 1: `sort.merge@1.0.0` {size=4000000}
