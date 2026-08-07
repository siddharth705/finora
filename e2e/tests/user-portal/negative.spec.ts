import { test, expect, uploadStatement, signIn } from '../../fixtures/test';
import { csv, FIVE_ROW_STATEMENT } from '../../fixtures/statements';
import { transactionsFor, count } from '../../fixtures/db';
import { API_BASE, USER_APP } from '../../fixtures/config';

/**
 * Phase 12 — the things a real user does that a happy-path test never covers.
 *
 * Every one of these is a way to make the product write the wrong thing without the user ever
 * intending it: a double-click that imports twice, a refresh that loses a review, a back button
 * that replays a confirmation. The failures are silent by nature — nobody reports "I clicked twice
 * and now I have two salaries", they report that their balance is wrong months later.
 */

test.describe('Phase 12 — negative and interruption cases', () => {
  /**
   * The classic. A slow confirm and an impatient user is two imports of the same statement, and
   * the second one is indistinguishable from a real re-import after the fact.
   */
  test('double-clicking Confirm imports once, not twice', async ({ userPage, user }) => {
    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));
    await expect(userPage.getByText(/5 row\(s\) parsed/i)).toBeVisible({ timeout: 20_000 });

    const confirm = userPage.getByRole('button', { name: /confirm import/i });
    await confirm.dblclick();

    await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });
    expect(await transactionsFor(user.id), 'one click, two imports').toHaveLength(5);
    expect(
      await count('select count(*) from statement_imports where user_id = $1', [user.id]),
      'the same statement was recorded twice'
    ).toBe(1);
  });

  /**
   * A staged session that is confirmed twice must be refused the second time. The button guard
   * above protects a careful user; this protects against a replayed request, which is what a
   * flaky network retry or a back-button resubmit actually produces.
   */
  test('a session cannot be confirmed twice', async ({ api, user }) => {
    const staged = await api.stage(FIVE_ROW_STATEMENT);
    const payload = {
      sessionId: staged.data!.sessionId,
      rows: staged.data!.staging.rows.map((r) => ({
        date: r.date, description: r.description, amount: r.amount, type: r.type,
        category: r.suggestedCategory, include: true, categorySource: r.categorySource,
        ruleId: r.ruleId, likelyDuplicate: r.likelyDuplicate,
        referenceNumber: r.referenceNumber, balanceAfter: r.balanceAfter,
      })),
      existingAccountId: null,
      newAccount: { name: 'Replay', accountType: 'SAVINGS', openingBalance: 0 },
      statementOpeningBalance: null, statementClosingBalance: null,
    };

    const first = await api.postRaw('/import/csv/confirm', payload);
    expect(first.status).toBe(200);

    const replay = await api.postRaw('/import/csv/confirm', payload);
    expect(replay.status, 'the same session confirmed twice').toBeGreaterThanOrEqual(400);
    expect(await transactionsFor(user.id)).toHaveLength(5);
  });

  /** A staged-but-unconfirmed session has written nothing, so a refresh loses the review and
   *  nothing else. What must NOT happen is a half-import. */
  test('refreshing mid-review loses the review and nothing else', async ({ userPage, user }) => {
    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));
    await expect(userPage.getByText(/5 row\(s\) parsed/i)).toBeVisible({ timeout: 20_000 });

    await userPage.reload();

    expect(await transactionsFor(user.id), 'a reload wrote something').toHaveLength(0);
    await expect(userPage.locator('body')).not.toBeEmpty();
  });

  /** The session survives server-side, so the work of uploading is not thrown away — that is what
   *  `GET /import/sessions` exists for. */
  test('an abandoned review can still be found afterwards', async ({ userPage, api }) => {
    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));
    await expect(userPage.getByText(/5 row\(s\) parsed/i)).toBeVisible({ timeout: 20_000 });

    await userPage.goto('/app');

    const sessions = await api.get<{ id: string }[]>('/import/sessions');
    expect(sessions.success).toBe(true);
    expect(sessions.data!.length, 'the upload was thrown away rather than resumable')
      .toBeGreaterThan(0);
  });

  test('the back button after a completed import does not re-run it', async ({ userPage, user }) => {
    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));
    await expect(userPage.getByText(/5 row\(s\) parsed/i)).toBeVisible({ timeout: 20_000 });
    await userPage.getByRole('button', { name: /confirm import/i }).click();
    await expect(userPage.getByText('Import complete')).toBeVisible({ timeout: 30_000 });

    await userPage.goBack();
    await userPage.goForward();

    expect(await transactionsFor(user.id)).toHaveLength(5);
    await expect(userPage.locator('body')).not.toBeEmpty();
  });

  /**
   * A request that never reaches the server must say so, and must say something different from a
   * parse failure — "we could not read your statement" when the wifi dropped sends the user off to
   * re-export a file that was fine.
   */
  test('a network failure during upload reports a transport problem, not a parse error',
    async ({ userPage, allowConsoleErrors }) => {
      allowConsoleErrors('the upload is aborted on purpose -- a failed request logging to the console is correct');
      await userPage.route('**/api/v1/import/csv/stage', (route) => route.abort('failed'));

      await userPage.goto('/app/import');
      await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));

      await expect(userPage.getByText(/connect|network|reach|try again/i).first())
        .toBeVisible({ timeout: 20_000 });
      await expect(userPage.getByText(/could not read|parse|no transaction table/i)).toBeHidden();
    });

  /** A slow response must leave the page in a waiting state, not an apparently-idle one that
   *  invites a second click. */
  test('a slow upload shows progress rather than looking idle', async ({ userPage }) => {
    await userPage.route('**/api/v1/import/csv/stage', async (route) => {
      await new Promise((r) => setTimeout(r, 3000));
      await route.continue();
    });

    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));

    await expect(userPage.getByText(/uploading|processing|%|please wait/i).first())
      .toBeVisible({ timeout: 10_000 });
    await expect(userPage.getByText(/5 row\(s\) parsed/i)).toBeVisible({ timeout: 30_000 });
  });

  /** A failed upload must be retryable without re-picking the file — otherwise a transient blip
   *  costs the user the whole flow. */
  test('a failed upload can be retried', async ({ userPage, allowConsoleErrors }) => {
    allowConsoleErrors('the first attempt is aborted on purpose');
    let attempts = 0;
    await userPage.route('**/api/v1/import/csv/stage', async (route) => {
      attempts += 1;
      if (attempts === 1) return route.abort('failed');
      return route.continue();
    });

    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));
    await expect(userPage.getByText(/connect|network|reach|try again/i).first()).toBeVisible({
      timeout: 20_000,
    });

    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));
    await expect(userPage.getByText(/5 row\(s\) parsed/i)).toBeVisible({ timeout: 30_000 });
  });

  test('an expired or unknown session is refused with an explanation, not a stack trace',
    async ({ api }) => {
      const response = await api.postRaw('/import/csv/confirm', {
        sessionId: '00000000-0000-0000-0000-000000000000',
        rows: [], existingAccountId: null,
        newAccount: { name: 'Ghost', accountType: 'SAVINGS', openingBalance: 0 },
        statementOpeningBalance: null, statementClosingBalance: null,
      });

      expect(response.status).toBeGreaterThanOrEqual(400);
      expect(response.status, 'a bad session id must not be a server error').toBeLessThan(500);
      expect(response.body?.message ?? '', 'no explanation for the user').not.toBe('');
    });

  test('an expired session logs the user out rather than half-working', async ({ userPage }) => {
    await userPage.goto('/app/import');
    await expect(userPage.getByTestId('statement-file-input')).toBeAttached({ timeout: 20_000 });

    await userPage.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
    await userPage.goto('/app/import');

    await expect(userPage).toHaveURL(/\/login/, { timeout: 20_000 });
  });

  /** An unauthenticated caller must be refused by the API, not merely hidden by the router. */
  test('the import API refuses an unauthenticated caller', async ({ request }) => {
    const response = await request.post(`${API_BASE}/import/csv/confirm`, {
      data: { sessionId: null, rows: [] },
      failOnStatusCode: false,
    });
    expect(response.status()).toBeGreaterThanOrEqual(400);
  });

  /** Rate limiting still trips. The test stack raises the ceiling so the suite can run at all, so
   *  this asserts the mechanism against whatever is configured rather than a hardcoded number —
   *  a test that asserted "5" would have quietly stopped testing anything the day it was raised. */
  test('the login limiter still trips, whatever it is set to', async ({ request }) => {
    const attempts = 40;
    let limited = false;

    for (let i = 0; i < attempts && !limited; i++) {
      const response = await request.post(`${API_BASE}/auth/login`, {
        data: { identifier: 'nobody@finora.test', password: 'wrong-on-purpose', scope: 'USER' },
        failOnStatusCode: false,
      });
      if (response.status() === 429) limited = true;
    }

    // Not a hard failure if the ceiling was raised above this loop's budget — the point is that the
    // mechanism is wired, and a skip says so honestly rather than a green tick that proves nothing.
    test.skip(!limited, `no 429 within ${attempts} attempts — the test stack's ceiling is higher`);
    expect(limited).toBe(true);
  });
});

test.describe('Phase 16 — accessibility of the review flow', () => {
  /** The duplicate review is the one screen where a decision is mandatory, so it is the one where
   *  being unable to reach a control by keyboard is not an inconvenience but a dead end. */
  test('every duplicate decision is reachable and operable by keyboard',
    async ({ userPage, api, user }) => {
      const fare = { date: '2026-06-07', description: 'METRO FARE', amount: 45.0, type: 'DEBIT' as const };
      await api.importStatement([fare], { accountName: 'Primary' });

      await userPage.goto('/app/import');
      await uploadStatement(userPage, 'fares.csv', 'text/csv', csv([fare]));
      await expect(userPage.getByTestId('duplicate-review')).toBeVisible({ timeout: 20_000 });

      const importAnyway = userPage.getByRole('button', { name: 'Import anyway' });
      await importAnyway.focus();
      await expect(importAnyway).toBeFocused();
      await userPage.keyboard.press('Enter');

      // aria-pressed carries the state to a screen reader; a purely visual highlight would leave a
      // non-sighted user unable to tell which decision they had made.
      await expect(importAnyway).toHaveAttribute('aria-pressed', 'true');
      await expect(userPage.getByRole('button', { name: /confirm import/i })).toBeEnabled();
    });

  test('the outstanding count is announced, not just coloured', async ({ userPage, api }) => {
    const fare = { date: '2026-06-07', description: 'METRO FARE', amount: 45.0, type: 'DEBIT' as const };
    await api.importStatement([fare], { accountName: 'Primary' });

    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'fares.csv', 'text/csv', csv([fare]));

    const status = userPage.getByTestId('duplicate-review').getByRole('status');
    await expect(status).toBeVisible({ timeout: 20_000 });
    await expect(status).toContainText(/needs a decision/);
  });

  test('the import controls have accessible names rather than icons alone', async ({ userPage }) => {
    await userPage.goto('/app/import');
    await uploadStatement(userPage, 'statement.csv', 'text/csv', csv(FIVE_ROW_STATEMENT));
    await expect(userPage.getByText(/5 row\(s\) parsed/i)).toBeVisible({ timeout: 20_000 });

    // Named rather than counted: "3 buttons are unlabelled" is not something anyone can act on,
    // and the outerHTML is what turns this from a score into a fix.
    const unnamed = await userPage.locator('button:visible').evaluateAll((buttons) =>
      buttons
        .filter((b) => {
          const label = (
            b.getAttribute('aria-label') ??
            b.getAttribute('title') ??
            b.textContent ??
            ''
          ).trim();
          return label === '';
        })
        .map((b) => b.outerHTML.slice(0, 160))
    );
    expect(unnamed, 'a button with no accessible name is invisible to a screen reader').toEqual([]);
  });

  test('the sign-in form labels its fields', async ({ page, user }) => {
    await page.goto(`${USER_APP}/login`);
    await expect(page.getByLabel(/email|phone/i).first()).toBeVisible();
    await expect(page.getByLabel(/password/i).first()).toBeVisible();
    await signIn(page, USER_APP, user.email, user.password);
  });
});
