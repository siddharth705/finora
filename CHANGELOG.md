# Changelog

All notable changes to this project are documented in this file. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/), and versioning follows
[Semantic Versioning](https://semver.org/) (see `CONTRIBUTING.md`).

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
