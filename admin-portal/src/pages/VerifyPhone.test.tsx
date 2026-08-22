import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import VerifyPhone from './VerifyPhone';
import { useAdminAuth } from '../context/AdminAuthContext';
import { phoneApi, userApi } from '../api/endpoints';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode } from '../lib/phoneAuth';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));

vi.mock('../api/endpoints', () => ({
  phoneApi: { verify: vi.fn() },
  userApi: { get: vi.fn() },
}));

vi.mock('../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: vi.fn(),
  confirmPhoneVerificationCode: vi.fn(),
  resetPhoneVerification: vi.fn(),
}));

const FAKE_CONFIRMATION = { confirm: vi.fn() } as any;

function renderVerifyPhone() {
  vi.mocked(useAdminAuth).mockReturnValue({
    token: 'tok', email: 'admin@example.com', fullName: 'Admin', phoneVerified: false,
    permissions: [], roles: [], loading: false,
    login: vi.fn(), completeMfaChallenge: vi.fn(), completePhoneVerification: vi.fn(), logout: vi.fn(), hasPermission: vi.fn(),
  });
  return render(
    <MemoryRouter>
      <VerifyPhone />
    </MemoryRouter>
  );
}

describe('VerifyPhone (admin portal)', () => {
  beforeEach(() => {
    vi.mocked(userApi.get).mockReset().mockResolvedValue({ phoneNumber: '+919876543705', signInMethod: 'PASSWORD' }); // synthetic-ok
    vi.mocked(sendPhoneVerificationCode).mockReset().mockResolvedValue(FAKE_CONFIRMATION);
    vi.mocked(confirmPhoneVerificationCode).mockReset().mockResolvedValue('fake-firebase-id-token');
    vi.mocked(phoneApi.verify).mockReset().mockResolvedValue({ message: 'Phone number verified.' });
  });

  /**
   * Every arrival at this screen (admin-created account, or a returning admin whose phone still
   * isn't verified) has the same gap: no code was sent as part of getting here. Firebase's own
   * client SDK sends it, once this page fetches the real phone number from the backend.
   */
  it('fetches the real phone number and triggers Firebase to send a code on mount', async () => {
    renderVerifyPhone();

    await waitFor(() => expect(userApi.get).toHaveBeenCalled());
    await waitFor(() => expect(sendPhoneVerificationCode).toHaveBeenCalledWith('+919876543705', expect.any(String)));
    expect(await screen.findByText(/\+•••••••••705/)).toBeInTheDocument();
  });

  it('falls back to generic text before the send resolves', () => {
    vi.mocked(userApi.get).mockReturnValue(new Promise(() => {})); // never resolves
    renderVerifyPhone();

    expect(screen.getByText(/your mobile number/)).toBeInTheDocument();
  });

  it('submits the confirmed code, verifies with the backend, and completes phone verification', async () => {
    const user = userEvent.setup();
    const completePhoneVerification = vi.fn();
    vi.mocked(useAdminAuth).mockReturnValue({
      token: 'tok', email: 'admin@example.com', fullName: 'Admin', phoneVerified: false,
      permissions: [], roles: [], loading: false,
      login: vi.fn(), completeMfaChallenge: vi.fn(), completePhoneVerification, logout: vi.fn(), hasPermission: vi.fn(),
    });
    render(
      <MemoryRouter>
        <VerifyPhone />
      </MemoryRouter>
    );
    await screen.findByText(/\+•••••••••705/);

    await user.type(screen.getByPlaceholderText('123456'), '123456');
    await user.click(screen.getByRole('button', { name: /^verify$/i }));

    await waitFor(() => expect(confirmPhoneVerificationCode).toHaveBeenCalledWith(FAKE_CONFIRMATION, '123456'));
    await waitFor(() => expect(phoneApi.verify).toHaveBeenCalledWith('fake-firebase-id-token'));
    expect(completePhoneVerification).toHaveBeenCalled();
  });

  it('shows an inline error and does not verify with the backend when Firebase rejects the code', async () => {
    vi.mocked(confirmPhoneVerificationCode).mockRejectedValue({ code: 'auth/invalid-verification-code' });
    const user = userEvent.setup();
    renderVerifyPhone();
    await screen.findByText(/\+•••••••••705/);

    await user.type(screen.getByPlaceholderText('123456'), '000000');
    await user.click(screen.getByRole('button', { name: /^verify$/i }));

    expect(await screen.findByText(/doesn't match/i)).toBeInTheDocument();
    expect(phoneApi.verify).not.toHaveBeenCalled();
  });
});
