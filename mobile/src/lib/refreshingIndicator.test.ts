import { deriveRefreshing, isPausedCold } from './refreshingIndicator';

describe('deriveRefreshing', () => {
  it('is false while the initial load is still in progress, even if a query is fetching', () => {
    expect(deriveRefreshing([{ isFetching: true, isLoading: true }], true)).toBe(false);
  });

  it('is true once initial load is done and any given query is still fetching', () => {
    expect(
      deriveRefreshing(
        [{ isFetching: false, isLoading: false }, { isFetching: true, isLoading: false }],
        false
      )
    ).toBe(true);
  });

  it('is false once initial load is done and none of the given queries are fetching', () => {
    expect(
      deriveRefreshing(
        [{ isFetching: false, isLoading: false }, { isFetching: false, isLoading: false }],
        false
      )
    ).toBe(false);
  });

  it('is false for an empty query list', () => {
    expect(deriveRefreshing([], false)).toBe(false);
  });

  // A query that starts fetching for the first time after the screen's own initial load has
  // already finished -- e.g. Dashboard mounting a new per-month report query when the Cash Flow
  // range widens -- must not flip the indicator on; that query's own first load isn't a refresh.
  it('is false when a fetching query is in its own first load, even after initial load is done', () => {
    expect(deriveRefreshing([{ isFetching: true, isLoading: true }], false)).toBe(false);
  });

  it('is true when one query is mid-first-load and another is genuinely refetching', () => {
    expect(
      deriveRefreshing(
        [{ isFetching: true, isLoading: true }, { isFetching: true, isLoading: false }],
        false
      )
    ).toBe(true);
  });
});

/**
 * The third outcome screens were collapsing into "empty". React Query pauses a first fetch made
 * with no connectivity: that query is not an error (so every isError guard skips it) and has no
 * data (so every `?? 0` / `length === 0` fallback treats it as a settled answer). On Investments
 * that printed ₹0 as the user's net worth under a banner saying nothing had loaded.
 */
describe('isPausedCold', () => {
  const q = (over: Partial<{ isPending: boolean; fetchStatus: string; data: unknown }> = {}) => ({
    isPending: true,
    fetchStatus: 'paused',
    data: undefined,
    ...over,
  });

  it('is true for a first fetch paused with nothing cached', () => {
    expect(isPausedCold(q())).toBe(true);
  });

  it('is false while the request is actually in flight', () => {
    // Ordinary loading is already covered by each screen's skeleton; this must not hijack it.
    expect(isPausedCold(q({ fetchStatus: 'fetching' }))).toBe(false);
  });

  it('is false for a paused REFETCH that still has data to show', () => {
    // This is precisely the "showing the last data loaded" case the offline banner describes --
    // the screen should keep showing that data, not blank it to "unavailable".
    expect(isPausedCold(q({ isPending: false, data: { netWorth: 42 } }))).toBe(false);
  });

  it('is false once the query has settled, successfully or not', () => {
    expect(isPausedCold(q({ isPending: false, fetchStatus: 'idle', data: {} }))).toBe(false);
    // A settled error is a different state with its own existing guards.
    expect(isPausedCold(q({ isPending: false, fetchStatus: 'idle' }))).toBe(false);
  });

  it('is false when a paused query was hydrated from the persisted cache', () => {
    // Restored figures are real and worth showing offline -- that is what persistence is for.
    expect(isPausedCold(q({ data: { netWorth: 100 } }))).toBe(false);
  });
});
