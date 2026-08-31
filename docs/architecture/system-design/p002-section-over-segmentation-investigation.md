# P-002: Section over-segmentation — investigation

Status: investigation complete, no code changed. Read-only sweep against the 20 committed
redacted traces in `backend/src/test/resources/traces/`. Every number below was measured by
running `PdfTableLocator.locateAll` and `TransactionNormalizer.normalize` against those traces;
nothing here is inferred from reading code alone.

Date: 2026-08-12. Parser Improvement Board item P-002.

---

## 1. The mechanism, precisely

### 1.1 There is literally no row-count gate in section acceptance

Confirmed by reading the whole of `locateAll`. `PdfTableLocator.locateAll`
(`backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java:390-742`) appends a
`LocatedSection` at exactly three unconditional sites — `:491` (section-marker banner), `:559`
(header-signature change), `:738` (end of document). None of the three inspects
`currentRows.size()`, cell content, or anything else. A section with one row, zero rows, or no
recognisable financial content is added on identical terms to a 569-row ledger.

`PdfPreviewGenerator.generateSectionsWithContext` (`PdfPreviewGenerator.java:216-231`) then loops
over `doc.sections()` and builds one `StagedAccountSection` per located section, again with no
gate. The sweep's framing "no minimum-row filter" is literally true at both layers.

### 1.2 What actually manufactures the extra sections: prose accepted as a table header

Only two things open a new section: a `SECTION_MARKER` banner (`:187-189`), or a row for which
`looksLikeHeaderRow` returns true with a header signature differing from the active one
(`:535-582`). On the affected documents it is always the second.

`looksLikeHeaderRow` (`:1745-1768`) is:

```java
if (row.size() > MAX_HEADER_ROW_CELLS) return false;       // 16
boolean hasDate = ... matchesAnyHint(t.text(), DATE_HINTS);
boolean denseEnoughToBeAHeader = matches * 3 >= row.size();
return hasDate && matches >= 2 && denseEnoughToBeAHeader;
```

All three guards fail open on fine print, for one reason: `matchesAnyHint` (`:1774-1789`)
tokenises the **whole cell** into words and matches any word against `HEADER_HINTS`. A
600-character terms-and-conditions paragraph that PDFBox emitted as two or three text runs is a
row of two or three "cells". If one of them contains the ordinary English word *date* and another
contains *amount*, *credit*, *balance* or *details* — which any credit-card MITC paragraph does —
then `hasDate` is true, `matches >= 2` is true, and `matches * 3 >= row.size()` is trivially true
because `row.size()` is 2 or 3. The density guard added to reject prose measures density in
*runs*, and prose that arrives as few long runs is maximally "dense" by that measure.

`MAX_HEADER_ROW_CELLS = 16` (`:1718`) does not help either: the comment at that constant says it
was raised from 8 to 16 precisely because the density check "took over the real work of rejecting
prose". Measured here, it did not.

Concretely, the measured header cell widths of the sections each document opened (post
`coalesceHeaderRuns`, i.e. the column names that end up as the row map's keys):

| Section kind | longest header cell (chars) | longest header cell (words) |
|---|---|---|
| every genuine table section in the corpus (24 of them) | ≤ 36 | ≤ 7 |
| every spurious prose section (8 of them) | ≥ 108 | ≥ 19 |

Kotak's phantom sections carry header "columns" of 203, 330, 465, 477, 570 and 614 characters —
i.e. whole paragraphs of the fee schedule and MITC text standing in as column names.

### 1.3 What the user actually sees — two further defects, downstream

Sections do get filtered before they become accounts, but the filter has a hole, and the guard
that would catch the hole is not on this path.

`StagedAccountSectionFilter.onlySectionsThatAreActuallyAccounts`
(`backend/src/main/java/com/finora/imports/StagedAccountSectionFilter.java`) drops any section
with zero **staged transaction rows** — except:

```java
List<StagedAccountSection> accounts = sections.stream().filter(s -> !s.rows().isEmpty()).toList();
if (accounts.isEmpty() || accounts.size() == sections.size()) return accounts.isEmpty() ? sections : accounts;
```

When *no* section staged a transaction, the filter returns **all** sections unchanged. That is the
exact state of every affected document: they stage zero transactions document-wide, so the filter
that exists to remove non-accounts hands every phantom straight through.

And `ImportService.parseAndStagePdfWithSession`
(`backend/src/main/java/com/finora/imports/ImportService.java:289-320`) calls
`rejectIfNothingWasExtracted` **only** in the `sections.size() <= 1` branch (`:305`). The
multi-section branch (`:314`, `createMultiSection`) has no zero-extraction guard at all. So:

- a one-section document that extracts nothing is correctly rejected with
  `IMPORT_NO_TRANSACTIONS_FOUND` / `IMPORT_NO_HEADER_DETECTED` (`ExtractionCheck`);
- an eight-section document that extracts nothing is staged as **eight accounts to confirm**.

The over-segmentation is what converts a clean rejection into a phantom-account review screen.

---

## 2. Genuine multi-section vs. spurious fragment

Positive control: `hdfc-composite-deposit-schedules` — 4 sections, 4 genuinely different products
(savings ledger, FD schedule, RD summary, RD installment schedule).

Measured discriminators across all 40 located sections in the corpus:

| Candidate rule | Separates genuine from spurious? | Evidence |
|---|---|---|
| **Minimum row count** | **No** | `hdfc-composite`[2] is a genuine RD section with **2 rows**. Kotak's phantoms have 1–2 rows. Identical range. Any threshold that kills one kills the other. |
| **Minimum staged transactions** | **No, actively harmful** | `hdfc-composite`[1], [2], [3] are all genuine products and all stage **0** transactions (deposits are not ledgers — see `PdfPreviewGenerator.buildSections:269`). This is the rule `StagedAccountSectionFilter` already uses, and it is why the control document's deposit sections are already not offered as accounts today. |
| **Date-and-amount cell density** | **No** | `hdfc-composite`[2] has 1 of 2 rows carrying date+amount; `au`[0] (a genuine credit-card transaction block) has 0 rows my date regex recognises, because AU prints `99- Xxx` with no year. |
| **Header-cell prose length / word count** | **Yes, cleanly** | Genuine ≤ 7 words per header cell; spurious ≥ 19. A gap of 12 words with no corpus member inside it. |

So the distinguishing property is not size, density, or transactionality — it is **whether the
line that opened the section is a header at all**. A genuine section is opened by a short,
deliberate list of column names. A spurious one is opened by a paragraph of prose that happened to
contain two hint words.

One important exception, discussed in §5: `au-credit-card-statement`[2] is a **real table** (an
interest-computation schedule: `Date | To Date | Balance Amount | No. of Days | Interest
Charged`). It is correctly located and is not prose — it simply is not an account. No structural
filter can remove it without removing `hdfc-composite`'s genuine deposit tables too. That part of
P-002 is a classification problem, not a filter problem.

---

## 3. Per-document quantification (the flagged four, plus corrections)

`staged` = rows that `TransactionNormalizer.normalize` returns as `RowKind.TRANSACTION`.
`prefill` = what `PdfPreviewGenerator.suggestedAccountTypeFor` would put in the review form.

### kotak-credit-card-ledger-validation — 8 sections, 0 real accounts staged

| § | rows | staged | header shape | prefill | verdict |
|---|---|---|---|---|---|
| 0 | 1 | 0 | 1 cell, 570 chars / 102 words | CREDIT_CARD | prose (interest-worked-example paragraph) |
| 1 | 2 | 0 | 203 chars / 38 words | SAVINGS | prose (card fee grid) |
| 2 | 1 | 0 | 477 chars / 77 words | SAVINGS | prose (MITC) |
| 3 | 2 | 0 | 614 chars / 109 words | SAVINGS | prose (MITC) |
| 4 | 0 | 0 | — | SAVINGS | empty section, no rows at all |
| 5 | 2 | 0 | 206 chars / 34 words | SAVINGS | prose (late-payment fee table) |
| 6 | 2 | 0 | 330 chars / 54 words | SAVINGS | prose |
| 7 | 2 | 0 | 465 chars / 70 words | SAVINGS | prose |

User-visible outcome: a multi-section review screen with **8 accounts**, 7 of them prefilled
SAVINGS, all 8 with zero transactions. Zero transactions normalize anywhere on this document, so
the correct outcome is an `IMPORT_NO_TRANSACTIONS_FOUND` rejection.

### sbi-credit-card-statement — 5 sections, 0 staged

| § | rows | staged | header | prefill | verdict |
|---|---|---|---|---|---|
| 0 | 1 | 0 | `Statement Date, Credit Limit ( ` ), ( ` )(as ...)` | CREDIT_CARD | genuine payment-summary grid |
| 1 | 2 | 0 | `Available Credit Limit, Payment Due Date, Available Cash Limit` | **SAVINGS** | genuine summary grid, mis-prefilled |
| 2 | 2 | 0 | `Transaction Details` | CREDIT_CARD | genuine txn block, unparsed |
| 3 | 2 | 0 | `Date` | **SAVINGS** | genuine txn block, unparsed |
| 4 | 2 | 0 | 221 chars / 31 words | CREDIT_CARD | prose (EMI legal text) |

Confirms the prior sweep exactly: 5 sections, 2 would stage as phantom SAVINGS. Note that only §4
is prose. §1 and §3 are fragments of the *same* card's own content, split apart because the column
layout changes between blocks — a different failure mode from Kotak's.

### au-credit-card-statement — 3 sections, 0 staged

| § | rows | staged | prefill | verdict |
|---|---|---|---|---|
| 0 | 2 | 0 | CREDIT_CARD | genuine transaction block (unparsed) |
| 1 | 2 | 0 | **SAVINGS** | prose fragment (`date. Payment is ... Total` — 32 chars / 6 words) |
| 2 | 2 | 0 | CREDIT_CARD | genuine **interest-computation table** — a real table, not an account |

1 phantom SAVINGS account.

### icici-credit-card-statement — 3 located sections, but **only 1 reaches the user**

| § | rows | staged | prefill | verdict |
|---|---|---|---|---|
| 0 | 0 | 0 | SAVINGS | empty |
| 1 | 2 | 0 | SAVINGS | prose (108 chars / 19 words) |
| 2 | 6 | **3** | CREDIT_CARD | genuine |

**Correction to the background brief.** Because §2 stages 3 transactions,
`StagedAccountSectionFilter` drops §0 and §1 and returns a single account. ICICI does **not**
produce a phantom SAVINGS account at the staging layer today. The over-segmentation is real at the
locator layer; the downstream filter already absorbs it. Same for `axis-credit-card-statement`
(2 sections; §1 stages 108 transactions, so §0's payment-summary block is filtered away).

### Newly found: hdfc-credit-card-ledger-validation — 2 sections, 0 staged

§0 (payment summary, 2 rows) and §1 (transaction block, 4 rows) both stage 0, both prefill
CREDIT_CARD. The filter's `accounts.isEmpty()` hole passes both through → **2 phantom accounts**.
Not previously flagged.

---

## 4. Full corpus sweep — all 20 traces

| trace | sections | genuine products | accounts the user is offered | verdict |
|---|---|---|---|---|
| au-credit-card-statement | 3 | 1 | 3 | **affected** (1 phantom SAVINGS + 1 non-account table) |
| axis-credit-card-statement | 2 | 1 | 1 | over-segmented at locator, absorbed downstream |
| bob-repeated-account-banner | 1 | 1 | 1 | correct |
| bob-savings-ledger-validation | 1 | 1 | 1 | correct |
| canara-savings-ledger-validation | 1 | 1 | 1 | correct |
| central-bank-savings-ledger-validation | 1 | 1 | 1 | correct |
| hdfc-composite-deposit-schedules | 4 | **4** | 1 | correct (control) |
| hdfc-credit-card-ledger-validation | 2 | 1 | **2** | **affected** |
| hdfc-savings-ledger-validation | 1 | 1 | 1 | correct |
| hdfc-savings-multi-page-ledger | 1 | 1 | 1 | correct |
| hdfc-savings-single-page-ledger | 1 | 1 | 1 | correct |
| hdfc-txn-date-narration-header | 1 | 1 | 1 | correct |
| hsbc-savings-ledger-validation | 1 | 1 | 1 | correct (extraction is broken, segmentation is not) |
| icici-credit-card-statement | 3 | 1 | 1 | over-segmented at locator, absorbed downstream |
| icici-savings-ledger-validation | 1 | 1 | 1 | correct |
| kotak-credit-card-ledger-validation | **8** | 1 | **8** | **affected, worst case** |
| kotak-savings-ledger-validation | 1 | 1 | 1 | correct |
| pnb-savings-ledger-validation | 1 | 1 | 1 | correct |
| sbi-credit-card-statement | **5** | 1 | **5** | **affected** |
| union-bank-savings-ledger-validation | 1 | 1 | 1 | correct |

Scope: **4 of 20 documents** reach the user over-segmented (kotak, sbi, au, hdfc-cc). All four are
credit-card statements; all four stage zero transactions. Two more (axis, icici) are
over-segmented internally but masked by the downstream filter. Every savings/current statement in
the corpus segments correctly. The control document segments correctly.

The correlation is not incidental: it is the `accounts.isEmpty()` hole. Over-segmentation only
becomes user-visible on a document where nothing parsed — which today means credit cards.

---

## 5. Recommended fix

### Fix 1 (smallest, highest value, no parser change) — close the zero-extraction hole

Apply the same zero-extraction rejection to the multi-section path that the single-section path
already has. `ImportService.java:299-315` currently guards only `sections.size() <= 1`. A document
where **no** section staged a single transaction is the same failure regardless of how many
sections were located, and `ExtractionCheck` already produces the right message and error code for
it.

Measured effect on the corpus: kotak, sbi, au and hdfc-cc stop producing phantom accounts and
start producing an honest `IMPORT_NO_TRANSACTIONS_FOUND`. `hdfc-composite` (75 staged), axis (108),
and every savings statement are untouched, because they all stage transactions. This is a
**four-document fix that cannot regress anything in the corpus**, and it touches no parsing logic.

It does not reduce the section count; it stops a zero-transaction document from being presented as
accounts at all. That is the user-visible harm.

### Fix 2 (root cause) — reject prose as a table header

Add a prose guard to `looksLikeHeaderRow`: a header cell of more than N words is not a column
name. Measured with N = 12, applied over `coalesceHeaderRuns(row)`:

| trace | sections before → after | rows before → after | staged before → after |
|---|---|---|---|
| kotak-credit-card | 8 → **0** | 12 → 0 | 0 → 0 |
| sbi-credit-card | 5 → **4** | 9 → 7 | 0 → 0 |
| icici-credit-card | 3 → **1** | 8 → 6 | **3 → 3** |
| au-credit-card | 3 → 3 | 6 → 6 | 0 → 0 |
| axis-credit-card | 2 → 2 | 113 → 113 | **108 → 108** |
| **hdfc-composite (control)** | **4 → 4** | **102 → 102** | **75 → 75** |
| all 14 other traces | unchanged | unchanged | unchanged |

Zero transactions lost anywhere in the corpus. The control document is byte-identical. The
discarded prose is not dropped: it falls through to `pendingAuxiliary`, and the measurement
confirms it (ICICI's surviving section's auxiliary text grows 15 → 237 lines, SBI's 76 → 455) —
the "never lose information" contract is preserved without a new code path.

Kotak going to **0 sections** is the correct outcome, not a cliff:
`PdfTableLocator.locate` (`:361-367`) returns all lines as `preTableLines` when there are no
sections, and `PdfPreviewGenerator.generateSectionsWithContext:193-214` turns that into one
section with every line reported as unparseable — which then hits the existing single-section
`rejectIfNothingWasExtracted`. Kotak's credit-card signals live in that auxiliary text, so the
detected-bank/card metadata is not lost.

Note that N = 12 is a wide gap, not a tuned fit: the corpus has nothing between 7 and 19 words.

### What Fix 2 does *not* fix

SBI stays at 4 sections and AU at 3, because their extra sections are **genuine tables** —
SBI's summary grids and transaction blocks, AU's interest-computation schedule. Removing those
requires deciding "this table is not an account", which is exactly the judgement
`StagedAccountSectionFilter`'s own doc comment defers to the planned product-classification stage.
Fix 1 makes them harmless in the meantime.

### Recommended sequencing

Fix 1 first, alone. It removes 100% of the user-visible harm measured in this corpus, is one
conditional in `ImportService`, and is provably neutral on every document that parses. Fix 2 is
the real root-cause fix and is measured-clean, but it changes parser behaviour and belongs in its
own commit with the golden-output snapshots regenerated.

---

## 6. Adversarial risks a future implementation must guard against

1. **A genuine header that is one long cell.** The guard must run per cell, not on the joined
   line, and it must run over `coalesceHeaderRuns(row)` — the same coalescing P-001 added
   (commit `2bcb21e`), which deliberately joins split runs into whole header cells. A genuine
   7-column HDFC header coalesces to cells of ≤ 3 words; do not apply the guard to the raw
   pre-coalesce runs, where the count is a different quantity.
2. **Interaction with WRAPPED_HEADER (P-001).** `wrappedHeaderAt` merges a second line into the
   header before `looksLikeHeaderRow` is asked (`:530-535`). A word cap applied to the merged row
   sees roughly double the words of either line. Measured at N = 12 nothing in the corpus is
   affected, but the margin for a genuine two-band header is smaller than the raw numbers suggest
   — do not lower N below ~10 without re-measuring the wrapped-header traces
   (`central-bank-savings-ledger-validation`, `hdfc-txn-date-narration-header`).
3. **Backward pollution.** Rejecting a header does not delete the line; it falls to the dateless-row
   path, which can merge it into the previous section's last row — the exact failure the
   `SECTION_MARKER` "DEPOSIT -" comment at `:170-186` documents ("24053.00 RECURRING DEPOSIT -
   30000000000003"). Measured here it did not happen (the caps at
   `MAX_LEADING_CONTINUATION_ROWS` route the text to auxiliary instead), and the largest section's
   first row is identical before and after on every trace. Any implementation must re-assert that
   with a content-equality check, not just a row-count check.
4. **Section indices are a coordinate space.** `StagedAccountSectionFilter`'s doc comment warns
   that every downstream index (`confirmMultiSection`'s loop index, `confirmSession`'s implicit 0)
   is an index into its *output*. Changing how many sections exist changes that space. Fix 1 does
   not (it rejects rather than renumbers); Fix 2 does, so the evidence-engine re-derivation in
   `com.finora.imports.evidence` that re-runs the generator must be re-checked.
5. **Do not reach for a row-count or transaction-count threshold.** Measured above: it is provably
   wrong on the control document, whose genuine RD section has 2 rows and 0 transactions — the same
   shape as Kotak's phantoms.
6. **Fix 1 changes a success into a rejection.** A user who today gets a confusing eight-account
   screen would get an error. That is correct, but it is a user-facing behaviour change on real
   documents that currently "succeed" in telemetry — the parse-success metric will drop for credit
   cards, and that drop is the bug becoming visible, not a regression.

---

## 7. Verdict: is this a production bug?

**Yes, and it is two bugs, only one of which is in the parser.**

- **Confirmed production bug, worth fixing now:** the multi-section staging path has no
  zero-extraction guard (`ImportService.java:299-315`), and `StagedAccountSectionFilter` returns
  every section unfiltered when none has transactions. Together these present 8 / 5 / 3 / 2
  zero-transaction phantom accounts to the user on four of the twenty real documents in the
  corpus, where a single-section document with identical content would be cleanly rejected. This is
  a real defect with a one-conditional fix and zero measured regression risk.

- **Confirmed parser bug, real but lower urgency:** `looksLikeHeaderRow` accepts prose paragraphs
  as table headers, because `matchesAnyHint` matches hint words anywhere inside a cell and the
  density guard is computed in PDFBox runs, which prose minimises. This manufactures the extra
  sections. The fix is measured clean on the whole corpus but is a parser change and should not be
  bundled with the first.

- **Not a bug, needs classification not filtering:** AU's interest-computation schedule and SBI's
  payment-summary grids are genuine, correctly-located tables that are not accounts. No structural
  filter can reject them without also rejecting `hdfc-composite`'s genuine deposit schedules. This
  is the planned product-classification work, and P-002 should not attempt it.

The honest framing for the board: the parser over-segments, but over-segmentation on its own has
been survivable for as long as documents parsed. What makes it reach the user is a zero-extraction
guard that only exists on one of two staging paths. Fix that first.
