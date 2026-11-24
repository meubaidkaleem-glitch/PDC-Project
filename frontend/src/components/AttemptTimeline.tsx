import type { LivePartition } from '../api/types';
import { formatDuration } from '../lib/format';

const COLORS: Record<string, string> = {
  SUCCEEDED: '#059669', RUNNING: '#2563eb', ACCEPTED: '#2563eb', DISPATCHED: '#60a5fa', DISPATCHING: '#93c5fd',
  FAILED: '#e11d48', TIMED_OUT: '#e11d48', LOST: '#e11d48', INVALID: '#e11d48', REJECTED: '#d97706',
  CANCELLED: '#94a3b8', SUPERSEDED: '#94a3b8', COMPLETED: '#059669',
};

/**
 * Assignment timeline: one row per worker, one bar per attempt from dispatch to its end (or now). Built from the
 * runtime's attempt records only.
 */
export function AttemptTimeline({ partitions, start, end }: { partitions: LivePartition[]; start: string | null; end: string | null }) {
  const attempts = partitions.flatMap((p) => p.attempts.filter((a) => a.dispatchedAt).map((a) => ({ ...a, partition: p.index })));
  if (attempts.length === 0 || !start) {
    return <p className="text-sm text-slate-500">No attempts to show (local runs are not split across workers).</p>;
  }
  const t0 = new Date(start).getTime();
  const now = Date.now();
  const time = (iso: string | null) => (iso ? new Date(iso).getTime() : now);
  const t1 = Math.max(end ? time(end) : now, ...attempts.map((a) => time(a.finishedAt)));
  const span = Math.max(1, t1 - t0);
  // Attempts that overlap on one worker ran in different slots: each worker gets as many lanes as it needed.
  const lanes: { worker: string; ends: number[] }[] = [];
  const placed = [...attempts].sort((a, b) => time(a.dispatchedAt) - time(b.dispatchedAt)).map((a) => {
    const worker = a.workerId ?? 'unassigned';
    let entry = lanes.find((l) => l.worker === worker);
    if (!entry) lanes.push(entry = { worker, ends: [] });
    let lane = entry.ends.findIndex((e) => e <= time(a.dispatchedAt));
    if (lane < 0) lane = entry.ends.push(0) - 1;
    entry.ends[lane] = time(a.finishedAt);
    return { ...a, worker, lane };
  });
  lanes.sort((a, b) => a.worker.localeCompare(b.worker));
  const rowOf = new Map<string, number>();
  let rowCount = 0;
  for (const l of lanes) {
    rowOf.set(l.worker, rowCount);
    rowCount += l.ends.length;
  }
  const rowHeight = 18;
  const labelWidth = 110;
  const width = 900;
  const plot = width - labelWidth - 10;
  const height = rowCount * rowHeight + 24;
  const x = (iso: string | null) => labelWidth + (time(iso) - t0) / span * plot;

  return (
    <div className="overflow-x-auto">
      <svg viewBox={`0 0 ${width} ${height}`} className="w-full min-w-[640px]" role="img"
        aria-label={`Assignment timeline of ${attempts.length} attempts on ${lanes.length} workers over ${formatDuration(span)}`}>
        {lanes.map((l) => {
          const top = rowOf.get(l.worker)! * rowHeight;
          const bottom = top + l.ends.length * rowHeight;
          return (
            <g key={l.worker}>
              <text x={0} y={top + 13} className="fill-slate-600 text-[11px] dark:fill-slate-300">{l.worker}</text>
              {l.ends.length > 1 && <text x={0} y={top + 25} className="fill-slate-400 text-[9px]">{l.ends.length} slots in use</text>}
              <line x1={labelWidth} x2={width - 10} y1={bottom} y2={bottom} className="stroke-slate-200 dark:stroke-slate-800" />
            </g>
          );
        })}
        {placed.map((a) => {
          const y = (rowOf.get(a.worker)! + a.lane) * rowHeight + 3;
          const x0 = x(a.dispatchedAt);
          const x1 = Math.max(x0 + 2, x(a.finishedAt));
          return (
            <rect key={a.attemptId ?? `${a.partition}-${a.dispatchedAt}`} x={x0} y={y} width={x1 - x0} height={rowHeight - 6} rx={2}
              fill={COLORS[a.status] ?? '#64748b'}>
              <title>{`${a.attemptId ?? `partition ${a.partition}`} on ${a.workerId}: ${a.status.toLowerCase()}${a.finishedAt ? `, ${formatDuration(new Date(a.finishedAt).getTime() - new Date(a.dispatchedAt!).getTime())}` : ' (running)'}${a.explanation ? `\n${a.explanation}` : ''}${a.failure ? `\n${a.failure}` : ''}`}</title>
            </rect>
          );
        })}
        <text x={labelWidth} y={height - 4} className="fill-slate-500 text-[10px]">0</text>
        <text x={width - 10} y={height - 4} textAnchor="end" className="fill-slate-500 text-[10px]">{formatDuration(span)}</text>
      </svg>
      <div className="mt-1 flex flex-wrap gap-3 text-xs text-slate-500">
        {[['succeeded', '#059669'], ['running', '#2563eb'], ['failed or lost', '#e11d48'], ['rejected', '#d97706'], ['cancelled or superseded', '#94a3b8']].map(([label, color]) => (
          <span key={label} className="flex items-center gap-1"><span className="inline-block h-2.5 w-2.5 rounded-sm" style={{ background: color }} />{label}</span>
        ))}
      </div>
    </div>
  );
}
