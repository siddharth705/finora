/**
 * MOB-AUTH-01 -- concurrent refresh verification.
 *
 * HYPOTHESIS: a 401 can present a refresh token the server has already rotated, which
 * RefreshTokenService.rotate() reads as a theft signal and answers by revoking every session the
 * user holds, on every device.
 *
 * The single-flight guard in client.ts covers the obvious case: two requests that 401 at the same
 * instant share one refresh call, and the later caller's token argument is discarded outright
 * (refreshAccessToken ignores its parameter once a promise is in flight). Simultaneous 401s are
 * therefore NOT the risk, and the first test below is the control proving that.
 *
 * The window probed here is narrower. `refreshInFlight` is cleared in `.finally()`, which fires the
 * moment the network call settles -- but the rotated token is persisted afterwards, by the
 * interceptor, across two further awaits. Between those points the guard is open while storage
 * still holds the OLD token, so a 401 arriving there reads a stale token and starts a second,
 * genuine refresh with it.
 *
 * Driven through the real axios instances rather than a mocked endpoints module: `authApi.refresh`
 * goes out on the exported `rawApi`, so installing an adapter puts a fake server underneath the
 * true code path -- real interceptor, real endpoints, real storage wrapper. Mocking './endpoints'
 * does not work here anyway; the interceptor reaches it through a dynamic import that resolves
 * past jest.doMock, as client.test.ts already documents.
 *
 * Timing is forced, not hoped for: the write of the rotated token is held open so the window is
 * deterministic. That is the difference between a reproduction and an argument.
 */

process.env.EXPO_PUBLIC_API_BASE_URL = 'https://tests.invalid';

const REFRESH_TOKEN_KEY = 'finora_refresh_token';
const TOKEN_KEY = 'finora_token';

type Deferred = { promise: Promise<void>; release: () => void };
function deferred(): Deferred {
  let release!: () => void;
  const promise = new Promise<void>((resolve) => {
    release = () => resolve();
  });
  return { promise, release };
}

/** Lets queued microtasks and timers drain so an in-flight interceptor reaches its next await. */
async function settle(times = 12) {
  for (let i = 0; i < times; i += 1) await new Promise((r) => setTimeout(r, 0));
}

/**
 * Waits for a condition rather than a fixed number of ticks. The refresh path crosses a real
 * dynamic import, so "how many microtasks until it has issued" is not a fixed number -- pinning one
 * makes the test assert the scheduler instead of the behaviour, and turns a real result into a
 * flake.
 */
async function until(predicate: () => boolean, what: string, budgetMs = 2000) {
  const deadline = Date.now() + budgetMs;
  while (!predicate()) {
    if (Date.now() > deadline) throw new Error(`timed out waiting for: ${what}`);
    await new Promise((r) => setTimeout(r, 1));
  }
}

describe('MOB-AUTH-01: concurrent refresh', () => {
  let api: typeof import('./client').api;
  let rawApi: typeof import('./client').rawApi;
  let secureStore: {
    __store: Map<string, string>;
    getItemAsync: jest.Mock;
    setItemAsync: jest.Mock;
  };

  /** Refresh tokens this fake server has already consumed. Re-presenting one is the theft signal. */
  let consumed: Set<string>;
  /** What each /auth/refresh call actually presented, in order. The evidence being collected. */
  let presented: string[];

  beforeEach(() => {
    jest.resetModules();
    consumed = new Set();
    presented = [];

    const client = require('./client');
    api = client.api;
    rawApi = client.rawApi;
    secureStore = require('expo-secure-store');
    secureStore.__store.clear();
    secureStore.getItemAsync.mockClear();
    secureStore.setItemAsync.mockClear();

    // The fake server, modelling RefreshTokenService.rotate()'s actual contract.
    rawApi.defaults.adapter = (async (config: { url?: string; data?: string }) => {
      if (!config.url?.includes('/auth/refresh')) throw new Error(`unexpected call: ${config.url}`);
      const { refreshToken } = JSON.parse(config.data ?? '{}');
      presented.push(refreshToken);

      if (consumed.has(refreshToken)) {
        // What the backend really does: a rotated token presented twice is treated as stolen.
        const err: Error & { response?: unknown } = new Error('THEFT_SIGNAL');
        err.response = { status: 401, data: { message: 'Refresh token already used' } };
        throw err;
      }
      consumed.add(refreshToken);
      const next = `R${consumed.size + 1}`;
      return {
        data: { data: { token: `access-${next}`, refreshToken: next } },
        status: 200, statusText: 'OK', headers: {}, config,
      };
    }) as never;

    // The interceptor replays the original request on `api` after refreshing; keep that harmless.
    api.defaults.adapter = (async (config: unknown) => ({
      data: { success: true, data: {} }, status: 200, statusText: 'OK', headers: {}, config,
    })) as never;
  });

  function reject401(url = '/accounts') {
    const handler = (api.interceptors.response as unknown as {
      handlers: { rejected: (e: unknown) => Promise<unknown> }[];
    }).handlers[0].rejected;
    return handler({
      config: { url, headers: {} },
      response: { status: 401, data: { message: 'expired' } },
    });
  }

  /** Holds the write of the rotated refresh token open, so the window can be entered deliberately. */
  function holdRefreshTokenWrite(): Deferred {
    const gate = deferred();
    const real = secureStore.setItemAsync.getMockImplementation()!;
    secureStore.setItemAsync.mockImplementation(async (key: string, value: string) => {
      if (key === REFRESH_TOKEN_KEY) await gate.promise;
      return real(key, value);
    });
    return gate;
  }

  it('CONTROL: two simultaneous 401s share a single refresh', async () => {
    // If this fails, the single-flight guard itself is broken and the window test below would be
    // measuring something else entirely.
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');

    await Promise.all([reject401().catch(() => {}), reject401().catch(() => {})]);

    expect(presented).toEqual(['R1']);
  });

  it('does not present an already-rotated token when a 401 lands mid-persist', async () => {
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');
    const gate = holdRefreshTokenWrite();

    // A refreshes, rotating R1 -> R2, then blocks while writing R2 back to storage.
    const a = reject401().catch(() => {});
    await until(() => presented.length === 1, 'A to issue its refresh');
    await until(
      () => secureStore.setItemAsync.mock.calls.some(([k]: [string]) => k === REFRESH_TOKEN_KEY),
      'A to reach the blocked write of the rotated token'
    );

    expect(presented).toEqual(['R1']);
    expect(secureStore.__store.get(REFRESH_TOKEN_KEY)).toBe('R1'); // R2 not yet persisted

    // B arrives inside the window: in-flight guard already cleared, storage still stale.
    const b = reject401().catch(() => {});
    await settle();

    gate.release();
    await Promise.all([a, b]);

    // THE ASSERTION. Presenting R1 twice is the reproduction -- the server reads the second as a
    // stolen token and revokes every session this user has, on every device.
    expect(presented.filter((t) => t === 'R1')).toHaveLength(1);
  });

  it('leaves storage holding a token the server still accepts', async () => {
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');
    const gate = holdRefreshTokenWrite();

    const a = reject401().catch(() => {});
    await settle();
    const b = reject401().catch(() => {});
    await settle();
    gate.release();
    await Promise.all([a, b]);

    // Whatever the ordering, the device must not be left holding a retired token -- that turns the
    // next ordinary request into a full sign-out.
    const stored = secureStore.__store.get(REFRESH_TOKEN_KEY);
    expect(stored === undefined || !consumed.has(stored) || stored === `R${consumed.size + 1}`).toBe(true);
  });

  it('writes the access token and the refresh token together or not at all', async () => {
    // They rotate as a pair; persisting one without the other strands a session that cannot recover.
    secureStore.__store.set(REFRESH_TOKEN_KEY, 'R1');

    await reject401().catch(() => {});

    const wroteAccess = secureStore.setItemAsync.mock.calls.some(([k]) => k === TOKEN_KEY);
    const wroteRefresh = secureStore.setItemAsync.mock.calls.some(([k]) => k === REFRESH_TOKEN_KEY);
    expect(wroteAccess).toBe(wroteRefresh);
  });
});
