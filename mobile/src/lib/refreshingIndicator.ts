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
