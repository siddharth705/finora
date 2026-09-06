import { Text } from 'react-native';
import { act, render, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { GoogleSignin } from '@react-native-google-signin/google-signin';
import { AuthProvider, useAuth } from './AuthContext';
import { api, rawApi } from '../api/client';
import { safeStorage } from '../lib/safeStorage';

// AuthProvider now calls configureRevenueCat() at sign-in -- unmocked, this pulls in the real
// react-native-purchases package, which Jest can't transform (an ESM-only transitive dependency).
// This test has nothing to do with RevenueCat.
jest.mock('../lib/revenueCat', () => ({ configureRevenueCat: jest.fn() }));

/**
 * MOB-AUTH-04 -- ending a Finora session must also end the Google one.
 *
 * MOB-AUTH-02 and MOB-AUTH-03 cleared the React Query cache on logout and on expiry, so the next
 * person on the device is not shown the previous person's money. Both stopped one step short: the
 * app calls GoogleSignin.signIn() and never GoogleSignin.signOut(), so Google's own cached
 * credential survives every exit.
 *
 * The consequence is worse than the cache was. Signing out clears Finora's token, then the next
 * person taps "Sign in with Google", the SDK silently returns the PREVIOUS user's id token without
 * showing an account picker, the backend accepts it, and they are inside that account -- balances,
 * transactions, statements. The cache leak disclosed data; this hands over a live session, through
 * the one control a user trusts to prevent exactly that.
 *
 * Asserted at both exits for the reason MOB-AUTH-03's header gives: there are two ways to stop
 * being signed in and only one of them is a button.
 */

const TOKEN_KEY = 'finora_token';
const REFRESH_TOKEN_KEY = 'finora_refresh_token';
const EMAIL_KEY = 'finora_email';

const mockedGoogleSignin = GoogleSignin as jest.Mocked<typeof GoogleSignin>;

let auth: ReturnType<typeof useAuth>;
function Capture() {
  auth = useAuth();
  return <Text testID="token">{auth.token ?? 'none'}</Text>;
}

/** Same cold-start restore MOB-AUTH-03 uses -- the token is present because the real bootstrap
 *  path put it there, not because a test assigned it. */
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
  expect(auth.token).toBe('A-access-token');
  return { ...utils, queryClient };
}

/** Answers every request with a 401 -- what an expired access token looks like. */
function respond401(instance: typeof api) {
  instance.defaults.adapter = (async (config: unknown) => {
    const err: Error & { config?: unknown; response?: unknown } = new Error('Session expired');
    err.config = config;
    err.response = { status: 401, data: { message: 'Session expired' }, config };
    throw err;
  }) as never;
}

describe('MOB-AUTH-04: ending a session ends the Google session too', () => {
  it('signs out of Google when the user signs out of Fynora', async () => {
    await renderSignedIn();

    await act(async () => {
      auth.logout();
    });

    await waitFor(() => expect(mockedGoogleSignin.signOut).toHaveBeenCalled());
  });

  it('signs out of Google when the session expires rather than being ended deliberately', async () => {
    // The path users actually take. Nobody chooses an expiry, and it ends on the Login screen
    // looking exactly like a clean sign-out -- so it has to leave the device in the same state.
    respond401(api);
    respond401(rawApi);
    await safeStorage.setItem(REFRESH_TOKEN_KEY, 'A-refresh-token');
    await renderSignedIn();

    await act(async () => {
      await api.get('/dashboard/summary').catch(() => {});
    });

    await waitFor(() => expect(auth.token).toBeNull());
    expect(mockedGoogleSignin.signOut).toHaveBeenCalled();
  });

  it('still completes the sign-out when Google was never configured on this device', async () => {
    // signOut() throws if configure() has never run -- a password-only user, or a build with no
    // web client id. That must not abort the rest of the sign-out, or the failure mode is a user
    // who pressed "Log out" and is still signed in.
    mockedGoogleSignin.signOut.mockRejectedValueOnce(new Error('RNGoogleSignin: not configured'));
    await renderSignedIn();

    await act(async () => {
      auth.logout();
    });

    await waitFor(() => expect(auth.token).toBeNull());
    expect(auth.email).toBeNull();
  });
});
