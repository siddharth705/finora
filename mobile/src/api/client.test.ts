import { normalizeApiBase } from './client';

/**
 * The refresh-on-401 interceptor, and specifically which requests it must NOT run for.
 *
 * Bug fix regression test: the interceptor used to exclude only '/auth/refresh' from its
 * refresh-and-replay branch, rather than the whole AUTH_ENDPOINTS_NO_TOKEN list the request
 * interceptor already uses. A 401 from /auth/login means "wrong password", not "your session
 * expired". For a signed-out user with a stale refresh token still in SecureStore (an app killed
 * mid-logout, or a logout whose network call failed), one mistyped password sent that stale token
 * to /auth/refresh -- and presenting an already-rotated refresh token is what
 * RefreshTokenService.rotate() treats as a theft signal, revoking every active session for that
 * user on every device.
 *
 * The interceptor is registered as a module side effect against the module's own axios instance,
 * so these drive it through that instance's real handler rather than re-implementing the rule.
 */

// Must be set before client.ts is imported -- it throws at import time when unset.
process.env.EXPO_PUBLIC_API_BASE_URL = 'https://tests.invalid';

const REFRESH_TOKEN_KEY = 'finora_refresh_token';

describe('normalizeApiBase', () => {
  it.each([
    ['https://api.example.com', 'https://api.example.com/api/v1'],
    ['https://api.example.com/', 'https://api.example.com/api/v1'],
    ['https://api.example.com///', 'https://api.example.com/api/v1'],
    ['https://api.example.com/api/v1', 'https://api.example.com/api/v1'],
    ['https://api.example.com/api/v1/', 'https://api.example.com/api/v1'],
  ])('%s -> %s', (input, expected) => {
    expect(normalizeApiBase(input)).toBe(expected);
  });
});

describe('401 handling', () => {
  let api: typeof import('./client').api;
  let secureStore: { __store: Map<string, string>; getItemAsync: jest.Mock };

  /**
   * Reading the stored refresh token is the first thing the refresh-and-replay branch does, and
   * the only thing it does before any network call -- so "was the refresh token read?" is exactly
   * "was the branch entered?". Asserted here rather than spying on authApi.refresh because the
   * interceptor reaches it through a dynamic `import('./endpoints')`, which resolves past
   * jest.doMock and would make the assertion test the mock rather than the code.
   */
  function attemptedRefresh(): boolean {
    return secureStore.getItemAsync.mock.calls.some(([key]) => key === REFRESH_TOKEN_KEY);
  }

  beforeEach(() => {
    jest.resetModules();
    api = require('./client').api;
    secureStore = require('expo-secure-store');
    secureStore.__store.clear();
    secureStore.getItemAsync.mockClear();
  });

  /** Feeds a synthetic 401 straight to the instance's own rejection handler. */
  function reject401(url: string) {
    const handler = (api.interceptors.response as unknown as {
      handlers: { rejected: (e: unknown) => Promise<unknown> }[];
    }).handlers[0].rejected;

    return handler({
      config: { url, headers: {} },
      response: { status: 401, data: { message: 'Invalid email or password' } },
    });
  }

  it('does not attempt a token refresh when the sign-in itself is rejected', async () => {
    // The exact dangerous state: signed out, but a stale refresh token is still on the device.
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'stale-but-present');

    await expect(reject401('/auth/login')).rejects.toBeDefined();

    expect(attemptedRefresh()).toBe(false);
  });

  it.each(['/auth/register', '/auth/forgot-password', '/auth/reset-password', '/auth/refresh'])(
    'does not attempt a token refresh for a 401 from %s',
    async (url) => {
      secureStore.__store.set(REFRESH_TOKEN_KEY, 'stale-but-present');

      await expect(reject401(url)).rejects.toBeDefined();

      expect(attemptedRefresh()).toBe(false);
    }
  );

  /** The positive control: without this, the tests above would still pass if the whole branch
   *  were deleted. */
  it('still attempts a refresh for a 401 on an ordinary authenticated endpoint', async () => {
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'valid-refresh');

    // The replay re-enters axios with no server to reach; only reaching the branch matters here.
    await reject401('/accounts').catch(() => {});

    expect(attemptedRefresh()).toBe(true);
  });
});
