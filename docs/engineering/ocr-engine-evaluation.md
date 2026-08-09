# OCR-3A — how an engine gets chosen

Status: harness calibrated; Tesseract 5.5.3 evaluated. The result is at the end of this document —
this first half describes the ruler, and was written and calibrated before any engine was installed.

## The question the evaluation answers

Not "which engine reads the most characters correctly". That question has a satisfying answer and
the wrong one.

An engine that transcribes every glyph on a statement perfectly, and reports positions one column
to the left, produces a flawless character score and a ledger in which a ₹55,000 salary credit has
left the account. Finora has already shipped that bug once, from native extraction rather than from
OCR, and it is the reason `SummaryTotalsValidator` exists. A transcription benchmark cannot see it.

So the question is: **after this engine reads the document, is the money right?**

## How it is answered

```
declaration ──► rendered PDF ──► rasterised image ──► engine ──► recognised runs
     │                                                                │
     │                                                    RecognisedTextAdapter
     │                                                                │
     │                                                          PositionedText
     │                                                                │
     │                                    the existing parser, unmodified:
     │                                    PdfTableLocator → TransactionNormalizer
     │                                              → verification rules
     │                                                                │
     └──► ground truth ────────► ground-truth-match.py ◄────── observation
                                          │
                                       verdict
```

Two properties of this shape carry the whole argument.

**The real parser scores.** Recognised runs become `PositionedText` and go through
`PdfTableLocator`, the normaliser and the verification rules exactly as native text does. A
scorecard row is therefore a claim about the pipeline, which is the thing being bought — not about
the engine in isolation, which is not.

**The same matcher judges.** OCR output is graded by `ground-truth-match.py`, the comparator built
in OCR-2B for native extraction. Nothing about it was relaxed, extended or forked for OCR. If a
recogniser needed its own comparator or its own thresholds, the claim that OCR is an acquisition
strategy rather than a second parser would be false, and enforcing it by reusing the program is
stronger than asserting it in prose.

**Expectations never descend from a reading.** The document is rendered from a declaration and the
ground truth is emitted from the same declaration, independently. No engine's output — and no
other engine's output — is ever the standard another is measured against.

## Calibration, before any engine

The harness must be shown capable of failing before a number from it means anything. Four stub
engines exist for that and only that:

| stub | what it does | must score |
|---|---|---|
| `ceiling` | returns the source text layer — a flawless read | **PASS** |
| `misread-amount` | one digit wrong: `55,000.00` → `35,000.00` | **FAIL** |
| `drifted-column` | perfect characters, value runs 80pt left | **FAIL** |
| `blind` | recognises nothing | **FAIL** |

```bash
scripts/ocr-scorecard.py --calibrate
```

Measured output:

```
engine              verdict  date        amount      direction   currency
ceiling             PASS     MATCHED     MATCHED     MATCHED     MATCHED
misread-amount      FAIL     MATCHED     UNEXPECTED  MATCHED     MATCHED
drifted-column      FAIL     MATCHED     MATCHED     UNEXPECTED  MATCHED
blind               FAIL     MISSING     MISSING     MISSING     MISSING
```

Each stub fails on precisely the axis it corrupts and no other. `drifted-column` is the one to read
twice: its **amount is MATCHED** — every digit correct — and its **direction is UNEXPECTED**. In
this fixture the deposit column sits at x=380 and the withdrawal column at x=300, so an 80pt drift
files the credit as a debit. That is the original defect, reproduced from geometry alone, and an
evaluation scoring characters would have ranked such an engine first.

None of these stubs is a candidate. `ceiling` in particular reads a text layer that does not exist
in a real scan; it establishes what a flawless engine would score, so that a real engine's shortfall
can be attributed to the engine rather than to the fixture or the parser.

## What the harness deliberately does not do

- **No engine is chosen, and no dependency is added.** The Tesseract adapter shells out to a binary
  the developer installs; nothing entered the build. OCR-3A's output is a decision, and adding a
  dependency before the evidence exists would make the evaluation ceremonial.
- **No production code changed.** The substitution is an override of `PdfTextExtractor.extract` in
  test scope. Routing will go through `DocumentTextAcquirer` when it arrives, and it will not arrive
  through this class.
- **No routing, no `NATIVE_PLUS_OCR`, no confidence thresholds.** Those need an engine's measured
  behaviour to be designed against, which is what this produces.

## Privacy

No real statement is involved at any stage. Documents are rendered from declarations, rasterised
in-process, and discarded with the temporary directory. This is what allows observations here to
carry financial values at all: the matcher refuses values on any record not declaring
`observationSource: SYNTHETIC`, and the real-corpus probe cannot produce one.

The rule that made this necessary is unchanged — real customer documents are never copied into the
repository, scratch space, logs, fixtures or development artefacts, and no part of the test suite
may require one.

## Running it

```bash
cd backend && ./mvnw -o test-compile
```

```bash
scripts/ocr-scorecard.py --calibrate
```

When an engine is installed, it joins the switch in `OcrScorecardEmitter.engine(...)` and appears as
a scorecard row. Nothing else changes — which is the point of building the ruler first.

---

# OCR-3A result — Tesseract 5.5.3

Measured 2026-08-09 against Tesseract 5.5.3 (leptonica 1.87.0, `eng`), via
`scripts/ocr-scorecard.py --benchmark tesseract`.

## Scorecard

| dimension | 150 DPI | 300 DPI |
|---|---|---|
| Entity | MATCHED | MATCHED |
| Product (SAVINGS) | MATCHED | MATCHED |
| Transaction count (3-row) | MATCHED | MATCHED |
| Dates | MATCHED | MATCHED |
| Amounts | MATCHED | MATCHED |
| Debit/credit | MATCHED | MATCHED |
| Currency | MATCHED | MATCHED |
| Position (`SALARY CREDIT` in declared band) | IN | IN |
| Wrong-amount mutation | **FAIL, as required** | **FAIL, as required** |
| Wrong-direction mutation | **FAIL, as required** | **FAIL, as required** |
| Multi-page (63 rows, 2 pages) | FAIL — 61 of 63 rows | FAIL — 63 of 63 rows, one amount wrong |

Mean confidence 0.953 at 150 DPI, 0.961 at 300.

Both mutation scenarios fail exactly as they must: a document printing 35,000.00 against a truth
declaring 55,000.00 is caught on the amount axis, and a credit printed in the withdrawal column is
caught on direction. The scorecard can fail, and a passing row therefore means something.

## The finding

**Tesseract's recognition was not the problem.** On the multi-page document it read every character
of every amount correctly, at ~0.96 confidence, and placed `SALARY CREDIT` inside its declared
region. The single wrong value came from somewhere else.

The row that failed, as each side segments it:

```
native (PDFBox)    x=130.0 w=110.0  'FILLER TRANSACTION 10'      x=300.0  '11.00'
tesseract          x=130.8 w=29.3   'FILLER'
                   x=163.2 w=63.6   'TRANSACTION'
                   x=230.9 w=7.4    '11'                          x=301.0  '11.00'
```

**PDFBox emits phrase-level runs; Tesseract emits word-level runs.** Finora's parser was built
against the first, so a description ending in digits leaves a bare numeric token near the value
columns, and it is absorbed into the amount: `11.00` becomes `111.00`.

This matters far beyond the fixture. Real statements end descriptions with numbers constantly —
cheque numbers, UPI references, instrument IDs — so this is not an artefact of `FILLER TRANSACTION
11`, it is the shape of the problem.

Note what found it. Character accuracy was 100%; a transcription benchmark would have reported a
flawless engine. The defect only appears when recognised runs go through the real parser, which is
the reason the harness is built the way it is.

The row loss at 150 DPI is a separate and lesser issue: 2 of 63 rows were not recognised, and 300
DPI recovers all of them. Resolution fixes that. Resolution does **not** fix the segmentation
mismatch — it persists identically at 300 DPI.

## Verdict

**Tesseract is viable, and is not yet sufficient on its own.** Recognition quality is not the
blocker; the run-segmentation contract is. Before an engine choice is final, OCR-3B needs a
run-assembly step in the adapter that groups word-level output into phrase-level runs, after which
this benchmark should be re-run.

PaddleOCR is still not justified. The defect found here is not one a different recogniser would
avoid — word-level output is the norm for OCR engines, so PaddleOCR would meet the same parser
contract and most likely the same failure. Swapping engines to escape a segmentation mismatch would
be treating the wrong layer.
