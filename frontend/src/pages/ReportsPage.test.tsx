import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import algorithms from '../test/fixtures/algorithms.json';
import experiment from '../test/fixtures/experiment.json';
import experiments from '../test/fixtures/experiments.json';
import workloads from '../test/fixtures/workloads.json';
import { fakeBackend, FakeEventSource } from '../test/server';
import { renderApp } from '../test/render';

const id = experiment.summary.id;

beforeEach(() => vi.stubGlobal('EventSource', FakeEventSource));
afterEach(() => vi.unstubAllGlobals());

function backend(role: 'ADMIN' | 'USER' = 'USER') {
  return fakeBackend({
    'GET /api/v1/auth/session': { authenticated: true, id: 2, username: 'alice', role },
    'GET /api/v1/experiments': experiments,
    [`GET /api/v1/experiments/${id}`]: experiment,
    'GET /api/v1/analysis/workloads': workloads,
    'GET /api/v1/algorithms': algorithms,
  });
}

const links = (group: string) => within(screen.getByRole('group', { name: group })).getAllByRole('link');

describe('ReportsPage', () => {
  it('offers the report of the experiment it was opened for in three formats', async () => {
    backend();
    renderApp(`/reports?experiment=${id}`);
    const group = await screen.findByRole('group', { name: 'Download the experiment report' });
    expect(within(group).getAllByRole('link').map((a) => a.getAttribute('href'))).toEqual(
      ['pdf', 'csv', 'json'].map((f) => `/api/v1/experiments/${id}/report?format=${f}`));
    expect(screen.getByLabelText('Experiment')).toHaveValue(id);
  });

  it('lets the user pick another experiment', async () => {
    backend();
    renderApp('/reports');
    const other = experiments.items[experiments.items.length - 1];
    await screen.findByRole('option', { name: new RegExp(other.name.replace(/[()]/g, '.')) });
    expect(screen.queryByRole('group', { name: 'Download the experiment report' })).not.toBeInTheDocument();
    await userEvent.selectOptions(screen.getByLabelText('Experiment'), other.id);
    expect(links('Download the experiment report')[0]).toHaveAttribute('href', `/api/v1/experiments/${other.id}/report?format=pdf`);
  });

  it('downloads comparisons of workloads with two or more experiments', async () => {
    backend();
    renderApp('/reports');
    const comparable = workloads.filter((g) => g.experimentIds.length >= 2);
    const groups = await screen.findAllByRole('group', { name: /Download the comparison of/ });
    expect(groups).toHaveLength(comparable.length);
    expect(within(groups[0]).getAllByRole('link')[0]).toHaveAttribute('href',
      `/api/v1/analysis/report?format=pdf&ids=${comparable[0].experimentIds.join(',')}`);
  });

  it('exports the history with the chosen filters, as CSV or JSON only', async () => {
    backend('ADMIN');
    renderApp('/reports');
    await userEvent.selectOptions(await screen.findByLabelText('Status'), 'COMPLETED');
    await userEvent.selectOptions(screen.getByLabelText('Mode'), 'DISTRIBUTED_CPU');
    await userEvent.click(screen.getByLabelText('Only mine'));
    const hrefs = links('Download the history').map((a) => a.getAttribute('href'));
    expect(hrefs).toEqual(['csv', 'json'].map((f) =>
      `/api/v1/experiments/export?format=${f}&status=COMPLETED&mode=DISTRIBUTED_CPU&mine=true`));
  });
});
