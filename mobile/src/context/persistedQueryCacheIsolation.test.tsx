import { Text } from 'react-native';
import { act, render, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider, dehydrate } from '@tanstack/react-query';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { AuthProvider, useAuth } from './AuthContext';

// AuthProvider now calls configureRevenueCat() at sign-in -- unmocked, this pulls in the real
// react-native-purchases package, which Jest can't transform (an ESM-only transitive dependency).
// This test has nothing to do with RevenueCat.
jest.mock('../lib/revenueCat', () => ({ configureRevenueCat: jest.fn() }));

/**
 * The AsyncStorage counterpart of MOB-AUTH-02 (logoutCacheIsolation.test.tsx). That test proved
 * signing out clears the IN-MEMORY React Query cache -- queryClient.clear() has no way to reach a
 * file on disk, so it says nothing about the copy Item B's persistence layer
 * (startQueryPersistence, api/queryClient.ts) writes to AsyncStorage. Without
 * clearPersistedQueryCache wired into the same clearLocalState convergence point, the next person
 * to sign in on a shared device would have their very first frame painted from the previous
 * account's persisted balances, restored from disk before a single real request completes -- the
 * same disclosure MOB-AUTH-02 fixed for memory, one layer further down.
 */

jest.mock('../api/endpoints', () => ({
  authApi: {
    login: jest.fn(),
    register: jest.fn(),
    logout: jest.fn(async () => ({ message: 'ok' })),
  },
}));

const PERSIST_KEY = 'finora_query_cache';

let auth: ReturnType<typeof useAuth>;
function Capture() {
  auth = useAuth();
  return <Text testID="token">{auth.token ?? 'none'}</Text>;
}

function renderWithCache() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Capture />
      </AuthProvider>
    </QueryClientProvider>
  );
}

describe('signing out clears the AsyncStorage-persisted query cache', () => {
  it('removes the persisted blob, not just the in-memory cache', async () => {
    renderWithCache();
    await waitFor(() => expect(auth.bootstrapping).toBe(false));

    // Stands in for Item B's own persister having already written a save.
    const seed = new QueryClient();
    seed.setQueryData(['dashboard-summary'], { currentBalance: 555000 });
    await AsyncStorage.setItem(
      PERSIST_KEY,
      JSON.stringify({ timestamp: Date.now(), buster: '1', clientState: dehydrate(seed) })
    );
    seed.clear();

    await act(async () => {
      auth.logout();
    });

    expect(await AsyncStorage.getItem(PERSIST_KEY)).toBeNull();
  });
});
