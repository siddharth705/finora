/**
 * Standard shape for a pull-to-refresh indicator built on React Query: visible while any of the
 * given queries is fetching, but never during their first load -- that's already covered by each
 * screen's own skeleton/spinner, not by RefreshControl.
 *
 * "First load" is checked per query (`isFetching && !isLoading`), not just once up front via
 * `initialLoad`: a query that starts fetching for the first time AFTER the screen's own initial
 * load has finished -- e.g. Dashboard mounting a brand new per-month report query when the user
 * taps a wider Cash Flow range, not from a pull gesture -- is still its own first load, and
 * `initialLoad` alone (computed once from a couple of bootstrap queries) can't see that. Without
 * this, that query's `isFetching: true` flips RefreshControl on with no pull gesture at all --
 * exactly the "reverse bug" this file already existed to guard against, just triggered by a
 * later-mounted query instead of a slow one at mount.
 *
 * A plain function, not a hook (no `use` prefix) -- it has no hook internals of its own, and
 * naming it as if it did would make the react-hooks lint rules apply hook-call-order checks to a
 * function that never calls one. Callers pass exactly the query results whose sections are
 * actually rendered on screen -- an invisible query (fetched for pre-warming another screen's
 * cache, its own data never read here) must not be included, or the indicator can flip on with no
 * user gesture, or stay stuck up after every visible section has already settled.
 */
export function deriveRefreshing(
  queries: readonly { isFetching: boolean; isLoading: boolean }[],
  initialLoad: boolean
): boolean {
  return queries.some((q) => q.isFetching && !q.isLoading) && !initialLoad;
}

/**
 * True when a query has never loaded and is currently PAUSED for lack of connectivity -- the state
 * React Query puts a first fetch into when onlineManager says the device is offline (queryClient.ts
 * wires that to NetInfo).
 *
 * This is a third outcome that screens were collapsing into "empty". A paused query is not an
 * error, so every `isError` guard skips it; and it has no data, so every `length === 0` /
 * `?? 0` fallback treats it as a settled answer. The result on Investments was ₹0 printed as this
 * person's net worth and liabilities -- underneath a banner reading "No connection — showing the
 * last data loaded", when in fact nothing had been loaded at all. 'networth' is deliberately kept
 * out of the persistence allowlist, so there is never a cached figure to fall back on: going to
 * that screen offline reliably produced fabricated zeroes.
 *
 * Deliberately narrow. `data === undefined` keeps it false whenever there IS something real to
 * show -- a paused REFETCH over restored or already-fetched data is exactly the "showing the last
 * data loaded" case the banner describes, and should keep showing it rather than blanking.
 */
export function isPausedCold(query: {
  isPending: boolean;
  fetchStatus: string;
  data: unknown;
}): boolean {
  return query.isPending && query.fetchStatus === 'paused' && query.data === undefined;
}
