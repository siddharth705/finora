# Transaction Intelligence Engine — Phase 0 Architecture Audit

**Status:** Investigation only. No code changed. Written for approval before Phase 1 begins.
**Scope:** Backend (`backend/src/main/java`) and frontend (`frontend/src`, `mobile/src`) architecture as it exists today, mapped against the 9-phase Transaction Intelligence Engine vision, to establish what's real before anything is built.

## Headline finding

The initiative's framing — "don't design `Merchant → Category`, design a full understanding engine" — is the right instinct, but **Finora already has most of that engine**: a canonical merchant-identity system, a two-tier rule/learning categorization pipeline with explicit precedence, a confidence-scored learning model with a per-user auto-apply threshold, transfer/refund/duplicate reconciliation, and recurring-subscription detection. None of Phases 1, 2, 4, 5 (partially), or 6 need to be built from scratch. What's actually missing is narrower and more mechanical than the original prompt assumes: **the pieces exist but aren't connected to each other or to the user**, and one whole capability (Phase 3, bulk grouping) is a backend-complete, frontend-absent gap that is the most direct fix for the stated pain point.

This changes the recommended order significantly from a phase-by-phase rebuild: the highest-leverage, lowest-risk work is wiring existing capability to the UI, not building new capability.

---

## 1. Current state analysis

### 1.1 Merchant intelligence (maps to the initiative's Phase 1) — already built

- `Merchant` entity + `MerchantAlias` entity (`backend/src/main/java/com/finora/entity/Merchant.java`, `MerchantAlias.java`, schema since V7) implement exactly the canonical-name/aliases model the initiative describes: `SWIGGY*12345` / `UPI-SWIGGY` / `SWIGGY INDIA` all resolve to one `Merchant` row via `MerchantNormalizationEngine.resolve()`.
- Normalization strips payment-rail tokens (`PaymentRailTokens.isRailToken`) before matching, specifically so `UPI/ref/SWIGGY` and `UPI/ref/ZOMATO` don't collide on the shared `UPI` token — a more sophisticated design than a naive substring match.
- New merchants start `TEMPORARY` (a guess) until approved via an admin `MerchantReviewService` — a lifecycle the initiative doesn't mention needing, but which already solves "what if the engine guesses wrong."
- Original description is never destroyed: `Transaction.description` (raw) and `Transaction.merchant` (extracted string) and `Transaction.merchantId` (resolved canonical identity) are three separate fields.
- A separate, unrelated system with a confusingly similar name exists: `MerchantTemplate` (V85+) is declarative Gmail-*receipt-email* parsing (domain → regex), not the transaction-description merchant identity system above. Any new work should not conflate the two.

**Real gap**: `StagedRow` (the DTO shown during import review, before a transaction is persisted) has no merchant field at all — normalization happens *between* staging and persistence, so the user never sees "this looks like SWIGGY" at the moment they're reviewing.

### 1.2 User learning (maps to Phase 2) — already built, and better-structured than proposed

Two distinct mechanisms exist, not the initiative's single `UserMerchantRule` table:

- **`CategoryRule`** (explicit rules, `scope ∈ {GLOBAL, USER}`, field/operator/action model, priority-ordered) — a user or admin can assert "if description contains X, assign category Y."
- **`MerchantCategoryLearning`** (implicit, statistical) — every manual correction increments a `(merchant, category)` confirmation count; `ConfidenceEngine.topCategory()` picks the pair with the most confirmations as the learned suggestion, with a cached confidence percentage.

**Precedence, already implemented exactly as the initiative asked for** (`CategorizationService.suggest()`):
1. USER `CategoryRule` (priority order)
2. GLOBAL `CategoryRule` (priority order)
3. `MerchantCategoryLearning` top category
4. Static keyword table (`CategoryRules`)
5. "Other" default

**Real gap**: the async learning pipeline (`MerchantLearningEventPublisher`/`Worker`, deliberately post-commit to avoid a single race rolling back a whole import) is correct engineering but invisible to the user — nothing in the UI tells them "this correction will apply to future Swiggy transactions" except one static sentence in `AskOnceCard`, and the general edit modal (`EditTransactionModal` in `Ledger.tsx`) gives no signal either way.

### 1.3 Bulk categorization (maps to Phase 3) — backend complete, frontend absent

This is the most actionable finding in this audit. `TransactionService.bulkRecategorize(userId, ids, categoryName, actingAdminId)` and `bulkDelete` are fully implemented, tested (including a dedicated `BulkRecategorizeLearningIT`), exposed at `POST /transactions/bulk-category`, capped at 500 ids, and even queue merchant-learning events on commit. The frontend API client stub (`bulkRecategorize()` in both `frontend/src/api/endpoints.ts` and `mobile/src/api/endpoints.ts`) exists — **and has zero call sites anywhere in the frontend or mobile codebases.** No component calls it. No multi-select UI exists to feed it ids.

Separately, **no merchant-grouping concept exists at all** — no `TransactionGroup` entity/table anywhere in the backend, no grouping API, no "5 similar transactions found" UI. The one thing that *does* group by merchant internally, `RecurringService.detectForUser()`, does so only for its own pattern-detection and doesn't expose the grouping.

**This is the direct fix for the pain point in the initiative's own example** (20 Swiggy/Zomato/Uber/Amazon transactions each needing individual review) and requires no new backend categorization logic — only: (a) a grouping endpoint/view over already-existing merchant identities, and (b) a frontend multi-select UI wired to the already-existing `bulk-category` endpoint.

### 1.4 Confidence (maps to Phase 4) — a real signal exists, but isn't the shape proposed

`MerchantCategoryLearning.confidence` (a merchant/category pair's share of total confirmations, recomputed by `ConfidenceEngine`) plus a per-user `autoApplyConfidenceThreshold` (`WorkspaceSettings`, default 90) already implement "high confidence → auto-apply" as a real, working mechanism — not the reasons-list UI the initiative sketches ("✓ User previously selected, ✓ Same merchant, ✓ 12 previous transactions"), but the same underlying decision.

`Transaction.decisionSource` (`GLOBAL_RULE | USER_RULE | LEARNED_PATTERN | KEYWORD_MATCH | MERCHANT_DEFAULT | MANUAL | FILE_PROVIDED`) is explicitly documented in code as **explainability, not a decision input** — this is the field that could back a "why was this categorized this way" UI without needing new backend state.

**Real gap**: confidence is deliberately single-signal by design (confirmation history only; the class doc explicitly defers richer signals like description similarity or amount patterns "until proven necessary") and lives only on the merchant/category pair — never surfaced per-transaction in any UI. There is no reasons-list, no confidence percentage shown anywhere a user can see.

### 1.5 Transaction type intelligence (maps to Phase 5) — partially built, genuinely fragmented

- Income/Expense: `Transaction.Type` enum — binary only.
- Transfer, Refund, Duplicate: modeled as `ReconciliationStatus` (a *third*, separate enum) plus dedicated boolean/pointer fields, detected by `ReconciliationService` in three passes (exact-match duplicate, amount+day-window transfer, keyword-seeded refund-reversal).
- Investment, Subscription: **not first-class types at all** — both are just `CategoryRule.ActionType` outcomes that resolve to a `Category`, e.g. `MARK_INVESTMENT` overwrites `categoryId` to an "Investments" category.
- Cash Withdrawal: no dedicated detection anywhere.

**Real gap, and a genuine design question for Phase 1 of any new work**: there are currently *three* separate, uncoordinated concepts doing type-like work (`Transaction.Type`, `ReconciliationStatus`, and category-as-type via rules) rather than one unified `TransactionType`. Unifying them is a real architecture decision with migration implications, not a green-field build.

### 1.6 Recurring/subscription detection (maps to Phase 6) — already built, matches the vision closely

`RecurringService.detectForUser()` already does same-merchant + amount-consistency (±20%) + interval-regularity (±35%) grouping, requires ≥3 occurrences, labels the cadence (Weekly/Biweekly/Monthly/Quarterly), and predicts a next-occurrence date — essentially the exact Netflix example in the initiative's Phase 6. It runs after every import/create/update/delete and only writes changed flags. A second, independent signal (`CategoryRule.ActionType.MARK_SUBSCRIPTION`) lets a rule assert subscription status on a single occurrence without waiting for the pattern to repeat.

**Real gap**: none structurally — this phase is close to done. The only missing piece relative to the initiative's framing is user-facing surfacing (a "7 active subscriptions" summary view, waste detection) — that's Phase 9 premium territory, not a Phase 6 gap.

### 1.7 Context intelligence (Phase 7), AI layer (Phase 8), premium features (Phase 9)

Not built, and this audit found no partial infrastructure for any of them. These remain genuinely future work, not rediscovery.

---

## 2. Missing capability list (ranked by leverage, not by phase number)

1. **Frontend bulk-categorization UI wired to the existing `bulk-category` endpoint** — no new backend work, highest-leverage fix for the stated pain point.
2. **A merchant-grouping view/endpoint** (new — no `TransactionGroup` concept exists) to identify "these N staged or existing transactions share a merchant" before bulk action.
3. **Merchant identity surfaced during import review** — `StagedRow` needs a merchant field so users see "SWIGGY" instead of raw bank text before confirming.
4. **A visible confidence/reasons UI**, backed by existing `decisionSource` + `MerchantCategoryLearning.confidence` data — no new scoring logic needed, just exposing what's already computed.
5. **An explicit "apply to future transactions from this merchant" control** at the point of correction — today this is implicit and undisclosed everywhere except one static sentence in `AskOnceCard`.
6. **A unified `TransactionType` design decision** — reconciling `Transaction.Type`, `ReconciliationStatus`, and category-as-type (Investment/Subscription) into one coherent model, or explicitly deciding to keep them separate with documented reasoning.
7. **Cash withdrawal detection** — genuinely absent.
8. **Context-beyond-merchant classification** (Amazon Prime vs. Amazon Retail) — genuinely absent, would need description+amount+frequency composite signals the current keyword/rule model doesn't attempt.
9. **Unknown-merchant AI-assisted classification** (Phase 8) — genuinely absent; correctly sequenced last per the initiative's own "avoid premature AI" principle, which matches this codebase's existing bias (no LLM use anywhere in the categorization pipeline today).

---

## 3. Recommended implementation order

Given the above, the phase order that minimizes risk and maximizes early value is **not** the initiative's original 0→9 sequence, because several "phases" are already done and one un-numbered gap (frontend bulk UI) dominates value:

1. **Wire the existing bulk-recategorize backend to a new frontend multi-select UI** (addresses the literal pain-point example with the least new code and the least new risk — reuses fully-tested backend logic).
2. **Add merchant-grouping** (new, small: a read endpoint grouping a user's staged or reviewable transactions by `merchantId`) to feed the bulk UI with "5 Swiggy transactions found" groupings instead of requiring manual multi-select.
3. **Surface merchant identity + confidence in the review/ledger UI** — add `merchant` (and optionally confidence) to `StagedRow`, and build the "why was this categorized" explainability view from existing `decisionSource`/`MerchantCategoryLearning` data.
4. **Make the future-transactions-learning consequence explicit** in both `AskOnceCard` and `EditTransactionModal` — a UI/UX change with a small backend flag (or reuse of existing learning triggers), not new categorization logic.
5. **Unify or explicitly document the `TransactionType` fragmentation** — a design decision to make deliberately, likely requiring a migration, before adding Cash Withdrawal or any other new type.
6. **Cash withdrawal detection** and **context-beyond-merchant classification** — genuinely new capability, best sequenced after the above so they're built against a settled type model rather than the current fragmented one.
7. **AI-assisted unknown-merchant classification (Phase 8)** — last, per the initiative's own stated principle, and consistent with this codebase's existing no-LLM-in-categorization posture.
8. **Premium features (Phase 9)** — deferred, out of scope until the above lands and is used.

This order deliberately front-loads near-zero-new-backend-risk UI work (steps 1–4) before any schema-changing design decision (step 5) or genuinely new detection logic (steps 6–7).

---

## 4. Database changes required

Given how much already exists, the required schema changes are smaller than the initiative's proposed `UserMerchantRule` table:

- **None** for bulk categorization (step 1) or merchant-grouping-as-a-read-view (step 2, if implemented as a query over existing `merchant_id`, not a new stored grouping).
- **`staged_row` DTO change, not a schema change**, for surfacing merchant identity during review (step 3) — this is an in-memory staging shape, not a persisted table.
- **Possibly a small addition** to `transactions` or a join for an explicit "apply to future" flag/audit trail (step 4) — needs design, not assumed here.
- **A real migration** for step 5 (unifying `TransactionType`) — likely a new enum column or a mapping migration from the existing three concepts, with backfill for existing rows. This is the one step in this plan that touches production data shape and needs its own design doc and careful rollout (feature-flagged, per this codebase's existing pattern with `RECURRING_DETECTION_ENABLED`).
- **New columns/table** for Cash Withdrawal detection if it needs persisted state beyond a category/rule outcome (step 6) — likely follows the existing `ReconciliationStatus`-style pattern rather than needing a new paradigm.

No new migration should touch `merchants`, `merchant_aliases`, `merchant_category_learning`, or `category_rules` — those tables and their precedence logic are sound as-is per this audit.

---

## 5. Risks and tradeoffs

- **Biggest risk is scope duplication, not missing capability.** The single most important outcome of this Phase 0 audit is preventing a rebuild of merchant intelligence, learning rules, confidence scoring, or recurring detection that already exist and are already tested. Any Phase 1 implementation plan must explicitly reference and reuse `MerchantNormalizationEngine`, `CategorizationService`, `ConfidenceEngine`, and `RecurringService` rather than introducing parallel concepts.
- **The `TransactionType` unification (step 5) is the one genuinely risky piece** — it touches a live production schema with three overlapping concepts and real user data. It should get its own design review and feature flag, not be bundled into the same PR as UI wiring work.
- **The bulk-categorization backend has been tested but never used in production via the frontend** — wiring it up for the first time real users will exercise means it should get renewed manual QA and monitoring even though the code itself is old and tested, since "tested but never called" is a different risk profile than "tested and battle-worn."
- **Confidence-surfacing (step 3) risks user confusion if done naively** — `MerchantCategoryLearning.confidence` is a narrow, single-signal number (confirmation-share) by explicit design; presenting it with the initiative's proposed rich "✓ reasons" UI risks implying a sophistication the underlying signal doesn't have. Any confidence UI should accurately reflect what's actually being measured.
- **MerchantTemplate naming collision** — the existing `MerchantTemplate` (Gmail receipt-email parsing) and any new merchant-identity UI work risk confusing engineers and product copy if not clearly distinguished in both code and user-facing language.
- **Frontend/mobile duplication** — `frontend/src/lib/importReview.ts` and `mobile/src/lib/importReview.ts` are separately maintained mirrors; any new review-flow logic (merchant display, bulk UI) will need to be built and kept in sync twice, or the duplication itself should be flagged as a separate concern.

---

## Next step

Per the initiative's own instruction: **no implementation begins until this Phase 0 audit is reviewed and approved.** Awaiting direction on which item in §3's recommended order to start with — my recommendation is item 1 (frontend bulk-categorization UI), since it requires no new backend code, no schema changes, and directly resolves the pain point the initiative opens with.
