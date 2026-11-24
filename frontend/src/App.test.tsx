import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { act } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { SESSION_EXPIRED } from './api/client';
import dashboard from './test/fixtures/dashboard.json';
import { fakeBackend, FakeEventSource } from './test/server';
import { renderApp } from './test/render';

const anonymous = { authenticated: false, id: null, username: null, role: null };
const user = { authenticated: true, id: 2, username: 'alice', role: 'USER' };
const admin = { authenticated: true, id: 1, username: 'admin', role: 'ADMIN' };

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal('EventSource', FakeEventSource);
  document.cookie = 'XSRF-TOKEN=test-token';
});
afterEach(() => vi.unstubAllGlobals());

describe('sign-in and navigation', () => {
  it('signs in with the form, sending the CSRF token, and opens the overview', async () => {
    let session: object = anonymous;
    const { requests } = fakeBackend({
      'GET /api/v1/auth/session': () => session,
      'POST /api/v1/auth/login': (r) => {
        const body = r.body as Record<string, string>;
        if (body.password !== 'correct horse battery') return { status: 401, body: { code: 'LOGIN_FAILED', detail: 'Wrong user name or password' } };
        session = user;
        return user;
      },
      'GET /api/v1/dashboard': dashboard,
    });
    renderApp('/');
    await userEvent.type(await screen.findByLabelText('User name'), 'alice');
    await userEvent.type(screen.getByLabelText('Password'), 'wrong');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByText('Wrong user name or password')).toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText('Password'));
    await userEvent.type(screen.getByLabelText('Password'), 'correct horse battery');
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('heading', { name: 'Overview' })).toBeInTheDocument();
    const login = requests.filter((r) => r.path === '/api/v1/auth/login');
    expect(login.at(-1)?.headers['X-XSRF-TOKEN']).toBe('test-token');
    expect(login.at(-1)?.body).toEqual({ username: 'alice', password: 'correct horse battery' });
  });

  it('shows administration only to administrators', async () => {
    fakeBackend({ 'GET /api/v1/auth/session': user, 'GET /api/v1/dashboard': dashboard });
    renderApp('/');
    await screen.findByRole('heading', { name: 'Overview' });
    expect(screen.queryByRole('link', { name: 'Fault lab' })).not.toBeInTheDocument();
  });

  it('marks unfinished pages as not implemented instead of showing sample content', async () => {
    fakeBackend({ 'GET /api/v1/auth/session': admin });
    renderApp('/settings');
    expect(await screen.findByText(/Not implemented yet/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Fault lab' })).toBeInTheDocument();
  });

  it('returns to the sign-in page when the session expires', async () => {
    fakeBackend({ 'GET /api/v1/auth/session': admin, 'GET /api/v1/dashboard': dashboard });
    renderApp('/');
    await screen.findByRole('heading', { name: 'Overview' });
    act(() => { window.dispatchEvent(new Event(SESSION_EXPIRED)); });
    await waitFor(() => expect(screen.getByLabelText('User name')).toBeInTheDocument());
  });
});
