import { test, expect, uploadStatement } from '../../fixtures/test';
import { csv, type Row } from '../../fixtures/statements';
import { transactionsFor } from '../../fixtures/db';
import type { Page } from '@playwright/test';

/**
 * Phase 3 — Duplicate Transaction Review (WI5). The brief calls this the highest priority, and it
 * is, because it is the one screen where the product asks a question it cannot answer itself.
 *
 * The behaviour being guarded is not "a review screen appears". It is that **no transaction is
 * imported or skipped on the system's own initiative**. Detection used to be a filter: a flagged
 * row was auto-unticked and, unless the user noticed a checkbox they had never touched, it simply
 * did not import. That was wrong in both directions — a genuine re-import got skipped without
 * anyone confirming it, and two identical coffees bought on the same day got skipped too.
 *
 * So every test here asserts a decision and its consequence, never just a rendering.
 */

const FARE: Row = { date: '2026-06-07', description: 'METRO FARE', amount: 45.0, type: 'DEBIT' };
const LUNCH: Row = { date: '2026-06-08', description: 'SWIGGY ORDER 4471', amount: 486.0, type: 'DEBIT' };
const COFFEE: Row = { date: '2026-06-09', description: 'BLUE TOKAI COFFEE', amount: 320.0, type: 'DEBIT' };
const GROCERIES: Row = { date: '2026-06-10', description: 'BLINKIT GROCERIES 9982', amount: 1240.5, type: 'DEBIT' };

async function stageInBrowser(page: Page, rows: Row[], fileName = 'statement.csv') {
  await page.goto('/app/import');
  await uploadStatement(page, fileName, 'text/csv', csv(rows));
}

const review = (page: Page) => page.getByTestId('duplicate-review');
const confirmButton = (page: Page) => page.getByRole('button', { name: /confirm import/i });
const pair = (page: Page, index: number) => page.getByTestId(`duplicate-${index}`);

test.describe('Phase 3 — duplicate review', () => {
  /** A statement with nothing suspicious in it must not pay for this feature. */
  test('a clean statement never sees the review step and imports freely', async ({ userPage, api }) => {
    await api.importStatement([LUNCH], { accountName: 'Primary' });

    await stageInBrowser(userPage, [COFFEE, GROCERIES]);

    await expect(confirmButton(userPage)).toBeEnabled({ timeout: 20_000 });
    await expect(review(userPage)).toBeHidden();
  });

  test('a flagged row shows what it appears to repeat, and blocks the import until answered',
    async ({ userPage, api, user }) => {
      const { summary } = await api.importStatement([FARE], { accountName: 'Primary' });
      expect(summary.imported).toBe(1);

      await stageInBrowser(userPage, [FARE]);

      const panel = review(userPage);
      await expect(panel).toBeVisible({ timeout: 20_000 });

      // Both sides, or the user is being asked to trust a flag rather than make a decision.
      // exact: true -- Playwright's text matching is case-insensitive substring by default, and
      // the panel's own explanatory copy contains "already in your ledger" twice more.
      await expect(panel.getByText('In this statement', { exact: true })).toBeVisible();
      await expect(panel.getByText('Already in your ledger', { exact: true })).toBeVisible();
      await expect(panel).toContainText('METRO FARE');
      // Rendered as DD-MMM-YYYY (formatDateDDMMMYYYY), not the raw ISO value -- see DuplicateReview.tsx.
      await expect(panel).toContainText('07-Jun-2026');
      await expect(panel).toContainText('45');
      // Reworded by #736 (DuplicateDetector): matching went case-insensitive on description, so
      // "exact description match" stopped being true and the copy says so -- date+amount together,
      // description called out separately as "matching" rather than folded into one "exact" claim.
      await expect(panel).toContainText(/Same date and amount, and a matching description/);
      // When the existing one was imported -- the strongest signal for "did I already load this?"
      await expect(panel).toContainText(/· imported/);

      await expect(confirmButton(userPage)).toBeDisabled();
      await expect(panel).toContainText(/1 still needs a decision/);

      // Nothing has been written by looking at the screen.
      expect(await transactionsFor(user.id, 'METRO FARE')).toHaveLength(1);
    });

  test('"Import anyway" releases the gate and the row lands on the ledger',
    async ({ userPage, api, user }) => {
      await api.importStatement([FARE], { accountName: 'Primary' });
      await stageInBrowser(userPage, [FARE]);
      await expect(review(userPage)).toBeVisible({ timeout: 20_000 });

      await pair(userPage, 0).getByRole('button', { name: 'Import anyway' }).click();

      await expect(review(userPage)).toContainText('All duplicates resolved.');
      await expect(confirmButton(userPage)).toBeEnabled();
      await confirmButton(userPage).click();

      await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });
      expect(await transactionsFor(user.id, 'METRO FARE')).toHaveLength(2);
    });

  test('"Skip this row" releases the gate and the row does not land',
    async ({ userPage, api, user }) => {
      await api.importStatement([FARE], { accountName: 'Primary' });
      await stageInBrowser(userPage, [FARE]);
      await expect(review(userPage)).toBeVisible({ timeout: 20_000 });

      await pair(userPage, 0).getByRole('button', { name: 'Skip this row' }).click();
      await expect(confirmButton(userPage)).toBeEnabled();
      await confirmButton(userPage).click();

      await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });
      expect(await transactionsFor(user.id, 'METRO FARE')).toHaveLength(1);
    });

  test('every duplicate must be answered, and the outstanding count tracks each one',
    async ({ userPage, api }) => {
      await api.importStatement([FARE, LUNCH, COFFEE], { accountName: 'Primary' });
      await stageInBrowser(userPage, [FARE, LUNCH, COFFEE]);

      const panel = review(userPage);
      await expect(panel).toBeVisible({ timeout: 20_000 });
      await expect(panel).toContainText('3 possible duplicates');
      await expect(panel).toContainText(/3 still need a decision/);

      await pair(userPage, 0).getByRole('button', { name: 'Import anyway' }).click();
      await expect(panel).toContainText(/2 still need a decision/);
      await expect(confirmButton(userPage)).toBeDisabled();

      await pair(userPage, 1).getByRole('button', { name: 'Skip this row' }).click();
      await expect(panel).toContainText(/1 still needs a decision/);
      await expect(confirmButton(userPage)).toBeDisabled();

      await pair(userPage, 2).getByRole('button', { name: 'Import anyway' }).click();
      await expect(panel).toContainText('All duplicates resolved.');
      await expect(confirmButton(userPage)).toBeEnabled();
    });

  /**
   * Mixed decisions, carried all the way to the ledger. This is the assertion the brief is really
   * asking for: three independent answers, three different outcomes, and no cross-talk between
   * them.
   */
  test('mixed decisions each produce their own outcome', async ({ userPage, api, user }) => {
    await api.importStatement([FARE, LUNCH, COFFEE], { accountName: 'Primary' });
    await stageInBrowser(userPage, [FARE, LUNCH, COFFEE]);
    await expect(review(userPage)).toBeVisible({ timeout: 20_000 });

    await pair(userPage, 0).getByRole('button', { name: 'Import anyway' }).click();  // FARE
    await pair(userPage, 1).getByRole('button', { name: 'Skip this row' }).click();  // LUNCH
    await pair(userPage, 2).getByRole('button', { name: 'Import anyway' }).click();  // COFFEE
    await confirmButton(userPage).click();

    await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });

    expect(await transactionsFor(user.id, 'METRO FARE')).toHaveLength(2);
    expect(await transactionsFor(user.id, 'SWIGGY ORDER 4471')).toHaveLength(1);
    expect(await transactionsFor(user.id, 'BLUE TOKAI COFFEE')).toHaveLength(2);
  });

  /**
   * Several existing matches means the user probably transacts this repeatedly — a daily fare, a
   * split bill. A filter would read that as "even more certainly a duplicate"; it is the opposite,
   * and the copy has to say so or the count actively misleads.
   */
  test('a repeated charge is framed as a reason to import, not a stronger duplicate signal',
    async ({ userPage, api }) => {
      // Two identical fares already on the books, so the third match reports matchCount 2.
      const { summary } = await api.importStatement([FARE], { accountName: 'Primary' });
      const accountId = summary.account?.id;
      await api.importStatement([FARE], { accountId, confirmedNotDuplicate: true });

      await stageInBrowser(userPage, [FARE]);
      const panel = review(userPage);
      await expect(panel).toBeVisible({ timeout: 20_000 });

      await expect(panel).toContainText(/already have 2 transactions matching this/);
      await expect(panel).toContainText(/importing is probably right/);
    });

  test.describe('apply to similar', () => {
    /** Offering a bulk action with nothing to apply invites a click that silently does nothing. */
    test('is not offered before a decision exists', async ({ userPage, api }) => {
      await api.importStatement([FARE, FARE], { accountName: 'Primary' });
      await stageInBrowser(userPage, [FARE, FARE]);
      await expect(review(userPage)).toBeVisible({ timeout: 20_000 });

      await expect(userPage.getByRole('button', { name: /Apply to \d+ similar/ })).toHaveCount(0);
    });

    /**
     * Bounded to rows still unresolved, and only to rows that actually match. A bulk action that
     * overwrote a hand-made choice, or reached a different merchant, would be worse than no bulk
     * action at all — the user would have no way to know it had happened.
     */
    test('reaches only unanswered rows with the same description', async ({ userPage, api, user }) => {
      const repeated = { ...FARE };
      await api.importStatement([repeated, LUNCH], { accountName: 'Primary' });

      // Three identical fares plus one unrelated duplicate, all flagged.
      await stageInBrowser(userPage, [repeated, repeated, repeated, LUNCH]);
      const panel = review(userPage);
      await expect(panel).toBeVisible({ timeout: 20_000 });
      await expect(panel).toContainText('4 possible duplicates');

      // Answer the third fare by hand FIRST, so the bulk action has something it must not touch.
      await pair(userPage, 2).getByRole('button', { name: 'Skip this row' }).click();

      await pair(userPage, 0).getByRole('button', { name: 'Import anyway' }).click();
      await pair(userPage, 0).getByRole('button', { name: /Apply to 1 similar/ }).click();

      // The unrelated duplicate is untouched and still blocking.
      await expect(panel).toContainText(/1 still needs a decision/);
      await expect(confirmButton(userPage)).toBeDisabled();
      await expect(pair(userPage, 3)).toContainText('Needs a decision');

      await pair(userPage, 3).getByRole('button', { name: 'Skip this row' }).click();
      await confirmButton(userPage).click();
      await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });

      // 1 original + 2 imported (one by hand, one in bulk). The hand-made Skip survived.
      expect(await transactionsFor(user.id, 'METRO FARE')).toHaveLength(3);
      expect(await transactionsFor(user.id, 'SWIGGY ORDER 4471')).toHaveLength(1);
    });
  });

  /**
   * `confidence` must not be rendered as a score. The detector matches on date AND amount AND
   * description being identical, so every match is exact — a percentage would imply a spectrum it
   * cannot produce, and an invented number on a financial screen is worse than no number.
   */
  test('does not present a confidence percentage it cannot justify', async ({ userPage, api }) => {
    await api.importStatement([FARE], { accountName: 'Primary' });
    await stageInBrowser(userPage, [FARE]);

    const panel = review(userPage);
    await expect(panel).toBeVisible({ timeout: 20_000 });
    await expect(panel).not.toContainText(/\d+\s*%/);
    await expect(panel).not.toContainText(/confidence/i);
  });

  /** The user needs to know that looking at this screen has not changed anything yet. */
  test('states that nothing changes until the import is confirmed', async ({ userPage, api }) => {
    await api.importStatement([FARE], { accountName: 'Primary' });
    await stageInBrowser(userPage, [FARE]);

    await expect(review(userPage)).toContainText(
      /nothing changes in your ledger until you confirm/, { timeout: 20_000 }
    );
  });
});
