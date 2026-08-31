import { test, expect } from '../../fixtures/test';
import type { Page } from '@playwright/test';
import { merchantsFor, transactionsFor, count, query } from '../../fixtures/db';
import type { Row } from '../../fixtures/statements';

/**
 * Phase 6 — the Merchant Review Center (WI4).
 *
 * WI3 made staging read-only, which required somewhere for an unrecognised merchant to go. That is
 * what TEMPORARY is: a guess the engine is willing to make but not willing to present as fact. This
 * screen is where a human resolves those guesses.
 *
 * The operations differ in how much they can destroy, and the tests are weighted accordingly.
 * Approve confirms, rename corrects, merge folds one into another — and discard is the only one
 * that removes anything, which is why the refusal path gets as much attention as the happy path.
 */

// Not Swiggy/Uber: MerchantSeedService now seeds every new user with 34 curated brand merchants
// (APPROVED, not TEMPORARY) at registration, including both of those, so an import naming them
// resolves to the seeded row instead of landing in the review queue this whole file exercises.
// Starbucks/Airtel are real CategoryRules keywords (Dining, Utilities) that stay off that list.
const GUESSES: Row[] = [
  { date: '2026-06-03', description: 'STARBUCKS COFFEE 2291', amount: 486.0, type: 'DEBIT' },
  { date: '2026-06-05', description: 'AIRTEL RECHARGE 88817', amount: 240.0, type: 'DEBIT' },
];


/**
 * Puts this account's merchants at the front of the review queue, then returns their row.
 *
 * The list is ordered oldest-first, deliberately -- its repository method is named
 * `findByLifecycleStatusInOrderByCreatedAtAsc` and carries the reason: a newest-first queue buries
 * the oldest outstanding work forever. Right for an operator, and it means a freshly seeded account
 * is always on the LAST page.
 *
 * Paging to it worked and then stopped working. The walk is O(pages), pages grow with every test in
 * the run, and the first walk in a fresh browser context is the slowest -- so as the suite grew, the
 * first walking test in this file began exceeding its timeout while the later ones passed. That is
 * a property of the harness, not of the product, and no amount of waiting fixes an O(n) walk over a
 * list that keeps getting longer.
 *
 * Backdating is the honest way out. It does not bypass the ordering or fake the screen's state; it
 * places the row exactly where the product's own rule says the oldest outstanding work belongs, and
 * the test then reads page one like an operator would. It also removes the only reason this file
 * needed a longer timeout.
 *
 * That the workaround is necessary at all is the WI4A gap stated as a cost: with no search and no
 * filter, finding one account's merchants means paging -- for a test and for a person.
 */
async function reviewRowFor(page: Page, userId: string, email: string) {
  await query(
    `update merchants set created_at = now() - interval '10 years' where user_id = $1`,
    [userId]
  );

  await page.goto('/merchant-review');
  await expect(page.getByRole('heading', { name: /merchant review/i })).toBeVisible({ timeout: 60_000 });

  const row = page.getByRole('row').filter({ hasText: email }).first();
  await expect(row, `no review row for ${email} on the first page, where the oldest work sorts`)
    .toBeVisible({ timeout: 20_000 });
  return row;
}

// Serial because this is a platform-wide list -- parallel tests page through each other's merchants
// and approve rows out from under one another. The timeout is raised in the SAME call: a second
// configure() replaces the first rather than merging with it, so splitting them silently dropped
// the serial mode.
//
// Paging a list with no search costs real time and this file does it once per test, so the default
// 90s runs out as the queue grows -- and a test failing on the clock rather than on the product is
// the least useful failure there is.
test.describe.configure({ mode: 'serial' });

test.describe('Phase 6 — merchant review center', () => {
  /**
   * First on purpose, and cheap on purpose.
   *
   * Whichever test runs first in this file pays a one-off cost -- a fresh browser context, a
   * sign-in, and Vite compiling this route on demand -- and a test that also has to page a
   * search-less list exceeded its timeout doing both. The failure was positional: it followed
   * whichever test was first, whatever it asserted.
   *
   * So the first test is one that does no paging. It is a real assertion, not a warm-up dressed as
   * one: an operator opening this screen has to be told immediately that nothing here represents a
   * blocked import, or they triage it as an outage.
   */
  test('says these merchants never blocked anything, so nobody triages them as an outage',
    async ({ adminPage }) => {
      await adminPage.goto('/merchant-review');
      await expect(adminPage.getByText(/nothing here blocked an import/i)).toBeVisible({
        timeout: 20_000,
      });
    });

  /**
   * The row has to name the account and show how much rides on the decision. Approving a merchant
   * with four hundred transactions is a different act from approving one with two, and an operator
   * deciding without that number is guessing.
   */
  test('lists a temporary merchant with its account and its weight, and approves it',
    async ({ adminPage, api, user }) => {
    await api.importStatement(GUESSES, { accountName: 'Primary' });

    // Confirm the account really has merchants awaiting review, so a missing row means the screen
    // failed to show one rather than that there was none to show. Read from the database, not the
    // list endpoint: that caps `size` at 100 and orders oldest-first, so a newly seeded account is
    // never on the page a probe would fetch.
    await expect
      .poll(async () => (await merchantsFor(user.id)).filter((m) => m.lifecycle_status === 'TEMPORARY').length,
        { timeout: 20_000, message: 'the import produced no merchants awaiting review' })
      .toBeGreaterThan(0);

    const row = await reviewRowFor(adminPage, user.id, user.email);

    // Whose account it is, and how much rides on the decision. Approving a merchant with four
    // hundred transactions is a different act from approving one with two, and an operator
    // deciding without that number is guessing.
    await expect(row).toContainText(user.email);
    await expect(row).toContainText(/transaction/i);

    await row.getByRole('button', { name: /^approve$/i }).click();

    await expect
      .poll(async () => (await merchantsFor(user.id)).filter((m) => m.lifecycle_status === 'APPROVED').length, {
        timeout: 20_000,
      })
      .toBeGreaterThan(0);
  });

  /**
   * The refusal, driven through the UI. A backend that returns 409 while the screen shows a
   * cheerful success toast is worse than no guard at all — the operator walks away believing they
   * cleaned something up.
   */
  test('refuses to discard a merchant that has transactions, and says why',
    async ({ adminPage, api, user }) => {
      await api.importStatement(GUESSES, { accountName: 'Primary' });
      const before = await merchantsFor(user.id);

      const row = await reviewRowFor(adminPage, user.id, user.email);
      await row.getByRole('button', { name: /review/i }).click();

      // The reason is stated where the action would have been, rather than as an error after the
      // fact -- the operator learns why before they try, not after.
      await expect(adminPage.getByText(/cannot discard/i)).toBeVisible();
      await expect(adminPage.getByText(/transactions are attributed|merge it instead/i)).toBeVisible();

      expect(await merchantsFor(user.id), 'nothing was removed').toHaveLength(before.length);
      expect(await transactionsFor(user.id), 'nothing was detached').toHaveLength(2);
    });

  test('will not offer a merge target that is itself an unconfirmed guess',
    async ({ adminPage, api, user }) => {
      await api.importStatement(GUESSES, { accountName: 'Primary' });

      const row = await reviewRowFor(adminPage, user.id, user.email);
      await row.getByRole('button', { name: /review/i }).click();

      // MerchantSeedService seeds every new user with a curated APPROVED catalog at registration,
      // so this account genuinely has legitimate merge targets now -- the assertion that matters
      // is narrower than "the list is empty": the sibling guess, still TEMPORARY and unconfirmed,
      // must never be one of them. Folding a guess into another guess would launder one unverified
      // name into a second one.
      const heading = adminPage.getByRole('heading', { level: 2 });
      await expect(heading).toBeVisible({ timeout: 20_000 });
      const openedName = (await heading.textContent())?.trim();

      const guesses = (await merchantsFor(user.id)).filter((m) => m.lifecycle_status === 'TEMPORARY');
      const sibling = guesses.find((m) => m.canonical_name !== openedName);
      expect(sibling, 'expected the other unconfirmed guess to still exist').toBeTruthy();

      // Wait for the candidate list to actually finish loading before asserting on its contents.
      await expect(adminPage.getByText(/loading candidates/i)).toHaveCount(0, { timeout: 20_000 });

      // The seeded catalog means the list is genuinely non-empty now -- the empty-state copy is
      // the cleanest signal that candidates actually loaded and there is something to check.
      await expect(adminPage.getByText(/no other approved merchants/i)).not.toBeVisible();
      await expect(adminPage.getByRole('button', { name: sibling!.canonical_name, exact: true }))
        .toHaveCount(0);
    });

  test('renaming corrects the guess in place', async ({ adminPage, api, user }) => {
    await api.importStatement(GUESSES, { accountName: 'Primary' });

    const row = await reviewRowFor(adminPage, user.id, user.email);
    await row.getByRole('button', { name: /review/i }).click();

    // By label, not by position: `getByRole('textbox').first()` picks whatever the drawer happens
    // to render first, which is not a contract and moved once already.
    const field = adminPage.getByLabel('Correct the name');
    await expect(field).toBeVisible({ timeout: 20_000 });
    await field.fill('Starbucks (Coffee Shop)');

    // The button is disabled until the name actually differs, so waiting for it to enable is
    // waiting for React to have taken the input -- not an arbitrary pause.
    const rename = adminPage.getByRole('button', { name: /rename & approve/i });
    await expect(rename).toBeEnabled({ timeout: 10_000 });
    await rename.click();

    await expect
      .poll(async () => (await merchantsFor(user.id)).map((m) => m.canonical_name).join('|'), {
        timeout: 20_000,
      })
      .toContain('Starbucks (Coffee Shop)');
  });

  /** Every operator action on someone else's data leaves a trace. An action with no audit entry is
   *  one nobody can be held to, which for a screen that edits customer records is not acceptable. */
  test('operator actions are auditable', async ({ adminPage, api, user, admin }) => {
    await api.importStatement(GUESSES, { accountName: 'Primary' });

    const row = await reviewRowFor(adminPage, user.id, user.email);
    await row.getByRole('button', { name: /^approve$/i }).click();

    await expect
      .poll(
        async () =>
          count(
            `select count(*) from audit_logs
              where action = 'MERCHANT_APPROVED' and metadata->>'actorId' = $1`,
            [admin.id]
          ),
        { timeout: 20_000, message: 'an operator action left no trace of who performed it' }
      )
      .toBeGreaterThan(0);
  });

  test('an outstanding count tells an operator whether there is work here at all',
    async ({ adminApi, api }) => {
      const before = await adminApi.get<{ outstanding: number }>('/admin/merchant-review/count');
      await api.importStatement(GUESSES, { accountName: 'Primary' });
      const after = await adminApi.get<{ outstanding: number }>('/admin/merchant-review/count');

      expect(after.data!.outstanding).toBeGreaterThan(before.data!.outstanding);
    });
});
