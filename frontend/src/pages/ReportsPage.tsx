import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Download } from 'lucide-react';
import { useApi } from '../api/hooks';
import type { Algorithm, ExperimentDetail, ExperimentSummary, Page, WorkloadGroup } from '../api/types';
import { Card, EmptyState, ErrorState, Field, PageHeader, SelectInput, Spinner, Table, TextInput } from '../components/ui';
import { formatDate, formatNumber } from '../lib/format';
import { MODE_LABELS, MODE_SHORT, modeLabel } from '../lib/labels';
import { useAuth } from '../auth/AuthContext';

type Format = 'pdf' | 'csv' | 'json';

const FORMAT_HINTS: Record<Format, string> = {
  pdf: 'for reading and printing: tables, charts and explanations',
  csv: 'for spreadsheets: one row per run or experiment',
  json: 'for programs: every stored value',
};

/** Links that download a report in each format; the session cookie authenticates them. */
export function DownloadLinks({ href, formats = ['pdf', 'csv', 'json'], label }: { href: (format: Format) => string; formats?: Format[]; label?: string }) {
  return (
    <div className="flex flex-wrap items-center gap-2" role="group" aria-label={label ?? 'Download'}>
      {formats.map((f) => (
        <a key={f} href={href(f)} download title={FORMAT_HINTS[f]}
          className="inline-flex items-center gap-1.5 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-800 hover:bg-slate-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:hover:bg-slate-800">
          <Download className="h-4 w-4" aria-hidden />{f.toUpperCase()}
        </a>
      ))}
    </div>
  );
}

export function ReportsPage() {
  const [search] = useSearchParams();
  return (
    <>
      <PageHeader title="Reports" description="Reports are generated when you download them, from the stored measurements; each download is recorded in the audit log. PDF is for reading and printing, CSV for spreadsheets, JSON for programs." />
      <div className="space-y-4">
        <ExperimentReport preselected={search.get('experiment') ?? undefined} />
        <ComparisonReports />
        <HistoryExport />
      </div>
    </>
  );
}

function ExperimentReport({ preselected }: { preselected: string | undefined }) {
  const [query, setQuery] = useState('');
  const [chosen, setChosen] = useState<string | undefined>(preselected);
  const path = useMemo(() => {
    const q = new URLSearchParams({ size: '50', sort: 'createdAt,desc' });
    if (query.trim()) q.set('search', query.trim());
    return `/api/v1/experiments?${q}`;
  }, [query]);
  const list = useApi<Page<ExperimentSummary>>(path);
  const pre = useApi<ExperimentDetail>(preselected ? `/api/v1/experiments/${preselected}` : null);
  const options = useMemo(() => {
    const items = list.data?.items ?? [];
    const extra = pre.data && !items.some((e) => e.id === pre.data!.summary.id) ? [pre.data.summary] : [];
    return [...extra, ...items];
  }, [list.data, pre.data]);
  const selected = options.find((e) => e.id === chosen);

  return (
    <Card title="Experiment report" subtitle="Configuration, recommendation, runs, every measurement with its statistics, and the verified results of one experiment">
      <div className="grid gap-3 md:grid-cols-2">
        <Field label="Find" htmlFor="report-search">
          <TextInput id="report-search" placeholder="Name, id or algorithm" value={query} onChange={(e) => setQuery(e.target.value)} />
        </Field>
        <Field label="Experiment" htmlFor="report-experiment">
          <SelectInput id="report-experiment" value={chosen ?? ''} onChange={(e) => setChosen(e.target.value || undefined)}>
            <option value="">Choose an experiment</option>
            {options.map((e) => <option key={e.id} value={e.id}>{e.name} ({modeLabel(e.executionMode)}, {e.status.toLowerCase()})</option>)}
          </SelectInput>
        </Field>
      </div>
      {list.error && <div className="mt-3"><ErrorState error={list.error} onRetry={list.reload} /></div>}
      {selected && (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
          <p className="text-sm">
            <Link className="font-medium text-blue-700 hover:underline dark:text-blue-400" to={`/experiments/${selected.id}`}>{selected.name}</Link>
            <span className="text-slate-500"> · {selected.algorithm} · {selected.completedRuns} of {selected.repetitions} runs completed · {formatDate(selected.createdAt)}</span>
          </p>
          <DownloadLinks label="Download the experiment report" href={(f) => `/api/v1/experiments/${selected.id}/report?format=${f}`} />
        </div>
      )}
      {selected && selected.completedRuns === 0 && (
        <p className="mt-2 text-xs text-slate-500">This experiment has no completed run yet; its report shows the configuration and what happened so far.</p>
      )}
    </Card>
  );
}

function ComparisonReports() {
  const groups = useApi<WorkloadGroup[]>('/api/v1/analysis/workloads');
  return (
    <Card title="Comparison reports" subtitle="Speedup, efficiency, variation, time breakdown and caveats for experiments of one workload, against a sequential baseline when there is one">
      {groups.loading && !groups.data && <Spinner />}
      {groups.error && <ErrorState error={groups.error} onRetry={groups.reload} />}
      {groups.data && groups.data.filter((g) => g.experimentIds.length >= 2).length === 0 && (
        <EmptyState title="Nothing to compare yet">A comparison needs two or more completed experiments of the same algorithm and size.</EmptyState>
      )}
      {groups.data && groups.data.some((g) => g.experimentIds.length >= 2) && (
        <>
          <Table>
            <thead><tr><th>Workload</th><th>Experiments</th><th>Modes</th><th><span className="sr-only">Downloads</span></th></tr></thead>
            <tbody>
              {groups.data.filter((g) => g.experimentIds.length >= 2).map((g) => (
                <tr key={g.key}>
                  <td><span className="font-mono text-xs">{g.algorithm}</span> <span className="text-xs text-slate-500">{Object.entries(g.dimensions).map(([k, v]) => `${k} ${formatNumber(v)}`).join(', ')}</span></td>
                  <td className="tabular-nums">{g.experimentIds.length}</td>
                  <td className="text-xs">{Object.entries(g.modes).map(([m, n]) => `${MODE_SHORT[m as keyof typeof MODE_SHORT] ?? m} ${n}`).join(', ')}</td>
                  <td><DownloadLinks label={`Download the comparison of ${g.algorithm}`} href={(f) => `/api/v1/analysis/report?format=${f}&ids=${g.experimentIds.join(',')}`} /></td>
                </tr>
              ))}
            </tbody>
          </Table>
          <p className="mt-2 text-xs text-slate-500">To choose the experiments, baseline or basis yourself, open <Link to="/analysis" className="text-blue-700 hover:underline dark:text-blue-400">Performance analysis</Link> and download from there.</p>
        </>
      )}
    </Card>
  );
}

function HistoryExport() {
  const { isAdmin } = useAuth();
  const algorithms = useApi<Algorithm[]>('/api/v1/algorithms');
  const [status, setStatus] = useState('');
  const [algorithm, setAlgorithm] = useState('');
  const [mode, setMode] = useState('');
  const [mine, setMine] = useState(false);
  const query = (format: Format) => {
    const q = new URLSearchParams({ format });
    if (status) q.set('status', status);
    if (algorithm) q.set('algorithm', algorithm);
    if (mode) q.set('mode', mode);
    if (mine) q.set('mine', 'true');
    return `/api/v1/experiments/export?${q}`;
  };
  return (
    <Card title="Experiment history" subtitle={`One row per experiment you can see, newest first, at most 5,000${isAdmin ? ' (all users unless "only mine")' : ''}`}>
      <div className="grid gap-3 md:grid-cols-4">
        <Field label="Status" htmlFor="export-status">
          <SelectInput id="export-status" value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">Any status</option>
            {['COMPLETED', 'FAILED', 'CANCELLED', 'RUNNING', 'QUEUED'].map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
          </SelectInput>
        </Field>
        <Field label="Algorithm" htmlFor="export-algorithm">
          <SelectInput id="export-algorithm" value={algorithm} onChange={(e) => setAlgorithm(e.target.value)}>
            <option value="">Any algorithm</option>
            {algorithms.data?.map((a) => <option key={a.id} value={a.id}>{a.displayName}</option>)}
          </SelectInput>
        </Field>
        <Field label="Mode" htmlFor="export-mode">
          <SelectInput id="export-mode" value={mode} onChange={(e) => setMode(e.target.value)}>
            <option value="">Any mode</option>
            {Object.entries(MODE_LABELS).slice(0, 3).map(([m, label]) => <option key={m} value={m}>{label}</option>)}
          </SelectInput>
        </Field>
        {isAdmin && <label className="flex items-end gap-2 pb-2 text-sm"><input type="checkbox" checked={mine} onChange={(e) => setMine(e.target.checked)} /> Only mine</label>}
      </div>
      <div className="mt-4"><DownloadLinks label="Download the history" formats={['csv', 'json']} href={query} /></div>
    </Card>
  );
}
