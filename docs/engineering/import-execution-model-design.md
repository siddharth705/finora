# Import execution model — design

**Status:** Proposal, for review. Nothing here is implemented.

**Depends on:** [`import-side-effect-idempotency-audit.md`](import-side-effect-idempotency-audit.md),
which supplies the evidence for where checkpoints belong.

---

## 1. The problem

Confirmation still runs on the request thread. Moving it into the worker makes failures granular:
today a confirm either happens or does not, and a retry re-runs all of it. Once the worker owns the
whole pipeline, a job can die between parsing and confirming, or between confirming and learning,
and "retry the job" stops being a safe instruction.

The execution model answers one question: **when a job resumes, what must it not do again?**

---

## 2. What the current model gets wrong

`ImportJob.Status` declares six progression states. **The worker reaches two.**

```
QUEUED → PARSING → ANALYZING → DEDUPING → IMPORTING → LEARNING → COMPLETED
         ^^^^^^^   ^^^^^^^^^   ────────── unreachable ──────────
```

`DEDUPING`, `IMPORTING` and `LEARNING` are states I declared speculatively and nothing can enter.
That is the scattered-state problem in miniature: a dashboard filtering on `IMPORTING` would show
zero rows forever and nobody could tell whether that meant healthy or broken.

**Rule adopted here: a state exists only if some code path enters it.** The set below is smaller
than the one it replaces, deliberately.

---

## 3. The state machine

```
                          ┌──────────────────────────────────────┐
                          │              CANCELLED               │
                          └──────────────▲───────────────────────┘
                                         │ (only before APPLYING)
QUEUED ──► FETCHING ──► PARSING ──► STAGED ══╪══► APPLYING ──► COMPLETED
   ▲          │            │          │   checkpoint   │
   │          ▼            ▼          ▼                ▼
   │   FAILED_STORAGE  FAILED_PARSE  ...        FAILED_APPLY
   │
   └── returned by recovery, or by a retry whose backoff elapsed
```

### Progression states

| State | Means | Safe to re-run? |
|---|---|---|
| `QUEUED` | Waiting to be claimed | — |
| `FETCHING` | Reading bytes from object storage | ✅ content-addressed read |
| `PARSING` | Extracting rows from CSV/PDF | ✅ pure |
| `STAGED` | Rows parsed, nothing written to the ledger | ✅ staging writes nothing (WI3) |
| `APPLYING` | **The one unsafe stage** — confirm + learning, in one transaction | ❌ see §4 |
| `COMPLETED` | Terminal, success | — |

### Failure states

One state per stage that can fail, rather than one `FAILED` plus a message to parse:

| State | Cause | First thing to check |
|---|---|---|
| `FAILED_STORAGE` | Object unreadable or storage down | Provider health; is the object address valid? |
| `FAILED_PARSE` | Statement could not be read | The document itself — likely a scanned PDF or an unknown layout |
| `FAILED_APPLY` | Confirm or learning failed | Database health, constraint violations |
| `CANCELLED` | User cancelled before `APPLYING` | Nothing — expected |

**Why granular rather than one `FAILED` with `last_error`:** these have different runbook entries
and different alert thresholds. A rise in `FAILED_STORAGE` is an infrastructure incident; a rise in
`FAILED_PARSE` is a product signal about statement formats and should page nobody. One state cannot
express that, and parsing a free-text `last_error` in an alert rule is how alerting becomes
unreliable.

`last_error` stays, as detail. The state is the classification.

**One column still.** A `FAILED_APPLY` state carries both "it failed" and "where", so the property
that status and stage cannot disagree is preserved — that was the reason for a single column in the
first place.

---

## 4. Checkpoints: one, not two

The obvious design checkpoints before `CONFIRMING` and again before `LEARNING`. **The audit says
that is wrong**, and the reason is the most important decision in this document.

Confirm and learning are inside **one transaction** today. Two of the nine side effects are not
replay-safe on their own — the account balance's net-delta branch, and the merchant-learning
confirmation count — and both are held safe purely by that atomicity: a replay violates V67's
`UNIQUE(import_job_id)` and the whole transaction rolls back.

**Splitting them into separately committed steps removes that protection.** It buys finer-grained
resume, and costs a guarantee that currently holds for free — and it would then require two new
guards that are not needed today, one of which (a learning dedup key) has semantics that are easy to
get wrong.

So:

```
QUEUED ─► FETCHING ─► PARSING ─► STAGED ═══► APPLYING ─► COMPLETED
                                        ▲
                                   the only
                                  checkpoint
```

**Everything before the checkpoint is freely replayable. Everything after it happens exactly once,
because it is one transaction.**

### A checkpoint is not new machinery

It is a **committed status transition**. The job row already exists and already has a status;
reaching `STAGED` in its own committed transaction *is* the checkpoint. Resume then reads the state
and knows what happened.

No checkpoint table, no step log, no new concept. That matters: the simplest thing that answers
"what must I not do again?" is a durable state, and there is already one.

---

## 5. Resume semantics

| Resuming from | Action |
|---|---|
| `QUEUED` | Run from the beginning |
| `FETCHING`, `PARSING` | Restart from `FETCHING` — both are free to repeat, and re-fetching is cheaper than tracking which byte range arrived |
| `STAGED` | Re-stage, then apply. Staging writes nothing, so repeating it costs time and nothing else |
| `APPLYING` | **Do not re-apply blindly.** See below |
| `COMPLETED`, `FAILED_*`, `CANCELLED` | Terminal — recovery must not touch these |

### Resuming from `APPLYING` is the whole problem

A worker that died mid-`APPLYING` left a transaction that either committed or did not. There is no
third case, and the job row cannot tell you which — the status write and the confirm are in
different transactions by necessity.

**The database answers it.** `statement_imports.import_job_id` is `UNIQUE` (V67), so:

`StatementImportRepository.findByImportJobId` **does not exist yet** — it is part of step 4 below,
not something to be assumed available.

```
resume(job in APPLYING):
    existing = statementImportRepository.findByImportJobId(job.id)   // to be added
    if present  ->  the transaction committed. Record the replay (§6), COMPLETED.
    if absent   ->  it rolled back. Re-apply normally.
```

This is a read, and a read can race — but the write it guards is protected by the unique index, so
the worst case is a second worker attempting the apply and being rejected by the database rather
than duplicating anything. **The check is an optimisation; the constraint is the correctness.**

---

## 6. Replay diagnostics

When a replay is detected, record why — support engineers should not have to infer it:

```
import_jobs
  replayed_of_import_id  UUID        -- the StatementImport that already existed
  replayed_at            TIMESTAMPTZ
  replayed_by            VARCHAR(64) -- the correlation id of the worker pass that noticed
```

So instead of a job that mysteriously completed with no work, the row says:

> Already applied by import `1f6a…`, detected `08:41 UTC` by `worker-3f2b…`

The correlation id then ties straight into the logs, audit rows and Sentry events for that pass —
the convention `WorkerObservability` already establishes.

---

## 7. Observability

No new framework. `WorkerExecution` already reports the lifecycle, and these map onto it:

| Model event | Framework call |
|---|---|
| Job claimed | `claimed(n)` |
| Job started | `started(jobId, queuedAt)` |
| Reached `COMPLETED` | `completed(jobId)` |
| Any `FAILED_*` after retries | `deadLettered(...)` |
| Retryable failure | `retryScheduled(...)` |
| Recovery of an abandoned job | `recovered(n)` |

**One addition worth making:** a `stage` tag on the existing counters, so
`finora_worker_dead_letters{worker="import",stage="FAILED_PARSE"}` separates a statement-format
problem from an infrastructure one on the dashboard that already exists. `stage` is low-cardinality
and belongs on the tag allowlist alongside `phase` and `outcome`.

---

## 8. What this does not solve

- **Notifications, webhooks, emails, analytics** do not exist in the import path. When added, each
  is an external side effect that no transaction can roll back, and each will need its own answer.
  The single-checkpoint design holds only while every unsafe effect is inside one database
  transaction; the first external call breaks that and forces the two-checkpoint model.
- **`row_ordinal` stability.** Fine for CSV. PDF re-extraction or a future API import may not
  produce a stable order, which would defeat V67's transaction constraint. A content-derived
  fingerprint is the eventual answer.
- **Multi-worker deployment** is unblocked by V67 but not proven. It needs a test that runs two
  workers against one queue, which is a different exercise from anything here.
- **Balance derivation.** Checkpointing makes the net-delta branch safe; it does not make it
  *correct*. Deriving balances from the ledger rather than accumulating remains the better answer
  and belongs in its own discussion.

---

## 9. Implementation order

Each step is independently reviewable and leaves the system working.

| # | Change | Notes |
|---|---|---|
| 1 | Prune unreachable states; add `FETCHING`, `STAGED`, `FAILED_*` | Migration + enum. No behaviour change — the worker still stages only |
| 2 | Worker transitions through the real states | Still no confirmation; states become reachable |
| 3 | Replay diagnostic columns | Unused until step 4, but cheap and additive |
| 4 | **Move confirmation into `APPLYING`**, with the resume check | The milestone. Adds `StatementImportRepository.findByImportJobId`. Confirm and learning stay in one transaction |
| 5 | `stage` tag on worker metrics | Dashboard follows |

Step 4 is the only one that changes user-visible behaviour, and it is the one to review hardest.

---

## 10. The argument in one line

**Only one operation in the import pipeline is unsafe to repeat, and it is already atomic — so the
execution model needs one checkpoint, not a step log.**
