# Load-testing baseline — 2026-08-14

**Scope, stated up front because it decides how to read everything below:** measure reality at
three fixed concurrency tiers, not chase a scale target. This is the P1 item from
[`project-plan-v1.0.md` §5a](../../project-management/plans/project-plan-v1.0.md) — kept
pre-Railway-Pro because it doesn't depend on infrastructure that's about to change. No
infrastructure changes were made to produce these numbers; this finds the current ceiling, it
doesn't move it.

**Bottom line:** the single number that matters is **10** — the HikariCP connection pool size
(`DB_POOL_MAX_SIZE`, `application.yml`), already known and deliberately set. Between the 100-user
and 500-user tiers, that pool becomes the bottleneck, and every other number in this document is
downstream of that one fact.

---

## 1. Method

| | |
|---|---|
| **Target** | Local docker-compose stack (`docker compose up -d`), **not** the deployed Railway instance |
| **Backend** | Single container, `dev` profile, no CPU/memory limit set on the container (`docker inspect`: `Memory=0, NanoCpus=0`) — free to use the full host |
| **JVM heap** | `MaxHeapSize` ergonomic at 2.08 GB (25% of the Docker Desktop VM's 7.75 GB) |
| **Host** | 10 physical cores, all visible to the Docker Desktop VM |
| **Database** | Postgres 16 in its own container, HikariCP pool capped at 10 (`DB_POOL_MAX_SIZE:10`, the same value already documented in the architecture audit) |
| **Data** | 100 seeded users (`scripts/load-test/seed.py`), 2 accounts + 300 transactions each — 30,000 transaction rows total |
| **Tool** | k6 2.2.0, `scripts/load-test/loadtest.js`, `constant-vus` executor, 60s hold per tier |
| **Traffic mix per iteration** | 40% dashboard summary, 35% transaction listing, 15% accounts listing, 10% CSV import (stage + confirm), 1–3s think time between iterations |
| **Rate limits** | Per-IP login/register/import-stage limits raised locally only (`docker-compose.override.yml`, gitignored, never committed) — every VU shares one IP (localhost), which would otherwise throttle the harness itself rather than the backend. **This means these numbers show what the backend does when that protection isn't in the way** — see §5. |

**Why local, not Railway.** Pushing 1,000 concurrent connections at the shared deployed instance
wasn't something to do without a separate, explicit conversation — it has cost and availability
implications for infrastructure other things depend on. This baseline answers "where does the
current architecture's ceiling sit," which the pool-size finding below answers regardless of which
machine runs it; a Railway-specific number is a different, later question, likely after Railway
Pro per §5a.

**Reproduce:** `python3 scripts/load-test/seed.py` once, then
`scripts/load-test/run.sh 100 60s` / `500 60s` / `1000 60s`. Results land in
`scripts/load-test/results/<n>-users/`.

---

## 2. Results by tier

| Tier | HTTP error rate | Dashboard p95 | Transactions p95 | Accounts p95 | Login p95 | Backend mem (max) | Backend CPU (max) | DB pool timeouts |
|---|---|---|---|---|---|---|---|---|
| **100 users** | 0.00% | 10ms | 9ms | 8ms | 5.4s | 715 MB | 1165% (≈11.6 cores) | 0 |
| **500 users** | 4.39% | 13.8s | 13.8s | 15.2s | 18.0s | 804 MB | 1067% (≈10.7 cores) | 348, up to 191 queued |
| **1000 users** | 7.31% | 37.6s | 37.1s | 38.4s | 40.6s | 791 MB | 1266% (≈12.7 cores) | 1,004, up to 190 queued |

Full k6 output and raw resource samples: `scripts/load-test/results/{100,500,1000}-users/`.

**Memory never became a constraint at any tier** (peaked at 804 MB against a 2 GB heap). Everything
that degrades between 100 and 500 users is CPU and connection-pool contention, not memory pressure
— useful on its own, since it rules out "the container needs more RAM" as a fix.

---

## 3. What's actually happening — the pool, not the CPU

The CPU numbers (1000%+ at every tier from 500 up) look like the story, but they're a symptom.
The backend's own logs pin the real cause exactly, with a number attached, and this is the finding
this baseline exists to produce:

```
2026-08-14 05:27:09 ERROR o.h.e.jdbc.spi.SqlExceptionHelper - HikariPool-1 - Connection is not
available, request timed out after 10000ms (total=10, active=10, idle=0, waiting=163)
```

At 500 users: **348 of these timeouts**, queue depth up to **191** requests waiting for one of the
pool's 10 connections. At 1000 users: **1,004 timeouts**, queue depth capped at essentially the
same **~190** — which is itself informative: the queue isn't growing with VU count past this
point, meaning something else (most plausibly the Tomcat thread pool, default 200) is capping how
many requests can even get in line to wait for a connection. Not chased further here — flagged in
§6 as the next thing this baseline didn't answer.

**The CPU spike is what a maxed connection pool looks like from the outside, not a separate
problem to fix.** 10 connections held for the ~10-second timeout window, with hundreds of threads
parked waiting and periodically waking to recheck, produces exactly this pattern. Independently,
login itself carries a real, deliberate CPU cost (BCrypt, strength 12) that compounds when many
logins land in the same instant — the 100-user tier's own login p95 (5.4s, with 0% pool timeouts)
shows that cost in isolation, before the pool becomes the dominant factor at 500+.

**Postgres-side connection sampling (`resource-samples.csv`) shows `active=1` throughout, even
during the 500- and 1000-user timeout storms.** This is not a second data point — it's a
measurement artifact. HikariCP's own logs are the exact, authoritative count; the external
`pg_stat_activity` polling ran every 2 seconds and simply kept missing a pool that fills and empties
within single-digit milliseconds. Recorded so a future reader doesn't read the two side by side and
conclude they disagree — treat the HikariCP figures as ground truth and the CSV's connection column
as unreliable at this sampling rate.

---

## 4. Where the ceiling sits

**Reading straight off these three tiers: the current single-instance, 10-connection-pool
architecture holds up cleanly through 100 concurrent users and starts failing before 500.** Nothing
here pins the exact number between 100 and 500 — that would need intermediate tiers (200, 300, 400)
this baseline didn't run, deliberately, per the "measure reality, don't over-build" scope. What
*is* clear: at 500, 4.4% of requests already fail outright (timeout past the pool's own 10-second
wait), and p95 latency for ordinary reads (dashboard, transactions, accounts) is 13–15 seconds —
unusable regardless of the failure rate.

This is not a surprise finding — it's the same fact the architecture audit already named
(`DB_POOL_MAX_SIZE:10`, tuned deliberately for Railway's connection ceiling) now with a measured
consequence attached: **that tuning caps concurrent throughput somewhere under 500 simultaneous
active users on this hardware**, not just "on Railway once multiple instances exist," which is how
it read in the audit.

---

## 5. What this baseline does not tell you

Stated plainly, matching this directory's own standing practice (see
[`methodology.md`](methodology.md) §10):

- **Not a Railway number.** Local Docker, unlimited host CPU, no network hop, no Railway's own
  connection ceiling on the Postgres side. The *mechanism* (pool exhaustion at N connections) will
  reproduce on Railway; the *N* at which it bites will differ, plausibly for the worse given
  Railway's own documented connection limits.
- **Rate limits were raised for this run, on purpose, locally only** (`docker-compose.override.yml`,
  gitignored). In production, `RateLimitFilter`'s per-IP login cap (10/60s default) would reject
  most of a real login storm from a single IP before it ever reached the pool — meaning this
  baseline measures the backend's raw capacity, not what a single attacker or client could do to
  it. It does **not** protect against many real users behind one shared IP (office NAT, campus
  network) hitting this same wall simultaneously, which is a legitimate way this could still occur
  in production.
- **A "login storm" (many users authenticating in the same few seconds) is a specific, narrower
  scenario than "500 steady-state users."** This test's `constant-vus` executor starts all VUs at
  once, which manufactures exactly that storm as a side effect of the test design — real traffic
  arrives more staggered. The pool-exhaustion mechanism is real regardless; how *often* production
  traffic actually clusters this way is a separate, unmeasured question.
- **Import processing time is under-measured at the higher tiers.** By the time the 500- and
  1000-user runs reached the CSV-import branch of the traffic mix, many VUs were already stuck
  waiting on login or a DB connection, so fewer imports completed than the traffic-mix percentage
  implies. The 100-user tier's import numbers (stage ~10–17ms median, confirm ~14–20ms median) are
  the more trustworthy read of import cost in isolation; §2's 500/1000 columns reflect the same
  pool exhaustion as everything else on those rows, not a separate import-specific finding.
- **The exact ceiling between 100 and 500 is not pinned down.** See §4.
- **Caching evaluation, folded into this same pass per the plan:** these three tiers point at the
  connection pool, not at a specific slow query or missing cache. Nothing here identifies a
  cacheable hot read yet — the dashboard/transactions/accounts endpoints all failed *the same way*
  (pool timeout) rather than one being disproportionately slow relative to the others, which is
  itself a data point: caching one endpoint would not move this ceiling, because the shared
  constraint is upstream of all of them.

---

## 6. Follow-ups this baseline surfaces (not done here — scope was measurement, not fixes)

- Intermediate tiers (200/300/400) would pin the actual breaking point rather than bracket it
  between 100 and 500.
- The Tomcat thread-pool ceiling implied by the ~190-request queue plateau (§3) is worth confirming
  directly rather than inferred from queue depth.
- A production-shaped test (realistic stagger instead of `constant-vus`' simultaneous start,
  per-user pacing matching actual session behavior) would separate "login storm" from "sustained
  concurrent load" as distinct scenarios — this baseline conflates them by construction.
- Re-run against Railway once Railway Pro is in place (§5a), to get the number this local run
  explicitly cannot provide.

---

## 7. Artifacts

- `scripts/load-test/seed.py` — idempotent user/data seeding (stdlib-only Python, matching this
  repo's `scripts/` convention)
- `scripts/load-test/loadtest.js` — the k6 script itself, reusable for the follow-ups in §6
- `scripts/load-test/run.sh` — orchestrates a tier + samples container memory/CPU and Postgres
  connection state alongside it
- `scripts/load-test/results/{100,500,1000}-users/` — raw k6 output, k6 JSON summary, and
  resource-sample CSVs for each tier
