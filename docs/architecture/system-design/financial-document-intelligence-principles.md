# Financial Document Intelligence Engine — Principles & Roadmap

**Status:** Phase 0 is in effect immediately — it governs how code is written from this point
forward. Phases 1–2 describe near-term, concrete work. Phases 3–6 and the Admin Portal Control
Center are a documented *direction*, not a commitment — see "Why Phases 3–4 stay a direction, not
a roadmap item" before starting anything in them; the same reasoning gates Phases 5–6 and the
Admin Portal.

**Relationship to other docs:** this is an engineering-practice document (naming, layering,
testing, sequencing) — the sibling to `CODING_STANDARDS.md` in this same folder, scoped to the
statement-import subsystem. It is not a replacement for `docs/statement-intelligence-engine-spec.md`
(a design-stage spec written before real PDF parsing existed in this codebase, and now stale in
places — e.g. it specs "rule-based PDF parsers (per bank)," which is exactly the architecture this
document rules out). If the two ever conflict, this document reflects what's actually true today.
Two companion documents track evidence over time rather than current rules: the
[Evidence Registry](evidence-registry.md) (what each real document taught the engine, plus a
per-cycle metrics snapshot) and the
[Financial Document Intelligence Changelog](../../project-management/milestones/financial-document-intelligence-changelog.md) (the
same history, compressed into a skimmable Learned/Improved/Protected/Observed/Deferred/Open
summary per cycle). Separately, [import-flow.md](../../engineering/import/import-flow.md) documents the *pipeline* rather
than the engine — stage/review/confirm, the endpoints and error codes, password-protected PDFs,
re-import, and what each client does. This document is about what the engine understands inside a
document; that one is about how a document travels through the system. A third,
[layout-intelligence-proposal.md](layout-intelligence-proposal.md), is a *proposal* rather than a
record of what is true: what the engine should do with the fact that it has seen a document's
structure before. Its approved scope is observability only — reading the `layout_fingerprint` data
this pipeline has already been storing, with mapping reuse explicitly excluded. Read it before
adding anything that reasons about recurring layouts.

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

## The Capability Rule

> **Every code change to this pipeline should leave the engine knowing more than it did before.**

The Golden Rule governs *what a capability is named and shaped like*. The Capability Rule governs
*whether a given change was worth making at all*. A parser-related change should produce at least
one of:

- a new capability
- a stronger (more general, more robust) existing capability
- a better diagnostic
- a better regression fixture
- better validation
- better confidence signal (once confidence exists — see Phase 5)
- better documentation

If a change produces none of these, that's worth pausing on: is this actually improving the
engine, or just patching a symptom in a way that will need patching again the next time a
similarly-shaped real document shows up? Every real fix in this codebase's own history satisfies
this rule by construction — `OFFSET_COLUMN_ANCHORS`, the singular `"deposit"`/`"withdrawal"`
hint fix, the closing-balance amount fallback, `GRID_METADATA_TRAILING_LABEL`,
`LEADING_NARRATION_CONTINUATION` each left behind a named capability and a regression test, not
just a fixed file. A change that only makes one specific real file's specific upload succeed, with
nothing of that shape surviving in the codebase afterward, hasn't actually followed this rule yet.

---

## The Trust Rule

> **Finora does not mark imported data as verified because parsing completed successfully.
> Verification is granted only when independent rules confirm the imported data is consistent with
> evidence contained in the source statement. Every new parser capability should, where possible,
> ship with a verification rule that can contradict it.**

Not the same as the Capability Rule above, and the difference is the point. The Capability Rule
governs *whether a change was worth making*. This one governs *what the system is entitled to
claim afterwards*. A parser can gain a genuine new capability and still be wrong on the very next
document; "it parsed" has never been evidence that "it parsed correctly".

Nor is it the "evidence before capability" discipline in
[scaling-triggers.md](scaling-triggers.md) and
[api-compatibility-policy.md](../../project-management/standards/api-compatibility-policy.md). That one is about **when to build**:
do not add infrastructure ahead of a condition that makes it necessary. This one is about **what
to assert**: do not report correctness you cannot demonstrate. A codebase can follow either while
violating the other.

**Why this became a rule.** A real HDFC statement imported three withdrawals as ₹0 and a deposit
in the wrong direction. Every stage reported success — the parser did not throw, the normalizer
produced rows, the preview rendered cleanly, and the numbers were wrong. Nothing in the pipeline
compared its output to anything, so nothing could have noticed. The user found it by eye.

**What makes it achievable here.** Bank statements are self-proving documents: the ground truth
ships inside the file. Every row prints the balance after it; most statements print their own
debit and credit totals. That evidence was sitting unused. See
[import-verification-framework.md](../../engineering/import/import-verification-framework.md) for the framework this rule
produced, and for why it reports rather than gates — a verification that refuses an import turns
any false positive into "Finora cannot read my statement", which is worse than the failure it
prevents.

**In practice**, three states must stay distinct, because collapsing them into one green tick is
how a system starts lying quietly:

| State | Meaning |
|---|---|
| not checked | no verification ran — say nothing, claim nothing |
| checked, not applicable | ran, but the document carried no evidence to check against |
| verified | a rule executed and the data agreed with the statement |

## The five things, named

These accumulate, and once two of them blur the measurements stop meaning anything. Stated once so
they stay distinct:

| | What it is | Example |
|---|---|---|
| **Capability** | a parser behaviour that improves extraction | `RIGHT_ALIGNED_AMOUNTS` |
| **Diagnostic** | a measurement explaining parse quality, good or bad | `UNANCHORED_ROWS_ABANDONED` |
| **Verification rule** | an independent correctness check against the document's own evidence | `BALANCE_CHAIN` |
| **Evidence** | what one import left behind | layout fingerprint, row count, reason histogram |
| **Corpus** | a committed regression document that exercises capabilities | `hdfc-txn-date-narration-header.trace` |

**Capability and diagnostic are the pair that actually blurred**, and the cost was concrete:
`UNANCHORED_ROWS_ABANDONED` — rows the parser could not confidently attach — was recorded through
the capability channel. A capability count that rises as the engine abandons more rows is a metric
that improves when quality drops. They now have separate channels on `DocumentContext`
(`record` and `recordDiagnostic`), which is a distinction in the code rather than a convention
someone has to remember.

The other pair worth keeping apart is **evidence and diagnostic**. Evidence is what an import left
behind; a diagnostic is a reading taken from it. Evidence is required by the rule below. A
diagnostic is a thing someone decided to compute, and the Capability Rule governs whether it earned
its place.

---

## The Evidence Rule

> **Every capability added to the import engine must leave behind evidence that it worked, failed,
> or was skipped. A capability that runs silently is a capability nobody can trust, improve, or
> retire.**

The three outcomes are the rule; "worked or failed" alone is the version that quietly fails. What
goes missing in practice is the third one. A rule that never ran, a merchant never resolved, a
verification with nothing to check against — these produce no row, no log line and no complaint, so
the system looks like it did its job and there is no way to tell the difference between *clean* and
*never examined*.

This is not a new instruction. It is a description of what the codebase already does when it is at
its best, written down so it stops depending on whoever happens to be reviewing:

- Verification reports `VERIFIED` / `WARNING` / `FAILED` / `NOT_APPLICABLE`, and the fourth state
  exists precisely so "we did not check" cannot be read as "we checked and it was fine".
- `ParseDiagnostics.NONE` carries the comment *"Nothing measured. Distinct from 'measured, and the
  answer was zero'."*
- An import that categorises a row it could not resolve records `needsCategoryReview` and queues no
  learning, because learning a guess would make one bad inference permanent and silent.
- A duplicate the user chose to import carries `not_duplicate_confirmed_at`, so reconciliation can
  tell a human decision from an engine one.
- Analysis sessions record the layout fingerprint, failure code, row count and unanchored reasons
  for a document the engine was only *asked about* — a run that imported nothing still leaves a
  trace.

**Why this became a rule.** Milestone 1's most expensive defect was not a crash. Reconciliation
re-marked transactions a user had explicitly confirmed, removing them from every spend total: the
ledger held ₹1,618.50 and the dashboard reported ₹1,528.50. Every stage succeeded. Nothing logged
anything. It was found by driving a real browser against a real database and comparing two numbers
that should have matched — and it existed for exactly as long as it did because the decision left
no evidence for the next stage to read.

**What this rule is not.** It is not a licence to add counters. That is the opposite failure, and
the [Capability Rule](#the-capability-rule) still governs: a diagnostic earns its place by being
able to prove a proposed capability *unnecessary*, and a number that only ever goes up proves
nothing.

The distinction that keeps both rules true at once:

| | |
|---|---|
| **Evidence** is *recorded* | a row, a status, a timestamp, a reason. Cheap, permanent, usually one column. Required. |
| **A diagnostic** is *surfaced* | a screen, a chart, an endpoint someone reads. Costs design and maintenance. Earned. |

Evidence is what makes a diagnostic possible later without a migration and a backfill. It is not
the diagnostic. Most capabilities should leave evidence and surface nothing.

**The test to apply at review.** For any new capability, ask: *six months from now, can someone
tell whether this ran on a given import — and if it did not, why not?* If the honest answer is "you
would have to reproduce it", the capability is not finished.

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

### The engine should explain itself

Not just "transaction imported" or "row dropped" — every parser decision should be traceable to
*why*. This already exists in narrow form today, in exactly the two places listed just above:
`TransactionNormalizer.explainFailure()` gives a specific reason a row was rejected, and
`PdfPipelineDiagnostic` reports which capabilities activated for a section. The target is the same
idea applied to *every* decision, not only rejections — `"imported because: running balance
matched, header confidence 97%, offset anchors activated, trailing amount detected, validation
passed"` is exactly as informative as `"rejected because: header confidence too low, no valid
date, balance chain inconsistent"` — and neither should ever require reading source code to
reconstruct after the fact.

This is the underlying reason the Admin Portal vision (Phase 5, below) is designed as a
per-document explainability page rather than a pass/fail status: an engine that can explain its
own reasoning is dramatically easier to debug, test, and improve than one that only produces a
final result. Not itself something that needs Phase 5's gate to start on, though — the
diagnostic-level version already exists today and should keep getting more complete as ordinary
capability work happens (see the PR Checklist's "does `PdfPipelineDiagnostic` expose the new
behavior" question, which is this principle enforced one PR at a time). It's specifically the
*full* per-decision explainability surface, wired into a real UI end users and support engineers
see, that needs Phase 5's confidence instrumentation to exist first — without it, that surface
would be explaining reasoning the engine doesn't actually have yet.

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

### Synthetic Fixture Policy

Formalizing a rule this document has followed informally since Phase 1, after an Evidence Cycle 1
audit found several *pre-existing* violations of it — real names, reference numbers, and balances
that had been copied verbatim into committed fixtures and tests instead of synthesized, despite
step 5 above always having said "build the synthetic fixture," not "reuse the real values." The
principle behind "Handling real documents" was always **real documents are evidence, not source
code** — this makes it explicit and gives it a name to cite in review.

- Real financial documents may be used during investigation (see "Handling real documents" above).
- They must never be committed.
- Any regression fixture derived from a real document must be **fully anonymized and synthesized**
  before entering the repository — not just the account holder's name, but reference numbers,
  account/card numbers, IFSC codes, balances, and any other value that came from the real file.
  Preserve only the *structural* properties a test actually depends on (a value's digit count, a
  narration's shape, an arithmetic relationship between rows) — never the value itself.
- Regression tests validate document **structure and parser behavior**, never real customer data.
  A test asserting `"104238.60"` proves nothing a test asserting `"50000.00"` doesn't; the former
  just happens to also leak what a real customer's account balance was on a specific day.

This applies uniformly to fixtures, tests, documentation, and examples — including doc comments
that quote "the exact real line" for context. Quote the *shape* instead ("a bare single-word label
with no colon"), not values that could re-identify the source document.

A lightweight pre-commit check (`scripts/check-fixture-hygiene.sh`, wired into `.husky/pre-commit`)
scans newly staged fixtures, tests, and docs for common real-data indicators (long digit runs,
IFSC-shaped codes, email addresses, phone numbers) and warns — it is not a substitute for this
policy, just a backstop against forgetting it.

### Evidence before capability

> A capability may only be introduced when at least one real document demonstrates a structural
> pattern that cannot already be represented using existing capabilities.

Synthetic fixtures *validate* a capability once it exists — the "Capability lifecycle" above
builds one from the Synthetic Fixture stage onward. Only a real document *justifies creating* one
in the first place; that's the whole reason the lifecycle starts at "Real Document," not at
"Discovery." Stated here explicitly, as an actual rule, so it's never skipped under time pressure
or enthusiasm for an elegant abstraction: no capability gets written on the strength of "we'll
probably need this eventually" — that's exactly the premature abstraction this whole document
exists to prevent, aimed at capabilities themselves instead of infrastructure.

### One capability, many documents

Before a new capability lands, its PR (see the PR Checklist below) should be able to answer three
questions:

1. Which existing real documents would benefit from this, right now?
2. Which plausible *future* documents would benefit from this — structurally, not speculatively?
3. What structural pattern does this represent — not which bank's export motivated it?

If the honest answer to the first two is "only one specific bank's file," it isn't a capability
yet. It's either a real capability without enough real evidence behind it yet (see "Evidence
before capability" above — wait for a second real document, or generalize from the one you have
and say so honestly in the PR), or it's a one-off that belongs inline in the layer it's fixing,
named for what it does structurally, not for what it's for.

### Capabilities must compose

A capability never depends on which *other* capabilities happen to be present for a specific
document — it composes with whichever generic building blocks that document needs, as peers the
pipeline assembles, never as a chain of document-specific parsers calling each other:

```
Document
    │
Capability A  +  Capability C  +  Capability G  +  Capability M   -- composition, from generics
```

not

```
Union Bank Parser -- calls --> Canara Parser -- calls --> Kotak Parser   -- a parser chain
```

This is "Build for replaceability" (above) applied one level down: that section protects the
*boundary* between `com.finora.imports.pdf` and everything outside it; this protects the
relationships *inside* that boundary from calcifying into the same kind of institution-specific
coupling the boundary exists to prevent in the first place. A capability that only makes sense
wired to one other specific capability, for one specific document's shape, has failed the Golden
Rule's fourth question ("would this still make sense with the bank's name deleted from it") just
as surely as a class literally named after a bank would.

### Prefer generalization over accumulation

A capability set that grows by addition — `AXIS_AMOUNT`, `KOTAK_AMOUNT`, `CANARA_AMOUNT`, each a
near-duplicate of the last — instead of by generalization (`SIGNED_AMOUNT_DETECTION`, covering all
three) has already violated the Golden Rule; it just took three PRs to notice instead of one.
Whenever a new capability looks structurally close to an existing one, the correct move is usually
to widen the *existing* capability's real-document coverage and regression tests, not add a
sibling next to it. `DR_CR_SUFFIX`'s own history in this codebase is the model to follow: found
first as a bare `"37.94 Dr"` suffix, then rediscovered as a parenthesized `"1627.00(Dr)"` suffix
on a different real file — the second discovery widened the *same* capability's pattern and test
coverage, and it never became `PARENTHESIZED_DR_CR` sitting alongside a separate `BARE_DR_CR`.
Periodically re-reading the Capability Registry (Phase 1) with one question in mind — "do any two
of these rows actually describe the same underlying pattern, just discovered on different files" —
is cheap. A capability set that's quietly become five overlapping variants of one real idea is
expensive to untangle later, and gets more expensive the longer it's left.

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
✓ Metadata Grid (2-row, single- and multi-column label/value rows)
✓ Metadata Grid (trailing label -- value precedes its label on the same line)
✓ Never Lose Information (unparseable rows surfaced with a reason, not dropped)
✓ Offset Column Anchors (header labels not aligned with their own column's data)
✓ Leading Narration Continuation (transaction description wraps before the date/amount row, not after)
✓ Leading Name Line (account holder name with no label at all, as the document's first line)
✓ Financial Product Discovery (four stages: Evidence Collection → Classification → Validation → Persistence)

Planned
• Excel
• Scanned PDFs / OCR
• Images
• Handwritten Statements
```

This list moves whenever a capability changes stage. It is the actual measure of progress this
document keeps insisting on (see "How progress is actually measured" below) — never "which banks
does Finora support."

### Instrumentation: recording facts, not yet analyzing them

`DocumentContext` (`com.finora.imports.DocumentContext`) is threaded through both the PDF and CSV
pipelines and records three things per parse run, deliberately facts-only per "Build data before
dashboards" below: **`FinancialDocumentMetadata`** (structural facts — page/table/column counts,
the header list, and which headers no capability recognized), a deterministic **`LayoutFingerprint`**
(a short hashed ID — e.g. `FP-1-A3F9C1E2`, where `1` is the fingerprint algorithm version, not
part of the hash itself — so "have we seen this exact layout before" is an equality
check against `statement_imports.layout_fingerprint`/`import_sessions.layout_fingerprint`, not a
JSON diff), and **`CapabilityActivation`** events (`{capability, status}` — which capabilities
actually fired on a given document, not just which ones exist in the registry below). This is
explicitly the format-agnostic entry point for both pipelines, not a PDF-specific mechanism — the
same recorder is used whether the source document was a PDF or a CSV. No scoring, no confidence,
no dashboard, no learning consumes this yet — see "Build data before dashboards" and Phase 5's own
entry criteria, none of which are met. This is the data those future systems would eventually
read, recorded now so it isn't retroactively unavailable once (if) that gate is actually met.

### Capability Registry

The single source of truth for every capability the engine understands.

`Coverage` is now a real, queryable metric: `CapabilityCoverageService` aggregates the
per-document activation events recorded since Phase 1 into how many imports each capability fired
on, plus — the useful half — which registry capabilities have never fired at all, which is either
dead code or a hole in the corpus. It also aggregates unparseable rows by failure reason and column
shape, giving the Capability Backlog below real frequency counts instead of the hand-counted "1
statement" / "6 of 7" evidence notes it still carries in places.

`Confidence` as a live metric still does not exist and is still gated (see Phase 3). Deliberately,
the coverage numbers produce **counts and nothing else** — no scoring, no thresholds, no
auto-review decisions — because the sequencing this document insists on is collect, store,
VALIDATE, then dashboard, then decide, and these numbers have not been checked against known cases
yet. Until confidence exists, "Regression tests" remains the honest proxy for whether a capability
actually works — no test, no claim.

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

#### `WRAPPED_HEADER`
- **Purpose:** one heading row printed across two or three visual lines, because the column
  headings are too long for their columns. Read a line at a time, neither half is a header — on the
  fixed-deposit schedule inside a real HDFC combined statement the upper half carries the column
  names but no date word (failing `hasDate`), and the lower half carries "Date" but only one other
  recognized name across seven cells (failing the density check that keeps prose out). So the table
  was not located at all: nine deposits imported as nothing while the import reported success.
- **Supported layouts:** any table whose headings wrap, including the centered case where the two
  lines do *not* share a left edge (the longer line starts further left — measured offsets on that
  statement run from 0.23pt to 13.77pt).
- **Implementation:** `PdfTableLocator.wrappedHeaderAt` / `mergeHeaderLines`, called from
  `locateAll` **after** the section-marker branch, so a banner line is spent on the meaning it
  already has before it can be read as half a heading. A merge is attempted only on a line that is
  not already a header by itself, so no document whose header is recognized today can have its
  header changed; it can only turn "no table found" into "table found". Lines join when they are on
  the same page, less than `HEADER_WRAP_MAX_GAP` (12pt) apart, and carry no parseable date or
  number — and the merge is refused outright unless **every** cell of the lower line joins a column
  the upper line established, which is what distinguishes a wrapped heading from a caption printed
  above the table.
- **Regression tests:** `WrappedHeaderPdfTableLocatorTest` — the two-line case, a three-line
  heading, the caption false positive, the second-heading-tier false positive, a wrapped heading
  reprinted on page 2, and the invariant below; `TraceFixtureRegressionTest` (the real statement,
  via the committed `hdfc-composite-deposit-schedules` trace).
- **Invariant:** this capability may make a table appear that was **not** being located, and may
  **not** disturb one that was. Pinned by
  `WrappedHeaderPdfTableLocatorTest.aTableThatWasAlreadyBeingLocatedIsNotDisturbed`, which asserts
  it never fires on the two traces that have no wrapped heading, and that the savings ledger in the
  one that does comes through with its 84 rows, 76 dated, and its columns unchanged. Structurally
  it holds because a merge is only ever attempted on a line that is not already a header. Anything
  that widens that precondition turns this into a general row-merging engine; the invariant is
  written down so that change has to be deliberate.
- **Boundary:** the locator recovers wrapped VISUAL headings. It does not determine
  financial-product semantics. Product terminology (`Principal`, `Instalment`, `Maturity Amount`,
  `Current FD Amount`) and multi-tier financial headings are downstream interpretation concerns and
  must not be special-cased inside `PdfTableLocator` — see that class's own "Where this class
  stops".
- **Maturity:** Beta — one real document, two tables within it.
- **Known limitations:** a heading whose upper line has FEWER labels than the table has columns is
  refused, and read from its lower line alone. The recurring-deposit installment schedule in the
  same statement is one — six columns, four labels above them, with "Instalment Amt Due" (x=181.53)
  and "Closing balance\*\*" (x=470.53) named only on the lower line. It extracts every installment
  correctly but names those columns "Number" and "Due" rather than "Instalment Number" and
  "Instalment Amt Due" — a loss of exactly the vocabulary that would identify the table. Admitting
  a new column was tried and measured: bounding new columns to the span the upper line covers
  leaves 470.53 outside it, and removing the bound lets the fixed-deposit tier below back in, which
  splits that table and re-anchors it on three columns. Separating the two needs a signal this
  class does not have where it decides — the data rows beneath the heading.
- **Known limitations (cont.):** that schedule prints a **second heading tier** lower down
  (`Current FD Amount #`, `Maturity Available Date **`, `Withdrawable***`) for the second visual
  line of each deposit record. It is correctly not treated as a header, but its columns are not
  recognized either, so it lands as one unparseable row and the values beneath it bucket against
  the upper tier's anchors. Two-tier records are not attempted here. Separately, that table's
  amounts are right-aligned under centered headings, and the correction that would place them
  (`RIGHT_ALIGNED_AMOUNTS`) is gated on the column being recognized as an amount column — which
  deposit vocabulary ("Principal", "Rate Of Interest", "Maturity Amount") is not. Measured, not
  assumed: adding estimated widths to the trace changes nothing on its own. Both belong to the
  deposit-attribute work, not here.
- **Known limitation (deferred to the intelligence layer):** a two-line block of pure LABELS scores
  as a wrapped heading. A summary panel reading "Opening Balance | Debit Amount | Credit Amount |
  Closing Balance" over "as on Date | Total | Net | Carried" is dateless, numberless, tightly spaced
  and column-aligned — every signal a wrapped heading has — so it closes the table above it and
  opens a section that never receives a row, leaving a phantom account. Bounded two ways: the same
  outcome is already reachable without this capability (a single label line that scores as a header
  does it too), and an empty section carries `mayCreateAutomatically=false`, so it is offered for
  review rather than created. Pinned as current behaviour by
  `doesNotYetRejectATwoLineLabelBlockThatScoresAsAHeading`. The obvious guard — require a merged
  heading to be followed by a row that reads as data under it — was implemented and measured: it
  rejects the label block correctly and also rejects the real fixed-deposit schedule, whose amounts
  mis-bucket into its date column so no row within any sane lookahead yields a parseable date. It
  removes the capability's only real win. Deciding this needs the semantic relationship between a
  heading and the data beneath it, which is this layer's question and not the locator's.
- **Known unverified:** a bank that reprints only PART of a wrapped heading on later pages. The
  symmetric case is verified — a heading reprinted in full merges identically and is recognised as
  `REPEATED_HEADER` rather than opening a second section
  (`aWrappedHeadingReprintedOnTheNextPageIsTheSameTable`). The asymmetric case appears in no
  committed document, so no behaviour is guaranteed for it and none is claimed. Recorded as an
  open scenario rather than closed by speculative code.

#### `INFERRED_HEADERLESS_LAYOUT`
- **Purpose:** a transaction table with no header row anywhere in the document — not a wrapped or
  malformed one, none at all. Found on a real SBI savings statement whose column vocabulary
  (Date/Narration/Debit/Credit/Balance) never appears as text at all, so `looksLikeHeaderRow` never
  scores true and the document returned zero sections despite a geometrically regular, 7-column
  transaction table.
- **Supported layouts:** any table with no header vocabulary at all, provided its transaction rows
  are geometrically regular (stable column x-positions) and carry a date, a narration, a running
  balance, and separate debit/credit columns. Not a general "infer any layout" mechanism — it is
  this one well-evidenced shape, architected to fire on any document with it rather than hardcoded
  to SBI, not a stand-in for the broader candidate-layout work this could grow into.
- **Implementation:** `PdfTableLocator.inferHeaderlessSection`, attempted only once `locateAll`'s
  header-based main loop has already produced zero sections. Row classification
  (`isTransactionShapedRow`) requires both a date-parseable cell and a decimal-amount cell on the
  same physical row; column discovery (`clusterIntoColumns`) clusters cell positions using a
  right-aligned amount's right edge and everything else's left edge, the same split
  `RIGHT_ALIGNED_AMOUNTS` needs at bucketing time, applied one step earlier; each column's role
  (Date, Description, or a numeric candidate) is decided from the content shape of its own values,
  never from a label. The one genuinely ambiguous decision — which numeric column is Debit and
  which is Credit — is resolved by trying the small, bounded set of plausible assignments
  (`resolveDebitCreditByBalanceChain`) and keeping whichever one's running-balance arithmetic
  actually holds up against the real data, a selection heuristic scored independently of
  `BalanceChainValidator` (a different architectural layer) rather than a replacement for it — the
  real verification still runs downstream, unchanged, on whatever labeling this settles on.
- **Regression tests:** `HeaderlessLayoutInferenceTest` — hand-synthesized fixtures only, per the
  Synthetic Fixture Policy (the real motivating document is never committed).
- **Invariant:** may only ever turn a document that located zero sections into one with rows; it is
  gated on `sections.isEmpty()` at the point `locateAll` would otherwise have returned, so it is
  unreachable on any document whose header-based path already finds something.
- **Maturity:** Beta — one real document.
- **Known limitations:** a statement whose closing summary block (totals, counts) is not marked by
  `PAGE_FOOTER` or `STATEMENT_CLOSING_MARKER` — the motivating document's own "Statement Summary"
  heading is neither — gets folded into the last transaction's Description as trailing noise rather
  than dropped, bounded to at most `MAX_BLOCK_CONTINUATION_ROWS` lines and never touching a date,
  amount, or balance cell. A numeric-candidate pool larger than `HEADERLESS_MAX_NUMERIC_CANDIDATES`
  (4), or a document where no Debit/Credit assignment clears the acceptance threshold, bails to
  today's zero-section outcome rather than guessing — by design, but means this fires narrower than
  the shape it targets until measured against more real headerless statements.

#### `PAGE_BOUNDARY_ISOLATION` / `PAGE_FOOTER_EXCLUSION`
- **Purpose:** a page-number footer line, a per-page repeated title banner, or a statement-closing
  marker line must never merge into the last real transaction row before it.
- **Supported layouts:** any paginated statement with a "Page X of Y"-style footer, a repeated
  per-page title/account banner, and/or a closing marker line (e.g. "**** End of Statement ****").
- **Implementation:** `PdfTableLocator` (`lastRowPage` page-boundary tracking, `PAGE_FOOTER` and
  `STATEMENT_CLOSING_MARKER` patterns).
- **Regression tests:** `ParenthesizedDrCrRunningBalancePdfPreviewGeneratorTest`,
  `StatementClosingMarkerPdfPreviewGeneratorTest`.
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
- **Purpose:** extract account-level metadata (e.g. Due Date, Credit Limit) from a payment-summary
  block laid out as a genuine label-row/value-row grid, rather than single-line "Label: Value" text.
- **Supported layouts:** an N-column label/value grid where a row of labels and a row of their
  values are vertically stacked rather than on the same line — originally scoped against a real
  HDFC statement's single-label-per-row shape; widened against a real Axis Bank Neo Rupay statement
  whose payment-summary header line carries FIVE labels at once ("Total Payment Due Minimum
  Payment Due Statement Period Payment Due Date Statement Generation Date"), with a Statement
  Period *range* ("24/06/2026 - 22/07/2026") sharing the value row with the standalone Payment Due
  Date field.
- **Implementation:** `PdfMetadataExtractor` — `findGridValue` (shared bounded-window scan for the
  first value of an expected shape after a label line), `GRID_DUE_DATE_LABEL`/`DATE_RANGE_MEMBER`
  (a date immediately adjacent to `" - "` is excluded, so a period's own start/end date is never
  confused with a standalone due-date field on the same row), `GRID_CREDIT_LIMIT_LABEL` (negative
  lookbehind excluding "Available Credit Limit," which shares the literal substring "Credit Limit"
  with the plain field this targets, on the same header line), `AMOUNT_LIKE` (requires either a
  decimal suffix or comma-grouped thousands formatting, not a decimal specifically — a real HDFC
  statement's Credit Limit is a whole rupee amount with no decimal places at all). The same-line
  `CREDIT_LIMIT`/`PAYMENT_DUE_DATE` "Label: Value" checks (above) only commit to a match when the
  captured text actually parses as the expected type — a real HDFC multi-column header line
  ("TOTAL CREDIT LIMIT (Including Cash) AVAILABLE CREDIT LIMIT AVAILABLE CASH LIMIT") satisfies
  the same-line label pattern too, and its greedy trailing capture used to swallow the rest of the
  line as if THAT were the value, silently failing and skipping past the line before this grid
  fallback ever got a chance to run on it.
- **Regression tests:** `GridMetadataFallbackPdfPreviewGeneratorTest`,
  `MultiColumnPaymentSummaryGridPdfPreviewGeneratorTest`, `PdfMetadataExtractorTest`.
- **Maturity:** Beta.
- **Known limitations:** only the "label row first" line shape — see the entry directly below for
  the reversed "value first" shape. The header-label match is a "contains" check, not a full
  positional column-index mapper (see the doc comment on the Test Corpus Strategy's evidence-first
  discipline for why: real x-position data isn't available at this stage, and a full N-column
  positional mapper built from plain text alone risks silently picking the WRONG column when
  labels/values don't line up 1:1 in count, e.g. a range field like Statement Period producing two
  date tokens for one label) — each field's own value-shape filter (date vs. amount, plus
  range-exclusion for dates) is what keeps this safe rather than a general column splitter.

#### `LEADING_NAME_LINE`
- **Purpose:** extract the account holder's name from a document with NO label for it at all —
  distinct from both `ACCOUNT_HOLDER`'s "Label: Value" shape and `GRID_METADATA_TRAILING_LABEL`'s
  "value precedes its label" shape.
- **Supported layouts:** the account holder's plain name as one of the first few lines of a
  section's pre-table text, with nothing identifying it as a name — verified against three
  independent real statements from three different banks (a Bank of Baroda savings account, an
  Axis Bank Neo Rupay credit card statement, a Kotak Mahindra Bank savings statement). The first
  two put the name as the literal first line; the Kotak statement's first two lines are a generic
  title ("Account Statement") and a date range before the name appears on the third.
- **Implementation:** `PdfMetadataExtractor` — `LEADING_NAME_LINE` pattern (optional courtesy
  title, then 2–4 capitalized words, no digits — capped the same way
  `ACCOUNT_NAME_TRAILING_LABEL` caps its own word count, same overreach-prevention reasoning),
  bounded to the first few lines (`LEADING_NAME_LINE_SEARCH_WINDOW`) rather than line 0 only,
  `LEADING_TITLE_WORDS` (a small denylist — "account," "statement," "card," "credit," "name," ...
  — since a generic title line like "Account Statement" shape-matches a real name exactly as well
  as one does), and rejected outright when `BankRegistry.detect()` recognizes the line as a known
  bank's own name/alias.
  <br><br>
  Bug fix: a real Canara Bank e-passbook labels the account holder with the bare word `"Name"`
  ("Name PRIYA NAIR," no colon) — `ACCOUNT_HOLDER`'s same-line "Label: Value" pattern (the
  one this capability is the fallback FOR) now recognizes `Name` as a third synonym alongside
  "Account Holder (Name)" and "Customer Name," so this specific document no longer falls through
  to `LEADING_NAME_LINE` at all. `"name"` was still added to `LEADING_TITLE_WORDS` defensively —
  if some future document's "Name" label and its value ever end up split across lines instead of
  sharing one, the bare word "Name" alone must not be swept into a `LEADING_NAME_LINE` match as if
  it were part of the person's actual name.
- **Regression tests:** `MultiColumnPaymentSummaryGridPdfPreviewGeneratorTest` (including a test
  that existing fixtures' leading "AXIS BANK"/"HDFC BANK" letterhead lines are never misread as an
  account holder), `PdfMetadataExtractorTest`.
- **Maturity:** Beta.
- **Known limitations:** deliberately the most conservative pattern in this pipeline — no label to
  anchor on at all, so it's gated behind three independent conditions (a bounded line window, a
  narrow word-count/shape cap plus title-word denylist, and the `BankRegistry` exclusion) rather
  than firing on shape alone. A statement whose first few lines contain a bank name `BankRegistry`
  doesn't yet recognize, or a generic title phrase not yet in `LEADING_TITLE_WORDS`, would still
  risk a false positive; a statement whose holder name appears later than the search window won't
  match at all.

#### `GRID_METADATA_TRAILING_LABEL`
- **Purpose:** extract account-level metadata from a grid where each row's VALUE comes BEFORE its
  own label on the same line (`"900011112222333 Account Number"`, `"UBIN0999999 IFSC"`) — the
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

#### `LEADING_NARRATION_CONTINUATION`
- **Purpose:** a transaction whose narration/description text wraps across multiple lines
  *before* its own date+amount row, rather than after it (the shape `WRAPPED_DESCRIPTION` already
  handles). A real Canara Bank statement's layout renders each transaction as: 2–3 narration lines
  (no date) → the date+amount+balance line (with a fragment of narration mixed in) → exactly 2
  trailing detail lines (a transaction time+reference line, then a `Chq: <number>` line — also no
  date) → the next transaction's own leading narration begins.
- **Supported layouts:** any table where a transaction's narration can appear either before or
  after its date row, including the case where BOTH happen for the same transaction (Canara's own
  shape) and the case where only trailing occurs (`WRAPPED_DESCRIPTION`'s shape — this capability
  doesn't replace that one, it composes with it).
- **Implementation:** `PdfTableLocator.locateAll` — a dateless row merges *backward* (unchanged
  `WRAPPED_DESCRIPTION` behavior) into the last row as long as fewer than
  `MAX_TRAILING_CONTINUATION_ROWS` (2) trailing rows have already been claimed by it; beyond that
  cap, a further dateless row is instead buffered into `pendingLeading` and merged *forward*,
  prepended, into the next date-bearing row once it appears — genuinely can cross a page boundary,
  unlike trailing continuation, which is deliberately still page-scoped (see `PAGE_FOOTER`'s own
  reasoning). A row added via the "nothing to attach to yet" path (an Opening Balance-style summary
  line, the very first row of a section) is closed to trailing continuation immediately, so it can
  never absorb the first real transaction's leading narration the way it did before this fix.
- **Regression tests:** `LeadingNarrationContinuationPdfPreviewGeneratorTest`.
- **Maturity:** Beta.
- **Known limitations:** `MAX_TRAILING_CONTINUATION_ROWS` is sized from the two real layouts seen
  so far (HDFC needs 1, Canara needs 2) — a real document needing 3+ genuine trailing continuation
  lines for one transaction would currently see the 3rd+ misclassified as the next transaction's
  leading narration instead. Revisit the constant, not the algorithm, if that real document shows
  up (see "Prefer generalization over accumulation" — widen this capability's own test coverage
  first, rather than reaching for a second, competing mechanism).

#### `FINANCIAL_PRODUCT_DISCOVERY`
- **Purpose:** answer "which financial PRODUCT is this section?" before transactions are parsed,
  instead of "which account is this?" — a question with no honest answer for a term-deposit summary
  or an installment schedule, which were therefore forced into being accounts or dropped.
- **Supported layouts:** any section, of any document format. The stages consume column names,
  section-scoped text and row counts, none of which are PDF-specific — wired into both the PDF
  (`PdfPreviewGenerator`) and CSV (`StatementValidator`) paths.
- **Implementation:** four stages that never blend, in `com.finora.imports.product`:
  `ProductEvidenceCollector` (Stage 1 — records `ObservedFact`s, makes no decisions and does no
  scoring), `FinancialProductClassifier` (Stage 2 — scores facts against every `ProductHypothesis`),
  `ProductValidator` (Stage 3 — can the winner PROVE what it claims), and `ProductDiscovery`
  (Stage 4 — the persistence gate, `mayCreateAutomatically()`). Three rules carry the weight, each
  from a real failure: **no single signal decides** (a hypothesis needs two independent positive
  signals); **contradiction disqualifies rather than subtracts** (a signal a product should never
  carry means the reading is wrong, not marginally less likely); and **where a name was found
  outweighs which name it was** (`EvidenceSource`).
- **Regression tests:** `FinancialProductClassifierTest`,
  `CompositeMultiProductClassificationTest` (all three sections of a composite statement classified
  correctly), `DepositAttributeExtractionPdfPreviewGeneratorTest`, `DepositIdentityPerDepositTest`,
  `ProductIdentityTest`/`ProductIdentityResolverTest`, `ProductAttributeExtractorTest`.
- **Maturity:** Beta.
- **Identity, attributes, and routing (added after the entry above was first written):** a
  discovered product carries a stable `ProductIdentity` — a hash of institution + the product's own
  number, never the number itself — so re-importing next month's statement recognises the same
  deposit instead of creating another one. A deposit also carries its own terms (principal, rate,
  maturity date, installment amount), and a fixed-deposit section splits into one product PER ROW,
  since a real FD section lists every deposit the customer holds separately. A recurring deposit
  deliberately does not split: its rows are installments of one product, and splitting them would
  multiply one real deposit into several phantom accounts.
- **Known limitations:** the `MIN_CORROBORATING_SIGNALS = 2` rule means a genuinely single-signal
  document reaches UNKNOWN rather than a correct answer — deliberate, since UNKNOWN costs one
  question on the review screen while a confident wrong product silently writes wrong data into
  someone's net worth. Products with no structural vocabulary yet (PPF/EPF/NPS/demat/mutual fund)
  are recognised by name only and always report UNPROVEN, so they can never auto-create. Identity
  requires a recognised institution AND a full account number: `BankRegistry`'s `OTHER` sentinel is
  not treated as an institution (that would make every product from an unrecognised bank identical),
  so a statement from an unknown bank gets no strong identity and falls back to a masked-digit
  PROBABLE match at best. Deposit attribute extraction and per-row splitting are **PDF-only** — no
  real CSV export in the corpus represents a multi-deposit schedule, and building that handling with
  no real document behind it is what "Evidence before capability" rules out.

#### Closed: auxiliary text is not section-scoped
- **Status:** **closed** by `FINANCIAL_PRODUCT_DISCOVERY` above. Previously documented as a known
  gap in `FinancialProductClassifier`'s `NAMED_IN_TEXT_WEIGHT` and asserted honestly in its test.
- **What the gap actually was — the documented explanation was wrong.** It was recorded as "a
  combined statement prints 'Savings Accounts' in its relationship summary and that phrase ends up
  in the auxiliary text of the deposit sections further down." Dumping the real trace's sections
  showed the deposit sections have *zero* auxiliary text. The leak was never the cause. The actual
  cause was that `looksLikeALedger` fired on **any one** ledger word, and a fixed-deposit schedule
  has a `Deposit(Mnth)` column — the monthly contribution amount, not money moving in. One keyword
  made the whole section a transaction account.
- **Fix:** a ledger is now a *combination* — a date column AND a free-text description column AND
  some form of amount AND rows (`SectionEvidence.looksLikeALedger`). A deposit schedule has no
  narration column, because it records amounts against dates rather than events. Separately, and
  still worth having, `EvidenceSource` now distinguishes document-level from section-level text,
  and free text naming two or more distinct products is demoted to document level automatically
  (one section is one product, so an enumeration cannot be describing one section) — that closes
  the leak the original note *described*, even though it was not the bug it was blamed for.
- **Corpus damage found along the way:** `PdfTraceRedactor`'s allowlist had no deposit vocabulary,
  so the committed trace had `"Maturity Date"` redacted to `"Xxxxxxxx Date"` and `"Deposit(Mnth)"`
  to `"Deposit(Xxxx)"` — the exact column headers product classification keys on, removed from the
  fixture meant to regression-test classifying them. The allowlist is fixed, but **a committed
  trace cannot be un-redacted**: traces captured before this need re-capturing from their real
  source files to exercise product classification. Until then the composite-statement test asserts
  the deposit sections are *not accounts* (true, and the regression that mattered) rather than that
  they are deposits (unprovable from a fixture whose evidence was redacted away).

#### Open Investigation: HDFC credit-card table over-extension (only 2/40 rows parsed)
- **Status:** root-caused, deliberately **not fixed yet** — this needs real design work, not a
  same-session patch (see "Don't fix it yet, root-cause it" below).
- **Symptom:** a real HDFC "Tata Neu Plus" credit card statement staged only 2 transactions out of
  40 raw bucketed rows, 38 dropped. Initially mischaracterized (in an earlier draft of this
  document) as a metadata gap — it is not. `accountHolderName`/`creditLimit` being null on this
  same file are a separate, already-documented issue (see `GRID_METADATA_TRAILING_LABEL`'s
  deferred-evidence tests above); this entry is about the transaction TABLE itself.
- **Root cause (confirmed via `PdfPipelineDiagnostic` against the real file):** `PdfTableLocator`
  never recognizes where this document's real transaction table ENDS. Once bucketing starts, it
  continues — under the same `[TRANSACTION DESCRIPTION, DATE & TIME, AMOUNT, PI, Base NeuCoins*]`
  header — straight through "Benefits on your card," the "IMPORTANT INFORMATION" bullet list, a
  completely different NeuCoins summary table, Terms & Conditions text, a GST entry table, a
  "Useful Links" table, and the digital-signature block at the very end of the document. Of the 40
  rows this produces, only 2 contain anything that resembles a real transaction date — the other
  38 are all later, unrelated content misbucketed as if it were more rows of the same table.
  Neither existing trailer-exclusion mechanism catches this: `PAGE_FOOTER`/`STATEMENT_CLOSING_MARKER`
  both require a specific recognizable marker (a page-footer pattern, "End of Statement" text)
  that this document's real trailer simply doesn't contain.
- **Which of the three paths this needs:** not a metadata capability extension (nothing in
  `PdfMetadataExtractor` is involved) and not a new leaf capability either — this is a
  **table-detection pipeline gap** in `PdfTableLocator` itself: some notion of "this no longer
  looks like more of the same table" is missing entirely for the case where trailing content is
  neither a page footer nor a closing marker, just structurally different text (long free-form
  sentences, a second table with a different header, a signature block).
- **Why this isn't being fixed inline:** a hasty heuristic (e.g. "stop after N rows with no
  date-like value") risks silently breaking the legitimate multi-page tables
  `PAGE_BOUNDARY_ISOLATION`/`REPEATED_HEADER` already handle correctly for other real documents —
  the exact failure mode "Prefer generalization over accumulation" warns against. This needs a
  second real document confirming the general shape before a specific mechanism is designed, per
  "Evidence before capability" — tracked in the Capability Backlog below, not attempted here.

### Capability Backlog (generated from real-document validation, not yet built)

Real documents keep producing evidence even when no capability gets written from it immediately —
this table is where that evidence is preserved instead of disappearing once the file itself is
deleted (see "Handling real documents" above). An entry here is a claim backed by at least one
real document, with an honest evidence count; it graduates to the Capability Registry once
"Evidence before capability" is satisfied and the implementation actually lands.

| Capability (candidate name) | Evidence | Priority | Why deferred |
|---|---|---|---|
| `ACCOUNT_NUMBER_RECOGNITION` | 6 of 7 real statements in the Aug 2026 validation pass | High | Recurring, not a one-off — `ACCOUNT_NUMBER`/`ACCOUNT_NUMBER_TRAILING_LABEL` only match an explicit "Account Number" label; real statements embed it mid-sentence ("Statement for A/c XXXXXXXXX1455"), under an unrelated label ("Alternate Account Number"), or as a masked card number never labeled "Account Number" at all. Needs its own evidence-gathering pass across these real shapes before a mechanism is designed. |
| Credit-card table boundary detection (`PdfTableLocator`) | 1 statement (HDFC Tata Neu Plus — see the Open Investigation above) | High | High-impact (2/40 rows parsed) but single-document evidence for the *general* shape of the fix; needs a second real document before a specific mechanism (vs. another one-off marker pattern) is justified. |
| `VALUE` → trailing label → trailing value (composite account-holder line) | 1 statement (HDFC: `"<card number> Credit Card No. <NAME>"`) | Low | Genuinely a structural pattern, not an HDFC quirk — could recur as `"Loan Number XXXXXXXX Borrower Name"` or similar on another institution's export. Documented as an observed shape to watch for (see the deferred-evidence test in `PdfMetadataExtractorTest`), not built on one document's strength. |
| Scrambled / split multi-row credit-summary grid | 1 statement (same HDFC file) | Low | The specific column/row scrambling in this one document isn't yet known to generalize; a naive fix was verified to produce a *wrong* value (₹200 instead of ₹78,000), which is worse than the current null — see that same deferred-evidence test's doc comment for the full reasoning. |
| Embedded narration reference numbers | 1 statement (Canara — reference number embedded inside free-text transaction narration, not a dedicated column) | Medium | Would need free-text mining rather than column-based extraction — a materially different mechanism from every existing capability, not a small extension of one. |
| Column Anchor Alignment Consistency (`PdfTableLocator`) | 1 statement (Union Bank — see Evidence Cycle 2 in the [Changelog](../../project-management/milestones/financial-document-intelligence-changelog.md)) | Medium | The `TransactionNormalizer.DESCRIPTION_HINTS` fallback added this cycle fixes the *symptom* (an empty description reaching the user), not the *cause*: the header row correctly detects a `"Remarks"` column, but the corresponding data values bucket under `"Transaction Id"` instead — a column-anchor mismatch between where a header token and its own column's data land. Investigate why `PdfTableLocator`'s bucketing can disagree with its own header detection for a document's data rows, and improve table reconstruction so values land under the correct semantic column without needing downstream recovery logic like this cycle's fallback. Single-document evidence so far — a Financial Document Engine improvement, not a Union-Bank-specific one; watch for a second real document before designing a specific mechanism. |

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

### Test Corpus Strategy

The regression fixtures under `PdfFixtureBuilder` and their matching tests are, collectively, the
engine's test corpus — and it's worth being explicit about how that corpus is allowed to grow,
since "build a comprehensive test corpus" is easy to read as license to invent scenarios ahead of
evidence. It isn't. The corpus grows exactly the same way a capability does:

```
Real Financial Document
        │
PdfPipelineDiagnostic
        │
Root Cause
        │
Generic Capability
        │
Synthetic Fixture
        │
Regression Test
        │
Capability Registry
        │
Delete Real Document
```

**Do NOT invent capabilities, header aliases, or layouts ahead of evidence.** Concretely:
- Don't add a header alias (`"Posting Date"`, `"UTR"`, `"Paid In"`, ...) because it seems plausible
  — only because a real document used it. Every hint in `TransactionNormalizer`'s hint arrays
  should be traceable to the real file that motivated it (see that class's own comments for the
  pattern — every existing entry already follows this).
- Don't build corrupted-document / OCR-noise tests before OCR exists (see "Excel, Scanned PDFs /
  OCR... — Planned" above) — there's nothing yet for those tests to protect.
- Don't build Excel/OFX/QFX/CAMT.053/MT940 regression suites before those parsers exist — a test
  corpus for a parser that hasn't been written is a spec, not a corpus, and inverts the order that
  has worked for every capability so far.
- Don't generate speculative layout variations (arbitrary column counts, alignments, hidden
  columns) with no real document behind them.

**What the corpus SHOULD grow through**, beyond the one-capability-per-real-document flow above:
- **Composability testing** — most fixtures exercise one capability in relative isolation, but a
  real document rarely activates only one. Combine already-evidenced capabilities (each
  independently justified by its own real document) into one fixture to prove they still work
  together — see `CapabilityCompositionPdfPreviewGeneratorTest` for the pattern. This is not
  inventing a new capability; it's testing that existing ones compose.
- **Capability-indexed organization** — `PdfFixtureBuilder`'s own class doc comment is a lookup
  table from capability name to fixture method(s), grouped and ordered to match the Capability
  Registry below, not chronologically (when each was added). Keep it current when adding a fixture.
- **Parser independence** — `TransactionNormalizer.normalize()` is the single place row-level
  meaning gets attached, shared unmodified by the CSV and PDF paths; `ParserIndependencePreviewGeneratorTest`
  proves the same logical statement data normalizes identically regardless of which parser
  produced the row map. Extend this the day a third real parser (Excel, OFX, ...) actually exists
  — not before.

**How progress here is measured** — deliberately not "number of fixtures," which rewards volume
over substance: capability coverage (how many registry capabilities have regression tests — see
below), capability composition (how many are verified working together, not just alone),
regression strength (does the suite actually catch a real regression, not just pass), and evidence
quality (can every fixture/hint be traced to the real document that motivated it). A corpus that
scores well on fixture count but poorly on evidence quality is exactly the "attractive
infrastructure built too early" this document's Phase 0 rules exist to prevent.

---

## Why Phases 3–6 and the Admin Portal stay a direction, not a roadmap item

Layout Profiles, per-field confidence, a corrections-and-metrics data layer, a capability registry
in code, layout fingerprinting, and eventually AI-driven extraction are sound ideas, worth having
written down precisely so they don't get reinvented differently each time someone thinks about
this problem again. They should **not** be built now. The honest trigger for starting Phase 3 (and,
by the same reasoning, Phase 5's instrumentation) is *real correction volume and real capability
count across many real documents* — not architectural elegance, and not because the idea is
appealing. Right now this pipeline has processed a handful of real files, several of which needed
real bug fixes found by reading actual extracted text by hand, and has a dozen or so capabilities —
small enough that "read the source" is still the honest way to answer "is this capability worth
its cost." Building a knowledge base, a confidence-scoring UI, a metrics pipeline, or an AI
extraction layer around that little data and that few capabilities would be exactly the premature
abstraction this whole document exists to prevent — just aimed at infrastructure instead of a bank
name. The same test applies to every phase below: not "would this be valuable eventually" (yes,
obviously) but "does the *current* scale of the problem actually need it yet."

### Build data before dashboards

The specific order Phase 5 and the Admin Portal must follow, once triggered — stated as its own
principle because it's easy to build in the appealing order (a dashboard first, since it's the
visible, demoable part) instead of the correct one:

```
Collect deterministic outputs   -- the pipeline already produces these; just start recording them
        │
Store metrics                    -- durable, queryable, not yet trusted
        │
Validate metrics                    -- confirm the numbers are actually correct against known cases
        │                              before anyone makes a decision from them
Build dashboards                       -- only once the data behind them is trustworthy
        │
Use dashboards for decisions              -- auto-review thresholds, prioritization, capability
                                             retirement -- only once the dashboard has been live
                                             long enough to have earned that trust
```

A dashboard built on unvalidated metrics is worse than no dashboard — it looks authoritative and
isn't, and a wrong "94% confidence" is more dangerous than an honest "we don't know yet," the same
way a garbled branch name was judged worse than a null one (`GRID_METADATA_TRAILING_LABEL`'s known
limitations, Phase 1). This is the same discipline as everywhere else in this document, restated
for the observability track specifically: don't build abstractions before they're needed, don't
build AI before deterministic capabilities exist, don't build learning before validation exists,
don't build dashboards before the data behind them has earned trust.

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

## Phase 5 — Observability & Capability Governance (direction, gated)

Everything in Phase 1–2 makes capabilities *work*. Nothing yet makes them *measurable*. Right now
"is `OFFSET_COLUMN_ANCHORS` actually pulling its weight" has one honest answer: read the source
and the regression test. That doesn't scale past a handful of capabilities, and it's the thing
that makes Phase 3 ("Collect Knowledge") more than a slogan — you can't collect knowledge about a
capability's real-world behavior if nothing records that behavior.

**Entry criteria — start Phase 5 only once ALL of the following are true**, not when any one of
them first becomes true, and not because the idea is appealing:

- 25+ stable (Phase 1's registry maturity, not Beta) document capabilities
- 20+ distinct layout fingerprints observed (informally — Phase 6's actual fingerprinting doesn't
  exist yet at this point, so this means "20+ recognizably different real-document shapes seen,"
  judged the same honest way `PdfPipelineDiagnostic` is read today)
- 10,000+ imported documents
- multiple developers actively working on the document engine (a single-developer team has no
  coordination problem for a registry to solve)
- manual debugging of import issues consumes noticeable, recurring engineering time — not a single
  memorable bad afternoon, a pattern
- confidence decisions have started affecting product UX (auto-review vs. manual review), so
  confidence scoring stops being speculative infrastructure and starts being something a real
  feature depends on

Below this line, "read the source" is still the correct, sufficient answer — building any of the
below earlier would be exactly the premature abstraction this whole document exists to prevent,
just aimed at infrastructure instead of a bank name. Only once every box above is checked does it
become worthwhile to build:

- **Per-capability metrics.** Times evaluated, times matched, times rejected, average confidence
  (once confidence exists — see below), which documents/fingerprints actually exercised it. Turns
  "is this capability worth its maintenance cost" from a guess into a number.
- **A Capability Registry *in code*, not just in this document.** This doc's own registry table
  above is deliberately the source of truth *today* — hand-maintained is honest and sufficient at
  the current scale. The code version is what replaces it once capability count and document
  volume make hand-maintenance itself the bottleneck: name, description, owner, status, confidence,
  dependencies, regression tests, known limitations, version — queryable, not just readable.
- **Confidence at every pipeline stage, not one score per document.** Layout, metadata, table
  detection, field extraction, balance-chain reconstruction each produce their own confidence;
  "96% overall" is an average that hides exactly the field that needs a second look. This is what
  makes an auto-review-vs-ask-the-user threshold in the review UI meaningful instead of arbitrary.
- **Unknown becomes first-class data, not a debugging session.** `PdfPipelineDiagnostic`'s
  `[UNKNOWN FIELDS]` reporting (Phase 1) is the seed of this — today it prints to a console and
  disappears. Stored instead: unknown header, unknown metadata label, unknown table shape, unknown
  section marker, unknown symbol/currency, unknown layout — accumulated across real documents
  rather than rediscovered fresh every time someone happens to run the diagnostic by hand. This is
  the concrete mechanism behind "unknown today may become tomorrow's capability" (Capability
  lifecycle, Phase 0) — right now that sentence is aspirational; stored unknowns are what would
  make it literally true.
- **Every capability declares its dependencies.** `RUNNING_BALANCE` depends on amount + date +
  balance; `COMPOSITE_STATEMENT` depends on section detection + header detection + table
  detection. Lets the engine (and a developer) answer "why did this fail" by walking a dependency
  graph instead of re-deriving it from the code each time.
- **Capability versioning and an explicit lifecycle.** A capability evolves (`WRAPPED_DESCRIPTION`
  v1 "simple continuation" → v2 "page boundary support," in this codebase's own real history) —
  regression tests should be able to say which version introduced which behavior. Lifecycle:
  Observed → Diagnosed → Implemented → Regression Tested → Validated → Production → Learning →
  Optimized. This document's own "Capability lifecycle" (Phase 0) is stages 1–4 of this; stages
  5–8 don't exist yet because nothing downstream of "regression tested" is instrumented.
- **Technical debt tracked per capability, not globally.** Confidence below threshold, high
  manual-correction rate, frequent regression failures, high maintenance effort, known
  limitations — a per-capability view is what lets prioritization follow actual impact instead of
  whichever bug report arrived most recently.
- **A Capability Maturity Index**, once there are enough capabilities and enough real data that
  "Stable/Beta" alone stops being a fine-grained enough signal — a small fixed scale (e.g. 1–5) per
  dimension (evidence, regression strength, composition coverage, parser independence,
  documentation, performance, observability) per capability, so two Stable capabilities can be
  compared instead of both just reading "Stable." Structures the same judgment a human already
  makes reading the source today; doesn't replace that judgment with a formula.
- **Test Impact Analysis**, once the code-level Capability Registry above exists with declared
  dependencies — a capability change runs only that capability's own tests plus its declared
  dependents' tests, not the full suite. A CI mechanism built on top of the dependency graph above,
  sequenced strictly after it, not a separate effort — there's nothing for this to walk until
  dependencies are actually declared somewhere machine-readable.
- **Capability Similarity**, later, and explicitly still deterministic — comparing two layout
  fingerprints' capability sets and structural facts to surface "this new layout looks 90% like an
  existing one" as a plain set-overlap calculation, not a model. Useful once there are enough
  fingerprints for "is this genuinely new or a close variant of something we already handle" to be
  a real, recurring question — not before. No relation to Phase 4's AI layer; this stays arithmetic
  on facts DocumentContext already records.

**What this deliberately is not, yet:** a mandate to build a metrics pipeline, a database schema,
or a registry service this sprint. Every one of the above is real work, and per the entry criteria
above, none of it should be built until every one of those boxes is actually checked — the same
discipline that's kept Phase 3–4 honest applies here without exception.

## Phase 6 — Learn From Every Import, Not Just Failures (direction, gated on Phase 5's instrumentation existing)

Phase 3 ("Collect Knowledge") already establishes recording-not-learning and the Layout Profile
concept. This phase makes both concrete.

**Entry criteria — start Phase 6 only once ALL of the following are true:**

- Phase 5's instrumentation already exists and is live (Phase 6 has nothing to learn from without
  it — this is a hard dependency, not just a suggested order)
- 100,000+ successful imports
- thousands of validated user corrections
- sufficient diversity of layouts that recurring patterns are actually identifiable, not just a
  handful of one-off documents
- a documented privacy and governance model for any learning derived from user data — see this
  phase's own "learning dataset" point below and Phase 3's "what gets shared across users" point;
  this criterion is not satisfied by an "anonymized" label, only by an actual resolved design

Below this line, Phase 3's recording-not-learning discipline is still the right amount of
ambition. Only once every box above is checked should the below be considered:

- **Layout Fingerprint**: a structural signature of a document — metadata strategy (leading-label
  vs. trailing-label vs. grid), table strategy (single amount column vs. separate debit/credit),
  header strategy (repeated vs. once), date format, amount format, section count, running balance
  present or not, which capabilities activated — independent of which bank issued it. Two
  completely different banks producing the *same* fingerprint is the concrete proof this document
  keeps insisting on: the engine is learning layouts, not institutions.
- **A gold-standard set from perfect imports, not only from corrections.** Every successfully
  imported document already has a rich signal sitting unused: which fingerprint, which
  capabilities fired, what confidence resulted, how many corrections the user needed (zero, for a
  clean import), whether validation passed. Phase 3 as written focuses on capturing *failures and
  corrections*; this adds capturing *success* as data too, since a document that needed zero
  corrections is exactly the kind of example a future model would need to learn what "confident
  and correct" looks like — not just what "wrong" looks like.
- **The learning dataset is fingerprints and capabilities, never raw documents or bank names.**
  Same privacy discipline Phase 3 already states explicitly for cross-user sharing (layout
  knowledge, never customer data) — restated here because Phase 6 is where that discipline would
  actually get exercised at scale, not just declared.
- **The self-improving-engine questions**, once Phase 5–6 both exist: has this fingerprint been
  seen before; which already-known capabilities solve most of this document; which sections are
  low-confidence; is a mismatch a new capability or a variation of an existing one; can the
  unknown portion be isolated without touching the rest of a document that otherwise parsed
  cleanly. Only the genuinely novel remainder should ever need a human. This is the same "AI
  proposes, deterministic code validates" boundary (Phase 4, "What AI can never do") applied to
  the question "does this document need new code" instead of "is this transaction real" — the
  answer is still never AI's to act on unilaterally.

## The Admin Portal as the Engine's Control Center (direction, gated on Phase 5 existing to power it)

Every diagnostic idea above needs a surface a human actually looks at — `PdfPipelineDiagnostic`'s
console output (Phase 0) is the developer-only, one-document-at-a-time version of this; the target
is the same explainability, generalized: every processed document gets a full "why did this
succeed or fail" page, and no one on the team — developer, QA, or support — should need to read
backend logs to answer that question.

Organized the same way the underlying engine is layered, not as a grab-bag of screens:

- **Overview** — import volume, confidence trends, capability usage across the whole system.
- **Documents** — per-document drill-down: pipeline stage-by-stage status (🟢/🟡/🔴), the layout
  fingerprint, which capabilities activated and which didn't (and why — "not required" vs.
  "attempted, failed"), the full confidence breakdown by stage, metadata extraction with its
  *source* shown per field (which capability produced it, not just the value), and the "Never lose
  information" view made concrete: every dropped row, its reason, and — once Phase 5's suggestion
  mechanism exists — which capability might explain it. **Import Replay** is this same drill-down
  played back stage-by-stage (extraction → metadata → table detection → capability activation →
  validation), the same data the stage-by-stage status already renders, just walked through
  instead of shown all at once — a presentation of Phase 5's data, not a new data source.
- **Capabilities** — the code-level registry (Phase 5) rendered: status, confidence, dependencies,
  version history, a usage heatmap (which capabilities actually matter vs. which fire on 4% of
  documents), the capability timeline (which version is live), and — once Phase 5's Capability
  Similarity metric exists — which fingerprints cluster near each other.
- **Benchmark Center** — a frozen **Golden Dataset** (a fixed set of real-shaped documents that
  must always import cleanly — see Phase 3's "gold-standard set from perfect imports," the same
  data, just re-run as a gate) and a **Stress Dataset** (the hardest known-real layouts on file),
  both re-run on every change alongside the regular regression suite. Frozen deliberately: these
  exist to catch a regression against KNOWN-good behavior, not to grow — new real documents feed
  Phase 1's ordinary capability/fixture flow instead, same as always.
- **Diagnostics** — parser decisions and warnings in plain language ("header confidence low: 13
  detected cells, expected ~5, suggests `HEADER_SANITY`") instead of a stack trace, plus the
  Unknown Patterns view Phase 5's stored-unknowns feed directly.
- **Learning** — Phase 6's surface: documents imported, unique fingerprints, capabilities learned
  this period, manual corrections, confidence trend over time. Once real (Phase 4), an AI
  Recommendation panel belongs here specifically — *proposing* a new capability with its own
  confidence and evidence ("seen in 5 documents"), never implementing one. A human still decides
  and writes the code; this is a suggestion queue, not an autopilot.
- **Regression** — the same `mvn test` result (619 passing as of this writing) rendered per
  capability instead of as one pass/fail number, so "which capability's coverage changed in this
  deploy" is visible without reading a diff.
- **Performance** — per-stage timing (extraction, layout detection, metadata, table detection,
  validation), since a capability that's technically correct but pathologically slow is its own
  kind of technical debt (see Phase 5's per-capability debt tracking).

Same caveat as Phase 5: this is a real, multi-week UI effort, not a next-sprint item. It's recorded
here in full because a dashboard built piecemeal without this shape in mind tends to end up as
disconnected screens bolted onto whatever happened to be easiest to query that week — writing the
target shape down now is what keeps it a *deliberate* rollout later, one section at a time, each
gated on the underlying engine capability (Phase 5's metrics, Phase 6's fingerprints) actually
existing to power it — not a screen built ahead of the data that would make it honest.

---

## PR Checklist

The Golden Rule's four questions (above) are the philosophical check. This is the concrete one —
every parser-related PR should be able to answer all twelve before merging:

1. What capability is being added?
2. Can another institution reuse it, unchanged?
3. Does it remove or increase coupling to a specific document's shape?
4. Is anything named after a bank? (The only legitimate exception: `BankRegistry`/`Bank`/
   `BankAlias`/`BankDto` business metadata — see Phase 0's "Naming.")
5. Is there a synthetic regression fixture (`PdfFixtureBuilder`)?
6. Is there a regression test, capability-named, run against the *whole* suite?
7. Does `PdfPipelineDiagnostic` expose the new behavior (a new capability signal, a new metadata
   field, a new drop reason)? (See "The engine should explain itself.")
8. Are unparseable rows preserved, not silently dropped (see "Never lose information")?
9. Does AI remain advisory rather than authoritative, if this PR touches anything AI-adjacent (see
   "What AI can never do")?
10. Does this move the engine closer to understanding documents in general, rather than adding
    another institution-specific parser?
11. Is there real-document evidence behind this specific capability, not just plausible future
    benefit (see "Evidence before capability")?
12. Does this generalize an existing capability's coverage instead of adding a near-duplicate
    sibling next to it (see "Prefer generalization over accumulation")?

If the honest answer to any of these is "no" or "not applicable in a way that's actually fine,"
that's worth writing down in the PR description — not skipped silently.

### Capability Impact Report

Answering PR Checklist question 1 ("what capability is being added?") as prose — "added a Name
synonym" — loses the before/after shape that makes a capability's own history legible later.
Every PR that changes an *existing* capability's coverage (not a brand-new one — those get a full
Capability Registry entry instead) should include a short impact report in this exact shape:

```
Capability:  ACCOUNT_HOLDER
Before:      Supported labels — Account Holder (Name), Customer Name
After:       Supported labels — Account Holder (Name), Customer Name, Name
Evidence:    Canara Bank e-passbook ("Name PRIYA NAIR", no colon)
Regression:  PdfMetadataExtractorTest#extract_recognizesBareNameAsAnAccountHolderLabelSynonym,
             #extract_doesNotMisreadAWordMerelyStartingWithName_asTheBareNameLabel
```

Cheap to write, and it's what actually lets a future developer (or this document's own Capability
Registry entries) answer "what changed and why" without reading the diff.

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
Phase 5: Observability & Capability Governance -- direction only, gated on capability
    │                                              count/document volume making
    │                                              hand-maintenance the bottleneck
Phase 3: Collect Knowledge      -- direction only, gated on real correction volume
    │                              (and made concrete by Phase 5's instrumentation)
Phase 6: Learn From Every Import  -- direction only, gated on Phase 5 existing
    │
Phase 4: Evaluate Training         -- direction only, gated on Phase 3/6's data existing
    │
Admin Portal Control Center          -- direction only, gated on Phase 5/6 existing
                                         to power it
```

Phase 5 is drawn alongside Phase 3 deliberately, not strictly after it — it's the instrumentation
that makes Phase 3 more than a slogan, so in practice they'd likely be worked on together once
either is triggered. Phase 6 gates on Phase 5, not on Phase 3 directly, since fingerprinting and
learning-from-success both need the same underlying metrics/confidence substrate Phase 3's
correction-recording needs too.

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
