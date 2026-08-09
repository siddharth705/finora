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

---

# OCR-3B — run assembly

The OCR-3A verdict was "viable, not yet sufficient: the blocker is the run-segmentation contract,
not recognition quality". This closes that gap, and turned up two defects rather than one.

## Defect 1 — the two sides did not report the same y

PDFBox's `getYDirAdj` is the **baseline**, at the bottom of the text. Tesseract's `top` is the top
of the ink box. Nothing reconciled them, so every recognised row sat one line-height above where the
same row sits natively.

Measured across nine fixtures and 368 matched runs, the offset is **6.27–6.54pt** against a median
run height of 6.48–6.72 — one text height, consistently. The adapter now reports `top + height`.

This alone took ledger equivalence from 5/8 layouts to 6/8, and fixed `offset column anchors`
outright (0 rows → 3, matching native). It was invisible on the evaluation fixture because a uniform
offset preserves relative structure; it only appeared when comparing against native geometry.

## Defect 2 — word-level runs, and what a threshold can and cannot do

`RunAssembler` groups words into phrases by line, joining while the horizontal gap stays below
`JOIN_WITHIN × median run height`. Confidence of a merged phrase is the **minimum** of its parts —
averaging would let one shaky digit disappear into a long confident description.

The threshold was swept against ledger equivalence over ten layouts at both resolutions:

| threshold | 150 DPI | 300 DPI |
|---|---|---|
| 0.55x | 4/10 | 8/10 |
| 0.58x | 7/10 | 9/10 |
| **0.64x** | 7/10 | **10/10** |
| **0.70x–1.10x** | 7/10 | **9/10** ← broad plateau |
| 1.25x | 5/10 | 8/10 |
| 1.50x | 5/10 | 8/10 |

10/10 is reachable at 0.64x **and only there**. A constant that must land on one point to work
against ten documents is a fit to those ten, and the first statement in a different font moves the
point out from under it. **0.85x** sits in the middle of the plateau instead, where a quarter either
way changes nothing.

An estimate derived from the document's own space width — the median of each line's smallest gap —
was tried in its place and behaved no better (10/10 at 1.3x alone, 9/10 across 1.5x–2.5x), so the
simpler measure stayed.

## The finding that outranks the threshold — 300 DPI

**At 150 DPI, ledger equivalence never exceeds 7 of 10 at any threshold tested.** The same three
layouts fail for every value, so the limit is pixels rather than grouping: 9pt text at 150 DPI is
about 19 pixels tall, and the characters that decide a financial value go first.

So `OcrEvaluation.OCR_DPI = 300`, and a production acquirer will have to rasterise there.
`ScannedPdfFixture.DEFAULT_DPI` deliberately stays at 150 — it describes what a *scanner* produces,
which is the input OCR must cope with, not the resolution OCR should render at. Two different
questions; sharing one constant would have hidden this.

## Result

| scenario | raw Tesseract | assembled |
|---|---|---|
| baseline | PASS | PASS |
| wrong-amount mutation | FAIL, as required | FAIL, as required |
| wrong-direction mutation | FAIL, as required | FAIL, as required |
| multi-page | **FAIL** — `11.00` read as `111.00` | **PASS** |

Stub calibration is unchanged: a flawless read still passes, and a one-digit misread, a one-column
drift and a blind engine all still fail. Assembly fixed the contract without blinding the ruler.

Seven of the eight repository statement layouts now read the same ledger through OCR as through
native extraction, asserted directly in `TesseractRunAssemblyTest`.

## Known limitation — `Dr`/`Cr` suffixes

`buildDrCrSuffixAmountColumnSample` prints amounts as `37.94 Dr`. PDFBox emits that as one run; a
word-level engine emits two, and whether they rejoin depends on a gap that is not reliably smaller
than the gap to the next column. No value of `JOIN_WITHIN` fixes it without costing others.

It is **not** bought back with vocabulary. Teaching the assembler that `Dr` and `Cr` belong to the
amount before them would put statement terminology inside a geometric component — the boundary
`PdfTableLocator` already refuses to cross. A component that knows what a debit is cannot be reused
for a document that does not have one.

The limitation is asserted rather than omitted, so a change that fixes it fails the test and gets
noticed instead of passing quietly.

## What remains before production

Assembly and DPI are settled; routing is not. `DocumentTextAcquirer` still has no OCR
implementation, nothing decides when to reach for one, and `NATIVE_PLUS_OCR` remains unbuilt. Those
are OCR-4, and they now have measured behaviour to be designed against.

---

# OCR-4 — routing

The first change in this sequence that touches production code. Until now the acquisition package
was built and unused: `DocumentTextAcquirer`, `AcquiredDocument` and `NativePdfAcquirer` existed but
were referenced nowhere outside their own package, and `PdfPreviewGenerator` called
`PdfTextExtractor` directly.

## The rule

```
native extraction always runs first
any runs at all      -> return them, untouched
zero runs            -> hand the bytes to a recogniser, if one is deployed
zero runs, no engine -> return the empty result; the existing error explains it
```

**Zero, not a threshold.** The obvious design routes on "the text layer looks poor", and there is no
evidence for what poor means. Across the real corpus, character density says nothing useful: 993
characters per page yields 58 transaction rows, while 1545 and 1799 per page yield none. A density
cutoff would be a guess wearing an authoritative number. The one measured signal is total absence —
`DocumentContext.hasNoExtractableText`, established in OCR-2D — and that is the only one acted on.

**Native-first is structural, not a preference.** A document with even one native run returns before
any recogniser is consulted, so no statement that works today can change behaviour because routing
exists. There is no path through `acquire` that reaches a recogniser while native text exists, and
the test asserts the recogniser is never *called* rather than that the output looks native — a
recogniser that happened to agree would satisfy the weaker claim.

## No engine ships

`RoutingTextAcquirer` takes `List<RecognisingTextAcquirer>`, and that list is **empty in the shipped
configuration** — asserted in `AcquisitionWiringIT`, not assumed.

An OCR engine is an operational dependency of a deployment, not a library. Registering Tesseract as
a `@Component` would commit the project to installing a binary in the production image, with image
size, cost and support consequences that are not this change's to make. So routing ships and its
tests run in the configuration production actually runs — with no engine — while
`TesseractRecogniser` in test scope proves the same seam carries a real engine end to end.

The consequence is worth stating plainly: **this change does not by itself make scanned statements
import in production.** It makes them importable the moment an engine is deployed, and it is
measured doing exactly that.

## What routing deliberately does not do

- **No per-page routing, no `NATIVE_PLUS_OCR`.** A cover page with a text layer above a scanned
  table is a real shape and `AcquiredDocument` already models it, but recognising *part* of a
  document needs a measurement of which parts are missing, and none exists. Built on a guess it
  would produce a document whose provenance is confident and wrong.
- **No confidence thresholds.** OCR-3A measured Tesseract reporting ~0.96 on the row whose value the
  pipeline then got wrong. Confidence has been shown *not* to predict financial correctness here, so
  it is recorded and never acted on.
- **No new failure mode.** A recogniser that throws is treated exactly like one that is absent: the
  user gets the existing "this PDF has no text in it", and the next recogniser still gets its turn.

## What this cost, and the guard that came out of it

Adding the acquirer constructor left `PdfPreviewGenerator` with two constructors, and Spring will not
choose between them. **344 tests failed at once**, every one reporting "No default constructor found"
against `PdfPreviewGenerator` — accurate, and pointing at the class rather than at the ambiguity. The
routing unit tests all passed throughout, because they construct it directly.

`AcquisitionWiringIT` now asserts that injecting `DocumentTextAcquirer` reaches routing, and that no
recogniser is present by default. A seam that is correct but unreachable is worth nothing, and the
way it becomes unreachable is a change nowhere near it.

The existing constructor taking a `PdfTextExtractor` was kept as a native-only delegate. Twenty-six
test call sites build the generator that way, and rewriting all of them in the same commit that
changed routing would have produced exactly the diff a real regression hides in.

## Verification

- 1892/1892 backend tests pass
- The OCR-3A calibration still holds and the OCR-3B benchmark still holds, both through the changed
  generator
- A scanned statement reaches **the same ledger as its native original**, end to end, with routing
  making the choice
- Fixture-hygiene ratchet at baseline; PII, XML-comment, client-auth and check-imports gates clean

Not yet run: the 18-document real-corpus diff, which needs the corpus that lives outside the
repository. Native-first makes a corpus change structurally impossible, but structural arguments are
what corpus runs exist to check.
