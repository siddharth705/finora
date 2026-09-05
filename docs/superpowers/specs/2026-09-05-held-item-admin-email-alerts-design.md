# Held-Item Admin Email Alerts — Design

**Status:** Approved design, ready for implementation planning.

## 1. Objective

Today, when a statement lands in either admin triage queue — the parser-gap queue
(`ImportJob.Status.HELD_FOR_REVIEW`, `/held-imports`) or the trust-review queue
(`HeldStatement`/`ImportJob.Status.HELD_FOR_TRUST_REVIEW`, `/held-statements`) — no one is told.
An admin only finds out by opening the admin portal and checking. This was surfaced directly: a
real statement (`Paytm_Statement_January_2026.pdf`) sat un-triaged with the user assuming it had
been reviewed, because nothing notified anyone it needed attention.

This design adds an email alert, sent to every admin holding the relevant permission, the moment
either kind of hold is created — a pointer into the admin portal, not a channel for statement
content.

## 2. Current behavior (verified against this codebase, not assumed)

- `com.finora.notification` (`NotificationService`/`NotificationDispatcher`, built in Phase A of
  `2026-09-02-import-failure-triage-and-notification-platform-design.md`) exists and works, but is
  built entirely around **one end-user's own channel preferences per `NotificationCategory`**
  (`SECURITY`/`FINANCIAL`/`MARKETING`) — `NotificationRequest` takes a single `userId` and is
  resolved through `NotificationPreferenceResolver`. There is no concept of "every user holding
  permission X" and no category that fits an always-on internal ops alert. Stretching it to cover
  this would mean either inventing an `OPERATIONAL` category with no real preference semantics, or
  gating an ops alert behind a personal notification toggle that makes no sense for it — the wrong
  shape for what this needs.
- `EmailProvider` (`backend/src/main/java/com/finora/service/EmailProvider.java`) already exposes
  a generic `send(EmailMessage)` alongside its purpose-built methods (`sendWelcomeEmail`,
  `sendPasswordResetEmail`, etc.), and `AuthService`/`PasswordChangeService` already call it
  directly, bypassing `NotificationService` entirely, for exactly this kind of one-off
  transactional email. This is an established, accepted pattern in this codebase, not a new one.
- Two distinct admin permissions already exist and are already the access boundary for the two
  queues: `IMPORT_TRIAGE_MANAGE` (V135, gates `AdminHeldImportController`) and
  `TRUST_REVIEW_MANAGE` (gates `AdminHeldStatementController`), both granted to the `ADMIN` and
  `SUPER_ADMIN` roles today. Nothing in the codebase currently queries "which users hold permission
  X" — `UserRepository` has no such method yet.
- `AfterCommit` (`backend/src/main/java/com/finora/util/AfterCommit.java`) is the established
  utility for "run this once the transaction that created the thing being announced is durable,
  without holding a pooled connection across the network call, and without ever throwing back to
  the caller." `ImportJobWorker.notifyIfPreviouslyHeld` and `HeldStatementService.
  notifyStatementReady` are the two existing call sites that notify a *user*, both fired from
  inside the transaction that changes the job's state, using this same discipline.
- `HeldStatementService.openHold` (creates the trust-review hold) and `ImportJobWorker`'s
  `job.holdForReview(failureCode, now)` call site (creates the parser-gap hold) are the two points
  in the codebase where a hold is actually created. Neither currently notifies anyone.
- The admin portal has a per-item detail route for trust-review (`/held-statements/:heldId`,
  `HeldStatementDetail.tsx`) but **not** for parser-gap holds — `/held-imports`
  (`HeldImports.tsx`) is list-only today, no `/held-imports/:jobId` detail route exists.

## 3. Scope decisions (from brainstorming)

- **Recipients: permission-based, not a fixed mailbox.** Every admin holding the relevant
  permission for that queue gets the email — resolved live from the RBAC graph each time, not a
  configured address. Scales as the admin team changes; reuses the access boundary V135 already
  drew instead of duplicating it.
- **Both queues, one mechanism, two trigger points.** Trust-review and parser-gap holds are
  structurally the same problem (a job lands in a queue nobody's watching) with two different
  permissions and two different trigger sites in the code.
- **Immediate per-item email, no digest.** Real corpus testing found the trust-review predicate
  essentially never fires on real documents, and parser-gap holds are one outcome among several —
  expected volume is low (pre-launch: a handful a week at most). A digest adds a scheduler, a
  batching window, and a second thing that can be wrong, for no real benefit at this volume.
  Revisit if volume grows enough to be noisy.
- **Metadata + link only, no statement content in the email body.** `IMPORT_TRIAGE_MANAGE`'s own
  migration comment already treats access to raw statement content as sensitive enough to audit
  every view. Email is a lower-security channel than an authenticated in-app view (forwarding,
  inbox compromise, unencrypted hops), so it functions purely as a pointer: filename, detected
  bank (if any), hold/job ID, failure or trigger-reason category, timestamp, and a deep link into
  the admin portal. Never the recovered text, transaction rows, account numbers, or any other
  customer PII.

## 4. Architecture

One new, small, purpose-built service — not an extension of `NotificationService` — following the
`EmailProvider`-direct pattern `AuthService` already uses. If no email provider is configured
(`EmailProvider.isConfigured()` false), sending no-ops silently, the same degrade-gracefully
posture every other optional integration in this app already has in dev.

### 4.1 Recipient resolution

New `UserRepository` query resolving admins by permission name, joined through the existing
`user_roles` → `roles` → `role_permissions` → `permissions` graph (the same graph
`AuthorizationService` already reads on every authenticated request, per `Role`'s own class doc —
this reuses that shape rather than inventing a second one):

```java
List<User> findByPermissionNameAndAccountScope(String permissionName, String accountScope);
```

Scoped to `User.SCOPE_ADMIN` — an admin-portal account is what holds these permissions at all, so
this is a correctness constraint carried over from how the permission system already works, not an
extra filter bolted on.

### 4.2 The alert service

`HeldItemAdminAlertService` (new), with two methods:

```java
void alertParserGapHeld(ImportJob job);
void alertTrustReviewHeld(HeldStatement held, ImportJob job);
```

Each:
1. Resolves recipients via §4.1, for the respective permission.
2. Builds one `EmailMessage` per recipient (`EmailMessage.to` is a single string — no
   multi-recipient send exists today, so this is N independent `EmailProvider.send(...)` calls,
   matching how every other multi-recipient-shaped need in this codebase would have to work
   given `EmailMessage`'s current shape).
3. Sends via a new purpose-built `EmailProvider` method, e.g.
   `sendHeldItemAlertEmail(String toEmail, HeldItemAlertContent content)`, following the existing
   convention of a named method per well-known email type rather than callers building
   `EmailMessage` by hand.
4. Logs a warning and continues to the next recipient on any individual send failure — one bounced
   or misconfigured admin address must never block another admin's copy, and must never propagate
   back to the caller (the import pipeline's success can never depend on email deliverability).

No recipients (permission granted to nobody, e.g. a misconfigured deployment) is not an error —
logs once at INFO and returns, same "degrades, doesn't fail" posture as everything else here.

### 4.3 Trigger points

- **Parser-gap:** in `ImportJobWorker`, at the existing `job.holdForReview(failureCode,
  Instant.now())` call site (`recordFailure`, inside `jobStore.update`'s lambda). Call
  `AfterCommit.run("held-item admin alert", () -> alertService.alertParserGapHeld(job))`
  immediately after, mirroring exactly how `notifyIfPreviouslyHeld` is already wired nearby in the
  same method — same transaction-commit boundary, same non-connection-holding discipline.
- **Trust-review:** in `HeldStatementService.openHold`, after the `HeldStatement` row (and its
  first audit event) are built, wrapped the same way. `openHold` already runs in its own
  `REQUIRES_NEW` transaction (per `createHold`'s own class doc), so `AfterCommit` here fires once
  *that* transaction — not the worker's outer one — is durable, which is the correct boundary:
  the hold row must actually exist before anyone is told to go look at it.

### 4.4 Idempotency

Both trigger points fire once per hold **occurrence**, not once per job. A parser-gap job can be
reprocessed (`AdminHeldImportController`'s reprocess action resets it to `QUEUED`) and, if the
underlying bug isn't actually fixed, land back in `HELD_FOR_REVIEW` a second time — that second
occurrence is a genuinely new, actionable event ("the fix didn't work") and must send its own
email. Trust-review holds have no such re-entry path (`releaseAfterTrustReview`/
`rejectAfterTrustReview` both move to a terminal `ImportJob.Status`), so this only matters for the
parser-gap side in practice, but the mechanism should not assume that invariant holds forever.

Concretely: the email itself has no delivery-idempotency requirement the way `NotificationService`
does (this bypasses that system's outbox entirely — see §4.2, "must never propagate"). What matters
is that the trigger fires exactly once per hold occurrence, which `AfterCommit` already guarantees
by construction (it runs once, when its specific transaction commits) — no additional dedup key is
needed at this layer. If a future caller needs replay-safety here too, that's a reason to route
through an outbox-backed mechanism, not a reason to add ad-hoc key tracking to this one.

### 4.5 Email content

Subject: `Statement held for review — {fileName}` (or the trust-review equivalent — exact copy is
implementation-time, matching how the original design left final wording to implementation).

Body: filename, detected bank (if available — `null`-safe, not every held item has one), hold ID
(`HeldStatement.heldId` for trust-review; `ImportJob.id` for parser-gap, since no distinct held-id
exists on that side), a one-line reason (`HoldDecision.summary()` for trust-review;
`ExtractionCheck`'s curated message for parser-gap — both are already user-safe, non-PII strings,
same text already shown in the admin queue UI), timestamp, and a link:
- Trust-review: `{ADMIN_APP_BASE_URL}/held-statements/{heldId}` — the detail route already exists.
- Parser-gap: `{ADMIN_APP_BASE_URL}/held-imports` — the list page, since no per-item detail route
  exists yet (§2). Noted as a follow-up improvement, not a blocker for this scope.

## 5. Data model changes

None. No new tables, no new columns. This rides entirely on the existing `permissions`/
`role_permissions`/`user_roles`/`roles`/`users` graph and the existing `ImportJob`/`HeldStatement`
rows — the new repository query is a read against tables that already exist.

## 6. Out of scope

- Digest/batching (see §3 — revisit if volume grows).
- Any change to `NotificationService`, `NotificationCategory`, or the notification platform's
  tables — this deliberately does not touch that system at all.
- A per-item detail route for parser-gap holds (`/held-imports/:jobId`) — the email links to the
  list page for that queue until that route exists.
- SMS or push channels for this alert — email only, matching how this was scoped in
  brainstorming (an ops alert to a small admin team, not a consumer notification needing
  multi-channel reach).
- Any admin-side preference/opt-out UI for these alerts — every admin holding the permission gets
  every alert for that queue; no per-admin muting in this scope.
- Changing what counts as a "genuinely unclassified" parser-gap hold — that boundary was already
  moved once today (see §7) and is not being revisited again here.

## 7. Relationship to the 2026-09-02 design, and a scope note

`2026-09-02-import-failure-triage-and-notification-platform-design.md` §6 originally scoped
`HELD_FOR_REVIEW` to *only* genuinely unclassified (`RetryPolicy.RETRY_ONCE_THEN_ALERT`) failures,
explicitly excluding known `ErrorCode` (`FAIL_FAST`) failures like `IMPORT_NO_HEADER_DETECTED`.
That boundary was deliberately widened earlier in this same session (already merged, PR #990): a
curated `FAIL_FAST` failure that carries `recoveredLines > 0` evidence (real proof a transaction
table existed, even though the parser couldn't locate it — confirmed against a real Paytm
statement) now also enters `HELD_FOR_REVIEW`, via `ImportJobWorker.carriesRecoveredEvidence`. This
design's parser-gap trigger point fires on *any* transition into `HELD_FOR_REVIEW` regardless of
which of the two paths produced it — the admin alert doesn't need to know or care which
classification put the job there, only that it's there.

The earlier design's own §4.5 scoped notifying the *user* once a held job completes; it explicitly
did not scope notifying an *admin* when one is first created. This design fills exactly that gap,
for both hold types.

## 8. Testing

- Unit test on the new `UserRepository` query: a user with the permission (via role membership) is
  returned; a user without it is not; scope filtering excludes a non-admin account that
  coincidentally shares a role name.
- Unit tests on `HeldItemAdminAlertService`: given N resolved recipients, N `EmailProvider.send`
  calls are made with the expected content; zero recipients sends nothing and doesn't throw; one
  recipient's send throwing doesn't stop the others; an unconfigured `EmailProvider` results in no
  send attempts.
- Unit tests on both trigger points (mirroring `ImportJobWorkerTest`'s existing style for
  `notifyIfPreviouslyHeld`): a fresh parser-gap hold triggers the alert; a reprocess-then-held-again
  triggers a second alert; a release/reject (terminal, no re-entry) triggers no further alert; a
  fresh trust-review hold triggers the alert exactly once even if `createHold` is called twice for
  the same job id (idempotent per its own doc comment).
- No new integration test infrastructure needed — this follows existing patterns
  (`AfterCommit`, `EmailProvider` mocking) already exercised elsewhere in the suite.
