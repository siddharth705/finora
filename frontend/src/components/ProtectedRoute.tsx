import { Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useOnboardingUI } from '../onboarding/OnboardingUIContext';
import { OnboardingFlow } from '../onboarding/OnboardingFlow';
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
  const { token, bootstrapping, phoneVerified, onboardingCompleted } = useAuth();
  const { step } = useOnboardingUI();
  // SEC-01: the access token is in-memory only now (AuthContext's own comment on its bootstrap
  // effect), so on a fresh page load `token` is briefly null even for an already-logged-in user --
  // it takes one round trip (a silent /auth/refresh against the HttpOnly refresh cookie) to know
  // either way. Redirecting to /auth on that gap would sign a returning user out on every reload.
  // Rendering nothing here rather than a spinner: this window is one network round trip, and
  // ProtectedRoute already sits below whatever page chrome (nav, sidebar) a real loading state
  // would otherwise have to duplicate around.
  if (bootstrapping) return null;
  if (!token) return <Navigate to="/auth" replace />;
  if (!allowUnverified && !phoneVerified) return <Navigate to="/verify-phone" replace />;
  // Onboarding only ever applies to a verified session -- allowUnverified routes (VerifyPhone
  // itself) must never be blocked behind it, same reasoning as the phoneVerified check above.
  //
  // The 'tour' step is deliberately NOT an OnboardingFlow takeover: the spec calls for the tour
  // to spotlight the REAL app (the live Sidebar), not a copy of it rendered by OnboardingFlow --
  // see docs/superpowers/specs/2026-09-06-first-login-onboarding-tour-design.md §7. So for that
  // one step, this renders the real children plus TourOverlay on top; every other incomplete step
  // (Welcome/FinancialFocus/TourIntro/Success) takes over the whole screen via OnboardingFlow.
  if (!allowUnverified && !onboardingCompleted) {
    if (step === 'tour') {
      // Task 10 replaces this stub with the real TourOverlay (that file doesn't exist until
      // Task 10 creates it -- importing it here would break this task's own build). onFinish/
      // onSkip both just advance to 'success': neither the tour finishing nor being skipped
      // completes onboarding by itself -- only SuccessScreen's own buttons do that (Task 11).
      return (
        <>
          {children}
          <div data-testid="tour-overlay-stub" />
        </>
      );
    }
    return <OnboardingFlow />;
  }
  return <>{children}</>;
}
