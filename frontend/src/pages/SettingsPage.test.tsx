import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fakeBackend, FakeEventSource } from '../test/server';
import { renderApp } from '../test/render';

const limits = { maxActiveExperimentsPerUser: 5, submissionsPerMinute: 30, maxRepetitions: 20, maxBatchSize: 50,
  maxEstimatedOperations: 1e13, maxEstimatedMemoryBytes: 2147483648, maxRunDurationSeconds: 3600, maxUploadBytes: 67108864,
  maxResultBytes: 536870912 };
const settings = { schedulingPolicy: 'LEAST_LOADED', availablePolicies: ['ROUND_ROBIN', 'LEAST_LOADED', 'DYNAMIC'], limits };

beforeEach(() => {
  vi.stubGlobal('EventSource', FakeEventSource);
  document.cookie = 'XSRF-TOKEN=test-token';
});
afterEach(() => vi.unstubAllGlobals());

function backend(role: 'ADMIN' | 'USER') {
  return fakeBackend({
    'GET /api/v1/auth/session': { authenticated: true, id: 1, username: 'root', role },
    'GET /api/v1/settings': role === 'ADMIN' ? { ...settings, server: { workerPort: 7650, faultLabEnabled: false } } : settings,
    'PATCH /api/v1/settings': (r) => ({ ...settings, schedulingPolicy: (r.body as { schedulingPolicy: string }).schedulingPolicy }),
    'GET /api/v1/system/info': { application: '0.1.0', core: '0.1.0', distributed: '0.1.0', protocol: '0.1' },
    'POST /api/v1/auth/password': (r) => ((r.body as { currentPassword: string }).currentPassword === 'old-password-123'
      ? { status: 204, body: undefined } : { status: 400, body: { code: 'WRONG_PASSWORD', detail: 'The current password is not correct' } }),
    'GET /api/v1/users': [{ id: 1, username: 'root', role: 'ADMIN', enabled: true, createdAt: '2026-11-24T10:00:00Z' },
      { id: 2, username: 'alice', role: 'USER', enabled: true, createdAt: '2026-11-24T10:00:00Z' }],
    'POST /api/v1/users': { status: 201, body: { id: 3, username: 'bob', role: 'USER', enabled: true, createdAt: '2026-11-24T11:00:00Z' } },
    'PATCH /api/v1/users/2': { id: 2, username: 'alice', role: 'USER', enabled: false, createdAt: '2026-11-24T10:00:00Z' },
  });
}

describe('SettingsPage', () => {
  it('changes the password with the current one', async () => {
    const { requests } = backend('USER');
    renderApp('/settings');
    await userEvent.type(await screen.findByLabelText('Current password'), 'wrong-password');
    await userEvent.type(screen.getByLabelText('New password'), 'a-new-password-1');
    await userEvent.type(screen.getByLabelText('New password again'), 'a-new-password-1');
    await userEvent.click(screen.getByRole('button', { name: 'Change the password' }));
    expect(await screen.findByText('The current password is not correct')).toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText('Current password'));
    await userEvent.type(screen.getByLabelText('Current password'), 'old-password-123');
    await userEvent.click(screen.getByRole('button', { name: 'Change the password' }));
    expect(await screen.findByText('Your password was changed.')).toBeInTheDocument();
    expect(requests.filter((r) => r.path === '/api/v1/auth/password').at(-1)?.body)
      .toEqual({ currentPassword: 'old-password-123', newPassword: 'a-new-password-1' });
  });

  it('shows users the limits but neither the server configuration nor administration', async () => {
    backend('USER');
    renderApp('/settings');
    expect(await screen.findByText('Experiments per batch')).toBeInTheDocument();
    expect(screen.getByText('Least loaded')).toBeInTheDocument();
    expect(screen.queryByLabelText('Default scheduling policy')).not.toBeInTheDocument();
    expect(screen.queryByText('Server')).not.toBeInTheDocument();
    expect(screen.queryByText('Users')).not.toBeInTheDocument();
  });

  it('lets an administrator change the default policy and manage users', async () => {
    const { requests } = backend('ADMIN');
    renderApp('/settings');
    await userEvent.selectOptions(await screen.findByLabelText('Default scheduling policy'), 'ROUND_ROBIN');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));
    await waitFor(() => expect(requests.find((r) => r.method === 'PATCH' && r.path === '/api/v1/settings')?.body)
      .toEqual({ schedulingPolicy: 'ROUND_ROBIN' }));

    const self = (await screen.findByText('root', { selector: 'td' })).closest('tr')!;
    expect(within(self).getByLabelText('Role of root')).toBeDisabled();
    expect(within(self).queryByRole('button', { name: 'Disable' })).not.toBeInTheDocument();
    const alice = screen.getByText('alice', { selector: 'td' }).closest('tr')!;
    await userEvent.click(within(alice).getByRole('button', { name: 'Disable' }));
    expect(requests.find((r) => r.method === 'PATCH' && r.path === '/api/v1/users/2')?.body).toEqual({ enabled: false });

    await userEvent.type(screen.getByLabelText('New user'), 'bob');
    await userEvent.type(screen.getByLabelText('Initial password'), 'bob-password-123');
    await userEvent.click(screen.getByRole('button', { name: 'Create the user' }));
    await waitFor(() => expect(requests.find((r) => r.method === 'POST' && r.path === '/api/v1/users')?.body)
      .toEqual({ username: 'bob', password: 'bob-password-123', role: 'USER' }));
  });
});
