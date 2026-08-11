# Queue Overhead — 2026-08-08

Measurement, not a proposal. This supplies the evidence
[`milestone-2-import-at-scale.md`](../../project-management/milestones/milestone-2-import-at-scale.md) §5 asked for before the last
open question in that item is answered:

> **When async applies.** Every import, or only above a threshold? A 3-row CSV routed through a queue
> is a worse experience than a synchronous one. Measure before choosing.

**The threshold is still yours to choose. This narrows what it should be chosen on.**

Do not re-derive these numbers by hand. `QueueOverheadMeasurementIT` prints them on every run, for
the reason [`methodology.md`](methodology.md) records: the last performance document here was stale
within forty minutes.

> **Follow-up, same day: recommendation 1 is implemented and re-measured. The threshold question is
> answered — there should not be one.**
>
> `ImportProgress` now polls on a backoff schedule (`POLL_SCHEDULE_MS` = 100, 200, 400, 800, 1500)
> instead of one immediate poll and then every 1500 ms. The immediate poll was worse than useless:
> it fired while the job had just been accepted and no worker had touched it, so it always read
> `QUEUED`, and its only effect was to make the *second* poll — at 1500 ms — the first that could
> observe a finished job.
>
> Re-measured with the same harness, which now derives and prints the perceived figure rather than
> leaving it to be worked out:
>
> | Rows | Server completes | Seen before | **Seen now** |
> |---:|---:|---:|---:|
> | 3 | ~40 ms | 1500 ms | **~100 ms** |
> | 50 | ~88 ms | 1500 ms | **~100 ms** |
> | 500 | ~295 ms | 1500 ms | **~300 ms** |
>
> A small statement is now perceived at ~100 ms against ~18 ms synchronous. That is a difference of
> about 80 ms on an action that follows a file picker, and it is below the ~100 ms threshold the
> milestone named as the point at which the routing question stops being worth asking.
>
> **So: no threshold, and async as the single path.** Not because the queue got faster — the
> server-side overhead is unchanged at ~22 ms — but because the only figure that ever justified a
> threshold was the poll interval, and it is gone. Two paths to one review screen is the condition
> that produced the confirm-payload drift recorded in `lib/newAccountPayload.ts` and again in
> mobile's `initialInclusion`; collapsing them is worth more than a routing rule.
>
> Unchanged by this: everything under *What this did not measure* below. R2, PDF and concurrent
> load are all still unmeasured, and the first two would push the completion figures up. On the
> schedule above that moves a statement from the 100 ms step to the 300 ms or 700 ms one — it does
> not reinstate the 1500 ms floor, which is what the threshold argument rested on.

---

## Result

Filesystem storage, local Postgres, median of 5 runs per size, the two paths **alternating** so
continuing JVM warm-up is not attributed to whichever runs second.

| Rows | Synchronous, median (min–max) | Queued, median (min–max) | Overhead |
|---:|---|---|---|
| 3 | 19 ms (17–21) | 40 ms (38–48) | **+21 ms** |
| 50 | 81 ms (66–100) | 89 ms (74–132) | below noise (spread 34 ms) |
| 500 | 343 ms (284–375) | 318 ms (293–362) | below noise (spread 91 ms) |

**The queue's server-side overhead is a constant of roughly 20 ms** — storing the bytes, writing and
committing the job row, claiming it back, re-reading the content. It does not grow with the
statement, so it is measurable only where the parse is small enough not to bury it.

### Two things this table does not say

**It does not say the queue is ever faster.** The 500-row row shows a negative difference and that
cannot be true: the queued path does everything the synchronous one does, plus a storage round trip
and three transactions. It is noise, and the spread column is printed so it reads as noise. An
earlier version of this harness ran synchronous first every time and reported the queue as 222 ms
*faster* at 500 rows — the number that prompted the alternating order and the spread column.

**It does not measure what the user experiences.** That gap is the actual finding.

---

## The finding: the poll interval dominates, by two orders of magnitude

The client polls every **1500 ms**. For a 3-row CSV the server-side comparison is 19 ms against
40 ms — a difference nobody can perceive — but the queued import cannot *appear* finished until a
poll says so. So the real comparison for a small statement is:

```
synchronous     ~19 ms   →  review screen
queued          ~40 ms   →  … up to 1500 ms of waiting  →  review screen
```

**≈98% of the penalty for queueing a small statement is the poll interval, not the queue.** Which
means a row-count threshold is aimed at the wrong thing. Three levers actually move this, and none of
them is a threshold:

1. **Poll immediately, then back off.** A first poll at ~100 ms costs one extra request and removes
   most of the penalty for exactly the statements that suffer it, because a small statement is
   already finished by then. Cheapest of the three, and it needs no routing decision at all.
2. **Return the job's state in the 202.** A job that completes before the client's first poll could
   be reported without any poll — but it will not have completed at accept time, so this only helps
   in combination with (1).
3. **A row-count threshold.** Requires knowing the row count before deciding, which means parsing,
   which is the work being deferred. It could be approximated from file size. This is the option
   the milestone named and, on this evidence, the weakest of the three.

## Recommendation

**Do (1) and re-measure; do not set a threshold yet.** If the perceived penalty for a 3-row CSV
falls to ~100 ms, the question the threshold exists to answer has gone away, and the async path can
be the single path — which is worth considerably more than a routing rule, because two paths to the
same review screen is the condition that produced the confirm-payload drift recorded in
`lib/newAccountPayload.ts`.

If a threshold is still wanted afterwards, set it on **file size**, not row count, and treat it as an
upload-time heuristic rather than a promise.

## What this did not measure

- **R2, the storage provider real deployments use.** These figures are the filesystem provider, so
  the storage component is a floor. R2 adds a network round trip on the write and another on the
  claim; on that evidence the ~20 ms constant is likely to be more like ~100 ms in production, which
  strengthens rather than weakens the conclusion — it is still far below 1500 ms.
- **PDF.** All figures are CSV. PDFBox extraction has a materially different profile, and a PDF is
  also the case where queueing pays off most.
- **Concurrent load.** Single-threaded, one job at a time. The queue's advantages — not holding a
  Tomcat thread, not holding a connection from a pool capped at 10 — are invisible in a measurement
  that never contends for either, and those are the reasons it exists.
