import { BrowserRouter, Link, Route, Routes } from 'react-router';
import { AuthProvider, useAuth } from './auth/AuthContext';
import { PreferencesProvider } from './lib/preferences';
import { AppShell } from './components/layout/AppShell';
import { Spinner } from './components/ui';
import { LoginPage } from './pages/LoginPage';
import { DashboardPage } from './pages/DashboardPage';
import { NewTaskPage } from './pages/NewTaskPage';
import { ExperimentPage } from './pages/ExperimentPage';
import { HistoryPage } from './pages/HistoryPage';
import { WorkersPage } from './pages/WorkersPage';
import { TopologyPage } from './pages/TopologyPage';
import { AnalysisPage } from './pages/AnalysisPage';
import { ReportsPage } from './pages/ReportsPage';
import { FaultLabPage } from './pages/FaultLabPage';
import { BatchesPage, BatchPage } from './pages/BatchesPage';
import { SettingsPage } from './pages/SettingsPage';

export function AppRoutes() {
  const { session } = useAuth();
  if (!session) return <div className="p-6"><Spinner label="Checking your session" /></div>;
  if (!session.authenticated) return <LoginPage />;
  return (
    <AppShell>
      <Routes>
        <Route path="/" element={<DashboardPage />} />
        <Route path="/tasks/new" element={<NewTaskPage />} />
        <Route path="/experiments" element={<HistoryPage />} />
        <Route path="/experiments/:id" element={<ExperimentPage />} />
        <Route path="/batches" element={<BatchesPage />} />
        <Route path="/batches/:id" element={<BatchPage />} />
        <Route path="/workers" element={<WorkersPage />} />
        <Route path="/topology" element={<TopologyPage />} />
        <Route path="/analysis" element={<AnalysisPage />} />
        <Route path="/reports" element={<ReportsPage />} />
        <Route path="/fault-lab" element={<FaultLabPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route path="*" element={<div className="py-10 text-center"><p className="text-sm">This page does not exist.</p><Link to="/" className="text-sm text-blue-700 hover:underline">Back to the overview</Link></div>} />
      </Routes>
    </AppShell>
  );
}

export function App() {
  return (
    <PreferencesProvider>
      <AuthProvider>
        <BrowserRouter>
          <AppRoutes />
        </BrowserRouter>
      </AuthProvider>
    </PreferencesProvider>
  );
}
