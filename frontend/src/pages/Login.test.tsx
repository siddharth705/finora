import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import Login from './Login';
import { AuthProvider } from '../context/AuthContext';
import { authApi } from '../api/endpoints';
import { AUTH_ACCOUNT_DEACTIVATED } from '../api/errorCodes';

// Only the post-redirect confirmation banner and the deactivated-account reactivation prompt are
// under test here -- everything else on this page (the sign-in form itself) has its own
// established behavior and isn't the subject of this change.
vi.mock('../api/endpoints', () => ({
  authApi: { login: vi.fn(), reactivate: vi.fn(), logout: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function renderLogin(state?: { message?: string }) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state }]}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/app" element={<p>Dashboard</p>} />
          <Route path="/verify-phone" element={<p>Verify your phone</p>} />
        </Routes>
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

// AuthService.login()'s deactivated branch: the password already checked out server-side, so
// this is a distinct UI state from an ordinary login failure, not just another error message.
describe('Login — deactivated account reactivation prompt', () => {
  function deactivatedError() {
    return {
      response: {
        data: {
          message: 'This account is deactivated.',
          // Imported from errorCodes.ts (a real, unmocked module -- see its own doc comment) rather
          // than hand-typed here, precisely because this suite got the wire value wrong once before
          // and every test still passed, since the mock and Login.tsx's own check agreed with each
          // other while both were wrong.
          errorCode: AUTH_ACCOUNT_DEACTIVATED,
          details: { reactivationToken: 'raw-reactivation-token' },
        },
      },
    };
  }

  async function submitLogin(user: ReturnType<typeof userEvent.setup>) {
    await user.type(screen.getByLabelText(/email or mobile number/i), 'jane@example.com');
    await user.type(screen.getByLabelText(/^password$/i), 'CorrectPassword123');
    await user.click(screen.getByRole('button', { name: /sign in/i }));
  }

  it('replaces the sign-in form with a reactivation prompt instead of a generic error', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockReset().mockRejectedValue(deactivatedError());
    renderLogin();

    await submitLogin(user);

    expect(await screen.findByRole('button', { name: /reactivate my account/i })).toBeInTheDocument();
    expect(screen.queryByLabelText(/email or mobile number/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/login failed/i)).not.toBeInTheDocument();
  });

  it('reactivates and lands on the dashboard once confirmed', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockReset().mockRejectedValue(deactivatedError());
    vi.mocked(authApi.reactivate).mockReset().mockResolvedValue({
      data: {
        token: 'new-access-token', refreshToken: 'new-refresh-token',
        email: 'jane@example.com', fullName: 'Jane', phoneVerified: true, maskedPhone: null,
      },
    } as any);
    renderLogin();
    await submitLogin(user);

    await user.click(await screen.findByRole('button', { name: /reactivate my account/i }));

    expect(authApi.reactivate).toHaveBeenCalledWith('raw-reactivation-token');
    await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
  });

  it('routes to phone verification instead when the reactivated account has not verified its phone', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockReset().mockRejectedValue(deactivatedError());
    vi.mocked(authApi.reactivate).mockReset().mockResolvedValue({
      data: {
        token: 'new-access-token', refreshToken: 'new-refresh-token',
        email: 'jane@example.com', fullName: 'Jane', phoneVerified: false, maskedPhone: '+•••••••••705',
      },
    } as any);
    renderLogin();
    await submitLogin(user);

    await user.click(await screen.findByRole('button', { name: /reactivate my account/i }));

    await waitFor(() => expect(screen.getByText('Verify your phone')).toBeInTheDocument());
  });

  it('goes back to the sign-in form without reactivating', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockReset().mockRejectedValue(deactivatedError());
    vi.mocked(authApi.reactivate).mockReset();
    renderLogin();
    await submitLogin(user);
    await screen.findByRole('button', { name: /reactivate my account/i });

    await user.click(screen.getByRole('button', { name: /not you\? go back/i }));

    expect(screen.getByLabelText(/email or mobile number/i)).toBeInTheDocument();
    expect(authApi.reactivate).not.toHaveBeenCalled();
  });
});
