# Multi-replica rate-limit / import-concurrency evidence — 2026-08-16

**Scope, stated up front:** this answers exactly what [D-4](../../project-management/plans/project-plan-v1.0.md)
asks for — real evidence on whether a second backend replica silently degrades `RateLimiter` and
`ImportConcurrencyLimiter`, both documented in their own class comments as in-memory and scoped to
a single JVM instance. It does not re-run the 2026-08-14 single-instance CPU/pool baseline
([`load-testing-baseline-2026-08-14.md`](load-testing-baseline-2026-08-14.md)) — that answered a
different question (where does one instance's ceiling sit) and stands unchanged. This is the
multi-instance gap that baseline explicitly did not cover.

**Bottom line: confirmed, with real numbers, not just code-reading.** A second replica behind a
plain round-robin load balancer does not share either guard's state. The effective ceiling seen
at the load-balancer address roughly doubles: import concurrency exactly doubled (6 → 12), login
rate limiting rose 80% (10 → 18) in the same 30-request burst.

---

## 1. Method

| | |
|---|---|
| **Target** | Local docker-compose stack, one backend replica (baseline) then two (the experiment), both behind a plain nginx round-robin proxy in front — `scripts/load-test/docker-compose.multi-replica.yml` |
| **Why nginx in both runs, not just the 2-replica one** | Isolates the replica-count variable from the proxy-hop variable — a difference between the two runs can only be the thing this investigation asks about, not an nginx artifact |
| **Backend replicas** | `docker compose ... up -d --scale backend=N` — Docker's embedded DNS round-robins `backend` across however many containers are running; nginx re-resolves per request (`resolver 127.0.0.11`), not once at startup |
| **Tool** | k6, new script `scripts/load-test/replica-experiment.js` — a sharp burst (`per-vu-iterations`, 1 iteration per VU, all VUs start together), not a sustained tier: the question is instantaneous ceiling, not throughput over time |
| **Scenario: login** | 30 concurrent login attempts, one seeded user, zero think-time, against `RATE_LIMIT_LOGIN_MAX` (default 10/60s) |
| **Scenario: import** | 15 concurrent `POST /import/csv/stage` calls, one authenticated session, distinct file content per call, against `app.import.max-concurrent` (default 6) |
| **Rate limits raised for the import scenario only** | `RATE_LIMIT_IMPORT_STAGE_MAX=5000` — otherwise the *rate* limiter (10/600s) becomes the binding constraint before the *concurrency* limiter (6, uncapped by count) ever does, measuring the wrong guard. The login scenario deliberately does NOT raise `RATE_LIMIT_LOGIN_MAX` — that limiter is the thing being measured there. |
| **Reproduce** | `docker compose -f docker-compose.yml -f scripts/load-test/docker-compose.multi-replica.yml up -d --scale backend=1` (baseline) or `--scale backend=2` (experiment), then `k6 run --env SCENARIO=login\|import --env BASE_URL=http://localhost:<nginx port> scripts/load-test/replica-experiment.js` |

## 2. Results

| Scenario | 1 replica (baseline) | 2 replicas | Configured single-instance limit |
|---|---|---|---|
| **Login burst** (30 requests) | 10 allowed, 20 rejected | 18 allowed, 12 rejected | 10 / 60s |
| **Import-stage burst** (15 requests) | 6 allowed, 9 rejected | 12 allowed, 3 rejected | 6 concurrent |

The 1-replica baseline for both scenarios lands exactly on the configured limit (10 logins,
6 concurrent imports) — confirms the harness is measuring the right thing before trusting the
2-replica comparison.

**Import concurrency: exactly 2×.** 6 → 12, matching `ImportConcurrencyLimiter`'s own doc comment
precisely ("each instance would enforce its own limit independently") — each replica's `Semaphore`
had no idea the other existed, so the load-balanced ceiling was the sum of both.

**Login rate limit: 80% higher, not a clean 2×.** 10 → 18, not 20. Round-robin distribution across
exactly 30 requests in a single k6-scheduled burst isn't perfectly even (BCrypt's own latency and
connection-open timing both introduce jitter in which replica actually receives which request) —
the qualitative finding is unaffected: a client hitting the load-balancer address gets meaningfully
more attempts through than the configured per-instance ceiling promises, in every run.

## 3. A separate bug this surfaced, not what this investigation was measuring

The first import-burst attempt (before file content was varied per request, see `replica-experiment.js`'s
own comment) sent 15 concurrent requests with **identical** CSV bytes from one user. Under real
concurrency this throws an unhandled `DataIntegrityViolationException` (HTTP 500) — a race on
`idx_import_sessions_live_content`'s `UNIQUE(user_id, content_hash)` constraint, not the clean
`IMPORT_SYSTEM_BUSY` (503) rejection `ImportConcurrencyLimiter` is designed to produce. This is a
real gap (two genuinely concurrent identical-content uploads from the same user can 500 instead of
either succeeding once or failing cleanly) but a different one than D-4 asks about — noted here for
the record, not fixed as part of this investigation. Worth its own ticket.

## 4. What this means for D-4

**"One instance or many at launch?"** — this investigation answers the "many" half concretely: **not
without Redis, or the two in-memory guards genuinely do what their own class docs already warned
they'd do.** Neither finding is new in kind (both classes' doc comments predicted exactly this) —
what's new is a real number instead of an inference: a second replica isn't a small, tolerable
degradation, it's roughly a 2× hole in both the abuse-rate defense and the memory-exhaustion
defense the semaphore exists to prevent.

**Recommendation: one instance for beta/launch, exactly as D-4's own standing recommendation
already said — this evidence confirms rather than changes it.** Nothing here argues for building
Redis-backed versions of either guard before there's a real second-instance need; it argues against
ever running two replicas casually (e.g., a well-meaning "just bump replicas for headroom" change)
without also doing that work first. The action item this evidence adds isn't "start GA on two
instances" — it's the opposite: **treat "stay on one instance until Redis-backed rate limiting and
import concurrency both ship" as a real constraint on any future scaling decision, not an assumption
that quietly erodes.**

---

## 5. Artifacts

- `scripts/load-test/docker-compose.multi-replica.yml` — nginx + scaled-backend override
- `scripts/load-test/nginx-multi-replica.conf` — round-robin proxy config
- `scripts/load-test/replica-experiment.js` — the login/import burst script
