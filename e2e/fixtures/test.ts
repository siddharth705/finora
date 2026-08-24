import { test as base, expect, type Page, type ConsoleMessage } from '@playwright/test';
import { Api } from './api';
import { createAdmin, createUser, type TestUser } from './accounts';
import { ADMIN_APP, USER_APP } from './config';

/**
 * The suite's fixtures.
 *
 * Two things every test gets without asking:
 *
 * **Its own freshly registered user.** This milestone's state is per-user — merchants, learning,
 * duplicate detection all scope to one account — so a shared login would make each duplicate test
 * depend on whatever its neighbours had already imported, and would turn the isolation phase into
 * a tautology. Registration is cheap; a flaky suite is not.
 *
 * **A console and network guard.** Phase 17 asks for this on every test rather than as a test of
 * its own, and that is the right shape: a page that renders correctly while throwing in the console
 * is still a defect, and it is exactly what a human tester scrolls past. Collected throughout and
 * asserted at teardown, so a failure names the test that caused it.
 */

interface Fixtures {
  user: TestUser;
  admin: TestUser;
  api: Api;
  adminApi: Api;
  userPage: Page;
  adminPage: Page;
  /** Errors seen so far, for a test that wants to assert about them mid-flight rather than at the
   *  end — the negative phases deliberately provoke failed requests and need to say so. */
  consoleErrors: string[];
  /** Opt out of the automatic assertion, for tests whose subject IS a failure. Must be called with
   *  a reason, so an exemption is always explained where it is taken. */
  allowConsoleErrors: (reason: string) => void;
}

/** React's own warnings are a defect signal the brief asks for explicitly, but they arrive as
 *  console.error with a distinctive prefix, so they are worth naming separately in the report. */
function classify(text: string): string {
  if (/Warning: /.test(text)) return 'React warning';
  if (/Unhandled|unhandled promise/i.test(text)) return 'unhandled rejection';
  return 'console error';
}

/**
 * Noise this suite deliberately does not fail on, each with a reason.
 *
 * Kept deliberately short. Every entry here is a thing a real user's browser prints, and the
 * temptation is always to grow the list until it swallows the defect you were looking for.
 */
const IGNORED = [
  // Vite's dev-server HMR chatter — an artefact of how the suite runs, not of the product.
  /\[vite\]/,
  // The React DevTools suggestion, printed by React itself in development builds.
  /Download the React DevTools/,
];

export const test = base.extend<Fixtures>({
  user: async ({}, use) => {
    await use(await createUser());
  },

  admin: async ({}, use) => {
    await use(await createAdmin());
  },

  api: async ({ user }, use) => {
    await use(new Api(user.token));
  },

  adminApi: async ({ admin }, use) => {
    await use(new Api(admin.token));
  },

  consoleErrors: async ({}, use) => {
    await use([]);
  },

  /**
   * The escape hatch, and the reason it takes a reason.
   *
   * Some tests exist precisely to provoke a failure -- an aborted upload, a damaged file, a
   * rejected request -- and a failed request legitimately logs to the console. Asserting a clean
   * console there would be asserting that the product fails silently, which is the opposite of what
   * this suite wants. Requiring a written reason keeps the exemption honest: it is visible in the
   * test that takes it, rather than a flag someone flips to make a red run green.
   */
  allowConsoleErrors: async ({ consoleErrors }, use) => {
    await use((reason: string) => {
      if (!reason?.trim()) throw new Error('allowConsoleErrors needs a reason.');
      consoleErrors.push(ALLOWED);
    });
  },

  /** The user app, signed in, watched. */
  userPage: async ({ page, user, consoleErrors }, use) => {
    watch(page, consoleErrors);

    await signIn(page, USER_APP, user.email, user.password);
    await use(page);

    assertClean(consoleErrors);
  },

  /** The admin portal, signed in, watched. A second browser context so a cross-app test can hold
   *  both sessions at once without one logging the other out. */
  adminPage: async ({ browser, admin, consoleErrors }, use) => {
    const context = await browser.newContext();
    const page = await context.newPage();
    watch(page, consoleErrors);

    await signIn(page, ADMIN_APP, admin.email, admin.password);
    await use(page);

    assertClean(consoleErrors);
    await context.close();
  },
});

/** Chrome's own text for a failed resource load never carries the URL -- just "...status of 401
 *  ()" -- so it can't be told apart from any other endpoint's 401 by regex on the message alone.
 *  See the counter in watch() below for how this gets matched to the right request anyway. */
const FAILED_RESOURCE_401 = /^Failed to load resource: the server responded with a status of 401/;

function watch(page: Page, sink: string[]) {
  // SEC-01's AuthProvider bootstrap effect (frontend/src/context/AuthContext.tsx) calls
  // authApi.refresh() unconditionally on mount to recover a session from the HttpOnly cookie --
  // and every test's first page load has no cookie yet, so this always 401s. The app's own catch
  // handles it silently (see that effect's own comment: "the ordinary 'not logged in' case ...
  // not logged or surfaced as an error"), but Chrome still logs the failed network request to the
  // console regardless of the JS catch -- expected on every run, not a defect.
  //
  // Counted rather than text-matched in IGNORED: the console line alone can't say which endpoint
  // 401'd (see FAILED_RESOURCE_401's own comment), so a plain regex there would swallow every
  // unrelated 401 a test might legitimately want to see. Counting real /auth/refresh 401 responses
  // and only excusing that many matching console lines keeps the exemption scoped to this one
  // endpoint -- a test that deliberately provokes a LATER refresh failure still gets it excused
  // (same expected shape, same reasoning), but a 401 from anything else is unaffected.
  let expectedAuthRefresh401s = 0;

  page.on('console', (msg: ConsoleMessage) => {
    if (msg.type() !== 'error' && msg.type() !== 'warning') return;
    const text = msg.text();
    if (IGNORED.some((p) => p.test(text))) return;
    if (msg.type() === 'warning' && !/Warning: /.test(text)) return;
    if (expectedAuthRefresh401s > 0 && FAILED_RESOURCE_401.test(text)) {
      expectedAuthRefresh401s--;
      return;
    }
    sink.push(`${classify(text)}: ${text}`);
  });
  page.on('pageerror', (err) => sink.push(`pageerror: ${err.message}`));
  page.on('response', (res) => {
    if (res.status() >= 500) sink.push(`HTTP ${res.status()} from ${res.url()}`);
    if (res.status() === 401 && new URL(res.url()).pathname.endsWith('/api/v1/auth/refresh')) {
      expectedAuthRefresh401s++;
    }
  });
}

/** Pushed by allowConsoleErrors. A marker in the same list rather than a separate flag, so the
 *  opt-out travels with the errors it excuses and cannot be set where nothing reads it. */
const ALLOWED = '__console-errors-allowed__';

function assertClean(errors: string[]) {
  if (errors.includes(ALLOWED)) return;
  expect(errors, `Phase 17: the page misbehaved while rendering correctly:\n${errors.join('\n')}`)
    .toEqual([]);
}

export async function signIn(page: Page, appOrigin: string, email: string, password: string) {
  await page.goto(`${appOrigin}/auth`);
  await page.getByLabel(/email|phone/i).first().fill(email);
  await page.getByRole('button', { name: /continue/i }).click();
  await page.getByLabel(/password/i).first().fill(password);
  await page.getByRole('button', { name: /sign in|log in/i }).click();
  await expect(page, `sign-in for ${email} never left /auth`).not.toHaveURL(/\/auth$/, { timeout: 20_000 });
}

/**
 * Uploads a statement through the real file input.
 *
 * `setInputFiles` with a buffer rather than a path on disk: the fixture builders produce content,
 * and writing it to a temp file first would add a cleanup problem and an OS-specific path for no
 * gain. This is still the same DOM event a file picker produces.
 */
export async function uploadStatement(
  page: Page,
  name: string,
  mimeType: string,
  buffer: Buffer
) {
  await page.getByTestId('statement-file-input').setInputFiles({ name, mimeType, buffer });
  // A PDF opens the password panel first and waits for an explicit Upload; a CSV goes straight up.
  const uploadButton = page.getByRole('button', { name: /upload statement/i });
  if (await uploadButton.isVisible().catch(() => false)) {
    await uploadButton.click();
  }
}

export { expect };

/**
 * Finds a row in a paginated admin list, walking forward until it appears.
 *
 * The Merchant Review Center is ordered oldest-first on purpose — its repository method is named
 * `findByLifecycleStatusInOrderByCreatedAtAsc` and carries the reasoning: a newest-first queue
 * buries the oldest outstanding work forever. Correct for an operator, awkward for a test, because
 * a freshly seeded account's merchants are always on the last page.
 *
 * Walking is also the only option available: the screen has no search or filter, so finding one
 * account's merchants means paging. That is a real operator cost, not just a test inconvenience —
 * it is the productivity gap recorded as WI4A.
 */
export async function findRowAcrossPages(page: Page, text: string, maxPages = 40) {
  const rowOn = () => page.getByRole('row').filter({ hasText: text }).first();
  const next = () => page.getByRole('button', { name: /next/i }).first();

  /**
   * Which page the list believes it is on.
   *
   * Read from the "Page N of M" indicator rather than inferred from how many times Next was
   * clicked. The list is server-paged and React re-renders asynchronously, so a click can be issued
   * against a control that has not settled -- the walker then thought it had reached the end while
   * still looking at page one, and reported a row missing that was simply further along. Asking the
   * page where it is removes the guess.
   */
  const position = async () => {
    const label = await page.getByText(/Page \d+ of \d+/).first().textContent().catch(() => null);
    const match = label?.match(/Page (\d+) of (\d+)/);
    return match ? { current: Number(match[1]), total: Number(match[2]) } : null;
  };

  const here = async () =>
    rowOn().waitFor({ state: 'visible', timeout: 2000 }).then(() => true).catch(() => false);

  for (let visited = 0; visited < maxPages; visited++) {
    if (await here()) return rowOn();

    const at = await position();
    if (!at || at.current >= at.total) break;

    await next().click();
    // Wait for the indicator to actually move. Without this the loop can read the page it just
    // left, conclude the row is absent, and walk off the end of a list it never really paged.
    await expect
      .poll(async () => (await position())?.current ?? at.current, { timeout: 5000 })
      .toBeGreaterThan(at.current);
  }

  const at = await position();
  throw new Error(
    `No row containing "${text}". The list reports ${at ? `page ${at.current} of ${at.total}` : 'no pagination'}.
` +
    `This screen has no search, so a test (and an operator) can only page through it -- see WI4A.`
  );
}
