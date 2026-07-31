import { BrowserRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AdminAuthProvider } from './context/AdminAuthContext';
import { NotificationProvider } from './context/NotificationContext';
import { ProtectedRoute } from './components/ProtectedRoute';
import Login from './pages/Login';
import ForgotPassword from './pages/ForgotPassword';
import ResetPassword from './pages/ResetPassword';
import Setup from './pages/Setup';
import VerifyPhone from './pages/VerifyPhone';
import Dashboard from './pages/Dashboard';
import Users from './pages/Users';
import UserDetail from './pages/UserDetail';
import Roles from './pages/Roles';
import Banks from './pages/Banks';
import MerchantIntelligence from './pages/MerchantIntelligence';
import GlobalRules from './pages/GlobalRules';
import LearningEngine from './pages/LearningEngine';
import ReconciliationMonitor from './pages/ReconciliationMonitor';
import PlatformAnalytics from './pages/PlatformAnalytics';
import AuditLog from './pages/AuditLog';
import SystemHealth from './pages/SystemHealth';
import Diagnostics from './pages/Diagnostics';
import FeatureFlags from './pages/FeatureFlags';
import Settings from './pages/Settings';

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
          <BrowserRouter future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
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
              <Route path="/feature-flags" element={<ProtectedRoute><FeatureFlags /></ProtectedRoute>} />
              <Route path="/settings" element={<ProtectedRoute><Settings /></ProtectedRoute>} />
            </Routes>
          </BrowserRouter>
        </AdminAuthProvider>
      </NotificationProvider>
    </QueryClientProvider>
  );
}
