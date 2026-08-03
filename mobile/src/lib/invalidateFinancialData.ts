import type { QueryClient } from '@tanstack/react-query';

/**
 * The cascading-refresh set that any write to a transaction, account, or import must trigger.
 *
 * Ported from the identical list duplicated in frontend/src/pages/Ledger.tsx
 * (invalidateEverything) and Import.tsx. Centralized here because the web copies have already
 * drifted apart once -- and the easiest key to forget is exactly the one whose screen isn't
 * visible from where the edit happened: 'report'/'report-months' feed the Dashboard's cash-flow
 * chart, which the Ledger doesn't render but every edit changes the totals of.
 */
const FINANCIAL_QUERY_KEYS = [
  'transactions',
  'dashboard-summary',
  'accounts',
  'recent-transactions',
  'budgets',
  'goals',
  'insights',
  'report-months',
  'report',
  'statement-imports',
] as const;

export function invalidateFinancialData(queryClient: QueryClient) {
  FINANCIAL_QUERY_KEYS.forEach((key) => {
    void queryClient.invalidateQueries({ queryKey: [key] });
  });
}
