import { Navigate } from 'react-router-dom';
import { useAdminAuth } from '../context/AdminAuthContext';
import type { ReactNode } from 'react';

/**
 * Gates every admin route on both "has a token" and "the /users/me/access fetch that follows
 * login/reload has finished" -- without the loading check, a page reload would redirect to
 * /login for a split second on every single navigation while that request is still in flight,
 * since `token` alone doesn't tell you whether permissions have been confirmed yet.
 */
export function ProtectedRoute({ children }: { children: ReactNode }) {
  const { token, loading } = useAdminAuth();

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-bg text-muted text-sm">
        Loading…
      </div>
    );
  }
  if (!token) return <Navigate to="/login" replace />;
  return <>{children}</>;
}

/** Gates a single section (not the whole app) on a specific permission -- used inside pages that
 *  are reachable but shouldn't render their content for an admin who lacks that one permission
 *  (e.g. a support-only role with AUDIT_VIEW but not USER_VIEW hitting /users directly by URL). */
export function RequirePermission({ permission, children }: { permission: string; children: ReactNode }) {
  const { hasPermission } = useAdminAuth();
  if (!hasPermission(permission)) {
    return (
      <div className="bg-card border border-border rounded-xl2 p-8 text-center">
        <p className="text-ink font-semibold mb-1">You don't have access to this section</p>
        <p className="text-muted text-sm">This requires the {permission} permission.</p>
      </div>
    );
  }
  return <>{children}</>;
}
