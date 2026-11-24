/** Types of the backend API (docs/api.md). They mirror the backend's records; keep them in sync. */

export type Role = 'ADMIN' | 'USER';
export type ExecutionMode = 'SEQUENTIAL_CPU' | 'MULTITHREADED_CPU' | 'DISTRIBUTED_CPU' | 'GPU' | 'HYBRID';
export type ExperimentStatus = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface Session {
  authenticated: boolean;
  id: number | null;
  username: string | null;
  role: Role | null;
}

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface Category {
  id: string;
  name: string;
  description: string;
  algorithmCount: number;
  available: boolean;
}

export interface ParameterSchema {
  name: string;
  type: 'INTEGER' | 'DECIMAL' | 'BOOLEAN' | 'ENUM' | 'INTEGER_ARRAY' | 'DECIMAL_ARRAY';
  description: string;
  required: boolean;
  defaultValue: unknown;
  minimum: number | null;
  maximum: number | null;
  allowedValues: string[];
  minLength: number | null;
  maxLength: number | null;
  unit: string | null;
  uploadable: boolean;
}

export interface ModeInfo {
  mode: ExecutionMode;
  supportedByAlgorithm: boolean;
  executable: boolean;
  reason: string | null;
}

export interface Algorithm {
  id: string;
  version: string;
  displayName: string;
  description: string;
  category: string;
  coreCategory: string;
  builtIn: boolean;
  parameters: ParameterSchema[];
  resultType: string;
  verificationMethod: string;
  limitations: string[];
  partitionStrategy: string;
  distributable: boolean;
  modes: ModeInfo[];
  inputParameters: string[];
}

export interface ExecutionSettings {
  mode: ExecutionMode;
  threads?: number | null;
  targetPartitions?: number | null;
  partitionSize?: number | null;
  blockRows?: number | null;
  blockCols?: number | null;
  partitionsPerSlot?: number | null;
  schedulingPolicy?: string | null;
  maxWorkers?: number | null;
  maxAttempts?: number | null;
  attemptTimeoutSeconds?: number | null;
  timeoutSeconds?: number | null;
  maxMemoryMb?: number | null;
  priority?: 'LOW' | 'NORMAL' | 'HIGH' | null;
  repetitions?: number | null;
}

export interface TaskDraft {
  algorithm: string;
  parameters: Record<string, unknown>;
  inputs: Record<string, string>;
  execution: ExecutionSettings;
}

export interface InputUpload {
  id: string;
  algorithm: string;
  parameter: string;
  elementType: 'INTEGER' | 'DECIMAL';
  count: number;
  rows: number;
  columns: number;
  fileName: string;
  bytes: number;
  sha256: string;
  uploadedAt: string;
}

export interface Workload {
  dimensions: Record<string, number>;
  estimatedOperations: number;
  operationUnit: string;
  timeComplexity: string;
  inputBytes: number;
  outputBytes: number;
  estimatedPeakMemoryBytes: number;
  partitionable: boolean;
  partitionStrategy: string | null;
  maxUsefulPartitions: number;
  dataDependency: string | null;
  aggregation: string | null;
}

export interface Measured {
  runs: number;
  medianSeconds: number;
  minSeconds: number;
  maxSeconds: number;
}

export interface ModeAdvice {
  mode: ExecutionMode;
  verdict: string;
  executable: boolean;
  threads: number | null;
  estimatedSeconds: number | null;
  estimateBasis: 'MEASURED' | 'HISTORICAL' | 'THEORETICAL' | 'NONE';
  measured: Measured | null;
  reasons: string[];
}

export interface Advice {
  createdAt: string;
  algorithm: string;
  workload: Workload;
  recommendedMode: ExecutionMode | null;
  reliable: boolean;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW' | 'NONE';
  basis: string;
  explanation: string;
  modes: ModeAdvice[];
  resources: {
    localCores: number;
    eligibleWorkers: string[];
    ineligibleWorkers: { workerId: string; reason: string }[];
    totalSlots: number;
    busySlots: number;
    pendingPartitions: number;
    gpuAvailable: boolean;
    gpuStatus: string;
    networkMeasured: boolean;
    transferBytesPerSecond: number | null;
    latencyMillis: number | null;
    estimatedTransferBytes: number;
    estimatedCommunicationSeconds: number | null;
  };
  assumptions: string[];
  warnings: string[];
}

export interface ExperimentSummary {
  id: string;
  name: string;
  owner: string;
  category: string;
  algorithm: string;
  algorithmVersion: string;
  executionMode: ExecutionMode;
  recommendedMode: ExecutionMode | null;
  recommendationAccepted: boolean | null;
  schedulingPolicy: string | null;
  status: ExperimentStatus;
  repetitions: number;
  completedRuns: number;
  workersUsed: number | null;
  medianWallTimeMs: number | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  durationMs: number | null;
  errorMessage: string | null;
  batchId: string | null;
}

export interface RunView {
  id: number;
  runIndex: number;
  coreTaskId: string;
  executionMode: ExecutionMode;
  status: string;
  threads: number | null;
  partitions: number | null;
  workersUsed: number | null;
  submittedAt: string | null;
  startedAt: string | null;
  completedAt: string | null;
  wallTimeMs: number | null;
  resultDigest: string | null;
  verificationPassed: boolean | null;
  verificationMethod: string | null;
  errorCode: string | null;
  errorMessage: string | null;
}

export interface ResultView {
  runIndex: number;
  resultType: string;
  summary: Record<string, unknown>;
  digest: string;
  validationStatus: 'VERIFIED' | 'VERIFICATION_FAILED' | 'NOT_VERIFIED';
  validationMethod: string | null;
  validationMessage: string | null;
  stored: boolean;
  format: 'CSV' | 'JSON' | null;
  sizeBytes: number | null;
  storedDigestMatch: boolean | null;
  storageNote: string | null;
}

export interface MetricStats {
  name: string;
  unit: string;
  source: string;
  count: number;
  median: number;
  mean: number;
  min: number;
  max: number;
  stddev: number;
}

export interface ExperimentDetail {
  summary: ExperimentSummary;
  description: string | null;
  parameters: Record<string, unknown>;
  inputSource: string;
  inputSummary: Record<string, unknown>;
  configuration: Record<string, unknown>;
  adviceBasis: string | null;
  overrideReason: string | null;
  errorCode: string | null;
  runs: RunView[];
  results: ResultView[];
  metrics: MetricStats[];
}

export interface LiveAttempt {
  attemptId: string | null;
  workerId: string | null;
  status: string;
  dispatchedAt: string | null;
  acceptedAt: string | null;
  finishedAt: string | null;
  failure: string | null;
  explanation: string | null;
  transferMs: number;
  computeMs: number;
  bytes: number;
}

export interface LivePartition {
  index: number;
  partitionId: string;
  status: string;
  attempts: LiveAttempt[];
}

export interface LiveRun {
  experimentId: string;
  experimentStatus: ExperimentStatus;
  runIndex: number;
  coreTaskId: string;
  available: boolean;
  status: string;
  mode: ExecutionMode;
  algorithm: string;
  policy: string | null;
  submittedAt: string | null;
  finishedAt: string | null;
  totalPartitions: number;
  completedPartitions: number;
  failedPartitions: number;
  pendingPartitions: number;
  runningPartitions: number;
  attempts: number;
  retries: number;
  activeWorkers: string[];
  partitions: LivePartition[];
  metrics: Record<string, unknown>;
  errors: string[];
}

export interface EventView {
  id: number | null;
  experimentId: string | null;
  runId: number | null;
  taskRef: string | null;
  workerId: string | null;
  source: 'RUNTIME' | 'APPLICATION';
  type: string;
  severity: 'INFO' | 'WARN' | 'ERROR';
  message: string;
  details: Record<string, unknown>;
  simulated: boolean;
  occurredAt: string;
}

export interface Worker {
  workerId: string;
  hostname: string | null;
  endpoint: string | null;
  status: string;
  statusReason: string | null;
  connected: boolean;
  approved: boolean;
  deactivated: boolean;
  cpuCores: number | null;
  maxHeapBytes: number | null;
  physicalMemoryBytes: number | null;
  os: string | null;
  javaVersion: string | null;
  runtimeVersion: string | null;
  coreVersion: string | null;
  gpuAvailable: boolean;
  gpuDetails: string[];
  algorithms: string[];
  labels: Record<string, string>;
  faultSimulation: boolean;
  slots: number | null;
  assignedTasks: number | null;
  activeTasks: number | null;
  freeMemoryBytes: number | null;
  processCpuLoad: number | null;
  systemLoadAverage: number | null;
  completedTasks: number;
  failedTasks: number;
  counters: Record<string, number>;
  performance: { algorithm: string; samples: number; operationsPerSecond: number; transferBytesPerSecond: number;
    meanKernelMillis: number }[];
  lastHeartbeatAt: string | null;
  registeredAt: string | null;
  lastSeenAt: string | null;
  notes: string | null;
}

export interface WorkerRegistration {
  workerId: string;
  coordinatorHost: string;
  coordinatorPort: number;
  tls: boolean;
  approvalRequired: boolean;
  commandLine: string;
  configFile: string;
  secretInstructions: string;
}

export interface ConnectionTest {
  workerId: string;
  coordinatorListening: boolean;
  connected: boolean;
  status: string;
  heartbeatAgeMs: number | null;
  heartbeatIntervalMs: number;
  verdict: 'OK' | 'LATE' | 'DISCONNECTED' | 'NEVER_CONNECTED';
  explanation: string;
}

export interface Dashboard {
  scope: string;
  experiments: { total: number; byStatus: Record<ExperimentStatus, number> };
  workers: { byStatus: Record<string, number>; connected: number; totalSlots: number; busySlots: number };
  resources: { localCores: number; workerSlots: number; gpuAvailable: boolean };
  recentExperiments: ExperimentSummary[];
  recentEvents: EventView[];
  performance: { mode: ExecutionMode; completedRuns: number; medianWallTimeMs: number }[];
  health: { database: string; coordinator: string; eventsDropped: number };
}

export interface SystemInfo {
  application: string;
  core: string;
  distributed: string;
  protocol: string;
}

// Comparison and performance analysis (docs/analysis.md)

export interface Stats {
  count: number;
  median: number;
  mean: number;
  min: number;
  max: number;
  stddev: number;
  cv: number | null;
}

export interface RunPoint {
  runIndex: number;
  wallTimeMs: number;
  resultDigest: string | null;
}

export interface ComparedExperiment {
  id: string;
  name: string;
  owner: string;
  algorithm: string;
  algorithmVersion: string;
  mode: ExecutionMode;
  status: ExperimentStatus;
  inputSource: string;
  dimensions: Record<string, number>;
  estimatedOperations: number | null;
  operationUnit: string | null;
  parameters: Record<string, unknown>;
  repetitions: number;
  completedRuns: number;
  threads: number | null;
  workers: number | null;
  partitions: number | null;
  schedulingPolicy: string | null;
  wallTime: Stats | null;
  executionTime: Stats | null;
  waitingTime: Stats | null;
  runs: RunPoint[];
  speedup: number | null;
  speedupRange: [number, number] | null;
  efficiency: number | null;
  efficiencyBasis: 'thread' | 'worker' | null;
  breakdown: Record<string, number>;
  indicators: Record<string, number>;
  resultDigest: string | null;
  resultsConsistent: boolean;
}

export type ComparisonBasis = 'EXECUTION_TIME' | 'WALL_TIME';

export interface ComparisonIssue {
  severity: 'ERROR' | 'WARNING' | 'INFO';
  code: string;
  message: string;
  experimentIds: string[];
}

export interface Comparison {
  computedAt: string;
  comparable: boolean;
  baselineId: string | null;
  baselineReason: string;
  basis: ComparisonBasis;
  basisDescription: string;
  experiments: ComparedExperiment[];
  issues: ComparisonIssue[];
  summary: string[];
}

export interface WorkloadGroup {
  key: string;
  algorithm: string;
  algorithmVersion: string;
  dimensions: Record<string, number>;
  experiments: number;
  modes: Record<string, number>;
  lastCompletedAt: string | null;
  experimentIds: string[];
}
