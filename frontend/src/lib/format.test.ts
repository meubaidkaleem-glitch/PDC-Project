import { describe, expect, it } from 'vitest';
import { formatAge, formatBytes, formatDuration, formatMetric, formatNumber } from './format';

describe('formatting', () => {
  it('formats durations in readable units', () => {
    expect(formatDuration(null)).toBe('—');
    expect(formatDuration(0.25)).toBe('0.250 ms');
    expect(formatDuration(7.5)).toBe('7.50 ms');
    expect(formatDuration(754)).toBe('754 ms');
    expect(formatDuration(2700)).toBe('2.70 s');
    expect(formatDuration(125_000)).toBe('2 min 5 s');
  });

  it('formats sizes in binary units', () => {
    expect(formatBytes(512)).toBe('512 B');
    expect(formatBytes(4 * 1024 * 1024)).toBe('4.0 MiB');
    expect(formatBytes(-1)).toBe('—');
  });

  it('uses exponents only for very large or small numbers', () => {
    expect(formatNumber(2.68e8)).toBe('2.68e+8');
    expect(formatNumber(1234.5678)).toBe('1,234.6');
    expect(formatNumber(Number.NaN)).toBe('—');
  });

  it('formats metrics by unit, and load imbalance as a factor', () => {
    expect(formatMetric(1114.1, 'ms')).toBe('1.11 s');
    expect(formatMetric(2_803_100, 'bytes')).toBe('2.7 MiB');
    expect(formatMetric(0.19146, 'ratio')).toBe('19.1 %');
    expect(formatMetric(1.0674, 'ratio', 'load_imbalance')).toBe('1.07×');
    expect(formatMetric(2.73e8, 'operations/s')).toBe('2.73e+8 operations/s');
    expect(formatMetric(8, 'count')).toBe('8');
    expect(formatMetric(null, 'ms')).toBe('—');
  });

  it('describes ages relative to now', () => {
    const now = Date.parse('2026-11-24T10:00:00Z');
    expect(formatAge('2026-11-24T09:59:50Z', now)).toBe('10 s ago');
    expect(formatAge('2026-11-24T09:00:00Z', now)).toBe('1 h ago');
    expect(formatAge(null, now)).toBe('never');
  });
});
