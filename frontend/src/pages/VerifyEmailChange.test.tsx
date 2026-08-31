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

  it('shows verify()\'s own error message when both verify() and the complete() fallback fail (a genuinely wrong/expired token)', async () => {
    vi.mocked(emailChangeApi.verify).mockRejectedValue({
      response: { data: { message: 'This verification link is invalid.' } },
    });
    vi.mocked(emailChangeApi.complete).mockRejectedValue({
      response: { data: { message: 'Confirm the link sent to your new email before completing this change.' } },
    });

    renderPage('session-1', 'wrong-token');

    await waitFor(() => expect(screen.getByText('Confirmation failed')).toBeInTheDocument());
    // verify()'s own message is shown, not complete()'s -- it's the more specific one for a
    // genuinely bad token; complete()'s generic "confirm the link first" would be confusing here.
    expect(screen.getByText('This verification link is invalid.')).toBeInTheDocument();
  });

  /** Bug fix (self-review): verify() requires the session to be exactly STARTED server-side --
   *  revisiting this page (refresh, double-click, a second tab) after the first successful
   *  visit already advanced it past STARTED, so verify() fails with a generic "already been
   *  completed" message even though the email change genuinely succeeded. Falling back to
   *  complete() -- idempotent, and authoritative about the real outcome -- turns that false
   *  failure into the success message it actually should show. */
  it('falls back to complete() when verify() fails because the session was already verified/completed on an earlier visit, and shows success', async () => {
    vi.mocked(emailChangeApi.verify).mockRejectedValue({
      response: { data: { message: 'This step has already been completed, or the session is no longer valid. Please start again.' } },
    });
    vi.mocked(emailChangeApi.complete).mockResolvedValue({ message: 'Your email address has been updated.', email: 'jane.new@example.com' });

    renderPage('session-1', 'already-used-token');

    await waitFor(() => expect(screen.getByText('Email updated')).toBeInTheDocument());
    expect(emailChangeApi.complete).toHaveBeenCalledWith('session-1');
    expect(screen.getByText(/jane\.new@example\.com/)).toBeInTheDocument();
  });

  it('shows an error immediately, with no API calls, when the link is missing sessionId or token', () => {
    renderPage('session-1', null);

    expect(screen.getByText('Confirmation failed')).toBeInTheDocument();
    expect(screen.getByText(/missing information/)).toBeInTheDocument();
    expect(emailChangeApi.verify).not.toHaveBeenCalled();
  });

  /**
   * Phase 4 mobile: mobile has no way to intercept this page's own https:// URL (no hosted
   * apple-app-site-association/assetlinks.json for a true universal/app link -- see
   * RootNavigator.tsx's own doc comment on the mobile side), so anyone reading the confirmation
   * email on their phone needs an explicit way to jump into the app instead. finora:// is the
   * custom scheme RootNavigator's `linking` config registers there.
   */
  it('offers a link to open the confirmation in the Fynora app, carrying the same sessionId and token', () => {
    renderPage('session-1', 'real-token');

    expect(screen.getByRole('link', { name: /open in the fynora app/i })).toHaveAttribute(
      'href', 'finora://email-change-verify?sessionId=session-1&token=real-token'
    );
  });

  it('omits the open-in-app link when the URL is missing sessionId or token, since there is nothing valid to hand the app', () => {
    renderPage('session-1', null);

    expect(screen.queryByRole('link', { name: /open in the finora app/i })).not.toBeInTheDocument();
  });
});
