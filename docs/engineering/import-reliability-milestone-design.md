# Import Reliability Milestone — Technical Design

**Status:** Design. Nothing here is built. Sections 1 and 2 contain **decisions that must be made
before any code is written** — three of them reverse or conflict with code already on `main`, and
one exposes a prerequisite the implementation brief does not name. Sections 3–6 are the design that
follows once those are settled.

**Source:** the *Implementation Brief – Remaining Architecture & Product Decisions*, which follows
the bug-hardening phase merged in `31d3f91` (33 of 38 reported findings plus 4 newly found ones).

**Relationship to other docs.** [import-flow.md](import-flow.md) documents the pipeline as it is
today — stage, review, confirm — and this document changes that pipeline, so the two will disagree
until this ships; import-flow.md is authoritative until then.
[financial-document-intelligence-principles.md](financial-document-intelligence-principles.md)
governs how the engine reads a document and is unaffected: this milestone is about what the
surrounding system is allowed to *write*, not about parsing.
[import-engine-improvement-proposal.md](import-engine-improvement-proposal.md) is a proposal;
where it overlaps, this document supersedes it.

---

## The rule this milestone exists to enforce

> An import that parsed correctly must complete, and must persist exactly what the user approved —
> regardless of what any auxiliary system (learning, normalization, analytics, recurring detection)
> does or fails to do.

Every work item below is a consequence of that one sentence. It is worth stating because it also
decides the arguments: when a design choice trades import reliability for learning quality,
reliability wins, and when it trades a user's explicit decision for an automatic one, the user
wins.

---

## 1. Decisions required before code

Five. Each blocks work. Recommendations given, but these are calls for the team, not for the
implementer.

### 1.1 WI3 reverses `AdminAnalysisService`, which merged hours earlier

`AdminAnalysisService` (in `0bc382d`) makes analysis persist nothing by running the real pipeline
inside a transaction it deliberately marks rollback-only. Its own class doc rejects the approach
WI3 now mandates, by name:

> *"Threading a 'dry run' flag down through normalize, suggest and resolve touches the hot path of
> every real import to serve a diagnostic. Skipping normalization changes what the engine actually
> does, so the analysis would stop describing what a customer would get — which destroys the reason
> for running it."*

Both designs achieve "analysis persists nothing." They differ in how, and the difference is real:

| | Rollback (shipped) | Dry-run flag (WI3) |
|---|---|---|
| Code path analysed | byte-identical to a customer import | diverges at every write site |
| Fidelity of the analysis | exact | approximate — it no longer measures the real thing |
| Cost to the import hot path | none | a flag threaded through `normalize` → `suggest` → `resolve` |
| Behaviour under a mid-parse crash | writes discarded by the transaction | writes already committed |
| Sequence/identity consumption | still burns sequences | none |

**Recommendation: keep the rollback for `AdminAnalysisService`, and adopt WI3's stricter rule for
the *user-facing staging* path only.** They are different problems wearing the same words. Admin
analysis is a diagnostic whose entire value is that it runs the identical code path; user staging
is a preview the user may abandon, where the objection is not "it wrote and rolled back" but
"Bug 36 — it wrote and *kept*." WI4's temporary-merchant workflow removes the need for staging to
write at all, which resolves that without a dry-run flag anywhere.

If the team wants one uniform rule instead, that is defensible — but it means rewriting code that
just landed, and whoever wrote it argued the other way in the file. Have the conversation before
reversing it.

### 1.2 WI4 assumes a canonical merchant registry that does not exist

The Merchant Review Center specifies a *Suggested Canonical Merchant*, *Number of Users*, and
*Bulk Merge*. All three imply a platform-level merchant identity. There isn't one.

`Merchant` carries `user_id NOT NULL` (V7). Every merchant row is one user's private record, and
`MerchantStatDto` says so explicitly: *"there's no shared/canonical merchant table today, this is
purely an aggregate view over every user's own private Merchant rows."* `MerchantService.merge()`
merges two merchants **within one user**.

So WI4 forks:

- **(a) Per-user review center.** No schema beyond a lifecycle column. Admins review one user's
  temporary merchants at a time. "Number of Users" becomes meaningless and *Bulk Merge* only
  operates inside a single user's set. Cheap, and much less useful.
- **(b) Introduce a canonical merchant registry.** A new platform-level table that per-user
  merchants point at. This is what the brief actually describes, and it makes cross-user
  suggestions and bulk merges real. It is also a substantial data-model change with a backfill,
  and it touches every merchant read path in the product.

**Recommendation: (b), but as its own scoped piece of work sequenced before the Review Center UI,
not folded into it.** Option (a) delivers a page that cannot do what the brief asks. Do not start
the Review Center until this is decided — the UI is a direct function of the answer.

### 1.3 WI7 conflicts with the change-password flow

WI7: *"No existing session should survive a password change or password reset."*

`PasswordChangeService.complete` deliberately does the opposite — the calling device stays signed
in, and revoking the rest is opt-in via `signOutOtherDevices` / `currentRefreshToken`. The reset
path already matches WI7 as of `f3f1c31`.

The cost of the brief's version: a user who changes their password in Settings is signed out of
the tab they are standing in, immediately, with no warning. That is correct for a *reset* (the
account may be compromised; no legitimate session is worth preserving) and hostile for a
*voluntary change*.

**Recommendation: keep the current-device exemption for change-password; unconditional revocation
for reset.** If the team wants it uniform, make the sign-out explicit in the UI before it happens,
not a surprise.

### 1.4 WI1 needs background-job infrastructure that does not exist

There is no async or scheduled execution anywhere in the backend. Four files record its absence as
a deliberate constraint (`RateLimiter`, `ImportSession`, `ImportSessionService`,
`ImportSessionRepository`). WI1 needs event publication, a worker, exponential backoff and a retry
scheduler; none of the primitives exist.

This is **Deliverable 0** and it is not in the brief's list. Designed in section 2.

### 1.5 WI6 is already implemented — and it closes Bug 34

`PasswordHistoryService.HISTORY_LIMIT = 5`, enforced from both `AuthService.resetPassword` and
`PasswordChangeService.complete`. Zero work.

Its real content is a **product decision that resolves a deferred finding**. Bug 34 (a reset
performs up to seven bcrypt(12) hashes, ~1.5–2s of CPU behind a 10-per-10-minutes-per-IP limiter)
was deferred precisely because reducing it means weakening this policy. WI6 confirms the policy
stands, so Bug 34 is **resolved as by-design**, not deferred. Record it that way; the alternative
is someone re-opening it every audit.

---

## 2. Deliverable 0 — the asynchronous foundation

WI1 and WI2 rest entirely on this. Get it wrong and every retry is either lost or run twice.

### 2.1 Constraint that decides the design

Railway can run more than one instance. Any design where a poller assumes it is alone will
double-process the queue: two workers claim the same failed learning event, both apply it, and the
merchant's confirmation count is incremented twice. Confirmation counts drive
`ConfidenceEngine.topCategory`, which decides the auto-applied category — so double-processing
corrupts the learning distribution rather than merely wasting work.

In-memory `@Async` also loses the queue on restart, and a deploy is a restart.

### 2.2 Design: a database-backed queue claimed with row locks

```
merchant_learning_events
  id, user_id, merchant_id, category_id, source_statement_import_id,
  status,            -- PENDING | PROCESSING | COMPLETED | FAILED
  attempt_count,     -- 0..5
  next_attempt_at,   -- exponential backoff
  last_error,
  created_at, updated_at, first_failed_at, last_retry_at
```

Claiming:

```sql
SELECT * FROM merchant_learning_events
 WHERE status = 'PENDING' AND next_attempt_at <= now()
 ORDER BY next_attempt_at
 FOR UPDATE SKIP LOCKED
 LIMIT :batch
```

`FOR UPDATE SKIP LOCKED` is what makes multiple instances safe: a row claimed by one worker is
invisible to the others rather than contended. This is the same reasoning already applied in
`ImportSessionRepository.claimForConfirmation`, which uses an atomic conditional `UPDATE` to stop
two concurrent confirms importing the same statement twice — the pattern exists in this codebase
and should be reused rather than reinvented.

Backoff: `next_attempt_at = now() + 2^attempt_count minutes`, capped at 5 attempts (1, 2, 4, 8,
16 min). After the fifth, `status = FAILED` and the row surfaces in WI2's queue page.

### 2.3 Publishing without reintroducing the bug this milestone exists to fix

The event row must be written **in the import's own transaction**, and the worker must run
**after that transaction commits**. Writing it outside means an import that rolls back still
queues learning for transactions that do not exist; processing it inside means a learning failure
can once again take the import down — which is Bug 02 by another route.

Use `TransactionSynchronizationManager.registerSynchronization(...)` `afterCommit` to trigger the
worker, exactly as `SetupService.completeSetup` now defers its irreversible file deletion. The row
is inserted transactionally; only the *nudge* is deferred. If the nudge is lost (crash between
commit and notify), the poller picks the row up on its next pass — which is why the poller exists
even though the nudge usually beats it.

**This is what actually closes Bug 02.** Learning stops sharing a transaction with the import, so
the check-then-act race against `UNIQUE(user_id, merchant_id, category_id)` can no longer roll back
a statement. The `REQUIRES_NEW` route stays ruled out for the reason recorded in
`MerchantLearningService.confirm`'s doc comment: it fails the foreign keys to `merchants` and
`categories` whenever those parents were created in the caller's uncommitted transaction. Moving
the work *after commit* is the fix that suspending the transaction could never be.

---

## 3. Schema changes

Next migration is **V62** (V58 is intentionally absent; V57 → V59 is a documented gap).

| Migration | Change | For |
|---|---|---|
| V62 | `merchant_learning_events` + indexes on `(status, next_attempt_at)` | Deliverable 0 |
| V63 | `merchants.lifecycle_status` (`TEMPORARY`/`UNDER_REVIEW`/`APPROVED`), default `APPROVED` for existing rows | WI4 |
| V64 | canonical merchant registry + FK from `merchants` — **only if decision 1.2 lands on (b)** | WI4 |
| V65 | `MERCHANT_REVIEW` and `LEARNING_QUEUE_MANAGE` permissions, granted to ADMIN and SUPER_ADMIN | WI2, WI4 |

V65 follows V61's precedent: a new permission needs an explicit grant, because SUPER_ADMIN's V16
"every permission" seed was a one-time snapshot, not a standing rule. Do not gate these behind
`PLATFORM_DIAGNOSTICS_VIEW` — V61 records why reusing a read-only permission for an action quietly
undoes the separation V34 established.

Backfilling existing merchants to `APPROVED` is deliberate: every merchant that exists today came
from a confirmed import, so none of them are temporary.

---

## 4. Work item designs

### WI1 — Merchant learning as an independent subsystem

Import commits → event row inserted in the same transaction → `afterCommit` nudge → worker calls
the existing `MerchantLearningService.confirm` → success marks `COMPLETED`, failure schedules a
retry.

`CategorizationService.learn` stops calling `confirm` synchronously on the import path. It keeps
doing so on the **explicit** admin confirm endpoints, where a failure is the answer to the request
and must surface as an error rather than be queued.

### WI2 — Merchant Learning Queue (Admin Portal)

New page behind `LEARNING_QUEUE_MANAGE`. Columns and filters per the brief. Every action — Retry,
Retry All, Mark Resolved — writes an audit entry through `AuditService` with the acting admin's id,
matching the `actorId` threading `RoleService` and `MerchantService` already use.

Retry All must be bounded. `TransactionDto.MAX_BULK_IDS` (500) set the precedent this session;
reuse the same ceiling rather than inventing a second number.

### WI3 — Read-only staging

Scope per decision 1.1. Once WI4 gives unknown merchants a non-persisting path, staging stops
calling `MerchantNormalizationEngine.resolve`'s write branches and Bug 36 closes as a consequence
rather than as a separate fix.

### WI4 — Temporary merchants (**and Bug 35**)

Unknown merchant during import → create with `lifecycle_status = TEMPORARY` → import continues →
admin reviews later. Import is never blocked by an unrecognised merchant.

**Bug 35 must be fixed here and the brief does not mention it.** `MerchantNormalizationEngine.resolve`
performs `merchantRepository.findByUserId(userId)` — a full per-user merchant load — inside a
per-row call, on the alias-miss path, which is the *common* case on a first import. That is the
single largest cost in staging, and WI4 rewrites exactly that method. Fixing it anywhere else means
touching this code twice. Replace the per-row scan with one load hoisted to the statement level,
or an indexed lookup on the first significant token.

### WI5 — Duplicate review

`DuplicateDetector` stops being a filter and becomes a proposal. Import stays disabled until every
flagged pair is resolved, per the brief — with the caveat that "Apply decision to similar matches"
is not optional polish: a 569-row statement (the largest this engine has parsed) with a high
duplicate rate is unusable without it.

Confidence and reason-for-match must come from the detector rather than being invented in the UI,
so the explanation shown to the user is the one the system actually used.

---

## 5. Sequencing

```
Decisions 1.1–1.4  ──────────────┐
                                 ▼
                    Deliverable 0 (queue infra, V62)
                                 │
                    ┌────────────┼────────────┐
                    ▼            ▼            ▼
                  WI1          WI2         WI4 + Bug 35 (V63, V65)
                (closes 02)  (queue UI)         │
                                                ▼
                                          WI3 (closes 36)
                                                │
                                                ▼
                                              WI5
```

WI7's small delta (decision 1.3) is independent and can land any time. WI6 needs no work.

The ordering is not preference. WI1 cannot exist without Deliverable 0; WI3 cannot be read-only
until WI4 gives unknown merchants somewhere to go; WI5 is the only item with no upstream
dependency and can run in parallel if there is capacity.

---

## 6. Testing

The brief's requirement — *"do not rely solely on mocked repository tests for transaction-boundary
behavior"* — is not generic advice here. It is the direct lesson of the hardening phase, and it
should be quoted in review:

- `BudgetServiceTest` stubbed `save()` to throw `DataIntegrityViolationException` and asserted the
  recovery ran. In production `save()` routes through `merge()` and the insert is deferred to
  commit, so it never threw, the catch never ran, and the test passed for as long as it existed.
- `BootstrapServiceTest`'s race test could not observe the `UnexpectedRollbackException` that made
  the catch it was verifying useless — no transaction exists in a Mockito test to commit.

Both asserted behaviour that could not happen. For this milestone specifically, the following need
integration tests against a real transactional context, not mocks:

1. A learning failure leaves every imported transaction persisted.
2. An import rollback leaves no orphan event row.
3. Two workers cannot claim the same event (exercise `SKIP LOCKED` with concurrent claims).
4. Retry stops at 5 and lands in `FAILED`.
5. Staging writes no merchant, alias, category or transaction row — asserted by counting rows
   before and after, not by verifying a mock was not called.
6. Every new admin endpoint 403s without its permission.

---

## 7. Explicitly out of scope, and still open

- **Bug 30** — dependencies roughly two years behind (`spring-boot-starter-parent` 3.3.2, PDFBox
  3.0.3, jjwt 0.12.5) with no CVE scan run. Not in the brief, not in this design, still open.
  PDFBox is the one to prioritise: it parses attacker-supplied files as a core product feature and
  is reachable by an authenticated low-privilege user.
- **Bug 03 (partial)** — the refresh-token cookie transport now works, but the token is still
  written to `localStorage`, so the XSS mitigation the cookie exists for is not delivered.
  Completing it means changing how the session is held in both clients.
- **Bug 18 (partial)** — granting an admin role to a USER-scope account is now blocked, but scope
  is still absent from the JWT and still unread at authorization time. The real fix is a scope
  claim checked during authorization.
- **New finding #4** — login reveals account existence for suspended accounts before
  authentication. Needs the lockout/suspension checks reordered relative to `authenticate()`.
- **New finding #5** — access tokens survive every session revocation, so a token stays valid for
  up to 15 minutes after the platform has concluded it was stolen. `JwtAuthFilter` already extracts
  the `sid` claim on every request; closing this means validating it.

These are listed here so the milestone's completion is not mistaken for the backlog being empty.
