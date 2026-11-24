import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { BatchView, ExperimentSummary, TaskDraft } from '../api/types';
import experiments from '../test/fixtures/experiments.json';
import { fakeBackend, FakeEventSource } from '../test/server';
import { renderApp } from '../test/render';

const session = { authenticated: true, id: 2, username: 'alice', role: 'USER' };
const source = experiments.items[0] as unknown as ExperimentSummary;
const task: TaskDraft = { algorithm: 'matrix.multiply', parameters: { rowsA: 768, colsA: 768, colsB: 768 }, inputs: {},
  execution: { mode: 'SEQUENTIAL_CPU', repetitions: 3 } };
const done = (e: ExperimentSummary, name: string, mode: string): ExperimentSummary => ({ ...e, name, executionMode: mode, status: 'COMPLETED' } as ExperimentSummary);
const batch: BatchView = {
  id: 'batch-1', name: 'Benchmark', owner: 'alice', createdAt: '2026-11-24T10:00:00Z', status: 'COMPLETED', counts: { COMPLETED: 2 },
  experiments: [done({ ...source, id: 'e1' }, 'first', 'SEQUENTIAL_CPU'), done({ ...source, id: 'e2' }, 'second', 'MULTITHREADED_CPU')],
};

beforeEach(() => {
  vi.stubGlobal('EventSource', FakeEventSource);
  document.cookie = 'XSRF-TOKEN=test-token';
});
afterEach(() => vi.unstubAllGlobals());

async function chooseSource() {
  await screen.findByRole('option', { name: `${source.name} (${source.algorithm})` });
  await userEvent.selectOptions(screen.getByLabelText('Start from'), source.id);
}

function backend(running = false) {
  return fakeBackend({
    'GET /api/v1/auth/session': session,
    'GET /api/v1/batches': [batch],
    'GET /api/v1/experiments': experiments,
    'GET /api/v1/templates': [],
    [`GET /api/v1/experiments/${source.id}/task`]: task,
    'POST /api/v1/batches': { status: 202, body: batch },
    'GET /api/v1/batches/batch-1': running ? { ...batch, status: 'RUNNING', counts: { COMPLETED: 1, RUNNING: 1 },
      experiments: [batch.experiments[0], { ...batch.experiments[1], status: 'RUNNING', completedRuns: 0 }] } : batch,
    'POST /api/v1/batches/batch-1/cancel': { status: 202, body: batch },
  });
}

describe('BatchesPage', () => {
  it('builds a benchmark batch from an experiment and submits it in order', async () => {
    const { requests } = backend();
    renderApp('/batches');
    await chooseSource();
    await userEvent.clear(screen.getByLabelText('Thread counts'));
    await userEvent.type(screen.getByLabelText('Thread counts'), '2, 4');
    expect(await screen.findByText('4 experiments, run in this order:')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /Submit the batch/ }));
    await screen.findByRole('heading', { name: 'Benchmark' });
    const body = requests.find((r) => r.method === 'POST' && r.path === '/api/v1/batches')!.body as { name: string; items: { task: TaskDraft }[] };
    expect(body.name).toBe(`Benchmark: ${source.name}`);
    expect(body.items.map((i) => [i.task.execution.mode, i.task.execution.threads ?? null, i.task.execution.repetitions]))
      .toEqual([['SEQUENTIAL_CPU', null, 3], ['MULTITHREADED_CPU', 2, 3], ['MULTITHREADED_CPU', 4, 3], ['DISTRIBUTED_CPU', null, 3]]);
  });

  it('will not submit without a mode', async () => {
    backend();
    renderApp('/batches');
    await chooseSource();
    for (const label of [/Sequential CPU/, /Parallel CPU with threads/, /Distributed CPU on the connected workers/]) {
      await userEvent.click(screen.getByLabelText(label));
    }
    expect(await screen.findByText('Choose at least one mode.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Submit the batch/ })).toBeDisabled();
  });

  it('shows a batch in order, compares its completed experiments and offers their report', async () => {
    backend();
    renderApp('/batches/batch-1');
    const rows = (await screen.findAllByRole('row')).slice(1);
    expect(rows.map((r) => within(r).getAllByRole('cell')[1].textContent)).toEqual(['first', 'second']);
    expect(screen.getByRole('link', { name: /Compare/ })).toHaveAttribute('href', '/analysis?ids=e1,e2');
    expect(within(screen.getByRole('group', { name: 'Download the comparison of this batch' })).getAllByRole('link')[0])
      .toHaveAttribute('href', '/api/v1/analysis/report?format=pdf&ids=e1,e2');
  });

  it('cancels a running batch', async () => {
    const { requests } = backend(true);
    renderApp('/batches/batch-1');
    await userEvent.click(await screen.findByRole('button', { name: /Cancel the batch/ }));
    await waitFor(() => expect(requests.some((r) => r.method === 'POST' && r.path === '/api/v1/batches/batch-1/cancel')).toBe(true));
  });
});
