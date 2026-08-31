import { test, expect } from '../../fixtures/test';
import { listZipEntryNames } from '../../fixtures/zip';
import fs from 'node:fs';

/**
 * The self-service account-lifecycle surface -- export, deactivate, delete -- had no e2e coverage
 * at all before this file (found while checking whether goal_contributions.json, a new file added
 * to the "Download My Data" ZIP, flows through the frontend correctly). Neither the PR-gating
 * smoke suite nor the nightly full suite touched any of these three flows.
 *
 * Delete Account only gets partial coverage here, on purpose. Its final step needs a real Firebase
 * phone-OTP round trip (DeleteAccountModal -> lib/phoneAuth.ts -> Firebase Auth), and this suite's
 * environment has no VITE_FIREBASE_* configured (see ci.yml and frontend/.env.example) -- the same
 * root cause fixtures/accounts.ts already documents for why createUser() fakes phone_verified via
 * SQL instead of driving registration's own OTP step through the UI. What IS tested is everything
 * up to that boundary: the current-password check genuinely reaches the backend and succeeds,
 * distinguishing "blocked by the environment" from "broken."
 */

test.describe('account lifecycle', () => {
  test('Export My Data downloads a ZIP containing every file DataExportService promises, including goal_contributions.json', async ({ userPage, user }) => {
    await userPage.goto('/app/settings');
    await userPage.getByRole('button', { name: 'Export My Data' }).click();

    const modal = userPage.getByTestId('export-data-modal');
    await expect(modal).toBeVisible();
    await modal.getByLabel('Current password').fill(user.password);

    const [download] = await Promise.all([
      userPage.waitForEvent('download'),
      modal.getByRole('button', { name: 'Export My Data' }).click(),
    ]);

    expect(download.suggestedFilename(), 'export filename should match fynora-data-export-<date>.zip')
      .toMatch(/^fynora-data-export-\d{4}-\d{2}-\d{2}\.zip$/);

    const downloadPath = await download.path();
    if (!downloadPath) throw new Error('Playwright reported no local path for the downloaded export.');
    const zip = fs.readFileSync(downloadPath);

    expect(zip.subarray(0, 4), 'downloaded file does not start with the ZIP local-file-header magic bytes')
      .toEqual(Buffer.from([0x50, 0x4b, 0x03, 0x04]));

    const entries = listZipEntryNames(zip);
    // Everything writeZip() writes unconditionally, regardless of how empty this fresh user's
    // ledger is -- a fresh user still has zero statements, so nothing under statements/ is
    // asserted here. goal_contributions.json is the file this test exists to prove made it
    // through the browser round-trip, not just DataExportServiceTest's own mocked unit test.
    expect(entries).toEqual(expect.arrayContaining([
      'manifest.json', 'README.txt', 'accounts.json', 'transactions.json', 'budgets.json',
      'goals.json', 'goal_contributions.json', 'categories.json', 'category_rules.json',
      'relationships.json', 'net_worth_history.json', 'merchants.json', 'import_jobs.json',
      'import_sessions.json', 'statements.json', 'gmail_connection.json',
      'account_settings.json', 'workspace_settings.json',
    ]));

    // ExportDataModal.submitWithCredential() calls onClose() on success -- no separate success
    // screen, the modal just goes away.
    await expect(modal).toBeHidden();
  });

  test('Deactivate Account signs the user out immediately, and a plain sign-in afterward offers reactivation instead of the app', async ({ userPage, user, allowConsoleErrors }) => {
    // The next sign-in attempt is deliberately made against a now-deactivated account, to prove
    // deactivation happened server-side, not just client-side. AuthService.enforceAccountIsSignable
    // answers that POST /auth/login with a 403 -- the right response, surfaced to the user as the
    // reactivation prompt rather than a raw error -- but Chrome logs any failed resource load to
    // the console regardless of how gracefully the app's own JS handles it (see watch()'s identical
    // reasoning for the unconditionally-expected /auth/refresh 401 on every fresh page load).
    allowConsoleErrors('POST /auth/login expectedly 403s when this test signs back in as a still-deactivated account.');

    await userPage.goto('/app/settings');
    await userPage.getByRole('button', { name: 'Deactivate Account' }).click();

    const modal = userPage.getByTestId('deactivate-account-modal');
    await modal.getByLabel('Current password').fill(user.password);
    await modal.getByLabel('Reason').selectOption('TAKING_A_BREAK');
    await modal.getByRole('button', { name: 'Deactivate Account' }).click();

    await expect(userPage, 'deactivating did not redirect to /auth').toHaveURL(/\/auth/, { timeout: 20_000 });
    // No assertion on the "account has been deactivated" reason here -- see the test.fixme below
    // this describe block for why it doesn't render on the screen this redirect actually lands on.

    // Proves the deactivation actually happened server-side (AuthService.enforceAccountIsSignable)
    // rather than only clearing this browser's own session -- a normal sign-in with the same,
    // still-correct password must be intercepted before it reaches the app.
    await userPage.getByLabel(/email|phone/i).first().fill(user.email);
    await userPage.getByRole('button', { name: /continue/i }).click();
    await userPage.getByLabel(/password/i).first().fill(user.password);
    await userPage.getByRole('button', { name: /sign in|log in/i }).click();

    await expect(userPage.getByRole('heading', { name: 'Welcome back' })).toBeVisible();
    await expect(userPage, 'a deactivated account should not have reached the app').toHaveURL(/\/auth/);

    // Closes the loop: reactivating is the whole point of the self-service window this account
    // is still inside (app.account-lifecycle.reactivation-window-enabled is off by default in
    // this suite's backend config, which AuthService.selfServiceReactivationWindowHasClosed
    // treats as "never closes").
    await userPage.getByRole('button', { name: 'Reactivate my account' }).click();
    await expect(userPage, 'reactivating should return the user to the app').not.toHaveURL(/\/auth/, { timeout: 20_000 });
  });

  /**
   * Real gap, not a stale assertion. clearSessionAndRedirect (api/client.ts) stashes the "your
   * account has been deactivated" reason and sends the browser to /auth -- but since #410 unified
   * login into a two-step identify -> password flow, that reason is only ever read by
   * PasswordStep.tsx. IdentifyStep.tsx, which is what this redirect actually lands on, never reads
   * it, so a user who was just signed out sees a blank sign-in screen with no explanation until
   * they've re-entered their email and reached the password step (or never, if they don't get that
   * far). The same gap applies to every other clearSessionAndRedirect caller, including plain
   * session expiry -- deactivation is just the one with a test that already looks for it.
   *
   * Left as a product decision rather than fixed here: does the reason belong on IdentifyStep too,
   * or does clearSessionAndRedirect need to route through the password step some other way? Once
   * settled, this becomes a real assertion again (see the removed line above the reactivation
   * flow in the previous test).
   */
  test.fixme(
    'a session-ended reason is visible on /auth immediately after the redirect, not only after re-entering an identifier',
    async () => {});

  test('Delete Account verifies the current password against the backend before the phone-OTP step this suite cannot complete', async ({ userPage, user, allowConsoleErrors }) => {
    // DeleteAccountModal.startWithCredential logs the Firebase-unconfigured failure via
    // console.error before setting its own error state -- expected in this environment (see this
    // file's own top-of-file comment), not a defect this test is provoking to prove something else.
    allowConsoleErrors('Firebase is not configured in this suite -- DeleteAccountModal logs that failure to the console by design.');

    await userPage.goto('/app/settings');
    await userPage.getByRole('button', { name: 'Delete Account' }).click();

    const modal = userPage.getByTestId('delete-account-modal');
    await modal.getByLabel('Current password').fill(user.password);
    await modal.getByRole('button', { name: 'Send code' }).click();

    // passwordChangeApi.start() genuinely succeeded against the real backend here -- if it
    // hadn't, the error shown would be the backend's own message (e.g. "Current password is
    // incorrect."), not this fallback, which DeleteAccountModal.startWithCredential only shows
    // when sendPhoneVerificationCode() throws its own plain Error (Firebase unconfigured has no
    // err.response, so the ?? fallback is what renders). This is the environment boundary, not a
    // product defect -- see this file's own top-of-file comment.
    await expect(modal.getByText('Could not start account deletion. Please try again.')).toBeVisible();
    // Never advanced past the password step -- proves the failure happened where expected, not
    // that the OTP screen rendered and then broke some other way.
    await expect(modal.getByLabel('Current password')).toBeVisible();
  });
});
