import type { BatchItem, ExecutionMode, ExecutionSettings, TaskDraft } from '../api/types';
import { MODE_LABELS } from './labels';

/** Settings that only apply to distributed runs. */
const DISTRIBUTED_ONLY: (keyof ExecutionSettings)[] = ['schedulingPolicy', 'partitionsPerSlot', 'maxWorkers',
  'maxAttempts', 'attemptTimeoutSeconds'];

export interface Variation {
  mode: ExecutionMode;
  threads?: number;
}

/**
 * Parses "2, 4 8" into distinct thread counts between 1 and 1024, in order. Anything else is reported.
 */
export function parseThreads(text: string): { values: number[]; error?: string } {
  const parts = text.split(/[\s,;]+/).filter(Boolean);
  const values: number[] = [];
  for (const p of parts) {
    const n = Number(p);
    if (!Number.isInteger(n) || n < 1 || n > 1024) return { values: [], error: `"${p}" is not a thread count from 1 to 1024` };
    if (!values.includes(n)) values.push(n);
  }
  return { values };
}

/**
 * The experiments of a benchmark batch: the same task in each chosen mode (and thread count), with the same
 * repetitions. Settings that do not apply to a mode are left out, so each experiment is valid on its own.
 */
export function batchItems(base: TaskDraft, baseName: string, variations: Variation[], repetitions: number): BatchItem[] {
  return variations.map((v) => {
    const execution: ExecutionSettings = { ...base.execution, mode: v.mode, repetitions };
    if (v.mode === 'MULTITHREADED_CPU') execution.threads = v.threads ?? null;
    else delete execution.threads;
    if (v.mode !== 'DISTRIBUTED_CPU') DISTRIBUTED_ONLY.forEach((k) => delete execution[k]);
    if (v.mode === 'SEQUENTIAL_CPU') {
      delete execution.targetPartitions;
      delete execution.partitionSize;
      delete execution.blockRows;
      delete execution.blockCols;
    }
    const label = MODE_LABELS[v.mode] + (v.mode === 'MULTITHREADED_CPU' && v.threads ? `, ${v.threads} threads` : '');
    return { name: `${baseName} · ${label}`.substring(0, 200), task: { ...base, execution } };
  });
}
