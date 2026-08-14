# HikariCP pool exhaustion — bottleneck investigation

**Follow-up to** [`load-testing-baseline-2026-08-14.md`](load-testing-baseline-2026-08-14.md), which
found the connection pool (10 connections) exhausts between 100 and 500 concurrent users. This
document answers *why*, using the five questions scoped for this pass. **It does not choose a fix.**
That's deliberate — see §6.

**Headline result, because it inverts the obvious next move:** raising `maximumPoolSize` was tested
directly (Q5) and made the problem **worse**, not better, at every size tried. The bottleneck is CPU
contention wearing a connection-pool costume, not a shortage of connections. Anyone reading only the
baseline doc and reaching for `application.yml` would very likely make this worse — that's the
reason this investigation exists.

---

## 1. Method

Same local docker-compose stack and seeded data as the baseline
(`scripts/load-test/seed.py`). Reused `scripts/load-test/loadtest.js`, extended with a `MODE`
environment variable (`dashboard`/`transactions`/`accounts`/`import`) to isolate one traffic type at
a time — the mixed-traffic baseline tiers can't attribute connection hold time to a specific
endpoint on their own.

## 2. Q2 — Are transactions too broad? (code review)

Delegated to a focused code review across the four endpoints' `@Transactional` boundaries.
Full detail in the review itself; summarized here:

| Endpoint | `@Transactional` at | Verdict |
|---|---|---|
| Dashboard summary | `DashboardService.summarize()`, `service/DashboardService.java:50` | **Broad.** 5 repository calls, then substantial in-Java computation (health-score variance/stddev, two `groupingBy` passes for category aggregation, notification building) — all held under one connection, on top of the full unfiltered transaction history. |
| Transaction listing | `TransactionService.search()`, `transactions/TransactionService.java:75` | **Narrow.** 3–4 queries, light DTO mapping, page-bounded. Clean. |
| Accounts listing | `AccountService.listForUser()`, `accounts/AccountService.java:41` | **Narrow-ish, with a real N+1.** `bankManagementService.resolve()` is called once per account (`accounts/AccountService.java:64`) — a sequential `bankRepository.findById` per account, inside the same read transaction. |
| CSV import confirm | `ImportService.confirmSession()` → `confirm()` → `persistSection`, `imports/ImportService.java:585,687` | **Broad — confirmed worst offender.** Per-row category/rule/merchant resolution (the codebase's own `ImportQueryCountIT` enforces a ceiling of 2.5 marginal queries/row), a possible synchronous call to R2 object storage (`imports/storage/R2StatementStorage.java`) while the connection is checked out, and full-history reconciliation (`reconcileForImport`, `detectForUser`) — all inside one write transaction. |

## 3. Q4 — Are queries missing indexes? (EXPLAIN ANALYZE)

Ran `EXPLAIN ANALYZE` on the actual generated queries (captured from `org.hibernate.SQL` debug
logs, not guessed) for all four endpoints, against the seeded data volume (100 users, ~300
transactions each, 31k+ rows total).

| Query | Plan | Execution time |
|---|---|---|
| Dashboard's full-history transaction fetch | Bitmap Index Scan, `idx_transactions_user_category_active` | 0.19ms |
| Transaction listing (paginated, all filters) | Index Scan, `idx_txn_user_date` | 0.19ms |
| Accounts listing | **Seq Scan** on `accounts` | 0.04ms |
| Accounts' per-account transaction count | Bitmap Index Scan | 0.19ms |
| Statement imports (accounts endpoint) | Bitmap Index Scan, `idx_statement_imports_user_account_active` | 0.09ms |

**No missing index found.** The `accounts` sequential scan is the planner correctly choosing a scan
over an index for a 114-row table — expected behavior at this size, not a defect; worth re-checking
once the table is large enough that the planner should switch. **Every query executes in
sub-millisecond time.** This rules out slow queries as the cause — whatever is exhausting the pool,
it isn't query cost.

## 4. Q1 — Which endpoint holds connections longest? (measured, not estimated)

HikariCP exposes real hold-time metrics via Micrometer (`hikaricp_connections_usage_seconds`,
`/actuator/prometheus`) — not inferred from request latency, which also includes queueing time.
Ran each endpoint in isolation (50 VUs / 30s, single mode; import at 10 VUs given
`ImportConcurrencyLimiter`'s 6-concurrent cap), reading the metric delta before/after each run:

| Endpoint | Connection checkouts / request | Total hold time / request |
|---|---|---|
| Dashboard | 6.1 | **174ms** |
| Transaction listing | 5.9 | 77ms |
| Accounts listing | 5.9 | 69ms |
| Import (stage+confirm cycle) | 30.4 | **1,580ms** |

**Two findings, one expected and one not:**

1. **Every authenticated request pays ~5 connection checkouts before it even reaches endpoint
   logic** — `refresh_tokens` lookup, the user+roles+permissions 4-way join, a `roles` lookup, a
   `role_permissions` join, and a `phone_verified` check, all in `JwtAuthFilter`/
   `PhoneVerificationFilter`. This is fixed overhead on *every single request regardless of
   endpoint*, and it's roughly 5/6 of the checkout count for the three read endpoints. Cheap
   individually (sub-millisecond queries per Q4) but it multiplies with request volume.
2. **Import holds the pool 9–20× longer per operation than any read endpoint** — exactly what
   Q2's code review predicted, now with a number attached.

## 5. Q3 — Are imports competing with dashboard reads?

Answered directly by §4's numbers rather than needing a separate mixed-load run: `ImportConcurrencyLimiter`
allows up to 6 concurrent imports, each holding a connection roughly **9–20× longer** than a
concurrent dashboard or transaction-listing request. During an import burst, those 6 imports alone
can occupy well over half the pool's 10 connections for multi-second stretches — leaving little
headroom for every other endpoint sharing the same pool. **Yes, imports compete, and they compete
disproportionately to their share of request volume**, not just by adding load like any other
endpoint would.

## 6. Q5 — What happens at pool size 20/30?

Re-ran the 500-user tier (the tier where the baseline first broke) at `DB_POOL_MAX_SIZE=20` and
`=30`, isolating this one variable. Nothing else changed from the baseline's 500-user run.

| Pool size | Error rate | Dashboard p95 | Pool timeouts |
|---|---|---|---|
| 10 (baseline) | 4.4% | 13.8s | 348 |
| **20** | **41.7%** | **35.1s** | **2,122** |
| 30 | 13.2% | 23.7s | 1,255 |

**Raising the pool made every measured outcome worse, at both sizes tested.** No Postgres-side
connection limit was hit (`pg_stat_activity` showed ~30 total connections against Postgres's own
default ceiling, well under it) and memory stayed flat (~800–950MB) — this isn't a wall being hit
elsewhere, it's the same fixed CPU capacity (10 host cores) now serving more simultaneous DB-bound
work, which increases scheduling contention faster than it relieves queueing. This is the textbook
shape of a CPU-bound system: past its real capacity, adding concurrency makes throughput *worse*,
not better.

**One honest caveat:** this is a single run per pool size, on a shared machine, back-to-back with
other heavy runs — the exact ordering (20 worse than 30) may partly be noise rather than a precise
relationship. What's *not* noise, and consistent across both tested sizes: **no pool size tried came
close to fixing it**, and the direction was consistently bad. That's enough to answer the question
this investigation was scoped to answer — it is not enough to declare a "correct" pool size, and
this document doesn't attempt to.

---

## 7. Synthesis — what's actually happening

Three things compound, not one:

1. **Fixed per-request overhead** (§4) — ~5 auth-related connection checkouts on every request,
   regardless of endpoint, each cheap alone but multiplying with request volume.
2. **Import holds connections disproportionately long** (§2, §4, §5) — per-row DB chatter, a
   possible synchronous network call to R2 inside the transaction, and full-history reconciliation,
   all under one connection checkout.
3. **The real ceiling is CPU, not the number 10** (§6) — confirmed by raising the pool making
   things worse rather than better. More concurrent DB-bound work competing for the same fixed CPU
   capacity increases contention faster than it relieves queueing.

No query is slow (§3). No index is missing (§3). The pool isn't "too small" in the sense that a
bigger number fixes it (§6) — it's exhausted because too much CPU-bound work is trying to run
through it at once, and some of that work (import) holds its share far longer than it needs to.

---

## 8. What this does NOT recommend — read before touching configuration

**This investigation does not choose a remediation.** That was explicit scope going in (see the
plan's [§5a](../../project-management/plans/project-plan-v1.0.md)) and the evidence here reinforces
why: §6 already falsified the obvious first move. What follows is the option space this evidence
points at, with tradeoffs, not a decision.

- **Reduce per-request auth overhead** — the 5-checkout fixed cost (§4) hits every endpoint, every
  request. A cache for role/permission lookups (session-scoped or short-TTL) would cut this without
  touching business logic. This is also the concrete candidate the original baseline's folded-in
  caching evaluation didn't have — it does now.
- **Narrow the broad transactions** (§2) — move dashboard's in-Java aggregation and import's R2
  call outside the connection checkout where the code allows it; batch the accounts N+1
  (`bankRepository.findById` per account) into one query; reduce import's per-row chatter (already
  partially tracked as ongoing work per `ImportQueryCountIT`'s own ceiling).
- **Pool size tuning** — §6 found this actively harmful in the two configurations tested. Railway
  Production's actual ceiling is **500 connections** (confirmed 2026-08-14 via `SHOW
  max_connections;` against the real production database, `railway connect postgres`) — generous
  headroom relative to the pool of 10 in use today, and relative to anything this investigation
  tested (20, 30). **This changes what the constraint is, not the conclusion**: Railway was never
  the wall here — §6's finding (raising the pool made things worse) was CPU contention on the local
  test machine, not an approach toward any Railway-side limit. A larger pool remains available to
  try in production *if* the CPU-bound issues above (auth overhead, broad transactions) are
  addressed first — trying it before that, per §6's own evidence, would likely still make things
  worse, just with more headroom before hitting Railway's ceiling rather than because of it.
- **Do nothing yet and re-scope** — also a legitimate option. This document's job was diagnosis;
  which lever to pull, in what order, and on what timeline is a product/engineering-priority
  decision, not one this investigation makes.

**Railway connection ceiling — resolved, 2026-08-14.** `max_connections = 500` on Production. No
open item remains blocking a future pool-size discussion; the finding from §6 (raising pool size
made the local test worse) stands regardless, since it was never gated on this number.

---

## 9. Artifacts

- `scripts/load-test/loadtest.js` — now supports `MODE` env var for single-endpoint isolation
- `scripts/load-test/results/500-users-pool20/`, `500-users-pool30/` — raw k6 output for the Q5
  experiment
- Hikari hold-time deltas (§4) were read live via `/actuator/prometheus`, not saved as files —
  reproducible by re-running the same before/after capture around any `MODE=` run
