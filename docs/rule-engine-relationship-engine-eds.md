# Finora Rule Engine, Decision Source & Relationship Engine — Technical Design

**Status:** Design review draft. Reconciles the "FILE pipeline architecture" proposal (Epics 1–10) against `financial-intelligence-engine-spec.md` (✅ implemented) and the current codebase. No implementation should proceed against the four items in §1 until this reconciliation is treated as final.

**Relationship to other docs:** this does not replace `financial-intelligence-engine-spec.md` — Merchant Resolution, Confidence, Learning, Duplicate/Transfer detection stay exactly as specified and implemented there. This document covers what that spec left as 🔲 or didn't cover at all: a real Rule Engine (global + user-defined rules, not just the static `CategoryRules` keyword table), explicit Decision Source tracking on every transaction, and a new Relationship Engine (family/friends/own-accounts).

---

## 1. Reconciliation of the four conflicts

The FILE pipeline proposal, read against the current codebase and existing specs, disagreed with prior decisions in four places. Resolved as follows:

| # | Proposal said | Reconciled decision | Why |
|---|---|---|---|
| 1 | Flat feature packages (`engine/`, `parser/`, `rules/`, `learning/`, `merchant/`, `mapper/`, ...) | **Keep the current layered structure** (`controller/`, `service/`, `repository/`, `entity/`, `dto/`). New classes below (`RuleEngineService`, `RelationshipService`, etc.) land in the existing `service`/`entity`/`repository`/`controller` packages. | Matches the Phase 3 TDD's explicit recommendation to keep layered until module count roughly doubles; this work doesn't cross that line. No dedicated `mapper` package either — inline DTO mapping stays the convention (see `CategorizationService`, `AccountDto.from`, etc.). |
| 2 | Auto-apply confidence threshold: <80% → ask user | **Keep `ConfidenceEngine.DEFAULT_AUTO_APPLY_THRESHOLD = 90`** | Already implemented and load-bearing for existing behavior (Ask Once, Learn Forever). Changing it is a real product decision about false-positive risk, not a documentation-reconciliation side effect. Not changed here. |
| 3 | New endpoints `POST /api/import/preview`, `POST /api/import/confirm`, `GET /api/import/history` | **Reuse and extend the existing `/api/v1/import/csv/stage` and `/api/v1/import/csv/confirm`** (`ImportController`/`CsvImportService`), plus the existing `StatementImportController` for history | Those endpoints already implement stage→review→confirm for CSV today ("Complete for CSV MVP"). The pipeline stages this doc wants added (rule evaluation, relationship tagging, decision-source recording) are new *steps inside* `CsvImportService.parseRow()` / `confirm()`, not a new parallel API surface. |
| 4 | Epic 2: bank-specific parsers (SBI/ICICI/PNB/HDFC/Axis) as part of this milestone | **Deferred to `statement-intelligence-engine-spec.md`'s own milestone**, gated on its existing DPDP Act 2023 compliance review for the AI-fallback path | Today's `CsvImportService` is one generic, bank-agnostic CSV parser (header-hint detection, not per-bank templates) — see its own doc comment ("PDF ingestion is intentionally out of scope"). Building 5 bank-specific parsers is materially new scope belonging to that spec's sequencing, not bolted onto this one. |

Everything below is new, additive scope this document *is* proposing for the current milestone — it doesn't touch the four items above.

---

## 2. Component responsibilities (new)

| Component | Responsibility | Explicitly NOT responsible for |
|---|---|---|
| **Rule Engine** (`RuleEngineService`, new) | Evaluate a transaction's fields (`description`, `amount`, `merchant`, `accountType`) against a user's ordered list of `category_rules` — global (seeded, all users) then user-defined — and return a match or none | Merchant resolution, confidence math, learning (all delegate to existing engines). Runs *before* the existing learned-distribution/keyword fallback in `CategorizationService.suggest()`, not instead of it. |
| **Decision Source Recorder** (no new service — a field on `Transaction` + a helper in `CategorizationService`) | Record *which* mechanism produced a transaction's category: `GLOBAL_RULE`, `USER_RULE`, `LEARNED_PATTERN`, `MERCHANT_DEFAULT`, `MANUAL`, `FILE_PROVIDED` | Deciding the category itself — purely an audit/explainability label attached after the fact |
| **Relationship Engine** (`RelationshipService`, new) | Let a user tag other people/accounts as family, friend, or "my own other account," and use that tag to (a) improve transfer detection beyond amount+date heuristics, (b) let rules/analytics condition on relationship | Categorization directly — a relationship tag is a signal `ReconciliationService` and `RuleEngineService` can consume, not a category itself |

---

## 3. Database design

### 3.1 `category_rules` (new — V17)

One table, `scope` column distinguishes global (system-seeded, read-only to users) from user-authored rules. More general than the old flat `CategoryRules` keyword map: field-based conditions, and actions beyond "assign category."

```sql
CREATE TABLE category_rules (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID REFERENCES users(id),          -- NULL for GLOBAL scope
    scope           VARCHAR(10) NOT NULL,                 -- GLOBAL | USER
    field           VARCHAR(20) NOT NULL,                 -- DESCRIPTION | AMOUNT | MERCHANT | ACCOUNT_TYPE
    operator        VARCHAR(20) NOT NULL,                 -- CONTAINS | EQUALS | STARTS_WITH | GT | LT | BETWEEN
    comparison_value TEXT NOT NULL,
    action_type     VARCHAR(20) NOT NULL,                 -- ASSIGN_CATEGORY | MARK_TRANSFER | MARK_INVESTMENT | MARK_SUBSCRIPTION | ADD_TAG
    action_value    TEXT,                                 -- category name / tag name, per action_type
    priority        INT NOT NULL DEFAULT 100,             -- lower runs first; global rules default higher (run later) than user rules
    enabled         BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_category_rules_user ON category_rules(user_id) WHERE scope = 'USER';
CHECK ((scope = 'GLOBAL' AND user_id IS NULL) OR (scope = 'USER' AND user_id IS NOT NULL))
```

Seed data (global rules) ships as a follow-up migration once the initial keyword set from `CategoryRules.RULES` is reviewed for conversion — not auto-converted wholesale, since `CategoryRules`' word-boundary Pattern matching and this table's simpler `CONTAINS`/`EQUALS` operators aren't a 1:1 mapping (see that class's own comments on false-positive fixes like "rent" vs "current").

### 3.2 `transactions.decision_source` (new — V17)

```sql
ALTER TABLE transactions ADD COLUMN decision_source VARCHAR(20) NOT NULL DEFAULT 'MERCHANT_DEFAULT';
ALTER TABLE transactions ADD COLUMN decision_rule_id UUID REFERENCES category_rules(id);
```

`decision_rule_id` is nullable and only set when `decision_source` is `GLOBAL_RULE` or `USER_RULE` — lets a future "why was this categorized this way" screen link straight back to the rule that fired, not just the enum label.

Mapping from today's `CategorizationService.Suggestion.source()` strings to the new enum (both are kept — `source()` stays the internal categorization contract, `decision_source` is the persisted audit label):

| `Suggestion.source()` | `decision_source` |
|---|---|
| `"learned"` | `LEARNED_PATTERN` |
| `"rule"` (new: global/user rule matched) | `GLOBAL_RULE` or `USER_RULE` |
| `"default"` | `MERCHANT_DEFAULT` |
| `"file"` (category came from the CSV's own Category column) | `FILE_PROVIDED` |
| set via `TransactionService.updateCategory()` / manual edit / bulk recategorize | `MANUAL` |

### 3.3 `relationships` and `relationship_rules` (new — V18)

```sql
CREATE TABLE relationships (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id),
    label           VARCHAR(100) NOT NULL,        -- e.g. "Mom", "Roommate", "My HDFC Savings"
    relationship_type VARCHAR(20) NOT NULL,        -- FAMILY | FRIEND | OWN_ACCOUNT | OTHER
    linked_account_id UUID REFERENCES accounts(id), -- set only for OWN_ACCOUNT
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE relationship_identifiers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    relationship_id UUID NOT NULL REFERENCES relationships(id) ON DELETE CASCADE,
    identifier_type VARCHAR(20) NOT NULL,          -- UPI_ID | ACCOUNT_LAST4 | NAME_PATTERN
    identifier_value VARCHAR(200) NOT NULL
);
```

A relationship is many-to-many with raw transaction descriptions the same way `MerchantAlias` is for merchants — deliberately reusing that pattern (exact match on a normalized identifier) rather than inventing a second fuzzy-matching heuristic.

---

## 4. Processing pipeline (updated)

`CategorizationService.suggest()` gains one step, inserted before the existing learned-distribution check:

```
1. Resolve merchant                         (MerchantNormalizationEngine — unchanged)
2. NEW: Evaluate user rules (category_rules, scope=USER, enabled, ordered by priority)
   → match found: decision_source = USER_RULE, done
3. NEW: Evaluate global rules (scope=GLOBAL)
   → match found: decision_source = GLOBAL_RULE, done
4. Learned distribution check                (existing — ConfidenceEngine.topCategory)
   → match found: decision_source = LEARNED_PATTERN, done
5. Keyword fallback (CategoryRules.suggestCategory) → decision_source = MERCHANT_DEFAULT
```

User rules run before global rules so a user can always override a system default for their own data. This is additive to the existing `Suggestion(category, source, merchantId)` record — no existing caller (`TransactionService`, `CsvImportService`) needs to change its branching logic, since `"rule"` was already a recognized `source()` value before this work (see `CategorizationService`'s own doc comment: `("learned" | "rule" | "default")`); only `decision_source` on the persisted `Transaction` is new.

Relationship tagging runs as an additional pass inside `ReconciliationService.reconcileForUser()`, after its existing duplicate/transfer detection: for `OWN_ACCOUNT` relationships specifically, a matched `relationship_identifiers` hit on either side of a transfer-shaped pair is stronger evidence than the current amount+date heuristic alone and can raise transfer-match confidence rather than replace the heuristic.

---

## 5. API surface

All new endpoints follow the existing `/api/v1/...` convention and the codebase's thin-controller/service-layer split (see `RoleAdminController`/`RoleService` for the pattern this follows).

| Method | Path | Notes |
|---|---|---|
| `GET /api/v1/rules` | List the caller's user rules (+ read-only global rules) | |
| `POST /api/v1/rules` | Create a user rule | |
| `PUT /api/v1/rules/{id}` | Update a user rule (403 if it's GLOBAL scope) | |
| `DELETE /api/v1/rules/{id}` | Delete a user rule | |
| `GET /api/v1/relationships` | List the caller's relationships | |
| `POST /api/v1/relationships` | Create a relationship + its identifiers | |
| `DELETE /api/v1/relationships/{id}` | Remove | |
| `POST /api/v1/import/csv/stage` *(existing, unchanged path)* | Staging response gains `decisionSource` per row | |
| `POST /api/v1/import/csv/confirm` *(existing, unchanged path)* | `ConfirmResponse` gains a `decisionSourceTally` alongside the existing `categoryTally` | |

No new import-history endpoint — `StatementImportController`'s existing list/detail endpoints already serve that.

---

## 6. Non-goals for this milestone

- Bank-specific (SBI/ICICI/PNB/HDFC/Axis) parsers — belongs to `statement-intelligence-engine-spec.md`.
- Package restructure to feature/engine-based modules — not warranted at current module count.
- Changing the 90% auto-apply threshold.
- A dedicated `mapper` layer — inline mapping stays consistent with the rest of the codebase.
- Global rule *authoring* UI/admin console — global rules ship via migration-seeded data for this milestone; an admin CRUD surface (gated the way `RoleAdminController` gates RBAC, e.g. a `RULE_MANAGE` permission) is a fast-follow, not blocking.

---

## 7. Definition of Done

- `category_rules`, `relationships`, `relationship_identifiers` migrations (V17, V18) applied; `transactions.decision_source`/`decision_rule_id` added.
- `RuleEngineService` evaluates user rules then global rules ahead of the existing learned/keyword fallback in `CategorizationService.suggest()`, with tests covering priority ordering and the USER-before-GLOBAL precedence.
- Every code path that sets a transaction's category (CSV confirm, manual create, manual edit, bulk recategorize) sets `decision_source` correctly per the mapping table in §3.2.
- `RelationshipService` CRUD + identifier matching, with `ReconciliationService` consuming `OWN_ACCOUNT` matches as a transfer-confidence signal.
- No changes to `/api/v1/import/csv/stage` or `/csv/confirm` request/response shapes beyond additive fields (existing frontend callers keep working unmodified).
- No new top-level packages; new classes land in `service`/`entity`/`repository`/`controller`/`dto`.
