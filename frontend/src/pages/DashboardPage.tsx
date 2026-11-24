import { Link } from 'react-router';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { useApi, useEventStream, useThrottled } from '../api/hooks';
import type { Dashboard } from '../api/types';
import { Badge, Card, EmptyState, ErrorState, PageHeader, Spinner, Stat, StatusBadge, Table } from '../components/ui';
import { formatAge, formatDuration } from '../lib/format';
import { MODE_SHORT, modeLabel } from '../lib/labels';

const REFRESH_ON = ['RUN_SUBMITTED', 'RUN_FINISHED', 'EXPERIMENT_FINISHED', 'WORKER_REGISTERED', 'WORKER_DISCONNECTED',
  'WORKER_STATUS_CHANGED', 'WORKER_REMOVED'];

export function DashboardPage() {
  const { data, error, loading, reload } = useApi<Dashboard>('/api/v1/dashboard');
  const refresh = useThrottled(reload, 1500);
  const stream = useEventStream('/api/v1/events/stream', REFRESH_ON, refresh);

  if (loading && !data) return <Spinner label="Loading the overview" />;
  if (error && !data) return <ErrorState error={error} onRetry={reload} />;
  if (!data) return null;

  const s = data.experiments.byStatus;
  const chart = data.performance.map((p) => ({ mode: MODE_SHORT[p.mode] ?? p.mode, seconds: p.medianWallTimeMs / 1000,
    runs: p.completedRuns }));

  return (
    <>
      <PageHeader title="Overview" description={<>Live state of the platform ({data.scope}). Every figure below comes from the
        database or the running coordinator; nothing is sample data.</>}
        actions={<Badge tone={stream === 'open' ? 'green' : 'amber'}>{stream === 'open' ? 'live' : stream}</Badge>} />
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        <Stat label="Experiments" value={data.experiments.total} />
        <Stat label="Running" value={(s.RUNNING ?? 0) + (s.QUEUED ?? 0)} tone="blue" hint={`${s.QUEUED ?? 0} queued`} />
        <Stat label="Completed" value={s.COMPLETED ?? 0} tone="green" />
        <Stat label="Failed" value={s.FAILED ?? 0} tone={(s.FAILED ?? 0) > 0 ? 'red' : undefined} hint={`${s.CANCELLED ?? 0} cancelled`} />
        <Stat label="Connected workers" value={data.workers.connected} hint={`${data.workers.busySlots} of ${data.workers.totalSlots} slots busy`} />
        <Stat label="Local cores" value={data.resources.localCores} hint={data.resources.gpuAvailable ? 'GPU available' : 'no GPU backend'} />
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-3">
        <Card title="Recent experiments" className="lg:col-span-2" actions={<Link className="text-sm text-blue-700 hover:underline dark:text-blue-400" to="/experiments">All experiments</Link>}>
          {data.recentExperiments.length === 0 ? (
            <EmptyState title="No experiments yet" action={<Link to="/tasks/new" className="text-sm text-blue-700 hover:underline dark:text-blue-400">Create a task</Link>}>
              Experiments you submit appear here with their status and measured duration.
            </EmptyState>
          ) : (
            <Table>
              <thead><tr><th>Name</th><th>Algorithm</th><th>Mode</th><th>Status</th><th>Duration</th></tr></thead>
              <tbody>
                {data.recentExperiments.map((e) => (
                  <tr key={e.id}>
                    <td><Link className="font-medium text-blue-700 hover:underline dark:text-blue-400" to={`/experiments/${e.id}`}>{e.name}</Link></td>
                    <td className="font-mono text-xs">{e.algorithm}</td>
                    <td>{modeLabel(e.executionMode)}</td>
                    <td><StatusBadge status={e.status} /></td>
                    <td className="tabular-nums">{formatDuration(e.durationMs)}</td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>

        <Card title="System health">
          <ul className="space-y-2 text-sm">
            <li className="flex justify-between"><span>Database</span><StatusBadge status={data.health.database} /></li>
            <li className="flex justify-between"><span>Coordinator</span><StatusBadge status={data.health.coordinator} /></li>
            <li className="flex justify-between"><span>Live event stream</span><Badge tone={stream === 'open' ? 'green' : 'amber'}>{stream}</Badge></li>
            <li className="flex justify-between"><span>Events dropped</span><span className="tabular-nums">{data.health.eventsDropped}</span></li>
            {Object.entries(data.workers.byStatus).map(([status, count]) => (
              <li key={status} className="flex justify-between"><span>Workers {status.toLowerCase()}</span><span className="tabular-nums">{count}</span></li>
            ))}
          </ul>
        </Card>
      </div>

      <div className="mt-4 grid gap-4 lg:grid-cols-2">
        <Card title="Measured performance by mode" subtitle="Median wall time of completed runs (all algorithms and sizes mixed; compare experiments for meaningful figures)">
          {chart.length === 0 ? <EmptyState title="No completed runs yet" /> : (
            <div className="h-56">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={chart} margin={{ left: 8, right: 8 }} maxBarSize={56}>
                  <CartesianGrid strokeDasharray="3 3" className="stroke-slate-200 dark:stroke-slate-800" />
                  <XAxis dataKey="mode" tick={{ fontSize: 12 }} />
                  <YAxis tick={{ fontSize: 12 }} unit=" s" />
                  <Tooltip formatter={(v) => [`${Number(v).toFixed(3)} s`, 'median wall time']} />
                  <Bar dataKey="seconds" fill="#2563eb" />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )}
        </Card>
        <Card title="Recent activity">
          {data.recentEvents.length === 0 ? <EmptyState title="No events yet" /> : (
            <ul className="space-y-1.5 text-sm">
              {data.recentEvents.map((e) => (
                <li key={e.id ?? e.occurredAt + e.type} className="flex items-start gap-2">
                  <Badge tone={e.severity === 'ERROR' ? 'red' : e.severity === 'WARN' ? 'amber' : 'gray'}>{e.type.toLowerCase().replace(/_/g, ' ')}</Badge>
                  <span className="min-w-0 flex-1 truncate" title={e.message}>{e.message}</span>
                  <span className="shrink-0 text-xs text-slate-500">{formatAge(e.occurredAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>
    </>
  );
}
