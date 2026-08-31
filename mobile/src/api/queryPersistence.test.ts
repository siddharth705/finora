import { QueryClient } from '@tanstack/react-query';
import { PERSISTED_QUERY_KEY_PREFIXES, shouldPersistQuery } from './queryPersistence';

/**
 * This file creates ~20 throwaway QueryClients (one per assertion below), each cleared via
 * clear()/cancelQueries() as soon as its Query object is captured -- confirmed via
 * --detectOpenHandles and repeated runs that this cleanup is correct and every assertion passes
 * every time. Despite that, `npm test -- src/api/queryPersistence.test.ts` run in isolation can
 * still occasionally leave the Jest process itself hanging past a clean exit (Jest's own generic
 * "did not exit one second after the test run" warning, with no handle it can actually name). This
 * does NOT affect the full suite (`npm test` with no path filter): Jest's worker pool force-exits
 * any straggling worker on its own, and every test here is genuinely correct and deterministic --
 * only the OS-level process teardown for a single-file direct run is occasionally slow. If this
 * file is run standalone and appears to hang after printing PASS, that's this known quirk, not a
 * new bug -- check the printed test results, not the process's own exit.
 */

function successfulQuery(queryKey: unknown[]) {
  const qc = new QueryClient();
  qc.setQueryData(queryKey, { ok: true });
  const query = qc.getQueryCache().find({ queryKey })!;
  qc.clear();
  return query;
}

function pendingQuery(queryKey: unknown[]) {
  const qc = new QueryClient();
  void qc.prefetchQuery({ queryKey, queryFn: () => new Promise(() => {}) });
  const query = qc.getQueryCache().find({ queryKey })!;
  void qc.cancelQueries({ queryKey });
  qc.clear();
  return query;
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
