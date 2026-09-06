# Track A Measurement Pass — Header Vocabulary & PdfTableLocator Reliability

**Doc status:** OPEN / TRACKING. Last reviewed: 2026-08-15. Purpose: measurement evidence
supporting Track A's still-open items (#2 header vocabulary, non-ledger #3) — the evidence trail
for why that work remains blocked on real-corpus acquisition rather than resolved.

**Status:** measurement only. No production code changed. No `HEADER_HINTS`/hint-list edits, no
`PdfTableLocator` edits, no OCR routing edits. Answers exactly two of the five open questions in
`c8-extraction-sufficiency-investigation.md` §6 — item 3 (header-synonym coverage against real
corpus) and item 5 (header-detection reliability, unmeasured until now). Items 1, 2, 4 and 6 are
product decisions and are deliberately untouched.

Every claim below is labelled **MEASURED**, **INFERRED**, or **UNKNOWN**.

---

## 0. The corpus, stated up front

**MEASURED.** The entire real-document corpus in this repository is:

| Artifact | Bank | Pages | Path |
|---|---|---|---|
| `bob-repeated-account-banner.trace` | Bank of Baroda | 3 | `backend/src/test/resources/traces/` |
| `hdfc-txn-date-narration-header.trace` | HDFC | 2 | `backend/src/test/resources/traces/` |
| `hdfc-composite-deposit-schedules.trace` | HDFC (combined savings + FD + RD) | 15 | `backend/src/test/resources/traces/` |
| `separate_debit_credit_balance_sample.pdf` + its golden | SBI-shaped (synthetic, real layout) | 1 | `backend/src/test/resources/pdf/`, `backend/src/test/resources/golden/extractor-separate-debit-credit-balance.golden.txt` |

That is **3 real traces + 1 synthetic-but-real-shaped PDF**, covering **2 banks** (BOB, HDFC) plus
one SBI-shaped synthetic. The prior investigation's "only 3 traces + 1 PDF" note is confirmed
exactly. `PdfFixtureBuilder` adds ~40 synthetic layouts, each written against a real bug — realistic
in shape, but authored, so they are reported separately and never counted as corpus coverage.

**Two structural ceilings on what this corpus can measure at all:**

1. **MEASURED — redaction ceiling.** `PdfTraceRedactor` is an allowlist
   (`backend/src/test/java/com/finora/imports/pdf/fixtures/PdfTraceRedactor.java:100-131`). Any
   header word not in `STRUCTURAL_WORDS` is masked to same-length `Xxxx` filler. That allowlist
   already contains essentially every word in `HEADER_HINTS`. **Header vocabulary measured from
   committed traces is therefore biased toward confirming the existing hint lists** — a word the
   hints do not know is also, usually, a word the redactor did not keep. The class of finding this
   measurement most wants (a real header word nobody anticipated) is the class the corpus is least
   able to show. The file's own doc comment records exactly this incident happening once already
   ("the allowlist had no deposit vocabulary, so three captured traces … removed from the fixtures
   meant to regression-test reading them").
2. **MEASURED — trace-version ceiling.** All three committed traces are `v1`
   (`# finora-pdf-trace v1`, fields `page/x/y/text`, no width). `PdfTableLocator`'s
   `RIGHT_ALIGNED_AMOUNTS` correction is guarded on `t.width() > 0`
   (`PdfTableLocator.java:1548-1560`) and is therefore **unreachable when replaying any committed
   trace**. `TraceWidthFidelityTest`'s class doc states this directly and says the three traces
   "are v1 and stay width-blind until they are recaptured from their source documents." One of the
   misassignments measured in §2 below is a direct consequence.

---

# Finding #2 — Header vocabulary measurement

## Finding

**MEASURED.** For **transaction-ledger tables** the existing hint lists cover the real corpus almost
completely: **15 of 16 observed ledger header cells (94%)** are recognized by
`PdfTableLocator.HEADER_HINTS`, and the single miss is a cell whose true text is destroyed by
redaction. For **non-ledger product tables** (fixed-deposit schedules, recurring-deposit summaries,
installment schedules) coverage collapses to **4 of 14 legible header cells (29%)**, and a further
19 header cells in those tables are masked so their true text is **UNKNOWN**.

**MEASURED.** A second, separate gap is not about vocabulary at all but about *which matcher*:
`PdfTableLocator.matchesAnyHint` matches **per word with edge punctuation stripped**
(`PdfTableLocator.java:1494-1509`), while `CsvParser.hasHeaderMatch` / `CsvParser.firstNonBlank`
match the **whole normalized cell by exact equality** (`CsvParser.java:233-257`). Real headers in
this corpus carry trailing footnote markers (`**`, `***`, `*`, `#`) that
`CsvParser.normalizeHeaderCell` does **not** strip (it strips only `. , ; :`,
`CsvParser.java:226`). Consequence, measured on the corpus: the header cell `Xxxxxxx balance**`
(`hdfc-composite`, p13) is recognized as a balance column by the locator and is **not** recognized
by `hasHeaderMatch(row, "balance", "running balance", "closing balance")`. This matters directly to
the PM's decision, because §2 of the prior investigation proposes building field coverage on
`hasHeaderMatch`.

## Evidence

**MEASURED.** Observed header rows, read directly from the raw trace files by grouping runs by
page and y (±3pt) — i.e. established from the document's own text, not from `PdfTableLocator`.

Transaction-ledger headers (the only three distinct real ledger headers in the corpus):

| Document | Header row (verbatim, post-redaction) | Location |
|---|---|---|
| BOB | `DATE \| NARRATION \| XXX.XX. \| WITHDRAWAL (DR) \| DEPOSIT (CR) \| BALANCE` | p0 y=371.03, p1 y=76.95, p2 y=76.95 |
| HDFC (both traces) | `Txn Date \| Narration \| Withdrawals \| Deposits \| Closing Balance` | hdfc-txn p1 y=314.12; hdfc-composite p1–p8 y=314.12 |
| SBI-shaped golden | `Date \| Description \| Debit \| Credit \| Balance` | `extractor-separate-debit-credit-balance.golden.txt` lines 8–12 |

Non-ledger / product-table headers, same document (`hdfc-composite-deposit-schedules`):

| Table | Header (verbatim) | Location |
|---|---|---|
| Account summary | `Ccy \| Account Type \| Balance \| CR \| DR \| Limit \| Amount \| Sweep xx FD Amt# \| Hold \| Total Withdrawable**` (3 tiers) | p0 y=285.79/291.30/294.79 |
| Statement SUMMARY | `Opening Balance \| Debit Amount \| Credit Amount \| Closing Balance`; `Debit Count \| Credit Count` | p9 y=338.52, y=378.66 |
| FD schedule | `FD Number \| FD \| Xxxxxxxx \| Open/Xxxx \| Xxxx Of \| Xxxx \| Xxxxxxxx Amount \| Nomination` / `CCY \| Xxxxxxxxx \| Xxxxx Date \| Xxxxxxxx \| Amount \| (Xxxxxxx) \| Registered` / `Current FD Amount # \| Xxxxxxxx Available` / `Date ** \| Withdrawable***` (**4 tiers**) | p10 y=184.27/193.27/226.77/235.77 |
| Term-deposit summary | `Account No \| Xxxxxxxxxxx \| Deposit \| Period Of \| XXX \| Deposit \| Xxxxxxxx` / `Amount(Xx) \| Xxxxx Date \| Deposit(Xxxx) \| Xxxxxxxx Date \| Amount(Xx)*` (2 tiers) | p13 y=276.35/285.35 |
| RD summary | `No of Total No of Xxxx \| No of xxxx Xxxx \| Amount \| Xxxxxxxxxxx …` (4 tiers, heavily masked) | p13 y=330.65–352.59 |
| RD installment schedule | `Xxxxxxxxxxx \| Xxxxxxx. \| Xxxxxxxxxxx Xxxx \| Xxxxxxxxxxx Xxxx` / `Number \| Due Date \| Amount Xxxx \| Due \| Status \| Xxxxxxx balance**` (2 tiers) | p13 y=449.49/457.36 |
| Nominee | `FD Number \| Nominee Name` | p11 y=185.84 |

Plus, from BOB: account summary `Xxxxxxxxxxxx Type \| Currency \| Xxxxxx \| Xxxxxxxxxxx` (p0
y=263.22), nominee table `XX.XX. \| ACCOUNT TYPE \| ACCOUNT NUMBER \| NOMINEE NAME(X)` (p2
y=388.90), branch block `XXXX BRANCH ADDRESS \| MICR \| IFSC` (p2 y=430.05).

## Ground truth

**MEASURED.** Ground truth for this finding is the raw trace text itself, grouped by geometry with a
throwaway script — never `PdfTableLocator`'s own output. The one exception is the SBI-shaped golden,
which is a `PdfTextExtractor` snapshot (upstream of the locator), so it is also independent.

**UNKNOWN.** For 19 of the 33 non-ledger header cells the true text is destroyed by redaction. From
position and context these are *inferrable* (`Xxxx Of Xxxxxxxx` at the FD interest-rate position is
almost certainly "Rate Of Interest"; `Xxxxxxxx Amount` is almost certainly "Maturity Amount") — that
is **INFERRED**, and the corpus cannot confirm it. `PdfTraceRedactor`'s own comment names exactly
these two strings as having been lost this way.

## Failure mode

**MEASURED — three distinct formatting phenomena in the real corpus, and how each fares:**

| Phenomenon | Real examples | Handled? |
|---|---|---|
| Case | `DATE`/`Date`, `BALANCE`/`Balance`, `NARRATION`/`Narration` | Yes — `normalizeHeaderCell` lowercases |
| Trailing parenthetical | `WITHDRAWAL (DR)`, `DEPOSIT (CR)`, `Period Of Deposit(Xxxx)`, `Amount(Xx)` | Yes — stripped |
| Trailing `.` | `Withdrawal Amt.`, `Chq./Ref.No.` | Yes — stripped |
| **Trailing footnote marker** | `Total Withdrawable**`, `Xxxxxxx balance**`, `Withdrawable***`, `Xxxxxxxx Amount(Xx)*`, `Current FD Amount #`, `Sweep xx FD Amt#` | **No** — `normalizeHeaderCell` strips only `.,;:`. Locator survives via per-word matching; `hasHeaderMatch`/`firstNonBlank` do **not** |
| Abbreviation | `Txn Date`, `Ccy`, `FD CCY`, `Account No`, `Amt.` | Partially — `txn date` is a hint; `ccy`, `account no` are not |
| Multi-tier / wrapped headers | 4 of 6 non-ledger tables print their header over 2–4 physical lines | Partially — see Finding #3 |
| Multilingual | **None observed** | n/a |

**MEASURED.** Zero non-English header text appears anywhere in the corpus. **UNKNOWN** whether
Hindi/regional-script headers occur in production; this corpus cannot say.

## False positives

**MEASURED.** `matchesAnyHint`'s per-word matching produces semantically wrong but structurally
harmless matches on non-ledger tables:

- `Deposit Xxxxx Date` (FD "Deposit Start Date") matches `TRANSACTION_AMOUNT_HINTS` via the word
  "deposit" — a *date* column recognized as an *amount* column.
- `Period Of Deposit(Xxxx)` (a tenure in months) matches `TRANSACTION_AMOUNT_HINTS` the same way.
- `Xxxxxxx balance**` (an RD outstanding balance) matches `HEADER_HINTS` via "balance".

**INFERRED.** These are latent, not currently harmful, because deposit sections never route through
`TransactionNormalizer`. They become live the moment a field-coverage check asks "does this header
contain a balance/amount synonym" on a non-ledger section — which is exactly what the sufficiency
sketch proposes.

## False negatives

**MEASURED — the complete list of real corpus header strings matched by no hint list at all:**

Ledger tables (1 of 16): `XXX.XX.` — BOB, redacted; positionally the Chq./Ref.No. column; true text
**UNKNOWN**.

Non-ledger tables (legible cells only): `Ccy`, `CR`, `DR`, `Limit`, `Hold`, `Sweep xx FD Amt#`,
`Total Withdrawable**`, `FD Number`, `FD CCY`, `Nomination Registered`, `Withdrawable***`,
`Account No`, `Number`, `Due`, `Status`, `Currency`, `ACCOUNT NUMBER`, `NOMINEE NAME(X)`, `MICR`,
`IFSC`, `XXXX BRANCH ADDRESS`, `Nominee Name`, `Xxxxxxxx Amount(Xx)*` (footnote-marker case).

**MEASURED — `OPENING_BALANCE` specifically.** `"opening balance"` is **not** in
`TransactionNormalizer.BALANCE_HINTS` (`{"balance","running balance","closing balance"}`,
`TransactionNormalizer.java:133`), and in this corpus `Opening Balance` never appears as a *ledger
column* — only as a metadata label (`Opening Balance : 24,818.22`, hdfc-composite p1–p9 y=271.63)
and as a *summary-block* column header (p9 y=338.52). **INFERRED:** a field-coverage check that asks
"is `OPENING_BALANCE` structurally present in the ledger header" will answer *no* for every document
in this corpus, correctly — but for the reason that opening balance is not a per-row column in
Indian bank statements at all, not because extraction failed.

## Coverage

**MEASURED**, per field meaning, over **real ledger headers only (n = 3 distinct documents,
16 header cells)** — small sample, so treat the percentages as exact counts, not as population
estimates:

| MaterialField | Observed real strings | Recognized | Coverage |
|---|---|---|---|
| `TRANSACTION_DATE` | `DATE`, `Txn Date`, `Date` | 3/3 | 100% |
| `TRANSACTION_DESCRIPTION` | `NARRATION`, `Narration`, `Description` | 3/3 | 100% |
| `TRANSACTION_AMOUNT` (debit side) | `WITHDRAWAL (DR)`, `Withdrawals`, `Debit` | 3/3 | 100% |
| `TRANSACTION_AMOUNT` (credit side) | `DEPOSIT (CR)`, `Deposits`, `Credit` | 3/3 | 100% |
| `CLOSING_BALANCE` / running balance | `BALANCE`, `Closing Balance`, `Balance` | 3/3 by `HEADER_HINTS`; **3/3 by the 3-synonym `BALANCE_HINTS` exact-match list too** | 100% |
| `TRANSACTION_DIRECTION` | *no dedicated column in any real ledger* — encoded as `(DR)`/`(CR)` qualifiers or by column pair | 0 columns to match | n/a |
| `OPENING_BALANCE` | *never a ledger column* | n/a | see above |
| `ACCOUNT_NUMBER`, `ACCOUNT_TYPE`, `IFSC`, `BRANCH`, `STATEMENT_PERIOD_*`, `ACCOUNT_HOLDER` | `Account Number`, `Account No`, `Account Type`, `RTGS/NEFT IFSC`, `IFSC`, `Account Branch`, `MICR`, `Statement From`, `Statement as on`, `Customer ID` | **no header-hint list exists for these fields** (they are handled by `PdfMetadataExtractor` regexes on auxiliary text) | 0% by hint list, by design |
| `CREDIT_LIMIT`, `PAYMENT_DUE_DATE` | **zero occurrences in the real corpus** — no credit-card statement is committed | n/a | **UNKNOWN** |

Non-ledger product tables: **4 of 14 legible header cells recognized (29%)**; 19 further cells
masked → **UNKNOWN**.

**INFERRED, low confidence (n = 2 banks).** The prior investigation's §6 item 3 worry —
"header-synonym coverage is currently exactly three strings, tuned to the existing corpus" — is
**not** borne out for the balance field on the ledger path: those three strings cover 3/3 real
ledgers. The real vocabulary risk sits (a) outside the ledger, in product/deposit tables, and
(b) in the footnote-marker normalization gap, not in the synonym count.

## Recommendation

Measurement only; no change proposed and none made. Three things the PM's decisions should weigh:

1. **MEASURED.** If field coverage is built on `CsvParser.hasHeaderMatch`, it will disagree with
   `PdfTableLocator`'s own matcher on real headers carrying footnote markers. Whichever way that is
   resolved, it is a decision, not an oversight to fix silently.
2. **MEASURED.** The corpus cannot answer the vocabulary question for credit cards, deposits, or
   non-English statements, and the redaction allowlist structurally biases it toward confirming the
   current lists. Any "coverage is fine" conclusion drawn from this corpus is circular.
3. **MEASURED.** Re-capturing the three traces at v3 (with widths) is a prerequisite for any further
   measurement of bucketing behaviour — see Finding #3.

---

# Finding #3 — `PdfTableLocator` reliability measurement

## Finding

**MEASURED.** `PdfTableLocator`'s **header-row detection on transaction ledgers is perfect on this
corpus: 12 true positives, 0 false negatives, 0 false positives.** The prior investigation's §6
item 5 worry — that field coverage might silently inherit a header-detection false-negative rate —
is **not supported for ledgers by this corpus**.

**MEASURED.** The failures are elsewhere and are real: **header *assembly* on multi-tier headers**,
and **row-boundary reconstruction on non-ledger tables**. In `hdfc-composite-deposit-schedules`,
3 of the 4 located non-ledger sections have materially wrong content, and 1 genuine table is not
located at all.

## Evidence

**MEASURED.** Produced by a throwaway JUnit harness that (a) called `PdfTableLocator`'s private
`groupIntoRows`, `looksLikeHeaderRow` and `wrappedHeaderAt` by reflection over each trace, and
(b) dumped every section's rows. Ground truth was established first, by reading the raw trace text
grouped by geometry. **The harness was deleted after the run** (it lived at
`backend/src/test/java/com/finora/imports/pdf/ZzScratchMeasurementTest.java`); nothing was
committed, and no production or permanent test file was added or modified.

Detector output, verbatim:

```
##### bob-repeated-account-banner
HDR-plain p0 y=371.03 cells=6 :: [DATE][NARRATION][XXX.XX.][WITHDRAWAL (DR)][DEPOSIT (CR)][BALANCE]
HDR-plain p1 y=76.95  cells=6 :: (same)
HDR-plain p2 y=76.95  cells=6 :: (same)
SECTION 0 rows=58 unionKeys=[DATE, NARRATION, BALANCE, WITHDRAWAL (DR), DEPOSIT (CR), XXX.XX.]

##### hdfc-txn-date-narration-header
HDR-plain p1 y=314.12 cells=5 :: [Txn Date][Narration][Withdrawals][Deposits][Closing Balance]
SECTION 0 rows=5 unionKeys=[Txn Date, Narration, Deposits, Closing Balance, Withdrawals]

##### hdfc-composite-deposit-schedules
HDR-plain   p1..p8 y=314.12 cells=5 :: [Txn Date][Narration][Withdrawals][Deposits][Closing Balance]  (x8)
HDR-WRAPPED p10 y=184.27 cells=8 :: [FD Number][FD][Xxxxxxxx][Open/Xxxx][Xxxx Of][Xxxx][Xxxxxxxx Amount][Nomination]
HDR-WRAPPED p13 y=276.35 cells=7 :: [Account No][Xxxxxxxxxxx][Deposit][Period Of][XXX][Deposit][Xxxxxxxx]
HDR-plain   p13 y=285.35 cells=5 :: [Amount(Xx)][Xxxxx Date][Deposit(Xxxx)][Xxxxxxxx Date][Amount(Xx)*]
HDR-plain   p13 y=457.36 cells=6 :: [Number][Due Date][Amount Xxxx][Due][Status][Xxxxxxx balance**]
SECTION 0 rows=84  ledger
SECTION 1 rows=9   FD schedule
SECTION 2 rows=2   term-deposit summary
SECTION 3 rows=7   RD installment schedule
```

Note the golden files under-report the located header: `GoldenOutputSnapshotTest.java:94-98` derives
`columns` from `rows.get(0).keySet()`, i.e. only the columns the **first data row** happened to
populate. That is why `bob…golden.txt` reads `columns: [DATE, NARRATION, BALANCE]` while the locator
in fact carries all six. **The golden files are not a valid ground truth for header detection**, and
reading them as one would have produced two spurious false negatives.

## Ground truth

**MEASURED.** Established by reading the raw traces, before running the locator:

| Table | Real header rows | Real data rows |
|---|---|---|
| BOB ledger | 3 (repeated per page) | 58 transaction lines + 1 `Opening Balance` marker line |
| hdfc-txn ledger | 1 | 4 transactions (the statement's own SUMMARY block prints 3 debits + 1 credit) |
| hdfc-composite ledger | 8 (repeated per page) | 84 (not independently recounted — see UNKNOWN) |
| hdfc-composite FD schedule (p10) | 1 header spanning **4 physical tiers** | **9** fixed deposits, each on 2 visual lines |
| hdfc-composite term-deposit summary (p13) | 1 header spanning 2 tiers | **1** |
| hdfc-composite RD summary (p13) | 1 header spanning 4 tiers, **no date column** | **1** |
| hdfc-composite RD installment schedule (p13) | 1 header spanning 2 tiers | **6** |
| Non-transaction tables (account summaries ×3, SUMMARY blocks ×2, nominee tables ×2, FD-interest tables ×2) | 9 real headers, **none with a date column** | — |

## Failure mode

**MEASURED, per failure, with root-cause category:**

1. **FD schedule, p10 — geometry/reconstruction.** The real header has 4 tiers (y=184.27, 193.27,
   226.77, 235.77). `WRAPPED_HEADER` merged only tiers 1–2; tiers 3–4 sit 33pt below, past
   `HEADER_WRAP_MAX_GAP = 12.0f` (`PdfTableLocator.java:110`). Result: tier 3
   (`Current FD Amount # | Xxxxxxxx Available`) was **staged as data row 0**, and tier 4
   (`Date ** | Withdrawable***`) leaked into row 1. Row 1 then absorbed **five whole fixed deposits**
   into single cells (`FD Number = "99999999999999 99999999999999 99999999999999 99999999999999
   99999999999999"`). Vocabulary is not implicated: the merge failure is geometric, exactly as this
   capability's own doc comment predicts.
2. **Term-deposit summary, p13 — geometry/reconstruction.** Located correctly (2 tiers merged), but
   `Account No` on row 0 absorbed the entire following table's header text, and row 1 is fabricated
   entirely from the *adjacent RD-summary table's* data line.
3. **RD summary, p13 — missing-header-by-rule (not vocabulary, not geometry).** Its header carries
   no date word, and `looksLikeHeaderRow` requires `hasDate` unconditionally
   (`PdfTableLocator.java:1474-1486`). No vocabulary change could ever locate it. Its single data
   row was instead swallowed by section 2.
4. **RD installment schedule, p13 — geometry (partial header loss) + row-boundary.** Only the lower
   tier (y=457.36) was used as the header; the upper tier (y=449.49) was dropped, so the columns
   read `Number / Due / Status` rather than their qualified forms. Row 5's `Number` cell absorbed
   two page footnotes and row 6 is pure page furniture.
5. **hdfc-txn ledger row 0 — corpus artifact, not a locator defect.** `Deposits = "0.00 25,000.00"`
   with **no** `Withdrawals` value. This is verbatim the defect `RIGHT_ALIGNED_AMOUNTS` was built to
   fix (`PdfTableLocator.java:1548-1560` names these exact coordinates), and it reappears only
   because the trace is v1 and width-blind. **UNKNOWN** whether the live pipeline gets this row
   right; it cannot be determined without recapturing the trace at v3.

## False positives

**MEASURED.**

- **Header-level false positives: 0.** Every row the detector accepted as a header is a genuine
  header row in the document. The `p13 y=285.35` plain-detection is the lower tier of the wrapped
  header immediately above it, and the wrapped path superseded it in the output.
- **Section-level false positives: 0.** No section was invented where no table exists.
- **Row-level false positives: measured, and non-trivial.**

| Section | Rows staged | Real rows | Spurious |
|---|---|---|---|
| hdfc-composite §1 (FD) | 9 | 9 FDs | row 0 = a header tier; row 8 = page furniture; row 1 = 5 FDs collapsed into one |
| hdfc-composite §2 (term deposit) | 2 | 1 | 1 (another table's data row) |
| hdfc-composite §3 (RD installments) | 7 | 6 | 1 (page footnote block) |
| hdfc-txn §0 (ledger) | 5 | 4 | 1 |
| BOB §0 (ledger) | 58 | 57 txns + 1 `Opening Balance` marker | marker row staged as a transaction — the known C-8.1 Finding A pollution, still present |

## False negatives

**MEASURED.**

- **Ledger header rows missed: 0 of 12.** (BOB 3/3, hdfc-txn 1/1, hdfc-composite 8/8.)
- **Ledger header *columns* missed: 0.** All 6 BOB columns and all 5 HDFC columns appear in the
  section's union of row keys.
- **Non-ledger tables missed entirely: 1 of 5** — the p13 RD summary (root cause: no date column,
  rule-by-construction).
- **Partial header loss (tiers dropped): 2 of 4** located non-ledger tables — FD schedule (2 of 4
  tiers lost) and RD installment schedule (1 of 2 tiers lost). Root cause: geometry in both.

## Undetectable-by-construction

**MEASURED — the date-column requirement.** `looksLikeHeaderRow` requires a date word. **Nine real
tables in this corpus have no date column and are therefore permanently invisible to the locator**,
independent of vocabulary or geometry: HDFC account summary (both documents), HDFC statement SUMMARY
block (both documents), HDFC `Debit Count / Credit Count` block, BOB account summary, BOB nominee
table, HDFC p11 nominee table, HDFC p12/p14 FD-interest tables, HDFC p13 RD summary. This is a
design choice, correct for a transaction-table locator and correct as true-negative behaviour for
every one of these except the RD summary, which is a genuine product table.

**MEASURED.** There is **no image-only or scanned document anywhere in the corpus**. The
"OCR would be required" failure category has **zero instances** and cannot be measured here at all.

**MEASURED.** The v1 trace format is itself an undetectable-by-construction category for *this
corpus*: it silently disables `RIGHT_ALIGNED_AMOUNTS` for every replay.

## Coverage

**MEASURED**, on 3 real documents / 20 pages / 12 ledger header instances / 14 real tables:

| Category | Count |
|---|---|
| Ledger header true positives | 12 / 12 (100%) |
| Ledger header false negatives | 0 |
| Header false positives | 0 |
| True negatives (non-transaction headers correctly rejected) | 9 / 9 |
| Non-ledger product tables located | 4 / 5 |
| Non-ledger product tables located **and materially correct** | **1 / 5** (RD installment schedule, modulo 1 spurious row and 1 lost header tier) |
| Row-level false positives | ≥ 6 across 5 sections |
| Undetectable-by-construction (no date column) | 9 tables |
| Undetectable-by-construction (image/OCR) | 0 instances measurable |

**INFERRED, low confidence.** Header detection on ledgers appears robust; the extraction risk in
this pipeline is concentrated in row/tier reconstruction on multi-tier non-ledger layouts, not in
header vocabulary or header detection. With 2 banks and 1 composite document, this is a hypothesis
the corpus is consistent with, not a rate.

**UNKNOWN.** The 84-row count for the hdfc-composite ledger was not independently recounted from the
raw trace, so row-level precision/recall for that section is unmeasured. Header detection for it is
measured (8/8).

## Recommendation

Measurement only; nothing proposed and nothing changed.

**MEASURED — one finding bears directly on a PM decision that was not asked about but is squarely
implicated.** `DocumentClassification.suspectedIncompleteByPageRatio()` is `rows > 0 && rows < pages`.
On this corpus: BOB 58 rows / 3 pages; hdfc-txn 5 rows / 2 pages; hdfc-composite 102 rows / 15 pages.
**It fires zero times** — while §1's and §2's and §3's measured defects (a header tier staged as
data, five deposits collapsed into one row, a whole table lost, a footnote staged as a transaction)
are all present in those same documents. **On the only real corpus this repository has, the
page-ratio heuristic has zero sensitivity to every extraction defect actually present.** That is a
measured fact about this corpus, not a general claim about the heuristic.

---

## Throwaway tooling — disposition

- `backend/src/test/java/com/finora/imports/pdf/ZzScratchMeasurementTest.java` — reflection-based
  dump of `groupIntoRows` / `looksLikeHeaderRow` / `wrappedHeaderAt` plus per-section row dumps.
  **Created, run, and deleted.** Not committed, not a permanent test.
- Two Python scripts (trace row-grouping; hint-list coverage replication) lived in the session
  scratchpad only. Not in the repository.
- No production file, no fixture, no permanent test, no hint list was modified at any point.
