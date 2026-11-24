import { useState } from 'react';
import type { FormEvent } from 'react';
import { api, ApiError } from '../api/client';
import { useApi } from '../api/hooks';
import { useAuth } from '../auth/AuthContext';
import { Badge, Button, Card, ErrorState, Field, KeyValue, PageHeader, SelectInput, Spinner, Table, TextInput } from '../components/ui';
import { formatBytes, formatDate, formatNumber } from '../lib/format';
import { POLICIES } from '../lib/labels';
import { usePreferences } from '../lib/preferences';

interface Settings {
  schedulingPolicy: string;
  availablePolicies: string[];
  schedulingPolicyChangedBy?: string;
  schedulingPolicyChangedAt?: string;
  limits: Record<string, number>;
  server?: Record<string, string | number | boolean>;
}

interface UserView {
  id: number;
  username: string;
  role: 'ADMIN' | 'USER';
  enabled: boolean;
  createdAt: string;
}

interface Versions {
  application: string;
  core: string;
  distributed: string;
  protocol: string;
}

const LIMIT_LABELS: Record<string, [string, (v: number) => string]> = {
  maxActiveExperimentsPerUser: ['Active experiments or batches per user', String],
  submissionsPerMinute: ['Submissions per minute', String],
  maxRepetitions: ['Repetitions per experiment', String],
  maxBatchSize: ['Experiments per batch', String],
  maxEstimatedOperations: ['Largest estimated work', (v) => `${formatNumber(v)} operations`],
  maxEstimatedMemoryBytes: ['Largest estimated memory', (v) => formatBytes(v)],
  maxRunDurationSeconds: ['Longest run', (v) => `${Math.round(v / 60)} min`],
  maxUploadBytes: ['Largest upload', (v) => formatBytes(v)],
  maxResultBytes: ['Largest stored result value', (v) => formatBytes(v)],
};

export function SettingsPage() {
  const { isAdmin } = useAuth();
  const settings = useApi<Settings>('/api/v1/settings');
  const versions = useApi<Versions>('/api/v1/system/info');
  return (
    <>
      <PageHeader title="Settings" description="Your account and interface; the cluster's defaults and the server's limits. Secrets are never shown here." />
      <div className="grid gap-4 lg:grid-cols-2">
        <PasswordCard />
        <InterfaceCard />
        {settings.error && <ErrorState error={settings.error} onRetry={settings.reload} />}
        {settings.loading && !settings.data && <Spinner />}
        {settings.data && <ClusterCard settings={settings.data} admin={isAdmin} onSaved={settings.reload} />}
        {settings.data && (
          <Card title="Limits" subtitle="Set in the server's configuration (docs/configuration.md)">
            <KeyValue items={Object.entries(settings.data.limits).map(([k, v]) => [LIMIT_LABELS[k]?.[0] ?? k, LIMIT_LABELS[k] ? LIMIT_LABELS[k][1](v) : String(v)])} />
          </Card>
        )}
        {settings.data?.server && (
          <Card title="Server" subtitle="Read-only; change it in the configuration and restart">
            <KeyValue items={Object.entries(settings.data.server).map(([k, v]) => [k, typeof v === 'boolean' ? (v ? 'yes' : 'no') : String(v)])} />
          </Card>
        )}
        {versions.data && (
          <Card title="Versions">
            <KeyValue items={[['Application', versions.data.application], ['DistriCompute-Core', versions.data.core],
              ['DistriCompute-Distributed', versions.data.distributed], ['Worker protocol', versions.data.protocol]]} />
          </Card>
        )}
      </div>
      {isAdmin && <div className="mt-4"><UsersCard /></div>}
    </>
  );
}

function PasswordCard() {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [repeat, setRepeat] = useState('');
  const [error, setError] = useState<ApiError>();
  const [done, setDone] = useState(false);
  const mismatch = repeat.length > 0 && next !== repeat;

  async function submit(e: FormEvent) {
    e.preventDefault();
    setError(undefined);
    setDone(false);
    try {
      await api('/api/v1/auth/password', { body: { currentPassword: current, newPassword: next } });
      setDone(true);
      setCurrent('');
      setNext('');
      setRepeat('');
    } catch (err) {
      setError(err instanceof ApiError ? err : new ApiError(0, 'UNKNOWN', String(err)));
    }
  }

  return (
    <Card title="Password">
      <form onSubmit={(e) => void submit(e)} className="space-y-3">
        <Field label="Current password" htmlFor="current-password">
          <TextInput id="current-password" type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
        </Field>
        <Field label="New password" htmlFor="new-password" hint="12 to 128 characters">
          <TextInput id="new-password" type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
        </Field>
        <Field label="New password again" htmlFor="repeat-password" error={mismatch ? 'The passwords differ' : undefined}>
          <TextInput id="repeat-password" type="password" autoComplete="new-password" value={repeat} onChange={(e) => setRepeat(e.target.value)} />
        </Field>
        {error && <ErrorState error={error} />}
        {done && <p role="status" className="text-sm text-emerald-700 dark:text-emerald-400">Your password was changed.</p>}
        <Button type="submit" variant="primary" disabled={!current || next.length < 12 || next !== repeat}>Change the password</Button>
      </form>
    </Card>
  );
}

function InterfaceCard() {
  const { theme, setTheme, level, setLevel } = usePreferences();
  return (
    <Card title="Interface" subtitle="Kept in this browser">
      <div className="space-y-4 text-sm">
        <fieldset>
          <legend className="mb-1 font-medium">Theme</legend>
          {(['system', 'light', 'dark'] as const).map((t) => (
            <label key={t} className="mr-4 inline-flex items-center gap-1.5"><input type="radio" name="theme" checked={theme === t} onChange={() => setTheme(t)} />{t === 'system' ? 'Follow the system' : t === 'light' ? 'Light' : 'Dark'}</label>
          ))}
        </fieldset>
        <fieldset>
          <legend className="mb-1 font-medium">Level</legend>
          <label className="mr-4 inline-flex items-center gap-1.5"><input type="radio" name="level" checked={level === 'beginner'} onChange={() => setLevel('beginner')} />Beginner: recommended settings, explanations</label>
          <label className="inline-flex items-center gap-1.5"><input type="radio" name="level" checked={level === 'advanced'} onChange={() => setLevel('advanced')} />Advanced: every setting</label>
        </fieldset>
      </div>
    </Card>
  );
}

function ClusterCard({ settings, admin, onSaved }: { settings: Settings; admin: boolean; onSaved: () => void }) {
  const [policy, setPolicy] = useState(settings.schedulingPolicy);
  const [error, setError] = useState<ApiError>();
  const label = (id: string) => POLICIES.find((p) => p.id === id)?.label ?? id;

  async function save() {
    setError(undefined);
    try {
      await api('/api/v1/settings', { method: 'PATCH', body: { schedulingPolicy: policy } });
      onSaved();
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e)));
    }
  }

  return (
    <Card title="Cluster" subtitle="Used by distributed runs that do not choose a policy">
      {admin ? (
        <div className="flex flex-wrap items-end gap-2">
          <Field label="Default scheduling policy" htmlFor="default-policy" hint={POLICIES.find((p) => p.id === policy)?.description}>
            <SelectInput id="default-policy" value={policy} onChange={(e) => setPolicy(e.target.value)}>
              {settings.availablePolicies.map((p) => <option key={p} value={p}>{label(p)}</option>)}
            </SelectInput>
          </Field>
          <Button onClick={() => void save()} disabled={policy === settings.schedulingPolicy}>Save</Button>
        </div>
      ) : (
        <KeyValue items={[['Default scheduling policy', label(settings.schedulingPolicy)]]} />
      )}
      {settings.schedulingPolicyChangedBy && (
        <p className="mt-2 text-xs text-slate-500">Changed by {settings.schedulingPolicyChangedBy} on {formatDate(settings.schedulingPolicyChangedAt)}.</p>
      )}
      {error && <div className="mt-2"><ErrorState error={error} /></div>}
    </Card>
  );
}

function UsersCard() {
  const { session } = useAuth();
  const users = useApi<UserView[]>('/api/v1/users');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [role, setRole] = useState<'USER' | 'ADMIN'>('USER');
  const [error, setError] = useState<ApiError>();

  async function run(action: () => Promise<unknown>) {
    setError(undefined);
    try {
      await action();
      users.reload();
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', String(e)));
    }
  }

  return (
    <Card title="Users" subtitle="Administrators manage workers, settings and the fault lab and see every experiment">
      {users.data && (
        <Table>
          <thead><tr><th>User</th><th>Role</th><th>Status</th><th>Created</th><th><span className="sr-only">Actions</span></th></tr></thead>
          <tbody>
            {users.data.map((u) => {
              const self = u.username === session?.username;
              return (
                <tr key={u.id}>
                  <td className="font-medium">{u.username}{self && <span className="ml-2"><Badge>you</Badge></span>}</td>
                  <td>
                    <SelectInput aria-label={`Role of ${u.username}`} value={u.role} disabled={self} className="w-32"
                      onChange={(e) => void run(() => api(`/api/v1/users/${u.id}`, { method: 'PATCH', body: { role: e.target.value } }))}>
                      <option value="USER">user</option>
                      <option value="ADMIN">administrator</option>
                    </SelectInput>
                  </td>
                  <td><Badge tone={u.enabled ? 'green' : 'gray'}>{u.enabled ? 'enabled' : 'disabled'}</Badge></td>
                  <td className="whitespace-nowrap text-xs">{formatDate(u.createdAt)}</td>
                  <td>{!self && (
                    <Button variant="ghost" onClick={() => void run(() => api(`/api/v1/users/${u.id}`, { method: 'PATCH', body: { enabled: !u.enabled } }))}>
                      {u.enabled ? 'Disable' : 'Enable'}
                    </Button>
                  )}</td>
                </tr>
              );
            })}
          </tbody>
        </Table>
      )}
      <form className="mt-4 grid gap-3 md:grid-cols-4" onSubmit={(e) => {
        e.preventDefault();
        void run(async () => {
          await api('/api/v1/users', { body: { username, password, role } });
          setUsername('');
          setPassword('');
        });
      }}>
        <Field label="New user" htmlFor="new-username"><TextInput id="new-username" autoComplete="off" value={username} onChange={(e) => setUsername(e.target.value)} /></Field>
        <Field label="Initial password" htmlFor="new-user-password" hint="12 to 128 characters"><TextInput id="new-user-password" type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} /></Field>
        <Field label="Role" htmlFor="new-user-role">
          <SelectInput id="new-user-role" value={role} onChange={(e) => setRole(e.target.value as 'USER' | 'ADMIN')}>
            <option value="USER">user</option>
            <option value="ADMIN">administrator</option>
          </SelectInput>
        </Field>
        <div className="flex items-end"><Button type="submit" variant="primary" disabled={!username.trim() || password.length < 12}>Create the user</Button></div>
      </form>
      {error && <div className="mt-3"><ErrorState error={error} /></div>}
    </Card>
  );
}
