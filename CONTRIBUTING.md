# Contributing to Finora

This is the day-to-day reference for the workflow defined in
[`docs/engineering-directive-phase1.md`](docs/engineering-directive-phase1.md), Priority 1. Read
that doc for the *why*; this one is the *how*.

## One-time setup

```bash
git clone <repo-url>
cd finora
npm install          # installs husky and commitlint at the repo root
```

`npm install` runs the root `prepare` script, which wires `.husky/` up as the active git hooks
directory and installs `commitlint` for the `commit-msg` hook (`npx --no -- commitlint`, which
deliberately refuses to auto-install a missing package rather than silently fetching one at
commit time). `pre-commit` itself (see below) is a dependency-free `sh` script that doesn't need
`npm install` to have run at all — it shells out directly to `python`, per-app `npx`, and
`./mvnw`.

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

`.husky/pre-commit` is a plain `sh` script (not `lint-staged`) that runs a fixed sequence against
whatever's staged:

1. `scripts/check-fixture-hygiene.sh` — always. Blocks a commit that adds an email address, IFSC
   code, or Indian mobile number that doesn't look like a placeholder (see the Synthetic Fixture
   Policy in `docs/engineering/financial-document-intelligence-principles.md`); warns on long
   digit sequences it can't classify either way.
2. `scripts/check-client-auth-policy.py` — only when `frontend/`, `admin-portal/`, or `mobile/`'s
   `src/api/client.ts` is staged. Fails if the three clients' unauthenticated-endpoint lists drift
   apart (see the script's own docstring for the incident this guards against).
3. `npx eslint --fix` against staged files, re-adding what it changes — for whichever of
   `frontend/`, `admin-portal/`, and `mobile/` have staged files. All three apps have a working
   ESLint config today.
4. `./mvnw -q -o compile` — only when `backend/` files are staged. Blocks the commit if the
   backend doesn't compile.

Full test suites intentionally stay in CI (`.github/workflows/ci.yml`) rather than here — a
pre-commit hook slow enough to notice is a pre-commit hook people bypass with `--no-verify`.

## Database changes

Never edit the schema by hand. Add a new versioned Flyway migration under
`backend/src/main/resources/db/migration/` (`V{next}__description.sql`) — check that directory
for the current highest `V` number before picking the next one; this list grows with every
schema change, so no fixed range is quoted here.
