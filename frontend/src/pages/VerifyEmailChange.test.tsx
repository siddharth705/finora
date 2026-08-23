import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import VerifyEmailChange from './VerifyEmailChange';
import { emailChangeApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  emailChangeApi: { verify: vi.fn(), complete: vi.fn() },
}));

function renderPage(sessionId: string | null, token: string | null) {
  const params = new URLSearchParams();
  if (sessionId) params.set('sessionId', sessionId);
  if (token) params.set('token', token);
  const path = params.toString() ? `/email-change-verify?${params.toString()}` : '/email-change-verify';
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/email-change-verify" element={<VerifyEmailChange />} />
        <Route path="/app/profile" element={<p>Profile page</p>} />
      </Routes>
    </MemoryRouter>
  );
}

describe('VerifyEmailChange', () => {
  beforeEach(() => {
    vi.mocked(emailChangeApi.verify).mockReset();
    vi.mocked(emailChangeApi.complete).mockReset();
  });

  it('shows a loading state, then chains verify() into complete(), showing the new email on success', async () => {
    vi.mocked(emailChangeApi.verify).mockResolvedValue({ message: 'Verified.' });
    vi.mocked(emailChangeApi.complete).mockResolvedValue({ message: 'Your email address has been updated.', email: 'jane.new@example.com' });

    renderPage('session-1', 'real-token');

    expect(screen.getByText('Confirming your new email…')).toBeInTheDocument();

    await waitFor(() => expect(screen.getByText('Email updated')).toBeInTheDocument());
    expect(emailChangeApi.verify).toHaveBeenCalledWith('session-1', 'real-token');
    expect(emailChangeApi.complete).toHaveBeenCalledWith('session-1');
    expect(screen.getByText(/jane\.new@example\.com/)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to Profile' })).toHaveAttribute('href', '/app/profile');
  });

  it('shows the backend error message and never calls complete() when verify() rejects', async () => {
    vi.mocked(emailChangeApi.verify).mockRejectedValue({
      response: { data: { message: 'This verification link is invalid.' } },
    });

    renderPage('session-1', 'wrong-token');

    await waitFor(() => expect(screen.getByText('Confirmation failed')).toBeInTheDocument());
    expect(screen.getByText('This verification link is invalid.')).toBeInTheDocument();
    expect(emailChangeApi.complete).not.toHaveBeenCalled();
  });

  it('shows an error immediately, with no API calls, when the link is missing sessionId or token', () => {
    renderPage('session-1', null);

    expect(screen.getByText('Confirmation failed')).toBeInTheDocument();
    expect(screen.getByText(/missing information/)).toBeInTheDocument();
    expect(emailChangeApi.verify).not.toHaveBeenCalled();
  });
});
