import { Text } from 'react-native';
import { act, render, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useAuth } from './AuthContext';
import { api, rawApi } from '../api/client';
import { safeStorage } from '../lib/safeStorage';

/**
 * MOB-AUTH-03 -- a session that EXPIRES must clear the cache, exactly as signing out does.
 *
 * MOB-AUTH-02 (logoutCacheIsolation.test.tsx) established the disclosure: the financial query keys
 * carry no user identity, React Query serves cached data synchronously on mount, so whatever is
 * left in the cache is rendered to whoever signs in next. It fixed that in `logout()`, and that
 * turned out to be half the problem. There are two ways to stop being signed in, and only one of
 * them is a button. A session expiring -- a refresh the server rejects, or a refresh token that
 * isn't there -- unwound through the API client's `clearSessionAndRedirect()` instead, which
 * cleared storage and auth state and left the cache fully populated.
 *
 * That is the worse half. Nobody chooses an expiry, so the unprotected path is the one users
 * actually take, and it ends on the Login screen looking exactly like a clean sign-out.
 *
 * Reproduced before the fix moved: A's {currentBalance: 86667, monthlyExpense: 13333} was still
 * readable from the cache after expiry. Those are the numbers asserted below.
 *
 * Driven through the real interceptor with a fake server underneath it, the same way
 * refreshRace.test.ts does, rather than by calling the registered callback directly. The defect was
 * never in `clearLocalState` -- it was in which paths reached the clear. A test that invokes the
 * callback itself assumes the wiring it is supposed to be checking, and would have passed just as
 * happily before the fix.
 */

const TOKEN_KEY = 'finora_token';
const REFRESH_TOKEN_KEY = 'finora_refresh_token';
const EMAIL_KEY = 'finora_email';

/** The reproduction's actual figures -- see the header. */
const A_MONEY = { currentBalance: 86667, monthlyExpense: 13333 };

let auth: ReturnType<typeof useAuth>;
function Capture() {
  auth = useAuth();
  return <Text testID="token">{auth.token ?? 'none'}</Text>;
}

/**
 * Restores a signed-in session the way a cold start does -- through SecureStore and AuthProvider's
 * bootstrap effect -- rather than by calling login(). It means `token` is non-null because the real
 * restore path put it there, so the assertion that expiry nulls it is measuring a transition rather
 * than a value that was never set.
 */
async function renderSignedIn() {
  await safeStorage.setItem(TOKEN_KEY, 'A-access-token');
  await safeStorage.setItem(EMAIL_KEY, 'a@example.com');

  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Capture />
      </AuthProvider>
    </QueryClientProvider>
  );

  await waitFor(() => expect(auth.bootstrapping).toBe(false));
  // The premise of every test here: A is signed in, with money on screen.
  expect(auth.token).toBe('A-access-token');

  queryClient.setQueryData(['dashboard-summary'], A_MONEY);
  queryClient.setQueryData(['transactions', {}], { content: [{ id: 't1', amount: -13333 }] });
  queryClient.setQueryData(['accounts'], [{ id: 'a1', balance: 86667 }]);

  return { ...utils, queryClient };
}

/** Answers every request with a 401, which is what an expired access token looks like. */
function respond401(instance: typeof api) {
  instance.defaults.adapter = (async (config: unknown) => {
    const err: Error & { config?: unknown; response?: unknown } = new Error('Session expired');
    // axios normally attaches both; the interceptor reads error.config as the request to replay,
    // so an adapter that omits it fails on a TypeError instead of on the branch under test.
    err.config = config;
    err.response = { status: 401, data: { message: 'Session expired' }, config };
    throw err;
  }) as never;
}

/** Drives one real request through the real interceptor and lets the expiry unwind settle. */
async function requestUntilExpired() {
  await act(async () => {
    await api.get('/dashboard/summary').catch(() => {});
  });
}

describe('MOB-AUTH-03: an expired session clears cached financial data', () => {
  beforeEach(() => {
    respond401(api);
  });

  it('leaves nothing behind when the server rejects the refresh', async () => {
    // The ordinary expiry: a refresh token exists, and the backend refuses it.
    await safeStorage.setItem(REFRESH_TOKEN_KEY, 'A-refresh-token');
    respond401(rawApi);
    const { queryClient } = await renderSignedIn();

    await requestUntilExpired();

    // The expiry genuinely ran -- without this, an empty cache could mean the request never
    // reached the interceptor at all.
    await waitFor(() => expect(auth.token).toBeNull());
    expect(queryClient.getQueryData(['dashboard-summary'])).toBeUndefined();
    expect(queryClient.getQueryData(['transactions', {}])).toBeUndefined();
    expect(queryClient.getQueryData(['accounts'])).toBeUndefined();
  });

  it('leaves nothing behind when there is no refresh token to present', async () => {
    // The other way in: an app killed mid-logout, or storage cleared underneath a live session.
    // refreshAccessToken() throws before any network call, so "no session" and "refresh rejected"
    // land on the same branch -- and must land on the same clear.
    const { queryClient } = await renderSignedIn();

    await requestUntilExpired();

    await waitFor(() => expect(auth.token).toBeNull());
    expect(queryClient.getQueryData(['dashboard-summary'])).toBeUndefined();
  });

  it('empties the cache wholesale rather than the keys someone remembered to list', async () => {
    // Same reasoning as MOB-AUTH-02: an allow-list goes stale the first time a screen adds a query,
    // and the cost of forgetting one is somebody else's money. Asserted here too because this is
    // the path that reaches the clear indirectly, and a future edit could narrow it without the
    // logout test noticing.
    const { queryClient } = await renderSignedIn();
    queryClient.setQueryData(['some-future-screen-nobody-has-written-yet'], A_MONEY);
    queryClient.setQueryData(['report', '2026-08'], { income: 0, expense: 13333 });

    await requestUntilExpired();

    await waitFor(() => expect(auth.token).toBeNull());
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  it('clears the identity shown on screen, not just the token', async () => {
    // Guards the fix from under-reaching in the other direction: a stale name or email is a weaker
    // disclosure than a balance, but it is the same person's data on the next person's screen.
    const { queryClient } = await renderSignedIn();

    await requestUntilExpired();

    await waitFor(() => expect(auth.token).toBeNull());
    expect(auth.email).toBeNull();
    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });
});
