import type { ReactNode } from 'react';
import { renderHook, waitFor } from '@testing-library/react-native';

// Captured once via plain require (not a static `import`, to get the literal object identity
// jest.doMock below hands back out, with no Babel ESM-interop rewrapping in between) -- pinned back
// onto every test's freshly-reset module graph. See the beforeEach comment for why.
const ReactModule = require('react');
const ReactQueryModule = require('@tanstack/react-query');
const { QueryClient, QueryClientProvider, dehydrate, useQuery } = ReactQueryModule;

const PERSIST_KEY = 'finora_query_cache';
// Mirrors queryClient.ts's own constant -- not exported from there, so asserted against a local
// copy rather than loosening that module's surface for a test.
const PERSIST_MAX_AGE_MS = 24 * 60 * 60 * 1000;

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

// Required fresh per test rather than imported statically.
//
// queryClient.ts's persister wraps createAsyncStoragePersister's asyncThrottle in a closure built
// once, at module load -- its nextExecutionTime cooldown is shared by every call the module ever
// sees, module-load to module-load, not test to test. With a single shared import across this whole
// file, one test's write left that throttle "cooling down" for up to 1000ms, which the next test's
// own first write (several tests below assume this fires immediately) could land inside of -- and
// waitFor's own default timeout is also exactly 1000ms, the same interval, so the two raced. Real
// instrumentation (timestamped persistClient calls) confirmed it: under a full, loaded suite run,
// flushes landed on a rigid ~1000ms cadence set by the PREVIOUS test's completion, not by when the
// current test's own call fired -- deterministic in mechanism, flaky in outcome depending on how
// close to that boundary a given run's scheduling happened to land.
let AsyncStorage: typeof import('@react-native-async-storage/async-storage').default;
let PERSISTED_QUERY_KEY_PREFIXES: typeof import('./queryPersistence').PERSISTED_QUERY_KEY_PREFIXES;
let clearPersistedQueryCache: typeof import('./queryClient').clearPersistedQueryCache;
let pauseQueryPersistence: typeof import('./queryClient').pauseQueryPersistence;
let queryClient: typeof import('./queryClient').queryClient;
let startQueryPersistence: typeof import('./queryClient').startQueryPersistence;

beforeEach(() => {
  jest.resetModules();
  // 'react' and '@tanstack/react-query' are pinned back to the copies already captured above
  // instead of being swept up in the reset too. @tanstack/react-query-persist-client's index
  // eagerly re-exports PersistQueryClientProvider (a React component) even though queryClient.ts
  // only ever uses its two non-React functions -- so a bare resetModules() drags a SECOND react
  // and react-query instance in behind it. Confirmed the hard way: without this pin,
  // QueryClientProvider's useEffect threw "Cannot read properties of null", because renderHook's
  // react-test-renderer (bound to the ORIGINAL react) and the freshly-reloaded QueryClientProvider
  // (bound to a new one) don't share React's hook dispatcher. jest.doMock forces every transitive
  // require of these two, however deep, back onto the one already-loaded instance, while
  // queryClient.ts itself and its own private dependencies -- AsyncStorage, the persister/throttle,
  // queryPersistence -- still reload fresh.
  jest.doMock('react', () => ReactModule);
  jest.doMock('@tanstack/react-query', () => ReactQueryModule);

  AsyncStorage = require('@react-native-async-storage/async-storage').default;
  ({ PERSISTED_QUERY_KEY_PREFIXES } = require('./queryPersistence'));
  ({ clearPersistedQueryCache, pauseQueryPersistence, queryClient, startQueryPersistence } =
    require('./queryClient'));
});

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
  /**
   * The leak this closes, and why the sibling test below could not catch it.
   *
   * createAsyncStoragePersister throttles persistClient at 1000ms, and asyncThrottle has no cancel
   * path: once a call is queued it WILL run when the interval elapses. persistQueryClientSave also
   * dehydrates eagerly, so the queued call is already holding a finished snapshot of the departing
   * user's cache -- clearing the live cache afterwards cannot empty it, and stopPersisting() only
   * detaches the cache subscription sitting ABOVE the throttle.
   *
   * So the write outlived the wipe by ~1s and re-created the blob with a fresh timestamp. Since
   * AuthContext.login never clears the cache, the next cold start hydrated it and painted the next
   * person's first frame with the previous account's balances.
   *
   * The sibling test cannot see this: it deliberately sleeps out the 1000ms interval BEFORE
   * logging out ("wait out its 1000ms interval so only a write genuinely triggered by the clear
   * below would show up"), which is exactly the state in which no write is pending. This one does
   * the opposite -- it makes sure a write IS in flight at the moment of logout.
   */
  it('refuses a write already queued inside the persister throttle when the logout happened', async () => {
    await AsyncStorage.removeItem(PERSIST_KEY);
    startQueryPersistence();
    await new Promise((resolve) => setTimeout(resolve, 50));

    // asyncThrottle runs the FIRST call straight away and only then opens its 1000ms window, so a
    // single write is never pending long enough to race anything. This first one exists to open
    // that window.
    queryClient.setQueryData(['dashboard-summary'], { currentBalance: 111 });
    await waitFor(async () => expect(await AsyncStorage.getItem(PERSIST_KEY)).not.toBeNull());

    // ...and this one lands inside it, so it is queued rather than executed. This is the write that
    // used to outlive the wipe, carrying this balance with it.
    queryClient.setQueryData(['dashboard-summary'], { currentBalance: 555000 });

    pauseQueryPersistence();
    queryClient.clear();
    await clearPersistedQueryCache();
    expect(await AsyncStorage.getItem(PERSIST_KEY)).toBeNull();

    // The queued write fires somewhere in here.
    await new Promise((resolve) => setTimeout(resolve, 1200));

    expect(await AsyncStorage.getItem(PERSIST_KEY)).toBeNull();
  });

  it('lets the next session persist normally once it has signed in', async () => {
    // The guard must invalidate the departing session's queued write WITHOUT wedging persistence
    // for whoever signs in next -- otherwise the fix would silently disable warm starts from the
    // first logout onwards, which no existing test would notice.
    await AsyncStorage.removeItem(PERSIST_KEY);
    startQueryPersistence();
    await new Promise((resolve) => setTimeout(resolve, 50));

    pauseQueryPersistence();
    queryClient.clear();
    await clearPersistedQueryCache();

    // setQueryData on a key with no existing entry fires TWO cache events synchronously, an
    // "added" (the Query object created, no data yet) immediately followed by an "updated" (the
    // data just set) -- the persister subscription reacts to both. The first's snapshot dehydrates
    // to no queries (nothing has data yet) but is still a real persistClient call, and being the
    // very first call on this test's fresh throttle it starts executing right away.
    // createAsyncStoragePersister's own asyncThrottle then busy-waits a FULL 1000ms interval
    // before it even rechecks whether that first call has finished when the second one (this
    // write, carrying 42) arrives while it's still in flight -- regardless of how soon the first
    // call actually completes (single-digit ms here). So this specific write is reliably delayed
    // close to a full throttle interval, not the ~immediate flush every other write in this file
    // gets; waitFor's own default timeout is exactly that same 1000ms, so the two collide. Traced
    // with real timestamps (not assumed): call at +59ms, flush of the EMPTY snapshot at +64ms, but
    // the 42 snapshot (called at +62ms) doesn't flush until +1065ms.
    queryClient.setQueryData(['dashboard-summary'], { currentBalance: 42 });

    await waitFor(
      async () => {
        const raw = await AsyncStorage.getItem(PERSIST_KEY);
        expect(raw).not.toBeNull();
        expect(raw).toContain('42');
      },
      { timeout: 3000 }
    );
  });

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

/**
 * TanStack's persistence contract: a query garbage-collected out of memory is also dropped from the
 * next persisted write. With gcTime left at the 5-minute default against a 24h PERSIST_MAX_AGE, the
 * disk cache eroded during the session -- anything restored or prefetched and not looked at within
 * five minutes (Budgets and Reports live in the More stack, so they are unmounted most of the time)
 * was evicted and erased, and the next cold start showed the skeleton persistence exists to remove.
 */
describe('gcTime vs the persisted maxAge', () => {
  it('keeps every persisted key in memory at least as long as it is kept on disk', () => {
    for (const prefix of PERSISTED_QUERY_KEY_PREFIXES) {
      const defaults = queryClient.getQueryDefaults([prefix]);
      expect(defaults?.gcTime).toBeGreaterThanOrEqual(PERSIST_MAX_AGE_MS);
    }
  });

  it('leaves non-persisted keys on the shorter default', () => {
    // The scoping is the point: pinning every screen's data in memory for a day costs real memory
    // on a phone and buys nothing, since none of it is on disk to warm-start from.
    expect(queryClient.getQueryDefaults(['insights'])?.gcTime).toBeUndefined();
    expect(queryClient.getQueryDefaults(['statement-imports'])?.gcTime).toBeUndefined();
  });
});
