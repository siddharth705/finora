import { test, expect, uploadStatement } from '../../fixtures/test';
import { csv, type Row } from '../../fixtures/statements';
import { countedExpense, ledgerExpense, transactionsFor } from '../../fixtures/db';

/**
 * Phase 7 — dashboard consistency, and Phase 8 — reconciliation. The brief calls Phase 7 a
 * critical regression test, and it is the one that already caught a real defect.
 *
 * The defect: a user reviewed two identical METRO FARE charges and chose "Import anyway" — the
 * right answer, they commute twice a day. The rows landed. Then `ReconciliationService`'s duplicate
 * pass ran, saw two rows sharing a duplicate key, and marked the later one `is_duplicate_of`. Seven
 * call sites filter that out, so the ledger held ₹1,618.50 while the dashboard reported ₹1,528.50.
 * The decision was honoured in the ledger and reversed in every number.
 *
 * That is why these assertions read the database rather than the DOM. A workflow that looks correct
 * while producing incorrect financial data is still a failed test, and the DOM cannot tell you
 * whether a transaction was *counted* — only whether it was *displayed*.
 *
 * `is_duplicate_of IS NULL` is the single condition shared by BudgetService, AnalyticsService,
 * DashboardService, InsightsService, RecurringService, ReportService and two TransactionRepository
 * aggregates. Asserting against it covers all seven at once: a row that passes is counted
 * everywhere, and one that fails is missing from everywhere. The dashboard endpoint is then checked
 * separately, as the one surface a user actually reads.
 */

const FARE: Row = { date: '2026-06-07', description: 'METRO FARE', amount: 45.0, type: 'DEBIT' };

test.describe('Phase 7 — what the ledger holds and what the product counts', () => {
  /**
   * The regression, stated as the product claim it broke.
   *
   * Driven through the real review screen rather than the API, because the whole failure was in the
   * seam between a decision made in a browser and a background pass that never heard about it.
   */
  test('a duplicate the user chose to import counts as spending', async ({ userPage, api, user }) => {
    await api.importStatement([FARE], { accountName: 'Primary' });

    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'fares.csv', 'text/csv', csv([FARE]));
    await expect(userPage.getByTestId('duplicate-review')).toBeVisible({ timeout: 20_000 });

    await userPage.getByTestId('duplicate-0').getByRole('button', { name: 'Import anyway' }).click();
    await userPage.getByRole('button', { name: /confirm import/i }).click();
    await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });

    const ledger = await ledgerExpense(user.id);
    const counted = await countedExpense(user.id);

    expect(ledger, 'both fares should be on the ledger').toBe(90);
    expect(counted, 'and both should be counted — the user said so').toBe(ledger);

    // The surface the user actually reads, checked separately from the condition every service
    // shares, so a divergence between the two is visible rather than assumed away.
    const dashboard = await api.dashboard();
    expect(dashboard.data!.spendByCategory['Transport']).toBe(90);
  });

  /** The other direction. A skipped row must be absent from both, not merely uncounted. */
  test('a duplicate the user chose to skip is absent from the ledger, not hidden in it',
    async ({ userPage, api, user }) => {
      await api.importStatement([FARE], { accountName: 'Primary' });

      await userPage.goto('/app/import');
      await uploadStatement(userPage, 'fares.csv', 'text/csv', csv([FARE]));
      await expect(userPage.getByTestId('duplicate-review')).toBeVisible({ timeout: 20_000 });

      await userPage.getByTestId('duplicate-0').getByRole('button', { name: 'Skip this row' }).click();
      await userPage.getByRole('button', { name: /confirm import/i }).click();
      await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });

      expect(await transactionsFor(user.id, 'METRO FARE')).toHaveLength(1);
      expect(await ledgerExpense(user.id)).toBe(45);
      expect(await countedExpense(user.id), 'skipping means not imported, not imported-and-ignored')
        .toBe(45);
    });

  /**
   * A confirmed row must stay confirmed. Reconciliation runs after every import, create, edit and
   * delete — so a decision honoured only by the run that follows the import would be undone by the
   * user's next unrelated action, which is a worse failure because it appears later and for no
   * visible reason.
   */
  test('a later, unrelated import does not un-count an earlier decision', async ({ api, user }) => {
    const { summary } = await api.importStatement([FARE], { accountName: 'Primary' });
    const accountId = summary.account?.id;
    await api.importStatement([FARE], { accountId, confirmedNotDuplicate: true });

    expect(await countedExpense(user.id)).toBe(90);

    // Something else entirely, which triggers another reconciliation pass.
    await api.importStatement(
      [{ date: '2026-06-20', description: 'BLUE TOKAI COFFEE', amount: 320.0, type: 'DEBIT' }],
      { accountId }
    );

    expect(await countedExpense(user.id), 'the fares must still be counted').toBe(410);
    const fares = await transactionsFor(user.id, 'METRO FARE');
    expect(fares.every((t) => t.is_duplicate_of === null)).toBe(true);
  });
});

test.describe('Phase 8 — reconciliation respects the user, and only the user', () => {
  /**
   * The guard has to be narrow or it is not a fix, it is a hole. Someone who re-uploads the same
   * statement without deciding anything must still get duplicate detection.
   */
  test('an unconfirmed repeat is still flagged and still excluded', async ({ api, user }) => {
    const { summary } = await api.importStatement([FARE], { accountName: 'Primary' });
    const accountId = summary.account?.id;

    // A client that shows no review screen and carries no decision — the mobile app's shape.
    await api.importStatement([FARE], { accountId, confirmedNotDuplicate: false });

    const fares = await transactionsFor(user.id, 'METRO FARE');
    expect(fares).toHaveLength(2);
    expect(fares.filter((t) => t.is_duplicate_of !== null)).toHaveLength(1);
    expect(await ledgerExpense(user.id)).toBe(90);
    expect(await countedExpense(user.id), 'nobody vouched for the second one').toBe(45);
  });

  /**
   * A confirmed row stays in its duplicate group rather than being lifted out of it, so a third
   * copy nobody ruled on is still caught. Excluding confirmed rows from grouping entirely was the
   * simpler implementation and would have silently disabled detection for every later repeat —
   * which is precisely the kind of hole that only shows up on the third import.
   */
  test('a third, unruled copy is caught even next to a confirmed one', async ({ api, user }) => {
    const { summary } = await api.importStatement([FARE], { accountName: 'Primary' });
    const accountId = summary.account?.id;
    await api.importStatement([FARE], { accountId, confirmedNotDuplicate: true });
    await api.importStatement([FARE], { accountId, confirmedNotDuplicate: false });

    const fares = await transactionsFor(user.id, 'METRO FARE');
    expect(fares).toHaveLength(3);
    expect(fares.filter((t) => t.is_duplicate_of !== null), 'only the copy nobody ruled on')
      .toHaveLength(1);
    expect(fares.filter((t) => t.not_duplicate_confirmed_at !== null)).toHaveLength(1);

    // The confirmed one is never the one marked.
    for (const row of fares) {
      if (row.not_duplicate_confirmed_at) expect(row.is_duplicate_of).toBeNull();
    }
  });

  /** The earliest transaction in a group anchors it and is never itself marked — otherwise a
   *  re-import could orphan the original the user has been looking at for months. */
  test('the earliest transaction in a duplicate group is never the one marked', async ({ api, user }) => {
    const { summary } = await api.importStatement([FARE], { accountName: 'Primary' });
    const accountId = summary.account?.id;
    await api.importStatement([FARE], { accountId });

    const fares = await transactionsFor(user.id, 'METRO FARE'); // ordered by created_at
    expect(fares[0].is_duplicate_of).toBeNull();
    expect(fares[1].is_duplicate_of).toBe(fares[0].id);
    expect(fares[1].reconciliation_status).toBe('DUPLICATE');
  });

  /** A client that sends no decision field at all — every pre-WI5 client, and the mobile app,
   *  which has no duplicate review screen — must behave exactly as it did before. */
  test('a client that carries no decision is treated as having made none', async ({ api, user }) => {
    const { summary } = await api.importStatement([FARE], { accountName: 'Primary' });
    const accountId = summary.account?.id;
    const staged = await api.stage([FARE]);

    await api.post('/import/csv/confirm', {
      sessionId: staged.data!.sessionId,
      rows: staged.data!.staging.rows.map((r) => ({
        date: r.date, description: r.description, amount: r.amount, type: r.type,
        category: r.suggestedCategory, include: true, categorySource: r.categorySource,
        ruleId: r.ruleId, likelyDuplicate: r.likelyDuplicate,
        referenceNumber: r.referenceNumber, balanceAfter: r.balanceAfter,
        // confirmedNotDuplicate deliberately absent, not false.
      })),
      existingAccountId: accountId, newAccount: null,
      statementOpeningBalance: null, statementClosingBalance: null,
    });

    const fares = await transactionsFor(user.id, 'METRO FARE');
    expect(fares).toHaveLength(2);
    expect(fares.every((t) => t.not_duplicate_confirmed_at === null)).toBe(true);
    expect(fares.filter((t) => t.is_duplicate_of !== null)).toHaveLength(1);
  });
});
