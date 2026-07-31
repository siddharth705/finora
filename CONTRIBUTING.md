# Contributing to Finora

This is the day-to-day reference for the workflow defined in
[`docs/engineering-directive-phase1.md`](docs/engineering-directive-phase1.md), Priority 1. Read
that doc for the *why*; this one is the *how*.

## One-time setup

```bash
git clone <repo-url>
cd finora
npm install          # installs husky, commitlint, lint-staged at the repo root
```

`npm install` runs the root `prepare` script, which wires `.husky/` up as the active git hooks
directory. If you skip this step the hooks still run (they're committed to the repo and
`core.hooksPath` is already set), but `commit-msg` and `pre-commit` fall back to lighter,
dependency-free checks instead of the full `commitlint`/`lint-staged` rule sets — you'll see a
warning printed on every commit reminding you to run `npm install`.

## Branching strategy

Direct commits to `main` or `develop` are not allowed. Everything happens on a branch:

```
main (production releases only)
  ▲
  │ (tagged releases)
release/*
  ▲
develop (integration branch)
  ▲
  ├── feature/*   new work
  ├── bugfix/*    non-urgent defect fixes
  └── hotfix/*    urgent production patches, branched from main
```

- Branch off `develop` for `feature/*` and `bugfix/*` (e.g. `feature/goal-contributions-api`).
- Branch off `main` for `hotfix/*` when production is broken and can't wait for the next release
  train; merge the hotfix back into both `main` and `develop`.
- `release/*` branches cut from `develop` when it's stable enough to ship; only bug fixes land on
  a release branch, no new features. Merges into `main` (tagged) and back into `develop`.
- Open a PR into `develop` (or `main` for hotfixes) rather than merging locally, so there's a
  review point.

## Commit messages

Enforced by the `commit-msg` hook. Format:

```
type(scope): subject
```

Allowed types: `feat` `fix` `docs` `style` `refactor` `perf` `test` `build` `ci` `chore` `revert`.

```
feat(auth): implement JWT refresh token rotation
fix(import): resolve balance rounding error in CSV parser
refactor(accounts): isolate bank registry into core module
```

Scope is optional but encouraged — it's usually the module or feature area (`auth`, `import`,
`accounts`, `budgets`, `goals`, ...). The subject explains *why*, not just *what*, wherever the
change isn't self-evident from the type/scope alone.

## Versioning

[Semantic Versioning](https://semver.org/): `MAJOR.MINOR.PATCH`.

- `0.x.y` while pre-1.0 — breaking changes are expected and fine, bump `MINOR`.
- `1.0.0` marks the first production baseline; after that, breaking API/schema changes bump
  `MAJOR`.
- Every milestone release gets a Git tag (`vX.Y.Z`) and an entry in
  [`CHANGELOG.md`](CHANGELOG.md).

## Pre-commit checks

The `pre-commit` hook runs `lint-staged` against staged files once dependencies are installed.
Currently wired up for the frontend (`frontend/**/*.{ts,tsx}` → `npm run lint`). Note: the
frontend's `lint` script isn't fully wired yet — see the open item in
`docs/engineering-directive-phase1.md` Priority 4 (no ESLint config/deps committed yet). Backend
lint/format enforcement (Checkstyle or Spotless via Maven) isn't set up yet either — tracked as a
Priority 4 follow-up, not part of this Priority 1 pass.

## Database changes

Never edit the schema by hand. Add a new versioned Flyway migration under
`backend/src/main/resources/db/migration/` (`V{next}__description.sql`) — see the existing
`V1`–`V15` migrations for the naming convention already in use.
