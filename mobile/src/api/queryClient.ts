import NetInfo from '@react-native-community/netinfo';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, onlineManager } from '@tanstack/react-query';
import { createAsyncStoragePersister } from '@tanstack/query-async-storage-persister';
import { persistQueryClient } from '@tanstack/react-query-persist-client';
import { shouldPersistQuery } from './queryPersistence';

/**
 * Mirrors the web app's QueryClient config (frontend/src/App.tsx). refetchOnWindowFocus is a
 * browser concept with no native equivalent, and the web app explicitly disables it anyway, so
 * it's simply omitted rather than re-implemented against AppState -- that would enable a behavior
 * the web app deliberately turned off.
 */
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
    },
  },
});

/**
 * Teaches React Query what "online" means on a device.
 *
 * Without this it assumes online forever, which is the right default on the web (the browser has
 * its own online/offline events) but wrong on a phone. Two things were broken as a result:
 * `refetchOnReconnect` never fired, because there was no reconnect event to observe; and queries
 * issued with no connectivity would burn their retry immediately and settle as errors instead of
 * pausing until the network came back.
 *
 * `isInternetReachable` is deliberately preferred over `isConnected`, falling back to it only
 * while the reachability probe is still pending (it's null at that point, not false). They differ
 * in exactly the case worth handling: attached to a wifi network that has no working route out --
 * a captive portal, a hotel network, a router with no upstream. `isConnected` is true there and
 * every request still fails.
 */
export function startNetworkMonitoring(): () => void {
  return NetInfo.addEventListener((state) => {
    onlineManager.setOnline(
      state.isInternetReachable ?? state.isConnected ?? true
    );
  });
}

const PERSIST_KEY = 'finora_query_cache';

// Bump this to invalidate every persisted cache in one release, independent of PERSIST_MAX_AGE
// below -- e.g. after a change to one of the persisted keys' response shape that hydrate() can no
// longer safely restore. maxAge alone wouldn't catch that: a cache written five minutes ago is
// well within 24h but can still be a shape the new code can't use.
const PERSIST_BUSTER = '1';

// 24h: long enough that reopening the app the next morning still shows real figures instantly
// instead of a skeleton; short enough that a persisted balance from days ago never survives to be
// shown as fact. Anything older is discarded wholesale on restore (persistQueryClientRestore's own
// maxAge behavior) rather than shown stale-then-silently-corrected -- a number that's briefly wrong
// and then jumps is worse, in a finance app, than the ordinary loading skeleton it replaces.
const PERSIST_MAX_AGE = 24 * 60 * 60 * 1000;

const persister = createAsyncStoragePersister({
  storage: AsyncStorage,
  key: PERSIST_KEY,
});

/**
 * Restores the last-known dashboard/ledger/reports/budgets figures from AsyncStorage on cold
 * start, so those screens can show real numbers on the very first frame instead of a spinner --
 * and keeps saving the cache back as it changes (throttled by the persister itself). Only ever
 * writes what shouldPersistQuery allows through -- see queryPersistence.ts for the fintech-specific
 * allowlist reasoning; no import/session/error/draft state ever touches disk.
 *
 * Same shape as startNetworkMonitoring above (called the same way from App.tsx's own effect) and
 * undone by clearPersistedQueryCache at the same logout/session-expiry convergence point
 * AuthContext already clears everything else at -- see AuthContext.tsx's clearLocalState.
 */
export function startQueryPersistence(): () => void {
  const [unsubscribe, restored] = persistQueryClient({
    queryClient,
    persister,
    maxAge: PERSIST_MAX_AGE,
    buster: PERSIST_BUSTER,
    dehydrateOptions: { shouldDehydrateQuery: shouldPersistQuery },
  });

  // persistQueryClient (this installed version) has no onSuccess/onError option of its own -- that
  // callback pair only exists on the separate <PersistQueryClientProvider> React component, which
  // this app doesn't use (persistence is started imperatively from App.tsx instead). The second
  // element of persistQueryClient's own return tuple is the restore promise itself, which resolves
  // once restoreClient() has settled (whether or not anything was actually restored) -- see
  // node_modules/@tanstack/query-persist-client-core's persist.ts.
  void restored.then(() => {
    // Restored data is shown instantly, but must never be treated as fresh purely because it
    // falls inside the shared 30s staleTime -- a quick force-quit-and-reopen would otherwise skip
    // revalidation entirely. invalidateQueries() marks every restored query stale AND triggers an
    // immediate background refetch for any with an active observer (a mounted screen), giving the
    // explicit flow: restore -> stale immediately -> background refresh -> UI updates once fresh
    // data arrives.
    void queryClient.invalidateQueries();
    // The <PersistQueryClientProvider>'s own default onSuccess resumes paused (offline-queued)
    // mutations; since this app doesn't use that component, this call preserves that behavior.
    void queryClient.resumePausedMutations();
  });

  return unsubscribe;
}

/**
 * Called from AuthContext's clearLocalState, the same convergence point that clears the in-memory
 * cache (queryClient.clear()) and the persisted nav state (clearPersistedNavigationState). The
 * in-memory clear alone isn't enough: without this, the next person to sign in on a shared device
 * would have their FIRST frame painted from the previous account's persisted AsyncStorage blob,
 * before a single real request completes -- the same leak queryClient.clear()'s own doc comment
 * describes, one layer further down, on disk instead of in memory.
 */
export async function clearPersistedQueryCache(): Promise<void> {
  await persister.removeClient();
}
