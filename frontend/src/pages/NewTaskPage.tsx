import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { ChevronLeft, Lock, Sparkles } from 'lucide-react';
import { api, ApiError } from '../api/client';
import { useApi } from '../api/hooks';
import type { Advice, Algorithm, Category, ExecutionMode, ExecutionSettings, ExperimentDetail, TaskDraft } from '../api/types';
import { AdvisorPanel } from '../components/AdvisorPanel';
import { EMPTY_PARAMETERS, ParameterForm, toDraftParameters } from '../components/ParameterForm';
import type { ParameterState } from '../components/ParameterForm';
import { Badge, Button, Card, ErrorState, Field, Notice, PageHeader, SelectInput, Spinner, TextArea, TextInput, cx } from '../components/ui';
import { usePreferences } from '../lib/preferences';
import { MODE_LABELS, POLICIES } from '../lib/labels';

type NumericSetting = Exclude<keyof ExecutionSettings, 'mode' | 'schedulingPolicy' | 'priority'>;

const ADVANCED_SETTINGS: { key: NumericSetting; label: string; hint: string; modes: ExecutionMode[] }[] = [
  { key: 'repetitions', label: 'Benchmark repetitions', hint: 'Runs of the same task; statistics use all completed runs', modes: ['SEQUENTIAL_CPU', 'MULTITHREADED_CPU', 'DISTRIBUTED_CPU'] },
  { key: 'threads', label: 'Threads', hint: 'Default: all processors of the server', modes: ['MULTITHREADED_CPU'] },
  { key: 'targetPartitions', label: 'Partitions', hint: 'Number of partitions Core plans (default: chosen by the runtime)', modes: ['MULTITHREADED_CPU', 'DISTRIBUTED_CPU'] },
  { key: 'partitionSize', label: 'Partition size', hint: 'Elements per partition (range and chunk algorithms)', modes: ['MULTITHREADED_CPU', 'DISTRIBUTED_CPU'] },
  { key: 'blockRows', label: 'Block rows', hint: 'Matrix algorithms: rows per block', modes: ['MULTITHREADED_CPU', 'DISTRIBUTED_CPU'] },
  { key: 'blockCols', label: 'Block columns', hint: 'Matrix algorithms: columns per block', modes: ['MULTITHREADED_CPU', 'DISTRIBUTED_CPU'] },
  { key: 'partitionsPerSlot', label: 'Partitions per worker slot', hint: 'When partitions are not set (default 2)', modes: ['DISTRIBUTED_CPU'] },
  { key: 'maxWorkers', label: 'Maximum workers', hint: 'Use at most this many workers', modes: ['DISTRIBUTED_CPU'] },
  { key: 'maxAttempts', label: 'Attempts per partition', hint: 'Retry budget (default 3)', modes: ['DISTRIBUTED_CPU'] },
  { key: 'attemptTimeoutSeconds', label: 'Attempt timeout (s)', hint: 'Limit of one attempt on a worker', modes: ['DISTRIBUTED_CPU'] },
  { key: 'timeoutSeconds', label: 'Run timeout (s)', hint: 'Deadline of each run (default: the server limit)', modes: ['SEQUENTIAL_CPU', 'MULTITHREADED_CPU', 'DISTRIBUTED_CPU'] },
  { key: 'maxMemoryMb', label: 'Memory cap (MiB)', hint: 'Core rejects runs estimated above it', modes: ['SEQUENTIAL_CPU', 'MULTITHREADED_CPU', 'DISTRIBUTED_CPU'] },
];

export function NewTaskPage() {
  const { level } = usePreferences();
  const advanced = level === 'advanced';
  const navigate = useNavigate();
  const [search] = useSearchParams();
  const categories = useApi<Category[]>('/api/v1/categories');
  const algorithms = useApi<Algorithm[]>('/api/v1/algorithms');
  const source = useApi<ExperimentDetail>(search.get('from') ? `/api/v1/experiments/${search.get('from')}` : null);

  const [categoryId, setCategoryId] = useState<string>();
  const [algorithmId, setAlgorithmId] = useState<string>();
  const [params, setParams] = useState<ParameterState>(EMPTY_PARAMETERS);
  const [mode, setMode] = useState<ExecutionMode>('MULTITHREADED_CPU');
  const [settings, setSettings] = useState<Record<string, string>>({});
  const [policy, setPolicy] = useState('');
  const [advice, setAdvice] = useState<Advice>();
  const [adviceStale, setAdviceStale] = useState(false);
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const [error, setError] = useState<ApiError>();
  const [busy, setBusy] = useState<'analyze' | 'submit'>();

  const algorithm = algorithms.data?.find((a) => a.id === algorithmId);

  // Prefill from an earlier experiment (?from=<id>): same algorithm, parameters and settings.
  useEffect(() => {
    const d = source.data;
    if (!d || !algorithms.data) return;
    const a = algorithms.data.find((x) => x.id === d.summary.algorithm);
    if (!a) return;
    setCategoryId(a.category);
    setAlgorithmId(a.id);
    const values: Record<string, string> = {};
    for (const [k, v] of Object.entries(d.parameters)) {
      if (v && typeof v === 'object' && !Array.isArray(v)) continue;
      values[k] = Array.isArray(v) ? v.join(', ') : String(v);
    }
    setParams({ values, uploads: {} });
    setMode(d.summary.executionMode);
    const s: Record<string, string> = {};
    for (const [k, v] of Object.entries(d.configuration)) if (v !== null && k !== 'mode' && k !== 'schedulingPolicy' && k !== 'priority') s[k] = String(v);
    setSettings(s);
    setPolicy((d.configuration.schedulingPolicy as string) ?? '');
    setName(`${d.summary.name} (copy)`);
  }, [source.data, algorithms.data]);

  const draft = useMemo(() => {
    if (!algorithm) return undefined;
    const { parameters, inputs, problems } = toDraftParameters(algorithm, params);
    const execution: ExecutionSettings = { mode };
    for (const s of ADVANCED_SETTINGS) {
      const raw = settings[s.key]?.trim();
      if (advanced && raw && s.modes.includes(mode)) execution[s.key] = Number(raw);
    }
    if (advanced && policy && mode === 'DISTRIBUTED_CPU') execution.schedulingPolicy = policy;
    const task: TaskDraft = { algorithm: algorithm.id, parameters, inputs, execution };
    return { task, problems };
  }, [algorithm, params, mode, settings, policy, advanced]);

  const violations = useMemo(() => {
    const out: Record<string, string> = { ...(draft?.problems ?? {}) };
    error?.violations.forEach((v) => { out[v.field] = v.message; });
    return out;
  }, [draft, error]);

  function changed(next: ParameterState) {
    setParams(next);
    if (advice) setAdviceStale(true);
  }

  async function analyze() {
    if (!draft || Object.keys(draft.problems).length) return;
    setBusy('analyze');
    setError(undefined);
    try {
      const result = await api<Advice>('/api/v1/tasks/analyze', { body: draft.task });
      setAdvice(result);
      setAdviceStale(false);
      if (result.recommendedMode && (!advanced || !advice)) setMode(result.recommendedMode);
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e)));
    } finally {
      setBusy(undefined);
    }
  }

  async function submit() {
    if (!draft || !algorithm) return;
    setBusy('submit');
    setError(undefined);
    try {
      const detail = await api<ExperimentDetail>('/api/v1/experiments', { body: {
        name: name.trim() || `${algorithm.displayName} ${new Date().toLocaleString()}`,
        description: description.trim() || null,
        task: draft.task,
        overrideReason: overriding ? overrideReason.trim() || null : null,
      } });
      navigate(`/experiments/${detail.summary.id}`);
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e)));
      setBusy(undefined);
    }
  }

  if (categories.loading || algorithms.loading) return <Spinner label="Loading Core's algorithm catalog" />;
  if (categories.error || algorithms.error) return <ErrorState error={(categories.error ?? algorithms.error)!} onRetry={() => { categories.reload(); algorithms.reload(); }} />;

  const overriding = !!advice?.recommendedMode && advice.recommendedMode !== mode;
  const executable = (algorithm?.modes ?? []).filter((m) => m.executable);

  return (
    <>
      <PageHeader title="New task" description={advanced ? 'Advanced mode: every execution setting is available.'
        : 'Beginner mode: choose what to compute, analyse it, and run it with the recommended strategy. Switch to advanced mode at the top for more control.'} />

      {!categoryId && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {categories.data!.map((c) => (
            <button key={c.id} type="button" disabled={!c.available} onClick={() => setCategoryId(c.id)}
              className={cx('rounded-lg border p-4 text-left transition-colors',
                c.available ? 'border-slate-200 bg-white hover:border-blue-400 dark:border-slate-800 dark:bg-slate-900'
                  : 'cursor-not-allowed border-dashed border-slate-300 bg-slate-50 text-slate-500 dark:border-slate-700 dark:bg-slate-900/50')}>
              <div className="flex items-center justify-between gap-2">
                <span className="font-medium">{c.name}</span>
                {c.available ? <Badge tone="blue">{c.algorithmCount} algorithm{c.algorithmCount === 1 ? '' : 's'}</Badge>
                  : <Badge><Lock className="h-3 w-3" aria-hidden /> not available</Badge>}
              </div>
              <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">{c.description}</p>
            </button>
          ))}
        </div>
      )}

      {categoryId && !algorithmId && (
        <>
          <Button variant="ghost" onClick={() => setCategoryId(undefined)}><ChevronLeft className="h-4 w-4" aria-hidden />Categories</Button>
          <div className="mt-3 grid gap-3 md:grid-cols-2">
            {algorithms.data!.filter((a) => a.category === categoryId).map((a) => (
              <button key={a.id} type="button" onClick={() => { setAlgorithmId(a.id); setParams(EMPTY_PARAMETERS); setAdvice(undefined); }}
                className="rounded-lg border border-slate-200 bg-white p-4 text-left hover:border-blue-400 dark:border-slate-800 dark:bg-slate-900">
                <div className="flex items-center justify-between gap-2">
                  <span className="font-medium">{a.displayName}</span>
                  <span className="font-mono text-xs text-slate-500">{a.id} {a.version}</span>
                </div>
                <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">{a.description}</p>
                <p className="mt-2 text-xs text-slate-500">Verification: {a.verificationMethod}</p>
              </button>
            ))}
          </div>
        </>
      )}

      {algorithm && (
        <div className="space-y-4">
          <div className="flex flex-wrap items-center gap-2">
            <Button variant="ghost" onClick={() => { setAlgorithmId(undefined); setAdvice(undefined); setError(undefined); }}>
              <ChevronLeft className="h-4 w-4" aria-hidden />Algorithms
            </Button>
            <h2 className="text-lg font-semibold">{algorithm.displayName}</h2>
            <span className="font-mono text-xs text-slate-500">{algorithm.id} {algorithm.version}</span>
          </div>
          {algorithm.limitations.length > 0 && advanced && (
            <Notice tone="gray"><span className="font-medium">Core's documented limitations:</span> {algorithm.limitations.join(' ')}</Notice>
          )}

          <Card title="Input and parameters" subtitle="Validated by DistriCompute-Core before anything runs">
            <ParameterForm algorithm={algorithm} state={params} onChange={changed} advanced={advanced} violations={violations} />
          </Card>

          <Card title="Execution">
            <div className="grid gap-4 md:grid-cols-2">
              <Field label="Execution mode" htmlFor="mode" error={violations['execution.mode']}
                hint={advice?.recommendedMode ? `Recommended: ${MODE_LABELS[advice.recommendedMode]}` : 'Analyse the task for a recommendation'}>
                <SelectInput id="mode" value={mode} onChange={(e) => setMode(e.target.value as ExecutionMode)}>
                  {(algorithm.modes).map((m) => (
                    <option key={m.mode} value={m.mode} disabled={!m.executable}>{MODE_LABELS[m.mode]}{m.executable ? '' : ' — not available'}</option>
                  ))}
                </SelectInput>
              </Field>
              {advanced && mode === 'DISTRIBUTED_CPU' && (
                <Field label="Scheduling policy" htmlFor="policy" hint={POLICIES.find((p) => p.id === policy)?.description ?? 'Default: the coordinator\'s policy'}>
                  <SelectInput id="policy" value={policy} onChange={(e) => setPolicy(e.target.value)}>
                    <option value="">Coordinator default</option>
                    {POLICIES.map((p) => <option key={p.id} value={p.id}>{p.label}</option>)}
                  </SelectInput>
                </Field>
              )}
              {advanced && ADVANCED_SETTINGS.filter((s) => s.modes.includes(mode)).map((s) => (
                <Field key={s.key} label={s.label} hint={s.hint} htmlFor={`set-${s.key}`} error={violations[`execution.${s.key}`]}>
                  <TextInput id={`set-${s.key}`} inputMode="numeric" value={settings[s.key] ?? ''}
                    onChange={(e) => setSettings({ ...settings, [s.key]: e.target.value })} />
                </Field>
              ))}
            </div>
            {executable.length < algorithm.modes.length && (
              <p className="mt-3 text-xs text-slate-500">
                Not available: {algorithm.modes.filter((m) => !m.executable).map((m) => `${MODE_LABELS[m.mode]} (${m.reason})`).join('; ')}.
              </p>
            )}
            <div className="mt-4 flex flex-wrap items-center gap-2">
              <Button onClick={() => void analyze()} busy={busy === 'analyze'} disabled={!draft || Object.keys(draft.problems).length > 0}>
                <Sparkles className="h-4 w-4" aria-hidden />{advice ? 'Analyse again' : 'Analyse and recommend'}
              </Button>
              {adviceStale && <span className="text-xs text-amber-700 dark:text-amber-400">The task changed since the analysis.</span>}
            </div>
          </Card>

          {error && <ErrorState error={error} />}

          {advice && <AdvisorPanel advice={advice} selected={mode} onSelect={setMode} advanced={advanced} />}

          <Card title="Submit">
            <div className="grid gap-4 md:grid-cols-2">
              <Field label="Experiment name" htmlFor="name" hint="Shown in the history and in reports">
                <TextInput id="name" value={name} maxLength={200} placeholder={`${algorithm.displayName} …`} onChange={(e) => setName(e.target.value)} />
              </Field>
              <Field label="Description (optional)" htmlFor="description">
                <TextArea id="description" rows={2} maxLength={2000} value={description} onChange={(e) => setDescription(e.target.value)} className="font-sans" />
              </Field>
              {overriding && (
                <div className="md:col-span-2">
                  <Field label={`Why ${MODE_LABELS[mode]} instead of the recommended ${MODE_LABELS[advice!.recommendedMode!]}?`} htmlFor="override"
                    hint="Recorded with the experiment, so comparisons and reports show that the recommendation was overridden">
                    <TextInput id="override" maxLength={500} value={overrideReason} onChange={(e) => setOverrideReason(e.target.value)} />
                  </Field>
                </div>
              )}
            </div>
            <div className="mt-4 flex items-center gap-3">
              <Button variant="primary" onClick={() => void submit()} busy={busy === 'submit'} disabled={!draft || Object.keys(draft.problems).length > 0}>
                Run with {MODE_LABELS[mode]}
              </Button>
              <span className="text-xs text-slate-500">The task runs in the background; you will see it live.</span>
            </div>
          </Card>
        </div>
      )}
    </>
  );
}
