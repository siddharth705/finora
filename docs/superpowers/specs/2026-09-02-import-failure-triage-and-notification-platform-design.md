# Import Failure Triage & Notification Platform — Design

**Status:** Approved design, ready for implementation planning. Two phases (see §3).

## 1. Objective

Today, when a bank statement import fails for a reason the parser doesn't recognize (a new
layout, a header/pattern mismatch — not a known, curated user error like a wrong password or
unsupported file type), the user sees a plain, unhelpful "FAILED" state and the failure is
otherwise only visible to engineering via an alert. This design gives those specific failures a
distinct held state: the user sees an honest "we're looking into it" message instead of a dead
end, the statement goes into an admin queue for troubleshooting (in practice: Sid, often with
Claude's help, the same way the real-world bank-statement corpus has been fixed to date), and
once the underlying parser bug is fixed, the held statement is automatically reprocessed — no
reupload — and the user is notified by push and email.

Known, curated failures (bad PDF password, unsupported file type, no header detected, etc.) are
explicitly **not** touched by this design — those already fail fast with a specific message and
stay that way.

## 2. Current behavior (verified against this codebase, not assumed)

- `ImportJob.Status` (`backend/src/main/java/com/finora/entity/ImportJob.java`) has no
  "held/needs triage" state. Every terminal failure — a known `ErrorCode`, a transient
  infra error that exhausted retries, or a genuinely unclassified exception — lands in the same
  `FAILED` bucket.
- The three-tier retry classification described in the "Premium Import Reliability v1" work is
  **already implemented**, not just designed: `ExceptionClassifier` (`backend/src/main/java/
  com/finora/imports/jobs/ExceptionClassifier.java`) maps a known `ErrorCode` to its own
  `RetryPolicy` (`FAIL_FAST` / `RETRY` / `RETRY_ONCE_THEN_ALERT`), and defaults **any** unrecognized
  exception type, or an `ApiException` with no code, to `RETRY_ONCE_THEN_ALERT` — retried once,
  then dead-lettered to plain `FAILED` with an `AlertSeverity.ERROR` alert
  (`ImportJobWorker.severityFor`, lines 382–396). This `RETRY_ONCE_THEN_ALERT` bucket is exactly
  the "header mismatch / pattern" class of failure this feature targets — the classification work
  is done; this design adds a *destination* for it other than plain `FAILED`.
- `UserFacingImportStatus` (`backend/src/main/java/com/finora/imports/jobs/
  UserFacingImportStatus.java`) collapses `ImportJob.Status` into what the user sees. An
  unclassified failure's `failureCode` is a raw exception class name, which
  `ErrorCode.userActionRequiredOrDefault` can't resolve — so it silently defaults to `false` and
  the user gets plain `FAILED`, not even today's best-available `ACTION_REQUIRED` treatment. This
  is the live gap this feature closes.
- The raw statement file **is already retained** on a `FAILED` async job, specifically so it can
  be reprocessed without reupload: `StatementStorageSweepService`
  (`backend/src/main/java/com/finora/service/StatementStorageSweepService.java`, `
  IMPORT_JOB_EXCLUDED_STATUSES`, lines 126–127) explicitly excludes `FAILED` jobs' objects from
  the garbage-collection sweep, with the class doc citing "retry without re-upload" as the reason,
  indefinitely (no TTL). `ImportJobWorker.readContent()` already re-reads by content address on
  every attempt. **Auto-reprocess without reupload is infrastructure that already exists** for
  this purpose — this design is its first real consumer.
- This all depends on the async job path (`ImportJob` / `ImportJobWorker`), gated by
  `app.import.queue.enabled` (no override found in any repo config file — confirmed separately
  that it is in fact enabled in production). The synchronous upload path
  (`ImportSessionService`/`StatementImport`) is a different code path that doesn't persist a job
  row with a storage address on failure; it is out of scope for this design (see §6).
- `com.finora.notification` does not exist in the codebase at all — `docs/proposals/
  notification-communication-platform-proposal.md` is a frozen, unimplemented design. Today,
  notification-shaped code is a handful of point-to-point calls to an `emailProvider` bean at
  specific call sites (e.g. `PasswordChangeService.sendPasswordChangedEmail`), each followed by
  its own `auditService.record(..., "EMAIL_SENT", ...)` — not a reusable module.

## 3. Two phases

**Phase A — build the frozen notification platform**, exactly to the architecture already locked
in `docs/proposals/notification-communication-platform-proposal.md` §2–§5. No new design
decisions here; this phase is execution of an existing spec, not a redesign. Its own document's
`§7` gate ("Sentry + production monitoring ready") is **not yet checked** — building it now is a
deliberate decision made in this conversation, in full knowledge of that gap, not an oversight.
Record that explicitly at the top of the implementation plan.

**Phase B — the import held-for-review feature** (the new design in §4 below), which becomes
Phase A's first real caller. Phase B depends on Phase A's `NotificationService` existing; it
cannot ship its "notify the user once fixed" step without it.

These should be two distinct implementation-plan tracks, sequenced A before B, not one
monolithic plan — they touch different parts of the system and have an explicit dependency
direction.

## 4. Phase B architecture — import held-for-review

### 4.1 New terminal state

Add `ImportJob.Status.HELD_FOR_REVIEW`. In `ImportJobWorker.recordFailure`, when
`FailureOutcome.DEAD_LETTERED` is reached **and** the classified `RetryPolicy` was
`RETRY_ONCE_THEN_ALERT` (i.e. genuinely unclassified — not a known `FAIL_FAST` `ErrorCode`, and
not a `RETRY`-classified transient-infra exhaustion), set status to `HELD_FOR_REVIEW` instead of
`FAILED`. Known-error and transient-infra-exhausted failures are unaffected and continue landing
in plain `FAILED` exactly as today — this scoping matches the "straightforward errors don't need
admin, header/pattern-mismatch errors do" decision made in brainstorming.

### 4.2 User-facing message

Extend `UserFacingImportStatus` with a `HELD_FOR_REVIEW` value, mapped 1:1 from
`ImportJob.Status.HELD_FOR_REVIEW`. Copy (final wording is a product/copy decision at
implementation time, but the intent is locked): *"We need to run some additional checks on this
statement before we can complete the import. We'll notify you once it's ready — no action needed
from you right now."* No time commitment (no "within an hour" or similar), and no
genuineness/fraud framing — both were explicit decisions in brainstorming. This is a true
statement (checks genuinely are being run), not a cover story.

### 4.3 Admin "Held Imports" queue

Mirror the existing `AdminLearningQueueController` / `AdminLearningQueueService` /
`LearningQueue.tsx` pattern (`backend/src/main/java/com/finora/controller/
AdminLearningQueueController.java` and `admin-portal/src/pages/LearningQueue.tsx`) as closely as
possible rather than inventing a new admin-UI shape:

- `AdminHeldImportController` — `/api/v1/admin/held-imports`, gated by a new
  `IMPORT_TRIAGE_MANAGE` authority.
  - `GET /` — paged, filterable list of `HELD_FOR_REVIEW` jobs.
  - `GET /summary` — counts for filter chips.
  - `GET /{jobId}` — detail view, including the `failureCode` (raw exception class name) and
    whatever diagnostic trace the existing `AdminImportTraceController`/
    `AdminImportRowTraceController` already expose for a job.
  - `POST /{jobId}/reprocess` — resets the job back to `QUEUED` (with attempt count reset) for
    `ImportJobWorker` to pick up; 409 if not `HELD_FOR_REVIEW`. Safe to call speculatively: if the
    underlying bug isn't actually fixed yet, the job simply lands back in `HELD_FOR_REVIEW` again.
  - `POST /reprocess-all` — bulk variant, same bounded-cap pattern as the learning queue's
    `retry-all` (`MAX_RETRY_ALL`-equivalent cap).
  - `POST /{jobId}/resolve` — gives up on triage without a fix; transitions to plain `FAILED`
    (the same terminal state this job would have reached today), takes an optional free-text
    reason recorded on the audit entry, not the entity — matching the learning queue's
    `resolve` shape exactly.
- `AdminHeldImportService` — `@Transactional`, wraps `ImportJobRepository` + `ImportJobWorker` +
  `AuditService`, nudges the worker only after a reprocess actually commits (same
  `TransactionSynchronizationManager`/`afterCommit` pattern `AdminLearningQueueService` already
  uses).
- `admin-portal/src/pages/HeldImports.tsx` — `AdminLayout` + `RequirePermission` + `DataTable`/
  `Pagination`, React Query, status-tone filter chips — same structural shape as
  `LearningQueue.tsx`, including its stated principle of never re-deriving server-owned
  state-machine rules client-side.

### 4.4 Audit logging

Every admin view of a held statement's content, and every reprocess/resolve action, gets an
`AuditService.record(...)` call following the exact pattern already used in
`PasswordChangeService`/`UserAccountLifecycleService`:
`auditService.record(adminUserId, "HELD_IMPORT_VIEWED", "ImportJob", jobId, Map.of(...))`, plus
`HELD_IMPORT_REPROCESSED` / `HELD_IMPORT_RESOLVED`, following the existing
`SCREAMING_SNAKE_CASE`, past-tense action-name convention.

### 4.5 Notification on success

When a reprocessed `HELD_FOR_REVIEW` job reaches `COMPLETED`, call
`NotificationService.request(...)` (Phase A) with a new type (e.g. `IMPORT_STATEMENT_READY`),
`category=FINANCIAL`, both `PUSH` and `EMAIL` channels, `priority=NORMAL` or `HIGH` (final choice
is implementation-time, per the frozen proposal's own "priority policy is implementation-time"
note) — not `CRITICAL`, which is reserved for security events per the frozen proposal. This is
the first real caller of the notification module; no notification-specific design work happens
here beyond picking the type/category/priority values — the mechanism itself is entirely Phase A.

### 4.6 Privacy disclosure

Non-engineering follow-up, not part of the implementation plan itself: the privacy policy needs a
line disclosing that failed imports may be manually reviewed by staff (and AI tooling) to resolve
the issue. Flagged here so it isn't lost, but it's a product/legal task for Sid, not a code
change.

## 5. Data model changes (Phase B)

- `ImportJob.Status`: add `HELD_FOR_REVIEW`. Exact position in the enum's ordinal ordering and
  whether it's excluded from `TERMINAL`/`IN_FLIGHT` groupings needs care at implementation
  time — it behaves like a terminal state for user-facing purposes but must remain
  distinguishable from `FAILED` for the sweep-exclusion and reprocess logic.
- `IMPORT_JOB_EXCLUDED_STATUSES` in `StatementStorageSweepService` must include
  `HELD_FOR_REVIEW` alongside `FAILED` (same reprocess-without-reupload reasoning applies, and a
  held job could plausibly sit for a long time awaiting a fix).
- No new tables for Phase B itself — it rides entirely on the existing `ImportJob` row and
  Phase A's new notification tables.
- New Flyway migration(s) as needed (e.g. if `Status` is a DB-backed enum/check constraint, not
  just a Java enum) — **do not hardcode a version number in this doc**; per project convention,
  fetch `origin/main` and re-check `backend/src/main/resources/db/migration` for the next free
  version immediately before writing the migration, since multiple sessions work this repo
  concurrently (latest at spec-writing time was `V122`).

## 6. Out of scope

- The synchronous import path (`ImportSessionService`/`StatementImport`) — no job row is
  persisted with a storage address on a sync-path failure today, so there is nothing to hold or
  reprocess for that path as currently built. If synchronous imports turn out to carry meaningful
  production volume, that's a separate, follow-up piece of work, not silently folded into this one.
- Transient-infra failures that exhaust retries (`RetryPolicy.RETRY`) and known `ErrorCode`
  failures (`RetryPolicy.FAIL_FAST`) — both continue exactly as today (plain `FAILED`, no admin
  queue). Only genuinely unclassified (`RETRY_ONCE_THEN_ALERT`) failures enter
  `HELD_FOR_REVIEW`.
- Any change to the copy/behavior of already-specific known-error messages.
- Everything Phase A's own source document (`docs/proposals/notification-communication-platform-
  proposal.md` §4) already places out of scope: OTP changes, Fino integration, rich admin
  analytics, SMS OTP, marketing notifications, in-app inbox UI, `DELIVERED`/`READ` states,
  localization, and any message broker/microservice split.

## 7. Risks and explicit decisions carried from brainstorming

- **Sentry/monitoring gate override**: Phase A proceeds despite the frozen proposal's own gate
  checklist showing "Sentry + production monitoring ready" unchecked. This was surfaced directly
  and is a deliberate, informed decision, not an oversight — worth re-confirming once Phase A is
  actually being picked up for implementation, in case circumstances have changed.
- **No SLA promise** to users on held statements — avoids ever breaking a promised deadline as
  triage volume grows beyond what one admin can review quickly.
- **Honest message copy**, not a genuineness/fraud-check cover story — avoids the trust risk of a
  financial app implying a user's own document is suspect when the real cause is a parser gap.
- **Held statements become real-world corpus material**: fixing a `HELD_FOR_REVIEW` job's
  underlying parser bug is, in practice, the same activity as the existing manual bug-hunt/corpus
  workflow — worth keeping that connection in mind at implementation time rather than building two
  parallel processes.
