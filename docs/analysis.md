# Comparison and performance analysis

How the application compares experiments (`POST /api/v1/analysis/compare`, the Performance analysis page). Every
figure is computed from stored measurements of **completed** runs ([metrics.md](metrics.md)); nothing is estimated
or filled in.

## 1. What is compared

A comparison takes 2 to 20 experiments the user may see (owners see their own, administrators all). Experiments
without a completed run are listed with a warning and left out of every computation.

### Basis

Speedups, variation and the findings use one of two times, the **basis**:

| Basis | Covers | Use |
|---|---|---|
| `EXECUTION_TIME` (default) | Core's execution time: validation, input preparation, partitioning, computation on threads or workers (the distributed phase included), aggregation and verification | Comparing execution strategies |
| `WALL_TIME` | The application's wall time: from submission to the stored result, including the wait before the run starts | What the user experienced |

The default is `EXECUTION_TIME` when every compared experiment reports it, else `WALL_TIME` (with a
`BASIS_UNAVAILABLE` warning if `EXECUTION_TIME` was asked for).

Why the default is not wall time: the coordinator runs **local** tasks (sequential and multithreaded) one after
another on a single thread (`LocalTaskRunner` in DistriCompute-Distributed), and a run's result value is stored
before the run completes. When several experiments are submitted together, their runs queue behind each other, and
the wall time of a run includes that wait. This was observed on a 4-core machine with three 768 × 768 matrix
multiplications submitted together: Core's execution time showed 316 ms sequentially and 155 ms on four threads (2.04×),
while the wall times were 891 ms and 900 ms, about 600 ms of each being the wait. Compared by wall time, the
parallel run looked no faster. A wall-time comparison therefore warns (`WAITING_INCLUDED`) when the wait exceeds 10 %
of an experiment's median wall time.

### Statistics

For each experiment and for wall time, execution time and waiting time (submission to the start of execution):
count, median, mean, minimum, maximum, sample standard deviation and coefficient of variation (standard deviation /
mean; none for a single run, whose variation is unknown rather than zero).

### Baseline, speedup and efficiency

- **Baseline**: the experiment chosen by the user; otherwise the sequential experiment with the most completed runs;
  otherwise the first experiment with completed runs. The answer says which rule applied.
- **Speedup** = baseline median ÷ experiment median, on the basis. The **range** divides the baseline median by the
  experiment's slowest and fastest run.
- **Efficiency** = speedup ÷ processing units, computed only against a **sequential** baseline:
  - multithreaded runs: per thread (the median threads Core used);
  - distributed runs: per worker (the median workers used). A worker can run several partitions at once on several
    cores, so a distributed efficiency above 100 % is possible; it is not a per-core figure.
- Speedups and efficiencies are not computed when the comparison has an `ERROR` issue.

### Breakdown

The median milliseconds of each phase, in order: waiting to start; Core's validation and input preparation,
partitioning, computation, aggregation and verification; and the rest of the wall time ("other": storing the result
value, scheduling and transfer not inside Core's phases, the completion path). Phases a run does not report count as
zero.

### Further measurements

Medians of throughput (Core's estimated operations per second of execution time), CPU utilization (process-wide, see
[metrics.md](metrics.md)), and for distributed runs communication share, worker utilization, load imbalance,
transfer time, bytes sent and received, attempts, retries and failed attempts.

## 2. Compatibility checks

| Code | Severity | When |
|---|---|---|
| `ALGORITHM_DIFFERS` | ERROR | Different algorithms |
| `DIMENSIONS_DIFFER` | ERROR | Same algorithm, different dimensions (Core's workload dimensions at submission) |
| `BASELINE_WITHOUT_RUNS` | ERROR | The baseline has no completed run |
| `VERSION_DIFFERS` | WARNING | Different algorithm versions |
| `INPUT_DIFFERS` | WARNING | Different input source or uploaded data with the same dimensions |
| `PARAMETERS_DIFFER` | WARNING | Other parameters differ (for example the block size or the seed); listed by name |
| `NO_COMPLETED_RUNS` | WARNING | An experiment has no completed run |
| `SINGLE_RUN` | WARNING | An experiment has one completed run |
| `HIGH_VARIATION` | WARNING | Coefficient of variation above 10 % |
| `INCONSISTENT_RESULTS` | WARNING | The runs of one experiment produced different verified results |
| `RESULTS_DIFFER` | WARNING | Verified results are not bit-identical across experiments (floating-point rounding when work is split differently) |
| `CONCURRENT_RUNS` | WARNING | Compared runs executed while other runs (of any experiment) were executing, so they shared processors or workers |
| `WAITING_INCLUDED` | WARNING | Wall-time basis and waiting above 10 % of an experiment's wall time |
| `BASIS_UNAVAILABLE` | WARNING | Execution time asked for but not reported by every experiment |
| `RESULTS_IDENTICAL` | INFO | Every compared run produced the same verified result |
| `SMALL_WORKLOAD` | INFO | The baseline takes under 50 ms: fixed costs dominate |
| `EFFICIENCY_NEEDS_SEQUENTIAL` | INFO | The baseline is not sequential, so no efficiency is computed |
| `UNCONTROLLED_LOAD` | INFO | Always, when comparable: other load at measurement time is not controlled |

"Same workload" means same algorithm and dimensions. Parameters that change only the implementation (a block
size) or the data (a seed) are reported as warnings, because they are part of the measured difference; the result
digests show whether the work was identical.

## 3. Workload groups

`GET /api/v1/analysis/workloads` groups the user's finished experiments that have completed runs by algorithm,
version and dimensions (newest 1,000 experiments scanned), with the number of experiments per mode, so that a whole
group can be compared at once.

## 4. Limits

- Measurements are taken when experiments run, on shared machines; the application does not reserve processors or
  workers for a benchmark. `CONCURRENT_RUNS` and `UNCONTROLLED_LOAD` say so; repetitions and running experiments
  one after another make comparisons more reliable.
- Efficiency counts threads or workers, not cores, and does not account for hyper-threading.
- Distributed runs on workers that share a machine with the application (as in a local trial) compete with it for
  the same processors.
