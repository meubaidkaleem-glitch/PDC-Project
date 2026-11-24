import { useNavigate } from 'react-router';
import { useApi, useEventStream, useThrottled } from '../api/hooks';
import type { Worker } from '../api/types';
import { Badge, Card, EmptyState, ErrorState, PageHeader, Spinner } from '../components/ui';
import { formatBytes } from '../lib/format';

const EVENTS = ['WORKER_REGISTERED', 'WORKER_STATUS_CHANGED', 'WORKER_DISCONNECTED', 'WORKER_REMOVED', 'ATTEMPT_ASSIGNED',
  'ATTEMPT_SUCCEEDED', 'ATTEMPT_FAILED', 'TASK_FINISHED'];

const STATUS_COLOR: Record<string, string> = {
  ACTIVE: '#059669', SUSPECT: '#d97706', DRAINING: '#d97706', DEACTIVATED: '#d97706', QUARANTINED: '#e11d48',
  DISCONNECTED: '#94a3b8', NEVER_CONNECTED: '#cbd5e1',
};

interface Status {
  coordinator: { id: string; workerPort: number; advertisedHost: string; tls: boolean; schedulingPolicy: string };
  workers: { totalSlots: number; busySlots: number };
}

/** The coordinator and its workers, drawn from the live registry; edges carry the current assignments. */
export function TopologyPage() {
  const navigate = useNavigate();
  const workers = useApi<Worker[]>('/api/v1/workers');
  const status = useApi<Status>('/api/v1/system/status');
  const refresh = useThrottled(() => { workers.reload(); status.reload(); }, 800);
  const stream = useEventStream('/api/v1/events/stream', EVENTS, refresh);

  if (workers.loading && !workers.data) return <Spinner label="Loading the topology" />;
  if (workers.error) return <ErrorState error={workers.error} onRetry={workers.reload} />;
  const list = (workers.data ?? []).filter((w) => w.status !== 'NEVER_CONNECTED');
  const size = 640;
  const c = size / 2;
  const radius = list.length <= 1 ? 170 : 220;

  return (
    <>
      <PageHeader title="Cluster topology" description="The application's coordinator and every worker it knows. Line width shows attempts running on a worker now; grey workers are disconnected."
        actions={<Badge tone={stream === 'open' ? 'green' : 'amber'}>{stream === 'open' ? 'live' : stream}</Badge>} />
      <Card>
        {list.length === 0 ? (
          <EmptyState title="Only the coordinator is running">No worker has connected. Sequential and parallel runs still work on the application server.</EmptyState>
        ) : (
          <svg viewBox={`0 0 ${size} ${size}`} className="mx-auto w-full max-w-2xl" role="img" aria-label={`Coordinator with ${list.length} workers`}>
            {list.map((w, i) => {
              const angle = (2 * Math.PI * i) / list.length - Math.PI / 2;
              const x = c + radius * Math.cos(angle);
              const y = c + radius * Math.sin(angle);
              const busy = w.connected ? (w.assignedTasks ?? 0) : 0;
              return (
                <g key={w.workerId} className="cursor-pointer" onClick={() => navigate(`/workers?selected=${w.workerId}`)}>
                  <line x1={c} y1={c} x2={x} y2={y} stroke={w.connected ? STATUS_COLOR[w.status] ?? '#64748b' : '#cbd5e1'}
                    strokeWidth={1.5 + busy * 2} strokeDasharray={w.connected ? (busy > 0 ? '6 4' : undefined) : '2 6'}
                    className={busy > 0 ? 'animate-[dash_1s_linear_infinite]' : undefined} />
                  <circle cx={x} cy={y} r={34} fill="white" stroke={STATUS_COLOR[w.status] ?? '#64748b'} strokeWidth={3} className="dark:fill-slate-900" />
                  <text x={x} y={y - 4} textAnchor="middle" className="fill-slate-800 text-[11px] font-medium dark:fill-slate-100">{w.workerId.length > 12 ? `${w.workerId.slice(0, 11)}…` : w.workerId}</text>
                  <text x={x} y={y + 10} textAnchor="middle" className="fill-slate-500 text-[10px]">{w.connected ? `${busy}/${w.slots ?? '?'} slots` : w.status.toLowerCase()}</text>
                  <title>{`${w.workerId} (${w.status.toLowerCase()})\n${w.cpuCores ?? '?'} cores, heap ${formatBytes(w.maxHeapBytes)}\n${busy} attempts running, ${w.completedTasks} completed, ${w.failedTasks} failed`}</title>
                </g>
              );
            })}
            <circle cx={c} cy={c} r={52} className="fill-blue-700 dark:fill-blue-600" />
            <text x={c} y={c - 4} textAnchor="middle" className="fill-white text-[12px] font-semibold">Coordinator</text>
            <text x={c} y={c + 12} textAnchor="middle" className="fill-blue-100 text-[10px]">
              {status.data ? `${status.data.workers.busySlots}/${status.data.workers.totalSlots} slots busy` : ''}
            </text>
          </svg>
        )}
        {status.data && (
          <p className="mt-3 text-center text-xs text-slate-500">
            Worker port {status.data.coordinator.advertisedHost}:{status.data.coordinator.workerPort}{status.data.coordinator.tls ? ' (TLS)' : ''} · default policy {status.data.coordinator.schedulingPolicy.toLowerCase().replace(/_/g, ' ')}
          </p>
        )}
      </Card>
    </>
  );
}
