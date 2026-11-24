/** Formatting helpers. Every value shown comes from the API; these only change its presentation. */

export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return '—';
  if (ms < 1) return `${ms.toFixed(3)} ms`;
  if (ms < 1000) return `${ms.toFixed(ms < 10 ? 2 : 0)} ms`;
  const s = ms / 1000;
  if (s < 60) return `${s.toFixed(s < 10 ? 2 : 1)} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m} min ${Math.round(s - m * 60)} s`;
  return `${Math.floor(m / 60)} h ${m % 60} min`;
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes === null || bytes === undefined || !Number.isFinite(bytes) || bytes < 0) return '—';
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(unit === 0 || value >= 100 ? 0 : 1)} ${units[unit]}`;
}

export function formatNumber(value: number | null | undefined, digits = 3): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  const abs = Math.abs(value);
  if (abs !== 0 && (abs >= 1e7 || abs < 1e-3)) return value.toExponential(2);
  return value.toLocaleString('en-US', { maximumSignificantDigits: Math.max(digits, 1) + 2 });
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString();
}

export function formatAge(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return 'never';
  const ms = now - new Date(iso).getTime();
  if (ms < 0) return 'just now';
  if (ms < 60_000) return `${Math.round(ms / 1000)} s ago`;
  if (ms < 3_600_000) return `${Math.round(ms / 60_000)} min ago`;
  if (ms < 86_400_000) return `${Math.round(ms / 3_600_000)} h ago`;
  return `${Math.round(ms / 86_400_000)} d ago`;
}

export function shortId(id: string): string {
  return id.length > 8 ? id.substring(0, 8) : id;
}

/**
 * Formats a metric value with its unit: durations and sizes in readable units, shares as percentages. Load imbalance
 * is a ratio of largest to mean (1 = balanced), so it is shown as a factor.
 */
export function formatMetric(value: number | null | undefined, unit: string | null | undefined, name?: string): string {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—';
  if (name === 'load_imbalance') return `${value.toFixed(2)}×`;
  switch (unit) {
    case 'ms': return formatDuration(value);
    case 'bytes': return formatBytes(value);
    case 'ratio': return `${(value * 100).toLocaleString('en-US', { maximumFractionDigits: 1 })} %`;
    case 'count': return value.toLocaleString('en-US', { maximumFractionDigits: 2 });
    case null: case undefined: case '': return formatNumber(value);
    default: return `${formatNumber(value)} ${unit}`;
  }
}
