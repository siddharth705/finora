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
  const { token, phoneVerified } = useAuth();
  if (!token) return <Navigate to="/login" replace />;
  if (!allowUnverified && !phoneVerified) return <Navigate to="/verify-phone" replace />;
  return <>{children}</>;
}
