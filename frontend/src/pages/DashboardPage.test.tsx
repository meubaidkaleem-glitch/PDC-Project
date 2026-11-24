import { act, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import dashboard from '../test/fixtures/dashboard.json';
import { fakeBackend, FakeEventSource } from '../test/server';
import { renderApp } from '../test/render';

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal('EventSource', FakeEventSource);
});
afterEach(() => vi.unstubAllGlobals());

describe('DashboardPage', () => {
  it('shows the figures the backend reports and the state of the live stream', async () => {
    fakeBackend({ 'GET /api/v1/auth/session': { authenticated: true, id: 1, username: 'admin', role: 'ADMIN' }, 'GET /api/v1/dashboard': dashboard });
    renderApp('/');
    await screen.findByRole('heading', { name: 'Overview' });
    const stat = (label: string) => screen.getByText(label, { selector: 'p' }).parentElement!;
    expect(within(stat('Experiments')).getByText(String(dashboard.experiments.total))).toBeInTheDocument();
    expect(within(stat('Connected workers')).getByText(String(dashboard.workers.connected))).toBeInTheDocument();
    for (const e of dashboard.recentExperiments) expect(screen.getByRole('link', { name: e.name })).toBeInTheDocument();
    expect(screen.getAllByText('connecting').length).toBeGreaterThan(0);

    act(() => FakeEventSource.instances[0].open());
    expect(screen.getAllByText('live').length).toBeGreaterThan(0);
  });

  it('reloads when the stream reports a finished experiment', async () => {
    const { requests } = fakeBackend({ 'GET /api/v1/auth/session': { authenticated: true, id: 1, username: 'admin', role: 'ADMIN' }, 'GET /api/v1/dashboard': dashboard });
    renderApp('/');
    await screen.findByRole('heading', { name: 'Overview' });
    const before = requests.filter((r) => r.path === '/api/v1/dashboard').length;
    act(() => FakeEventSource.instances[0].emit('EXPERIMENT_FINISHED', { type: 'EXPERIMENT_FINISHED' }));
    await vi.waitFor(() => expect(requests.filter((r) => r.path === '/api/v1/dashboard').length).toBe(before + 1));
  });

  it('shows the error and offers a retry when the backend fails', async () => {
    fakeBackend({
      'GET /api/v1/auth/session': { authenticated: true, id: 1, username: 'admin', role: 'ADMIN' },
      'GET /api/v1/dashboard': { status: 503, body: { code: 'UNAVAILABLE', detail: 'The database is not reachable' } },
    });
    renderApp('/');
    expect(await screen.findByText('The database is not reachable')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
  });
});
