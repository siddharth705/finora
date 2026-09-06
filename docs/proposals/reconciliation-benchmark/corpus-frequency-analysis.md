# Real-Corpus Frequency Analysis

**Purpose:** the original benchmark report explicitly named "real-world frequency" as unmeasured.
This document uses this project's own real bank-statement corpus (`/Users/sid/Downloads/Bank
statement/` — 29 real statements: 20 savings/current accounts, 9 credit cards, across HDFC, ICICI,
SBI, HSBC, Axis, Kotak, BOB, Canara, PNB, AU, Indusland, Bandhan, CBI, Standard Chartered, Union
Bank, and Paytm) to answer, where the corpus can actually answer it, "how often does this pattern
occur in real Indian banking text."

**Method:** raw text was extracted from each PDF with `pdftotext -layout` into a scratch directory
outside this repository, analyzed for aggregate keyword/pattern counts only, and then deleted. No
real transaction description, account number, name, or amount from this corpus is quoted anywhere
in this document or committed to this repository — only counts and percentages, the same privacy
discipline this project's own `CorpusProbe`/`ImportVerificationRecorder` already apply to this
corpus for the identical reason. This is a read-only analysis; no application code or ground-truth
file was touched.

---

## Finding A: the transfer keyword gate — strongly confirmed

| Measurement | Count |
|---|---|
| Total lines across all 29 statements | 16,635 |
| Lines containing a transfer-rail marker (NEFT/IMPS/RTGS/UPI, whole word) | 2,464 |
| ...of which also contain the word "payment" | **27 (1.1%)** |
| Lines carrying an explicit self-transfer marker ("self", "own a/c", "own account") | 4 |
| ...of which look like an actual transaction row (carry a decimal amount) | 1 |
| ...of which contain "payment" | **0** |

**Reading this honestly:** the 1.1% figure is strong, real evidence that the reconciliation
pass's `"payment"`-substring gate is a poor match for real Indian banking narration vocabulary —
transfer-rail-shaped lines essentially never use that word. This *directly* supports finding A's
mechanism.

**What this does NOT establish:** not every NEFT/IMPS/RTGS/UPI line is a *self*-transfer — most are
payments to other people or merchants, which correctly should not be flagged as transfers at all.
This corpus is 29 different, unrelated real people's statements collected for parser testing, not
one household's multiple linked accounts, so it cannot directly answer "how many self-transfers per
month does this miss" — only 1 line in the entire corpus is even shaped like a genuine self-transfer
transaction row, which is too small a sample to extrapolate an absolute rate from, though it is
consistent with (and does not contradict) the broader 1.1% vocabulary finding. **Getting an actual
self-transfer miss rate requires either Fynora's own production data (pairing transactions across a
single real user's own multiple accounts) or a larger, deliberately-paired synthetic corpus — this
analysis cannot produce that number honestly.**

## Finding F: investment-transfer keyword fusion — real-world rate is materially higher than originally estimated

| Measurement | Count |
|---|---|
| Real transaction lines mentioning "Groww" at all (any form) | 19 |
| ...correctly caught by `CategoryRules`' word-boundary match | 11 (58%) |
| ...where "Groww" appears ONLY in a fused form (no word-boundary match anywhere on the line) | **8 (42%)** |

**This changes the original report's assessment.** The original benchmark rated this finding
"low-medium" real-world likelihood, treating the fused-keyword narration as a constructed edge
case. In the one real user's statement in this corpus with Groww activity at all, **42% of the
real Groww-related lines would already be missed today** by `CategoryRules.suggestCategory`'s
word-boundary matching. This is a small sample (one user, 19 lines) but it is real, measured data,
not a hypothetical — and it moves this finding up the priority list from where the original report
placed it.

## Findings this corpus cannot answer

Stated plainly, per this project's own evidence discipline — a limitation admitted is cheaper than
a number invented:

- **First-match-wins misattribution (finding B).** Requires simulating the actual matching
  algorithm over real multi-account, cross-referenced data — this corpus is single statements from
  unrelated individuals, so there is no real "coincidental second candidate" to observe; would need
  either production data or running the real pipeline (not just text-grepping) against a corpus with
  genuinely linked own-accounts.
- **Manual entry vs. import duplicate (finding D, downgraded to a design question in the audit).**
  Cannot be observed from bank statements at all — a manual entry never appears in a bank statement
  by definition. Needs Fynora's own production database (comparing `source = MANUAL` against
  `source = CSV_IMPORT` rows for the same user/date/amount).
- **CC payment late-window (finding G).** Requires pairing a card statement's due date against an
  actual payment date across two related accounts — not reliably derivable from static text
  extraction without running the full parsing pipeline and cross-referencing dates, which this
  analysis did not do.
- **Gmail alias / short-token false positive (finding E).** Gated by Gmail Sync adoption, which is
  not observable from bank-statement PDFs at all — this needs Fynora's own product-usage data, not
  bank statement text.
- **Split-transfer, transfer amount tolerance.** Same shape as B — needs real paired-account data
  or production transaction volume, not single unrelated statements.

## Net effect on priority ranking

The core roadmap ranking from the original report is **not overturned** by this data — if anything
it is strengthened for item #1 (transfer gate) and item #8 (investment keyword fusion), which
should move up from #8 to roughly #3–4 given the 42% real-world hit rate just measured. Every other
item's ranking stands as originally reasoned, now explicitly labeled as *not yet corpus-validated*
rather than silently assumed.
