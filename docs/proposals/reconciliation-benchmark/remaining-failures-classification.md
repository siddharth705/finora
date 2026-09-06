# Remaining 13 Failures: Classification & Next-ROI Recommendation

**Status:** Analysis only. Nothing in this document has been implemented.
**Baseline:** re-run for this analysis (not carried over from memory) — 78.0% overall (46/59), the
state after roadmap item #1, item 1a (RTGS/self-transfer), and item #2 (investment word-boundary
fusion). Transfer vocabulary and investment keyword matching are excluded from the ranking below,
per the brief — those are already addressed and re-litigating them here would just repeat the last
three analyses.

---

## 1. Classification

Each failure classified as one of **data loss** (a real transaction is silently excluded from
every total, with no signal anything happened), **misclassification** (a real transaction is
labeled wrong, in a way that distorts totals in one or both directions), **missed reconciliation**
(a real link that should exist doesn't, typically producing double-counting), or **false positive**
(an actively wrong match, distinct from a mere miss).

| # | Scenario | Class | User impact | Engineering effort | Regression risk |
|---|---|---|---|---|---|
| 1 | `sipInstallments_...wronglyMerged` | **Data loss** | **High** — a real ₹5,000 SIP silently vanishes from every total; nothing in the UI signals it happened | Low-medium | Low |
| 2 | `emiPayments_...wronglyMerged` | **Data loss** | **High** — same mechanism, a real EMI payment | Low-medium | Low |
| 3 | `multipleCandidateMatches_firstMatchWins_...` | **Misclassification** (compound: wrong candidate wrongly excluded from totals *and* the real transfer leg wrongly stays counted) | Medium-high | Medium (a real scoring change, not a keyword edit) | Medium — touches tie-break behavior for every existing 2+-candidate scenario, not just the failing ones |
| 4 | `ambiguousTransferSelection_...` | **Misclassification** (same compound shape as #3, from the expense side) | Medium-high | Medium (same fix as #3) | Medium (same reason) |
| 5 | `nearDuplicate_reformattedNarration_...` | Missed reconciliation → double-count | Medium-high (strong evidence: a well-known real UPI settlement pattern) | Medium (reference/UTR-number matching, narrower than full fuzzy text) | Low-medium |
| 6 | `manualEntryThenImport_...` | Missed reconciliation → double-count | Medium, but see the benchmark audit: the "ideal" fix here is a genuine design question (auto-merge risk), not a settled fact | High | Medium-high if done carelessly |
| 7 | `latePayment_beyondTenDayWindow_...` | Missed reconciliation → double-count | Medium-high (late card payments are common) | Low, but unsafe alone (worsens the already-documented ambiguous-CC-attribution risk — see the original report) | Medium if widened without better tiebreak evidence |
| 8 | `merchantAlias_brandToLegalEntityName_...` | Missed reconciliation → double-count (conditional on the user having *both* a Gmail and a bank import of the same charge) | Low-medium (Gmail Sync is still early-stage) | Medium (new dependency wiring) | Low |
| 9 | `falsePositive_shortTokenEditDistance_...` (zoom/room) | **False positive** — but see §2, this one is contained | Low today (does not touch cash-flow totals), would be higher if reconciliation ever surfaces Gmail-fuzzy edges more prominently | Low | Low-medium |
| 10 | `splitTransfer_...` | Missed reconciliation → double-count (both directions at once) | Medium, unmeasured absolute frequency | High (different, combinatorial matching model) | High |
| 11 | `transferWithRealisticFee_...` | Missed reconciliation → double-count | Low-medium (bounded by typical rail fees, ~₹20) | Low, but unsafe as a flat change | Medium if done as a flat widen |
| — | `savingsToCreditCardAutoDebit_...` | Missed reconciliation → double-count | *(out of scope — transfer vocabulary, already addressed as a category)* | — | — |
| — | `walletFunding_...` | Missed reconciliation → double-count | *(out of scope — transfer vocabulary, already addressed as a category)* | — | — |

## 2. A finding this classification surfaced that the earlier reports didn't have: duplicates are self-correctable by the user today; transfers are not

Checked directly against the code, not assumed: `TransactionController` exposes
`POST /{id}/not-duplicate` (`TransactionService.confirmNotDuplicate`) — a real, user-facing
correction path. A user who notices a wrong `DUPLICATE` flag can fix it themselves, permanently
(`notDuplicateConfirmedAt` stops future passes from re-flagging it).

**No equivalent exists for a wrong `TRANSFER` flag.** The only place `isTransfer` gets reset to
`false` in the whole backend (`TransactionService.clearReconciliationPointersTo`) is an automatic
side effect of deleting the *other* side of the pair — there is no direct "this is not a transfer"
action. A user hit by finding #3/#4 above has no self-service fix short of deleting a real
transaction, which is a materially more destructive workaround than clicking "not a duplicate."

This cuts both ways on the two top candidates: it's a point *in favor* of prioritizing the
misclassification fix (#3/#4) at some point, since its failure mode is currently unrecoverable by
the user — but it does not change the ranking below, because #1/#2 (data loss) are also silent
*and* only recoverable if the user happens to know to look at a duplicates-review surface at all,
which most users don't. Both gaps are real; this is additional context for whoever prioritizes the
*next* one after this recommendation, not a reason to reorder this one.

## 3. Recommendation: exactly one next implementation target — ✅ IMPLEMENTED

**SIP/EMI duplicate-merge guard (#1/#2 in the table above).**

**Implementation:** `ReconciliationService.splitByDiscriminator` gets a third branch, after the
existing balance and reference-number discriminators: when a same-day/same-amount/same-description
group has neither, and the shared description contains a recurring-mandate marker (`sip`, `emi`,
`ecs`, `nach`, `mandate`, `installment` — a small, purpose-built set, deliberately not a reuse of
`CategoryRules`' category vocabulary, which is tuned for a different job with a different
false-trigger cost), every member of the group is left on its own instead of being collapsed into
one canonical row. A false trigger here only means a genuine duplicate slips through uncaught — the
same accepted, safer-direction failure this method's balance/reference logic already carries.

**Re-measured result:**

```
Before this change:  78.0% overall (46/59), duplicate detection 56% (5/9)
After this change:   81.4% overall (48/59), duplicate detection 78% (7/9)
```

`sipInstallments_...` and `emiPayments_...` both now pass — the two scenarios this fix targeted.
Full regression check (`ReconciliationServiceTest`, `ReconciliationEndToEndTest`, and the whole
`com.finora.service`/`transactions`/`util`/`imports`/`rules` test surface) is unaffected — in
particular, every existing balance-discriminator and reference-number-discriminator test (the real
PNB/HDFC corpus cases this method was originally built for) still passes unchanged, since those
groups resolve in the first two branches before ever reaching the new guard.

**11 failures remain** (down from 13, 14, 16, and the original 18).

## 3b. First-match-wins → best-candidate transfer scoring — ✅ IMPLEMENTED (next in sequence)

Per the sequencing already agreed (SIP/EMI guard, then this, then stop for production telemetry):
the transfer pass's inner loop now collects every candidate that already passes the structural
checks (different account, opposite type, not salary, amount within tolerance, within the
applicable day window) instead of committing to the first one found in `(date, id)` sort order, and
scores each with `transferCandidateScore(relationshipMatch, daysApart, dayWindow)`:

- **Own-account relationship match: +40** — a user-configured `OWN_ACCOUNT` identifier hit on
  either side, the strongest evidence this pass has.
- **Date proximity: 0–20, linearly decaying from the anchor date to the window's edge** — the same
  shape `ConfidenceScorer`'s own `date_decay` already uses elsewhere in this file, deliberately
  reused rather than invented fresh.

**Scoped down from the original four-signal sketch (own-account, same day, same description, known
account pair) to these two, and here is why, not just what:** the benchmark's own before/after
measurement only needed these two signals to resolve both scenarios it found. "Same description"
has no natural meaning for a transfer pair specifically — the two legs of a real transfer routinely
read nothing alike ("CREDIT CARD PAYMENT" vs. "PAYMENT RECEIVED THANK YOU"), unlike the duplicate
pass, where identical description *is* the signal. "Known account pair" would mean querying past
confirmed edges between the same two specific accounts — real, plausible future work, but a
genuinely different, stateful mechanism from what a single candidate's own fields can answer today,
and it isn't included by default just because a sketch named it; it would need its own evidence
first, the same discipline every other change in this file has followed.

**Re-measured result:**

```
Before this change:  81.4% overall (48/59), transfers 65% (11/17)
After this change:   84.7% overall (50/59), transfers 76.5% (13/17)
```

`multipleCandidateMatches_firstMatchWins_...` and `ambiguousTransferSelection_...` both now pass —
the two scenarios this fix targeted. Full regression check (`ReconciliationServiceTest`,
`ReconciliationEndToEndTest`, the whole `com.finora.service`/`transactions`/`util`/`imports`/
`rules`/`controller`/`repository` test surface, plus the Postgres-backed
`ReconciliationAuditVolumeIT` and the opt-in `ReconciliationScalingBenchmark`) is unaffected — every
scenario with only one qualifying candidate (the overwhelming majority of existing tests) picks the
same candidate it always did, since scoring only changes the outcome when two or more candidates
are actually competing.

**9 failures remain.** Per the agreed plan, this is the stopping point for benchmark-driven changes
this cycle — the next improvements should come from production telemetry on real user data, not
further synthetic-scenario optimization. See §1's table above for what's left and why each is
parked for now.

---

Reasoning for picking the SIP/EMI guard over this fix first, weighing all four axes together
(preserved from before either was implemented, since it's what justified the sequencing):

- **Class severity:** data loss is a strictly worse failure mode than misclassification for a
  personal-finance app specifically because it is *silent in a way nothing else in this table is* —
  the money doesn't just get labeled wrong, it disappears from every total with zero signal, and
  (per §2) recovering from it depends on a user noticing a duplicate flag they had no reason to
  suspect. A misclassified transfer at least keeps both rows visible in the raw ledger with a
  labeled status; a wrongly-merged duplicate's second row reads, to every aggregate query in the
  codebase, as if it never happened.
- **Effort and risk are both lower than the misclassification fix.** The SIP/EMI guard is a
  narrowly-scoped addition to `splitByDiscriminator`'s already-documented unresolved case — it does
  not touch the discriminator logic that already works correctly for the majority of real
  statements. The first-match-wins fix is a real scoring-algorithm change to a loop that today's
  entire passing transfer-scenario suite already depends on for its tie-break behavior; it needs
  more careful regression validation before it's the safer of the two to ship.
- **Benchmark impact is tied (2 scenarios each)**, so it doesn't break the decision either way —
  severity and risk do.

This matches the sequencing already proposed independently: SIP/EMI guard next, best-candidate
transfer scoring after it once the guard has shipped and been re-measured on its own. Nothing in
this analysis argues for a different order or a different pair of targets.

**Not recommended for this cycle, and why, briefly (full reasoning already on record in earlier
reports):** the near-duplicate reference-number case (#5) is well-evidenced but a different, larger
mechanism than the SIP/EMI guard, worth its own cycle rather than bundling; the manual-entry case
(#6) is a design question, not a bug, per the benchmark audit; split-transfer (#10) stays deferred
pending real usage evidence, unchanged from every prior report; the CC-payment window (#7) and
transfer-fee tolerance (#11) are both real but smaller in scope and risk regressing the
already-documented ambiguous-attribution findings if touched without their own tiebreak work first;
the Gmail findings (#8/#9) are gated by Gmail Sync's early adoption stage regardless of mechanism
correctness.
