# P-001 — "Wrapped header" row loss: investigation

Status: investigation only. No production code changed. Read-only pass against the real, redacted
traces captured during the real-world validation sweep.

Method: `PdfTableLocator.locateAll` was run against every trace in
`backend/src/test/resources/traces/` via a throwaway JUnit driver (written, measured, deleted).
Column-name resolution was then replayed using verbatim copies of `TransactionNormalizer`'s hint
arrays, so "which column would the normalizer read" is measured, not assumed. Every number below
comes from that run.

---

## 1. The mechanism — the sweep's characterization is wrong

The sweep reported HDFC's header as *"printed across two text bands"* and blamed `WRAPPED_HEADER`
not reaching that shape. **It is not a two-band header.** In all three HDFC savings traces the
entire header is on a single y band, `y=239.85`:

```
0  39.91  239.85  15.99  Date
0 144.18  239.85  34.22  Narration
0 283.52  239.85   0.00  Chq./Xxx.Xx.
0 361.50  239.85  20.00  Value
0 383.50  239.85   8.44  Dt
0 405.32  239.85  41.34  Withdrawal
0 448.66  239.85  17.10  Amt.
0 491.05  239.85  25.78  Deposit
0 518.83  239.85  17.10  Amt.
0 564.28  239.85  25.78  Closing
0 592.07  239.85  27.11  Balance
```
(`backend/src/test/resources/traces/hdfc-savings-ledger-validation.trace:134-143`; byte-identical
in `hdfc-savings-multi-page-ledger.trace` and `hdfc-savings-single-page-ledger.trace`.)

The failure is **horizontal, not vertical**. PDFBox splits each multi-word header cell into two
runs, and `locateAll` makes **one column per run**:

```java
for (PositionedText t : row) {
    headerNames.add(t.text().trim());
    headerAnchors.add(t.x());
    headerEnds.add(t.endX());
}
```
`backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java:537-543`

So a genuine 7-column header becomes 11 columns, two of which are both literally `"Amt."`.
`bucketRow` writes into a `LinkedHashMap` keyed by column *name*
(`PdfTableLocator.java:596`, `result.put(columnName, ...)`), so the withdrawal column and the
deposit column **collapse onto the same map key**. Located output confirms it — the section's rows
carry the keys `[Date, Narration, Chq./Xxx.Xx., Value, Amt., Balance]`.

`looksLikeHeaderRow` is not the problem: it explicitly handles this document. Its own comment
(`PdfTableLocator.java:1478-1486`) says the 11-run/5-match HDFC header is exactly why the density
check (`matches * 3 >= row.size()`) replaced the flat cell cap. The header **is** recognized. What
is broken is what the columns are *called*.

### The downstream damage

`TransactionNormalizer` matches column names by **whole-cell** equality
(`CsvParser.firstNonBlank`, hint arrays at `TransactionNormalizer.java:62-73`). `"amt."`
normalizes to `"amt"`, which is in none of them. So:

* `TRANSACTION_AMOUNT_HINTS` finds nothing.
* `AMOUNT_HINTS` falls through to its last-resort `"balance"` entry
  (`TransactionNormalizer.java:66-70`) → **the transaction's amount becomes its running balance**.
* `CREDIT_HINTS` finds nothing → **every transaction stages as an EXPENSE**, including deposits.

This is byte-for-byte the failure already documented for Kotak's `"Deposit (Cr.)"` at
`TransactionNormalizer.java:41-51` — *"every transaction staged with its AMOUNT showing as the
account's running BALANCE… Not a dropped-row bug… a silently-wrong-data bug, worse in kind."*
The same class of bug reappeared through a different door: last time the column *name* was
unrecognized, this time the column name is *destroyed by run splitting*.

**So P-001 is not primarily row loss on HDFC.** Rows stage. Their amounts and their signs are
wrong.

---

## 2. Where `WRAPPED_HEADER` already works, and why it cannot reach this

`WRAPPED_HEADER` handles a strictly **vertical** shape: two or three separate y bands merged into
one header row.

* Entry point: `wrappedHeaderAt` (`PdfTableLocator.java:1160`), reached from `locateAll:500-505`.
* Bounds: `HEADER_WRAP_MAX_GAP = 12.0f`, `HEADER_WRAP_MAX_LINES = 3`,
  `HEADER_WRAP_MAX_COLUMN_JOIN = 40.0f` (`PdfTableLocator.java:110-112`).
* Merge: `mergeHeaderLines` (`PdfTableLocator.java:1330`) — line 1 seeds columns, later lines join
  by nearest **left edge**, refusing outright if any cell joins no column.
* Working evidence: `backend/src/test/java/com/finora/imports/pdf/WrappedHeaderPdfTableLocatorTest.java`
  (fixture `PdfFixtureBuilder.buildWrappedHeaderDepositScheduleSample`, plus a 3-line positioned-run
  case), and the committed `hdfc-composite-deposit-schedules` trace, on which the capability fires
  and 84 rows locate.

Difference from the HDFC savings shape, stated plainly: the working case has **N y bands to merge
into one row**; the HDFC savings case has **one y band whose runs need merging into fewer cells**.
There is no vertical gap to widen and no second band to find. `HEADER_WRAP_MAX_GAP` is irrelevant
here — no threshold adjustment of any kind reaches this document.

There is currently **no horizontal run-coalescing step anywhere in header construction.** That is
the actual gap.

---

## 3. The CBI variant — confirmed, and it is the more severe of the two

Central Bank of India *is* a genuine two-band header
(`central-bank-savings-ledger-validation.trace:35-40`):

```
0  26.04 273.28   0.00  Xxxx Date          0  93.44 284.92  21.12  Date
0  91.21 273.28  25.57  Value              0 142.05 284.92  23.90  Code
0 138.16 273.28  31.68  Branch             0 187.72 284.92  35.56  Number
0 187.99 273.28  35.02  Cheque
0 241.99 273.28 105.03  Transaction Description
0 374.83 273.28  23.34  Debit
0 438.67 273.28  26.67  Credit
0 511.93 273.28  36.13  Balance
```

Vertical gap 11.64pt — **inside** `HEADER_WRAP_MAX_GAP` (12.0). The merge is refused for a
different reason entirely: `locateAll:500` only asks about a wrap when the line is **not already a
header**:

```java
if (!looksLikeHeaderRow(row)) {
    WrappedHeader wrapped = wrappedHeaderAt(rows, rowIndex);
```
Line 1 alone scores as a header (token-aware matching sees `date` inside `"Xxxx Date"`, plus
`Debit`/`Credit`/`Balance`), so the wrap is never even attempted. That guard is deliberate and
documented as load-bearing (`PdfTableLocator.java:1146-1152`). Line 2 therefore falls through and
is consumed as a data row — confirming the sweep's characterization exactly. Located row 0 is:

```
{Value=Date, Branch=Code, Cheque=Number}
```

**But the consumed junk row is the small half of the damage.** Because line 2 never merges, the
column stays named `"Value"` instead of `"Value Date"`, and the first column stays `"Post Date"`
(redacted to `"Xxxx Date"`). `TransactionNormalizer.DATE_HINTS` matches whole cells only, and
neither `"value"` nor `"post date"`/`"xxxx date"` is in it. Measured: **of 224 located rows on this
document, 0 have a column the normalizer recognizes as a date.** Every one of its 222 transactions
is rejected downstream. This is the real 100% row loss in the corpus — and the locator reports
success.

(Note in passing, not acted on: this also exposes a locator/normalizer contract mismatch — the
locator matches header cells **per word**, `matchesAnyHint` at `PdfTableLocator.java:1494`, while
the normalizer matches **whole cell**. The locator can therefore declare "this table has a date
column" for a name the normalizer cannot find. Logged for the board; out of P-001 scope.)

---

## 4. Quantified impact, measured

Simulated by pre-transforming the run list and re-running `locateAll` + the normalizer's own
column resolution. `dated` = rows whose date column the normalizer can read; `realAmountCol` =
rows with a value in a `TRANSACTION_AMOUNT_HINTS` column; `creditCol` = rows the normalizer would
stage as income; `balanceFallback` = rows whose amount silently resolves to the running balance.

| Trace | | rows | dated | realAmountCol | creditCol | balanceFallback |
|---|---|---|---|---|---|---|
| hdfc-savings-ledger-validation | now | 331 | 243 | **7** | **0** | **230** |
| | fixed | 331 | 243 | 253 | 30 | 0 |
| hdfc-savings-multi-page-ledger | now | 569 | 360 | **3** | **1** | **343** |
| | fixed | 569 | 360 | 374 | 34 | 0 |
| hdfc-savings-single-page-ledger | now | 9 | 8 | **1** | **1** | **7** |
| | fixed | 9 | 8 | 9 | 2 | 0 |
| central-bank-savings-ledger-validation | now | 224 | **0** | 222 | 36 | 0 |
| | fixed | 223 | **222** | 222 | 36 | 0 |

Ground truth cross-check (count of date-shaped runs at the date column's own x in the raw trace):
HDFC ledger 243, HDFC multi-page 360, HDFC single-page 8, CBI 222. These match the `dated` figures,
so the located row counts above are real transactions plus dateless narration fragments, not
inflation.

Headline:
* **CBI: 0 → 222 transactions importable.** Complete recovery of a document that currently imports
  nothing while reporting success.
* **HDFC (3 documents, 611 transactions): amounts stop being the running balance, and 66
  deposits stop staging as expenses.** Row counts do not change; correctness does.

After the fix the HDFC columns read
`[Date, Narration, Chq./Xxx.Xx., Value Dt, Withdrawal Amt., Deposit Amt., Closing Balance]` —
`"withdrawal amt"`, `"deposit amt"` and `"closing balance"` are all already present in
`TransactionNormalizer`'s hint arrays, so **no vocabulary change is needed anywhere.**

---

## 5. Recommended smallest safe fix

Two independent changes. They should ship as two commits with separate evidence — they share a
theme but nothing else.

### Fix A (HDFC) — coalesce adjacent header runs horizontally

New private step in `PdfTableLocator`, applied **at the point `headerNames`/`headerAnchors`/
`headerEnds` are built** (`PdfTableLocator.java:536-543`) — i.e. strictly *after*
`looksLikeHeaderRow(headerRow)` has already accepted the row, and *after* any `wrappedHeaderAt`
merge.

Rule: walk the accepted header row left to right; join run *n* into run *n−1* when
* both runs carry a **measured width** (`width() > 0`), and
* `n.x() − (n−1).endX()` is `>= 0` and `<= HEADER_RUN_JOIN_MAX_GAP` (propose `6.0f`), and
* neither run parses as a date or a number (`CsvParser.parseDate` / `parseNumeric`).

The joined cell takes the left run's `x` as anchor and the right run's `endX` as end — the same
convention `asOneCell` already uses (`PdfTableLocator.java:1462-1472`).

Why 6.0f is safe here rather than fitted: on the HDFC header the intra-cell gaps are
2.00 / 2.00 / 2.00 / 2.01 pt (one space at that font size) and the smallest genuine inter-column
gap is **13.38 pt** (`Dt` → `Withdrawal`). The nearest miss is more than twice the threshold.

**Corpus scan result: the ≤6pt-gap symptom occurs on exactly three documents in the entire
committed corpus — the three HDFC savings traces.** No other trace has any adjacent pair of
measured header runs within 6pt. Replaying every trace with the gated transform applied produced
**byte-identical located output on all 16 non-HDFC traces.**

The placement matters and is the crux of the safety argument — see the risks below.

### Fix B (CBI) — let the wrap merge run on a line that already scores as a header

Relax the `if (!looksLikeHeaderRow(row))` guard at `PdfTableLocator.java:500`, but only under a
much tighter admission rule than the existing one, applied inside `wrappedHeaderAt`/
`mergeHeaderLines`:

1. the lower line has **>= 2** cells;
2. **every** lower cell sits within a tight column-alignment tolerance of a header anchor —
   propose `HEADER_WRAP_STRICT_COLUMN_JOIN = 5.0f`, *not* the existing 40pt
   `HEADER_WRAP_MAX_COLUMN_JOIN` (which exists for centered labels and is far too loose to be a
   discriminator here);
3. the merge **adds no columns** — merged column count must equal line 1's column count;
4. the merged row still passes `looksLikeHeaderRow`;
5. the merged row strictly **increases** the number of cells that match a hint by whole-cell
   comparison (this is what makes the merge an improvement rather than a rename).

Everything already in `wrappedHeaderAt` — `carriesNoDataValue`, `carriesStructuralMeaning`,
`HEADER_WRAP_MAX_GAP` — stays and still applies.

Measured discrimination across the corpus. Every line that already scores as a header, paired with
a dateless line within 12pt below it, and its worst per-cell alignment distance:

| Trace | lower line | cells | worst align | verdict |
|---|---|---|---|---|
| central-bank (all 9 pages) | `[Date, Code, Number]` | 3 | **3.89** | MERGE — correct |
| icici-credit-card | `[Xxxxxx, amount]` | 2 | **4.51** | MERGE — genuine wrap, but a credit-card doc; watch |
| axis-credit-card p0 | `[Card No:, 999…, Name, …]` | 4 | 99.50 | refused |
| axis-credit-card p2 | prose | 14 | 286.00 | refused |
| bob-savings / bob-banner | `[XXX/999…/UPI/…]` | 1 | 36.92 | refused (twice over) |
| hsbc-savings | `[(DR=Debit)]` | 1 | 12.00 | refused |
| hdfc-credit-card | `[(Xxxxxxxxx Xxxx)]` | 1 | 111.67 | refused |
| au-credit-card | `[amount due.]` | 1 | 0.00 | refused on cell count |
| sbi-credit-card | 1-cell fragments | 1 | 22.03 / 113.18 | refused |
| kotak-credit-card | prose blocks | 2–3 | 11.34 / 22.93 / 171.02 | refused |

The alignment tolerance and the cell-count floor are each independently sufficient for most rows,
and jointly sufficient for all of them.

---

## Adversarial risks an implementation must guard

1. **Coalescing must never change *whether* a row is a header.** This is the single biggest risk
   and it is not theoretical — it was measured. `looksLikeHeaderRow`'s density test is
   `matches * 3 >= row.size()` (`PdfTableLocator.java:1489`). Coalescing shrinks `row.size()`
   while leaving `matches` unchanged or higher, so it makes **prose strictly more likely to be
   misread as a header**. Applying the transform to every line (rather than only to an
   already-accepted header) invented a bogus section on `axis-credit-card-statement` out of
   fine-print — `[Txn Date, Type, Cr/Xx, Amount xxxxxxxxxx, of Axis Bank…]` — which is exactly the
   false-positive class `MAX_HEADER_ROW_CELLS` and the density check were built to stop
   (`PdfTableLocator.java:1425-1437`). Gating on "the un-coalesced row already scored as a header"
   removed that regression completely.
2. **Coalescing must not run before `mergeHeaderLines`.** `mergeHeaderLines` seeds its columns from
   line 1's *runs* (`PdfTableLocator.java:1341-1348`) and joins later lines by nearest anchor, so
   changing run granularity changes which columns exist and therefore which joins are made. In the
   pre-locate simulation this shifted section boundaries and column names on
   `sbi-credit-card-statement` (a `WRAPPED_HEADER` document) and cost one recognized amount value.
   Placing the coalescer after header acceptance and after the wrap merge avoids this entirely —
   SBI's own transaction header is two runs 336pt apart and is untouched by any join rule.
3. **Zero-width runs must be excluded.** Older v1/v2 traces and some redacted runs carry
   `width = 0`, so `endX == x` and the horizontal gap is meaningless — it would compute as the raw
   x-delta and could join two genuinely separate columns. Requiring a measured width on *both*
   runs degrades those inputs to exactly today's behaviour, matching the existing precedent set by
   `RIGHT_ALIGNED_AMOUNTS` (`PdfTableLocator.java:583-586`) and `asOneCell`.
4. **Do not merely widen a gap threshold for Fix B.** The gap is already inside the 12pt bound;
   widening it does nothing for CBI and would let real data rows in. Conversely, relaxing the
   `!looksLikeHeaderRow` guard *without* the strict alignment rule absorbs live data: BoB's
   per-page narration line sits 11.49pt below its header, is dateless and numberless, and passes
   every existing check. It is stopped only by column alignment (36.92pt) and cell count (1).
5. **Column-count preservation is the invariant that makes "these two lines are one header" mean
   something.** A genuine wrapped second line renames columns; it never introduces them. Enforcing
   this also preserves the deliberate half-named-heading outcome documented at
   `PdfTableLocator.java:1315-1327`.
6. **Header signature and `REPEATED_HEADER`.** CBI reprints both bands on all 9 pages. `Fix B` must
   produce an identical merged signature per page or the document will split into 9 sections
   instead of recording `REPEATED_HEADER` (`locateAll:526-530`). The simulation confirms it does.
7. **`ICICI credit card` will newly merge** under Fix B (2 cells, 4.51pt). Inspect that document's
   before/after explicitly before shipping; it is a real behaviour change on a document outside
   P-001's scope.
8. **Duplicate header names remain possible in principle.** Fix A removes the observed
   `"Amt."`/`"Amt."` collision, but nothing in `bucketRow` guards against two columns normalizing
   to the same key. A defensive de-duplication (or at minimum a recorded diagnostic when
   `headerNames` contains duplicates) would turn the next instance of this class of bug from
   silent to visible.

---

## 6. Test surface for a future fix pass

Directly affected, must change:
* `hdfc-savings-ledger-validation` (243 txns), `hdfc-savings-multi-page-ledger` (360),
  `hdfc-savings-single-page-ledger` (8) — Fix A
* `central-bank-savings-ledger-validation` (222) — Fix B

Must be proven unchanged (the whole rest of the corpus was measured byte-identical under the gated
Fix A transform):
* `hdfc-composite-deposit-schedules` and `sbi-credit-card-statement` — the two committed
  `WRAPPED_HEADER` documents; highest regression risk for Fix B
* `axis-credit-card-statement` — the prose-as-header false positive; the density-check canary
* `bob-savings-ledger-validation`, `bob-repeated-account-banner`, `hsbc-savings-ledger-validation`,
  `au-credit-card-statement`, `hdfc-credit-card-ledger-validation`, `kotak-credit-card-ledger-validation` —
  all have a dateless line within 12pt of a scoring header and must keep refusing to merge
* `canara-savings-ledger-validation` — cited in `wrappedHeaderAt`'s comment as the layout the
  `!looksLikeHeaderRow` guard protects. Measured: its line below the header sits **24pt** down and
  carries `1,15,238.60`, so it is doubly protected and the guard is **not** in fact what saves it.
  Worth correcting that comment when Fix B lands.
* `icici-credit-card-statement` — will newly merge under Fix B; inspect before/after

Also flagged, related but out of P-001 scope (for the board, not this item):
* Locator matches header cells per word, normalizer matches whole cell — CBI's `"Post Date"` is
  invisible to the normalizer even after the merge; it is rescued only because the merge also
  produces `"Value Date"`.
* `hsbc-savings-ledger-validation` (2 rows), `icici-savings-ledger-validation` (2 rows),
  `kotak-savings-ledger-validation` (2 rows) all locate almost nothing, for reasons unrelated to
  header wrapping.
