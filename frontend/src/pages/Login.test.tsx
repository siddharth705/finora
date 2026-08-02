import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import Login from './Login';
import { AuthProvider } from '../context/AuthContext';

// Only the post-redirect confirmation banner is under test here (arriving from
// ChangePasswordModal or ResetPassword's own navigate('/login', { state: { message } })) --
// everything else on this page (the sign-in form itself) has its own established behavior and
// isn't the subject of this change.
vi.mock('../api/endpoints', () => ({
  authApi: { login: vi.fn(), logout: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function renderLogin(state?: { message?: string }) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state }]}>
      <AuthProvider>
        <Login />
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('Login — post-redirect confirmation banner', () => {
  it('shows the confirmation message when arriving with router state from a password change', () => {
    renderLogin({ message: 'Password updated successfully. Please sign in using your new password.' });

    expect(screen.getByText(/password updated successfully/i)).toBeInTheDocument();
  });

  it('shows no banner on an ordinary, direct visit with no router state', () => {
    renderLogin();

    expect(screen.queryByText(/please sign in using your new password/i)).not.toBeInTheDocument();
  });
});
