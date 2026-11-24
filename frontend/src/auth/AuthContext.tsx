import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { api, ensureCsrf, SESSION_EXPIRED } from '../api/client';
import type { Session } from '../api/types';

interface AuthState {
  session: Session | undefined;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  isAdmin: boolean;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session>();

  useEffect(() => {
    api<Session>('/api/v1/auth/session').then(setSession)
      .catch(() => setSession({ authenticated: false, id: null, username: null, role: null }));
    const expired = () => setSession({ authenticated: false, id: null, username: null, role: null });
    window.addEventListener(SESSION_EXPIRED, expired);
    return () => window.removeEventListener(SESSION_EXPIRED, expired);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    await ensureCsrf();
    const form = new URLSearchParams({ username, password });
    setSession(await api<Session>('/api/v1/auth/login', { method: 'POST', form }));
  }, []);

  const logout = useCallback(async () => {
    try {
      await api('/api/v1/auth/logout', { method: 'POST' });
    } finally {
      setSession({ authenticated: false, id: null, username: null, role: null });
    }
  }, []);

  const value = useMemo(() => ({ session, login, logout, isAdmin: session?.role === 'ADMIN' }), [session, login, logout]);
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth outside AuthProvider');
  return ctx;
}
