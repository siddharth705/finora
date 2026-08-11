# Engineering Tooling Roadmap

Phased plan derived from [`TESTING_AND_QUALITY_TOOLING_REVIEW.md`](TESTING_AND_QUALITY_TOOLING_REVIEW.md).

**Sequencing principle: close the gaps in things we already own before adding anything new.** Six of
the proposed tools are already implemented; two of them are not enforcing anything, and one whole
tier of the system has no error reporting. Those are worth more than any new adoption.

> **Status update — 2026-08-08. Phase 1 is done and the backend error-reporting gap is closed.**
> Playwright runs as a blocking `smoke` job on every PR, and backend Sentry exists
> (`com.finora.observability`). The phases below are left as written because the sequencing argument
> is still the right one, but **the present-tense "not enforcing anything" and "no error reporting"
> framing is out of date** and is annotated where it appears. What actually remains in Phase 1 is the
> nightly cross-browser slot (1.3).

---

## Deviations from the proposed phasing

The suggested order was Foundation → Security → Observability → Analytics → Performance →
Modernization. Three changes:

1. **Observability moves ahead of Security.** ~~The backend has no error monitoring at all. A
   production exception in the import pipeline is currently invisible.~~ **— DONE.** Backend Sentry
   landed (`com.finora.observability`); the reordering argument is kept because it is why that work
   came first. That outranked scanning for vulnerabilities we have no evidence of.
2. **Performance moves to "on trigger", not a phase.** k6 without metrics means load-testing
   something you cannot observe. It is gated on Phase 2 existing, and on a scaling trigger firing —
   not on a calendar.
3. **Modernization is removed as a phase.** OpenRewrite is a tool you run during a migration, not one
   you adopt. SonarQube is declined. There is nothing left in that phase.

---

## Phase 0 — Same-day (hours, not days)

The cheapest items on the list, all guarding failure modes we have already experienced.

| Item | Effort | Why now |
|---|---|---|
| ~~`timeout-minutes` on all four CI jobs~~ **DONE** | done | A hung Testcontainers job ran to GitHub's 6-hour default, burning the runner allowance and blocking the serialized `main` queue behind it |
| ~~Dependabot `docker` ecosystem~~ **DONE** | done | `backend/Dockerfile` is multi-stage; both base images now watched |
| **Publish JaCoCo HTML as a CI artifact** | 30 min | The report already generates every run and is thrown away |

**Status: 2 of 3 done in this change.** Remaining: publish the JaCoCo HTML report as a CI artifact.

**Exit criteria:** no CI job can run longer than its budget; coverage trends are viewable without a
local build.

---

## Phase 1 — Enforce what we already own

Nothing new is installed in this phase. Both items are capabilities we paid for and are not using.

### 1.1 Playwright in CI — ~~**P0**~~ **DONE (2026-08-08)**

Was: 9 specs and 8 browser/viewport projects that run only when someone remembers. Now 12 spec files
/ 112 cases, with the Chromium smoke subset blocking every PR.

- ~~Fifth CI job, **Chromium-only**, on every push (~2–3 min)~~ — shipped as the `smoke` job, which
  also stands up Postgres, builds and boots the backend, and runs a production-classpath check
- **Still open:** Firefox / Edge / tablet / mobile projects were to move to the nightly job
  (Phase 1.3). There is no nightly job yet, so they remain manual (`npm run test:browsers`)
- ~~Publish the Playwright HTML report as an artifact on failure~~ — shipped, plus the backend log
- ~~**Blocked on:** `e2e/` settling~~ — resolved

**Effort** ~2h. **Risk:** flake. Start Chromium-only; treat a flaky spec as broken, never as a retry
candidate.

### 1.2 Backend Sentry — **P0**

The tier running the import pipeline, async workers and queues is the only tier with no error
monitoring, while frontend, admin portal and mobile all have it.

- Match the established pattern: DSN-gated, no-op when unset, exactly as
  `mobile/src/lib/monitoring.ts` and both web apps do
- **The work is the PII scrubbing, not the wiring.** A stack trace from the import pipeline can carry
  statement contents, account numbers and merchant narrations. The scrubbing rules in the existing
  `monitoring.ts` files are the standard to match, and this must be tested before a DSN is ever set
  in production
- Scope: unhandled exceptions, worker failures, queue failures, import failures
- Release tracking tied to the commit SHA so an error points at a deploy

**Effort** ~4h. **Maintenance** low once tuned.

### 1.3 Nightly workflow

A scheduled job is the home for everything too slow for per-push feedback.

- Cross-browser + responsive Playwright projects
- Later: Trivy image scan (Phase 2), ZAP baseline (Phase 3)

**Effort** ~1h.

### 1.4 Test depth — no tooling

The thinnest areas of an otherwise strong suite, and all test-writing rather than adoption:

- **Statement upload and import pipeline E2E** — the highest-risk flow in the product currently has
  no end-to-end coverage
- **Worker, retry, idempotency and concurrency tests** — the import pipeline moved to async workers
  and queues; those paths are where a missing test is most expensive

**Exit criteria for Phase 1:** every test we have runs automatically; a backend exception in
production reaches a human.

---

## Phase 2 — Observability depth & security scanning

### 2.1 Metrics — Micrometer + Prometheus endpoint

Actuator exposes `health` only. Nothing emits request rate, error rate, latency, queue depth or
worker throughput, so we cannot tell *degrading* from *broken*, and cannot act on a load test.

- Add `micrometer-registry-prometheus`, expose `/actuator/prometheus`
- **Keep `show-details: never` on health and do not expose the new endpoint publicly** — the existing
  posture on that endpoint is deliberate and correct
- Dashboards after the metrics exist, not before

**Effort** ~M. **Prerequisite for k6 being meaningful.**

### 2.2 Semgrep

Gating on `ERROR` severity only, every exclusion carrying a written reason — the same discipline as
`check-dependency-advisories.py`. Budget a triage pass before it gates, or it becomes the next
`npm audit`.

**Effort** ~4h incl. triage.

### 2.3 Trivy — image scanning only

Base-image OS CVEs are covered by nothing else. Scope narrowly; its dependency scanning duplicates
Dependabot. Nightly, not per-push.

**Effort** ~2h.

### 2.4 SpotBugs — with a baseline

Real null-deref and concurrency findings. **Only adopt with a baseline file** accepting today's
findings and gating on new ones; without that it is muted within a week.

**Effort** ~4h incl. baselining.

---

## Phase 3 — Dynamic security

### 3.1 OWASP ZAP baseline scan

Deliberately after Phase 1.1, because ZAP needs a running app with authenticated sessions — the exact
stack orchestration the Playwright job provides. Building that twice is waste.

Baseline scan nightly against the E2E stack; a full active scan is far too slow for push feedback.

**Effort** ~L. **Maintenance** high — auth scripting and false-positive triage are the real costs.

---

## Phase 4 — On evidence only, not on a schedule

### 4.1 Grafana k6 — gated on a scaling trigger

`docs/engineering/scaling-triggers.md` records the thresholds that would justify this. **Requires
Phase 2.1 metrics to exist**, otherwise a load test produces numbers nobody can act on.

First target when it fires: the import pipeline and its workers/queues, where concurrency actually
lives.

### 4.2 PostHog — a product decision, not an engineering one

Deferred, and if ever adopted: **event tracking only, with session replay and heatmaps explicitly
disabled.** Replay on our screens records balances, transaction narrations and statement contents;
that conflicts with the PII posture the rest of the codebase takes. Feature flags stay where they
are — `FeatureFlagService` and `V32__feature_flags.sql` already provide them server-side, and
splitting flags across two systems is worse than either alone.

---

## Not scheduled

| Tool | Reason |
|---|---|
| **Snyk** | Overlaps Dependabot + Semgrep almost entirely; a third scanner reporting the same findings starts alert fatigue |
| **PMD** | High-noise maintainability heuristics on a codebase with no agreed style document |
| **Checkstyle** | Same, worse — retrofit formatting rules produce thousands of findings and no behaviour change |
| **SonarQube / SonarCloud** | Paid on a private repo; roughly the union of SpotBugs + Semgrep + JaCoCo, which we can adopt and tune individually |
| **OpenRewrite** | Not a pipeline member. Run the relevant recipe when upgrading Spring Boot or Java; nothing to install |
| **JaCoCo coverage gate** | Recommended **against**. The rate-limit bypass we shipped had five passing tests. Coverage as a diagnostic is valuable; as a target it produces low-value tests |
| **Firebase expansion** | Keep to phone auth, push, mobile support. No Firestore for transactional data, no Firebase Auth as primary identity — Postgres, Flyway and our relational RBAC are load-bearing |

---

## Effort summary

| Phase | Items | Rough effort | Adds to CI runtime |
|---|---|---|---|
| 0 | 3 | ~1h total | none |
| 1 | 4 | ~8h + ongoing test writing | +2–3 min (Playwright) |
| 2 | 4 | ~12h | +1–2 min push, rest nightly |
| 3 | 1 | ~L | nightly only |
| 4 | 2 | on trigger | nightly only |

Per-push feedback stays under ~6 minutes through Phase 2, which is the number worth protecting —
past roughly ten minutes people stop waiting for CI and start merging on hope.

---

## What this roadmap optimises for

Every phase is ordered so that **the thing that tells you something is wrong exists before the thing
that generates more findings.** Backend Sentry before scanners. Metrics before load tests. Enforcement
of existing tests before authoring new tools.

The finite resource being spent is not engineering hours — it is the team's willingness to believe a
red build. Phase 0 and Phase 1 spend almost none of it, and both are mostly switching on things we
have already paid for.
