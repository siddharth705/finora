import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { ProtectedRoute } from './ProtectedRoute';
import { useAuth } from '../context/AuthContext';

// useAuth pulls from AuthContext, which itself talks to localStorage/authApi on mount --
// mocking the hook directly (rather than rendering a real AuthProvider) keeps this test focused
// on ProtectedRoute's own redirect logic, which is the thing actually under test here.
vi.mock('../context/AuthContext', () => ({
  useAuth: vi.fn(),
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
        <Route path="/login" element={<div>Login page</div>} />
        <Route path="/verify-phone" element={<div>Verify phone page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('ProtectedRoute', () => {
  beforeEach(() => {
    vi.mocked(useAuth).mockReset();
  });

  it('redirects to /login when there is no token', () => {
    vi.mocked(useAuth).mockReturnValue({ token: null, bootstrapping: false, phoneVerified: false } as ReturnType<typeof useAuth>);

    renderProtected();

    expect(screen.getByText('Login page')).toBeInTheDocument();
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
    vi.mocked(useAuth).mockReturnValue({ token: null, bootstrapping: true, phoneVerified: false } as ReturnType<typeof useAuth>);

    renderProtected();

    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
    expect(screen.queryByText('Login page')).not.toBeInTheDocument();
    expect(screen.queryByText('Verify phone page')).not.toBeInTheDocument();
  });

  it('redirects to /verify-phone when authenticated but not phone-verified', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', bootstrapping: false, phoneVerified: false } as ReturnType<typeof useAuth>);

    renderProtected();

    expect(screen.getByText('Verify phone page')).toBeInTheDocument();
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });

  it('renders the protected content once authenticated and phone-verified', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', bootstrapping: false, phoneVerified: true } as ReturnType<typeof useAuth>);

    renderProtected();

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });

  it('allows an unverified user through when allowUnverified is set (e.g. the verify-phone screen itself)', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', bootstrapping: false, phoneVerified: false } as ReturnType<typeof useAuth>);

    renderProtected(true);

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });
});
