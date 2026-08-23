import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import ResetPassword from './ResetPassword';
import { authApi } from '../api/endpoints';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode } from '../lib/phoneAuth';

vi.mock('../api/endpoints', () => ({
  authApi: { verifyResetPasswordPhone: vi.fn(), resetPassword: vi.fn() },
}));

vi.mock('../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: vi.fn(),
  confirmPhoneVerificationCode: vi.fn(),
  resetPhoneVerification: vi.fn(),
  friendlySendError: () => 'Could not send the code. Please try again.',
}));

const FAKE_CONFIRMATION = { confirm: vi.fn() } as any;

// Invented, fake sequential digits -- same convention used throughout this app's test fixtures
// (e.g. Register.test.tsx, ChangePasswordModal.test.tsx). Declared once so the hygiene marker
// sits in one place rather than on every line that mentions it.
const LOCAL_PHONE = '9876543210'; // synthetic-ok: invented test number
const FULL_PHONE = `+91${LOCAL_PHONE}`;

function renderPage(token: string | null = 'reset-token-abc') {
  const path = token ? `/reset-password?token=${token}` : '/reset-password';
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/reset-password" element={<ResetPassword />} />
        <Route path="/login" element={<p>Login page</p>} />
      </Routes>
    </MemoryRouter>
  );
}

async function confirmPhoneStep(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/mobile number/i), LOCAL_PHONE);
  await user.click(screen.getByRole('button', { name: /continue/i }));
  await screen.findByLabelText(/verification code/i);
}

describe('ResetPassword', () => {
  beforeEach(() => {
    vi.mocked(authApi.verifyResetPasswordPhone).mockReset().mockResolvedValue({ message: 'Phone number confirmed.' });
    vi.mocked(authApi.resetPassword).mockReset().mockResolvedValue({ message: 'Password updated.' });
    vi.mocked(sendPhoneVerificationCode).mockReset().mockResolvedValue(FAKE_CONFIRMATION);
    vi.mocked(confirmPhoneVerificationCode).mockReset().mockResolvedValue('fake-firebase-id-token');
  });

  it('shows an error and no phone form when the link has no token', () => {
    renderPage(null);

    expect(screen.getByText(/no reset token found/i)).toBeInTheDocument();
    expect(screen.queryByLabelText(/mobile number/i)).not.toBeInTheDocument();
  });

  it('starts on the phone-number step, not an auto-sent code', () => {
    renderPage();

    expect(screen.getByText('Confirm your phone number')).toBeInTheDocument();
    expect(screen.queryByLabelText(/verification code/i)).not.toBeInTheDocument();
    expect(authApi.verifyResetPasswordPhone).not.toHaveBeenCalled();
  });

  it('keeps Continue disabled until a valid 10-digit number is typed', async () => {
    renderPage();
    const user = userEvent.setup();

    expect(screen.getByRole('button', { name: /continue/i })).toBeDisabled();
    await user.type(screen.getByLabelText(/mobile number/i), '12345');
    expect(screen.getByRole('button', { name: /continue/i })).toBeDisabled();
  });

  /**
   * BH-015 fix's core behavior: the typed number, not anything the backend hands back, is what
   * gets sent to Firebase -- this is the whole point of the fix, so it's the single most
   * important assertion in this file.
   */
  it('sends the user-typed number (not a backend-revealed one) to both the backend verify call and Firebase', async () => {
    renderPage();
    const user = userEvent.setup();

    await confirmPhoneStep(user);

    expect(authApi.verifyResetPasswordPhone).toHaveBeenCalledWith('reset-token-abc', FULL_PHONE);
    expect(sendPhoneVerificationCode).toHaveBeenCalledWith(FULL_PHONE, 'reset-password-recaptcha');
  });

  it('shows a server error and stays on the phone step when the typed number does not match the account', async () => {
    vi.mocked(authApi.verifyResetPasswordPhone).mockRejectedValue({
      response: { data: { message: "That doesn't match the phone number on this account." } },
    });
    renderPage();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/mobile number/i), LOCAL_PHONE);
    await user.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(screen.getByText("That doesn't match the phone number on this account.")).toBeInTheDocument());
    expect(screen.queryByLabelText(/verification code/i)).not.toBeInTheDocument();
    expect(sendPhoneVerificationCode).not.toHaveBeenCalled();
  });

  it('advances to the OTP + new-password step once the phone number is confirmed', async () => {
    renderPage();
    const user = userEvent.setup();

    await confirmPhoneStep(user);

    expect(screen.getByText('Set a new password')).toBeInTheDocument();
    expect(screen.getByText(new RegExp(`enter the 6-digit code sent to \\${FULL_PHONE}`, 'i'))).toBeInTheDocument();
  });

  it('resends the code via Firebase directly, without re-verifying the phone number with the backend', async () => {
    renderPage();
    const user = userEvent.setup();
    await confirmPhoneStep(user);
    vi.mocked(authApi.verifyResetPasswordPhone).mockClear();
    vi.mocked(sendPhoneVerificationCode).mockClear();

    await user.click(screen.getByRole('button', { name: /resend code/i }));

    expect(authApi.verifyResetPasswordPhone).not.toHaveBeenCalled();
    expect(sendPhoneVerificationCode).toHaveBeenCalledWith(FULL_PHONE, 'reset-password-recaptcha');
  });

  it('completes the reset end to end: phone confirm, OTP, new password', async () => {
    renderPage();
    const user = userEvent.setup();
    await confirmPhoneStep(user);

    await user.type(screen.getByLabelText(/verification code/i), '654321');
    await user.type(screen.getByLabelText(/^new password$/i), 'BrandNewPass1!');
    await user.type(screen.getByLabelText(/confirm password/i), 'BrandNewPass1!');
    await user.click(screen.getByRole('button', { name: /update password/i }));

    await waitFor(() => expect(screen.getByText('Password updated')).toBeInTheDocument());
    expect(confirmPhoneVerificationCode).toHaveBeenCalledWith(FAKE_CONFIRMATION, '654321');
    expect(authApi.resetPassword).toHaveBeenCalledWith('reset-token-abc', 'fake-firebase-id-token', 'BrandNewPass1!');
  });

  it('has exactly one reCAPTCHA anchor in the DOM, unaffected by the phone-to-OTP step transition', async () => {
    const { container } = renderPage();
    const user = userEvent.setup();

    expect(container.querySelectorAll('#reset-password-recaptcha')).toHaveLength(1);

    await confirmPhoneStep(user);

    expect(container.querySelectorAll('#reset-password-recaptcha')).toHaveLength(1);
  });
});
