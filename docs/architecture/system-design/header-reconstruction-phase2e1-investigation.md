# Header Reconstruction — Phase 2E.1 Investigation

Evidence-only investigation. No production code changed. Scope, per the owner's Phase 2E framing:
for the documents where `HeaderReconstructionFinding` fires and an entire section's real
transactions are lost, what is the actual mechanism, precisely — not the general "wrapped header
collapses" description already on record, but why `mergeHeaderLines` specifically refuses each one.

## Method

Temporary, uncommitted trace instrumentation (`System.err.println` at the two decision points —
`wrapsOnto` and the `mergeHeaderLines` call site inside `wrappedHeaderAt`) run against the real,
unredacted document, then reverted before this doc was written. No code changes ship from this
investigation.

## SBI Credit Card.PDF, Section 1 — root cause fully diagnosed

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

## Statement.pdf (Indian Overseas Bank) — confirmed still live, structurally different, not root-caused this pass

Re-verified: `Detected table columns: [Date), Type]`, 2 raw bucketed rows, both dropped at Stage 4.
Genuinely live, matching the reliability matrix's prior description. But the real header's shape is
different from SBI's, and more complex — it spans up to **three** physical lines with **uneven
per-column wrap depth**, not a clean two-line split:

```
 Date(Value                                                Ref No.     Transaction
                              Particulars                                               Debit(Rs)     Credit(Rs) Balance(Rs)
   Date)                                                 /Cheque No       Type
```

"Date(Value" / "Date)" wraps across two lines (the outer pair); "Ref No." / "/Cheque No" wraps
across the same two lines; "Transaction" / "Type" too — but "Particulars", "Debit(Rs)",
"Credit(Rs)", and "Balance(Rs)" each appear on only one line, vertically positioned *between* the
other columns' two wrapped lines rather than aligned with either one. This is not the same failure
as SBI's (columns split across lines with zero positional overlap) — several of IOB's columns
plausibly DO wrap in the "same column, multiple lines" shape `mergeHeaderLines` already handles, but
apparently not consistently enough across the whole row for the merge to complete, or the header
scan window (`HEADER_WRAP_MAX_LINES`) doesn't accommodate a column that needs 2 lines sitting beside
one that needs only 1 at a different vertical offset. **Not traced to a precise root cause in this
pass** — flagged for a dedicated follow-up rather than guessed at from the text layout alone the way
SBI's was confirmed.

## HSBC DB.pdf — not re-verified this pass (tool limitation, not new evidence)

Neither `CorpusProbe` nor `PdfPipelineDiagnostic` invoke the OCR-routing path production actually
uses (`RoutingTextAcquirer`) — both report 0 positioned runs for this document, which reflects a
diagnostic-tool gap, not the document's real current state. The last real evidence on this document
is the 08-18 reliability-matrix entry, gathered via direct reflection testing against the true OCR
output: `groupIntoRows` is confirmed correct (7 clean physical rows, no cross-row merge), and the
cause is the same `HeaderReconstructionFinding` mechanism — a 3-line wrapped header
(`Date`/`Details`/`Withdrawals`/`Deposits`/`Balance`) where `looksLikeHeaderRow` scores the first
line alone (`Date`+`Balance` only) as a complete 2-column header before the 3-line merge ever gets a
chance. That is a different specific shape again (an EARLY line satisfies the header bar on its own
and short-circuits before wrapping is even attempted) — a third distinct failure mode under the same
`HeaderReconstructionFinding` umbrella. Re-verifying this document properly needs a probe that goes
through the real OCR-routing pipeline, not attempted in this pass.

## Summary: three real documents, three different structural shapes, one shared symptom

| Document | Collapsed header | Real shape of the failure |
|---|---|---|
| SBI CC Section 1 | `[Date, Amount]` | Upper line supplies only ONE column's name; lower line supplies the OTHER TWO, with zero X-position overlap. `mergeHeaderLines` only knows how to refine the SAME column across lines, never to compose DIFFERENT columns from different lines. **Root-caused precisely.** |
| Statement.pdf (IOB) | `[Date), Type]` | Up to 3 physical lines, uneven per-column wrap depth — some columns need 2 lines, others sit on just 1 at an intermediate vertical offset. **Not root-caused this pass.** |
| HSBC DB.pdf (OCR) | 2-column fallback (per 08-18 evidence) | An early line satisfies `looksLikeHeaderRow` alone and is accepted before the 3-line wrap is attempted. **Not re-verified this pass** (diagnostic-tool OCR gap). |

All three lose an entire section's real transactions to zero staged rows, with the only trace being
an internal, evidence-only finding never surfaced to a user. None of the three share a single root
cause — this is not "one bug, three symptoms." `HeaderReconstructionFinding` is correctly firing as
a *detector* for the general class in all three; the actual reconstruction logic needed to *recover*
from each is genuinely different per document.

## A candidate direction for SBI's case specifically — not implemented, not coding yet

Named because it's the one shape precisely understood, not because it's simple. `mergeHeaderLines`
would need a genuinely different composition mode: instead of "seed one column set from line 1, fold
every later cell into the nearest existing column," a version that can also ask "does this
line's cell fill an EMPTY gap between two already-known columns, rather than refining one of them?"
— i.e., recognize that a header can be partitioned column-by-column across physical lines, not just
refined line-by-line. This is a materially different algorithm shape from the current one, not a
threshold or gate adjustment, and risks exactly the false-positive class `HEADER_WRAP_MAX_COLUMN_JOIN`
and the four `refinesRatherThanRedefines` gates already exist to prevent (a caption or an unrelated
line getting folded in as if it were a header column). Any implementation would need the same
real-corpus-measured-gate discipline every other rule in this class already carries — evaluated
against the full committed trace corpus, not just SBI, before being trusted. Not attempted here.
