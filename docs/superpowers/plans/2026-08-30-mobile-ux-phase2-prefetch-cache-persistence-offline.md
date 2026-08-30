# Mobile UX Excellence — Phase 2 (P1): Prefetching, React Query Cache Persistence, Offline Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Warm the caches for Ledger/Budgets/Reports whenever Dashboard gains focus, persist the React Query cache to AsyncStorage so the app reopens instantly (fintech-scoped allowlist, cleared on logout), and add transient "back online" feedback to the existing offline banner.

**Architecture:** `usePrefetchAdjacentScreens` hooks into `useFocusEffect` on Dashboard and issues `prefetchQuery`/`prefetchInfiniteQuery` calls under the exact same query keys/functions the target screens' own hooks use (exported from `LedgerScreen.tsx` for this purpose). A locked allowlist predicate (`shouldPersistQuery`) gates what `persistQueryClient` is allowed to write to AsyncStorage — dashboard/accounts/transactions/budgets/reports/categories only, everything else (import workflow, auth/session, error/draft state) excluded by simply not being on the list. The persisted cache is cleared from the same `clearLocalState` convergence point `AuthContext` already uses for the in-memory cache and persisted nav state. `OfflineBoundary` gains a transient state built on the existing `useTransientFlag` hook, reusing the one persistent banner rather than adding a second component.

**Tech Stack:** React Native (Expo SDK 57), `@tanstack/react-query` v5.101.4, `@tanstack/query-async-storage-persister` + `@tanstack/react-query-persist-client` (new, version-matched to react-query), `@react-native-async-storage/async-storage` (already installed, `2.2.0`), `@react-navigation/native` v7 (`useFocusEffect`), Jest 29 + `@testing-library/react-native` 13.3.3.

**Spec:** User-provided "Mobile UX Excellence Initiative" brief (Phase 2 / P1 items: React Query Prefetching, Persist React Query Cache, Offline Awareness), scoped against a live codebase survey and locked decisions from conversation: cache persistence storage = plain AsyncStorage with guardrails (allowlist by domain, exclude import/auth/error/draft state, 24h maxAge, cleared on logout).

## Global Constraints

- One P1 item is **already merged to `main`** (PR #623 / `ebf68f57`) and is NOT part of this plan: navigation state persistence (`useNavigationStatePersistence.ts`).
- No transaction detail screen exists anywhere in the app — the brief's "prefetch adjacent transaction details" is explicitly out of scope until such a screen exists (not invented here).
- `@react-native-async-storage/async-storage` is already a direct dependency — no new storage dependency, only the two persistence packages.
- **Fintech cache-persistence guardrails, locked by the user, non-negotiable in this plan:** persist only dashboard/accounts/transactions/budgets/reports/categories query keys; never persist import workflow state, pending uploads, the auth/session domain (tokens stay in SecureStore via `safeStorage.ts`, never duplicated), error states, or drafts; 24h `maxAge`; persisted cache cleared on logout/session-expiry at the same `clearLocalState` convergence point as the in-memory cache and nav state.
- Every prefetch must reuse the target screen's own exact query key/function — a prefetch under a near-identical-but-different key is a wasted network call, not a warm cache.
- On cold start, restored persisted data must be treated as stale immediately and revalidated in the background — never trusted as fresh purely because it happens to fall inside the shared 30s `staleTime` window. The flow is: persist cache → restore on launch → mark stale immediately → background refresh → update UI when fresh data arrives.
- Test tooling: Jest 29, `@testing-library/react-native` 13.3.3, `npm test -- <path>` from `mobile/`.

---

## Already done (PR #623, merged 2026-08-30, commit `ebf68f57`) — reference only

`src/navigation/useNavigationStatePersistence.ts` persists `AppTabs` navigation state to AsyncStorage under `finora_nav_state`, scoped to the signed-in tree only (`isAppTabsActive` gate in `src/navigation/RootNavigator.tsx`), stripping `params` from every route before writing (some routes carry tokens/passwords), and is cleared from `AuthContext.clearLocalState` alongside `queryClient.clear()`. Task 9 below follows this exact convergence-point pattern for the persisted React Query cache: same `clearLocalState` call site, same "strip/allowlist before it touches disk" posture, same fire-and-forget `void` call.

Two upstream findings that shape the tasks below:
- No transaction detail screen exists anywhere in the app (`src/navigation/types.ts`'s `AppTabParamList`/`MoreStackParamList` have no such route, `AppTabs.tsx` has no such screen). Per the brief, **adjacent-transaction prefetch is not applicable** — no screen exists to prefetch data for. Not built, not invented.
- `@react-native-async-storage/async-storage` (`2.2.0`) is **already a direct dependency** (`package.json`). This plan needs no new storage dependency — only the two persistence packages below.

---

### Task 1: Export `useOnline` for reuse outside the banner

**Files:**
- Modify: `src/components/OfflineBanner.tsx:8`
- Test: `src/components/OfflineBanner.test.tsx`

**Interfaces:**
- Consumes: `onlineManager` from `@tanstack/react-query` (already imported in this file).
- Produces: `useOnline(): boolean` — now exported, so `src/lib/prefetchAdjacentScreens.ts` (Task 4) can read the same connectivity signal `OfflineBoundary` renders from, instead of standing up a second NetInfo/onlineManager subscription that could drift from what's on screen.

- [x] **Step 1: Write the failing test**
```ts
// Added to src/components/OfflineBanner.test.tsx, above the existing `describe('OfflineBoundary', ...)`
import { useOnline } from './OfflineBanner';
import { renderHook, act as rhAct } from '@testing-library/react-native';

describe('useOnline', () => {
  afterEach(() => onlineManager.setOnline(true));

  it('is exported and tracks onlineManager', () => {
    onlineManager.setOnline(true);
    const { result } = renderHook(() => useOnline());
    expect(result.current).toBe(true);

    rhAct(() => onlineManager.setOnline(false));
    expect(result.current).toBe(false);
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/components/OfflineBanner.test.tsx` — Expected: FAIL with `TypeError: (0 , _OfflineBanner.useOnline) is not a function` (or a module has no exported member `useOnline`), since the function exists but isn't exported yet.
- [x] **Step 3: Write minimal implementation**
```ts
// src/components/OfflineBanner.tsx:8 — add `export`, nothing else changes
/** Subscribes to React Query's own notion of connectivity, fed by NetInfo in api/queryClient.ts.
 *  Exported so anything that needs the identical online/offline signal the banner itself renders
 *  from (usePrefetchAdjacentScreens, Task 4 below) reads from this one source rather than a second
 *  NetInfo subscription that could disagree with what's on screen. */
export function useOnline(): boolean {
  const [online, setOnline] = useState(() => onlineManager.isOnline());
  useEffect(() => onlineManager.subscribe(setOnline), []);
  return online;
}
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS, and the whole file's existing suite (`OfflineBoundary`, mount test) still passes unchanged.
- [x] **Step 5: Commit** — `git add src/components/OfflineBanner.tsx src/components/OfflineBanner.test.tsx` / `git commit -m "mobile: export useOnline for reuse outside OfflineBanner"`

---

### Task 2: Make `useFocusEffect` testable without a real navigation tree

**Files:**
- Modify: `src/test/setup.ts:66-69` (insert after the existing NetInfo mock)
- Test: none new — this task's own correctness is proven by Task 4/5's tests, which fail without it.

**Interfaces:**
- Consumes: nothing new.
- Produces: a global test double for `@react-navigation/native`'s `useFocusEffect`, available to every test file without per-file setup.

React Navigation's `useFocusEffect` reads a `NavigationContext` that only exists inside a real `Navigator`/`Screen` tree. `DashboardScreen.test.tsx` (and most screen tests in this repo) render their screen bare — no `NavigationContainer`, no `Navigator` — exactly like `OfflineBanner.test.tsx` mocks `RootNavigator` away rather than mounting the real navigation tree. Wiring `useFocusEffect` straight into `usePrefetchAdjacentScreens` (Task 4) would make `DashboardScreen.test.tsx` throw immediately on render. This task fakes it globally as an ordinary mount effect, the same way `expo-secure-store`/`@react-native-async-storage/async-storage`/NetInfo are faked globally in this same file, so screen tests don't each need to grow a full navigation tree they have no other use for.

- [x] **Step 1: Write the failing test**
```ts
// src/lib/prefetchAdjacentScreens.focusMock.test.ts (throwaway probe, deleted once Task 4 lands —
// or fold straight into Task 4's own test file; shown standalone here only to isolate this step)
import { useFocusEffect } from '@react-navigation/native';
import { renderHook } from '@testing-library/react-native';

describe('useFocusEffect test double (src/test/setup.ts)', () => {
  it('runs its effect on mount without a NavigationContainer', () => {
    const effect = jest.fn();
    renderHook(() => useFocusEffect(effect));
    expect(effect).toHaveBeenCalledTimes(1);
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/lib/prefetchAdjacentScreens.focusMock.test.ts` — Expected: FAIL — real `useFocusEffect` throws (`Couldn't find a navigation object. Is your component inside NavigationContainer?`) because nothing mocks it yet.
- [x] **Step 3: Write minimal implementation**
```ts
// src/test/setup.ts — inserted immediately after the '@react-native-community/netinfo' mock (line 69)

// react-navigation's useFocusEffect needs a real Navigator/Screen context, which most screen unit
// tests here render without (see DashboardScreen.test.tsx, which mounts DashboardScreen bare, the
// same way OfflineBanner.test.tsx mocks RootNavigator away rather than building a real navigation
// tree). Faked as a plain mount effect so Dashboard's prefetch-on-focus wiring (Task 4/5) can be
// exercised without every affected screen test growing a navigation tree it otherwise has no use
// for. Everything else the module exports stays real.
jest.mock('@react-navigation/native', () => {
  const actual = jest.requireActual('@react-navigation/native');
  const { useEffect } = require('react');
  return {
    ...actual,
    useFocusEffect: (effect: () => void | (() => void)) => useEffect(effect, []),
  };
});
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS. Then delete the throwaway probe file (`rm src/lib/prefetchAdjacentScreens.focusMock.test.ts`) — its only purpose was to pin this step; Task 4/5's real tests exercise the same mock going forward.
- [x] **Step 5: Commit** — `git add src/test/setup.ts` / `git commit -m "mobile(test): fake useFocusEffect globally so screen tests don't need a real nav tree"`

---

### Task 3: Export Ledger's default query key/fn so prefetch can reuse it exactly

**Files:**
- Modify: `src/screens/LedgerScreen.tsx:16` (PAGE_SIZE), `:30-39` (filters), `:56-57` (getNextPageParam)
- Test: `src/screens/LedgerScreen.test.tsx`

**Interfaces:**
- Consumes: `TransactionFilters`, `PagedResponse` from `../api/endpoints` (the latter newly imported into this file).
- Produces: `LEDGER_PAGE_SIZE: number`, `DEFAULT_LEDGER_FILTERS: TransactionFilters`, `getLedgerNextPageParam(lastPage: PagedResponse<Transaction>): number | undefined` — all exported for Task 4 to build a byte-identical `['transactions', DEFAULT_LEDGER_FILTERS]` cache key.

React Query's cache lookup is a value match on the whole key (via a stable stringify), not a prefix match — a prefetch built from a filters object that's merely *similar* to what `LedgerScreen` constructs is a cache miss with extra network calls, not a warm cache. This task changes zero runtime behavior: `LedgerScreen`'s own filters on a fresh mount (empty search, `typeFilter: 'ALL'`) already equal `{ size: 20, sortField: 'date', sortDir: 'desc', keyword: undefined, type: undefined }`, which is what `DEFAULT_LEDGER_FILTERS` spread with `keyword`/`type` re-added still produces.

- [x] **Step 1: Write the failing test**
```ts
// Appended to src/screens/LedgerScreen.test.tsx
import { DEFAULT_LEDGER_FILTERS, LEDGER_PAGE_SIZE, getLedgerNextPageParam } from './LedgerScreen';

describe('DEFAULT_LEDGER_FILTERS export (for Dashboard prefetch, Task 4)', () => {
  it('matches exactly what a fresh mount searches with', async () => {
    transactions.search.mockResolvedValue(page([]) as never);

    renderScreen();

    await screen.findByText(/No transactions yet/i);
    expect(transactions.search).toHaveBeenCalledWith({ ...DEFAULT_LEDGER_FILTERS, page: 0 });
  });

  it('exposes the page size and pagination cursor logic LedgerScreen itself uses', () => {
    expect(LEDGER_PAGE_SIZE).toBe(20);
    expect(DEFAULT_LEDGER_FILTERS).toEqual({ size: 20, sortField: 'date', sortDir: 'desc' });
    expect(getLedgerNextPageParam({ content: [], page: 0, size: 20, totalElements: 40, totalPages: 2 })).toBe(1);
    expect(getLedgerNextPageParam({ content: [], page: 1, size: 20, totalElements: 40, totalPages: 2 })).toBeUndefined();
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/screens/LedgerScreen.test.tsx` — Expected: FAIL with `TypeError: Cannot read properties of undefined` / `does not provide an export named 'DEFAULT_LEDGER_FILTERS'`, since nothing is exported yet.
- [x] **Step 3: Write minimal implementation**
```ts
// src/screens/LedgerScreen.tsx — imports (top of file)
import { transactionsApi, type PagedResponse, type TransactionFilters } from '../api/endpoints';

// src/screens/LedgerScreen.tsx:16 — replace `const PAGE_SIZE = 20;` with:
export const LEDGER_PAGE_SIZE = 20;

/**
 * The exact filters this screen's own useInfiniteQuery below sends on a fresh mount (no search
 * keyword typed, no type filter chosen). Exported so Dashboard's prefetch-on-focus hook
 * (usePrefetchAdjacentScreens, Task 4) can warm ['transactions', DEFAULT_LEDGER_FILTERS] under
 * this EXACT key -- a prefetch built from a near-identical object is a cache miss with extra
 * network calls, not a warm cache.
 */
export const DEFAULT_LEDGER_FILTERS: TransactionFilters = {
  size: LEDGER_PAGE_SIZE,
  sortField: 'date',
  sortDir: 'desc',
};

/** Exported for the same reason as DEFAULT_LEDGER_FILTERS -- prefetchInfiniteQuery (Task 4) needs
 *  the identical pagination cursor logic this screen's own useInfiniteQuery uses below, so a
 *  prefetched page and a screen-fetched page agree on whether there's a next one. */
export function getLedgerNextPageParam(lastPage: PagedResponse<Transaction>) {
  return lastPage.page + 1 < lastPage.totalPages ? lastPage.page + 1 : undefined;
}

// src/screens/LedgerScreen.tsx:30-39 — replace the `filters` useMemo body:
const filters: TransactionFilters = useMemo(
  () => ({
    ...DEFAULT_LEDGER_FILTERS,
    keyword: debouncedKeyword || undefined,
    type: typeFilter === 'ALL' ? undefined : typeFilter,
  }),
  [debouncedKeyword, typeFilter]
);

// src/screens/LedgerScreen.tsx:56-57 — replace the inline getNextPageParam:
getNextPageParam: getLedgerNextPageParam,
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS, and every pre-existing test in this file still passes unchanged (filters object is value-identical to before).
- [x] **Step 5: Commit** — `git add src/screens/LedgerScreen.tsx src/screens/LedgerScreen.test.tsx` / `git commit -m "mobile: export LedgerScreen's default query key so Dashboard can prefetch it exactly"`

---

### Task 4: `usePrefetchAdjacentScreens` — warm Ledger/Budgets/Reports on Dashboard focus

**Files:**
- Create: `src/lib/prefetchAdjacentScreens.ts`
- Test: `src/lib/prefetchAdjacentScreens.test.ts`

**Interfaces:**
- Consumes: `useOnline` (Task 1, `../components/OfflineBanner`), `DEFAULT_LEDGER_FILTERS`/`getLedgerNextPageParam` (Task 3, `../screens/LedgerScreen`), `useFocusEffect` (Task 2's test double in tests / real react-navigation in the app), `transactionsApi.search`, `budgetsApi.list`, `reportsApi.availableMonths`, `reportsApi.forMonth` (all from `../api/endpoints`, signatures read from `src/api/endpoints.ts:131-134,403-407,477-480`).
- Produces: `usePrefetchAdjacentScreens(): void` — called from `DashboardScreen` in Task 5.

- [x] **Step 1: Write the failing test**
```ts
// src/lib/prefetchAdjacentScreens.test.ts
import type { ReactNode } from 'react';
import { renderHook, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { usePrefetchAdjacentScreens } from './prefetchAdjacentScreens';
import { budgetsApi, reportsApi, transactionsApi } from '../api/endpoints';
import { DEFAULT_LEDGER_FILTERS } from '../screens/LedgerScreen';

jest.mock('../api/endpoints', () => ({
  transactionsApi: { search: jest.fn() },
  budgetsApi: { list: jest.fn() },
  reportsApi: { availableMonths: jest.fn(), forMonth: jest.fn() },
}));

const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;
const budgets = budgetsApi as jest.Mocked<typeof budgetsApi>;
const reports = reportsApi as jest.Mocked<typeof reportsApi>;

function page(over: Record<string, unknown> = {}) {
  return { content: [], page: 0, size: DEFAULT_LEDGER_FILTERS.size, totalElements: 0, totalPages: 1, ...over };
}

function renderPrefetch(queryClient: QueryClient) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => usePrefetchAdjacentScreens(), { wrapper });
}

beforeEach(() => jest.clearAllMocks());
afterEach(() => onlineManager.setOnline(true));

describe('usePrefetchAdjacentScreens', () => {
  it('warms Ledger, Budgets, and the latest Reports month under their real query keys', async () => {
    transactions.search.mockResolvedValue(page());
    budgets.list.mockResolvedValue([{ id: 'b-1' } as never]);
    reports.availableMonths.mockResolvedValue(['2026-06', '2026-07', '2026-08']);
    reports.forMonth.mockResolvedValue({ month: '2026-08', income: 0, expense: 0, categories: [] });

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderPrefetch(queryClient);

    await waitFor(() => {
      expect(queryClient.getQueryData(['transactions', DEFAULT_LEDGER_FILTERS])).toBeDefined();
      expect(queryClient.getQueryData(['budgets'])).toEqual([{ id: 'b-1' }]);
      expect(queryClient.getQueryData(['report', '2026-08'])).toBeDefined();
    });

    expect(transactions.search).toHaveBeenCalledWith({ ...DEFAULT_LEDGER_FILTERS, page: 0 });
    expect(reports.forMonth).toHaveBeenCalledWith('2026-08');
  });

  it('does not prefetch anything while offline', async () => {
    onlineManager.setOnline(false);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderPrefetch(queryClient);

    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(transactions.search).not.toHaveBeenCalled();
    expect(budgets.list).not.toHaveBeenCalled();
    expect(reports.availableMonths).not.toHaveBeenCalled();
  });

  it('skips the network for report-months when a fresh cache entry already exists', async () => {
    transactions.search.mockResolvedValue(page());
    budgets.list.mockResolvedValue([]);
    reports.forMonth.mockResolvedValue({ month: '2026-08', income: 0, expense: 0, categories: [] });

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 30_000 } } });
    // Stands in for Dashboard's OWN ['report-months'] query having already populated the cache a
    // moment earlier -- fetchQuery must not issue a second network call for data that's still fresh.
    queryClient.setQueryData(['report-months'], ['2026-08']);

    renderPrefetch(queryClient);

    await waitFor(() => expect(queryClient.getQueryData(['report', '2026-08'])).toBeDefined());
    expect(reports.availableMonths).not.toHaveBeenCalled();
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/lib/prefetchAdjacentScreens.test.ts` — Expected: FAIL — `Cannot find module './prefetchAdjacentScreens'`.
- [x] **Step 3: Write minimal implementation**
```ts
// src/lib/prefetchAdjacentScreens.ts
import { useCallback } from 'react';
import { useFocusEffect } from '@react-navigation/native';
import { useQueryClient } from '@tanstack/react-query';
import { budgetsApi, reportsApi, transactionsApi } from '../api/endpoints';
import { DEFAULT_LEDGER_FILTERS, getLedgerNextPageParam } from '../screens/LedgerScreen';
import { useOnline } from '../components/OfflineBanner';

/**
 * Warms the caches for the three screens a tap away from Dashboard -- Ledger, Budgets, and
 * Reports' latest month -- every time Dashboard gains focus, so navigating to any of them shows
 * real data on the first frame instead of that screen's own loading spinner.
 *
 * Every key/query function below is the SAME one the target screen's own useQuery/useInfiniteQuery
 * uses (see LedgerScreen's own comment on DEFAULT_LEDGER_FILTERS/getLedgerNextPageParam) -- a
 * prefetch under a key nothing else reads is a wasted network call, not a warm cache.
 *
 * prefetchQuery/prefetchInfiniteQuery/fetchQuery already no-op and skip the network entirely when
 * a fresh (within the shared 30s staleTime -- see queryClient.ts) cache entry exists, so flipping
 * back to Home a few seconds after leaving it does not re-issue any of these requests.
 *
 * Gated on useOnline() (the same onlineManager-backed source OfflineBanner reads) rather than
 * letting the query layer's own offline-pause absorb it: an issued query still counts as an
 * attempt and burns the configured retry before settling as an error (see queryClient.ts's own
 * comment on why retry:1 matters here), which is wasted work for a prefetch nobody is waiting on
 * and nobody will see fail.
 */
export function usePrefetchAdjacentScreens() {
  const queryClient = useQueryClient();
  const online = useOnline();

  useFocusEffect(
    useCallback(() => {
      if (!online) return;

      void queryClient.prefetchInfiniteQuery({
        queryKey: ['transactions', DEFAULT_LEDGER_FILTERS],
        queryFn: ({ pageParam }) =>
          transactionsApi.search({ ...DEFAULT_LEDGER_FILTERS, page: pageParam as number }),
        initialPageParam: 0,
        getNextPageParam: getLedgerNextPageParam,
      });

      void queryClient.prefetchQuery({ queryKey: ['budgets'], queryFn: () => budgetsApi.list() });

      // Sequential, not a third parallel prefetchQuery: which month is "current" isn't known until
      // report-months resolves. Reuses fetchQuery (not prefetchQuery) here specifically because its
      // return value is needed to pick the latest month -- both still respect staleTime and skip
      // the network when Dashboard's own ['report-months'] query is already warm.
      void (async () => {
        const months = await queryClient.fetchQuery({
          queryKey: ['report-months'],
          queryFn: () => reportsApi.availableMonths(),
        });
        const latest = months[months.length - 1];
        if (!latest) return;
        void queryClient.prefetchQuery({
          queryKey: ['report', latest],
          queryFn: () => reportsApi.forMonth(latest),
          staleTime: 5 * 60_000, // matches Dashboard's/ReportsScreen's own staleTime for a past month
        });
      })();
    }, [queryClient, online])
  );
}
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS (all three cases).
- [x] **Step 5: Commit** — `git add src/lib/prefetchAdjacentScreens.ts src/lib/prefetchAdjacentScreens.test.ts` / `git commit -m "mobile: add usePrefetchAdjacentScreens for Ledger/Budgets/Reports"`

---

### Task 5: Wire prefetching into Dashboard; note on transaction-detail prefetch

**Files:**
- Modify: `src/screens/DashboardScreen.tsx:1-38`
- Modify: `src/screens/DashboardScreen.test.tsx` (endpoints mock factory)

**Interfaces:**
- Consumes: `usePrefetchAdjacentScreens` (Task 4, `../lib/prefetchAdjacentScreens`).
- Produces: nothing new — this task only calls the hook from the screen.

Per the brief: adjacent-transaction prefetch ("on transaction detail view, prefetch neighboring transactions") is **not applicable**. `src/navigation/types.ts`'s `AppTabParamList`/`MoreStackParamList` and `src/navigation/AppTabs.tsx` were read in full — there is no transaction detail screen anywhere in this app (`LedgerScreen` renders rows inline with long-press-to-delete, no push-to-detail navigation exists). No screen was invented to satisfy this; it is simply out of scope until such a screen exists.

- [x] **Step 1: Write the failing test**
```ts
// Appended to src/screens/DashboardScreen.test.tsx, after the existing mocks near the top:
// (the endpoints factory below REPLACES the existing one at the top of the file, adding budgetsApi)
jest.mock('../api/endpoints', () => ({
  dashboardApi: { summary: jest.fn() },
  accountsApi: { list: jest.fn() },
  transactionsApi: { search: jest.fn() },
  goalsApi: { list: jest.fn() },
  insightsApi: { get: jest.fn() },
  userApi: { get: jest.fn() },
  reportsApi: { availableMonths: jest.fn(), forMonth: jest.fn() },
  budgetsApi: { list: jest.fn() },
}));

// New describe block, appended at the end of the file:
describe('adjacent-screen prefetching (Task 4 wiring)', () => {
  it('prefetches the Ledger, Budgets and latest Reports caches once summary loads', async () => {
    dashboard.summary.mockResolvedValue(emptySummary());
    reports.availableMonths.mockResolvedValue(['2026-08']);
    reports.forMonth.mockResolvedValue({ month: '2026-08', income: 0, expense: 0, categories: [] });
    const budgetsMock = (require('../api/endpoints').budgetsApi.list as jest.Mock).mockResolvedValue([]);

    const { queryClient } = renderScreen();

    await waitFor(() => expect(queryClient.getQueryData(['budgets'])).toEqual([]));
    expect(budgetsMock).toHaveBeenCalled();
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/screens/DashboardScreen.test.tsx` — Expected: FAIL — `queryClient.getQueryData(['budgets'])` stays `undefined` because nothing calls `usePrefetchAdjacentScreens` yet.
- [x] **Step 3: Write minimal implementation**
```ts
// src/screens/DashboardScreen.tsx — add import near the other lib imports (after line 16):
import { usePrefetchAdjacentScreens } from '../lib/prefetchAdjacentScreens';

// src/screens/DashboardScreen.tsx — inside export function DashboardScreen(), immediately after
// the existing usePreventScreenCapture() call (line 38):
usePreventScreenCapture();
usePrefetchAdjacentScreens();
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS. Also re-run the file's full pre-existing suite (`npm test -- src/screens/DashboardScreen.test.tsx`) to confirm the `!summary` guard tests and every other existing case are unaffected by the new hook (they will be, since `usePrefetchAdjacentScreens` only reads `useQueryClient()`/`useOnline()` and never touches render output).
- [x] **Step 5: Commit** — `git add src/screens/DashboardScreen.tsx src/screens/DashboardScreen.test.tsx` / `git commit -m "mobile: prefetch Ledger/Budgets/Reports on Dashboard focus"`

---

### Task 6: `shouldPersistQuery` — the fintech allowlist predicate

**Files:**
- Create: `src/api/queryPersistence.ts`
- Test: `src/api/queryPersistence.test.ts`

**Interfaces:**
- Consumes: `defaultShouldDehydrateQuery`, `type Query` (both re-exported by `@tanstack/react-query` from `@tanstack/query-core` — confirmed via `@tanstack/react-query`'s `src/index.ts:4`, `export * from '@tanstack/query-core'`; no new dependency needed for this task).
- Produces: `PERSISTED_QUERY_KEY_PREFIXES: readonly string[]`, `shouldPersistQuery(query: Query): boolean` — consumed by Task 7's `dehydrateOptions.shouldDehydrateQuery`.

Locked allowlist, built from every real query key found via `grep -rn "queryKey:" src` (`src/screens/DashboardScreen.tsx:51-59,64,74`, `BudgetsScreen.tsx:39,44`, `LedgerScreen.tsx:51`, `ReportsScreen.tsx:40,51`, `InsightsScreen.tsx:20-21`, `InvestmentsScreen.tsx:66-67`, `GoalsScreen.tsx:43`, `StatementHistoryScreen.tsx:62,379`, `SettingsScreen.tsx:89-93`, `settings/DeviceSessionsSection.tsx:36`, `import/ImportScreen.tsx:81,85`). Only the brief's named domains (dashboard/accounts/transactions/budgets/reports/categories) are included; everything else — `goals`, `insights`, `networth`, `recurring`, `user-settings`, `workspace-settings`, `devices`, `import-statistics`, `statement-imports`, `statement-import-transactions` — is excluded by simply not appearing, which covers every category the brief calls out (import workflow state, pending uploads, auth/session domain, error/draft state) without needing a denylist that could miss a future one.

- [x] **Step 1: Write the failing test**
```ts
// src/api/queryPersistence.test.ts
import { QueryClient } from '@tanstack/react-query';
import { PERSISTED_QUERY_KEY_PREFIXES, shouldPersistQuery } from './queryPersistence';

function successfulQuery(queryKey: unknown[]) {
  const qc = new QueryClient();
  qc.setQueryData(queryKey, { ok: true });
  return qc.getQueryCache().find({ queryKey })!;
}

function pendingQuery(queryKey: unknown[]) {
  const qc = new QueryClient();
  void qc.prefetchQuery({ queryKey, queryFn: () => new Promise(() => {}) });
  return qc.getQueryCache().find({ queryKey })!;
}

describe('shouldPersistQuery', () => {
  it.each(PERSISTED_QUERY_KEY_PREFIXES)('allows a successful "%s" query', (prefix) => {
    expect(shouldPersistQuery(successfulQuery([prefix]))).toBe(true);
  });

  it('allows a transactions query with its filter object as part of the key', () => {
    expect(shouldPersistQuery(successfulQuery(['transactions', { page: 0, size: 20 }]))).toBe(true);
  });

  it.each([
    'statement-imports',
    'statement-import-transactions',
    'devices',
    'user-settings',
    'workspace-settings',
    'import-statistics',
    'goals',
    'insights',
    'networth',
    'recurring',
  ])('excludes "%s" -- not on the persisted allowlist', (key) => {
    expect(shouldPersistQuery(successfulQuery([key]))).toBe(false);
  });

  it('excludes an allowlisted key that has not resolved yet', () => {
    expect(shouldPersistQuery(pendingQuery(['dashboard-summary']))).toBe(false);
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/api/queryPersistence.test.ts` — Expected: FAIL — `Cannot find module './queryPersistence'`.
- [x] **Step 3: Write minimal implementation**
```ts
// src/api/queryPersistence.ts
import { defaultShouldDehydrateQuery, type Query } from '@tanstack/react-query';

/**
 * Locked-down allowlist for what's allowed to survive an app restart in plaintext AsyncStorage --
 * see startQueryPersistence's own doc comment in queryClient.ts. Built from every real queryKey in
 * this app (grep -rn "queryKey:" src) and limited to the brief's named domains: dashboard,
 * accounts, transactions, budgets, reports, categories.
 *
 * Deliberately excluded by simply not appearing here, rather than a denylist that would need
 * remembering: import workflow state ('statement-imports', 'statement-import-transactions'),
 * pending uploads, the auth/session domain ('devices' -- tokens themselves already live in
 * SecureStore via safeStorage.ts and are never duplicated here), and everything else this app
 * doesn't need instantly on cold start ('goals', 'insights', 'networth', 'recurring',
 * 'user-settings', 'workspace-settings', 'import-statistics'). An allowlist, not a denylist, on
 * purpose: the failure mode of forgetting to add a new sensitive key to a denylist is a leak; the
 * failure mode here is a new screen's data not warm-starting until someone adds it -- a slower
 * cold start, not a disclosure.
 */
export const PERSISTED_QUERY_KEY_PREFIXES = [
  'dashboard-summary',
  'accounts',
  'transactions',
  'recent-transactions',
  'budgets',
  'report',
  'report-months',
  'categories',
] as const;

/**
 * Passed as dehydrateOptions.shouldDehydrateQuery to persistQueryClient (queryClient.ts). Starts
 * from the library's own default -- only ever persist a query that actually SUCCEEDED, since an
 * in-flight or errored fetch has nothing worth restoring -- and narrows further to the allowlist
 * above.
 */
export function shouldPersistQuery(query: Query): boolean {
  if (!defaultShouldDehydrateQuery(query)) return false;
  const [prefix] = query.queryKey;
  return typeof prefix === 'string' && (PERSISTED_QUERY_KEY_PREFIXES as readonly string[]).includes(prefix);
}
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS.
- [x] **Step 5: Commit** — `git add src/api/queryPersistence.ts src/api/queryPersistence.test.ts` / `git commit -m "mobile: add shouldPersistQuery allowlist for React Query cache persistence"`

---

### Task 7: Wire `persistQueryClient` around the shared `queryClient`

**Files:**
- Modify: `package.json` (dependencies)
- Modify: `src/api/queryClient.ts:1-41`
- Test: `src/api/queryClient.test.ts`

**Interfaces:**
- Consumes: `shouldPersistQuery` (Task 6), `queryClient` (this file's own existing export).
- Produces: `startQueryPersistence(): () => void` (mirrors the existing `startNetworkMonitoring(): () => void` shape exactly), `clearPersistedQueryCache(): Promise<void>` — consumed by Task 8 (App.tsx) and Task 9 (AuthContext).

Package versions confirmed on the npm registry: `@tanstack/query-async-storage-persister` and `@tanstack/react-query-persist-client` both publish `5.101.4`, matching the installed `@tanstack/react-query@^5.101.4` exactly (same monorepo, synced versioning). `createAsyncStoragePersister`'s `storage` parameter (`@tanstack/query-async-storage-persister`'s `src/index.ts`) expects `{ getItem, setItem, removeItem }` returning promises — `@react-native-async-storage/async-storage`'s default export matches this shape directly, no adapter needed.

`maxAge: 24h` — long enough that reopening the app the next morning still shows real figures instantly instead of a skeleton; short enough that a persisted balance from days ago never survives to be shown as fact. Data older than this is discarded wholesale on restore (`persistQueryClientRestore`'s own `maxAge` behavior, confirmed by reading `@tanstack/query-persist-client-core`'s `src/persist.ts`) rather than shown stale-then-silently-corrected — a number that's briefly wrong and then jumps is worse, in a finance app, than the ordinary loading skeleton it replaces.

**Restored data is never trusted as fresh, even inside `staleTime`.** Relying purely on the shared 30s `staleTime` to trigger revalidation after a cold start works most of the time (an app is rarely relaunched within 30s of being killed) but isn't guaranteed — a quick force-quit-and-reopen would otherwise restore a query that's still technically "fresh" and skip the background refetch entirely, leaving a screen showing seconds-old-but-now-possibly-wrong data with no revalidation in flight. `persistQueryClient`'s `onSuccess` callback (fired once restore completes) is used to call `queryClient.invalidateQueries()` unconditionally, which marks every restored query stale and triggers an immediate background refetch for any that have an active observer (a mounted screen). This is the explicit flow: persist → restore on launch → mark stale immediately → background refresh → update UI when fresh data arrives — the "opens instantly, then quietly corrects itself" feel the whole initiative is chasing, made deterministic rather than incidental. `persistQueryClient`'s own default `onSuccess` resumes any paused (offline-queued) mutations; overriding `onSuccess` replaces that default, so `resumePausedMutations()` is called explicitly to preserve it.

- [x] **Step 1: Write the failing test**
```ts
// src/api/queryClient.test.ts
import type { ReactNode } from 'react';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { QueryClient, QueryClientProvider, dehydrate, useQuery } from '@tanstack/react-query';
import { renderHook } from '@testing-library/react-native';
import { clearPersistedQueryCache, queryClient, startQueryPersistence } from './queryClient';

const PERSIST_KEY = 'finora_query_cache';

function blob(clientState: unknown, over: Partial<{ timestamp: number; buster: string }> = {}) {
  return JSON.stringify({ timestamp: Date.now(), buster: '1', clientState, ...over });
}

afterEach(() => queryClient.clear());

describe('startQueryPersistence', () => {
  it('restores a previously persisted, allowed query into the shared queryClient', async () => {
    const seed = new QueryClient();
    seed.setQueryData(['dashboard-summary'], { currentBalance: 42 });
    await AsyncStorage.setItem(PERSIST_KEY, blob(dehydrate(seed)));

    startQueryPersistence();

    await waitFor(() =>
      expect(queryClient.getQueryData(['dashboard-summary'])).toEqual({ currentBalance: 42 })
    );
  });

  it('discards a persisted cache older than 24h instead of restoring it', async () => {
    const seed = new QueryClient();
    seed.setQueryData(['dashboard-summary'], { currentBalance: 999 });
    await AsyncStorage.setItem(
      PERSIST_KEY,
      blob(dehydrate(seed), { timestamp: Date.now() - 25 * 60 * 60 * 1000 })
    );

    startQueryPersistence();

    await waitFor(async () => expect(await AsyncStorage.getItem(PERSIST_KEY)).toBeNull());
    expect(queryClient.getQueryData(['dashboard-summary'])).toBeUndefined();
  });

  it('treats restored data as stale immediately and refetches it in the background, even though it is still within staleTime', async () => {
    const seed = new QueryClient();
    seed.setQueryData(['dashboard-summary'], { currentBalance: 42 });
    await AsyncStorage.setItem(PERSIST_KEY, blob(dehydrate(seed)));

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

    // Restored instantly...
    await waitFor(() =>
      expect(queryClient.getQueryData(['dashboard-summary'])).toEqual({ currentBalance: 42 })
    );
    // ...but never trusted as fresh -- a background refetch fires without anything else asking for
    // it, and the UI-visible cache value updates once it resolves.
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
```
Add `import { waitFor } from '@testing-library/react-native';` to the top of the file alongside the other imports (combine with the `renderHook` import shown above into one `@testing-library/react-native` import line).
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/api/queryClient.test.ts` — Expected: FAIL — `startQueryPersistence`/`clearPersistedQueryCache` are not exported yet (module has no such members). The new "treats restored data as stale immediately" test will additionally fail on its own even once those exist, since nothing yet calls `invalidateQueries()` after restore — `queryFn` is never called a second time.
- [x] **Step 3: Write minimal implementation**
```bash
npm install @tanstack/query-async-storage-persister@^5.101.4 @tanstack/react-query-persist-client@^5.101.4
```
```ts
// src/api/queryClient.ts — appended after the existing startNetworkMonitoring function
import AsyncStorage from '@react-native-async-storage/async-storage';
import { createAsyncStoragePersister } from '@tanstack/query-async-storage-persister';
import { persistQueryClient } from '@tanstack/react-query-persist-client';
import { shouldPersistQuery } from './queryPersistence';

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
  const [unsubscribe] = persistQueryClient({
    queryClient,
    persister,
    maxAge: PERSIST_MAX_AGE,
    buster: PERSIST_BUSTER,
    dehydrateOptions: { shouldDehydrateQuery: shouldPersistQuery },
    onSuccess: () => {
      // Restored data is shown instantly, but must never be treated as fresh purely because it
      // falls inside the shared 30s staleTime -- a quick force-quit-and-reopen would otherwise
      // skip revalidation entirely. invalidateQueries() marks every restored query stale AND
      // triggers an immediate background refetch for any with an active observer (a mounted
      // screen), giving the explicit flow: restore -> stale immediately -> background refresh ->
      // UI updates once fresh data arrives.
      void queryClient.invalidateQueries();
      // persistQueryClient's own default onSuccess resumes paused (offline-queued) mutations;
      // overriding onSuccess replaces that default, so it's called explicitly here to preserve it.
      void queryClient.resumePausedMutations();
    },
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
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS (all three cases).
- [x] **Step 5: Commit** — `git add package.json package-lock.json src/api/queryClient.ts src/api/queryClient.test.ts` / `git commit -m "mobile: persist React Query cache to AsyncStorage via persistQueryClient"`

---

### Task 8: Start persistence when the app boots

**Files:**
- Modify: `App.tsx:6,37`

**Interfaces:**
- Consumes: `startQueryPersistence` (Task 7, `./src/api/queryClient`).
- Produces: nothing new — wires the effect into the root component.

Mirrors the existing `startNetworkMonitoring` wiring exactly (`App.tsx:37`, `useEffect(() => startNetworkMonitoring(), [])`), including subscribing inside an effect rather than at module scope so it's torn down cleanly rather than leaking across fast-refresh reloads. No `App.test.tsx` exists in this repo, and `startNetworkMonitoring`'s own wiring has no dedicated test either (confirmed via `grep -rln "startNetworkMonitoring" src App.tsx` — only `App.tsx` and `queryClient.ts` reference it); the precedent here is that the function itself is unit-tested (Task 7) and the wiring is implicitly covered by the App-rendering tests that already exist (`src/components/OfflineBanner.test.tsx`'s `"the app actually mounts it"` test, `AppLockGate.test.tsx`, `RootWarningBanner.test.tsx` — all render the real `<App />`).

- [x] **Step 1: Write the failing test** — none new; run the existing App-mounting regression suite first to record its current green baseline: `npm test -- src/components/OfflineBanner.test.tsx src/components/AppLockGate.test.tsx src/components/RootWarningBanner.test.tsx` — Expected: PASS (baseline, before this change — confirms nothing is already broken that this task's change could be blamed for).
- [x] **Step 2: Run test to verify it fails** — N/A for this task (wiring-only, no new assertions); proceed to Step 3.
- [x] **Step 3: Write minimal implementation**
```ts
// App.tsx:6 — import
import { queryClient, startNetworkMonitoring, startQueryPersistence } from './src/api/queryClient';

// App.tsx:37 — immediately after the existing effect
useEffect(() => startNetworkMonitoring(), []);
// Item B: warms the query cache from AsyncStorage on cold start and keeps saving it as it
// changes -- see startQueryPersistence's own doc comment in api/queryClient.ts. Same posture as
// the network-monitoring effect just above: subscribed here, not at module scope, so it's torn
// down cleanly rather than leaking across fast-refresh reloads in development.
useEffect(() => startQueryPersistence(), []);
```
- [x] **Step 4: Run test to verify it passes** — `npm test -- src/components/OfflineBanner.test.tsx src/components/AppLockGate.test.tsx src/components/RootWarningBanner.test.tsx` — Expected: PASS, unchanged from the Step 1 baseline (the real `<App />` still mounts and behaves identically; `startQueryPersistence()` finds an empty AsyncStorage under test and silently no-ops the restore).
- [x] **Step 5: Commit** — `git add App.tsx` / `git commit -m "mobile: start React Query cache persistence on app boot"`

---

### Task 9: Clear the persisted cache on logout/session-expiry

**Files:**
- Modify: `src/context/AuthContext.tsx:6,126`
- Test: `src/context/persistedQueryCacheIsolation.test.tsx` (new)

**Interfaces:**
- Consumes: `clearPersistedQueryCache` (Task 7, `../api/queryClient`).
- Produces: nothing new — extends `clearLocalState`'s existing convergence-point behavior.

This is a real security requirement, not polish (per the brief): without it, a persisted AsyncStorage blob from account A survives `queryClient.clear()` (which only empties the in-memory cache) and would be the very first thing restored — and rendered — for account B on the next cold start of a shared device. The test below is the AsyncStorage counterpart of the existing `src/context/logoutCacheIsolation.test.tsx` (MOB-AUTH-02), which already proved `clearLocalState` empties the in-memory cache on logout. Only the logout path is exercised here — not a separate session-expiry variant — because both already reach the identical `clearLocalState` function body; `src/context/sessionExpiryCacheIsolation.test.tsx` (MOB-AUTH-03) already proves that convergence for the in-memory cache, and re-proving the same routing here would test routing again, not this feature.

- [x] **Step 1: Write the failing test**
```tsx
// src/context/persistedQueryCacheIsolation.test.tsx
import { Text } from 'react-native';
import { act, render, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider, dehydrate } from '@tanstack/react-query';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { AuthProvider, useAuth } from './AuthContext';

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

    await act(async () => {
      auth.logout();
    });

    expect(await AsyncStorage.getItem(PERSIST_KEY)).toBeNull();
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/context/persistedQueryCacheIsolation.test.tsx` — Expected: FAIL — `AsyncStorage.getItem(PERSIST_KEY)` still resolves to the seeded blob, since `clearLocalState` doesn't call `clearPersistedQueryCache` yet.
- [x] **Step 3: Write minimal implementation**
```ts
// src/context/AuthContext.tsx:6 — import, alongside the existing clearPersistedNavigationState import
import { clearPersistedNavigationState } from '../navigation/useNavigationStatePersistence';
import { clearPersistedQueryCache } from '../api/queryClient';

// src/context/AuthContext.tsx:126 — inside clearLocalState, immediately after the existing
// `void clearPersistedNavigationState();` call
void clearPersistedNavigationState();
// Item B: same convergence-point reasoning as clearPersistedNavigationState just above, one layer
// further down. queryClient.clear() (above) only empties the IN-MEMORY cache -- Item B's
// AsyncStorage persistence (startQueryPersistence, api/queryClient.ts) means a copy of whatever
// was cached at the last save also lives on disk. Without this, the next person to sign in on this
// device would have their very first frame painted from the PREVIOUS account's persisted balances.
// Fire-and-forget, same as every other AsyncStorage write here.
void clearPersistedQueryCache();
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS. Re-run `src/context/logoutCacheIsolation.test.tsx` and `src/context/sessionExpiryCacheIsolation.test.tsx` too, to confirm the existing in-memory-cache guarantees are untouched by this addition.
- [x] **Step 5: Commit** — `git add src/context/AuthContext.tsx src/context/persistedQueryCacheIsolation.test.tsx` / `git commit -m "mobile: clear the persisted query cache on logout/session-expiry"`

---

### Task 10: Add a WCAG-AA `successInk` palette token

**Files:**
- Modify: `src/theme/palette.ts:38-39,65-66`
- Test: `src/theme/palette.test.ts`

**Interfaces:**
- Consumes: nothing new.
- Produces: `Palette.successInk: string` (light `#166534`, dark `#22c55e`) — consumed by Task 11's back-online banner text.

`light.success` (`#16a34a`) on `light.successBg` (`#dcfce7`) computes to **~3.00:1**, under WCAG AA's 4.5:1 floor for small text — the exact same shape of problem `warningInk` (`palette.ts:44-48`) already exists to fix for the warning banner. `#166534` (a darker green) computes to **~6.49:1** on the same background — real margin, not just over the line, matching `warningInk`'s own margin (2.86:1 → 6.37:1). Dark theme's `success`/`successBg` pair already clears **~6.28:1**, so `dark.successInk` is simply set equal to `dark.success`, mirroring `dark.warningInk === dark.warning`.

- [x] **Step 1: Write the failing test**
```ts
// Appended to src/theme/palette.test.ts, inside the existing describe('theme palette contrast', ...)
it.each([
  ['light', light],
  ['dark', dark],
])('%s: successInk clears WCAG AA (4.5:1) against successBg', (_name, p) => {
  expect(contrastRatio(p.successInk, p.successBg)).toBeGreaterThanOrEqual(AA_SMALL_TEXT);
});

it('light.successInk has a real margin over light.success, not just a token rename', () => {
  const before = contrastRatio(light.success, light.successBg);
  const after = contrastRatio(light.successInk, light.successBg);
  expect(after).toBeGreaterThan(before + 2.0);
});

it('dark.successInk intentionally equals dark.success, since dark theme already clears AA', () => {
  expect(dark.successInk).toBe(dark.success);
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/theme/palette.test.ts` — Expected: FAIL — TypeScript/runtime error, `successInk` does not exist on `Palette`.
- [x] **Step 3: Write minimal implementation**
```ts
// src/theme/palette.ts:38-39 — light, immediately after successBg
success: '#16a34a',
successBg: '#dcfce7',
// `success` on `successBg` sits at ~3.00:1 -- under WCAG AA's 4.5:1 floor, the same shape of
// problem warningInk exists to fix just below. This green-800 clears ~6.49:1 on the same ground --
// real margin, not just over the line. Needed for OfflineBanner's transient "back online" state.
successInk: '#166534',

// src/theme/palette.ts:65-66 — dark, immediately after successBg
success: '#22c55e',
successBg: '#12301f',
// Dark theme's success already clears AA comfortably on successBg (~6.28:1), so this is the same
// value as success -- same reasoning as dark.warningInk/dark.mutedInk above.
successInk: '#22c55e',
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS.
- [x] **Step 5: Commit** — `git add src/theme/palette.ts src/theme/palette.test.ts` / `git commit -m "mobile: add WCAG-AA successInk palette token"`

---

### Task 11: Transient "back online" feedback on `OfflineBoundary`

**Files:**
- Modify: `src/components/OfflineBanner.tsx:1,14-16,35-74`
- Test: `src/components/OfflineBanner.test.tsx`

**Interfaces:**
- Consumes: `useTransientFlag` (existing, `../lib/useTransientFlag` — already used by `BudgetsScreen`'s "Saved." confirmation; its own test suite already proves it cleans up its timer on unmount, so this task doesn't need to re-prove that), `successInk` (Task 10).
- Produces: nothing new — extends `OfflineBoundary`'s existing render output.

No new component, per the brief: `OfflineBoundary` already owns the one persistent strip every screen renders under; a second banner component would fight it for the same screen real estate. `useTransientFlag(2500)` is reused rather than a hand-rolled `setTimeout`, exactly because its own doc comment (`src/lib/useTransientFlag.ts`) documents that the hand-rolled version was written and leaked three times on other screens before this hook existed — the same shape of leak here (a timer firing into an unmounted `OfflineBoundary`, which wraps the entire app for its whole lifetime, is unlikely to unmount, but there is no reason to hand-roll the risk when a proven, leak-safe hook already exists).

- [x] **Step 1: Write the failing test**
```tsx
// Appended to src/components/OfflineBanner.test.tsx, after the existing describe('OfflineBoundary', ...) block
describe('back online feedback', () => {
  const BACK_ONLINE_TEXT = /Back online/i;

  beforeEach(() => jest.useFakeTimers());
  afterEach(() => {
    jest.useRealTimers();
    onlineManager.setOnline(true);
  });

  it('shows a transient success message when connectivity returns, then clears it, without hiding the app', () => {
    setOnline(false);
    renderBoundary();
    expect(screen.getByText(OFFLINE_TEXT)).toBeTruthy();

    setOnline(true);
    expect(screen.getByText(BACK_ONLINE_TEXT)).toBeTruthy();
    expect(screen.getByText('protected content')).toBeTruthy();

    act(() => { jest.advanceTimersByTime(2500); });
    expect(screen.queryByText(BACK_ONLINE_TEXT)).toBeNull();
    expect(screen.getByText('protected content')).toBeTruthy();
  });

  it('does not show the back-online message on initial mount while already online', () => {
    setOnline(true);
    renderBoundary();

    expect(screen.queryByText(BACK_ONLINE_TEXT)).toBeNull();
  });

  describe('iOS VoiceOver announcement', () => {
    const originalOS = Platform.OS;
    let announceSpy: jest.SpyInstance;

    beforeEach(() => {
      Platform.OS = 'ios';
      announceSpy = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
    });
    afterEach(() => {
      Platform.OS = originalOS;
      announceSpy.mockRestore();
    });

    it('announces when connectivity returns', () => {
      setOnline(false);
      renderBoundary();
      announceSpy.mockClear();

      setOnline(true);

      expect(announceSpy).toHaveBeenCalledWith('Back online — refreshing your data');
    });
  });

  describe('on Android', () => {
    const originalOS = Platform.OS;
    let announceSpy: jest.SpyInstance;

    beforeEach(() => {
      Platform.OS = 'android';
      announceSpy = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
    });
    afterEach(() => {
      Platform.OS = originalOS;
      announceSpy.mockRestore();
    });

    it('still shows the visible back-online banner, but never calls the iOS announcement API', () => {
      setOnline(false);
      renderBoundary();

      setOnline(true);

      expect(screen.getByText(BACK_ONLINE_TEXT)).toBeTruthy();
      expect(announceSpy).not.toHaveBeenCalled();
    });
  });
});
```
- [x] **Step 2: Run test to verify it fails** — `npm test -- src/components/OfflineBanner.test.tsx` — Expected: FAIL — no "Back online" text is ever rendered yet.
- [x] **Step 3: Write minimal implementation**
```tsx
// src/components/OfflineBanner.tsx:1 — add import
import { useTransientFlag } from '../lib/useTransientFlag';

// src/components/OfflineBanner.tsx:14-16 — after OFFLINE_MESSAGE
const OFFLINE_MESSAGE = 'No connection — showing the last data loaded';

/** Shown briefly on the SAME banner when connectivity returns -- see OfflineBoundary's own
 *  comment for why this reuses useTransientFlag rather than a new component or a modal. */
const BACK_ONLINE_MESSAGE = 'Back online — refreshing your data';
const BACK_ONLINE_DURATION_MS = 2500;

// src/components/OfflineBanner.tsx:35-74 — replace the OfflineBoundary function body
export function OfflineBoundary({ children }: { children: ReactNode }) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const online = useOnline();
  const [showingBackOnline, confirmBackOnline] = useTransientFlag(BACK_ONLINE_DURATION_MS);

  const wasOnline = useRef(online);
  useEffect(() => {
    if (Platform.OS === 'ios' && wasOnline.current && !online) {
      AccessibilityInfo.announceForAccessibility(OFFLINE_MESSAGE);
    }
    // The reverse transition: connectivity just came back. Triggers the same transient-flag
    // pattern BudgetsScreen's "Saved." confirmation uses (see useTransientFlag's own doc comment
    // on why a hand-rolled setTimeout here would leak) rather than a toast or modal -- staying
    // consistent with this component's "being offline is a state, not an event" reasoning: coming
    // back online IS an event, briefly, and this is the one place in the boundary allowed to be.
    if (!wasOnline.current && online) {
      confirmBackOnline();
      if (Platform.OS === 'ios') {
        AccessibilityInfo.announceForAccessibility(BACK_ONLINE_MESSAGE);
      }
    }
    wasOnline.current = online;
  }, [online, confirmBackOnline]);

  if (online && !showingBackOnline) return <>{children}</>;

  const barColor = online ? c.successBg : c.warningBg;
  const textColor = online ? c.successInk : c.warningInk;
  const message = online ? BACK_ONLINE_MESSAGE : OFFLINE_MESSAGE;

  return (
    <View style={styles.flex}>
      <View
        style={[styles.bar, { backgroundColor: barColor, paddingTop: insets.top + 6 }]}
        accessible
        accessibilityRole="alert"
        accessibilityLiveRegion="polite"
      >
        <Text style={[styles.text, { color: textColor }]}>{message}</Text>
      </View>
      <SafeAreaInsetsContext.Provider value={{ ...insets, top: 0 }}>
        <View style={styles.flex}>{children}</View>
      </SafeAreaInsetsContext.Provider>
    </View>
  );
}
```
- [x] **Step 4: Run test to verify it passes** — Expected: PASS. Re-run the full file (`npm test -- src/components/OfflineBanner.test.tsx`) to confirm every pre-existing `OfflineBoundary`/mount test still passes unchanged (the `online && !showingBackOnline` early return preserves the exact prior behavior once the transient flag has cleared).
- [x] **Step 5: Commit** — `git add src/components/OfflineBanner.tsx src/components/OfflineBanner.test.tsx` / `git commit -m "mobile: add transient back-online feedback to OfflineBoundary"`

## Self-Review Notes

- **Spec coverage:** React Query Prefetching (Tasks 1-5), Persist React Query Cache (Tasks 6-9), Offline Awareness (Tasks 10-11). All three Phase 2 brief items are covered; navigation state persistence is correctly excluded as already merged.
- **Placeholder scan:** none found — every task's Step 3 shows complete, real code.
- **Type consistency:** `PagedResponse<Transaction>` (Task 3) matches the type `getLedgerNextPageParam` consumes in Task 4's `prefetchInfiniteQuery`. `Query`/`shouldPersistQuery` (Task 6) matches the `dehydrateOptions.shouldDehydrateQuery` signature `persistQueryClient` expects in Task 7. `successInk` (Task 10) is referenced with the exact same name in Task 11.
- **2026-08-30 update:** Task 7 revised to add an explicit `onSuccess` callback (`invalidateQueries()` + `resumePausedMutations()`) so restored data is deterministically marked stale and revalidated in the background on every cold start, rather than relying incidentally on the 30s `staleTime` window — per direction to make the "restore → stale immediately → background refresh → UI updates" flow explicit rather than assumed. New test case added to Task 7 to prove the background refetch actually fires.
- **Execution note (Task 7):** the plan's `persistQueryClient` call assumed an `onSuccess`/`onError` option that does not exist on this project's installed `@tanstack/react-query-persist-client` version's `PersistQueryClientOptions` type (that only exists on the separate, unused `<PersistQueryClientProvider>` component). Fixed by using the `restorePromise` the function actually returns (`const [unsubscribe, restored] = persistQueryClient({...})`) and chaining `.then()` on it instead.
- **Execution note (Task 11):** the plan's new test block was written to be appended as a *sibling* of the existing `describe('OfflineBoundary', ...)` block, but referenced that block's locally-scoped `renderBoundary()` helper — a `ReferenceError` at collection time, the same shape of bug Phase 1 hit with `ReportsScreen.test.tsx`'s sibling-not-nested `describe`. Fixed by nesting the new `describe('back online feedback', ...)` inside `OfflineBoundary` instead. Separately, two pre-existing tests in that file asserted the *opposite* of Task 11's intended behavior: one asserted no VoiceOver announcement fires on the online transition (removed, since Task 11 deliberately adds one, and the new suite already covers it), and one counted total `announceSpy` calls across an offline→online→offline sequence expecting 2 (now 3, since the online leg also announces) — fixed by filtering the assertion down to offline-specific announcements.
