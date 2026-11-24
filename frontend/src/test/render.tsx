import { render } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { AppRoutes } from '../App';
import { AuthProvider } from '../auth/AuthContext';
import { PreferencesProvider } from '../lib/preferences';

/** Renders the whole application (session check, shell, routes) at {@code path}. */
export function renderApp(path = '/') {
  return render(
    <PreferencesProvider>
      <AuthProvider>
        <MemoryRouter initialEntries={[path]}>
          <AppRoutes />
        </MemoryRouter>
      </AuthProvider>
    </PreferencesProvider>,
  );
}
