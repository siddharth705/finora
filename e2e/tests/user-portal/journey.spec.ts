import { test, expect, signIn, endSession } from '../../fixtures/test';
import { USER_APP } from '../../fixtures/config';
import type { Page } from '@playwright/test';

/**
 * Session behaviour: the things that have to hold for every authenticated screen, tested once here
 * rather than repeated in each feature spec.
 *
 * Previously these signed in as a hardcoded `e2e.user@finora.test` that existed only because
 * someone had once created it by hand in a developer's database. That is exactly the fixture this
 * suite's seeding exists to remove: the tests passed on the machine where the account happened to
 * exist and failed everywhere else, including against the fresh database the milestone brief asks
 * for. They now seed their own account like every other spec.
 */

test.describe('authenticated session', () => {
  test('signing in lands on an app page that is not the login screen', async ({ userPage }) => {
    // Asserts the session actually took, rather than that some specific dashboard widget exists --
    // the widget set is product surface that changes; "no longer at /auth and rendering content"
    // is the invariant. The Phase 17 guard on this fixture covers the console.
    await expect(userPage).not.toHaveURL(/\/auth/);
    await expect(userPage.locator('body')).not.toBeEmpty();
  });

  /** A session that survives login but not F5 is a real and common defect: it means the token lives
   *  only in memory and the refresh path is not wired up. */
  test('reloading an authenticated page keeps the session', async ({ userPage }) => {
    await userPage.goto('/app/import');
    const before = userPage.url();

    await userPage.reload();

    await expect(userPage).toHaveURL(before);
    await expect(userPage).not.toHaveURL(/\/auth/);
  });

  test('the back button after signing in does not strand the user on a dead screen',
    async ({ userPage }) => {
      await userPage.goto('/app/import');
      await userPage.goBack();

      await expect(userPage.locator('body')).not.toBeEmpty();
    });

  /**
   * Signing out has to take the session with it. The failure this guards against is a client that
   * clears its own state and calls it done — the user looks logged out while the token stays valid
   * server-side, which is worse than not offering logout at all.
   */
  test('signing out clears the session and protects app routes again',
    async ({ userPage, allowConsoleErrors }) => {
      allowConsoleErrors('the point is that requests start failing once the session is gone');
      const logout = userPage.getByRole('button', { name: /log ?out|sign ?out/i }).first();
      if (await logout.isVisible().catch(() => false)) {
        await logout.click();
      } else {
        // Some layouts keep logout behind a menu. Since SEC-01 (#187) the session that matters is
        // an HttpOnly refresh cookie, not anything in storage -- closing the browser no longer
        // ends it (that's the point: a returning user's session survives a reload), so the guard
        // needs the session actually revoked here, the same way the real logout button does.
        await endSession(userPage);
      }

      // waitUntil 'commit' rather than the default: the app redirects to /auth mid-navigation, and
      // waiting for load on a request that gets aborted by that redirect is an ERR_ABORTED, not a
      // failure of the thing under test.
      await userPage.goto('/app', { waitUntil: 'commit' }).catch(() => {});
      await expect(userPage).toHaveURL(/\/auth/, { timeout: 20_000 });
    });

  /** Two accounts in one browser must not bleed into each other. Sequential sign-ins are the shape
   *  a shared machine produces, and a leftover token from the first would show the second user
   *  someone else's ledger. */
  test('signing in as a second account does not inherit the first session',
    async ({ page, user }) => {
      await signIn(page, USER_APP, user.email, user.password);
      // Since SEC-01 (#187) the session lives in an HttpOnly refresh cookie, not storage -- see
      // endSession's own comment. Ending it for real is what leaves the browser in the state a
      // second person signing in would actually find it in.
      await endSession(page);

      // 'commit' rather than the default: the guard redirects mid-navigation, and waiting for load
      // on the request that redirect aborts is an ERR_ABORTED rather than a failure of the guard.
      await page.goto(`${USER_APP}/app`, { waitUntil: 'commit' }).catch(() => {});
      await expect(page).toHaveURL(/\/auth/, { timeout: 20_000 });
    });
});

test.describe('public surface', () => {
  const visit = async (page: Page, path: string) => {
    await page.goto(`${USER_APP}${path}`);
    await expect(page.locator('body')).not.toBeEmpty();
  };

  test('the landing page renders and offers a route to register', async ({ page }) => {
    await visit(page, '/');
    await expect(page.getByRole('link', { name: /get started|register|sign up/i }).first())
      .toBeVisible();
  });

  /**
   * `rel="noopener"` on every link that opens a new tab. Without it the opened page gets a handle
   * on `window.opener` and can navigate this one somewhere else — a phishing vector that costs one
   * attribute to close, and one a jsdom component test cannot see.
   */
  test('new-tab links on the register step cannot reach back into this one', async ({ page }) => {
    // /register redirects to /auth's identify step; an identifier with no account reaches the
    // register step, which is where these Terms/Privacy links actually live now.
    await visit(page, '/register');
    await page.getByLabel(/email|phone/i).first().fill(`new-${Date.now()}@example.com`);
    await page.getByRole('button', { name: /continue/i }).click();

    // Waits for a field only the register step has before counting: count() doesn't auto-wait,
    // so right after the click resolves the render can still be in flight, sampling an empty DOM
    // and counting zero links -- see smoke.spec.ts's identical fix for the same race.
    await expect(page.getByLabel(/full name/i)).toBeVisible();

    const newTabLinks = page.locator('a[target="_blank"]');
    const total = await newTabLinks.count();
    test.skip(total === 0, 'no new-tab links on this page');

    for (let i = 0; i < total; i++) {
      const rel = (await newTabLinks.nth(i).getAttribute('rel')) ?? '';
      const href = await newTabLinks.nth(i).getAttribute('href');
      expect(rel, `target=_blank link to ${href} has no rel="noopener"`).toMatch(/noopener/);
    }
  });

  test('an unknown route does not blank the page', async ({ page }) => {
    await visit(page, '/this-route-does-not-exist');
    await expect(page.locator('body')).not.toBeEmpty();
  });

  test('an app route is refused to someone who has not signed in', async ({ page }) => {
    await page.goto(`${USER_APP}/app`);
    await expect(page).toHaveURL(/\/auth/, { timeout: 20_000 });
  });
});
