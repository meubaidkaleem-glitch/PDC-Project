import { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { AlertTriangle, Info, XCircle } from 'lucide-react';
import { Bar, BarChart, CartesianGrid, ErrorBar, Legend, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { api, ApiError } from '../api/client';
import { useApi } from '../api/hooks';
import type { ComparedExperiment, Comparison, ComparisonBasis, ComparisonIssue, Stats, WorkloadGroup } from '../api/types';
import { Badge, Button, Card, EmptyState, ErrorState, Field, Notice, PageHeader, SelectInput, Spinner, Table } from '../components/ui';
import { formatDate, formatDuration, formatMetric, formatNumber } from '../lib/format';
import { metricLabel, MODE_SHORT, modeLabel } from '../lib/labels';
import { usePreferences } from '../lib/preferences';
import { DownloadLinks } from './ReportsPage';

/** Phases of the time breakdown, in execution order, with the chart colour slot of each. */
const PHASES: { key: string; label: string; metrics: string[]; color: string }[] = [
  { key: 'waiting', label: 'Waiting to start', metrics: ['waiting'], color: 'var(--series-1)' },
  { key: 'preparation', label: 'Validation and input', metrics: ['validation_time', 'input_preparation_time'], color: 'var(--series-2)' },
  { key: 'partitioning', label: 'Partitioning', metrics: ['partitioning_time'], color: 'var(--series-3)' },
  { key: 'compute', label: 'Compute', metrics: ['compute_time'], color: 'var(--series-4)' },
  { key: 'aggregation', label: 'Aggregation', metrics: ['aggregation_time'], color: 'var(--series-5)' },
  { key: 'verification', label: 'Verification', metrics: ['verification_time'], color: 'var(--series-6)' },
  { key: 'other', label: 'Other (storing the result, scheduling, transfer)', metrics: ['other'], color: 'var(--series-7)' },
];

const BASES: { id: ComparisonBasis; label: string }[] = [
  { id: 'EXECUTION_TIME', label: 'Core execution time' },
  { id: 'WALL_TIME', label: 'Wall time' },
];

const INDICATOR_UNITS: Record<string, string> = {
  communication_ratio: 'ratio', worker_utilization: 'ratio', load_imbalance: 'ratio', cpu_utilization: 'ratio',
  transfer_time_sum: 'ms', bytes_sent: 'bytes', bytes_received: 'bytes', attempts: 'count', retries: 'count',
  failed_attempts: 'count',
};

const AXIS = { fontSize: 12, fill: 'var(--chart-ink)' };

export function AnalysisPage() {
  const [search, setSearch] = useSearchParams();
  const ids = (search.get('ids') ?? '').split(',').map((s) => s.trim()).filter(Boolean);
  const baseline = search.get('baseline') ?? undefined;
  const basis = (search.get('basis') as ComparisonBasis | null) ?? undefined;
  const update = (changes: Record<string, string>) => {
    const next: Record<string, string> = { ids: ids.join(',') };
    if (baseline) next.baseline = baseline;
    if (basis) next.basis = basis;
    setSearch({ ...next, ...changes });
  };
  return (
    <>
      <PageHeader title="Performance analysis" description="Compare completed experiments of the same workload: measured times, variation, speedup and efficiency against a baseline. Every figure is computed from stored runs." />
      {ids.length >= 2
        ? <ComparisonView ids={ids} baseline={baseline} basis={basis} onBaseline={(id) => update({ baseline: id })} onBasis={(b) => update({ basis: b })} />
        : (
          <>
            {ids.length === 1 && <div className="mb-4"><Notice tone="amber">Select at least two experiments to compare.</Notice></div>}
            <Workloads />
          </>
        )}
    </>
  );
}

function Workloads() {
  const groups = useApi<WorkloadGroup[]>('/api/v1/analysis/workloads');
  if (groups.loading && !groups.data) return <Spinner label="Grouping completed experiments" />;
  if (groups.error) return <ErrorState error={groups.error} onRetry={groups.reload} />;
  if (!groups.data || groups.data.length === 0) {
    return (
      <Card>
        <EmptyState title="No completed experiments yet" action={<Link to="/tasks/new" className="text-sm text-blue-700 hover:underline dark:text-blue-400">Create a task</Link>}>
          Run the same task in different modes (or with different settings), then compare them here.
        </EmptyState>
      </Card>
    );
  }
  return (
    <Card title="Workloads" subtitle="Completed experiments grouped by algorithm and size. Compare a group, or pick experiments in the history.">
      <Table>
        <thead><tr><th>Algorithm</th><th>Size</th><th>Experiments</th><th>Modes</th><th>Last completed</th><th><span className="sr-only">Actions</span></th></tr></thead>
        <tbody>
          {groups.data.map((g) => (
            <tr key={g.key}>
              <td className="font-mono text-xs">{g.algorithm} {g.algorithmVersion}</td>
              <td className="text-xs">{dimensionsText(g.dimensions)}</td>
              <td className="tabular-nums">{g.experiments}</td>
              <td><div className="flex flex-wrap gap-1">{Object.entries(g.modes).map(([m, n]) => <Badge key={m}>{MODE_SHORT[m as keyof typeof MODE_SHORT] ?? m} {n}</Badge>)}</div></td>
              <td className="whitespace-nowrap text-xs">{formatDate(g.lastCompletedAt)}</td>
              <td>
                {g.experimentIds.length >= 2
                  ? <Link className="text-sm text-blue-700 hover:underline dark:text-blue-400" to={`/analysis?ids=${g.experimentIds.join(',')}`}>Compare {g.experimentIds.length}</Link>
                  : <span className="text-xs text-slate-500">one experiment</span>}
              </td>
            </tr>
          ))}
        </tbody>
      </Table>
    </Card>
  );
}

interface ViewProps {
  ids: string[];
  baseline: string | undefined;
  basis: ComparisonBasis | undefined;
  onBaseline: (id: string) => void;
  onBasis: (basis: ComparisonBasis) => void;
}

function ComparisonView({ ids, baseline, basis, onBaseline, onBasis }: ViewProps) {
  const { level } = usePreferences();
  const advanced = level === 'advanced';
  const [comparison, setComparison] = useState<Comparison>();
  const [error, setError] = useState<ApiError>();
  const key = ids.join(',') + '|' + (baseline ?? '') + '|' + (basis ?? '');

  useEffect(() => {
    let cancelled = false;
    setError(undefined);
    api<Comparison>('/api/v1/analysis/compare', { body: { experimentIds: ids, baselineId: baseline ?? null, basis: basis ?? null } })
      .then((c) => { if (!cancelled) setComparison(c); })
      .catch((e: unknown) => { if (!cancelled) setError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e))); });
    return () => { cancelled = true; };
    // The key covers ids and baseline, which are new arrays on every render.
  }, [key]); // eslint-disable-line react-hooks/exhaustive-deps

  if (error) return <ErrorState error={error} />;
  if (!comparison) return <Spinner label="Comparing" />;
  const c = comparison;
  const measured = c.experiments.filter((e) => e.wallTime);
  const number = (e: ComparedExperiment) => c.experiments.indexOf(e) + 1;
  const tick = (e: ComparedExperiment) => `${number(e)}. ${MODE_SHORT[e.mode] ?? e.mode}`;
  const time = (e: ComparedExperiment): Stats | null => (c.basis === 'EXECUTION_TIME' ? e.executionTime : e.wallTime);
  const basisLabel = BASES.find((b) => b.id === c.basis)?.label ?? c.basis;

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <Link to="/analysis" className="text-sm text-blue-700 hover:underline dark:text-blue-400">All workloads</Link>
        <DownloadLinks label="Download this comparison" href={(f) => {
          const q = new URLSearchParams({ format: f, ids: ids.join(','), basis: c.basis });
          if (c.baselineId) q.set('baseline', c.baselineId);
          return `/api/v1/analysis/report?${q}`;
        }} />
      </div>
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Compare by" htmlFor="basis" hint={c.basisDescription}>
          <SelectInput id="basis" value={c.basis} onChange={(e) => onBasis(e.target.value as ComparisonBasis)}>
            {BASES.map((b) => <option key={b.id} value={b.id}>{b.label}</option>)}
          </SelectInput>
        </Field>
        <Field label="Baseline" htmlFor="baseline" hint={c.baselineReason}>
          <SelectInput id="baseline" value={c.baselineId ?? ''} onChange={(e) => onBaseline(e.target.value)}>
            {c.experiments.map((e) => <option key={e.id} value={e.id} disabled={!e.wallTime}>{number(e)}. {e.name}</option>)}
          </SelectInput>
        </Field>
      </div>

      <Issues issues={c.issues} advanced={advanced} />

      <Card title="Findings" subtitle={`Speedup = baseline ÷ experiment, from the median ${basisLabel.toLowerCase()}`}>
        <ul className="list-disc space-y-1 pl-5 text-sm">{c.summary.map((s, i) => <li key={i}>{s}</li>)}</ul>
      </Card>

      <Card title="Experiments">
        <Table>
          <thead>
            <tr><th>#</th><th>Experiment</th><th>Mode</th><th>Runs</th><th>Median {basisLabel.toLowerCase()}</th><th>Fastest – slowest</th><th>Variation</th><th>Speedup</th><th>Efficiency</th><th>Result</th></tr>
          </thead>
          <tbody>
            {c.experiments.map((e) => (
              <tr key={e.id}>
                <td className="tabular-nums">{number(e)}</td>
                <td className="min-w-48">
                  <Link className="font-medium text-blue-700 hover:underline dark:text-blue-400" to={`/experiments/${e.id}`}>{e.name}</Link>
                  {e.id === c.baselineId && <span className="ml-2"><Badge tone="blue">baseline</Badge></span>}
                </td>
                <td className="whitespace-nowrap">{modeLabel(e.mode)}{units(e)}</td>
                <td className="tabular-nums">{e.completedRuns} / {e.repetitions}</td>
                <td className="tabular-nums">{formatDuration(time(e)?.median)}</td>
                <td className="whitespace-nowrap tabular-nums">{time(e) ? `${formatDuration(time(e)!.min)} – ${formatDuration(time(e)!.max)}` : '—'}</td>
                <td className="tabular-nums">{time(e)?.cv != null ? `${(time(e)!.cv! * 100).toFixed(1)} %` : time(e) ? <span className="text-xs text-slate-500">one run</span> : '—'}</td>
                <td className="tabular-nums" title={e.speedupRange ? `from ${e.speedupRange[0].toFixed(2)}× (slowest run) to ${e.speedupRange[1].toFixed(2)}× (fastest run)` : undefined}>
                  {e.speedup != null ? `${e.speedup.toFixed(2)}×` : '—'}
                </td>
                <td className="tabular-nums">{e.efficiency != null ? <>{(e.efficiency * 100).toFixed(0)} % <span className="text-xs text-slate-500">per {e.efficiencyBasis}</span></> : '—'}</td>
                <td>{!e.wallTime ? '—' : e.resultsConsistent
                  ? <span className="font-mono text-xs" title={e.resultDigest ?? undefined}>{e.resultDigest?.substring(7, 15) ?? '—'}</span>
                  : <Badge tone="amber">runs differ</Badge>}</td>
              </tr>
            ))}
          </tbody>
        </Table>
        <p className="mt-2 text-xs text-slate-500">
          Efficiency is the speedup divided by the threads of a parallel run or the workers of a distributed run, against a sequential baseline. Workers can have several cores, so a distributed efficiency above 100 % is possible. Result shows the start of the verified result's digest: equal digests mean equal results.
        </p>
      </Card>

      {measured.length > 0 && (
        <div className="grid gap-4 lg:grid-cols-2">
          <Card title={`Median ${basisLabel.toLowerCase()}`} subtitle="Whiskers: fastest and slowest run">
            <div className="h-64">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={measured.filter((e) => time(e)).map((e) => ({ label: tick(e), name: e.name, median: time(e)!.median / 1000,
                  range: [(time(e)!.median - time(e)!.min) / 1000, (time(e)!.max - time(e)!.median) / 1000], runs: time(e)!.count }))} maxBarSize={56}>
                  <CartesianGrid strokeDasharray="3 3" stroke="var(--chart-grid)" vertical={false} />
                  <XAxis dataKey="label" tick={AXIS} />
                  <YAxis tick={AXIS} unit=" s" />
                  <Tooltip formatter={(v, _n, item) => [`${Number(v).toFixed(3)} s (median of ${(item.payload as { runs: number }).runs})`, (item.payload as { name: string }).name]} />
                  <Bar dataKey="median" fill="var(--series-1)" radius={[4, 4, 0, 0]} isAnimationActive={false}>
                    <ErrorBar dataKey="range" width={6} stroke="var(--chart-ink)" />
                  </Bar>
                </BarChart>
              </ResponsiveContainer>
            </div>
          </Card>
          <Card title="Speedup over the baseline" subtitle={c.comparable ? 'Above 1: faster than the baseline' : 'Not computed: the experiments are not comparable'}>
            {c.comparable ? (
              <div className="h-64">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={measured.filter((e) => e.speedup != null).map((e) => ({ label: tick(e), name: e.name, speedup: e.speedup }))} maxBarSize={56} margin={{ right: 56 }}>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--chart-grid)" vertical={false} />
                    <XAxis dataKey="label" tick={AXIS} />
                    <YAxis tick={AXIS} unit="×" />
                    <ReferenceLine y={1} stroke="var(--chart-ink)" strokeDasharray="4 4" label={{ value: 'baseline', position: 'right', fill: 'var(--chart-ink)', fontSize: 11 }} />
                    <Tooltip formatter={(v, _n, item) => [`${Number(v).toFixed(2)}×`, (item.payload as { name: string }).name]} />
                    <Bar dataKey="speedup" fill="var(--series-1)" radius={[4, 4, 0, 0]} isAnimationActive={false} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            ) : <EmptyState title="No speedup">The issues above explain why.</EmptyState>}
          </Card>
        </div>
      )}

      {measured.some((e) => Object.keys(e.breakdown).length > 0) && <Breakdown experiments={measured} tick={tick} />}

      {advanced && measured.some((e) => Object.keys(e.indicators).length > 0) && <Indicators experiments={measured} number={number} />}
    </div>
  );
}

function Breakdown({ experiments, tick }: { experiments: ComparedExperiment[]; tick: (e: ComparedExperiment) => string }) {
  const [asTable, setAsTable] = useState(false);
  const rows = experiments.map((e) => {
    const row: Record<string, number | string> = { label: tick(e), name: e.name };
    for (const p of PHASES) row[p.key] = p.metrics.reduce((sum, m) => sum + (e.breakdown[m] ?? 0), 0) / 1000;
    return row;
  });
  return (
    <Card title="Where the wall time goes" subtitle="Median of each phase over completed runs, as Core and the application measured them"
      actions={<Button variant="ghost" onClick={() => setAsTable(!asTable)}>{asTable ? 'Show chart' : 'Show table'}</Button>}>
      {asTable ? (
        <Table>
          <thead><tr><th>Experiment</th>{PHASES.map((p) => <th key={p.key}>{p.label}</th>)}</tr></thead>
          <tbody>{rows.map((r) => (
            <tr key={String(r.label)}><td>{String(r.label)}</td>{PHASES.map((p) => <td key={p.key} className="tabular-nums">{formatDuration(Number(r[p.key]) * 1000)}</td>)}</tr>
          ))}</tbody>
        </Table>
      ) : (
        <div className="h-72">
          <ResponsiveContainer width="100%" height="100%">
            <BarChart data={rows} maxBarSize={56}>
              <CartesianGrid strokeDasharray="3 3" stroke="var(--chart-grid)" vertical={false} />
              <XAxis dataKey="label" tick={AXIS} />
              <YAxis tick={AXIS} unit=" s" />
              <Tooltip formatter={(v, n) => [`${formatDuration(Number(v) * 1000)}`, String(n)]} />
              {/* Legend in stack order (bottom first), in text colour; the swatch carries the identity. */}
              <Legend wrapperStyle={{ fontSize: 12 }} itemSorter={(item) => PHASES.findIndex((p) => p.key === item.dataKey)}
                formatter={(value) => <span style={{ color: 'var(--chart-ink)' }}>{value}</span>} />
              {PHASES.map((p) => (
                <Bar key={p.key} dataKey={p.key} name={p.label} stackId="time" fill={p.color} stroke="var(--chart-surface)" strokeWidth={1} isAnimationActive={false} />
              ))}
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
    </Card>
  );
}

function Indicators({ experiments, number }: { experiments: ComparedExperiment[]; number: (e: ComparedExperiment) => number }) {
  const names = [...new Set(experiments.flatMap((e) => Object.keys(e.indicators)))];
  return (
    <Card title="Further measurements" subtitle="Medians over completed runs; distributed runs report communication and worker figures">
      <Table>
        <thead><tr><th>Measurement</th>{experiments.map((e) => <th key={e.id}>{number(e)}. {MODE_SHORT[e.mode] ?? e.mode}</th>)}</tr></thead>
        <tbody>
          {names.map((n) => (
            <tr key={n}>
              <td>{metricLabel(n)}</td>
              {experiments.map((e) => (
                <td key={e.id} className="tabular-nums">
                  {e.indicators[n] === undefined ? '—' : n === 'throughput_operations'
                    ? `${formatNumber(e.indicators[n])} ${e.operationUnit ?? 'operations'}/s`
                    : formatMetric(e.indicators[n], INDICATOR_UNITS[n], n)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </Table>
    </Card>
  );
}

function Issues({ issues, advanced }: { issues: ComparisonIssue[]; advanced: boolean }) {
  const shown = issues.filter((i) => advanced || i.severity !== 'INFO' || i.code === 'RESULTS_IDENTICAL' || i.code === 'SMALL_WORKLOAD');
  if (shown.length === 0) return null;
  const groups: [ComparisonIssue['severity'], 'red' | 'amber' | 'gray'][] = [['ERROR', 'red'], ['WARNING', 'amber'], ['INFO', 'gray']];
  return (
    <div className="space-y-2">
      {groups.map(([severity, tone]) => {
        const list = shown.filter((i) => i.severity === severity);
        if (list.length === 0) return null;
        const Icon = severity === 'ERROR' ? XCircle : severity === 'WARNING' ? AlertTriangle : Info;
        return (
          <Notice key={severity} tone={tone}>
            <ul className="space-y-1">
              {list.map((i, n) => <li key={n} className="flex gap-2"><Icon className="mt-0.5 h-4 w-4 shrink-0" aria-hidden /><span>{i.message}</span></li>)}
            </ul>
          </Notice>
        );
      })}
    </div>
  );
}

function units(e: ComparedExperiment): string {
  if (e.mode === 'MULTITHREADED_CPU' && e.threads) return `, ${e.threads} threads`;
  if (e.mode === 'DISTRIBUTED_CPU' && e.workers) return `, ${e.workers} worker${e.workers === 1 ? '' : 's'}`;
  return '';
}

function dimensionsText(d: Record<string, number>): string {
  const entries = Object.entries(d);
  return entries.length ? entries.map(([k, v]) => `${k} ${formatNumber(v)}`).join(', ') : '—';
}
