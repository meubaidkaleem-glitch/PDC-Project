# Benchmark report: matmul-scaling

Strong scaling of cache-blocked matrix multiplication: sequential baseline versus 1, 2 and 4 threads for three problem sizes (row-band partitions, 4 per thread by default).

| Environment | |
|---|---|
| Measured | 2026-11-22T09:55:18.505405754Z to 2026-11-22T09:55:41.554653292Z |
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
| set 0 sequential | 1 | 14.847 | 11.414 | 38.346 | 10.978 | 0.000 | 0.035 | 0.000 | 0.6348 | baseline |  |
| set 0 threads=1 default partitions | 4 | 17.643 | 15.271 | 18.929 | 1.490 | 0.061 | 0.214 | 1.523 | 0.5283 | 0.8415 | 0.8415 |
| set 0 threads=2 default partitions | 8 | 11.400 | 10.892 | 13.028 | 0.837 | 0.068 | 0.172 | 2.789 | 0.5757 | 1.302 | 0.6512 |
| set 0 threads=4 default partitions | 16 | 7.862 | 6.802 | 11.146 | 1.732 | 0.053 | 0.472 | 2.963 | 0.6360 | 1.888 | 0.4721 |
| set 1 sequential | 1 | 118.769 | 101.613 | 127.513 | 10.477 | 0.000 | 0.039 | 0.000 | 0.2526 | baseline |  |
| set 1 threads=1 default partitions | 4 | 117.508 | 109.799 | 134.130 | 10.342 | 0.091 | 0.837 | 2.386 | 0.2553 | 1.011 | 1.011 |
| set 1 threads=2 default partitions | 8 | 72.326 | 63.910 | 79.489 | 6.000 | 0.177 | 0.753 | 6.203 | 0.5028 | 1.642 | 0.8211 |
| set 1 threads=4 default partitions | 16 | 36.981 | 32.288 | 46.661 | 6.199 | 0.104 | 0.794 | 8.028 | 0.8517 | 3.212 | 0.8029 |
| set 2 sequential | 1 | 1032.242 | 756.550 | 1170.987 | 151.773 | 0.000 | 0.027 | 0.000 | 0.2502 | baseline |  |
| set 2 threads=1 default partitions | 4 | 917.603 | 898.986 | 934.931 | 14.318 | 0.102 | 4.724 | 3.366 | 0.2495 | 1.125 | 1.125 |
| set 2 threads=2 default partitions | 8 | 476.130 | 464.043 | 518.665 | 22.528 | 0.114 | 2.806 | 32.818 | 0.4570 | 2.168 | 1.084 |
| set 2 threads=4 default partitions | 16 | 292.627 | 263.690 | 332.855 | 25.589 | 0.086 | 3.011 | 28.561 | 0.8970 | 3.528 | 0.8819 |

Parameter sets:

* set 0: `matrix.multiply@1.0.0` {rowsA=256, colsA=256, colsB=256}
* set 1: `matrix.multiply@1.0.0` {rowsA=512, colsA=512, colsB=512}
* set 2: `matrix.multiply@1.0.0` {rowsA=1024, colsA=1024, colsB=1024}
