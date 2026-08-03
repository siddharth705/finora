import { BrowserRouter, Routes, Route } from 'react-router-dom';
import type { ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider } from './context/AuthContext';
import { ThemeProvider } from './context/ThemeContext';
import { ProtectedRoute } from './components/ProtectedRoute';
import { Sidebar } from './components/Sidebar';
import { TopBar } from './components/TopBar';
import Landing from './pages/Landing';
import Terms from './pages/Terms';
import Privacy from './pages/Privacy';
import About from './pages/About';
import Careers from './pages/Careers';
import Help from './pages/Help';
import Login from './pages/Login';
import Register from './pages/Register';
import ForgotPassword from './pages/ForgotPassword';
import ResetPassword from './pages/ResetPassword';
import VerifyPhone from './pages/VerifyPhone';
import Dashboard from './pages/Dashboard';
import Ledger from './pages/Ledger';
import Import from './pages/Import';
import StatementHistory from './pages/StatementHistory';
import Budgets from './pages/Budgets';
import Goals from './pages/Goals';
import Investments from './pages/Investments';
import Reports from './pages/Reports';
import Insights from './pages/Insights';
import Profile from './pages/Profile';
import Settings from './pages/Settings';
import Setup from './pages/Setup';

function AppShell({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen bg-bg flex">
      <Sidebar />
      <main className="flex-1 p-8 max-w-[1600px]">
        <TopBar />
        {children}
      </main>
    </div>
  );
}

function Protected({ children }: { children: ReactNode }) {
  return (
    <ProtectedRoute>
      <AppShell>{children}</AppShell>
    </ProtectedRoute>
  );
}

// A single shared client — sensible defaults for a dashboard app: don't refetch on every
// window focus (financial data doesn't change that fast), do retry once on transient failures.
const queryClient = new QueryClient({
  defaultOptions: {
    queries: { refetchOnWindowFocus: false, retry: 1, staleTime: 30_000 },
  },
});

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
    <ThemeProvider>
    <AuthProvider>
      <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
        <Routes>
          {/* Marketing site */}
          <Route path="/" element={<Landing />} />
          <Route path="/terms" element={<Terms />} />
          <Route path="/privacy" element={<Privacy />} />
          <Route path="/about" element={<About />} />
          <Route path="/careers" element={<Careers />} />
          <Route path="/help" element={<Help />} />

          {/* Auth */}
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          <Route path="/forgot-password" element={<ForgotPassword />} />
          <Route path="/reset-password" element={<ResetPassword />} />
          <Route path="/verify-phone" element={<ProtectedRoute allowUnverified><VerifyPhone /></ProtectedRoute>} />

          {/* App (authenticated) */}
          <Route path="/app" element={<Protected><Dashboard /></Protected>} />
          <Route path="/app/accounts" element={<Protected><Setup /></Protected>} />
          <Route path="/app/transactions" element={<Protected><Ledger /></Protected>} />
          <Route path="/app/import" element={<Protected><Import /></Protected>} />
          <Route path="/app/statements" element={<Protected><StatementHistory /></Protected>} />
          <Route path="/app/budgets" element={<Protected><Budgets /></Protected>} />
          <Route path="/app/goals" element={<Protected><Goals /></Protected>} />
          <Route path="/app/investments" element={<Protected><Investments /></Protected>} />
          <Route path="/app/reports" element={<Protected><Reports /></Protected>} />
          <Route path="/app/insights" element={<Protected><Insights /></Protected>} />
          <Route path="/app/profile" element={<Protected><Profile /></Protected>} />
          <Route path="/app/settings" element={<Protected><Settings /></Protected>} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
    </ThemeProvider>
    </QueryClientProvider>
  );
}
