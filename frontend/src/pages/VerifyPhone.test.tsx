import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import VerifyPhone from './VerifyPhone';
import { useAuth } from '../context/AuthContext';
import { phoneApi, userApi } from '../api/endpoints';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode } from '../lib/phoneAuth';

vi.mock('../context/AuthContext', () => ({
  useAuth: vi.fn(),
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
  vi.mocked(useAuth).mockReturnValue({
    token: 'tok', email: 'jane@example.com', fullName: 'Jane', phoneVerified: false,
    login: vi.fn(), reactivate: vi.fn(), register: vi.fn(), setPhoneVerified: vi.fn(), logout: vi.fn(),
  });
  return render(
    <MemoryRouter initialEntries={['/verify-phone']}>
      <Routes>
        <Route path="/verify-phone" element={<VerifyPhone />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('VerifyPhone', () => {
  beforeEach(() => {
    vi.mocked(userApi.get).mockReset().mockResolvedValue({
      email: 'jane@example.com', fullName: 'Jane', lowBalanceThreshold: 2000, theme: 'system',
      timezone: 'Asia/Kolkata', phoneNumber: '+919876543705', phoneVerified: false,
      createdAt: '2026-01-01T00:00:00Z', passwordChangedAt: null,
    });
    vi.mocked(sendPhoneVerificationCode).mockReset().mockResolvedValue(FAKE_CONFIRMATION);
    vi.mocked(confirmPhoneVerificationCode).mockReset().mockResolvedValue('fake-firebase-id-token');
    vi.mocked(phoneApi.verify).mockReset().mockResolvedValue({ message: 'Phone number verified.' });
  });

  it('fetches the real phone number and triggers Firebase to send a code on mount', async () => {
    renderVerifyPhone();

    await waitFor(() => expect(userApi.get).toHaveBeenCalled());
    await waitFor(() => expect(sendPhoneVerificationCode).toHaveBeenCalledWith('+919876543705', expect.any(String)));
    expect(await screen.findByText(/\+•••••••••705/)).toBeInTheDocument();
  });

  it('keeps Verify disabled until a code has been sent and a 6-digit code is entered', async () => {
    const user = userEvent.setup();
    renderVerifyPhone();
    await screen.findByText(/\+•••••••••705/);

    const verifyButton = screen.getByRole('button', { name: /^verify$/i });
    expect(verifyButton).toBeDisabled();

    await user.type(screen.getByPlaceholderText('123456'), '123456');
    expect(verifyButton).toBeEnabled();
  });

  it('submits the confirmed code, verifies with the backend, and marks the phone verified', async () => {
    const user = userEvent.setup();
    const setPhoneVerified = vi.fn();
    vi.mocked(useAuth).mockReturnValue({
      token: 'tok', email: 'jane@example.com', fullName: 'Jane', phoneVerified: false,
      login: vi.fn(), reactivate: vi.fn(), register: vi.fn(), setPhoneVerified, logout: vi.fn(),
    });
    render(
      <MemoryRouter initialEntries={['/verify-phone']}>
        <Routes><Route path="/verify-phone" element={<VerifyPhone />} /></Routes>
      </MemoryRouter>
    );
    await screen.findByText(/\+•••••••••705/);

    await user.type(screen.getByPlaceholderText('123456'), '123456');
    await user.click(screen.getByRole('button', { name: /^verify$/i }));

    await waitFor(() => expect(confirmPhoneVerificationCode).toHaveBeenCalledWith(FAKE_CONFIRMATION, '123456'));
    await waitFor(() => expect(phoneApi.verify).toHaveBeenCalledWith('fake-firebase-id-token'));
    expect(setPhoneVerified).toHaveBeenCalledWith(true);
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

  it('re-sends a code via Firebase when Resend is clicked', async () => {
    const user = userEvent.setup();
    renderVerifyPhone();
    await screen.findByText(/\+•••••••••705/);
    vi.mocked(sendPhoneVerificationCode).mockClear();

    await user.click(screen.getByText("Didn't get a code? Resend"));

    await waitFor(() => expect(sendPhoneVerificationCode).toHaveBeenCalledTimes(1));
  });

  it('shows a generic error when the initial send fails', async () => {
    vi.mocked(sendPhoneVerificationCode).mockRejectedValue(new Error('network error'));
    renderVerifyPhone();

    expect(await screen.findByText(/could not send a verification code/i)).toBeInTheDocument();
  });
});
