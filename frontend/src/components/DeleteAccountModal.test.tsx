import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { DeleteAccountModal } from './DeleteAccountModal';
import { passwordChangeApi, accountLifecycleApi } from '../api/endpoints';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode } from '../lib/phoneAuth';
import { isGoogleLoginConfigured, loadGoogleIdentityServices } from '../lib/googleIdentity';

vi.mock('../api/endpoints', () => ({
  passwordChangeApi: { start: vi.fn(), verifyOtp: vi.fn() },
  accountLifecycleApi: { deleteAccount: vi.fn() },
}));

vi.mock('../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: vi.fn(),
  confirmPhoneVerificationCode: vi.fn(),
  resetPhoneVerification: vi.fn(),
}));

// See GoogleSignInButton.test.tsx's own doc comment for why this is mocked wholesale.
vi.mock('../lib/googleIdentity', () => ({
  isGoogleLoginConfigured: vi.fn(),
  loadGoogleIdentityServices: vi.fn(),
}));

const FAKE_CONFIRMATION = { confirm: vi.fn() } as any;

describe('DeleteAccountModal', () => {
  beforeEach(() => {
    vi.mocked(passwordChangeApi.start).mockReset().mockResolvedValue({
      sessionId: 'session-1', phoneNumber: '+919876543705', maskedPhone: '+•••••••••705', // synthetic-ok
    });
    vi.mocked(sendPhoneVerificationCode).mockReset().mockResolvedValue(FAKE_CONFIRMATION);
    vi.mocked(confirmPhoneVerificationCode).mockReset().mockResolvedValue('fake-firebase-id-token');
    vi.mocked(passwordChangeApi.verifyOtp).mockReset().mockResolvedValue({ message: 'Verified.' });
    vi.mocked(accountLifecycleApi.deleteAccount).mockReset().mockResolvedValue({ message: 'Account deleted.' });
  });

  describe('a password account', () => {
    it('starts a session with the entered password and advances to the OTP step', async () => {
      const user = userEvent.setup();
      render(<DeleteAccountModal onClose={vi.fn()} onDeleted={vi.fn()} signInMethod="PASSWORD" />);

      await user.type(screen.getByLabelText(/current password/i), 'OldPass123!');
      await user.click(screen.getByRole('button', { name: /send code/i }));

      await waitFor(() => expect(passwordChangeApi.start).toHaveBeenCalledWith('OldPass123!', null));
      expect(await screen.findByText(/\+•••••••••705/)).toBeInTheDocument();
    });
  });

  describe('a Google Sign-In account', () => {
    beforeEach(() => {
      vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
      vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
      vi.mocked(loadGoogleIdentityServices).mockResolvedValue({
        initialize: vi.fn(), renderButton: vi.fn(),
      } as any);
    });

    it('offers no password field at all', async () => {
      render(<DeleteAccountModal onClose={vi.fn()} onDeleted={vi.fn()} signInMethod="GOOGLE" />);

      await waitFor(() => expect(loadGoogleIdentityServices).toHaveBeenCalled());
      expect(screen.queryByLabelText(/current password/i)).not.toBeInTheDocument();
    });

    it('starts a session with the fresh Google credential instead of a password', async () => {
      const initialize = vi.fn();
      vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);
      render(<DeleteAccountModal onClose={vi.fn()} onDeleted={vi.fn()} signInMethod="GOOGLE" />);
      await waitFor(() => expect(initialize).toHaveBeenCalled());

      const { callback } = initialize.mock.calls[0][0];
      callback({ credential: 'fresh-google-id-token' });

      await waitFor(() => expect(passwordChangeApi.start).toHaveBeenCalledWith(null, 'fresh-google-id-token'));
      expect(await screen.findByText(/\+•••••••••705/)).toBeInTheDocument();
    });
  });
});
