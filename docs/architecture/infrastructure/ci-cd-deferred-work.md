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

**Status:** Future

**Problem:** `backend`'s `./mvnw test` runs unit tests (`*Test`, `*Tests`, `*TestCase`) and
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

**Related, lower-risk idea surfaced alongside this one:** independent of whether/when the
unit/integration split happens, publishing per-module or per-class backend test timing (e.g. a
step that summarizes `target/surefire-reports/*.xml` durations, similar to what
`scripts/summarize-surefire.py` already parses for pass/fail) would show which suite actually
drives the backend job's ~2m20s–2m56s runtime, rather than guessing. That's additive
instrumentation, not a workflow restructure, and could be picked up on its own before or
independent of the split above.

---

## RFC-CI-03: Runner capacity scaling

**Status:** Future, evaluate only after RFC-CI-01/02 land

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

**Recommended direction:** Don't add a second runner as a response to CI feeling slow. Do it only
after RFC-CI-01 (path filters — fewer jobs run per PR) and RFC-CI-02 (unit/integration split —
the slowest job gets shorter for most PRs) have landed and CI is *still* queuing measurably, per
the health-check and throughput guidance already in `self-hosted-runner.md`. At that point, follow
that doc's existing "Adding and removing a second runner" section rather than reinventing the
labeling scheme.
