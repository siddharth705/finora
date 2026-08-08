# Testing & Quality Tooling Review

Evaluation of the proposed engineering ecosystem against what Finora actually runs today.
Companion document: [`ENGINEERING_TOOLING_ROADMAP.md`](ENGINEERING_TOOLING_ROADMAP.md).

**Headline: six of the proposed tools are already implemented. The three highest-value actions on
this list involve installing almost nothing —** wire the Playwright suite we own into CI, put Sentry
on the backend where it is missing, and stop CI jobs from being able to hang for six hours.

> **Status update — 2026-08-08. All three headline actions have since landed.** The Playwright smoke
> suite is a blocking CI job on every PR, backend Sentry exists
> (`com.finora.observability`, with `SentryScrubber` and its own test suite), and every job carries
> `timeout-minutes`. The analysis below is left standing because the reasoning is still worth
> reading, but **its "not in CI" / "zero enforcement" claims about Playwright and its "no backend
> error monitoring" claim are no longer true** — they are annotated inline where they appear. A
> reader acting on the un-annotated text would redo work that is already done.

---

## 1. What already exists

| Proposed | Status | Correctly integrated? | In CI? |
|---|---|---|---|
| Playwright | **Implemented** — 12 spec files / 112 cases, 8 browser/viewport projects, own dev-server orchestration | Yes | **Yes** — Chromium smoke subset, blocking on every PR |
| JUnit | **Implemented** — 1689 tests, 226 classes, 59 Testcontainers `*IT` | Yes | Yes, with published unit/IT breakdown |
| Vitest | **Implemented** — frontend + admin portal (mobile uses Jest) | Yes | Yes, gated |
| JaCoCo | **Implemented** — 0.8.12, report-only **by design** | Yes | Runs; deliberately no gate |
| Dependabot | **Implemented this week** — maven, npm ×4, github-actions | Yes | N/A (opens PRs) |
| **Sentry** | **Implemented on all four tiers** — `monitoring.ts` per client app; backend `com.finora.observability` with `SentryScrubber`, logback appender deliberately off | Yes | N/A |
| PostHog | Not present | — | — |
| Firebase | Phone auth only (web + admin + backend verification) | Yes | — |
| ZAP / Snyk / SpotBugs / PMD / Checkstyle / Semgrep / Trivy / k6 / SonarQube / OpenRewrite | Not present | — | — |

Beyond the list, we run a structural and security layer that overlaps several proposals:

- **ArchUnit** rules — each with a self-test proving it can fail — now formalised into a Guardian
  registry with IDs and ownership.
- **Seven custom static checks** in CI and pre-commit: customer-PII hygiene, client auth-policy drift,
  import cross-reference, XML comment validity, executable bits, contact addresses, dependency
  advisories.
- **Spring Boot Actuator**, exposing `health` only, with `show-details: never`.

---

## 2. The principle this review applies

Finora's recurring failure is not too few tools. It is **tools whose output nobody reads**, and our
own history documents it four times over:

- `check-xml-comments.py` existed for months, wired to nothing, always exiting 0 — the exact shape
  that let the original `pom.xml` incident happen.
- `backend/mvnw` was committed non-executable, so **the backend suite never ran in CI at all** from
  the workflow's creation until 2026-08-05. Three green jobs beside one red one read as flake.
- 29 `*IT` classes never executed for months because surefire's includes did not match `*IT.java`.
  The suite was green at 941 tests while `com.finora.controller` sat at 0% coverage.
- `npm audit` reports 18 advisories, all already judged not to apply. A command whose output is
  always non-empty is one people learn to skip — and a real open redirect sat in that noise.

Every one is a signal-to-noise failure. Adding a dozen finding-emitting tools reproduces it at scale.
**The bar applied below: does this produce a signal someone will act on that we do not already get?**

---

## 3. Per-tool evaluation

### 3.1 End-to-end — Playwright

~~**Already implemented; the gap is enforcement, not capability.**~~ **— enforcement gap CLOSED,
2026-08-08.** The `smoke` job runs Chromium specs against a real backend and database on every PR
and blocks the merge.

Coverage today: 12 spec files / 112 cases — user-portal smoke, authenticated journey, import and
negative paths; admin-portal smoke, merchant-review and learning-queue; cross-app workflow specs for
dashboard consistency, duplicate review, tenant isolation and merchant lifecycle. Eight projects
cover Chromium, Firefox, Edge, plus tablet and mobile viewport emulation.

**Against the requested scope:** authentication ✅, admin portal ✅, user portal ✅, merchant
learning ✅, queue management ✅, duplicate review ✅, cross-application workflows ✅.
~~**Statement upload and the import pipeline are the notable gaps**~~ **— CLOSED.**
`tests/user-portal/import.spec.ts` now covers the upload and import flow. Reports and Settings
remain uncovered.

~~**The problem: none of it runs automatically.** Nine specs that execute only when someone remembers
is the `check-xml-comments.py` shape again — real work, zero enforcement — and the suite keeps
growing, so the gap widens rather than closes.~~

~~**Recommendation: wire it into CI as a fifth job. Highest priority on this list.**~~ **— DONE.**
The `smoke` job is the fifth job, Chromium-only, blocking on every PR. Firefox/Edge/responsive are
still manual (`npm run test:browsers`) rather than nightly — the nightly slot in Phase 1.3 remains
open, and is now the only part of this recommendation left.

**Risk:** flake is what kills E2E suites. Mitigate by starting Chromium-only, requiring deterministic
fixtures, and treating a flaky spec as broken rather than as a retry candidate.

### 3.2 Backend — JUnit

Strong. 1689 tests including 59 Testcontainers integration classes running against real Postgres,
plus 31 ArchUnit Repository Guardian rules and a published unit/IT split on every run.

Against the requested list: unit ✅, integration ✅, transaction ✅, import pipeline ✅, security ✅
(`AdminEndpointAuthorizationTest`, `AuditActorAttributionTest`, `ValidatedRequestBodyTest`).
**Concurrency, worker, retry and idempotency tests are thinner** and worth targeted work — the
import pipeline moved to async workers and queues, and those are exactly the paths where a missing
test is expensive. That is test-writing, not tooling.

**Recommendation: no new tool. Invest in worker/retry/idempotency coverage.**

### 3.3 Frontend — Vitest

In use and gated, `--max-warnings 0`. Component, hook and utility coverage exist across frontend and
admin portal. Mobile uses Jest because Expo ships `jest-expo`; same role, and consolidating them
would buy nothing.

**Recommendation: no change.**

### 3.4 Security testing — OWASP ZAP

**Later, and specifically after Playwright is in CI.** DAST needs a running app with authenticated
sessions — the same stack orchestration the Playwright job will provide. Building it twice is waste.

Also note part of the requested scope is already covered statically and more cheaply:
`AdminEndpointAuthorizationTest` fails the build on an unguarded admin endpoint (broken access
control), and `SafeHttpUrl` plus the admin-portal render guards cover the URL-injection class.

**Can it be automated in CI?** Yes — ZAP baseline scan against the E2E stack, as a nightly job, not
per-push. A full active scan takes far too long for push feedback.

Effort ~L. Maintenance high (auth scripting, false-positive triage).

### 3.5 Dependency security — Dependabot ✅ / Snyk ❌

**Dependabot is now configured** for maven, npm ×4 and github-actions. The Maven side was the real
hole: Spring Boot, Jackson, the Postgres driver and Firebase Admin had **no CVE monitoring at all**
in a backend that parses bank statements.

**Docker: added in this change.** Dependabot now watches `backend/Dockerfile`'s base images (it is
multi-stage — both `maven:3.9-eclipse-temurin-21` and `eclipse-temurin:21-jre-alpine`). Note this
watches the *tag*, telling us a newer base exists — not whether the current one has known CVEs.
Trivy (§3.9) answers that second question, which is why the roadmap keeps both.

**Snyk: no.** It overlaps Dependabot (dependencies) and Semgrep (SAST) almost entirely. Its
differentiators — reachability analysis, licence compliance, container scanning — are either covered
by Trivy or are not problems we have. A third scanner reporting the same findings is how alert
fatigue starts. Revisit only if Dependabot proves insufficient in practice.

### 3.6 Static analysis

| Tool | Detects | False positives | Maintenance | Verdict |
|---|---|---|---|---|
| **Semgrep** | Hardcoded secrets, unsafe APIs, injection shapes, OWASP Top 10 | Low–med with a tuned ruleset | Medium | **Adopt, phase 2** |
| **SpotBugs** | Null deref, concurrency bugs, resource leaks | Medium; large first run | Medium | **Adopt with a baseline, phase 2** |
| **PMD** | Complexity, duplication, unused code | High on an existing codebase | High | **Decline** |
| **Checkstyle** | Formatting, naming | Very high without an agreed style | High | **Decline** |

**Semgrep** is the best of the four: it catches classes our guards do not, and complements ArchUnit
(structure) and the PII checks (data hygiene). Adopt gating on `ERROR` severity only, with every
exclusion carrying a written reason — the same discipline as `check-dependency-advisories.py`.

**SpotBugs** finds real defects (null-deref, concurrency) that tests and ArchUnit miss. The catch is
the first run on a 1200-test codebase: a large list, mostly low-value. **Only worth adopting with a
baseline file** that accepts today's findings and gates on new ones. Without that it is muted within
a week.

**PMD and Checkstyle enforce style and maintainability heuristics, and we have no agreed style
document to enforce.** Retrofitting either produces thousands of findings that must be bulk-suppressed
(making the tool meaningless) or bulk-fixed (a large diff, no behaviour change, real review cost).
Neither has caught a class of bug we have actually hit. `CODING_STANDARDS.md` plus review is doing
this adequately.

### 3.7 Performance — Grafana k6

**Right tool, premature.** Load testing with no measured problem and no scaling trigger fired is
speculative infrastructure, and our standing rule is to measure before optimising.
`docs/engineering/scaling-triggers.md` already records the thresholds that *would* justify it.

**The correct sequence is: export metrics (§3.11) → watch for a trigger → load-test the specific
thing the trigger names.** Adopting k6 before metrics exist means load-testing without being able to
observe the result, which produces numbers nobody can act on.

**When it becomes worth it:** the import pipeline and its workers/queues are the right first target,
because that is where concurrency actually lives.

### 3.8 Coverage — JaCoCo

Installed, running, report-only. **I recommend explicitly against converting it into a gate.**

The reasoning is already recorded in `backend/pom.xml`: the rate-limit bypass we shipped had five
dedicated tests that all passed. Coverage measures whether a line executed, not whether it was
verified. A percentage gate converts a diagnostic into a target, and the cheapest way to hit a target
is low-value tests.

Its current job — **finding blind spots** — is the valuable one, and it has already done it once,
exposing `com.finora.controller` at 0% and revealing that 29 IT classes had never run.

**Recommendation: keep informational. Publish the report as a CI artifact so trends are visible
without being enforced.**

### 3.9 Container security — Trivy

**Adopt, phase 2, scoped to image scanning only.** `backend/Dockerfile` ships as a container on
Railway, and base-image OS CVEs are covered by nothing else we run. Its dependency scanning would
duplicate Dependabot — scope it narrowly to avoid that.

Effort ~2h. Maintenance low. CI: a step in the backend job or a nightly scan.

### 3.10 Code quality platform — SonarQube / SonarCloud

**Decline both, for now.**

| | SonarCloud | SonarQube CE |
|---|---|---|
| Cost | **Free only for public repos** — ours is private, so paid | Free licence, but you operate the server |
| Effort | Low | High (host, upgrade, back up) |
| Overlap | ~SpotBugs + Semgrep + JaCoCo combined | Same |

The quality-gate concept is genuinely good. The packaging is not worth it at our size when the
constituent tools can be adopted individually and tuned per-tool. Revisit when team size makes a
central dashboard more valuable than per-tool control.

### 3.11 Error monitoring & observability — Sentry

**Already implemented on frontend, admin portal and mobile.** Each has `monitoring.ts` with tests,
PII scrubbing appropriate to a financial app, and a DSN-gated no-op default so absent config
degrades cleanly rather than half-working.

**The backend has none.** `backend/pom.xml` contains no Sentry dependency. So the tier that runs the
import pipeline, the async workers, the queues and every financial mutation is the one tier with **no
error monitoring at all**, while all three clients have it. The message asks specifically about
worker failures, queue failures and import failures — every one of those is invisible today beyond
whatever reaches application logs.

**This is the highest-value new integration on the entire list.** Effort ~4h, mostly because the PII
scrubbing rules must match the standard `mobile/src/lib/monitoring.ts` already sets — a stack trace
from the import pipeline can carry statement contents, and that must never leave the building.

**Metrics are a separate and also-missing half.** Actuator is present but exposes `health` only, and
there is no Micrometer/Prometheus registry, so nothing emits request rates, error rates, latencies,
queue depth or worker throughput. Sentry tells you *something broke*; metrics tell you *something is
degrading*. The k6 work in §3.7 is not meaningfully actionable until this exists.

**Recommendation: backend Sentry now; Micrometer + Prometheus endpoint next; dashboards after.**

### 3.12 Product analytics — PostHog

**Decline the platform; the useful parts are already covered or actively unwanted.**

- **Feature flags — already built.** `FeatureFlagService` plus the `V32__feature_flags.sql` migration
  give us server-side flags with an admin UI. PostHog would duplicate this, and splitting flags
  across two systems is worse than either alone.
- **Session replay and heatmaps — recommend against, on a financial product.** Replay on our screens
  records account balances, transaction narrations and statement contents. Even with masking, the
  default posture of a replay tool is "capture everything", which is the opposite of the posture the
  rest of this codebase takes (see the PII scrubbing in every `monitoring.ts` and
  `check-fixture-hygiene.sh`). The compliance and trust cost outweighs the UX insight.
- **Funnels and feature usage — genuinely useful,** and the listed questions (registration
  completion, import completion, subscription conversion) are real product questions.

**Recommendation: defer PostHog. If product analytics become a priority, adopt event tracking only —
explicitly disabling replay and heatmaps — and keep feature flags where they are.** Note this is a
product decision with privacy implications, not an engineering one, and it should be made
deliberately rather than by installing an SDK.

### 3.13 Firebase scope

**Your instinct is right, and I would keep the boundary exactly where you have drawn it.**

Current usage is narrow and appropriate: phone verification via `FirebaseConfig`, `PhoneController`
and the two web apps' `phoneAuth.ts`. Financial data and business logic have no Firebase dependency.

**Keep to:** phone auth, push notifications (future), and mobile-support services (crash-free
sessions if ever wanted — though Sentry already covers that).

**Avoid:** Firestore or Realtime Database for anything transactional. We have Postgres with Flyway
migrations, referential integrity and a real transaction model; the import pipeline depends on all
three. Firebase Auth as the primary identity store would also be a mistake — our RBAC, roles and
permissions are relational and admin-managed.

**The one thing worth watching:** Firebase is a hard dependency of the login path for phone
verification. That is an availability coupling on a critical flow. Worth confirming the fallback
behaviour if Firebase is unreachable — but that is a resilience question, not a tooling one.

### 3.14 Automated refactoring — OpenRewrite

**Not a pipeline member — a power tool.** OpenRewrite is something you *run* during a migration, not
something you *adopt* continuously. It has no place in CI.

When we upgrade past Spring Boot 3.3.2 or move Java versions, running the relevant recipe is the
right call and takes an afternoon. **Nothing to install today.**

---

## 4. CI/CD pipeline review

Current state: one workflow, four jobs (backend, frontend, admin-portal, mobile), running in parallel
with no `needs:` chain. Dependency caching is configured per app. Concurrency cancels superseded runs
on branches but never on `main`. The backend job runs its cheap static guards *before* `setup-java`,
so a policy violation fails in seconds rather than after a 2m30s test run — that ordering is good and
should be preserved.

**Findings, highest value first:**

| # | Finding | Why it matters | Effort |
|---|---|---|---|
| 1 | ~~No `timeout-minutes` on any job~~ **— DONE in this change** | A hung Testcontainers job ran to GitHub's **6-hour** default, burning the runner allowance and blocking the serialized `main` queue behind it. Now 20 min (backend) / 15 min (JS jobs) — hang detectors, not performance budgets | done |
| 2 | ~~**Playwright not executed**~~ **— DONE** | Was 9 specs with zero enforcement. Now the `smoke` job: Chromium, real backend and database, blocking on every PR | done |
| 3 | **No nightly / scheduled jobs** | Cross-browser E2E, ZAP baseline and Trivy scans all want a nightly slot rather than per-push | ~1h |
| 4 | **No artifact retention except surefire-on-failure** | JaCoCo HTML and the Playwright HTML report are the two worth publishing | ~30 min |
| 5 | Every push runs all four jobs, including docs-only commits | Wasted minutes — but `paths-ignore` is a footgun that can make required checks never run on a PR. Recommend **only** if branch protection is configured to tolerate it | ~30 min, with care |

**Parallelism and caching are already correct** and need no change. Feedback time is ~2m40s wall
clock, which is healthy; the backend job dominates and is dominated in turn by Testcontainers
startup.

**Recommendation: do #1 immediately** — it is a five-minute change guarding against a six-hour
failure mode, and the cheapest item in this entire document.

---

## 5. Summary table

| Tool | Implemented? | Recommended? | CI/CD | Effort | Maintenance | Priority |
|---|---|---|---|---|---|---|
| Playwright | Yes | **Wire to CI** | New job | S–M | Med | **P0** |
| Sentry (backend) | **No** | **Yes** | N/A | M | Low | **P0** |
| CI job timeouts | **Yes (this change)** | Done | ci.yml | XS | None | ✅ |
| JUnit | Yes | Deepen worker/retry tests | Yes | — | Low | P1 |
| Metrics (Micrometer) | No | Yes | Scrape endpoint | M | Med | P1 |
| Vitest | Yes | Keep | Yes | — | Low | — |
| Dependabot | **Yes, incl. `docker`** | Done | N/A | XS | V.low | ✅ |
| Semgrep | No | Yes | Gate on ERROR | M | Med | P2 |
| Trivy | No | Yes, image-only | Nightly | S–M | Low | P2 |
| SpotBugs | No | Yes, with baseline | Backend job | M | Med | P2 |
| JaCoCo | Yes | Keep **ungated** | Artifact | XS | Low | P2 |
| OWASP ZAP | No | Later | Nightly | L | High | P3 |
| PostHog | No | Events only, if ever | N/A | M | Med | P4 |
| k6 | No | On trigger only | Nightly | L | Med | P4 |
| Snyk | No | **No** | — | — | — | — |
| PMD | No | **No** | — | — | — | — |
| Checkstyle | No | **No** | — | — | — | — |
| SonarQube/Cloud | No | **No** | — | — | — | — |
| OpenRewrite | No | Use per migration | Never | S | None | — |

---

## 6. The trade-off, stated plainly

Adopting everything proposed would give Finora more findings and less signal. We have concrete,
documented evidence that this team's failure mode is guards nobody reads — a suite that never ran for
weeks, a script that always exited 0, an audit stream trained to be ignored.

Every tool added to CI spends a finite budget: **the team's willingness to believe a red build.**

The three P0 items spend almost none of it. One is a config line. One runs tests that are already
written and already trusted. One puts error reporting on the only tier that lacks it.
