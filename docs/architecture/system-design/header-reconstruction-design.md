# Design: header reconstruction from distributed fragments

**Status:** design only. No implementation, and none should follow until this is agreed — the risk
this phase exists to manage is not "does a fix exist" but "does a fix for SBI silently regress
every statement that already works," and that risk is only controlled by writing the architecture
down before touching `mergeHeaderLines`.
**Evidence base:** [transaction-boundary-phase2a-investigation.md](transaction-boundary-phase2a-investigation.md),
[row-completeness-phase2d-investigation.md](row-completeness-phase2d-investigation.md),
[header-reconstruction-phase2e1-investigation.md](header-reconstruction-phase2e1-investigation.md)
(PR #239) — every claim about SBI, IOB, and HSBC below is sourced from that document's traced,
coordinate-level evidence, not re-derived here.

---

## 1. Problem statement

> How does Finora reconstruct a trustworthy transaction table header when the source text does not
> present the header as a clean single row?

Three real, unredacted documents in the ground-truthed corpus lose an entire section's transactions
today because the header their real layout prints cannot be reconstructed: SBI Credit Card.PDF
Section 1 (~30 rows), Statement.pdf/IOB (2 rows), HSBC DB.pdf (7 rows, via OCR). In every case the
mechanism responsible for noticing this, `HeaderReconstructionFinding`, already fires correctly —
it is an internal, evidence-only signal, never surfaced to a user, that nothing downstream currently
acts on. The gap is not detection. It is recovery.

Phase 2E.1.1/2E.1.2 changed the framing of what "recovery" needs to mean. The prior working
hypothesis, going into that investigation, was three separate bugs. The evidence does not support
that:

> **There is one shared missing capability — header reconstruction from distributed fragments —
> plus two separate contributing layers that are not header-reconstruction problems at all.**

## 2. Existing algorithm limitation

Today's flow:

```
PDF/OCR text
      |
      v
Physical rows
      |
      v
Header detection (looksLikeHeaderRow)
      |
      v
mergeHeaderLines()
      |
      v
Column mapping
      |
      v
Transactions
```

`mergeHeaderLines`'s only model of a multi-line header is **refinement**: seed a column set from the
first physical line, then fold every subsequent line's cells into the *nearest* already-seeded
column (within `HEADER_WRAP_MAX_COLUMN_JOIN`, 40pt). This is correct and load-bearing for the cases
it was built for — three real HDFC statements and Central Bank of India, all of which print the
*same* set of columns across two lines, one line completing or renaming what the other started:

```
Date        Details
Date        Details
```

It has no model at all for a header whose columns are **partitioned** across lines — where line 1
names some columns and a different line names other, non-overlapping columns, rather than the same
ones restated:

```
SBI:                          IOB:                              HSBC (OCR):
             Amount           Date(Value      Ref No.           Date
Date                Balance           Particulars               Details
                                Debit    Credit    Balance       Withdrawals
                                                                  Deposits
                                                                  Balance
                                                            (5 labels, y-jittered
                                                             across several rows)
```

In every one of these, some cell on some line sits farther than 40pt from every column
`mergeHeaderLines` has seeded so far — not because the tolerance is wrong, but because that cell
belongs to a column the seed line simply never named. `mergeHeaderLines` returns `null` (refuses the
whole merge) the first time this happens, and the document falls back to whichever single line
scores as a header alone — always a worse, narrower reading than the real header.

## 3. Real-document evidence

Full traces, exact coordinates, and the precise decision points where each merge is refused are in
[header-reconstruction-phase2e1-investigation.md](header-reconstruction-phase2e1-investigation.md).
Summarized for this design:

### SBI Credit Card.PDF, Section 1 — confirmed, precisely traced

Header spans 2 physical lines. Line 1 supplies exactly one column ("Transaction Details", centered).
Line 2 supplies the other two ("Date" far-left, "Amount" far-right), both >40pt from line 1's single
anchor. `mergeHeaderLines` refuses on the first attempt; the accepted fallback header is the bare
2-cell "Date | Amount" line, and all ~30 real rows lose their description, debit/credit direction,
and running balance at Stage 4 normalization.

### Statement.pdf, IOB — confirmed, precisely traced, two layers

Header spans 3 physical lines with the same partition problem — "Particulars" on line 2 sits in the
gap between line 1's two seeded columns, >100pt from either. The merge is refused on the very first
two-line attempt, so line 3 is never even reached. **A second, independent problem compounds this**:
line 1's "Date(Value" and "Ref No." — two distinct column names — arrive from `PdfTextExtractor`
already fused into one `PositionedText` run, a PDFBox line/word-grouping decision made before
`PdfTableLocator` ever sees the text. This layer is out of scope for header reconstruction and is
addressed separately in §7 and Document 2.

### HSBC DB.pdf — confirmed still live via OCR; mechanism is a strong hypothesis, not fully traced

Reproduced directly against the real OCR-routed pipeline (`RoutingTextAcquirer` +
`TesseractRecogniser`, not the plain-extraction diagnostic tools, which do not exercise OCR at all).
Acquisition succeeds (244 runs, matching prior evidence — the earlier "0 runs" reading was a
diagnostic-tool gap, not a regression). `HeaderReconstructionFinding` fires with
`vocabularySignals: [withdrawals, deposits]` and an accepted header of only `[Date, Balance]`. The
five real header labels ("Date", "Details", "Withdrawals", "Deposits", "Balance") sit within 5.3pt
of each other in y — visually one printed line, but OCR's per-word bounding-box jitter is consistent
with that being enough to split them into more than one physical row before header logic ever runs.
**What is confirmed**: OCR acquisition works, the vocabulary is present, header fragments exist, and
reconstruction fails. **What is not yet confirmed**: the exact physical-row bucket each label landed
in — this pass did not dump `groupIntoRows`' private row-assignment output. Kept explicitly as a
*suspected contributor*, not a proven mechanism, per §7.

## 4. Proposed architecture

Do not replace `mergeHeaderLines`. Its refinement model is correct, in production, on the documents
it was built for — the same real corpus this design must not regress. Introduce a **Header
Reconstruction Engine** that runs when refinement fails, rather than mutating refinement itself to
try to do two different jobs:

```
Physical header-region rows
            |
            v
  ┌──────────────────────┐
  │  mergeHeaderLines()   │   <- unchanged, tried first
  │  (refinement)         │
  └──────────────────────┘
            |
       succeeds? ──yes──> Column mapping (unchanged)
            |
            no
            v
  ┌──────────────────────────────┐
  │ Header Reconstruction Engine │   <- new, only reached on refusal
  └──────────────────────────────┘
            |
            v
  1. Collect header fragments
            |
            v
  2. Classify vocabulary
            |
            v
  3. Build candidate column layouts
            |
            v
  4. Validate against real transaction rows
            |
            v
       Best candidate, or none
            |
            v
      Column mapping / Transactions
```

Ordering refinement first, unconditionally, is a safety property, not a preference: no document that
scores a successful merge today can be reached by the new engine, the same "structural, not
asserted" guarantee `RoutingTextAcquirer`'s native-first ordering already uses for OCR routing. The
new engine only ever sees documents that currently produce a `HeaderReconstructionFinding` — i.e.,
today's failures, never today's successes.

### Step 1 — Collect header fragments

Instead of treating the header region as a fixed list of "lines," represent it as a flat list of
independently positioned fragments — one per non-blank cell in the header region, each carrying its
own text and (x, y):

```
SBI Section 1 fragments:
  { text: "Transaction Details", x: ~middle, y: line1 }
  { text: "Date",                x: far-left,  y: line2 }
  { text: "Amount ( ` )",        x: far-right, y: line2 }
```

This is the structural change from today's model: `mergeHeaderLines` reasons about *lines that
refine each other*; the new engine reasons about *fragments that, together, name the table's
columns*, regardless of which line each one happened to print on.

### Step 2 — Classify vocabulary

Each fragment is scored against the same hint vocabulary `looksLikeHeaderRow` and
`wholeCellHintMatches` already use (`DATE_HINTS`, `HEADER_HINTS`) — reused, not reinvented, so a
fragment's classification means the same thing here as it means to the rest of the pipeline:

```
"Withdrawals"  -> DEBIT
"Deposits"     -> CREDIT
"Transaction Details" -> DESCRIPTION
```

### Step 3 — Build candidate column layouts

Generate more than one plausible partition of the fragments into columns — not a single guess. At
minimum: the layout `mergeHeaderLines` would have produced if the refusal gate were relaxed (all
fragments folded into their nearest neighbor regardless of distance), and the layout formed by
treating every column-vocabulary match as its own column regardless of which line it came from.
Concretely, for SBI:

```
Candidate A: Date | Amount                              (today's fallback)
Candidate B: Date | Transaction Details | Amount ( ` )   (fragments as separate columns)
```

### Step 4 — Validate against real transaction rows

The design's central new idea, and the one that makes this safe to generalize instead of
bank-specific: **a candidate header is not accepted because it looks like a header. It is accepted
because the section's own real transaction rows fit under it better than they fit under any other
candidate — including the fallback the pipeline would otherwise use.**

```
Candidate A: Date | Amount
  Row: 05-Aug | UPI XYZ 500        <- "UPI XYZ" has no column to go in; only 1 of 2 cells resolves

Candidate B: Date | Transaction Details | Amount ( ` )
  Row: 05-Aug | UPI XYZ | 500      <- all 3 cells resolve
```

This is a generalization of a gate `refinesRatherThanRedefines` already applies narrowly today
(gate 4: a merge is admitted only if it strictly increases how many columns
`TransactionNormalizer` can name by whole-cell comparison). The new engine applies the same
principle at the scale of whole rows, not just column names: bucket a sample of the section's own
rows under each candidate, and prefer the candidate more of a row's real cells resolve against,
never the candidate that merely "reads like" a header in isolation.

## 5. Candidate scoring

Internal ranking only — never a user-facing confidence number, consistent with `RoutingTextAcquirer`
already establishing (§ "no confidence thresholds... shown NOT to predict financial correctness")
that OCR/recognition confidence has been measured and rejected as a decision signal on this
pipeline. The same discipline applies here: score to rank candidates against each other on one
document, never to compare across documents or expose to a user as a quality percentage.

**Positive signals** (increase rank):
- Header vocabulary coverage (how many fragments classify against known column names)
- Expected banking columns found (a date column and at least one amount-shaped column present)
- Transaction rows successfully map (§4's row-fit validation — the dominant signal)
- Date column parses across the sampled rows
- Amount column(s) parse across the sampled rows
- Balance chain is internally consistent, when a balance column is present

**Negative signals** (decrease rank):
- Many unparseable rows under this candidate
- Cells merged across what the vocabulary classification suggests are two distinct columns
- A mandatory field (date, at minimum one amount) has no column at all
- Two fragments both classify to the same column with conflicting positions (collision)

No signal here is proposed with a specific numeric weight. Per the same principle
`ImportVerifier`'s own doc comment already states elsewhere in this codebase — "a weighting policy
invented before there is anything to calibrate it against is a guess with an authoritative
appearance" — any concrete scoring formula belongs to the implementation phase, measured against
the full committed trace corpus, not asserted in this design.

## 6. Regression strategy

The full matrix is Document 2 ([header-reconstruction-regression-corpus.md](header-reconstruction-regression-corpus.md)).
Two properties any implementation must hold, both falsifiable against the real corpus before merge:

1. **Zero regression on documents that already work.** Because the new engine is only reached when
   `mergeHeaderLines` refuses, this is structural rather than something that needs to be measured
   fresh for every one of the ~20 already-passing real documents — but it still needs an explicit
   test asserting the engine is never invoked on any of them, the same kind of structural guarantee
   `RoutingTextAcquirerTest` already asserts for native-first OCR routing.
2. **Recovery on the three known failures**, verified against real transaction counts from the
   external ground-truth corpus, not just "a header was found."

## 7. Non-goals

Explicitly out of scope for this design, to keep it one coherent architecture rather than absorbing
every adjacent problem the investigation happened to surface:

- **IOB's `PdfTextExtractor` fusion artifact.** Splitting "Date(Value...Ref No." back into two
  cells is a text-extraction-layer fix, not a header-reconstruction one. The new engine cannot
  recover a column whose name was never presented as a separate fragment in the first place — this
  must be fixed upstream, independently, before IOB can fully recover even with the new engine in
  place.
- **HSBC's row-grouping mechanism**, until confirmed. Kept as a *suspected contributor* per §3. If
  confirmed, the fix is very likely a `groupIntoRows`/row-formation change (a y-tolerance question
  for OCR-sourced text specifically), not a header-reconstruction one — the new engine can only
  compose fragments it is handed as a coherent header *region*; it cannot repair rows that were
  mis-formed before reaching it.
- **A bank-specific branch of any kind.** No `if SBI` / `if HSBC OCR: increase tolerance`. Every
  signal in §5 and every fragment/candidate structure in §4 is general — SBI, IOB (once its
  extraction layer is separately fixed), and any future document with a partitioned header should
  all be reachable by the same engine. A document-specific special case would repeat exactly the
  failure Phase 2D already found and reverted (a marker built to fit one document's shape that
  turned out to be unsafe on another).
- **A numeric scoring formula.** §5 lists signals, not weights. Calibration is implementation work,
  measured against the corpus, not a design-time guess.
- **Any change to `mergeHeaderLines` itself.** It is correct on its own cases; this design adds a
  fallback path after it, not a rewrite of it.
