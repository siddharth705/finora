import { useCallback, useEffect, useRef } from 'react';
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
  // Read via a ref inside the focus callback below rather than closing over `online` directly:
  // react-navigation's useFocusEffect re-subscribes its focus/blur listeners AND immediately
  // re-invokes the callback whenever its identity changes while the screen is already focused.
  // Putting `online` in that callback's own deps meant every connectivity flap while sitting on
  // Dashboard -- not just a genuine focus transition -- tore down and re-fired the whole prefetch
  // trio. The ref keeps the callback's identity stable across online/offline flips; only real
  // focus/blur transitions re-run it now.
  const onlineRef = useRef(online);
  useEffect(() => {
    onlineRef.current = online;
  }, [online]);

  useFocusEffect(
    useCallback(() => {
      if (!onlineRef.current) return;

      // Only ever warms a COLD ledger cache, which is the whole point of a prefetch. Unconditional,
      // this stopped being a prefetch as soon as the user had actually used the Ledger: refetching
      // an infinite query refetches every page it currently holds, not just the first --
      // query-core's infiniteQueryBehavior computes `remainingPages = pages ?? oldPages.length` --
      // so someone who had scrolled to page 20 and come back to Dashboard replayed all 20 requests
      // sequentially on each focus, once the 30s staleTime had lapsed.
      //
      // Capping it with `pages: 1` would be worse than leaving it alone: the refetch rebuilds
      // data.pages from what it fetched, so it would truncate the user's scrolled list back to a
      // single page. Skipping outright when there is already data is the only option that neither
      // storms the network nor discards state -- and a warm ledger has nothing left to warm.
      if (!queryClient.getQueryData(['transactions', DEFAULT_LEDGER_FILTERS])) {
        void queryClient.prefetchInfiniteQuery({
          queryKey: ['transactions', DEFAULT_LEDGER_FILTERS],
          queryFn: ({ pageParam }) =>
            transactionsApi.search({ ...DEFAULT_LEDGER_FILTERS, page: pageParam as number }),
          initialPageParam: 0,
          getNextPageParam: getLedgerNextPageParam,
        });
      }

      void queryClient.prefetchQuery({ queryKey: ['budgets'], queryFn: () => budgetsApi.list() });

      // Sequential, not a third parallel prefetchQuery: which month is "current" isn't known until
      // report-months resolves. Reuses fetchQuery (not prefetchQuery) here specifically because its
      // return value is needed to pick the latest month -- both still respect staleTime and skip
      // the network when Dashboard's own ['report-months'] query is already warm.
      // `.catch` rather than bare `void`: unlike prefetchQuery (which swallows its own errors),
      // fetchQuery REJECTS on failure, so `void`-ing this IIFE left an unhandled rejection on every
      // /reports/months failure -- and on the entirely ordinary path of signing out while the
      // request is in flight, since the 401 handler rejects everything pending. Nothing here is
      // recoverable and nothing is shown: a prefetch that fails just means the screen it was
      // warming loads normally when opened.
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
      })().catch(() => {});
    }, [queryClient])
  );
}
