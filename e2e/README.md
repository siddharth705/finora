# End-to-end tests

Playwright tests that drive the user portal and the admin portal in a real browser, against a real
backend and a real database.

## Running them

```bash
cd e2e
npm install
npx playwright install chromium
npm run stack:up
npm test
```

`stack:up` brings up the stack these tests need: a throwaway Postgres on **5433** and a backend on
**8081**, deliberately *not* the 5432/8080 pair you already have running. Several assertions read
platform-wide counts and financial totals, and a database carrying yesterday's experiments makes
those either wrong or meaningless — and sharing your stack would mean a test run rewriting data you
were in the middle of looking at.

The backend runs from the `finora-backend-*.jar` in `backend/target/`, so it is whatever you last built.
That is on purpose: "did you rebuild" should be a question you can answer.

```bash
npm run stack:reset   # destroy the database and start again, empty
npm run stack:down    # stop it

npm run test:smoke    # the PR gate — one pass through the product, under 5 minutes
npm test              # everything else: user-portal + admin-portal + workflow
npm run test:all      # both
npm run test:workflow # the business-outcome specs only
npm run test:browsers # Firefox + Edge
npm run test:responsive
npm run report        # the HTML report from the last run
```

## Two suites, on purpose

`test:smoke` is the **build-confidence** suite: sign in, import a CSV, exercise the PDF path,
resolve a duplicate, check the ledger and the dashboard agree, drain the learning queue, look at
both operator screens, sign out. One journey through each system that would make the product
unusable if broken. Run it on every PR.

`npm test` is everything else — edge cases, error paths, permutations, behaviour at size. Run it
nightly or before a release.

The split is by file rather than by tag filter over one suite. A tag filter looks cheaper and drifts
the moment someone retags, and it hides the fast suite's cost: nobody notices a five-minute gate
becoming a fifteen-minute one until they are waiting on it. `smoke.spec.ts` asserts its own runtime
budget, so it fails when it stops being a smoke test.

**Adding to the smoke suite:** if the test fails, would you stop the release? If not, it belongs in
the full suite.

Playwright starts both Vite dev servers itself and points them at the test backend via
`FINORA_API_PROXY_TARGET`. `globalSetup` fails the run with a readable message if the stack is not
there, rather than letting the specs discover it as a wall of "element not found".

## What these are for

Not page-render checks. Every spec asserts a **business outcome**: the question is not "did the
screen show something" but "did the user's decision survive the whole system". A workflow that looks
correct while writing wrong financial data is still a failed test.

| Directory | Covers |
|---|---|
| `tests/workflow/` | Duplicate review, dashboard/ledger consistency, reconciliation, merchant lifecycle, multi-user isolation, data integrity |
| `tests/user-portal/` | Upload, verification, import completion, session behaviour, negative and interruption cases, accessibility, behaviour at size |
| `tests/admin-portal/` | Learning Queue, Merchant Review Center |

The Vitest suites in `frontend/` and `admin-portal/` are **not** superseded. They run in jsdom with
the network mocked — fast, and the right place for component behaviour, form validation and hook
logic. A test belongs here only when it needs a real browser, a real database, or both apps at once.

## The database is a first-class fixture

`fixtures/db.ts` talks to Postgres directly, for two jobs, and it is worth being precise about why
neither can go through the API.

**Seeding.** On a genuinely fresh database there is no way to reach an authenticated state through
the product. Setup suspends the only pre-verified account in the same transaction that promotes the
new one, and `FirebaseConfig` returns null without credentials so phone verification cannot succeed
locally at all. That is Issue 01 in `E2E_TEST_REPORT.md`. The fixtures do in SQL exactly what
Firebase would have done — `phone_verified = true` — and nothing else. Registration itself goes
through the real `POST /auth/register`, and first-run setup through the real BOOTSTRAP_ADMIN and
installation-key flow.

**Assertion.** "Was this transaction *counted*" cannot be answered from the DOM. `is_duplicate_of IS
NULL` is the filter shared by BudgetService, AnalyticsService, DashboardService, InsightsService,
RecurringService, ReportService and two repository aggregates — asserting against it covers all
seven at once.

The rule: SQL may set up what the product cannot, and may observe anything, but never performs a
step the test is meant to be exercising.

## Every test gets

- **Its own freshly registered account.** This milestone's state is per-user, so a shared login would
  make each duplicate test depend on what its neighbours imported — and would turn the isolation
  specs into tautologies.
- **A console and network guard.** Asserted at teardown on every test, not as a test of its own: a
  page that renders correctly while throwing in the console is still a defect. Tests whose subject
  *is* a failure call `allowConsoleErrors(reason)` — the reason is required, so the exemption is
  visible where it is taken.

## Things worth knowing before adding a test

**Put the spec in the directory for the app it drives.** Each Playwright project is scoped by
directory, so a spec placed anywhere else runs against nothing and is silently skipped.

**The admin specs are serial.** The Learning Queue and Merchant Review Center are platform-wide
views — that is what makes them useful to an operator — so parallel tests see each other's rows and
approve merchants out from under one another.

**Merchant Review is ordered oldest-first**, deliberately (a newest-first queue buries the oldest
outstanding work forever). A freshly seeded account is therefore always on the last page, and the
screen has no search or filter. `reviewRowFor` backdates the seeded merchants so they sort to page
one — which does not bypass the ordering, it places the row where the product's own rule says the
oldest work belongs. Paging to it instead is O(pages) and gets slower with every test in the run.
That the workaround is needed at all is the WI4A gap stated as a cost.

**Learning happens for confident categorisations, not for guesses.** A row the engine could not
categorise falls to "Other" and is deliberately *not* learned — learning a guess would poison every
future statement. A fixture of genuinely unrecognisable merchant names produces zero learning events
and reads as a broken queue when it is a working one.

**`locator.count()` does not auto-wait**, unlike the `expect(locator)` matchers. Calling it straight
after `page.goto()` can sample the DOM before React has rendered, yielding zero and quietly making
any loop over that count assert nothing. Wait for something visible first, then count.

## In CI

The **smoke** suite runs on every push and pull request (`smoke` job in
`.github/workflows/ci.yml`): Postgres as a service, the backend started in the job's process tree so
a crash fails the step rather than hanging it, Chromium only. Report, traces and the backend log
upload on failure — without them a failed E2E run is a one-line assertion message nobody can act on.

The **full** suite is not wired in. It needs the same infrastructure and several minutes of browser
time, and putting it on every PR would make people wait on it and then learn to ignore it. Run it
nightly, or before a release.

## Known gaps

- `tests/admin-portal/merchant-review.spec.ts` has one `test.fixme` — see the comment above it. The
  behaviour it covers is asserted elsewhere; what is not covered is the row's own presentation.
- The cross-browser and responsive projects (`test:browsers`, `test:responsive`) are configured but
  have never been run to green.
- Phase 14 of the milestone test brief — regression against previously-working statements — needs a
  sanitized corpus that does not exist yet.

## A note on running this alongside another agent

Two Maven builds sharing one `backend/target/` will delete and rewrite each other's class files
mid-run. Locally that presents as an intermittently red build with a different failing set each
time: ArchUnit scans returning empty, `NoClassDefFoundError` for classes that plainly exist, Mockito
failing to mock a class whose file was mid-write, Spring contexts failing across every `*IT`. All of
it passes in isolation, which is the tell.

If you see that shape, check for another build before reaching for the test code. CI is unaffected —
it gets its own checkout.
