import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import VerifyEmail from './VerifyEmail';
import { authApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  authApi: { verifyEmail: vi.fn() },
}));

function renderVerifyEmail(token: string | null) {
  const path = token ? `/verify-email?token=${token}` : '/verify-email';
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/verify-email" element={<VerifyEmail />} />
        <Route path="/login" element={<p>Sign in page</p>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('VerifyEmail', () => {
  beforeEach(() => {
    vi.mocked(authApi.verifyEmail).mockReset();
  });

  it('shows a loading state, then success, once the token verifies', async () => {
    vi.mocked(authApi.verifyEmail).mockResolvedValue({ message: 'Your email has been verified.' });

    renderVerifyEmail('real-token');

    expect(screen.getByText('Verifying your email…')).toBeInTheDocument();

    await waitFor(() => expect(screen.getByText('Email verified')).toBeInTheDocument());
    expect(authApi.verifyEmail).toHaveBeenCalledWith('real-token');
    expect(screen.getByRole('link', { name: 'Continue to sign in' })).toHaveAttribute('href', '/login');
  });

  it('shows the backend error message when the token is invalid or expired', async () => {
    vi.mocked(authApi.verifyEmail).mockRejectedValue({
      response: { data: { message: 'This verification link has expired.' } },
    });

    renderVerifyEmail('stale-token');

    await waitFor(() => expect(screen.getByText('Verification failed')).toBeInTheDocument());
    expect(screen.getByText('This verification link has expired.')).toBeInTheDocument();
  });

  it('shows an error immediately, with no API call, when the link has no token at all', () => {
    renderVerifyEmail(null);

    expect(screen.getByText('Verification failed')).toBeInTheDocument();
    expect(screen.getByText('No verification token found in the link.')).toBeInTheDocument();
    expect(authApi.verifyEmail).not.toHaveBeenCalled();
  });
});
