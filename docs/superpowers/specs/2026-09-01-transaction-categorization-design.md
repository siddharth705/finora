# Transaction Categorization — Architecture & Product Review

**Date:** 2026-09-01
**Scope:** Why "Other" dominates categorization outcomes, and how to fix it as a system, not a patch.

This is a first-principles review, not an "add an LLM" pitch. Section 1 is grounded in a real
measurement against Finora's actual 29-file bank-statement corpus, run through the actual
production `CategoryRules`/`RuleEngineService` matching logic — not simulated or guessed.

---

## 1. Root Cause Analysis

### The measurement

`CorpusProbe --synthetic` was run against all 29 real PDFs in the corpus (`~/Downloads/Bank
statement`), extracting 1,679 real transaction narrations. Each was run through the actual
production classes: `CategoryRules.suggestCategory()` plus the 47 CONTAINS keywords seeded in
`V19__category_rules_global_seed.sql` — replicating exactly what `RuleEngineService` does on a
brand-new user's first statement import (no prior rules, no learned merchant history, which is
the honest worst case and also the first-impression case that matters most for trust).

**Real Other rate: 1,400 / 1,679 = 83.4%.** That matches the impression closely enough to treat
it as confirmed, not anecdotal. It's concentrated unevenly — a handful of files (Mann HDFC, CBI,
HDFC savings) account for most of the volume, while others (ICICI CC, AU CC, Indusland) mostly
matched fine. That unevenness itself is a clue: this isn't one uniform failure, it's several
distinct failure modes stacked on top of each other.

### The bucket breakdown (42-item stratified sample, real transactions)

| Bucket | Share | What it actually is |
|---|---|---|
| **Clean but unmatched** | 62% | Readable narration, genuinely no merchant/keyword hit |
| **Garbled/truncated** | 31% | Extraction-layer defect — not a categorization problem at all |
| **UPI/VPA pattern** | 5% | A structural signal (merchant-QR handle) the pipeline ignores |
| **Format mismatch** | 2% | Merchant is in the keyword list, but the string form doesn't match |

Four findings inside this that change the shape of the whole project:

**(1) The single largest driver isn't "we don't know enough merchants" — it's that person-to-person
transfers have no merchant at all.** The dominant pattern inside "clean but unmatched" is UPI/NEFT/IMPS
narrations naming an individual, not a business. No keyword table, no LLM, no shared corpus can
ever categorize these correctly by merchant lookup, because there is no merchant — this is a
**taxonomy gap**, not a matching gap. Right now these silently fall into "Other" as if the system
failed to recognize a business it should have known. It didn't fail — it was asked a question
that doesn't have a merchant-shaped answer.

**(2) Real, legitimate merchants are missing from the vocabulary in ways that are hard to
predict.** `ASSPL` is literally how Amazon Seller Services appears on a real Indian card
statement — not "amazon," not "Amazon.in." A keyword list seeded from consumer-facing brand names
will never catch this. Same story for `Cinnabon`, `Housingcom Gurgaon`, `Pureplay Skin Sciences`,
`JNS-PMJJBY` (a government insurance scheme abbreviation), `NET PAYIN TO NSE MF A/C` (a mutual
fund platform using "MF" not "mutual fund"). This is the genuine long-tail problem an LLM or a
much bigger merchant database is suited for.

**(3) Nearly a third of "Other" isn't a categorization bug at all — it's an extraction bug wearing
a categorization costume.** Empty descriptions, hex/UUID fragments spliced into narration text,
words truncated mid-token (`RELIANCE NIPPON LIFE ASSET MANA`, cut from "MANAGEMENT"; a VPA handle
cut mid-string). Given this repo's own history of PDF-extraction fixes, this is not surprising —
but it means **no categorization engine, however smart, can fix this share of "Other."** It has to
be fixed upstream, and conflating it with the categorization problem will waste effort building
matching logic against corrupted input.

**(4) A real architectural gap was found, not hypothesized: merchant normalization is currently
inert for the path that actually decides most outcomes.** `MerchantNormalizationEngine` does run
before categorization, but its cleaned-up `merchantName` only feeds rules scoped
`field='MERCHANT'` — and every rule in the current global seed uses `field='DESCRIPTION'`. So the
raw, un-normalized narration is what `CategoryRules.suggestCategory()` actually sees for the
keyword fallback that resolves the vast majority of transactions. The system built the
normalization layer and then didn't wire it into the path that needed it most. This is a real bug
in the existing architecture, independent of anything new we build.

### Validated at scale (full population, not a small sample)

The 42-item sample above was re-checked against the **full 1,400-transaction "Other" population**
using a heuristic classifier, itself spot-checked against 110 hand-read items (~8% of the
population) for an honest error bound. Two findings changed materially enough to matter for
prioritization; the rest held:

| Bucket | Small sample (42) | Full population (1,400) |
|---|---|---|
| Clean but unmatched | 62% | 63.3% (confirmed) |
| — of which: P2P named individual | *(not split)* | **42.2%** |
| — of which: real business/entity | *(not split)* | **21.1%** |
| Garbled/truncated | 31% | **17.4%** (revised down) |
| UPI/VPA pattern | 5% | 7.6% |
| Format mismatch | 2% | 0.1% (~2 items — negligible) |
| Unclear | 0% | 11.4% |

**P2P transfers are an even bigger share than the small sample suggested** — 42% of ALL "Other"
transactions, and P2P outnumbers missing-real-business by roughly 2:1 within the unmatched bucket.
This strengthens, not weakens, the case for a Transfer category and structural P2P detection as
the first move. **Extraction defects are real but smaller than first estimated** (17.4%, not
31%) — still large enough to matter as a parallel track, just not the co-equal driver it first
appeared to be. Heuristic error rate was 8–12%, with two disclosed, partially-offsetting biases
(a lone first name under-counted as P2P; some multi-word brand names without an obvious business
descriptor over-counted as P2P) — treat the P2P/business split as directionally solid, not exact
to the decimal.

**Spend-value concentration — the more important number for a "% of spend categorized" goal:**
"Other" transactions are 83.4% of transaction *count* but **91.7% of total transaction value**
— larger transactions skew disproportionately into "Other." And within that Other spend, value is
sharply concentrated: the top 100 of 1,400 transactions account for 83% of Other's total value,
driven by a handful of large recurring items — salary-like NEFT credits and large P2P transfers,
not a long tail of small purchases. This is the single most useful finding for prioritization: **a
"% of spend value categorized" metric will move far more from correctly handling a small number of
large, recurring, identifiable counterparties than from broad long-tail merchant-vocabulary
expansion.** It also surfaces a distinct, previously-unflagged issue worth checking: some of those
large recurring NEFT credits read as salary/income — if genuine salary deposits are landing in
"Other" instead of an Income category, that's a gap in income detection specifically, separate
from spend categorization, and worth a dedicated look.

### Metrics to build (regardless of which fix path is chosen)

- **Decision-source distribution** — `CategorizationService` already tags every suggestion with a
  `DecisionSource` (rule/learned/keyword/default). This is a free, already-instrumented metric —
  surface it as a dashboard, don't rebuild it. It answers "which layer is doing the work" without
  any new measurement infrastructure.
- **Coverage rate**, split by the buckets above (taxonomy-gap / long-tail-unknown /
  extraction-defect / format-mismatch) — a single "% Other" number hides which of four different
  problems is actually being looked at.
- **Correction rate per decision source** — of transactions auto-categorized by each layer, what
  fraction does the user later change? This is the real accuracy signal, and it's cheap: it's just
  `TransactionService.updateCategory` calls joined against the `DecisionSource` that was recorded.
- **Merchant-volume concentration** — what % of transaction *volume* is covered by the top N
  *unique* unresolved merchants? In this corpus, a small number of files/merchants drove most of
  the miss rate — this tells you whether fixing 20 merchants moves the number a lot (it likely
  does) before reaching for anything probabilistic.

---

## 2. Categorization Architecture

### Ideal pipeline, in execution order

```
1. Extraction quality gate     → flag/quarantine known-garbled narrations before they
                                  reach categorization at all (don't guess on broken input)
2. Structural normalization    → canonicalize narration: strip ref numbers/dates, extract
                                  UPI VPA handle, extract sender/receiver name for P2P,
                                  produce ONE canonical form every downstream layer shares
3. P2P/transfer detection      → structural check (named individual, no VPA-business
                                  signal, no biller code) routes directly to a real
                                  "Transfer"/"Sent to person" category — never touches
                                  merchant lookup at all
4. User rules                  → explicit personal instruction always wins
5. Global rules (curated)      → high-precision, deterministic, free
6. Shared merchant corpus      → global merchant→category lookup, keyed by the SAME
                                  canonical form step 2 produced — this is where the bulk
                                  of long-run coverage should live
7. VPA/QR structural signal    → "this looks like a business, not a person" (e.g.
                                  PAYTMQR*, merchant-style VPA) as a fallback signal when
                                  name matching fails but structure suggests a business
8. Per-user learned history    → this user's own past corrections for this exact merchant
9. LLM fallback                → ONLY for canonical merchants that hit nothing above;
                                  called once per unique canonical merchant, not per
                                  transaction, not per user; result written back into the
                                  shared corpus with a confidence + provenance tag
10. Confidence-gated apply     → auto-apply (high) / apply-with-badge (medium) /
                                  queue for review, never silently bucket (low)
```

### Why this order

Cheapest and most reversible signal first, most expensive and least certain last. Step 1 exists
because — per the measurement above — 31% of failures are an input-quality problem, and running
expensive inference against garbled text just produces confident garbage instead of honest
"Other." Step 3 exists because the single largest bucket in the real data literally cannot be
solved by anything below it — a taxonomy answer, not a smarter lookup, is correct here. Step 6
(shared corpus) sits *before* the LLM specifically so that the LLM is a cache-filling operation,
not a per-transaction cost center — called once per unique merchant across the entire user base,
not once per transaction.

### Where "confidence" comes from

Not a single opaque number. Confidence should be a function of **which layer answered** (rules >
shared corpus with many corroborating confirmations > shared corpus with one confirmation > LLM
guess) and, for the shared corpus specifically, **how many independent users agree** — not a
naive count that a handful of careless confirmations can inflate.

---

## 3. Premium SaaS Experience

Top-tier fintech products (Monarch, Copilot, Cleo-tier) share a pattern worth copying directly:
**"Other" is never displayed as if it were a category peer to "Food" or "Shopping."** It's either a
real, deliberately-chosen bucket for genuinely miscellaneous spend, or it's an actionable queue —
never a silent dump that just sits in a pie chart looking like the app doesn't understand the
user's money.

**Unknown vs. Other should be two different concepts**, not synonyms — and the codebase already
half-agrees: `Category` has a real, persisted "Other" row, and `DashboardService` separately has a
synthetic `unknownCategory()` helper. Right now that distinction may not be consistently surfaced
in the UI. Recommended split:

- **"Other"** = a real, user-choosable category for spend that's genuinely miscellaneous by
  nature (a one-off government fee, unexplained ATM cash). It's fine for this to show in a pie
  chart — the user picked it.
- **"Needs review"** = a queue state, never a chart slice. It should never blend into a spend
  breakdown as if it were real data (a 40% "Other" wedge looks like the product is broken, because
  it *is* broken — that's not real information about spending, it's an admission of not knowing
  yet). Show it instead as "N transactions need a 30-second look" — a nudge, not a lie dressed up
  as a category.

**Confidence should never be shown as a percentage.** That's engineer-speak. Use a visual tier
instead — a subtle dot or "auto-detected" tag on a medium-confidence guess vs. a plain confirmed
chip. Users learn the visual language in one session without ever needing the word "confidence."

**Merchant review, not transaction review**, as the primary interaction — the repo already has
this (`MerchantGroupReviewCard`, bulk recategorize by merchant group). Lean into it harder: make
it a genuine first-run moment right after the first import ("We found 12 merchants that need a
quick label — 2 minutes, and we'll remember every one"), ordered by transaction-volume coverage
(clear the merchant covering 200 transactions before the one covering 1), not alphabetically or
chronologically. One correction should visibly and immediately re-color every past and future
transaction for that merchant — the payoff needs to feel instant, not eventual.

---

## 4. Failure Modes & Loopholes — Aggressive Attack

**Merchant ≠ category (Amazon, Paytm, and friends).** Amazon alone can be Shopping, Groceries
(Amazon Fresh), Bills (Amazon Pay bill payment), or Entertainment (Prime Video) — a single
merchant string maps to N real categories depending on context that isn't in the merchant name at
all. A shared corpus that assumes one merchant → one category will confidently mis-categorize
these *at scale*, which is worse than leaving them unclassified (see below). **Mitigation:**
maintain a small explicit list of "polymorphic" merchants (probably under 50 — Amazon, Paytm,
PhonePe, GPay, Flipkart) that are *excluded* from single-answer global sharing; for these, either
require an extra signal before auto-applying, route them to per-transaction LLM calls instead of
a cached merchant answer, or default to lower confidence and lean on per-user learning instead.

**Global-corpus contamination.** Two real, legitimate different meanings for the same string can
collide — e.g. "RELIANCE" spans retail, telecom, and fuel businesses under one brand umbrella —
and different users "correcting" it to different truths will just overwrite each other under a
naive last-write-wins scheme. **Mitigation:** the shared corpus needs confidence-weighted voting
(store a distribution, only auto-apply when one answer clearly dominates, e.g. >80% of
confirmations), not a single mutable cell.

**Privacy.** This is the sharpest one, and the real data proves it's not hypothetical: the
dominant "Other" bucket is P2P transfers naming real individuals. A "shared merchant corpus" that
naively ingests raw narration text risks leaking one user's contact's name into a global,
cross-user system. **Mitigation: P2P/person-to-person transfers must never enter the shared
corpus at all.** They're solved structurally (bucket 3 in the pipeline above), not via
merchant-lookup — which is good, because it also means the privacy-sensitive share of the data
never needs to leave the per-user boundary in the first place.

**Wrong-category vs. unknown-category.** A confidently wrong category is worse than an honest
"unknown," because it silently corrupts dashboards and budgets *without the user noticing* — an
honest "Other" at least visibly asks for help. This means coverage% should never be optimized in
isolation; it needs a paired accuracy/correction-rate guardrail, or it's trivial to game (auto-apply
everything at low confidence, coverage hits 100%, trust collapses).

**Taxonomy drift.** Categories are per-user rows, seeded once at signup, and users can rename or
delete them freely. Two real risks: (a) if global rules or the shared corpus resolve to a category
by *name* rather than a stable system-level identity, a user renaming "Food" silently breaks every
rule that used to resolve there — verified in code, see Section 10; (b) the seeded taxonomy itself
may be missing shapes the real data clearly needs — there's no obvious home today for "sent money
to a person," government scheme payments, or mutual-fund platform transfers, all of which showed
up as real examples in the 42-item sample.

**Review fatigue.** At an 83% real "Other" rate, a naive "review everything" queue on day one is
overwhelming, not premium. It needs prioritization by transaction-volume coverage (a handful of
merchants likely account for most of the failing volume, per the corpus's uneven distribution)
and a real "skip for now" that doesn't nag again in the same session.

**Cold start / cost spikes.** A new user's first import could be years of history landing in one
shot — the worst-case moment for LLM cost and latency to spike unpredictably. **Mitigation:**
never call the LLM synchronously inside the import request path; queue it, rate-limit it, and
prioritize by transaction-count-weighted merchant frequency so the merchant covering 200
transactions gets classified before the one covering 1.

**LLM hallucination.** An LLM asked to categorize a foreign or ambiguous merchant name can
confidently invent a category that doesn't even exist in the user's taxonomy. **Mitigation:**
constrain output to a closed list (the user's actual category set, never free text), never let an
LLM answer override an existing rule/keyword match even if it disagrees, and tag its output with a
distinct provenance ("AI-suggested, unconfirmed") so it never masquerades as a curated rule.

**Indian banking narration edge cases**, confirmed against real data, not assumed: reference
numbers and dates are frequently glued directly onto merchant tokens with no delimiter, breaking
naive word-boundary matching; recurring auto-debits sometimes show a biller's *legal* registered
name rather than the consumer brand; fixed-width, mainframe-origin narrations get truncated
mid-word on some bank cores (`RELIANCE NIPPON LIFE ASSET MANA`); NEFT/IMPS narration grammar
differs for credits vs. debits on the same account. The repo already handles this kind of
bank-specific variance for *document structure* (per-bank PDF parsers) — the same pattern likely
needs to extend to *narration grammar*, not just page layout.

**Hidden architectural traps:**
- Running the LLM synchronously inside the import request risks import timeouts on a large,
  many-unique-merchant statement — it must be async/best-effort, with transactions landing as
  "pending categorization" rather than blocking the whole import.
- **Backfill matters as much as the fix.** Turning any of this on only for *future* imports leaves
  every existing user staring at their current, already-imported 83% "Other" — the fix needs to
  re-run against the existing stock of transactions, without silently overwriting categories a
  user has already manually confirmed.
- Confidence can accumulate false certainty over time if it's a naive function of confirmation
  count — a wrong early mapping that a few users click-through without really checking becomes
  sticky and hard to dislodge later. Needs a floor/ceiling and occasional re-sampling, not a
  monotonic counter.
- The normalization-is-inert bug found in Section 1 is itself a hidden trap: any new layer built
  on top of "the normalized merchant string" needs to verify it's actually wired into the path
  that matters, not parallel to it.

---

## 5. Roadmap

**Immediate (days) — cheap, safe, no architecture change:**
- Fix the normalization-is-inert bug: retry the keyword fallback against the merchant's canonical
  name when the raw description misses, since right now the normalization layer is built but
  disconnected from where most decisions happen.
- Add a real "Transfer" / "Sent to a person" category (already exists in the default taxonomy) and
  a structural P2P detector (named individual + no business-VPA signal) — this alone likely
  resolves the single largest bucket in the real data, with zero matching-logic cleverness
  required.
- Expand the global keyword/rule seed using the real corpus findings directly — `ASSPL`, biller
  abbreviations like `BPPY`/`CC PAYMENT`, and similar real misses cost nothing to add and are
  immediately verifiable against this corpus.
- Split "Other" from "Needs review" in the dashboard UI — stop blending unclassified spend into
  the category legend as if it were real data.
- Surface the already-instrumented `DecisionSource` distribution as a real metric — it's free.

**Medium-term (weeks):**
- UPI VPA structural parsing (business-QR vs. person-handle) as its own extraction step.
- Build the shared global merchant→category corpus with confidence-weighted voting, write-through
  from confirmed corrections, and P2P/person-name data explicitly excluded by construction.
- Merchant-review-as-onboarding flow, ordered by transaction-volume coverage.
- Introduce a stable category identity key decoupled from the user-editable display name.
- Taxonomy audit against real data shapes found in this review (transfers, government schemes,
  investment-platform transfers) — the matching engine can't produce a category that doesn't
  exist.

**Long-term / moat (months):**
- LLM fallback, cache-keyed per unique canonical merchant, rate-limited, cost-controlled, wired
  in only after the deterministic layers above are doing their share of the work.
- Polymorphic-merchant handling (Amazon/Paytm-style contextual sub-categorization).
- Bank-specific narration grammars extending the existing per-bank parser pattern from document
  structure to narration text.
- A genuinely differentiated, community-scale Indian-UPI-ecosystem merchant database — this is a
  real defensible asset if the contamination/privacy safeguards above are built in from the start.

---

## 6. Success Metrics

**Don't optimize raw coverage% alone — it's trivially gameable** (auto-apply everything at low
confidence and coverage hits 100% while trust collapses). Recommended set, in priority order:

1. **Correction rate per decision source** — the real accuracy signal; track rules, shared corpus,
   and LLM layers independently so a bad layer is visible, not averaged away.
2. **Coverage rate, split by root-cause bucket** (taxonomy-gap / long-tail / extraction-defect /
   format-mismatch) — a single number hides which problem is actually being solved.
3. **Time/taps to clear the review queue** — the actual user-effort proxy for "premium and
   low-effort," which is the stated product goal.
4. **Merchant-volume concentration** — % of transaction volume covered by the top N resolved
   merchants, to keep prioritization honest (fix what moves the number, not what's easy).
5. **Review-flow engagement** — % of users who ever interact with merchant review vs. silently
   tolerate the dashboard; a premium product gets used, not endured.

Treat "reduce Other to X%" as a target only when it's paired with a correction-rate guardrail —
never as a standalone KPI.

---

## 7. Target State

**6 months** — deterministic + UX fixes only, no shared corpus or LLM required to hit this:

- Unresolved (needs-review) rate after a fresh import: from 83% down to roughly **25–35%** of
  transaction count. This is a direct, evidence-based estimate — P2P detection alone removes ~42%
  of today's "Other," and a real first-pass rule/vocabulary expansion plus the normalization fix
  claims a meaningful share of the remaining 21% "real business" bucket.
- **Spend-value coverage becomes the headline metric, not transaction count** — because value is
  concentrated in a small number of large recurring items (top 100 of 1,400 = 83% of Other's
  value), correctly handling salary-like credits and recurring large P2P transfers should move
  spend-value coverage past 60–70% well before transaction-count coverage catches up.
- First-import review effort: under 2 minutes for a typical user, because merchant-grouped review
  only has to handle the long tail once P2P and extraction-defect volume is stripped out upstream.
- "Other" and "Needs review" are visually distinct everywhere in the product; no dashboard chart
  shows an unlabeled wedge that looks like the app doesn't understand the user's spending.
- `categorization_version`, richer provenance, and a confidence *tier* (not just the raw integer
  that already exists) are in the schema, even before every layer that uses them is fully built.

**12 months** — with shared corpus, governance, and LLM fallback operating:

- **>90% of spend value** auto-categorized at acceptable confidence with zero user action —
  spend-weighted deliberately, matching the value-concentration finding above, not
  transaction-count-weighted.
- A user never categorizes the same merchant twice: one correction propagates immediately to that
  user's own history, and — for merchants eligible for sharing (see anti-goals on P2P) —
  contributes to shared intelligence for every other user going forward.
- Merchant review is a one-time onboarding moment for a new user or a first statement from a new
  bank, not a recurring chore; steady-state review-queue growth per new statement approaches zero
  for existing users.
- Corpus governance (voting, decay, dispute handling) is live, so global accuracy doesn't silently
  degrade as the corpus grows.
- Correction rate per decision-source sits below an agreed ceiling (directionally: low
  single-digit % for rule-sourced, higher but bounded for LLM-sourced) as a standing trust
  guardrail, not a one-time check.

**What users should actually feel improving** (not internal metrics): first import goes from "most
of my transactions are unlabeled" to "a handful need a quick look"; the category pie chart looks
like a real reflection of spending instead of being dominated by a gray wedge; correcting a
merchant once visibly and permanently fixes it everywhere; nobody gets asked about the same
merchant twice.

---

## 8. Expected Impact, Effort, and Risk per Initiative

Ranked by impact-per-unit-of-engineering-effort, using the validated corpus numbers directly
rather than treating every initiative as equally weighted:

| # | Initiative | Impact | Effort | Risk | Why |
|---|---|---|---|---|---|
| 1 | **Transfer / P2P structural detection** | **High** | Low–Med | Low | Directly addresses 42.2% of all "Other" — the single largest bucket by a wide margin. Main risk is false positives (a real business misread as a person), tunable and low-stakes to get wrong. |
| 2 | **Normalization wiring fix** | Medium, foundational | **Low** | **Low** | Cheap, deterministic, already-scoped bug fix. Ceiling looks small in isolation but it's a prerequisite — every later layer that assumes "the canonical merchant string is what gets matched" depends on this actually being true. |
| 3 | **Expanded deterministic rules (one real pass using corpus findings)** | Medium, front-loaded | **Low** | Very low | Directly recovers real, verified misses (`ASSPL`, `BPPY`) at near-zero cost. Diminishing returns after the first pass — this is a one-time bump, not a sustained lever. |
| 4 | **Extraction-quality fixes (garbled/truncated)** | Medium | Med–High | Low | Separate track, not really "categorization" work, but caps its ceiling — 17.4% of Other cannot be fixed by any matching logic, however good. Effort depends entirely on which parser/bank is responsible; treat as a sibling initiative tracked alongside, not folded in. |
| 5 | **Merchant review UX (onboarding-ized, volume-prioritized)** | High (trust/perception, not raw coverage) | Medium | Low | Doesn't reduce Other numerically, but is the lever that makes whatever coverage exists *feel* premium instead of frustrating — matches the stated product goal directly. |
| 6 | **VPA parsing (business-vs-person structural signal)** | Low–Medium standalone | Low–Med | Low | Only 7.6% direct hit rate, but its real value is as *infrastructure* feeding #1 (rules out P2P) and #7 (identity key for the corpus) — underrated in isolation, necessary in combination. |
| 7 | **Shared merchant corpus + governance** | **High, long-run** | **High** | **Medium–High** | Highest ceiling — this is what turns every correction into permanent product-wide coverage. Also where contamination, privacy, and governance risk concentrate; should follow once 1–4 have shrunk and stabilized the remaining problem, not precede them. |
| 8 | **LLM fallback** | Medium–High, but only on what's left | **High** | Medium | Its marginal impact is lower than it looks in isolation precisely because most of "Other" isn't actually a merchant-intelligence problem (P2P + extraction defects = ~60% of the total). Valuable for the genuine long tail, but should be last, scoped to a much smaller remaining set once 1–7 exist. |

**Answer to "what should we build first for the largest reduction per unit of effort":** #1 and #2
together, in that order — P2P detection has by far the best evidence-to-effort ratio in the whole
list, and the normalization fix is close to free and unblocks everything downstream of it. #3 and
#5 are cheap enough to run in parallel with #1/#2. #7 and #8 are real, valuable, and explicitly
*not* where the biggest near-term win lives — building them first would be optimizing the smaller
share of the problem while the largest share (P2P, currently mislabeled as a matching failure)
sits untouched.

**This spec's first implementation plan covers #1, #2, and #3** — see
`docs/superpowers/plans/2026-09-01-categorization-p2p-normalization-rules.md`. #5 (merchant review
UX) is a separate follow-on plan, since it's a distinct subsystem (frontend onboarding flow) from
the backend categorization-engine changes in #1–#3.

---

## 9. Historical Data & Backfill Strategy

This matters as much as the fix itself — a solution that only improves future imports leaves every
existing user staring at their current 83%-Other dashboard indefinitely.

**Good news from the schema check: the safety mechanism already exists.** `Transaction` already
has two distinct, persisted booleans — `needsCategoryReview` and a separate `categoryManuallySet`
— and `updateCategory()` sets both together whenever a user explicitly picks or corrects a
category. This means a backfill job can safely and precisely answer "is it safe to touch this
transaction" today, with no new schema needed for that specific question: **only ever re-evaluate
transactions where `categoryManuallySet=false`.** A transaction a user explicitly confirmed —
even if that confirmation was "Other" — must never be silently overwritten by a later, smarter
pass, regardless of how confident the new layer is.

**Re-run shape:** not a one-off migration script — an ongoing, idempotent, resumable batch
capability, since every future rule/corpus/vocabulary improvement should be able to reach back into
history, not just the moment it ships. Concretely:

- A backfill pass = query `categoryManuallySet=false AND decisionSource='MERCHANT_DEFAULT'` (or,
  once versioning exists, `categorization_version < current`), re-run `CategorizationService`, and
  apply only if the new suggestion clears the confidence bar.
- LLM-driven backfill compounds naturally with the caching design from Section 2: one LLM call
  against a shared, unique canonical merchant unlocks every historical "Other" transaction for that
  merchant across every user who has one, not just future imports — the backfill isn't a separate
  cost center, it's a side effect of the same cache-fill mechanism.
- Rate/cost control applies here exactly as it does to cold-start (Section 4's failure-mode list):
  a backfill touching a large historical stock, if it involves paid inference, needs the same
  batching/rate-limiting discipline as a big first-time import.
- User-facing behavior: this should surface as quiet improvement, not a re-opened review queue for
  things the user already implicitly accepted by not engaging — an optional one-time notice ("we
  recategorized N transactions automatically") is enough; don't re-nag.

This is exactly why **Section 10's versioning fields matter beyond debugging** — without a
`categorization_version` stamped per decision, every backfill pass has to either re-process
everything from scratch (expensive, wasteful) or track eligibility ad hoc. With it, "find
everything still on an old version" is a single indexed query.

*This first implementation plan does not yet build a backfill job — Task 1–3 change only how new
suggestions are computed. A backfill pass that re-runs the new logic against existing
`categoryManuallySet=false` transactions is natural follow-on work once these land.*

---

## 10. Categorization Versioning & Auditability

**What's already there** (confirmed against the actual schema, not assumed):

- `Transaction.decisionSource` — a real, persisted, queryable enum column
  (`GLOBAL_RULE`/`USER_RULE`/`LEARNED_PATTERN`/`KEYWORD_MATCH`/`MERCHANT_DEFAULT`/`MANUAL`/
  `FILE_PROVIDED`), plus a companion `decisionRuleId` FK back to the specific `CategoryRule` that
  fired when applicable. Already exactly the "why" mechanism this section would otherwise need to
  propose from scratch. **This plan adds one new value: `STRUCTURAL_P2P`** — no CHECK constraint
  exists on the column (confirmed against `V17__category_rules_decision_source.sql`), so adding a
  value needs no migration, matching the pattern already used for `Transaction.Source` and
  `Transaction.ReconciliationStatus`.
- `Transaction.decisionConfidence` — a persisted 0–100 integer, nulled out the moment a category
  is manually set. No tiering today, just the raw number.
- `needsCategoryReview` / `categoryManuallySet` — persisted and already distinct, as covered above.
- Admin-facing audit logging already exists and is fairly built out: `RuleService`,
  `MerchantService`/`MerchantReviewService`, and `CategoryService` all write structured entries
  (`RULE_CREATED/UPDATED/DELETED`, `MERCHANT_MERGED/APPROVED/DISCARDED`,
  `CATEGORY_CREATED/RENAMED/DELETED`, etc.) to a generic `audit_logs` table with actor, entity,
  timestamp, and a JSONB metadata field.

**What's genuinely missing** — confirmed absent by grep, not just unlikely:

- **`categorization_version`** — no version/ruleset-hash/model-version concept exists anywhere
  today. Not built in this first plan (no backfill job exists yet to consume it) — worth adding
  deliberately once a backfill pass is being built.
- **Evidence/provenance for corpus and LLM decisions specifically** — `decisionRuleId` already
  covers "why" for rule-sourced decisions. Once the shared corpus and LLM fallback exist, they need
  the equivalent. Not needed for this plan's P2P detector, since it's rule-shaped (deterministic,
  explainable from the description text itself, no external mapping to trace back to).
- **A confidence tier**, not just the raw integer, for the UI-facing distinction between
  "auto-applied silently," "applied with a soft badge," and "held for review" described in Section
  3 — the raw 0–100 number already exists and can derive a tier without a schema change, this is
  really a display-layer gap, not a persistence one.

**Category identity — a real but bounded risk, not a hypothetical one.** `CategoryRule.actionValue`
resolves by category *name*, case-insensitively — not by a stable ID — and `Category` has no
system-level key independent of its display name. This is mitigated for the default taxonomy
specifically: `CategoryService` throws `403` on any attempt to rename or delete an `isSystem=true`
category, and the global rule seed's values match those default names exactly, so the pre-seeded
categories can't drift. The gap is real for **user-created custom categories** — nothing stops a
user from creating a category that happens to share a name with a system category's rule target.
This plan's P2P detector returns the plain string `"Transfer"` (the existing default category, the
same way every other `Suggestion` in `CategorizationService` already returns a plain category-name
string) — consistent with existing behavior, not a new risk this plan introduces.

---

## 11. Shared Corpus Governance

The instinct that "a shared corpus becomes a data-quality system, not a lookup table" is correct,
and the admin/audit infrastructure needed to run it **already exists** — `AdminRuleController`,
`AdminMerchantReviewController`, `AdminUserMerchantController`, and the `audit_logs` table are
real, built, and already handle a very similar problem (merchant approve/rename/merge/discard).
Corpus governance should extend this pattern, not invent a parallel one.

**Trust tiers, not a single binary "is this trusted":**
- **Provisional** — 1 confirmation, used for that one user/context only, not yet shared globally.
- **Trusted** — a threshold of *independent* confirmations (start conservative, e.g. 3+ distinct
  users, or 1 confirmation sourced from an explicit curated/admin rule) — shared globally, eligible
  for auto-apply at a higher confidence tier.
- **Disputed** — confirmations split roughly evenly across two or more categories. Never
  auto-apply a disputed mapping silently; fall back to per-user learning or hold for review
  instead.

**Weighting, not equal-weight counting.** Three confirmations from three different users should
count for more than three corrections from one user repeatedly re-confirming their own personal
labeling habit. Recency should matter too — old confirmations should soft-decay rather than stay
permanently authoritative, since a merchant's real category can genuinely change (a brand pivots,
a location changes use) and a stale mapping shouldn't be sticky forever. A light anti-abuse
consideration is also worth having: a brand-new account's confirmation probably shouldn't carry
full weight immediately.

**Correcting a bad global mapping:** two paths, both needed. Organic — enough contrary
confirmations flip a "trusted" mapping back to "disputed" or to the new majority answer. Admin —
for cases where waiting for organic disagreement would take too long, or the mapping is actively
harmful, extend `AdminMerchantReviewController`'s existing approve/merge/discard pattern to cover
corpus entries directly, with the action landing in `audit_logs` exactly like every other admin
action does today — no new audit mechanism needed, just a new entity type flowing through the
existing one.

**Periodic re-validation, not permanent silence.** Even a "trusted" mapping should occasionally
resurface for confirmation on a small sample of transactions (not every time — that's exactly the
review-fatigue failure mode from Section 4) so drift gets caught before it accumulates into a
larger, harder-to-unwind wrong answer.

*Not built in this first plan — the shared corpus (#7 in Section 8) is explicitly sequenced after
#1–#3, once the deterministic layers have shrunk and stabilized the remaining problem.*

---

## 12. Product Principles (Non-Negotiable)

- **A user-confirmed category always wins.** The system never silently overwrites a category a
  user explicitly set or confirmed — enforced today by the existing `categoryManuallySet` flag,
  and every future layer (corpus, LLM, backfill) must respect it without exception.
- **Unknown is preferable to confidently wrong.** Never trade honest uncertainty for a coverage
  number — a wrong category silently corrupts dashboards and budgets; an honest "needs review"
  visibly asks for help.
- **Coverage is never optimized independently of accuracy.** Every coverage metric ships paired
  with a correction-rate guardrail for the layer that produced it.
- **P2P/person-identifying narration never enters shared, cross-user storage.** Transfers are
  solved structurally (a real Transfer category + detection), not by merchant lookup — which also
  means the privacy-sensitive share of the data never needs to leave the per-user boundary at all.
- **A user should never have to categorize the same merchant twice.** One correction propagates
  immediately to that user's own history, and — for merchants eligible for sharing — feeds the
  corpus for everyone else.
- **Every categorization decision is explainable.** Not "the algorithm said so" — a stored
  decision source, and for corpus/LLM decisions, real provenance back to the specific mapping or
  call that produced it.
- **No layer silently overrides a higher-trust layer.** An LLM suggestion never overrides a rule or
  keyword match; the global corpus never overrides a user's own explicit rule. This plan's P2P
  detector is placed last in the waterfall specifically so it never overrides an existing rule,
  learned pattern, or keyword match — it only ever fires once everything else has already missed.
- **Every new capability degrades gracefully.** If the corpus service or the LLM is unavailable,
  the deterministic layers still work end to end — nothing about premium categorization becomes a
  single point of failure for import itself.

---

## 13. Monitoring & Quality Control

Continuous, layered — not a periodic report that only catches problems in hindsight:

- **Extraction-failure-rate alert**, per bank/parser — a regression here silently inflates "Other"
  without any categorization change even happening, and this repo's own history shows per-bank
  parser regressions are a real, recurring risk category.
- **Correction-rate regression alert**, per decision source, post-release — a bad rule, a bad
  corpus write, or a prompt change should be caught by a jump in how often users undo it, ideally
  via a canary rollout to a percentage of imports before going broad, not discovered after the
  fact.
- **Unresolved-volume-growth alert** — a sudden rise in the review queue, globally or concentrated
  on one bank, is an early signal of either an upstream format change or a matching regression.
- **Corpus dispute alert** — a mapping crossing from "trusted" into "disputed" (Section 11) should
  actively notify for review, not silently degrade in place.
- **LLM cost/volume alert** — an unexpected spike in unique-merchant LLM calls, particularly during
  a large first-time import or a backfill pass, should be caught before it becomes a cost surprise.
- **Category-distribution sanity check**, periodic/batch — comparing category-share distribution
  against historical norms per bank/user-segment catches slow drift that per-transaction alerts
  miss.

---

## 14. Do Not Build (Anti-Goals)

- No per-transaction LLM categorization at scale — always cache-keyed per unique canonical
  merchant, never called once per transaction.
- No synchronous LLM calls inside the import request path — always async/best-effort; import must
  never block on categorization.
- No free-text AI-generated categories — LLM output is always constrained to the user's actual,
  closed taxonomy list.
- No sharing of person-to-person transfer data into any global or cross-user store, ever.
- No optimizing "Other"% in isolation, without a paired correction-rate guardrail.
- No last-write-wins mutation of shared corpus entries — always versioned/voted, never a single
  mutable cell one correction can silently flip.
- No silent overwrite of a `categoryManuallySet=true` transaction, regardless of how confident a
  new layer is.
- No new taxonomy categories invented ad hoc by an automated layer — taxonomy changes are a
  deliberate product decision, never a side effect of a rule or LLM change. (This plan's P2P
  detector routes into "Transfer," an existing default category — it does not add a new one.)
- No blocking the entire import pipeline on the health of any single categorization layer.

---

## 15. Merchant → Category, or Merchant + Context → Category?

Pure **Merchant → Category** is the right default model for the large majority of merchants — a
specific restaurant, a specific SaaS subscription, a specific utility genuinely is one category.
It breaks down specifically for a small, identifiable class of **platform/aggregator merchants**,
and that class splits into three distinct sub-types that each need different handling — treating
them as one generic "add context" problem would over-engineer the 95% of merchants that don't need
it:

1. **Payment-rail / wallet merchants** (Paytm, PhonePe, GPay used as a pass-through). The merchant
   name here isn't the payee at all — it's the payment method. Category should never be derived
   from "Paytm" as a merchant; the system needs to look past the rail to whatever real payee token
   sits inside the narration (a wallet top-up narration and a "PAYTM-SWIGGY-..." narration are
   different problems entirely).
2. **True multi-category platforms** (Amazon, Reliance conglomerate-wide, Google). Same legal
   entity, genuinely different lines of business, and the narration alone often can't disambiguate.
   This is the one case that legitimately needs Merchant + Context — recurring small amount tends
   to mean subscription, one-off larger amount tends to mean a purchase, and the first time a truly
   ambiguous case appears, explicit user disambiguation ("you've paid Amazon 3 times this month for
   different things — want to label these separately?") beats guessing.
3. **Conglomerate sub-brands that already disambiguate themselves in the narration** (`RELIANCE
   JIO` vs `RELIANCE FRESH` vs `RELIANCE PETROLEUM` appear as genuinely different strings in real
   bank narrations). This is *not* actually a Merchant+Context problem — it's a normalization-
   granularity problem. If the canonical merchant key is kept at the sub-brand/legal-entity level
   instead of being collapsed up to a coarse parent-brand identity, most apparent "polymorphism"
   resolves away on its own, with no extra logic required.

**Recommendation:** don't build a general Merchant+Context model everywhere. Instead, (a) keep
canonical merchant identity granular by construction, so sub-type 3 mostly disappears as a problem
before it starts; (b) maintain a small, explicitly-curated exception list for the genuinely
irreducible cases (sub-types 1 and 2 — realistically under 50 entries); (c) only that short list
gets excluded from single-answer global caching and given amount/frequency heuristics or
per-transaction (not per-merchant-cached) treatment. This keeps the common case simple and cheap
while still handling the real polymorphic merchants correctly, rather than adding
context-awareness overhead to every merchant lookup in the system.
