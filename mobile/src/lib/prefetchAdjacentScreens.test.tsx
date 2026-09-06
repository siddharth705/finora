import type { ReactNode } from 'react';
import { renderHook, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider, onlineManager } from '@tanstack/react-query';
import { usePrefetchAdjacentScreens } from './prefetchAdjacentScreens';
import { budgetsApi, reportsApi, transactionsApi } from '../api/endpoints';
import { DEFAULT_LEDGER_FILTERS, LEDGER_PAGE_SIZE } from '../screens/LedgerScreen';

jest.mock('../api/endpoints', () => ({
  transactionsApi: { search: jest.fn() },
  budgetsApi: { list: jest.fn() },
  reportsApi: { availableMonths: jest.fn(), forMonth: jest.fn() },
}));

const transactions = transactionsApi as jest.Mocked<typeof transactionsApi>;
const budgets = budgetsApi as jest.Mocked<typeof budgetsApi>;
const reports = reportsApi as jest.Mocked<typeof reportsApi>;

function page(over: Record<string, unknown> = {}) {
  return { content: [], page: 0, size: LEDGER_PAGE_SIZE, totalElements: 0, totalPages: 1, ...over };
}

// Tracked so afterEach can clear every QueryClient a test created -- prefetched queries have no
// mounted useQuery observer, so they go "inactive" the instant they resolve and schedule their own
// garbage-collection timer (5 minutes by default). Left unresolved, that's a real leaked timer, not
// a slow test: `queryClient.clear()` removes each query outright, which cancels its GC timeout too.
// gcTime: 0 was considered instead and rejected -- it would race prefetched (unobserved, so
// immediately "inactive") data against this file's own assertions reading it back out.
const activeClients: QueryClient[] = [];

function renderPrefetch(queryClient: QueryClient) {
  activeClients.push(queryClient);
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => usePrefetchAdjacentScreens(), { wrapper });
}

beforeEach(() => jest.clearAllMocks());
afterEach(() => {
  onlineManager.setOnline(true);
  activeClients.splice(0).forEach((qc) => qc.clear());
});

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

  /**
   * Refetching an infinite query refetches every page it currently holds, not just the first --
   * query-core's infiniteQueryBehavior uses `remainingPages = pages ?? oldPages.length`. So once
   * the user had actually scrolled the Ledger, returning to Dashboard replayed the whole scrolled
   * range as sequential requests every time the 30s staleTime lapsed. Guarded by existence rather
   * than capped with `pages: 1`, because the cap would rebuild data.pages from just the one fetched
   * page and truncate the user's list -- so this asserts BOTH halves: no requests, and the scrolled
   * pages still intact.
   */
  it('does not replay a scrolled Ledger, and does not truncate it either', async () => {
    budgets.list.mockResolvedValue([]);
    reports.availableMonths.mockResolvedValue([]);

    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false, staleTime: 30_000 } },
    });
    // Three pages already scrolled, and deliberately STALE (staleTime is 30s, this is older) so the
    // prefetch would genuinely refetch rather than being skipped for freshness.
    queryClient.setQueryData(
      ['transactions', DEFAULT_LEDGER_FILTERS],
      { pages: [page(), page({ page: 1 }), page({ page: 2 })], pageParams: [0, 1, 2] }
    );
    const entry = queryClient.getQueryCache().find({ queryKey: ['transactions', DEFAULT_LEDGER_FILTERS] });
    entry!.state.dataUpdatedAt = Date.now() - 60_000;

    renderPrefetch(queryClient);
    await waitFor(() => expect(budgets.list).toHaveBeenCalled());

    expect(transactions.search).not.toHaveBeenCalled();
    const after = queryClient.getQueryData(['transactions', DEFAULT_LEDGER_FILTERS]) as {
      pages: unknown[];
    };
    expect(after.pages).toHaveLength(3);
  });

  it('still warms the Ledger when its cache is genuinely cold', async () => {
    // The other half of the guard above: skipping when data exists must not become skipping always.
    transactions.search.mockResolvedValue(page());
    budgets.list.mockResolvedValue([]);
    reports.availableMonths.mockResolvedValue([]);

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderPrefetch(queryClient);

    await waitFor(() =>
      expect(queryClient.getQueryData(['transactions', DEFAULT_LEDGER_FILTERS])).toBeDefined()
    );
    expect(transactions.search).toHaveBeenCalledWith({ ...DEFAULT_LEDGER_FILTERS, page: 0 });
  });

  it('does not leave an unhandled rejection when the report prefetch fails', async () => {
    // fetchQuery rejects (unlike prefetchQuery, which swallows), and this path is reached on the
    // entirely ordinary case of signing out mid-request, not just on a server fault.
    transactions.search.mockResolvedValue(page());
    budgets.list.mockResolvedValue([]);
    reports.availableMonths.mockRejectedValue(new Error('session ended'));

    const onUnhandled = jest.fn();
    process.on('unhandledRejection', onUnhandled);
    try {
      const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
      renderPrefetch(queryClient);
      await waitFor(() => expect(reports.availableMonths).toHaveBeenCalled());
      // Rejections surface a macrotask later than the awaited assertion above.
      await new Promise((resolve) => setTimeout(resolve, 50));
      expect(onUnhandled).not.toHaveBeenCalled();
    } finally {
      process.off('unhandledRejection', onUnhandled);
    }
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
