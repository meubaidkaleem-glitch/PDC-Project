# Benchmark report: primes-scaling

Segmented-sieve prime counting: compute-bound and embarrassingly parallel (tiny partial results).

| Environment | |
|---|---|
| Measured | 2026-11-22T09:55:59.561609100Z to 2026-11-22T09:56:11.781003001Z |
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
| set 0 sequential | 1 | 100.192 | 95.428 | 142.375 | 19.599 | 0.000 | 0.041 | 0.000 | 0.2620 | baseline |  |
| set 0 threads=1 default partitions | 4 | 98.882 | 88.925 | 109.014 | 8.044 | 0.016 | 0.040 | 1.780 | 0.2530 | 1.013 | 1.013 |
| set 0 threads=2 default partitions | 8 | 56.306 | 53.672 | 68.753 | 5.946 | 0.017 | 0.029 | 5.073 | 0.4884 | 1.779 | 0.8897 |
| set 0 threads=4 default partitions | 16 | 35.236 | 32.700 | 42.928 | 4.373 | 0.019 | 0.034 | 5.388 | 0.9174 | 2.843 | 0.7109 |
| set 1 sequential | 1 | 477.231 | 388.393 | 616.257 | 103.578 | 0.000 | 0.030 | 0.000 | 0.2515 | baseline |  |
| set 1 threads=1 default partitions | 4 | 435.211 | 424.498 | 445.972 | 8.465 | 0.017 | 0.041 | 1.840 | 0.2486 | 1.097 | 1.097 |
| set 1 threads=2 default partitions | 8 | 264.688 | 233.298 | 289.556 | 20.327 | 0.020 | 0.044 | 23.747 | 0.4438 | 1.803 | 0.9015 |
| set 1 threads=4 default partitions | 16 | 138.783 | 134.860 | 147.658 | 4.935 | 0.037 | 0.044 | 13.438 | 0.9143 | 3.439 | 0.8597 |

Parameter sets:

* set 0: `primes.count@1.0.0` {upperBound=100000000}
* set 1: `primes.count@1.0.0` {upperBound=400000000}
