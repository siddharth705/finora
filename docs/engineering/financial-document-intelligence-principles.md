# Financial Document Intelligence Engine — Principles & Roadmap

**Status:** Phase 0 is in effect immediately — it governs how code is written from this point
forward. Phases 1–2 describe near-term, concrete work. Phases 3–4 are a documented *direction*,
not a commitment — see "Why Phases 3–4 stay a direction, not a roadmap item" before starting
anything in them.

**Relationship to other docs:** this is an engineering-practice document (naming, layering,
testing, sequencing) — the sibling to `CODING_STANDARDS.md` in this same folder, scoped to the
statement-import subsystem. It is not a replacement for `docs/statement-intelligence-engine-spec.md`
(a design-stage spec written before real PDF parsing existed in this codebase, and now stale in
places — e.g. it specs "rule-based PDF parsers (per bank)," which is exactly the architecture this
document rules out). If the two ever conflict, this document reflects what's actually true today.

---

## The one paragraph that matters most

> From this point forward, Finora does not evolve by adding support for individual banks. It
> evolves by adding support for new document *capabilities*. Every new parser change must answer
> "what financial document feature does this solve?" — not "which bank does this support?" A new
> document should never introduce a new parser; it should either reuse existing capabilities or
> justify exactly one new reusable one. Bank names may appear only where they represent business
> metadata (`BankRegistry`, `Bank`, `SupportedBank`, `BankAlias`). Parser classes, extractors,
> services, diagnostics, tests, and fixtures stay generic and capability-driven. This is what lets
> the platform improve through reusable capabilities as banks change layouts or converge on similar
> ones, instead of accumulating institution-specific code. Support for a new institution should
> usually mean *recognizing it uses capabilities we already understand* — not writing a new parser.

Everything below is that principle worked out into specifics.

---

## The Golden Rule

> **Every new financial document must either reuse existing capabilities or justify exactly one
> new reusable capability. If supporting a document requires multiple institution-specific rules,
> stop implementation and revisit the abstraction before continuing.**

This is the load-bearing rule everything else in this document exists to serve. Before opening a
PR that touches statement import, ask:

1. What capability am I adding?
2. Can another institution reuse this, unchanged?
3. Does this reduce coupling to a specific document's shape, or increase it?
4. Would this class/method/test still make sense with the bank's name deleted from it?

If the answer to (4) is no, the abstraction isn't right yet — go back to the Golden Rule before
writing more code, not after.

---

## Financial Document, not PDF

The pipeline is described below in PDF-specific class names (`PdfTextExtractor`,
`PdfTableLocator`, ...) because PDF is the only input format implemented today. That's an
implementation detail, not the model to design around. The conceptual pipeline is format-agnostic:

```
Financial Document (PDF today; CSV, Excel, image, scanned PDF, email statement later)
        │
Classification         -- what kind of document is this, what format
        │
Layout Understanding    -- which capabilities does it use (grid metadata, running balance, ...)
        │
Metadata Detection       -- account holder, number, IFSC, statement period, credit limit, ...
        │
Transaction Table Detection -- where the actual rows are, how many sections
        │
Field Extraction           -- date, description, amount, type per row
        │
Validation                  -- arithmetic checks, balance-chain reconstruction, plausibility
        │
Confidence                   -- how sure is each field, not just "did it parse"
        │
Review                        -- user sees and corrects what confidence didn't resolve
        │
Import Session                  -- staged, then confirmed into the ledger
```

Today's PDF classes each own one stage of this (`PdfTableLocator` = table detection,
`PdfMetadataExtractor` = metadata detection, `TransactionNormalizer` = field extraction, and so
on). When CSV or Excel support is genuinely needed, the target is a second implementation of the
early stages (classification, layout understanding) that still feeds the *same* downstream
stages (validation, confidence, review, import session) — not a parallel pipeline. `Confidence`
doesn't exist as a first-class concept yet (see Phase 3) — that's a known, named gap, not an
oversight. What every row *does* get today, whether it parses or not, is "Never lose information"
below: nothing is silently dropped anymore.

---

## Phase 0 — Engineering Standards (in effect now)

### Naming

| Don't | Do |
|---|---|
| `HdfcParser`, `AxisPdfExtractor`, `UnionBankReader`, `SbiStatementReader` | `PdfTableLocator`, `PdfMetadataExtractor` |
| `HdfcTataNeuPdfPreviewGeneratorTest` | `WrappedDescriptionCreditCardPdfPreviewGeneratorTest` |
| `AxisFixtureBuilder`, `RealHdfcDiagnosticTest`, `RealUnionBankDiagnosticTest` | `PdfFixtureBuilder`, `PdfPipelineDiagnostic` |

A class/test/fixture name should survive, unchanged, the day a second bank ships the exact same
layout. If a name only makes sense for one institution, it's describing the wrong thing — the
underlying *capability* (a Dr/Cr amount suffix, a running-balance column, a wrapped description, a
metadata grid, a composite multi-account statement) is what's reusable; which bank happened to
motivate finding it is a fact worth one line in a doc comment, never the class name. If a new
statement seems to need `if (bank == ...)` anywhere, or a bank-named class of any kind, that's the
signal to stop and find the actual reusable capability underneath it before writing more code.

**The only legitimate exception**: things that genuinely *are* bank business data —
`BankRegistry`, `Bank`, `BankAlias`, `BankDto`. Metadata about which bank an account belongs to is
not the same thing as a parser hardcoded to one bank's layout, and this document doesn't ask
anyone to pretend otherwise.

### Layering discipline: fix a bug at the layer where it actually originates

Confirmed the hard way, twice: an amount-parsing bug (a font-encoding artifact rendering `₹` as a
literal `C`) belonged in `CsvParser.parseNumeric()`, not compensated for downstream in
`TransactionNormalizer` or `ImportService`. A header-normalization bug (a split `"Amount("` /
`")"` header pair) belonged in `CsvParser.normalizeHeaderCell()`, the one shared utility both the
CSV and PDF paths already route through — not a PDF-specific patch. If a fix only works by adding
a special case layers away from where the bad data was actually produced, that's a sign the real
bug is still there, just hidden.

Rule of thumb for this pipeline specifically:

```
PdfTextExtractor        -- wrong here: broken font/encoding/coordinate handling
        │
PdfTableLocator          -- wrong here: broken row/section/table structure
        │
PdfMetadataExtractor      -- wrong here: broken account-level field detection
        │
TransactionNormalizer      -- wrong here: broken column-semantics interpretation
        │
Validation / ImportService  -- wrong here: broken business rules, not data shape
```

A bug fixed below its true origin will keep resurfacing in new shapes. This is exactly what
happened with the HDFC continuation-line bug (a naive pixel-distance heuristic in
`PdfTableLocator`) — the fix belonged in table structure logic, not a downstream patch. It's also
why the same class of bug (an unclosed-paren header, a parenthesized Dr/Cr suffix, a page-footer
line) kept surfacing against a *second* real file after the *first* file's version of it was
fixed — new data, same layer, same discipline.

### Build for replaceability — no vendor lock-in, AI or otherwise

The moment AI enters this pipeline (Phase 4), no core service may depend on a specific AI
provider directly. `ImportService`, `Validation`, `Reconciliation`, `ImportSessionService`, and
the business-rules layer consume a common internal representation (a staged row, a detected
account, a confidence score) — never a provider-specific response shape. Swapping which model or
API produces that representation should never require a change to any of those classes. This
isn't AI-specific practice, either: it's the same reason `PdfPreviewGenerator` is the *only* class
outside `com.finora.imports.pdf` allowed to reach into that package — a narrow, stable seam that
lets the implementation behind it change freely.

### Testing philosophy

Tests verify **capabilities**, not banks:

| Don't | Do |
|---|---|
| `HdfcTest`, `AxisTest`, `HsbcTest`, `PnbTest`, `UnionBankTest` | `DrCrSuffixAmountColumnTest`, `WrappedDescriptionTest`, `MultiSectionCompositeStatementTest`, `ReverseChronologicalRunningBalanceTest`, `ParenthesizedDrCrRunningBalanceTest` |

Both **synthetic fixtures** and **real documents** are required — they validate different things
and neither substitutes for the other:

- **Synthetic fixtures** (built via `PdfFixtureBuilder`, committed to the repo, regenerable):
  validate parser *logic* — does the row-grouping, column-bucketing, and sign-detection code do
  what it's supposed to, in isolation, without real-world noise.
- **Real documents** (never committed — see "Handling real documents" below): validate
  *extraction behavior* against actual font encoding, actual coordinate placement, and actual
  layout quirks a synthetic fixture won't reproduce on its own. Every real bug found so far (the
  Rupee-as-`C` glyph, the over-aggressive continuation-fold, the split `"Amount("` header, the
  parenthesized Dr/Cr suffix, the page-footer merge) was a real-document-only finding — the
  synthetic fixture suite was green throughout every one of them. When a real bug is found, its
  fixture-builder method and test are added *afterward*, from what the real file taught, so the
  next regression is caught without needing another real file.

**No-regression rule**: every new capability must (1) add a synthetic regression test, (2) run
against the full existing suite, not just its own new test, and (3) not reduce what any previously
supported layout correctly extracts. This is what stops "support document X" from silently
becoming "break document Y" — the failure mode a bank-specific-parser architecture is especially
prone to, since nothing forces a change to one parser to be checked against another's fixtures at
all. In this codebase, that check is simply: run the whole suite (`mvn test`), not a filtered
subset, before considering any capability work done.

### Diagnostics stay generic

A diagnostic that only runs against one bank's file only ever helps debug that one bank's file.
This is now built: `PdfPipelineDiagnostic` (`backend/src/test/java/com/finora/imports/pdf/`) takes
any file path and reports, for that file:

- which section(s) were detected, and their header signatures
- the detected table columns for each section (the raw header text, exactly as extracted)
- which capabilities were observed in each section (Dr/Cr suffix, running balance, credit-card
  summary signal, ...)
- which columns exist in the data but aren't used by any known capability yet (`[UNKNOWN FIELDS]`)
  — the concrete, per-document version of "an unrecognized column today may be tomorrow's
  capability"
- how many rows survived table location vs. how many survived normalization (and why the
  difference — which rows got dropped, at which stage, and what specifically about them failed,
  via `TransactionNormalizer.explainFailure()` — the same reasons "Never lose information" surfaces
  to end users, not a second, diverging explanation)
- what account-level metadata was found vs. left null
- the detected account type and the signal that produced it
- an explicit note that per-field confidence isn't implemented yet, rather than silently omitting
  it (see Phase 3) — so its absence reads as a deliberate, documented gap, not an oversight

Deliberately named without a `Test` suffix so a bare `mvn test` never picks it up automatically;
run explicitly with `-Dtest=PdfPipelineDiagnostic#runFromSystemProperty -DpdfPath=<file>` when
debugging a new real document. It replaced what would otherwise have been a second one-off,
bank-named throwaway, and is the intended answer to "no developer should need to debug by reading
parser code."

### Capability lifecycle (how a real document becomes a permanent capability)

```
Real Document
        │
Discovery         -- run PdfPipelineDiagnostic, find exactly what fails and at which layer
        │
Generalization      -- name the underlying capability, not the bank ("PARENTHESIZED_DR_CR", not "Union Bank format")
        │
Implementation         -- fix it at the layer it actually originates (see "Layering discipline")
        │
Synthetic Fixture         -- encode the pattern in PdfFixtureBuilder, from what the real file taught
        │
Regression Test              -- assert against the synthetic fixture, capability-named
        │
Stable Capability                -- permanent, reusable, in the list below
```

The real document disappears at the end of this — deleted per the steps below. Only the
capability survives, as code and a test. This is deliberate: the codebase should never depend on
a real file continuing to exist somewhere to prove a capability still works.

Real uploaded statements contain real personal/financial data (account numbers, names, card
numbers). They are useful for debugging exactly once, interactively, and are never committed:

1. Copy the file to `backend/scratch-pdf/` (gitignored).
2. Run `PdfPipelineDiagnostic` against it.
3. Fix whatever it surfaces, in the correct layer (see "Layering discipline" above).
4. Re-run against the same real file until it's clean.
5. Build the synthetic fixture and regression test from what was just learned (see "No-regression
   rule" above) — this is the step that turns a one-off fix into a permanent capability.
6. Delete the scratch copy. It never becomes a permanent test fixture.

### Never lose information

**Implemented.** A row that fails to parse (bad date, bad amount, or no recognizable date/amount
column at all) no longer just returns `null` and vanishes. `TransactionNormalizer.explainFailure()`
derives a specific, human-readable reason using the exact same hint lookups `normalize()` itself
uses (kept in one place — `TransactionNormalizer`'s shared hint constants — so the two can never
drift apart). Every row that fails to normalize is captured, on both the CSV path
(`PreviewGenerator`) and the PDF path (`PdfPreviewGenerator`), as an `UnparseableRow(raw, reason)`
and returned on `StagingResponse.unparseableRows()` / `StagedAccountSection.unparseableRows()` —
surfaced in the review screen (`UnparseableRowsPanel` in `Import.tsx`), never silently dropped.

The full target shape (below) is carried in three of its five states today — Raw, Reason, Review
all exist; Extracted (a *partial* interpretation, not just pass/fail) and Confidence (see Phase 3)
do not yet:

```
Raw          -- exactly what was extracted, untouched                          [done]
    │
Extracted     -- what normalization managed to interpret (possibly partial)    [not yet]
    │
Confidence     -- how sure the engine is about what it extracted (see Phase 3)  [not yet]
    │
Reason           -- if something couldn't be resolved, WHY, not just "failed"  [done]
    │
Review              -- surfaced to the user, not discarded                     [done]
```

If the engine doesn't understand something today, that doesn't mean it's worthless — a row it
can't fully parse now may be exactly what teaches the next capability later (see "Capability
lifecycle" above, and `PdfPipelineDiagnostic`'s `[UNKNOWN FIELDS]` reporting, which surfaces
unrecognized *columns* the same way this surfaces unparseable *rows*). Deleting it silently
forecloses that entirely.

**Known, accepted v1 gap:** `unparseableRows` is not persisted on `ImportSession` — reopening a
saved session (`ImportController.getSession()`) returns an empty list for it rather than the
original reasons. Documented in code at that call site; revisit if this turns out to matter in
practice rather than fixing it speculatively.

---

## Phase 1 — Strengthen the Generic Engine (current, concrete)

Continue hardening what exists: more capabilities as real documents motivate them, the generic
diagnostic (done), validation, and closing the "never lose information" gap (also done — see
above). This section is deliberately the roadmap artifact — look here to answer "what can the
engine do," never at a bank list.

### Document Capabilities

```
Done
✓ Running Balance / Balance-Chain Reconstruction
✓ Dr/Cr Suffix (bare and parenthesized)
✓ Leading "+" Credit
✓ Date + Time Column
✓ Wrapped Description
✓ Repeated Headers
✓ Page Boundary Isolation / Page Footer Exclusion
✓ Composite Statements (multi-account, multi-section)
✓ Credit Card Summary Signal
✓ Metadata Grid (2-row)
✓ Metadata Grid (trailing label -- value precedes its label on the same line)
✓ Never Lose Information (unparseable rows surfaced with a reason, not dropped)
✓ Offset Column Anchors (header labels not aligned with their own column's data)

Planned
• Leading Narration Continuation (transaction description wraps before the date/amount row, not after)
• Excel
• Scanned PDFs / OCR
• Images
• Handwritten Statements
```

This list moves whenever a capability changes stage. It is the actual measure of progress this
document keeps insisting on (see "How progress is actually measured" below) — never "which banks
does Finora support."

### Capability Registry

The single source of truth for every capability the engine understands. `Coverage`/`Confidence`
as live, queryable metrics need real instrumentation that doesn't exist yet (see Phase 3); until
then, "Regression tests" is the honest proxy for confidence a capability actually works — no test,
no claim.

#### `RUNNING_BALANCE` / `BALANCE_CHAIN_RECONSTRUCTION`
- **Purpose:** reconstruct which transaction happened first when a statement lists a running
  balance, including same-day clusters and reverse-chronological (newest-first) files.
- **Supported layouts:** any table with a balance-after-transaction column, in either
  chronological or reverse-chronological row order.
- **Implementation:** `BalanceChainUtil`, `PdfPreviewGenerator` (row sort + balance-point wiring).
- **Regression tests:** `ReverseChronologicalRunningBalancePdfPreviewGeneratorTest`,
  `BalanceChainUtilTest`.
- **Maturity:** Stable.
- **Known limitations:** ordering is *value*-based (matches a row's implied balance to another
  row's actual balance), not positional — this is what makes it order-independent, but same-day
  transactions with no other distinguishing signal fall back to file-encountered order for their
  relative sequence within that day, not a fully reconstructed intra-day chain.

#### `DR_CR_SUFFIX` (bare and parenthesized)
- **Purpose:** a single amount column whose sign is carried by a trailing Dr/Cr marker instead of
  a separate debit/credit column.
- **Supported layouts:** bare (`"37.94 Dr"`) and parenthesized (`"50000.00(Cr)"`) forms — two
  real, independently-discovered variants, now one shared implementation.
- **Implementation:** `CsvParser` (`TRAILING_DR`/`TRAILING_CR` patterns, `parseNumeric`,
  `detectSignFromRawAmount`), `TransactionNormalizer` (sign-inference fallback chain).
- **Regression tests:** `DrCrSuffixAmountColumnPdfPreviewGeneratorTest`,
  `ParenthesizedDrCrRunningBalancePdfPreviewGeneratorTest`, `CsvParserTest`.
- **Maturity:** Stable.
- **Known limitations:** only recognizes the literal tokens "Dr"/"Cr" (with optional trailing
  period/parens); a statement using a different debit/credit abbreviation would need that token
  added to the pattern, not a new capability.

#### `LEADING_PLUS_CREDIT`
- **Purpose:** a `+` prefix marking a credit with no separate Type/Credit column and no Dr/Cr
  suffix at all (e.g. a credit-card statement's single Amount column).
- **Supported layouts:** single-amount-column statements where credits are marked with a leading
  `+` and debits carry no marker.
- **Implementation:** `CsvParser.detectSignFromRawAmount`, consulted only as the lowest-priority
  fallback in `TransactionNormalizer.normalize()`.
- **Regression tests:** `WrappedDescriptionCreditCardPdfPreviewGeneratorTest`.
- **Maturity:** Stable.
- **Known limitations:** lowest-priority by design — any Type/Credit-column or Dr/Cr-suffix signal
  wins first, so this only ever fires when nothing more specific is present.

#### `DATE_TIME_COLUMN`
- **Purpose:** a single cell combining a date and a time-of-day (e.g. `"30/06/2026| 14:18"`),
  which the existing date formatters can't parse directly.
- **Supported layouts:** date+time in one column, separated by whitespace or a literal `|`.
- **Implementation:** `CsvParser.parseDate` (trailing time-of-day strip),
  `TransactionNormalizer` (`"date & time"` hint).
- **Regression tests:** `WrappedDescriptionCreditCardPdfPreviewGeneratorTest`, `CsvParserTest`.
- **Maturity:** Stable.
- **Known limitations:** the time component is discarded, not retained anywhere — transactions are
  ordered by date only, never by time-of-day, even when the source data has it.

#### `WRAPPED_DESCRIPTION`
- **Purpose:** a transaction whose description (or, in one real file, whose amount) spills onto a
  second, dateless row instead of staying on one line.
- **Supported layouts:** any table where a continuation row has a description-shaped value but no
  date or amount value of its own.
- **Implementation:** `PdfTableLocator` (structural continuation-row folding — no value in the
  date column, not a pixel-distance guess; the earlier positional heuristic corrupted a real
  document badly enough to merge an entire letterhead into the transaction table).
- **Regression tests:** `WrappedDescriptionCreditCardPdfPreviewGeneratorTest`.
- **Maturity:** Stable.
- **Known limitations:** the continuation-row y-gap threshold hasn't been tuned against a broad
  real-document corpus — only against the real files that motivated it so far.

#### `REPEATED_HEADER`
- **Purpose:** the same table's header row reappearing on later pages of a multi-page statement,
  which must be recognized and skipped rather than staged as a garbage data row.
- **Supported layouts:** any multi-page table whose header repeats with an identical column
  signature.
- **Implementation:** `PdfTableLocator.locateAll` (header-signature equality check).
- **Regression tests:** `DrCrSuffixAmountColumnPdfPreviewGeneratorTest`.
- **Maturity:** Stable.
- **Known limitations:** relies on the repeated header's column signature matching exactly; a
  reprinted header with even slightly reordered or renamed columns would currently be treated as
  the start of a new section instead of a repeat.

#### `PAGE_BOUNDARY_ISOLATION` / `PAGE_FOOTER_EXCLUSION`
- **Purpose:** a page-number footer line, or a per-page repeated title banner, must never merge
  into the last real transaction row of the page before it.
- **Supported layouts:** any paginated statement with a "Page X of Y"-style footer and/or a
  repeated per-page title/account banner.
- **Implementation:** `PdfTableLocator` (`lastRowPage` page-boundary tracking, `PAGE_FOOTER`
  pattern).
- **Regression tests:** `ParenthesizedDrCrRunningBalancePdfPreviewGeneratorTest`.
- **Maturity:** Stable.
- **Known limitations:** the `PAGE_FOOTER` pattern is intentionally loose (tolerant of
  font-encoding artifacts on digits) — a legitimate transaction description that happens to
  contain both "page" and "of" could, in principle, be misclassified as footer noise.

#### `COMPOSITE_STATEMENT` / `MULTI_ACCOUNT`
- **Purpose:** one PDF containing more than one account section (e.g. a savings account and a
  credit card in the same statement), each detected, staged, and confirmed independently.
- **Supported layouts:** documents with an explicit section-marker banner line, or a header
  signature change with no marker at all.
- **Implementation:** `PdfTableLocator.locateAll`, `PdfPreviewGenerator.generateSections`,
  `ImportSessionService` (multi-section sessions), `ImportService.confirmMultiSection`,
  `ImportController` (`/import/pdf/confirm-multi`).
- **Regression tests:** `MultiSectionCompositeStatementPdfPreviewGeneratorTest`.
- **Maturity:** Stable.
- **Known limitations:** each per-section confirm currently stores the entire multi-account PDF's
  bytes again (once per section) — an accepted, documented v1 trade-off, not a correctness bug;
  see the reimport/confirm code comments for the full reasoning. Separately, the section-marker
  pattern that detects a new account banner (`SAVINGS ACCOUNT`, `CREDIT CARD`, etc. followed by a
  4+ digit run) is a free-text match against transaction-shaped content — a real transaction
  description containing both an account-type word and a long digit run (e.g. a transfer narration
  naming a destination account) could in principle be misread as a section boundary and diverted
  out of the transaction table entirely, bypassing even `TransactionNormalizer`/
  `explainFailure()`'s "Never lose information" safety net. Narrow and not yet seen in a real
  document; noted here rather than fixed speculatively. Separately: the header-signature-difference
  fallback (no marker line) used to be vulnerable to the same false-positive class `looksLikeHeaderRow`
  had (see `OFFSET_COLUMN_ANCHORS` below) — a wrapped fine-print paragraph, split into many small
  runs, could satisfy the "date + 2 header hints" check purely by incidentally containing the words
  "date" and "amount," misreading an entire sentence as a second table's header and wrongly
  splitting one account into two. Fixed by `MAX_HEADER_ROW_CELLS`, found and verified against a
  real Axis Bank statement whose "Schedule of Charges" fine print did exactly this.

#### `CREDIT_CARD_SUMMARY_SIGNAL`
- **Purpose:** detect a credit-card account from free-text payment-summary wording ("Total Payment
  Due," "Minimum Amount Due," "Card Number") rather than requiring a dedicated column.
- **Supported layouts:** any statement whose auxiliary (non-table) text contains recognizable
  credit-card summary phrasing.
- **Implementation:** `PdfPreviewGenerator` (auxiliary-text signal check feeding
  `buildDetectedAccountInfo`).
- **Regression tests:** covered across the credit-card fixture tests
  (`DrCrSuffixAmountColumnPdfPreviewGeneratorTest`, `WrappedDescriptionCreditCardPdfPreviewGeneratorTest`).
- **Maturity:** Stable.
- **Known limitations:** matches a fixed list of English-language phrases; a statement using
  different wording for the same concept won't trigger this signal until that phrase is added.

#### `OFFSET_COLUMN_ANCHORS`
- **Purpose:** correctly bucket a row's text into columns even when a column's header LABEL
  doesn't sit anywhere near where that column's own DATA actually starts -- plain nearest-x
  bucketing silently mis-assigns data to the wrong column whenever this happens.
- **Supported layouts:** any table where (a) a header label is positioned well away from its own
  column's data (e.g. centered over a wide column while data is left-aligned within it), (b) a
  short value (like a one-word merchant category) sits nearer a neighboring short amount than that
  amount's own header anchor, and/or (c) a fee/charge line's label and its trailing amount are
  extracted as a single combined text run instead of two separate ones.
- **Implementation:** `PdfTableLocator.bucketRow` -- a date-column-overflow redirect (a date cell
  holds exactly one value; once it has one, a further run nearest to it advances to the next
  column instead), an amount-column redirect (an amount-shaped run that would otherwise append
  onto an already-non-blank earlier cell advances to the nearest later amount-hint column
  instead), and `splitTrailingAmountIfMissing` (splits a trailing amount off a combined cell when
  the row's dedicated amount column came back with no value at all).
- **Regression tests:** `OffsetColumnAnchorsPdfPreviewGeneratorTest`.
- **Maturity:** Stable.
- **Known limitations:** the date- and amount-redirects are deliberately narrow (content-type-
  specific, not a general "advance past a full column" rule for every column) — a date or amount
  column is known structurally to hold exactly one value, but a description column can
  legitimately receive more than one run on the same row (PDFBox splitting one multi-word cell),
  so a general rule would risk breaking that instead. Found and fixed against a real Axis Bank
  "Neo Rupay" credit-card statement (see `PdfTableLocator.bucketRow`'s own doc comment for the
  exact coordinates) — every transaction row in that file was being dropped for having an
  unparseable date before this fix, and every upload of it was incorrectly detected as two
  separate accounts (see `COMPOSITE_STATEMENT`'s "Known limitations" above for that half of the
  same underlying discovery).

#### `GRID_METADATA_FALLBACK` (2-row grid)
- **Purpose:** extract account-level metadata (e.g. a Due Date field) from a payment-summary block
  laid out as a genuine label-row/value-row grid, rather than single-line "Label: Value" text.
- **Supported layouts:** a 2-row label/value grid where labels and their values are vertically
  stacked rather than on the same line.
- **Implementation:** `PdfMetadataExtractor` (bounded-window scan for the first plausible value
  after a trailing label).
- **Regression tests:** `GridMetadataFallbackPdfPreviewGeneratorTest`.
- **Maturity:** Beta.
- **Known limitations:** only handles a 2-row grid, and only the "label first" line shape — see
  the entry directly below for the reversed "value first" shape.

#### `GRID_METADATA_TRAILING_LABEL`
- **Purpose:** extract account-level metadata from a grid where each row's VALUE comes BEFORE its
  own label on the same line (`"317002010038811 Account Number"`, `"UBIN0531707 IFSC"`) — the
  reverse of every "Label: Value" shape `ACCOUNT_HOLDER`/`ACCOUNT_NUMBER`/`IFSC`/`BRANCH` already
  handle. Originally scoped (speculatively, before a real document existed to check it against) as
  "a 4-column grid" — the real layout turned out to be a two-column grid with reversed value/label
  order instead; renamed once the actual shape was known rather than keeping a name that no longer
  described it.
- **Supported layouts:** a metadata panel whose lines are `<value> <label>` rather than `<label>
  <value>` — verified against account number, IFSC, account holder name, and statement period
  fields specifically.
- **Implementation:** `PdfMetadataExtractor` — `ACCOUNT_NUMBER_TRAILING_LABEL` (digit-run before
  "Account Number"), `IFSC_SHAPE` (IFSC codes have a fixed, distinctive shape — 4 letters, a
  literal `0`, 6 more alphanumerics — found directly by content, independent of any label at all,
  which sidesteps the real statement's IFSC line being merged with an unrelated Email field on the
  same extracted line), `ACCOUNT_NAME_TRAILING_LABEL` (up to 3 capitalized words before "Account
  Name" — capped specifically so an unrelated capitalized address fragment immediately before the
  real name doesn't get swept in), `STATEMENT_PERIOD_TRAILING_LABEL`. All four are fallbacks,
  consulted only when the ordinary "label first" checks already had their chance on a given line —
  a document using the ordinary shape is completely unaffected.
- **Regression tests:** `PdfMetadataExtractorTest`.
- **Maturity:** Beta.
- **Known limitations:** the same real statement's own "Name" field (a *different*, more complete
  holder name — with an honorific — than the "Account Name" field this capability uses instead)
  wraps its value across several lines *before* the "Name" label appears at all; not attempted here
  (see `LEADING_NARRATION_CONTINUATION` below for the same underlying "value precedes its label
  across multiple lines" difficulty appearing in a different part of this pipeline). Branch name
  stays null against this same statement — its branch-address panel is *also* a multi-line-wrapped
  value-before-label field, same gap, not attempted for the same reason. A real, if incomplete
  (no honorific), holder name from `ACCOUNT_NAME_TRAILING_LABEL` was judged a better outcome than
  none — a genuinely null branch name was judged better than another guess.

#### `LEADING_NARRATION_CONTINUATION` — Planned, not yet attempted
- **Purpose:** a transaction whose narration/description text wraps across multiple lines
  *before* its own date+amount row, rather than after it (the shape every existing continuation
  capability — `WRAPPED_DESCRIPTION` — assumes). A real Canara Bank statement's layout renders
  each transaction as: 2-3 narration lines (no date) → the date+amount+balance line (with a
  fragment of narration mixed in) → 1-2 trailing detail lines (transaction time + reference,
  then a cheque number — also no date).
- **Supported layouts:** not yet implemented.
- **Implementation:** none yet — deliberately not attempted this pass. `PdfTableLocator.locateAll`'s
  continuation-merge is structurally single-directional (a dateless row always merges *backward*
  into whatever row came before it), which is exactly right for `WRAPPED_DESCRIPTION` but exactly
  wrong for leading narration: on the real file, every transaction's leading lines merged into the
  *previous* transaction's row instead (or, for the very first transaction, into the statement's
  own "Opening Balance" summary row) — silently attaching the wrong narration to the wrong
  transaction — until a page boundary happened to break the chain, at which point the leading
  lines surfaced as an orphaned, undated `UnparseableRow` instead (still not attached to the
  transaction they belong to, just visibly so instead of silently).
- **Regression tests:** none yet.
- **Maturity:** Planned / blocked, deliberately.
- **Known limitations:** a real general fix needs the merge logic to buffer dateless rows and
  resolve them against *either* the previous or the next date-bearing row, not always the
  previous one — and doing that safely requires distinguishing "leading narration" from
  `WRAPPED_DESCRIPTION`'s existing "trailing narration" shape without breaking the already-
  validated capability (`WrappedDescriptionCreditCardPdfPreviewGeneratorTest` and every other
  fixture depending on backward-only merging). A narrow content-pattern signal (Canara's trailing
  detail lines are recognizably shaped — a `HH:MM:SS/<reference>` line, then a `Chq: <number>`
  line) could distinguish "this dateless row is definitely trailing" from "buffer it forward" —
  but that needs to be designed and tested deliberately against this real file, not bolted on
  under time pressure in the middle of fixing three *other* real files' bugs. Left open rather
  than risking a regression to a working capability.

#### Excel, Scanned PDFs / OCR, Images, Handwritten Statements — Planned
- **Purpose:** additional document formats, each requiring a new implementation of the early
  pipeline stages (Classification, Layout Understanding) feeding the same downstream stages
  (Validation, Confidence, Review, Import Session) — see "Financial Document, not PDF" above.
- **Supported layouts / Implementation / Regression tests:** none yet — genuinely not started.
- **Maturity:** Planned, explicitly out of scope until a real driver exists (see "What I would not
  do right now" discipline this document has followed since Phase 0).
- **Known limitations:** N/A — not yet attempted.

Update this section whenever a capability moves stage — it's the thing to look at instead of
asking "which banks do we support."

## Phase 2 — More Capabilities, More Document Types (not more banks)

Two axes, neither of which is "which bank":

- **More layout capabilities**, added as real documents motivate them: separate debit/credit
  columns, single-amount-column variants not yet seen, merchant-category columns, additional date
  formats, leading (not just trailing) narration continuation.
- **More document types**, once there's a real driver: CSV already exists (predates this
  document, already generic); Excel, scanned PDFs, and OCR are explicitly out of scope until then
  — see the existing PDF package doc's own reasoning for deferring OCR once already. Each new
  format is a new implementation of the early pipeline stages (Classification, Layout
  Understanding) feeding the same downstream stages — see "Financial Document, not PDF" above.

Every addition here gets a synthetic fixture and (transiently) a real-document diagnostic pass —
same process as Phase 1.

---

## Why Phases 3–4 stay a direction, not a roadmap item

Layout Profiles, per-field confidence, a corrections-and-metrics data layer, and eventually AI-
driven extraction are sound ideas, worth having written down precisely so they don't get
reinvented differently each time someone thinks about this problem again. They should **not** be
built now. The honest trigger for starting Phase 3 is *real correction volume across many real
documents* — not architectural elegance, and not because the idea is appealing. Right now this
pipeline has processed a handful of real files, several of which needed real bug fixes found by
reading actual extracted text by hand. Building a knowledge base, a confidence-scoring UI, or an
AI extraction layer around that little data would be exactly the premature abstraction this whole
document exists to prevent — just aimed at infrastructure instead of a bank name.

## Phase 3 — Collect Knowledge (direction, gated)

Deliberately not titled "train AI" — that's Phase 4, and only maybe. The shape, when the trigger
condition above is actually met:

- **Layout Profile**, not Bank Profile: a named bundle of capabilities (`RUNNING_BALANCE` +
  `GRID_METADATA_FALLBACK` + `DR_CR_SUFFIX`, say), independent of which bank(s) happen to use it.
  Multiple banks may share one profile; one bank may use different profiles for different account
  types or after a redesign. `BankRegistry` stays purely business metadata (name, logo, IFSC
  prefixes) — a layout profile is never keyed by bank.
- **Per-field confidence**, not one score per document — a date and amount extracted from a clean
  table row are near-certain; a value pulled from a bounded-window grid-fallback scan is not, and
  the review UI should be able to tell a user which fields actually need a second look.
  `GRID_METADATA_FALLBACK`'s existing fields (Phase 1) are the first real candidates for this once
  it exists.
- **Recording, not learning yet**: extraction results, confidence, validation outcomes, and user
  corrections get stored somewhere durable. No model training, no fine-tuning, no learning system
  built on top of this data in Phase 3 — a learning system trained on nothing is not better than
  no learning system. The whole point of this phase is accumulating the *something* Phase 4 would
  need, honestly, before attempting to use it for anything.
- **What gets shared across users, eventually — layouts, never customer data.** The intent is
  real network effects: one user's correction on a layout should eventually help every user who
  later uploads a document with that same layout, not just the user who made the correction. What
  crosses that boundary is capability/structure knowledge (which layout, which fields were
  uncertain, what the correction pattern was) — never transaction content, account numbers, names,
  or anything else that identifies a specific user or their finances. **This needs a real privacy
  design before it's built, not just an "anonymized" label** — what "anonymized" actually means in
  practice (is a layout fingerprint alone ever re-identifying? does this need disclosure in a
  privacy policy or terms of service before a single real user's correction is used this way?) is
  a genuine open question this document doesn't resolve, and Phase 3 shouldn't start moving
  correction data across user boundaries until it has been resolved for real — this is called out
  explicitly so it doesn't get implemented as an afterthought once the rest of Phase 3 exists.

## Phase 4 — Evaluate Training (direction, gated on Phase 3's data existing)

Deliberately not titled "fine-tune GPT" or any specific model — that decision doesn't exist yet,
and naming one here would commit to it prematurely. What Phase 4 actually is:

AI becomes responsible for document classification, layout understanding, metadata understanding,
semantic field extraction, section identification, and confidence estimation. It is never
responsible for validation, duplicate detection, reconciliation, business rules, or persistence —
those stay deterministic, in the existing backend, unchanged in spirit from how they work today.
**AI proposes; the backend validates.** This is the same reason `ImportService` never lets a
staged row reach the ledger without going through the existing validation/reconciliation/
duplicate-detection path regardless of which stage produced it — AI extraction, when it exists,
is just one more thing producing a staged row.

AI in this pipeline learns **layout concepts** (running balance, metadata grid, credit card
summary, repeated header, wrapped description), never bank names — same principle as Phase 0's
naming rule, applied to what a model is trained or prompted to recognize instead of what a class
is named.

### What AI can never do

Explicit, not implied, because "AI proposes, backend validates" is easy to agree with in the
abstract and easy to quietly violate once a model's output looks confident enough to trust
directly. AI must never:

- insert a transaction into the ledger
- modify an account balance
- bypass validation
- bypass confidence thresholds
- bypass reconciliation
- bypass duplicate detection

Every one of those stays exclusively the deterministic backend's job, regardless of how good
extraction gets. AI produces a staged row, same shape and same review step as anything else that
produces one today — it never gets a shorter path to the ledger than a human-reviewed CSV import
does.

### Transaction Intelligence — a distinct, later layer

Separate from layout/capability detection (which answers *where* information sits on a page) is
understanding what a transaction's raw text actually *means* — normalizing bank-specific phrasing
into canonical financial concepts: "Interest Paid" and "Interest Credit" are the same concept
differently worded; "SMS Charges" is a bank fee; "Amazon Refund" is a refund; a UPI-prefixed
credit is an incoming transfer. This is **not** the same thing as `CategorizationService`/
`CategoryRules`, which already exist and assign a *spending category* (Food, Transport, ...) to a
transaction — Transaction Intelligence would sit conceptually upstream of that, normalizing the
raw description into a canonical form categorization (or a future model) can reason about more
reliably. Not designed in any detail yet, and not scheduled ahead of Phase 3's data actually
existing — noted here so it doesn't get bolted onto `CategorizationService` later as a scope-creep
surprise, and so nobody mistakes existing categorization for having already solved this.

---

## PR Checklist

The Golden Rule's four questions (above) are the philosophical check. This is the concrete one —
every parser-related PR should be able to answer all ten before merging:

1. What capability is being added?
2. Can another institution reuse it, unchanged?
3. Does it remove or increase coupling to a specific document's shape?
4. Is anything named after a bank? (The only legitimate exception: `BankRegistry`/`Bank`/
   `BankAlias`/`BankDto` business metadata — see Phase 0's "Naming.")
5. Is there a synthetic regression fixture (`PdfFixtureBuilder`)?
6. Is there a regression test, capability-named, run against the *whole* suite?
7. Does `PdfPipelineDiagnostic` expose the new behavior (a new capability signal, a new metadata
   field, a new drop reason)?
8. Are unparseable rows preserved, not silently dropped (see "Never lose information")?
9. Does AI remain advisory rather than authoritative, if this PR touches anything AI-adjacent (see
   "What AI can never do")?
10. Does this move the engine closer to understanding documents in general, rather than adding
    another institution-specific parser?

If the honest answer to any of these is "no" or "not applicable in a way that's actually fine,"
that's worth writing down in the PR description — not skipped silently.

---

## How progress is actually measured

Not "how many banks does Finora support." Instead:

- How many document **capabilities** does the engine understand?
- How well does it adapt when a bank changes its layout, without new code?
- How little engineering effort does a genuinely new document need — reusing what exists,
  contributing at most one new named capability?
- How much does confidence and correction data (Phase 3+) actually improve extraction quality over
  time, once it exists?

"Does Finora support Kotak" is the wrong question. "Does this document's layout match capabilities
we already have, or does it need one new one" is the right one.

---

## Sequencing

```
Phase 0: Standards                    -- in effect now, governs everything below
    │
Phase 1: Strengthen the Generic Engine -- concrete, in progress
    │
Phase 2: More Capabilities & Formats     -- added as real documents/needs motivate them
    │
Phase 3: Collect Knowledge      -- direction only, gated on real correction volume
    │
Phase 4: Evaluate Training         -- direction only, gated on Phase 3's data existing
```

Capability-driven work naturally produces bank *coverage* as a side effect — it is never the goal
being optimized for.

---

## Final vision

Finora's competitive advantage will not come from supporting more banks than competitors. It will
come from continuously improving its understanding of financial documents. Every new capability,
every verified correction, and every successfully imported statement should strengthen the
platform's ability to understand future documents with less manual effort. The long-term objective
is a capability-driven Financial Document Intelligence Engine that evolves through accumulated
knowledge rather than through an ever-growing collection of institution-specific parsers.

## Five principles

If everything above is followed consistently, it reduces to five:

1. **Think in capabilities, never in banks.**
2. **Generalize from real documents; don't build around them.** (See "Capability lifecycle" — the
   real document disappears; only the capability remains.)
3. **AI understands documents; deterministic code guarantees correctness.** (See "What AI can
   never do" — this is a hard boundary, not a preference.)
4. **Every verified import increases the system's knowledge** — once Phase 3 exists to actually
   capture that; until then, this principle is aspirational and this document says so plainly
   rather than pretending otherwise.
5. **Every new document should either reuse an existing capability or introduce exactly one new
   reusable one.** (The Golden Rule, restated.)
