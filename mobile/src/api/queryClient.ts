import NetInfo from '@react-native-community/netinfo';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, onlineManager } from '@tanstack/react-query';
import { createAsyncStoragePersister } from '@tanstack/query-async-storage-persister';
import {
  persistQueryClientRestore,
  persistQueryClientSubscribe,
} from '@tanstack/react-query-persist-client';
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
 * The categorization review queue must never retry, and that policy has to live HERE rather than
 * on the useQuery calls that read it.
 *
 * A Query in query-core v5 holds ONE options bag, not one per observer: `setOptions` replaces
 * `this.options` wholesale every time an observer drives a fetch, and a client-driven refetch
 * (`invalidateQueries`/`refetchQueries` -> `query.fetch(undefined, ...)`) carries no observer
 * options at all, so it silently reuses whatever the last fetching observer left behind. Both
 * consumers of these two keys are mounted at once -- the Dashboard nudge (Home is a bottom tab,
 * so it stays mounted) and CategoryReviewScreen -- so with the policy expressed per-observer,
 * whether a refetch retried depended on which screen had fetched most recently, i.e. on
 * navigation order. The Dashboard's nudge is documented to fail silently; instead it could sit
 * through ~7s of backoff holding the pull-to-refresh spinner up.
 *
 * setQueryDefaults writes into the Query's `#defaultOptions`, which is spread back in on every
 * setOptions call and therefore survives all of the above.
 */
queryClient.setQueryDefaults(['needs-review'], { retry: false });
queryClient.setQueryDefaults(['needs-review-groups'], { retry: false });

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

const dehydrateOptions = {
  shouldDehydrateQuery: shouldPersistQuery,
  // persistQueryClient's own default (shouldDehydrateMutation = mutation => mutation.state.isPaused)
  // persists ANY offline-paused mutation's raw payload verbatim -- shouldPersistQuery's allowlist
  // only ever governs queries, so a future useMutation call would silently start writing to
  // plaintext AsyncStorage the moment it's paused offline, bypassing the fintech-safe allowlist
  // this file exists to enforce. No useMutation call exists in this app yet; opting out explicitly
  // means the first one that's added has to make persisting it a conscious choice here, not
  // discover it was already happening.
  shouldDehydrateMutation: () => false,
};

let stopPersisting: (() => void) | null = null;
// True for as long as this app's one-time cold-start restore is still in flight.
let restoring = false;
// Set the instant a logout/session-expiry clear starts, so a restore still in flight when it
// happens knows to undo whatever it hydrates instead of trusting it -- see
// clearPersistedQueryCache's own comment.
let clearedDuringRestore = false;

function subscribe(): void {
  // Idempotent: safe to call from more than one reconciliation path (see startQueryPersistence and
  // clearPersistedQueryCache below) without leaking a previous subscription.
  stopPersisting?.();
  stopPersisting = persistQueryClientSubscribe({
    queryClient,
    persister,
    buster: PERSIST_BUSTER,
    dehydrateOptions,
  });
}

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
 *
 * Built from persistQueryClientRestore/persistQueryClientSubscribe directly rather than the
 * persistQueryClient convenience wrapper, because restoring needs to be reconcilable: the
 * wrapper's restore->hydrate() has no cancellation hook, so a clear that races an in-flight
 * restore (AuthContext's onSessionExpired firing before the AsyncStorage read settles) would
 * otherwise resurrect the departing session's data into the very cache that clear just emptied,
 * with nothing left to notice or undo it. See the clearedDuringRestore check below.
 */
export function startQueryPersistence(): () => void {
  restoring = true;
  clearedDuringRestore = false;

  void persistQueryClientRestore({
    queryClient,
    persister,
    maxAge: PERSIST_MAX_AGE,
    buster: PERSIST_BUSTER,
  }).then(() => {
    restoring = false;

    if (clearedDuringRestore) {
      // A logout/session-expiry raced this restore -- hydrate() already ran (it has no
      // cancellation hook), so undo whatever it just wrote rather than trust it, and resume
      // persisting for whoever signs in next.
      queryClient.clear();
      subscribe();
      return;
    }

    subscribe();
    // Restored data is shown instantly, but must never be treated as fresh purely because it
    // falls inside the shared 30s staleTime -- a quick force-quit-and-reopen would otherwise skip
    // revalidation entirely. invalidateQueries() marks every restored query stale AND triggers an
    // immediate background refetch for any with an active observer (a mounted screen), giving the
    // explicit flow: restore -> stale immediately -> background refresh -> UI updates once fresh
    // data arrives. Scoped to shouldPersistQuery's own allowlist, not every active query --
    // unscoped, this would also force-refetch unrelated screens' data (goals, insights, ...) that
    // was never persisted and has nothing stale about it, and could refetch every cached page of
    // an infinite query like Ledger's in one burst.
    void queryClient.invalidateQueries({ predicate: shouldPersistQuery });
    // The <PersistQueryClientProvider>'s own default onSuccess resumes paused (offline-queued)
    // mutations; since this app doesn't use that component, this call preserves that behavior.
    void queryClient.resumePausedMutations();
  });

  return () => stopPersisting?.();
}

/**
 * Stops the persister reacting to the queryClient.clear() AuthContext is about to call -- called
 * from clearLocalState immediately BEFORE that clear, while the persister is still subscribed.
 * Without this, clear()'s own cache-removal events reach the persister's live subscription and
 * trigger an unthrottled disk write of the still-mostly-intact cache, which can land on disk AFTER
 * clearPersistedQueryCache's own removeClient() below and resurrect the departing session's data
 * for the next person to sign in on this device.
 */
export function pauseQueryPersistence(): void {
  clearedDuringRestore = true;
  stopPersisting?.();
}

/**
 * Called from AuthContext's clearLocalState, the same convergence point that clears the in-memory
 * cache (queryClient.clear(), guarded by pauseQueryPersistence above) and the persisted nav state
 * (clearPersistedNavigationState). The in-memory clear alone isn't enough: without this, the next
 * person to sign in on a shared device would have their FIRST frame painted from the previous
 * account's persisted AsyncStorage blob, before a single real request completes -- the same leak
 * queryClient.clear()'s own doc comment describes, one layer further down, on disk instead of in
 * memory.
 */
export async function clearPersistedQueryCache(): Promise<void> {
  await persister.removeClient();
  // If a restore is still in flight, leave resubscribing to startQueryPersistence's own
  // reconciliation branch above -- subscribing here could start listening again before that
  // restore's still-pending hydrate() call runs, which would immediately re-persist the very data
  // this just removed.
  if (!restoring) subscribe();
}
