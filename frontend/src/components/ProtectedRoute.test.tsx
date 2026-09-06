import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { ProtectedRoute } from './ProtectedRoute';
import { useAuth } from '../context/AuthContext';
import { useOnboardingUI } from '../onboarding/OnboardingUIContext';

// useAuth pulls from AuthContext, which itself talks to localStorage/authApi on mount --
// mocking the hook directly (rather than rendering a real AuthProvider) keeps this test focused
// on ProtectedRoute's own redirect logic, which is the thing actually under test here.
vi.mock('../context/AuthContext', () => ({
  useAuth: vi.fn(),
}));

// Same reasoning as useAuth above -- ProtectedRoute only consumes this, doesn't own it.
vi.mock('../onboarding/OnboardingUIContext', () => ({
  useOnboardingUI: vi.fn(),
}));

function renderProtected(allowUnverified?: boolean) {
  return render(
    <MemoryRouter initialEntries={['/dashboard']}>
      <Routes>
        <Route
          path="/dashboard"
          element={
            <ProtectedRoute allowUnverified={allowUnverified}>
              <div>Protected content</div>
            </ProtectedRoute>
          }
        />
        <Route path="/auth" element={<div>Auth page</div>} />
        <Route path="/verify-phone" element={<div>Verify phone page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('ProtectedRoute', () => {
  beforeEach(() => {
    vi.mocked(useAuth).mockReset();
    // Every existing test in this file predates onboarding and implicitly assumes it's already
    // done -- default to 'welcome'/never called so those tests don't have to know this state
    // exists; the tests that actually care about onboarding override this explicitly below.
    vi.mocked(useOnboardingUI).mockReset().mockReturnValue({ step: 'welcome', setStep: vi.fn() });
  });

  it('redirects to /auth when there is no token', () => {
    vi.mocked(useAuth).mockReturnValue({ token: null, bootstrapping: false, phoneVerified: false, onboardingCompleted: true } as ReturnType<typeof useAuth>);

    renderProtected();

    expect(screen.getByText('Auth page')).toBeInTheDocument();
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });

  /**
   * SEC-01 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). The access token no
   * longer survives a reload on its own (AuthContext's own comment on its bootstrap effect) -- a
   * returning, still-logged-in user has `token: null` for one round trip while that effect asks
   * the HttpOnly refresh cookie for a fresh one. Redirecting to /login on that gap, the way the
   * "no token" case above correctly does once bootstrapping is actually done, would sign a
   * genuinely logged-in user out on every single reload.
   */
  it('renders neither the protected content nor a redirect while still bootstrapping', () => {
    vi.mocked(useAuth).mockReturnValue({ token: null, bootstrapping: true, phoneVerified: false, onboardingCompleted: true } as ReturnType<typeof useAuth>);

    renderProtected();

    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
    expect(screen.queryByText('Auth page')).not.toBeInTheDocument();
    expect(screen.queryByText('Verify phone page')).not.toBeInTheDocument();
  });

  it('redirects to /verify-phone when authenticated but not phone-verified', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', bootstrapping: false, phoneVerified: false, onboardingCompleted: true } as ReturnType<typeof useAuth>);

    renderProtected();

    expect(screen.getByText('Verify phone page')).toBeInTheDocument();
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });

  it('renders the protected content once authenticated, phone-verified and onboarded', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', bootstrapping: false, phoneVerified: true, onboardingCompleted: true } as ReturnType<typeof useAuth>);

    renderProtected();

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });

  it('allows an unverified user through when allowUnverified is set (e.g. the verify-phone screen itself)', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', bootstrapping: false, phoneVerified: false, onboardingCompleted: true } as ReturnType<typeof useAuth>);

    renderProtected(true);

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });

  it('renders the onboarding flow instead of children when onboarding is not complete and step is not tour', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', bootstrapping: false, phoneVerified: true, onboardingCompleted: false } as ReturnType<typeof useAuth>);
    vi.mocked(useOnboardingUI).mockReturnValue({ step: 'welcome', setStep: vi.fn() });

    renderProtected();

    expect(screen.getByTestId('onboarding-flow')).toBeInTheDocument();
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });

  it('renders the real children plus the tour overlay when step is tour', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', bootstrapping: false, phoneVerified: true, onboardingCompleted: false } as ReturnType<typeof useAuth>);
    vi.mocked(useOnboardingUI).mockReturnValue({ step: 'tour', setStep: vi.fn() });

    renderProtected();

    expect(screen.getByText('Protected content')).toBeInTheDocument();
    expect(screen.queryByTestId('onboarding-flow')).not.toBeInTheDocument();
  });

  it('allowUnverified routes skip the onboarding gate too', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', bootstrapping: false, phoneVerified: false, onboardingCompleted: false } as ReturnType<typeof useAuth>);
    vi.mocked(useOnboardingUI).mockReturnValue({ step: 'welcome', setStep: vi.fn() });

    renderProtected(true);

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });
});
