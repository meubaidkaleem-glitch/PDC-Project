import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import advice from '../test/fixtures/advice.json';
import algorithms from '../test/fixtures/algorithms.json';
import categories from '../test/fixtures/categories.json';
import experiment from '../test/fixtures/experiment.json';
import live from '../test/fixtures/live.json';
import { fakeBackend, FakeEventSource } from '../test/server';
import { renderApp } from '../test/render';

const session = { authenticated: true, id: 2, username: 'alice', role: 'USER' };
const created = { ...experiment, summary: { ...experiment.summary, id: 'new-experiment' } };

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal('EventSource', FakeEventSource);
  document.cookie = 'XSRF-TOKEN=test-token';
  localStorage.clear();
});
afterEach(() => vi.unstubAllGlobals());

const template = { id: 1, name: 'mm small', description: null, algorithm: 'matrix.multiply', executionMode: 'DISTRIBUTED_CPU',
  createdAt: '2026-11-24T10:00:00Z', task: { algorithm: 'matrix.multiply', parameters: { rowsA: 32, colsA: 32, colsB: 32 }, inputs: {},
    execution: { mode: 'DISTRIBUTED_CPU' } } };

function backend(analyze: object = advice) {
  return fakeBackend({
    'GET /api/v1/templates': [template],
    'POST /api/v1/templates': (r) => ({ status: 201, body: { ...template, id: 2, name: (r.body as { name: string }).name } }),
    'GET /api/v1/auth/session': session,
    'GET /api/v1/categories': categories,
    'GET /api/v1/algorithms': algorithms,
    'POST /api/v1/tasks/analyze': analyze,
    'POST /api/v1/experiments': { status: 202, body: created },
    'GET /api/v1/experiments/new-experiment': created,
    'GET /api/v1/experiments/new-experiment/live': live,
    'GET /api/v1/experiments/new-experiment/events': [],
    'GET /api/v1/algorithms/matrix.multiply': algorithms.find((a) => a.id === 'matrix.multiply')!,
  });
}

async function chooseMatrixMultiplication() {
  await userEvent.click(await screen.findByRole('button', { name: /Matrix/ }));
  await userEvent.click(screen.getByRole('button', { name: /Matrix multiplication/ }));
}

describe('NewTaskPage', () => {
  it('only offers categories Core implements', async () => {
    backend();
    renderApp('/tasks/new');
    for (const c of categories) {
      const button = await screen.findByRole('button', { name: new RegExp(c.name) });
      if (c.available) expect(button).toBeEnabled(); else expect(button).toBeDisabled();
    }
  });

  it('analyses the task, shows the advice, and submits the chosen mode', async () => {
    const { requests } = backend();
    renderApp('/tasks/new');
    await chooseMatrixMultiplication();
    await userEvent.type(screen.getByLabelText('rowsA'), '64');
    await userEvent.click(screen.getByRole('button', { name: /Analyse and recommend/ }));
    expect(await screen.findByText('Execution strategy advisor')).toBeInTheDocument();
    const analyze = requests.find((r) => r.path === '/api/v1/tasks/analyze')!;
    expect(analyze.body).toEqual({ algorithm: 'matrix.multiply', parameters: { rowsA: 64 }, inputs: {}, execution: { mode: 'MULTITHREADED_CPU' } });
    expect(analyze.headers['X-XSRF-TOKEN']).toBe('test-token');

    await userEvent.selectOptions(screen.getByLabelText('Execution mode'), 'DISTRIBUTED_CPU');
    await userEvent.type(screen.getByLabelText('Experiment name'), 'First run');
    await userEvent.click(screen.getByRole('button', { name: 'Run with Distributed CPU (workers)' }));
    expect(await screen.findByRole('heading', { name: created.summary.name })).toBeInTheDocument();
    const submit = requests.find((r) => r.method === 'POST' && r.path === '/api/v1/experiments')!;
    expect(submit.body).toMatchObject({ name: 'First run', task: { algorithm: 'matrix.multiply', execution: { mode: 'DISTRIBUTED_CPU' } }, overrideReason: null });
  });

  it('places the violations Core reports next to the field and does not navigate', async () => {
    backend({ status: 400, body: { code: 'VALIDATION_FAILED', detail: 'Core rejected the parameters',
      violations: [{ field: 'parameters.rowsA', code: 'BELOW_MINIMUM', message: 'must be at least 1, got 0' }] } });
    renderApp('/tasks/new');
    await chooseMatrixMultiplication();
    await userEvent.type(screen.getByLabelText('rowsA'), '0');
    await userEvent.click(screen.getByRole('button', { name: /Analyse and recommend/ }));
    expect(await screen.findByText('Core rejected the parameters')).toBeInTheDocument();
    expect(screen.getAllByText(/must be at least 1, got 0/).length).toBeGreaterThan(0);
    expect(screen.getByRole('heading', { name: 'New task' })).toBeInTheDocument();
  });

  it('blocks analysis while a field has a syntax error', async () => {
    const { requests } = backend();
    renderApp('/tasks/new');
    await chooseMatrixMultiplication();
    await userEvent.type(screen.getByLabelText('rowsA'), 'abc');
    expect(screen.getByText('Enter a whole number')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Analyse and recommend/ })).toBeDisabled();
    expect(requests.some((r) => r.path === '/api/v1/tasks/analyze')).toBe(false);
  });

  it('lists GPU and hybrid execution as unavailable, with the reason', async () => {
    backend();
    renderApp('/tasks/new');
    await chooseMatrixMultiplication();
    expect(screen.getByRole('option', { name: /GPU — not available/ })).toBeDisabled();
    expect(screen.getByRole('option', { name: /Hybrid — not available/ })).toBeDisabled();
    expect(screen.getByText(/No GPU backend is installed/)).toBeInTheDocument();
  });

  it('starts from a template', async () => {
    backend();
    renderApp('/tasks/new');
    await userEvent.selectOptions(await screen.findByLabelText('Start from a template'), '1');
    expect(await screen.findByLabelText('rowsA')).toHaveValue('32');
    expect(screen.getByLabelText('Execution mode')).toHaveValue('DISTRIBUTED_CPU');
    expect(screen.getByLabelText('Experiment name')).toHaveValue('mm small');
  });

  it('saves the current task as a template', async () => {
    const { requests } = backend();
    renderApp('/tasks/new');
    await chooseMatrixMultiplication();
    await userEvent.type(screen.getByLabelText('rowsA'), '48');
    await userEvent.type(screen.getByLabelText('Save this task as a template'), 'my template');
    await userEvent.click(screen.getByRole('button', { name: 'Save as template' }));
    expect(await screen.findByText('Saved as template "my template".')).toBeInTheDocument();
    expect(requests.find((r) => r.method === 'POST' && r.path === '/api/v1/templates')?.body).toEqual({ name: 'my template',
      task: { algorithm: 'matrix.multiply', parameters: { rowsA: 48 }, inputs: {}, execution: { mode: 'MULTITHREADED_CPU' } } });
  });
});
