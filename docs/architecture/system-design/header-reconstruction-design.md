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
addressed separately in §8 and Document 2.

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
*suspected contributor*, not a proven mechanism, per §8.

## 4. Proposed architecture

Do not replace `mergeHeaderLines`. Its refinement model is correct, in production, on the documents
it was built for — the same real corpus this design must not regress. Introduce a **Header
Reconstruction Engine** as a recovery mechanism reached through a **Header Quality Gate**, not
directly on merge refusal.

### 4.1 The trigger is quality, not merge failure — traced against the real code, not assumed

The first version of this design gated the new engine on "`mergeHeaderLines` returned `null`."
Traced against `PdfTableLocator`'s actual header-acceptance loop (around line 930), that trigger is
wrong, and wrong in a way that would make SBI itself never reach the new engine:

- **Iteration on row 1** ("Transaction Details"): `looksLikeHeaderRow(row1)` is false, so
  `wrappedHeaderAt(rows, row1, alreadyScores=false)` runs. This is the call that refuses — the one
  traced in §3. But because `row1` alone does not score as a header either, the outer `if
  (looksLikeHeaderRow(headerRow))` at line 949 is **never entered** for row 1. Nothing is accepted
  here; row 1's only effect is being recorded as reconstruction evidence.
- **Iteration on row 2** ("Date | Amount ( \` )"): this is a **separate loop iteration on a
  different row**, two physical lines later. `looksLikeHeaderRow(row2)` is **true** — it has a date
  cell and an amount cell, which is enough to score alone. `wrappedHeaderAt(rows, row2,
  alreadyScores=true)` is called too, but it is asking a different question now ("does row2 wrap
  onto row3, the first real transaction row?") — and refuses for the ordinary reason that row3
  carries a date/number and so cannot be a header continuation. `headerRow` stays `row2` unchanged,
  `looksLikeHeaderRow(headerRow)` is true, and **row 2 is accepted as the header** — through the
  exact same code path every ordinary, never-wrapped header in the whole document goes through.

There is no decision point where "did a merge attempt fail" is ever asked about `row2`, the row
that actually becomes the header. Row 1's refusal and row 2's acceptance are two different loop
iterations examining two different rows; nothing connects them except that they happen to be
physically adjacent. **A trigger keyed on merge failure would miss SBI's own accepted header
entirely**, confirming the concern raised in review even more precisely than the review's own
example: it is not that "`mergeHeaderLines` succeeded and produced a bad result" — `row2` was never
run through a *successful* merge at all, wrapped or otherwise. It is a standalone row that happens
to satisfy the same bar every real header satisfies, while quietly explaining only 2 of the table's
3 real columns.

The gate therefore has to sit on **whatever header row is about to be accepted**, at line 949,
regardless of which path produced it — a genuine single-line header, a successful wrap, or (as
here) an unwrapped fallback sitting near a row that failed to wrap for something else entirely:

```
Physical header-region rows
            |
            v
  ┌──────────────────────┐
  │  mergeHeaderLines()   │   <- unchanged, tried first where applicable
  │  (refinement)         │
  └──────────────────────┘
            |
            v
  headerRow accepted by looksLikeHeaderRow
  (wrapped, unwrapped, or fallback -- any path)
            |
            v
  ┌──────────────────────┐
  │ HeaderQualityValidator│   <- NEW: runs on the accepted row, not on merge success/failure
  └──────────────────────┘
            |
       +----+----+
       |         |
     good       weak
       |         |
   existing   Header Reconstruction Engine
   path         |
   (unchanged)  v
       |    1. Collect header fragments
       |         |
       |         v
       |    2. Classify vocabulary
       |         |
       |         v
       |    3. Build candidate column layouts
       |         |
       |         v
       |    4. Validate against real transaction rows
       |         |
       |         v
       |    Best candidate, or HEADER_RECONSTRUCTION_UNCERTAIN retained (no forced guess, §4.3)
       |         |
       +---------+
            |
            v
      Column mapping / Transactions
```

`HeaderQualityValidator` is deliberately its own component, not folded into `looksLikeHeaderRow` —
that method answers "is this plausibly a header at all," a question every row in the document is
asked; the new validator answers "is the specific header this document is about to commit to good
enough to trust," a question asked exactly once per section, right before commitment. Conflating
them would make `looksLikeHeaderRow` slower and more speculative for the ~20 documents that already
pass through it correctly today.

### 4.2 What makes a header "weak" — signals, not a merge outcome

Per the review: too few expected columns for the section's apparent document type (§4.4), missing
transaction-critical vocabulary (no date-shaped column, or no amount-shaped column at all), poor row
compatibility when sampled against the section's own rows (§4.9's validation, reused here as the
gate's own strongest signal), and a high unparseable-row ratio among the sampled rows. None of these
require a merge to have been attempted, let alone failed — SBI's `[Date, Amount]` fails this gate on
"too few expected columns for a credit-card statement" and "poor row compatibility" alike, entirely
independent of the fact that a merge was tried and refused on a different row three lines above it.

### 4.3 The gate's own failure state — no forced guess

`HeaderQualityValidator` finding a header weak does not guarantee the reconstruction engine finds a
better one. Per review: the existing `TRANSACTION_HEADER_RECONSTRUCTION_UNCERTAIN` reason on
`HeaderReconstructionFinding` already models exactly this outcome and should be kept, not replaced.
If no candidate in §4.9 clears the same evidence bar the accepted candidate would need to clear, the
engine returns nothing — the section is left in its current, honest "uncertain" state, never
silently downgraded to picking whichever candidate scored highest even though none of them were
actually good. A worse header with a confident-looking justification is a worse outcome than an
honestly unresolved one.

### 4.4 Candidate validation needs document-type context

Per review, the engine cannot validate "does this look like a complete header" against one fixed
schema — a savings account's real column set (date, description, debit/credit or a single signed
amount, balance) is not a credit card's (date, description, amount — usually no running balance
printed per row) is not an RD/FD's (a different model entirely, and already out of scope per
`ground-truth-model-design.md`/ADR-005 §10). The candidate-layout step (§4.8) and the quality gate
(§4.2) both need to know which of these shapes the section is being evaluated against, sourced from
wherever `ProductDiscovery`/account-type detection already determines section type today — not
re-derived from scratch inside this engine.

### 4.5 Safety property, restated precisely

Ordering refinement first, and gating on accepted-header quality rather than raw merge
success/failure, is together the safety property: no document whose accepted header already clears
the quality bar today can be reached by the new engine — nothing currently correct changes shape.
The engine only ever runs on sections whose accepted header the gate itself judges weak, which by
construction includes every one of today's `HeaderReconstructionFinding` cases and would have
included SBI from the very first version of this design if the gate had been checked against the
right row.

### 4.6 Step 1 — Collect header fragments

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

### 4.7 Step 2 — Classify vocabulary

Each fragment is scored against the same hint vocabulary `looksLikeHeaderRow` and
`wholeCellHintMatches` already use (`DATE_HINTS`, `HEADER_HINTS`) — reused, not reinvented, so a
fragment's classification means the same thing here as it means to the rest of the pipeline:

```
"Withdrawals"  -> DEBIT
"Deposits"     -> CREDIT
"Transaction Details" -> DESCRIPTION
```

### 4.8 Step 3 — Build candidate column layouts

Generate more than one plausible partition of the fragments into columns — not a single guess. At
minimum: the layout `mergeHeaderLines` would have produced if the refusal gate were relaxed (all
fragments folded into their nearest neighbor regardless of distance), and the layout formed by
treating every column-vocabulary match as its own column regardless of which line it came from.
Concretely, for SBI:

```
Candidate A: Date | Amount                              (today's fallback)
Candidate B: Date | Transaction Details | Amount ( ` )   (fragments as separate columns)
```

### 4.9 Step 4 — Validate against real transaction rows

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

## 5. Candidate scoring — qualitative tiers, not weights

Internal ranking only — never a user-facing confidence number, consistent with `RoutingTextAcquirer`
already establishing (§ "no confidence thresholds... shown NOT to predict financial correctness")
that OCR/recognition confidence has been measured and rejected as a decision signal on this
pipeline. The same discipline applies here, taken further per review: rather than a point-scoring
formula (even an internal one), signals are grouped into three evidence tiers. A candidate is
compared to another candidate by tier — does it clear a higher tier of evidence, not by how many
points it accumulates within one:

**Strong evidence** (a candidate clearing this tier is preferred over one that does not, regardless
of any other signal):
- Date column parses across the sampled real transaction rows
- Amount column(s) parse across the sampled real transaction rows
- Balance chain is internally consistent, when a balance column is present and the section's
  document type expects one (§4.4)

**Medium evidence** (a tiebreaker only between candidates that are equal on strong evidence):
- Column vocabulary matches (how many fragments classify against known column names, §4.7)
- Geometry aligns (fragments compose into columns without unexplained gaps or overlaps)

**Weak evidence** (never decisive alone; present mainly so a candidate with only this tier is
visibly weaker, not silently promoted):
- Header words exist and score via `looksLikeHeaderRow`'s ordinary token-aware bar

A candidate with only weak evidence should not usually win over a candidate with any strong
evidence — but this design does not commit to an exact tie-breaking formula between tiers, or to
what happens when two candidates tie within the same tier. That calibration is implementation work,
measured against the regression corpus (Document 2), not a design-time guess. **Only after real
production data exists — genuine ambiguous cases the qualitative tiers alone cannot separate —
should numerical calibration be considered**, and even then as a measured refinement of these tiers,
not a replacement for them.

**Negative signals** (count against a candidate within whichever tier comparison is being made,
never on their own promote a different candidate):
- Many unparseable rows under this candidate
- Cells merged across what the vocabulary classification suggests are two distinct columns
- A mandatory field for the section's document type (§4.4) has no column at all
- Two fragments both classify to the same column with conflicting positions (collision)

## 6. Explainability output

Per review: not user-facing initially, but every reconstruction the engine performs should leave a
trace precise enough to debug without re-running the investigation from scratch — the same
motivation that already produced `explainWrap`'s DEBUG-level narrative for ordinary wrap
decisions (§2 of this doc references it implicitly via `mergeHeaderLines`), and the same motivation
`HeaderReconstructionFinding` itself already partly serves. This is that same idea extended to cover
the reconstruction attempt itself, not just the fact that one was needed:

```
Header Reconstruction:

Original: 2 physical header rows, refused by mergeHeaderLines (§4.1)

Recovered: Date | Transaction Details | Amount ( ` )

Evidence:
  [strong] Date column parses on 30/30 sampled rows
  [strong] Amount column parses on 30/30 sampled rows
  [medium] 3/3 fragments classify against known vocabulary
  [medium] geometry: no unexplained gaps between composed columns
```

Or, on the uncertain path (§4.3):

```
Header Reconstruction:

Original: 3 physical header rows, refused by mergeHeaderLines (§4.1)

Recovered: none -- retained as HEADER_RECONSTRUCTION_UNCERTAIN

Best candidate considered: Date | Particulars | Debit | Credit | Balance
  [weak] header vocabulary present
  [absent] row compatibility -- 0/2 sampled rows had any rows to sample
    (section produced 0 raw bucketed rows; see phase2e1 investigation, IOB)
```

Where this lives (a field on `HeaderReconstructionFinding`, a separate evidence object, a DEBUG log
in the style of `explainWrap`) is an implementation decision, not fixed here. What is fixed by this
design: the explanation is structured evidence tied to the tiers in §5, not a prose summary
generated after the fact — so it can eventually feed the kind of derived, rule-based reliability
signal `import-verification-framework.md` already anticipates but does not yet compute, without
being redesigned when that day comes.

## 7. Regression strategy

The full matrix is Document 2 ([header-reconstruction-regression-corpus.md](header-reconstruction-regression-corpus.md)).
Two properties any implementation must hold, both falsifiable against the real corpus before merge:

1. **Zero regression on documents that already work.** Because the new engine is only reached when
   `HeaderQualityValidator` (§4.1–4.2) judges the accepted header weak, this is structural rather
   than something that needs to be measured fresh for every one of the ~20 already-passing real
   documents — but it still needs an explicit test asserting the engine is never invoked on any of
   them, the same kind of structural guarantee `RoutingTextAcquirerTest` already asserts for
   native-first OCR routing. This also means the quality gate itself needs its own regression
   coverage: a test asserting it judges every one of those ~20 documents' real accepted headers
   "good," not just that the reconstruction engine is skipped as a side effect.
2. **Recovery on the three known failures**, verified against real transaction counts from the
   external ground-truth corpus, not just "a header was found."

## 8. Non-goals

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
- **A numeric scoring formula.** §5 lists tiers, not weights. Calibration is implementation work,
  measured against the corpus, not a design-time guess.
- **Any change to `mergeHeaderLines` itself.** It is correct on its own cases; this design adds a
  quality gate and a fallback path after it, not a rewrite of it.
- **Folding `HeaderQualityValidator` into `looksLikeHeaderRow`.** Per §4.1, they answer different
  questions at different frequencies — one is asked of every row in a document, the other once per
  section, right before a header is committed to. Merging them would slow and complicate the
  common, already-correct path for no benefit.
