import NetInfo from '@react-native-community/netinfo';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, onlineManager } from '@tanstack/react-query';
import { createAsyncStoragePersister } from '@tanstack/query-async-storage-persister';
import {
  persistQueryClientRestore,
  persistQueryClientSubscribe,
} from '@tanstack/react-query-persist-client';
import { PERSISTED_QUERY_KEY_PREFIXES, shouldPersistQuery } from './queryPersistence';

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

/**
 * Bumped by pauseQueryPersistence on every logout/session-expiry. `pendingWriteEpoch` records the
 * epoch that was current when the persister was last ASKED to write; the guarded setItem below
 * refuses any write whose epoch is no longer current.
 *
 * This exists because unsubscribing is not the same as cancelling. createAsyncStoragePersister
 * wraps persistClient in its own asyncThrottle (1000ms by default) and that throttle has no cancel
 * path -- once a write is scheduled, `await func(...lastArgs)` is guaranteed to run when the
 * interval elapses. Worse, persistQueryClientSave calls `dehydrate(queryClient)` EAGERLY at cache-
 * event time and hands the finished snapshot to the throttle, so emptying the live cache afterwards
 * cannot empty an already-captured payload. stopPersisting() only detaches the cache subscription;
 * it cannot reach inside the throttle. removeClient(), by contrast, is an unthrottled
 * storage.removeItem that runs immediately.
 *
 * So the ordering on logout was: snapshot of user A captured -> write scheduled -> cache cleared ->
 * disk wiped -> ~1s later the queued write lands and puts user A's balances back on disk with a
 * fresh timestamp. Nothing clears the cache on LOGIN (AuthContext.login only persists the new
 * session), so the next cold start hydrated that blob and painted the next person's first frame
 * from the previous account's figures -- the exact disclosure clearPersistedQueryCache exists to
 * prevent, reintroduced one layer below it.
 *
 * Guarding at the storage layer rather than around persistClient is deliberate: the throttle sits
 * between those two, so a wrapper on persistClient can only suppress writes that haven't been
 * scheduled yet -- precisely not the dangerous one. An epoch comparison rather than a timer keeps
 * it deterministic: a write captured before the clear is refused however late it fires, and a write
 * captured after it (the next user's) is allowed immediately, with no interval to tune or wait out.
 */
let cacheEpoch = 0;
let pendingWriteEpoch = 0;

const basePersister = createAsyncStoragePersister({
  storage: {
    getItem: (key) => AsyncStorage.getItem(key),
    removeItem: (key) => AsyncStorage.removeItem(key),
    // The only guarded operation. Reads must still work (restore) and deletes must ALWAYS work --
    // suppressing removeItem would defeat the wipe this guard exists to protect.
    setItem: (key, value) =>
      pendingWriteEpoch === cacheEpoch ? AsyncStorage.setItem(key, value) : Promise.resolve(),
  },
  key: PERSIST_KEY,
});

// TanStack's persistence contract: a query garbage-collected out of the in-memory cache is also
// dropped from the next persisted write, so gcTime shorter than the persister's maxAge quietly
// truncates the cache it was told to keep for 24h. At the 5-minute default, anything restored or
// prefetched but not looked at within five minutes -- Budgets and Reports, reached through the More
// stack and so unmounted most of the time -- was evicted mid-session and erased from disk, and the
// next cold start showed the skeleton this whole mechanism exists to remove.
//
// Scoped to the allowlist rather than set as a global default: those are the only queries the 24h
// promise is made about, and they are small JSON payloads. Applying it globally would also pin
// every non-persisted screen's data in memory for a day, which is a real cost on a phone and buys
// nothing -- none of it is on disk to warm-start from anyway.
PERSISTED_QUERY_KEY_PREFIXES.forEach((prefix) => {
  queryClient.setQueryDefaults([prefix], { gcTime: PERSIST_MAX_AGE });
});

const persister = {
  ...basePersister,
  persistClient: (client: Parameters<typeof basePersister.persistClient>[0]) => {
    // Stamped at call time, which is when the snapshot was taken -- not when the throttle
    // eventually flushes it. The throttle keeps only the newest args, and this is updated on the
    // same call, so the stamp and the payload it guards can never drift apart.
    pendingWriteEpoch = cacheEpoch;
    return basePersister.persistClient(client);
  },
};

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
  // Invalidates any snapshot already captured and queued inside the persister's throttle. This is
  // the half that stopPersisting() below cannot do -- see the cacheEpoch comment above for why
  // unsubscribing leaves a scheduled write alive and holding the departing session's data.
  cacheEpoch += 1;
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
