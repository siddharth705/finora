import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ChangePasswordModal } from './ChangePasswordModal';
import { passwordChangeApi } from '../api/endpoints';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode } from '../lib/phoneAuth';

vi.mock('../api/endpoints', () => ({
  passwordChangeApi: { start: vi.fn(), verifyOtp: vi.fn(), complete: vi.fn() },
}));

vi.mock('../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: vi.fn(),
  confirmPhoneVerificationCode: vi.fn(),
  resetPhoneVerification: vi.fn(),
}));

const FAKE_CONFIRMATION = { confirm: vi.fn() } as any;

function renderModal(onClose = vi.fn(), onSuccess = vi.fn()) {
  return { onClose, onSuccess, ...render(<ChangePasswordModal onClose={onClose} onSuccess={onSuccess} />) };
}

async function advanceToOtpStep(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/^current password$/i), 'OldPass123!');
  await user.click(screen.getByRole('button', { name: /send code/i }));
  await screen.findByLabelText(/verification code/i);
}

async function advanceToNewPasswordStep(user: ReturnType<typeof userEvent.setup>) {
  await advanceToOtpStep(user);
  await user.type(screen.getByLabelText(/verification code/i), '654321');
  await user.click(screen.getByRole('button', { name: /^verify$/i }));
  await screen.findByLabelText(/^new password$/i);
}

describe('ChangePasswordModal', () => {
  beforeEach(() => {
    vi.mocked(passwordChangeApi.start).mockReset().mockResolvedValue({
      sessionId: 'session-1', phoneNumber: '+919876543705', maskedPhone: '+•••••••••705',
    });
    vi.mocked(sendPhoneVerificationCode).mockReset().mockResolvedValue(FAKE_CONFIRMATION);
    vi.mocked(confirmPhoneVerificationCode).mockReset().mockResolvedValue('fake-firebase-id-token');
    vi.mocked(passwordChangeApi.verifyOtp).mockReset().mockResolvedValue({ message: 'Verified.' });
    vi.mocked(passwordChangeApi.complete).mockReset().mockResolvedValue({
      message: 'Your password has been updated. This device stays signed in; every other device has been signed out.',
      otherDevicesSignedOut: true,
    });
    localStorage.setItem('finora_refresh_token', 'this-devices-refresh-token');
  });

  describe('Step 1 -- current password', () => {
    it('keeps Send code disabled until a current password is typed', async () => {
      renderModal();
      expect(screen.getByRole('button', { name: /send code/i })).toBeDisabled();

      await userEvent.setup().type(screen.getByLabelText(/^current password$/i), 'OldPass123!');
      expect(screen.getByRole('button', { name: /send code/i })).toBeEnabled();
    });

    it('calls passwordChangeApi.start, sends a Firebase code to the real phone number, and advances to the OTP step', async () => {
      const user = userEvent.setup();
      renderModal();

      await advanceToOtpStep(user);

      expect(passwordChangeApi.start).toHaveBeenCalledWith('OldPass123!');
      expect(sendPhoneVerificationCode).toHaveBeenCalledWith('+919876543705', expect.any(String));
      expect(screen.getByText(/\+•••••••••705/)).toBeInTheDocument();
    });

    it('renders the reCAPTCHA container in the DOM before sendPhoneVerificationCode() is called', async () => {
      // Regression test: sendPhoneVerificationCode()/RecaptchaVerifier require their container
      // element to already exist in the DOM at call time -- Firebase throws auth/argument-error
      // otherwise. This bug shipped once already: the container div used to be rendered only
      // inside the OTP step's JSX, but submitCurrentPassword() calls sendPhoneVerificationCode()
      // while still on the *password* step, before that step (and its div) ever renders -- so
      // the call always failed, on every attempt, in production, while every other test in this
      // file kept passing because sendPhoneVerificationCode is fully mocked below and never
      // actually touches the real DOM requirement. This test doesn't mock that requirement away:
      // it inspects the real document at the moment the mock is invoked.
      let containerExistedAtCallTime = false;
      vi.mocked(sendPhoneVerificationCode).mockImplementationOnce(async (_phone, containerId) => {
        containerExistedAtCallTime = document.getElementById(containerId) !== null;
        return FAKE_CONFIRMATION;
      });
      const user = userEvent.setup();
      renderModal();

      await advanceToOtpStep(user);

      expect(containerExistedAtCallTime).toBe(true);
    });

    it('shows the server error inline (e.g. wrong current password) without advancing', async () => {
      vi.mocked(passwordChangeApi.start).mockReset().mockRejectedValue({
        response: { data: { message: 'Current password is incorrect.' } },
      });
      const user = userEvent.setup();
      renderModal();

      await user.type(screen.getByLabelText(/^current password$/i), 'WrongPassword');
      await user.click(screen.getByRole('button', { name: /send code/i }));

      expect(await screen.findByText(/current password is incorrect/i)).toBeInTheDocument();
      expect(screen.queryByLabelText(/verification code/i)).not.toBeInTheDocument();
    });

    it('calls onClose when Cancel is clicked', async () => {
      const user = userEvent.setup();
      const { onClose } = renderModal();

      await user.click(screen.getByRole('button', { name: /cancel/i }));

      expect(onClose).toHaveBeenCalled();
    });
  });

  describe('Step 2 -- OTP', () => {
    it('keeps Verify disabled until a 6-digit code is entered', async () => {
      const user = userEvent.setup();
      renderModal();
      await advanceToOtpStep(user);

      expect(screen.getByRole('button', { name: /^verify$/i })).toBeDisabled();
      await user.type(screen.getByLabelText(/verification code/i), '123');
      expect(screen.getByRole('button', { name: /^verify$/i })).toBeDisabled();
      await user.type(screen.getByLabelText(/verification code/i), '456');
      expect(screen.getByRole('button', { name: /^verify$/i })).toBeEnabled();
    });

    it('shows an inline error and does not advance when Firebase rejects the code', async () => {
      vi.mocked(confirmPhoneVerificationCode).mockRejectedValue({ code: 'auth/invalid-verification-code' });
      const user = userEvent.setup();
      renderModal();
      await advanceToOtpStep(user);

      await user.type(screen.getByLabelText(/verification code/i), '000000');
      await user.click(screen.getByRole('button', { name: /^verify$/i }));

      expect(await screen.findByText(/doesn't match/i)).toBeInTheDocument();
      expect(screen.queryByLabelText(/^new password$/i)).not.toBeInTheDocument();
      expect(passwordChangeApi.verifyOtp).not.toHaveBeenCalled();
    });

    it('advances to the new-password step on a correct code, sending the Firebase ID token to the backend', async () => {
      const user = userEvent.setup();
      renderModal();

      await advanceToNewPasswordStep(user);

      expect(confirmPhoneVerificationCode).toHaveBeenCalledWith(FAKE_CONFIRMATION, '654321');
      expect(passwordChangeApi.verifyOtp).toHaveBeenCalledWith('session-1', 'fake-firebase-id-token');
      expect(screen.getByLabelText(/^new password$/i)).toBeInTheDocument();
    });

    it('"Start over" resets back to the current-password step', async () => {
      const user = userEvent.setup();
      renderModal();
      await advanceToOtpStep(user);

      await user.click(screen.getByRole('button', { name: /start over/i }));

      expect(screen.getByLabelText(/^current password$/i)).toBeInTheDocument();
    });
  });

  describe('Step 3 -- new password', () => {
    it('keeps Update Password disabled until new and matching-confirm passwords are filled in', async () => {
      const user = userEvent.setup();
      renderModal();
      await advanceToNewPasswordStep(user);

      const updateButton = screen.getByRole('button', { name: /update password/i });
      expect(updateButton).toBeDisabled();

      await user.type(screen.getByLabelText(/^new password$/i), 'NewPass456!');
      expect(updateButton).toBeDisabled();

      await user.type(screen.getByLabelText(/^confirm new password$/i), 'NewPass456!');
      expect(updateButton).toBeEnabled();
    });

    it("shows a mismatch warning when confirm doesn't match, and keeps Update disabled", async () => {
      const user = userEvent.setup();
      renderModal();
      await advanceToNewPasswordStep(user);

      await user.type(screen.getByLabelText(/^new password$/i), 'NewPass456!');
      await user.type(screen.getByLabelText(/^confirm new password$/i), 'Different789!');

      expect(screen.getByText(/don't match/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /update password/i })).toBeDisabled();
    });

    it('does not gate submission on the strength checklist -- only the 8-character length the backend actually enforces', async () => {
      const user = userEvent.setup();
      renderModal();
      await advanceToNewPasswordStep(user);

      await user.type(screen.getByLabelText(/^new password$/i), 'longenoughpw');
      await user.type(screen.getByLabelText(/^confirm new password$/i), 'longenoughpw');

      expect(screen.getByRole('button', { name: /update password/i })).toBeEnabled();
    });

    it('keeps the recommendation list collapsed until the toggle is clicked', async () => {
      const user = userEvent.setup();
      renderModal();
      await advanceToNewPasswordStep(user);

      expect(screen.queryByText(/an uppercase letter/i)).not.toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: /recommended for a stronger password/i }));
      expect(screen.getByText(/an uppercase letter/i)).toBeInTheDocument();
    });

    it('defaults to "sign out other devices" selected', async () => {
      const user = userEvent.setup();
      renderModal();
      await advanceToNewPasswordStep(user);

      expect(screen.getByRole('radio', { name: /sign out other devices/i })).toBeChecked();
      expect(screen.getByRole('radio', { name: /keep other devices signed in/i })).not.toBeChecked();
    });

    it('submits via passwordChangeApi.complete with the stored refresh token and shows success', async () => {
      const user = userEvent.setup();
      renderModal();
      await advanceToNewPasswordStep(user);

      await user.type(screen.getByLabelText(/^new password$/i), 'NewPass456!');
      await user.type(screen.getByLabelText(/^confirm new password$/i), 'NewPass456!');
      await user.click(screen.getByRole('button', { name: /update password/i }));

      await waitFor(() => expect(passwordChangeApi.complete).toHaveBeenCalledWith(
        'session-1', 'NewPass456!', true, 'this-devices-refresh-token',
      ));
      expect(await screen.findByText(/password updated/i)).toBeInTheDocument();
      expect(screen.getByText(/every other device has been signed out/i)).toBeInTheDocument();
    });

    it('passes signOutOtherDevices=false when "keep other devices signed in" is chosen', async () => {
      const user = userEvent.setup();
      renderModal();
      await advanceToNewPasswordStep(user);

      await user.click(screen.getByRole('radio', { name: /keep other devices signed in/i }));
      await user.type(screen.getByLabelText(/^new password$/i), 'NewPass456!');
      await user.type(screen.getByLabelText(/^confirm new password$/i), 'NewPass456!');
      await user.click(screen.getByRole('button', { name: /update password/i }));

      await waitFor(() => expect(passwordChangeApi.complete).toHaveBeenCalledWith(
        'session-1', 'NewPass456!', false, 'this-devices-refresh-token',
      ));
    });

    it('shows the server error inline without closing the modal', async () => {
      vi.mocked(passwordChangeApi.complete).mockReset().mockRejectedValue({
        response: { data: { message: 'This password change session has expired. Please start again.' } },
      });
      const user = userEvent.setup();
      renderModal();
      await advanceToNewPasswordStep(user);

      await user.type(screen.getByLabelText(/^new password$/i), 'NewPass456!');
      await user.type(screen.getByLabelText(/^confirm new password$/i), 'NewPass456!');
      await user.click(screen.getByRole('button', { name: /update password/i }));

      expect(await screen.findByText(/session has expired/i)).toBeInTheDocument();
      expect(screen.queryByText(/password updated/i)).not.toBeInTheDocument();
    });

    it('calls onSuccess once the password change completes', async () => {
      const user = userEvent.setup();
      const { onSuccess } = renderModal();
      await advanceToNewPasswordStep(user);

      await user.type(screen.getByLabelText(/^new password$/i), 'NewPass456!');
      await user.type(screen.getByLabelText(/^confirm new password$/i), 'NewPass456!');
      await user.click(screen.getByRole('button', { name: /update password/i }));

      await waitFor(() => expect(onSuccess).toHaveBeenCalled());
    });
  });

  describe('success', () => {
    it('closes the modal via the Done button, without navigating anywhere', async () => {
      const user = userEvent.setup();
      const { onClose } = renderModal();
      await advanceToNewPasswordStep(user);

      await user.type(screen.getByLabelText(/^new password$/i), 'NewPass456!');
      await user.type(screen.getByLabelText(/^confirm new password$/i), 'NewPass456!');
      await user.click(screen.getByRole('button', { name: /update password/i }));
      await screen.findByText(/password updated/i);

      await user.click(screen.getByRole('button', { name: /^done$/i }));
      expect(onClose).toHaveBeenCalled();
    });
  });
});
