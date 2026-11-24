import { useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { ArrowDown, ArrowUp, BarChart3 } from 'lucide-react';
import { useApi } from '../api/hooks';
import type { Algorithm, ExperimentSummary, Page } from '../api/types';
import { Button, Card, EmptyState, ErrorState, PageHeader, SelectInput, Spinner, StatusBadge, Table, TextInput } from '../components/ui';
import { formatDate, formatDuration } from '../lib/format';
import { MODE_LABELS, modeLabel } from '../lib/labels';
import { useAuth } from '../auth/AuthContext';

const SORTS = [['createdAt', 'Created'], ['name', 'Name'], ['status', 'Status'], ['algorithmId', 'Algorithm'],
  ['executionMode', 'Mode'], ['completedAt', 'Completed']] as const;

export function HistoryPage() {
  const { isAdmin } = useAuth();
  const navigate = useNavigate();
  const algorithms = useApi<Algorithm[]>('/api/v1/algorithms');
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const [algorithm, setAlgorithm] = useState('');
  const [mode, setMode] = useState('');
  const [mine, setMine] = useState(false);
  const [sort, setSort] = useState<{ field: string; asc: boolean }>({ field: 'createdAt', asc: false });
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<string[]>([]);

  const path = useMemo(() => {
    const q = new URLSearchParams({ page: String(page), size: '20', sort: `${sort.field},${sort.asc ? 'asc' : 'desc'}` });
    if (search.trim()) q.set('search', search.trim());
    if (status) q.set('status', status);
    if (algorithm) q.set('algorithm', algorithm);
    if (mode) q.set('mode', mode);
    if (mine) q.set('mine', 'true');
    return `/api/v1/experiments?${q}`;
  }, [search, status, algorithm, mode, mine, sort, page]);
  const list = useApi<Page<ExperimentSummary>>(path);

  const toggleSort = (field: string) => setSort((s) => ({ field, asc: s.field === field ? !s.asc : field === 'name' }));
  const toggle = (id: string) => setSelected((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  return (
    <>
      <PageHeader title="Experiments" description="Every experiment you can see, with its measured performance. Select two or more to compare them."
        actions={<>
          <Button disabled={selected.length < 2} onClick={() => navigate(`/analysis?ids=${selected.join(',')}`)}>
            <BarChart3 className="h-4 w-4" aria-hidden />Compare {selected.length > 0 ? `(${selected.length})` : ''}
          </Button>
          <Link to="/tasks/new" className="text-sm text-blue-700 hover:underline dark:text-blue-400">New task</Link>
        </>} />
      <Card>
        <div className="mb-4 grid gap-3 md:grid-cols-5">
          <TextInput aria-label="Search" placeholder="Search name, id or algorithm" value={search} onChange={(e) => { setSearch(e.target.value); setPage(0); }} />
          <SelectInput aria-label="Status" value={status} onChange={(e) => { setStatus(e.target.value); setPage(0); }}>
            <option value="">Any status</option>
            {['QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED'].map((s) => <option key={s} value={s}>{s.toLowerCase()}</option>)}
          </SelectInput>
          <SelectInput aria-label="Algorithm" value={algorithm} onChange={(e) => { setAlgorithm(e.target.value); setPage(0); }}>
            <option value="">Any algorithm</option>
            {algorithms.data?.map((a) => <option key={a.id} value={a.id}>{a.displayName}</option>)}
          </SelectInput>
          <SelectInput aria-label="Mode" value={mode} onChange={(e) => { setMode(e.target.value); setPage(0); }}>
            <option value="">Any mode</option>
            {Object.entries(MODE_LABELS).slice(0, 3).map(([m, label]) => <option key={m} value={m}>{label}</option>)}
          </SelectInput>
          {isAdmin && (
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={mine} onChange={(e) => { setMine(e.target.checked); setPage(0); }} /> Only mine</label>
          )}
        </div>
        {list.error && <ErrorState error={list.error} onRetry={list.reload} />}
        {list.loading && !list.data && <Spinner />}
        {list.data && list.data.items.length === 0 && (
          <EmptyState title="No experiments match" action={<Link to="/tasks/new" className="text-sm text-blue-700 hover:underline dark:text-blue-400">Create a task</Link>}>
            Change the filters, or submit a task.
          </EmptyState>
        )}
        {list.data && list.data.items.length > 0 && (
          <>
            <Table>
              <thead>
                <tr>
                  <th className="w-8"><span className="sr-only">Select</span></th>
                  {SORTS.slice(0, 2).map(([field, label]) => <SortHeader key={field} field={field} label={label} sort={sort} onSort={toggleSort} />)}
                  <SortHeader field="algorithmId" label="Algorithm" sort={sort} onSort={toggleSort} />
                  <SortHeader field="executionMode" label="Mode" sort={sort} onSort={toggleSort} />
                  <SortHeader field="status" label="Status" sort={sort} onSort={toggleSort} />
                  <th>Duration</th><th>Workers</th><th>Median run</th>{isAdmin && <th>Owner</th>}
                </tr>
              </thead>
              <tbody>
                {list.data.items.map((e) => (
                  <tr key={e.id}>
                    <td><input type="checkbox" aria-label={`Select ${e.name}`} checked={selected.includes(e.id)} onChange={() => toggle(e.id)} /></td>
                    <td className="whitespace-nowrap text-xs">{formatDate(e.createdAt)}</td>
                    <td><Link className="font-medium text-blue-700 hover:underline dark:text-blue-400" to={`/experiments/${e.id}`}>{e.name}</Link>
                      {e.repetitions > 1 && <span className="ml-1 text-xs text-slate-500">×{e.repetitions}</span>}</td>
                    <td className="font-mono text-xs">{e.algorithm}</td>
                    <td>{modeLabel(e.executionMode)}</td>
                    <td><StatusBadge status={e.status} /></td>
                    <td className="tabular-nums">{formatDuration(e.durationMs)}</td>
                    <td className="tabular-nums">{e.workersUsed ?? '—'}</td>
                    <td className="tabular-nums">{formatDuration(e.medianWallTimeMs)}</td>
                    {isAdmin && <td className="text-xs">{e.owner}</td>}
                  </tr>
                ))}
              </tbody>
            </Table>
            <div className="mt-3 flex items-center justify-between text-sm">
              <span className="text-slate-500">{list.data.totalItems} experiments</span>
              <div className="flex items-center gap-2">
                <Button disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</Button>
                <span className="tabular-nums">{list.data.page + 1} / {Math.max(1, list.data.totalPages)}</span>
                <Button disabled={page + 1 >= list.data.totalPages} onClick={() => setPage(page + 1)}>Next</Button>
              </div>
            </div>
          </>
        )}
      </Card>
    </>
  );
}

function SortHeader({ field, label, sort, onSort }: { field: string; label: string; sort: { field: string; asc: boolean }; onSort: (f: string) => void }) {
  const active = sort.field === field;
  return (
    <th aria-sort={active ? (sort.asc ? 'ascending' : 'descending') : 'none'}>
      <button type="button" onClick={() => onSort(field)} className="inline-flex items-center gap-1 uppercase">
        {label}{active && (sort.asc ? <ArrowUp className="h-3 w-3" aria-hidden /> : <ArrowDown className="h-3 w-3" aria-hidden />)}
      </button>
    </th>
  );
}
