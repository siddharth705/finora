import { describe, it, expect, vi, beforeEach } from 'vitest';
import { api, normalizeApiBase } from './client';

const refreshMock = vi.fn();
vi.mock('./endpoints', () => ({
  authApi: { refresh: (...args: unknown[]) => refreshMock(...args) },
}));

/**
 * Regression coverage for a real production bug: VITE_API_BASE_URL was set to the bare Railway
 * origin (e.g. https://confident-wonder-dev.up.railway.app) without the /api/v1 path segment
 * every backend route actually lives under -- every request silently dropped that segment,
 * hitting "<origin>/auth/register" instead of "<origin>/api/v1/auth/register" (a route that
 * doesn't exist), which the browser reported as a CORS failure rather than a 404 (the OPTIONS
 * preflight itself never got a matching route to succeed against). normalizeApiBase makes this
 * correct regardless of which form the env var is set to.
 */
describe('normalizeApiBase', () => {
  it('appends /api/v1 when the raw base is just the bare origin', () => {
    expect(normalizeApiBase('https://confident-wonder-dev.up.railway.app'))
      .toBe('https://confident-wonder-dev.up.railway.app/api/v1');
  });

  it('does not double up /api/v1 when the raw base already includes it', () => {
    expect(normalizeApiBase('https://confident-wonder-dev.up.railway.app/api/v1'))
      .toBe('https://confident-wonder-dev.up.railway.app/api/v1');
  });

  it('strips a trailing slash before checking/appending, either form', () => {
    expect(normalizeApiBase('https://example.com/'))
      .toBe('https://example.com/api/v1');
    expect(normalizeApiBase('https://example.com/api/v1/'))
      .toBe('https://example.com/api/v1');
  });

  it('handles multiple trailing slashes', () => {
    expect(normalizeApiBase('https://example.com///'))
      .toBe('https://example.com/api/v1');
  });
});

// No HTTP-mocking library (msw, axios-mock-adapter) is set up in this project, so this test
// invokes axios's registered response-interceptor rejection handler directly rather than mocking
// a full request/response round trip -- same approach admin-portal's identical client.test.ts
// already established for this exact scenario.
function rejectedHandler() {
  const handlers = (api.interceptors.response as any).handlers;
  return handlers[handlers.length - 1].rejected;
}

describe('api response interceptor', () => {
  beforeEach(() => {
    localStorage.clear();
    refreshMock.mockReset();
  });

  /**
   * Bug fix: refresh tokens rotate server-side on every use (RefreshTokenService.rotate()) --
   * presenting an already-rotated token is treated as theft and revokes every active session for
   * the user, everywhere (see that method's own backend doc comment). Before this fix, N requests
   * 401'ing around the same moment (a very real scenario: the access token expires while a tab is
   * idle, then several components refetch at once when it regains focus) each independently
   * called authApi.refresh() with the SAME refresh token -- only the first ever succeeded; the
   * rest tripped the backend's theft response over a client-side race, not actual theft. This
   * locks in that concurrent 401s now share exactly ONE refresh call.
   */
  it('shares one refresh call across multiple 401s that arrive at the same time, instead of one per request', async () => {
    // BH-012: what says "this browser has a session" is now the ACCESS token. The refresh token
    // lives only in the HttpOnly cookie and this app cannot read it.
    localStorage.setItem('finora_token', 'the-stale-access-token');
    refreshMock.mockResolvedValue({ token: 'new-access-token', refreshToken: 'new-refresh-token' });

    const handler = rejectedHandler();
    const errorFor = (url: string) => ({
      response: { status: 401, data: { message: 'Unauthorized', errorCode: null } },
      config: { url, _retried: false, headers: {} },
    });

    // Three different original requests all 401'ing around the same moment -- the exact scenario
    // an idle tab regaining focus and refetching several pages' worth of data at once produces.
    await Promise.allSettled([
      handler(errorFor('/users/me')),
      handler(errorFor('/categories')),
      handler(errorFor('/accounts')),
    ]);

    expect(refreshMock).toHaveBeenCalledTimes(1);
    expect(localStorage.getItem('finora_token')).toBe('new-access-token');
  });

  /**
   * A 401 from /auth/login means "wrong password", not "your session expired". This branch used to
   * exclude only /auth/refresh, so a failed sign-in ran the refresh-then-clear-session path and
   * hard-navigated to /login -- destroying the React state holding Login.tsx's own inline error
   * before it could render. The user saw a page flash and no explanation. admin-portal's client.ts
   * already guarded every auth endpoint; this app had the AUTH_ENDPOINTS_NO_TOKEN list and used it
   * in the REQUEST interceptor only.
   */
  it('leaves a failed sign-in alone instead of treating it as an expired session', async () => {
    localStorage.setItem('finora_token', 'a-stale-access-token-from-a-previous-session');

    await rejectedHandler()({
      response: { status: 401, data: { message: 'Invalid credentials', errorCode: 'AUTH_001' } },
      config: { url: '/auth/login', _retried: false, headers: {} },
    }).catch(() => { /* the caller's own .catch is what renders the inline error */ });

    expect(refreshMock).not.toHaveBeenCalled();
    // Still signed out (nothing to keep), but crucially NOT cleared-and-redirected: the stale token
    // survives untouched, which is the observable proof clearSessionAndRedirect() never ran.
    expect(localStorage.getItem('finora_token')).toBe('a-stale-access-token-from-a-previous-session');
    expect(localStorage.getItem('finora_session_ended_reason')).toBeNull();
  });

  /**
   * The other half of the same problem: when a session genuinely DOES end, the backend's
   * explanation used to be discarded by clearSessionAndRedirect()'s full-page navigation, so the
   * user landed on the login screen with no idea why. The reason is now handed off through storage
   * for Login.tsx to read once.
   */
  it('hands the backend reason for a real session expiry to the login page', async () => {
    localStorage.setItem('finora_token', 'an-expired-session-access-token');
    refreshMock.mockRejectedValue({
      response: { status: 401, data: { message: 'Refresh token expired — please sign in again.', errorCode: 'AUTH_002' } },
    });

    await rejectedHandler()({
      response: { status: 401, data: { message: 'Unauthorized', errorCode: null } },
      config: { url: '/users/me', _retried: false, headers: {} },
    }).catch(() => { /* expected -- the original 401 is still rejected to the caller */ });

    expect(localStorage.getItem('finora_session_ended_reason'))
      .toBe('Refresh token expired — please sign in again.');
    expect(localStorage.getItem('finora_token')).toBeNull();
  });

  it('falls back to generic copy when there was no refresh token to explain the sign-out', async () => {
    await rejectedHandler()({
      response: { status: 401, data: { message: 'Unauthorized', errorCode: null } },
      config: { url: '/users/me', _retried: false, headers: {} },
    }).catch(() => { /* no-op */ });

    expect(localStorage.getItem('finora_session_ended_reason'))
      .toBe('Your session has ended. Please sign in again to continue.');
  });

  /**
   * BH-013. The in-tab promise above only de-duplicates within ONE JavaScript context. Two open
   * tabs are two contexts with two module instances, so each has its own `refreshInFlight` and
   * neither can see the other's. Both idle tabs wake, both 401, both refresh -- one wins, and the
   * loser presents a token the server has just rotated. Reuse detection reads that as a stolen
   * credential and revokes every session on every device, so the user is signed out of their
   * laptop AND their phone for having two tabs open.
   *
   * <p>What actually prevents the second refresh is not the lock but the RE-CHECK inside it:
   * waiting for the lock and then refreshing anyway would merely serialise the two calls and
   * still present the rotated token. The loser has to notice the work is already done.
   *
   * <p>jsdom does not implement the Web Locks API, so the lock is stubbed here to do what a real
   * contended lock does -- let the other tab finish first, then admit this one.
   */
  it('does not refresh again when another tab already did while this one waited for the lock', async () => {
    localStorage.setItem('finora_token', 'the-stale-access-token');

    (navigator as any).locks = {
      request: async (_name: string, work: () => Promise<unknown>) => {
        // The other tab held the lock, refreshed, and stored its result before releasing.
        localStorage.setItem('finora_token', 'token-the-other-tab-obtained');
        return work();
      },
    };

    // Kept so the retried request's headers can be inspected -- the replayed call has no server
    // to reach in this harness, so what it was replayed WITH is the observable outcome.
    const config = { url: '/users/me', _retried: false, headers: {} as Record<string, string> };

    try {
      await rejectedHandler()({
        response: { status: 401, data: { message: 'Unauthorized', errorCode: null } },
        config,
      }).catch(() => { /* no server behind the retry here */ });

      expect(refreshMock).not.toHaveBeenCalled();
      expect(localStorage.getItem('finora_token')).toBe('token-the-other-tab-obtained');
      expect(config.headers.Authorization)
        .toBe('Bearer token-the-other-tab-obtained');
    } finally {
      delete (navigator as any).locks;
    }
  });

  /** The other side of it: with the lock free and nothing changed, this tab is the one that must
   *  do the work -- the re-check must not swallow a refresh that genuinely needs to happen. */
  it('does refresh, under the lock, when no other tab has beaten it to it', async () => {
    localStorage.setItem('finora_token', 'the-stale-access-token');
    refreshMock.mockResolvedValue({ token: 'new-access-token', refreshToken: 'ignored-by-web' });

    const lockNames: string[] = [];
    (navigator as any).locks = {
      request: async (name: string, work: () => Promise<unknown>) => {
        lockNames.push(name);
        return work();
      },
    };

    try {
      await rejectedHandler()({
        response: { status: 401, data: { message: 'Unauthorized', errorCode: null } },
        config: { url: '/users/me', _retried: false, headers: {} },
      }).catch(() => { /* the retry has no server to reach in this harness */ });

      expect(refreshMock).toHaveBeenCalledTimes(1);
      expect(lockNames)
        .toEqual(['finora-token-refresh']);
      expect(localStorage.getItem('finora_token')).toBe('new-access-token');
    } finally {
      delete (navigator as any).locks;
    }
  });

  /** BH-012: refresh is called with NO argument. The token is in an HttpOnly cookie the browser
   *  attaches itself; if this app ever passes one again it means it has started reading a
   *  credential it is not supposed to be able to read. */
  it('asks for a refresh without supplying a token', async () => {
    localStorage.setItem('finora_token', 'the-stale-access-token');
    refreshMock.mockResolvedValue({ token: 'new-access-token', refreshToken: 'ignored-by-web' });

    await rejectedHandler()({
      response: { status: 401, data: { message: 'Unauthorized', errorCode: null } },
      config: { url: '/users/me', _retried: false, headers: {} },
    }).catch(() => { /* as above */ });

    expect(refreshMock).toHaveBeenCalledWith();
  });

  /**
   * Sprint 4 item 22. The error-shape reduction below used to drop everything except message and
   * errorCode -- backend evidence a page might need (userActionRequired, ErrorCode.
   * userActionRequired() via GlobalExceptionHandler's details map) would have reached this
   * interceptor and then been silently discarded before any component saw it.
   */
  it('surfaces userActionRequired from the error envelope details, not just message and errorCode', async () => {
    const caught = await rejectedHandler()({
      response: {
        status: 422,
        data: {
          message: 'Could not find a transaction table in this file',
          errorCode: 'IMPORT_001',
          details: { userActionRequired: true },
        },
      },
      config: { url: '/import/pdf/stage', _retried: false, headers: {} },
    }).catch((e: unknown) => e);

    expect((caught as any).response.data).toEqual({
      message: 'Could not find a transaction table in this file',
      errorCode: 'IMPORT_001',
      details: { userActionRequired: true },
      userActionRequired: true,
    });
  });

  /**
   * The other half: a codeless ApiException's `details` never gets the key added at all (see
   * GlobalExceptionHandlerTest's own coverage of that) -- this must not be silently coerced to
   * `false` here, which would claim a considered "not actionable" answer that was never actually
   * given. `undefined` is the honest value; callers already treat that the same as false.
   */
  it('leaves userActionRequired undefined, not false, when the backend never sent it at all', async () => {
    const caught = await rejectedHandler()({
      response: {
        status: 500,
        data: { message: 'Unexpected error', errorCode: 'INTERNAL_ERROR', details: {} },
      },
      config: { url: '/import/pdf/stage', _retried: false, headers: {} },
    }).catch((e: unknown) => e);

    expect((caught as any).response.data.userActionRequired).toBeUndefined();
  });
});
