import { Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import type { ReactNode } from 'react';

interface ProtectedRouteProps {
  children: ReactNode;
  // The phone-verification screen itself must render for an unverified, authenticated user --
  // everything else redirects them there instead. The backend's PhoneVerificationFilter is the
  // real enforcement (it 403s any other endpoint for an unverified user); this client-side check
  // only avoids a flash of the authenticated shell before that round-trip comes back.
  allowUnverified?: boolean;
}

export function ProtectedRoute({ children, allowUnverified = false }: ProtectedRouteProps) {
  const { token, bootstrapping, phoneVerified } = useAuth();
  // SEC-01: the access token is in-memory only now (AuthContext's own comment on its bootstrap
  // effect), so on a fresh page load `token` is briefly null even for an already-logged-in user --
  // it takes one round trip (a silent /auth/refresh against the HttpOnly refresh cookie) to know
  // either way. Redirecting to /login on that gap would sign a returning user out on every reload.
  // Rendering nothing here rather than a spinner: this window is one network round trip, and
  // ProtectedRoute already sits below whatever page chrome (nav, sidebar) a real loading state
  // would otherwise have to duplicate around.
  if (bootstrapping) return null;
  if (!token) return <Navigate to="/login" replace />;
  if (!allowUnverified && !phoneVerified) return <Navigate to="/verify-phone" replace />;
  return <>{children}</>;
}
