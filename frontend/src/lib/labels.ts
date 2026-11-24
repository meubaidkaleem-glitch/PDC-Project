import type { ExecutionMode } from '../api/types';

export const MODE_LABELS: Record<ExecutionMode, string> = {
  SEQUENTIAL_CPU: 'Sequential CPU',
  MULTITHREADED_CPU: 'Parallel CPU (threads)',
  DISTRIBUTED_CPU: 'Distributed CPU (workers)',
  GPU: 'GPU',
  HYBRID: 'Hybrid',
};

export const MODE_SHORT: Record<ExecutionMode, string> = {
  SEQUENTIAL_CPU: 'Sequential',
  MULTITHREADED_CPU: 'Parallel',
  DISTRIBUTED_CPU: 'Distributed',
  GPU: 'GPU',
  HYBRID: 'Hybrid',
};

export function modeLabel(mode: string | null | undefined): string {
  return mode ? (MODE_LABELS[mode as ExecutionMode] ?? mode) : '—';
}

export const POLICIES: { id: string; label: string; description: string }[] = [
  { id: 'DYNAMIC', label: 'Dynamic', description: 'The worker that has waited longest gets the next partition.' },
  { id: 'LEAST_LOADED', label: 'Least loaded', description: 'The worker with the lowest share of busy slots.' },
  { id: 'ROUND_ROBIN', label: 'Round robin', description: 'Workers in turn.' },
  { id: 'CAPABILITY_AWARE', label: 'Capability-aware', description: 'The most idle cores and memory headroom.' },
  { id: 'PERFORMANCE_AWARE', label: 'Performance-aware', description: 'The shortest time estimated from measured throughput.' },
];

export const METRIC_LABELS: Record<string, string> = {
  wall_time: 'Wall time',
  validation_time: 'Validation time',
  input_preparation_time: 'Input preparation time',
  partitioning_time: 'Partitioning time',
  compute_time: 'Compute time',
  aggregation_time: 'Aggregation time',
  verification_time: 'Verification time',
  execution_time: 'Execution time (Core)',
  busy_time: 'Thread busy time (sum)',
  scheduling_overhead: 'Scheduling overhead (Core)',
  threads: 'Threads',
  partitions: 'Partitions',
  cpu_time: 'CPU time',
  gc_count: 'Garbage collections',
  gc_time: 'Garbage collection time',
  allocated_memory: 'Allocated memory',
  estimated_operations: 'Estimated operations',
  estimated_peak_memory: 'Estimated peak memory',
  runtime_total_time: 'Runtime total time',
  queue_time: 'Queue time',
  planning_time: 'Planning time',
  distributed_time: 'Distributed phase',
  runtime_aggregation_time: 'Runtime aggregation time',
  kernel_time_sum: 'Kernel time (sum)',
  worker_execute_time_sum: 'Worker execution time (sum)',
  transfer_time_sum: 'Transfer time (sum)',
  serialization_time_sum: 'Encoding time (sum)',
  result_processing_time_sum: 'Result processing time (sum)',
  scheduling_time: 'Scheduling time',
  bytes_sent: 'Bytes sent',
  bytes_received: 'Bytes received',
  attempts: 'Attempts',
  failed_attempts: 'Failed attempts',
  retries: 'Retries',
  workers_used: 'Workers used',
  worker_utilization: 'Worker utilization',
  throughput_partitions: 'Partition throughput',
  load_imbalance: 'Load imbalance',
  communication_ratio: 'Communication share',
  throughput_operations: 'Throughput',
  cpu_utilization: 'CPU utilization',
};

export function metricLabel(name: string): string {
  return METRIC_LABELS[name] ?? name.replace(/_/g, ' ');
}

/** Names of the execution settings stored with an experiment. */
export const SETTING_LABELS: Record<string, string> = {
  mode: 'Execution mode',
  threads: 'Threads',
  targetPartitions: 'Partitions',
  partitionSize: 'Partition size',
  blockRows: 'Block rows',
  blockCols: 'Block columns',
  partitionsPerSlot: 'Partitions per worker slot',
  schedulingPolicy: 'Scheduling policy',
  maxWorkers: 'Maximum workers',
  maxAttempts: 'Attempts per partition',
  attemptTimeoutSeconds: 'Attempt timeout (s)',
  timeoutSeconds: 'Run timeout (s)',
  maxMemoryMb: 'Memory cap (MiB)',
  priority: 'Priority',
  repetitions: 'Repetitions',
};
