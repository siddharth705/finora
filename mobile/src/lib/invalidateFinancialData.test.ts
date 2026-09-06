import { readdirSync, readFileSync, statSync } from 'fs';
import { join } from 'path';
import { FINANCIAL_QUERY_KEYS } from './invalidateFinancialData';

/**
 * A guard against the one bug this module exists to prevent, and which it has now hit twice.
 *
 * Adding a screen that reads a NEW query key silently opts that screen out of the post-write
 * refresh: nothing fails, nothing warns, the screen just shows pre-edit figures until its cache
 * ages out on its own. Phase 4 introduced 'networth' and 'recurring' exactly that way.
 *
 * So rather than trusting the next person to remember, this reads the source and fails when a key
 * appears in a query that is in neither list. Deciding it isn't financial is fine -- that decision
 * just has to be written down in NON_FINANCIAL_KEYS, with a reason.
 */

const SRC = join(__dirname, '..');

/**
 * Keys deliberately outside the cascade. Each is here because a write to a transaction, account or
 * import cannot change its answer -- not because it was inconvenient to refresh.
 */
const NON_FINANCIAL_KEYS = new Set([
  // Reference data. Categories change when a user edits their category list, never as a side
  // effect of a transaction edit.
  'categories',
  // The user's own profile/preferences.
  'user-settings',
  // Scoped to one already-imported statement and fetched when its row is expanded. The statement's
  // own rows do not change under it; deleting the statement removes the row entirely, and
  // 'statement-imports' (which is in the cascade) is what drives that.
  'statement-import-transactions',
  // Active sessions. Changes on sign-in, sign-out and password change -- never as a side effect of
  // editing a transaction. SettingsScreen refreshes it directly after a password change.
  'devices',
  // The auto-apply confidence threshold. A preference the user sets; no financial write moves it.
  'workspace-settings',
  // Support, Help & Feedback v1, Phase 8. A support ticket's list/detail is data ABOUT the app,
  // not financial data in it -- no transaction, account, or import write can change a ticket's
  // subject, status, or attachments. SupportTicketsScreen refreshes 'support-tickets-mine' itself
  // right after creating a new ticket.
  'support-tickets-mine',
  'support-ticket-detail',
  // Track C/C7. Which statement row a transaction came from is fixed at import time -- editing
  // this transaction's (or any other transaction's) category, amount, or notes never changes
  // which file/row it was originally read from. Same reasoning as 'statement-import-transactions'
  // above.
  'transaction-source',
  // Refer & Earn MVP. A referral relationship (and the resulting count) only changes when someone
  // else signs up with this user's code -- no transaction, account, or import write moves it. Same
  // reasoning as 'support-tickets-mine' above.
  'referrals-mine',
  // Subscription billing V4. A user's plan/entitlements change only as a side effect of a billing
  // event (a RevenueCat purchase, restore, or webhook-driven state change) -- never as a side
  // effect of editing a transaction, adding an account, or importing a statement. Same reasoning
  // as 'user-settings'/'workspace-settings' above. PaywallScreen and MySubscriptionScreen already
  // invalidate both of these directly right after a purchase/restore completes.
  'entitlements',
  'my-subscription',
]);

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) return sourceFiles(full);
    if (!/\.tsx?$/.test(entry) || /\.test\.tsx?$/.test(entry)) return [];
    return [full];
  });
}

function queryKeysInSource(): Map<string, string[]> {
  const found = new Map<string, string[]>();
  for (const file of sourceFiles(SRC)) {
    const contents = readFileSync(file, 'utf8');
    for (const match of contents.matchAll(/queryKey:\s*\[\s*'([^']+)'/g)) {
      const key = match[1];
      found.set(key, [...(found.get(key) ?? []), file]);
    }
  }
  return found;
}

describe('invalidateFinancialData covers every query the app runs', () => {
  const declared = new Set<string>(FINANCIAL_QUERY_KEYS);

  it('finds the query keys to check', () => {
    // Guards the guard: a regex that silently matches nothing would make every assertion below
    // pass without checking anything.
    expect(queryKeysInSource().size).toBeGreaterThan(5);
  });

  it('classifies every key as either refreshed or deliberately not', () => {
    const unclassified = [...queryKeysInSource().entries()]
      .filter(([key]) => !declared.has(key) && !NON_FINANCIAL_KEYS.has(key))
      .map(([key, files]) => `'${key}' (used in ${files.map((f) => f.replace(SRC, 'src')).join(', ')})`);

    expect(unclassified).toEqual([]);
  });

  it('keeps the two lists disjoint, so a key has exactly one answer', () => {
    const inBoth = [...declared].filter((key) => NON_FINANCIAL_KEYS.has(key));
    expect(inBoth).toEqual([]);
  });

  // Regression: both were read by a Phase 4 screen while missing from the cascade.
  it.each(['networth', 'recurring'])('refreshes %s after a write', (key) => {
    expect(declared.has(key)).toBe(true);
  });
});
