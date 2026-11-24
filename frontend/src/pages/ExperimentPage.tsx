import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { Copy, Download, FileText, RotateCcw, Square, Trash2 } from 'lucide-react';
import { Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api, ApiError } from '../api/client';
import { useApi, useEventStream, useThrottled } from '../api/hooks';
import type { Algorithm, EventView, ExperimentDetail, LiveRun, ResultView } from '../api/types';
import { AttemptTimeline } from '../components/AttemptTimeline';
import { Badge, Button, Card, EmptyState, ErrorState, KeyValue, Notice, PageHeader, ProgressBar, Spinner, Stat,
  StatusBadge, Table, Tabs } from '../components/ui';
import { formatBytes, formatDate, formatDuration, formatMetric, formatNumber, shortId } from '../lib/format';
import { metricLabel, modeLabel, SETTING_LABELS } from '../lib/labels';

const STREAM_TYPES = ['RUN_SUBMITTED', 'RUN_FINISHED', 'EXPERIMENT_FINISHED', 'TASK_SUBMITTED', 'TASK_STATUS_CHANGED',
  'TASK_PROGRESS', 'TASK_FINISHED', 'ATTEMPT_ASSIGNED', 'ATTEMPT_ACCEPTED', 'ATTEMPT_SUCCEEDED', 'ATTEMPT_FAILED',
  'RESULT_IGNORED', 'PARTITION_RETRIED', 'STRATEGY_OVERRIDE'];

type Tab = 'monitor' | 'results' | 'metrics' | 'events' | 'configuration';

export function ExperimentPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const detail = useApi<ExperimentDetail>(`/api/v1/experiments/${id}`);
  const live = useApi<LiveRun>(`/api/v1/experiments/${id}/live`);
  const history = useApi<EventView[]>(`/api/v1/experiments/${id}/events?limit=1000`);
  const [events, setEvents] = useState<EventView[]>([]);
  const [tab, setTab] = useState<Tab>('monitor');
  const [actionError, setActionError] = useState<ApiError>();
  const [now, setNow] = useState(Date.now());

  useEffect(() => { if (history.data) setEvents(history.data); }, [history.data]);
  const active = detail.data ? ['QUEUED', 'RUNNING'].includes(detail.data.summary.status) : false;
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [active]);

  const reloadLive = useThrottled(live.reload, 600);
  const reloadDetail = useThrottled(detail.reload, 1000);
  const stream = useEventStream(active ? `/api/v1/experiments/${id}/stream` : null, STREAM_TYPES, (type, data) => {
    const event = data as EventView;
    if (event.id !== null) setEvents((list) => (list.some((e) => e.id === event.id) ? list : [...list, event]));
    reloadLive();
    if (type === 'RUN_FINISHED' || type === 'EXPERIMENT_FINISHED' || type === 'RUN_SUBMITTED') reloadDetail();
  });

  async function act(action: 'cancel' | 'rerun' | 'delete') {
    setActionError(undefined);
    try {
      if (action === 'cancel') await api(`/api/v1/experiments/${id}/cancel`, { method: 'POST' });
      if (action === 'rerun') {
        const created = await api<ExperimentDetail>(`/api/v1/experiments/${id}/rerun`, { method: 'POST' });
        navigate(`/experiments/${created.summary.id}`);
        return;
      }
      if (action === 'delete') {
        if (!window.confirm('Delete this experiment, its runs, events and stored results?')) return;
        await api(`/api/v1/experiments/${id}`, { method: 'DELETE' });
        navigate('/experiments');
        return;
      }
      detail.reload();
    } catch (e) {
      setActionError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e)));
    }
  }

  if (detail.loading && !detail.data) return <Spinner label="Loading the experiment" />;
  if (detail.error && !detail.data) return <ErrorState error={detail.error} onRetry={detail.reload} />;
  if (!detail.data) return null;
  const d = detail.data;
  const s = d.summary;
  const elapsed = s.startedAt ? (s.completedAt ? new Date(s.completedAt).getTime() : now) - new Date(s.startedAt).getTime() : null;

  return (
    <>
      <PageHeader title={s.name}
        description={<span className="flex flex-wrap items-center gap-2">
          <StatusBadge status={s.status} />
          <span className="font-mono text-xs" title={s.id}>{s.id}</span>
          <button type="button" aria-label="Copy experiment id" onClick={() => void navigator.clipboard?.writeText(s.id)}><Copy className="h-3.5 w-3.5" /></button>
          <span>{s.algorithm} {s.algorithmVersion}</span><span>·</span><span>{modeLabel(s.executionMode)}</span>
          {active && <Badge tone={stream === 'open' ? 'green' : 'amber'}>{stream === 'open' ? 'live' : stream}</Badge>}
        </span>}
        actions={<>
          {active && <Button variant="danger" onClick={() => void act('cancel')}><Square className="h-4 w-4" aria-hidden />Cancel</Button>}
          <Button onClick={() => void act('rerun')}><RotateCcw className="h-4 w-4" aria-hidden />Run again</Button>
          <Link to={`/tasks/new?from=${s.id}`} className="text-sm text-blue-700 hover:underline dark:text-blue-400">Copy and edit</Link>
          {!active && <Link to={`/reports?experiment=${s.id}`} className="inline-flex items-center gap-1 text-sm text-blue-700 hover:underline dark:text-blue-400"><FileText className="h-4 w-4" aria-hidden />Report</Link>}
          {!active && <Button variant="ghost" onClick={() => void act('delete')} aria-label="Delete experiment"><Trash2 className="h-4 w-4" aria-hidden /></Button>}
        </>} />
      {actionError && <div className="mb-4"><ErrorState error={actionError} /></div>}
      {s.status === 'FAILED' && s.errorMessage && <div className="mb-4"><Notice tone="amber"><span className="font-medium">{d.errorCode}:</span> {s.errorMessage}</Notice></div>}

      <div className="mb-4 grid grid-cols-2 gap-3 md:grid-cols-4">
        <Stat label="Runs" value={`${s.completedRuns} / ${s.repetitions}`} hint="completed / planned" />
        <Stat label={s.completedAt ? 'Duration' : 'Elapsed'} value={formatDuration(elapsed)} hint={s.startedAt ? `started ${formatDate(s.startedAt)}` : 'not started yet'} />
        <Stat label="Median wall time" value={formatDuration(s.medianWallTimeMs)} hint="measured, completed runs" />
        <Stat label="Recommendation" value={s.recommendedMode ? (s.recommendationAccepted ? 'followed' : 'overridden') : '—'}
          hint={s.recommendedMode ? `recommended ${modeLabel(s.recommendedMode)}` : 'none could be made'} />
      </div>

      <Tabs<Tab> value={tab} onChange={setTab} tabs={[
        { id: 'monitor', label: 'Live monitor' }, { id: 'results', label: `Results (${d.results.length})` },
        { id: 'metrics', label: 'Metrics' }, { id: 'events', label: `Events (${events.length})` }, { id: 'configuration', label: 'Configuration' }]} />

      {tab === 'monitor' && <Monitor live={live.data} liveError={live.error} detail={d} />}
      {tab === 'results' && <Results experimentId={id} results={d.results} />}
      {tab === 'metrics' && <Metrics detail={d} />}
      {tab === 'events' && <Events events={events} />}
      {tab === 'configuration' && <Configuration detail={d} />}
    </>
  );
}

function Monitor({ live, liveError, detail }: { live: LiveRun | undefined; liveError: ApiError | undefined; detail: ExperimentDetail }) {
  if (liveError) return <ErrorState error={liveError} />;
  if (!live) return <Spinner label="Loading the runtime's view" />;
  const bytes = live.partitions.flatMap((p) => p.attempts).reduce((sum, a) => sum + a.bytes, 0);
  const transfer = live.partitions.flatMap((p) => p.attempts).reduce((sum, a) => sum + a.transferMs, 0);
  return (
    <div className="space-y-4">
      <Card title={`Run ${live.runIndex} of ${detail.summary.repetitions}`} subtitle={live.available ? `Reported by the coordinator for task ${live.coreTaskId}` : 'The runtime no longer holds this run; showing the stored record'}
        actions={<StatusBadge status={live.status} />}>
        <ProgressBar value={live.completedPartitions} max={Math.max(live.totalPartitions, 1)} label="Partitions completed" />
        <div className="mt-4 grid grid-cols-2 gap-3 md:grid-cols-6">
          <Stat label="Completed" value={live.completedPartitions} tone="green" />
          <Stat label="Running" value={live.runningPartitions} tone="blue" />
          <Stat label="Pending" value={live.pendingPartitions} />
          <Stat label="Failed" value={live.failedPartitions} tone={live.failedPartitions > 0 ? 'red' : undefined} />
          <Stat label="Attempts" value={live.attempts} hint={`${live.retries} retries`} />
          {live.available
            ? <Stat label="Moved" value={formatBytes(bytes)} hint={`transfer ${formatDuration(transfer)} (sum)`} />
            // The stored record keeps attempts but not their byte counts; the run's totals are in Metrics.
            : <Stat label="Moved" value="—" hint="see Bytes sent and received in Metrics" />}
        </div>
        <div className="mt-4 text-sm">
          <span className="text-slate-500">Active workers: </span>
          {live.activeWorkers.length ? live.activeWorkers.map((w) => <Link key={w} to={`/workers?selected=${w}`} className="mr-2 font-mono text-xs text-blue-700 hover:underline dark:text-blue-400">{w}</Link>) : 'none'}
          {live.policy && <span className="ml-4 text-slate-500">Policy: {live.policy.toLowerCase().replace(/_/g, ' ')}</span>}
        </div>
      </Card>
      <Card title="Assignment timeline" subtitle="Attempts per worker from dispatch to result; hover a bar for the scheduler's reason">
        <AttemptTimeline partitions={live.partitions} start={live.submittedAt} end={live.finishedAt} />
      </Card>
      {live.errors.length > 0 && (
        <Card title="Errors and retries">
          <ul className="space-y-1 text-sm">{live.errors.map((e, i) => <li key={i} className="font-mono text-xs">{e}</li>)}</ul>
        </Card>
      )}
    </div>
  );
}

function Results({ experimentId, results }: { experimentId: string; results: ResultView[] }) {
  if (results.length === 0) return <EmptyState title="No results yet">Results appear when a run completes. Core verifies each one.</EmptyState>;
  return <div className="space-y-4">{results.map((r) => <ResultCard key={r.runIndex} experimentId={experimentId} result={r} />)}</div>;
}

function ResultCard({ experimentId, result }: { experimentId: string; result: ResultView }) {
  const [open, setOpen] = useState(false);
  const preview = useApi<{ format: string; totalRows: number | null; totalColumns: number | null; rows: string[][]; json: string | null; truncated: boolean }>(
    open && result.stored ? `/api/v1/experiments/${experimentId}/runs/${result.runIndex}/result/preview` : null);
  const summary = Object.entries(result.summary).filter(([, v]) => typeof v !== 'object' || v === null);
  return (
    <Card title={`Run ${result.runIndex}: ${result.resultType}`}
      actions={<>
        <StatusBadge status={result.validationStatus} />
        {result.stored && <a className="inline-flex items-center gap-1 text-sm text-blue-700 hover:underline dark:text-blue-400"
          href={`/api/v1/experiments/${experimentId}/runs/${result.runIndex}/result/download`}><Download className="h-4 w-4" aria-hidden />Download {result.format}</a>}
      </>}>
      <KeyValue items={[
        ['Verification', result.validationMethod ?? '—'],
        ['Digest', <span className="font-mono text-xs">{result.digest}</span>],
        ['Stored value', result.stored ? `${result.format}, ${formatBytes(result.sizeBytes)}${result.storedDigestMatch ? ', digest matches the verified result' : ''}` : (result.storageNote ?? 'not stored')],
        ...summary.map(([k, v]) => [k, typeof v === 'number' ? formatNumber(v, 6) : String(v)] as [string, string]),
      ]} />
      {result.stored && !open && <Button className="mt-3" onClick={() => setOpen(true)}>Show a preview</Button>}
      {open && preview.loading && <Spinner label="Loading the preview" />}
      {open && preview.error && <ErrorState error={preview.error} />}
      {open && preview.data && (preview.data.json ? (
        <pre className="mt-3 max-h-80 overflow-auto rounded bg-slate-50 p-3 text-xs dark:bg-slate-950">{preview.data.json}</pre>
      ) : (
        <div className="mt-3">
          <p className="mb-1 text-xs text-slate-500">First {preview.data.rows.length} rows{preview.data.totalRows !== null ? ` of ${preview.data.totalRows}` : ''}{preview.data.truncated ? ' (truncated; download for the full value)' : ''}</p>
          <div className="max-h-80 overflow-auto"><Table>
            <tbody>{preview.data.rows.map((row, i) => <tr key={i}>{row.map((cell, j) => <td key={j} className="font-mono text-xs tabular-nums">{cell}</td>)}</tr>)}</tbody>
          </Table></div>
        </div>
      ))}
    </Card>
  );
}

function Metrics({ detail }: { detail: ExperimentDetail }) {
  const runs = detail.runs.filter((r) => r.wallTimeMs !== null).map((r) => ({ run: `#${r.runIndex}`, seconds: r.wallTimeMs! / 1000 }));
  if (detail.metrics.length === 0) return <EmptyState title="No metrics yet">Metrics are stored when a run completes.</EmptyState>;
  const single = detail.runs.filter((r) => r.status === 'COMPLETED').length < 2;
  return (
    <div className="space-y-4">
      {single && <Notice tone="gray">Only one completed run: there is no variation to report. Use repetitions to measure it.</Notice>}
      <Card title="Wall time per run" subtitle="Measured by the application from submission to completion">
        <div className="h-56">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={runs} maxBarSize={56}>
              <CartesianGrid strokeDasharray="3 3" className="stroke-slate-200 dark:stroke-slate-800" />
              <XAxis dataKey="run" tick={{ fontSize: 12 }} />
              <YAxis tick={{ fontSize: 12 }} unit=" s" />
              <Tooltip formatter={(v) => [`${Number(v).toFixed(3)} s`, 'wall time']} />
              <Bar dataKey="seconds" fill="var(--series-1)" radius={[4, 4, 0, 0]} isAnimationActive={false} />
            </BarChart>
          </ResponsiveContainer>
        </div>
      </Card>
      <Card title="Statistics over completed runs">
        <Table>
          <thead><tr><th>Metric</th><th>Source</th><th>Runs</th><th>Median</th><th>Min</th><th>Max</th><th>Std. dev.</th></tr></thead>
          <tbody>{detail.metrics.map((m) => (
            <tr key={m.name + m.source}>
              <td>{metricLabel(m.name)}</td><td><Badge>{m.source.toLowerCase()}</Badge></td><td className="tabular-nums">{m.count}</td>
              <td className="tabular-nums">{formatMetric(m.median, m.unit, m.name)}</td><td className="tabular-nums">{formatMetric(m.min, m.unit, m.name)}</td>
              <td className="tabular-nums">{formatMetric(m.max, m.unit, m.name)}</td><td className="tabular-nums">{formatMetric(m.stddev, m.unit, m.name)}</td>
            </tr>))}</tbody>
        </Table>
      </Card>
      <Card title="Runs">
        <Table>
          <thead><tr><th>#</th><th>Task id</th><th>Status</th><th>Wall time</th><th>Threads</th><th>Partitions</th><th>Workers</th><th>Verified</th></tr></thead>
          <tbody>{detail.runs.map((r) => (
            <tr key={r.id}><td>{r.runIndex}</td><td className="font-mono text-xs">{shortId(r.coreTaskId)}…{r.coreTaskId.slice(-3)}</td>
              <td><StatusBadge status={r.status} /></td><td className="tabular-nums">{formatDuration(r.wallTimeMs)}</td>
              <td>{r.threads ?? '—'}</td><td>{r.partitions ?? '—'}</td><td>{r.workersUsed ?? '—'}</td>
              <td>{r.verificationPassed === null ? '—' : r.verificationPassed ? 'yes' : 'no'}</td></tr>))}</tbody>
        </Table>
      </Card>
    </div>
  );
}

function Events({ events }: { events: EventView[] }) {
  if (events.length === 0) return <EmptyState title="No events yet" />;
  return (
    <Card title="Event log" subtitle="Runtime events from the coordinator and application events, oldest first">
      <div className="max-h-[32rem] overflow-auto">
        <Table>
          <thead><tr><th>Time</th><th>Type</th><th>Worker</th><th>Message</th></tr></thead>
          <tbody>{events.map((e) => (
            <tr key={e.id ?? e.occurredAt + e.type}>
              <td className="whitespace-nowrap text-xs tabular-nums">{new Date(e.occurredAt).toLocaleTimeString()}</td>
              <td><Badge tone={e.severity === 'ERROR' ? 'red' : e.severity === 'WARN' ? 'amber' : e.source === 'APPLICATION' ? 'violet' : 'gray'}>{e.type.toLowerCase().replace(/_/g, ' ')}</Badge>{e.simulated && <Badge tone="amber">simulated</Badge>}</td>
              <td className="font-mono text-xs">{e.workerId ?? ''}</td>
              <td className="text-xs">{e.message}</td>
            </tr>))}</tbody>
        </Table>
      </div>
    </Card>
  );
}

function Configuration({ detail }: { detail: ExperimentDetail }) {
  const s = detail.summary;
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <Card title="Strategy">
        <KeyValue items={[
          ['Chosen mode', modeLabel(s.executionMode)],
          ['Recommended at submission', s.recommendedMode ? modeLabel(s.recommendedMode) : 'no reliable recommendation'],
          ['Recommendation followed', s.recommendationAccepted === null ? '—' : s.recommendationAccepted ? 'yes' : 'no'],
          ['Reason for the override', detail.overrideReason ?? '—'],
          ['Basis of the recommendation', detail.adviceBasis?.toLowerCase() ?? '—'],
          ['Scheduling policy', s.schedulingPolicy?.toLowerCase().replace(/_/g, ' ') ?? 'coordinator default'],
        ]} />
      </Card>
      <Card title="Execution settings">
        <KeyValue items={Object.entries(detail.configuration).filter(([, v]) => v !== null).map(([k, v]) =>
          [SETTING_LABELS[k] ?? k, k === 'mode' ? modeLabel(String(v)) : k === 'schedulingPolicy' ? String(v).toLowerCase().replace(/_/g, ' ') : String(v)])} />
      </Card>
      <Parameters detail={detail} />
      <Card title="Workload at submission (Core)">
        <WorkloadSummary summary={detail.inputSummary} />
      </Card>
      {detail.description && <Card title="Description"><p className="text-sm">{detail.description}</p></Card>}
    </div>
  );
}

/**
 * The parameters the experiment ran with: the values given at submission, and for the rest Core's documented
 * defaults (marked as such). Large arrays are summarised.
 */
function Parameters({ detail }: { detail: ExperimentDetail }) {
  const algorithm = useApi<Algorithm>(`/api/v1/algorithms/${encodeURIComponent(detail.summary.algorithm)}`);
  const given = detail.parameters ?? {};
  const show = (v: unknown) => Array.isArray(v) ? (v.length > 12 ? `${v.length} values` : v.join(', ')) : String(v);
  const rows: [string, ReactNode][] = algorithm.data
    ? algorithm.data.parameters
      .filter((p) => p.name in given || (p.defaultValue !== null && p.defaultValue !== undefined))
      .map((p) => [p.name, p.name in given ? show(given[p.name])
        : <span className="text-slate-500">{show(p.defaultValue)} <span className="text-xs">(Core default)</span></span>])
    : Object.entries(given).map(([k, v]) => [k, show(v)]);
  return (
    <Card title="Parameters" subtitle={`Input: ${detail.inputSource.toLowerCase()}`}>
      {rows.length === 0 ? <p className="text-sm text-slate-500">No parameters.</p> : <KeyValue items={rows} />}
    </Card>
  );
}

/** Core's workload analysis stored at submission; unknown fields are listed as they are. */
function WorkloadSummary({ summary }: { summary: Record<string, unknown> | null }) {
  if (!summary || Object.keys(summary).length === 0) return <p className="text-sm text-slate-500">Not recorded.</p>;
  const num = (k: string) => (typeof summary[k] === 'number' ? summary[k] as number : null);
  const dimensions = summary.dimensions && typeof summary.dimensions === 'object'
    ? Object.entries(summary.dimensions as Record<string, number>).map(([k, v]) => `${k} ${formatNumber(v)}`).join(', ') : null;
  const known = new Set(['dimensions', 'estimatedOperations', 'operationUnit', 'inputBytes', 'outputBytes', 'estimatedPeakMemoryBytes']);
  const items: [string, ReactNode][] = [];
  if (dimensions) items.push(['Dimensions', dimensions]);
  if (num('estimatedOperations') !== null) items.push(['Estimated work', `${formatNumber(num('estimatedOperations'))} ${String(summary.operationUnit ?? '')}`]);
  if (num('inputBytes') !== null) items.push(['Input / output', `${formatBytes(num('inputBytes'))} / ${formatBytes(num('outputBytes'))}`]);
  if (num('estimatedPeakMemoryBytes') !== null) items.push(['Estimated peak memory', formatBytes(num('estimatedPeakMemoryBytes'))]);
  for (const [k, v] of Object.entries(summary)) if (!known.has(k)) items.push([k, typeof v === 'object' ? JSON.stringify(v) : String(v)]);
  return <KeyValue items={items} />;
}
