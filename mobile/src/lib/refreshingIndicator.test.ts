import { deriveRefreshing } from './refreshingIndicator';

describe('deriveRefreshing', () => {
  it('is false while the initial load is still in progress, even if a query is fetching', () => {
    expect(deriveRefreshing([{ isFetching: true }], true)).toBe(false);
  });

  it('is true once initial load is done and any given query is still fetching', () => {
    expect(deriveRefreshing([{ isFetching: false }, { isFetching: true }], false)).toBe(true);
  });

  it('is false once initial load is done and none of the given queries are fetching', () => {
    expect(deriveRefreshing([{ isFetching: false }, { isFetching: false }], false)).toBe(false);
  });

  it('is false for an empty query list', () => {
    expect(deriveRefreshing([], false)).toBe(false);
  });
});
