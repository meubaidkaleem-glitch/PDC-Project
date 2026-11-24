import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { LivePartition } from '../api/types';
import { AttemptTimeline } from './AttemptTimeline';

const attempt = (partition: number, worker: string, from: number, to: number, status = 'SUCCEEDED') => ({
  index: partition, status, attempts: [{
    attemptId: `t/p${partition}/a1`, workerId: worker, status, dispatchedAt: new Date(Date.UTC(2026, 10, 24, 7, 0, from)).toISOString(),
    finishedAt: new Date(Date.UTC(2026, 10, 24, 7, 0, to)).toISOString(), explanation: null, failure: null,
  }],
}) as unknown as LivePartition;

describe('AttemptTimeline', () => {
  it('gives overlapping attempts on one worker separate lanes', () => {
    const partitions = [attempt(0, 'w1', 0, 4), attempt(1, 'w1', 1, 3), attempt(2, 'w1', 5, 6), attempt(3, 'w2', 0, 2, 'FAILED')];
    const { container } = render(<AttemptTimeline partitions={partitions} start="2026-11-24T07:00:00Z" end="2026-11-24T07:00:06Z" />);
    expect(screen.getByRole('img', { name: 'Assignment timeline of 4 attempts on 2 workers over 6.00 s' })).toBeInTheDocument();
    expect(screen.getByText('2 slots in use')).toBeInTheDocument();
    const rows = [...container.querySelectorAll('rect')].map((r) => r.getAttribute('y'));
    expect(new Set(rows).size).toBe(3);
  });

  it('explains why local runs have no timeline', () => {
    render(<AttemptTimeline partitions={[]} start={null} end={null} />);
    expect(screen.getByText(/local runs are not split across workers/)).toBeInTheDocument();
  });
});
