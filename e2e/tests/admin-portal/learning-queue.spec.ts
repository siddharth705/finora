import { test, expect } from '../../fixtures/test';
import { Api, waitForLearningToSettle } from '../../fixtures/api';
import { createUser } from '../../fixtures/accounts';
import { learningEventsFor, query } from '../../fixtures/db';
import type { Row } from '../../fixtures/statements';

/**
 * Phase 5 — the Merchant Learning Queue (WI2).
 *
 * This screen exists because WI1 moved learning out of the import transaction. That was the right
 * trade — a learning failure must never cost a user their import — but it creates an obligation:
 * something that fails silently in the background has to be visible to somebody. The queue is that
 * somebody's screen.
 *
 * So the tests here are about whether an operator can *act*, not whether a table renders. A queue
 * that lists failures without saying which import produced them, or which user is affected, is a
 * list of problems nobody can do anything about.
 */

const LEARNABLE: Row[] = [
  { date: '2026-06-03', description: 'SWIGGY ORDER 4471', amount: 486.0, type: 'DEBIT' },
  { date: '2026-06-05', description: 'UBER TRIP 8891', amount: 240.0, type: 'DEBIT' },
];

/**
 * Forces an event into FAILED without breaking the worker, so the operator-facing states can be
 * driven deterministically. Provoking a real failure would mean sabotaging a shared backend, and
 * the screen under test cannot tell the difference.
 *
 * @param attemptCount deliberately below MAX_ATTEMPTS by default. `retryable` is computed by the
 *        server from the retry state, not re-derived in the UI, so an event that has exhausted its
 *        five attempts renders NO per-row Retry button — correctly, because retrying it would just
 *        fail again. A fixture that maxed the counter would therefore be testing the absence of a
 *        button while claiming to test the button.
 */
async function markFailed(userId: string, attemptCount = 2) {
  await query(
    `update merchant_learning_events
        set status = 'FAILED', attempt_count = $2, last_error = 'Simulated: constraint violation',
            first_failed_at = now(), last_retry_at = now()
      where user_id = $1`,
    [userId, attemptCount]
  );
}

/**
 * Serial, and deliberately so.
 *
 * The Learning Queue is a PLATFORM-WIDE view — that is what makes it useful to an operator — so two
 * tests running at once see each other's rows. One test forcing an event to FAILED makes a
 * neighbour's "the queue is healthy" assertion false, and the failure lands on the innocent test.
 * Running these in sequence is the honest fix; scoping the assertions to one user would test
 * something the screen does not actually do.
 */
test.describe.configure({ mode: 'serial' });

test.describe('Phase 5 — learning queue', () => {
  test('shows a healthy queue as healthy, rather than as an empty table', async ({ adminPage }) => {
    await adminPage.goto('/learning-queue');

    await expect(adminPage.getByRole('heading', { name: /learning queue/i })).toBeVisible();

    // The distinction matters: "nothing has failed" and "nothing loaded" look identical in a bare
    // empty table, and an operator has no way to tell which one they are looking at. Either the
    // reassurance or a populated table is acceptable -- what is not acceptable is neither, which is
    // the shape of a screen that failed to load and said nothing.
    const reassurance = adminPage.getByText(/nothing has failed|queue is healthy/i);
    const rows = adminPage.getByRole('row');
    await expect
      .poll(async () => (await reassurance.isVisible().catch(() => false)) || (await rows.count()) > 1,
        { timeout: 20_000, message: 'the queue rendered neither rows nor an explanation of their absence' })
      .toBe(true);
  });

  test('says plainly that a failure here never cost anyone their import', async ({ adminPage }) => {
    await adminPage.goto('/learning-queue');

    // The single most important thing an operator can know when they open this screen at 2am.
    await expect(adminPage.getByText(/never blocked an import|already imported/i)).toBeVisible({
      timeout: 20_000,
    });
  });

  test('counts every status, so an operator sees the shape of the queue at a glance',
    async ({ adminPage, api, user }) => {
      await api.importStatement(LEARNABLE, { accountName: 'Primary' });
      await waitForLearningToSettle(user.id, learningEventsFor);

      await adminPage.goto('/learning-queue');
      for (const status of ['FAILED', 'PENDING', 'PROCESSING', 'COMPLETED', 'RESOLVED']) {
        await expect(adminPage.getByText(status, { exact: false }).first()).toBeVisible({
          timeout: 20_000,
        });
      }
    });

  /**
   * The correlation chain the milestone specifically added (and that the design doc's decision
   * required: "Do not invent synthetic session IDs"). Without it, a failed event names a merchant
   * and a category and nothing an operator can trace back to a file.
   */
  test('a queue row can be traced back to the import that produced it',
    async ({ adminPage, api, user }) => {
      await api.importStatement(LEARNABLE, { accountName: 'Primary', fileName: 'june-statement.csv' });
      await waitForLearningToSettle(user.id, learningEventsFor);

      await adminPage.goto('/learning-queue');
      await adminPage.getByRole('button', { name: /^all$/i }).click();

      const row = adminPage.getByRole('row').filter({ hasText: user.email }).first();
      await expect(row).toBeVisible({ timeout: 20_000 });
      await row.getByRole('button', { name: /details/i }).click();

      // Everything needed to answer "which import produced this, and for whom". Asserted against
      // the drawer's own labels rather than a container element, because the drawer's DOM shape is
      // presentation and these labels are the contract.
      await expect(adminPage.getByText(/event detail/i)).toBeVisible();
      await expect(adminPage.getByText('june-statement.csv')).toBeVisible();
      await expect(adminPage.getByText(user.email).first()).toBeVisible();
      for (const label of ['Merchant', 'Category', 'Affected user', 'Statement', 'Import session']) {
        await expect(adminPage.getByText(label, { exact: true }).first(),
          `the detail drawer does not say "${label}", so the row is a dead end`).toBeVisible();
      }
    });

  test('a failed event is retryable, and retrying it clears the failure',
    async ({ adminPage, api, user }) => {
      await api.importStatement(LEARNABLE, { accountName: 'Primary' });
      await waitForLearningToSettle(user.id, learningEventsFor);
      await markFailed(user.id);

      await adminPage.goto('/learning-queue');
      const row = adminPage.getByRole('row').filter({ hasText: user.email }).first();
      await expect(row).toBeVisible({ timeout: 20_000 });
      await expect(row).toContainText('FAILED');
      // The error is shown, not just the fact of failure -- "it failed" is not something an
      // operator can act on.
      await expect(row.or(adminPage.getByText(/constraint violation/i)).first()).toBeVisible();

      const failedBefore = (await learningEventsFor(user.id)).filter((e) => e.status === 'FAILED');
      expect(failedBefore.length, 'the fixture should have failed both events').toBe(2);

      await row.getByRole('button', { name: /^retry$/i }).first().click();

      // ONE row's Retry clears ONE event. Asserting "nothing is FAILED any more" would be asserting
      // that a single-row action drained the queue, which is not what the button claims to do and
      // is not what an operator would want it to do.
      await expect
        .poll(async () => (await learningEventsFor(user.id)).filter((e) => e.status === 'FAILED').length, {
          timeout: 20_000,
          message: 'clicking Retry on one row cleared no event',
        })
        .toBe(1);
    });

  test('offers a bulk retry, because a systemic failure produces many identical rows',
    async ({ adminPage, api, user }) => {
      await api.importStatement(LEARNABLE, { accountName: 'Primary' });
      await waitForLearningToSettle(user.id, learningEventsFor);
      await markFailed(user.id);

      await adminPage.goto('/learning-queue');
      const retryAll = adminPage.getByRole('button', { name: /retry all/i });
      await expect(retryAll).toBeVisible({ timeout: 20_000 });
      await retryAll.click();

      await expect
        .poll(async () => (await learningEventsFor(user.id)).filter((e) => e.status === 'FAILED').length, {
          timeout: 20_000,
        })
        .toBe(0);
    });

  /**
   * An event that has used all five attempts is STILL manually retryable, and that is right.
   *
   * I assumed the opposite when writing this and the product corrected me. Automatic retry stopping
   * at the cap and manual retry remaining available are different rules for different actors: the
   * cap exists so a broken event does not spin forever unattended, while the whole reason an
   * operator is looking at this screen is that they have just fixed the cause. Refusing them the
   * button would leave the only person who can help with nothing to click.
   *
   * `retryable` is `status == FAILED` on the server and the UI does not re-derive it, so the two
   * cannot drift.
   */
  test('an exhausted event can still be retried by hand, because a human may have fixed the cause',
    async ({ adminPage, api, user }) => {
      await api.importStatement(LEARNABLE, { accountName: 'Primary' });
      await waitForLearningToSettle(user.id, learningEventsFor);
      await markFailed(user.id, 5);

      await adminPage.goto('/learning-queue');
      const row = adminPage.getByRole('row').filter({ hasText: user.email }).first();
      await expect(row).toBeVisible({ timeout: 20_000 });
      await expect(row, 'the attempt count is shown, so the operator knows it is exhausted')
        .toContainText('5/5');

      const retry = row.getByRole('button', { name: /^retry$/i });
      await expect(retry, 'an exhausted event left the only person who can help with nothing to click')
        .toHaveCount(1);

      await retry.click();
      await expect
        .poll(async () => (await learningEventsFor(user.id)).filter((e) => e.status === 'FAILED').length,
          { timeout: 20_000, message: 'the manual retry did nothing' })
        .toBe(1);
    });

  /** The queue defaults to failures because that is the only thing needing action; the ability to
   *  see everything is a deliberate second step, not the landing state. */
  test('defaults to what needs attention and can be widened on demand',
    async ({ adminPage, api, user }) => {
      await api.importStatement(LEARNABLE, { accountName: 'Primary' });
      await waitForLearningToSettle(user.id, learningEventsFor);

      await adminPage.goto('/learning-queue');

      // The landing state is failures-only, so a COMPLETED event is NOT listed until the filter is
      // widened. That is the assertion -- not that the table is empty, which depends on what every
      // other account on the platform happens to be doing.
      await expect(adminPage.getByRole('row').filter({ hasText: user.email })).toHaveCount(0);

      await adminPage.getByRole('button', { name: /^all$/i }).click();
      await expect(adminPage.getByRole('row').filter({ hasText: user.email }).first()).toBeVisible();
    });

  /** A detail lookup that only searched the current page 404s past page one — the exact defect this
   *  milestone already fixed once. Worth a test, because it reappears the moment anyone reintroduces
   *  a page-scoped find. */
  test('a row on a later page can still be opened', async ({ adminPage, adminApi }) => {
    // Enough events to guarantee more than one page at the default size.
    for (let i = 0; i < 3; i++) {
      const extra = await createUser(`queue-page-${i}`);
      await new Api(extra.token).importStatement(LEARNABLE, { accountName: 'Primary' });
      await waitForLearningToSettle(extra.id, learningEventsFor);
    }

    const page2 = await adminApi.get<{ content: { id: string }[]; totalElements: number }>(
      '/admin/learning-queue?page=1&size=2'
    );
    expect(page2.success).toBe(true);
    if (page2.data!.content.length === 0) test.skip(true, 'not enough events for a second page');

    const single = await adminApi.get(`/admin/learning-queue/${page2.data!.content[0].id}`);
    expect(single.success, 'a row visible in the list could not be opened').toBe(true);
  });
});
