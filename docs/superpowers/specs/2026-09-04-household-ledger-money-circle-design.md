# Household Ledger / Money Circle — Design

**Status:** Product decisions locked after multiple principal-architecture review rounds. Ready
for implementation planning. This spec consolidates those rounds into one reference; no new
decisions are made here.

## 1. Objective

Finora already understands *what* a transaction was for (`Category`) and, increasingly, *who*
was on the other side of it (`CounterpartyIdentity`, `Relationship`). It has no concept of a
financial obligation between two people — money one person fronted that another owes back
(rent collected from flatmates, a maid payment split three ways, a cash loan to a friend, a
security deposit a landlord owes back). This design adds that primitive: **Obligation** — who
owes whom, how much, and whether it's been settled — plus the two constructs built on top of it,
**SharedExpense** (an obligation-creating split of one payment) and **MoneyCircle** (a named
group of relationships obligations can be scoped to).

The differentiator over an app like Splitwise: Finora already has imported bank transactions,
`CounterpartyIdentity`, and a transaction graph. A settlement can be *observed* (a matching
credit lands and is proposed as a match, evidenced) rather than only *declared* (a user taps
"mark as paid" with nothing behind it). Both paths exist in this design — manual settlement is
explicitly required — but they must remain distinguishable forever, not just at write time but
everywhere a settlement is displayed.

## 2. Current behavior (verified against this codebase, not assumed)

- **`CounterpartyIdentity`** (`backend/src/main/java/com/finora/util/CounterpartyIdentity.java`)
  derives a stable `vpa:`/`name:` key per transaction, persisted as `transactions.counterparty_key`
  (V142). Corpus-validated: a median of 2 counterparties explain 80% of a statement's unresolved
  value; counterparties seen 3+ times carry 79.8% of it. The VPA form deliberately drops the PSP
  handle so the same person paying via `@ybl` and `@paytm` merges to one key. `keyOf()` explicitly
  excludes any token with 4+ digits as reference noise — it does not and will not resolve an
  account-number-style identifier.
- **`Relationship`/`RelationshipIdentifier`** (`backend/src/main/java/com/finora/entity/
  Relationship.java`, `RelationshipIdentifier.java`, migration V18) is a named, durable person a
  user tags (`FAMILY`/`FRIEND`/`OWN_ACCOUNT`/`OTHER`), predating `counterparty_key` by over 120
  migrations. Its identifier types are `UPI_ID`, `ACCOUNT_LAST4`, `NAME_PATTERN`. Matching
  (`RelationshipService.transactionsFor()`, and `ReconciliationService`'s own-account transfer
  check via `RelationshipService.ownAccountIdentifierValues()`) is a raw
  `CategoryRules.normalize(description).contains(identifierValue)` scan — computed at query time,
  not indexed, and blind to which extraction path `CounterpartyIdentity` would use for the same
  transaction.
- **The only API surface for `Relationship` is `AdminUserRelationshipController`**
  (`@PreAuthorize("hasAuthority('RELATIONSHIP_MANAGE')")`) — admin/support tooling, not an
  end-user API. No frontend, admin-portal or otherwise, calls it. Traced every write path in the
  backend: `RelationshipService` is the only writer of `Relationship` rows, reachable only through
  that one admin controller. As far as static analysis can establish, there are zero production
  `Relationship` rows — **to be confirmed by `SELECT count(*) FROM relationships;` before this
  design's "no migration needed" assumption is treated as certain.**
- **`transaction_relationships`** (`backend/src/main/java/com/finora/entity/
  TransactionRelationship.java`, migrations V114/V115) is a directed edge table —
  `RelationshipType` (`TRANSFER`, `REFUND`, `REVERSAL`, `DUPLICATE`, `CC_PAYMENT`, `EMI`,
  `SALARY`, `LOAN_REPAYMENT`, `INVESTMENT_TRANSFER`, `CASH_WITHDRAWAL`, `CASH_DEPOSIT`), a
  `CANDIDATE`→`AUTO_CONFIRMED`/`USER_CONFIRMED`/`REJECTED` lifecycle, `confidence`, `sourceTrust`,
  `detectionMethod`. `from_transaction_id`/`to_transaction_id` are both `NOT NULL`. Traced every
  real consumer, not just the enum switch sites: `BudgetService`, `AnalyticsService`,
  `DashboardService`, `InsightsService`, `ReportService` all read the graph through exactly one
  method, `TransactionGraphService.ccPaymentFromTransactionIds()`, whose query hard-filters
  `relationshipType = CC_PAYMENT`. **No consumer generically nets "any graph edge" to zero** —
  a new `RelationshipType` value is invisible to all five unless someone explicitly adds it to
  that one query.
- **Explainability already reaches end users.** `GET /transactions/{id}/explanation` →
  `TransactionExplanationService` → `ExplanationModal` in `Ledger.tsx` shows both a categorization
  "why" (`Transaction.DecisionSource`, matched `CategoryRule`) and a reconciliation match summary.
  Separately, `AdminReconciliationExplorerController`/`ReconciliationExplorerService` is a
  broader, admin-only full graph trace.
- **The notification platform is built and live**, not just designed
  (`backend/src/main/java/com/finora/notification/` — domain, repository, provider, worker
  layers, migrations V125–V128), triggered today by `ImportJobWorker` and `HeldStatementService`.
- **The bulk-review pattern** (`TransactionGroupingService` groups needs-review transactions,
  sorts by value; `MerchantGroupReviewCard`/`CounterpartyGroupReviewCard` render it) is the
  established shape for "N rows, one action" — no settlement-review sibling exists yet.
- **`AuditService`** is the established pattern for recording who/what/when on every
  state-changing write across the codebase (`RelationshipService`, `TransactionService`, etc.).
- **`Budget`/`Goal`** are single-owner (`user_id` only, no group column) — shared budgets/goals
  are explicitly not part of this design (see §7).
- **INR only.** No currency column exists on `Transaction`/`Account`; none is introduced here.

## 3. Core primitives

### 3.1 Obligation — the primitive

Not `SharedExpense`. Represents a single directional debt: who owes whom, how much, its status,
and its settlement state.

Columns (conceptual, not a migration):

- `relationship_id` — who the obligation is with. References `Relationship`.
- `amount` — the *current remaining* amount (see §5, partial settlement).
- `direction` — `THEY_OWE_ME` / `I_OWE_THEM`. Not redundant with a signed amount elsewhere —
  unlike `Category` or `CounterpartyType`, direction is constitutive of what a debt is, not a
  second encoding of an existing fact.
- `status` — `OPEN`, `SETTLED`, `WRITTEN_OFF`.
- `source_transaction_id` — nullable. Set when the obligation was created from an imported
  transaction (see §3.4, verified vs. manual). Null for a manual obligation with nothing to link
  (cash dinner, never imported).
- `split_group_id` — nullable. Set when this obligation was created as one share of a
  `SharedExpense` (§3.2).
- `settlement_type` — nullable until settled: `AUTO_MATCHED` / `MANUAL`.
- `settlement_reason` — required when `settlement_type = MANUAL` (Decision: mandatory reason).
- `settled_at`, `settled_by` — set once, when `amount` reaches zero (§5).
- `settling_edge_id` — nullable, references a `transaction_relationships` row when
  `settlement_type = AUTO_MATCHED` (§3.5). Never set for `MANUAL`.

Rule, locked: **`Obligation` is current truth. `AuditService` is historical truth.** Every state
change (create, partial payment, settle, write off, reopen, dispute) writes an audit event; the
row itself only ever reflects the current state. This is why `settlement_reason`/`settled_by`
are *not* duplicated as a running list on the row — the row holds the latest value, the audit log
holds every value that preceded it.

### 3.2 SharedExpense — a grouping construct, not the primitive

Creates multiple `Obligation` rows from one payment. Kept as a thin table (not collapsed into a
plain `split_group_id` column with derived totals) specifically because a manual split with no
linked transaction — a cash dinner never imported — has nothing to derive `total_amount`/
`description` from. Columns: `id`, `user_id`, `payer_transaction_id` (nullable, same reasoning as
`Obligation.source_transaction_id`), `description`, `total_amount`, `split_type`
(`EQUAL`/`CUSTOM`). Each resulting `Obligation` row carries `split_group_id` pointing back to it.

### 3.3 MoneyCircle — social grouping, genuinely new

No existing abstraction in the codebase to build on — checked for a Group/Tag/Label entity,
found none. Two tables: `money_circle` (`id`, `user_id`, `label`) and `money_circle_members`
(`money_circle_id`, `relationship_id`). Many-to-many, not a `circle_id` column on `Relationship`:
one relationship (e.g. a flatmate) can belong to more than one circle at once (a "Flatmates"
circle and a "Goa Trip" circle), so a single-owner column would be a functional regression.

### 3.4 Obligation creation — two required paths

- **Verified**: derived from an imported transaction. `source_transaction_id` set. Either a
  single 1:1 obligation (a P2P transfer that looks like a loan/reimbursement) or, via
  `SharedExpense`, N obligations from one split payer leg (rent, groceries).
- **Manual**: created directly by the user, no imported transaction required.
  `source_transaction_id` null. Covers cash dinners, cash rent advances, security deposits,
  friend loans, employer reimbursements, shared travel expenses paid outside any tracked account.

These two states must remain distinguishable forever — same rule as settlement type, applied one
layer up (how the obligation was *created*, not how it was *settled*).

### 3.5 Settlement matching — extend the graph, don't parallel it

Add one `RelationshipType` value to `transaction_relationships`: `SHARE_SETTLEMENT`. No new
table, no parallel matching subsystem — confirmed safe: every downstream consumer of the graph
that nets matched legs to zero for spend/income totals does so through
`ccPaymentFromTransactionIds()`, which is hard-filtered to `CC_PAYMENT` and structurally blind to
any other `RelationshipType`, `SHARE_SETTLEMENT` included. Both legs of a `SHARE_SETTLEMENT` edge
are the *same user's* own transactions (the payer-leg debit, and the reimbursement credit landing
in their own account) — Finora never sees the other person's statement, so the graph's existing
same-user invariant holds without modification.

`AUTO_MATCHED` settlement: a `CANDIDATE` `SHARE_SETTLEMENT` edge is proposed (same
confidence/detection-method machinery every other edge type already uses), surfaced through a new
settlement review card (sibling to `MerchantGroupReviewCard`/`CounterpartyGroupReviewCard`, same
group-sort-bulk-apply pattern via `TransactionGroupingService`), user confirms, `Obligation`
updates.

`MANUAL` settlement: **no graph edge at all.** `transaction_relationships.from_transaction_id`/
`to_transaction_id` are `NOT NULL` — a manual settlement has no settling transaction by
definition, so representing one as an edge means either nullable columns on a table ten services
depend on, or a fabricated placeholder transaction. Recorded directly on `Obligation`
(`settlement_type = MANUAL`, `settlement_reason` required, `settled_by`, `settled_at`) plus an
`AuditService` event. This also means manual settlements are automatically invisible to every
graph-netting consumer traced above — consistent by construction, no special-casing needed.

## 4. Identity layer changes

Collapse `RelationshipIdentifier.Type.UPI_ID` and `NAME_PATTERN` into one `COUNTERPARTY_KEY`
type, resolved by an indexed join against `transactions.counterparty_key` instead of the current
`CategoryRules.normalize(description).contains(...)` scan. `ACCOUNT_LAST4` is kept, unchanged —
`CounterpartyIdentity.keyOf()` deliberately excludes 4+ digit tokens as reference noise, so this
is a genuinely distinct signal, not a duplicate.

**Validated, not assumed, that this loses no matching capability it needs to keep**: the current
`NAME_PATTERN` substring scan is format-agnostic (matches "RAHUL" anywhere in normalized text,
regardless of whether that transaction would key as `vpa:rahul` or `name:rahul verma`).
`COUNTERPARTY_KEY` is more precise per binding but requires binding to every key format a person
actually appears under — a `Relationship` may need more than one `COUNTERPARTY_KEY` identifier
row (e.g. both `vpa:rahul` and `name:rahul verma`, if the same person sometimes pays by UPI and
sometimes by name-only NEFT) to match everything the old loose pattern used to catch in one row.
`RelationshipIdentifier` is already one-to-many per relationship, so this is a UX design point
(surface a person's already-observed keys as bindable suggestions, don't rely on free-text entry
alone), not a schema change beyond the type collapse itself.

No end-user `Relationship` API exists today (§2) — one is required regardless of sequencing.

**`CounterpartyIdentity.keyOf()` re-verified against its actual source** (not recalled from
memory), with worked examples grounding the "same person across banks" claim this whole design
leans on:

```java
Matcher vpa = VPA.matcher(description);          // ([A-Za-z0-9._]{2,})@([A-Za-z][A-Za-z0-9]{1,})
if (vpa.find()) {
    String local = vpa.group(1).toLowerCase();
    if (!local.replaceAll("[._-]", "").isEmpty()) return cap("vpa:" + local);
}
```

| Input narration fragment | Local part matched | Key produced |
|---|---|---|
| `rahul@oksbi` | `rahul` | `vpa:rahul` |
| `rahul@okhdfc` | `rahul` | `vpa:rahul` — **same key**, handle dropped by design |
| `rahul@ybl` | `rahul` | `vpa:rahul` — **same key** |
| `rahul.kumar@oksbi` | `rahul.kumar` | `vpa:rahul.kumar` — **different key**, different local part |

The plan's assumption holds exactly as stated: three banks, one key, because the regex captures
only `group(1)` (the local part) and discards `group(2)` (the handle) entirely — the handle never
reaches `cap("vpa:" + local)`. The boundary worth documenting alongside that guarantee: this is a
local-part *equality* merge, not a fuzzy-name merge — `vpa:rahul` and `vpa:rahul.kumar` do **not**
collapse into each other even if they're plausibly the same person, because the merge mechanism is
"same local part, different handle," not "similar-looking identity." A person who is inconsistent
about which VPA local part they use needs more than one `COUNTERPARTY_KEY` identifier bound to
their `Relationship` — the same one-to-many shape already used for the multi-bank case, just
applied to a different kind of variation.

## 5. Manual settlement, partial settlement, and lifecycle

- **Mandatory reason + immutable audit trail** for every manual settlement (locked). Reuses
  `AuditService`, the established pattern.
- **Auto-matched and manual settlements must remain visually distinguishable everywhere a
  settlement is shown**, not only distinguishable in the database — the trust argument for this
  feature over a Splitwise-style declared ledger depends on that distinction being visible, not
  just queryable.
- **Reopen after settlement**: handled by the current-truth/audit-log split already locked — reset
  `Obligation.status`/`settlement_*` columns to their pre-settlement state, the full history
  (original settlement, the reopen, any resettlement) lives in the audit log regardless.
- **Write-off**: a named `status = WRITTEN_OFF`, not a new mechanism.
- **Partial and multiple settlements** (the one gap the lifecycle-completeness check surfaced —
  directly implicated by "friend loan" being in-scope for manual obligations, since loans are the
  case most likely to be repaid in parts): **a partial payment reduces `Obligation.amount` and
  writes an audit event each time** (`PARTIAL_PAYMENT_RECORDED`, amount paid, remaining balance).
  `settlement_type`/`settlement_reason`/`settled_at`/`settled_by` are written exactly once, when
  `amount` reaches zero. No new columns, no child settlement-events table — this fits the
  already-locked current-truth/audit-log rule rather than extending it.
- **Dispute**: not resolved by this spec. No `DISPUTED` status exists in the locked design.
  Flagged as a genuine gap, not silently covered — left for the implementation plan to either add
  a status value or explicitly defer to V2.

## 6. Balance model

Gross and net are both required (locked). **Neither is stored.** Gross is the list of open
`Obligation` rows for a relationship, already the source of truth. Net is
`SUM(signed amount) GROUP BY relationship_id WHERE status = OPEN`, computed at read time — the
same pattern `DashboardService.summarize` already uses for account-level aggregates rather than
materializing them. At household transaction volumes this costs nothing; storing it would
introduce exactly the write-skew/staleness risk this codebase has repeatedly found and fixed
elsewhere (e.g. balance-sequence-ordering, ghost-merchant bugs).

## 7. Explicitly out of scope for this spec

- Multi-currency (INR only, locked).
- Shared `Budget`/`Goal` (both remain single-owner; `MoneyCircle` does not extend them).
- Employer/landlord as a first-class `Relationship.Type` (still only `FAMILY`/`FRIEND`/
  `OWN_ACCOUNT`/`OTHER` — a real, small V2 gap, not a blocker for the relationship-focused V1
  scope already decided).
- `DISPUTED` obligation status (§5).
- Any redesign of `CounterpartyIdentity`, `MerchantNormalizationEngine`, or
  `transaction_relationships`' existing edge types — this design extends, none of it is touched.

## 8. Validation status

| Item | Status |
|---|---|
| `relationships` table is empty in production | **Confirmed** — `SELECT count(*) FROM relationships;` returned 0. `relationship_identifiers` is necessarily 0 too, by its `NOT NULL REFERENCES relationships(id) ON DELETE CASCADE` foreign key (V18). The identity-matcher rewrite is zero-migration with certainty, not assumption. |
| `COUNTERPARTY_KEY` collapse preserves matching behavior | Validated (§4) — re-verified against `keyOf()`'s actual source, worked examples above. Preserves capability given multi-key binding per relationship; changes matching from implicit substring to explicit key binding. |
| `SHARE_SETTLEMENT` cannot corrupt spend/budget/analytics/dashboard/insights/reporting | Validated by tracing every real consumer (§2, §3.5) — not inferred from the enum switch sites alone. |
| Manual obligation lifecycle completeness | Validated (§5) — partial settlement resolved; dispute explicitly deferred, not silently dropped. |
