import { act, fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { ChangePasswordSheet, nextPasswordSuggestion, passwordStrengthMeter } from './ChangePasswordSheet';
import { passwordChangeApi } from '../../api/endpoints';
import { confirmPhoneVerificationCode, sendPhoneVerificationCode } from '../../lib/phoneAuth';
import { safeStorage } from '../../lib/safeStorage';

jest.mock('../../api/endpoints', () => ({
  passwordChangeApi: { start: jest.fn(), verifyOtp: jest.fn(), complete: jest.fn() },
}));

jest.mock('../../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: jest.fn(),
  confirmPhoneVerificationCode: jest.fn(),
}));

const api = passwordChangeApi as jest.Mocked<typeof passwordChangeApi>;
const sendCode = sendPhoneVerificationCode as jest.MockedFunction<typeof sendPhoneVerificationCode>;
const confirmCode = confirmPhoneVerificationCode as jest.MockedFunction<typeof confirmPhoneVerificationCode>;

// Invented, matching the value the rest of this suite uses. Declared once so the hygiene marker
// sits in one place rather than on every line that mentions it.
const PHONE = '+919876543210'; // synthetic-ok: invented test number
const MASKED_PHONE = '+•••••••••210';

const onClose = jest.fn();
const onSuccess = jest.fn();

function renderSheet() {
  return render(<ChangePasswordSheet onClose={onClose} onSuccess={onSuccess} />);
}

async function settle() {
  await act(async () => {});
}

/** Drives the flow up to the new-password step. */
async function reachNewPasswordStep() {
  fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
  fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
  await settle();
  fireEvent.changeText(screen.getByLabelText('Verification code'), '123456');
  fireEvent.press(screen.getByRole('button', { name: /Verify/ }));
  await settle();
}

describe('ChangePasswordSheet', () => {
  beforeEach(async () => {
    onClose.mockReset();
    onSuccess.mockReset();
    api.start.mockReset().mockResolvedValue({
      sessionId: 'sess-1', phoneNumber: PHONE, maskedPhone: MASKED_PHONE,
    });
    api.verifyOtp.mockReset().mockResolvedValue({ message: 'ok' });
    api.complete.mockReset().mockResolvedValue({ message: 'Password updated.', otherDevicesSignedOut: true });
    sendCode.mockReset().mockResolvedValue({ confirm: jest.fn() } as never);
    confirmCode.mockReset().mockResolvedValue('firebase-id-token');
    await safeStorage.setItem('finora_refresh_token', 'refresh-abc');
  });

  it('starts by asking for the current password', () => {
    renderSheet();

    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.queryByLabelText('Verification code')).toBeNull();
  });

  /**
   * The backend never sends the code -- it returns the phone number and Firebase sends it. Getting
   * this backwards would look identical on screen and deliver nothing.
   */
  it('asks the backend to start, then has Firebase send the code', async () => {
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    await waitFor(() => expect(api.start).toHaveBeenCalledWith('CurrentPw1!'));
    expect(sendCode).toHaveBeenCalledWith(PHONE);
    expect(await screen.findByLabelText('Verification code')).toBeTruthy();
    // Masked, never the full number.
    expect(screen.getByText(new RegExp(MASKED_PHONE.replace('+', '\\+')))).toBeTruthy();
  });

  it('stays on the first step and explains when the current password is rejected', async () => {
    api.start.mockReset().mockRejectedValue(
      Object.assign(new Error('bad'), {
        isAxiosError: true,
        response: { status: 400, data: { message: 'Current password is incorrect.' } },
      })
    );
    renderSheet();

    fireEvent.changeText(screen.getByLabelText('Current password'), 'wrong');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    expect(await screen.findByText('Current password is incorrect.')).toBeTruthy();
    expect(screen.queryByLabelText('Verification code')).toBeNull();
    expect(sendCode).not.toHaveBeenCalled();
  });

  it('sends the Firebase ID token to the backend, never the code itself', async () => {
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    fireEvent.changeText(screen.getByLabelText('Verification code'), '123456');
    fireEvent.press(screen.getByRole('button', { name: /Verify/ }));
    await settle();

    await waitFor(() => expect(api.verifyOtp).toHaveBeenCalledWith('sess-1', 'firebase-id-token'));
  });

  it('clears a rejected code so the field is ready for another attempt', async () => {
    confirmCode.mockReset().mockRejectedValue({ code: 'auth/invalid-verification-code' });
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    fireEvent.changeText(screen.getByLabelText('Verification code'), '111111');
    fireEvent.press(screen.getByRole('button', { name: /Verify/ }));
    await settle();

    expect(await screen.findByText(/doesn't match/i)).toBeTruthy();
    expect(screen.getByLabelText('Verification code').props.value).toBe('');
  });

  it('completes with the session, the choice about other devices, and this device’s refresh token', async () => {
    renderSheet();
    await reachNewPasswordStep();

    fireEvent.changeText(screen.getByLabelText('New password'), 'BrandNewPw1!');
    fireEvent.changeText(screen.getByLabelText('Confirm new password'), 'BrandNewPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Update Password/ }));
    await settle();

    await waitFor(() =>
      expect(api.complete).toHaveBeenCalledWith('sess-1', 'BrandNewPw1!', true, 'refresh-abc')
    );
    expect(onSuccess).toHaveBeenCalled();
    expect(await screen.findByText('Password updated')).toBeTruthy();
  });

  it('will not submit until both new-password fields match', async () => {
    renderSheet();
    await reachNewPasswordStep();

    fireEvent.changeText(screen.getByLabelText('New password'), 'BrandNewPw1!');
    fireEvent.changeText(screen.getByLabelText('Confirm new password'), 'Different1!');
    await settle();

    expect(screen.getByRole('button', { name: /Update Password/ }).props.accessibilityState.disabled).toBe(true);
    expect(screen.getByText("Passwords don't match.")).toBeTruthy();
  });

  it('lets the user keep other devices signed in', async () => {
    renderSheet();
    await reachNewPasswordStep();

    fireEvent.press(screen.getByLabelText('Keep other devices signed in'));
    fireEvent.changeText(screen.getByLabelText('New password'), 'BrandNewPw1!');
    fireEvent.changeText(screen.getByLabelText('Confirm new password'), 'BrandNewPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Update Password/ }));
    await settle();

    await waitFor(() => expect(api.complete).toHaveBeenCalledWith('sess-1', 'BrandNewPw1!', false, 'refresh-abc'));
  });

  // Without the refresh token the backend cannot tell which session to spare, and signing out
  // "other" devices would include this one.
  it('refuses to complete when this device’s refresh token is missing', async () => {
    await safeStorage.removeItem('finora_refresh_token');
    renderSheet();
    await reachNewPasswordStep();

    fireEvent.changeText(screen.getByLabelText('New password'), 'BrandNewPw1!');
    fireEvent.changeText(screen.getByLabelText('Confirm new password'), 'BrandNewPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Update Password/ }));
    await settle();

    expect(await screen.findByText(/session information is missing/i)).toBeTruthy();
    expect(api.complete).not.toHaveBeenCalled();
  });

  it('starts over back at the current-password step', async () => {
    renderSheet();
    fireEvent.changeText(screen.getByLabelText('Current password'), 'CurrentPw1!');
    fireEvent.press(screen.getByRole('button', { name: /Send code/ }));
    await settle();

    fireEvent.press(screen.getByText(/Start over/));
    await settle();

    expect(screen.getByLabelText('Current password')).toBeTruthy();
    expect(screen.queryByLabelText('Verification code')).toBeNull();
  });
});

describe('password strength guidance', () => {
  // Only length is enforced server-side; this is a guide, so it must never read as a gate.
  it('scores from weak to strong', () => {
    expect(passwordStrengthMeter('').tone).toBe('none');
    expect(passwordStrengthMeter('abc').tone).toBe('weak');
    expect(passwordStrengthMeter('abcdefgh').tone).toBe('weak');
    expect(passwordStrengthMeter('Abcdefgh1').tone).toBe('good');
    expect(passwordStrengthMeter('Abcdefgh1!').tone).toBe('strong');
  });

  it('names the next concrete step rather than only a verdict', () => {
    expect(nextPasswordSuggestion('abcdefgh')).toMatch(/uppercase/i);
    expect(nextPasswordSuggestion('Abcdefgh')).toMatch(/number/i);
    expect(nextPasswordSuggestion('Abcdefgh1!')).toBeNull();
  });
});
