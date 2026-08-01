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
doesn't exist as a first-class concept yet (see Phase 3); everything today either parses or is
silently dropped (see "Never silently drop data" below) — that's a known, named gap, not an
oversight.

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

### Diagnostics stay generic

A diagnostic that only runs against one bank's file only ever helps debug that one bank's file.
This is now built: `PdfPipelineDiagnostic` (`backend/src/test/java/com/finora/imports/pdf/`) takes
any file path and reports, for that file:

- which section(s) were detected, and their header signatures
- how many rows survived table location vs. how many survived normalization (and why the
  difference — which rows got dropped, at which stage, and what specifically about them failed)
- what account-level metadata was found vs. left null
- the detected account type and the signal that produced it

Deliberately named without a `Test` suffix so a bare `mvn test` never picks it up automatically;
run explicitly with `-Dtest=PdfPipelineDiagnostic#runFromSystemProperty -DpdfPath=<file>` when
debugging a new real document. It replaced what would otherwise have been a second one-off,
bank-named throwaway.

### Handling real documents

Real uploaded statements contain real personal/financial data (account numbers, names, card
numbers). They are useful for debugging exactly once, interactively, and are never committed:

1. Copy the file to `backend/scratch-pdf/` (gitignored).
2. Run `PdfPipelineDiagnostic` against it.
3. Fix whatever it surfaces, in the correct layer (see "Layering discipline" above).
4. Re-run against the same real file until it's clean.
5. Delete the scratch copy. It never becomes a permanent test fixture — build a synthetic fixture
   instead if the layout pattern needs a permanent regression test.

### Never silently drop data

Current behavior: a row that fails to parse (bad date, bad amount) returns `null` from
`TransactionNormalizer.normalize()` and is dropped without a trace. That's how the transaction-loss
bugs found this session stayed invisible until real files surfaced them — there was no signal
anywhere that some or all of a file's rows had failed to parse.

This is a real, worthwhile improvement but **not a Phase-0 code change** — it changes the shape of
`StagingResponse` (rows that failed to parse need to surface as *something* reviewable, not just a
lower row count) and touches the frontend review screen too. Recorded here as a committed Phase 1
item, not implemented today, so scope doesn't quietly balloon this document into a code change.

---

## Phase 1 — Strengthen the Generic Engine (current, concrete)

Continue hardening what exists: more capabilities as real documents motivate them, the generic
diagnostic (done), validation, and closing the "never silently drop data" gap. Capabilities built
so far, correctly named for what they do rather than who uses them:

- `RUNNING_BALANCE` + `BALANCE_CHAIN_RECONSTRUCTION` (`BalanceChainUtil`) — handles same-day
  transaction clusters and reverse-chronological files correctly, regardless of bank.
- `DR_CR_SUFFIX` — a single amount column with a trailing Dr/Cr marker, in both the bare form
  (`"37.94 Dr"`) and the parenthesized form (`"50000.00(Cr)"`) — two real, independently-discovered
  variants of the same capability, now one shared implementation.
- `LEADING_PLUS_CREDIT` — a `+` prefix marking a credit with no other marker.
- `DATE_TIME_COLUMN` — a combined date+time cell, with or without a `|` separator.
- `WRAPPED_DESCRIPTION` — a transaction whose description (or, in one real file, whose *amount*)
  continues onto a second, dateless row. Structural (no value in the date column), not positional
  (a pixel-distance guess) — the positional version corrupted a real document badly enough to
  merge an entire letterhead into the transaction table.
- `REPEATED_HEADER` — the same table's header reappearing on later pages, recognized and skipped
  rather than staged as garbage rows.
- `PAGE_BOUNDARY_ISOLATION` + `PAGE_FOOTER_EXCLUSION` — a page-number line, or a per-page title
  banner, must never merge into the last real row of the page before it.
- `COMPOSITE_STATEMENT` / `MULTI_ACCOUNT` — one document containing more than one account section,
  each independently staged and confirmed.
- `CREDIT_CARD_SUMMARY_SIGNAL` — detecting a credit-card account from free-text payment-summary
  wording ("Total Payment Due," "Minimum Amount Due"), not a column.
- `GRID_METADATA_FALLBACK` — a payment-summary or account-details block laid out as a genuine
  label-row/value-row grid rather than single-line "Label: Value" text (HDFC's Due Date field);
  bounded-window scan for the first plausible value after a trailing label. Still incomplete — see
  the open item below.

**Open, not yet solved**: a *4-column* label/value metadata grid (`Name | [value] | Customer/CIF
ID | [value]`), distinct from the 2-row grid `GRID_METADATA_FALLBACK` already handles. Found in a
real Union Bank of India statement; account holder name, masked account number, and IFSC all stay
null against it today, and branch name resolves to a garbled wrong value. Not fixed speculatively
— per "Layering discipline" and the real-document testing philosophy above, this needs a real
diagnostic pass against the actual extracted structure before a fix is written, not a guess at
what a 4-column grid probably looks like.

## Phase 2 — More Capabilities, More Document Types (not more banks)

Two axes, neither of which is "which bank":

- **More layout capabilities**, added as real documents motivate them: separate debit/credit
  columns, single-amount-column variants not yet seen, merchant-category columns, additional date
  formats, the 4-column metadata grid above.
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

## Phase 3 — Layout Profiles & Data Collection (direction, gated)

The shape, when the trigger condition above is actually met:

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

## Phase 4 — The Financial Document Intelligence Engine (direction, gated on Phase 3's data existing)

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
Phase 3: Layout Profiles & Data Collection -- direction only, gated on real correction volume
    │
Phase 4: AI-Driven Extraction                -- direction only, gated on Phase 3's data existing
```

Capability-driven work naturally produces bank *coverage* as a side effect — it is never the goal
being optimized for.
