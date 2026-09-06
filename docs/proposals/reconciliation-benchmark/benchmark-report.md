# Reconciliation Engine: Measured Baseline & Improvement Roadmap

**Status:** Baseline measured, benchmark audited, real-corpus frequency check done, roadmap item #1
implemented and re-measured. See `benchmark-audit.md` (Step 1: benchmark quality audit) and
`corpus-frequency-analysis.md` (Step 2: real-corpus frequency evidence) for the two follow-up
analyses that informed the change below — both were done, and both are reflected in this document,
before any other reconciliation logic changed.
**Scope read:** `ReconciliationService`, `ReconciliationPolicy`, `TransactionGraphService`,
`RelationshipService`, `TransactionRepository`, `GmailReconciliationMatcher`,
`BalanceChainValidator`, `CreditCardFlowReconciliationValidator`, `ConfidenceScorer`,
`CategoryRules`, `DuplicateMatching`.
**Benchmark:** 59 scenarios across 6 categories, executed against the real
`ReconciliationService` code path (mocked persistence only — see `README.md`).
**Implemented:** roadmap item #1 only (transfer keyword-gate widening) — see §7. Nothing else in
this report has been implemented.

---

## 1. Executive summary

| | Before | After item #1 |
|---|---|---|
| Scenarios run | 59 | 59 |
| Passed | 41 (69.5%) | 43 (72.9%) |
| Failed (real, evidenced gaps) | 18 (30.5%) | 16 (27.1%) |

(The audit in `benchmark-audit.md` separately argues one of the 18 original failures —
the CC-payment late-window case — was scored inconsistently with two sibling findings that were
*not* counted as failures; see §8. That reclassification is a scoring correction, not a code
change, and is kept out of the headline numbers above so this table stays a direct, literal
`mvn test` before/after comparison.)

Failures are **not evenly spread**. Before the fix, one category — transfer matching between the
user's own accounts — accounted for over half of every finding (10 of 18) and was the weakest part
of the engine by a wide margin. Refund/reversal matching, by contrast, passed all 10 of its
scenarios and is the strongest. The largest single mechanism behind the transfer failures: the
pass's "does this look like a transfer" gate checked for the literal substring `"payment"` and
nothing else, so most non-card transfer rails (UPI self-transfer, IMPS, NEFT, RTGS, wallet top-ups)
that don't happen to use that word were invisible to it.

**A real-corpus check (see `corpus-frequency-analysis.md`) confirmed this before touching any
code:** across 29 real bank statements, transfer-rail-shaped lines (NEFT/IMPS/RTGS/UPI) contained
the word "payment" only 1.1% of the time.

| Category | Scenarios | Passed (before → after) | Failed (before → after) | Pass rate (before → after) |
|---|---|---|---|---|
| Transfers | 17 | 7 → 9 | 10 → 8 | 41% → **53%** |
| Duplicate detection | 9 | 5 → 5 | 4 → 4 | 56% (unchanged) |
| Gmail cross-source matching | 7 | 5 → 5 | 2 → 2 | 71% (unchanged) |
| Investment transfers | 6 | 5 → 5 | 1 → 1 | 83% (unchanged) |
| Credit card payments | 10 | 9 → 9 | 1 → 1 | 90% (unchanged) |
| Refunds & reversals | 10 | 10 → 10 | 0 → 0 | 100% (unchanged) |
| **Total** | **59** | **41 → 43** | **18 → 16** | **69.5% → 72.9%** |

**Honest reading of this result:** the fix as implemented (see §7) closed exactly 2 of the 6
transfer-keyword-gate failures (`impsTransfer`, `neftTransfer`) — not all 6. It deliberately reused
only `CategoryRules`' already-vetted `"Transfer"` keyword list (`"neft to"`, `"imps to"`,
`"autopay"`, `"billdesk"`, plus the phrases that already contained "payment"), per this report's own
original framing of item #1 as "the smallest, lowest-risk slice." RTGS, bare "self transfer"/UPI
narrations, wallet top-ups, and "AUTO DEBIT" (as opposed to "AUTOPAY") narrations carry none of
those keywords and are still missed — this was foreseeable from reading the keyword list before
implementing, and is not a surprise; it's exactly the kind of measured, incremental result the
step-by-step plan this analysis followed was designed to produce, rather than assuming the
theoretical best case would show up in one shot.

---

## 2. How the current engine actually behaves

`ReconciliationService.reconcileForUser()` runs synchronously after every transaction create,
update, delete, import confirm, and statement delete. It loads the user's full live-account
transaction history and applies five passes in a fixed order, over the same in-memory list, ending
in one batched `saveAll` and one batched graph-edge write (`TransactionGraphService.linkAll`):

1. **Duplicates.** Groups transactions by `accountId|date|amount(normalized)|description(case/
   whitespace-folded)`. A group is further split by `balanceAfter` or, failing that,
   `referenceNumber` — but *only* when every member of the group carries that field; otherwise the
   group is left unsplit. The higher-`SourceTrust` row wins as canonical (ties broken by creation
   order). A user's explicit "not a duplicate" confirmation is permanently respected.
2. **Transfers.** Pairs an EXPENSE on one account with an INCOME on a *different* account, same
   amount (± ₹1), within a date window — 4 days by default, widened to 10 days if either side
   matches a user-configured `OWN_ACCOUNT` relationship identifier. A candidate pair is only ever
   *considered* at all if at least one side's description literally contains the substring
   `"payment"`, or matches a relationship identifier. Candidates are sorted by `(date, id)`; the
   loop takes the **first** amount/window match it finds, not the closest or most plausible one,
   and stops.
3. **Investment transfers.** A pure category gate: any EXPENSE whose description makes
   `CategoryRules.suggestCategory()` return `"Investments"` is excluded from cash flow. No pairing,
   no graph edge — Groww/Zerodha/SIP/NPS outflows never have a matching credit leg in this system.
4. **Refunds & reversals.** Same-account only (the mirror-image constraint from transfers, which
   require *different* accounts). An INCOME within 180 days *after* an EXPENSE, needs a refund/
   reversal keyword or a merchant-identity match, and can't exceed the expense's *remaining*
   capacity (tracked so two partial refunds against one purchase don't both double-claim it).
   Exact-amount matches always outrank partial ones; ties break on date-proximity, never merchant
   or reference number.
5. **Gmail cross-source matching.** For a `GMAIL_IMPORT` expense, groups same-exact-amount bank
   expenses within a 3-day window, then delegates to `GmailReconciliationMatcher`, which reduces
   both descriptions through `CategoryRules.extractMerchant` and compares every token pair by
   normalized Levenshtein similarity (threshold 0.6). Writes a graph edge only — deliberately never
   touches the legacy `isDuplicateOf`/status columns, because this is the engine's lowest-confidence
   tier by design.
6. **Credit card payments.** Links a savings-side payment to the specific charges a card statement
   billed, using the statement's extracted `totalAmountDue`/`paymentDueDate`. Two phases: every
   statement's *exact*-amount match is resolved globally first, then a second pass allows
   partial (≥5% of total) or overpayment (≤2.5x total) matches for whatever is still unsettled —
   specifically so an earlier-due-date card's wide search can't steal a payment that's actually an
   exact match for a different card. A payment whose description names a card's last-4 digits wins
   the tiebreak outright over a merely closer-to-due-date one; a payment naming a *different* known
   card's last-4 is excluded as a candidate entirely. Always writes a `CANDIDATE`-status edge, never
   `AUTO_CONFIRMED` — this pass has no destination-account-type check.

Every pass writes to two places: legacy single-pointer columns on `Transaction`
(`isDuplicateOf`/`isTransfer`/`refundOfTransactionId`, plus `reconciliationStatus`) that dashboards
and reports filter on directly, and a richer `TransactionRelationship` graph (many-to-many, carries
a 0–100 `confidence` score from `ConfidenceScorer`, a `CANDIDATE`/`AUTO_CONFIRMED` status gated at
confidence ≥80).

---

## 3. Benchmark methodology

Each of the 59 scenarios is a small, realistic Indian-bank transaction fixture, run through the
real `ReconciliationService` (mocked repositories only — see `README.md`), asserting what a
**correct** verdict is, established independently of what the code currently does. Every test's
`@DisplayName` and inline comment states, *before* execution, whether it is:

- **BASELINE (known-good)** — a passing regression anchor, so a future change can't silently break
  behavior already confirmed correct.
- **GAP** — the assertion encodes the ideal outcome; a failure is a real, evidenced defect with a
  measurable effect on totals or on which rows are excluded from cash flow.
- **BY DESIGN / documented risk** — the current, deliberate behavior IS the assertion (the test
  passes), but the scenario is kept because the code's own comments (or this analysis) flag a real
  edge-case risk worth roadmap awareness even though it isn't a code defect.
- **Documents an attribution risk, not a totals defect** — a deliberately-not-failed test: the
  aggregate numbers come out right either way, but *which* specific transaction gets credited as
  the match is unverified/arbitrary. Kept as BASELINE rather than GAP so this report doesn't
  conflate "the number is wrong" with "the explanation might name the wrong row" — see this
  project's own evidence rule (CLAUDE.md, "No guessing").

### Reading this as precision/recall

The counts below are a category-level classification of the 59 scenarios by what each one was
designed to prove (a scenario testing "should match" vs. "should not match"), not a per-transaction
audit over a real, randomly-sampled corpus. Treat the percentages as *relative signal about which
categories are weaker*, not as a claim about real-world accuracy on live user data — this benchmark
was deliberately built to go looking for edge cases, so its scenario mix is not representative of
real transaction-volume proportions.

| Category | TP | FN | FP | TN | Precision | Recall |
|---|---|---|---|---|---|---|
| Transfers | 6 | 10 | 2 | 1 | 75% | 38% |
| Duplicate detection | 3 | 4 | 0 | 2 | 100% | 43% |
| Gmail matching (matcher-only, 6 of 7 scenarios) | 3 | 1 | 1 | 1 | 75% | 75% |
| Investment transfers | 5 | 1 | 0 | 0 | 100% | 83% |
| Credit card payments | 8 | 1 | 0 | 1 | 100% | 89% |
| Refunds & reversals | ~8 | 0 | 0 | ~2 | 100% | 100% |

The pattern that matters: **every category's precision is high (75–100%) — the engine rarely
excludes real spend/income it shouldn't. Recall is what varies widely (38–100%)** — the engine's
failure mode across the board is under-matching (missing real transfers/duplicates/investment
outflows), not over-matching. The two exceptions (transfer misattribution, the Gmail short-token
false positive) are flagged individually below because they *are* over-matching, and are rarer but
more actively harmful when they happen.

---

## 4. Failure analysis

All 18 failures, grouped by root cause.

### A. The transfer pass's "looks like a transfer" gate is a single hardcoded keyword (6 failures — the largest cluster)

**Rule:** `ReconciliationService`'s transfer pass only evaluates a candidate pair if at least one
side's normalized description contains the literal substring `"payment"`. This is a hardcoded
check, not `CategoryRules.RULES.get("Transfer")`'s own keyword list (`"neft to"`, `"imps to"`,
`"autopay"`, `"billdesk"`, `"cc payment"`, `"card bill payment"`), which drives *categorization*
and is never consulted here.

**Failing scenarios:** `upiSelfTransfer_noPaymentKeyword_noRelationshipConfigured_missed`,
`impsTransfer_noPaymentKeyword_missed`, `neftTransfer_noPaymentKeyword_missed`,
`rtgsTransfer_noPaymentKeyword_missed`, `savingsToCreditCardAutoDebit_noPaymentKeyword_missed`,
`walletFunding_noPaymentKeyword_missed`.

- **Type:** False negative (the pair is never even evaluated).
- **Severity:** High. A real self-transfer is counted as both real spending AND real income —
  double-counted in both directions, not just missing from one side.
- **Real-world likelihood:** Very high. Most Indian bank/UPI narrations for a self-transfer never
  use the word "payment" — "UPI-SELF TRANSFER", "IMPS TO 9876 SAVINGS AC", "NEFT CR SELF", "RTGS
  CREDIT" are all realistic, common shapes. Most users never configure an `OWN_ACCOUNT`
  relationship (the only other way in), so this almost certainly affects the majority of self-
  transfer activity across the user base, not an edge case.
- **User impact:** Inflated spend AND income totals, wrong "cash flow" numbers, wrong dashboard
  charts, wrong monthly comparisons — this is a core-metric-correctness bug, not a cosmetic one.

### B. First-match-wins: candidates are matched in sort order, not by plausibility (2 failures, but a systemic risk beyond just these 2)

**Rule:** Both the transfer pass and (by a related mechanism, see the CC-payment ambiguous-
description finding in section 4E) the CC-payment pass stop at the *first* structurally-qualifying
candidate in `(date, id)`-sorted order. Neither ranks by temporal closeness, reference-number
agreement, or any corroborating signal.

**Failing scenarios:** `multipleCandidateMatches_firstMatchWins_picksTheWrongCandidate`,
`ambiguousTransferSelection_pickCloserCandidateOverFartherOne`.

- **Type:** Misattribution — not a pure miss. The engine DOES create a transfer link, just to the
  wrong transaction. This produces one false-positive exclusion (a coincidental, unrelated
  transaction wrongly removed from totals) *and* one false-negative (the real transfer partner
  stays counted as ordinary spend/income) from a single wrong decision.
- **Severity:** Medium-high. Two numbers are wrong per incident, not one.
- **Real-world likelihood:** Medium. Requires two same-amount candidates within the transfer
  window — plausible for round transfer amounts (₹5,000, ₹10,000) coinciding with an unrelated
  cashback/ATM withdrawal, but less frequent than the keyword-gate miss above.
- **User impact:** A specific, confusing discrepancy a support agent would have to manually trace —
  "why is this cashback missing from my income?" has no visible cause in the UI today.

### C. Same-day, same-amount, no-discriminator duplicates are wrongly merged (2 failures)

**Rule:** `splitByDiscriminator` only splits a duplicate-key-matched group by `balanceAfter` or
`referenceNumber` when *every* member of the group carries that field. When neither is captured at
all (common for minimal-column imports or a bank that omits both), two genuinely separate
same-day, same-amount transactions collapse into one.

**Failing scenarios:** `sipInstallments_sameDaySameAmountNoDiscriminator_wronglyMerged`,
`emiPayments_sameDaySameAmountNoDiscriminator_wronglyMerged`.

- **Type:** False positive (two real transactions treated as one).
- **Severity:** High per incident — a whole real transaction (a full SIP installment, a full EMI
  payment) silently vanishes from every total, with no visible flag that anything was dropped.
- **Real-world likelihood:** Medium. Requires two coincidentally-equal-amount recurring debits on
  the same day with no balance/reference captured — round SIP/EMI amounts make the coincidence more
  likely than it sounds, but it's not the common case (the balance/reference discriminator already
  handles most real bank statements, which is why this is the SAME known limitation the code's own
  `splitByDiscriminator` doc comment already names, not a novel one this benchmark discovered from
  scratch).
- **User impact:** A silently wrong balance and understated spend, discoverable only by manually
  reconciling against the bank statement.

### D. No cross-description duplicate matching (2 failures, same root cause)

**Rule:** The duplicate key is `accountId|date|amount|description`, compared via case/whitespace
folding only (`DuplicateMatching.normalizeDescription`) — no fuzzy matching, no reference-number-
only comparison across differently-formatted narrations.

**Failing scenarios:** `nearDuplicate_reformattedNarrationAcrossPendingAndSettled_isNotCaught`
(the same UPI payment narrated differently as a pending notification vs. a settled statement row),
`manualEntryThenImport_sameRealTransaction_differentDescription_notCaught` (a manually-typed note
vs. the bank's own narration for the same charge).

- **Type:** False negative.
- **Severity:** Medium — inflates spend by double-counting one real expense, but each individual
  incident is smaller in magnitude than the transfer-gate or SIP/EMI findings (a single transaction,
  not a systemic multiplier).
- **Real-world likelihood:** High for the manual-entry case specifically — this is one of the most
  intuitive things a user would expect the app to catch ("I already logged this, why did importing
  the statement create a second row?"), and is a very plausible source of real support complaints.
- **User impact:** Visible, explainable double-counting a user is likely to notice and question —
  this is as much a trust/UX finding as a numbers one.

### E. Gmail matcher: real but narrower gaps (2 failures)

1. **No merchant-alias-table lookup.** `GmailReconciliationMatcher` is pure Levenshtein edit-
   distance over raw text; it never consults the `MerchantAlias`/`MerchantNormalizationEngine`
   table this project already maintains for exactly the "known brand ↔ legal entity name" problem
   (e.g., Swiggy ↔ Bundl Technologies). **Type:** false negative. **Severity:** medium (double-
   counts one Gmail receipt against one bank import, only when both sources exist for the same
   charge). **Likelihood:** low-medium — depends on Gmail Sync adoption, a feature this project's
   own memory notes as still forming.
2. **Short-token false positive.** Two unrelated 4-letter brand tokens one Levenshtein edit apart
   (`"zoom"` vs `"room"`, similarity 0.75) clear the 0.6 threshold; `MIN_BRAND_TOKEN_LENGTH` (3)
   only screens tokens *shorter* than 3 characters, doing nothing for two same-length short words.
   **Type:** false positive — actively wrong, not just missing. **Severity:** medium — a real
   subscription and a real rent payment get cross-linked. **Likelihood:** low but not negligible;
   round subscription/rent amounts collide often enough, and short brand names (Zoom, Uber, Ola,
   Boat) are common.

### F. Investment-transfer category keyword needs a word boundary (1 failure)

A broker-funding narration with the keyword fused into a longer token
(a synthetic example modeled on this shape: `"UPI-ICCLGROWWPAY-<reference>-BSE"` — "groww" has no
boundary on either side) is invisible to
`CategoryRules.suggestCategory`'s word-boundary keyword matching. **Type:** false negative.
**Severity:** medium (inflates spend by the investment amount). **Likelihood:** low-medium —
depends on how often a payment gateway fuses the broker ID into one token with no separately-
occurring brand mention (the real corpus example this pass's own code comment cites,
`"UPI-ICCLGROWW-GROWW-BSE"`, happens to have "GROWW" occur a second time as its own token, which is
why it currently works).

### G. Credit card payment window too narrow for genuinely late payers (1 failure)

`CC_PAYMENT_DUE_DATE_WINDOW_DAYS` is 10 (reusing `OWN_ACCOUNT_MATCH_DAY_WINDOW`'s own
justification — "auto-pay early... or a few days late"). A payment 12+ days after the due date —
exactly the situation where a late fee applies and accurate tracking matters most — falls outside
it. **Type:** false negative, with real totals impact (unlike the by-design boundary findings
below): the charge stays counted as ordinary card spend AND the payment stays counted as ordinary
savings-side spend — the same money effectively counted twice. **Severity:** medium-high per
incident. **Likelihood:** medium — late credit card payments are a common real-world pattern, not
an edge case.

### Documented but not counted as failures (by-design boundaries and attribution-only risks)

These are worth roadmap awareness even though their tests pass — flagged in the code comments
where they live, not manufactured as red tests, per this benchmark's own evidence discipline (see
`refundBeyond180DayWindow_silentlyLeftAsOrdinaryIncome`, `transferBeyondDefaultWindow_...`,
`ambiguousMerchant_multipleSameAmountCandidates_attributionIsUnverified`, and
`ambiguousPaymentDescription_noCardReference_...` in the test suite):

- A refund landing more than 180 days after its purchase is silently left as ordinary income — the
  code's own `ReconciliationPolicy` doc comment already names this as "the one threshold where being
  too narrow fails silently," with no user-visible signal today.
- A self-transfer settling more than 4 days apart, with no `OWN_ACCOUNT` relationship configured,
  is correctly not matched by policy — but since most users never configure that relationship, this
  interacts with finding A above and is effectively part of the same underlying exposure.
- When two purchases at the same merchant, same amount, sit in a refund's matching window, the
  refund is attributed to whichever is temporally closer, with no reference/order-number
  verification. Totals are correct either way; which specific purchase is named as refunded is not.
- The same shape of attribution ambiguity exists in the CC-payment pass when two cards have similar
  (not identical) dues and the payment description carries no card reference — whichever card is
  processed first (earlier due date) claims the payment.
- A chargeback is classified `REFUND`, not `REVERSAL` — a deliberate, documented choice in the code,
  arguably debatable (a chargeback is bank/network-adjudicated, closer in kind to a reversal), but
  with zero difference in aggregate treatment either way.

---

## 5. Prioritized improvement roadmap

Ranked by ROI (frequency × severity of the evidenced gap, divided by estimated engineering effort),
using only the findings above — nothing here is speculative.

> **Status update (post-audit, post-corpus-check, post-implementation):** item #1 below is
> **implemented** — see §7 for the exact change and §1 for the measured before/after numbers. Item
> #8 (investment keyword fusion) was **re-prioritized upward**, from #8 to roughly #3–4, after the
> real-corpus check in `corpus-frequency-analysis.md` measured a 42% real-world miss rate, well
> above this report's original "low-medium likelihood" guess. Every other item's ranking is
> unchanged and still awaits its own frequency evidence before implementation, per the staged plan
> this analysis followed (audit → corpus check → smallest fix → re-measure, before any further
> work).

### 1. Widen the transfer pass's transfer-detection gate beyond the literal `"payment"` substring — ✅ IMPLEMENTED
- **Addresses:** Finding A (6 of 18 failures — the single largest cluster).
- **Effort:** Low. Reuses `CategoryRules.RULES.get("Transfer")`'s existing keyword list instead of
  the hardcoded literal; a small, localized change to one boolean expression.
- **Complexity:** Low.
- **Risk:** Low-medium. Broadening the gate could increase false-positive transfer matches (e.g., a
  genuine NEFT bill payment to a third party, not a self-transfer) — but the amount/date/opposite-
  type/different-account requirements still have to hold regardless, bounding the blast radius.
  Should be validated against the real bank-statement corpus this project already maintains before
  shipping, the same way every other reconciliation change in this codebase's history has been.
- **Expected accuracy gain:** High — the single biggest lever available.
- **User impact:** High — corrects the most common and highest-severity failure mode found.

### 2. Guard against merging same-day, same-amount, no-discriminator duplicates
- **Addresses:** Finding C (2 of 18 failures, but each one silently deletes a whole real
  transaction from the user's records).
- **Effort:** Low-medium. Narrowly scoped to the specific case `splitByDiscriminator` already
  documents as unresolved — e.g., don't auto-merge when the description also matches an
  Investments/EMI-shaped category, or require explicit user confirmation for an undiscriminated
  group instead of auto-canonicalizing.
- **Complexity:** Low-medium.
- **Risk:** Low — narrowly scoped, doesn't touch the well-tested balance/reference discriminator
  path that already handles the majority of real statements correctly.
- **Expected accuracy gain:** Medium.
- **User impact:** High per incident (silent data loss), low frequency.

### 3. Replace first-match-wins with a scored, best-candidate selection in the transfer pass
- **Addresses:** Finding B directly (2 of 18), and reduces the *severity* (though not the pass rate)
  of the documented attribution-risk findings in refunds and CC-payments, which share the same
  "stop at the first structurally-qualifying candidate" shape.
- **Effort:** Medium. A real algorithmic change — collect all qualifying candidates instead of
  breaking on the first, rank by date-proximity (and, where available, reference-number agreement),
  then pick the best. Needs to touch the transfer pass's inner loop without changing the outcome
  for every scenario that already passes today.
- **Complexity:** Medium.
- **Risk:** Medium. Changes matching outcomes for existing users' historical data on their next
  reconciliation run — needs a migration/backfill plan, and every currently-passing benchmark
  scenario (and the wider `ReconciliationServiceTest`/`ReconciliationEndToEndTest` suites) must stay
  green.
- **Expected accuracy gain:** Medium-high (fixes 2 direct failures, improves explainability
  everywhere else this shape recurs).
- **User impact:** Medium — fewer confusing "why is this transaction categorized as a transfer"
  support questions.

### 4. Widen the credit-card payment due-date window for late payers, paired with better evidence-weighting
- **Addresses:** Finding G (1 of 18, but real totals impact).
- **Effort:** Low (a constant change, mirroring the existing `OWN_ACCOUNT_MATCH_DAY_WINDOW`
  precedent), but should not ship alone — widening the window without also strengthening the
  last-4/evidence tiebreak (item 3 above) would worsen the already-documented "ambiguous payment
  description, two similar-due cards" attribution risk.
- **Complexity:** Low, if bundled with item 3; otherwise carries hidden risk.
- **Risk:** Low-medium.
- **Expected accuracy gain:** Low-medium (narrow finding, real per-incident impact).
- **User impact:** Medium — corrects double-counting for a common real behavior (paying a card bill
  late).

### 5. Widen the transfer amount tolerance with an evidence-aware guard, not a flat increase
- **Addresses:** the `transferWithRealisticFee` finding within cluster A's broader pattern.
- **Effort:** Low (a constant change), but should follow the same "only widen with corroborating
  evidence" pattern `OWN_ACCOUNT_MATCH_DAY_WINDOW` already establishes for the date axis, rather
  than a flat, unconditional increase — otherwise it trades one false-negative class for a new
  false-positive class (coincidentally-similar unrelated amounts).
- **Complexity:** Low-medium (mostly in getting the guard condition right).
- **Risk:** Low-medium.
- **Expected accuracy gain:** Low-medium.
- **User impact:** Low-medium.

### 6. Add a length- or context-aware guard against short-token false positives in Gmail matching
- **Addresses:** Finding E.2 (1 of 18, but an active over-match, not just a miss).
- **Effort:** Low — a threshold or minimum-length adjustment, or require a second corroborating
  signal (e.g., merchant category agreement) alongside the similarity score for short tokens
  specifically.
- **Complexity:** Low.
- **Risk:** Low-medium — raising the bar could introduce new false negatives for legitimately short
  real brand names; needs validation against real Gmail-import data before shipping.
- **Expected accuracy gain:** Low (narrow finding) but high value per fix, since this is the only
  actively-wrong (not merely missing) result in the whole Gmail category.
- **User impact:** Medium — an incorrect cross-link is more visibly confusing to a user than a
  missed one.

### 7. Wire `GmailReconciliationMatcher` into the existing merchant-alias table
- **Addresses:** Finding E.1 (1 of 18).
- **Effort:** Medium — this matcher currently has no dependency on `MerchantAlias`/
  `MerchantNormalizationEngine` at all; adding it is a real (if bounded) integration, not a
  constant tweak.
- **Complexity:** Medium.
- **Risk:** Low.
- **Expected accuracy gain:** Low-medium — narrow blast radius (only Gmail-Sync users with a
  brand/legal-entity narration mismatch), and Gmail Sync itself is still an early-stage feature per
  this project's own tracking.
- **User impact:** Low-medium today; grows as Gmail Sync adoption grows.

### 8 → re-ranked to ~3-4. Fix the investment-transfer keyword's word-boundary fusion gap
- **Addresses:** Finding F (1 of 18 scenarios, but see the corpus check below).
- **Effort:** Low — this is a `CategoryRules` keyword-matching fix, not a reconciliation-logic
  change.
- **Complexity:** Low.
- **Risk:** Low.
- **Expected accuracy gain:** **Upgraded from Low to Medium.** `corpus-frequency-analysis.md`
  measured a 42% real-world miss rate for fused "Groww" narrations in this project's own corpus (8
  of 19 real lines) — this was originally estimated "low-medium" on no evidence; it is now
  evidence-backed and materially higher than guessed, and should move up the queue accordingly.
- **User impact:** Medium (inflates spend by the investment amount when it occurs, and now known to
  occur close to half the time for at least one real user's Groww activity).

### 9. Cross-description duplicate detection (manual entry vs. import, reformatted narration)
- **Addresses:** Finding D (2 of 18) — the most user-visible, "why didn't the app catch this"
  finding, but the hardest to fix safely.
- **Effort:** High. This needs a genuinely different mechanism (fuzzy/reference-number-based
  matching independent of exact description text), and this project's own documented philosophy
  for the duplicate pass is explicit that over-matching is the more dangerous failure mode here
  ("a wrongly-grouped pair costs the user one tap to un-duplicate, a missed group costs them
  nothing they can see is wrong" — i.e., today's design deliberately accepts this exact class of
  false negative as the safer failure). Any fix should probably surface as a low-confidence
  `CANDIDATE` graph edge for user confirmation (mirroring how the Gmail cross-source pass already
  handles its own fuzzy tier) rather than an automatic merge.
- **Complexity:** High.
- **Risk:** Medium-high — this is precisely the class of change the codebase's own comments warn
  against making casually.
- **Expected accuracy gain:** Medium-high for this specific, common scenario, but should be scoped
  and evidence-gathered carefully (real corpus validation, like every other reconciliation change
  in this project's history) before implementation begins.
- **User impact:** High if done well; this is the finding most likely to generate a real support
  ticket ("I already added this, why is it counted twice").

### Explicitly deferred: many-to-one / split-transfer matching

**Not recommended for near-term work.** Addresses only 1 of 18 failures directly, requires a
genuinely different (combinatorial) matching model, carries real performance and correctness risk,
and — following this project's own stated precedent for the investment-transfer pass ("scoped down
after checking this project's own real bank-statement corpus rather than building speculatively") —
should wait for real evidence of how often users actually split transfers across multiple
transactions before any design work begins.

---

## 6. What this benchmark does not establish

Per this project's evidence rule: say what was not measured, not just what was.

- **Real-world frequency.** This benchmark measures whether the engine gets specific, realistic
  scenarios right — it does not measure how often each scenario actually occurs across Finora's
  real user base. The roadmap's "real-world likelihood" judgments are reasoned from Indian banking
  conventions and this project's own real bank-statement corpus findings (cited throughout the
  codebase's own comments), not from a query against production data.
- **Performance impact of any proposed fix.** Not evaluated here — see
  `docs/engineering/scaling-triggers.md`'s own standard for when that becomes the deciding factor.
- **The GmailReconciliationMatcher.findMatch() staging-time path.** Only `findMatchAmongTransactions`
  (the confirmed-transaction-vs-transaction path `ReconciliationService` actually calls) was
  benchmarked; the staging-time `findMatch()` used before a Gmail receipt is even confirmed shares
  the same similarity function and would very likely show the same short-token false-positive risk,
  but this was not separately verified.

---

## 7. Item #1: what was actually implemented

**File:** `backend/src/main/java/com/finora/service/ReconciliationService.java`, the transfer
pass's `looksLikeTransfer` check.

**Before:**
```java
boolean looksLikeTransfer = aOwnAccountMatch
        || CategoryRules.normalize(a.getDescription()).contains("payment");
```

**After:**
```java
boolean looksLikeTransfer = aOwnAccountMatch
        || CategoryRules.normalize(a.getDescription()).contains("payment")
        || "Transfer".equals(CategoryRules.suggestCategory(a.getDescription()));
```

This reuses `CategoryRules`' existing, already-vetted `"Transfer"` keyword list (`"neft to"`,
`"imps to"`, `"autopay"`, `"billdesk"`, `"cc payment"`, `"card bill payment"`) rather than inventing
new keywords — the smallest, lowest-risk version of item #1, exactly as originally scoped. It does
not touch the amount/date/account/type matching logic at all, only which candidates are offered to
it.

**Verification before and after the change:**
- The full 59-scenario benchmark (§1) — before: 41/59 passed; after: 43/59 passed.
- The existing `ReconciliationServiceTest`, `ReconciliationEndToEndTest`, and every other test in
  `com.finora.service.*Test` / `com.finora.transactions.*Test` (the packages this change could
  plausibly affect) — all pass, unchanged, after the fix. No regression.

**What this fix does not do, on purpose:** it does not add RTGS, "self transfer", "wallet", or "auto
debit" (as opposed to "autopay") as new keywords — none of those are in `CategoryRules`' existing,
already-reviewed vocabulary, and adding them would be a materially different, higher-risk change
(new keyword invention rather than reuse) that deserves its own measurement cycle, not a scope
expansion smuggled into "item #1." That is the next natural increment if this project decides the
53% transfer pass rate is still worth improving further.

## 8. Audit-driven correction to the failure count

Per `benchmark-audit.md` §2.2: three window-boundary scenarios (transfer, refund, CC-payment) test
the identical mechanical shape and should be scored consistently. The original report counted the
CC-payment case as a hard failure while treating the other two as by-design/documented risk. Applying
one consistent standard moves that scenario into the same "documented risk" bucket as its siblings:

- **Literal `mvn test` result (used throughout this document):** 18 failures before item #1, 16 after.
- **After the audit's consistency correction:** 17 before item #1, 15 after.

Both are reported here rather than silently picking one — the literal test-execution number is what
actually ran; the audit's number is what a fair, consistent scoring standard would show. Neither
changes any conclusion in this report: item #1 is still the highest-ROI fix available, and the
overall shape of the findings (transfers weakest, refunds strongest) is identical either way.
