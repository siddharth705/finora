import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { lazy, Suspense } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AdminAuthProvider } from './context/AdminAuthContext';
import { NotificationProvider } from './context/NotificationContext';
import { ErrorBoundary } from './components/ErrorBoundary';
import { ProtectedRoute } from './components/ProtectedRoute';
// Login stays eager: with no token it is the first paint for every visitor, so making it lazy
// would add a round trip to the most common entry point.
import Login from './pages/Login';

// Everything else is route-split. Measured from the pre-split bundle's sourcemap, firebase is
// ~33% of this app's source bytes (26.1% plus @firebase/*) and is reached only by the phone
// verification and password reset screens -- lib/firebase.ts is a STATIC import, so the existing
// lazy getFirebaseAuth() deferred initialisation but never the download.
const ForgotPassword = lazy(() => import('./pages/ForgotPassword'));
const ResetPassword = lazy(() => import('./pages/ResetPassword'));
const Setup = lazy(() => import('./pages/Setup'));
const VerifyPhone = lazy(() => import('./pages/VerifyPhone'));
const Dashboard = lazy(() => import('./pages/Dashboard'));
const Users = lazy(() => import('./pages/Users'));
const UserDetail = lazy(() => import('./pages/UserDetail'));
const Roles = lazy(() => import('./pages/Roles'));
const Banks = lazy(() => import('./pages/Banks'));
const MerchantIntelligence = lazy(() => import('./pages/MerchantIntelligence'));
const GlobalRules = lazy(() => import('./pages/GlobalRules'));
const LearningEngine = lazy(() => import('./pages/LearningEngine'));
const ReconciliationMonitor = lazy(() => import('./pages/ReconciliationMonitor'));
const PlatformAnalytics = lazy(() => import('./pages/PlatformAnalytics'));
const AuditLog = lazy(() => import('./pages/AuditLog'));
const SystemHealth = lazy(() => import('./pages/SystemHealth'));
const Diagnostics = lazy(() => import('./pages/Diagnostics'));
const Settings = lazy(() => import('./pages/Settings'));

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { refetchOnWindowFocus: false, retry: 1, staleTime: 30_000 },
  },
});

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <NotificationProvider>
        <AdminAuthProvider>
          {/* No `future` prop since the v7 upgrade: v7_startTransition and v7_relativeSplatPath
              were opt-in flags for exactly this migration and are now the only behaviour, so v7
              removed the prop entirely. */}
          <BrowserRouter>
            {/* The outer of two boundaries, and the lesser one. Pages that use AdminLayout are
                caught by its inner boundary first (React unwinds to the nearest one), which is what
                keeps the sidebar alive. This one covers the routes that render standalone with no
                layout -- login, setup, reset-password, verify-phone -- where it is the difference
                between a recovery panel and the blank white page they would otherwise show. */}
            <ErrorBoundary context="root">
            {/* Unlike the user app there is no shared shell to keep on screen -- AdminLayout lives
                inside each page -- so this covers every route. The fallback is a line of text
                rather than a blank screen, which is the failure lazy routes reintroduce most
                easily. */}
            <Suspense fallback={<p className="min-h-screen flex items-center justify-center bg-bg text-muted text-sm" role="status">Loading…</p>}>
            <Routes>
              <Route path="/login" element={<Login />} />
              <Route path="/forgot-password" element={<ForgotPassword />} />
              <Route path="/reset-password" element={<ResetPassword />} />
              <Route path="/setup" element={<Setup />} />
              <Route path="/verify-phone" element={<ProtectedRoute><VerifyPhone /></ProtectedRoute>} />
              <Route path="/" element={<ProtectedRoute><Dashboard /></ProtectedRoute>} />
              <Route path="/users" element={<ProtectedRoute><Users /></ProtectedRoute>} />
              <Route path="/users/:id" element={<ProtectedRoute><UserDetail /></ProtectedRoute>} />
              <Route path="/roles" element={<ProtectedRoute><Roles /></ProtectedRoute>} />
              <Route path="/banks" element={<ProtectedRoute><Banks /></ProtectedRoute>} />
              <Route path="/merchants" element={<ProtectedRoute><MerchantIntelligence /></ProtectedRoute>} />
              <Route path="/rules" element={<ProtectedRoute><GlobalRules /></ProtectedRoute>} />
              <Route path="/learning" element={<ProtectedRoute><LearningEngine /></ProtectedRoute>} />
              <Route path="/reconciliation" element={<ProtectedRoute><ReconciliationMonitor /></ProtectedRoute>} />
              <Route path="/analytics" element={<ProtectedRoute><PlatformAnalytics /></ProtectedRoute>} />
              <Route path="/audit" element={<ProtectedRoute><AuditLog /></ProtectedRoute>} />
              <Route path="/health" element={<ProtectedRoute><SystemHealth /></ProtectedRoute>} />
              <Route path="/diagnostics" element={<ProtectedRoute><Diagnostics /></ProtectedRoute>} />
              <Route path="/settings" element={<ProtectedRoute><Settings /></ProtectedRoute>} />

              {/* Same bug, same fix as the user app's App.tsx: with no catch-all, any unmatched
                  path rendered a completely blank page instead of going anywhere. "/" is the
                  Dashboard behind ProtectedRoute, so an unauthenticated visitor still lands on
                  Login rather than being handed an admin screen. */}
              <Route path="*" element={<Navigate to="/" replace />} />
            </Routes>
            </Suspense>
            </ErrorBoundary>
          </BrowserRouter>
        </AdminAuthProvider>
      </NotificationProvider>
    </QueryClientProvider>
  );
}
