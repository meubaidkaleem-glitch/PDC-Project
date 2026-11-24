import { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { FlaskConical, Play } from 'lucide-react';
import { api, ApiError } from '../api/client';
import { useApi } from '../api/hooks';
import type { EventView, FaultLabStatus, LabOutcome, LabRun } from '../api/types';
import { Badge, Button, Card, EmptyState, ErrorState, KeyValue, Notice, PageHeader, Spinner, Table, TextInput } from '../components/ui';
import type { Tone } from '../components/ui';
import { formatDate, formatDuration, formatMetric } from '../lib/format';
import { metricLabel } from '../lib/labels';
import { usePreferences } from '../lib/preferences';

const VERDICT: Record<LabOutcome['verdict'], { label: string; tone: Tone }> = {
  RECOVERED: { label: 'detected and recovered', tone: 'green' },
  UNAFFECTED: { label: 'completed, nothing to recover', tone: 'blue' },
  NOT_RECOVERED: { label: 'not recovered', tone: 'red' },
  DIFFERENT_RESULT: { label: 'different result', tone: 'amber' },
};

const METRIC_UNITS: Record<string, string> = {
  distributed_time: 'ms', transfer_time_sum: 'ms', load_imbalance: 'ratio', worker_utilization: 'ratio',
  communication_ratio: 'ratio', attempts: 'count', failed_attempts: 'count', retries: 'count', workers_used: 'count',
};

export function FaultLabPage() {
  const { level } = usePreferences();
  const status = useApi<FaultLabStatus>('/api/v1/fault-lab');
  const history = useApi<LabRun[]>('/api/v1/fault-lab/runs?limit=20');
  const [search, setSearch] = useSearchParams();
  const selected = search.get('run') ?? undefined;
  const [seed, setSeed] = useState('');
  const [error, setError] = useState<ApiError>();
  const [starting, setStarting] = useState<string>();

  async function start(scenario: string) {
    setError(undefined);
    setStarting(scenario);
    try {
      const body: { scenario: string; seed?: number } = { scenario };
      if (seed.trim()) body.seed = Number(seed);
      const run = await api<LabRun>('/api/v1/fault-lab/runs', { body });
      setSearch({ run: run.id });
      status.reload();
      history.reload();
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e)));
    } finally {
      setStarting(undefined);
    }
  }

  if (status.loading && !status.data) return <Spinner label="Loading the fault lab" />;
  if (status.error) return <ErrorState error={status.error} onRetry={status.reload} />;
  const s = status.data!;

  return (
    <>
      <PageHeader title="Fault lab" description="Inject a fault into a worker and watch DistriCompute-Distributed detect it and recover." />
      <div className="space-y-4">
        <Notice tone="amber">
          <span className="font-medium">Everything on this page is simulated.</span> Each run starts its own coordinator on a loopback port with three workers inside the application, one of them with the scenario's fault profile, and stops them afterwards. Nothing here touches experiments, the application's coordinator or its workers. Lab events are stored separately and marked as simulated.
        </Notice>
        {!s.enabled && (
          <Notice tone="gray">The fault lab is disabled on this server. An administrator enables it by starting the application with <code className="font-mono text-xs">districompute.faults.enabled=true</code>.</Notice>
        )}
        {error && <ErrorState error={error} />}

        {selected && <LabRunPanel id={selected} onFinished={() => { status.reload(); history.reload(); }} />}

        <Card title="Scenarios" subtitle={`Each run: ${s.setup.size} ${s.setup.algorithm} in ${s.setup.partitions} partitions; ${s.setup.workers}; policy ${String(s.setup.schedulingPolicy).toLowerCase().replace('_', ' ')}; heartbeat ${s.setup.heartbeat}.`}
          actions={level === 'advanced' ? (
            <label className="flex items-center gap-2 text-sm">Seed
              <TextInput aria-label="Seed" inputMode="numeric" className="w-28" placeholder="random" value={seed} onChange={(e) => setSeed(e.target.value.replace(/\D/g, ''))} />
            </label>
          ) : undefined}>
          <div className="grid gap-3 md:grid-cols-2">
            {s.scenarios.map((sc) => (
              <div key={sc.id} className="rounded-lg border border-slate-200 p-4 dark:border-slate-800">
                <div className="flex items-start justify-between gap-2">
                  <h3 className="font-medium">{sc.title}</h3>
                  <Button onClick={() => void start(sc.id)} busy={starting === sc.id} disabled={!s.enabled || s.busy || !!starting}
                    aria-label={`Run ${sc.title}`}>
                    <Play className="h-4 w-4" aria-hidden />Run
                  </Button>
                </div>
                <p className="mt-1 text-sm">{sc.fault}</p>
                <dl className="mt-2 space-y-1 text-xs text-slate-600 dark:text-slate-400">
                  <div><dt className="inline font-medium">Expected detection: </dt><dd className="inline">{sc.expectedDetection}</dd></div>
                  <div><dt className="inline font-medium">Expected recovery: </dt><dd className="inline">{sc.expectedRecovery}</dd></div>
                </dl>
              </div>
            ))}
          </div>
          {s.busy && <p className="mt-3 text-xs text-slate-500">A lab run is in progress; one runs at a time.</p>}
        </Card>

        <Card title="Lab runs">
          {history.data && history.data.length === 0 && <EmptyState title="No lab runs yet">Run a scenario above.</EmptyState>}
          {history.data && history.data.length > 0 && (
            <Table>
              <thead><tr><th>Started</th><th>Scenario</th><th>Outcome</th><th>Seed</th><th>By</th></tr></thead>
              <tbody>
                {history.data.map((r) => (
                  <tr key={r.id} className={r.id === selected ? 'bg-blue-50/60 dark:bg-blue-950/40' : undefined}>
                    <td className="whitespace-nowrap text-xs">{formatDate(r.createdAt)}</td>
                    <td><button type="button" className="font-medium text-blue-700 hover:underline dark:text-blue-400" onClick={() => setSearch({ run: r.id })}>{r.title}</button></td>
                    <td><Outcome run={r} /></td>
                    <td className="font-mono text-xs">{r.seed}</td>
                    <td className="text-xs">{r.owner}</td>
                  </tr>
                ))}
              </tbody>
            </Table>
          )}
        </Card>
      </div>
    </>
  );
}

function Outcome({ run }: { run: LabRun }) {
  if (run.status === 'RUNNING') return <Badge tone="blue">running</Badge>;
  if (run.status === 'FAILED') return <Badge tone="red">lab error</Badge>;
  const v = run.outcome ? VERDICT[run.outcome.verdict] : undefined;
  return <span className="inline-flex gap-1"><Badge tone={v?.tone ?? 'gray'}>{v?.label ?? 'completed'}</Badge><Badge>simulated</Badge></span>;
}

function LabRunPanel({ id, onFinished }: { id: string; onFinished: () => void }) {
  const run = useApi<LabRun>(`/api/v1/fault-lab/runs/${id}`);
  const events = useApi<EventView[]>(`/api/v1/fault-lab/runs/${id}/events`);
  const running = run.data?.status === 'RUNNING';

  useEffect(() => {
    if (!running) return;
    const timer = setInterval(() => { run.reload(); events.reload(); }, 1000);
    return () => clearInterval(timer);
  }, [running, run.reload, events.reload]); // eslint-disable-line react-hooks/exhaustive-deps

  // Once the run has ended, fetch its events again: the last poll may have raced the final flush.
  useEffect(() => {
    if (run.data && run.data.status !== 'RUNNING') {
      events.reload();
      onFinished();
    }
  }, [run.data?.status]); // eslint-disable-line react-hooks/exhaustive-deps

  if (run.error) return <ErrorState error={run.error} />;
  if (!run.data) return <Spinner />;
  const r = run.data;
  const o = r.outcome;
  return (
    <Card title={<span className="flex items-center gap-2"><FlaskConical className="h-4 w-4" aria-hidden />{r.title}</span>}
      subtitle={`Seed ${r.seed}; faulty worker: ${String(r.faultProfile.description ?? '')}`} actions={<Outcome run={r} />}>
      {r.status === 'RUNNING' && <Spinner label="Running on the isolated lab coordinator" />}
      {r.status === 'FAILED' && <Notice tone="red">The lab could not run this scenario: {r.errorMessage}</Notice>}
      {o && (
        <div className="space-y-4">
          <p className="text-sm">{o.verdictText}</p>
          <div className="grid gap-4 lg:grid-cols-3">
            <div>
              <h3 className="mb-2 text-sm font-semibold">Detection</h3>
              {o.detection ? (
                <KeyValue items={[
                  ['Event', o.detection.type.toLowerCase().replace(/_/g, ' ')],
                  ['After', formatDuration(o.detection.afterMs)],
                  ['Message', o.detection.message],
                ]} />
              ) : <p className="text-sm text-slate-500">No failure event concerned the faulty worker.</p>}
            </div>
            <div>
              <h3 className="mb-2 text-sm font-semibold">Recovery</h3>
              <KeyValue items={[
                ['Retries', o.recovery.retries],
                ['Partitions recovered', `${o.recovery.recoveredPartitions} of ${o.task.partitions}`],
                ['First retry after', o.recovery.firstRetry ? formatDuration(o.recovery.firstRetry.afterMs) : '—'],
                ['Task finished after', formatDuration(o.recovery.finishedAfterMs)],
              ]} />
            </div>
            <div>
              <h3 className="mb-2 text-sm font-semibold">Result</h3>
              <KeyValue items={[
                ['Task', o.task.status.toLowerCase() + (o.task.error ? `: ${o.task.error}` : '')],
                ['Same as the fault-free reference', o.task.resultMatchesReference ? 'yes (equal digests)' : 'no'],
                ['Verified by Core', o.task.verified === null ? '—' : o.task.verified ? 'yes' : 'no'],
                ['Digest', <span key="d" className="font-mono text-xs">{o.task.digest?.substring(0, 23) ?? '—'}</span>],
              ]} />
            </div>
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            <div>
              <h3 className="mb-2 text-sm font-semibold">Attempts per worker</h3>
              <Table>
                <thead><tr><th>Worker</th><th>Attempts</th><th>Compute (sum)</th></tr></thead>
                <tbody>
                  {Object.entries(o.workers).map(([w, info]) => (
                    <tr key={w}>
                      <td><span className="font-mono text-xs">{w}</span> {info.role === 'faulty' && <Badge tone="amber">faulty</Badge>}</td>
                      <td><div className="flex flex-wrap gap-1">{Object.entries(info.attempts).map(([st, n]) => (
                        <Badge key={st} tone={st === 'SUCCEEDED' ? 'green' : 'red'}>{st.toLowerCase().replace('_', ' ')} {n}</Badge>
                      ))}</div></td>
                      <td className="tabular-nums">{formatDuration(info.computeMs)}</td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </div>
            <div>
              <h3 className="mb-2 text-sm font-semibold">Runtime measurements</h3>
              <KeyValue items={Object.entries(o.metrics).map(([k, v]) => [metricLabel(k), formatMetric(v, METRIC_UNITS[k], k)])} />
            </div>
          </div>
        </div>
      )}
      <div className="mt-4">
        <h3 className="mb-2 text-sm font-semibold">Event timeline <Badge>simulated</Badge></h3>
        {events.data && events.data.length > 0 ? (
          <div className="max-h-80 overflow-auto">
            <Table>
              <thead><tr><th>Time</th><th>Event</th><th>Worker</th><th>Message</th></tr></thead>
              <tbody>
                {events.data.map((e) => (
                  <tr key={e.id ?? `${e.occurredAt}${e.type}`}>
                    <td className="whitespace-nowrap font-mono text-xs">{new Date(e.occurredAt).toISOString().substring(11, 23)}</td>
                    <td><Badge tone={e.severity === 'ERROR' ? 'red' : e.severity === 'WARN' ? 'amber' : 'gray'}>{e.type.toLowerCase().replace(/_/g, ' ')}</Badge></td>
                    <td className="font-mono text-xs">{e.workerId?.replace(/^lab-[0-9a-f]{8}-/, '') ?? ''}</td>
                    <td className="text-xs">{e.message}</td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </div>
        ) : <p className="text-sm text-slate-500">{running ? 'Waiting for events…' : 'No events.'}</p>}
      </div>
    </Card>
  );
}
