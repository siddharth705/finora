# CI/CD: deferred future work

A 2026-08-17 review of `.github/workflows/ci.yml` and `e2e-nightly.yml` against a standard
CI-optimization checklist (dependency caching, job parallelization, workflow separation, test
strategy, Docker layer caching, path-based triggers) found the pipeline already does most of what
that checklist asks for — see the review itself for the full per-item evidence. Two genuine,
zero-tradeoff gaps were found and fixed immediately (npm caching missing from the `smoke` and
nightly-e2e jobs). This document tracks the three items that came up but were deliberately
**not** done as part of that pass, because each one trades away either test coverage, cost, or
touches a workflow file that has already caused real incidents when changed carelessly. Documented
so they're revisited deliberately, not forgotten or done as a drive-by edit.

## Priority, per 2026-08-17 follow-up discussion

None of the three are blocking anything, so this is sequencing rather than a deadline:

1. **RFC-CI-03's measurement, not its hardware.** Evaluating runner capacity (cost, the Mac's
   actual CPU/RAM ceiling, where wall-clock time really goes, whether parallel execution would
   even help) is cheap, high-signal, and doesn't touch a single workflow file — do this first.
   Acting on it (registering a second runner) stays gated on that data, consistent with
   `self-hosted-runner.md`'s own "a deliberate response to a *measured* queue."
2. **RFC-CI-02's test-timing visibility, before the split itself.** Publishing per-class/per-module
   backend test durations answers "which tests actually consume CI time" — without that, deciding
   whether/how to split unit from integration tests is guesswork. This is additive instrumentation
   (no coverage risk), so it can proceed independent of the harder split decision.
3. **RFC-CI-01 last.** Path-based triggers save real compute, but Finora has more cross-cutting
   change shapes than most repos its size — backend API changes, DB migrations, frontend contracts,
   shared DTOs — and a careless filter risks silently skipping the job that would've caught a
   regression. Worth doing once the mapping is deliberately designed (see the RFC below), not worth
   rushing for the compute savings alone.

---

## RFC-CI-01: Path-based workflow triggers

**Status:** Future

**Problem:** `ci.yml` has no `paths:`/`paths-ignore:` filters at all. Every job — `backend`,
`frontend`, `admin-portal`, `mobile`, `smoke` — runs on every PR and every push to `main`
regardless of what changed. A frontend-only CSS tweak still runs the full backend Testcontainers
suite; a backend-only change still runs three separate npm installs, lints, and builds.

**Why not fixed now:** GitHub Actions path filters apply at the *workflow* level, not per-job —
there's no way to say "skip just the `backend` job" from `on:` alone. Getting real per-project
skipping needs one of:

- Splitting `ci.yml` into separate workflow files per subproject (`backend-ci.yml`,
  `frontend-ci.yml`, `admin-portal-ci.yml`, `mobile-ci.yml`), each with its own `paths:` filter, or
- Keeping one workflow but adding a `dorny/paths-filter`-based "changes" job that the others
  `needs:` and gate on via `if:`.

Either is a structural change to a file whose current shape (five independent jobs, deliberately
*not* using `needs:` between them, one `permissions:` block instead of five) is itself the result
of past incidents documented in the file's own comments — see the header of `ci.yml` for the
`push`-trigger and `concurrency` history, and `docs/architecture/infrastructure/self-hosted-runner.md`
for the `primary`-label runner-contention incident. A path-filter rework needs the same care,
plus explicit design for the cases the checklist that prompted this review itself flagged:

- Shared config files (root `package.json`, lockfiles, `eslint.config.js` if shared)
- `backend/Dockerfile` and `backend/railway.json`
- Database migrations (`backend/src/main/resources/db/migration/`) — these can affect more than
  just the backend job if any other job's tests seed or assert against schema
- API contract changes (anything under `backend/src/main/java/**/controller` or an OpenAPI spec,
  if one exists) — a backend-only path filter must not let a breaking API change skip the
  frontend/admin-portal/mobile jobs that consume it
- Deployment config (`railway.json`, `.github/workflows/*` itself — a change to the workflow files
  must always run everything, or a broken filter could silently stop testing itself)

Also worth noting: a required-status-check whose workflow never triggers (because paths didn't
match) is currently treated by GitHub as satisfied for merge purposes, but this has changed
behavior across GitHub's history and should be re-verified against current behavior before
depending on it for branch protection.

**Recommended direction:** Define the path→job mapping explicitly (as a small table, ideally
committed alongside the workflow) before writing any YAML, get it reviewed against the shared-file
list above, then implement as a single `changes` detection job (the `dorny/paths-filter` pattern)
rather than a full workflow split — that preserves the existing single-`permissions:`-block,
no-cross-job-`needs:` design for the jobs that DO run, and keeps the blast radius of the change to
one new job plus four `if:` conditions.

---

## RFC-CI-02: PR vs. merge/nightly test strategy redesign

**Status:** Implemented 2026-09-02.

**Outcome:** Investigating why the backend job had grown to 7+ minutes (up from the ~2m20s–2m56s
baseline below) found the ArchUnit fix below (11 classes independently re-scanning the same
`com.finora` classpath, ~200s of it) — but measuring CI's actual timing after that fix landed
showed it made no difference to wall-clock at all: `AbstractIntegrationTest` is `@Isolated`, so
the ~140 `*IT` classes already run serially regardless of unit-test concurrency, and that serial
IT sequence — not redundant unit-test CPU work — is what CI's more core-constrained `ubuntu-latest`
runner was actually bottlenecked on. That pointed straight back at this RFC.

Implemented as specified below: `backend/pom.xml` now has `maven-surefire-plugin` (unit only,
`*IT.java` removed from its includes) and a new `maven-failsafe-plugin` (bound to
`integration-test` + `verify`, `*IT.java` only, sharing surefire's reports directory so
`scripts/summarize-surefire.py` doesn't need to know which plugin produced which report).
`ci.yml`'s "Run tests" step branches on `github.event_name`: a `pull_request` runs `./mvnw test`
(unit only); a push to `main` (or `workflow_dispatch`) runs `./mvnw verify` (the full suite,
matching the "PR's checks + integration tests" design below). `summarize-surefire.py` gained a
`--unit-only` flag — passed only on the PR path — so its "BLOCKED: no `*IT` classes ran" check
still runs, by default, on every push-to-main, which is exactly the run where that check matters.
`jacoco-maven-plugin` gained a second `report` execution bound to `verify`, so the push-to-main
path's coverage report reflects `*IT`-exercised code (`com.finora.controller`, previously 0% before
the original includes fix) instead of silently reverting to unit-only numbers.

Verified locally before merging: `./mvnw test` runs 356 unit classes, 0 `*IT`, build green;
`./mvnw verify` runs the same 356 plus 143 `*IT` classes (499 total, matching the pre-split
combined count), build green, `com.finora.controller` coverage at 70%/52% (not 0%). Both
`summarize-surefire.py` paths tested directly, including the negative case: running it *without*
`--unit-only` against unit-only output correctly returns exit 1 with the BLOCKED message — the
safeguard this whole RFC exists to preserve still fires exactly when it's supposed to.

**2026-09-05 follow-up — the nightly-only cadence this RFC chose for `CapabilityCorpusCoverageTest`
had a real gap.** PR #930 registered four new capabilities in `CapabilityCoverageService
.KNOWN_CAPABILITIES` with no committed trace exercising any of them; because that test runs only on
`corpus-coverage-nightly.yml`'s schedule, the gap sat on `main` undetected for a full day until the
next nightly run failed (fixed in PR #957). This RFC's "safe to defer" judgment for this
specific test is unchanged — it is still a coverage metric, not a correctness gate, and the other
three slow corpus-driven tests correctly stayed on every run for exactly the reason given above.
What was missing was enforcement at the one moment that actually introduces the gap: the PR that
adds a capability. `corpus-coverage-nightly.yml` now also runs on any pull request touching
`CapabilityCoverageService.java`, `CapabilityCorpusCoverageTest.java`, or the committed trace
corpus (`backend/src/test/resources/traces/**`) — a `paths:` filter on the one file where
`KNOWN_CAPABILITIES` is declared, so it only ever adds a run on top of the existing nightly
schedule for the rare PR that can actually create this gap, never removing coverage the way a
careless RFC-CI-01-style filter risks elsewhere in this file.

**Original problem, for context:** `backend`'s `./mvnw test` used to run unit tests (`*Test`, `*Tests`, `*TestCase`) and
integration tests (`*IT`, Testcontainers-backed) together, in one Maven `test` phase invocation, on
every single PR. There's no fast unit-only gate with integration tests deferred to merge or a
scheduled run.

**Why not fixed now — this is the important part, not a footnote:** `maven-surefire-plugin`'s
`includes` in `backend/pom.xml` was *deliberately* widened to catch `**/*IT.java` recently, and
the reason is recorded in `ci.yml`'s own comments on the `backend` job: before that change,
`maven-failsafe-plugin` was never configured, surefire's default includes don't match `*IT.java`,
and so **the 29 `*IT` classes had silently never run at all** — a green backend job was not proof
those tests ever executed. Splitting integration tests back out of the PR gate for speed, without
a deliberate replacement mechanism, risks recreating exactly that hole: a fast green PR pipeline
that quietly stops proving what it used to prove. This is a coverage-vs-speed tradeoff that needs
a specific committed replacement design, not a revert.

**Recommended direction (needs sign-off before implementing, not a mechanical change):**

```
PR:            compile, unit tests (*Test/*Tests/*TestCase), typecheck/lint — fast gate
Merge to main: PR's checks + integration tests (*IT, Testcontainers) — the full mvn test as today
Nightly:       merge's checks + full E2E suite (already exists via e2e-nightly.yml)
```

This needs `maven-failsafe-plugin` actually configured this time (bound to `integration-test` +
`verify`, not left absent the way it was before the recent fix), so `mvn test` on PRs runs only
`*Test`/`*Tests`/`*TestCase` and a separate `mvn verify` (or explicit failsafe invocation) on merge
runs `*IT` — with an explicit CI step, not just a Maven phase boundary, that fails loudly if the IT
count is ever zero, mirroring the safeguard `ci.yml`'s "Test summary" step already has for the
current combined run. Whoever implements this should re-read the incident this guards against in
full before touching `backend/pom.xml`'s surefire/failsafe config.

**Do this part first, before deciding on the split above:** publish per-module or per-class backend
test timing (e.g. a step that summarizes `target/surefire-reports/*.xml` durations, similar to what
`scripts/summarize-surefire.py` already parses for pass/fail) to show which suite actually drives
the backend job's ~2m20s–2m56s runtime, rather than guessing. That's additive instrumentation with
no coverage risk, and the split design above should be informed by its numbers rather than
proceeding in parallel with them.

---

## RFC-CI-03: Runner capacity scaling

**Status:** Future for the hardware decision; the *measurement* can start any time — see
"Priority" above

**Problem:** There is exactly one self-hosted runner (`finora-m5`). All five `ci.yml` jobs are
schedulable in parallel (no `needs:` between `backend`/`frontend`/`admin-portal`/`mobile`) but
execute serially in practice because one runner can only run one job at a time — wall-clock is the
sum of all jobs (~13–14 min warm) rather than the slowest one.

**Why not fixed now:** `docs/architecture/infrastructure/self-hosted-runner.md`'s own "Throughput"
section already states the intended posture directly: "The default posture is **one runner**. A
second is a deliberate, temporary response to a measured queue, not a standing part of the setup —
capacity added during a spike tends to stay." More runners buy wall-clock at the cost of ongoing
spend and, per that same doc, hide whatever inefficiency is actually in the pipeline rather than
fixing it — and the `primary`-label mechanism that keeps `smoke`'s host-level state
(Postgres container, fixed ports) safe under multiple runners adds real operational surface that
doesn't shrink just because a second Mac is registered.

**Recommended direction:** Don't add a second runner as a response to CI feeling slow — but do run
the measurement now rather than waiting, since it's cheap and doesn't touch a workflow file: cost
of a second Mac (or reconsidering hosted runners) vs. the actual wall-clock win, the current Mac's
real CPU/RAM headroom under a full run, and where time is actually going per job (this overlaps
with RFC-CI-02's test-timing instrumentation, which answers the same "where does time go" question
one layer down). Only register a second runner if that data — plus whatever RFC-CI-01 (fewer jobs
per PR) and RFC-CI-02 (shorter backend job) have already bought by the time this is revisited —
still shows CI queuing measurably, per the health-check and throughput guidance already in
`self-hosted-runner.md`. At that point, follow that doc's existing "Adding and removing a second
runner" section rather than reinventing the labeling scheme.

---

## RFC-CI-04: CI duration and flakiness observability

**Status:** Future

**Problem:** Right now, knowing whether CI is trending slower or a particular test has started
flaking means either remembering it anecdotally or manually digging through run history. There's
no standing view of it.

**Recommended direction:** A lightweight dashboard (could be as simple as a scheduled job appending
to a tracked CSV/JSON file and a small chart, rather than standing up a metrics service) tracking:

```
Average CI duration (per job, and total)
p50 / p90 duration
Longest job per run
Flaky tests (pass/fail inconsistency across reruns of the same commit)
```

Overlaps with RFC-CI-02's per-test-class timing idea — the same surefire-report parsing that
answers "which test suite is slow right now" is most of the raw data this would need to track over
time. Worth scoping together rather than as two separate efforts once either is picked up. Not
urgent on its own — this is instrumentation for noticing regressions in CI health, not something
blocking any of RFC-CI-01/02/03.
