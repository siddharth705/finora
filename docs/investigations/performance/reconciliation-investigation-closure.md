# Reconciliation Performance Investigation

**Status: CLOSED — 2026-08-05**

Closed because the evidence ran out, not the ideas. This record exists so nobody reopens the same
investigation in six months wondering whether something was overlooked.

---

## Outcome

- **Major bottleneck identified and fixed.** `reconcileForUser` ran two O(n²) pair-matching passes
  over the user's entire history, synchronously, after every transaction create, update, delete,
  import confirm and statement delete. Both passes already had hard date bounds, so almost every
  comparison was work whose result was knowable in advance. Sorting candidates by `(txnDate, id)`
  once and binary-searching the window cut 50k transactions from **52.8 s to single-digit seconds**
  with no matching rule changed and all 21 reconciliation tests passing unchanged.
- **Benchmark methodology corrected and versioned.** The original benchmark warmed up on the same
  mutable list it then timed, so every figure was a *second* run over already-reconciled data.
  Numbers were roughly 5× too low on both sides. v1 is recorded as deprecated rather than deleted;
  v2 is the current baseline. See `../scaling-triggers.md`.
- **Account bucketing evaluated and rejected.** An isolated count said it would remove 66.6% of
  refund-pass candidate examinations. Prototyped, timed end-to-end, and compared against a baseline
  re-measured in the same session: the ranges overlapped almost completely and the bucketed median
  was *worse*. Not merged. The prototype remains on `perf/refund-account-bucketing`.
- **A permanent CPU baseline captured.** See
  `2026-08-05-reconcile-for-user-cpu-profile.md`.
- **Supporting work shipped alongside**: batched writes (`saveAll` rather than a round trip per
  match), thresholds consolidated into `ReconciliationPolicy`, structured per-match explanations
  (V55), run metrics with a slow-run warning, and end-to-end plus concurrency tests covering the
  three passes interacting — which no previous test did.

## The finding that changes where anyone should look next

The closing profile did not confirm what everyone expected.

**Roughly half the remaining time is regex, in a linear pre-pass — not in the quadratic matching.**
`CategoryRules.suggestCategory` is 47.1% inclusive, called once per candidate to answer "is this a
salary credit?", and it walks every compiled keyword pattern for every category to do it. The
refund pass's own iteration — the thing account bucketing narrowed — is a 2.8% line item.

That is also *why* bucketing measured as nothing: it optimised a part of the method that is not
hot. Anyone reopening this on the assumption that "the quadratic refund loop is the problem" would
be repeating a mistake this investigation already made and paid for.

## Deferred, deliberately — not forgotten

Each of these was considered and parked with a reason. None is blocked on the other.

| Item | Why it is parked |
|---|---|
| **Refund ranking** (merchant vs date proximity) | Documented in `../refund-ranking-design-note.md`. Changes financial classification, and the affected population is unmeasured. V55's stored explanations make it a SQL query once production data exists — the note contains the query. |
| **ENG-23** — backend dependency scanning | Written up in `../eng-controls-proposal.md`. The recommended next infrastructure item: the three JS apps are covered while the backend holds the only untrusted-input parser. Needs an NVD key and one triage pass. |
| **ENG-24** — static analysis (SpotBugs) | Same proposal. Needs a budgeted baseline pass, or it becomes a report nobody reads. |
| **Incremental reconciliation** | Reconciling only recently-touched rows changes business behaviour, not just performance. Architectural phase, not an optimisation. |
| **Background reconciliation / job queue** | Would move the same work somewhere the user cannot see it. Requires job infrastructure, retries, monitoring, idempotency. Also gated by this repository's own multi-replica trigger. |
| **`RecurringService.saveAll` write-back** | Recorded as *unmeasured*, not as passing. The benchmark mocks the repository, so it cannot speak to a database write. |

## What would justify reopening this

- **A throughput or latency target is set and exceeded.** None exists today — that gap is itself
  worth noting: "seconds of synchronous work" was judged too slow on engineering instinct, not
  against a stated requirement.
- **New benchmark data**, particularly a profile taken against a real database rather than a mocked
  repository. Query time, connection acquisition and Hibernate hydration are entirely absent from
  the current baseline and are plausibly larger than everything measured here.
- **A product requirement change** — bulk import sizes, a real-time reconciliation view, or
  multi-replica deployment.
- **The `durationMs` metric now recorded on every `RECONCILIATION_RUN` audit entry trending up** on
  real accounts. That metric exists specifically so the next version of this investigation starts
  with production evidence rather than a synthetic benchmark.

## What this investigation is worth remembering for

Three things, all learned the expensive way:

1. **A count is a hypothesis, not a measurement.** 66.6% of iterations removed translated to no
   measurable time saved.
2. **A benchmark that mutates its own fixture measures the wrong thing**, and will do so
   consistently enough to look credible.
3. **Always re-measure the baseline in the same session as the change.** A single sample taken
   while another build was running was 3–4× off, and briefly made a rejected optimisation look like
   a 3× win.
