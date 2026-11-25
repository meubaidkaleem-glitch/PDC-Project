import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import algorithms from '../test/fixtures/algorithms.json';
import events from '../test/fixtures/events.json';
import experiment from '../test/fixtures/experiment.json';
import live from '../test/fixtures/live.json';
import { fakeBackend, FakeEventSource } from '../test/server';
import { renderApp } from '../test/render';

const id = experiment.summary.id;
const session = { authenticated: true, id: 1, username: 'admin', role: 'ADMIN' };

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal('EventSource', FakeEventSource);
  document.cookie = 'XSRF-TOKEN=test-token';
});
afterEach(() => vi.unstubAllGlobals());

function backend(detail: object = experiment) {
  return fakeBackend({
    'GET /api/v1/auth/session': session,
    [`GET /api/v1/experiments/${id}`]: detail,
    [`GET /api/v1/experiments/${id}/live`]: live,
    [`GET /api/v1/experiments/${id}/events`]: events,
    'GET /api/v1/algorithms/matrix.multiply': algorithms.find((a) => a.id === 'matrix.multiply')!,
  });
}

describe('ExperimentPage', () => {
  it('shows the stored outcome of every run', async () => {
    backend();
    renderApp(`/experiments/${id}`);
    expect(await screen.findByRole('heading', { name: experiment.summary.name })).toBeInTheDocument();
    expect(screen.getByText(`${experiment.summary.completedRuns} / ${experiment.summary.repetitions}`)).toBeInTheDocument();
    expect(screen.getByRole('img', { name: /Assignment timeline of \d+ attempts on 2 workers/ })).toBeInTheDocument();
    expect(FakeEventSource.instances).toHaveLength(0);

    await userEvent.click(screen.getByRole('tab', { name: /Results/ }));
    expect(screen.getAllByText('verified').length).toBe(experiment.results.length);
    expect(screen.getAllByRole('link', { name: /Download CSV/ })[0]).toHaveAttribute('href', expect.stringContaining(`/api/v1/experiments/${id}/runs/1/result/download`));

    await userEvent.click(screen.getByRole('tab', { name: 'Metrics' }));
    const wall = screen.getByRole('row', { name: /Wall time application/ });
    expect(within(wall).getAllByText(/\d+ ms|\d+(\.\d+)? s/).length).toBeGreaterThan(0);

    await userEvent.click(screen.getByRole('tab', { name: 'Configuration' }));
    expect(screen.getByText('Repetitions')).toBeInTheDocument();
    expect(screen.getAllByText('(Core default)').length).toBeGreaterThan(0);
  });

  it('follows the live stream of a running experiment: new events appear and finished runs reload it', async () => {
    const { requests } = backend({ ...experiment, summary: { ...experiment.summary, status: 'RUNNING' } });
    renderApp(`/experiments/${id}`);
    await screen.findByRole('heading', { name: experiment.summary.name });
    // The page opens the stream in an effect, possibly after the heading appeared: wait for it.
    const stream = await vi.waitFor(() => {
      const opened = FakeEventSource.instances.find((s) => s.url.includes(`/experiments/${id}/stream`));
      expect(opened).toBeDefined();
      return opened!;
    });
    const loads = () => requests.filter((r) => r.path === `/api/v1/experiments/${id}`).length;
    const before = loads();
    act(() => {
      stream.open();
      stream.emit('RUN_FINISHED', { id: 999_999, type: 'RUN_FINISHED', severity: 'INFO', message: 'Run 4 COMPLETED in 0.700 s',
        occurredAt: new Date().toISOString(), experimentId: id, workerId: null, simulated: false, details: {} });
    });
    await vi.waitFor(() => expect(loads()).toBeGreaterThan(before));
    await userEvent.click(screen.getByRole('tab', { name: /Events/ }));
    expect(screen.getByText('Run 4 COMPLETED in 0.700 s')).toBeInTheDocument();
  });

  it('cancels a running experiment through the API', async () => {
    const running = { ...experiment, summary: { ...experiment.summary, status: 'RUNNING' } };
    const { requests } = fakeBackend({
      'GET /api/v1/auth/session': session,
      [`GET /api/v1/experiments/${id}`]: running,
      [`GET /api/v1/experiments/${id}/live`]: live,
      [`GET /api/v1/experiments/${id}/events`]: events,
      [`POST /api/v1/experiments/${id}/cancel`]: { status: 202, body: running },
    });
    renderApp(`/experiments/${id}`);
    await userEvent.click(await screen.findByRole('button', { name: /Cancel/ }));
    expect(requests.some((r) => r.method === 'POST' && r.path === `/api/v1/experiments/${id}/cancel`)).toBe(true);
  });

  it('reports an experiment that does not exist or is not visible', async () => {
    backend({ status: 404, body: { code: 'NOT_FOUND', detail: 'Experiment not found' } });
    renderApp(`/experiments/${id}`);
    expect(await screen.findByText('Experiment not found')).toBeInTheDocument();
  });
});
