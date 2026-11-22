# Benchmarks

Benchmark suites are JSON files in [`suites/`](suites). The `BenchmarkCli` runs them and writes a JSON report
(raw samples, statistics and the environment), a CSV file (for spreadsheets and plotting) and a Markdown report.
The method is described in [docs/performance-methodology.md](../docs/performance-methodology.md).

```bash
./mvnw -q test-compile exec:java -Dexec.args="benchmarks/suites/matmul-scaling.json target/benchmarks"
```

| Suite | Investigates |
|---|---|
| `quick-smoke.json` | Pipeline sanity check (seconds). Not for conclusions. |
| `matmul-scaling.json` | Strong scaling of matrix multiplication for 256³, 512³ and 1024³ with 1, 2 and 4 threads |
| `matmul-block-sizes.json` | Cache tile edge 8–768 for 768³, sequential and with 4 threads |
| `matmul-partitioning.json` | Partition count (4 to 256) and shape (row bands versus 2-D blocks) for 1024³ with 4 threads |
| `sort-scaling.json` | Merge sort of 10⁶ and 4·10⁶ integers, where the sequential k-way merge bounds the speedup |
| `primes-scaling.json` | Segmented sieve up to 10⁸ and 4·10⁸ with 1, 2 and 4 threads |

## Results policy

* **Only measured numbers.** Every committed result was produced by `BenchmarkCli` on the machine described in its
  report. Nothing is typed in by hand or extrapolated.
* **Environment first.** Each report records the CPU model, available processors, heap, JVM, OS and Core version.
  Don't compare results from different environments without naming the differences.
* **Correct runs only.** A run counts only if its result passed independent verification. A failed or unverified
  run aborts the benchmark.
* **CI does not benchmark.** GitHub-hosted runners are shared and noisy. CI runs the benchmark pipeline only as a
  correctness test (`@Tag("performance")`), and never asserts timings.
* Local scratch output belongs in `benchmarks/results/local/` (git-ignored). Commit curated runs in a directory
  named after the environment and date, with a README that explains the caveats.

## Committed results

| Directory | Environment | Suites |
|---|---|---|
| [`results/dev-container-2026-11-22`](results/dev-container-2026-11-22) | Shared cloud development container, 4 vCPU Intel Xeon @ 2.10 GHz, OpenJDK 21 | `matmul-scaling`, `sort-scaling`, `primes-scaling` |
