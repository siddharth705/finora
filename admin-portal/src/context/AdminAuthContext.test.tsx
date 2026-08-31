import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { AdminAuthProvider, useAdminAuth, AdminAccessError } from './AdminAuthContext';
import { authApi, meApi } from '../api/endpoints';
import { getAdminToken, setAdminToken } from '../api/client';

vi.mock('../api/endpoints', () => ({
  authApi: { login: vi.fn(), refresh: vi.fn(), verifyMfa: vi.fn() },
  meApi: { access: vi.fn() },
}));

function wrapper({ children }: { children: ReactNode }) {
  return <AdminAuthProvider>{children}</AdminAuthProvider>;
}

describe('AdminAuthContext.login', () => {
  beforeEach(() => {
    localStorage.clear();
    // SEC-01: the access token is a module-level variable now (client.ts), not storage.
    setAdminToken(null);
    vi.mocked(authApi.login).mockReset();
    vi.mocked(meApi.access).mockReset();
    // AdminAuthProvider now attempts a silent refresh on every mount (SEC-01 bootstrap, recovering
    // a session from the HttpOnly cookie after a reload) -- rejecting by default here models "no
    // cookie, not logged in," the state every test below already assumes before it calls login()
    // itself. The one test that cares about a successful bootstrap overrides this explicitly.
    vi.mocked(authApi.refresh).mockReset().mockRejectedValue(new Error('no session'));
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
    expect(getAdminToken()).toBe('tok');
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

  // Admin MFA UI (SEC-03): errorCode/details are threaded through onto the thrown
  // AdminAccessError -- without this, Login.tsx would have no way to tell "MFA required" apart
  // from any other login failure, or reach the mfaChallengeToken to start that step.
  it('surfaces errorCode and details on AUTH_MFA_REQUIRED as an AdminAccessError', async () => {
    vi.mocked(authApi.login).mockRejectedValue({
      response: { data: { message: 'MFA required.', errorCode: 'AUTH_008', details: { mfaChallengeToken: 'chal-abc' } } },
    });
    const { result } = renderHook(() => useAdminAuth(), { wrapper });

    let caught: unknown;
    await act(async () => {
      try {
        await result.current.login('amy@example.com', 'password');
      } catch (err) {
        caught = err;
      }
    });

    expect(caught).toBeInstanceOf(AdminAccessError);
    expect((caught as AdminAccessError).code).toBe('AUTH_008');
    expect((caught as AdminAccessError).details).toEqual({ mfaChallengeToken: 'chal-abc' });
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

describe('AdminAuthContext.completeMfaChallenge', () => {
  beforeEach(() => {
    localStorage.clear();
    setAdminToken(null);
    vi.mocked(authApi.verifyMfa).mockReset();
    vi.mocked(meApi.access).mockReset();
    vi.mocked(authApi.refresh).mockReset().mockRejectedValue(new Error('no session'));
  });

  // Same applySuccessfulAuth() path login() uses -- these three tests mirror login()'s own
  // three success/branch/failure cases above, just reached via the MFA-challenge step instead of
  // a password. If these two ever diverge, an MFA sign-in and a password sign-in stop being
  // equivalent, which completeMfaLogin()'s identical AuthResponse shape (backend) says they should be.
  it('signs in successfully and populates permissions once the challenge is verified', async () => {
    vi.mocked(authApi.verifyMfa).mockResolvedValue({
      token: 'tok', refreshToken: 'refresh', email: 'amy@example.com', fullName: 'Amy Admin', phoneVerified: true,
    });
    vi.mocked(meApi.access).mockResolvedValue({ roles: ['SUPER_ADMIN'], permissions: ['USER_VIEW'] });
    const { result } = renderHook(() => useAdminAuth(), { wrapper });

    const resolvedTo = await act(() => result.current.completeMfaChallenge('chal-123', '123456'));

    expect(resolvedTo).toBe(true);
    expect(authApi.verifyMfa).toHaveBeenCalledWith('chal-123', '123456');
    expect(result.current.token).toBe('tok');
    expect(result.current.permissions).toEqual(['USER_VIEW']);
  });

  it('resolves to false and skips loadAccess() when the account is not yet phone-verified', async () => {
    vi.mocked(authApi.verifyMfa).mockResolvedValue({
      token: 'tok', refreshToken: 'refresh', email: 'amy@example.com', fullName: 'Amy Admin', phoneVerified: false,
    });
    const { result } = renderHook(() => useAdminAuth(), { wrapper });

    const resolvedTo = await act(() => result.current.completeMfaChallenge('chal-123', '123456'));

    expect(resolvedTo).toBe(false);
    expect(result.current.token).toBe('tok');
    expect(meApi.access).not.toHaveBeenCalled();
  });

  it('throws a friendly AdminAccessError on a wrong or expired code', async () => {
    vi.mocked(authApi.verifyMfa).mockRejectedValue({
      response: { data: { message: "That code didn't work. Check your authenticator app and try again." } },
    });
    const { result } = renderHook(() => useAdminAuth(), { wrapper });

    await expect(act(() => result.current.completeMfaChallenge('chal-123', '000000')))
      .rejects.toThrow(/that code didn't work/i);
    expect(result.current.token).toBeNull();
  });
});

describe('AdminAuthContext SEC-01 bootstrap', () => {
  beforeEach(() => {
    localStorage.clear();
    setAdminToken(null);
    vi.mocked(authApi.login).mockReset();
    vi.mocked(meApi.access).mockReset();
    vi.mocked(authApi.refresh).mockReset();
  });

  /**
   * SEC-01 (docs/quality/bug-reports/2026-08-19-security-review-findings.md): the access token no
   * longer survives a reload (in-memory only, see client.ts), but the HttpOnly refresh cookie
   * does. A returning, already-logged-in admin must be recovered on mount via a silent refresh,
   * not bounced to the login screen just because `token` starts null now.
   */
  it('recovers an existing session on mount via a silent refresh against the HttpOnly cookie', async () => {
    vi.mocked(authApi.refresh).mockResolvedValue({ token: 'recovered-tok', refreshToken: 'refresh' });
    vi.mocked(meApi.access).mockResolvedValue({ roles: ['SUPER_ADMIN'], permissions: ['USER_VIEW'] });

    const { result } = renderHook(() => useAdminAuth(), { wrapper });

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.token).toBe('recovered-tok');
    expect(result.current.permissions).toEqual(['USER_VIEW']);
    expect(getAdminToken()).toBe('recovered-tok');
  });

  it('ends up logged out, not stuck loading, when there is no cookie to recover from', async () => {
    vi.mocked(authApi.refresh).mockRejectedValue(new Error('no session'));

    const { result } = renderHook(() => useAdminAuth(), { wrapper });

    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.token).toBeNull();
    expect(meApi.access).not.toHaveBeenCalled();
  });
});
