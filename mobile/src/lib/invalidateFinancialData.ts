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
export const FINANCIAL_QUERY_KEYS = [
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
  // Both added with the Phase 4 screens, and both are exactly the case this module's comment
  // warns about -- a key whose screen isn't visible from where the edit happens. Every balance
  // change moves net worth, and every transaction change can create or break a recurring-payment
  // pattern; without these, the Investments and Insights screens keep showing pre-import figures
  // until their caches age out on their own.
  'networth',
  'recurring',
  // Settings' Data section: statements imported, transactions imported/skipped, last import.
  // Every one of those is a direct count of the thing an import or a statement deletion changes.
  'import-statistics',
  // The categorization review backlog and its merchant-grouped half. Both shrink when a category
  // is set anywhere -- including from the Ledger, which is a different screen than the one showing
  // the queue, and exactly the "key whose screen isn't visible from where the edit happens" case
  // this module's comment warns about. An import also refills them, so they belong in the cascade
  // in both directions.
  'needs-review',
  'needs-review-groups',
] as const;

export function invalidateFinancialData(queryClient: QueryClient) {
  FINANCIAL_QUERY_KEYS.forEach((key) => {
    void queryClient.invalidateQueries({ queryKey: [key] });
  });
}
