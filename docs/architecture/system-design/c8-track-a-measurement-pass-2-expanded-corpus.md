# Track A Measurement Pass 2 — Expanded Corpus, Header Vocabulary & PdfTableLocator Reliability

**Doc status:** OPEN / TRACKING. Last reviewed: 2026-08-15. Purpose: same as
`c8-track-a-measurement-pass-header-vocabulary-and-locator-reliability.md`, re-run against an
expanded corpus. Note: `Pass2MeasurementHarness.java`, referenced below, has since been deleted
(2026-08-15) — it was the throwaway harness that produced these numbers; the measurements
themselves are preserved here.

**Status:** measurement only. No production code changed. No `HEADER_HINTS`/hint-list edits, no
`PdfTableLocator` edits, no OCR routing edits, no normalization added for the footnote-marker gap
(explicitly deferred by the PM). Re-runs items #2 and #3 from
`c8-track-a-measurement-pass-header-vocabulary-and-locator-reliability.md` against a corpus expanded
across the eight categories the PM required, using that pass's methodology unchanged.

Every claim is labelled **MEASURED**, **INFERRED**, or **UNKNOWN**.

---

## 0. The corpus after expansion, stated up front

### 0.1 What is REAL — unchanged from pass 1

**MEASURED.** The real-document corpus in this repository is **exactly what it was in pass 1**:

| Artifact | Bank | Pages | Path |
|---|---|---|---|
| `bob-repeated-account-banner.trace` | Bank of Baroda | 3 | `backend/src/test/resources/traces/` |
| `hdfc-txn-date-narration-header.trace` | HDFC | 2 | `backend/src/test/resources/traces/` |
| `hdfc-composite-deposit-schedules.trace` | HDFC (savings + FD + RD) | 15 | `backend/src/test/resources/traces/` |

**Three real traces, two real banks, all `v1` (width-blind).** No new real document was obtained or
could be obtained in this environment. **Everything added in this pass is synthetic.** That single
fact bounds every conclusion below and is repeated wherever a number depends on it.

### 0.2 What was ADDED — 17 realistic-synthetic fixtures

**MEASURED.** `backend/src/test/java/com/finora/imports/analysis/Pass2CorpusFixtures.java` (new,
uncommitted) builds 17 fixtures over 16 distinct documents. Ground truth for each is declared in the
construction code (`Pass2CorpusFixtures.Spec`) — the locator is never asked what it thinks is there.

| # | Category the PM required | Fixtures built | Fidelity |
|---|---|---|---|
| 1 | More banks/layouts beyond BOB + HDFC | `axis-savings-ledger`, `icici-serial-ledger`, `sbi-ref-ledger`, `union-bank-single-amount-ledger`, `canara-reference-ledger`, `hsbc-deposits-first-ledger` | **realistic-synthetic** |
| 2 | Header footnote-marker variants | `footnote-marked-ledger` (attached markers), `detached-footnote-marked-ledger` (detached markers) | **realistic-synthetic** |
| 3 | More non-ledger sections | `non-ledger-sections-legible` (7 tables: statement summary, counts, FD schedule, nominee, RD summary, instalment schedule, TDS/interest summary) | **realistic-synthetic, strings INFERRED** |
| 4 | Genuinely scanned/image-only | `scanned-axis-ledger` (rasterised at 150 DPI via the repo's existing `ScannedPdfFixture`) | **genuine for "no text layer", nothing more** |
| 5 | Extraction succeeds, load-bearing field missing | `missing-amount-columns-ledger` (no debit/credit columns at all), `yearless-date-ledger` (dates without a year) | **realistic-synthetic** |
| 6 | Headers visible but not extractable | `image-only-header-ledger` (header band rasterised, data rows text), `four-tier-wide-gap-header` (tiers 20pt apart, past `HEADER_WRAP_MAX_GAP = 12`) | **genuine for the stated mechanism** |
| 7 | Merged amount columns | `merged-amount-single-run` (two/three amounts as one PDFBox run), `right-aligned-amount-collision` (short value crosses the anchor midpoint) | **realistic-synthetic** |
| 8 | Dormant / zero-activity | `dormant-account-statement` | **realistic-synthetic** |

**MEASURED — what "realistic-synthetic" means here, precisely.** Column vocabulary and layout
conventions are copied from bank layouts already named in this repository's own production comments
(Axis, ICICI, SBI, Union Bank of India, Canara, Kotak, HSBC, PNB, Bandhan). The geometry is authored.
So:

- A vocabulary result from these fixtures can confirm whether a hint list covers a string **somebody
  already wrote down in this repo**. It **cannot discover a real-world string nobody has recorded.**
  The pass-1 redaction ceiling has been replaced by an author ceiling, not removed.
- A geometry result from these fixtures **proves nothing about real documents.** Where a fixture
  reproduces a geometric failure it does so because it was constructed to. That is a demonstration
  of a mechanism, never a rate.

### 0.3 Structural ceilings — what changed and what did not

| Pass-1 ceiling | Status after expansion |
|---|---|
| **Redaction allowlist biases vocabulary toward confirming the hints** | **Removed for the new fixtures** (nothing is redacted) and **replaced by an author bias** — I chose the strings, from this repo's own comments. Still circular, differently. |
| **v1 traces are width-blind, so `RIGHT_ALIGNED_AMOUNTS` is unreachable** | **Lifted for the new fixtures.** PDFBox-rendered fixtures carry real measured widths. The capability is measured for the first time (Finding #3, §7). |
| **Zero scanned documents** | **Lifted, trivially.** One image-only document now exists. It tests one bit: extraction yields nothing. |
| **Only 2 banks** | **NOT lifted.** Still 2 real banks. 8 additional layout *conventions* are now represented, all synthetic. |

### 0.4 Method

**MEASURED.** Identical to pass 1. A throwaway JUnit harness
(`backend/src/test/java/com/finora/imports/analysis/Pass2MeasurementHarness.java`, new, uncommitted,
asserts nothing) that:

1. renders each fixture, extracts with `PdfTextExtractor`, and checks every **declared** header cell
   is actually recoverable from the text layer (extraction-fidelity control — this caught four
   fixture-authoring column collisions that would otherwise have been reported as findings);
2. scores each declared header cell with `PdfTableLocator.matchesAnyHint` **by reflection on the
   production method**, against the production `HEADER_HINTS` and `DATE_HINTS` fields — not a
   re-implementation;
3. scores the same cell with the whole-cell exact-equality rule `CsvParser.hasHeaderMatch` /
   `firstNonBlank` use, against `TransactionNormalizer.recognizedColumnNames()`;
4. calls `groupIntoRows` and `looksLikeHeaderRow` by reflection to count header detections
   independently of section assembly, then runs `locateAll` with a `DocumentContext` to record
   capability activations and diagnostics;
5. grades all of it against `Spec`, never against locator output.

---

# Finding #2 — Header vocabulary measurement (expanded corpus)

## Finding

**MEASURED.** Over **73 unique header-cell strings** across the 17 new fixtures (132 cell
measurements), `PdfTableLocator`'s matcher recognizes **46 (63%)**. Split by table kind, the picture
pass 1 drew is confirmed and sharpened:

| Table kind | Unique header cells | Recognized by locator | Recognized by CSV exact-match |
|---|---|---|---|
| **Transaction ledger** | 41 | **32 (78%)** | 25 (61%) |
| **Non-ledger (FD/RD/loan/summary/nominee/TDS)** | 32 | **14 (44%)** | 2 (6%) |

**MEASURED — the pass-1 headline number does not survive contact with more layouts.** Pass 1 measured
15/16 (94%) ledger header cells recognized, over 3 real ledgers. Over 13 ledger layouts the figure is
**32/41 (78%)**. The drop is not a contradiction: pass 1's ledgers were 5–6 columns of pure
date/narration/amount/balance vocabulary, and the added layouts carry a **column family pass 1's
corpus barely contained** — serial numbers and instrument references.

**MEASURED — the newly visible blind spot is one coherent family, not scattered misses.** All nine
unrecognized ledger cells are serial-number or reference-number columns:

`S No.` · `S.No` · `Chq No` · `Cheque Number` · `Ref No./Cheque No.` · `Reference / Cheque No.` ·
`Transaction Id` · `Dr/Cr` · `Init.Br`

**MEASURED — and the two matchers disagree in BOTH directions, which pass 1 did not observe.** Pass 1
found only locator-yes / CSV-no. Four of those nine are **CSV-yes / locator-no**: `Chq No`,
`Transaction Id`, `Dr/Cr`, `Reference / Cheque No.` are in
`TransactionNormalizer.recognizedColumnNames()` (via `REFERENCE_HINTS` and the Dr/Cr type hints) and
are in **neither** `HEADER_HINTS` nor `DATE_HINTS`. **INFERRED:** the two vocabularies were grown
against different documents and were never reconciled; whichever a coverage check is built on, it
will be blind to a set the other one knows.

**MEASURED — the footnote-marker gap is confirmed, widened, and has one exception nobody had noticed.**
Nine marker-bearing headings were measured. All nine are recognized by the locator. **Eight of nine
are rejected by the CSV exact-match rule.** The one that is not — `Description (a)` — passes because
`CsvParser.normalizeHeaderCell` strips a **trailing parenthetical** (`CsvParser.java:211-215`). So the gap
is precisely: *non-parenthesised* trailing markers. Both attached (`Closing Balance**`) and detached
(`Balance ***`) forms fail; a parenthesised marker already works by accident of an unrelated rule.

## Evidence

**MEASURED.** Harness output, verbatim (`norm=` is `CsvParser.normalizeHeaderCell`'s result).

Category-2 fixtures — every marker shape measured:

```
footnote-marked-ledger
  "Txn Date#"          norm="txn date#"          locator=true  csvExact=false
  "Narration^"         norm="narration^"         locator=true  csvExact=false
  "Withdrawal Amt.*"   norm="withdrawal amt.*"   locator=true  csvExact=false
  "Deposit Amt.*"      norm="deposit amt.*"      locator=true  csvExact=false
  "Closing Balance**"  norm="closing balance**"  locator=true  csvExact=false
detached-footnote-marked-ledger
  "Date 1)"            norm="date 1)"            locator=true  csvExact=false
  "Description (a)"    norm="description"        locator=true  csvExact=TRUE   <-- parenthetical stripped
  "Debit **"           norm="debit **"           locator=true  csvExact=false
  "Credit **"          norm="credit **"          locator=true  csvExact=false
  "Balance ***"        norm="balance ***"        locator=true  csvExact=false
```

Category-1 fixtures — the reference/serial family, and the two-directional disagreement:

```
axis-savings-ledger    "Chq No"                 locator=false csvExact=true
axis-savings-ledger    "Init.Br"                locator=false csvExact=false
icici-serial-ledger    "S No."                  locator=false csvExact=false
icici-serial-ledger    "Cheque Number"          locator=false csvExact=false
icici-serial-ledger    "Transaction Remarks"    locator=true  csvExact=false
sbi-ref-ledger         "Ref No./Cheque No."     locator=false csvExact=false
union-bank-...-ledger  "S.No"                   locator=false csvExact=false
union-bank-...-ledger  "Transaction Id"         locator=false csvExact=true
union-bank-...-ledger  "Dr/Cr"                  locator=false csvExact=true
canara-reference-...   "Reference / Cheque No." locator=false csvExact=true
```

Category-3 fixture — non-ledger headings written out in full (the cells pass 1 could only see as
`Xxxxxxxx`):

```
"Opening Balance"          locator=true  csvExact=false
"Debit Count"              locator=true  csvExact=false
"Credit Count"             locator=true  csvExact=false
"FD Number"                locator=false csvExact=false
"Currency Code"            locator=false csvExact=false
"Deposit Principal"        locator=true  csvExact=false
"Open/Value Date"          locator=true  csvExact=false
"Rate Of Interest"         locator=false csvExact=false
"Maturity Date"            locator=true  csvExact=false
"Maturity Amount"          locator=true  csvExact=false
"Nomination Registered"    locator=false csvExact=false
"Nominee Name"             locator=false csvExact=false
"Relationship"             locator=false csvExact=false
"Account Number"           locator=false csvExact=false
"Instalment Amount"        locator=true  csvExact=false
"Instalment Due Date"      locator=true  csvExact=false
"Total No of Instalments"  locator=false csvExact=false
"No of Instalments Paid"   locator=false csvExact=false
"Outstanding Balance"      locator=true  csvExact=false
"Instalment Number"        locator=false csvExact=false
"Due Date"                 locator=true  csvExact=false
"Principal Component"      locator=false csvExact=false
"Interest Component"       locator=false csvExact=false
"Outstanding Principal"    locator=false csvExact=false
"Status"                   locator=false csvExact=false
"Quarter"                  locator=false csvExact=false
"Interest Paid"            locator=false csvExact=false
"Tax Deducted"             locator=false csvExact=false
"Certificate Number"       locator=false csvExact=false
```

## Ground truth

**MEASURED.** Ground truth is `Pass2CorpusFixtures.Spec`, written in the same source file that places
the cells — the strongest form available for an authored fixture. It was validated, not assumed: the
harness independently checks each declared cell against the extracted text layer. That control fired
on four fixtures whose columns I had placed too close together (PDFBox merged adjacent runs, e.g.
`Transaction DaCteheque NumbeTr…`); those were **fixture defects of my own making** and were fixed by
re-spacing the columns before any number in this document was produced. Two fixtures still report
declared cells as unrecoverable — `scanned-axis-ledger` (7 of 7) and `image-only-header-ledger`
(5 of 5) — and that is the intended behaviour of those two categories.

**MEASURED.** Real-corpus contribution to every coverage number in this finding: **zero new cells.**
The pass-1 real-corpus figures (ledger 15/16; non-ledger 4/14 legible with 19 masked) stand unchanged
and are the only vocabulary numbers in this investigation with real-document provenance.

**INFERRED, and load-bearing — read this before using the non-ledger numbers.** The 32 non-ledger
strings are *reconstructions* of cells the redactor destroyed in the real trace. Pass 1 inferred
`Xxxx Of Xxxxxxxx` → "Rate Of Interest" and `Xxxxxxxx Amount` → "Maturity Amount" from position, and
`PdfTraceRedactor`'s own comment names those two as having been lost. I wrote the full set from that
same inference. **The 14/32 (44%) non-ledger coverage figure therefore measures the hint lists against
my reconstruction, not against HDFC's printer.** It is strictly better than pass 1's 29%-of-29%-legible
position, and it is not evidence about real documents.

## Failure mode

**MEASURED — the formatting phenomena, updated:**

| Phenomenon | Examples now in corpus | Handled? |
|---|---|---|
| Case | `DATE`/`Date`, `Tran Date` | Yes |
| Trailing parenthetical | `Amount(Rs)`, `Balance (INR )`, `Withdrawal Amount (INR )`, `Description (a)` | Yes — including the space-before-paren variant |
| Trailing `.` | `Withdrawal Amt.`, `S No.` | Stripped; `S No.` still misses on vocabulary, not punctuation |
| **Trailing footnote marker, non-parenthesised** | `Closing Balance**`, `Txn Date#`, `Narration^`, `Balance ***`, `Debit **`, `Date 1)`, `Withdrawal Amt.*` | **No** for `hasHeaderMatch`/`firstNonBlank`; locator survives per-word |
| **Trailing footnote marker, parenthesised** | `Description (a)` | **Yes**, incidentally — the parenthetical strip catches it |
| Abbreviation | `Txn Date`, `Tran Date`, `Chq No`, `S.No`, `Init.Br`, `Ccy` | Partial — `txn date` known; `tran date` matches only via the word "date"; `chq no`/`s.no`/`init.br` unmatched |
| **Reference/serial column family** | `Ref No./Cheque No.`, `Reference / Cheque No.`, `Cheque Number`, `Transaction Id`, `S No.` | **Not in `HEADER_HINTS` at all** — four of five *are* in the CSV vocabulary |
| Interior `/` compound | `Ref No./Cheque No.`, `Open/Value Date`, `Dr/Cr` | Per-word matching splits on whitespace, not `/` — `Open/Value Date` matches via "date"; `Dr/Cr` matches nothing |
| Multi-tier / wrapped headings | 2 fixtures | See Finding #3 |
| Multilingual | **Still zero instances** | **UNKNOWN** |

**MEASURED.** Zero non-English header text exists anywhere in the corpus, real or synthetic. Pass 1
said this; pass 2 did not change it, and deliberately did not manufacture it — an authored Hindi
header would measure my transliteration, not a bank's.

## False positives

**MEASURED.** The per-word matcher's semantically-wrong-but-structurally-harmless matches from pass 1
are reproduced and extended on the fully-legible non-ledger corpus:

- `Deposit Principal` (a principal amount) matches via the word "deposit" — read as a *deposit
  amount*.
- `Instalment Due Date`, `Open/Value Date`, `Maturity Date` match `DATE_HINTS` via "date" — three
  different date meanings (a due date, an origination date, a maturity date), none of them a
  transaction date, all indistinguishable to the matcher.
- `Debit Count` / `Credit Count` (integers) match `HEADER_HINTS` via "debit"/"credit" — *counts*
  recognized as *amount* columns.
- `Outstanding Balance` matches via "balance" — a loan/RD outstanding principal read as a running
  balance.

**INFERRED.** Same conclusion as pass 1, now with more instances: these are latent while deposit and
loan sections never reach `TransactionNormalizer`, and they become live the moment a field-coverage
check asks "does this header contain a balance/amount/date synonym" on a non-ledger section.

## False negatives

**MEASURED — the complete list of corpus header strings matched by no hint list.**

Ledger tables, 9 of 41 unique cells:
`S No.` · `S.No` · `Chq No` · `Cheque Number` · `Ref No./Cheque No.` · `Reference / Cheque No.` ·
`Transaction Id` · `Dr/Cr` · `Init.Br`

Non-ledger tables, 18 of 32 unique cells:
`FD Number` · `Currency Code` · `Rate Of Interest` · `Nomination Registered` · `Nominee Name` ·
`Relationship` · `Account Number` · `Total No of Instalments` · `No of Instalments Paid` ·
`Instalment Number` · `Principal Component` · `Interest Component` · `Outstanding Principal` ·
`Status` · `Quarter` · `Interest Paid` · `Tax Deducted` · `Certificate Number`

**MEASURED — `OPENING_BALANCE`, re-checked.** `"opening balance"` remains absent from
`TransactionNormalizer.BALANCE_HINTS`. In the expanded corpus `Opening Balance` again appears **only**
as a summary-block column heading (`non-ledger-sections-legible`, `dormant-account-statement`) and
never as a per-row ledger column — across 13 ledger layouts. Pass 1's inference is confirmed on a
larger sample: a coverage check asking "is `OPENING_BALANCE` structurally present in the ledger
header" answers *no* for every document in this corpus, correctly, because it is not a per-row column
in this statement family.

**UNKNOWN — unchanged.** `CREDIT_LIMIT` and `PAYMENT_DUE_DATE`: no real credit-card statement exists
in the corpus. `PdfFixtureBuilder` carries synthetic credit-card layouts; those were not re-measured
here because they contribute no new vocabulary.

## Coverage

**MEASURED**, over the 17 new synthetic fixtures. Percentages are exact counts on a small authored
sample, not population estimates.

| MaterialField | Observed strings in the expanded corpus | Locator | CSV exact |
|---|---|---|---|
| `TRANSACTION_DATE` | `Date`, `Txn Date`, `Tran Date`, `Value Date`, `Transaction Date`, `Date 1)`, `Txn Date#` | 7/7 | 5/7 |
| `TRANSACTION_DESCRIPTION` | `Narration`, `Description`, `Particulars`, `Remarks`, `Transaction details`, `Transaction Remarks`, `Narration^`, `Description (a)` | 8/8 | 5/8 |
| `TRANSACTION_AMOUNT` (debit) | `Debit`, `Withdrawals`, `Withdrawal Amount (INR )`, `Withdrawal Amt.*`, `Debit **`, `Amount(Rs)` | 6/6 | 5/6 |
| `TRANSACTION_AMOUNT` (credit) | `Credit`, `Deposits`, `Deposit Amount (INR )`, `Deposit Amt.*`, `Credit **` | 5/5 | 4/5 |
| `CLOSING_BALANCE` / running balance | `Balance`, `Closing Balance`, `Balance(Rs)`, `Balance (INR )`, `Closing Balance**`, `Balance ***` | 6/6 | 4/6 |
| `TRANSACTION_DIRECTION` | `Dr/Cr` (one dedicated column, `union-bank`) | **0/1** | 1/1 |
| `REFERENCE` / instrument id | `Chq No`, `Cheque Number`, `Ref No./Cheque No.`, `Reference / Cheque No.`, `Transaction Id`, `S No.`, `S.No` | **0/7** | 3/7 |
| `OPENING_BALANCE` | never a ledger column (13/13 layouts) | n/a | n/a |
| Non-ledger product fields (principal, maturity, rate, instalment, TDS, nominee) | 32 unique strings | 14/32 (44%) | 2/32 (6%) |
| `CREDIT_LIMIT`, `PAYMENT_DUE_DATE` | zero real instances | n/a | **UNKNOWN** |

**Real vs synthetic contribution, stated explicitly as required:**

| Number | Real-corpus contribution | Synthetic contribution |
|---|---|---|
| Ledger 32/41 (78%) | 0 new cells (pass-1 real figure 15/16 unchanged) | **all 41** |
| Non-ledger 14/32 (44%) | 0 new cells (pass-1 real figure 4/14 legible, 19 masked) | **all 32** |
| Footnote markers 8/9 divergent | **1 real instance** (`Xxxxxxx balance**`, hdfc-composite p13, from pass 1) | 9 authored variants |
| Reference-column blind spot | **0** — this family barely appears in the real corpus (BOB's is redacted to `XXX.XX.`) | **all 7** |

**INFERRED, low confidence.** The vocabulary risk is concentrated in (a) the reference/serial column
family, (b) non-ledger product tables, and (c) the two hint vocabularies disagreeing in both
directions. Pass 1 reached (b) and half of (c); (a) is new and is the clearest new signal in this
pass. It is a hypothesis this corpus is consistent with, not a rate.

## Recommendation

Measurement only; nothing proposed, nothing changed, and the footnote-marker gap deliberately not
fixed. Four things the PM's decisions should weigh:

1. **MEASURED.** `HEADER_HINTS` and `TransactionNormalizer.recognizedColumnNames()` disagree in both
   directions on 15 of 73 corpus header strings. A field-coverage check built on either inherits that
   list's specific blindness. Which one is used is a decision, not a detail.
2. **MEASURED.** The footnote-marker divergence is real, reproducible across nine marker shapes, and
   has an accidental exception (parenthesised markers). Any normalization decision should account for
   the exception, or it will produce a third behaviour rather than a consistent one.
3. **MEASURED.** Every new vocabulary number in this pass is synthetic. The redaction ceiling was
   traded for an author ceiling. **A "coverage is now adequate" conclusion drawn from pass 2 is as
   circular as one drawn from pass 1**, for a different reason.
4. **MEASURED.** Re-capturing the three real traces at v3 remains the single highest-value corpus
   action available, and it is the only one that adds real-document evidence.

---

# Finding #3 — `PdfTableLocator` reliability measurement (expanded corpus)

## Finding

**MEASURED.** Ledger header detection remains the strongest part of the pipeline. Across the 13
ledger layouts whose header is present in the text layer: **13 true positives, 0 false negatives,
0 false positives, 0 surplus detections.** Pass 1's 12/12 on real traces is confirmed on eight further
layout conventions. **Pass 1's conclusion holds and is now better supported.**

**MEASURED.** The two ledger header misses are both **undetectable by construction**, and both are
categories that did not exist in pass 1's corpus: `scanned-axis-ledger` (no text layer at all, 0 runs
extracted) and `image-only-header-ledger` (header rasterised, data rows extractable). Neither is a
detector defect.

**MEASURED.** Failures again concentrate outside the ledger. Of **10 declared non-ledger tables**,
**3 were located**, of which **1 has materially correct values** (and lost its header semantics), and
**2 are materially wrong**. Seven were not located: **6 correctly, by the date-column rule**, and
**1 is a genuine false negative with a vocabulary root cause** — a density failure, a category pass 1
did not observe.

**MEASURED — first measurement of `RIGHT_ALIGNED_AMOUNTS`.** Pass 1 recorded this capability as
structurally unreachable (v1 traces are width-blind). On `right-aligned-amount-collision`, built with
real measured widths and the real HDFC collision geometry, the capability **fires and corrects all
four rows**. This is the one place where corpus expansion converted an UNKNOWN into a MEASURED result.

## Evidence

**MEASURED.** Harness output. `HDR` lines are `looksLikeHeaderRow` acceptances taken directly by
reflection over `groupIntoRows`, independently of section assembly.

Ledger fixtures — all detections correct, all rows staged as declared unless noted:

```
axis-savings-ledger        HDR [Tran Date, Chq No, Particulars, Debit, Credit, Balance, Init.Br]
                           SECTION 0 rows=4   (declared 4)  all values correct
icici-serial-ledger        HDR [S No., Value Date, Transaction Date, Cheque Number, Transaction
                                Remarks, Withdrawal Amount (INR ), Deposit Amount (INR ),
                                Balance (INR )]
                           SECTION 0 rows=3   (declared 3)  all values correct
sbi-ref-ledger             HDR [Txn Date, Value Date, Description, Ref No./Cheque No., Debit,
                                Credit, Balance]
                           SECTION 0 rows=3   (declared 3)  all values correct
union-bank-...-ledger      HDR [S.No, Date, Transaction Id, Remarks, Amount(Rs), Balance(Rs), Dr/Cr]
                           SECTION 0 rows=3   (declared 3)  all values correct
canara-reference-ledger    HDR [Txn Date, Value Date, Reference / Cheque No., Description, Debit,
                                Credit, Balance]
                           SECTION 0 rows=2   (declared 2)  all values correct
hsbc-deposits-first-...    HDR [Date, Transaction details, Deposits, Withdrawals, Balance]
                           SECTION 0 rows=3   (declared 3)  all values correct
detached-footnote-...      HDR [Date 1), Description (a), Debit **, Credit **, Balance ***]
                           SECTION 0 rows=2   (declared 2)  all values correct
missing-amount-columns     HDR [Date, Narration, Balance]
                           SECTION 0 rows=4   (declared 4)  all values correct
merged-amount-single-run   HDR [Txn Date, Narration, Withdrawals, Deposits, Closing Balance]
                           SECTION 0 rows=3   (declared 3)  2 rows carry merged cells BY CONSTRUCTION
right-aligned-collision    HDR [Txn Date, Narration, Withdrawals, Deposits, Closing Balance]
                           SECTION 0 rows=4   (declared 4)  ALL CORRECT; RIGHT_ALIGNED_AMOUNTS fired
```

Fixtures with defects:

```
footnote-marked-ledger     HDR [Txn Date#, Narration^, Withdrawal Amt.*, Deposit Amt.*,
                                Closing Balance**]
  ROW {Txn Date#=05/07/2026, Narration^=GROCERY * excludes charges   ** as at close of business
       # posting date   ^ as printed, Withdrawal Amt.*=1200.00, Closing Balance**=48800.00}
  -> the footnote LEGEND line was absorbed into the last transaction's narration as
     WRAPPED_DESCRIPTION. Amounts, date and balance all correct.

yearless-date-ledger       HDR [Date, Particulars, Debit, Credit, Balance]
  SECTION 0 rows=2  (declared 3)
  ROW {Date=01/07, Particulars=OPENING BALANCE, Balance=10000.00}
  ROW {Date=04/07 12/07, Particulars=UPI SAMPLE PAYEE SALARY CREDIT 48750.00, Debit=1250.00,
       Balance=8750.00, Credit=40000.00}
  -> two transactions collapsed into one row.

dormant-account-statement  HDR [Date, Narration, Withdrawals, Deposits, Balance]
  SECTION 0 rows=1  (declared 0)
  ROW {Date=No transactions during this period}
  -> one SPURIOUS row where the correct answer is zero rows.

image-only-header-ledger   (no HDR)  sections=0   3 declared data rows lost entirely
scanned-axis-ledger        0 runs extracted, 0 visual rows, 0 sections
```

Non-ledger fixtures:

```
non-ledger-sections-legible   7 tables declared; 2 header rows detected
  HDR [FD Number, Currency Code, Deposit Principal, Open/Value Date, Rate Of Interest,
       Maturity Date, Maturity Amount, Nomination Registered]
  HDR [Account Number, Instalment Amount, Instalment Due Date, Total No of Instalments,
       No of Instalments Paid, Rate Of Interest, Outstanding Balance]
  SECTION 0 rows=3 (2 FDs declared):
    row 0 correct;
    row 1 FD Number = "50300000012346 50300000012345 Sample Nominee One Spouse"
          (the NOMINEE table's entire data row absorbed into an FD row);
    row 2 = the nominee table's HEADER staged as data
          {FD Number=FD Number, Deposit Principal=Nominee Name, Open/Value Date=Relationship}
  SECTION 1 rows=2 (1 RD declared):
    row 0 absorbed the whole instalment schedule's header AND its first data row;
    row 1 is fabricated from the instalment schedule + the TDS table combined.
  capabilities = [LEADING_NARRATION_CONTINUATION, WRAPPED_DESCRIPTION, COMPOSITE_STATEMENT,
                  RIGHT_ALIGNED_AMOUNTS]

four-tier-wide-gap-header     1 table declared; header detected as the LOWER TIER ONLY
  HDR [Number, Principal, Interest, Date, Amount]
  SECTION 0 rows=2  values all correct, column NAMES stripped of their qualifiers
  (declared: Deposit Number | Deposit Principal | Rate Of Interest | Maturity Date |
             Maturity Amount)
```

## Ground truth

**MEASURED.** Declared in `Pass2CorpusFixtures.Spec` at construction time — table count, header cells
verbatim, and row count — and cross-checked against the raw extracted text by the harness's
extraction-fidelity control before the locator was run. For the two fixtures whose header is
deliberately unextractable, ground truth is the construction code alone, and that is stated wherever
their numbers appear.

**MEASURED.** No golden file was used as ground truth. Pass 1 established that
`GoldenOutputSnapshotTest` derives `columns` from the first data row's key set and therefore
under-reports the located header; that finding stands and was not re-litigated.

## Failure mode

**MEASURED, per failure, with root-cause category:**

| # | Fixture | Failure | Root cause category |
|---|---|---|---|
| 1 | `scanned-axis-ledger` | 0 runs → no table | **Acquisition** (no text layer). Not vocabulary, not geometry. |
| 2 | `image-only-header-ledger` | Data rows extractable, header absent → no table, 3 rows lost silently | **Missing header text.** The exact category pass 1 listed as unmeasurable. |
| 3 | `four-tier-wide-gap-header` | Only the lower tier used as header; qualifiers lost | **Geometry** — tiers 20pt apart vs `HEADER_WRAP_MAX_GAP = 12.0f` (`PdfTableLocator.java:110`). Same mechanism as the real HDFC p10 failure in pass 1, reproduced deliberately. |
| 4 | `non-ledger-sections-legible` §0 | Nominee table's header and data absorbed into the FD schedule | **Row-boundary reconstruction.** The nominee table has no date column, so it is never a section boundary; its rows fall into the FD section. |
| 5 | `non-ledger-sections-legible` §1 | Instalment schedule + TDS table absorbed into the RD summary | **Vocabulary (density) + row-boundary.** The instalment header scores 2 matches over 7 cells; `looksLikeHeaderRow` requires `matches * 3 >= row.size()` (`PdfTableLocator.java:1486`), i.e. ≥ 3. Not detected → not a boundary → its rows and the TDS table's rows merge backward. |
| 6 | `yearless-date-ledger` | 3 rows → 2 | **Normalization.** `CsvParser.parseDate` rejects `04/07`; `hasDateValue` is false; the row becomes a continuation and merges into the row above. Correctly diagnosable — `LEADING_NARRATION_CONTINUATION` fired. |
| 7 | `footnote-marked-ledger` | Footnote legend absorbed into the last transaction's narration | **Row-boundary.** A dateless, amount-free line inside a located table is a `WRAPPED_DESCRIPTION` continuation by rule. Same class as pass 1's "row 5's `Number` cell absorbed two page footnotes". |
| 8 | `dormant-account-statement` | 1 spurious row where 0 is correct | **Row-boundary.** "No transactions during this period" is dateless and arrives with `currentRows` empty, so it is staged as a standalone row (`PdfTableLocator.java:622-633`). |

**MEASURED — three root-cause categories, ranked by instance count in this corpus:**
row-boundary/reconstruction **4**, geometry **1**, vocabulary/density **1**, normalization **1**,
missing header text **1**, acquisition **1**. Pass 1's ranking (reconstruction dominant) is confirmed.

## False positives

**MEASURED.**

- **Header-level false positives: 0.** Across 17 fixtures, every row accepted by `looksLikeHeaderRow`
  is a genuine header row placed by the construction code. Surplus detections: **0**.
- **Section-level false positives: 0.** No section was invented where no table exists.
- **Row-level false positives: 5**, all root-caused above:

| Fixture | Rows staged | Rows declared | Spurious / corrupted |
|---|---|---|---|
| `non-ledger-sections-legible` §0 | 3 | 2 | 1 spurious (a header staged as data) + 1 corrupted |
| `non-ledger-sections-legible` §1 | 2 | 1 | 1 spurious (two other tables fused) + 1 corrupted |
| `footnote-marked-ledger` | 2 | 2 | 0 spurious, 1 narration polluted |
| `dormant-account-statement` | 1 | 0 | **1 spurious** |
| `yearless-date-ledger` | 2 | 3 | 0 spurious, 1 row is two transactions fused |

## False negatives

**MEASURED.**

- **Ledger header rows missed: 2 of 15** (13 TP). Both undetectable by construction:
  `scanned-axis-ledger` (no text layer) and `image-only-header-ledger` (header not in the text layer).
  **On ledgers whose header text exists: 13 of 13, 100%, matching pass 1's 12/12.**
- **Ledger header *columns* missed: 0.** Every declared ledger column appears in its section's union
  of row keys on all 13 detectable ledgers — including the nine columns no hint list recognizes
  (`S No.`, `Dr/Cr`, `Init.Br`, …). Vocabulary gaps cost *recognition*, not *capture*.
- **Non-ledger tables missed entirely: 7 of 10.** Six are correct true negatives (no date column,
  `looksLikeHeaderRow` requires `hasDate` — statement summary ×2, debit/credit counts ×2, nominee,
  TDS summary). **One is a genuine false negative**: the instalment schedule, root cause **density
  check**, not the date rule and not geometry. Pass 1 found one non-ledger miss whose cause was the
  date rule; this is a second, distinct cause.
- **Partial header loss (tiers dropped): 1 of 3** located non-ledger tables
  (`four-tier-wide-gap-header`). Pass 1: 2 of 4. Same mechanism, same threshold.

## Undetectable-by-construction

**MEASURED — the date-column requirement, re-confirmed.** Six of the ten declared non-ledger tables
have no date column and are permanently invisible to the locator regardless of vocabulary or geometry.
For five of the six that is the right answer (a summary block and a nominee list are not transaction
tables); for the TDS/interest summary it is arguably a genuine product table lost by rule, the same
shape as pass 1's RD-summary case.

**MEASURED — the image/OCR category now has instances, and they are shallow.** Two:
`scanned-axis-ledger` (0 runs) and `image-only-header-ledger` (data without a header). Pass 1 recorded
**zero** instances. What these two now establish is exactly two facts — that a text-layer-free document
yields nothing, and that a document with an unextractable header yields no table while its data rows
extract cleanly. **They establish nothing about OCR quality, scan degradation, or what a recogniser
would need.** A real scanned statement additionally presents sensor noise, JPEG artefacts, skew,
uneven illumination, bleed-through, fold shadows, phone-camera perspective, handwriting and stamps.
**UNKNOWN** for all of those.

**MEASURED — the width ceiling is lifted for synthetic fixtures only.** `RIGHT_ALIGNED_AMOUNTS` is now
measurable and works on a constructed collision. It remains **unreachable on all three real traces**,
so pass 1's caveat on `hdfc-txn` row 0 (`Deposits = "0.00 25,000.00"`) stands: **UNKNOWN** whether the
live pipeline reads that real row correctly, and recapture at v3 is still the only way to find out.

**MEASURED — pre-merged runs cannot be recovered downstream.** On `merged-amount-single-run`, a cell
placed as one run (`"0.00 96,142.00"`) stays one cell, and the three-way merge
(`"20.00 0.00 95686.00"`) lands wholly in `Closing Balance`. No rule in the locator un-merges what
extraction merged. This is a demonstration of a mechanism, not a rate — I constructed the merge.

## Coverage

**MEASURED**, over 17 synthetic fixtures / 16 documents / 15 declared ledger header instances /
23 declared tables:

| Category | Pass 1 (3 real docs) | Pass 2 (17 synthetic fixtures) |
|---|---|---|
| Ledger header true positives | 12 / 12 (100%) | **13 / 13 detectable (100%)**; 13 / 15 including the 2 by-construction cases |
| Ledger header false negatives | 0 | 0 detectable; 2 undetectable-by-construction |
| Header false positives | 0 | **0** |
| Surplus header detections | not reported | **0** |
| True negatives (non-transaction headers correctly rejected) | 9 / 9 | **6 / 6** |
| Non-ledger tables located | 4 / 5 | **3 / 10** |
| Non-ledger tables located **and materially correct in values** | 1 / 5 | **1 / 10** (`four-tier`, with header semantics lost) |
| Row-level false positives | ≥ 6 across 5 sections | **5 across 5 sections** |
| Undetectable-by-construction (no date column) | 9 tables | **6 tables** |
| Undetectable-by-construction (image/OCR) | **0 instances measurable** | **2 instances, shallow** |
| `RIGHT_ALIGNED_AMOUNTS` measurable | **no** (v1 width-blind) | **yes — fires and corrects 4/4 rows** |

**INFERRED, moderate confidence (up from low).** Ledger header detection is robust: 25 header
instances across 16 layout conventions and 2 real banks, 0 false positives and 0 detectable false
negatives across both passes. The extraction risk in this pipeline is concentrated in row-boundary
reconstruction on multi-table documents, not in header vocabulary or header detection. Two independent
corpora now agree.

**INFERRED, low confidence.** The non-ledger figures (3/10 located, 1/10 materially correct) look
worse than pass 1's (4/5, 1/5) but are not comparable: `non-ledger-sections-legible` deliberately packs
seven tables onto two pages with no section banners between them, which is a harder document than any
real one in the corpus. The **absolute** conclusion — non-ledger reconstruction is unreliable — is
shared by both passes; the **rate** is an artefact of how I built the fixture and should not be quoted.

**UNKNOWN.** Whether any of the eight synthetic bank layouts corresponds to what those banks actually
print. They correspond to what this repository's comments say those banks print.

## Recommendation

Measurement only; nothing proposed, nothing changed, no trigger and no sufficiency contract designed.

**MEASURED — the pass-1 page-ratio observation, re-run on the new corpus.**
`DocumentClassification.suspectedIncompleteByPageRatio()` is `rows > 0 && rows < pages`. On the
expanded corpus every ledger fixture is 1–2 pages with 2–4 rows, so it fires **zero times** — while
the measured defects in those same documents include a header staged as data, two transactions fused
into one row, a footnote legend inside a narration, a spurious row on a dormant account, three data
rows lost entirely behind an unextractable header, and a whole document yielding nothing. Two
independent corpora now show the heuristic has **zero sensitivity to every extraction defect actually
present**. That is a measured fact about these two corpora, not a general claim.

**MEASURED — the dormant case is the one that most directly threatens a future sufficiency check.**
A correct dormant statement should stage **zero** transactions; this one stages **one**, and its single
row is the sentence "No transactions during this period". Any check that distinguishes "correctly
empty" from "failed to read" by row count will see 1, not 0, and will see it on the document where
being right matters most.

---

## Adequacy assessment — the PM's actual decision point

**#2 (header vocabulary): NOT adequately measured. Further expansion needed, and only real documents
will help.**

- **MEASURED.** Real-corpus vocabulary evidence is **unchanged** from pass 1: 3 traces, 2 banks, 19 of
  33 non-ledger header cells still destroyed by redaction. Zero real header strings were added.
- **MEASURED.** Every new coverage number is synthetic and traces back to strings written in this
  repository's own comments. The pass-1 redaction ceiling ("the corpus can only confirm what the hints
  already know") has been **replaced, not removed**: the corpus can now only confirm what *we already
  wrote down*.
- **MEASURED.** The pass found one substantive new thing — the reference/serial column family, and the
  two hint vocabularies disagreeing in both directions — which is real and actionable regardless of
  provenance, because both hint lists are real production code.
- **What would actually close it:** real statements from banks outside BOB/HDFC, captured at v3, with
  a redactor allowlist widened *before* capture. Nothing achievable inside this environment changes
  the answer.

**#3 (`PdfTableLocator` reliability): adequately measured for ledger header detection; NOT adequately
measured for non-ledger reconstruction or for anything OCR-related.**

- **MEASURED.** Ledger header detection: 25 header instances, 16 layout conventions, two independent
  corpora, **0 false positives and 0 detectable false negatives in both**. Pass 1's §6-item-5 worry
  — that field coverage might inherit a silent header-detection false-negative rate — is **answered
  for ledgers**. Further ledger-header measurement has low marginal value.
- **MEASURED.** Non-ledger reconstruction: both passes agree it is unreliable, and pass 2 added a
  third distinct root cause (density) to pass 1's two (geometry, date rule). But every non-ledger
  number in pass 2 comes from **one authored fixture** whose difficulty I set. The *direction* is
  established; the *rate* is not, and cannot be from authored fixtures.
- **MEASURED.** OCR/scanned: went from 0 instances to 2 shallow ones. Everything a real scanned
  document would test remains **UNKNOWN**. This category is not measured; it is merely no longer
  empty.
- **MEASURED.** `RIGHT_ALIGNED_AMOUNTS` is now measured, and works on the constructed collision. Its
  behaviour on the real HDFC row that motivated it stays **UNKNOWN** until the traces are recaptured
  at v3.

**One-line answer.** Pass 2 confirmed pass 1 rather than overturning it, found one genuinely new
vocabulary gap and one new root-cause category, and lifted two structural ceilings — but it did so
entirely with synthetic documents, so **#2 still needs real-corpus expansion before any coverage
claim is safe, while #3's ledger-header question can be closed and its non-ledger and OCR questions
cannot.**

---

## Artefact disposition

Left on disk, **uncommitted**, for PM review:

- `backend/src/test/java/com/finora/imports/analysis/Pass2CorpusFixtures.java` — the 17 fixtures with
  their declared ground truth. Deliberately **not** added to `PdfFixtureBuilder`: that class's doc
  comment states its capability index "only ever grows when a REAL document motivates a new fixture —
  never speculatively", and these are measurement fixtures, not capability evidence. Adding them there
  would corrupt a policy the repository maintains on purpose. If the PM wants any of them retained as
  permanent test infrastructure, that is a separate decision.
- `backend/src/test/java/com/finora/imports/analysis/Pass2MeasurementHarness.java` — the throwaway
  harness. Asserts nothing, prints the report above. Intended to be deleted.
- This document.

**No production file, no existing fixture, no permanent test, and no hint list was modified at any
point.** Nothing was committed.
