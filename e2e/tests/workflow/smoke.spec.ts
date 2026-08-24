import { test, expect, uploadStatement } from '../../fixtures/test';
import { csv, corruptedPdf, type Row } from '../../fixtures/statements';
import { waitForLearningToSettle } from '../../fixtures/api';
import {
  countedExpense, ledgerExpense, learningEventsFor, merchantsFor, transactionsFor, query,
} from '../../fixtures/db';
import { ADMIN_APP } from '../../fixtures/config';

/**
 * The build-confidence suite: one pass through the whole product, under five minutes, for every PR.
 *
 * Deliberately NOT a subset of the other specs by tag. A tag filter looks cheaper but drifts the
 * moment someone retags, and it makes the fast suite's cost invisible — nobody notices a five-minute
 * gate becoming a fifteen-minute one until they are waiting on it. This file's budget is its own,
 * visible, and asserted at the end.
 *
 * What belongs here: a single journey through each system that would make the product unusable if
 * broken. What does NOT belong here: edge cases, error paths, permutations, anything that measures
 * behaviour at size. Those are the full suite's job and it runs nightly.
 *
 * The rule for adding a test: if it fails, would you stop the release? If not, it belongs in the
 * full suite.
 */

const STATEMENT: Row[] = [
  { date: '2026-06-03', description: 'SWIGGY ORDER 4471', amount: 486.0, type: 'DEBIT' },
  { date: '2026-06-05', description: 'UBER TRIP 8891', amount: 240.0, type: 'DEBIT' },
  { date: '2026-06-07', description: 'METRO FARE', amount: 45.0, type: 'DEBIT' },
];

// Serial: one continuous journey reads better as a sequence than as eight independent tests that
// each rebuild the same state, and it keeps the whole file inside its time budget.
test.describe.configure({ mode: 'serial' });

test.describe('@smoke — the product works end to end', () => {
  const startedAt = Date.now();

  test('1. a user can sign in', async ({ userPage }) => {
    // The userPage fixture signs in and asserts it left /auth; this makes that a named step rather
    // than a precondition buried in a fixture, because "nobody can log in" is the single failure
    // that makes every other result meaningless.
    await expect(userPage).not.toHaveURL(/\/auth/);
    await expect(userPage.locator('body')).not.toBeEmpty();
  });

  test('2. a CSV statement imports', async ({ userPage, user }) => {
    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(STATEMENT));

    await expect(userPage.getByText(/3 row\(s\) parsed/i)).toBeVisible({ timeout: 30_000 });
    await userPage.getByRole('button', { name: /confirm import/i }).click();
    await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });

    expect(await transactionsFor(user.id)).toHaveLength(3);
  });

  /** Not a happy path, but the PDF path's cheapest proof of life: the file reaches the extractor
   *  and comes back with an actionable answer rather than a 500. A valid-PDF import needs a fixture
   *  we do not have, and inventing one here would be slower than it is worth. */
  test('3. the PDF path answers, and answers usefully', async ({ userPage, allowConsoleErrors }) => {
    allowConsoleErrors('a rejected upload logs its 422, which is correct');
    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.pdf', 'application/pdf', corruptedPdf());

    await expect(userPage.getByText(/damaged or incomplete/i)).toBeVisible({ timeout: 30_000 });
    await expect(userPage.getByText(/root object|trailer|Unexpected error/i)).toHaveCount(0);
  });

  test('4. a duplicate blocks the import until the user decides, then honours the decision',
    async ({ userPage, api, user }) => {
      const { summary } = await api.importStatement(STATEMENT, { accountName: 'Smoke' });

      await userPage.goto('/app/import');
      await uploadStatement(userPage, 'again.csv', 'text/csv', csv(STATEMENT));
      await expect(userPage.getByTestId('duplicate-review')).toBeVisible({ timeout: 30_000 });
      await expect(userPage.getByRole('button', { name: /confirm import/i })).toBeDisabled();

      for (let i = 0; i < STATEMENT.length; i++) {
        await userPage.getByTestId(`duplicate-${i}`).getByRole('button', { name: 'Import anyway' }).click();
      }
      await userPage.getByRole('button', { name: /confirm import/i }).click();
      await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });

      expect(summary.imported).toBe(3);
      expect(await transactionsFor(user.id)).toHaveLength(6);
    });

  /** The regression that cost this milestone a defect: what the ledger holds and what the product
   *  counts are the same set. If one number in this suite is worth keeping, it is this one. */
  test('5. the dashboard counts everything the ledger holds', async ({ api, user }) => {
    const { summary } = await api.importStatement(STATEMENT, { accountName: 'Smoke' });
    await api.importStatement(STATEMENT, { accountId: summary.account?.id, confirmedNotDuplicate: true });

    const ledger = await ledgerExpense(user.id);
    expect(ledger).toBe(1542);
    expect(await countedExpense(user.id), 'the ledger and the dashboard disagree').toBe(ledger);

    const dashboard = await api.dashboard();
    expect(dashboard.data!.spendByCategory['Transport']).toBe(570);
  });

  test('6. learning is applied after the import, without blocking it', async ({ api, user }) => {
    const { summary } = await api.importStatement(STATEMENT, { accountName: 'Smoke' });
    expect(summary.imported, 'the import completed before learning was applied').toBe(3);

    const events = await waitForLearningToSettle(user.id, learningEventsFor);
    expect(events.every((e) => e.status === 'COMPLETED'), 'the learning queue did not drain').toBe(true);
  });

  test('7. an operator can see the queue and the merchants awaiting review',
    async ({ adminPage, api, user }) => {
      await api.importStatement(STATEMENT, { accountName: 'Smoke' });
      await waitForLearningToSettle(user.id, learningEventsFor);

      // Absolute, not relative. This spec lives in tests/workflow/, whose project baseURL is the
      // USER app -- so a relative goto on the admin page lands on the marketing site and every
      // assertion then fails describing a page nobody was looking for. The admin-portal specs get
      // away with relative paths because their project's baseURL is the admin app; this one cannot.
      await adminPage.goto(`${ADMIN_APP}/learning-queue`);
      await expect(adminPage.getByRole('heading', { name: /learning queue/i })).toBeVisible({ timeout: 60_000 });
      // Either reassurance or rows -- what must not happen is neither, which is a screen that failed
      // to load and said nothing.
      await expect
        .poll(async () =>
          (await adminPage.getByText(/nothing has failed|queue is healthy/i).isVisible().catch(() => false))
          || (await adminPage.getByRole('row').count()) > 1,
          { timeout: 20_000 })
        .toBe(true);

      // Backdated so the seeded account sorts to page one -- the list is oldest-first and has no
      // search. See reviewRowFor in the merchant-review spec for the full reasoning.
      await query(`update merchants set created_at = now() - interval '10 years' where user_id = $1`, [user.id]);
      await adminPage.goto(`${ADMIN_APP}/merchant-review`);
      await expect(adminPage.getByRole('row').filter({ hasText: user.email }).first())
        .toBeVisible({ timeout: 20_000 });

      expect(await merchantsFor(user.id)).not.toHaveLength(0);
    });

  test('8. signing out takes the session with it', async ({ userPage, allowConsoleErrors }) => {
    allowConsoleErrors('requests start failing once the session is gone, which is the point');

    // "Log out" lives inside the Sidebar's account menu (Sidebar.tsx), closed by default --
    // open it before the button is clickable. Under SEC-01 the access token is an in-memory
    // module variable and the refresh token is an HttpOnly cookie, so an `evaluate` fallback
    // that only clears localStorage/sessionStorage wouldn't actually end the session.
    await userPage.getByRole('button', { name: 'Account menu' }).click();
    await userPage.getByRole('button', { name: /log ?out|sign ?out/i }).first().click();

    await userPage.goto('/app', { waitUntil: 'commit' }).catch(() => {});
    await expect(userPage).toHaveURL(/\/auth/, { timeout: 20_000 });
  });

  /**
   * The budget, asserted rather than intended.
   *
   * A gate that runs on every PR only stays useful while it is fast, and "keep it under five
   * minutes" written in a README is a wish. This fails the suite when it stops being a smoke test,
   * which is the point at which someone should either move a test to the full suite or make a
   * deliberate decision to spend longer.
   *
   * Deliberately generous relative to the target: this measures wall-clock on whatever machine it
   * runs on, and a CI runner under load is slower than a laptop without being broken.
   */
  test('stays within its budget', async () => {
    const elapsedSeconds = Math.round((Date.now() - startedAt) / 1000);
    console.log(`[smoke] completed in ${elapsedSeconds}s`);
    expect(
      elapsedSeconds,
      'the smoke suite has stopped being fast. Move something to the full suite, or decide ' +
      'deliberately to spend longer here -- do not just raise this number.'
    ).toBeLessThan(300);
  });
});
