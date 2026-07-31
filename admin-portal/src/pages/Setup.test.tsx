import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import Setup from './Setup';
import { setupApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  setupApi: { status: vi.fn(), loginAsBootstrap: vi.fn(), complete: vi.fn() },
}));

function renderSetup() {
  return render(
    <MemoryRouter initialEntries={['/setup']}>
      <Routes>
        <Route path="/setup" element={<Setup />} />
        <Route path="/login" element={<div>Login page</div>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('Setup', () => {
  beforeEach(() => {
    vi.mocked(setupApi.status).mockReset();
    vi.mocked(setupApi.loginAsBootstrap).mockReset();
    vi.mocked(setupApi.complete).mockReset();
  });

  it('redirects to /login when setup has already been completed', async () => {
    vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: false, installationKeyAvailable: true });

    renderSetup();

    await waitFor(() => expect(screen.getByText('Login page')).toBeInTheDocument());
  });

  it('never shows an identifier field -- the installer only ever asks for the key', async () => {
    // BOOTSTRAP_ADMIN is a fixed internal constant, not a decision the installing operator
    // should ever need to make or even see.
    vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: true, installationKeyAvailable: true });

    renderSetup();

    await waitFor(() => expect(screen.getByPlaceholderText('Installation key')).toBeInTheDocument());
    expect(screen.queryByText(/identifier/i)).not.toBeInTheDocument();
  });

  it('walks through the installation key and admin creation steps end to end', async () => {
    vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: true, installationKeyAvailable: true });
    vi.mocked(setupApi.loginAsBootstrap).mockResolvedValue({
      token: 'bootstrap-token', refreshToken: 'irrelevant', email: 'BOOTSTRAP_ADMIN', fullName: 'Bootstrap Installer',
    });
    vi.mocked(setupApi.complete).mockResolvedValue({ success: true, message: 'ok', data: null, timestamp: '', errorCode: null, requestId: null });
    const user = userEvent.setup();

    renderSetup();

    await waitFor(() => expect(screen.getByPlaceholderText('Installation key')).toBeInTheDocument());
    await user.type(screen.getByPlaceholderText('Installation key'), 'the-printed-key');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    // Uses the fixed internal identifier automatically -- the person never typed or saw it.
    expect(setupApi.loginAsBootstrap).toHaveBeenCalledWith('BOOTSTRAP_ADMIN', 'the-printed-key');
    await waitFor(() => expect(screen.getByText('Create your administrator account')).toBeInTheDocument());

    await user.type(screen.getByPlaceholderText('Full name'), 'Amy Admin');
    await user.type(screen.getByPlaceholderText('Email'), 'amy@example.com');
    await user.type(screen.getByPlaceholderText('Phone number (e.g. +91XXXXXXXXXX)'), '+919876543210');
    await user.type(screen.getByPlaceholderText('Password (at least 8 characters)'), 'a-real-strong-password');
    await user.type(screen.getByPlaceholderText('Confirm password'), 'a-real-strong-password');
    await user.click(screen.getByRole('button', { name: 'Finish setup' }));

    // The session token from step 1 is what authorizes this call, not anything from step 2 --
    // this is the whole point of keeping it in local state rather than the normal admin session.
    expect(setupApi.complete).toHaveBeenCalledWith('bootstrap-token', {
      email: 'amy@example.com', password: 'a-real-strong-password', fullName: 'Amy Admin', phoneNumber: '+919876543210',
    });
    await waitFor(() => expect(screen.getByText("You're all set")).toBeInTheDocument());
  });

  it('rejects mismatched passwords without ever calling the API', async () => {
    vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: true, installationKeyAvailable: true });
    vi.mocked(setupApi.loginAsBootstrap).mockResolvedValue({
      token: 'bootstrap-token', refreshToken: 'irrelevant', email: 'BOOTSTRAP_ADMIN', fullName: 'Bootstrap Installer',
    });
    const user = userEvent.setup();

    renderSetup();

    await waitFor(() => expect(screen.getByPlaceholderText('Installation key')).toBeInTheDocument());
    await user.type(screen.getByPlaceholderText('Installation key'), 'the-printed-key');
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(screen.getByText('Create your administrator account')).toBeInTheDocument());

    await user.type(screen.getByPlaceholderText('Full name'), 'Amy Admin');
    await user.type(screen.getByPlaceholderText('Email'), 'amy@example.com');
    await user.type(screen.getByPlaceholderText('Phone number (e.g. +91XXXXXXXXXX)'), '+919876543210');
    await user.type(screen.getByPlaceholderText('Password (at least 8 characters)'), 'a-real-strong-password');
    await user.type(screen.getByPlaceholderText('Confirm password'), 'a-different-password');
    await user.click(screen.getByRole('button', { name: 'Finish setup' }));

    expect(screen.getByText('Passwords do not match.')).toBeInTheDocument();
    expect(setupApi.complete).not.toHaveBeenCalled();
  });

  it('shows a specific warning when the backend reports no installation key is available', async () => {
    vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: true, installationKeyAvailable: false });

    renderSetup();

    await waitFor(() => expect(screen.getByText(/couldn't find an installation key/)).toBeInTheDocument());
    // The form itself must still render -- a wrong heuristic on the backend's side (e.g. a race
    // right at startup) shouldn't fully block someone who does have a valid key from trying it.
    expect(screen.getByPlaceholderText('Installation key')).toBeInTheDocument();
  });

  it('shows a friendly message, not a raw backend error, when the key is wrong', async () => {
    vi.mocked(setupApi.status).mockResolvedValue({ setupRequired: true, installationKeyAvailable: true });
    vi.mocked(setupApi.loginAsBootstrap).mockRejectedValue({ response: { data: { message: 'Invalid credentials' } } });
    const user = userEvent.setup();

    renderSetup();

    await waitFor(() => expect(screen.getByPlaceholderText('Installation key')).toBeInTheDocument());
    await user.type(screen.getByPlaceholderText('Installation key'), 'wrong-key');
    await user.click(screen.getByRole('button', { name: 'Continue' }));

    await waitFor(() => expect(screen.getByText(/doesn't match/)).toBeInTheDocument());
    expect(screen.queryByText('Invalid credentials')).not.toBeInTheDocument();
  });
});
