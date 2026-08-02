import { describe, it, expect, vi, beforeEach } from 'vitest';
import { api, normalizeApiBase } from './client';

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
    localStorage.setItem('finora_admin_refresh_token', 'the-refresh-token');
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
    expect(localStorage.getItem('finora_admin_token')).toBe('new-access-token');
  });
});
