# Header Reconstruction — Phase 2E.1 Investigation

Evidence-only investigation. No production code changed. Scope, per the owner's Phase 2E framing:
for the documents where `HeaderReconstructionFinding` fires and an entire section's real
transactions are lost, what is the actual mechanism, precisely — not the general "wrapped header
collapses" description already on record, but why `mergeHeaderLines` specifically refuses each one.

## Method

Two temporary, uncommitted instrumentation techniques, both run against real, unredacted
documents and both fully removed before this doc was written:

1. `System.err.println` at the two decision points in `PdfTableLocator` — `wrapsOnto` and the
   `mergeHeaderLines` call site inside `wrappedHeaderAt` — reverted via `git checkout --`.
2. `-DdumpPage0Positions=true`, an existing flag on `PdfPipelineDiagnostic` that prints every raw
   positioned text run with exact coordinates — no code change needed, since the flag already
   exists for this purpose.
3. For SBI's and IOB's headers, `PdfPipelineDiagnostic`/`CorpusProbe` were sufficient (native PDF
   text, no OCR involved). For HSBC DB.pdf, neither tool exercises the real OCR-routing pipeline,
   so a small throwaway test (`TempHsbcOcrRepro.java`, never committed, deleted immediately after
   use) constructed the real `RoutingTextAcquirer` + `TesseractRecogniser` + `PdfTableLocator`
   exactly as production wires them and called `locateAll` directly, to get real
   `HeaderReconstructionFinding` evidence instead of guessing from raw text.

No code changes ship from this investigation.

## 2E.1 (background) — SBI Credit Card.PDF, Section 1 — root cause fully diagnosed

**The real header spans two physical lines, but not the way any currently-handled case does.**

```
                                          Transaction Details
Date                                                                                    Amount ( ` )
```

Line 1 has exactly one cell: **"Transaction Details"**, centered in the middle of the table's width.
Line 2 has exactly two cells: **"Date"** (far left) and **"Amount ( \` )"** (far right) — with nothing
in the middle; the visible gap under "Transaction Details" is blank on line 2's own row.

Traced precisely: `wrapsOnto("Transaction Details", "Date | Amount")` returns `true` — the gap
(7.87pt) is well inside `HEADER_WRAP_MAX_GAP` (12.0), the lower line carries no date/number of its
own, and it isn't a structural marker. So `wrappedHeaderAt` proceeds to `mergeHeaderLines`, which
returns **`null`** — refusing the merge entirely. This is not a threshold miss; it's a structural
mismatch. `mergeHeaderLines` builds its column set by seeding from the block's first line
(`"Transaction Details"`, one column) and then, for every cell on every following line, finds the
*nearest existing column* within `HEADER_WRAP_MAX_COLUMN_JOIN` (40.0pt) and folds it in. "Date" and
"Amount" both sit far outside 40pt of the single "Transaction Details" anchor — one is at the far
left edge, the other at the far right, and "Transaction Details" is in the middle. Neither has
anywhere to join, so the merge is refused for the same reason `columnFor` already logs when it
happens: *"lower cell joins no column above it... a caption, or a second heading tier."*

**Why this differs from every case this mechanism already handles.** Every documented wrapped-header
success in this file — the three real HDFC statements (P-001), Central Bank of India's two-band
header, the general model the class's own doc comments describe — shares one shape: **each physical
line names the SAME set of columns, and a lower line either completes or renames a column the upper
line already started.** `mergeHeaderLines`'s whole algorithm is built on that assumption: seed
columns from line 1, then fold each later line's cells into the *nearest* column that already
exists. SBI's real header is a different shape entirely: **line 1 supplies only the middle column's
name, and line 2 supplies the other two columns' names** — not a refinement of one column, a
partition of three columns across two lines with no positional overlap between them. The algorithm
has no path for "this line supplies columns 1 and 3; a different line supplies column 2." It was
never asked to compose columns from different lines, only to extend the same ones.

Because the merge fails, "Transaction Details" is discarded entirely and "Date | Amount ( \` )" — a
real 2-cell row that happens to satisfy `looksLikeHeaderRow`'s own bar (a date-hint cell plus one
more recognized name) — is accepted as a complete header on its own. Every real transaction row that
follows has no column for its description, debit/credit direction, or running balance; all 30 fail
`TransactionNormalizer`'s Stage 4 (no useful data survives bucketing into just two slots), and the
section reports zero staged rows with only an internal, evidence-only `HeaderReconstructionFinding`
marking that anything went wrong.

```
Document:
SBI Credit Card.PDF, Section 1

Header shape:
2 physical rows, columns PARTITIONED (not refined) across them

Failure class:
HEADER_PARTITION

Existing algorithm limitation:
mergeHeaderLines seeds columns from row 1 only ("Transaction Details",
1 column) and folds later cells into the NEAREST existing column within
40pt. Row 2's "Date" and "Amount" are both >40pt from that single
anchor -- on opposite sides of it -- so neither joins, the merge
returns null, and the header falls back to the 2-column row alone.

Potential general fix:
see "candidate direction" below -- a composition mode that recognizes
a later line filling an EMPTY slot between columns, not just refining
an existing one.
```

## 2E.1.1 — Statement.pdf (Indian Overseas Bank) — root-caused precisely

Re-verified: `Detected table columns: [Date), Type]`, 2 raw bucketed rows, both dropped at Stage 4.
Genuinely live, matching the reliability matrix's prior description. Exact coordinates (via
`-DdumpPage0Positions=true`) show the real header spans three physical rows:

- **Row 1** (y=284.1): two runs — one wide run reading "Date(Value ... Ref No." (x=49.7 to
  endX=314.6, a single ~265pt-wide cell), and "Transaction" (x=333.3).
- **Row 2** (y=287.3, 3.2pt below row 1): four runs — "Particulars" (x=167.7), "Debit(Rs)"
  (x=398.4), "Credit(Rs)" (x=455.6), "Balance(Rs)" (x=507.3).
- **Row 3** (y=292.8, 5.5pt below row 2): three runs — "Date)" (x=62.7), "/Cheque No" (x=274.6),
  "Type" (x=346.4).

Traced precisely: `wrappedHeaderAt` seeds from row 1 (2 cells: the wide "Date(Value...Ref No."
blob, and "Transaction"). `wrapsOnto(row1, row2)` returns true (gap 3.2pt, no data value, no
structural marker), so `mergeHeaderLines([row1, row2])` runs — and refuses immediately: row 2's
"Particulars" (x=167.7) is 118pt from the nearest seeded column ("Date(Value...Ref No." at
x=49.7) and 165.6pt from the other ("Transaction" at x=333.3), both far outside
`HEADER_WRAP_MAX_COLUMN_JOIN` (40.0pt) — it sits in the gap *between* the two seeded columns, not
near either. The merge returns `null` on this very first two-line attempt, which breaks
`wrappedHeaderAt`'s loop outright — row 3 is never even reached, so whatever "Type"/"/Cheque No"
continuation it carries is moot; the header never survives past row 2.

**Same failure family as SBI, with one compounding difference.** The surface mechanism is
identical to SBI's: a later line supplies a column at an x-position with no seeded anchor within
40pt, because `mergeHeaderLines` can only refine/extend columns the seed line already established,
never recognize that a later line fills a genuinely new slot between two existing ones. But IOB
adds a second, distinct problem sitting one layer BELOW `PdfTableLocator`: row 1's "Date(Value" and
"Ref No." — two logically separate column names — arrive from `PdfTextExtractor` already fused
into a **single** `PositionedText` run, because PDFBox's own `PDFTextStripper.writeString` callback
(which this codebase does not override the line/word-grouping heuristics of, beyond overriding
`writeString` itself to capture position) decided not to split them despite the large visible gap
between them — while the very same document's row 2, with visually smaller inter-column gaps,
*does* split into four separate runs. That split decision is emergent from PDFBox's own internal
spacing heuristics, not something `PdfTableLocator` controls. Even a `mergeHeaderLines` rewritten to
compose non-overlapping columns from different lines could not recover "Ref No." as its own column
here, because by the time it ever sees row 1, "Date(Value" and "Ref No." are already one opaque
cell with a single x/width.

```
Document:
Statement.pdf (IOB)

Header shape:
3 physical rows

Failure class:
HEADER_PARTITION (same family as SBI's mergeHeaderLines limitation),
compounded by a TEXT_EXTRACTION_FUSION artifact upstream of it (PDFBox's
own writeString grouping fuses two column names into one run on row 1
specifically, independent of the header-merge logic)

Existing algorithm limitation:
mergeHeaderLines seeds columns from row 1 only, and can only fold a later
cell into the NEAREST existing column within 40pt -- it has no path for
"this cell fills a new slot between two seeded columns." The merge is
refused on the very first (row1+row2) attempt, so the loop breaks before
row 3 is ever tried.

Potential general fix:
The composition-mode change sketched for SBI below would also be
necessary (not sufficient) here -- IOB additionally needs row 1's fused
"Date(Value...Ref No." run to be split back into two cells before
mergeHeaderLines ever runs, which is a PdfTextExtractor-level concern,
not a PdfTableLocator one. Two separate fixes, not one.
```

## 2E.1.2 — HSBC DB.pdf — OCR acquisition re-verified and reproduced; header failure confirmed live, real cause is different from the 08-18 description

Prior state: `CorpusProbe`/`PdfPipelineDiagnostic` report 0 positioned runs for this document,
because neither exercises the real OCR-routing pipeline (`RoutingTextAcquirer`) — a diagnostic-tool
gap flagged at the end of the previous investigation session, not evidence of a regression. That
gap is now closed for this document: a throwaway test wired the real
`RoutingTextAcquirer(NativePdfAcquirer, List.of(TesseractRecogniser))` exactly as production does,
and called `PdfTableLocator.locateAll` directly on the result.

```
HSBC DB.pdf

Acquisition:
PASS

Text source:
OCR (244 runs)

Header detection:
FAIL -- HeaderReconstructionFinding fires
  reason: TRANSACTION_HEADER_RECONSTRUCTION_UNCERTAIN
  vocabularySignals: [withdrawals, deposits]
  acceptedHeaderColumnCount: 2

Header reconstruction:
FAIL -- accepted header is [Date, Balance] only

Rows:
7 physical rows detected; every row buckets only a Date and a Balance
cell, so Details/Withdrawals/Deposits are lost from all 7
```

This confirms the OCR acquisition side of the 08-18 finding was and is correct (244 runs, not a
regression) — the earlier "0 runs" reading in this investigation was purely a probe artifact. But
the *header* mechanism, traced against the real OCR text this time rather than reconstructed from
memory, does not match the 08-18 description ("first line scores as 2-column header before a
3-line merge is attempted"). The real header's five labels arrive at very close but NOT identical
y-coordinates: `Details`/`Withdrawals`/`Deposits` all at y=169.7, `Date` at y=166.6 (3.1pt above),
`Balance` at y=164.4 (5.3pt above that same trio). Native PDF text from one printed line lands
within a fraction of a point of a shared baseline; Tesseract's per-word bounding boxes do not —
5.3pt of y-jitter across five words that are visually one line is normal OCR noise. The accepted
2-column header being exactly `[Date, Balance]` — the two labels sitting closest to each other in y
— combined with `vocabularySignals: [withdrawals, deposits]` being recorded (meaning the
reconstruction logic saw those words nearby and recognized them as header vocabulary, but never
folded them in) is consistent with row-grouping splitting this one visual header line into more
than one physical row along OCR's y-jitter, then failing to compose them back into one header — the
same class of "compose columns across rows" gap SBI and IOB both hit, but triggered by OCR
measurement noise rather than a genuinely multi-row-printed header. **Not confirmed to the same
precision as SBI or IOB** — this pass did not dump `groupIntoRows`' actual row-bucket assignment
(a private method), so exactly which rows `Date`/`Balance` vs. `Details`/`Withdrawals`/`Deposits`
landed in is inferred from the y-coordinates above, not directly observed. Flagged as a strong,
evidence-backed hypothesis distinct from the SBI/IOB mechanism, not yet a confirmed root cause.

**Correction (2026-08-22):** now confirmed. A follow-up pass dumped `groupIntoRows`' actual
row-bucket assignment directly (reflection-based throwaway probe, real OCR pipeline, deleted after
use): the 5 labels split into exactly `[Balance, Date]` and `[Details, Withdrawals, Deposits]`,
matching this section's inference precisely. Root cause: `groupIntoRows` clusters against a *fixed*
first-member anchor (`ROW_Y_TOLERANCE = 3.0f`, `PdfTableLocator.java:73,1838`), not the previous
member, so the 5.28pt of *total* y-jitter across the line exceeds tolerance even though each
individual consecutive gap does not. Full mechanism recorded in
[header-reconstruction-design.md](header-reconstruction-design.md) §9.4. Confirmed as
row-formation-stage, unrelated to `mergeHeaderLines`/header composition, matching this doc's own
§8-equivalent framing exactly.

## 2E.1.a taxonomy — three real documents, related but distinct failure classes

| Document | Collapsed header | Failure class | Confidence |
|---|---|---|---|
| SBI CC Section 1 | `[Date, Amount]` | `HEADER_PARTITION` — a later line supplies a column with no x-overlap to any seeded anchor | **Root-caused, precisely traced.** |
| Statement.pdf (IOB) | `[Date), Type]` | `HEADER_PARTITION` (same mechanism as SBI) **+** `TEXT_EXTRACTION_FUSION` (PDFBox fuses 2 column names into 1 run on the seed line, upstream of the merge logic) | **Root-caused, precisely traced.** |
| HSBC DB.pdf (OCR) | `[Date, Balance]` | OCR y-jitter splitting one visual header line into multiple physical rows via `groupIntoRows`' fixed-anchor clustering — **not** `HEADER_PARTITION`; this never reaches `mergeHeaderLines` at all | **Confirmed 2026-08-22 — exact row-bucket split directly observed; see correction above and `header-reconstruction-design.md` §9.4.** |

Revises the prior (pre-this-pass) framing: this is not three unrelated bugs. SBI and IOB are the
*same* algorithmic gap in `mergeHeaderLines` — no path to compose non-overlapping columns supplied
by different lines — with IOB carrying a second, independent problem on top (an extraction-layer
fusion artifact). HSBC's evidence is consistent with that same gap, just reached through a
different door (OCR coordinate noise rather than a printed multi-row layout). All three lose an
entire section's real transactions to zero-or-near-zero usable staged rows, with the only trace
being an internal, evidence-only `HeaderReconstructionFinding` never surfaced to a user.
`HeaderReconstructionFinding` is correctly firing as a *detector* in all three; what's missing is
the *recovery* logic, and — per the taxonomy above — that recovery logic is closer to ONE shared
fix (composing partitioned columns) plus one document-specific extra (IOB's fusion) than to three
unrelated designs.

## A candidate direction — not implemented, not coding yet

Named for SBI because it's the shape now understood most precisely, but per the taxonomy above the
same gap is implicated in IOB and plausibly HSBC too, so a fix here is not a one-document patch.
`mergeHeaderLines` would need a genuinely different composition mode: instead of "seed one column
set from line 1, fold every later cell into the nearest existing column," a version that can also
ask "does this line's cell fill an EMPTY gap between two already-known columns, rather than
refining one of them?" — i.e., recognize that a header can be partitioned column-by-column across
physical lines, not just refined line-by-line.

This is a materially different algorithm shape from the current one, not a threshold or gate
adjustment, and it risks exactly the false-positive class `HEADER_WRAP_MAX_COLUMN_JOIN` and the four
`refinesRatherThanRedefines` gates already exist to prevent (a caption or an unrelated line getting
folded in as if it were a header column). The validation idea worth taking seriously for that gate:
a candidate header should be judged not by whether it looks like a header in isolation, but by
whether the real transaction rows beneath it fit — bucket a sample of the section's own rows under
each candidate composition and prefer the one more of the row's real values resolve against, the
same principle `refinesRatherThanRedefines`'s gate 4 already applies narrowly (whole-cell hint
matches before vs. after a merge). Generalizing that from "does this merge increase nameable
columns" to "do real rows fit this header" is a bigger change than gate 4's current scope, and
would need the same real-corpus-measured-gate discipline every rule in this class already carries —
evaluated against the full committed trace corpus (not just SBI, IOB, or HSBC) before being
trusted.

IOB additionally needs a second, independent fix at the `PdfTextExtractor` layer (splitting a fused
multi-column run back apart), which a `mergeHeaderLines` change alone cannot address. HSBC's
hypothesis (OCR y-jitter defeating row-grouping) would need its own confirmation and, if confirmed,
likely its own fix at the row-formation stage rather than the header-merge stage — composing
columns correctly doesn't help if the columns were already split into the wrong rows before
`wrappedHeaderAt` ever runs.

None of this is implemented. Consistent with the explicit instruction for this phase, the next step
is a header-reconstruction model design document (2E.1.3) that reasons about all three fixes
together as one coherent architecture, not three bank-specific patches — attempted only after that
design is reviewed, not as a side effect of this investigation.
