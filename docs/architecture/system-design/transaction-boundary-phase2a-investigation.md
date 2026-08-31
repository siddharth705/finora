# Transaction Boundary — Phase 2A Investigation

Evidence-only investigation, per D-29 (`docs/project-management/plans/project-plan-v1.0.md`, §11 and
§4d). No production code touched. Scope: does `PdfTableLocator` correctly stop at the last real
transaction on a credit-card statement, or can bank-generated content after it (rewards summaries,
fee schedules, interest/MAD illustration tables, T&Cs) be mistaken for more transactions?

## Verdict up front

**No currently-live miscount on the real corpus — but the reason it doesn't miscount is an accident
of row-merging, not a structural "the table has ended" decision, and it is more fragile than it
looks.** On 4 of the 6 real credit-card statements checked (Axis, Kotak, ICICI, HDFC), bank-generated
trailing content — rewards summaries, fee/MITC schedules, cardholder-detail footers, and (on Axis)
a Minimum-Amount-Due illustration table structurally shaped like a real transaction table (date,
description, Dr/Cr, amount) — gets bucketed by `PdfTableLocator` as candidate rows in the *same*
section as the real transactions. `PdfTableLocator` never recognizes any of this as "past the end of
the table" and never closes the section for it. These rows only fail to become phantom transactions
because `TransactionNormalizer`'s Stage-4 date-format check (`CsvParser.parseDate`, called from
`TransactionNormalizer.java:302`) rejects them — and it only rejects them because the row-continuation
merge logic (`PdfTableLocator.groupIntoRows`/the trailing-continuation mechanism around
`MAX_TRAILING_CONTINUATION_ROWS`, `PdfTableLocator.java:309`) happens to fuse multiple originally
separate lines of boilerplate into one row whose date cell becomes an unparseable run-on paragraph.
Reliable in practice on this corpus; not reliable by design.

Only one document (AU Credit card.pdf) has a real structural exclusion — `ILLUSTRATIVE_EXAMPLE_MARKER`
(`PdfTableLocator.java:282-284`) — and it exists only because that document's exact phrasing was
hand-evidenced into the pattern already.

## Method

Per D-29/§4d: render each real credit-card statement, compare the pipeline's actual output against
ground truth, and look for the structural signal (if any) that currently separates real transactions
from trailing noise — without changing any row-grouping code first.

1. `pdftotext -layout` against each of the 7 real credit-card statements
   (`~/Downloads/Bank statement/Credit cards/`) to read the raw tail content by eye.
2. `scripts/corpus-run.py` (`com.finora.imports.analysis.CorpusProbe`) against the same 6 (of 7 —
   `SBI Credit Card.PDF`'s uppercase extension needs a separate invocation) to get the pipeline's
   actual staged row count per document, cross-checked against `~/Downloads/Bank statement/ground-truth/*.json`
   where a ground-truth file exists.
3. `com.finora.imports.pdf.PdfPipelineDiagnostic` (`-DpdfPath=...`) against each, to see the gap
   between **raw bucketed rows** (`LocatedSection.rows()`, before `TransactionNormalizer`) and
   **staged rows** (after Stage 4), and read the `DROPPED` reason for every row that didn't survive.

## Findings, per document

| Document | Raw bucketed | Staged | Ground truth | Gap explained by |
|---|---|---|---|---|
| AU Credit card.pdf | 4 | 4 | 4 | Nothing to explain — 2 illustrative tables (interest calc, late-payment calc) excluded **before** bucketing by `ILLUSTRATIVE_EXAMPLE_MARKER` |
| Axis credit.pdf | 111 | 108 | 108 (human-reviewed, Dr sum matches printed total exactly) | 3 raw rows dropped at Stage 4. One of the 3 fuses the MAD illustration table's example line together with unrelated cheque-payment instructions, GST notices, and grievance-officer contact text into a single row whose date cell is a run-on paragraph — fails `CsvParser.parseDate`, not excluded structurally |
| Kotak CC.pdf | 23 | 21 | 21 | 2 raw rows dropped at Stage 4, both MITC/fees-and-charges legal-schedule content fused into an unparseable date cell |
| ICICI CC.pdf | 6 | 3 | 3 | 3 raw rows dropped at Stage 4: a rewards-points summary row and a registered-office-address/safe-banking-tips block, both fused into unparseable date cells |
| HDFC credit.pdf | 4 | 2 | 2 | 2 raw rows dropped at Stage 4: a cardholder-identity line and a NeuCoins rewards-program summary block, both fused into unparseable date cells |
| HSBC CC.pdf | 0 (LAYOUT_UNSUPPORTED) | 0 | no ground-truth file | Separate, already-tracked acquisition-layer gap (matches the reliability matrix's `PRE_HEADER_ACTIVITY_CANDIDATE` note on this document) — not a 2B/2C finding, not re-diagnosed here |
| SBI Credit Card.PDF | not measured | 30 (Section 0) + 0 (Section 1) | no ground-truth file; the 2026-08-18 reliability matrix narrative claims 32 for Section 0 | Section 1's 0 rows is the already-tracked `TRANSACTION_HEADER_RECONSTRUCTION_UNCERTAIN` gap. The 30-vs-32 gap on Section 0 is **unreconciled** — no ground-truth JSON exists to adjudicate it, and it wasn't diagnosed further here since it looks like a 2D/2E (row completeness/correctness) question, not a 2B/2C one |

## Why "it currently works" is not the same as "the boundary is understood"

Two things make the current safety net fragile, found directly from the `DROPPED` evidence, not
inferred:

1. **The save happens one stage later than the risk.** `PdfTableLocator` (Stage 2, table location) is
   where D-29 asked the question — "does the table know it has ended?" — and the answer on 4 of 6
   documents is no: it keeps the section open and buckets the trailing content as rows regardless of
   content. The actual rejection happens in `TransactionNormalizer` (Stage 4, semantic validation),
   a component whose job is "is this row shaped like a transaction," not "is this document region
   still the transaction table." A row can pass Stage 4's date check by accident just as easily as it
   can fail it.
2. **The failure mode is row-merging, not row-recognition.** None of the individual MAD-table lines on
   Axis (`25th Sep | Purchase | Db | 2% | 5000`, and seven more like it) survive as 111 - 108 = 3
   separate near-miss rows — they don't appear as 8 individually-rejected rows at all. The
   continuation-merge logic that exists to glue a genuinely wrapped transaction description onto its
   date row (`MAX_TRAILING_CONTINUATION_ROWS`, `BLOCK_PITCH_TOLERANCE`) is, on this trailing content,
   instead fusing multiple *unrelated* boilerplate lines into a handful of oversized rows. The date
   cell only fails to parse because so much unrelated text got glued into it. A differently-shaped
   trailing block — one where the merge logic doesn't happen to fuse a clean date-shaped token
   together with enough surrounding prose to break it — would not get this accidental protection.
   AU's own illustration tables are the proof this isn't hypothetical: read in isolation, rows like
   "Purchase on 05th Dec 2025 — ₹10,000.00" have a perfectly parseable date and amount. They are safe
   today only because `ILLUSTRATIVE_EXAMPLE_MARKER` catches AU's specific phrasing *before* bucketing
   — if that marker didn't exist, nothing else in the pipeline is positioned to say those rows aren't
   real transactions.

## A candidate structural signal, not yet evaluated

`CREDIT_CARD_SUMMARY_SIGNAL` (recorded at `PdfPreviewGenerator.java:591`) already reliably locates a
credit-card statement's "Total Amount Due" / "Minimum Amount Due" summary line — it's the same
evidence Phase 1B (`CreditCardSummaryEvidence.totalAmountDue`) already surfaces to the user today.
Every one of the 6 real CC statements with a Stage-4 near-miss has its summary line appear at or near
the true end of the real transaction table, before the trailing boilerplate begins. This is a
promising real-corpus-evidenced candidate for a genuine `PdfTableLocator`-level closing signal — but
it has not been evaluated against the corpus the way D-29 requires before it's treated as the answer:
whether it appears reliably enough, whether any real document has transactions genuinely *after* it
(a fee posted the same statement, for instance), and whether it exists on the two documents this
investigation didn't fully diagnose (HSBC CC, SBI CC) are all open questions, not conclusions.

## Explicitly not done here, per D-29's own instruction

No change to `groupIntoRows`, `PdfTableLocator`'s section-closing logic, or `TransactionNormalizer`.
No synthetic fixtures built yet — D-29 asks for those only after a real-corpus failure shape is
confirmed, and what's confirmed so far is a near-miss pattern, not a live miscount. Savings-account
statements (2F, multi-page continuation) were spot-checked on 3 documents (HDFC sav, canara,
Union Bank) with no equivalent trailing-noise shape found — canara's tail is caught cleanly by the
existing `STATEMENT_CLOSING_MARKER`, the other two simply end at the real transaction table with nothing
transaction-shaped following it. Not exhaustively swept; credit-card statements are where this
investigation found real signal, consistent with D-29's own framing of 2B as the highest-value item.

## Open threads for a decision, not resolved here

1. Is the near-miss pattern (Axis/Kotak/ICICI/HDFC) worth closing now with a real structural signal,
   given it hasn't caused a live miscount yet on this corpus?
2. If so, is `CREDIT_CARD_SUMMARY_SIGNAL`'s summary-line position the right anchor, or does it need
   more real-corpus evidence first (per the candidate-signal section above)?
3. The SBI Credit Card.PDF Section-0 discrepancy (30 vs. the reliability matrix's narrative claim of
   32) has no ground truth to adjudicate it — worth a ground-truth JSON for this document before
   trusting either number?
4. HSBC CC.pdf's `LAYOUT_UNSUPPORTED`/0-row result doesn't match the reliability matrix's
   PASS/PASS/PASS/FLAGGED description for the same file — worth reconciling, though it looks like a
   Stage-1 acquisition question, not a 2B/2C one.
