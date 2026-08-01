import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import VerifyPhone from './VerifyPhone';
import { useAdminAuth } from '../context/AdminAuthContext';
import { phoneApi } from '../api/endpoints';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));

vi.mock('../api/endpoints', () => ({
  phoneApi: {
    sendOtp: vi.fn(),
    verifyOtp: vi.fn(),
  },
}));

describe('VerifyPhone (admin portal)', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReturnValue({
      token: 'tok', email: 'admin@example.com', fullName: 'Admin', phoneVerified: false,
      permissions: [], roles: [], loading: false,
      login: vi.fn(), completePhoneVerification: vi.fn(), logout: vi.fn(), hasPermission: vi.fn(),
    });
    vi.mocked(phoneApi.sendOtp).mockReset().mockResolvedValue({
      message: 'A verification code has been sent to your phone.',
      devOtp: null,
      maskedPhone: '+•••••••••705',
    });
  });

  /**
   * Every arrival at this screen (admin-created account, or a returning admin whose phone still
   * isn't verified) has the same gap: no OTP was issued as part of getting here, unlike
   * Register.tsx's flow in the main app. This is the pre-existing always-auto-send behavior --
   * confirming the newly added masked-phone display rides along with it correctly.
   */
  it('auto-sends a code on mount and displays which number it was sent to', async () => {
    render(
      <MemoryRouter>
        <VerifyPhone />
      </MemoryRouter>
    );

    await waitFor(() => expect(phoneApi.sendOtp).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/\+•••••••••705/)).toBeInTheDocument();
  });

  it('falls back to generic text before the send resolves', () => {
    vi.mocked(phoneApi.sendOtp).mockReturnValue(new Promise(() => {})); // never resolves
    render(
      <MemoryRouter>
        <VerifyPhone />
      </MemoryRouter>
    );

    expect(screen.getByText(/your mobile number/)).toBeInTheDocument();
  });
});
