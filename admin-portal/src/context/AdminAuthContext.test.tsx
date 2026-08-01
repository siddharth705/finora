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
   * Bug fix (superseding an older version of this test that asserted the opposite): a
   * freshly-created admin account (e.g. from /setup) is phone-unverified by default. login()
   * used to always call loadAccess() next, which hit /users/me/access -- not excluded from
   * PhoneVerificationFilter -- and got a 403, at which point the old code cleared the session
   * entirely. That meant the token needed to actually call /phone/send-otp and /phone/verify-otp
   * on VerifyPhone.tsx was already gone by the time anything could redirect there, leaving no way
   * to ever complete verification. login() now checks response.phoneVerified directly (the info
   * was already on the response, just previously ignored -- see ADR-0001) and skips loadAccess()
   * entirely in this case: the token and account info are kept, login() resolves to `false`
   * (not a rejection) so Login.tsx can route to /verify-phone with a still-valid session, and
   * meApi.access() is never called until completePhoneVerification() does so afterward.
   */
  it('keeps the token and resolves to false when the account is not yet phone-verified', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      token: 'tok', refreshToken: 'refresh', email: 'amy@example.com', fullName: 'Amy Admin', phoneVerified: false,
    });
    const { result } = renderHook(() => useAdminAuth(), { wrapper });

    const resolvedTo = await act(() => result.current.login('amy@example.com', 'password'));

    expect(resolvedTo).toBe(false);
    expect(result.current.token).toBe('tok');
    expect(localStorage.getItem('finora_admin_token')).toBe('tok');
    expect(result.current.phoneVerified).toBe(false);
    expect(meApi.access).not.toHaveBeenCalled();
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
