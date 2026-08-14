# Load-testing baseline

Measures reality at fixed concurrency tiers against a local docker-compose stack. Not a scaling
exercise — see [`docs/investigations/performance/load-testing-baseline-2026-08-14.md`](../../docs/investigations/performance/load-testing-baseline-2026-08-14.md)
for the results and what they do/don't mean, and [`project-plan-v1.0.md` §5a](../../docs/project-management/plans/project-plan-v1.0.md)
for why this is scoped the way it is.

## Setup

```bash
docker compose up -d --build   # backend + postgres, from the repo root
```

Registration and login are rate-limited per-IP in production, and every request in this harness
comes from `localhost` — one IP. Create `docker-compose.override.yml` at the repo root (gitignored,
never committed) to raise those limits locally before seeding or running:

```yaml
services:
  backend:
    environment:
      RATE_LIMIT_REGISTER_MAX: "500"
      RATE_LIMIT_REGISTER_WINDOW_SECONDS: "60"
      RATE_LIMIT_LOGIN_MAX: "5000"
      RATE_LIMIT_LOGIN_WINDOW_SECONDS: "60"
      RATE_LIMIT_IMPORT_STAGE_MAX: "5000"
      RATE_LIMIT_IMPORT_STAGE_WINDOW_SECONDS: "60"
```

Then `docker compose up -d backend` to pick it up, and seed:

```bash
python3 scripts/load-test/seed.py   # 100 users, 300 transactions each, idempotent
```

## Running a tier

```bash
scripts/load-test/run.sh 100 60s
scripts/load-test/run.sh 500 60s
scripts/load-test/run.sh 1000 60s
```

Results land in `scripts/load-test/results/<n>-users/`: k6's own output, a JSON summary, and a CSV
of backend memory/CPU and Postgres connection-state samples taken every 2 seconds for the duration
of the run.

## Files

- `seed.py` — idempotent user/account/transaction seeding, stdlib-only Python (matches the
  convention in `scripts/requirements.txt`)
- `loadtest.js` — the k6 script: login (cached per VU), then a weighted mix of dashboard,
  transaction listing, accounts listing, and CSV import
- `run.sh` — runs one tier and samples resources alongside it
