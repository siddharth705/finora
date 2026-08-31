# Audit & Activity Intelligence — Design Proposal

**Status:** Proposal only. Design after GA blockers, production-safety work, and the current bug hunt
are closed. Same sequencing as every other document in this directory.

**Major correction to the originating draft's premise:** it proposes building a "unified audit event
model," a generic queryable table, and an "admin investigation view" as new work. **All three
already exist, and are more built-out than the draft assumed:**

- `AuditService.record(actorId, action, entityType, entityId, metadata)` — a single append-only
  service writing to a real, indexed, queryable `audit_logs` table (`AuditLog` entity,
  `backend/src/main/java/com/finora/entity/AuditLog.java`), not scattered logging.
- **79 call sites across 21 services** — `TransactionService`, `BudgetService`, `AccountService`,
  `GoalService`, `RuleService`, `AuthService`, `RoleService`, `FeatureFlagService`,
  `StatementImportService`, and more. Action strings already include
  `TRANSACTION_CREATED/UPDATED/DELETED/BULK_DELETED/CATEGORY_UPDATED`, `BUDGET_UPSERTED`,
  `ACCOUNT_CREATED/UPDATED/DELETED`, `GOAL_CREATED/DELETED`, alongside every auth/RBAC/admin-setting
  event already known from prior proposals in this set.
- **A working admin UI, not a gap**: `admin-portal/src/pages/AuditLog.tsx` — a day-grouped,
  icon-categorized, filterable (search/date-range/sort, with saved views) global activity feed,
  backed by `AdminController.globalAuditLogs` → `AuditLogRepository.search(q, dateFrom, dateTo,
  pageable)`.
- **A per-user drill-down already implemented**: `GET /admin/users/{userId}/audit-logs`, gated by
  the `AUDIT_VIEW` permission. This is exactly the "Admin Investigation View" §3.4 of the original
  draft asked for — it isn't a new screen to design, it's a screen to point at in this document.

This changes the proposal from "build an audit platform" to "expose two missing views on top of an
audit platform that already exists."

---

## 1. Objective

Add the two genuinely missing pieces on top of the existing, working `AuditService`/`AuditLog`/
`AuditLog.tsx` foundation: a **user-facing security/activity timeline**, and a **cross-entity trace
capability for non-import entities**, extending the pattern `AdminImportTraceController` already
established for imports.

## 2. What exists today (baseline — see correction above for detail)

- Generic append-only audit log, 79 call sites, 21 services, admin global feed + per-user drill-down.
- New-state-only convention (confirmed again here: no `oldValue`/`newValue` field anywhere in the
  codebase) — matches the convention already documented in
  `remote-configuration-feature-management-proposal.md` §5.3. Any proposal item requiring
  before/after diffing (e.g. "oldCategory: Shopping, newCategory: Food" in the original draft's
  example) needs metadata to carry both values explicitly at the call site, same as today — this
  isn't a schema gap, it's how existing calls already work when they need it
  (`TRANSACTION_CATEGORY_UPDATED`'s metadata presumably already does this; confirm at implementation
  time rather than assume).
- Import-specific trace endpoints (`/api/v1/admin/imports/traces/by-analysis/{reference}`,
  `by-job/{jobId}`) exist; nothing equivalent exists for transactions or budgets.
- No user-facing activity/security page exists — `frontend/src/pages/landing/Security.tsx` is static
  marketing copy about Finora's security model, not a live feed of the viewer's own events.

## 3. Proposed scope (v1 — the only thing being designed here)

### 3.1 User-facing security/activity timeline (new — the real gap)

A `frontend/` page reading the user's own audit events, scoped to security-relevant action types
only (not every `TRANSACTION_UPDATED` — a user doesn't need their own edit history framed as
"security activity"):

```
GET /api/v1/me/security-activity
```

Filtered server-side to a small allowlist: `LOGIN_SUCCESS`, `PASSWORD_CHANGED`, `PHONE_VERIFIED`,
`PHONE_UPDATED`, and whatever new-device/session events land from the (separately parked) User
Security Center proposal. **Reuses `AuditLogRepository` and the existing per-user query path** —
this is a new controller method and a new frontend page, not a new backend audit mechanism.

### 3.2 Entity trace capability — transactions and budgets (new, modeled on imports)

`AdminImportTraceController`'s pattern (assemble every recorded event for one entity into a single
ordered response) generalizes cleanly:

```
GET /api/v1/admin/audit/trace/{entityType}/{entityId}
```

Returns every `AuditLog` row for that `(entityType, entityId)` pair, ordered by time — "what
happened to transaction TXN-12345, in order." This is a thin query layer over the existing table
(`AuditLogRepository` already has `entityType`/`entityId` columns to filter on per the entity
definition), not a new tracking mechanism. Scope to transactions and budgets for v1, since those are
the entities named in the original draft's investigation example.

### 3.3 Financial change *coverage* audit (verification task, not new code)

Before claiming "financial change tracking" as a deliverable, verify — don't assume — that every
mutating operation on transactions/budgets/accounts/goals actually calls `AuditService.record()`
today. The 79-call-site count is a strong signal of good coverage, but a targeted review (which
service methods mutate state without an adjacent audit call) is cheap and catches silent gaps before
building UI on top of an assumption. Sized as a review task, not a design item.

**Coverage criteria — so the review has a measurable pass/fail, not a subjective impression.** A
mutation on `Transaction`/`Budget`/`Account`/`Goal` is "covered" only if an adjacent
`AuditService.record()` call exists for each of:

```
CREATE
UPDATE
DELETE
BULK_UPDATE / BULK_DELETE
IMPORT (system-initiated creation, not user-initiated)
ADMIN_OVERRIDE (an admin acting on a user's entity)
```

Review output should be a table, not a paragraph — entity × operation × covered/not:

```
Entity        create()  update()  delete()  import()  bulk*()
Transaction      ✅        ✅        ❌         ✅        ✅
Budget           ✅        ✅        ✅         —         —
```

The dangerous gaps are consistently in delete/bulk/admin-override paths, not create — reviewing all
five operation kinds per entity rather than just checking "does this entity have any audit calls" is
what makes the review actually catch something.

### 3.4 Audit event visibility model (new — replaces an ad-hoc allowlist with a queryable field)

§3.1's "small allowlist" of action types for the user-facing timeline works for a first pass, but an
allowlist maintained only in controller code drifts silently as new action types get added elsewhere
(the same drift risk this proposal set has flagged repeatedly for other systems). A `visibility`
column on `AuditLog` itself makes the rule enforceable at write time, not just read time:

```
audit_logs
├── ... (existing columns)
├── visibility     — USER_VISIBLE, ADMIN_ONLY, SYSTEM_ONLY
```

```
PASSWORD_CHANGED           → USER_VISIBLE
STATEMENT_IMPORTED         → USER_VISIBLE
BUDGET_CREATED              → USER_VISIBLE
ADMIN_SETTINGS_UPDATED       → ADMIN_ONLY
DATABASE_RECONCILIATION_RUN   → SYSTEM_ONLY
```

§3.1's `GET /api/v1/me/security-activity` then filters on `visibility = USER_VISIBLE` instead of
maintaining a separate allowlist — one source of truth instead of two. Existing call sites default to
`ADMIN_ONLY` (today's actual behavior — nothing is user-visible today) until explicitly reviewed and
reclassified, so this is additive and can't accidentally expose something that isn't ready. Also the
right foundation for Fino: an assistant answering "what happened to my account" should only ever be
able to read `USER_VISIBLE` rows for that user, which this column makes enforceable at the query
level rather than something Fino's prompt has to get right on its own.

### 3.5 Permission separation for the entity trace endpoint (resolves §6's open question)

`AUDIT_VIEW` today covers everything in the global admin feed — authentication events and admin
actions alike. §3.2's entity trace endpoint exposes something more sensitive at finer grain
(transaction/account-level financial detail, not just "what admin action happened"), and reusing the
same permission for both understates that difference. Split:

```
AUDIT_VIEW_SYSTEM       — the existing global feed and per-user drill-down (auth, admin, RBAC events)
AUDIT_VIEW_FINANCIAL    — §3.2's entity trace endpoint specifically (transaction/budget history)
```

Existing `AUDIT_VIEW` holders should be grandfathered into `AUDIT_VIEW_SYSTEM` only, not both — an
admin who currently has audit access shouldn't silently gain financial-entity trace access as a side
effect of this permission split. Whether to also grant `AUDIT_VIEW_FINANCIAL` to that same admin
population is a role-design decision for whoever implements this, not defaulted here.

## 4. Explicitly out of scope

- Rebuilding `AuditService`, `AuditLog`, or the existing admin `AuditLog.tsx` page — done.
- Full SIEM system, security threat-detection engine, compliance reporting, AI anomaly detection —
  unchanged from the original draft's out-of-scope list, still correct.
- Before/after value tracking as a schema-wide change — flagged as a system-wide convention question
  in the Remote Configuration proposal (§5.3), not decided or built here.
- Fino "why did my balance change" / "what happened with failed imports" — these consume the trace
  endpoints in §3.2 and the existing import traces respectively, once Fino itself is unparked. No
  Fino work here.
- Retroactively backfilling `visibility` (§3.4) on historical rows by inference — default them to
  `ADMIN_ONLY` and reclassify only the specific action types worth surfacing, don't guess at scale.

## 5. Estimated effort

| Component | Effort |
|---|---|
| ~~Unified audit model~~ | Already built |
| ~~Admin investigation view~~ | Already built |
| User-facing security timeline (API + page) | S–M |
| Entity trace endpoint (transactions + budgets) | S |
| Audit-coverage verification review (§3.3, with pass/fail criteria) | S |
| `visibility` column + reclassification of existing action types (§3.4) | S |
| `AUDIT_VIEW_SYSTEM`/`AUDIT_VIEW_FINANCIAL` permission split (§3.5) | S |

Substantially smaller than the original draft implied — the "Foundation" phase it described is
mostly already shipped.

## 6. Open questions for whoever implements this

- Which existing action types get reclassified to `USER_VISIBLE` (§3.4) — needs product input on what
  a user should and shouldn't see about their own account activity; §3.1's allowlist example is a
  starting point, not a final list.
- Should `AUDIT_VIEW_FINANCIAL` (§3.5) be granted to the same admin population that currently holds
  `AUDIT_VIEW`, or is it a narrower group — a role-design decision, not resolved here.
