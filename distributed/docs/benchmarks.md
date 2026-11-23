# Benchmarks

Real measurements of the runtime, produced by the `bench` command. The raw data of every run and the generated
summary are committed in [benchmarks/2026-11-23](../benchmarks/2026-11-23).

## 1. Method

```bash
java -jar distributed/target/districompute-distributed-0.1.0-SNAPSHOT-all.jar bench --suite standard --output benchmark-results
```

* **Processes.** Each configuration gets a fresh coordinator (in the `bench` JVM) and fresh worker processes:
  separate JVMs running the `worker` command, each with its own heap (1 GiB), garbage collector and JIT, talking to
  the coordinator over loopback TCP with the real protocol. Local runs (`SEQUENTIAL_CPU`, `MULTITHREADED_CPU`) go
  through Core's `LocalExecutionEngine` in the `bench` JVM, while no worker process is running.
* **Runs.** One unmeasured warm-up run per configuration (JIT compilation, and history for the performance-aware
  policy), then 5 measured runs. Tables show the median and the range.
* **Wall time** is measured by the benchmark from just before `submit` to the completion of the task, as the
  Application would see it. It includes Core's planning, aggregation and verification.
* **Validity.** A run counts only if Core reports `COMPLETED`, Core's verification passed, and its result digest
  equals that of every other run of the same workload, local or distributed. Otherwise the run is listed as a
  problem, excluded from the tables, and `bench` exits with status 1.
* **Sums** (kernel, transfer, encoding) add up per-attempt times, which overlap in wall time. *Transfer* is an
  attempt's round trip minus its residence on the worker: it includes TCP on loopback and the queues of both
  connections, not the network alone. *Encoding* is the coordinator's time building and encoding work units.

## 2. Environment and caveats

Measured on 2026-11-23, 15:13–15:23 UTC, with the code of the commit that adds
[benchmarks/2026-11-23](../benchmarks/2026-11-23) (see [benchmarks/README.md](../benchmarks/README.md) for two
non-functional edits made afterwards):

| | |
|---|---|
| Machine | Cloud VM, 4 vCPUs (`Intel(R) Xeon(R) Processor @ 2.30GHz`), 14 GB memory, Linux 6.18 |
| Java | OpenJDK 21.0.13 (Ubuntu build), default garbage collector (G1) |
| Heaps | Coordinator and local runs: 3.3 GiB (JVM default); each worker process: 1 GiB |
| Network | Loopback: coordinator and workers on the same machine |
| Load at start | 1.04 (1-minute load average, the build that preceded the run) |
| Runs | 1 warm-up + 5 measured per configuration; 252 runs in total, all completed and verified, one result digest per workload |

**Read the numbers with these limits in mind.**

* **One machine.** The coordinator, every worker process and the benchmark share 4 virtual CPUs of a cloud VM.
  "Distributed" here means separate processes over loopback TCP, not separate machines: there is no real network
  latency or bandwidth limit, and the processes compete for the same cores and memory bandwidth. On a LAN,
  transfers would be slower and computation would not compete.
* **More processes than cores.** With 4 single-slot workers, the 4 kernels plus the coordinator and the JVMs'
  own threads share 4 vCPUs.
* **Virtual CPUs.** A cloud VM's vCPUs can be hyper-threads of shared physical cores, and neighbours on the host
  add noise. The ranges show the noise observed.
* **The slow worker is simulated.** Its fault profile stretches each kernel by sleeping for the extra time. It
  does not use more CPU, so it behaves like a slower machine, not like a busier one.
* **Two algorithms.** Prime counting is compute-bound with tiny messages. Matrix multiplication sends matrix data
  in every work unit. Other Core algorithms lie between these, or below both (where distribution never pays).

## 3. Results

### 3.1 Scaling with worker processes

Prime counting up to 10¹⁰ (sequential ≈ 9.5 s). Each worker has one slot and gets two partitions.

| Configuration | Cores | Wall s, median (min-max) | Speedup | Efficiency | Partitions | Kernel sum s | Transfer sum s | Encoding sum s | Scheduling ms | Moved MiB | Utilization | Imbalance | Attempts per worker |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| sequential | 1 | 9.459 (9.301-9.908) | 1.00 | 1.00 | 1 |  |  |  |  |  |  |  |  |
| multithreaded 4t | 4 | 2.865 (2.655-2.934) | 3.30 | 0.83 | 16 |  |  |  |  |  |  |  |  |
| distributed 1w x 1s | 1 | 9.929 (9.716-10.081) | 0.95 | 0.95 | 2 | 9.877 | 0.003 | 0.001 | 0.67 | 0.00 | 1.00 | 1.00 | w1 2 |
| distributed 2w x 1s | 2 | 4.900 (4.774-5.062) | 1.93 | 0.97 | 4 | 9.576 | 0.005 | 0.002 | 1.00 | 0.01 | 0.99 | 1.01 | w1 2, w2 2 |
| distributed 3w x 1s | 3 | 3.357 (3.336-3.384) | 2.82 | 0.94 | 6 | 9.687 | 0.009 | 0.003 | 1.70 | 0.01 | 0.98 | 1.02 | w1 2, w2 2, w3 2 |
| distributed 4w x 1s | 4 | 2.590 (2.561-2.713) | 3.65 | 0.91 | 8 | 9.866 | 0.017 | 0.003 | 2.07 | 0.01 | 0.96 | 1.03 | w1 2, w2 2, w3 2, w4 2 |

* With 1 to 4 single-slot workers, the speedup is 0.95, 1.93, 2.82 and 3.65: an efficiency of 0.91–0.97.
  Communication is negligible for this algorithm: under 20 ms of transfer and 3 ms of encoding in total, and
  about 10 KiB moved.
* One worker is 5 % slower than Core's sequential run. The kernel itself took 9.88 s in the worker process
  against 9.46 s for the whole sequential run, so the gap is in the kernel, not in communication. A plausible
  reason is that a fresh worker JVM had only one warm-up run. This was not investigated further.
* Four worker processes (3.65×) were faster than Core's multithreaded run with 4 threads (3.30×). The cause was
  not investigated. Separate heaps and partition counts differ between the two (8 partitions against 16), but the
  granularity experiment shows that 16 partitions are not slower. Treat it as an observation on this machine, not
  as a property of the runtime.

### 3.2 Partition granularity

The same task on 2 workers with 2 slots each, with 1 to 32 partitions per slot.

| Configuration | Cores | Wall s, median (min-max) | Speedup | Efficiency | Partitions | Kernel sum s | Transfer sum s | Encoding sum s | Scheduling ms | Moved MiB | Utilization | Imbalance | Attempts per worker |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| 4 partitions (1 per slot) | 4 | 2.705 (2.531-2.904) | 3.50 | 0.87 | 4 | 9.974 | 0.010 | 0.001 | 0.40 | 0.01 | 0.95 | 1.02 | w1 2, w2 2 |
| 8 partitions (2 per slot) | 4 | 2.747 (2.606-3.020) | 3.44 | 0.86 | 8 | 10.468 | 0.017 | 0.003 | 1.85 | 0.01 | 0.96 | 1.02 | w1 4, w2 4 |
| 16 partitions (4 per slot) | 4 | 2.542 (2.512-2.573) | 3.72 | 0.93 | 16 | 9.666 | 0.034 | 0.007 | 5.55 | 0.03 | 0.97 | 1.01 | w1 8, w2 8 |
| 32 partitions (8 per slot) | 4 | 2.730 (2.663-2.749) | 3.46 | 0.87 | 32 | 10.074 | 0.051 | 0.009 | 6.00 | 0.05 | 0.95 | 1.01 | w1 16, w2 16 |
| 64 partitions (16 per slot) | 4 | 2.735 (2.723-2.778) | 3.46 | 0.86 | 64 | 10.318 | 0.103 | 0.011 | 9.45 | 0.11 | 0.95 | 1.01 | w1 33, w2 31 |
| 128 partitions (32 per slot) | 4 | 2.984 (2.903-3.074) | 3.17 | 0.79 | 128 | 11.062 | 0.195 | 0.020 | 24.80 | 0.22 | 0.95 | 1.01 | w1 63, w2 65 |

* From 4 to 64 partitions, the wall time stays within 2.54–2.75 s. More partitions cost little here: scheduling
  grows from 0.4 to 9.5 ms in total, and transfer sums stay around 0.1 s or less.
* At 128 partitions the task is 17 % slower than the best setting (16 partitions) and 9 % slower than the
  typical 2.7 s. The kernel sum rises from about 10.0 to 11.1 s, because every segment of the sieve repeats some
  fixed work, and scheduling reaches 25 ms.
* Over-decomposition shows its benefit only when workers differ ([§3.5](#35-load-imbalance-and-scheduling-policies)).
  With identical workers, 1 partition per slot was already balanced (imbalance 1.02).

### 3.3 Workload size

Prime counting up to 10⁶ … 10⁹ on Core sequentially, Core with 4 threads, and 2 workers × 2 slots.

| Configuration | Cores | Wall s, median (min-max) | Speedup | Efficiency | Partitions | Kernel sum s | Transfer sum s | Encoding sum s | Scheduling ms | Moved MiB | Utilization | Imbalance | Attempts per worker |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| sequential, n=1e6 | 1 | 0.002 (0.002-0.002) | 1.00 | 1.00 | 1 |  |  |  |  |  |  |  |  |
| multithreaded 4t, n=1e6 | 4 | 0.002 (0.002-0.002) | 0.84 | 0.21 | 1 |  |  |  |  |  |  |  |  |
| distributed 2w x 2s, n=1e6 | 4 | 0.009 (0.006-0.018) | 0.18 | 0.04 | 1 | 0.001 | 0.003 | 0.000 | 0.18 | 0.00 | 0.12 | 1.00 | w1 1 |
| sequential, n=1e7 | 1 | 0.009 (0.009-0.009) | 1.00 | 1.00 | 1 |  |  |  |  |  |  |  |  |
| multithreaded 4t, n=1e7 | 4 | 0.006 (0.005-0.016) | 1.63 | 0.41 | 9 |  |  |  |  |  |  |  |  |
| distributed 2w x 2s, n=1e7 | 4 | 0.027 (0.018-0.034) | 0.33 | 0.08 | 8 | 0.015 | 0.025 | 0.001 | 0.67 | 0.01 | 0.19 | 1.33 | w1 4, w2 4 |
| sequential, n=1e8 | 1 | 0.087 (0.083-0.115) | 1.00 | 1.00 | 1 |  |  |  |  |  |  |  |  |
| multithreaded 4t, n=1e8 | 4 | 0.029 (0.027-0.033) | 2.95 | 0.74 | 16 |  |  |  |  |  |  |  |  |
| distributed 2w x 2s, n=1e8 | 4 | 0.052 (0.043-0.067) | 1.68 | 0.42 | 8 | 0.106 | 0.024 | 0.001 | 0.76 | 0.01 | 0.63 | 1.07 | w1 4, w2 4 |
| sequential, n=1e9 | 1 | 0.959 (0.895-0.994) | 1.00 | 1.00 | 1 |  |  |  |  |  |  |  |  |
| multithreaded 4t, n=1e9 | 4 | 0.264 (0.260-0.286) | 3.63 | 0.91 | 16 |  |  |  |  |  |  |  |  |
| distributed 2w x 2s, n=1e9 | 4 | 0.289 (0.251-0.336) | 3.32 | 0.83 | 8 | 0.974 | 0.019 | 0.001 | 1.09 | 0.01 | 0.87 | 1.01 | w1 4, w2 4 |

* A distributed task has a fixed cost of roughly 5–20 ms on loopback (n = 10⁶: 9 ms against 2 ms
  sequentially). It covers planning, the first dispatch, TCP round trips and aggregation.
* Distribution starts to pay between 10⁷ (9 ms sequentially, distributed 3× slower) and 10⁸ (87 ms
  sequentially, distributed 1.7× faster). At 10⁹ it reaches 3.3×, close to Core's 4 threads (3.6×).
* On one machine, Core's multithreaded mode is always at least as fast. Distribution pays when it adds machines,
  which this benchmark cannot show.

### 3.4 Communication-heavy work

Multiplication of two generated n × n matrices. With Core's default row-band partitioning, every work unit
carries its rows of A **and all of B** (`MultiplyWork`), so the data moved grows with partitions × n². (Core's 2-D
block partitioning sends only a column band of B, but needs more blocks. It was not measured.)

| Configuration | Cores | Wall s, median (min-max) | Speedup | Efficiency | Partitions | Kernel sum s | Transfer sum s | Encoding sum s | Scheduling ms | Moved MiB | Utilization | Imbalance | Attempts per worker |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| sequential, n=256 | 1 | 0.013 (0.012-0.015) | 1.00 | 1.00 | 1 |  |  |  |  |  |  |  |  |
| multithreaded 4t, n=256 | 4 | 0.007 (0.006-0.010) | 1.76 | 0.44 | 16 |  |  |  |  |  |  |  |  |
| distributed 2w x 2s, n=256 | 4 | 0.051 (0.041-0.071) | 0.25 | 0.06 | 8 | 0.018 | 0.051 | 0.021 | 0.34 | 6.68 | 0.22 | 1.32 | w1 4, w2 4 |
| sequential, n=512 | 1 | 0.082 (0.079-0.128) | 1.00 | 1.00 | 1 |  |  |  |  |  |  |  |  |
| multithreaded 4t, n=512 | 4 | 0.038 (0.033-0.040) | 2.18 | 0.54 | 16 |  |  |  |  |  |  |  |  |
| distributed 2w x 2s, n=512 | 4 | 0.160 (0.148-0.166) | 0.52 | 0.13 | 8 | 0.116 | 0.079 | 0.128 | 0.67 | 26.68 | 0.27 | 1.05 | w1 4, w2 4 |
| sequential, n=1024 | 1 | 0.613 (0.604-0.654) | 1.00 | 1.00 | 1 |  |  |  |  |  |  |  |  |
| multithreaded 4t, n=1024 | 4 | 0.204 (0.193-0.220) | 3.01 | 0.75 | 16 |  |  |  |  |  |  |  |  |
| distributed 2w x 2s, n=1024 | 4 | 0.655 (0.633-0.727) | 0.94 | 0.23 | 8 | 0.726 | 0.250 | 0.476 | 3.53 | 106.68 | 0.38 | 1.03 | w1 4, w2 4 |
| sequential, n=1536 | 1 | 2.116 (2.099-2.326) | 1.00 | 1.00 | 1 |  |  |  |  |  |  |  |  |
| multithreaded 4t, n=1536 | 4 | 0.713 (0.685-0.781) | 2.97 | 0.74 | 16 |  |  |  |  |  |  |  |  |
| distributed 2w x 2s, n=1536 | 4 | 1.651 (1.578-1.862) | 1.28 | 0.32 | 8 | 2.395 | 0.542 | 1.212 | 1.95 | 240.01 | 0.47 | 1.04 | w1 4, w2 4 |

* Distributed matrix multiplication was slower than sequential up to n = 1024 (0.94×). At n = 1536 it was
  1.28× faster, while Core's 4 threads reached 2.97×.
* The cost is data: 240 MiB moved for n = 1536. The coordinator spent 1.21 s building and encoding work units (base64
  inside JSON) and 0.54 s in transfers, against 2.40 s of kernel time. Utilization stayed below 0.5.
* So the postponed binary frames (implementation plan §3) and sending B once per worker instead of once per
  partition would pay off for data-heavy algorithms. Neither is implemented.

### 3.5 Load imbalance and scheduling policies

Prime counting up to 10¹⁰ with 12 partitions (4 per slot) on two normal workers and one worker slowed down 4×
(each slot one attempt at a time). The first row is the same task without the slow worker.

| Configuration | Cores | Wall s, median (min-max) | Speedup | Efficiency | Partitions | Kernel sum s | Transfer sum s | Encoding sum s | Scheduling ms | Moved MiB | Utilization | Imbalance | Attempts per worker |
|---|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|---|
| 2 normal workers only (DYNAMIC) | 2 | 4.991 (4.948-5.192) | 1.90 | 0.95 | 8 | 9.751 | 0.012 | 0.002 | 1.25 | 0.01 | 0.98 | 1.01 | w1 4, w2 4 |
| 2 normal + 1 slow (4x), ROUND_ROBIN | 3 | 6.610 (5.493-7.049) | 1.43 | 0.48 | 12 | 15.092 | 0.018 | 0.002 | 1.36 | 0.02 | 0.77 | 1.30 | slow1 2, w1 5, w2 5 |
| 2 normal + 1 slow (4x), LEAST_LOADED | 3 | 6.682 (6.292-7.152) | 1.42 | 0.47 | 12 | 15.364 | 0.018 | 0.002 | 1.47 | 0.02 | 0.77 | 1.30 | slow1 2, w1 5, w2 5 |
| 2 normal + 1 slow (4x), CAPABILITY_AWARE | 3 | 6.634 (5.204-7.111) | 1.43 | 0.48 | 12 | 15.152 | 0.022 | 0.002 | 2.16 | 0.02 | 0.80 | 1.25 | slow1 2, w1 5, w2 5 |
| 2 normal + 1 slow (4x), PERFORMANCE_AWARE | 3 | 7.002 (6.865-7.262) | 1.35 | 0.45 | 12 | 15.902 | 0.020 | 0.002 | 2.40 | 0.02 | 0.75 | 1.33 | slow1 2, w1 5, w2 5 |
| 2 normal + 1 slow (4x), DYNAMIC | 3 | 6.714 (4.925-6.885) | 1.41 | 0.47 | 12 | 15.018 | 0.017 | 0.002 | 1.86 | 0.02 | 0.76 | 1.31 | slow1 2, w1 5, w2 5 |

* **Adding the slow worker made every policy slower**: 6.6–7.0 s against 5.0 s with the two normal workers
  alone.
* Every policy produced the same split (slow worker 2 partitions, the others 5 each), because all policies choose
  among workers with a free slot *now* ([scheduling.md §3](scheduling.md#3-policies)). Timeline: a normal worker
  needs about 0.83 s per partition, the slow worker 3.3 s. At 3.3 s the normal workers have finished 8 partitions
  and take partitions 11 and 12. The slow worker finishes its first partition at the same moment and takes the
  last one, which ends at about 6.6 s. The normal workers are idle from about 4.2 s.
* `PERFORMANCE_AWARE` knows from its history that the slow worker is slow, but it cannot choose "nobody": when
  the slow worker is the only free one, it gets the partition.
* Two remedies are known and **not implemented**: holding a partition back when a busy, faster worker would
  finish it sooner, and *speculative backup attempts* (re-running a straggling partition on an idle worker and
  keeping the first result, which Core's duplicate handling already makes safe). This is the most important
  scheduling limitation the benchmarks found.
* The policies differed by less than the run-to-run noise (ranges overlap).

## 4. Summary

| Question | Answer on this machine |
|---|---|
| Does a compute-bound task scale with workers? | Yes: efficiency 0.91–0.97 up to 4 workers |
| What does the runtime cost? | About 5–20 ms per task on loopback, plus 0.1–0.4 ms of coordinator scheduling time per partition |
| When does distribution pay? | Prime counting: from about 10⁸ (≈ 0.1 s of sequential work). Matrix multiplication: only from about n = 1536, because of data volume |
| Is partition size critical? | Hardly: 1 to 16 partitions per slot were within 8 % of the best; 32 per slot cost 17 % |
| Do the policies handle a 4× slower worker? | No. All of them let it delay the end of the task (§3.5) |
| Should base64 JSON be replaced for data-heavy work? | The measurements say yes: encoding took half as long as computing for n = 1536 |

