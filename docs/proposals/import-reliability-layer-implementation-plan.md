# Premium Import Reliability v1 — Implementation Plan

**Status (as of PM review round 5, 2026-08-12) — design phase closed:**

| | |
|---|---|
| Architecture | ✅ Approved |
| Lifecycle model | ✅ Approved |
| Retry model | ✅ Approved |
| Failure classification | ✅ Approved |
| Failure UX | ✅ Approved |
| Observability | ✅ Approved |
| Audit trail | ✅ Already covered (round 4) — see §4's guardrail note, not a new build |
| Sprint sequencing | ✅ Approved |
| Implementation | ⚠️ Waiting for execution |

**Further design review is understood to have diminishing returns from here — the remaining risk is
execution quality, test coverage, and rollout discipline, not more planning.** The one remaining item before
Sprint 1 starts is Step 0's Railway confirmation (`app.import.queue.enabled`,
`app.statement-storage.provider`) — see below; Sprints 1-3 don't depend on it and can start regardless.
Every open design question raised across rounds 2-5 is resolved and reflected in this document. **No code
has been written or modified to produce this document** — it is planning only. Built on
`docs/architecture/system-design/import-reliability-layer-audit.md` (read-only audit, 2026-08-12),
`docs/proposals/import-reliability-layer-remediation-proposal.md` (options/tradeoffs, same day), and the
`ImportJobWorker` retry-classification investigation (read-only, same day, not yet a committed doc — its
findings are folded into §5 below). Where those source documents laid out choices without picking one,
this document picks — every subsection below ends in a concrete sequence, not a menu. Spot-checked against
the current repo state on 2026-08-12; see the "current vs. documented" note at the end of each subsection
where anything had drifted.

**Scope: this is now the "Premium Import Reliability v1" plan.** Six areas: import lifecycle states,
original-PDF retention, retry without re-upload, observability, retry classification and failure handling
(§5, merged in from the retry audit), and a failure UX contract (§6). **Phase 2 (the P-002/SBI parser
investigation) is explicitly out of scope for this document** — it is a separate, already-understood item
on the parser board and nothing below assumes or blocks on it.

**PM review round 2 (2026-08-12), approved with three changes, all applied below:** (1) retry-classification
ownership split — `ErrorCode` carries `retryPolicy()` for known import failures only; a new, standalone
`ExceptionClassifier` owns infrastructure and unknown-exception dispatch (§5); (2) a fifth user-facing
state, `ACTION_REQUIRED`, distinguishing "you need to do one specific thing" from a plain, no-clear-fix
`Failed` (§1); (3) an import timeline and a user-facing self-service import detail page added to Phase 1
observability, not deferred (§4). The ordered task list is resequenced into four sprints per the PM's
stated preference (customer-facing safety first, then the retry engine, then recovery, then production
verification) rather than the flatter Phase A/B split from round 1.

**PM review round 3 (2026-08-12), final approval, two changes applied below:** (1) the `ACTION_REQUIRED`
mapping is broadened — `IMPORT_NO_HEADER_DETECTED` and `IMPORT_NO_TRANSACTIONS_FOUND` move from plain
`FAILED` to `ACTION_REQUIRED`, under a clarified governing rule (§1: "`ACTION_REQUIRED` = the user can
reasonably correct the input; `FAILED` = the user cannot fix it without Finora or support"), with the §6 UX
contract's message copy updated to match; (2) a lightweight failure-analytics query added to §4 — grouped
counts of `failure_code`/last-seen/best-effort bank over the existing `StatementAnalysisSession` data,
explicitly scoped as a query, not a dashboard, so "what's actually failing after launch" is answerable
without waiting on support tickets to surface it one at a time.

**PM review round 4 (2026-08-12), final sign-off — no new scope, one clarifying edit.** The PM asked for a
"failure event audit trail" distinct from the analytics query. On inspection, this was already fully
covered by two things already in this document plus one pre-existing feature, not something to add: the
admin trace UI (`ImportTrace.tsx`, pre-dates this plan, already renders per-stage timings from
`ImportJobStage`) is the audit-side half; §4.6/4.7 (this document's own import timeline + self-service
detail page) is the user-facing half. §4's "Added per PM review" subsection now states this explicitly so
the connection isn't left implicit. **Architecture, scope, failure model, UX contract, and sprint sequencing
are all approved as of this round.** The only remaining item before Sprint 1 starts is Step 0's Railway
confirmation.

**Spot-check note on recent commits:** `98560ef` and `c5c4e0f` (both landed 2026-08-12, same day as the
audit) are P-002 parser-correctness fixes — one rejects multi-section imports that extract zero
transactions anywhere, the other stops `PdfTableLocator` from misreading prose (fee schedules, MITC
clauses) as table headers. Both are extraction-quality fixes to *what counts as a successful parse*, not
to the lifecycle/retention/retry/observability layer this plan covers. Neither touches `ImportService`'s
staging/failure-recording control flow, `ImportSessionService`, `StatementContentService`,
`ImportJobService`, or any frontend file this plan depends on. Nothing below needs updating on their
account.

---

## Step 0 — The decision gate (do this first, before any other line item)

Both source documents flag the same blocking unknown: whether `app.import.queue.enabled` is `true` in
the production Railway environment, and what `app.statement-storage.provider` actually resolves to.
`ProductionConfigValidator.java` (merged `2c22dd6`, three days before the audit) now refuses to boot in
the `prod` Spring profile with no storage provider set, which makes "some provider is set" a near-certainty
given the app is live — but it says **nothing about which provider**, whether it is durable
(a filesystem provider pointed at Railway's ephemeral disk would pass the same boot check and still lose
bytes on redeploy), and **nothing at all** about the queue flag, which has no boot-time validator.

**Action:** the PM (Sid) confirms two values directly against the Railway dashboard, not the repo:

1. Is `app.import.queue.enabled` `true` or `false` in production right now?
2. What is `app.statement-storage.provider` set to — `r2` or `filesystem` — and if `r2`, are the
   `app.statement-storage.r2.*` credentials valid under load, not just at boot (a wrong-but-present
   credential still passes the boot check, since the validator only checks presence, not connectivity)?

This is a five-minute dashboard lookup, not an engineering task, and it is **step 0 for a reason**: every
cost and risk estimate below is written twice, once per branch, because the two answers change the shape
of nearly everything else in this plan.

### Branch A — queue already on, storage already durable (`r2`, credentials valid)

If both answers come back favorable, most of Phase 1's backend is **already shipped and running in
production today**. The 9-state `ImportJob` machine, 5-attempt automatic retry with backoff, and
byte-retention-on-failure are live for every user right now; nobody has been told. Phase 1 work under this
branch is almost entirely: (a) verification that this is really true under load, not just at boot, and
(b) frontend work to surface state that already exists server-side. This is the cheap branch.

### Branch B — queue off, or storage unresolved/non-durable

If the queue is off (the audit's stated default, and the more likely case absent contrary evidence — the
storage-provider validator only forces *a* provider to be set, it does not force the queue on, and they
are independent flags), then turning the queue on is a **live production behavior change to the primary
upload path**: a different code path handling every statement upload, different latency and timeout
characteristics, a worker that can back up under load, and a queue depth that has never been exercised
against real traffic. This branch requires load-testing and a rollback plan before flip, sequenced as real
backend rollout work, not a same-day config flip. If storage resolves to `filesystem` on ephemeral disk,
that is its own separate finding requiring a migration to `r2` regardless of the queue answer, and blocks
any plan item that assumes byte durability.

**Per PM review round 5: a production safety review is a required gate before flipping the queue flag,
separate from and in addition to load-testing.** Load-testing answers "does the queue perform correctly
under volume"; the safety review answers "is the surrounding production environment ready for a primary
upload path to change" — and per the standing pre-launch safety findings (project memory, cross-referenced
in the remediation proposal §0), that environment currently has two open, unrelated gaps: no confirmed
PostgreSQL backup/recovery path on the current Railway plan, and `SENTRY_DSN` unset in prod (no confirmed
error alerting). Flipping the primary upload path to a new code path while those two gaps are still open is
a materially different risk than flipping it once they're closed. This review is scoped as part of task
19b/20b below (Sprint 4, Branch B), not a new document — it's a checklist item on that rollout's own gate,
not separate design work.

**Everything below is written so each step states which branch it depends on, if any, so the team can
start executing branch-independent items immediately without waiting on step 0's answer, and gate the rest
behind it.**

---

## 1. Import lifecycle states

### What the PM sketched vs. what already exists

The PM's illustrative model — `UPLOADED → STORING → EXTRACTING → VALIDATING → COMPLETED/FAILED` — is a
clean five-state linear machine. The audit found that `ImportJob.Status`
(`backend/src/main/java/com/finora/entity/ImportJob.java:51`) already implements a **nine**-state machine
— `QUEUED, PARSING, ANALYZING, DEDUPING, IMPORTING, LEARNING, COMPLETED, FAILED, CANCELLED` — with guarded
transitions, per-stage timing rows (`ImportJobStage`), and automatic retry-with-backoff built in
(`MAX_ATTEMPTS = 5`, `ImportJob.java:67`). It is finer-grained than the PM's sketch, already correctly
models retry as a return-to-`QUEUED` transition rather than a separate `RETRYING` state, and is fully
built and tested. It is reachable **only on the async path**, which defaults off.

The companion `docs/proposals/data-import-intelligence-proposal.md` independently reached the identical
conclusion about a near-duplicate PM sketch (`UPLOADED/PROCESSING/COMPLETED/FAILED/RETRYING`) for the
admin-observability angle on the same code, and called building it fresh "a regression, not an addition."
That reasoning transfers directly here: **building the PM's simpler model as new work would mean shipping
a second, worse state machine next to a first one that already exists**, on a table (`import_jobs`) that
already has migrations, a repository, an admin trace view wired to it, and a worker consuming it.

### The decision

**Phase 1 lifecycle work is "expose and, if necessary, activate what already exists," not "build the
PM's simpler model as a new thing."** This is the single largest judgment call in this document, and the
PM could reasonably push back on it — the counter-argument is that a 9-state machine is more surface area
for a user-facing status pill than a 5-state one warrants, and the extra states (`ANALYZING`, `DEDUPING`,
`LEARNING`) are backend-internal concerns a user doesn't need to see distinguished. The plan below
resolves that by **not** exposing all nine states verbatim to the end user — it maps them to a small
user-facing vocabulary (below) while keeping the nine-state machine as the system of record, which gets
the PM's simplicity goal for the UI without discarding working, tested backend code or building a second,
competing source of truth. `StatementImport.status` — declared, never assigned, dead
(`StatementImport.java:104-105`) — should be either wired to reflect the same job-state mapping or removed;
carrying a field that has silently done nothing since it was written is worse than not having it.

**Revised per PM review to add a fifth state, `ACTION_REQUIRED`.** A flat `Failed` collapses two
meaningfully different experiences: "this will never work, try a different file or contact support" and
"this needs one specific thing from you, then it will work" (the clearest case: a password-protected PDF —
the fix is not "give up", it's "enter the password"). Telling a premium user "failed" when the real answer
is "we need your password" reads as more broken than it is.

`ACTION_REQUIRED` is not a new backend `ImportJob.Status` — it's a refinement of the existing `FAILED`
status, distinguished at the `ErrorCode` level by whether the failure has a single, concrete, user-doable
fix. This reuses the exact mechanism §5 already introduces (per-`ErrorCode` metadata) rather than adding a
second classification system: a `userActionRequired` (or equivalent) field alongside `retryPolicy()`.

**Governing rule, per PM review round 3 (finalized, not just a judgment call to revisit later):**

> `ACTION_REQUIRED` = the user can reasonably correct the input. `FAILED` = the user cannot fix it without
> Finora or support (an unsupported layout, a genuine parser gap, a system issue).

The first draft of this table drew the line too narrowly — it read "concrete fix" as "we can name the exact
mechanical step" (enter a password) and put every ambiguous-cause failure into plain `FAILED`. The PM's
correction: `IMPORT_NO_HEADER_DETECTED` and `IMPORT_NO_TRANSACTIONS_FOUND` are common results of a user
uploading the *wrong document* — a bank's homepage export, a terms-and-conditions PDF, a credit-card
marketing brochure, an account summary instead of a transaction statement — and "please upload the
transaction statement PDF from your bank" is exactly as actionable as "enter the password," even though
Finora can't be certain that's the cause. The state is not a claim that Finora is broken; it's a request for
a different input, which is `ACTION_REQUIRED`'s whole purpose.

| `ErrorCode` | `ACTION_REQUIRED` or plain `FAILED`? | Why |
|---|---|---|
| `IMPORT_PDF_PASSWORD_REQUIRED` | `ACTION_REQUIRED` | Concrete fix: enter the password |
| `IMPORT_PDF_PASSWORD_INVALID` | `ACTION_REQUIRED` | Concrete fix: re-check and re-enter the password |
| `IMPORT_SCANNED_OCR_REQUIRED` | `ACTION_REQUIRED` | Concrete fix: re-export the statement as a text PDF, not a scan |
| `IMPORT_NO_HEADER_DETECTED` | `ACTION_REQUIRED` | The user can reasonably be asked to check they uploaded the actual transaction statement, not another document from their bank — a real and common cause of this code |
| `IMPORT_NO_TRANSACTIONS_FOUND` | `ACTION_REQUIRED` | Same reasoning — a located-but-unreadable table is often the wrong document, and re-checking the upload is a reasonable, correctly-worded first ask |
| Corrupt/truncated PDF (once coded, §2.2) | `FAILED` | Re-uploading *might* fix a download corruption, but there is no single thing to tell the user to check or change — this stays `FAILED`, the clearest remaining case of "Finora/support needs to look at this" |

**Adjustable without a backend migration**, since this lives as data on `ErrorCode`, not as branching logic
— if support data after launch shows `ACTION_REQUIRED` messaging isn't actually resolving no-header/
no-transactions cases (i.e. users re-upload the identical file and hit the same code again), moving those
two back to plain `FAILED` is a one-line, no-migration change.

Proposed user-facing mapping (five states now, sourced from the nine-state machine plus the
`ACTION_REQUIRED` refinement of `FAILED`, rather than replacing either):

| User sees | Backed by |
|---|---|
| Processing | `ImportJob.Status`: `QUEUED`, `PARSING`, `ANALYZING`, `DEDUPING`, `IMPORTING`, `LEARNING` |
| Completed | `ImportJob.Status`: `COMPLETED` |
| Action Required | `ImportJob.Status`: `FAILED`, where the `ErrorCode`'s `userActionRequired` is true |
| Failed | `ImportJob.Status`: `FAILED`, where it is false (or the failure has no `ErrorCode` at all, e.g. an alerted tier-3 dead-letter) |
| Cancelled | `ImportJob.Status`: `CANCELLED` |

### Sequenced steps

| # | Step | Branch dependency | Effort | Risk | Depends on |
|---|---|---|---|---|---|
| 1.1 | Confirm via Railway dashboard + a live traced import whether `ImportJob` rows are actually being created in production today (Branch A check) | Needs step 0 answer | XS (query + one test upload) | None | Step 0 |
| 1.2 | If Branch A: write the user-facing 5-state mapping table above (including `ACTION_REQUIRED`) as a documented contract (which `ImportJob.Status` values fold into which user string), add a small mapping function server-side rather than leaking raw enum values into any new API response | A | S | Low | 1.1 confirms A |
| 1.3 | If Branch B: plan the queue-enable rollout as its own tracked workstream — load test against realistic upload volume/size, define a rollback trigger (e.g. queue depth or worker error rate threshold), stage it behind a percentage rollout if the infra supports it, **and confirm the production safety review gate (backups, Sentry alerting — see Branch B note above) before flipping**; **do not flip the flag Phase-1-wide without both** | B | L (multi-day: load test design, execution, rollback plan, staged flip, safety-gate confirmation) | High — primary upload path, real financial data already live | Step 0 confirms B |
| 1.4 | Decide and execute the fate of `StatementImport.status`: wire it to the mapping in 1.2, or remove the dead field and its column in a migration | Either | S | Low (dead code either way) | 1.2 (need the mapping decided first) |
| 1.5 | Add a `GET`-able job-state field to whatever response the frontend already polls/reads for import status, using the 5-state mapping, not the raw 9 enum values | A: ready once 1.2 lands. B: blocked until 1.3's rollout reaches production | S | Low | 1.2, and (B only) 1.3 |

**Note:** step 1.3 is the single largest and riskiest item in this entire plan. It should not be scheduled
inside the same sprint as the rest of Phase 1's UI work — if Branch B is confirmed, treat 1.3 as its own
tracked initiative with its own timeline, and let 1.1/1.2/1.4/1.5's Branch-A-only path define what "Phase
1 shipped" means for a first release, with 1.3 following once the rollout is actually safe.

---

## 2. Original PDF retention policy

### What exists, what's missing

Confirmed bytes retained: confirmed imports (indefinite, 90-day post-delete reclaim,
`StatementStorageSweepService.java:96`), staged-unconfirmed sessions (48h TTL,
`ImportSessionService.SESSION_TTL:41`), and — only when the queue is on — any async job outcome including
`FAILED`, because bytes are stored before the job row exists
(`ImportJobService.accept:151`, package `com.finora.imports.jobs`). The one confirmed zero: a **synchronous
failure stores nothing**, ever — `storeContent` is reachable only from `createSession`/`createMultiSection`,
and every failure path throws before either is called (`ImportService.java:189-190`, `316-325`,
package `com.finora.imports`).

The remediation proposal laid out four options (A: nothing, B: turn on the queue, C: add byte persistence
directly to the sync failure path, D: durable failure record without bytes) and one accurate constraint:
**storing bytes without a record to reference them (bytes-without-D) is worse than today's state** — it
converts a re-upload inconvenience into an orphaned-file cleanup problem. Its own view called out that C
and D are cheaper reasoned about independently than B is as a single unit, but did not commit to an order.

### The decision

**D first, unconditionally and immediately; B (or C, subordinate to B) second, gated on step 0.**

- **D is unconditional** because it has no dependency on the storage-provider question at all — it is a
  durable *row*, not durable *bytes*. It directly fixes the sharpest visibility gap (§3 below and the
  audit's "genuinely untrackable state" finding) with zero coupling to Branch A/B. Do this regardless of
  what step 0 answers.
- **B, not standalone C, is the byte-retention fix**, and it is deliberately not decoupled from the
  lifecycle decision in §1: turning on the queue gives durable bytes-on-failure *and* the state machine
  *and* automatic retry in one action, whereas C alone (byte persistence bolted onto the sync path without
  a queue) would need to reinvent a retry/cleanup story the queue already has, and risks exactly the
  orphaned-bytes failure mode the remediation proposal warned about if D isn't shipped first or in lockstep.
  Given D is already being built as step 2.1 below, C's main advantage over B (smaller, more contained
  change) stops being decisive — B is "do it once, correctly," and this plan has already committed to B
  for the lifecycle machine in §1's Branch B path, so paying for it twice under two different justifications
  is not efficient. **C is dropped from this plan**, not because it was a bad option, but because choosing
  B for §1 already buys everything C would have bought for §2, once D covers the record side.
- **Encryption-at-rest / access-control questions** (raised in the PM's original prompt to the audit) are
  already answered structurally for the durable case: R2 and Postgres `bytea` both inherit the platform's
  at-rest encryption; access is already ownership-gated for users
  (`OwnershipGuard.requireOwned`, cited in the audit at `StatementImportController.java:49-63`) and there is
  currently **no admin file-download endpoint at all** — support cannot retrieve bytes even for a confirmed
  import today. That gap is real but is an observability/support-tooling item, folded into §4 below (the
  admin-lookup work), not a separate retention decision.

### Sequenced steps

| # | Step | Branch dependency | Effort | Risk | Depends on |
|---|---|---|---|---|---|
| 2.1 | Build the durable failure record (Option D): on every sync-path failure, write a first-class, user-listable row — reusing or extending `StatementAnalysisSession` rather than inventing a new table, since it already captures reference/user/filename/failure-code/duration per attempt — and expose it through the same read path StatementHistory already uses, with an explicit "failed, no file retained, please re-upload" UX so it doesn't read as a broken retry affordance | None | M (backend row/read-path work + frontend list item + explicit "can't retry, re-upload" messaging) | Low — additive, no behavior change to existing paths | None — start immediately |
| 2.2 | Give the corrupt-PDF path a real `ErrorCode` instead of `failureCode = null` (`PdfTextExtractor.java:115-117`) — small, but makes 2.1's failure list and any histogram built on `failure_code` actually distinguish this case | None | XS | Low | None — can ship alongside or before 2.1 |
| 2.3 | If Branch A confirmed: verify (don't build) that failed-job bytes are actually retained and reclaimed on the same schedule as other object-storage bytes; confirm the `r2` credentials are valid under a real failing upload, not just at boot | A | S (verification task) | Low | Step 0 |
| 2.4 | If Branch B confirmed: byte retention on failure ships as part of §1's step 1.3 queue rollout — no separate engineering task, but explicitly track it as one of that rollout's acceptance criteria (a failed upload during the rollout test must have retrievable bytes) | B | Folded into 1.3 | Folded into 1.3 | 1.3 |
| 2.5 | Once bytes are durable on failure (either branch), add the manual retry action to 2.1's failure record — this is the point where a failed sync import becomes actually retryable, not just visible | A: after 2.3. B: after 1.3/2.4 ships | S (wire an existing pattern — the confirmed-import reimport button already exists as a template, `StatementHistory.tsx:79-104`) | Low | 2.1, and (A: 2.3 / B: 1.3) |

---

## 3. Retry mechanism ("no re-upload needed")

### What exists

The cheapest real win in the entire audit: the staged-session resume UI is **backend-complete and
unused**. `GET /import/sessions`, `GET /import/sessions/{id}` exist and work
(`ImportController.java:94-114`); `importApi.listSessions` and `importApi.discardSession` are defined in
`frontend/src/api/endpoints.ts:374,377` and, confirmed on spot-check of the whole `frontend/src` tree, are
called from **nowhere except test mocks** in `Import.test.tsx`. Zero coupling to the two Railway unknowns
— a staged session already has its bytes (48h TTL) regardless of queue/storage-provider state, because
staging happens on a different code path than the sync-failure gap this plan is otherwise working around.

### The decision

**This is the first thing built, not sequenced behind step 0 at all.** It is the highest ratio of user
value to engineering cost in this document: zero backend work, a known and tested API surface, and it
directly closes one of the two retry gaps identified in the audit (abandoned staged session — the other,
sync-failure retry, is handled by §2's sequence above).

### Sequenced steps

| # | Step | Branch dependency | Effort | Risk | Depends on |
|---|---|---|---|---|---|
| 3.1 | Add a "Resume" affordance: on the Import page and/or a banner in Statement History, call `importApi.listSessions` on load and surface any session inside its 48h window with file name, staged date, and a resume action that re-enters the existing confirm flow | None | S (UI over an already-tested backend contract) | Low | None — start immediately, first item in this whole plan |
| 3.2 | Wire `importApi.discardSession` to an explicit "discard" action next to resume, so a user who doesn't want to finish an old staged import isn't left with a stale banner until the 48h sweep runs | None | XS | Low | 3.1 (same surface) |
| 3.3 | Confirmed-import "Reimport" flow (`StatementHistory.tsx:79-104`) already works — no action needed, listed here only to close the loop: after 3.1/3.2 ship, all three retry paths (confirmed reimport, staged resume, and §2.5's failed-sync retry once bytes are durable) share one consistent "retry" visual pattern in the UI, worth a short design pass so they don't look like three different features | None | XS (consistency pass) | Low | 3.1, 2.5 |

---

## 4. Observability

### What exists vs. the PM's ask

The PM asked for: import ID, user ID, statement type, duration, failure reason, parser version.
Cross-referenced against the audit:

| PM's ask | Status |
|---|---|
| Import ID / correlation | ✅ `requestId` on every API response, persisted on both the analysis row and the job row (`StatementAnalysisSession.java:127-135`, `ImportJob.java:125-126`) |
| Duration | ✅ Already recorded per attempt |
| Failure reason | ✅ Structured `failureCode` + truncated detail + layout fingerprint, on every path including parser crashes (BH-028 widened the catch clause) |
| User ID | ⚠️ Exists on the row, **deliberately excluded** from every admin DTO (`ImportTraceDto.java:27-32`) |
| Statement type (file name / format) | ⚠️ Same — `fileName` exists on the row, excluded from the DTO; `sourceFormat` (PDF/CSV) is present but bank identity is not tracked anywhere |
| Parser version | ❌ Does not exist as a concept. Zero grep hits for `parserVersion`/`parser_version`/`engineVersion`/`schemaVersion` across `backend/src/main` |

The admin trace UI itself (`ImportTrace.tsx`, `AdminImportTraceController.java`, package
`com.finora.controller`) is real, detailed, and gated on `PLATFORM_DIAGNOSTICS_VIEW` — but every lookup
into it requires an internal `SA-YYYYMMDD-NNNN` reference or a job id, neither of which a real user
complaint ever contains. **The gap is entirely at the entry point, not the data or the UI built on it.**

### Cross-reference: parser version is already scoped elsewhere, not here

`docs/proposals/data-import-intelligence-proposal.md` §3.3 already scopes a `parser_version` column
at S effort, as part of that document's admin-observability design. This plan does not re-scope it —
noting it here only so Phase 1's observability work doesn't accidentally duplicate it. If that proposal's
Phase-1-relevant slice (the column itself, not the dashboard it feeds) is judged small enough to pull
into this phase for schedule reasons, that's the PM's call to fold in, not a default assumption of this
plan.

### Added per PM review: import timeline and a user-facing self-service detail page

Two related gaps the first draft under-scoped. Both reuse data that already exists — neither requires new
capture, only new surface. **Together, these two items ARE the "failure event audit trail" raised in PM
review round 4 — not a separate thing to build.** The admin half of that ask is already shipped and
pre-dates this plan entirely: the audit confirmed `ImportTrace.tsx` already renders per-stage timings,
failure code, and completion state from `ImportJobStage` for support/engineering. What's missing — and what
4.6/4.7 below build — is the SAME data, reshaped and exposed to the user who owns the import, not a new
capture mechanism or a second timeline implementation. One data source (`ImportJobStage`, plus the
sync-path event points §2.1 adds), two presentations: the existing admin trace view, and this section's new
user-facing one.

**Implementation guardrail, per PM review round 5 — one event model, permission-aware projections, not
permission-aware capture.** "Same source, two presentations" must not become "same payload, two audiences."
`ImportJobStage`/the sync-path event points are the single source of truth for BOTH views, but 4.6's read
endpoint needs two distinct response shapes drawn from it, not one shape gated by a role check:

- **User-facing projection** (feeds 4.7): stage name in plain language ("Uploaded statement", "Reading
  transactions", "Action required"), timestamp, and — only on the terminal failed/action-required stage —
  the §6 contract's message. Never a `PdfTableLocator` internal, a density score, an exception class name,
  or a stack trace reference.
- **Admin-facing projection** (the existing `ImportTrace.tsx`, unchanged by this plan): import ID, user ID,
  request ID, failure code, stage timings, layout fingerprint, and whatever else `AdminImportTraceController`
  already returns — this view already exists and is not being narrowed.

This is a code-review-time check for whoever implements 4.6, not a new design decision — call it out
explicitly in that step's PR/review so a single shared DTO with a `if (isAdmin)` branch doesn't quietly
become the implementation (the failure mode this guardrail exists to prevent: one response shape, fields
conditionally included, which is much easier to get wrong under a permission check than two DTOs built for
two audiences from the start).

**Import timeline.** `ImportJobStage` already records per-stage timing rows (the audit confirmed this is
"genuinely strong" infrastructure). Today that data is admin-only, inside the trace UI. The PM's point:
showing the SAME timeline to the user who owns the import — "10:02 Upload received, 10:02 PDF stored, 10:03
Parsing started, 10:04 Transaction extraction failed, 10:04 You were notified" — builds trust that the
system did something coherent, rather than a black box that returned an error, and it means a support
conversation starts from a shared timeline instead of the user having to describe what they think happened.

**Self-service import detail page.** The failure record from §2.1 and the timeline above are both data;
this is the page that presents them to the user as one artifact, keyed by a stable, user-shown ID (distinct
from the internal `SA-YYYYMMDD-NNNN` reference used for admin-side lookup today — §4.3 already plans to
surface that reference; this makes it the anchor of a real page, not just a string in an error toast):
status, failure reason in the §6 contract's plain language, the file name, what the user can do next, and
the ID to quote if they contact support. This is the single biggest lever for reducing support ticket
volume identified anywhere in this document — a user who can self-answer "why did this fail and what do I
do" never generates a ticket in the first place.

### Added per PM review round 3: failure analytics (backend metrics, not a v1 dashboard)

The PM's framing: after launch, the first question is "what percentage of premium users fail an import,"
and without this, the only way to find out is through support tickets arriving one at a time. This does
not need a dashboard in v1 — every input already exists on `StatementAnalysisSession`'s `failure_code`
column (per-attempt, already written on every failure path today, sync and async) and the layout fingerprint
that approximates "which bank." What's missing is a **query**, not new capture:

```
failure_code | count | last_seen | bank (best-effort, from layout fingerprint where resolvable)
```

A single grouped-count query against the existing table, exposed as one more admin endpoint (reusing the
`PLATFORM_DIAGNOSTICS_VIEW` gate §4's admin work already sits behind) is enough to answer "what's actually
breaking, and how often" without building a dashboard nobody's asked for yet. If usage later justifies a
real dashboard, that's a separate, larger `data-import-intelligence-proposal.md`-scale initiative, not a
Phase 1 line item — this document scopes only the query.

### The decision

**Ship the admin-lookup fix (userId/fileName reachable from a complaint), the import timeline, the
self-service detail page, and the failure-count query as the Phase 1 observability work; treat parser
version as out of scope for this document per the cross-reference above.** The remediation proposal's own
view — that adding the two excluded fields plus a repository query is more durable than relying on a user
correctly quoting a reference — is adopted here without change. None of this section depends on Step 0: it's
DTO/query/UI work over data already being written on both the sync and async paths today.

### Sequenced steps

| # | Step | Branch dependency | Effort | Risk | Depends on |
|---|---|---|---|---|---|
| 4.1 | Add `userId`/`fileName` to the admin trace DTO (`ImportTraceDto.java`) and a repository method querying `statement_analysis_sessions` by user id or email | None | S–M (fields exist on the entity already, per the audit — this is a DTO change plus one new repository method, not a migration) | Low, but note the deliberate-exclusion history: confirm with whoever excluded them originally (git blame / commit history) that there wasn't a privacy reason not visible from the code alone before re-adding them to an admin-facing DTO | None |
| 4.2 | Add a search box to the admin trace UI (`ImportTrace.tsx`) wired to 4.1's new query, so support can start from "user emailed us" instead of "user has a reference number" | None | S | Low | 4.1 |
| 4.3 | Return the internal analysis reference to the user on failure (surfaced in §2.1's failure record UI), so a user who does capture it gives support a fast path directly to the existing by-reference lookup | None | XS | Low | 2.1 (shares the same UI surface) |
| 4.4 | Give the corrupt-PDF failure a real error code — already sequenced as 2.2 above, listed here only because it's as much an observability fix (an unclassifiable row in any `failure_code` histogram) as a frontend one | None | (counted under 2.2, not double-counted) | — | 2.2 |
| 4.5 | Cross-check with the data-import-intelligence-proposal owner before either document's next revision, so `parser_version` isn't scoped twice | None | XS (a conversation, not code) | None | None |
| 4.6 | Build a read endpoint that assembles `ImportJobStage` rows (or the sync-path equivalent event points once §2.1 exists) into an ordered timeline for a given import, scoped to its owner — **a dedicated user-facing projection (plain-language stage names, §6-contract message on the terminal stage only), not the admin trace's payload gated by a role check** (see the guardrail above) | None | S–M (the rows already exist; this is a query + shaping endpoint, not new capture) | Low, but review the response shape specifically for accidental internal-field leakage before merging (density scores, exception types, stack traces) | 2.1 (needs sync-path events to exist for the sync case; async-path timeline can ship independently since `ImportJobStage` already exists) |
| 4.7 | Build the self-service import detail page: status (using §1's 5-state mapping), the §6 contract's message for the failure code, file name, "what you can do" copy, the shareable ID from 4.3, and 4.6's timeline | None | M (a new frontend page, but every data source it reads already exists by this point in the sequence) | Low | 4.3, 4.6, 6.2 (needs the UX contract to source its message) |
| 4.8 | Link to 4.7's detail page from Statement History and from the failure-record UI (2.1), so it's reachable from where a user would actually be when they want it, not just from a direct URL | None | XS | Low | 4.7 |
| 4.9 | Failure-count query: `failure_code`, count, last-seen, best-effort bank (from layout fingerprint), grouped over a rolling window, exposed as one admin endpoint behind `PLATFORM_DIAGNOSTICS_VIEW` — no dashboard, no new capture, a single grouped query over `StatementAnalysisSession` | None | S | Low | None — start immediately, independent of everything else in this document |

---

## 5. Retry classification and failure handling

### The problem, confirmed by direct code reading, not estimated

`ImportJobWorker`'s only exception handler (`ImportJobWorker.java:249`, `catch (Exception e)`) treats every
failure identically. It calls `describe(cause)` (line 346-348) — `e.getClass().getSimpleName() + ": " +
e.getMessage()` — which turns the exception into a **string for the admin log only**; nothing downstream
ever inspects it programmatically. `ImportJob.recordFailure(String error, Instant now)`
(`ImportJob.java:269-286`) has exactly one branch: `attemptCount >= MAX_ATTEMPTS` (5). A password-protected
PDF, a missing transaction table, an R2 outage, and an unhandled `NullPointerException` from a parser bug
are the same `Exception` to this code.

Backoff is confirmed exact by `ImportJob.backoffFor` (`ImportJob.java:289-291`,
`Duration.ofMinutes(1L << min(max(attempts,1)-1, 4))`) and its own test
(`ImportJobTest.java:94-98`): 1, 2, 4, 8, 16 minutes, **31 minutes total** from first failure to
`DEAD_LETTERED`. Concurrency cost is real but narrow, not catastrophic: the worker pool
(`BackgroundWorkConfig.importQueueExecutor`, `corePoolSize=1, maxPoolSize=2`) backs only the async nudge
trigger, not the parse work itself; a failing job releases its claim slot immediately rather than holding a
thread, so the actual cost of this gap is overwhelmingly **user-facing latency, not worker starvation** — a
premium customer with a password-protected statement waits up to 31 minutes to hear what a synchronous
upload tells them in seconds.

**No precedent exists anywhere in the codebase for classifying exceptions by retry-worthiness.** A
repo-wide grep for `retryable`/`transient`/`permanent` exception concepts returns exactly one hit —
`LearningQueueDto.retryable`, a UI-facing flag derived from queue state (`attemptCount < maxAttempts`) for
an admin dashboard button, not an exception-type classification, and it has no counterpart on the import
path. `ErrorCode.java` carries no retry metadata per code today. This is net-new work, not a bug in
something that already exists.

### The decision: three-tier classification, not binary

A flat "retryable: true/false" model is insufficient for the case that actually matters most for
production safety — an unclassified application exception (a parser crash the codebase has never seen
before). Defaulting it to always-retryable repeats today's 31-minute waste on a bug that will never
succeed; defaulting it to never-retryable risks permanently dead-lettering a legitimately transient crash
on its first occurrence, with no visibility that it happened. Three tiers instead:

| Tier | Examples (confirmed by the retry audit against real code) | Behavior |
|---|---|---|
| **1. Permanent (known user/document failure)** | `IMPORT_PDF_PASSWORD_REQUIRED`, `IMPORT_PDF_PASSWORD_INVALID` (never actually fires on the async path today — the worker always passes `password=null`, `ImportJobWorker.java:266-272` — but still needs marking for correctness/future-proofing), `IMPORT_NO_HEADER_DETECTED`, `IMPORT_NO_TRANSACTIONS_FOUND`, `IMPORT_SCANNED_OCR_REQUIRED`, the corrupt-PDF case once it has a real code (§2.2/4.4) | Fail immediately, first attempt. No retry. |
| **2. Transient (infrastructure)** | `StatementStorageException` (R2, `R2StatementStorage.java:196-224`), Spring `DataAccessException` (DB unavailable, query timeout) — both already fall through the same catch-all today and are retried correctly, but by accident, not by design | Retry with the existing backoff, unchanged (1, 2, 4, 8, 16 min, 5 attempts). |
| **3. Unknown (unclassified application exception)** | An uncaught `RuntimeException`/`NullPointerException`/`IllegalStateException` from a genuine parser bug, or any exception type this classification doesn't recognize | Retry **once**. If the retry also fails, dead-letter immediately (do not spend all 5 attempts) and alert engineering — this is precisely the case where monitoring earns its keep, since it's neither a known transient cause nor a known user error. |

Tier 3 exists because the honest answer to "is an unknown exception transient or permanent" is "we don't
know yet" — one retry absorbs a real transient blip without the full 31-minute cost of assuming it's always
one; failing fast after that (rather than exhausting all 5 attempts) stops a genuine bug from wasting 4 more
cycles finding out what one retry already told us.

### Where the signal lives — `ErrorCode` for known failures, a separate `ExceptionClassifier` for everything else

**Revised per PM review.** The first draft of this section put the whole `classify(e)` dispatch — including
the infrastructure and unknown-exception branches — next to `ErrorCode`. That's the wrong owner for two of
the three tiers: `ErrorCode` is a vocabulary of *known import/business failures* (`IMPORT_NO_HEADER_DETECTED`,
`IMPORT_PDF_PASSWORD_REQUIRED`), and `StatementStorageException`/a `DataAccessException` from a timed-out
Postgres connection are not import failures at all — they're infrastructure exceptions that happen to occur
while an import is running. Folding "R2 timed out" into the same enum that carries "this PDF has no header"
conflates two different domains that should be free to evolve independently (a new `ErrorCode` shouldn't
require touching infrastructure-classification logic, and a new infrastructure exception type shouldn't
require touching the import vocabulary).

**Split ownership:**

- `ErrorCode` gets exactly one new field — `RetryPolicy retryPolicy()` (`FAIL_FAST`, `RETRY`,
  `RETRY_ONCE_THEN_ALERT`) — scoped to what it already owns: every current `IMPORT_*` code defaults to
  `FAIL_FAST`, because every one of them is a known, permanent, user-input failure today.
- A new, standalone `ExceptionClassifier` component (`RetryDecision classify(Throwable e)`, same three-value
  return type) owns the dispatch across exception *types*, and defers to `ErrorCode.retryPolicy()` only for
  the one type that has an opinion of its own:

```
ExceptionClassifier.classify(e):
  if e instanceof ApiException ae:
      return ae.getCode() != null ? ae.getCode().retryPolicy() : RETRY_ONCE_THEN_ALERT  // unrecognized code -> tier 3, not tier 1 or 2
  if e instanceof StatementStorageException: return RETRY               // infrastructure -- R2/object storage
  if e instanceof DataAccessException:       return RETRY               // infrastructure -- DB/Redis
  else:                                       return RETRY_ONCE_THEN_ALERT  // tier 3 -- unknown application exception
```

`ImportJobWorker` calls `ExceptionClassifier.classify(e)` once at its catch site — a single dependency, not
a branch tree scattered through the worker — and `ExceptionClassifier` is where a future infrastructure
exception type (Redis, a new storage backend) gets added, without ever touching `ErrorCode`.

### Test impact

`ImportJobTest.java` (lines 103-116, 159-178, 224-252) calls `job.recordFailure("boom", Instant.now())`
with bare strings and asserts on attempt-count/backoff arithmetic only — no test asserts "different
exception types retry identically" as an intentional, documented invariant, so changing
`recordFailure`'s signature to accept a retry-tier signal is a mechanical test update, not a design
conflict with existing coverage.

### Sequenced steps

| # | Step | Branch dependency | Effort | Risk | Depends on |
|---|---|---|---|---|---|
| 5.1 | Add `RetryPolicy` enum (`FAIL_FAST`, `RETRY`, `RETRY_ONCE_THEN_ALERT`) and a `retryPolicy()` field per `ErrorCode`, defaulting every current `IMPORT_*` code to `FAIL_FAST` | None | S | Low — additive enum field, no behavior change until wired to the worker | None — start immediately |
| 5.2 | Give the corrupt-PDF path a real `ErrorCode` (already sequenced as 2.2/4.4) so it has a tier-1 home instead of falling through as an unrecognized code | None | (counted under 2.2, not double-counted) | — | 2.2 |
| 5.3 | Build the standalone `ExceptionClassifier` (`classify(Throwable): RetryDecision`) as its own class, not a method on `ErrorCode` or on the worker — dispatches by exception type, deferring to `ErrorCode.retryPolicy()` only for `ApiException` | None | S | Low — new, small, independently unit-testable class with no existing callers to break | 5.1 |
| 5.4 | Change `ImportJob.recordFailure`'s signature to accept the classification result and dead-letter immediately on `FAIL_FAST`, or after one attempt on `RETRY_ONCE_THEN_ALERT`; update `ImportJobTest` for the new signature | None (a synchronous-code change; only becomes operationally meaningful once the async path carries real traffic, but is correct to build and test regardless of Step 0's answer) | M | Medium — touches the core retry state machine; needs the existing backoff/attempt-count tests to keep passing for the `RETRY` tier unchanged | 5.1 |
| 5.5 | Wire `ExceptionClassifier.classify(e)` at `ImportJobWorker`'s catch site (line 249), replacing the current unconditional `recordFailure(execution, jobId, e)` call | None | S | Low — one call site, well-covered by 5.4's tests | 5.3, 5.4 |
| 5.6 | Add the "alert engineering" hook for `RETRY_ONCE_THEN_ALERT`'s dead-letter case | **Coupled to the standing pre-launch gap**: Sentry is integrated in code but `SENTRY_DSN` is unset in Railway prod (no confirmed alerting today) — this step's alert needs that gap resolved first, or it fires into a channel nobody watches | S once Sentry is live | Low (mechanical) once dependency clears | The Sentry/`SENTRY_DSN` pre-launch fix (tracked separately, not part of this plan) |

**Note:** 5.1/5.2/5.3 are branch-independent and can ship in the very first wave alongside §3's staged-session
resume work. 5.4/5.5 are also branch-independent — they change synchronous classification logic that's
correct regardless of whether the async queue is on, though their real-world impact is naturally larger
once Step 0 confirms Branch A/B, since that's when the async path carries production traffic. 5.6 is the
one item in this whole plan gated on a workstream outside Phase 1 entirely.

---

## 6. Failure UX contract

### The gap

Nothing in this plan so far defines what a premium user actually *reads* when an import fails. §2.1 and §1
reference "explicit failure messaging" and a 5-state status mapping, but neither commits to real copy. The
audit already found the backend writes good, specific prose into `message` for every `ErrorCode` — the gap
is that the frontend only branches on 2 of 6+ codes (`frontend/src/api/errorCodes.ts` defines only the two
password constants), so most of that good server-side prose either never reaches the user in a
differentiated way, or reaches them as a generic banner. This section is the missing link between §5's
classification and something a non-technical user reads without seeing an internal code.

### The contract

One row per `ErrorCode` currently in play (extend this table, don't replace it, as new codes are added —
it is the single source of truth for user-facing copy, not a one-off):

| `ErrorCode` | Retry tier (§5) | User-facing state (§1) | User-facing message |
|---|---|---|---|
| `IMPORT_PDF_PASSWORD_REQUIRED` | Permanent | `ACTION_REQUIRED` | "This PDF is password protected. Enter the password to continue, or remove it and upload again." |
| `IMPORT_PDF_PASSWORD_INVALID` | Permanent | `ACTION_REQUIRED` | "That password didn't open this statement. Please check it and try again." |
| `IMPORT_NO_HEADER_DETECTED` | Permanent | `ACTION_REQUIRED` | "We couldn't find a transaction table in this file. Please check that you've uploaded the transaction statement PDF from your bank, not a summary, terms document, or other export." |
| `IMPORT_NO_TRANSACTIONS_FOUND` | Permanent | `ACTION_REQUIRED` | "We found a table in this statement but couldn't read any transactions from it. Please double-check this is the transaction statement PDF from your bank — some other exports use a similar layout." |
| `IMPORT_SCANNED_OCR_REQUIRED` | Permanent (today — no OCR exists) | `ACTION_REQUIRED` | "This PDF appears to be a scanned image rather than text. Statements exported directly from your bank's website usually work best." |
| Corrupt/truncated PDF (needs a real code first, §2.2) | Permanent, once coded | `FAILED` | "This file appears to be damaged or incomplete. Please try downloading and uploading it again." |
| Unclassified system error (tier 3, after its one retry fails) | `RETRY_ONCE_THEN_ALERT` | `FAILED` | "We had a temporary problem processing your statement. Our team has been notified — please try again in a few minutes, or contact support if it keeps happening." |
| Infrastructure failure still mid-retry (tier 2) | `RETRY` | `PROCESSING` | "We're processing your statement — this can take a moment." (not an error state at all while retries are in flight; only surface a message if all retries are exhausted, which today doesn't happen for tier 2 since retries aren't capped differently from tier 1 pre-fix) |

**Principle governing every row:** state what Finora observed and, where relevant, what the user can do
about it — never claim more than the backend actually knows (mirrors `ExtractionCheck`'s own existing
message-writing discipline, e.g. the scanned-PDF message doesn't assert the file *is* a bank statement,
only what was observed). No row exposes an internal code, a stack trace, or an exception class name to the
user; tier 3's message deliberately does not describe what broke, only that it was noticed and reported.

### Sequenced steps

| # | Step | Branch dependency | Effort | Risk | Depends on |
|---|---|---|---|---|---|
| 6.1 | Extend `frontend/src/api/errorCodes.ts` with constants for every code in the contract table above, not just the two password ones | None | XS | Low | None — start immediately |
| 6.2 | Replace `Import.tsx`'s generic `else` branch (lines ~371-388) with a lookup against the contract table, one message per code | None | S | Low | 6.1 |
| 6.3 | Reuse the same contract/lookup for §2.1's failure-record UI and §3's retry surfaces, so a user sees the same message whether they hit the error live or come back to it later in their import history | None | XS (reuse, not new copy) | Low | 6.2, 2.1 |
| 6.4 | Async-path equivalent: today `Import.tsx`'s job-failure handling collapses to `job.error ?? 'That import could not be completed.'` — wire the same contract once §5's classification is providing a real code on the failed-job record | A: needed once Branch A confirms real async traffic exists today. B: needed once 1.3's rollout ships | S | Low | 6.1, 5.4, 5.5 |

---

## Ordered task list (sprint-ready)

Resequenced into four sprints per the PM's stated preference: customer-facing safety first (language and
visibility, since everything downstream depends on having the right words and the right record to show),
then the retry engine, then recovery, then the branch-gated production-verification work. Task IDs in
parentheses refer to the numbered steps in §1-§6 above. **Step 0's Railway request should go out on day
one regardless of sprint** — it's a five-minute dashboard lookup, not engineering time, and only Sprint 4
actually blocks on the answer.

### Sprint 1 — Customer safety (11 items, zero Railway dependency)

*Language and visibility before anything else: everything downstream — the failure record, the retry
engine's tiers, the detail page — reads better if the words and the "what happened" record already exist.*

| Order | Task | Depends on |
|---|---|---|
| 0 | **Step 0 request** (send today, in parallel — doesn't block Sprint 1-3) — ask Sid/PM to confirm `app.import.queue.enabled` and `app.statement-storage.provider` (+ credential validity) against Railway | — |
| 1 | Failure UX contract: extend `errorCodes.ts` with every code (6.1) | — |
| 2 | Failure UX contract: wire `Import.tsx`'s generic branch to it (6.2) | 1 |
| 3 | Durable sync-failure record, Option D (2.1) | — |
| 4 | Corrupt-PDF error code (2.2 / 4.4 / 5.2) — a triple-purpose fix: observability gap, admin-histogram gap, and a tier-1 retry-classification home | — |
| 5 | `RetryPolicy` field on `ErrorCode` + `userActionRequired` field for the `ACTION_REQUIRED` mapping (5.1, and §1's per-code `ACTION_REQUIRED`/`FAILED` table) — "structured failures," the metadata every later sprint reads | — |
| 6 | Reuse the UX contract in the failure-record UI (6.3) | 2, 3 |
| 7 | Document the user-facing 5-state mapping (§1, including `ACTION_REQUIRED`) as a contract — definition only; wiring it into a live API response is branch-gated, in Sprint 4 | 5 |
| 8 | Return the internal analysis reference to the user on failure (4.3) | 3 |
| 9 | Import timeline read endpoint (4.6) | 3 |
| 10 | Self-service import detail page (4.7), linked from Statement History and the failure record (4.8) | 8, 9, 2 |

### Sprint 2 — Reliability engine (3 items)

*The classification mechanism itself — safe to build and test in isolation, since it only becomes
operationally load-bearing once real traffic flows through `ImportJobWorker`, which Sprint 4 confirms.*

| Order | Task | Depends on |
|---|---|---|
| 11 | Build the standalone `ExceptionClassifier` (5.3) | Sprint 1 item 5 |
| 12 | Change `ImportJob.recordFailure`'s signature to accept a classification result; update `ImportJobTest` (5.4) | Sprint 1 item 5 |
| 13 | Wire `ExceptionClassifier.classify(e)` at `ImportJobWorker`'s catch site (5.5) | 11, 12 |

### Sprint 3 — Recovery (4 items)

*Retry paths a user can actually use, once Sprints 1-2 have given them somewhere to see a failure and a
reason for it.*

| Order | Task | Depends on |
|---|---|---|
| 14 | Staged-session Resume UI (3.1) — still the single highest value-to-cost item in this entire plan; could ship as early as Sprint 1 if the team wants to pull it forward, sequenced here only to match the PM's stated grouping | — |
| 15 | Staged-session Discard action (3.2) | 14 |
| 16 | Manual retry action on the sync-failure record (2.5) — durable here means Sprint 1's own record (item 3), not full byte retention; ships as "see it, know why, re-upload" until Sprint 4 lands full retry-without-reupload for the sync-failure case, if Branch B | 3 (Sprint 1) |
| 17 | Consistency pass across all three retry surfaces — confirmed reimport, staged resume, failed-sync retry (3.3) | 14, 16 |

### Sprint 4 — Production confidence (forks on Step 0's answer; 9 items)

| Order | Task | Branch | Depends on |
|---|---|---|---|
| 18 | Branch check: what did Step 0's Railway lookup return? | — | Step 0 |
| 19a | **[If A]** Verify job rows / retained bytes / valid `r2` credentials under a real failing upload (1.1, 2.3) | A | 18 |
| 19b | **[If B]** Scope and schedule the queue-enable rollout as its own tracked initiative: load test plan, rollback trigger, staged flip (1.3) | B | 18 |
| 20a | **[If A]** Wire the Sprint-1-documented 5-state mapping into a live API field; wire `StatementImport.status` to it or remove the dead field (1.2, 1.4, 1.5) | A | 19a, Sprint 1 item 7 |
| 20b | **[If B]** Execute the queue rollout (1.3 continued) — separate initiative, not a Phase-1-sprint line item, then wire the same 5-state mapping once it ships | B | 19b, Sprint 1 item 7 |
| 21 | Admin trace DTO + query by user/email, plus the search box (4.1, 4.2) — grouped here as the "is this whole system actually usable by support" check, though technically branch-independent and could pull forward if the team prefers | None (grouped with production-confidence work by choice, not by dependency) | — |
| 22 | Async job-failure UX wired to the same contract as Sprint 1 item 2 (6.4) | A: after 20a confirms real async traffic. B: after 20b ships | Sprint 1 item 1, Sprint 2 items 12-13 |
| 23 | Cross-check `parser_version` scope with the companion proposal owner (4.5) | None | — |
| 24 | Failure-count analytics query (4.9) — grouped here alongside admin search as operator-facing tooling, though branch-independent and equally shippable in Sprint 1 if the team prefers to answer "what's failing" from day one | None (grouped by choice, not dependency) | — |

**Not in any sprint — gated on external work:** the "alert engineering" hook for `RETRY_ONCE_THEN_ALERT`'s
dead-letter case (5.6) needs the standing Sentry/`SENTRY_DSN` pre-launch gap resolved first, or it fires
into a channel nobody watches. Track it against that fix, not against this plan's sprints.

**Reading the list:** Sprints 1-3 (18 of 24 sequenced items) need nothing from Step 0 and can run
back-to-back or overlapped — send the Railway request on day one and it'll have an answer well before
Sprint 4 needs it. Sprint 4 is where the plan's one real fork lives, and it's now explicitly the last
sprint rather than an implicit blocker sitting at the front of the list, matching the PM's framing that
customer-facing safety shouldn't wait on an infrastructure answer that doesn't actually gate it.
