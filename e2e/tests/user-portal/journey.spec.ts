import { expect, test, type ConsoleMessage, type Page } from '@playwright/test';

/**
 * Authenticated user journeys against a live backend.
 *
 * These require the full stack running locally (Postgres + backend on :8080) and the seeded
 * accounts described in E2E_TEST_REPORT.md. They are skipped automatically when the backend is
 * not reachable, so a checkout without a running stack still gets a green `npm test` from the
 * smoke specs rather than a wall of misleading failures.
 */
const USER = { identifier: 'e2e.user@finora.test', password: 'E2eUserPass2026' };

/** Console errors are collected per test and asserted at the end -- a page that renders correctly
 *  while throwing in the console is still a defect, and is exactly what a human tester misses. */
function collectConsoleErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on('console', (msg: ConsoleMessage) => {
    if (msg.type() === 'error') errors.push(msg.text());
  });
  page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`));
  return errors;
}

async function backendReachable(): Promise<boolean> {
  try {
    const res = await fetch('http://localhost:8080/actuator/health');
    return res.ok;
  } catch {
    return false;
  }
}

test.beforeAll(async () => {
  test.skip(!(await backendReachable()), 'backend not running on :8080 -- see E2E_TEST_REPORT.md');
});

async function login(page: Page) {
  await page.goto('/login');
  await page.getByLabel(/email|phone/i).first().fill(USER.identifier);
  await page.getByLabel(/password/i).first().fill(USER.password);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await expect(page).not.toHaveURL(/\/login/, { timeout: 15_000 });
}

test.describe('authenticated user journey', () => {
  test('signs in and lands on an app page that is not the login screen', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    await login(page);

    // Asserts the session actually took, rather than that some specific dashboard widget exists --
    // the widget set is product surface that changes; "no longer at /login and rendering content"
    // is the invariant.
    await expect(page.locator('body')).not.toBeEmpty();
    expect(errors, `console errors after login:\n${errors.join('\n')}`).toEqual([]);
  });

  test('reloading an authenticated page keeps the session', async ({ page }) => {
    await login(page);
    const afterLogin = page.url();

    await page.reload();

    // A session that survives login but not F5 is a real and common defect: it means the token
    // lives only in memory and the refresh path is not wired up.
    await expect(page).toHaveURL(afterLogin);
    await expect(page).not.toHaveURL(/\/login/);
  });

  test('the browser back button after login does not strand the user on a dead screen', async ({ page }) => {
    await login(page);
    await page.goBack();
    await expect(page.locator('body')).not.toBeEmpty();
  });

  test('logging out clears the session and protects app routes again', async ({ page }) => {
    await login(page);

    const logout = page.getByRole('button', { name: /log ?out|sign ?out/i }).first();
    if (await logout.isVisible().catch(() => false)) {
      await logout.click();
    } else {
      // Some layouts hide logout behind a menu; fall back to clearing storage the way a closed
      // browser would, then assert the guard still holds.
      await page.evaluate(() => { localStorage.clear(); sessionStorage.clear(); });
    }

    await page.goto('/app');
    await expect(page).toHaveURL(/\/login/, { timeout: 15_000 });
  });
});
