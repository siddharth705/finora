# Changelog

All notable changes to this project are documented in this file. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/), and versioning follows
[Semantic Versioning](https://semver.org/) (see `CONTRIBUTING.md`).

## [0.2.0] - 2026-08-07

A toolchain and infrastructure release. No product behaviour changes.

### Infrastructure

The short version, for anyone scanning. Each line is expanded under Changed or Fixed below.

- CI migrated from GitHub-hosted runners to a self-hosted macOS (Apple Silicon) runner, after an
  account billing block stopped hosted runners from starting at all.
- Development and CI standardised on **Java 25**, which required moving Spring Boot 3.3.2 → 3.5.16
  and springdoc 2.6.0 → 2.9.0 with it.
- `python` → `python3` in both places the repository had the same portability bug: the CI workflow
  and the pre-commit hook. On a current macOS or Ubuntu the bare command does not exist.
- The backend jar is no longer referenced by a pinned, version-coupled filename.
- Test suites made to run on Node 22 and later, which they previously could not.
- New: [`docs/infrastructure/self-hosted-runner.md`](docs/architecture/infrastructure/self-hosted-runner.md),
  covering host requirements, a health-check sequence, and rebuilding on a new machine.

### Changed
- **Backend runs on Java 25** (was 21). This needed more than the compiler target: Spring Framework
  6.1's bundled ASM cannot parse class file 69, so component scanning failed in all 57
  `@SpringBootTest` classes, and Byte Buddy 1.14.18 under Mockito 5.11.0 failed every `@Mock` at
  `setUp`, for 762 more errors. Compilation succeeded throughout, so the build looked like it got
  further than it did. Spring Boot moved 3.3.2 → 3.5.16, which ships Spring 6.2 and manages
  versions that read class file 69, so no per-library pin was needed. Verified at 1647 tests, 0
  failures, 0 errors.
- **springdoc-openapi 2.6.0 → 2.9.0.** springdoc is not managed by Boot's BOM, so nothing raised it
  alongside the parent, and 2.6.0 against Spring 6.2 returned 500 from `/v3/api-docs` with a
  `NoSuchMethodError`. The full test suite stayed green through it, because nothing called that
  endpoint — `OpenApiSpecIT` now does.
- **CI runs on a self-hosted macOS runner** rather than `ubuntu-latest`, which stopped starting
  because of a GitHub account billing block. The `smoke` job's Postgres `services:` container
  became an explicit `docker run` (service containers do not run on macOS runners) with a readiness
  gate and teardown, and `playwright install` dropped `--with-deps` (apt-get only). See
  [`docs/infrastructure/self-hosted-runner.md`](docs/architecture/infrastructure/self-hosted-runner.md).
- **CI triggers on pushes to `main` only**, plus `pull_request` and `workflow_dispatch`. Triggering
  on every branch push produced two full runs per pull-request update, which the `concurrency`
  group cannot collapse because it keys on `github.ref` and the two events differ there. Invisible
  waste on hosted runners; the throughput ceiling on one self-hosted machine.

### Fixed
- **Every commit was blocked on macOS**, and every guard step would have failed on a current Linux
  runner: `.husky/pre-commit` and `.github/workflows/ci.yml` invoked `python`, which macOS removed
  in 12.3 and Ubuntu has not shipped since 20.04. Each hook call is `|| exit 1`, so the first guard
  failed with `python: command not found` and stopped the commit — arriving the moment someone ran
  `npm install` to install the hooks, which made installing them look like the cause.
- **The vitest suites failed on Node 22 and later** (33 in `frontend`, 36 in `admin-portal`). Node
  22 added its own experimental global `localStorage`, functional only with `--localstorage-file`;
  without that flag it exists as an accessor returning `undefined`, and vitest's jsdom environment
  aliases `globalThis` to the jsdom window, so it occupied the slot jsdom's own `Storage` would
  have filled. Both `localStorage` and `window.localStorage` were undefined. CI stayed green
  because it pins Node 20.
- The backend jar is no longer referenced by a pinned filename in `ci.yml`, `e2e/scripts/stack.mjs`
  and `e2e/README.md`. It is derived from `pom.xml`'s `<version>`, so a hardcoded name meant every
  release had to bump a string in three places, and whichever was missed failed with "Unable to
  access jarfile" — an error that says nothing about versions.

## [0.1.0] - 2026-07-26

Initial tagged baseline. Everything up to this point was built directly against a working
scaffold rather than through the branch/PR workflow below — this tag is where that workflow
starts being enforced going forward.

### Added
- Core application scaffold: Spring Boot backend (auth, accounts, transactions, budgets, goals,
  recurring detection, net worth, statement import, admin/RBAC groundwork) and a React/Vite
  frontend covering the full authenticated app plus marketing pages.
- Bank logo resolution via a three-stage fallback chain (Brandfetch Logo API → local SVG library
  → colored initials), with a 1.5s timeout so a slow/unreachable CDN never blocks rendering.
- Full-codebase bug review and fix pass, including: word-boundary category matching (previously
  matched substrings like "rent" inside "current"), a cross-tenant data leak in the merchant
  learning audit repository, logout being blocked for unverified users by a security filter,
  CORS origin parsing breaking on a space after the comma, unvalidated request headers reaching
  logs, missing server-side validation on Goals/Budgets amounts, a leaked blob URL, notification
  read-state being shared across accounts on the same browser, a forced-logout path missing a
  localStorage clear, and a stale-cache bug where the Budgets/Goals pages never invalidated the
  shared dashboard query cache after a mutation.

### Engineering
- Phase 1 (Priority 1) engineering governance established per
  `docs/engineering-directive-phase1.md`:
  - Git repository initialized with a strict `.gitignore` (build artifacts, dependencies, local
    env files, IDE configs, logs, local DB state all excluded).
  - `main` / `develop` / `feature`-`bugfix`-`hotfix` / `release` branching model adopted (see
    `CONTRIBUTING.md`).
  - Conventional Commits enforced via a `commit-msg` hook (full `commitlint` ruleset once
    `npm install` has run; a dependency-free fallback check otherwise).
  - Pre-commit lint gate wired up via `lint-staged` (currently scoped to the frontend; backend
    lint/format enforcement is a tracked follow-up).
  - Semantic Versioning + `CHANGELOG.md` adopted starting with this release.

### Known gaps (tracked, not yet addressed)
- Frontend `lint` script has no ESLint config or dependency committed yet — `pre-commit` will
  no-op on frontend files until that's added.
- Backend has no Checkstyle/Spotless (or equivalent) wired into Maven yet.
- Priorities 2–4 of the engineering directive (IAM/RBAC hardening, Docker infra for
  Redis/MinIO, OpenAPI-generated frontend types, etc.) are still open.
