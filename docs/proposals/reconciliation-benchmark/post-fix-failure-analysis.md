# Post-Fix Failure Analysis & Next Target Recommendation

**Status:** Analysis only. Nothing in this document has been implemented.
**Baseline this analyzes:** the benchmark state *after* roadmap item #1 (transfer keyword-gate
widening, PR #1082) — 43/59 passed (72.9%), 53% on transfers — not the original 18-failure report.

---

## 1. Current benchmark, re-measured

```
Overall:            72.9% (43/59)
Transfer accuracy:  53% (9/17)
Remaining failures: 16
```

## 2. Remaining failures, grouped by root cause

Grouped from the actual `mvn test` output (re-run for this analysis), not from memory or the
original report's estimate:

| Root cause | Scenarios | Count |
|---|---|---|
| Missing transfer vocabulary (RTGS, bare self-transfer, wallet, "auto debit") | `upiSelfTransfer`, `rtgsTransfer`, `walletFunding`, `savingsToCreditCardAutoDebit` | **4** |
| First-match-wins transfer/refund-adjacent selection | `multipleCandidateMatches`, `ambiguousTransferSelection` | **2** |
| Duplicate SIP/EMI undiscriminated merging | `sipInstallments`, `emiPayments` | **2** |
| Cross-description duplicate matching | `nearDuplicate` (reference-number match), `manualEntryThenImport` (design question, see audit) | **2** |
| Gmail matcher gaps | `merchantAlias` (alias lookup), `falsePositive` (short-token) | **2** |
| Split-transfer (many-to-one) | `splitTransfer` | **1** |
| Transfer amount tolerance too tight | `transferWithRealisticFee` | **1** |
| Investment keyword word-boundary fusion | `brokerFundingWithFusedKeyword` | **1** |
| CC-payment late-window (see audit's scoring-consistency note) | `latePayment_beyondTenDayWindow` | **1** |
| **Total** | | **16** |

**This corrects the illustrative grouping in the prompt that kicked off this analysis** — the real
data shows 4 missing-vocabulary failures (not fewer) and only 2 first-match-wins failures (not 4),
and surfaces a "cross-description duplicate matching" cluster the illustrative grouping didn't
name at all. Worth saying plainly: **first-match-wins is not the largest remaining cluster.**
Missing transfer vocabulary is, by a clear margin (4 vs. 2).

## 3. Ranked by ROI

Scored on four axes per the brief — engineering effort, false-positive risk, benchmark impact
(scenarios closed), real-world likelihood (grounded in the corpus check where available, in the
audit's reasoning otherwise). The missing-vocabulary cluster is split into two sub-groups because
its risk is not uniform — that distinction is the main finding of this section.

| # | Fix | Effort | FP risk | Benchmark impact | Real-world likelihood | Evidence quality |
|---|---|---|---|---|---|---|
| 1a | Add `"rtgs"` and an explicit self-transfer phrase to the gate | **Low** (same mechanism as PR #1082) | **Low** (both terms are narrow, transfer-specific) | 2/16 | **High** (corpus-confirmed vocabulary rarity) | Strong (direct corpus measurement) |
| 1b | Add `"wallet"` / `"auto debit"` to the gate | Low | **Medium-high** — "auto debit" in particular would misfire on genuine EMI/insurance auto-debits, which must NOT be marked transfers | 2/16 | Medium | Weak — not corpus-validated, and collides with the very domain (EMI) this project has already deliberately deferred (§ dead-relationship-types audit) |
| 2 | Investment keyword word-boundary fusion fix | Low-Medium (needs a per-keyword-length policy, not a blanket regex loosen — loosening word-boundary matching universally reintroduces the exact false-positive risk `CategoryRules` was built to avoid, per its own documented "emi"/"ngo" exclusion) | Low, if scoped to long/specific brand tokens only | 1/16 | **High — 42% measured directly** in this project's own real corpus | **Strongest direct evidence of anything in this table** |
| 3 | First-match-wins → best-candidate selection | **Medium** (real algorithmic change: collect candidates instead of breaking on first, rank by proximity) | Low (doesn't broaden what triggers evaluation, only which candidate wins among what's already gated) | 2/16 | Medium (real, but needs a coincidental second candidate — not measured) | Moderate (reasoned, not corpus-validated) |
| 4 | SIP/EMI undiscriminated-duplicate guard | Low-Medium | Low (narrowly scoped, doesn't touch the working discriminator path) | 2/16 | Medium-low (conditional on *both* balance and reference being absent, which most real statements in this project's corpus already provide) | Moderate |
| 5 | Cross-description duplicate — reference-number sub-case only | Medium (new signal: match on an embedded shared reference/UTR number, not full fuzzy text) | Low-medium | 1/16 (the `nearDuplicate` scenario only — `manualEntryThenImport` is a design question per the audit, not a clean bug) | Medium-high (a well-known real UPI pattern) | Moderate |
| 6 | Gmail short-token false-positive guard | Low | Low-medium (raising the bar risks new false negatives on short real brand names) | 1/16 | Low overall reach (Gmail Sync adoption is still early), but high severity per incident (actively wrong, not just missing) | Weak (not corpus-measurable) |
| 7 | Gmail merchant-alias table integration | Medium | Low | 1/16 | Low (same adoption gate as #6) | Weak |
| 8 | Transfer amount tolerance widening | Low, but unsafe without an evidence-aware guard | Medium if done as a flat increase | 1/16 | Medium | Weak |
| 9 | Split-transfer many-to-one aggregation | **High** (different, combinatorial matching model) | **High** | 1/16 | Unmeasured | None — explicitly deferred already, no new evidence |
| — | CC-payment late-window | Low, but should not ship alone (see audit) | Medium if widened without better tiebreak evidence | 1/16 | Medium | Weak; also flagged as inconsistently scored against two sibling window-boundary cases — a policy decision, not a queued fix |

## 4. Recommendation: exactly one next implementation target — ✅ IMPLEMENTED

**Recommended and implemented: #1a — added `"rtgs"` and `"self transfer"` to `CategoryRules`'
`"Transfer"` keyword list**, which `ReconciliationService`'s transfer pass already reads via
`suggestCategory()` (the mechanism PR #1082 wired in). No `ReconciliationService` change was needed
this time — the entire fix is the two new keywords in `CategoryRules`, which benefits
categorization and reconciliation identically, for free.

**Re-measured result:**

```
Before this change:  72.9% overall (43/59), 53% transfers (9/17)
After this change:   76.3% overall (45/59), 65% transfers (11/17)
```

`rtgsTransfer_noPaymentKeyword_missed` and `upiSelfTransfer_noPaymentKeyword_noRelationshipConfigured_missed`
both now pass, exactly the 2 scenarios this fix targeted — nothing else moved, as expected (this
was a narrow, additive change). Full regression check
(`ReconciliationServiceTest`, `ReconciliationEndToEndTest`, `CategoryRulesTest`,
`PersonToPersonTransferDetectorTest`, `RuleServiceTest`, `RuleEngineServiceTest`, and the whole
`com.finora.service.*Test`/`com.finora.transactions.*Test`/`com.finora.util.*Test` surface) is
unaffected.

**14 failures remain** (down from 16, down from the original 18): the same §2/§3 grouping and
ranking above still applies to what's left, minus the two closed scenarios — the "missing transfer
vocabulary" cluster is now 2 (`walletFunding`, `savingsToCreditCardAutoDebit`), both deliberately
left unfixed this round for the reasons already given in §3's row 1b: `"wallet"` and `"auto debit"`
are broader, higher-collision-risk terms than `"rtgs"`/`"self transfer"` were, and `"auto debit"`
specifically would misfire on real EMI/insurance auto-debits.

The original reasoning for this pick (kept for the record, since it's what justified making this
change before it was made):

This is a close call against #2 (investment word-boundary fusion) and #3 (first-match-wins) — all
three score well. The reasoning for picking #1a specifically:

- **It is the same proven, already-shipped mechanism**, extended incrementally rather than a new
  one. Zero new design risk beyond "which two words to add," and the two words proposed (`"rtgs"`,
  `"self transfer"`) are both narrow and transfer-specific — neither is a generic term that
  legitimate non-transfer spend would plausibly contain (contrast with `"wallet"`/`"auto debit"`,
  explicitly *not* recommended here, see the table).
- **It closes the single largest remaining cluster's safest half** — 2 of the 4 missing-vocabulary
  failures, without touching the two that carry real collision risk with domains this project has
  already deliberately deferred (EMI detection specifically — see the companion audit below).
- **Directly evidenced**, not guessed: the same corpus check that justified PR #1082 (transfer-rail
  lines almost never contain "payment") applies identically here.

**Why not #2 (investment fusion) despite having the single strongest evidence (42%):** it is a real
and well-evidenced fix, and should be next in the queue right after this one — but it touches
`CategoryRules`' shared keyword-matching mechanism (used by every category, not just Transfer/
Investments), so a safe implementation needs a real design decision (which keywords are long/
specific enough to relax word-boundary matching for) rather than a one-line gate extension. Slightly
more design surface than #1a for a smaller (1-scenario) direct benchmark gain — recommended as the
**second** target, immediately after this one.

**Why not #3 (first-match-wins) despite matching the original hypothesis this analysis was asked to
check:** the post-fix data does not support it being the top pick. It closes only 2 of 16 remaining
failures (not "multiple," and fewer than the vocabulary cluster), and is a materially larger
engineering change (a real candidate-scoring algorithm, not a keyword addition) for a comparable
benchmark gain. Still a good third target — its risk profile is excellent (it improves selection
quality without broadening what triggers matching at all) — just not the single best next step by
the numbers.

**What this recommendation explicitly avoids:** no scoring engine, no ML, no user-learning, no
architecture change, no split-transfer aggregation, no confidence UI — per the brief. This is one
more small, evidence-backed vocabulary extension, re-measured before deciding what comes after it.
