import type { FeedbackContext } from '../api/endpoints';

/**
 * Maps the current route to a FeedbackEntry.Context so the widget never asks the user to pick one
 * themselves -- "mount points, not a bespoke form per page" (proposal §3.3): one widget, mounted
 * once in TopBar (present on every authenticated route), rather than a form wired into each page
 * individually. Order matters -- checked longest-prefix-first so a nested route (e.g.
 * /app/settings/gmail/review) doesn't fall through to a shorter, wrong-context prefix.
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
  ['/app/imports', 'IMPORT_FLOW'],
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
