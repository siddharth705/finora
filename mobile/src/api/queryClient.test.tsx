import type { ReactNode } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, QueryClientProvider, dehydrate, useQuery } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react-native';
import { clearPersistedQueryCache, queryClient, startQueryPersistence } from './queryClient';

const PERSIST_KEY = 'finora_query_cache';

function blob(clientState: unknown, over: Partial<{ timestamp: number; buster: string }> = {}) {
  return JSON.stringify({ timestamp: Date.now(), buster: '1', clientState, ...over });
}

// `seed` QueryClients below exist only to build a dehydrated payload -- cleared immediately after,
// same reasoning as queryPersistence.test.ts's own throwaway clients: an unobserved query
// schedules its own 5-minute GC timer the instant it's set, which is a real leaked timer across a
// whole test file's worth of them if left uncleared.
function seededDehydratedState(queryKey: unknown[], data: unknown) {
  const seed = new QueryClient();
  seed.setQueryData(queryKey, data);
  const state = dehydrate(seed);
  seed.clear();
  return state;
}

afterEach(() => queryClient.clear());

describe('startQueryPersistence', () => {
  it('restores a previously persisted, allowed query into the shared queryClient', async () => {
    await AsyncStorage.setItem(PERSIST_KEY, blob(seededDehydratedState(['dashboard-summary'], { currentBalance: 42 })));

    startQueryPersistence();

    await waitFor(() =>
      expect(queryClient.getQueryData(['dashboard-summary'])).toEqual({ currentBalance: 42 })
    );
  });

  it('discards a persisted cache older than 24h instead of restoring it', async () => {
    await AsyncStorage.setItem(
      PERSIST_KEY,
      blob(seededDehydratedState(['dashboard-summary'], { currentBalance: 999 }), {
        timestamp: Date.now() - 25 * 60 * 60 * 1000,
      })
    );

    startQueryPersistence();

    await waitFor(async () => expect(await AsyncStorage.getItem(PERSIST_KEY)).toBeNull());
    expect(queryClient.getQueryData(['dashboard-summary'])).toBeUndefined();
  });

  it('treats restored data as stale immediately and refetches it in the background, even though it is still within staleTime', async () => {
    await AsyncStorage.setItem(PERSIST_KEY, blob(seededDehydratedState(['dashboard-summary'], { currentBalance: 42 })));

    // A queryFn that resolves to a DIFFERENT value than what's restored, so a passing test proves
    // a real background refetch happened -- not just that the cache still holds the restored value.
    const queryFn = jest.fn().mockResolvedValue({ currentBalance: 99 });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    // Mounted BEFORE persistence restores, mirroring a screen that's already on screen at boot --
    // invalidateQueries only triggers an immediate refetch for queries with an active observer.
    renderHook(() => useQuery({ queryKey: ['dashboard-summary'], queryFn }), { wrapper });

    startQueryPersistence();

    // Restored data is never trusted as fresh: a background refetch fires without anything else
    // asking for it, and the UI-visible cache value updates once it resolves. (The intermediate
    // "restored: 42, not yet refetched" state is real -- confirmed by manual tracing -- but not
    // reliably observable here: under instant mocks, restore and refetch both resolve within the
    // same tick, often before waitFor's first poll runs at all. queryFn actually being called is
    // what proves a genuine refetch happened, not just that the restored value stuck around.)
    await waitFor(() => expect(queryFn).toHaveBeenCalled());
    await waitFor(() =>
      expect(queryClient.getQueryData(['dashboard-summary'])).toEqual({ currentBalance: 99 })
    );
  });
});

describe('clearPersistedQueryCache', () => {
  it('removes the persisted blob from AsyncStorage', async () => {
    await AsyncStorage.setItem(PERSIST_KEY, blob({ queries: [], mutations: [] }));

    await clearPersistedQueryCache();

    expect(await AsyncStorage.getItem(PERSIST_KEY)).toBeNull();
  });
});
