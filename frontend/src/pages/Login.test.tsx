import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
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

// A stand-in for the real VerifyPhone.tsx (out of scope here -- its own test file covers it),
// but one that surfaces the fromLogin router-state flag Login.tsx is responsible for setting.
// "Verify your phone" stays its own exact-text element so every existing assertion below keeps
// matching regardless of whether the flag marker is also present.
function VerifyPhoneStub() {
  const location = useLocation();
  const fromLogin = Boolean((location.state as { fromLogin?: boolean } | null)?.fromLogin);
  return (
    <div>
      <p>Verify your phone</p>
      {fromLogin && <p>fromLogin=true</p>}
    </div>
  );
}

function renderLogin(state?: { message?: string; identifier?: string; method?: string }) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state }]}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/app" element={<p>Dashboard</p>} />
          <Route path="/verify-phone" element={<VerifyPhoneStub />} />
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

// Phase 3 (§2.2/§2.4): AuthEntry.tsx sends the identifier it already resolved, plus the
// account's sign-in method, via router state -- so this screen doesn't ask the user to retype
// the identifier, and doesn't show a password field/forgot-password link for an account that has
// no password to check it against (the backend already refuses this; this just stops the
// dead-end form from being shown at all -- see the doc comment on Login.tsx).
describe('Login — prefill and OAuth hint from AuthEntry', () => {
  it('prefills the identifier field when arriving with router state from AuthEntry', () => {
    renderLogin({ identifier: 'jane@example.com', method: 'PASSWORD' });

    expect(screen.getByLabelText(/email or mobile number/i)).toHaveValue('jane@example.com');
  });

  it('hides the password field and forgot-password link, and shows a Google hint, when method is GOOGLE', () => {
    renderLogin({ identifier: 'jane@example.com', method: 'GOOGLE' });

    expect(screen.queryByLabelText(/^password$/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /forgot password/i })).not.toBeInTheDocument();
    expect(screen.getByText(/this account signs in with google/i)).toBeInTheDocument();
  });

  it('hides the password field and shows an Apple hint, when method is APPLE', () => {
    renderLogin({ identifier: 'jane@example.com', method: 'APPLE' });

    expect(screen.queryByLabelText(/^password$/i)).not.toBeInTheDocument();
    expect(screen.getByText(/this account signs in with apple/i)).toBeInTheDocument();
  });

  it('does not ask for a password when the identifier field is submitted via Enter while the Google hint is shown', async () => {
    renderLogin({ identifier: 'jane@example.com', method: 'GOOGLE' });
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/email or mobile number/i), '{Enter}');

    expect(screen.queryByText(/enter your password/i)).not.toBeInTheDocument();
    expect(authApi.login).not.toHaveBeenCalled();
  });

  it('shows the ordinary password form with no hint on a direct visit with no router state', () => {
    renderLogin();

    expect(screen.getByLabelText(/^password$/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /forgot password/i })).toBeInTheDocument();
    expect(screen.queryByText(/this account signs in with/i)).not.toBeInTheDocument();
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

  it('routes to phone verification instead when the reactivated account has not verified its phone, flagged as a returning user', async () => {
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
    // A user reactivating an account is a returning one too, arguably more so -- same fromLogin
    // treatment as an ordinary unverified login below.
    expect(screen.getByText('fromLogin=true')).toBeInTheDocument();
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

// An ordinary (non-deactivated) login where the account just hasn't verified its phone yet --
// distinct from Register.tsx's own identical navigate call, which never sets this: a brand-new
// signup landing on /verify-phone for the first time is not a "welcome back" moment, but a
// returning user who still hasn't finished verifying is.
describe('Login — flags a returning-but-unverified user for VerifyPhone', () => {
  it('routes to phone verification with fromLogin set, on an ordinary successful login', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockReset().mockResolvedValue({
      data: {
        token: 'access-token', refreshToken: 'refresh-token',
        email: 'jane@example.com', fullName: 'Jane', phoneVerified: false, maskedPhone: '+•••••••••705',
      },
    } as any);
    renderLogin();

    await user.type(screen.getByLabelText(/email or mobile number/i), 'jane@example.com');
    await user.type(screen.getByLabelText(/^password$/i), 'CorrectPassword123');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(screen.getByText('Verify your phone')).toBeInTheDocument());
    expect(screen.getByText('fromLogin=true')).toBeInTheDocument();
  });

  it('does not set fromLogin when the account is already verified and lands on the dashboard', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockReset().mockResolvedValue({
      data: {
        token: 'access-token', refreshToken: 'refresh-token',
        email: 'jane@example.com', fullName: 'Jane', phoneVerified: true, maskedPhone: null,
      },
    } as any);
    renderLogin();

    await user.type(screen.getByLabelText(/email or mobile number/i), 'jane@example.com');
    await user.type(screen.getByLabelText(/^password$/i), 'CorrectPassword123');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(screen.getByText('Dashboard')).toBeInTheDocument());
  });
});
