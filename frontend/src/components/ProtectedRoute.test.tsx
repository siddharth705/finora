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
    vi.mocked(useAuth).mockReturnValue({ token: null, phoneVerified: false } as ReturnType<typeof useAuth>);

    renderProtected();

    expect(screen.getByText('Login page')).toBeInTheDocument();
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });

  it('redirects to /verify-phone when authenticated but not phone-verified', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', phoneVerified: false } as ReturnType<typeof useAuth>);

    renderProtected();

    expect(screen.getByText('Verify phone page')).toBeInTheDocument();
    expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
  });

  it('renders the protected content once authenticated and phone-verified', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', phoneVerified: true } as ReturnType<typeof useAuth>);

    renderProtected();

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });

  it('allows an unverified user through when allowUnverified is set (e.g. the verify-phone screen itself)', () => {
    vi.mocked(useAuth).mockReturnValue({ token: 'tok', phoneVerified: false } as ReturnType<typeof useAuth>);

    renderProtected(true);

    expect(screen.getByText('Protected content')).toBeInTheDocument();
  });
});
