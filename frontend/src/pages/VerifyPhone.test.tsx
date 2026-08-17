import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import VerifyPhone from './VerifyPhone';
import { useAuth } from '../context/AuthContext';
import { phoneApi, phoneChangeApi, userApi } from '../api/endpoints';
import { sendPhoneVerificationCode, confirmPhoneVerificationCode } from '../lib/phoneAuth';

vi.mock('../context/AuthContext', () => ({
  useAuth: vi.fn(),
}));

vi.mock('../api/endpoints', () => ({
  phoneApi: { verify: vi.fn() },
  phoneChangeApi: { start: vi.fn(), verifyOtp: vi.fn(), complete: vi.fn() },
  userApi: { get: vi.fn() },
}));

vi.mock('../lib/phoneAuth', () => ({
  sendPhoneVerificationCode: vi.fn(),
  confirmPhoneVerificationCode: vi.fn(),
  resetPhoneVerification: vi.fn(),
  friendlySendError: vi.fn(() => 'Could not send a verification code right now. Please try again.'),
}));

vi.mock('../lib/monitoring', () => ({
  reportHandledError: vi.fn(),
}));

const FAKE_CONFIRMATION = { confirm: vi.fn() } as any;

function renderVerifyPhone(routerState?: { fromLogin?: boolean }) {
  vi.mocked(useAuth).mockReturnValue({
    token: 'tok', email: 'jane@example.com', fullName: 'Jane', phoneVerified: false,
    login: vi.fn(), reactivate: vi.fn(), register: vi.fn(), loginWithGoogle: vi.fn(), setPhoneVerified: vi.fn(), logout: vi.fn(),
  });
  return render(
    <MemoryRouter initialEntries={[{ pathname: '/verify-phone', state: routerState }]}>
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
    vi.mocked(phoneChangeApi.start).mockReset().mockResolvedValue({
      sessionId: 'change-session-1', maskedPhone: '+•••••••••888',
    });
    vi.mocked(phoneChangeApi.verifyOtp).mockReset().mockResolvedValue({ message: 'Verified.' });
    vi.mocked(phoneChangeApi.complete).mockReset().mockResolvedValue({
      message: 'Your phone number has been updated.', phoneNumber: '+919888888888',
    });
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
      login: vi.fn(), reactivate: vi.fn(), register: vi.fn(), loginWithGoogle: vi.fn(), setPhoneVerified, logout: vi.fn(),
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

  it('disables Resend with a countdown right after a manual resend, so it cannot be spammed', async () => {
    const user = userEvent.setup();
    renderVerifyPhone();
    await screen.findByText(/\+•••••••••705/);

    await user.click(screen.getByText("Didn't get a code? Resend"));

    const cooldownButton = await screen.findByRole('button', { name: /resend in \d+s/i });
    expect(cooldownButton).toBeDisabled();
  });

  it('offers a Log Out escape hatch when the initial send fails, and signs the user out on click', async () => {
    vi.mocked(sendPhoneVerificationCode).mockRejectedValue({ code: 'auth/invalid-app-credential' });
    const logout = vi.fn();
    vi.mocked(useAuth).mockReturnValue({
      token: 'tok', email: 'jane@example.com', fullName: 'Jane', phoneVerified: false,
      login: vi.fn(), reactivate: vi.fn(), register: vi.fn(), loginWithGoogle: vi.fn(), setPhoneVerified: vi.fn(), logout,
    });
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/verify-phone']}>
        <Routes><Route path="/verify-phone" element={<VerifyPhone />} /></Routes>
      </MemoryRouter>
    );

    await user.click(await screen.findByRole('button', { name: /log out and try again later/i }));

    expect(logout).toHaveBeenCalled();
  });

  it('does not offer the Log Out escape hatch for a wrong-code error -- that only needs a retype', async () => {
    vi.mocked(confirmPhoneVerificationCode).mockRejectedValue({ code: 'auth/invalid-verification-code' });
    const user = userEvent.setup();
    renderVerifyPhone();
    await screen.findByText(/\+•••••••••705/);

    await user.type(screen.getByPlaceholderText('123456'), '000000');
    await user.click(screen.getByRole('button', { name: /^verify$/i }));

    expect(await screen.findByText(/doesn't match/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /log out and try again later/i })).not.toBeInTheDocument();
  });

  it('greets a returning user with "Welcome back!" when arriving from Login.tsx', async () => {
    renderVerifyPhone({ fromLogin: true });

    expect(await screen.findByText('Welcome back!')).toBeInTheDocument();
  });

  it('shows no greeting for a brand-new registration, which never sets fromLogin', async () => {
    renderVerifyPhone();
    await screen.findByText(/\+•••••••••705/);

    expect(screen.queryByText('Welcome back!')).not.toBeInTheDocument();
  });

  describe('Change Number', () => {
    /** Gets to the "enter a new number" form -- only reachable from the sendError state, the
     *  same escape hatch Log Out is offered alongside. Does NOT render itself -- the caller
     *  renders first (via renderVerifyPhone() or its own render() call with custom mocks), since
     *  renderVerifyPhone() always installs its own fresh useAuth mock and would silently discard
     *  a caller's own setPhoneVerified/logout spy set up beforehand. */
    async function openChangeNumberForm(user: ReturnType<typeof userEvent.setup>) {
      await user.click(await screen.findByRole('button', { name: /^change number$/i }));
    }

    it('is not offered until the original send has actually failed', async () => {
      renderVerifyPhone();
      await screen.findByText(/\+•••••••••705/);

      expect(screen.queryByRole('button', { name: /^change number$/i })).not.toBeInTheDocument();
    });

    it('rejects an invalid number without submitting', async () => {
      vi.mocked(sendPhoneVerificationCode).mockRejectedValueOnce({ code: 'auth/invalid-app-credential' });
      renderVerifyPhone();
      const user = userEvent.setup();
      await openChangeNumberForm(user);

      await user.type(screen.getByPlaceholderText('XXXXXXXXXX'), '123');
      await user.click(screen.getByRole('button', { name: /send code/i }));

      expect(await screen.findByText(/enter a valid 10-digit mobile number/i)).toBeInTheDocument();
      expect(phoneChangeApi.start).not.toHaveBeenCalled();
    });

    it('starts a session, sends a Firebase code to the new number, and shows the masked confirmation', async () => {
      vi.mocked(sendPhoneVerificationCode).mockRejectedValueOnce({ code: 'auth/invalid-app-credential' });
      renderVerifyPhone();
      const user = userEvent.setup();
      await openChangeNumberForm(user);

      await user.type(screen.getByPlaceholderText('XXXXXXXXXX'), '9888888888');
      await user.click(screen.getByRole('button', { name: /send code/i }));

      await waitFor(() => expect(phoneChangeApi.start).toHaveBeenCalledWith('+919888888888'));
      await waitFor(() => expect(sendPhoneVerificationCode).toHaveBeenCalledWith('+919888888888', expect.any(String)));
      expect(await screen.findByText(/\+•••••••••888/)).toBeInTheDocument();
    });

    it('disables Send code with a cooldown after a genuine Firebase send failure', async () => {
      vi.mocked(sendPhoneVerificationCode)
        .mockRejectedValueOnce({ code: 'auth/invalid-app-credential' }) // the initial page-load send
        .mockRejectedValueOnce({ code: 'auth/too-many-requests' }); // this test's own send attempt
      renderVerifyPhone();
      const user = userEvent.setup();
      await openChangeNumberForm(user);

      await user.type(screen.getByPlaceholderText('XXXXXXXXXX'), '9888888888');
      await user.click(screen.getByRole('button', { name: /send code/i }));

      const cooldownButton = await screen.findByRole('button', { name: /send code in \d+s/i });
      expect(cooldownButton).toBeDisabled();
    });

    /** A backend rejection (duplicate number, same-as-current) means the fix is editing the
     *  number just typed in -- not waiting out a timer before resubmitting the exact same one.
     *  Only a genuine Firebase-side send failure is the "don't hammer this" case the cooldown
     *  above exists for. */
    it('does not apply the cooldown after a backend rejection -- the user needs to edit the number, not wait', async () => {
      vi.mocked(sendPhoneVerificationCode).mockRejectedValueOnce({ code: 'auth/invalid-app-credential' });
      vi.mocked(phoneChangeApi.start).mockRejectedValue({
        response: { data: { message: 'An account with this mobile number already exists.' } },
      });
      renderVerifyPhone();
      const user = userEvent.setup();
      await openChangeNumberForm(user);

      await user.type(screen.getByPlaceholderText('XXXXXXXXXX'), '9888888888');
      await user.click(screen.getByRole('button', { name: /send code/i }));

      await screen.findByText(/already exists/i);
      expect(screen.getByRole('button', { name: /^send code$/i })).toBeEnabled();
    });

    it('shows the backend rejection inline (e.g. a number already in use) without touching Firebase', async () => {
      vi.mocked(sendPhoneVerificationCode).mockRejectedValueOnce({ code: 'auth/invalid-app-credential' });
      vi.mocked(phoneChangeApi.start).mockRejectedValue({
        response: { data: { message: 'An account with this mobile number already exists.' } },
      });
      renderVerifyPhone();
      const user = userEvent.setup();
      await openChangeNumberForm(user);

      await user.type(screen.getByPlaceholderText('XXXXXXXXXX'), '9888888888');
      await user.click(screen.getByRole('button', { name: /send code/i }));

      expect(await screen.findByText(/already exists/i)).toBeInTheDocument();
      expect(sendPhoneVerificationCode).not.toHaveBeenCalledWith('+919888888888', expect.any(String));
    });

    it('confirming the code verifies, completes the change, and marks the phone verified', async () => {
      vi.mocked(sendPhoneVerificationCode).mockRejectedValueOnce({ code: 'auth/invalid-app-credential' });
      const setPhoneVerified = vi.fn();
      vi.mocked(useAuth).mockReturnValue({
        token: 'tok', email: 'jane@example.com', fullName: 'Jane', phoneVerified: false,
        login: vi.fn(), reactivate: vi.fn(), register: vi.fn(), loginWithGoogle: vi.fn(), setPhoneVerified, logout: vi.fn(),
      });
      render(
        <MemoryRouter initialEntries={['/verify-phone']}>
          <Routes><Route path="/verify-phone" element={<VerifyPhone />} /></Routes>
        </MemoryRouter>
      );
      const user = userEvent.setup();
      await openChangeNumberForm(user);
      await user.type(screen.getByPlaceholderText('XXXXXXXXXX'), '9888888888');
      await user.click(screen.getByRole('button', { name: /send code/i }));
      await screen.findByText(/\+•••••••••888/);

      await user.type(screen.getByPlaceholderText('123456'), '654321');
      await user.click(screen.getByRole('button', { name: /confirm number/i }));

      await waitFor(() => expect(confirmPhoneVerificationCode).toHaveBeenCalledWith(FAKE_CONFIRMATION, '654321'));
      await waitFor(() => expect(phoneChangeApi.verifyOtp).toHaveBeenCalledWith('change-session-1', 'fake-firebase-id-token'));
      await waitFor(() => expect(phoneChangeApi.complete).toHaveBeenCalledWith('change-session-1'));
      await waitFor(() => expect(setPhoneVerified).toHaveBeenCalledWith(true));
    });

    it('shows an inline error and does not complete when Firebase rejects the confirmation code', async () => {
      vi.mocked(sendPhoneVerificationCode).mockRejectedValueOnce({ code: 'auth/invalid-app-credential' });
      vi.mocked(confirmPhoneVerificationCode).mockRejectedValue({ code: 'auth/invalid-verification-code' });
      renderVerifyPhone();
      const user = userEvent.setup();
      await openChangeNumberForm(user);
      await user.type(screen.getByPlaceholderText('XXXXXXXXXX'), '9888888888');
      await user.click(screen.getByRole('button', { name: /send code/i }));
      await screen.findByText(/\+•••••••••888/);

      await user.type(screen.getByPlaceholderText('123456'), '000000');
      await user.click(screen.getByRole('button', { name: /confirm number/i }));

      expect(await screen.findByText(/doesn't match/i)).toBeInTheDocument();
      expect(phoneChangeApi.complete).not.toHaveBeenCalled();
    });

    it('Back returns to the original verify screen without starting a session', async () => {
      vi.mocked(sendPhoneVerificationCode).mockRejectedValueOnce({ code: 'auth/invalid-app-credential' });
      renderVerifyPhone();
      const user = userEvent.setup();
      await openChangeNumberForm(user);

      await user.click(screen.getByRole('button', { name: /^back$/i }));

      expect(await screen.findByRole('heading', { name: /verify your phone/i })).toBeInTheDocument();
      expect(phoneChangeApi.start).not.toHaveBeenCalled();
    });
  });
});
