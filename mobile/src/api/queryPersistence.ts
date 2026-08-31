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
