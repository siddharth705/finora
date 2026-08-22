import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import Login from './Login';
import { useAdminAuth, AdminAccessError } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { setupApi } from '../api/endpoints';
import { AUTH_MFA_REQUIRED } from '../api/errorCodes';

// Real AdminAccessError kept (not replaced), not just useAdminAuth mocked -- Login.tsx does
// `err instanceof AdminAccessError` in its login() catch block to decide whether to start the
// MFA-challenge step; a mocked-away class would make that check always false.
vi.mock('../context/AdminAuthContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../context/AdminAuthContext')>();
  return { ...actual, useAdminAuth: vi.fn() };
});
vi.mock('../api/endpoints', () => ({
  setupApi: { status: vi.fn() },
}));

function renderLogin(state?: { message?: string }) {
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/login', state }]}>
      <Routes>
        <Route path="/login" element={<Login />} />
        <Route path="/setup" element={<div>Setup page</div>} />
        <Route path="/verify-phone" element={<div>Verify phone page</div>} />
        <Route path="/" element={<div>Dashboard page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('Login', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ token: null, login: vi.fn() }));
    vi.mocked(setupApi.status).mockReset();
  });

  it('redirects to /setup when the platform has never been initialized', async () => {
    vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: true, installationKeyAvailable: true });

    renderLogin();

    await waitFor(() => expect(screen.getByText('Setup page')).toBeInTheDocument());
  });

  it('shows the normal sign-in form once setup has already been completed', async () => {
    vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: false, installationKeyAvailable: true });

    renderLogin();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument());
    expect(screen.queryByText('Setup page')).not.toBeInTheDocument();
  });

  it('still renders the sign-in form if the setup status check itself fails', async () => {
    // A backend that's briefly unreachable must never trap the login page from rendering at all.
    vi.mocked(setupApi.status).mockRejectedValue(new Error('network error'));

    renderLogin();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument());
  });

  it('redirects straight to the dashboard when already signed in and verified', async () => {
    vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: false, installationKeyAvailable: true });
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ token: 'existing-token', phoneVerified: true, login: vi.fn() }));

    renderLogin();

    await waitFor(() => expect(screen.getByText('Dashboard page')).toBeInTheDocument());
  });

  it('redirects to phone verification when signed in but not yet verified', async () => {
    vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: false, installationKeyAvailable: true });
    vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ token: 'existing-token', phoneVerified: false, login: vi.fn() }));

    renderLogin();

    await waitFor(() => expect(screen.getByText('Verify phone page')).toBeInTheDocument());
  });

  // This page previously never read location.state at all -- ResetPassword.tsx's
  // navigate('/login', { state: { message } }) on a successful reset did nothing, so an admin who
  // reset their password landed here with zero acknowledgment it worked.
  it('shows the confirmation message when arriving with router state from a password reset', async () => {
    vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: false, installationKeyAvailable: true });

    renderLogin({ message: 'Password reset successfully. Please sign in using your new password.' });

    expect(await screen.findByText(/password reset successfully/i)).toBeInTheDocument();
  });

  it('shows no banner on an ordinary, direct visit with no router state', async () => {
    vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: false, installationKeyAvailable: true });

    renderLogin();

    await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument());
    expect(screen.queryByText(/please sign in using your new password/i)).not.toBeInTheDocument();
  });

  // Admin MFA UI (SEC-03): login() rejecting with AUTH_MFA_REQUIRED means the password was
  // correct and this is a second step to start, not a failure to display.
  describe('two-factor authentication', () => {
    async function submitCredentials(user: ReturnType<typeof userEvent.setup>) {
      await user.type(screen.getByLabelText('Email or phone'), 'admin@finora.test');
      await user.type(screen.getByLabelText('Password'), 'correct-horse-battery-staple');
      await user.click(screen.getByRole('button', { name: 'Sign in' }));
    }

    it('starts the code-entry step when login() comes back AUTH_MFA_REQUIRED', async () => {
      const user = userEvent.setup();
      vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: false, installationKeyAvailable: true });
      const login = vi.fn().mockRejectedValue(
        new AdminAccessError('MFA required.', AUTH_MFA_REQUIRED, { mfaChallengeToken: 'chal-123' }));
      vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ token: null, login }));

      renderLogin();
      await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument());
      await submitCredentials(user);

      expect(await screen.findByText('Two-factor authentication')).toBeInTheDocument();
      expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
    });

    it('signs in once the code is verified', async () => {
      const user = userEvent.setup();
      vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: false, installationKeyAvailable: true });
      const login = vi.fn().mockRejectedValue(
        new AdminAccessError('MFA required.', AUTH_MFA_REQUIRED, { mfaChallengeToken: 'chal-123' }));
      const completeMfaChallenge = vi.fn().mockResolvedValue(true);
      vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ token: null, login, completeMfaChallenge }));

      renderLogin();
      await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument());
      await submitCredentials(user);
      await screen.findByText('Two-factor authentication');

      await user.type(screen.getByLabelText('Code'), '123456');
      await user.click(screen.getByRole('button', { name: 'Verify' }));

      expect(completeMfaChallenge).toHaveBeenCalledWith('chal-123', '123456');
      await waitFor(() => expect(screen.getByText('Dashboard page')).toBeInTheDocument());
    });

    it('shows an error and lets the admin retry on a wrong code', async () => {
      const user = userEvent.setup();
      vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: false, installationKeyAvailable: true });
      const login = vi.fn().mockRejectedValue(
        new AdminAccessError('MFA required.', AUTH_MFA_REQUIRED, { mfaChallengeToken: 'chal-123' }));
      const completeMfaChallenge = vi.fn().mockRejectedValue(
        new AdminAccessError("That code didn't work. Check your authenticator app and try again."));
      vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ token: null, login, completeMfaChallenge }));

      renderLogin();
      await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument());
      await submitCredentials(user);
      await screen.findByText('Two-factor authentication');

      await user.type(screen.getByLabelText('Code'), '000000');
      await user.click(screen.getByRole('button', { name: 'Verify' }));

      expect(await screen.findByText(/that code didn't work/i)).toBeInTheDocument();
      // Still on the code-entry step, not bounced back to a failure-only message.
      expect(screen.getByLabelText('Code')).toBeInTheDocument();
    });

    it('returns to the sign-in form when "Back to sign in" is clicked', async () => {
      const user = userEvent.setup();
      vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: false, installationKeyAvailable: true });
      const login = vi.fn().mockRejectedValue(
        new AdminAccessError('MFA required.', AUTH_MFA_REQUIRED, { mfaChallengeToken: 'chal-123' }));
      vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({ token: null, login }));

      renderLogin();
      await waitFor(() => expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument());
      await submitCredentials(user);
      await screen.findByText('Two-factor authentication');

      await user.click(screen.getByRole('button', { name: 'Back to sign in' }));

      expect(screen.getByLabelText('Password')).toBeInTheDocument();
      expect(screen.queryByText('Two-factor authentication')).not.toBeInTheDocument();
    });
  });
});
