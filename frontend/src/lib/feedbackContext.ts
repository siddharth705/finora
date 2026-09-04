import type { FeedbackContext } from '../api/endpoints';

/**
 * Maps the current route to a FeedbackEntry.Context so the widget never asks the user to pick one
 * themselves -- "mount points, not a bespoke form per page" (proposal §3.3): one widget, mounted
 * once in TopBar (present on every authenticated route), rather than a form wired into each page
 * individually. Checked in array order via `.find()`, first prefix match wins -- so wherever two
 * entries could both match the same path (e.g. /app/settings and /app/settings/gmail/review),
 * either they map to the same context (harmless which one "wins") or the more specific one MUST
 * be listed first. /app/import intentionally covers /app/imports/:jobId too -- both are
 * IMPORT_FLOW, so there's deliberately no separate /app/imports entry to keep in sync with it.
 *
 * Investments, Insights, Billing, Referrals and Support have no dedicated Context value (the enum
 * only grew what the original proposal named) -- OTHER for all of them until a value is worth
 * adding, exactly the cheap-revisit path FeedbackEntry.Context's own doc comment describes.
 */
const ROUTE_CONTEXTS: [prefix: string, context: FeedbackContext][] = [
  ['/app/settings', 'SETTINGS'],
  ['/app/profile', 'SETTINGS'],
  ['/app/transactions', 'TRANSACTIONS'],
  ['/app/reports', 'REPORTS'],
  ['/app/budgets', 'BUDGETS'],
  ['/app/goals', 'GOALS'],
  ['/app/import', 'IMPORT_FLOW'],
  ['/app/statements', 'IMPORT_FLOW'],
  ['/app/accounts', 'ACCOUNTS'],
  ['/help', 'HELP'],
];

export function contextForPath(pathname: string): FeedbackContext {
  const match = ROUTE_CONTEXTS.find(([prefix]) => pathname.startsWith(prefix));
  if (match) return match[1];
  if (pathname === '/app') return 'DASHBOARD';
  return 'OTHER';
}
