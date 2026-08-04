# Finora — Repository-Wide Engineering Improvement Proposal

**Status:** originally proposal-only. Four items have since been prioritised and built; the rest
are still unimplemented and awaiting prioritisation.

| # | Item | Status |
|---|---|---|
| 1 | Crash reporting and error boundaries | **Done** — `c9357cd` |
| 3 | Linters in CI | **Done** — `fbd7a5e` |
| 4 | react-router advisory | **Done** — see the resolution note under item 4; the audit output is *expected* to still show 2 high |
| 7 | Backend coverage measurement | **Done** — see item 7; it immediately surfaced a separate, larger defect |
| 2, 5, 6, 8–12 | — | Not started |

Everything below is the original text, with a **Resolution** note appended to each completed item.
The point of this document was to keep "evaluate first" separate from "fix immediately"; the
resolution notes record what the evaluation concluded once it was actually built, including where
it disagreed with the proposal.

**Scope of the review:** the whole repository — `backend/`, `frontend/`, `admin-portal/`,
`mobile/`, `scripts/`, `.github/`, `.husky/`, `docs/`, and root configuration. This is the
counterpart to the repo-wide bug hunt that produced `6ee925a`, `c33a859`, `5972c97`, `a224548`.

**What this excludes:**

- **The statement import subsystem**, which already has its own Pass 2 document —
  `import-engine-improvement-proposal.md` (12 items). Nothing here duplicates it. Item 5 below
  touches merchant resolution, which the import path *calls*, but the class itself
  (`MerchantNormalizationEngine`) is shared with manual transaction entry and is not in that
  document's scope.
- **Anything already fixed** in the bug-hunt commits above.

**A note on evidence.** Every item below cites something measured in this repository on 2026-08-04,
not a general best practice. Where a number appears, it came from a command. Where something was
not measured, the item says so rather than implying it was.

---

## Summary

| # | Improvement | Impact | Effort | Priority |
|---|---|---|---|---|
| 1 | Crash reporting and error boundaries for the two web apps | High | S | **High** |
| 2 | A shared client layer for the three TypeScript apps | High | L | **High** |
| 3 | Run the linters in CI, and give mobile one | Medium | S | **High** |
| 4 | Upgrade `react-router-dom` past the open-redirect advisory | Medium | M | Medium |
| 5 | Merchant resolution loads every merchant on every imported row | Medium | M | Medium |
| 6 | Break up `UserDetail.tsx` (1770 lines) | Medium | M | Medium |
| 7 | Measure backend test coverage | Medium | S | Medium |
| 8 | One implementation of client-IP resolution | Low | S | Medium |
| 9 | Code-split the two web bundles | Low | M | Low |
| 10 | Move rate-limit state out of process before a second instance exists | Medium | M | Low |
| 11 | Set a policy for the Expo toolchain advisories | Low | S | Low |
| 12 | Establish an accessibility baseline for the web apps | Medium | M | Low |

---

## High priority

### 1. Crash reporting and error boundaries for the two web apps

**What.** `mobile/` reports crashes to Sentry (`src/lib/monitoring.ts`, scrubbed for a financial
app). `frontend/` and `admin-portal/` have no crash reporting of any kind — a search for `sentry`
across `frontend/src` returns nothing, and across `admin-portal/src` returns only a page that
*displays* backend diagnostics. Separately, no React `ErrorBoundary` or `componentDidCatch` exists
in **any** of the three apps.

**Why it is valuable.** These two gaps compound into one failure mode: an uncaught error during
render unmounts the tree, React renders nothing, and the user gets a blank white page — with
nobody notified that it happened. That is not hypothetical. The bug hunt found and fixed a routing
defect whose entire symptom was exactly this (`#root` with empty `innerHTML`), and it had been in
production behind Cloudflare's SPA fallback for every typo'd URL. It was found by navigating to a
bad URL on purpose, not by a report, because there is no mechanism that could have produced a
report. The mobile app — the newest and least-used surface — is currently the only one that would
tell you it broke.

**Expected impact.** Failures in the two primary product surfaces become observable instead of
silent, and a render error degrades to a recoverable "something went wrong, reload" panel scoped to
a subtree rather than taking out the whole page. This also retires a whole class of "a user says it
went blank and we can't reproduce it" investigation.

**Effort.** Small. Sentry's React SDK plus one boundary component per app, reusing the scrubbing
rules `mobile/src/lib/monitoring.ts` already establishes — the hard thinking about what must never
leave a financial app's client has been done once already.

**Risks / trade-offs.** A third-party SDK in the web bundle adds weight (see item 9) and is another
data-egress path that has to honour the same PII rules as mobile — the scrubbers must be ported,
not reinvented. An error boundary can also *hide* problems if placed too high in the tree; it
should wrap route subtrees, not the whole app, so a broken page doesn't silently mask itself.

**Dependencies.** None. Sharing the scrubbers with mobile is easier after item 2, but does not
require it.

---

### 2. A shared client layer for the three TypeScript apps

**What.** `frontend/`, `admin-portal/` and `mobile/` each carry their own copy of the same
client-side concerns. Measured, comparing same-named files:

| File | frontend vs admin-portal |
|---|---|
| `api/client.ts` | 196 differing lines |
| `components/ProtectedRoute.tsx` | 44 |
| `lib/phoneAuth.ts` | 19 |
| `lib/safeStorage.ts` | 12 |
| `lib/maskPhone.ts` | 3 (comments only — the function body is identical) |

`normalizeApiBase()` exists verbatim in all three. `maskPhone()` exists three times with identical
bodies and three different comments explaining that it is a copy.

**Why it is valuable.** This is the only item in this document that has already caused a shipped
bug, which is why it is High despite being the most expensive. The mobile app's `client.ts` was
ported from the web app's and then the web app's was fixed and mobile's was not: its 401 handler
excluded only `/auth/refresh` where the web version excluded every auth endpoint. The consequence
was not cosmetic — for a signed-out user with a stale refresh token on the device, one mistyped
password sent that token to `/auth/refresh`, which `RefreshTokenService.rotate()` treats as a theft
signal and answers by revoking every active session on every device. A typo could sign you out
everywhere. That was fixed in `5972c97`, but the *mechanism* that produced it is untouched: three
copies, no enforcement, and a divergence that is invisible until someone diffs the files.

The 196-line divergence between the two web clients is the same hazard sitting there today. Some of
it is legitimate (different storage keys, different redirect targets); the point is that nothing
distinguishes legitimate divergence from a fix that only landed in one place.

**Expected impact.** Auth/refresh semantics, storage contracts, API base normalisation and phone
masking get one implementation and one test suite. A fix to the refresh interceptor lands in all
three apps at once. New surfaces start from the shared layer instead of a copy that is correct on
the day it is made.

**Effort.** Large, and this is the main argument against doing it soon. It needs a real decision
about packaging: a workspace package is the clean answer but the repo currently has no monorepo
tooling (the root `package.json` is dev tooling only and says so). Mobile also cannot share
everything — its storage is async `SecureStore` where web is sync `localStorage`, so the shared
piece is the *contract* and the interceptor logic, not the storage implementation.

**Risks / trade-offs.** A premature shared package is worse than duplication: it couples three
independently deployable apps and makes every change a three-app regression risk. Recommend scoping
tightly to what has demonstrably drifted (the API client and its interceptors first, then the small
pure utilities) and explicitly *not* sharing UI components, which legitimately differ. Also worth
considering the cheaper 80% first: a test that asserts the three `client.ts` interceptor rules agree,
which would have caught the actual bug for a fraction of the cost.

**Dependencies.** None, but it makes items 1 and 8 cheaper afterwards.

---

### 3. Run the linters in CI, and give mobile one

**What.** `frontend/package.json` and `admin-portal/package.json` both define `"lint": "eslint ."`.
`.github/workflows/ci.yml` contains the string `lint` exactly **zero** times — neither is ever run.
`mobile/package.json` defines no lint script at all.

**Why it is valuable.** A lint script nobody runs is indistinguishable from no lint script, except
that it looks like coverage in review. This is the same shape as the pre-commit hook defect fixed in
`8d1fbe0` (a guard that announced a block it never enforced) and the CI test count fixed in
`a224548` (a number nothing kept honest): the repo has a recurring pattern of checks that exist on
paper. The two web apps also carry `eslint-plugin-react-hooks`, whose exhaustive-deps rule catches
a genuine class of stale-closure bug that no test in this repo would catch.

**Expected impact.** Small but immediate and permanent. It also converts the existing eslint config
from documentation into enforcement.

**Effort.** Small, and unusually low-risk right now: both apps are already clean. Running eslint
today produces **1 warning each**, and in both cases it is the same trivial finding — an
`eslint-disable` comment for `react-hooks/exhaustive-deps` that is no longer suppressing anything,
in each app's `pages/VerifyPhone.tsx` (line 62 and line 74 respectively). So the job goes green on the first run with a
two-line cleanup, rather than needing a backlog burned down first. That window closes as the code
grows.

**Risks / trade-offs.** Adds ~30s per web job. Mobile needs `eslint-config-expo` wired up, which is
new configuration rather than just a CI step, so mobile is the larger half of an already small item.
If the team wants lint failures to be advisory at first, `continue-on-error: true` is a reasonable
staging step — though it reintroduces exactly the "check that does not enforce" pattern above, so it
should be time-boxed.

**Dependencies.** None.

---

## Medium priority

### 4. Upgrade `react-router-dom` past the open-redirect advisory

**What.** Both web apps pin `react-router-dom` `^6.30.3`. `npm audit --omit=dev` reports **2
moderate** production vulnerabilities in each, both in this dependency:

- *React Router: Open redirect leading to XSS*
- *Open redirect via backslash in `<Link>` and `useNavigate` (CVE-2025-68470 bypass)*, and
  *Arbitrary Constructor Injection via `deserializeErrors()` in SSR hydration*

`fixAvailable` is `react-router-dom@7.18.2`, flagged `isSemVerMajor: true`.

**Why it is valuable.** These are the only production-dependency advisories in the repo (mobile's
14 are all build-time — see item 11). An open redirect in a financial app is a credible phishing
primitive: a link that looks like a Finora URL and lands on an attacker's login page. The SSR
hydration issue does not apply — both apps are client-rendered SPAs — so the honest severity is the
redirect pair, not all three.

**Expected impact.** Closes the repo's only shipped-code advisories.

**Effort.** Medium, and the effort is the major version, not the audit. v7 changes data-router APIs;
both apps already opt into `v7_startTransition` and `v7_relativeSplatPath` future flags, which is
the intended migration on-ramp and suggests the jump is smaller here than for a typical v6 app.

**Risks / trade-offs.** A major router upgrade touches every route in both apps, including the
catch-all redirects added in `c33a859`. Both apps have route-level tests now (`App.test.tsx` in
each), which did not exist before the bug hunt — so there is a regression net that there would not
have been a week ago. Worth checking whether a 6.x patch line ever carries the fix before committing
to v7; if it does, that is a far cheaper path.

**Dependencies.** None.

**Resolution.** Upgraded both apps to `react-router-dom` 7.18.2.

The cheaper 6.x path this item asked about does not exist. The advisory ranges are
`>=6.0.0 <7.18.0` (backslash open redirect) and `>=6.30.2 <=6.30.4` (open redirect to XSS): the
entire 6.x line is affected and no 6.x patch carries a fix.

**`npm audit` still reports 2 high, and that is expected. Do not "fix" it by downgrading.** After
the upgrade a different advisory applies — GHSA-qwww-vcr4-c8h2, *RSC Mode CSRF Bypass*, range
`>=7.12.0 <8.3.0`, rated high. npm's suggested remediation is `react-router-dom@7.11.0`, which is
below the vulnerable range start but also below 7.18.0, so taking it would reintroduce the open
redirect. There is no version that clears everything: the advisory's fix boundary is 8.3.0 and **no
8.x has been published** (`latest` is 7.18.2).

7.18.2 is the right choice because it resolves the only advisory that actually applies here. The
RSC CSRF issue requires React Server Components, and the SSR `deserializeErrors()` issue requires
SSR; both apps are client-rendered SPAs using `BrowserRouter` with neither. Revisit when 8.3.0
ships.

On exposure: the open redirect was **latent rather than exploitable**. Every navigation target in
both apps is a string literal or a server-built path; the one dynamic case,
`navigate(result.link)` in the admin `GlobalSearch`, receives links `AdminSearchService` composes
from UUIDs and fixed segments, with no user-controlled string interpolated. Fixed because the
guard against someone later navigating to a user-supplied value should be the library, not the
observation that nobody has done it yet.

The migration itself was two mechanical changes, and the second is the more interesting one:

  - `BrowserRouter`'s `future` prop is gone; `v7_startTransition` and `v7_relativeSplatPath` were
    opt-in flags for this exact migration and are now the only behaviour. Having opted in early is
    what made this small.
  - `useNavigate()`'s returned function now returns a Promise, so all 14 fire-and-forget
    `navigate(...)` calls across both apps became floating promises. **This was caught by
    `@typescript-eslint/no-floating-promises`, i.e. by item 3, within minutes of the upgrade
    landing** — the concrete payoff for having done item 3 first. They now read `void navigate(...)`,
    satisfying the rule deliberately rather than suppressing it.

---

### 5. Merchant resolution loads every merchant on every imported row

**What.** `ImportService` calls `categorizationService.resolveMerchantId(userId, ...)` inside its
per-row loop (`ImportService.java:445`). That reaches
`MerchantNormalizationEngine.resolve()`, which — whenever the description's normalised alias is not
already known — runs `merchantRepository.findByUserId(userId).stream()` and does the
first-significant-token match in Java.

**Why it is valuable.** The alias-miss path is not the rare case; it is the *first import* case, by
definition. A statement whose descriptions the user has never seen misses on most rows. For a
500-row statement against 300 existing merchants that is ~500 queries each hydrating ~300 entities
— roughly 150,000 entity loads for one import, all while holding one of the six
`ImportConcurrencyLimiter` permits and one of ten DB connections (`DB_POOL_MAX_SIZE=10`). The
limiter bounds how many imports run at once; it does not make any one of them cheap.

**Expected impact.** Import wall-clock time on first imports, which is the moment a new user forms
their impression of the product. It also frees a scarce permit sooner, which matters more than the
raw time saved.

**Effort.** Medium — and this is a case where the obvious fix is the wrong one. Hoisting the
merchant list into a per-import cache is a two-line change and is **incorrect**: `resolve()` creates
new merchants as it goes, so a snapshot taken before the loop makes row 400 unable to see the
merchant that row 12 created, silently producing duplicate merchants. Any real fix has to maintain
the cache as merchants are created, or push the token match into SQL (which means persisting the
normalised first token as an indexed column — a migration).

**Risks / trade-offs.** This is the item most likely to introduce a correctness regression while
"only" changing performance, and merchant identity feeds categorisation learning, so a bug here
teaches the engine wrong things persistently. It should not be attempted without a test that
imports a statement containing several rows that resolve to the *same new* merchant.

**Dependencies.** None. Note this is deliberately not in the import-engine proposal — the class is
shared with manual transaction entry (`TransactionService:175`), so a change here affects both paths.

---

### 6. Break up `UserDetail.tsx` (1770 lines)

**What.** At 1770 lines it is the largest source file in the repository — 600 lines longer than
`Import.tsx` (1168), which the import proposal already flags as item 9 for the same reason.

**Why it is valuable.** Same argument the import proposal makes, applied to the file that is
actually worse. A file this size is where merge conflicts concentrate on a multi-developer team, and
it is effectively unreviewable in a diff: a reviewer cannot hold the whole thing in their head, so
changes get approved on the basis of the hunk rather than the behaviour.

**Expected impact.** Reviewability and parallel work on the admin portal. No user-visible change.

**Effort.** Medium. Mechanical (extract sections into components), but 1770 lines of mechanical is
still a real amount of careful work.

**Risks / trade-offs.** Pure refactors of untested UI are how regressions arrive. `UserDetail.tsx`
has **no test file** (`admin-portal/src/pages/` carries 14 page test files; the repo's largest
source file is not among them). Recommend characterisation tests for the page's main sections *before* splitting it — which
means the honest effort is "tests then refactor", not just the refactor.

**Dependencies.** None. Worth sequencing against the import proposal's item 9 so the two large-file
splits establish the same conventions rather than two different ones.

---

### 7. Measure backend test coverage

**What.** `backend/pom.xml` contains no JaCoCo plugin and no coverage tooling of any kind (a search
for `jacoco|coverage` returns 0 matches). The suite is at **941 tests** and passing; what fraction
of the code they exercise is simply unknown.

**Why it is valuable.** 941 passing tests reads as strong coverage and may well be, but the bug hunt
found three real defects that the suite passed straight through — the rate-limit bypass in
particular had five dedicated tests in `RateLimitFilterTest` that all passed while the filter was
bypassable, because every one of them fed it an already-canonical path. Test *count* is not the
signal it looks like. A coverage report would not have caught that specific bug (the lines were
covered; the inputs were wrong), which is exactly why this is Medium and not High — but it would
show where there is no net at all. A scan for main classes with no matching test file returns a long
list including `AuthController`, `ImportController`, `AccountRepository` and `JwtService`.

**Expected impact.** Turns "we have a lot of tests" into a number, and shows which packages are
genuinely unguarded.

**Effort.** Small — one plugin, one report.

**Risks / trade-offs.** The real risk is cultural: a coverage number invites a coverage *target*,
and targets produce tests written to move the number rather than to catch bugs. Recommend adopting
it as a report to read, explicitly **not** as a CI gate, at least initially. If a gate is ever added,
ratchet-only (never decrease) is far healthier than a fixed percentage.

**Dependencies.** None.

**Resolution.** JaCoCo added as a report only — no `check` goal, no threshold, nothing that can
fail the build — bound to `test` so `./mvnw test` produces it with no extra step. DTOs and entities
are excluded so the number reflects logic that could be wrong rather than accessors.

First run: **78.3% instructions, 74.4% branches.** Healthier than expected. But the headline is a
single anomalous row, and it is not a coverage problem:

```
com.finora.controller     0.0% instructions   0.0% branches   1700 missed
```

Controllers are exercised only by the `*IT` classes, and **those have never run.** Surefire's
default includes are `Test*.java`, `*Test.java`, `*Tests.java`, `*TestCase.java`; `*IT.java` is the
maven-failsafe convention, and failsafe is not configured in this project. Confirmed by diffing
`target/surefire-reports/` against the test sources: 149 test classes exist, 122 ran, **29 never
did** — `AuthFlowIT`, `AdminRbacIT`, `PasswordChangeFlowIT`, `GlobalAuditLogIT`,
`TransactionRepositoryIT`, `MerchantMergeIT`, and all 21 admin controller ITs. They compile, they
look like coverage in review, and they assert nothing anywhere. It also means `ci.yml`'s own claim
that the suite includes "the Testcontainers ones" has never been true.

This is exactly the class of thing this item existed to surface, and it is a stronger result than
"where is there no net" — it is "a third of the safety net was never attached".

**Deliberately not fixed here.** Adding `**/*IT.java` to surefire's includes does make them run;
that was verified (970 tests collected instead of 941). Whether they *pass* is unknown, because
they have never executed once. Enabling 29 never-run test classes on `main` without that answer
could red the pipeline for the whole team, so it needs its own change, verified against a working
Docker daemon. See the follow-up note below.

**Follow-up: the Docker blocker.** Verifying that locally is currently impossible on Docker Desktop
29.x. Engine 29 raised its minimum API version to 1.40; the docker-java bundled with Testcontainers
1.20.1 (this project's pin) negotiates 1.32, and every container creation fails with
`client version 1.32 is too old`. Bumping to 1.21.3 did not help, and `DOCKER_API_VERSION` breaks
the ping path instead. CI is unaffected today because `ubuntu-latest` still ships an older engine
— which is precisely why this would otherwise go unnoticed until it blocks a developer. Worth its
own investigation alongside enabling the ITs.

---

### 8. One implementation of client-IP resolution

**What.** `ClientIpResolver` and `RateLimitFilter.resolveClientIp()` implement byte-identical
spoofing-safe X-Forwarded-For logic. `ClientIpResolver`'s own class comment states the duplication
is deliberate and ends: *"keep the two in sync if this logic ever changes."*

**Why it is valuable.** "Keep these in sync" is a comment where a mechanism should be. This exact
instruction, in this exact form, is what item 2 documents going wrong across the three API clients —
and the original reason for duplicating (not wanting to touch the filter's tested internals) is
weaker now that `RateLimitFilter` has been modified and its test suite extended anyway. The security
property involved is not decorative: getting it wrong in one direction collapses every user onto one
rate-limit bucket, and in the other lets anyone spoof any IP.

**Expected impact.** Removes a documented divergence hazard on a security-relevant code path.

**Effort.** Small — inject the existing `ClientIpResolver` bean into the filter and delete the
private copy.

**Risks / trade-offs.** `RateLimitFilter` currently reads `trust-proxy-headers` via a field-injected
`@Value`, and its tests set that field with `ReflectionTestUtils`; switching to an injected
collaborator changes how those tests construct the filter. Low risk, but it is test churn on a
filter that was just modified, so it is worth letting `6ee925a` settle first.

**Dependencies.** None.

---

## Low priority

### 9. Code-split the two web bundles

**What.** Production builds emit single chunks past Rolldown's 500 kB warning: `frontend`
**823.68 kB** (243.67 kB gzip), `admin-portal` **554.54 kB** (156.91 kB gzip). Both builds also warn
that `src/api/endpoints.ts` is dynamically imported by `client.ts` while also being statically
imported by many components, so the dynamic import does not actually split anything.

**Why it is valuable.** The user app's bundle is downloaded before a visitor sees the landing page.
243 kB gzipped is not catastrophic, which is why this is Low — but it is paid by every first-time
visitor on a phone, and the marketing site and the authenticated app currently ship as one artifact
even though a logged-out visitor needs none of the latter.

**Expected impact.** Faster first paint for logged-out visitors, and a smaller admin bundle for a
tool used by few people on good connections (which is why admin matters less).

**Effort.** Medium. Route-level `React.lazy` is straightforward; resolving the
`INEFFECTIVE_DYNAMIC_IMPORT` warning means untangling the static/dynamic split of `endpoints.ts`,
which is exactly the file item 2 would move.

**Risks / trade-offs.** Lazy routes introduce loading states that do not exist today and can produce
a flash of nothing on slow connections — which, without item 1's error boundaries, is
indistinguishable from the blank-page failure just fixed. Do item 1 first.

**Dependencies.** Cleaner after item 2. Should follow item 1.

---

### 10. Move rate-limit state out of process before a second instance exists

**What.** `RateLimiter` is an in-memory `ConcurrentHashMap` scoped to one JVM, as its class comment
states plainly. `ImportConcurrencyLimiter` is likewise an in-process semaphore and says the same.

**Why it is valuable.** Both classes are honest that they are correct for a single Railway instance
and insufficient beyond it. Listed here not because it needs doing now — it does not — but because
it is the one piece of the architecture with a known, documented expiry condition, and the moment it
expires (the day a second instance is started for availability or load) every limit silently becomes
N× more permissive with nothing failing. The failure mode is silent, which is what makes it worth
writing down rather than remembering.

Note also that `6ee925a` fixed *which requests* the limiter matches; it did not change *where the
counters live*. Those are independent, and only the first is done.

**Expected impact.** None today. Prevents a silent security regression on the day of horizontal
scaling.

**Effort.** Medium, and mostly infrastructure: it means running Redis, which the repo currently has
no dependency on.

**Risks / trade-offs.** Doing this before there is a second instance is exactly the speculative
infrastructure the project has deliberately avoided — Redis for a single-instance app is complexity
with no payoff, and the existing comments make the right call. The actionable version of this item is
not "build it" but "add it to the deployment checklist as a precondition for scaling out", which
costs nothing.

**Dependencies.** Triggered by a scaling decision, not by code.

---

### 11. Set a policy for the Expo toolchain advisories

**What.** `npm audit` in `mobile/` reports **14 moderate** vulnerabilities. Every one is transitive
through the Expo toolchain — `@expo/cli`, `@expo/config-plugins`, `@expo/metro-config`,
`expo-sharing`, and `xcode` → `uuid` (*missing buffer bounds check in v3/v5/v6*). None are in code
that ships to a device; they are build-time and CLI dependencies.

**Why it is valuable.** Not for the vulnerabilities themselves — the honest reading is that these
are low-consequence for a build toolchain. It is valuable because 14 standing advisories train
everyone to ignore `npm audit` output in this repo, which is precisely how a real advisory in
shipped code (item 4) gets missed. The fix is a decision, not a patch.

**Expected impact.** `npm audit` becomes a signal again.

**Effort.** Small. Either pin/override the `uuid` transitive, or record an accepted-risk note with a
re-review date, or add `npm audit --omit=dev --audit-level=high` to CI so shipped-code advisories
fail while toolchain noise does not.

**Risks / trade-offs.** Overrides on an Expo toolchain can break `expo export`, which CI depends on
for the Metro bundle job. An accepted-risk note carries no such risk and is probably the right first
move; overrides should wait for Expo's own upstream fix.

**Dependencies.** None.

---

### 12. Establish an accessibility baseline for the web apps

**What.** No accessibility tooling exists anywhere in the repo — no `eslint-plugin-jsx-a11y`, no
`axe-core`, nothing in CI. Counting accessibility attributes by hand:

| App | Source files | `aria-*` / `role=` (web) or `accessibility*` (RN) |
|---|---|---|
| `mobile/` | 46 | **51** |
| `admin-portal/` | 70 | 22 |
| `frontend/` | 63 | 21 |

**Why it is valuable.** The pattern is consistent and worth naming: the mobile app, built most
recently, uses roughly three times the accessibility annotation per file that either web app does —
`ImportScreen.tsx` alone sets `accessibilityRole` and `accessibilityState` on every interactive
chip. The web apps predate that habit. For a personal-finance product, keyboard and screen-reader
access to one's own financial records is a baseline expectation and, depending on market, a
compliance question.

**Expected impact.** Unknown, and stated as unknown deliberately — **no accessibility audit was
performed**, and attribute counts are a proxy for effort, not a measure of conformance. The first
deliverable should be a measurement, not a fix.

**Effort.** Medium, and genuinely unbounded until measured. `eslint-plugin-jsx-a11y` is a Small
first step that would turn this from a guess into a list.

**Risks / trade-offs.** Adding the eslint plugin to two apps that were not written against it will
produce a large finding list at once, which is demoralising and tempts blanket disables. Recommend
adding it in warning mode alongside item 3 rather than as a separate initiative, and fixing by area
rather than all at once.

**Dependencies.** Cheapest bundled with item 3, since it is the same CI job and the same config file.

---

## Recommended order

Sequenced by what unblocks or de-risks what, not strictly by priority:

1. **Item 3** (linters in CI) — smallest, currently green, and it is the enforcement floor
   everything else lands on top of. Bundle **item 12**'s eslint plugin in warning mode here.
2. **Item 1** (crash reporting + error boundaries) — until this exists, no other change's failures
   in production are observable, including the ones proposed below. It is also a prerequisite for
   item 9.
3. **Item 4** (router advisory) — the only shipped-code security item, and it benefits from the
   route tests that now exist.
4. **Item 7** (coverage report) — cheap, and it should inform how much test-writing items 5 and 6
   actually need.
5. **Item 8** (client-IP consolidation) — small, and a natural warm-up for item 2's argument.
6. **Item 2** (shared client layer) — the highest-value structural change and the most expensive.
   Consider the cheap 80% first: a test asserting the three clients' interceptor rules agree. Do
   this before more surfaces exist to copy into.
7. **Items 5, 6** — both need characterisation tests before the change itself; schedule as
   "tests then refactor", not as refactors.
8. **Items 9, 11** — opportunistic.
9. **Item 10** — not now. Add to the deployment checklist as a precondition for running a second
   instance.
