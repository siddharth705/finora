# Support, Help & Feedback — Design Proposal

**Status:** Proposal only. **Nothing here is implemented.** Design now, build later — scheduled to
start after C-8 Track B, the 56 open bug-hunt findings, and the pre-launch production-safety
remediation (backup/recovery, Sentry monitoring) are all closed. See the decision log entry dated
2026-08-11 in project memory for why this is sequenced after those, not alongside them.

**Revised 2026-08-29 following design review.** Changes: added a ticket `subject`; moved attachments
to a child table; added internal admin notes; rewrote §3.5 after verifying its premise was stale;
made deletion, export and enum-storage policy explicit; and corrected the package layout, migration
number and ticket-prefix errors. Section numbering is unchanged. Every repo claim below was
re-verified against `origin/main` on the revision date rather than carried forward.

**Relationship to the parked Support Platform / Fino vision:** a separate, much larger proposal
(workflow engine, SLA policies, knowledge base, event-driven Fino copilot, customer-360, support
analytics) was already evaluated and explicitly parked as V2/V3 discovery — see
`docs/roadmap/fino-v2-readiness.md`. This document is deliberately **not** that. It is the small,
evidence-first slice that proposal itself recommended starting with: plain ticket + feedback CRUD,
sized to current usage, with no dependency on Fino or any workflow/SLA infrastructure. If real usage
later justifies more, that's a separate, future proposal grounded in actual data from this one.

---

## 1. Objective

Give users a real in-product way to get help, report problems, and leave feedback — replacing the
current all-static Help page and mailto-only Contact page — without introducing the operational
surface (workflow engine, SLA tracking, AI triage) that a support volume of zero doesn't justify yet.

## 2. What exists today (baseline)

- `frontend/src/pages/Help.tsx` — static FAQ page, no backend.
- `frontend/src/pages/Contact.tsx` + `frontend/src/lib/contact.ts` — a hardcoded `SUPPORT_EMAIL`,
  mailto only. No persistence, no backend endpoint, no admin visibility.
- No ticket, feedback, or case domain exists anywhere in the backend.
- Admin portal has no support-related screens.

## 3. Proposed scope (v1 — the only thing being designed here)

### 3.1 Help Center (mostly content, minimal engineering)

Expand the existing static `Help.tsx` with the FAQ content already listed in the team's request
(statement import, categorization, budgets, balances/reports, troubleshooting). No backend change
needed — this stays static content, same as today, just fuller. Lowest-cost item in this proposal.

### 3.2 Support Tickets (new — small domain)

Backend module `com.finora.support` (mirrors the existing flat-package convention, e.g.
`com.finora.budgets`: `SupportController`, `SupportTicketDto`, `SupportTicketService`,
`package-info.java`).

**Correction — the convention is narrower than it looks.** `com.finora.budgets` holds only the
controller, DTO and service. `BudgetRepository` lives in `com.finora.repository` and the entity in
`com.finora.entity`. Support must follow the same split: `SupportTicketRepository` in
`com.finora.repository`, entities in `com.finora.entity` — **not** inside `com.finora.support`.

```
SupportTicket
├── id (UUID)
├── ticketNumber   — human-facing sequential id, e.g. SUP-000001 — see below
├── userId
├── category      — enum: STATEMENT_IMPORT, CATEGORIZATION, ACCOUNT_LINKING,
│                    DATA_ACCURACY, TECHNICAL_ISSUE, OTHER
├── subject        — short free-text summary, length-capped (e.g. 120 chars)
├── description    — free text
├── status         — enum: OPEN, IN_PROGRESS, RESOLVED, CLOSED
├── createdAt / updatedAt
```

**Subject.** A short summary field distinct from `description`. Without it, every admin list view
in §3.4 ends up truncating description prose, search has nothing concise to match on, and the
notification copy in §3.8 has no title to render. Low cost, and the alternative is discovering the
need after the admin screen is already built.

**Ticket number.** `id` stays the real primary key (UUID, matches every other entity in the
codebase), but a user should never have to read or quote a UUID back to support. Add a
`ticketNumber` — a short, sequential, human-facing identifier (`SUP-000001`) generated at creation.
Simplest implementation: a Postgres sequence formatted at insert time, not a second lookup key.

No priority/severity, no SLA timer, no assignment routing in v1 — a ticket is created, an admin
updates its status, done.

**Attachments — a child table, not a column.** This is not speculative future-proofing. The API
surface below already specifies `/attachments/{attachmentId}`, an attachment identity that a single
`attachmentUrl` column on `SupportTicket` cannot supply — the original draft's schema and its own API
contradicted each other. A child table resolves that, and makes the eventual single→multiple change a
UI and validation change rather than a backfill migration against a live table. The v1 UI still
accepts exactly one file; only the storage shape is plural.

```
SupportTicketAttachment
├── id (UUID)       — this is the {attachmentId} in the download URL
├── ticketId
├── filename
├── contentType
├── sizeBytes
├── content         — bytes; see §3.5 for why they live here and not in object storage
├── createdAt
```

Note the field is `content`, not `attachmentUrl`: §3.5 stores bytes, so there is no URL to hold. The
draft's original name described a storage model it did not propose.

**Internal admin notes (in v1).** Admins need somewhere in-product to record operational context —
"reproduced on Android 1.3.7", "waiting on the next deploy", "linked to bug #452". Without it, that
context reliably migrates to Slack or email, which is the same failure mode this section already
flags for the conversation thread. This is the one deliberate scope *addition* over the original
minimal design, accepted because it is the smallest feature with the largest operational payoff and
because retrofitting author attribution onto notes later is worse than building it now.

```
SupportTicketInternalNote
├── id (UUID)
├── ticketId
├── adminId         — actor attribution; the portal is multi-admin (V52's
│                     account_scope IN ('USER','ADMIN')), so an unattributed note is not useful
├── note            — free text
├── createdAt       — append-only; notes are never edited or deleted in v1
```

Deliberately a separate table — **not** a single mutable `adminNote` column on the ticket, and
**not** an `ADMIN_INTERNAL` variant of the future `SupportTicketMessage`:

- A single column carries no author and silently overwrites the previous note. In a multi-admin
  portal that loses exactly the information the field exists to capture.
- Folding internal notes into the customer-visible message table is the Zendesk public/internal
  comment shape, whose well-known failure mode is one missing filter rendering an admin's internal
  notes inside the user's own ticket view. A separate table cannot fail that way: there is no query
  path from any user-facing endpoint to it at all. Given §3.6's posture on financial-data access,
  that structural guarantee is worth one extra table.

**Reserved evolution path — not built now:** a conversation thread is the first thing to add later
if evidence shows single-shot tickets aren't enough (e.g. an admin needs to ask a clarifying
question, or confirm a fix landed). Recording the shape here so the schema doesn't have to be
reworked when that evidence arrives:

```
SupportTicketMessage (future — not v1)
├── id
├── ticketId
├── senderType     — USER, ADMIN
├── message
├── createdAt
```

If this is skipped, the likely failure mode is support conversation drifting outside Finora
entirely (back to email), which defeats the point of building tickets in the first place — worth
watching for once real volume exists, not worth building speculatively now.

**API surface:**
- `POST /api/v1/support/tickets` — create (user)
- `GET /api/v1/support/tickets` — list own tickets (user)
- `GET /api/v1/support/tickets/{id}` — view own ticket (user)
- `GET /api/v1/support/tickets/{id}/attachments/{attachmentId}` — authenticated download, not a
  public URL (see §3.6 Security)
- `GET /api/v1/admin/support/tickets` — list all, filterable by status/category (admin)
- `PATCH /api/v1/admin/support/tickets/{id}` — update status (admin)
- `GET /api/v1/admin/support/tickets/{id}/notes` — list internal notes (admin only)
- `POST /api/v1/admin/support/tickets/{id}/notes` — add an internal note (admin only)

The two note endpoints exist **only** under `/api/v1/admin/`. No user-facing endpoint reads, joins
to, or serialises `SupportTicketInternalNote` under any circumstance.

**Frontend:** replace `Contact.tsx`'s mailto form with a real ticket-creation form (category select
+ subject + description + optional attachment), plus a "My Tickets" list/detail view. Admin portal gets a
Support Tickets list/filter/detail screen mirroring existing admin list patterns already in the
codebase (e.g. the Merchant Review Center).

### 3.3 Feedback (new — separate, simpler domain)

Deliberately **not** the same table as tickets — feedback doesn't need status tracking or admin
action per row, only aggregation.

```
FeedbackEntry
├── id (UUID)
├── userId          — nullable if feedback is ever allowed pre-auth; TBD at implementation
├── type            — enum: BUG, FEATURE_REQUEST, IMPROVEMENT, GENERAL
├── context          — which page/feature it came from (dashboard, transactions, reports,
│                       import-flow, mobile/web) — a bounded enum, not free text, so counts are
│                       meaningful without text-mining
├── source           — enum: WEB, MOBILE_ANDROID, MOBILE_IOS, ADMIN_CREATED — separate from
│                       `context` (context is *which feature*, source is *which client*); answers
│                       "are mobile users hitting more issues than web" without cross-referencing
│                       user-agent strings after the fact
├── message          — free text
├── createdAt
```

**Storing `context` — enum in Java, no CHECK constraint in Postgres.** Persist enum-backed columns
as plain `varchar` validated by the Java enum (`@Enumerated(EnumType.STRING)`, the convention
throughout `com.finora.entity`), and deliberately **without** a database `CHECK` constraint on
`context` specifically.

The reason is concrete, not theoretical. `context` is the one field that gains a value every time a
feature ships, and CHECK-constrained enum columns in this repo have already proven expensive:
`V95__user_sign_in_method.sql` added `CHECK (sign_in_method IN ('PASSWORD','GOOGLE'))`, and
`V96__user_sign_in_method_apple.sql` exists for no other purpose than dropping and recreating that
constraint to admit `'APPLE'`. Only 11 of the repo's migrations use CHECK constraints at all, so a
constraint-free enum column is already the majority pattern here.

Keeping the Java enum preserves validation at the API boundary and keeps counts aggregatable;
dropping the DB constraint makes adding a value a one-constant change with no migration. A free-text
`featureKey` with a frontend-maintained list was considered and rejected: it trades the migration for
typo drift, which destroys the aggregation that is this field's entire stated purpose.

**API surface:**
- `POST /api/v1/feedback` — submit (user)
- `GET /api/v1/admin/feedback` — list, filterable by type/context (admin)

**Frontend:** a small reusable feedback widget component ("Was this helpful?" / "Report an issue" /
"Suggest improvement"), embedded on the pages listed in the team's request. One component, several
mount points — not a bespoke form per page.

### 3.4 Admin visibility (v1 scope only)

Ticket lists show `ticketNumber` and `subject` as the identifying columns — this is what §3.2's
`subject` field exists to serve. The ticket detail screen carries the internal-notes panel (read +
append, §3.2); notes appear nowhere in the user-facing app.

A list + filter + basic counts view for both tickets and feedback (status breakdown, category/type
breakdown, count over time). Explicitly **not** in v1: resolution-time analytics, trend detection,
clustering, or anything described as "product intelligence" in the earlier vision doc — those need
real volume to be meaningful, and Finora has none yet.

### 3.5 Attachments

**The original draft's premise here was wrong and has been rewritten.** It claimed statement files
"currently live as Postgres `BYTEA` (R2 migration proposed, not built)" and reasoned that tickets
should match. That is no longer true: `com.finora.imports.storage` contains a complete, built object
storage layer — `StatementStorage` (the S3-shaped interface), `R2StatementStorage`,
`FilesystemStatementStorage`, `ContentAddress`, `GzipCompression`, `StatementIntegrityException` and
`StatementStorageSweepService` — selected by `app.statement-storage.provider`, with `V54` adding
`content_hash`/`object_key` and `V76` making `file_content` nullable behind a
`file_content IS NOT NULL OR object_key IS NOT NULL` check. The migration shipped.

**The conclusion still stands — store attachment bytes as `BYTEA` on `SupportTicketAttachment` —
but for a different and stronger reason.** The obvious move once you know `StatementStorage` exists
is to reuse it. That would be a mistake at v1, because that layer is content-addressed and its
objects are *shared*:

- Identical bytes resolve to one object, deliberately: a staged session and the import it confirms
  into, multi-section statements, and re-imports all point at the same key. Deleting a row therefore
  must never delete its object.
- Reclamation is handled solely by `StatementStorageSweepService`, which proves the absence of any
  live reference across **three** tables (`statement_imports`, `import_sessions`, `import_jobs`)
  before deleting anything.
- That third table was added to the check only after production evidence surfaced a real FAILED
  `import_jobs` row whose object had no other live reference — i.e. the cost of missing a
  referencing table has already been paid once here, in production, and is documented as BH-017.

Routing support attachments through `StatementStorage` would introduce a **fourth** source of
references that the sweep does not know about. The sweep would then reclaim objects that a support
ticket still points at, and the resulting failure is silent and delayed — a download breaking days
later with nothing connecting cause to effect. Making that safe is a change to an incident-hardened
component, which is not a cost v1 support should carry.

Support attachments also need none of what that layer provides: they are small, low-volume, never
deduplicated, never re-imported, and never shared between rows. `BYTEA` on the child table, size-
capped (e.g. 5MB per file), keeps them entirely outside the shared-object world. If volume ever
justifies moving them, the prerequisite is explicit: extend `StatementStorageSweepService`'s
reference count to include `SupportTicketAttachment` **first**, in the same change.

### 3.6 Security considerations

Finora handles financial data, so ticket access needs the same discipline as everything else in the
app, not an implicit "authenticated users can see support stuff" assumption:

- **Users can view only their own tickets** — enforced the same way ownership checks already work
  elsewhere in the backend (e.g. transactions, imports), not a new authorization pattern.
- **Admins can view all tickets** — gated behind an existing admin-permission check, same as other
  admin-portal screens.
- **Attachments are never publicly accessible URLs.** No signed/guessable link — every download goes
  through an authenticated endpoint (`GET /api/v1/support/tickets/{id}/attachments/{attachmentId}`,
  §3.2) that re-checks ownership/admin status per request, same posture as the existing statement
  download path.
- **Internal notes are admin-only, structurally.** `SupportTicketInternalNote` is reachable only
  from `/api/v1/admin/` endpoints (§3.2). It is never joined into, or serialised by, any user-facing
  ticket response.
- **No deletion in v1.** No delete endpoint for tickets, attachments, feedback or internal notes —
  neither user-facing nor admin-facing. Closed tickets are historical records, and deletion
  requirements tend to arrive later carrying auditing complications; not building the endpoint now
  costs nothing and avoids designing a retention policy before there is anything to retain. This is
  about *endpoints*, and does not conflict with the account-lifecycle bullet below: purge removes a
  departing user's support records as part of deleting their account, which is a lifecycle path, not
  a delete API anyone can call against an individual ticket.
- **Support records must join the existing account-lifecycle paths, not get their own.** This is a
  new user-scoped domain, and the repo has already shipped both an account purge
  (`UserAccountLifecycleService` + `AccountPurgeSweepService.purgeOne`, `V90__account_deletion.sql`)
  and a user data export (`DataExportService`). Four new user-linked tables that neither path knows
  about would mean a deleted user's support tickets and attachments survive the purge, and an
  exporting user's tickets are silently missing from their export. Both are the same class of defect
  as the deleted-account dashboard leak already found in this codebase. Wiring support into both
  paths is part of this work, not a follow-up.

### 3.7 Audit events

Finora already treats `AuditService` as the source of truth for who-did-what
(`docs/engineering/observability.md` §7 and the `AuditService` calls found throughout `AuthService`,
`PasswordChangeService`). Support should follow the same convention rather than being a silent
exception:

- `SUPPORT_TICKET_CREATED`
- `SUPPORT_TICKET_STATUS_CHANGED` — logged on every admin status transition (`OPEN` → `IN_PROGRESS`
  → `RESOLVED`/`CLOSED`), with actor (which admin) and both old/new status.
- `SUPPORT_TICKET_NOTE_ADDED` — actor and ticket only. The note body is **not** copied into the
  audit record; the note table is already append-only and admin-scoped, and duplicating free text
  into a second store widens the surface for no gain.

**The audit log is the source of truth for a ticket's status timeline.** `SupportTicket` carries only
the current `status` and `updatedAt`, so the `OPEN → IN_PROGRESS → RESOLVED → CLOSED` history exists
nowhere else. That is accepted for v1 and stated here so it is a decision rather than an oversight:
operational reporting has to read audit records, not the ticket table. If that becomes painful,
`MerchantLearningAudit` is the in-repo precedent for a domain-specific history table living alongside
`AuditService` — but adding one is an evidence-driven change, listed in §4, not v1 scope.

Low cost to add now, and it's the same data a future Fino admin-assistant would need to answer "how
long did ticket SUP-000001 take to resolve, and who touched it" — see §3.8.

### 3.8 Notification integration (placeholder only — not built now)

Once the notification platform proposal (`notification-communication-platform-proposal.md`) exists,
support tickets are a natural first caller of it — but this is a roadmap connection, not scope added
to this proposal:

```
Ticket created / status changed
        |
NotificationService.send(...)
        |
User notified: "Your support request SUP-000001 was updated"
```

Nothing here should be implemented before the notification service itself exists. Recorded so the
two proposals are known to connect, and so `SupportTicketService` isn't built in a way that makes
adding this call later awkward (e.g. status-change logic should already be centralized in one method
by §3.7's audit-event requirement, which is also the natural place to add a notification call).

## 4. Explicitly out of scope for v1

Carried forward from the parked platform vision, not rejected — just not evidence-justified yet:

- Workflow/routing engine, SLA policies and timers
- Ticket conversation threads (multi-message) — schema reserved (§3.2's `SupportTicketMessage`),
  not built
- Knowledge base / article management
- Fino integration of any kind (classification, auto-resolution, copilot)
- Customer-360 context panel
- Event bus / async event architecture (a synchronous CRUD API needs none of this;
  `MerchantLearningEvent`'s publisher→queue→worker pattern is the reference to extend if that ever
  changes, per prior team decision — not something to build preemptively)
- Dedicated status-history table — the status timeline lives in audit records for v1 (§3.7);
  `MerchantLearningAudit` is the precedent to follow if reporting ever needs to query it directly
- Editing or deleting support records of any kind (§3.6)
- Moving attachment bytes into `StatementStorage` — blocked on extending
  `StatementStorageSweepService`'s reference count first (§3.5)
- Support analytics beyond basic counts (trend detection, clustering, churn signals)
- Multi-channel (email/WhatsApp) ingestion — in-app only for v1

## 5. Rough sizing

| Item | Effort |
|---|---|
| Help Center content expansion | S |
| Support ticket backend (entity, migration, CRUD API) | S–M |
| Support ticket frontend (form, My Tickets, admin list) | M |
| Support ticket attachments (child entity + migration) | S |
| Internal admin notes (entity, migration, admin API, admin UI panel) | S–M |
| Wiring support tables into account purge + data export (§3.6) | S |
| Feedback backend (entity, migration, API) | S |
| Feedback widget (component + mount points) + admin list | S–M |
| Ticket-number generation (sequence + formatting) | S |
| Audit events (`SUPPORT_TICKET_CREATED`/`_STATUS_CHANGED`) | S |
| Authenticated attachment download endpoint | S |

Ticket number, audit events, and the security-scoped attachment endpoint are folded into the ticket
backend item above in practice — broken out here only so each is individually visible, not because
they're separate work items.

Migration versions: latest on `origin/main` is `V118__category_customization.sql` as of the
2026-08-29 revision. The draft's original figure (`V76`) was 42 versions stale by then, which is
precisely the failure its own warning predicted — so the warning stands, doubled: **re-check the
actual latest at implementation time**, not this number. Prior incident on record: concurrent
sessions on this repo produced a real `V75` collision once. This proposal now needs four migrations
(ticket, attachment, internal note, feedback), so reserve the block in one pass rather than
allocating them one at a time across a long-running branch.

## 6. Open questions for whoever implements this

- Should feedback require auth, or accept anonymous submissions from public pages?
- Attachment size cap and allowed types for tickets — align with `UPLOAD_MAX_FILE_SIZE` conventions?
- Does "My Tickets" need real-time status updates, or is a manual refresh acceptable for v1?
- Does the `DataExportService` payload include attachment *bytes*, or only ticket metadata plus
  filenames? Bytes make the export honest but can dominate its size for a single user.
- Are internal notes visible to every admin, or only to admin roles above some threshold? V52's
  `account_scope` distinguishes user from admin, not admin from admin — if per-role scoping is
  wanted, that is a prerequisite this proposal does not currently assume.

None of these block the design being reviewed — they're implementation-time decisions.
