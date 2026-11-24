import { describe, expect, it } from 'vitest';
import type { TaskDraft } from '../api/types';
import { batchItems, parseThreads } from './batch';

const base: TaskDraft = {
  algorithm: 'matrix.multiply', parameters: { rowsA: 256 }, inputs: { matrixA: 'upload-1' },
  execution: { mode: 'DISTRIBUTED_CPU', threads: 8, targetPartitions: 16, schedulingPolicy: 'DYNAMIC', maxAttempts: 3, repetitions: 1 },
};

describe('batch builder', () => {
  it('parses distinct thread counts and rejects anything else', () => {
    expect(parseThreads('2, 4 4;8')).toEqual({ values: [2, 4, 8] });
    expect(parseThreads('2, x').error).toMatch(/"x"/);
    expect(parseThreads('0').error).toBeDefined();
  });

  it('builds one experiment per mode and thread count, keeping only the settings that apply', () => {
    const items = batchItems(base, 'MM 256', [{ mode: 'SEQUENTIAL_CPU' }, { mode: 'MULTITHREADED_CPU', threads: 4 },
      { mode: 'DISTRIBUTED_CPU' }], 3);
    expect(items.map((i) => i.name)).toEqual(['MM 256 · Sequential CPU', 'MM 256 · Parallel CPU (threads), 4 threads',
      'MM 256 · Distributed CPU (workers)']);
    expect(items[0].task.execution).toEqual({ mode: 'SEQUENTIAL_CPU', repetitions: 3 });
    expect(items[1].task.execution).toEqual({ mode: 'MULTITHREADED_CPU', threads: 4, targetPartitions: 16, repetitions: 3 });
    expect(items[2].task.execution).toEqual({ mode: 'DISTRIBUTED_CPU', targetPartitions: 16, schedulingPolicy: 'DYNAMIC', maxAttempts: 3, repetitions: 3 });
    for (const i of items) {
      expect(i.task.parameters).toEqual(base.parameters);
      expect(i.task.inputs).toEqual(base.inputs);
    }
  });
});
