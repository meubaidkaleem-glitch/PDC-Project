# Examples

Sample contract documents for Contributors 2 and 3. `ExamplesTest` runs every task here in CI, so the files can't
silently drift from the code.

## Tasks (`tasks/`)

| File | Shows |
|---|---|
| `matmul-multithreaded.json` | `MULTITHREADED_CPU` with 2 threads and explicit 128×128 result blocks |
| `matmul-inline.json` | Inline operands (2×3 · 3×2) with an explicit `rowsB` for the inner-dimension check |
| `matmul-distributed.json` | `DISTRIBUTED_CPU`, driven by `PartitionedTaskSession` and `PartitionWorker` |
| `primes-count.json` | Range partitioning with `targetPartitions` |
| `sort-inline.json` | Sorting user-supplied values |
| `integrate-sin.json` | ∫₀^π sin x dx, verified against the closed form (2) |
| `statistics.json` | Descriptive statistics of 2·10⁶ normally distributed values |
| `invalid-dimensions.json` | A rejected task: `matrixB` does not match the declared 2×2 shape and `rowsB ≠ colsA` (`VALIDATION_FAILED` with `DIMENSION_MISMATCH`) |

## Outputs (`results/`)

These were produced by actually running the CLI in the development container. They are not hand-written.

| File | Command |
|---|---|
| `matmul-multithreaded.result.json` | `run examples/tasks/matmul-multithreaded.json` |
| `matmul-inline.result.json` | `run examples/tasks/matmul-inline.json` |
| `sort-inline.result.json` | `run examples/tasks/sort-inline.json` |
| `invalid-dimensions.result.json` | `run examples/tasks/invalid-dimensions.json` (a `FAILED` result with field-level violations) |
| `matmul-advice.json` | `advise examples/tasks/matmul-multithreaded.json` (strategy assessment on this node) |
| `catalog.json` | `catalog` (every algorithm with its parameter schema and derived capabilities) |

Timing fields in the results are real measurements from that one run. They illustrate the format and are not
benchmark results (see [benchmarks/](../benchmarks)).

## Running them

```bash
./mvnw -q test-compile exec:java -Dexec.args="run examples/tasks/matmul-multithreaded.json"
```

```bash
./mvnw -q test-compile exec:java -Dexec.args="advise examples/tasks/matmul-multithreaded.json"
```

```bash
./mvnw -q test-compile exec:java -Dexec.args="catalog"
```

Progress events go to standard error and the result JSON goes to standard output.
