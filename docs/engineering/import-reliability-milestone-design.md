# Import Reliability Milestone — Implementation Blueprint

**Status:** Approved. All open decisions are resolved (section 1) and this document is the
blueprint the team builds from. Nothing is built yet. Where this conflicts with an earlier
proposal, this wins.

**Source:** the *Implementation Brief – Remaining Architecture & Product Decisions*, following the
bug-hardening phase merged in `31d3f91` (33 of 38 reported findings plus 4 newly found ones), with
two product decisions resolved on review: **no global merchant registry in this milestone**, and
**password reset and password change get different session policies**.

**Relationship to other docs.** [import-flow.md](import-flow.md) documents the pipeline as it is
today — stage, review, confirm — and this milestone changes that pipeline, so the two will disagree
until this ships; import-flow.md stays authoritative until then and must be updated as the last
step of WI5. [financial-document-intelligence-principles.md](financial-document-intelligence-principles.md)
governs how the engine reads a document and is unaffected: this milestone is about what the
surrounding system may *write*. [import-engine-improvement-proposal.md](import-engine-improvement-proposal.md)
is superseded where the two overlap.

---

## The rule this milestone enforces

> An import that parsed correctly must complete, and must persist exactly what the user approved —
> regardless of what any auxiliary system (learning, normalization, analytics, recurring detection)
> does or fails to do.

Every work item is a consequence of that sentence. It also settles the arguments: where a design
choice trades import reliability for learning quality, reliability wins; where it trades a user's
explicit decision for an automatic one, the user wins.

---

## 1. Resolved decisions

### 1.1 Analysis isolation — two rules, not one

`AdminAnalysisService` keeps its **rollback** approach. User-facing **staging becomes genuinely
non-writing** via WI4.

These are different problems that were wearing the same words. Admin analysis is a diagnostic whose
entire value is that it executes byte-identical code to a customer import; a dry-run flag threaded
through `normalize` → `suggest` → `resolve` would make it measure something other than the real
thing, and would put a diagnostic's concern in the import hot path. User staging is a preview the
user may abandon, and the objection there was never "it wrote and rolled back" — it was Bug 36,
"it wrote and *kept*." WI4 removes staging's need to write at all, which resolves that without a
flag anywhere.

Practical consequence: do not "fix" `AdminAnalysisService` to match WI3. Its class doc explains the
choice; leave it.

### 1.2 No global merchant registry in this milestone

**Decided: merchants stay per-user.** No canonical registry, no cross-user merchant identity, no
backfill. Cross-user merchant intelligence is deferred to a future *Merchant Intelligence Platform*
milestone.

The reasoning is scope discipline: a registry changes a core domain model, requires a data
migration, and touches nearly every merchant lookup in the product. That is its own milestone, not
a sub-task of this one.

What this changes about WI4, concretely:

| Brief specified | Becomes |
|---|---|
| Suggested Canonical Merchant | Suggested existing merchant **belonging to the same user** |
| Number of Users | **Dropped** — meaningless without a registry |
| Bulk Merge (platform-wide) | Bulk merge **within one user's** merchant set |
| Merchant Review Center | Per-user review; see below for how an admin still finds work |

**The one thing that survives cross-user, and why it is safe:** the review *queue* may still list
temporary merchants across all users, so an admin has a single place to see outstanding work. That
is a read-only aggregate over per-user rows, which this codebase already does twice —
`MerchantRepository.platformMerchantCounts()` and `searchDistinctCanonicalNames()`, both documented
as aggregates precisely because no shared table exists. **Listing is cross-user; every action is
scoped to the owning user.** Approve, Merge, Rename and Delete operate strictly within one user's
merchants, and merge candidates are drawn only from that user's set.

### 1.3 Password session policy — different journeys, different rules

| Journey | Policy |
|---|---|
| **Reset** (forgot password) | Revoke every session and refresh token, including the caller's. Treated as potential compromise. |
| **Change** (Settings, authenticated) | Keep the current device signed in. Revoke all others. Preserve the calling refresh token. `signOutOtherDevices` continues to work as it does. |

Reset already behaves this way as of `f3f1c31`. Change already behaves this way in
`PasswordChangeService.complete`. **Net implementation work for WI7: none.** Both halves are
correct today; what was needed was the decision that they are allowed to differ.

Record it in the code, though — a future reader comparing the two paths will otherwise read the
asymmetry as an oversight and "fix" it. One comment on each path citing this decision.

### 1.4 Deliverable 0 is a database-backed queue, not `@Async`

Confirmed. Designed in section 2.

### 1.5 Bug 34 is resolved by product policy

WI6's password-history policy (remember 5, block reuse, both paths) is **already implemented** —
`PasswordHistoryService.HISTORY_LIMIT = 5`, enforced from `AuthService.resetPassword` and
`PasswordChangeService.complete`. No work.

Its real content is a decision: Bug 34 (a reset performs up to seven bcrypt(12) hashes, roughly
1.5–2s of CPU, behind a 10-per-10-minutes-per-IP limiter) was deferred because reducing it means
weakening this policy. The policy stands, so **Bug 34 is closed as resolved-by-policy, not
deferred.** Recording it that way is what stops it being re-opened at every audit.

---

## 2. Deliverable 0 — the asynchronous foundation

WI1 and WI2 rest entirely on this.

### 2.1 The constraint that decides the design

Railway can run more than one instance. Any design where a poller assumes it is alone will
double-process: two workers claim the same failed learning event, both apply it, and the merchant's
confirmation count increments twice. Confirmation counts drive `ConfidenceEngine.topCategory`,
which decides the auto-applied category — so double-processing **corrupts the learning
distribution**, not merely wastes work. In-memory `@Async` additionally loses the queue on every
restart, and a deploy is a restart.

### 2.2 The queue

```
merchant_learning_events
  id                          UUID PK
  user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE
  merchant_id                 UUID NOT NULL REFERENCES merchants(id) ON DELETE CASCADE
  category_id                 UUID NOT NULL REFERENCES categories(id) ON DELETE CASCADE
  source_statement_import_id  UUID REFERENCES statement_imports(id) ON DELETE SET NULL
  status                      VARCHAR(16) NOT NULL   -- PENDING | PROCESSING | COMPLETED | FAILED
  attempt_count               INT NOT NULL DEFAULT 0
  next_attempt_at             TIMESTAMPTZ NOT NULL DEFAULT now()
  last_error                  TEXT
  created_at, updated_at, first_failed_at, last_retry_at
```

`ON DELETE CASCADE` on merchant/category is deliberate: if the merchant the event refers to is
deleted, the event is meaningless. `SET NULL` on the statement import keeps the event processable
after a statement is removed.

Claiming:

```sql
SELECT * FROM merchant_learning_events
 WHERE status = 'PENDING' AND next_attempt_at <= now()
 ORDER BY next_attempt_at
 FOR UPDATE SKIP LOCKED
 LIMIT :batch
```

`FOR UPDATE SKIP LOCKED` is what makes multiple instances safe: a row claimed by one worker is
invisible to the others rather than contended. This is the same reasoning already applied by
`ImportSessionRepository.claimForConfirmation`, whose atomic conditional `UPDATE` stops two
concurrent confirms importing one statement twice. Reuse that pattern's discipline rather than
inventing a second one.

Backoff: `next_attempt_at = now() + 2^attempt_count minutes`, capped at 5 attempts — 1, 2, 4, 8,
16 minutes. After the fifth, `status = FAILED` and the row surfaces in WI2.

Index: `(status, next_attempt_at)` — the claim query's exact predicate.

### 2.3 Publishing without reintroducing the bug this milestone exists to fix

The event row is inserted **in the import's own transaction**. The worker runs **after that
transaction commits**.

Both halves matter. Writing the row outside the transaction means an import that rolls back still
queues learning for transactions that do not exist. Processing it inside means a learning failure
can once again take the import down — which is Bug 02 by another route.

Trigger the worker with `TransactionSynchronizationManager.registerSynchronization(...)`
`afterCommit`, the same shape `SetupService.completeSetup` now uses to defer its irreversible file
deletion. The row is transactional; only the *nudge* is deferred. If the nudge is lost — a crash
between commit and notify — the poller picks the row up on its next pass. That is why the poller
exists even though the nudge usually beats it.

**This is what closes Bug 02.** Learning stops sharing a transaction with the import, so the
check-then-act race against `UNIQUE(user_id, merchant_id, category_id)` can no longer roll back a
statement. `REQUIRES_NEW` stays ruled out for the reason recorded in
`MerchantLearningService.confirm`'s doc comment: it fails the foreign keys to `merchants` and
`categories` whenever those parents were created in the caller's uncommitted transaction. Moving
the work *after commit* is the fix that suspending the transaction could never be.

---

## 3. Migration plan

**Verified free.** `main` is at V61. The two other live branches (`feat/landing-v4`,
`feat/mobile-phase-5`) are both 14 commits behind `main` and **add no migrations**, so V62 onward
carries no collision risk. The V58 gap is pre-existing and intentional (V57 → V59).

| Migration | Change | For |
|---|---|---|
| **V62** | `merchant_learning_events` + index on `(status, next_attempt_at)` | Deliverable 0 |
| **V63** | `merchants.lifecycle_status VARCHAR(16) NOT NULL DEFAULT 'APPROVED'` + index on `(user_id, lifecycle_status)` | WI4 |
| **V64** | `MERCHANT_REVIEW` and `LEARNING_QUEUE_MANAGE` permissions, granted to ADMIN and SUPER_ADMIN | WI2, WI4 |

The registry migration is **removed** — decision 1.2. Three migrations, not four.

V64 follows V61's precedent: a new permission needs an explicit grant, because SUPER_ADMIN's V16
"every permission" seed was a one-time snapshot, not a standing rule. Do not gate these behind
`PLATFORM_DIAGNOSTICS_VIEW` — V61 records why reusing a read-only permission for an action quietly
undoes the separation V34 established.

Backfilling existing merchants to `APPROVED` is deliberate: every merchant that exists today came
from a confirmed import, so none are temporary.

**Note for V63:** `merchants` has *no* unique constraint on `(user_id, canonical_name)` — only
`idx_merchants_user` on `user_id`. Do not add one. Duplicate canonical names within a user are
exactly the input the review workflow exists to resolve; a unique constraint would make the import
fail instead of queueing the merchant for review, which inverts this milestone's core rule.

---

## 4. Work item designs

### WI1 — Merchant learning as an independent subsystem

Import commits → event row inserted in the same transaction → `afterCommit` nudge → worker calls
the existing `MerchantLearningService.confirm` → success marks `COMPLETED`, failure schedules a
retry.

`CategorizationService.learn` stops calling `confirm` synchronously on the import path. It keeps
doing so on the **explicit** admin confirm endpoints, where a failure is the answer to the request
and must surface as an error rather than be silently queued.

### WI2 — Merchant Learning Queue (Admin Portal)

New page behind `LEARNING_QUEUE_MANAGE`. Columns, filters and actions per the brief. Every action —
Retry, Retry All, Mark Resolved — writes an audit entry through `AuditService` carrying the acting
admin's id, matching the `actorId` threading `RoleService` and `MerchantService` already use.

Retry All must be bounded. `TransactionDto.MAX_BULK_IDS` (500) set that ceiling this session; reuse
it rather than inventing a second number.

### WI3 — Read-only staging

Scope per decision 1.1: staging only. Once WI4 gives unknown merchants a non-persisting path,
staging stops reaching `MerchantNormalizationEngine.resolve`'s write branches, and **Bug 36 closes
as a consequence** rather than as a separate fix.

### WI4 — Temporary merchants, per-user (**and Bug 35**)

Unknown merchant during import → create with `lifecycle_status = TEMPORARY` → import continues →
admin reviews later. An unrecognised merchant never blocks an import.

Review Center per decision 1.2: **cross-user listing, user-scoped actions.**

**Delete is the dangerous action and must not be implemented naively.** Four foreign keys point at
`merchants`: `merchant_aliases`, `merchant_category_learning` and `merchant_learning_audit` all
`ON DELETE CASCADE`, and `transactions.merchant_id` is `ON DELETE SET NULL`. A raw delete therefore
destroys the merchant's learning distribution *and its audit history*, and silently orphans the
transaction pointers. `MerchantService.merge()` already repoints aliases, transactions, learning
rows and audit history before deleting — **route Delete and Merge through that same path.** A
temporary merchant with transactions attached should not be deletable at all; only merged.

**Bug 35 is in scope here.** `MerchantNormalizationEngine.resolve` performs
`merchantRepository.findByUserId(userId)` — a full per-user merchant load — inside a per-row call,
on the alias-miss path, which is the *common* case on a first import. It is the largest cost in
staging, and WI4 rewrites exactly that method. Fixing it separately means touching this code twice.
Replace the per-row scan with one load hoisted to statement level, or an indexed lookup on the
first significant token.

### WI5 — Duplicate review

`DuplicateDetector` stops being a filter and becomes a proposal. Import stays disabled until every
flagged pair is resolved, per the brief — with the caveat that *"apply decision to similar
matches"* is not optional polish. A 569-row statement (the largest this engine has parsed) with a
high duplicate rate is unusable without it.

Confidence and reason-for-match must come from the detector rather than being composed in the UI,
so the explanation shown to the user is the one the system actually used.

Update [import-flow.md](import-flow.md) as the closing step.

---

## 5. Sequencing

```
              Deliverable 0 (queue infra, V62)
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
            WI1          WI2      WI4 + Bug 35 (V63, V64)
         (closes 02)  (queue UI)        │
                                        ▼
                                  WI3 (closes 36)
                                        │
                                        ▼
                                      WI5
```

WI6 and WI7 need no code — only the comments recording decisions 1.3 and 1.5.

This ordering is structural, not preference: WI1 cannot exist without Deliverable 0; WI3 cannot be
read-only until WI4 gives unknown merchants somewhere to go. WI5 has no upstream dependency and can
run in parallel given capacity.

---

## 6. Testing

The brief's requirement — *"do not rely solely on mocked repository tests for transaction-boundary
behavior"* — is the direct lesson of the hardening phase and should be quoted in review:

- `BudgetServiceTest` stubbed `save()` to throw `DataIntegrityViolationException` and asserted the
  recovery ran. In production `save()` routes through `merge()` and the insert defers to commit, so
  it never threw, the catch never ran, and the test passed for as long as it existed.
- `BootstrapServiceTest`'s race test could not observe the `UnexpectedRollbackException` that made
  the catch it verified useless — no transaction exists in a Mockito test to commit.

Both asserted behaviour that could not happen. For this milestone, these need integration tests
against a real transactional context:

1. A learning failure leaves every imported transaction persisted.
2. An import rollback leaves no orphan event row.
3. Two workers cannot claim the same event — exercise `SKIP LOCKED` with concurrent claims.
4. Retry stops at 5 and lands in `FAILED`.
5. Staging writes no merchant, alias, category or transaction row — asserted by **counting rows
   before and after**, not by verifying a mock was not called.
6. Deleting or merging a temporary merchant preserves its audit history and repoints its
   transactions.
7. Every new admin endpoint 403s without its permission.

---

## 7. Out of scope, and still open

Tracked deliberately so this milestone's completion is not mistaken for an empty backlog.

**Separate maintenance initiative:**

- **Bug 30** — dependencies roughly two years behind (`spring-boot-starter-parent` 3.3.2, PDFBox
  3.0.3, jjwt 0.12.5); no CVE scan run. PDFBox is the one to prioritise: it parses attacker-supplied
  files as a core product feature, reachable by an authenticated low-privilege user.

**Follow-up work after this milestone:**

- **Bug 03 (partial)** — the refresh-token cookie transport works, but the token is still written to
  `localStorage`, so the XSS mitigation the cookie exists for is not delivered. Completing it means
  changing how the session is held in both clients.
- **Bug 18 (partial)** — granting an admin role to a USER-scope account is blocked, but scope is
  still absent from the JWT and unread at authorization time. The real fix is a scope claim checked
  during authorization.
- **New finding #4** — login reveals account existence for suspended accounts before authentication.
  Needs the lockout/suspension checks reordered relative to `authenticate()`.
- **New finding #5** — access tokens survive every session revocation, so a token stays valid up to
  15 minutes after the platform has concluded it was stolen. `JwtAuthFilter` already extracts the
  `sid` claim on every request; closing this means validating it.

**Deferred by product decision:**

- **Cross-user merchant intelligence** — the canonical registry, cross-user suggestions and
  platform-wide merge, per decision 1.2. Its own milestone.
