import { deriveRefreshing } from './refreshingIndicator';

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
