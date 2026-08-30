import type { ReactNode } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, QueryClientProvider, dehydrate, useQuery } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react-native';
import {
  clearPersistedQueryCache,
  pauseQueryPersistence,
  queryClient,
  startQueryPersistence,
} from './queryClient';

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

  it('only invalidates and refetches persisted-domain queries after restore, not unrelated active ones', async () => {
    await AsyncStorage.setItem(PERSIST_KEY, blob(seededDehydratedState(['dashboard-summary'], { currentBalance: 42 })));

    // Stands in for a query outside shouldPersistQuery's allowlist (e.g. Dashboard's own 'goals'
    // query) that was already fetched and is still mounted -- an unscoped invalidateQueries() would
    // force-refetch this too, purely because the restore promise settled, even though it was never
    // persisted and has nothing stale about it.
    const goalsQueryFn = jest.fn().mockResolvedValue(['goal-a']);
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
    renderHook(() => useQuery({ queryKey: ['goals'], queryFn: goalsQueryFn }), { wrapper });
    await waitFor(() => expect(goalsQueryFn).toHaveBeenCalledTimes(1));
    goalsQueryFn.mockClear();

    startQueryPersistence();

    await waitFor(() =>
      expect(queryClient.getQueryData(['dashboard-summary'])).toEqual({ currentBalance: 42 })
    );
    // Give an (incorrect) unscoped invalidation a chance to fire before asserting its absence.
    await new Promise((resolve) => setTimeout(resolve, 50));
    expect(goalsQueryFn).not.toHaveBeenCalled();
  });

  it('never persists mutations to disk, even though the library would by default once one is paused', async () => {
    startQueryPersistence();
    await waitFor(() => expect(queryClient.getQueryData(['dashboard-summary'])).toBeUndefined());

    const mutation = queryClient.getMutationCache().build(queryClient, {
      mutationFn: () => Promise.resolve('done'),
    });
    await mutation.execute({});

    await waitFor(async () => expect(await AsyncStorage.getItem(PERSIST_KEY)).not.toBeNull());
    const persisted = JSON.parse((await AsyncStorage.getItem(PERSIST_KEY)) as string);
    expect(persisted.clientState.mutations).toEqual([]);

    queryClient.getMutationCache().clear();
  });

  it('undoes a stale restore that resolves after a logout/session-expiry clear raced it mid-restore', async () => {
    await AsyncStorage.setItem(PERSIST_KEY, blob(seededDehydratedState(['dashboard-summary'], { currentBalance: 42 })));

    startQueryPersistence();
    // Simulate AuthContext's clearLocalState firing (a 401 arriving) before the restore above --
    // still mid-flight against the mocked AsyncStorage -- has resolved.
    pauseQueryPersistence();
    queryClient.clear();
    await clearPersistedQueryCache();

    // The restore's own pending hydrate() has no cancellation hook and will still run; this proves
    // whatever it resurrects gets cleared back out rather than left sitting in the live cache.
    await waitFor(() => expect(queryClient.getQueryData(['dashboard-summary'])).toBeUndefined());
    expect(await AsyncStorage.getItem(PERSIST_KEY)).toBeNull();
  });
});

describe('pauseQueryPersistence', () => {
  it('stops a subsequent queryClient.clear() from triggering a reactive disk write', async () => {
    await AsyncStorage.removeItem(PERSIST_KEY);
    startQueryPersistence();
    // Nothing to restore (AsyncStorage is empty), so the restore -> subscribe cycle settles almost
    // immediately with zero cache events of its own -- give it a moment to fully finish before this
    // test starts driving cache changes, so every write below is unambiguously attributable to
    // something THIS test did, not a leftover step of startQueryPersistence's own restore flow.
    await new Promise((resolve) => setTimeout(resolve, 50));

    // Prove the persister is genuinely subscribed and reactive first.
    queryClient.setQueryData(['dashboard-summary'], { currentBalance: 7 });
    await waitFor(async () => expect(await AsyncStorage.getItem(PERSIST_KEY)).not.toBeNull());
    await AsyncStorage.removeItem(PERSIST_KEY);
    // setQueryData above can fire more than one cache event, and asyncThrottle's own single-slot
    // design means a second, already-queued write from that same burst could still be pending here,
    // unrelated to anything this test is about to do next. Wait out its 1000ms interval so only a
    // write genuinely triggered by the clear below would show up in the spy.
    await new Promise((resolve) => setTimeout(resolve, 1100));

    const setItemSpy = jest.spyOn(AsyncStorage, 'setItem');
    // AsyncStorage's own mock (src/test/setup.ts) is already a jest.fn(), so spyOn reuses it rather
    // than wrapping a fresh one -- its call history from the "prove reactive" write above is still
    // attached. Clear it so the assertion below reflects only what happens from here on.
    setItemSpy.mockClear();
    pauseQueryPersistence();
    queryClient.clear();
    await clearPersistedQueryCache();
    // Give any (incorrect) reactive write a chance to fire before asserting its absence.
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(setItemSpy).not.toHaveBeenCalled();
    setItemSpy.mockRestore();
  });
});

describe('clearPersistedQueryCache', () => {
  it('removes the persisted blob from AsyncStorage', async () => {
    await AsyncStorage.setItem(PERSIST_KEY, blob({ queries: [], mutations: [] }));

    await clearPersistedQueryCache();

    expect(await AsyncStorage.getItem(PERSIST_KEY)).toBeNull();
  });
});
