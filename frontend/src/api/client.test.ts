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
    localStorage.setItem('finora_refresh_token', 'the-refresh-token');
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
});
