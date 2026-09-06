# Extraction Sufficiency Assessment — Design Investigation (C-8.3 pre-work)

**Doc status:** OPEN / TRACKING. Last reviewed: 2026-08-15. Purpose: context document for Track A's
still-open items (#2 header vocabulary, non-ledger #3), both blocked on real-corpus acquisition,
not on this doc. Note: `CompositeOcrTrigger.java`, referenced below, has since been deleted
(2026-08-15) — the finding it's cited for is preserved in
`c8.1-c8.2-class-two-fixture-and-trigger-evaluation.md`.

**Status:** investigation only. No production code changed, nothing wired into
`RoutingTextAcquirer` or any classifier, nothing committed to `src/main`. Builds on
`c8.1-c8.2-class-two-fixture-and-trigger-evaluation.md` (the disproof of the composite OCR
trigger) and reframes the problem per the PM's explicit direction: not another OR-condition, not
a hardcoded `if missingField`, but a real capability — **Extraction Sufficiency Assessment** —
composed from row coverage + field coverage + structural integrity, producing
SUFFICIENT / UNCERTAIN / INSUFFICIENT.

This document answers the six questions the PM asked, grounded in the actual codebase. It resolves
nothing about routing or thresholds — those stay open per ADR-006 §4b/§8.

---

## 1. What makes a field "load-bearing"? Is there already a notion of required-vs-optional?

**No enforced notion exists today.** Three places gesture at it, none of them a real answer:

- `MaterialField` (`backend/src/main/java/com/finora/imports/evidence/MaterialField.java`) is a flat
  enum of 15 fields (`ACCOUNT_TYPE`, `ACCOUNT_NUMBER`, ..., `OPENING_BALANCE`, `CLOSING_BALANCE`, ...,
  `TRANSACTION_DESCRIPTION`). It names fields the evidence model reasons about; it carries no
  required/optional flag, no per-section-type applicability, nothing.
- ADR-006 §5 explicitly lists a starting set — "opening/closing balance, account identity, per-transaction
  amount and direction, at minimum" — for confirm-time enforcement, but immediately disclaims it:
  *"Which fields count as 'load-bearing' ... are implementation decisions for the detailed design that
  follows this ADR — not decided here."* (adr-006-evidence-decision-reconciliation.md §5, §8). So the ADR
  that is supposed to own this question defers it too.
- `DimensionAssessor.assessFinancialValidation` (`evidence/DimensionAssessor.java:126-194`) hardcodes
  which `MaterialField`s get a financial-validation mapping at all: `TRANSACTION_AMOUNT`/
  `TRANSACTION_DIRECTION` map to `BalanceChainValidator`, `OPENING_BALANCE`/`CLOSING_BALANCE` map to
  `StatementTotalsValidator`, and "any other `MaterialField` has no financial validator mapped to it at
  all ... reported as `INSUFFICIENT`, not a crash or a guess." This is a hardcoded field list, by the
  class's own doc comment — and it is scoped to "which fields get a financial-validation dimension,"
  not "which fields are load-bearing for sufficiency," a narrower and different question.

**What a principled, non-ad-hoc definition would look like, if it were derived rather than listed:**

The cleanest available anchor is *what StatementTotalsValidator and DimensionAssessor already treat as
required to run a check at all* — i.e., derive load-bearing-ness from "is there an existing financial
consistency check whose non-`NOT_APPLICABLE` outcome depends on this field." `OPENING_BALANCE`/
`CLOSING_BALANCE` clear this bar (StatementTotalsValidator degrades to NOT_APPLICABLE without them —
`StatementTotalsValidator.java:55-62`); `TRANSACTION_AMOUNT`/`TRANSACTION_DIRECTION` clear it via
BalanceChainValidator. This derivation is real, not ad hoc, but it is *reactive* — it tells you a field
is load-bearing because a check already depends on it, not because the statement's own structure implies
the field should exist. It would not, on its own, generalize to a field no validator currently consults
(e.g. `ACCOUNT_NUMBER`).

A second, structural anchor exists and is stronger for the class-2 problem specifically: **section
shape implies field applicability.** `PdfPreviewGenerator.buildProductSections` (the deposit-schedule
path, `PdfPreviewGenerator.java:280-287`) explicitly sets `openingBalance`/`closingBalance` to `null`
unconditionally for deposit sections, with the comment *"No opening/closing balance, no statement
period — neither concept applies to a deposit schedule the way it does to a ledger's own transaction
date range."* This is a real, already-encoded example of section-type-conditioned field applicability
— it already exists, just not generalized or exposed as a queryable rule. A ledger section's
load-bearing fields and a deposit-schedule section's load-bearing fields are already known to differ in
this codebase; nothing currently asks "for section type X, which fields are load-bearing" as a
first-class query.

**A hardcoded field list is not fully avoidable, and the codebase's own two ADR-006 corrections say so
directly** (§5: "implementation decisions... not decided here"; §3: no invented threshold without corpus
evidence). What *is* avoidable is hardcoding it as a **flat list independent of section/product type**
(the failure mode PM explicitly rejected). The defensible middle ground, grounded in what already
exists: a small per-section-type table (ledger vs. deposit-schedule vs. credit-card, mirroring
`ProductDiscovery.DiscoveredProduct` and the branching already in `PdfPreviewGenerator` between
`buildLedgerSection` and `buildProductSections`) that says which `MaterialField`s apply to that section
type at all — closing this exact gap requires deciding it as a real product/PM call, not inventing it
here. This document surfaces the gap; it does not resolve it (see §6).

---

## 2. How would field coverage be established independently of "is the Java field null"?

This is the central technical finding of the investigation, and there is good news: **the mechanism
already exists in the codebase, just not exposed as its own signal.**

`PdfPreviewGenerator.buildLedgerSection` (`PdfPreviewGenerator.java:314-328`) derives `balancePoints`
per row via:

```java
BigDecimal balance = CsvParser.parseNumeric(
        CsvParser.firstNonBlank(row, "balance", "running balance", "closing balance"));
```

`CsvParser.firstNonBlank` (`CsvParser.java:247-257`) and its sibling `CsvParser.hasHeaderMatch`
(`CsvParser.java:233-242`) both work by scanning `row.keySet()` — the **column headers PdfTableLocator
already parsed for this ledger** — for a case-insensitive match against a list of known synonyms. This
check is answerable **before and independent of whether normalization/value-parsing succeeded for any
individual row.** A row can have a `"Balance"` key present in its map with a value that fails to parse
(structurally present, extraction incomplete) or have no such key at all (structurally absent, no
extraction outcome could ever have populated it) — these are two different findings today conflated
into the same downstream null.

This is exactly the class-2 fixture's own shape: `buildReconciledSummaryNoBalanceColumnSample()`
(`PdfFixtureBuilder.java:988-1007`) builds a ledger with header row `Date, Narration, Withdrawals,
Deposits` — no `balance`/`running balance`/`closing balance` synonym anywhere in that header. `
CsvParser.hasHeaderMatch(row, "balance", "running balance", "closing balance")` on any row from this
fixture returns `false`, structurally, from the header alone — no row needs to be normalized first to
know this.

**The actual test field coverage needs, stated precisely:** for a load-bearing field F on section type
S, "is F structurally present" = does the located table's header row contain any of F's known column-
name synonyms (via `CsvParser.hasHeaderMatch`, generalized from its current three balance-synonyms to a
synonym table per `MaterialField`)? This is **independent of extraction success** by construction — it
only reads header text, never a parsed value. Compare against "is F populated" = is the resulting
`DetectedAccountInfo` field non-null. The class-2 case is exactly: structurally-absent field (header
never had it) → correctly null (there was nothing to extract) → **sufficiency assessment should read
this as "field does not apply to this document," not "field is missing due to extraction failure."**
The genuinely dangerous case sufficiency needs to catch is the *other* combination: field
structurally-present (header synonym found) but value not populated (extraction failed to derive it
from a column that demonstrably exists) — that is a real coverage gap, and today nothing distinguishes
it from the class-2 case, because both currently just show up as `null`.

**One caveat found while reading `PdfTableLocator`'s doc comment** (referenced from
`PdfPreviewGenerator.java:296-302`): "PdfTableLocator treats everything after a header as a candidate
row," so header detection itself is not infallible — a header-detection failure could masquerade as
"field structurally absent" when it is actually "header row misidentified." Field coverage built on
`hasHeaderMatch` inherits whatever false-negative rate `PdfTableLocator`'s header detection already has;
this investigation did not attempt to quantify that rate, and flags it in §6 as an open question rather
than assuming header detection is itself perfectly reliable.

---

## 3. Row coverage and structural integrity — what exists, what's missing

**Row coverage — partially covered, several real signals, none composed:**

- `DocumentClassification.Signals` (`analysis/DocumentClassification.java:146-191`) already carries
  `sections`, `rows`, `pages`, and `suspectedIncompleteByPageRatio()` (`rows > 0 && rows < pages`, an
  explicitly-labeled *heuristic*, tested against real bank corpus files but "not tuned to today's files"
  per its own doc comment — HDFC credit 2/2 and Manas_HDFC 4/2 deliberately left alone, HSBC 1/4, ICICI CC
  3/9, Bandhan 3/7 deliberately caught). This is a genuine row-coverage signal, but per C-8.1's own
  finding it is explicitly excluded from the composite trigger as "untested outside a small non-repo
  corpus, not safe to promote" (`CompositeOcrTrigger.java` class doc). It remains available as a signal;
  its safety for promotion into a new mechanism is unresolved, not settled by this document.
- `SummaryTotalsValidator`'s `locatedRowCount` parameter (`SummaryTotalsValidator.java:56-62`) already
  distinguishes "table located, N rows seen, 0 staged" from "no table located at all" — the exact
  distinction `DocumentClassification.LAYOUT_UNSUPPORTED`'s own doc comment says it *cannot* make
  (`analysis/DocumentClassification.java:71-80`, CBI vs. ICICI Saving). `SummaryTotalsValidator` already
  has the finer-grained located-vs-staged split `DocumentClassification` lacks; nothing currently
  threads `locatedRowCount` back into `DocumentClassification.Signals`.
- `SummaryTotalsValidator.PRINTED_ACTIVITY_WITH_ZERO_STAGED` is real, independent evidence: the printed
  summary asserting activity while zero rows staged. C-8.2's Finding C already showed this signal, if
  consulted, is exactly what a naive row-count-only check (`DocumentClassification`'s own `rows == 0`
  branch) is missing — it is what would let sufficiency tell "dormant, correctly read" apart from
  "table failed to parse," which `DocumentClassification.LAYOUT_UNSUPPORTED` and the composite trigger's
  condition 2 both currently cannot.

**Structural integrity — well covered by BalanceChainValidator/StatementTotalsValidator, but C-8.1/C-8.2
already proved the specific composition trap:**

- `BalanceChainValidator` has real, tuned anti-noise guards (`MIN_PAIRS_FOR_A_VERDICT=2`,
  `FAILED_THRESHOLD=0.5`, `MIN_DISCREPANCIES_FOR_FAILED=2` — `BalanceChainValidator.java:92-117`),
  deliberately built to not flag a single scattered discrepancy as systematic.
- `StatementTotalsValidator` has **zero tolerance** by design (`difference.signum() == 0` is the only
  non-`FAILED` branch, `StatementTotalsValidator.java:76-78`) — appropriate for its own stated purpose
  (flag for review) but, per C-8.1 Finding B, defeats BalanceChainValidator's guard the moment the two
  are OR'd for a new purpose neither was built for.
- `suspectedCause` on both validators (`OPENING_BALANCE`/`TRANSACTIONS` on `StatementTotalsValidator`;
  `DIRECTION`/`ROW_GROUPING`/`MISSING_OR_EXTRA_ROWS`/`AMOUNTS` on `SummaryTotalsValidator`) is real
  scoping information ADR-006 §5 already builds confirm-time enforcement around — a structural-integrity
  signal not just "reconciles or not" but "reconciles or not, and if not, what specifically is
  implicated," which any sufficiency model should read rather than discard.
- What's genuinely missing structurally: nothing in `DocumentClassification`, `BalanceChainValidator`,
  or `StatementTotalsValidator` reasons about *marker rows* (OPENING BALANCE/CLOSING BALANCE lines typed
  as ordinary transactions — C-8.1 Finding A). `ClosingBalanceEvidenceVerticalSliceTest`'s own
  `realTransactionRows` filter is the only place in the codebase that already excludes them before
  validating, and nothing generalizes that filter as a first-class concept any new consumer of
  `section.verification()` would know to apply. A sufficiency assessor that reads
  `StagedAccountSection.verification()` directly, rather than recomputing against filtered rows the way
  the vertical-slice test does, inherits this exact trap.

---

## 4. Sketch of a SUFFICIENT / UNCERTAIN / INSUFFICIENT model

Design-level only, per the PM's scope. Three input categories, each already partially grounded in real
code per §§1-3:

```
Native extraction
   │
   ├── Row coverage
   │     inputs: DocumentClassification.Signals-style counts (rows, sections, pages),
   │             SummaryTotalsValidator's locatedRowCount split (located vs staged),
   │             PRINTED_ACTIVITY_WITH_ZERO_STAGED as corroborating printed-summary evidence
   │     avoids C-8.2 Finding C by consulting the printed summary BEFORE calling zero rows
   │     insufficient -- a summary that itself asserts zero activity makes zero rows expected,
   │     not suspicious.
   │
   ├── Field coverage  (the genuinely new ingredient)
   │     inputs: per load-bearing field F (per §1's still-open definition), structural presence
   │             via header-synonym matching (CsvParser.hasHeaderMatch, generalized from its
   │             current 3-synonym balance list), independent of whether F's value parsed
   │     three-way per field, not two: STRUCTURALLY_ABSENT (no header synonym -- field does not
   │     apply, not a gap), STRUCTURALLY_PRESENT_AND_POPULATED, STRUCTURALLY_PRESENT_BUT_NULL
   │     (header synonym found, value still null -- the real coverage gap; this is the one the
   │     class-2 fixture is NOT an example of, precisely because it's STRUCTURALLY_ABSENT there)
   │
   └── Structural integrity
         inputs: BalanceChainValidator + StatementTotalsValidator + SummaryTotalsValidator
         outcomes, RECOMPUTED against real-transaction rows with marker rows filtered
         (ClosingBalanceEvidenceVerticalSliceTest's realTransactionRows pattern, generalized --
         never read StagedAccountSection.verification() as-is, per C-8.1 Finding A) and READ
         WITH suspectedCause, not collapsed to a boolean (avoids C-8.1 Finding B by NOT OR-ing
         StatementTotalsValidator's zero-tolerance FAILED with BalanceChainValidator's
         anti-noise WARNING as equivalent severities -- a StatementTotalsValidator FAILED with
         suspectedCause=OPENING_BALANCE on an otherwise-clean statement is a different finding,
         and arguably a different severity, than a BalanceChainValidator FAILED spanning most rows)
   │
   ▼
Sufficiency decision
   SUFFICIENT   -- row coverage adequate (including the printed-summary corroboration path),
                   every load-bearing field for this section type is either STRUCTURALLY_ABSENT
                   (does not apply) or STRUCTURALLY_PRESENT_AND_POPULATED, structural integrity
                   clean (recomputed, marker-filtered)
   INSUFFICIENT -- any load-bearing field is STRUCTURALLY_PRESENT_BUT_NULL (the class-2 fixture's
                   inverse -- header exists, value doesn't), OR row coverage signals a table that
                   demonstrably failed to parse (located-but-unstaged with no printed-zero
                   corroboration), OR structural integrity finds a systematic (not scattered)
                   reconciliation failure
   UNCERTAIN    -- signals disagree or are individually weak: e.g. a single scattered balance
                   discrepancy (C-8.2's single-5.00-off case) that BalanceChainValidator's own
                   anti-noise guard already downgrades to WARNING, or a row/page ratio suspicion
                   with no corroborating validator failure -- escalate to human review or a
                   corroborating second pass, not an automatic OCR trigger, since C-8.1/C-8.2 gave
                   no evidence OCR would even resolve a scattered single-row discrepancy
```

**How this differs from the disproven composite trigger, concretely:**

- Class-2 case: the disproven trigger had no field-coverage input at all, so it structurally could not
  fire on the class-2 fixture (C-8.1 §3). This model's field-coverage category, applied to the class-2
  fixture, correctly reports `CLOSING_BALANCE`/`OPENING_BALANCE` as `STRUCTURALLY_ABSENT` (no balance
  header synonym), which under the sketch above does **not** trigger `INSUFFICIENT` — because the field
  genuinely does not apply to this ledger shape, matching the fixture's own designed intent (a bank that
  prints no running balance is not a defective extraction). **This is a real, load-bearing open
  question, not a solved one:** if the PM's product intent is instead "we should always be able to
  derive closing balance one way or another, even without a balance column, e.g. via
  StatementTotalsValidator-style arithmetic reconstruction," then STRUCTURALLY_ABSENT for a genuinely
  load-bearing field should itself be a sufficiency gap, not a pass. This document does not resolve
  that — see §6.
- Finding A (marker-row pollution): closed by recomputing structural-integrity validators against
  filtered real-transaction rows, never reading `section.verification()` raw, mirroring the pattern
  `ClosingBalanceEvidenceVerticalSliceTest` already uses.
- Finding B (StatementTotalsValidator zero-tolerance vs. BalanceChainValidator anti-noise): closed by
  not flattening both validators' outcomes to a single boolean OR — reading `suspectedCause` and
  severity distinctly, and routing a scattered single discrepancy to `UNCERTAIN` rather than
  `INSUFFICIENT`.
- Finding C (dormant vs. broken): closed by having row-coverage consult
  `PRINTED_ACTIVITY_WITH_ZERO_STAGED` before treating zero staged rows as insufficient.

---

## 5. Corpus fixtures needed beyond what C-8.1/C-8.2 already built

C-8.1/C-8.2 built: the class-2 fixture (reconciled, no balance column), a single-trailing-discrepancy
negative fixture, and exercised the existing `separate_debit_credit_balance_sample.pdf` and other
existing fixtures against the (disproven) trigger. Still missing, specific to validating *this* model
before it could influence routing:

- **A `STRUCTURALLY_PRESENT_BUT_NULL` fixture** — the genuine positive case field coverage exists to
  catch: a ledger whose header row DOES contain a balance-synonym column, but where extraction fails to
  populate a value for it (e.g. a malformed/unparseable balance cell, or a column whose header matches
  but whose values are consistently blank/garbled). Nothing in the current corpus distinguishes this
  from the class-2 (`STRUCTURALLY_ABSENT`) case — building one is the single most important missing
  fixture, because it is the only way to prove field coverage actually discriminates the two cases
  rather than just re-deriving the same null check under a new name.
- **A per-section-type deposit-schedule fixture exercised through this model** — `buildProductSections`
  already sets balance fields to null unconditionally for deposit schedules
  (`PdfPreviewGenerator.java:280-287`); a sufficiency model that doesn't special-case section type would
  misclassify every legitimate deposit schedule as `STRUCTURALLY_ABSENT`-therefore-fine only by
  accident of matching the class-2 verdict, not by actually reasoning about section type. Worth an
  explicit fixture + test to pin this, not just trust it falls out correctly.
- **A header-detection-failure fixture** — per §2's caveat, a document where `PdfTableLocator`
  misidentifies the header row (so a real balance column's header text is not where `hasHeaderMatch`
  looks). This tests whether field coverage silently reports false `STRUCTURALLY_ABSENT` when the truth
  is "header detection failed," a different bug field coverage should not be blamed for masking.
  C-8.1/C-8.2 did not build or need this; a sufficiency model built partly on header presence does.
- **A genuinely complete statement run through the RECOMPUTED (marker-filtered) validators**, to confirm
  the fixture from Finding A (`separate_debit_credit_balance_sample.pdf`) reports clean under the
  recompute discipline this model requires — C-8.1 already showed this works when recomputed
  (`classTwoFixture...`-adjacent tests reference this), but no dedicated fixture test currently pins
  "recomputed marker-filtered validators on the canonical happy-path fixture → SUFFICIENT" as its own
  regression, separate from the raw-verification-report trap it's guarding against.
- **A multi-account/composite-statement fixture** exercised through field coverage, since load-bearing
  fields may differ per section within one document (a ledger section and a deposit section in the same
  PDF) — nothing in C-8.1/C-8.2's fixtures combines two section types in one document to test whether
  field coverage is correctly scoped per-section rather than per-document.

---

## 6. Open design questions for the PM — not resolved here

1. **§1's core gap:** there is no principled, already-derivable definition of "load-bearing field per
   section type" anywhere in the codebase. The two available anchors (existing-validator-dependency, and
   `buildProductSections`'s hardcoded per-product-type null-out) are real but partial and would need a
   real per-section-type table built and product-approved — this is a product decision (which fields
   genuinely matter for which statement types), not something derivable purely from code archaeology.
2. **The class-2 case's actual resolution is still ambiguous at the product level, not just the
   engineering level.** Is "no balance column at all, but everything else reconciles" something Finora
   should treat as SUFFICIENT (the bank genuinely doesn't print one, nothing is wrong) or something that
   should always attempt further derivation/OCR because closing balance is considered non-negotiable for
   every ledger regardless of what the source document prints? §4's sketch defaults to the former
   (structurally-absent ≠ insufficient) because that matches the fixture's own designed premise, but this
   is exactly the kind of product call the PM has said he doesn't want made for him.
3. **Header-synonym coverage is currently exactly three strings** (`"balance"`, `"running balance"`,
   `"closing balance"`), tuned to the existing corpus. Generalizing `hasHeaderMatch`/`firstNonBlank` to
   other `MaterialField`s (account number, IFSC, credit limit, etc.) needs the same "found by the tests,
   not reasoned out first" discipline `BalanceChainValidator`'s own thresholds document — i.e., real
   corpus evidence per field, not a synonym list invented from first principles. Not attempted here;
   flagged as real, non-trivial follow-on work.
4. **Whether `DocumentClassification.suspectedIncompleteByPageRatio()` is safe to promote** into a new
   mechanism remains unresolved from the prior investigation and is not re-litigated here — it's real,
   tested against a small real corpus, and explicitly called "not tuned to today's files" by its own doc
   comment. Using it inside a sufficiency model's row-coverage category carries the same promotion risk
   C-8.1 already flagged for the composite trigger.
5. **Header-detection reliability is assumed, not measured.** Field coverage's entire value proposition
   rests on `PdfTableLocator`'s header-row identification being trustworthy; this investigation did not
   quantify its false-negative rate on the real corpus. If it is not reliable, field coverage inherits a
   silent failure mode that looks exactly like a genuine `STRUCTURALLY_ABSENT` finding.
6. **UNCERTAIN's downstream action is undefined by this document on purpose.** ADR-006 §4b already
   defers the exact OCR-trigger threshold to detailed design; whether `UNCERTAIN` should mean "escalate
   to OCR," "flag for human review only," or something else entirely is squarely the routing decision
   ADR-006 says isn't made yet, and this document does not make it either.
