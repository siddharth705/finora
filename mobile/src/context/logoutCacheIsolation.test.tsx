import { Text } from 'react-native';
import { act, render, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthProvider, useAuth } from './AuthContext';
import { sweepFileCache } from '../lib/fileCacheSweep';

// AuthProvider now calls configureRevenueCat() at sign-in -- unmocked, this pulls in the real
// react-native-purchases package, which Jest can't transform (an ESM-only transitive dependency).
// This test has nothing to do with RevenueCat.
jest.mock('../lib/revenueCat', () => ({ configureRevenueCat: jest.fn() }));

/**
 * MOB-AUTH-02 -- logout must not leave one person's money in the cache for the next.
 *
 * Signing out clears auth state, SecureStore and the server-side refresh token. It did NOT clear
 * the React Query cache, and the financial query keys carry no user identity: ['dashboard-summary'],
 * ['transactions'], ['accounts'] are the same keys for everybody. React Query serves cached data
 * synchronously on mount and refetches afterwards, so the next person to sign in on the device
 * renders the PREVIOUS person's balances first, then watches them change.
 *
 * On a shared or handed-over phone that is a disclosure, not a flicker. The window is small and
 * entirely invisible to any test that only checks tokens, which is why nothing caught it.
 */

jest.mock('../api/endpoints', () => ({
  authApi: {
    login: jest.fn(),
    register: jest.fn(),
    logout: jest.fn(async () => ({ message: 'ok' })),
  },
}));

jest.mock('../lib/fileCacheSweep', () => ({ sweepFileCache: jest.fn() }));

const SOMEONE_ELSES_MONEY = { currentBalance: 987654, monthlyExpense: 35500 };

let auth: ReturnType<typeof useAuth>;
function Capture() {
  auth = useAuth();
  return <Text testID="token">{auth.token ?? 'none'}</Text>;
}

function renderWithCache() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Capture />
      </AuthProvider>
    </QueryClientProvider>
  );
  return { ...utils, queryClient };
}

describe('MOB-AUTH-02: signing out clears cached financial data', () => {
  it('leaves no dashboard, transaction or account data behind', async () => {
    const { queryClient } = renderWithCache();
    await waitFor(() => expect(auth.bootstrapping).toBe(false));

    // Stand in for a signed-in session that has loaded its financial screens.
    queryClient.setQueryData(['dashboard-summary'], SOMEONE_ELSES_MONEY);
    queryClient.setQueryData(['transactions', {}], { content: [{ id: 't1', amount: -20000 }] });
    queryClient.setQueryData(['accounts'], [{ id: 'a1', balance: 100000 }]);

    await act(async () => {
      auth.logout();
    });

    // The next person to open this app must not be handed any of it.
    expect(queryClient.getQueryData(['dashboard-summary'])).toBeUndefined();
    expect(queryClient.getQueryData(['transactions', {}])).toBeUndefined();
    expect(queryClient.getQueryData(['accounts'])).toBeUndefined();
  });

  it('empties the cache entirely rather than the few keys someone remembered to list', async () => {
    // A logout that clears an allow-list goes stale the moment a screen adds a query. Anything
    // holding user data must go, including keys this test has never heard of.
    const { queryClient } = renderWithCache();
    await waitFor(() => expect(auth.bootstrapping).toBe(false));

    queryClient.setQueryData(['some-future-screen-nobody-has-written-yet'], SOMEONE_ELSES_MONEY);
    queryClient.setQueryData(['report', '2026-08'], { income: 0, expense: 35500 });

    await act(async () => {
      auth.logout();
    });

    expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  });

  it('still signs the user out locally', async () => {
    // Guards the fix from over-reaching: clearing the cache must not disturb what logout already
    // did correctly.
    const { queryClient } = renderWithCache();
    await waitFor(() => expect(auth.bootstrapping).toBe(false));
    queryClient.setQueryData(['dashboard-summary'], SOMEONE_ELSES_MONEY);

    await act(async () => {
      auth.logout();
    });

    expect(auth.token).toBeNull();
    expect(auth.email).toBeNull();
  });

  // Bug found in review (Track D/D2/D7): App.tsx's own sweepFileCache() call only runs once per
  // JS process lifetime (an empty-deps effect on the root component), so a device that's rarely
  // force-quit could go a long time without a sweep. A picked statement or ticket attachment
  // copied into the cache directory for the departing session should not wait out the rest of
  // sweepFileCache's own one-hour age margin once there's a clean point -- sign-out -- to know for
  // certain that file's purpose is over.
  it('sweeps the file cache on sign-out', async () => {
    renderWithCache();
    await waitFor(() => expect(auth.bootstrapping).toBe(false));

    await act(async () => {
      auth.logout();
    });

    expect(sweepFileCache).toHaveBeenCalled();
  });
});
