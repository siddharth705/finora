# Scaling Triggers

When to add Redis, background workers, or split the backend into services — as observable
conditions to watch for, not a roadmap with dates on it. Companion to
[api-compatibility-policy.md](api-compatibility-policy.md), which covers the same "don't build
ahead of evidence" discipline for API contracts specifically.

## Contents

1. [The rule](#the-rule)
2. [Redis](#redis)
3. [Background workers](#background-workers)
4. [Service extraction](#service-extraction)
5. [What this doc is not](#what-this-doc-is-not)

---

## The rule

"We'll probably need X eventually" is not a reason to add X. Every entry below names a condition
that can be checked against reality — a metric, a deploy log, a count — not a feeling about scale.
If the condition isn't true yet, the answer is no, not "not yet, but soon." Revisit when the
condition changes, not on a calendar.

This is the same judgment already in use elsewhere in the codebase, just not written down until
now:

- `ImportConcurrencyLimiter` caps statement-import concurrency at 6
  (`IMPORT_MAX_CONCURRENT`, `application.yml:159`), deliberately below the 10-connection database
  pool — a real, load-bearing number chosen because import bursts were observed capable of
  starving other endpoints of connections, not chosen to look conservative.
- `RateLimitFilter` only rate-limits the specific endpoints that have actually needed it —
  `/api/v1/auth/login`, `/api/v1/auth/register`, and password-change endpoints
  (`RateLimitFilter.java:127-128`) — not every endpoint uniformly "just in case."

Both are examples of sizing a control to a measured problem instead of a hypothetical one. The
triggers below extend that same habit to three infrastructure decisions that come up repeatedly in
review.

## Redis

**Trigger: the backend runs more than one replica.**

Today's caching, rate-limiting, and session-adjacent state live in-process
(`RateLimitFilter`'s limiters, `ImportConcurrencyLimiter`'s semaphore). That's correct as long as
there is exactly one backend instance — in-process state and shared state are the same thing when
there's only one process. It stops being correct the moment a second replica exists: two
`ImportConcurrencyLimiter` semaphores no longer add up to one real limit, and two independent rate
limiters double the effective allowance. Redis (or an equivalent shared store) becomes necessary at
that point, not before — adding it earlier buys operational complexity (another service to run,
monitor, and fail over) for a coordination problem that doesn't exist yet.

**What would make this trigger fire**: a decision to run `replicas: 2` (or Railway's equivalent)
for the backend service, for any reason — load, zero-downtime deploys, availability. Check
in-process state for correctness before that change ships, not after something breaks in
production.

## Background workers

**Trigger: a measured, synchronous bottleneck** — a request-handling thread blocked long enough,
often enough, to affect other users. Not "imports feel like they could be slow one day."

Statement import already runs synchronously inside the request today, and `ImportConcurrencyLimiter`
exists specifically to keep that safe under load (see [Redis](#redis) above for the multi-replica
caveat on the limiter itself). That's a real, working answer to "what if imports pile up" — it caps
damage rather than eliminating latency. Moving import processing to a background worker/queue is
justified once there's a measured case the limiter isn't solving: p95 request latency on the import
endpoint climbing during real usage, or the concurrency ceiling itself becoming the complaint
(legitimate imports timing out waiting for a slot, not just failing over-capacity gracefully).

### Benchmark methodology

Versioned, because the method changed after numbers had already been published and acted on. Every
measurement below states which version produced it. Without this, a future reader comparing an old
figure against a new one has no way to tell a performance regression from a methodology change —
and would reasonably assume the former.

**v1 — deprecated 2026-08-05. Do not compare its numbers against anything.**

- Warmed up by calling `reconcileForUser` on the **same** transaction list it then timed.
- `reconcileForUser` mutates what it classifies, and every pass skips what a prior run settled
  (duplicates skip a non-null `isDuplicateOf`; the transfer candidate list filters out
  `isTransfer()`; the refund pass skips any income whose status is no longer `OK`). So the timed
  run was always a **second** run over already-reconciled data.
- Consequently under-reported real runtime by roughly **5×**, on both sides of a before/after — so
  the *ratio* it reported was approximately right while the *severity* was badly wrong.
- Numbers published under v1 appear in commits `e533f8e` and `33cf944`. They are superseded, not
  merely refined.

**v2 — current, from 2026-08-05. All baselines start here.**

- A **fresh dataset for every measured run**, with warmup on its own separate copy.
- Measures a first reconciliation of unclassified rows — the case that actually happens on import,
  rather than the cheap re-run that happens to follow it.
- The v1→v2 "before" column was re-measured by checking the pre-fix service back out and running it
  through v2, **not** by scaling v1's numbers to fit.

When changing this method again, add a v3 here rather than editing v2 in place, and re-measure any
baseline still being cited.

### Measurement: `reconcileForUser` — 2026-08-05 (methodology v2)

That measurement now exists for one specific path, so this section records it rather than leaving
the next reader to redo it.

A repository audit raised `ReconciliationService.reconcileForUser()` as an O(n²) pass over the
user's entire transaction history, run synchronously after every transaction create, update,
delete, import confirm and statement delete. The audit was explicit that it had measured nothing.
`backend/src/test/java/com/finora/service/ReconciliationScalingBenchmark.java` measures it — it is
not part of the suite (the name matches none of surefire's includes), so run it deliberately:

```bash
cd backend && ./mvnw -o test -Dtest=ReconciliationScalingBenchmark -DfailIfNoTests=false
```

> **Correction, same day.** The numbers first published here were produced under **methodology v1**
> and were roughly five times too low on both sides — see the section above for what v1 got wrong
> and why. The table below is re-measured under **v2**.
>
> The correction does not change the decision — it strengthens it. The ratio was about right
> (4.8× reported, 5.2× actual); the **severity** was badly understated.

| transactions | `reconcileForUser` (before) | `reconcileForUser` (after) | `detectForUser` |
|---:|---:|---:|---:|
| 1,000 | 271 ms | 266 ms | 4 ms |
| 10,000 | 2,145 ms | 1,183 ms | 3 ms |
| 50,000 | **52,826 ms** | **10,173 ms** | 2 ms |

The "after" column is the date-windowed fix described below. At 50k the pass took **52.8 seconds**
of synchronous, request-thread work before it, and takes **10.2 seconds** after — a 5.2× reduction
that saves 42.7 seconds per run, and still leaves a number nobody should be comfortable with.

**Status per finding**, using the lifecycle *observed → measured → triggered → implemented*:

- **`reconcileForUser` — TRIGGERED, and now implemented.** 8.4 seconds of synchronous work on a
  request-handling thread met this section's bar without needing interpretation, and the growth was
  superlinear (5× the data cost ~10× the time between the last two rows). It is worth being precise
  about what that meant: it was never a scale problem waiting on user growth. It is reachable by
  **one** account with a long import history, and every transaction edit pays it.

  **The fix, and why it is not a rewrite.** Both remaining passes are pair-matching loops whose
  match condition already includes a hard date bound — 4 or 10 days for a transfer, 180 for a
  refund. The flat inner scan compared every pair and then rejected almost all of them on that
  bound: work whose outcome was knowable before it started. Sorting the candidates by
  `(txnDate, id)` once turns each inner loop into a binary search plus a contiguous slice. **Every
  predicate the loops applied before is still applied, in the same order, inside the loop.** The
  transfer slice deliberately uses the *wider* of the two windows, because which one applies
  depends on both sides of the pair and is not known until the pair is in hand. This narrows what
  is considered, never what qualifies — all 21 reconciliation tests pass unchanged.

  The `id` tiebreak matters more than it looks. Both passes stop at the first acceptable match, and
  `findByUserId()` has no `ORDER BY` — so the order that used to decide *which* of several
  equally-valid pairs got matched was whatever Postgres happened to return, unstable across plan
  changes and vacuum. Ordering by date alone would have left same-day ties resolved by that same
  accident, which is exactly the case most likely to have more than one candidate. This is
  therefore not a determinism regression traded for speed; it is a determinism **improvement** that
  happened to be a prerequisite.

  **What it does not fix, stated plainly.** 10.2 s at 50k is a 5.2× improvement, not a solved
  problem — and on the corrected measurement it is still well past this section's own trigger. The
  residue is the refund pass: a 180-day window covers roughly a quarter of a two-year corpus, so
  that loop is still quadratic with a smaller constant, and it now dominates. The next move is
  narrowing refund candidates by account (measured below) — not a background worker, which would
  still be doing the same quadratic work, just somewhere the user cannot see it.

### Measurement: bucketing refund candidates by account — 2026-08-05 (methodology v2)

Run `measureRefundCandidateReductionFromAccountBucketing` in the same benchmark class. A refund
lands back on the account its purchase was made on, so every candidate on a *different* account is
examined and rejected. Account assignment is uniform in the fixture, so the reduction is
structurally `1 − 1/accounts`:

| accounts held | candidates examined | after bucketing | removed |
|---:|---:|---:|---:|
| 2 | 5,575,478 | 2,788,166 | 50.0% |
| 3 | 5,575,478 | 1,860,382 | 66.6% |
| 5 | 5,575,478 | 1,117,231 | 80.0% |
| 10 | 5,575,478 | 559,181 | 90.0% |

**Read as a ceiling, not a saving.** Each removed candidate removes one
`getAccountId().equals(...)` — among the cheapest predicates in the pass — while bucketing adds a
grouping pass and a per-account structure to build and keep correct. A two-thirds cut in
*iterations* is not a two-thirds cut in *time*.

**Not yet proposed for implementation.** The honest limit of this measurement is that the isolated
loop over-counts: it does not model the earlier passes having already consumed many incomes, so it
reports more cross-account work than the real pass performs. Deciding this properly needs a
prototype timed end-to-end against `reconcileForUser`, not an isolated loop — which is the next
step, and a proposal before any change lands.
- **`detectForUser` — MEASURED, NOT triggered.** 1 ms at every size. The in-memory cost the audit
  attributed to it is not there.
- **`saveAll(active)` write-back (`RecurringService`) — STILL UNMEASURED.** The benchmark mocks the
  repository, so a write costs nothing in it. That finding is about database work and needs a
  DB-backed measurement before anything is said about it either way. Not refuted — unmeasured.

**What the benchmark does not cover**, stated so the numbers are not read as more than they are:
query counts, connection-pool contention, and Hibernate's hydration of 50k managed entities. That
last one is plausibly larger than the comparison cost being measured here. Treat the table as a
**lower bound**.

Until that measurement exists, a worker queue adds a second thing that can fail (the queue itself),
a second deploy target, and a class of bugs (jobs stuck, jobs processed twice, jobs silently
dropped) that a synchronous request handler structurally cannot have.

## Service extraction

**Trigger: a demonstrated need for independent scaling, independent deployment cadence, distinct
ownership, or a genuine isolation requirement** — not "the codebase has gotten big" or "this
package feels self-contained enough to be its own service."

The backend is a single Spring Boot modular monolith serving three clients (`frontend/`,
`admin-portal/`, `mobile/`) against one shared database — see
[mobile-architecture.md](mobile-architecture.md) for how those clients already share one backend
without a mobile-specific namespace or fork. Splitting `com.finora.imports` (or any
other package) into its own deployable service is a real option someday, but only once one of these
is actually true:

- **Independent scaling**: one part of the system needs to scale differently from the rest under
  real load — e.g. import processing needs 5x the backend's normal replica count and running it
  in-process would mean over-provisioning everything else to match.
- **Independent deployment cadence**: one part changes so much more often than the rest that
  bundling deploys together is a measured drag on shipping — not a preference for smaller PRs.
- **Distinct ownership**: a different team owns a part of the system and needs to deploy without
  coordinating through this one.
- **Genuine isolation requirement**: a security or compliance boundary that the monolith can't
  satisfy internally — not "it would be nice if this couldn't touch that."

None of these is true today. A package boundary inside one deployable is enough isolation for
"this code shouldn't reach into that code" — that's what Java package-private visibility and
`com.finora.imports`'s existing internal structure already enforce, at zero operational cost. Service
extraction trades that zero-cost boundary for a network hop, a second CI/CD pipeline, and a
distributed-systems failure mode (partial failure, network partition, version skew between
services) that doesn't exist inside one JVM. Pay that cost when one of the four triggers above is
real, not preemptively.

## What this doc is not

Not a prediction of when Finora will need these — there's no forecast here, and no target date.
It's the answer key for "should we add X now?" the next time that question comes up in review: check
the trigger, not the vibe.
