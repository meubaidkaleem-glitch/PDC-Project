import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { FaultLabStatus, LabRun } from '../api/types';
import labEvents from '../test/fixtures/lab-events.json';
import recordedRun from '../test/fixtures/lab-run.json';
import recordedStatus from '../test/fixtures/fault-lab.json';
import { fakeBackend, FakeEventSource } from '../test/server';
import { renderApp } from '../test/render';

const status = recordedStatus as unknown as FaultLabStatus;
const run = recordedRun as unknown as LabRun;
const admin = { authenticated: true, id: 1, username: 'admin', role: 'ADMIN' };

beforeEach(() => {
  vi.stubGlobal('EventSource', FakeEventSource);
  document.cookie = 'XSRF-TOKEN=test-token';
  localStorage.clear();
});
afterEach(() => vi.unstubAllGlobals());

function backend(overrides: Partial<FaultLabStatus> = {}) {
  return fakeBackend({
    'GET /api/v1/auth/session': admin,
    'GET /api/v1/fault-lab': { ...status, ...overrides },
    'GET /api/v1/fault-lab/runs': [run],
    'POST /api/v1/fault-lab/runs': { status: 202, body: run },
    [`GET /api/v1/fault-lab/runs/${run.id}`]: run,
    [`GET /api/v1/fault-lab/runs/${run.id}/events`]: labEvents,
  });
}

describe('FaultLabPage', () => {
  it('says that everything is simulated and runs a scenario', async () => {
    const { requests } = backend();
    renderApp('/fault-lab');
    expect(await screen.findByText('Everything on this page is simulated.')).toBeInTheDocument();
    for (const s of status.scenarios) expect(screen.getByRole('heading', { name: s.title })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Run Corrupted results' }));
    expect(requests.find((r) => r.method === 'POST')?.body).toEqual({ scenario: 'CORRUPTED_RESULTS' });
    expect(await screen.findByText(run.outcome!.verdictText)).toBeInTheDocument();
  });

  it('shows how the fault was detected and how the task recovered', async () => {
    backend();
    renderApp(`/fault-lab?run=${run.id}`);
    const o = run.outcome!;
    expect(await screen.findByText(o.verdictText)).toBeInTheDocument();
    expect(screen.getAllByText(o.detection!.message).length).toBeGreaterThanOrEqual(2); // detection and timeline
    expect(screen.getByText(`${o.recovery.recoveredPartitions} of ${o.task.partitions}`)).toBeInTheDocument();
    expect(screen.getByText('yes (equal digests)')).toBeInTheDocument();
    const faultyRow = screen.getByText(o.faultyWorker).closest('tr')!;
    expect(within(faultyRow).getByText('faulty')).toBeInTheDocument();
    const timeline = screen.getByRole('heading', { name: /Event timeline/ });
    expect(within(timeline).getByText('simulated')).toBeInTheDocument();
    await waitFor(() => expect(screen.getAllByRole('row').length).toBeGreaterThan(labEvents.length));
  });

  it('cannot run anything while the lab is disabled or busy', async () => {
    backend({ enabled: false });
    renderApp('/fault-lab');
    expect(await screen.findByText(/The fault lab is disabled on this server/)).toBeInTheDocument();
    for (const s of status.scenarios) expect(screen.getByRole('button', { name: `Run ${s.title}` })).toBeDisabled();
  });

  it('sends a chosen seed in advanced mode', async () => {
    localStorage.setItem('districompute.level', 'advanced');
    const { requests } = backend();
    renderApp('/fault-lab');
    await userEvent.type(await screen.findByLabelText('Seed'), '42');
    await userEvent.click(screen.getByRole('button', { name: 'Run Slow worker' }));
    expect(requests.find((r) => r.method === 'POST')?.body).toEqual({ scenario: 'SLOW_WORKER', seed: 42 });
  });
});
