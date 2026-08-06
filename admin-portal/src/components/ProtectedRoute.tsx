import { Navigate } from 'react-router-dom';
import { useAdminAuth } from '../context/AdminAuthContext';
import type { ReactNode } from 'react';

interface ProtectedRouteProps {
  children: ReactNode;
  /** The phone-verification screen itself must render for an authenticated but unverified admin
   *  -- every other route sends them there instead. Mirrors the user app's own prop of the same
   *  name. */
  allowUnverified?: boolean;
}

/**
 * Gates every admin route on "has a token", "the /users/me/access fetch that follows login/reload
 * has finished", and "phone verification is complete" -- without the loading check, a page reload
 * would redirect to /login for a split second on every single navigation while that request is
 * still in flight, since `token` alone doesn't tell you whether permissions have been confirmed
 * yet.
 */
export function ProtectedRoute({ children, allowUnverified = false }: ProtectedRouteProps) {
  const { token, loading, phoneVerified } = useAdminAuth();

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-bg text-muted text-sm">
        Loading…
      </div>
    );
  }
  if (!token) return <Navigate to="/login" replace />;
  // Phone verification used to be routed ONLY through Login.tsx, which redirects when
  // phoneVerified is false. Anyone who reached a protected route without passing through the
  // login screen -- a browser refresh on /users, a bookmark, a typed URL -- was admitted to the
  // admin shell instead. AdminAuthContext's mount effect then takes its early-return branch
  // ("Reloaded mid-verification ... nothing to fetch yet"), leaving permissions at [], so every
  // RequirePermission below rendered "You don't have access to this section" for an account whose
  // actual problem was an unfinished phone verification, with nothing on screen saying so and no
  // link to /verify-phone. The user app's own ProtectedRoute has always had this branch.
  if (!allowUnverified && !phoneVerified) return <Navigate to="/verify-phone" replace />;
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
