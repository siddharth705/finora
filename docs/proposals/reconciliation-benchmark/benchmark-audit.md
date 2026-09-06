# Benchmark Quality Audit

**Purpose:** challenge the 59-scenario benchmark itself before trusting its roadmap — a bad
benchmark produces a confidently-wrong priority list. This audit does not change any reconciliation
logic; it only re-examines the benchmark's own scenarios and assertions with an adversarial eye.

Scope, per the brief: for the original 18 failing scenarios, (1) is the expected outcome actually
correct, (2) which encode opinion rather than fact, (3) what real Indian-banking scenarios are
missing, (4) which failures are production-impacting versus theoretical.

---

## 1. Findings that hold up under scrutiny (fact, not opinion)

These construct their own ground truth directly (a shared reference number, an identical merchant,
a deliberately-planted "real" transfer leg) rather than asserting a debatable judgment call. Nothing
here changed after re-examination:

- **Transfer keyword-gate misses (6 scenarios).** The "ideal" outcome — a same-user, same-amount,
  opposite-direction, close-date pair on two different accounts is a transfer — is close to
  definitional, not a matter of taste. Solid.
- **First-match-wins misattribution (2 scenarios).** Each scenario plants an unambiguous real
  transfer leg and an unambiguous coincidental decoy; the "ideal" pick is the constructed real leg.
  Solid.
- **Gmail short-token false positive (`zoom`/`room`).** Two unrelated real-world merchants engineered
  to be one Levenshtein edit apart. Unambiguous. Solid.
- **Gmail merchant-alias miss (Swiggy/Bundl Technologies).** Same amount, one day apart, same real
  business under two names. Solid.
- **Near-duplicate reformatted narration.** Strengthened deliberately by embedding the *same* UPI
  reference number in both narrations — this is closer to proof of identity than a coincidence.
  Solid, and among the strongest-evidenced findings in the whole suite.
- **Split-transfer (many-to-one) miss.** The engine's one-to-one matching design is read directly
  from the code (`sameAmount` compares exactly two transactions); no aggregation path exists at
  all. Fact, not inference.

## 2. Findings downgraded or reclassified after this audit

### 2.1 Manual entry vs. import (opinion, not settled fact)

`manualEntryThenImport_sameRealTransaction_differentDescription_notCaught` asserts that a manual
note ("Dinner with Raj") and a bank-imported charge ("BARBEQUE NATION BLR") for the same amount and
date *should* auto-merge. On re-examination, **this is a product judgment call, not a fact the way
the transfer-gate finding is.** The engine's own duplicate-key design is deliberately narrow, and
the codebase's documented reasoning for the existing case/whitespace-folding fix argues over-
matching is the *safer* failure mode for a folding change — but matching across two **completely
different, unrelated description strings** on amount+date alone is a materially larger step than
folding case and whitespace, and carries a real over-matching risk this audit did not originally
weigh: two genuinely different ₹1,850 expenses on the same day (one logged manually, one imported
from an unrelated account) would also collide under a naive amount+date auto-merge.

**Resolution:** keep the scenario (it's still a real, common, user-visible gap worth roadmap
attention), but the roadmap's own item #9 already reflects this correctly — recommending a
low-confidence `CANDIDATE` edge for user confirmation, not an automatic merge. This audit's
correction is to stop treating the "ideal" outcome as settled fact; it's a scoped design proposal,
not a bug fix in the same sense as finding A.

### 2.2 Internal inconsistency: the CC-payment late-window boundary vs. the transfer/refund window boundaries

Three scenarios in the original benchmark test the identical mechanical shape — "does this pass's
matching window widen far enough for a common real-world settlement delay?" — but were scored
inconsistently:

| Scenario | Window | Days over | Labeled |
|---|---|---|---|
| `transferBeyondDefaultWindow_...` | 4-day default | +2 days | BASELINE (by design) |
| `refundBeyond180DayWindow_...` | 180-day window | +5 days | BY DESIGN (documented risk) |
| `latePayment_beyondTenDayWindow_...` | 10-day window | +2 days | **GAP** (counted as a failure) |

All three have the same double-counting mechanic when unmatched. The CC-payment case was singled
out as a hard GAP on the argument that its impact is "more real" than the refund case — but the
transfer-window case has an *identical* double-count mechanic (both legs stay counted as ordinary
spend/income) and was *not* counted as a failure. That's an inconsistency in how this benchmark
scored its own scenarios, not a principled distinction.

**Resolution:** for a fair comparison, all three should be treated the same way. This audit
recommends demoting `latePayment_beyondTenDayWindow_...` from the hard 18-failure tally to the same
"documented risk, by design" bucket as the other two — **which changes the honest failure count
from 18 to 17** (before the item #1 fix; 16 to 15 after it). The finding itself (a genuinely late
credit-card payment isn't matched) is still true and still worth roadmap awareness — it just
shouldn't be scored differently from its two siblings for no defensible reason. The test itself is
left unchanged (a red assertion is still useful signal); this is a scoring/classification
correction, not a code or test change.

## 3. Missing scenarios this benchmark should have covered

### 3.1 Dead relationship types (the most significant gap this audit found)

`TransactionRelationship.RelationshipType` defines **`EMI`, `SALARY`, `LOAN_REPAYMENT`,
`CASH_WITHDRAWAL`, `CASH_DEPOSIT`** alongside `TRANSFER`/`REFUND`/`REVERSAL`/`DUPLICATE`/
`CC_PAYMENT`. Re-reading `ReconciliationService` end to end for this audit confirms: **no pass ever
constructs an edge of any of those five types.** They exist in the schema and the enum, and nothing
produces them. This is a real, code-verified fact (not opinion), and the original benchmark never
tested it because the user's brief scoped six specific pass categories that don't include these —
the gap is in the brief's scope, not just the benchmark's execution of it.

This is worth flagging as its own roadmap consideration: either these five types represent
already-designed-for future work (in which case, fine, no action needed today), or the data model
has drifted ahead of the passes that were supposed to populate it. Recommend a short, separate
follow-up question to product/eng: *are `EMI`/`SALARY`/`LOAN_REPAYMENT`/`CASH_WITHDRAWAL`/
`CASH_DEPOSIT` planned work, or dead schema?* — not a benchmark scenario to build today, since there
is no behavior yet to benchmark.

### 3.2 Other real Indian-banking patterns not covered

- **NACH/ECS mandate bounce-and-retry.** A failed auto-debit (insufficient funds) followed by a
  retry a few days later, same amount, same merchant — could plausibly be miscategorized by the
  refund/reversal pass as a same-account "reversal" pair when it's actually two independent, both-
  real debit attempts (only one of which succeeds). Not tested.
- **Interest credit collisions.** A small periodic "INTEREST CREDIT" or "SAVINGS INT" INCOME row
  landing inside a refund's matching window, for a coincidentally identical or smaller amount than
  a recent expense — a candidate false-positive refund match this benchmark didn't construct. Likely
  low severity (small amounts) but easy to add.
- **UPI mandate registration/AutoPay setup fees**, distinct from the recurring debits themselves —
  not tested, low priority.
- **Multiple accounts at the same bank vs. different banks** — not distinguished anywhere in the
  benchmark; the engine is bank-agnostic once parsed, so this is unlikely to matter, but was not
  explicitly verified.

None of these are scored as findings (no test was built, so there is no measured result to report)
— they are logged here as coverage gaps for a future benchmark iteration, per this project's own
"say what was not measured" discipline.

## 4. Production-impact vs. theoretical, reassessed

Re-ranking the (now 17, post-audit) findings by how confident this audit is that they reflect real,
common production behavior — using only facts already established elsewhere in this project (real
corpus evidence, known feature-adoption stage), not new guesses:

**High confidence, production-impacting today:**
- Transfer keyword-gate misses (A) — confirmed independently by the real-corpus frequency check
  (see `corpus-frequency-analysis.md`): transfer-rail-shaped lines almost never contain "payment".
- Cross-description near-duplicate (the UPI reference-number variant specifically) — the underlying
  mechanism (same rail, two narration formats for one settlement) is a well-known real banking
  pattern, not a contrived one.

**Medium confidence, real but narrower preconditions:**
- SIP/EMI undiscriminated duplicates — conditional on a statement providing *neither* balance *nor*
  reference number, which most real statements in this project's own corpus do provide (the
  discriminator already handles the common case correctly).
- First-match-wins misattribution — requires a coincidental same-amount, same-window second
  candidate; real but not the majority case.
- Investment word-boundary fusion — **upgraded** by the real-corpus check (see next document) from
  this audit's original "low-medium" estimate to a materially higher rate than expected.

**Lower confidence today, but not zero — gated by feature adoption or usage pattern, not by the mechanism being wrong:**
- Gmail alias miss and Gmail short-token false positive — both real mechanisms, but only reachable
  by users with Gmail Sync enabled, a feature this project's own tracking notes as still early-stage.
- Manual-entry duplicate — real mechanism, but its frequency depends on how often users actually
  hand-enter transactions versus relying on statement import (this project's primary, and
  historically most-invested-in, workflow) — not measured here and not measurable from bank
  statement text at all (see next document's limitations section).
- Split-transfer, CC-payment late-window, transfer amount-tolerance — all real, all narrower in
  scope; no new evidence changes their standing from the original report.
