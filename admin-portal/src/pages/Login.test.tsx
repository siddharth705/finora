import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import Login from './Login';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { setupApi } from '../api/endpoints';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
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
});
