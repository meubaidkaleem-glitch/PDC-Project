import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { PlugZap, Plus, Power, PowerOff, Trash2 } from 'lucide-react';
import { api, ApiError } from '../api/client';
import { useApi, useEventStream, useThrottled } from '../api/hooks';
import type { ConnectionTest, EventView, Worker, WorkerRegistration } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { Badge, Button, Card, EmptyState, ErrorState, Field, KeyValue, Notice, PageHeader, Spinner, StatusBadge, Table, TextInput } from '../components/ui';
import { formatAge, formatBytes, formatNumber } from '../lib/format';

const WORKER_EVENTS = ['WORKER_REGISTERED', 'WORKER_STATUS_CHANGED', 'WORKER_DISCONNECTED', 'WORKER_REJECTED', 'WORKER_REMOVED',
  'WORKER_RECORDED', 'ATTEMPT_ASSIGNED', 'ATTEMPT_SUCCEEDED', 'ATTEMPT_FAILED'];

export function WorkersPage() {
  const { isAdmin } = useAuth();
  const [search, setSearch] = useSearchParams();
  const workers = useApi<Worker[]>('/api/v1/workers');
  const refresh = useThrottled(workers.reload, 1000);
  const stream = useEventStream('/api/v1/events/stream', WORKER_EVENTS, refresh);
  const [registering, setRegistering] = useState(false);
  const selectedId = search.get('selected');
  const select = (id: string | null) => setSearch(id ? { selected: id } : {});

  return (
    <>
      <PageHeader title="Workers" description="Worker processes connect to this application's coordinator. Capabilities are those each worker reported; nothing is filled in."
        actions={<>
          <Badge tone={stream === 'open' ? 'green' : 'amber'}>{stream === 'open' ? 'live' : stream}</Badge>
          {isAdmin && <Button onClick={() => setRegistering(true)}><Plus className="h-4 w-4" aria-hidden />Register a worker</Button>}
        </>} />
      {registering && <RegisterWorker onClose={() => { setRegistering(false); workers.reload(); }} />}
      {workers.error && <ErrorState error={workers.error} onRetry={workers.reload} />}
      {workers.loading && !workers.data && <Spinner label="Loading workers" />}
      {workers.data && workers.data.length === 0 && (
        <Card><EmptyState title="No worker has connected yet">
          Start a worker process with the coordinator's address and the cluster secret. {isAdmin ? 'Use “Register a worker” for the exact command.' : 'Ask an administrator for the command.'} Local runs (sequential and parallel) work without workers.
        </EmptyState></Card>
      )}
      {workers.data && workers.data.length > 0 && (
        <div className="grid gap-4 xl:grid-cols-[1fr_26rem]">
          <Card>
            <Table>
              <thead><tr><th>Worker</th><th>Status</th><th>Cores</th><th>Memory</th><th>Slots</th><th>Heartbeat</th><th>Done</th><th>Failed</th></tr></thead>
              <tbody>{workers.data.map((w) => (
                <tr key={w.workerId} onClick={() => select(w.workerId)} className={`cursor-pointer ${selectedId === w.workerId ? 'bg-blue-50/60 dark:bg-blue-950/40' : 'hover:bg-slate-50 dark:hover:bg-slate-800/50'}`}>
                  <td><div className="font-mono text-xs font-medium">{w.workerId}</div><div className="text-xs text-slate-500">{w.hostname ?? '—'}</div></td>
                  <td><StatusBadge status={w.status} />{w.faultSimulation && <Badge tone="amber">fault simulation</Badge>}</td>
                  <td className="tabular-nums">{w.cpuCores ?? '—'}</td>
                  <td className="tabular-nums">{formatBytes(w.maxHeapBytes)}</td>
                  <td className="tabular-nums">{w.slots !== null ? `${w.assignedTasks ?? 0} / ${w.slots}` : '—'}</td>
                  <td className="text-xs">{w.connected ? formatAge(w.lastHeartbeatAt) : '—'}</td>
                  <td className="tabular-nums">{w.completedTasks}</td>
                  <td className="tabular-nums">{w.failedTasks}</td>
                </tr>))}</tbody>
            </Table>
          </Card>
          {selectedId ? <WorkerDetail id={selectedId} admin={isAdmin} onChanged={workers.reload} onRemoved={() => { select(null); workers.reload(); }} />
            : <Card><EmptyState title="Select a worker">Its capabilities, load, counters and event history appear here.</EmptyState></Card>}
        </div>
      )}
    </>
  );
}

function WorkerDetail({ id, admin, onChanged, onRemoved }: { id: string; admin: boolean; onChanged: () => void; onRemoved: () => void }) {
  const worker = useApi<Worker>(`/api/v1/workers/${id}`);
  const events = useApi<EventView[]>(`/api/v1/workers/${id}/events?limit=50`);
  const [test, setTest] = useState<ConnectionTest>();
  const [registration, setRegistration] = useState<WorkerRegistration>();
  const [error, setError] = useState<ApiError>();
  const [reason, setReason] = useState('');

  async function run(action: () => Promise<unknown>) {
    setError(undefined);
    try {
      await action();
      worker.reload();
      events.reload();
      onChanged();
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e)));
    }
  }

  if (worker.loading && !worker.data) return <Card><Spinner /></Card>;
  if (worker.error) return <Card><ErrorState error={worker.error} /></Card>;
  const w = worker.data!;
  return (
    <Card title={<span className="font-mono">{w.workerId}</span>} subtitle={w.statusReason ?? undefined} actions={<StatusBadge status={w.status} />}>
      {error && <div className="mb-3"><ErrorState error={error} /></div>}
      <KeyValue items={[
        ['Host', `${w.hostname ?? '—'} (${w.endpoint ?? 'not connected'})`],
        ['Processors', w.cpuCores ?? '—'],
        ['Heap / physical memory', `${formatBytes(w.maxHeapBytes)} / ${formatBytes(w.physicalMemoryBytes)}`],
        ['System', `${w.os ?? '—'}, Java ${w.javaVersion ?? '—'}`],
        ['Versions', `Distributed ${w.runtimeVersion ?? '—'}, Core ${w.coreVersion ?? '—'}`],
        ['GPU', w.gpuAvailable ? w.gpuDetails.join(', ') : `none (${w.gpuDetails.join('; ') || 'not reported'})`],
        ['Slots', w.slots !== null ? `${w.assignedTasks ?? 0} assigned of ${w.slots}` : '—'],
        ['Load', w.connected ? `${w.activeTasks ?? 0} running, CPU ${w.processCpuLoad !== null && w.processCpuLoad >= 0 ? `${Math.round(w.processCpuLoad * 100)} %` : '—'}, free heap ${formatBytes(w.freeMemoryBytes)}` : '—'],
        ['Last heartbeat', formatAge(w.lastHeartbeatAt)],
        ['First registered', w.registeredAt ? new Date(w.registeredAt).toLocaleString() : 'never connected'],
        ['Approved', w.approved ? 'yes' : 'no'],
        ['Algorithms', w.algorithms.length ? w.algorithms.join(', ') : '—'],
        ['Labels', Object.entries(w.labels).map(([k, v]) => `${k}=${v}`).join(', ') || '—'],
        ['Counters', Object.entries(w.counters).map(([k, v]) => `${k} ${v}`).join(', ') || '—'],
      ]} />
      {w.performance.length > 0 && (
        <div className="mt-3 text-xs text-slate-600 dark:text-slate-400">
          <p className="font-medium">Observed performance</p>
          {w.performance.map((p) => <p key={p.algorithm}>{p.algorithm}: {formatNumber(p.operationsPerSecond)} ops/s, {formatBytes(p.transferBytesPerSecond)}/s transfer ({p.samples} attempts)</p>)}
        </div>
      )}
      <div className="mt-4 flex flex-wrap gap-2">
        <Button onClick={() => void run(async () => setTest(await api<ConnectionTest>(`/api/v1/workers/${id}/test`, { method: 'POST' })))}>
          <PlugZap className="h-4 w-4" aria-hidden />Test connection
        </Button>
        {admin && (w.deactivated || w.status === 'DEACTIVATED' ? (
          <Button onClick={() => void run(() => api(`/api/v1/workers/${id}/activate`, { method: 'POST' }))}><Power className="h-4 w-4" aria-hidden />Activate</Button>
        ) : (
          <Button onClick={() => void run(() => api(`/api/v1/workers/${id}/deactivate`, { body: { reason: reason || null } }))}><PowerOff className="h-4 w-4" aria-hidden />Deactivate</Button>
        ))}
        {admin && !w.approved && <Button onClick={() => void run(() => api(`/api/v1/workers/${id}/approve`, { method: 'POST' }))}>Approve</Button>}
        {admin && <Button variant="ghost" onClick={() => void run(async () => setRegistration(await api<WorkerRegistration>(`/api/v1/workers/${id}/registration`)))}>Start instructions</Button>}
        {admin && <Button variant="danger" onClick={() => {
          if (window.confirm(`Remove ${id}? A connected worker is told to stop; its running work is retried elsewhere.`)) {
            void api(`/api/v1/workers/${id}?reason=${encodeURIComponent(reason || 'removed in the user interface')}`, { method: 'DELETE' }).then(onRemoved, (e) => setError(e));
          }
        }}><Trash2 className="h-4 w-4" aria-hidden />Remove</Button>}
      </div>
      {admin && <div className="mt-2"><TextInput aria-label="Reason for deactivation or removal" placeholder="Reason (recorded in the status and audit log)" value={reason} onChange={(e) => setReason(e.target.value)} /></div>}
      {test && <div className="mt-3"><Notice tone={test.verdict === 'OK' ? 'blue' : 'amber'}><span className="font-medium">{test.verdict}:</span> {test.explanation}</Notice></div>}
      {registration && <Instructions registration={registration} />}
      <div className="mt-4">
        <p className="mb-1 text-sm font-medium">Event history</p>
        {events.data && events.data.length === 0 && <p className="text-sm text-slate-500">No events stored for this worker.</p>}
        <ul className="max-h-64 space-y-1 overflow-auto text-xs">
          {events.data?.map((e) => <li key={e.id ?? e.occurredAt}><span className="text-slate-500">{new Date(e.occurredAt).toLocaleString()}</span> {e.message}</li>)}
        </ul>
      </div>
    </Card>
  );
}

function RegisterWorker({ onClose }: { onClose: () => void }) {
  const [workerId, setWorkerId] = useState('');
  const [notes, setNotes] = useState('');
  const [registration, setRegistration] = useState<WorkerRegistration>();
  const [error, setError] = useState<ApiError>();
  return (
    <div className="mb-4">
      <Card title="Register a worker" actions={<Button variant="ghost" onClick={onClose}>Close</Button>}>
        {registration ? <Instructions registration={registration} /> : (
          <form className="grid gap-3 md:grid-cols-3" onSubmit={async (e) => {
            e.preventDefault();
            try {
              setRegistration(await api<WorkerRegistration>('/api/v1/workers/register', { body: { workerId, notes: notes || null } }));
            } catch (err) {
              setError(err instanceof ApiError ? err : new ApiError(0, 'UNKNOWN', String(err)));
            }
          }}>
            <Field label="Worker id" htmlFor="reg-id" hint="Letters, digits, . _ : - (the worker's --id)"><TextInput id="reg-id" required value={workerId} onChange={(e) => setWorkerId(e.target.value)} /></Field>
            <Field label="Notes" htmlFor="reg-notes"><TextInput id="reg-notes" value={notes} onChange={(e) => setNotes(e.target.value)} /></Field>
            <div className="flex items-end"><Button type="submit" variant="primary">Register and show the command</Button></div>
            {error && <div className="md:col-span-3"><ErrorState error={error} /></div>}
          </form>
        )}
      </Card>
    </div>
  );
}

function Instructions({ registration }: { registration: WorkerRegistration }) {
  return (
    <div className="mt-3 space-y-2 text-sm">
      <p>Run on the worker machine (Java 21 and the Distributed jar required):</p>
      <pre className="overflow-x-auto rounded bg-slate-900 p-3 text-xs text-slate-100">{registration.commandLine}</pre>
      <p>Or put this in a properties file and use <span className="font-mono">--config</span>:</p>
      <pre className="overflow-x-auto rounded bg-slate-100 p-3 text-xs dark:bg-slate-950">{registration.configFile}</pre>
      <Notice tone="gray">{registration.secretInstructions} The secret is never shown here.</Notice>
      {registration.approvalRequired && <Notice tone="amber">This installation requires approval: the worker stays deactivated until an administrator approves it.</Notice>}
    </div>
  );
}
