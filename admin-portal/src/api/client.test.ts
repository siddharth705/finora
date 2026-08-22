import { describe, it, expect, vi, beforeEach } from 'vitest';
import { api, normalizeApiBase, getAdminToken, setAdminToken } from './client';

const refreshMock = vi.fn();
vi.mock('./endpoints', () => ({
  authApi: { refresh: (...args: unknown[]) => refreshMock(...args) },
}));

/**
 * Regression coverage for a real production bug: VITE_API_BASE_URL got set to the bare Railway
 * origin without the /api/v1 path segment every backend route actually lives under -- see the
 * user frontend's identical client.test.ts for the full story (same fix, same root cause,
 * discovered on that app first).
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
    expect(normalizeApiBase('https://example.com/')).toBe('https://example.com/api/v1');
    expect(normalizeApiBase('https://example.com/api/v1/')).toBe('https://example.com/api/v1');
  });
});

// No HTTP-mocking library (msw, axios-mock-adapter) is set up in this project, so this test
// invokes axios's registered response-interceptor rejection handler directly rather than mocking
// a full request/response round trip -- sufficient to lock in the actual bug fixed here, without
// adding a new test dependency for one regression test.
function rejectedHandler() {
  const handlers = (api.interceptors.response as any).handlers;
  return handlers[handlers.length - 1].rejected;
}

describe('api response interceptor', () => {
  beforeEach(() => {
    localStorage.clear();
    // SEC-01: the access token is now a module-level variable (not localStorage), so
    // localStorage.clear() alone no longer resets it between tests.
    setAdminToken(null);
  });

  it('does not clear the session for a 401 from /auth/login (wrong credentials, not an expired session)', async () => {
    // Regression test: this 401 used to be treated the same as an expired-session 401 -- with no
    // refresh token in storage (a fresh browser, exactly like someone's first login attempt),
    // that fell straight to clearAdminSession() + a hard `window.location.href = '/login'`
    // navigation, wiping out Login.tsx's inline "Invalid credentials" message before it could
    // ever render. Checking that an unrelated stored value survives is a more robust way to
    // confirm clearAdminSession() didn't run than asserting on jsdom's navigation simulation.
    localStorage.setItem('finora_admin_email', 'someone-elses-stale-session@example.com');
    const error = {
      response: { status: 401, data: { message: 'Invalid credentials', errorCode: null } },
      config: { url: '/auth/login', _retried: false },
    };

    await expect(rejectedHandler()(error)).rejects.toBeTruthy();

    expect(localStorage.getItem('finora_admin_email')).toBe('someone-elses-stale-session@example.com');
  });

  /**
   * Bug fix: refresh tokens rotate server-side on every use -- presenting an already-rotated one
   * is treated as theft and revokes every active session for the admin, everywhere (see
   * RefreshTokenService.rotate()'s own backend doc comment). Before this fix, N requests 401'ing
   * around the same moment each independently called authApi.refresh() with the SAME refresh
   * token, so only the first ever succeeded -- the rest tripped the backend's theft response over
   * a client-side race, not actual theft. This locks in that concurrent 401s now share exactly
   * ONE refresh call.
   */
  it('shares one refresh call across multiple 401s that arrive at the same time, instead of one per request', async () => {
    // BH-012: what says "this browser has a session" is now the ACCESS token. The refresh token
    // lives only in the HttpOnly cookie and this app cannot read it.
    setAdminToken('the-stale-access-token');
    refreshMock.mockReset();
    refreshMock.mockResolvedValue({ token: 'new-access-token', refreshToken: 'new-refresh-token' });

    const handler = rejectedHandler();
    const errorFor = (url: string) => ({
      response: { status: 401, data: { message: 'Unauthorized', errorCode: null } },
      config: { url, _retried: false, headers: {} },
    });

    // Three different original requests all 401'ing around the same moment -- the exact scenario
    // an idle tab regaining focus and refetching several widgets at once produces.
    await Promise.allSettled([
      handler(errorFor('/users')),
      handler(errorFor('/roles')),
      handler(errorFor('/audit-logs')),
    ]);

    expect(refreshMock).toHaveBeenCalledTimes(1);
    expect(getAdminToken()).toBe('new-access-token');
  });

  /** BH-012: refresh is called with NO argument. The token is in an HttpOnly cookie the browser
   *  attaches itself; if this app ever passes one again it means it has started reading a
   *  credential it is not supposed to be able to read. Mirrors frontend/src/api/client.test.ts's
   *  identical assertion. */
  it('asks for a refresh without supplying a token', async () => {
    setAdminToken('the-stale-access-token');
    refreshMock.mockReset();
    refreshMock.mockResolvedValue({ token: 'new-access-token', refreshToken: 'ignored-by-web' });

    await rejectedHandler()({
      response: { status: 401, data: { message: 'Unauthorized', errorCode: null } },
      config: { url: '/users', _retried: false, headers: {} },
    }).catch(() => { /* the retry has no server to reach in this harness */ });

    expect(refreshMock).toHaveBeenCalledWith();
  });

  /**
   * When a session genuinely DOES end, the backend's explanation used to be discarded by the
   * hard `window.location.href` navigation, so the admin landed on the login screen with no idea
   * why -- and no way to tell an ordinary expiry apart from every session being revoked after a
   * suspected stolen token. The reason is now handed off through storage for Login.tsx to read
   * once. Same fix, same shape, as the user frontend's client.ts.
   */
  it('hands the backend reason for a real session expiry to the login page', async () => {
    setAdminToken('an-expired-access-token');
    refreshMock.mockReset();
    refreshMock.mockRejectedValue({
      response: {
        status: 401,
        data: {
          message: 'For your security, all sessions were signed out. Please sign in again.',
          errorCode: 'AUTH_004',
        },
      },
    });

    await rejectedHandler()({
      response: { status: 401, data: { message: 'Unauthorized', errorCode: null } },
      config: { url: '/users', _retried: false, headers: {} },
    }).catch(() => { /* the original 401 is still rejected to the caller */ });

    expect(localStorage.getItem('finora_admin_session_ended_reason'))
      .toBe('For your security, all sessions were signed out. Please sign in again.');
    expect(getAdminToken()).toBeNull();
  });

  it('falls back to generic copy when there was no access token to attempt a refresh with', async () => {
    refreshMock.mockReset();

    await rejectedHandler()({
      response: { status: 401, data: { message: 'Unauthorized', errorCode: null } },
      config: { url: '/users', _retried: false, headers: {} },
    }).catch(() => { /* no-op */ });

    expect(localStorage.getItem('finora_admin_session_ended_reason'))
      .toBe('Your session has ended. Please sign in again to continue.');
  });

  it('leaves no sign-out notice behind for a wrong-password 401, which is not a session ending', async () => {
    await rejectedHandler()({
      response: { status: 401, data: { message: 'Invalid credentials', errorCode: 'AUTH_001' } },
      config: { url: '/auth/login', _retried: false, headers: {} },
    }).catch(() => { /* Login.tsx's own catch renders this inline */ });

    expect(localStorage.getItem('finora_admin_session_ended_reason')).toBeNull();
  });

  /**
   * Bug 40. The error-shape reduction below used to drop everything except message and
   * errorCode -- see the user frontend's identical client.test.ts for the full story (same fix,
   * same root cause, fixed there first). Any structured ApiException (field-level import errors,
   * per-row validation, remaining-attempt counts) reached this interceptor and was silently
   * truncated to a bare string before any admin-portal caller could ever see it.
   */
  it('carries the details payload through the error-shape reduction, not just message and errorCode', async () => {
    const caught = await rejectedHandler()({
      response: {
        status: 422,
        data: {
          message: 'Could not find a transaction table in this file',
          errorCode: 'IMPORT_001',
          details: { rowsSkipped: 3 },
        },
      },
      config: { url: '/import/pdf/stage', _retried: false, headers: {} },
    }).catch((e: unknown) => e);

    expect((caught as any).response.data).toEqual({
      message: 'Could not find a transaction table in this file',
      errorCode: 'IMPORT_001',
      details: { rowsSkipped: 3 },
    });
  });
});
