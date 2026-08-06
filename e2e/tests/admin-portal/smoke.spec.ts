import { expect, test } from '@playwright/test';

/**
 * Admin portal smoke tests.
 *
 * Same constraint as the user portal's: no authenticated flows, because a real admin session
 * needs a backend, a database and a seeded admin. The unauthenticated surface is still worth
 * asserting in a real browser -- the redirect below is the portal's entire access-control
 * front door.
 */
test.describe('unauthenticated access', () => {
  test('the dashboard redirects an unauthenticated visitor to sign in', async ({ page }) => {
    await page.goto('/');

    // `/` is a ProtectedRoute wrapping Dashboard. Landing on the dashboard without a session
    // would be an access-control failure, not a cosmetic one.
    await expect(page).toHaveURL(/\/login/);
    await expect(page).toHaveTitle(/Finora Admin/);
  });

  test('the sign-in form exposes its fields by accessible name', async ({ page }) => {
    await page.goto('/login');

    // getByLabel resolves through the htmlFor/id association, so these three assertions fail if
    // the label-to-input wiring regresses. That is the exact defect this audit found on this
    // page -- and found only because the accessibility test that should have caught it was
    // scanning an empty container, having redirected away before the form rendered. A real
    // browser navigating to the real route cannot make that mistake.
    await expect(page.getByLabel('Email or phone')).toBeVisible();
    await expect(page.getByLabel('Password')).toBeVisible();
    await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
  });

  test('signing in with empty credentials does not navigate away', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: 'Sign in' }).click();

    // Required-field validation should hold the user on /login. This asserts the negative --
    // that a bad submit cannot slip past the form -- without depending on a backend to reject it.
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByLabel('Email or phone')).toBeVisible();
  });
});
