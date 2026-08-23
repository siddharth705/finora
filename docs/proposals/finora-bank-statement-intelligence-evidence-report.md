# Finora Bank Statement Intelligence Evidence Report

**Status:** Evidence-driven investigation. Every claim below comes from running Finora's actual extraction code (`CorpusProbe`, `run-corpus-ground-truth.py`, `PdfTextExtractor`, `RoutingTextAcquirer`, `PdfTableLocator`, `TesseractEngine`) against real files, not from inspection, assumption, or documentation review.
**Companion documents:** [`bank-statement-document-intelligence-research.md`](bank-statement-document-intelligence-research.md) is the prior theoretical/architecture research brief. This document supersedes its evidentiary basis — where the two disagree on a factual claim, trust this one, since this one is code-tested.
**Sourcing discipline (read before the rest of this document):** two corpora were used, deliberately kept separate and never blended into one statistic:

- **Real corpus (primary evidence)** — 25 files already on the investigator's own machine at `~/Downloads/Bank statement/`, spanning `Savings accounts/` (18 files) and `Credit cards/` (7 files), covering real named Indian banks: HDFC, ICICI, SBI, Axis, Kotak, HSBC, Bank of Baroda, PNB, Union Bank, Canara, Central Bank of India, Bandhan, AU Small Finance Bank. This is the only evidence in this document attributed to a real bank by name. Only structural, non-PII signals were captured or reported — page counts, character/run counts, capability names, verification verdicts, and already-masked account identifiers. No transaction narration, unmasked account number, or amount was read, printed, or retained anywhere in this investigation.
- **Synthetic corpus (secondary evidence)** — 12 files downloaded from the Apache-2.0-licensed `AgamiAI/Indian-Bank-Statements` Hugging Face dataset, explicitly synthetic (fictional bank names — "Progressive National Bank" etc. — real Indian financial vocabulary: UPI/NEFT/RTGS/IMPS/IFSC/MICR). Used only to test generic layout/OCR robustness, never attributed to any real bank.

No PDFs were sourced from Scribd, "fillable bank statement template" sites, forums, or any other venue carrying real third-party financial data or fraud-template risk — that avenue was deliberately ruled out during scoping (see §9 for why).

---

## 1. PDF corpus collected

| Corpus | Files | Source | Real bank attribution? |
|---|---|---|---|
| Real corpus — Savings accounts | 18 | `~/Downloads/Bank statement/Savings accounts/`, investigator-owned | Yes |
| Real corpus — Credit cards | 7 | `~/Downloads/Bank statement/Credit cards/`, investigator-owned | Yes |
| Synthetic corpus — Digital | 6 (3× Digital_Type1, 3× Digital_Type2) | `AgamiAI/Indian-Bank-Statements` (HF, Apache 2.0) | No — fictional banks |
| Synthetic corpus — Scanned | 6 (3× Scanned_Type1, 3× Scanned_Type2) | Same dataset | No — fictional banks |
| **Total** | **37** | | |

Ground truth existed for 21 of the 25 real files (via `~/Downloads/Bank statement/ground-truth/*.json`) and for all 12 synthetic files (paired `.json` per PDF in the dataset itself).

A tooling note surfaced during this run, reported here because it affects how future corpus statistics should be read: `scripts/run-corpus-ground-truth.py`'s file glob (`*.pdf`) is case-sensitive and silently dropped `SBI Credit Card.PDF` (uppercase extension) from its own count — it was still probed directly via `CorpusProbe`, so this report's real-corpus numbers include it, but the script's own summary line would under-count by one. Worth a one-line fix independent of anything else in this report.

---

## 2. Banks covered (real corpus only)

| Bank | Files in this corpus | Account types present |
|---|---|---|
| HDFC | 7 (`HDFC 3 month.pdf`, `HDFC credit.pdf`, `HDFC sav.pdf`, `Manas_HDFC.pdf`, `Mann HDFC.pdf`, `Sanjay HDFC.pdf`, `Shivani_HDFC.pdf`) | Savings, credit card |
| HSBC | 3 (`HSBC.pdf`, `HSBC CC.pdf`, `HSBC DB.pdf`) | Savings, credit card |
| SBI | 3 (`Sanjay SBI.pdf`, `Statement.pdf`, `SBI Credit Card.PDF`) | Savings, credit card |
| Kotak | 2 (`Kotak CC.pdf`, `new kotak.pdf`) | Savings, credit card |
| ICICI | 2 (`ICICI CC.pdf`, `ICICI saving.pdf`) | Savings, credit card |
| Axis | 1 (`Axis credit.pdf`) | Credit card |
| Bank of Baroda | 1 (`BOB.pdf`) | Savings |
| Punjab National Bank | 1 (`PNBONE_STMT_XX4802_31072026.pdf`) | Savings |
| Union Bank of India | 1 (`Union Bank.pdf`) | Savings |
| Canara Bank | 1 (`canara.pdf`) | Savings |
| Central Bank of India | 1 (`CBI .pdf`) | Savings |
| Bandhan Bank | 1 (`Bandhan bank.pdf`) | Savings |
| AU Small Finance Bank | 1 (`AU Credit card.pdf`) | Credit card |

Note: `HDFC 3 month.pdf` and `HDFC sav.pdf` produced byte-identical extraction fingerprints (`FP-1-FDB3E93E`) — they are almost certainly the same underlying document under two filenames. Any percentage computed "per file" in this report is therefore very slightly inflated for HDFC; computed "per distinct document," HDFC's real sample is 6, not 7.

Not represented in the real corpus despite being in the original research scope: IndusInd, IDFC First, Yes Bank, Federal Bank, RBL, Equitas/Jana/Ujjivan small finance banks, Indian Overseas Bank. This is an honest gap, not a hidden one — extending bank coverage means adding to the real corpus, which this investigation deliberately did not attempt to do by scraping the open web (§9).

---

## 3. PDF type analysis

### Real corpus (24 of 25 files, from `CorpusProbe`'s `observed.extractedChars`/`positionedRuns` vs. `observed.pages`)

**24 of 25 real files are native, text-layer PDFs.** Only one file in the entire real corpus — **`HSBC DB.pdf`** (an HSBC savings statement, 2 pages) — returned `extractedChars: 0, positionedRuns: 0` despite having real pages, and was correctly classified `SCANNED_OCR_REQUIRED` by Finora's own derived classification. Every other file, including the other two HSBC documents in the corpus, extracted real text natively.

This is a materially different picture than the theoretical research brief's framing (built from general Indian-banking-ecosystem literature) might suggest: for *this specific, real 25-document sample*, scanned/image-only PDFs are the rare case, not a common one. That doesn't mean scanned statements are rare in Finora's actual user base — 25 documents from one person's own accounts is not a population sample — but it is a data point against over-investing in OCR robustness relative to native-extraction robustness without first widening the real corpus.

| Bank | Document | Pages | Observed structure | Finora impact |
|---|---|---|---|---|
| HSBC | `HSBC DB.pdf` | 2 | 0 extractedChars, 0 positionedRuns — full-page raster, no text layer | Correctly routed to OCR (`SCANNED_OCR_REQUIRED`); zero capabilities fired because there is no text to analyze; no ground truth established for this file, so OCR correctness on it is currently unmeasured |
| HDFC | `Mann HDFC.pdf` | 39 | 87,930 extractedChars, 11,298 positionedRuns — the largest document in the corpus | Native extraction handled it: `PARSED_COMPLETE`, 360 rows, no verification warnings — evidence the native pipeline scales to long multi-page statements without degrading |
| SBI | `Statement.pdf` | 1 | 2,096 extractedChars (real text, not scanned) but `documentClassification: LAYOUT_UNSUPPORTED`, 0 rows | Text is extractable but the layout itself isn't recognized — this is a genuine layout-coverage gap, not a PDF-type problem |

### Synthetic corpus (all 12 files, cross-checked against `pdftotext` independently before the pipeline run)

The `Digital_Type1`/`Digital_Type2` files (6 total) are consistently native, yielding 1,500–2,000 positioned text runs each. The `Scanned_Type1`/`Scanned_Type2` files (6 total) are consistently zero-text rasters, correctly triggering OCR in all 6 cases. Routing classification was **100% correct** against this corpus's own labels — a clean confirmation of `RoutingTextAcquirer`'s zero-vs-nonzero trigger logic, independent of the real-corpus evidence above.

---

## 4. OCR requirement analysis

### Real corpus
Only 1 of 25 real files needs OCR (`HSBC DB.pdf`). No OCR extraction quality data exists for it in this investigation — it has no ground truth, so its correctness (as opposed to its acquisition-routing correctness) is unverified. This is the single most concrete "unknown" this investigation surfaces about Finora's real-world OCR reliability: the one real scanned document available produced zero measurable evidence about whether OCR got it right, because nobody has yet written down what it should have extracted.

### Synthetic corpus
All 6 scanned synthetic files needed and received OCR. Results were poor:

| File | Avg. OCR confidence | Rows extracted / ground truth | Failure mode observed |
|---|---|---|---|
| `scanned_type1/00001` | 0.670 | 27 / 162 | Column geometry collapse — OCR merges adjacent columns into one string (e.g. `Balance="Rs. 6,086.63 Rs. 65,218.35"`, two amounts concatenated), which `TransactionNormalizer` correctly refuses to parse as a single amount |
| `scanned_type1/00002` | 0.708 | 55 / 161 | Same merged-cell pattern; visible `I`/`1` confusion in header text (`"1xn Date"` for "Txn Date") |
| `scanned_type1/00003` | 0.682 | 29 / 159 | Same pattern |
| `scanned_type2/00001` | 0.486 | **0 / 161** | Total normalization failure — header columns anchored to garbled OCR tokens (`"| Cheque No."`, `"ai 7"`) |
| `scanned_type2/00002` | 0.549 | **0 / 167** | Same collapse |
| `scanned_type2/00003` | 0.389 | 4 / 163 | Worst OCR quality observed anywhere in this investigation — some recognized text runs are single punctuation marks at near-zero confidence; one of two sections found zero rows at the header-detection stage |

**A genuinely new, measurable finding**: across these 6 files, OCR confidence predicted outcome cleanly — the three complete failures had the three lowest average confidences (0.389–0.549); the three partial successes had 0.670–0.708. This appears to contradict Finora's own prior internal finding (documented in `ocr-document-intelligence.md`) that Tesseract's confidence doesn't predict financial correctness. Both findings are real; they are not actually in conflict once the mechanism is understood: the prior finding was about a single row's confidence not predicting *that row's* correctness (a fine-grained, per-value claim). This investigation's finding is about *document-average* confidence predicting *aggregate* outcome (a coarse, per-document claim). A document-level confidence gate ("route documents below average confidence X to more scrutiny") may be viable evidence-backed future work even though a value-level confidence gate has already been shown not to work — this distinction is worth testing deliberately rather than assumed from either result alone.

---

## 5. Header pattern analysis

Real-corpus evidence here comes from capability-activation data, since raw header text was deliberately not captured (§ sourcing discipline). The picture from what *did* fire:

- **`WRAPPED_HEADER`** fired on only 2 of 25 real files — rare in this sample, though known from Finora's existing fixture history (HDFC composite deposit schedules) to be a real, serious failure mode when it does occur.
- **`REPEATED_HEADER`** fired on 8 of 25 — meaningful minority, consistent with the theoretical brief's observation that some banks repeat the column header on every page of a multi-page statement.
- **`DUPLICATE_COLUMN_NAMES`** fired on exactly 2 files — a genuine, if rare, real-world header ambiguity case.
- **`INFERRED_HEADERLESS_LAYOUT`** fired exactly once — confirming headerless statements exist in the wild but are not common in this sample.

| Bank | Document | Page | Observed structure | Finora impact |
|---|---|---|---|---|
| HDFC (composite) | (prior fixture history, not this run) | — | Multi-line, centre-aligned wrapped header across 2–3 physical lines | Already handled by `WRAPPED_HEADER` capability, per existing `header-reconstruction-design.md` |
| (unidentified — 2 real files) | — | — | `DUPLICATE_COLUMN_NAMES` fired | Column-name collision handling exists and activates on real documents, not just synthetic fixtures |

For the synthetic corpus, the finding is different and more specific: watermark/background text (e.g. "SYNTHETIC DATA" boilerplate the dataset generator stamps onto every page) bled into narration and date cells on **all 6 digital synthetic files**, costing 11–40 rows per file (7–25% of that file's transactions) even with zero OCR involvement. This is a real, reproducible native-extraction parser gap — `PdfTableLocator`'s column-bucketing has no concept of "background/watermark text vs. foreground table text," so any incidental text sharing a row's y-coordinate band gets bucketed as if it were a table cell.

---

## 6. Transaction structure analysis

Real-corpus row-extraction outcomes, by document classification:

| Outcome | Count | Files |
|---|---|---|
| `PARSED_COMPLETE`, rows > 0, no verification issues | 19 | Most of the corpus |
| `PARSED_COMPLETE`, but with a WARNING | 1 | `Axis credit.pdf` — `CREDIT_CARD_STATEMENT_TOTALS: WARNING`, 108 rows still extracted, ground truth still PASSes |
| `PARSED_INCOMPLETE` | 2 | `Bandhan bank.pdf` (3 rows over 7 pages — ground truth still PASSes, so 3 may be genuinely correct for this document), `ICICI CC.pdf` (3 rows — ground truth still PASSes) |
| `LAYOUT_UNSUPPORTED`, 0 rows | 3 | `HSBC.pdf`, `HSBC CC.pdf`, `Statement.pdf` |
| `SCANNED_OCR_REQUIRED`, 0 rows extracted (no OCR ground truth) | 1 | `HSBC DB.pdf` |

The one genuine extraction **defect** in the real corpus — not a coverage gap, an actual wrong answer against known ground truth — is:

| Bank | Document | Page | Observed structure | Finora impact |
|---|---|---|---|---|
| HDFC | `Shivani_HDFC.pdf` | 15 pages, 4 detected sections | Section 2 is correctly classified `RECURRING_DEPOSIT` at 0.95 confidence, but extracts **0 of 6 expected rows**. Sections 1 and 3 are spurious — detected but correspond to no real entity in ground truth. Section 0 (SAVINGS) extracts correctly: 75 rows. | Ground truth verdict: **FAIL**. This is the one confirmed, reproducible extraction bug this investigation surfaces on a real document: a correctly-identified product section that nonetheless yields zero transactions, on the exact composite-multi-product-statement shape (savings + RD in one PDF) that the architecture research brief flagged Section Identity Resolver Layer 2 as needed for. |

Synthetic-corpus transaction narrations (fully fictional data; reference numbers genericized below since they're structurally irrelevant) show the real Indian financial vocabulary the theoretical research predicted: `"NEFT Cr-XXXXXXXXXXXX-XXXX0XXXXXX-CLOTHING INDUSTRIES LTD--"`, `"RTGS-XXXXXXXXXXXXX-Lakshmi Naidu"`, `"IMPS-XXXXXXXXXXXX-CLOUDTECH SERVICES"`, `"Chq Paid-MICR Inward Clearing-KAUSHIK SAHA-HDFC BANK LTD."`. Where native extraction succeeded, these matched ground truth exactly (date, amount, narration) in every spot-check performed — the extraction *logic* for these narration formats works; the *loss* on digital synthetic files was entirely attributable to the watermark-bleed issue in §5, not to narration-parsing failure.

---

## 7. Metadata extraction patterns

`CorpusProbe`'s `sectionDetail` gives a real, if coarse, signal on metadata/product classification confidence across the real corpus:

- **Savings accounts frequently classify as `detectedProduct: UNKNOWN`** with `suggestedAccountType: SAVINGS` and `productNeedsReview: true` — seen on BOB, CBI, HSBC.pdf, ICICI saving, PNB, Union Bank, canara, and new kotak (8 of ~17 savings-type files). The account-type heuristic fires; full product identification does not reach confidence.
- **Credit card statements show the inverse pattern** — `detectedProduct: UNKNOWN` but `suggestedAccountType: CREDIT_CARD` with `productConfidence: 0.0` — seen on AU, HSBC CC, ICICI CC, Kotak CC. The card-pattern signal (masked number format) is strong enough to call it a credit card but not strong enough to identify the specific card product.

Neither of these is a crash or an error — they're honest low-confidence outcomes — but they represent a real, measured gap between "Finora can tell this is a savings/credit-card document" and "Finora knows exactly which product this is," on roughly a third of the real corpus's non-savings-generic files.

---

## 8. Finora current capability assessment (real-corpus-verified)

| Discovered pattern | Can Finora handle this today? | Current component | Evidence |
|---|---|---|---|
| Native multi-page statement extraction (up to 39 pages observed) | **YES** | `PdfTextExtractor`, `PdfTableLocator` | `Mann HDFC.pdf`: 360 rows, `PARSED_COMPLETE`, no warnings |
| Scanned/image-only statement routing | **YES** (routing) / **UNVERIFIED** (correctness) | `RoutingTextAcquirer`, `TesseractEngine` | `HSBC DB.pdf` correctly routed to OCR; no ground truth exists to confirm the OCR output was right |
| Credit card statement totals reconciliation | **PARTIAL** | `CreditCardStatementTotalsValidator` | `Axis credit.pdf` produced a WARNING here even though row extraction succeeded — the validator is catching *something*, worth investigating what |
| Composite/multi-product statement (savings + RD in one PDF) | **PARTIAL, with a confirmed bug** | Section detection + `ProductDiscovery`, Section Identity Resolver Layer 1 | `Shivani_HDFC.pdf`: correct product classification, zero rows extracted, spurious extra sections — ground truth FAIL |
| Duplicate/ambiguous column names | **YES** | `PdfTableLocator`'s `DUPLICATE_COLUMN_NAMES` capability | Fired on 2 real files with no downstream verification issue |
| Headerless layouts | **YES, narrowly** | `INFERRED_HEADERLESS_LAYOUT` capability | Fired once on the real corpus — works, but the real-world sample supporting it is thin |
| Watermark/background text contamination during native extraction | **NO** | Not a named capability — a gap in `PdfTableLocator`'s column-bucketing | Confirmed via the synthetic corpus: 7–25% row loss on every digital synthetic file from the same mechanism |
| Some layout shapes (specific real SBI and HSBC formats in this corpus) | **NO** | `LAYOUT_UNSUPPORTED` classification | `Statement.pdf` (SBI), `HSBC.pdf`, `HSBC CC.pdf` — 3 of 25 real files, all zero rows |

---

## 9. Missing capabilities

Ranked by strength of evidence, strongest first:

1. **A real HDFC composite-savings+RD extraction defect** (`Shivani_HDFC.pdf`) — confirmed by ground truth, not inferred. This is the single strongest, most actionable finding in this report.
2. **Watermark/background-text-aware column bucketing** — confirmed via 6/6 synthetic digital files losing 7–25% of rows to the identical mechanism. Even though the *specific* watermark text ("synthetic data" boilerplate) won't appear on real statements, the *mechanism* (any incidental text sharing a table row's y-band gets bucketed as a cell) is a real gap that could just as easily be triggered by a real bank's page-background security text, stamp, or logo watermark.
3. **`LAYOUT_UNSUPPORTED` coverage on at least 2 real bank formats** (`Statement.pdf`/SBI, `HSBC.pdf`/`HSBC CC.pdf`) — real, currently-unsupported layouts sitting in the investigator's own corpus, not hypothetical ones.
4. **OCR ground truth entirely absent for the one real scanned document** — this isn't "OCR is weak," it's "OCR correctness on real documents is currently unmeasured," which is a more fundamental gap: Finora cannot currently know whether its own OCR path works on a real Indian bank statement, because no one has written down what the right answer looks like for the one real example available.
5. **Document-level OCR confidence as a coarse routing signal** — the synthetic-corpus evidence (§4) suggests average-confidence-per-document may predict aggregate outcome even where per-value confidence has already been shown not to predict per-value correctness. This is evidence for something Finora doesn't have yet, not evidence that something it has is broken.
6. **A repeatable, ethically-sourced way to grow real bank coverage** — this investigation confirmed there is no safe way to bulk-acquire more real, bank-attributed statements from the open web (see the sourcing note below). The only way to close the "IndusInd, Yes Bank, Federal, RBL, small finance banks" gap in §2 is more of Sid's own real statements, or another consenting user's, not web scraping.

**On why "search the web for more real PDFs" stayed off the table**: this investigation searched extensively (official bank sites, GitHub, Kaggle, Hugging Face, academic table-extraction datasets, RBI circulars) for a way to responsibly source more *real, bank-attributed* Indian statement PDFs. None exists: banks don't publish blank specimens, "sample statement" search results are overwhelmingly either real third parties' leaked/uploaded documents (unconsentable PII) or blank fillable templates from sites that primarily serve document-fraud use cases, and even the best synthetic datasets found (Hugging Face) use fictional bank names by design. This is a real, structural limit on how this kind of evidence can be grown — not a shortcut this investigation declined to take.

---

## 10. Recommended improvements

In priority order, each tied to a specific finding above:

1. **Fix the `Shivani_HDFC.pdf`-class defect first.** A correctly-classified product section extracting zero rows is the highest-confidence, most reproducible bug this investigation found. It sits exactly at the Section Identity Resolver Layer 2 gap the architecture research brief already flagged as unbuilt — this real failure is the concrete motivating case that gap was missing.
2. **Add watermark/background-text filtering to `PdfTableLocator`'s column bucketing**, informed by the synthetic-corpus evidence even though the specific trigger text won't recur on real documents. A reasonable first heuristic: text runs whose font/opacity/rotation metadata differs sharply from the surrounding table's dominant style are candidates for exclusion before bucketing — worth testing against both corpora.
3. **Write ground truth for the real corpus's 4 currently-`NOT_ESTABLISHED` files** (`HSBC DB.pdf`, `HSBC CC.pdf`, `Statement.pdf`, `SBI Credit Card.PDF`) — not as a documentation exercise, but because three of these four are also the corpus's worst-performing files, and ground truth is what would let a future fix be verified rather than assumed.
4. **Investigate the `Axis credit.pdf` credit-card-totals WARNING** specifically — row extraction succeeded and ground truth still passed, but a validator flagged something. Understanding what it's Actually catching (a real printed-total mismatch vs. a validator false positive) is cheap now and gets more expensive to untangle later.
5. **Test a document-level OCR confidence gate** as a scoped experiment — route documents whose average OCR confidence falls below an empirically-derived threshold to mandatory human review, while leaving per-value confidence un-trusted as already decided. This is a narrower, more defensible claim than a general "use OCR confidence" recommendation, and it's backed by evidence this investigation specifically generated (§4), not by vendor claims.
6. **Fix the case-sensitive `.pdf` glob in `scripts/run-corpus-ground-truth.py`** — small, but it silently under-counts the very corpus this kind of evidence work depends on.
7. **Treat "grow the real corpus" as an ongoing, manual, consent-based practice**, not a one-time task — every additional real statement (Sid's own, or another consenting user's, always kept outside the repo per the existing Synthetic Fixture Policy) is worth more to this kind of evidence-driven work than any amount of further web searching, which this investigation has now shown does not have a safe supply of what's actually needed.

---

## Evidence appendix

- Real-corpus probe records and ground-truth run transcripts (safe, non-PII: counts, classifications, capability names, masked identifiers only) were generated at `/tmp/corpus_out/probes/*.json`, `/tmp/corpus_out/all_records.json`, `/tmp/corpus_out/savings_run.txt`, `/tmp/corpus_out/creditcards_run.txt` on the investigator's machine during this session. These are ephemeral scratch outputs, not committed to this repository, consistent with the Synthetic Fixture Policy — real statement data and anything derived from it stays outside the repo, always.
- Synthetic-corpus PDFs and their diagnostic output were retained at `/private/tmp/claude-501/.../scratchpad/agami-corpus/` and `/private/tmp/claude-501/.../scratchpad/agami-diagnostic-output.txt` for this session only — also not committed, since they're bulk third-party dataset files with no reason to live in this repository.
- Tooling used, unmodified: `backend/src/test/java/com/finora/imports/analysis/CorpusProbe.java`, `scripts/run-corpus-ground-truth.py`, `scripts/ground-truth-match.py`. No production code was changed to produce any finding in this report.

## Sources

Internal:
- [`bank-statement-document-intelligence-research.md`](bank-statement-document-intelligence-research.md)
- [`financial-document-intelligence-principles.md`](../architecture/system-design/financial-document-intelligence-principles.md)
- [`ocr-document-intelligence.md`](../architecture/system-design/ocr-document-intelligence.md)
- [`header-reconstruction-design.md`](../architecture/system-design/header-reconstruction-design.md)
- `scripts/trace-capture.sh`, `scripts/run-corpus-ground-truth.py`, `backend/src/test/java/com/finora/imports/analysis/CorpusProbe.java`

External:
- [AgamiAI/Indian-Bank-Statements dataset (Hugging Face)](https://huggingface.co/datasets/AgamiAI/Indian-Bank-Statements)
