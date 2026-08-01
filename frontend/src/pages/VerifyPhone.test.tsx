import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import VerifyPhone from './VerifyPhone';
import { useAuth } from '../context/AuthContext';
import { phoneApi } from '../api/endpoints';

vi.mock('../context/AuthContext', () => ({
  useAuth: vi.fn(),
}));

vi.mock('../api/endpoints', () => ({
  phoneApi: {
    sendOtp: vi.fn(),
    verifyOtp: vi.fn(),
  },
}));

function renderAt(path: string, state?: unknown) {
  vi.mocked(useAuth).mockReturnValue({
    token: 'tok', email: 'jane@example.com', fullName: 'Jane', phoneVerified: false,
    login: vi.fn(), register: vi.fn(), setPhoneVerified: vi.fn(), logout: vi.fn(),
  });
  return render(
    <MemoryRouter initialEntries={[{ pathname: path, state }]}>
      <Routes>
        <Route path="/verify-phone" element={<VerifyPhone />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('VerifyPhone', () => {
  beforeEach(() => {
    vi.mocked(phoneApi.sendOtp).mockReset().mockResolvedValue({
      message: 'A verification code has been sent to your phone.',
      devOtp: null,
      maskedPhone: '+•••••••••705',
    });
  });

  /**
   * Bug fix: reached via Login.tsx (a returning user whose phone still isn't verified), no OTP
   * had ever been issued to get here -- unlike Register.tsx, which sends one automatically and
   * seeds this page's state with it. This mirrors admin-portal's VerifyPhone.tsx, which already
   * always auto-sends for exactly this reason.
   */
  it('auto-sends a code on mount when reached with no router state (the login path)', async () => {
    renderAt('/verify-phone');

    await waitFor(() => expect(phoneApi.sendOtp).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/\+•••••••••705/)).toBeInTheDocument();
  });

  it('does NOT auto-send when reached via Register with a seeded code (avoids sending twice)', async () => {
    renderAt('/verify-phone', { devOtp: '123456', maskedPhone: '+•••••••••705' });

    // Give any accidental auto-send a chance to fire before asserting it didn't.
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(phoneApi.sendOtp).not.toHaveBeenCalled();
    expect(screen.getByText(/\+•••••••••705/)).toBeInTheDocument();
    expect(screen.getByText('123456')).toBeInTheDocument();
  });

  it('updates the displayed masked phone after clicking Resend', async () => {
    const user = userEvent.setup();
    // Seeded so the initial auto-send guard is skipped -- this test is about Resend specifically.
    renderAt('/verify-phone', { devOtp: null, maskedPhone: '+•••••••••111' });
    expect(screen.getByText(/\+•••••••••111/)).toBeInTheDocument();

    vi.mocked(phoneApi.sendOtp).mockResolvedValueOnce({
      message: 'A verification code has been sent to your phone.',
      devOtp: null,
      maskedPhone: '+•••••••••705',
    });
    await user.click(screen.getByText("Didn't get a code? Resend"));

    expect(await screen.findByText(/\+•••••••••705/)).toBeInTheDocument();
  });

  it('falls back to generic text when no masked phone is available', () => {
    renderAt('/verify-phone', { devOtp: '123456', maskedPhone: null });
    expect(screen.getByText(/your mobile number/)).toBeInTheDocument();
  });
});
