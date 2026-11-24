import { createContext, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';

export type Theme = 'light' | 'dark' | 'system';
export type Level = 'beginner' | 'advanced';

interface Preferences {
  theme: Theme;
  setTheme: (t: Theme) => void;
  level: Level;
  setLevel: (l: Level) => void;
}

const PreferencesContext = createContext<Preferences | undefined>(undefined);

function read<T extends string>(key: string, fallback: T, allowed: T[]): T {
  try {
    const value = localStorage.getItem(key) as T | null;
    return value && allowed.includes(value) ? value : fallback;
  } catch {
    return fallback;
  }
}

function write(key: string, value: string) {
  try {
    localStorage.setItem(key, value);
  } catch {
    // Storage may be unavailable (private mode); the choice then lasts for this page only.
  }
}

/** Interface preferences kept in the browser: theme and the beginner or advanced level. */
export function PreferencesProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<Theme>(() => read('districompute.theme', 'system', ['light', 'dark', 'system']));
  const [level, setLevelState] = useState<Level>(() => read('districompute.level', 'beginner', ['beginner', 'advanced']));

  useEffect(() => {
    const media = window.matchMedia?.('(prefers-color-scheme: dark)');
    const apply = () => {
      const dark = theme === 'dark' || (theme === 'system' && !!media?.matches);
      document.documentElement.classList.toggle('dark', dark);
    };
    apply();
    media?.addEventListener?.('change', apply);
    return () => media?.removeEventListener?.('change', apply);
  }, [theme]);

  const value = useMemo(() => ({
    theme,
    setTheme: (t: Theme) => { setThemeState(t); write('districompute.theme', t); },
    level,
    setLevel: (l: Level) => { setLevelState(l); write('districompute.level', l); },
  }), [theme, level]);
  return <PreferencesContext.Provider value={value}>{children}</PreferencesContext.Provider>;
}

export function usePreferences(): Preferences {
  const ctx = useContext(PreferencesContext);
  if (!ctx) throw new Error('usePreferences outside PreferencesProvider');
  return ctx;
}
