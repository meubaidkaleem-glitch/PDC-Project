# Metrics

Every finished run stores its measurements in `run_metrics` with a name, a unit and a **source**. Pages, comparisons
and reports use these names and definitions only.

| Source | Meaning |
|---|---|
| `APPLICATION` | Measured by the application |
| `CORE` | Reported by DistriCompute-Core (`ExecutionMetrics`, `WorkloadProfile`) |
| `RUNTIME` | Reported by DistriCompute-Distributed (`DistributedMetrics`), distributed runs only |
| `DERIVED` | Computed by the application from measured values |

## 1. Per run

| Name | Unit | Source | Definition |
|---|---|---|---|
| `wall_time` | ms | APPLICATION | From the submission call to the completion of the run, as the user experiences it. Includes validation, **waiting for other runs** (local runs of the coordinator execute one after another), execution, aggregation, verification and **storing the result value**. Not a fair basis for speedups when runs queued; see [analysis.md](analysis.md) |
| `validation_time`, `input_preparation_time`, `partitioning_time`, `compute_time`, `aggregation_time`, `execution_time`, `verification_time` | ms | CORE | Core's phases of the run |
| `busy_time` | ms | CORE | Sum of the time threads were busy |
| `scheduling_overhead` | ms | CORE | Core's estimate of time lost to scheduling (compute time minus busy time per thread) |
| `threads`, `partitions` | count | CORE | Threads and partitions Core used |
| `cpu_time`, `gc_count`, `gc_time`, `allocated_memory` | ms, count, ms, bytes | CORE | Process CPU time, garbage collections and allocated bytes, where the JVM reports them. They cover the whole application process during the run (just-in-time compilation, garbage collection, the web server, storing an earlier result), not only the run's threads |
| `estimated_operations`, `estimated_peak_memory` | operations, bytes | CORE | Core's workload analysis |
| `runtime_total_time`, `queue_time`, `planning_time`, `distributed_time`, `runtime_aggregation_time` | ms | RUNTIME | The coordinator's wall-time phases |
| `kernel_time_sum`, `worker_execute_time_sum`, `transfer_time_sum`, `serialization_time_sum`, `result_processing_time_sum` | ms | RUNTIME | Sums over attempts (they overlap in wall time) |
| `scheduling_time` | ms | RUNTIME | Coordinator time spent choosing workers |
| `bytes_sent`, `bytes_received` | bytes | RUNTIME | Work units and results on the wire |
| `attempts`, `failed_attempts`, `retries`, `workers_used` | count | RUNTIME | Attempt accounting |
| `worker_utilization` | ratio | RUNTIME | Worker execute time / (distributed time × slots) |
| `throughput_partitions` | partitions/s | RUNTIME | Partitions per second of distributed time |
| `load_imbalance` | ratio | RUNTIME | Largest per-worker kernel time / mean (1 = balanced) |
| `communication_ratio` | ratio | DERIVED | Transfer sum / (kernel sum + transfer sum) |
| `throughput_operations` | operations/s | DERIVED | Core's estimated operations / Core's execution time (wall time if Core reports none) |
| `cpu_utilization` | ratio | DERIVED | CPU time / (execution time × threads), local runs only. Because CPU time is process-wide, values above 100 % occur when other work ran in the process at the same time |

## 2. Per experiment

`GET /api/v1/experiments/{id}` shows, for every metric and source, the count, median, mean, minimum, maximum and
sample standard deviation over the **completed** runs. With one run the standard deviation is 0, and the user
interface says that one run is not enough to judge variation.

Speedup and efficiency are computed when experiments are compared, because they need a baseline
([analysis.md](analysis.md)).
