import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import type { ReactNode } from 'react';
import { AdminAuthProvider, useAdminAuth } from './AdminAuthContext';
import { authApi, meApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  authApi: { login: vi.fn() },
  meApi: { access: vi.fn() },
}));

function wrapper({ children }: { children: ReactNode }) {
  return <AdminAuthProvider>{children}</AdminAuthProvider>;
}

describe('AdminAuthContext.login', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(authApi.login).mockReset();
    vi.mocked(meApi.access).mockReset();
  });

  it('signs in successfully and populates permissions when the account has admin access', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      token: 'tok', refreshToken: 'refresh', email: 'amy@example.com', fullName: 'Amy Admin', phoneVerified: true,
    });
    vi.mocked(meApi.access).mockResolvedValue({ roles: ['SUPER_ADMIN'], permissions: ['USER_VIEW', 'AUDIT_VIEW'] });
    const { result } = renderHook(() => useAdminAuth(), { wrapper });

    await act(() => result.current.login('amy@example.com', 'password'));

    expect(result.current.token).toBe('tok');
    expect(result.current.permissions).toEqual(['USER_VIEW', 'AUDIT_VIEW']);
  });

  /**
   * Regression test for the actual bug: a freshly-created admin account (e.g. from /setup) is
   * phone-unverified by default. /auth/login succeeds regardless (it's excluded from
   * PhoneVerificationFilter), but the follow-up /users/me/access call is not excluded from that
   * filter and gets a 403. The token must not survive that failure -- otherwise Login.tsx's own
   * `if (token) return <Navigate to="/" />` fires on the next render before this error message
   * ever gets shown, silently landing the caller in the admin shell with zero permissions instead
   * of a clear "verify your phone" message.
   */
  it('clears the session and shows a phone-verification message, never leaving a token behind', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      token: 'tok', refreshToken: 'refresh', email: 'amy@example.com', fullName: 'Amy Admin', phoneVerified: false,
    });
    vi.mocked(meApi.access).mockRejectedValue({ response: { data: { errorCode: 'PHONE_VERIFICATION_REQUIRED' } } });
    const { result } = renderHook(() => useAdminAuth(), { wrapper });

    await expect(act(() => result.current.login('amy@example.com', 'password')))
      .rejects.toThrow(/phone number isn.t verified/);

    expect(result.current.token).toBeNull();
    expect(localStorage.getItem('finora_admin_token')).toBeNull();
    expect(result.current.permissions).toEqual([]);
  });

  it('clears the session on any other loadAccess failure too, not just phone verification', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      token: 'tok', refreshToken: 'refresh', email: 'amy@example.com', fullName: 'Amy Admin', phoneVerified: true,
    });
    vi.mocked(meApi.access).mockRejectedValue({ response: { data: { message: 'Server error' } } });
    const { result } = renderHook(() => useAdminAuth(), { wrapper });

    await expect(act(() => result.current.login('amy@example.com', 'password'))).rejects.toThrow();

    expect(result.current.token).toBeNull();
  });

  it('clears the session when the account has no admin-relevant permissions at all', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      token: 'tok', refreshToken: 'refresh', email: 'amy@example.com', fullName: 'Amy Admin', phoneVerified: true,
    });
    vi.mocked(meApi.access).mockResolvedValue({ roles: ['USER'], permissions: ['REPORT_VIEW'] });
    const { result } = renderHook(() => useAdminAuth(), { wrapper });

    await expect(act(() => result.current.login('amy@example.com', 'password')))
      .rejects.toThrow(/doesn.t have any admin permissions/);

    expect(result.current.token).toBeNull();
  });
});
