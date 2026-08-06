# End-to-end tests

Playwright tests that drive the user portal and the admin portal in a real browser.

## Running them

```bash
cd e2e
npm install
npx playwright install chromium
npm test
```

Playwright starts both Vite dev servers itself (5173 for the user app, 5174 for the admin portal —
the ports each app's own `vite.config.ts` pins). You do not need to start anything first. If you
already have a dev server running locally it is reused rather than clashing on the port.

```bash
npm run test:user      # user portal only
npm run test:admin     # admin portal only
npm run test:headed    # watch it happen in a real browser window
npm run report         # open the HTML report from the last run
```

## What is and is not covered

These are **smoke tests over the unauthenticated surface only**. They prove each app builds, boots,
routes and renders in a real browser, and they guard a few specific behaviours that a jsdom
component test cannot see — the `rel="noopener"` attributes on the register page's new-tab links,
and the admin portal's redirect-to-login for a protected route.

They deliberately stop at the login wall. Authenticated flows need a running backend, a migrated
database and a seeded user — real fixtures that are a separate decision from installing the
harness. Writing them against fixtures that do not exist would mean committing tests that cannot
pass on a clean checkout.

`ECONNREFUSED /api/v1/setup/status` in the output is expected: no backend is running, and these
tests do not need one.

## Relationship to the existing test suites

This does not replace the Vitest suites in `frontend/` and `admin-portal/`, and should not grow to
duplicate them. Those run in jsdom with the network mocked — fast, and the right place for
component behaviour, form validation, error states and hook logic. A test belongs here only when it
genuinely needs a real browser or spans both apps.

## Adding a test

Put the spec in the directory for the app it drives:

```
tests/user-portal/*.spec.ts     -> runs against localhost:5173
tests/admin-portal/*.spec.ts    -> runs against localhost:5174
```

Each Playwright project is scoped to its directory, so **a spec placed anywhere else runs against
nothing and is silently skipped** — no error, no warning. Keep specs inside one of those two
folders.

One caveat worth knowing, because it already caused a flaky test here: `locator.count()` does not
auto-wait, unlike the `expect(locator)` matchers. Calling it straight after `page.goto()` can
sample the DOM before React has rendered, yielding zero and quietly making any loop over that count
assert nothing. Wait for something to be visible first, then count.

## Not wired into CI

Deliberately. An E2E job needs a backend, Postgres and a seeded test user, and it materially
increases CI runtime — that is its own decision rather than a side effect of installing the
harness. `.github/workflows/ci.yml` is unchanged.
