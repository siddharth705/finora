# OCR document intelligence

How character recognition enters this pipeline, what each milestone is entitled to claim, and what
the current ground truth cannot yet establish.

Deliberately separate from
[ground-truth-model-design.md](ground-truth-model-design.md). That document is the
contract for the ground-truth matcher and should stay one thing; this one is about acquisition, and
would otherwise accumulate concerns the matcher does not have.

## The rule everything else follows

> **OCR is an acquisition mechanism, not a financial decision-maker.**

Neither native extraction nor recognition has authority to declare a financial fact correct. They
supply evidence. Deterministic reconciliation and the verification rules decide whether that evidence
is sufficient — the same separation the pipeline already observes, where a parse being
self-consistent has never been a claim that it is right.

A recogniser reporting 96% confidence in the characters `40,000.00` has said nothing about whether
that is a credit, whether it belongs to this account, or whether it belongs in this row.

## Acceptance levels

Each level proves one thing. **Passing an earlier level does not imply any later level is correct**,
and the levels are worth naming precisely because "OCR works" is otherwise said when only L1 has been
demonstrated.

| Level | Proves |
|---|---|
| **L1 — Acquisition** | OCR produces `PositionedText` |
| **L2 — Text** | Expected text and labels are recovered |
| **L3 — Attributes** | Dates, amounts and fields are recovered |
| **L4 — Entity** | Correct financial entity and product attribution |
| **L5 — Financial** | Financial values and invariants are correct |
| **L6 — Production** | Routing, reconciliation, failure handling and privacy are proven |

An engine that returns text has cleared L1. That is the beginning of the work, not the end of it.

## Current ground-truth limitation

The existing model verifies **entity presence, product classification and transaction count**. It
does **not** establish transaction-level financial values.

> A document with the correct entity, product and transaction count can currently pass ground-truth
> matching even when an individual transaction amount is wrong.

This was found by the OCR-2A mutation test rather than by reading: an early version mutated a
transaction *amount* and the matcher returned `PASS` — correctly, because no assertion in the model
covers that field. The mutation was retargeted to withhold a transaction from the document, which the
model does assert on, and the matcher then failed as required.

The limitation matters most for recognition specifically. A recogniser's characteristic failure is
**the right number of rows with a wrong digit in one of them** — precisely the shape this model
cannot currently see. So value-level ground truth is a prerequisite for **L3 and above**, and is not
a reason to widen OCR-2A.

## Milestones

CORRECTED: this table originally planned OCR-3 through OCR-7 as "engine adapter" /
"native/OCR semantic comparison" / "confidence and contradiction handling" / "routing and
profiling" / "production rollout" — five milestones that don't correspond to how the work actually
sequenced. The real history, in detail, lives in
[ocr-engine-evaluation.md](../../engineering/import/ocr-engine-evaluation.md); this table is kept
reconciled to it rather than to the original plan.

| | | Status |
|---|---|---|
| OCR-1 | Acquisition seam and provenance | done |
| OCR-2 | Toolchain feasibility | done |
| OCR-2A | Synthetic ground truth and acquisition validation contract | done |
| OCR-2B | Scanned fixture generation and determinism | done |
| OCR-2D | Image-only detection (`hasNoExtractableText`) | done |
| OCR-3A | Engine evaluation harness; Tesseract 5.5.3 measured viable, not yet sufficient | done |
| OCR-3B | Run assembly (word-level → phrase-level runs); 300 DPI settled | done |
| OCR-4 | Routing (`RoutingTextAcquirer`, native-first, zero engines shipped) | done |
| OCR-5 | Production deployment (Tesseract installed, `TesseractRecogniser` promoted to `@Component`) | done |

### Routing is blocked on evidence, not on effort

No automatic "native extraction looks bad, therefore recognise it" decision exists, and none should
be added until OCR-4/5 produce a measured signal for when native extraction is actually inadequate.

Character density is known **not** to be that signal. Across the 18-document corpus, 993 chars/page
yields 58 rows while 1545 and 1799 chars/page yield none. A threshold on it would be a guess with an
authoritative appearance.

## Privacy boundary

> No real statement, extracted real-statement text, OCR intermediate, OCR image, OCR output, or
> identifier derived from a real statement may be required to run the test suite.

The failure this guards against is not only "someone committed a file". It is **real data becoming a
persistent repository or build artefact while engineering tooling runs** — which is what happened
during this work, when an investigation probe printed a holder name and account number into a
session transcript through a substring match on `count` inside the word `Account`.

`scripts/test-synthetic-ground-truth.py` therefore asserts its own independence: it refuses to run
with OCR credentials in the environment, needs no corpus and no network, and removes its temporary
directory on every path including failure. Generated artefacts are ephemeral by construction rather
than by convention.
