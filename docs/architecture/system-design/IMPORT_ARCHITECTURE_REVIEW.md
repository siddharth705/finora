# Import Architecture Review — High-Concurrency Statement Import

**Date:** 2026-08-07 · **Branch:** `feat/merchant-learning-queue` · **Reviewer:** architecture

> **Status update — this review's Phase 1 landed the same day it was written, and its Phase 3 was
> profiled shortly after.** The findings below are preserved as the reasoning that produced the work,
> not as a description of the codebase. Read this banner first; §2's status table in particular now
> misreports.
>
> | This review said | Since |
> |---|---|
> | §2 #1, #6, #8 — imports synchronous, no progress, no fault tolerance | **Built.** `import_jobs` (`V66`), `ImportJobWorker`, and `POST`/`GET /api/v1/import/jobs` — 202 plus a pollable status — landed in `eb91c02` and `4fa49d1`. Opt-in per environment: `app.import.queue.enabled` defaults to false and requires object storage |
> | §3 item 1–3 — `ImportJob`, a worker, `202 Accepted` | **Built**, as the review recommended: cloned from `MerchantLearningEventWorker`, Postgres `SKIP LOCKED`, no broker |
> | §5 — "crash mid-import loses the user's work"; replay could duplicate | **Closed** by `V67`'s two partial unique indexes (`acecdcd`), which the scaling design tracked as Phase 2. See `ImportIdempotencyIT` |
> | §6 — "16ms/row, per-row merchant resolution is the leading hypothesis, **not** a confirmed root cause" | **Confirmed by profiling** on the same day: five distinct per-row query patterns, scaling exactly linearly. Caching `category_rules` (`b7aab9d`) took ~6.0–6.6 queries/row to **3.00**. See [`docs/engineering/performance/import-pipeline-profile-2026-08-07.md`](../../investigations/performance/import-pipeline-profile-2026-08-07.md), and `ImportQueryCountIT`, which now fails the build if the figure rises |
> | §3 item 4–5 — job status endpoint, frontend progress UI | Endpoint **built**; the **frontend still does not poll it**. Both clients use the synchronous `/csv/stage` and `/pdf/stage` routes, which is deliberate — the API compatibility policy makes adding an endpoint non-breaking and changing one breaking |
> | §3 item 6–8 — storage on by default, worker profile, queue metrics | **Still open.** Queue depth and oldest-pending age are published by the worker; the rest is unchanged |
> | §9 — "no profiling, no load testing, no changes made" | The first is now done. Load testing is still not |
>
> One correction the update does not make: nothing here has been load-tested, so §4's 50,000-upload
> arithmetic remains arithmetic.

---

## Answer to the question asked

**Partially yes — and the part that is missing already has a working, proven template inside this
codebase.**

The pipeline splits cleanly in two:

| Stage | Architecture today |
|---|---|
| Upload → parse → analyze → duplicate detection → import transactions | **Synchronous.** Runs on the Tomcat request thread, returns `200` with the full result. |
| Merchant learning (the final stage) | **Already the target architecture.** Durable DB-backed queue, background workers, retry with backoff, crash recovery, multi-instance safe. |

So this is not a codebase that ignored asynchronous design. It is one where asynchronous design was
built correctly for the last stage of the pipeline and has not yet been extended to the earlier,
more expensive ones. `MerchantLearningEventWorker` is not a prototype — it is a careful piece of
work that already solves claim races, poisoned transactions, abandoned rows and retry backoff. The
recommendation in this document is largely **"apply the pattern you already have, one stage
earlier"**, not "adopt a new architecture."

The honest answer to the scale question is separate and less comfortable: **no, the current design
cannot serve 50,000 near-simultaneous uploads**, and the reason is not the queue design — it is
that the work itself currently costs ~16ms per transaction row. That is addressed in §6.

---

## 1. Current architecture

### 1.1 Upload path (synchronous)

`ImportController.stage()` / `stagePdf()`:

```java
StatementUpload.requireReadable(file, Format.CSV);          // cheap validation, before the gate
return ResponseEntity.ok(ApiResponse.ok(                     // HTTP 200, not 202
    concurrencyLimiter.runGated(() ->
        importService.parseAndStageWithSession(currentUser.id(), file))));
```

Everything — PDFBox extraction, table detection, normalization, categorization, duplicate detection
— happens inline before the response is written.

### 1.2 `ImportConcurrencyLimiter` — an admission gate, in-process, not a queue

BH-043 (2026-08-15): this used to be a **fair (FIFO) semaphore** with 6 permits
(`app.import.max-concurrent:6`) and a 20-second acquire timeout — a real, if in-process, queue,
which is what the rest of this section originally analyzed. It no longer queues anything: a caller
past all 6 permits gets `503 IMPORT_SYSTEM_BUSY` **instantly**, not after a wait, because the wait
itself was found to park the Tomcat request thread for up to 20s under load, degrading every other
endpoint sharing that pool (login, dashboard, ledger) — the exact failure mode this limiter exists
to prevent, just relocated rather than avoided. See `ImportConcurrencyLimiter`'s own class doc for
the full reasoning.

The distinction that matters for this review: there is no queue depth to reason about anymore, only
an accept/reject decision made immediately against the current permit count.

Its javadoc already anticipates precisely the question this review asks, and states the boundary
condition explicitly:

> If genuinely horizontal scaling (multiple backend instances) is ever needed, this in-process gate
> stops being sufficient on its own (each instance would enforce its own limit independently) —
> that's the point at which an external queue would earn its complexity, not before.

That reasoning was correct for a single Railway instance. **The premise it was written under is the
thing that has now changed**, not the reasoning.

### 1.3 Merchant learning queue (already asynchronous)

`MerchantLearningEvent` + `MerchantLearningEventWorker` implement, today:

- **Durable queue** — a database table, surviving restarts.
- **Status lifecycle** — `PENDING → PROCESSING → COMPLETED | FAILED`.
- **Safe multi-worker claim** — `SELECT ... FOR UPDATE SKIP LOCKED`, so multiple instances cannot
  double-claim.
- **Retry with backoff** — `attemptCount`, `nextAttemptAt`, terminal `FAILED` after `MAX_ATTEMPTS`.
- **Crash recovery** — rows stuck in `PROCESSING` beyond 15 minutes are returned to the queue.
- **Correct transaction boundaries** — three deliberate boundaries so a constraint violation cannot
  poison the transaction that records the failure.
- **Low-latency trigger plus backstop** — `@Async` nudge for immediate processing, `@Scheduled`
  `fixedDelay` poll as the safety net.
- **Operator visibility** — terminal failures surface in an admin queue with a manual retry.

This is a genuinely good implementation and satisfies most of expectations 2, 5, 8 and 10 —
**for its stage only**.

### 1.4 Storage

All three modes exist, selected by `app.statement-storage.provider`:

- unset → bytes stay in Postgres (**default**)
- `filesystem` → `FilesystemStatementStorage`, content-addressed, dev/test
- `r2` → `R2StatementStorage`, Cloudflare R2 object storage

Content addressing (SHA-256, atomic temp-file + `ATOMIC_MOVE`, path-traversal guarded) is already
implemented and was audited clean. A misconfigured provider name now fails loudly at startup rather
than silently falling back to the database.

### 1.5 Database efficiency

- **Transaction inserts are batched** — `transactionRepository.saveAll(toInsert)` with Hibernate
  `batch_size: 50`, `order_inserts: true`, `order_updates: true`. This is done correctly.
- **Merchant resolution is not batched** — `MerchantNormalizationEngine.resolve` performs
  `findByUserIdAndNormalizedAlias` per row, plus a `findByIdAndUserId` on the alias hit path. Its
  own javadoc records a prior measurement of ~500 table loads for a 500-row statement, and a
  two-column projection that was measured and **correctly reverted** because it traded round trips
  for hydration with no net win.

---

## 2. Comparison with the target architecture

| # | Expectation | Status | Evidence |
|---|---|---|---|
| 1 | Asynchronous processing | ❌ Import · ✅ Learning | `stage()` returns `200` after full parse |
| 2 | Import job queue with lifecycle | ❌ | `ImportSession` has only `STAGED` / `CONFIRMED` — not the 7-state lifecycle |
| 3 | Object storage | ✅ Available, ⚠️ off by default | R2 + filesystem implemented; default keeps bytes in Postgres |
| 4 | Workers scale independently of API | ❌ Import · ⚠️ Learning | Import runs in-request; learning workers run *inside* the API process |
| 5 | Queue infra suitable for multi-instance | ❌ Import · ✅ Learning | Semaphore is per-JVM; learning uses `SKIP LOCKED` |
| 6 | Progress tracking | ❌ | No job status to poll — the request simply blocks |
| 7 | Batch DB access | ⚠️ Mixed | Inserts batched; merchant lookups per-row |
| 8 | Fault tolerance | ❌ Import · ✅ Learning | A crash mid-import loses the request entirely |
| 9 | Scales to 50k concurrent | ❌ | See §4 |
| 10 | Monitoring | ❌ Import · ✅ Learning | No queue depth / processing time / retry metrics for imports |

**HTTP 202 + "Import Queued"**: not implemented. The client waits for the full result.

---

## 3. Missing components

1. **`ImportJob` entity and queue table** — with the full lifecycle (`UPLOADED → QUEUED →
   PROCESSING → ANALYZING → IMPORTING → LEARNING → COMPLETED | FAILED`), `attemptCount`,
   `nextAttemptAt`, `lastError`, and a claim query using `FOR UPDATE SKIP LOCKED`.
2. **An import worker** — structurally a sibling of `MerchantLearningEventWorker`.
3. **`202 Accepted` upload response** returning a job id instead of the parsed result.
4. **A job status endpoint** for the frontend to poll (`GET /api/v1/import/jobs/{id}`).
5. **Frontend progress UI** consuming that status.
6. **Object storage on by default** for any environment where workers are separate processes — a
   worker in another process cannot read a `MultipartFile` held in the API's memory.
7. **Deployable worker mode** — a way to run the app as worker-only, so workers scale separately.
8. **Import queue metrics** — depth, processing counts, failure counts, average duration, retries.

---

## 4. Scalability assessment — 50,000 concurrent uploads

**The current design cannot serve this, and would not fail quietly.**

With `max-concurrent: 6`, a burst of 50,000 uploads against a single instance means 6 proceed and
essentially all the rest receive `503 IMPORT_SYSTEM_BUSY` immediately (BH-043: no wait at all
anymore, see §1.2).

That is worth crediting properly: **the system degrades in a controlled way rather than collapsing.**
It does not OOM, it does not exhaust the connection pool, it does not take down unrelated endpoints.
The limiter is doing exactly the job it was written for.

But controlled rejection is not service. Using the measured throughput from the E2E pass
(§6), a 5,000-row statement occupies a permit for ~79 seconds:

- 6 concurrent × (1 statement / 79s) ≈ **0.076 statements/second per instance**
- 50,000 statements ≈ **7.6 days** on one instance
- Even at 100 instances: ~1.8 hours — and every request past the concurrency limit is rejected
  immediately (BH-043), so almost none of those users get a response tied to their upload actually
  being processed, they just find out sooner that it wasn't

The binding constraint is **not** the queue. Even with a perfect distributed queue, 50,000 × 5,000
rows = 250 million rows at ~16ms each is ~46 CPU-days of work. **Asynchronous processing changes
this from "50,000 users get a 503" to "50,000 users get a job id and results arrive over the next
few hours." Getting to minutes requires the per-row cost to come down as well.** Both are needed;
neither alone is sufficient.

---

## 5. Risks

| Risk | Severity | Note |
|---|---|---|
| Per-instance semaphore silently over-admits when scaled horizontally | High | 10 instances = 60 concurrent imports against one 10-connection pool |
| Crash mid-import loses the user's work entirely | High | No durable job record to resume from |
| Long-held request threads | High | 79s per request; load balancers and browsers time out well before |
| Statement bytes in Postgres by default | Medium | Bloats the primary datastore and its backups; R2 already exists |
| Learning workers run inside API processes | Medium | Learning throughput is coupled to API instance count |
| No progress feedback | Medium | User cannot distinguish "slow" from "hung" during a 79s wait |
| `CallerRunsPolicy` on `learningQueueExecutor` (queue 100) | Low | Under sustained backlog, learning work runs on the caller thread |

---

## 6. Performance bottlenecks

Measured during the E2E pass on 2026-08-07 (`E2E_TEST_REPORT.md`, Issue 02), against
`POST /api/v1/import/csv/stage`:

| Rows | Wall time |
|---:|---|
| 100 | 2s |
| 1,000 | 15s |
| 5,000 | **79s** |
| 20,000 | no response within 120s |
| ~400,000 (10 MB — within the configured upload limit) | no response within 300s |

≈ **16ms per row**, scaling roughly linearly. Ranked bottlenecks:

1. **Per-row merchant resolution** — the strongest candidate. `findByUserIdAndNormalizedAlias` per
   row, already documented at ~500 loads per 500-row statement. **Profile before changing anything**
   — the repository's standing rule is measure-before-and-after, and one plausible fix here has
   already been measured and reverted for making things worse.
2. **Request-thread occupancy** — the whole parse holds a Tomcat thread and, via the pipeline's
   queries, competes for a 10-connection pool.
3. **Upload limit advertises unreachable capability** — `UPLOAD_MAX_FILE_SIZE: 10MB` permits files
   that cannot complete. Either lower it or reject by row count with a clear error.
4. **Statement bytes through Postgres** by default on the same connection pool.

---

## 7. Recommended improvements

### Phase 1 — Make imports asynchronous (largest win, lowest novelty)

Copy the shape of `MerchantLearningEventWorker` rather than inventing anything:

1. `import_jobs` table + `ImportJob` entity with the full lifecycle, `attemptCount`,
   `nextAttemptAt`, `lastError`.
2. `claimDueJobs(...)` using `FOR UPDATE SKIP LOCKED` — identical to `claimDueEvents`.
3. `ImportJobWorker` with the same three transaction boundaries, the same `@Async` nudge plus
   `@Scheduled` `fixedDelay` backstop, and the same stuck-in-`PROCESSING` recovery.
4. `stage()` becomes: validate → store file → create job → **return `202` with the job id**.
5. `GET /api/v1/import/jobs/{id}` returning status, progress and any error.
6. Frontend polls it and shows the lifecycle.

This removes the request-thread bottleneck, makes crashes recoverable, and gives progress
tracking — four expectations at once. It does **not** require Redis, RabbitMQ or Kafka: Postgres
`SKIP LOCKED` is already proven in this codebase and is sufficient well past the current scale.

### Phase 2 — Enable object storage by default

Workers in separate processes cannot read an in-memory `MultipartFile`. R2 is already implemented;
this is a configuration and migration exercise, not new architecture.

### Phase 3 — Attack the per-row cost

Only after Phase 1, and only with profiling first. Likely: batch-load all merchant aliases for the
statement's distinct normalized descriptions in one query, resolve in memory, then batch-insert new
merchants. **Measure before and after** — a previous attempt here was reverted for making things
worse.

### Phase 4 — Independent worker scaling

A `worker` Spring profile that runs the schedulers without the web layer, deployed as its own
Railway service. Only worthwhile once Phases 1–3 land; before that there is nothing to scale.

### Phase 5 — Operational visibility

Import queue depth, jobs by status, average and p95 duration, retry counts, worker liveness —
surfaced through the existing admin monitoring surfaces.

### Explicitly *not* recommended

- **An external broker (Kafka/RabbitMQ/SQS).** Postgres `SKIP LOCKED` handles this scale, and the
  team already operates it correctly. Revisit only if queue depth or throughput proves otherwise.
- **Replacing `ImportConcurrencyLimiter`.** Once jobs are queued it still usefully bounds how many
  jobs one worker process runs at once. It becomes a worker-side concurrency control rather than a
  request-side admission gate.

---

## 8. Priority order

| # | Item | Why this order | Rough effort |
|---|---|---|---|
| 1 | `ImportJob` entity + queue table + claim query | Everything else depends on it | S |
| 2 | `ImportJobWorker` (clone the learning worker) | The pattern is proven in-repo | M |
| 3 | `202 Accepted` + job status endpoint | Unblocks the frontend | S |
| 4 | Frontend progress UI | Completes the user-visible half | M |
| 5 | Object storage on by default | Prerequisite for out-of-process workers | S |
| 6 | Profile and batch merchant resolution | Biggest throughput lever; needs measurement first | M |
| 7 | Fix the upload-limit/row-count mismatch | Small, stops advertising unreachable capability | XS |
| 8 | Worker deployment profile | Only meaningful once 1–6 land | M |
| 9 | Import queue metrics | Needed to operate the above | S |

Items 1–4 constitute the architecture change proper. Items 5–9 make it operable and fast.

---

## 9. What this review did not do

- **No profiling of the import path.** The 16ms/row figure is end-to-end wall time from black-box
  measurement. Per-row merchant resolution is the leading hypothesis, not a confirmed root cause.
- **No load testing.** The 50,000-user figures in §4 are arithmetic from single-request
  measurements, not observed behaviour under concurrent load.
- **No review of PDF-specific cost.** All timings are CSV. PDFBox extraction may have a materially
  different profile.
- **No changes made.** This document is a review; nothing in the pipeline was modified.
