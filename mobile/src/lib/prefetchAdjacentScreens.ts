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
 * attempt and burns the configured retry before settling as an error, which is wasted work for a
 * prefetch nobody is waiting on and nobody will see fail.
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
