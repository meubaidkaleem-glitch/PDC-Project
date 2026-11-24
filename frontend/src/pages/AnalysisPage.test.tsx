import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Comparison } from '../api/types';
import recorded from '../test/fixtures/comparison.json';
import workloads from '../test/fixtures/workloads.json';
import { fakeBackend, FakeEventSource } from '../test/server';
import { renderApp } from '../test/render';

const comparison = recorded as unknown as Comparison;
const ids = comparison.experiments.map((e) => e.id);
const session = { authenticated: true, id: 1, username: 'admin', role: 'ADMIN' };

beforeEach(() => {
  vi.stubGlobal('EventSource', FakeEventSource);
  document.cookie = 'XSRF-TOKEN=test-token';
  localStorage.clear();
});
afterEach(() => vi.unstubAllGlobals());

describe('AnalysisPage', () => {
  it('lists workloads and compares a group', async () => {
    const { requests } = fakeBackend({
      'GET /api/v1/auth/session': session,
      'GET /api/v1/analysis/workloads': workloads,
      'POST /api/v1/analysis/compare': comparison,
    });
    renderApp('/analysis');
    const group = workloads[0];
    const row = (await screen.findAllByText(`${group.algorithm} ${group.algorithmVersion}`))[0].closest('tr')!;
    await userEvent.click(within(row).getByRole('link', { name: `Compare ${group.experimentIds.length}` }));
    expect(await screen.findByText('Findings')).toBeInTheDocument();
    const call = requests.find((r) => r.path === '/api/v1/analysis/compare')!;
    expect(call.body).toEqual({ experimentIds: group.experimentIds, baselineId: null, basis: null });
  });

  it('shows the speedups, efficiency and findings the backend computed', async () => {
    fakeBackend({ 'GET /api/v1/auth/session': session, 'POST /api/v1/analysis/compare': comparison });
    renderApp(`/analysis?ids=${ids.join(',')}`);
    await screen.findByText('Findings');
    for (const sentence of comparison.summary) expect(screen.getByText(sentence)).toBeInTheDocument();
    const baseline = comparison.experiments.find((e) => e.id === comparison.baselineId)!;
    const baselineRow = screen.getByRole('link', { name: baseline.name }).closest('tr')!;
    expect(within(baselineRow).getByText('baseline')).toBeInTheDocument();
    for (const e of comparison.experiments.filter((x) => x.efficiency !== null)) {
      const row = screen.getByRole('link', { name: e.name }).closest('tr')!;
      expect(within(row).getByText(`${e.speedup!.toFixed(2)}×`)).toBeInTheDocument();
      expect(within(row).getByText(`${(e.efficiency! * 100).toFixed(0)} %`)).toBeInTheDocument();
    }
    expect(screen.getByText(/Every compared run produced the same verified result/)).toBeInTheDocument();
  });

  it('asks the backend again for another basis or baseline', async () => {
    const { requests } = fakeBackend({ 'GET /api/v1/auth/session': session, 'POST /api/v1/analysis/compare': comparison });
    renderApp(`/analysis?ids=${ids.join(',')}`);
    await screen.findByText('Findings');
    await userEvent.selectOptions(screen.getByLabelText('Compare by'), 'WALL_TIME');
    await waitFor(() => expect(requests.at(-1)!.body).toMatchObject({ basis: 'WALL_TIME' }));
    const other = comparison.experiments.find((e) => e.id !== comparison.baselineId)!;
    await userEvent.selectOptions(screen.getByLabelText('Baseline'), other.id);
    await waitFor(() => expect(requests.at(-1)!.body).toMatchObject({ baselineId: other.id, basis: 'WALL_TIME' }));
  });

  it('does not compute speedups for experiments that are not comparable', async () => {
    const mismatch: Comparison = {
      ...comparison, comparable: false,
      experiments: comparison.experiments.map((e) => ({ ...e, speedup: null, speedupRange: null, efficiency: null, efficiencyBasis: null })),
      issues: [{ severity: 'ERROR', code: 'DIMENSIONS_DIFFER', message: 'The workloads have different sizes.', experimentIds: ids }],
      summary: ['The experiments do not run the same workload, so their times are listed but not compared.'],
    };
    fakeBackend({ 'GET /api/v1/auth/session': session, 'POST /api/v1/analysis/compare': mismatch });
    renderApp(`/analysis?ids=${ids.join(',')}`);
    expect(await screen.findByText('The workloads have different sizes.')).toBeInTheDocument();
    expect(screen.getByText(/Not computed: the experiments are not comparable/)).toBeInTheDocument();
    expect(screen.queryByText(/×$/)).not.toBeInTheDocument();
  });

  it('asks for a second experiment when only one is selected', async () => {
    fakeBackend({ 'GET /api/v1/auth/session': session, 'GET /api/v1/analysis/workloads': [] });
    renderApp(`/analysis?ids=${ids[0]}`);
    expect(await screen.findByText('Select at least two experiments to compare.')).toBeInTheDocument();
    expect(await screen.findByText('No completed experiments yet')).toBeInTheDocument();
  });
});
