import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { BarChart3, Layers, Square } from 'lucide-react';
import { api, ApiError } from '../api/client';
import { useApi } from '../api/hooks';
import type { BatchView, ExperimentSummary, Page, TaskDraft, Template } from '../api/types';
import { Badge, Button, Card, EmptyState, ErrorState, Field, Notice, PageHeader, SelectInput, Spinner, StatusBadge, Table, TextInput } from '../components/ui';
import { batchItems, parseThreads } from '../lib/batch';
import type { Variation } from '../lib/batch';
import { formatDate, formatDuration } from '../lib/format';
import { modeLabel } from '../lib/labels';
import { DownloadLinks } from './ReportsPage';

const ACTIVE = new Set(['QUEUED', 'RUNNING']);

export function BatchesPage() {
  const list = useApi<BatchView[]>('/api/v1/batches?limit=50');
  return (
    <>
      <PageHeader title="Batches" description="Run one task in several modes or thread counts as a batch. A batch runs its experiments one after another, so they do not compete for processors, and counts as one active experiment." />
      <div className="space-y-4">
        <BatchBuilder />
        <Card title="Your batches">
          {list.error && <ErrorState error={list.error} onRetry={list.reload} />}
          {list.loading && !list.data && <Spinner />}
          {list.data && list.data.length === 0 && <EmptyState title="No batches yet">Build one above.</EmptyState>}
          {list.data && list.data.length > 0 && (
            <Table>
              <thead><tr><th>Name</th><th>Status</th><th>Experiments</th><th>Created</th></tr></thead>
              <tbody>
                {list.data.map((b) => (
                  <tr key={b.id}>
                    <td><Link className="font-medium text-blue-700 hover:underline dark:text-blue-400" to={`/batches/${b.id}`}>{b.name}</Link></td>
                    <td><StatusBadge status={b.status} /></td>
                    <td className="text-xs">{Object.entries(b.counts).map(([s, n]) => `${n} ${s.toLowerCase()}`).join(', ')}</td>
                    <td className="whitespace-nowrap text-xs">{formatDate(b.createdAt)}</td>
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

function BatchBuilder() {
  const navigate = useNavigate();
  const [source, setSource] = useState<'experiment' | 'template'>('experiment');
  const [sourceId, setSourceId] = useState('');
  const experiments = useApi<Page<ExperimentSummary>>('/api/v1/experiments?size=50&status=COMPLETED&sort=createdAt,desc');
  const templates = useApi<Template[]>('/api/v1/templates');
  const fromExperiment = useApi<TaskDraft>(source === 'experiment' && sourceId ? `/api/v1/experiments/${sourceId}/task` : null);
  const template = templates.data?.find((t) => String(t.id) === sourceId);
  const base = source === 'experiment' ? fromExperiment.data : template?.task;
  const baseName = source === 'experiment' ? experiments.data?.items.find((e) => e.id === sourceId)?.name : template?.name;

  const [sequential, setSequential] = useState(true);
  const [parallel, setParallel] = useState(true);
  const [threadsText, setThreadsText] = useState('2, 4');
  const [distributed, setDistributed] = useState(true);
  const [repetitions, setRepetitions] = useState('3');
  const [name, setName] = useState('');
  const [error, setError] = useState<ApiError>();
  const [busy, setBusy] = useState(false);

  const threads = parseThreads(threadsText);
  const reps = Number(repetitions);
  const repsValid = Number.isInteger(reps) && reps >= 1 && reps <= 20;
  const variations = useMemo<Variation[]>(() => [
    ...(sequential ? [{ mode: 'SEQUENTIAL_CPU' as const }] : []),
    ...(parallel ? threads.values.map((t) => ({ mode: 'MULTITHREADED_CPU' as const, threads: t })) : []),
    ...(distributed ? [{ mode: 'DISTRIBUTED_CPU' as const }] : []),
  ], [sequential, parallel, distributed, threads.values.join(',')]); // eslint-disable-line react-hooks/exhaustive-deps
  const items = base && baseName && repsValid ? batchItems(base, baseName, variations, reps) : [];
  const problem = !base ? 'Choose the task to benchmark.' : variations.length === 0 ? 'Choose at least one mode.'
    : parallel && threads.error ? threads.error : !repsValid ? 'Repetitions must be from 1 to 20.' : undefined;

  async function submit() {
    if (problem || items.length === 0) return;
    setBusy(true);
    setError(undefined);
    try {
      const batch = await api<BatchView>('/api/v1/batches', { body: { name: name.trim() || `Benchmark: ${baseName}`, items } });
      navigate(`/batches/${batch.id}`);
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e)));
      setBusy(false);
    }
  }

  return (
    <Card title="New benchmark batch" subtitle="Take the task of a completed experiment or a template and run it in the modes you choose, with the same repetitions">
      <div className="grid gap-4 md:grid-cols-2">
        <Field label="Start from" htmlFor="batch-source">
          <div className="flex gap-2">
            <SelectInput id="batch-source-kind" aria-label="Source kind" value={source} className="w-40"
              onChange={(e) => { setSource(e.target.value as 'experiment' | 'template'); setSourceId(''); }}>
              <option value="experiment">An experiment</option>
              <option value="template">A template</option>
            </SelectInput>
            <SelectInput id="batch-source" value={sourceId} onChange={(e) => setSourceId(e.target.value)}>
              <option value="">Choose…</option>
              {source === 'experiment'
                ? experiments.data?.items.map((e) => <option key={e.id} value={e.id}>{e.name} ({e.algorithm})</option>)
                : templates.data?.map((t) => <option key={t.id} value={String(t.id)}>{t.name} ({t.algorithm})</option>)}
            </SelectInput>
          </div>
        </Field>
        <Field label="Batch name" htmlFor="batch-name">
          <TextInput id="batch-name" maxLength={200} value={name} placeholder={baseName ? `Benchmark: ${baseName}` : 'Benchmark'} onChange={(e) => setName(e.target.value)} />
        </Field>
        <fieldset className="space-y-2 text-sm">
          <legend className="mb-1 font-medium">Modes</legend>
          <label className="flex items-center gap-2"><input type="checkbox" checked={sequential} onChange={(e) => setSequential(e.target.checked)} /> Sequential CPU (the baseline)</label>
          <label className="flex items-center gap-2"><input type="checkbox" checked={parallel} onChange={(e) => setParallel(e.target.checked)} /> Parallel CPU with threads
            <TextInput aria-label="Thread counts" className="w-28" value={threadsText} disabled={!parallel} onChange={(e) => setThreadsText(e.target.value)} />
          </label>
          <label className="flex items-center gap-2"><input type="checkbox" checked={distributed} onChange={(e) => setDistributed(e.target.checked)} /> Distributed CPU on the connected workers</label>
        </fieldset>
        <Field label="Repetitions per experiment" htmlFor="batch-reps" hint="More repetitions give a more reliable median and show the variation">
          <TextInput id="batch-reps" inputMode="numeric" value={repetitions} onChange={(e) => setRepetitions(e.target.value)} />
        </Field>
      </div>
      {fromExperiment.error && <div className="mt-3"><ErrorState error={fromExperiment.error} /></div>}
      {problem && sourceId && <p className="mt-3 text-sm text-amber-700 dark:text-amber-400">{problem}</p>}
      {items.length > 0 && !problem && (
        <div className="mt-4">
          <p className="mb-1 text-sm font-medium">{items.length} experiments, run in this order:</p>
          <ol className="list-decimal space-y-0.5 pl-5 text-sm">{items.map((i) => <li key={i.name}>{i.name} <span className="text-xs text-slate-500">× {reps}</span></li>)}</ol>
        </div>
      )}
      {error && <div className="mt-3"><ErrorState error={error} /></div>}
      <div className="mt-4">
        <Button variant="primary" onClick={() => void submit()} busy={busy} disabled={!!problem || items.length === 0}>
          <Layers className="h-4 w-4" aria-hidden />Submit the batch
        </Button>
      </div>
    </Card>
  );
}

export function BatchPage() {
  const { id = '' } = useParams();
  const batch = useApi<BatchView>(`/api/v1/batches/${id}`);
  const [actionError, setActionError] = useState<ApiError>();
  const active = batch.data ? ACTIVE.has(batch.data.status) : false;

  useEffect(() => {
    if (!active) return;
    const timer = setInterval(batch.reload, 2000);
    return () => clearInterval(timer);
  }, [active, batch.reload]);

  if (batch.error) return <ErrorState error={batch.error} />;
  if (!batch.data) return <Spinner />;
  const b = batch.data;
  const completed = b.experiments.filter((e) => e.completedRuns > 0).map((e) => e.id);

  async function cancel() {
    setActionError(undefined);
    try {
      await api(`/api/v1/batches/${b.id}/cancel`, { method: 'POST' });
      batch.reload();
    } catch (e) {
      setActionError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e)));
    }
  }

  return (
    <>
      <PageHeader title={b.name} description={<span className="flex flex-wrap items-center gap-2"><StatusBadge status={b.status} /> <span className="text-sm">{b.experiments.length} experiments, one after another · created {formatDate(b.createdAt)}{b.owner ? ` by ${b.owner}` : ''}</span></span>}
        actions={<>
          {active && <Button variant="danger" onClick={() => void cancel()}><Square className="h-4 w-4" aria-hidden />Cancel the batch</Button>}
          {completed.length >= 2 && <Link to={`/analysis?ids=${completed.join(',')}`} className="inline-flex items-center gap-1 text-sm text-blue-700 hover:underline dark:text-blue-400"><BarChart3 className="h-4 w-4" aria-hidden />Compare</Link>}
        </>} />
      {actionError && <div className="mb-4"><ErrorState error={actionError} /></div>}
      {active && <div className="mb-4"><Notice tone="blue">The batch is running; this page refreshes every 2 seconds.</Notice></div>}
      <Card title="Experiments">
        <Table>
          <thead><tr><th>#</th><th>Experiment</th><th>Mode</th><th>Status</th><th>Runs</th><th>Median wall time</th><th>Duration</th></tr></thead>
          <tbody>
            {b.experiments.map((e, i) => (
              <tr key={e.id}>
                <td className="tabular-nums">{i + 1}</td>
                <td><Link className="font-medium text-blue-700 hover:underline dark:text-blue-400" to={`/experiments/${e.id}`}>{e.name}</Link></td>
                <td>{modeLabel(e.executionMode)}</td>
                <td><StatusBadge status={e.status} /></td>
                <td className="tabular-nums">{e.completedRuns} / {e.repetitions}</td>
                <td className="tabular-nums">{formatDuration(e.medianWallTimeMs)}</td>
                <td className="tabular-nums">{formatDuration(e.durationMs)}</td>
              </tr>
            ))}
          </tbody>
        </Table>
        {completed.length >= 2 && (
          <div className="mt-4 flex flex-wrap items-center gap-3 text-sm">
            <span className="text-slate-500">Comparison report of the completed experiments:</span>
            <DownloadLinks label="Download the comparison of this batch" href={(f) => `/api/v1/analysis/report?format=${f}&ids=${completed.join(',')}`} />
          </div>
        )}
        {b.status === 'CANCELLED' && <p className="mt-3 text-xs text-slate-500">Cancelled experiments did not run; completed ones keep their results.</p>}
        {Object.keys(b.counts).length > 0 && <p className="mt-2 text-xs text-slate-500">{Object.entries(b.counts).map(([s, n]) => <Badge key={s}>{n} {s.toLowerCase()}</Badge>)}</p>}
      </Card>
    </>
  );
}
