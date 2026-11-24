import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import algorithms from '../test/fixtures/algorithms.json';
import experiments from '../test/fixtures/experiments.json';
import { fakeBackend, FakeEventSource } from '../test/server';
import { renderApp } from '../test/render';

beforeEach(() => vi.stubGlobal('EventSource', FakeEventSource));
afterEach(() => vi.unstubAllGlobals());

describe('HistoryPage', () => {
  it('lists experiments, filters and sorts through the API, and compares a selection', async () => {
    const { requests } = fakeBackend({
      'GET /api/v1/auth/session': { authenticated: true, id: 1, username: 'admin', role: 'ADMIN' },
      'GET /api/v1/algorithms': algorithms,
      'GET /api/v1/experiments': experiments,
    });
    renderApp('/experiments');
    for (const e of experiments.items) expect(await screen.findByRole('link', { name: e.name })).toBeInTheDocument();
    expect(screen.getByText(`${experiments.totalItems} experiments`)).toBeInTheDocument();

    await userEvent.selectOptions(screen.getByLabelText('Status'), 'FAILED');
    await waitFor(() => expect(requests.at(-1)!.path).toContain('status=FAILED'));
    await userEvent.click(screen.getByRole('button', { name: 'Name' }));
    await waitFor(() => expect(requests.at(-1)!.path).toContain('sort=name%2Casc'));

    const compare = screen.getByRole('button', { name: /Compare/ });
    expect(compare).toBeDisabled();
    for (const e of experiments.items) await userEvent.click(screen.getByRole('checkbox', { name: `Select ${e.name}` }));
    expect(compare).toBeEnabled();
  });

  it('says when nothing matches', async () => {
    fakeBackend({
      'GET /api/v1/auth/session': { authenticated: true, id: 2, username: 'alice', role: 'USER' },
      'GET /api/v1/algorithms': algorithms,
      'GET /api/v1/experiments': { items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 },
    });
    renderApp('/experiments');
    expect(await screen.findByText('No experiments match')).toBeInTheDocument();
  });
});
