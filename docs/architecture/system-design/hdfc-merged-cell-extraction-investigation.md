# HDFC merged-cell extraction defect — investigation

**Status:** investigation only. Read-only. No production code changed, nothing proposed for
implementation beyond a scoped recommendation. Uncommitted, untracked.

**Scope:** the `Deposits = "0.00 96,142.00"` merged-cell artefact on the real HDFC traces. Does not
touch Track A, C-8/C-8.3, OCR routing, R2, the evidence engine, credit-card detection, the `"-"`
placeholder issue, the CSV plural-header issue, or the session-resume verification gap.

---

## Headline

**The merged cell is real and reproducible, but it is an artefact of the committed *trace format*,
not of the production extraction path.** `PdfTableLocator` already carries the exact correction for
this exact mechanism (`RIGHT_ALIGNED_AMOUNTS`), added by commit `6d550cd` with this very document's
measurements written into its doc comment. The correction is guarded on a **measured run width**,
and all three committed traces are `# finora-pdf-trace v1`, which has **no width column** — so every
replay of the real corpus reproduces the pre-fix behaviour and cannot reach the fix.

When the same traces are replayed with widths reconstructed from a validated font metric, **14 of 14
merged ledger cells resolve correctly**, including the ₹96,142 salary credit, and the BOB trace is
**byte-for-byte unchanged**.

The open risk is therefore **not** "the extractor is broken in production". It is **"we have no
evidence of what production does on these documents, because our only real evidence is width-blind,
and our regression baseline (`GoldenOutputSnapshotTest`) is frozen against the *defective* output."**

---

## 1. Reproduction — MEASURED

**Yes, reproduced.** Method: a throwaway diagnostic (outside the repo, in the session scratchpad;
see "Method and hygiene" below) that loads each committed trace via
`PdfTrace.load(...)` and runs `new PdfTableLocator().locateAll(runs, ctx)` — the same two calls
`GoldenOutputSnapshotTest.render()` makes
(`backend/src/test/java/com/finora/imports/pdf/GoldenOutputSnapshotTest.java:83-86`).

No PDF reconstruction was needed. The traces are already the extractor's *output* (positioned text
runs), so replaying them exercises the reconstruction stage directly and losslessly for everything
v1 records.

The exact current value, verified rather than assumed:

```
row 75 | Txn Date="30/06/2026"
       | Narration="NEFT Cr-BOFA0XXXXXX-XXXXXX XXXXXXXXX XXXX LTD-... Value Dt 30/06/2026 Ref ..."
       | Deposits="0.00 96,142.00"
       | Closing Balance="96,214.35"
```

— `hdfc-composite-deposit-schedules`, section 0, row 75. There is **no `Withdrawals` key on the
row at all.**

The sibling document has the same shape at row 0:
`hdfc-txn-date-narration-header` → `Deposits="0.00 25,000.00"`, no `Withdrawals` key. That one is
already visible in the committed baseline:
`backend/src/test/resources/golden/hdfc-txn-date-narration-header.golden.txt:5` records
`columns: [Txn Date, Narration, Deposits, Closing Balance]` — the `Withdrawals` column is absent
from row 0's key set. **The defect is already frozen into a committed golden file.**

### Downstream consequence — MEASURED

Run through `TransactionNormalizer.normalize(...)` directly:

| `Deposits` cell | amount | type | `RowKind` |
|---|---|---|---|
| `"0.00 96,142.00"` (as extracted today) | `96214.35` | `EXPENSE` | **`BALANCE_MARKER`** |
| `"96,142.00"` + `Withdrawals="0.00"` (correct) | `96142.00` | `INCOME` | `TRANSACTION` |

`CsvParser.parseNumeric("0.00 96,142.00")` returns **null** — after comma-stripping and whitespace
collapse it becomes `0.0096142.00`, which fails `BigDecimal`
(`backend/src/main/java/com/finora/imports/CsvParser.java:347-388`). With no parseable transactional
amount and a present `Closing Balance`, `TransactionNormalizer` classifies the row `BALANCE_MARKER`
(`backend/src/main/java/com/finora/imports/TransactionNormalizer.java:479-532`), and the staging
loops drop it.

So the current outcome for a real ₹96,142 salary credit is **silently omitted from the ledger**.
Before `ade05ca` (`fix(imports): stop statement marker rows from becoming ledger transactions`) the
same row would have been staged as a **₹96,214.35 EXPENSE** — the balance masquerading as an amount.
`ade05ca` converted a wrong number into a missing row; it did not address the merge. That matches
the framing in the task: the safer failure mode was achieved, the defect was not fixed.

---

## 2. Where the merged value is created — MEASURED

`PdfTableLocator.bucketRow(...)`,
`backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java:1538-1596`.

The concatenation itself is the last line of the loop, **line 1591**:

```java
result.put(columnName, existing == null ? t.text() : existing + " " + t.text());
```

The wrong `columnName` comes from **line 1541**:

```java
int nearest = nearestColumn(t.x(), headerAnchors);
```

`nearestColumn` (`PdfTableLocator.java:~1355`) is a plain nearest-anchor pick over the header row's
own **left** x positions, so the effective boundary between two columns is the **midpoint of their
header anchors**.

The geometry, straight from `backend/src/test/resources/traces/hdfc-composite-deposit-schedules.trace`
(page 8):

| run | x | column it belongs to |
|---|---|---|
| header `Withdrawals` | 295.83 | — |
| header `Deposits` | 385.92 | — |
| `"0.00"` (a withdrawal) | **342.32** | Withdrawals |
| `"96,142.00"` (a deposit) | 407.73 | Deposits |

Midpoint of 295.83 and 385.92 = **340.88**. The withdrawal's left edge sits at 342.32 — **1.44
points past the boundary** — so it buckets into `Deposits`, and line 1591 joins it onto the deposit
already there.

The same trace shows why only *some* rows do this. The Withdrawals column is right-aligned; its
values' left edges slide with digit count:

`"1,500.00"` 326.75 · `"436.00"` 333.43 · `"20.00"` 337.87 · `"0.00"` **342.32**

Only the 4-character values cross 340.88. Every longer value on the same document buckets correctly.

Nothing downstream re-splits it: `splitTrailingAmountIfMissing` (`PdfTableLocator.java:1660-1673`)
only fires for a column normalised to exactly `"amount"`, and
`splitLeadingAmountFromBalanceIfMissing` (`:1620-1657`) only fires on a column normalised to exactly
`"balance"` and only when **no** direction column has a value. On this row `Deposits` has a value, so
both bail.

---

## 3. Which stage owns the defect — MEASURED

**Column reconstruction. Not PDF extraction, not normalization.**

- **Not extraction.** The raw positions are clean and unambiguous. `"0.00"` and `"96,142.00"` are
  two separate runs, 65 points apart, in the trace as captured
  (`hdfc-composite-deposit-schedules.trace`, page 8, y=366.85). Production extraction also records a
  measured width for every run
  (`backend/src/main/java/com/finora/imports/pdf/PdfTextExtractor.java:70-78`), taken from the last
  glyph's advance precisely so right-aligned amount columns can be separated.
- **Not normalization.** `CsvParser.parseNumeric` correctly refuses `"0.00 96,142.00"`. By the time
  the string exists, the information needed to split it (two distinct x positions) is already gone.
- **Column reconstruction**, specifically *left-edge* nearest-anchor bucketing against a
  right-aligned numeric column.

### The fix already exists and is unreachable on our evidence — MEASURED

`PdfTableLocator.java:1542-1568` (`RIGHT_ALIGNED_AMOUNTS`) is precisely this correction. Its doc
comment cites **this document's own numbers** — right edges at 357.89, left edges 333.43/337.87/342.32,
midpoint 340.88, the resulting `"0.00 25,000.00"` merge — so this defect was already diagnosed once,
in code, at commit `6d550cd`.

It is guarded at line 1560:

```java
if (t.width() > 0 && headerEnds != null && CsvParser.parseNumeric(t.text().trim()) != null) {
```

`t.width() > 0` is never true for a v1 trace. `PdfTrace.parse` gives a 4-field row width 0
(`backend/src/test/java/com/finora/imports/pdf/fixtures/PdfTrace.java:108-131`), and
`PdfTableLocator.asOneCell` deliberately refuses to fabricate a header width from zero-width runs
(`:1382-1400`) so a width-blind trace cannot reach the correction on evidence it does not have. All
three committed traces are v1; the current capture format is v3
(`PdfTrace.MAGIC_V3`, `TraceMetadata.CURRENT_TRACE_VERSION`).

### Confirming experiment — MEASURED (model), INFERRED (production behaviour)

Replayed each trace twice: once as committed, once with every run's width recomputed as
Helvetica 8pt.

**The width model is validated against the real document.** `PdfTableLocator`'s comment records that
the real statement's withdrawal values end at **357.89**. Helvetica 8pt gives
`"436.00"` at x=333.43 → end **357.894**, and `"0.00"` at x=342.32 → end **357.888**. The model
reproduces the real measured edge to two decimals on two independent values.

| trace | merged ledger cells, v1 as committed | with widths reconstructed |
|---|---|---|
| `hdfc-txn-date-narration-header` | 1 | **0** |
| `hdfc-composite-deposit-schedules` | 13 | **0** |
| `bob-repeated-account-banner` | 0 | **0** (output identical, zero diff lines) |

The ₹96,142 row becomes:

```
{Txn Date=30/06/2026, Narration=NEFT Cr-... , Withdrawals=0.00, Deposits=96,142.00,
 Closing Balance=96,214.35}
```

and the capability set gains `RIGHT_ALIGNED_AMOUNTS` on both HDFC traces.

**INFERRED, high confidence:** production, which always has real widths, already reads these rows
correctly. **Not MEASURED** — reconstructed widths are a model of the original PDF, not the original
PDF. Only a v3 recapture from the source documents can promote this to MEASURED. This is the same
`UNKNOWN` pass 1 already logged in
`c8-track-a-measurement-pass-2-expanded-corpus.md:536-538`; this investigation narrows it from
"unknown" to "very likely fine, still unproven".

---

## 4. How many rows are affected — MEASURED, with corpus caveats

**Real corpus: 3 traces** (`backend/src/test/resources/traces/`), 2 banks, all v1.

| trace | ledger rows located | merged `Deposits` cells |
|---|---|---|
| `hdfc-composite-deposit-schedules` | 102 | **13** |
| `hdfc-txn-date-narration-header` | 5 | **1** |
| `bob-repeated-account-banner` | 58 | 0 |

The 13 in the composite statement, by value:

- 9 of the form `"0.00 <deposit>"` — a real **credit lost**: `96,142.00`, `1,000.00`, `500.00`,
  `196.00`, `111.00`, `99.00`, `75.00`, `58.00`, `16.00`
- 4 of the form `"<withdrawal> 0.00"` — a real **debit lost**: `2.26` ×3, `6.46`

Every one of the 14 sits on a genuine transaction row. All 14 currently classify `BALANCE_MARKER`
and are dropped.

Separately, the same document's **FD schedule** table (a non-ledger table) carries 6 merged numeric
cells across `FD Number`, `Xxxx Of Xxxxxxxx` and `Xxxxxxxx Amount (Xxxxxxx)`. Those are **not** the
same defect — they are **unchanged** by reconstructed widths, and they belong to the already-known
non-ledger reconstruction problem. Out of scope here.

### Synthetic corpus — MEASURED

Two fixtures in `backend/src/test/java/com/finora/imports/analysis/Pass2CorpusFixtures.java` touch
this area, and they are **not** the same thing:

- `mergedAmountSingleRun()` (`:606-628`) constructs the cell as **one pre-merged text run** by
  design. Its own comment asserts "the run really is one run in the real document" — **that assertion
  is contradicted by the trace**, where they are two runs 65 points apart. This fixture reproduces
  the *string*, not the *mechanism*, and no width-based correction can or should fix it.
- `rightAlignedAmountCollision()` (`:635+`) reproduces the **actual** mechanism with real measured
  widths, and `RIGHT_ALIGNED_AMOUNTS` corrects it 4/4
  (`c8-track-a-measurement-pass-2-expanded-corpus.md`, coverage table).

**UNKNOWN:** the real-corpus rate. 3 documents, 2 banks. 14 rows out of ~165 located ledger rows is
roughly 8%, but that number is a property of one customer's HDFC statement and its digit lengths, not
of HDFC, and certainly not of Indian bank statements. It says nothing about layouts where the two
amount columns sit further apart (BOB: 0 occurrences).

---

## 5. Smallest safe fix — recommendation

**There is no production code change to make.** The narrowest correct action is a **test-fixture and
baseline** change:

1. **Recapture the two HDFC traces at v3** via `./scripts/trace-capture.sh <name> <path.pdf>`, so
   they carry widths. This is the *only* action that turns "production probably reads these rows
   correctly" into evidence. It requires the original documents, which live with the PM, not on the
   build machine — this is the same blocker `TraceCorpusHealthTest.staleTracesAreNamedSo...` already
   reports for all three traces.
2. **Regenerate `GoldenOutputSnapshotTest`'s baselines** with `-Dfinora.golden.regenerate=true` and
   read the diff. The expected diff is exactly the 14 rows above gaining a `Withdrawals` key and a
   clean `Deposits` value, plus `RIGHT_ALIGNED_AMOUNTS` in the capability list. Any *other* change is
   a finding.
3. **Correct `mergedAmountSingleRun`'s doc comment** (`Pass2CorpusFixtures.java:604-608`). It asserts
   as fact something the trace disproves, and it is the reason this defect has twice been recorded as
   "unrecoverable at extraction" when the real mechanism is recoverable.

**If recapture is impossible** (originals unavailable), the fallback is *not* to loosen
`RIGHT_ALIGNED_AMOUNTS`' `width > 0` guard — that guard is correct and is what keeps a width-blind
trace from getting a fabricated correction. The fallback is a **diagnostic**, not a parser change:
have `PdfTableLocator.bucketRow` record a capability/diagnostic (e.g. `MERGED_AMOUNT_CELL`) when it
appends a second parseable numeric run onto a cell in an amount column, so the condition is
*observable* in `DocumentContext` and in import diagnostics rather than surfacing as a generic
dropped row. That is ~5 lines at `PdfTableLocator.java:1591`, changes no output value, and cannot
regress any layout.

**Explicitly not recommended:** a normalization-level "split a merged-looking numeric cell" rule.
See §6.

---

## 6. Regression risk

### For the recommended action (recapture + regenerate): MEASURED, low

The three real traces were replayed with widths and diffed against the width-blind output:

| trace | diff |
|---|---|
| `bob-repeated-account-banner` | **0 lines.** Identical output, identical capability set. |
| `hdfc-txn-date-narration-header` | 2 lines — the one merged row, plus `RIGHT_ALIGNED_AMOUNTS` added |
| `hdfc-composite-deposit-schedules` | 38 lines — the 13 merged rows, plus the capability line |

Every changed line is a correction. Nothing else moved.

BOB is the strongest negative control available and it is worth naming why: it is a
**`Dr`/`Cr`-suffixed, narrow-column** layout (`BALANCE="38458.16 Cr"`,
`WITHDRAWAL (DR)` / `DEPOSIT (CR)` adjacent), i.e. exactly the "compound cell value" and
"legitimately close-together amount and balance" shapes a naive fix would be expected to break.
`CsvParser.parseNumeric` **does** accept `"38458.16 Cr"` (`CsvParser.java:352-360`), so those cells
*are* eligible for the right-edge redirect — and it still changed nothing, because
`RIGHT_ALIGNED_AMOUNTS` only relocates a run when the right-edge answer *differs* from the left-edge
answer **and** the target is an amount column (`PdfTableLocator.java:1561-1565`).

### For a hypothetical merged-cell *splitting* rule: HIGH — do not do this

Three concrete shapes already in this codebase that such a rule would misfire on:

1. **`Pass2CorpusFixtures.mergedAmountSingleRun()`** row 3 —
   `Closing Balance="20.00 0.00 95686.00"`. A three-way merge with no geometry to recover from. Any
   splitter has to *guess* which number is the withdrawal and which the balance. Guessing wrong here
   writes a wrong amount into a user's ledger, which is strictly worse than today's dropped row.
2. **`buildSingularDepositWithdrawalColumnsSample`** (cited in
   `c9-closing-balance-shadow-corpus-measurement.md:313`) — the `"1.00 24352.97"` merged-balance cell
   that `splitLeadingAmountFromBalanceIfMissing` (`PdfTableLocator.java:1620-1657`) *already*
   recovers correctly. A second, more general splitter would race this one, and the existing rule's
   careful guard ("only when every direction column is genuinely empty") is exactly the guard a
   general rule cannot keep.
3. **BOB's `BALANCE="38458.16 Cr"` and the DR_CR_SUFFIX family** — a legitimately compound cell. A
   splitter keyed on "cell contains a space and more than one number-ish token" has to special-case
   every suffix vocabulary in `CsvParser`, and gets it wrong the first time a bank prints
   `"1,500.00 CR"` in an amount column. Also `splitTrailingAmountIfMissing`'s real motivating case
   (`PdfTableLocator.java:1660-1673`): `"FUEL SURCHARGE    10.00 Dr"` arriving as one run, where the
   description and amount are legitimately in one cell and the correct split is already handled by a
   narrower rule.

The general principle this repo has already converged on, and which the evidence supports: **split
by geometry, at bucketing time, where the two x positions still exist — never by string surgery
afterwards.** `RIGHT_ALIGNED_AMOUNTS` is that fix. It is already written. It just needs evidence that
can reach it.

---

## Method and hygiene

Three throwaway Java diagnostics were compiled and run **entirely inside the session scratchpad**
(`.../scratchpad/Repro.java`, `Repro3.java`, `Repro5.java`), against the repo's already-compiled
`target/classes` + `target/test-classes` and its Maven dependency classpath. They only call public
APIs (`PdfTrace.load`, `PdfTableLocator.locateAll`, `TransactionNormalizer.normalize`,
`CsvParser.parseNumeric`) and print. **No file in the repository was created, edited, or deleted by
this investigation except this document.** `git status` is otherwise unchanged.

This was necessary because no existing test prints per-row bucketed output for a real trace, and
because the width-reconstruction experiment (§3) has no in-repo equivalent.

---

## Recommendation to the PM

**Do not write a parser fix.** The parser fix exists.

**Do get the two HDFC originals recaptured at v3.** It is the only action that (a) proves what
production actually does on the document that motivated `RIGHT_ALIGNED_AMOUNTS` in the first place,
(b) unfreezes a golden baseline that currently enshrines the defective output as expected, and (c)
costs one script invocation plus one reviewed diff. Consistent with this repo's measure-before-fixing
discipline, everything else here stays **INFERRED** until that recapture happens.
