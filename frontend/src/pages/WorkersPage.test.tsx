import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import workers from '../test/fixtures/workers.json';
import { fakeBackend, FakeEventSource } from '../test/server';
import { renderApp } from '../test/render';

const first = workers[0];

beforeEach(() => {
  vi.stubGlobal('EventSource', FakeEventSource);
  document.cookie = 'XSRF-TOKEN=test-token';
});
afterEach(() => vi.unstubAllGlobals());

function backend(role: 'ADMIN' | 'USER') {
  return fakeBackend({
    'GET /api/v1/auth/session': { authenticated: true, id: 1, username: 'someone', role },
    'GET /api/v1/workers': workers,
    [`GET /api/v1/workers/${first.workerId}`]: first,
    [`GET /api/v1/workers/${first.workerId}/events`]: [],
    [`POST /api/v1/workers/${first.workerId}/deactivate`]: { ...first, status: 'DEACTIVATED' },
  });
}

describe('WorkersPage', () => {
  it('lists the workers with the capabilities they reported', async () => {
    backend('USER');
    renderApp('/workers');
    for (const w of workers) {
      const row = (await screen.findByText(w.workerId, { selector: 'td *, td' })).closest('tr')!;
      expect(within(row).getByText(String(w.cpuCores))).toBeInTheDocument();
    }
    expect(screen.queryByRole('button', { name: /Register a worker/ })).not.toBeInTheDocument();
  });

  it('lets an administrator deactivate a worker with a reason', async () => {
    const { requests } = backend('ADMIN');
    renderApp('/workers');
    await userEvent.click(await screen.findByText(first.workerId, { selector: 'td *, td' }));
    await userEvent.type(await screen.findByLabelText('Reason for deactivation or removal'), 'maintenance');
    await userEvent.click(screen.getByRole('button', { name: /Deactivate/ }));
    const call = requests.find((r) => r.path === `/api/v1/workers/${first.workerId}/deactivate`);
    expect(call?.body).toEqual({ reason: 'maintenance' });
    expect(call?.headers['X-XSRF-TOKEN']).toBe('test-token');
  });
});
