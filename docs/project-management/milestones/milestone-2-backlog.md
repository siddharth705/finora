# Deferred work backlog

Everything the Import Reliability milestone deliberately did not do. It was written as a
"Milestone 2 backlog", which was wrong in one important way: a backlog is a list of leftovers, and
leftovers make an incoherent release. A milestone should represent one business outcome.

**Milestone 2 is now defined as [Import at Scale](milestone-2-import-at-scale.md)** — make Finora
capable of processing large, complex and varied statements reliably, with full visibility into the
import lifecycle. That charter pulls what it needs from this list and names what it deliberately
leaves behind. This file is the pool it draws from, not the plan.

Where each item went:

| Item | Now |
|---|---|
| Multi-account duplicate review | **Milestone 2** — closes a correctness gap |
| WI1A — bulk recategorization | **Milestone 2** — carried over, do it early |
| Corpus-driven regression | **Milestone 2** — and its first dependency |
| Cross-browser Playwright projects | **Milestone 2** — carried over |
| Merchant Intelligence Workbench (WI4A) | **Milestone 3** — higher leverage on top of reliable imports |
| Cross-user merchant intelligence | Its own milestone; changes what a merchant *is* |
| Security follow-ups, Bug 30 | Release-blocking work running **alongside** Milestone 2, not defining it |

Nothing here is a defect except where it says so. The detail below is kept because the reasoning
behind each deferral is worth more than the list of names.

---

## Merchant intelligence

### WI4A — Merchant Intelligence Workbench (Admin Portal)

WI4 gave operators a Merchant Review Center that is *sufficient*: it lists temporary merchants,
shows transaction counts, and supports approve / rename / merge / delete. It is not yet
*productive*. An operator working a backlog is currently the similarity engine — they read two
names, decide the names mean the same business, and type one into the other's merge field.

What the workbench would turn into something the system proposes and the operator confirms:

- Similarity scoring between merchants, with the evidence behind the score, so a suggested merge is
  auditable rather than a number.
- Rich side-by-side comparison — aliases, sample narrations, learning history, spend totals. "Are
  these the same business?" is not answerable from two name strings.
- Alias graph visualisation, so an operator can see what a merge would absorb before committing.
- Bulk merge and apply-to-similar, the operator-side equivalent of what WI5 gave the user.
- **Search and filter.** Currently absent, and the sharpest of these. The list is ordered
  oldest-first (deliberately — a newest-first queue buries the oldest outstanding work forever), so
  finding one account's merchants means paging through everything. The e2e suite has to backdate
  seeded merchants to make them findable at all; that workaround is this gap stated as a cost.

**Evidence first.** What the workbench should *suggest* is best answered by watching real operators
work a real backlog. Build the search and filter — that need is already demonstrated. Hold the
scoring and the graph until there is a backlog big enough to justify them.

### Cross-user merchant intelligence

The canonical registry, cross-user suggestions and platform-wide merge. Deferred by explicit product
decision (design doc §1.2): merchants, aliases and learning are per user, and the Merchant Review
Center operates within one account.

This is its own milestone, not a work item. It changes what a merchant *is*, and it carries a
privacy question the current design does not have to answer: one customer's spending shaping another
customer's categorisation is a different product from the one that exists today.

---

## Import

### Duplicate review for multi-account (multi-section) PDFs

The single-account path got WI5's review screen. The multi-section path still auto-unticks flagged
rows — the exact silent-filter behaviour WI5 removed — because its review state is per detected
account and wiring it correctly means restructuring that state rather than repeating the component.

Left alone deliberately: doing it carelessly would make the path worse, not better. The
single-account path is where the overwhelming majority of imports land.

### WI1A — move bulk recategorization onto the asynchronous learning pipeline — **done**

`TransactionService.bulkRecategorize` called `CategorizationService.learn` synchronously, in a loop,
up to `TransactionDto.MAX_BULK_IDS` (500) times inside one transaction. That is the import path's
exact pre-WI1 shape: one lost race against `UNIQUE(user_id, merchant_id, category_id)` rolled back
all 500 recategorizations.

WI1 left it alone because its scope was the import path, but the objective was to remove the last
synchronous *batch* learning path, not to leave one behind. Single interactive actions
(`updateCategory`, `confirmMerchantCategory`, `create`) stay synchronous by design — see
`CategorizationService.learn`'s doc comment.

Of everything on this list, this is the one that was arguably a latent defect rather than an
enhancement. Delivered as item 3 of Milestone 2; see that charter for what was built and for the
audit confirming no other synchronous batch learning path exists.

### Header row rendered as non-text (observed on a real SBI savings statement)

Diagnosed 2026-08-10 via `PdfPipelineDiagnostic` against a real SBI statement: native extraction
produces 174 text runs, and every transaction row's own data — both dates, both debit/credit cells,
the balance, the narration — extracts as complete, readable text. The header row does not. Only two
isolated fragments extract anywhere near the top of the table ("Balance" at the rightmost column
position, one short label far to the left); nothing extracts where the Date / Description / Debit /
Credit column headers should be. `looksLikeHeaderRow` correctly reports no header found — there is
no coherent row of column names to detect, recognized or not.

This is a different failure class from anything the pipeline currently distinguishes. Not
`IMPORT_SCANNED_OCR_REQUIRED` — the document has real text throughout, not zero. Not a `HEADER_HINTS`
vocabulary gap — there is no header text present to fail to match against. Most likely the header
row's labels are rendered as a graphic/styled banner, or use a header-specific font PDFBox cannot
decode, while the data rows below use an ordinary text font.

Recognizing this layout would mean inferring column identity positionally from the data rows
themselves (this statement's rows have a stable shape: two dates, two debit/credit cells, a balance,
then narration) rather than from a header row at all — a genuinely new acquisition/table-location
capability, not an extension of `HEADER_HINTS` or `WRAPPED_HEADER`. Left here rather than built ad
hoc: one real document is one data point, and per the evidence-first principle already governing the
OCR/extraction work, a positional-inference capability needs more than one observed case before its
column-order assumptions can be trusted.

No redacted trace fixture exists for this yet. `trace-capture.sh` refuses to write one — a trace
preserves structure a table *did* locate, and this document has none to preserve. The next occurrence
of this pattern (same bank or another) is what turns this from an anecdote into evidence.

### Boilerplate disclaimer text merged into a transaction's description (observed on a real HDFC credit-card statement)

Diagnosed 2026-08-10 via `PdfPipelineDiagnostic` against a real HDFC credit-card statement: the
last transaction on the page is immediately followed, a few points below, by a "Note:"-led
rewards-program disclaimer paragraph (several lines explaining how bonus reward points are
calculated). `PAGE_FOOTER` and `STATEMENT_CLOSING_MARKER` correctly exclude page-number footers and
"end of statement" banners from ever being folded into the last real transaction — this disclaimer
matches neither pattern, so it flows through the ordinary trailing-continuation merge and ends up
appended, in full, onto that transaction's own description.

Traced precisely: the disclaimer's first line ("Note:") sits far enough below the transaction's date
row (34pt) that `continuesTheBlock`'s learned `blockPitch` is set from that gap — but the disclaimer's
own internal line spacing is much tighter (~7.3pt), so `separatesItsBlocks` correctly refuses to let
`continuesTheBlock` extend the merge past the free two-row count cap on pitch grounds alone. The merge
still happens because the count-cap branch's `isNarrationOnly`/`belongsToTheRowAbove` fallback admits
each of the first rows on its own terms, and once inside the block, `continuesTheBlock` measures the
disclaimer's own (tight, self-consistent) internal pitch against `blockSeparation` freshly rather than
against the original anchor gap — passing a check it was never actually being asked here.

Not a one-line pattern-exclusion fix like `PAGE_FOOTER`/`STATEMENT_CLOSING_MARKER`, both of which
match and discard a single self-contained line. This disclaimer is a multi-line block whose *later*
lines carry no marker at all ("The 'Base NeuCoins' are calculated as...", "a) Base NeuCoins on
eligible...") — excluding only the "Note:" line would leave `trailingCountSinceLastAnchor` reset to 0
at that point, and the block would still merge starting from the very next line, just without the
word "Note" attached. A real fix needs a *stateful* "currently inside an excluded footnote block, stop
admitting continuations until the next dated anchor" flag — genuinely new state in the continuation
merge loop, not an addition to an existing regex list. Left here rather than built under time
pressure: this is exactly the kind of change that risks a second, harder-to-notice bug if rushed
through the same session that found it, and the existing continuation-merge tests already pin a lot of
adjacent, carefully-balanced behavior that deserves a full regression pass once this is designed
properly, not squeezed in alongside it.

### Genuine credit limit is architecturally unreachable on at least one real layout (observed on a real ICICI credit-card statement)

Diagnosed 2026-08-10 against a real ICICI credit-card statement, ground truth confirmed by the
cardholder: the real credit limit is ₹1,40,000.00. `PdfMetadataExtractor` reported 1,15,000.00 --
neither figure it was choosing between was real. Both matched lines came from the Most Important
Terms and Conditions section's worked example of how Minimum Amount Due is calculated ("Credit Limit
35,000.00" ... later "Credit Limit 1,15,000.00", two different hypothetical scenarios in the same
generic walkthrough every cardholder's statement prints), and -- a separate, now-fixed bug -- the
`CREDIT_LIMIT` same-line match had no `creditLimit == null` guard, so whichever of the two the scanner
reached *last* silently won. That ordering bug is fixed (see the commit that guards all seven primary
label extractions in this file the same way): the result is now deterministic, always the *first*
matching line. On this document that's "35,000.00" -- still wrong, just consistently so.

The real value genuinely does appear in the document text (confirmed directly: `` `1,40,000.00
`1,32,637.30 `14,000.00 `0.00 `` -- a Rupee-glyph font-encoding artifact prefixes each figure with a
backtick, the same class of issue `CsvParser`'s own "C" -- glyph workaround exists for elsewhere in
this codebase -- three lines below its own genuine "Credit Limit (Including cash) Available Credit
(Including cash) Cash Limit Available Cash" grid header, well within `GRID_VALUE_SEARCH_WINDOW`. But
neither the header nor the value row reaches `PdfMetadataExtractor` at all: they are not in either of
this document's two `LocatedSection`s' `auxiliaryText`. `PdfTableLocator` has already misrecognized an
unrelated, garbled header ("PAYMENT DUE DATE" merged with surrounding prose) as section 0's own table
before ever reaching this grid, and the grid itself ends up attributed to neither section. This is a
`PdfTableLocator` section/table-boundary bug wearing a `PdfMetadataExtractor` symptom, not a metadata-
parsing bug at all -- the real fix is upstream of where this was first diagnosed. Needs its own
investigation into why this specific header is misread, not a patch to the metadata scanner, which
never had a chance to see the right text in the first place.

---

## Testing and tooling

### Cross-browser and responsive Playwright projects

Configured (`npm run test:browsers`, `npm run test:responsive`) and never run to green. They
re-execute the user-portal specs against Firefox, Edge, tablet and mobile viewports.

### Regression against a real statement corpus

Phase 14 of the milestone test brief. Needs a sanitized corpus that does not exist yet — and by the
import engine's own rules, never a committed statement. The path is redacted extraction traces (see
`docs/engineering/trace-lifecycle.md`), which are reviewable and scannable.

### One `test.fixme` in the merchant review spec

`tests/admin-portal/merchant-review.spec.ts` — the row's own presentation (that it names the account
and shows the transaction count) is not asserted. Everything around it is. Reasoning is in the
comment above the test.

---

## Deliberately NOT on this list

Recorded because the temptation is real and naming it is the cheapest defence:

- More diagnostics, counters, metrics or admin graphs. The [diagnostics
  rule](../../../CLAUDE.md) applies — a diagnostic earns its place by being able to prove a proposed
  capability *unnecessary*, and the ones that exist have not yet been used in anger.
- More verification validators. Four rules ship (L3/L4/L5/L7) and the next move is corpus-driven,
  not another rule written from imagination.
- Confidence scoring on duplicate detection. The detector matches on date AND amount AND description
  being identical. There is no spectrum to score, and inventing one would put a number on a
  financial screen that nothing supports.
