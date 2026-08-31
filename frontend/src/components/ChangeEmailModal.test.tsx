import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ChangeEmailModal } from './ChangeEmailModal';
import { emailChangeApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  emailChangeApi: { start: vi.fn() },
}));

function renderModal(onClose = vi.fn(), signInMethod: 'PASSWORD' | 'GOOGLE' = 'PASSWORD') {
  return { onClose, ...render(<ChangeEmailModal onClose={onClose} signInMethod={signInMethod} />) };
}

describe('ChangeEmailModal', () => {
  beforeEach(() => {
    vi.mocked(emailChangeApi.start).mockReset().mockResolvedValue({ sessionId: 'session-1', devVerifyLink: null });
  });

  it('keeps Send confirmation link disabled until both a valid new email and a current password are entered', async () => {
    const user = userEvent.setup();
    renderModal();

    const button = screen.getByRole('button', { name: /send confirmation link/i });
    expect(button).toBeDisabled();

    await user.type(screen.getByLabelText(/new email address/i), 'jane.new@example.com');
    expect(button).toBeDisabled();

    await user.type(screen.getByLabelText(/^current password$/i), 'CorrectPassword');
    expect(button).toBeEnabled();
  });

  it('calls emailChangeApi.start with the credential and new email, then shows the "check your inbox" confirmation', async () => {
    const user = userEvent.setup();
    renderModal();

    await user.type(screen.getByLabelText(/new email address/i), 'jane.new@example.com');
    await user.type(screen.getByLabelText(/^current password$/i), 'CorrectPassword');
    await user.click(screen.getByRole('button', { name: /send confirmation link/i }));

    expect(emailChangeApi.start).toHaveBeenCalledWith('CorrectPassword', null, null, 'jane.new@example.com');
    expect(await screen.findByText(/check your inbox/i)).toBeInTheDocument();
    expect(screen.getByText(/jane\.new@example\.com/)).toBeInTheDocument();
  });

  it('shows the dev verify link when the backend returns one (no email provider configured)', async () => {
    vi.mocked(emailChangeApi.start).mockResolvedValue({
      sessionId: 'session-1', devVerifyLink: 'https://app.finora.test/email-change-verify?sessionId=session-1&token=abc',
    });
    const user = userEvent.setup();
    renderModal();

    await user.type(screen.getByLabelText(/new email address/i), 'jane.new@example.com');
    await user.type(screen.getByLabelText(/^current password$/i), 'CorrectPassword');
    await user.click(screen.getByRole('button', { name: /send confirmation link/i }));

    const link = await screen.findByRole('link', { name: /email-change-verify/ });
    expect(link).toHaveAttribute('href', 'https://app.finora.test/email-change-verify?sessionId=session-1&token=abc');
  });

  it('shows the server error inline (e.g. wrong current password) without advancing', async () => {
    vi.mocked(emailChangeApi.start).mockRejectedValue({
      response: { data: { message: 'Current password is incorrect.' } },
    });
    const user = userEvent.setup();
    renderModal();

    await user.type(screen.getByLabelText(/new email address/i), 'jane.new@example.com');
    await user.type(screen.getByLabelText(/^current password$/i), 'WrongPassword');
    await user.click(screen.getByRole('button', { name: /send confirmation link/i }));

    expect(await screen.findByText(/current password is incorrect/i)).toBeInTheDocument();
    expect(screen.queryByText(/check your inbox/i)).not.toBeInTheDocument();
  });

  it('calls onClose when Cancel is clicked', async () => {
    const user = userEvent.setup();
    const { onClose } = renderModal();

    await user.click(screen.getByRole('button', { name: /cancel/i }));

    expect(onClose).toHaveBeenCalled();
  });

  it('for a Google-signInMethod account, shows the Google reauth prompt only once a valid email is entered, and never renders a password field', async () => {
    const user = userEvent.setup();
    renderModal(vi.fn(), 'GOOGLE');

    expect(screen.queryByLabelText(/^current password$/i)).not.toBeInTheDocument();
    expect(screen.getByText(/enter a new email address to continue/i)).toBeInTheDocument();

    await user.type(screen.getByLabelText(/new email address/i), 'jane.new@example.com');

    expect(screen.queryByText(/enter a new email address to continue/i)).not.toBeInTheDocument();
  });
});
