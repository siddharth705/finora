# Transaction Intelligence Engine — Phase B.0/B.1 Audit (Learning Feedback & Confidence Explanation)

**Status:** Investigation only. No code changed. Written for approval before any Phase B implementation.
**Scope:** Backend (`backend/src/main/java`) and frontend (`frontend/src`) as it exists today, against the Phase B.0 (learning feedback) and B.1 (confidence explanation) questions raised for this phase.

## Headline finding

Same pattern as the Phase 0 audit: **most of what Phase B.0/B.1 set out to investigate already exists and is wired end to end.** The learning feedback loop is real, live, and per-user — a correction made this month measurably changes next month's suggestion for the same merchant, with no caching or snapshotting to go stale. A full "why was this categorized this way?" explanation feature (`TransactionExplanationService` + `Ledger.tsx`'s `ExplanationModal`) already shipped in PR #129, predating this audit request.

What's actually missing is one specific, well-bounded gap, and the codebase already names it precisely in its own comments: **a real numeric confidence score is computed and stored (`MerchantCategoryLearning.confidence`, a genuine percentage) but never leaves that table.** It doesn't reach `Transaction`, `TransactionExplanationDto`, `StagedRow`, or any frontend UI. And a user-facing setting for it — the auto-apply confidence threshold slider on the Settings page — is fully built, persisted, and editable, but **is not wired to any actual categorization decision.** `ConfidenceEngine.meetsAutoApplyThreshold` has zero callers.

There is no case here for building new tables or services. The next phase, if pursued, is a single well-scoped wiring change — not a new subsystem.

---

## B.0 — Learning Feedback Audit

### 1. What happens when a user changes a category?

`TransactionService.updateCategory` sets `decisionSource = MANUAL`, clears `decisionRuleId`, then calls `categorizationService.learn(userId, description, categoryId)` → `MerchantLearningService.confirm(...)`, which persists to `merchant_category_learning`. A separate `AuditService` entry is also written (general activity log, unrelated to learning). The same `learn()` path fires from manual category entry at transaction creation and from the merchant-review confirm flow. Bulk recategorization and import confirmation use a **queued** counterpart (`queueLearning`, applied post-commit by `MerchantLearningEventWorker`) specifically so one lost race on the learning table's unique constraint can't roll back an entire statement's import (Bug 02) or a whole bulk operation — a real, already-solved blast-radius problem, not an oversight.

**Verdict: real, stored, per-user. Nothing missing here.**

### 2–3. `MerchantCategoryLearning` — fields and multi-category support

One row per `(user_id, merchant_id, category_id)` (unique constraint), with:
- `confirmationCount` — raw evidence (how many times this pair was confirmed)
- `confidence` — that pair's cached share of the merchant's total confirmations across *every* category it's ever been assigned, recomputed on every confirmation
- `lastConfirmedAt`, `createdAt`, `updatedAt`

A merchant genuinely holds multiple simultaneous rows — Swiggy can be Food ×15 (confidence ≈94%) and Shopping ×1 (≈6%) at the same time, exactly the shape the original prompt asked about. `MerchantLearningService.confirm()` increments the matching pair (or creates it), recomputes confidence for *every* pair belonging to that merchant (since it's a share of the total), and logs a `MerchantLearningAudit` row tagged `CORRECTED` or `LEARNED` depending on whether the confirmed category matches what was previously top.

**Verdict: not "SWIGGY → Food" (single mapping) — a real per-category confirmation distribution. This is more than the prompt assumed might exist.**

### 4. `ConfidenceEngine.topCategory` — how the top pick is chosen

Sorts by `confirmationCount` ascending (stable), tie-broken by `lastConfirmedAt`, returns the last (highest) element — i.e., most-confirmed category wins, with a deliberate, documented tie-break history (a prior version had a real bug here: `Stream.max()`'s first-element-wins tie behavior silently depended on DB retrieval order). Separately, `recomputeDistribution` computes the actual percentage: `round(confirmationCount × 100 / total)`. **This percentage is real math, not a placeholder** — but `topCategory` (what `CategorizationService.suggest()` actually calls) discards it and returns only the winning category, never the percentage.

### 5 / 5b. Is the feedback loop actually live? Is it per-user?

Confirmed live, not dead code: `CategorizationService.suggest()`/`suggestReadOnly()` query `MerchantCategoryLearning` fresh on every call — no snapshot, no cache — so a correction made in month 1 is visible to a categorization decision made in month 2 the moment it's queried. `MerchantLearningService.reset()`'s own doc comment confirms this explicitly ("suggest() reads the distribution live... nothing else needs to change"). Every query is keyed by `userId` — confirmed via the entity's required `userId` column and the unique constraint including it. **Learning is strictly per-user, never global, and the loop genuinely closes.**

**B.0 conclusion: nothing to build. The mechanism the prompt worried might not exist — "does a correction actually change future suggestions?" — is real, tested, and already shipped.** The one soft gap: nothing in the UI tells a user *at correction time* that their edit will affect future imports of that merchant (Phase 0's finding here still stands — `AskOnceCard` has one static sentence, the general `Ledger.tsx` edit flow has none). That's a small, optional UX addition, not a backend gap.

---

## B.1 — Confidence Explanation Audit

### 6. `Transaction.DecisionSource` — full enum and triggers

```
GLOBAL_RULE, USER_RULE, LEARNED_PATTERN, KEYWORD_MATCH, MERCHANT_DEFAULT, MANUAL, FILE_PROVIDED
```
Set exhaustively in `CategorizationService` (rule match → `*_RULE`; non-empty learning distribution → `LEARNED_PATTERN`; keyword table hit → `KEYWORD_MATCH`; fallback → `MERCHANT_DEFAULT`) and in `TransactionService` (any explicit user edit → `MANUAL`). `FILE_PROVIDED` maps from an imported file's own category column. Every category decision in the codebase already carries one of these seven values — this is the "what signals exist" list the original prompt asked to find, and it already exists in full.

### 7. Is there a numeric confidence score anywhere in the live pipeline?

One exists (`MerchantCategoryLearning.confidence`, described above) but it is **structurally isolated** — computed and stored purely for `MerchantLearningService`'s own bookkeeping and audit trail, never read by `CategorizationService.suggest()`/`suggestReadOnly()`, never attached to `Transaction`, never returned in `TransactionExplanationDto`, never present on `StagedRow`. `DecisionSource` — an enum, not a score — is the only thing that reaches the explanation/display layer today.

### 8. What does `StagedRow`/`Transaction` expose about "why" today?

`StagedRow.confidence` and `StagedRow.merchantConfidence` both exist but are **not** category-decision confidence: the former is Gmail-receipt extraction reliability (per its own doc comment, null for every CSV/PDF row), the latter is merchant-identity resolution confidence from Phase A. `StagedRow.categorySource` + `ruleId` are the only category-decision signals staging carries. `Transaction` itself carries `decisionSource` + `decisionRuleId`, no numeric field at all.

### 9. What does the frontend show today?

- `Import.tsx`: a "low confidence" badge, but derived purely from `categorySource === 'default'` — a boolean flag, not a score.
- `Import.tsx`: a genuine percentage badge exists, but it's Gmail **product-detection** confidence, unrelated to category decisions.
- **`Ledger.tsx`'s `ExplanationModal`** (backed by `GET /transactions/{id}/explanation`) is a real, already-shipped "Why this category?" panel — this already answers the prompt's aspirational mockup ("✓ You categorized SWIGGY as Food before, ✓ Merchant matched") in prose form, evidence-list style, keyed off `decisionSource`. It does not show a percentage.

**No numeric percentage for category-decision confidence exists anywhere in the frontend.**

### 10. Existing "explain this decision" pattern to reuse

`TransactionExplanationService.explain()` (PR #129, `git log c69dd7b7`) already is this pattern: a `switch` on `decisionSource` producing a human-readable summary plus an evidence-string list, resolving the actual matched `CategoryRule` and rendering its condition/action in plain English when relevant. Its own doc comment explicitly cross-references the two sibling "evidence, not a new guess" patterns already established elsewhere in this codebase: `GmailReviewItemDto.reasoning` and `DuplicateMatch`'s evidence list on `StagedRow`. Any confidence-percentage work should extend this DTO and service, not create a parallel one.

**B.1 conclusion: the explanation UI the prompt asked to build already exists.** The only real, substantiated gap is narrower and more concrete than "build confidence explanations" — it's this:

## The actual gap (self-documented in the codebase)

`WorkspaceSettingsService`'s own doc comment states it outright:

> "This threshold is NOT currently wired into any live categorization decision. `ConfidenceEngine.meetsAutoApplyThreshold(int, int)` exists as a hook for exactly this purpose but has zero callers today — confidence itself isn't threaded through `CategorizationService`'s `Suggestion` record or either write path... at all yet... Wiring this setting into real behavior would mean adding a confidence field to `Suggestion` and touching ~20 call sites across 3 files."

Confirmed independently: the Settings page (`frontend/src/pages/Settings.tsx`) ships a real, persisted auto-apply-confidence-threshold slider (default 90) that a user can see and change today — and it currently does **nothing**. That's the concrete, user-visible gap: a control that looks functional and isn't.

## Gap report

| Capability | Status |
|---|---|
| Store user correction | ✅ (per-user, `MerchantCategoryLearning`) |
| Multiple categories per merchant, weighted by evidence | ✅ |
| Reuse correction in future suggestions | ✅ (live query, no caching) |
| Explain decision source (7-way enum + rule detail) | ✅ (`TransactionExplanationService`, PR #129) |
| Numeric confidence % computed | ✅ (`ConfidenceEngine.recomputeDistribution`) — but isolated to the learning table |
| Numeric confidence % surfaced (Suggestion/Transaction/StagedRow/UI) | ❌ |
| Auto-apply threshold setting — persisted & user-editable | ✅ |
| Auto-apply threshold setting — wired to any decision | ❌ (zero callers) |
| Proactive "this will apply to future imports" feedback at correction time | ❌ (Phase 0 finding, still open, cosmetic) |

## Implementation proposal (only if you want to close the gap)

This is **not** a proposal to build `CategoryDecisionEvidence` or any new table — the evidence model (`DecisionSource` + rule detail) already exists and works. The real, scoped work is threading the *existing* confidence number through:

1. Add a `confidence: Integer` field to `CategorizationService.Suggestion` (currently `category, source, merchantId, decisionSource, ruleId`), populated from `ConfidenceEngine`'s already-computed percentage when the source is `LEARNED_PATTERN`, and from `initialConfidence()`'s existing 70/20 constants for rule/keyword/default sources (already computed, already unused elsewhere).
2. Thread it into `Transaction` (new column) and `StagedRow` (new field) the same way `decisionSource`/`categorySource` already travel from suggestion through staging to persisted row — this touches the ~20 call sites `WorkspaceSettingsService`'s comment already estimates, concentrated in `CategorizationService`, `TransactionNormalizer`, and `ImportService`.
3. Wire `ConfidenceEngine.meetsAutoApplyThreshold(confidence, settings.autoApplyConfidenceThreshold)` into the one place it was always meant for — likely gating `needsCategoryReview` at confirm time, replacing or supplementing the current "default source only" review-flag logic.
4. Add the percentage to `TransactionExplanationDto` and render it in the existing `ExplanationModal` — no new frontend component needed, one new field in an existing panel.
5. Decide (product call, not an engineering one): does staging/review time show the percentage too, or only the post-confirm explanation panel? The Gmail-receipt confidence badge in `Import.tsx` already establishes a visual precedent for a percentage badge if you want staging-time visibility.

This is meaningfully smaller than the original Phase B.0/B.1 brief assumed, because the brief's premise — "we probably have some of this, need to map it" — undersold how much was already built. No new backend service, no new database table beyond one or two columns, no new frontend page. It's a threading exercise through an already-correct pipeline.

## What I'd recommend against

- Do not build a second "why" UI — extend `TransactionExplanationDto`/`ExplanationModal`.
- Do not build a second confidence model — `ConfidenceEngine.recomputeDistribution` is already real, evidence-based math; the gap is plumbing, not modeling.
- Do not touch `meetsAutoApplyThreshold`'s semantics without deciding what "auto-apply" means in the review-flag/needs-review context first — that's a product decision (does hitting the threshold skip review entirely, or just remove the "low confidence" badge?), not something to infer from the code.
