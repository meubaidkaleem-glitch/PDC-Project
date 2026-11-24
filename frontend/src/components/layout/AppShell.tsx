import { useState } from 'react';
import type { ReactNode } from 'react';
import { NavLink } from 'react-router';
import { Activity, BarChart3, Cpu, FileText, FlaskConical, Gauge, History, LogOut, Menu, Moon, Network,
  PlusSquare, Settings, Sun, SunMoon, X, Layers } from 'lucide-react';
import { useAuth } from '../../auth/AuthContext';
import { usePreferences } from '../../lib/preferences';
import type { Theme } from '../../lib/preferences';
import { cx } from '../ui';

const NAV = [
  { to: '/', label: 'Overview', icon: Gauge, end: true },
  { to: '/tasks/new', label: 'New task', icon: PlusSquare },
  { to: '/experiments', label: 'Experiments', icon: History },
  { to: '/batches', label: 'Batches', icon: Layers },
  { to: '/analysis', label: 'Performance analysis', icon: BarChart3 },
  { to: '/workers', label: 'Workers', icon: Cpu },
  { to: '/topology', label: 'Cluster topology', icon: Network },
  { to: '/reports', label: 'Reports', icon: FileText },
  { to: '/fault-lab', label: 'Fault lab', icon: FlaskConical, admin: true },
  { to: '/settings', label: 'Settings', icon: Settings },
];

const THEME_NEXT: Record<Theme, Theme> = { system: 'light', light: 'dark', dark: 'system' };

export function AppShell({ children }: { children: ReactNode }) {
  const { session, logout, isAdmin } = useAuth();
  const { theme, setTheme, level, setLevel } = usePreferences();
  const [open, setOpen] = useState(false);
  const ThemeIcon = theme === 'light' ? Sun : theme === 'dark' ? Moon : SunMoon;

  const nav = (
    <nav aria-label="Main" className="space-y-0.5 p-3">
      {NAV.filter((n) => !n.admin || isAdmin).map(({ to, label, icon: Icon, end }) => (
        <NavLink key={to} to={to} end={end} onClick={() => setOpen(false)}
          className={({ isActive }) => cx('flex items-center gap-2.5 rounded-md px-2.5 py-2 text-sm',
            isActive ? 'bg-blue-50 font-medium text-blue-800 dark:bg-blue-950 dark:text-blue-200'
              : 'text-slate-700 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800')}>
          <Icon className="h-4 w-4" aria-hidden />{label}
        </NavLink>
      ))}
    </nav>
  );

  return (
    <div className="min-h-screen lg:grid lg:grid-cols-[15rem_1fr]">
      <aside className={cx('fixed inset-y-0 left-0 z-30 w-60 border-r border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900 lg:static lg:block',
        open ? 'block' : 'hidden')}>
        <div className="flex h-14 items-center gap-2 border-b border-slate-200 px-4 dark:border-slate-800">
          <Activity className="h-5 w-5 text-blue-700 dark:text-blue-400" aria-hidden />
          <span className="font-semibold tracking-tight">DistriCompute</span>
          <button type="button" className="ml-auto lg:hidden" onClick={() => setOpen(false)} aria-label="Close menu"><X className="h-5 w-5" /></button>
        </div>
        {nav}
      </aside>
      <div className="min-w-0">
        <header className="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-slate-200 bg-white/90 px-4 backdrop-blur dark:border-slate-800 dark:bg-slate-900/90">
          <button type="button" className="lg:hidden" onClick={() => setOpen(true)} aria-label="Open menu"><Menu className="h-5 w-5" /></button>
          <div className="ml-auto flex items-center gap-2">
            <div role="radiogroup" aria-label="Interface level" className="flex rounded-md border border-slate-300 p-0.5 text-xs dark:border-slate-700">
              {(['beginner', 'advanced'] as const).map((l) => (
                <button key={l} type="button" role="radio" aria-checked={level === l} onClick={() => setLevel(l)}
                  className={cx('rounded px-2 py-1 capitalize', level === l ? 'bg-blue-700 text-white dark:bg-blue-600' : 'text-slate-600 dark:text-slate-300')}>
                  {l}
                </button>
              ))}
            </div>
            <button type="button" onClick={() => setTheme(THEME_NEXT[theme])} title={`Theme: ${theme}`} aria-label={`Theme: ${theme}`}
              className="rounded-md p-1.5 text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800">
              <ThemeIcon className="h-4 w-4" aria-hidden />
            </button>
            <span className="hidden text-sm text-slate-600 sm:inline dark:text-slate-300">
              {session?.username} <span className="text-xs text-slate-400">({session?.role?.toLowerCase()})</span>
            </span>
            <button type="button" onClick={() => void logout()} title="Sign out" aria-label="Sign out"
              className="rounded-md p-1.5 text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800">
              <LogOut className="h-4 w-4" aria-hidden />
            </button>
          </div>
        </header>
        <main className="mx-auto min-w-0 max-w-7xl p-4 sm:p-6">{children}</main>
      </div>
    </div>
  );
}
