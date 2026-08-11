# ADR-005: the Document Intelligence Contract

**Status:** accepted. Specification only — nothing here is implemented, and nothing should be until
the ground-truth model exists (see §10).
**Extends** [ADR-004](adr-004-document-pipeline-scope.md), which set the document *space* and the
graceful-failure promise. This adds the *representation* that space requires.
**Builds on** [persistence-boundary-design.md](../engineering/persistence-boundary-design.md) and
[security-control-audit.md](../engineering/security-control-audit.md) §"document integrity".

---

## 1. Why a contract before an implementation

A hybrid pipeline — profiler, OCR, layout engine, AI interpretation, validation — is the right
architecture, and it is also entirely capable of reproducing the failure it is meant to fix. A more
sophisticated system can still conclude:

> *We extracted something, therefore we extracted everything.*

That sentence is the defect. `Shivani_HDFC.pdf` locates three financial products, persists one, and
reports success. No amount of OCR or model capability corrects that, because the loss happens after
extraction and the pipeline has no vocabulary for "an entity exists and we did not get its data."

So the contract is written first, and the pipeline is built against it. The goal it encodes:

> We know what the document contains, what we extracted, what we could not extract, and whether we
> are confident enough to persist it.

## 2. Expressed over the existing model, not a new one

**Constraint:** no parallel `FinancialDocument → Section → FinancialEntity` hierarchy unless the
existing types demonstrably cannot satisfy this contract. The current evidence says they can.

| Contract concept | Existing type |
|---|---|
| positioned text run | `imports.pdf.PositionedText` |
| financial entity, per section | `DetectedAccountInfo` |
| entity identity | `ProductIdentity` (`forDeposit(principal, maturityDate, installmentAmount)`) |
| persisted entity | `Account` — already declares `productType`, `investmentKind`, `principalAmount`, `interestRate`, `maturityDate`, `maturityAmount`, `installmentAmount`, `installmentsPaid/Total`, `creditLimit` |
| verification outcome | the four validators' `VERIFIED / WARNING / FAILED / NOT_APPLICABLE` |

A parallel model would mean two representations to keep in step while neither is complete. The gap is
not expressiveness; it is that the commit path is transaction-centric and section-level facts are not
persisted. This is a specification of guarantees over existing types, not a type system.

## 3. One document representation, whatever the source

Native extraction and OCR must converge on the same representation before any layout, section or
entity logic runs. `PositionedText` is extended rather than duplicated:

```
PositionedText { text, page, x, y, width, height,
                 confidence,                  // NEW: per-run, 1.0 for native
                 source: NATIVE | OCR }       // NEW: provenance
```

**OCR is an acquisition capability, not a second parser.** There must never be a "PDF parser" and an
"OCR parser" as separate systems — that is the per-institution mistake ADR-004 §4 forbids, in a
different dimension. Table detection, section location and entity extraction must be unable to tell
where a run came from, except by reading `source` deliberately.

`confidence` exists so that a low-confidence OCR run can reach validation as *uncertain* rather than
as fact. Native runs carry 1.0 because PDFBox either reports a glyph or does not.

## 4. A section is a financial entity that may have transactions

Restating the inversion from the persistence design note, now as a contract term:

```
   today: section → transactions → Account (created because transactions needed a home)
   next:  section → financial entity → Account (created from identity) → transactions attach if present
```

**Transactions are `[0..N]`, not mandatory.** An entity the document evidences must survive
persistence with zero transactions. This is what makes savings-plus-card, savings-plus-RD-plus-FD,
several accounts in one file, and later loan and investment statements expressible at all.

## 5. The three-way distinction, and it is mandatory

Making zero legitimate creates a new ambiguity, and the contract fails without closing it. These are
three different facts and must be three different states:

| State | Meaning | Correct outcome |
|---|---|---|
| `PRESENT_NO_TRANSACTIONS` | entity exists; the document has no transaction ledger for it | persist, import succeeds |
| `PRESENT_EXTRACTION_FAILED` | entity exists; its data should have been extractable and was not | persist the entity, **flag for review**, do not report success |
| `NOT_DETECTED` | no entity found at this location | nothing to persist; a ground-truth mismatch if one was expected |

**`productType` must not be used to decide this.** A term deposit *can* list interest credits, so
"FDs have no transactions" is a guess dressed as a rule. The distinction is per-entity,
per-document.

### `zeroTransactionsLegitimate` carries provenance, not a boolean

A bare boolean derived from the parser's own output is the Shivani failure with a field name attached
— `0 rows → therefore zero is legitimate` is precisely the inference that must not be available.

```
zeroTransactionsLegitimate:
    value: true | false | UNKNOWN
    evidence:
        source: DOCUMENT | GROUND_TRUTH | ABSENT
        pages: [...]
        reason: "<what in the document supports this>"
```

`UNKNOWN` is a first-class value and **must not default to `true`**. An entity with zero
transactions and `UNKNOWN` legitimacy is a review item, not a successful import. This is the same
rule as ADR-004 §3: partial data under a success label is the one categorically unacceptable
outcome.

## 6. The entity contract

```
Financial Entity
├── identity                     ProductIdentity; may be partial, never fabricated
├── product / type               with confidence and evidence
├── attributes                   principal, rate, maturity, installments, limit, period, balances
├── transactions [0..N]
├── extraction status            PRESENT_NO_TRANSACTIONS | PRESENT_EXTRACTION_FAILED | NOT_DETECTED
├── evidence                     productEvidence — why we concluded what we concluded
├── confidence                   productConfidence, plus needsReview
├── verification                 per-entity, not collapsed to one document verdict
└── zeroTransactionsLegitimate   with provenance (§5)
```

Two of these already exist and are discarded at the commit boundary: `productEvidence` is persisted
nowhere, and verification findings are keyed per import rather than per section — which is how a
warning on one section was attributed to a whole statement.

## 7. Ground truth is not positional

Expected entities must not be a position-indexed list. Section identity is not reliable — which is
why `corpus-diff.py` suppresses positional comparison the moment section count changes — and a
positional ground truth inherits exactly that weakness.

So expected entities carry **stable local ids**, and matching observed sections to expected entities
is an explicit step that may return:

| | |
|---|---|
| `MATCHED` | identity, or product type plus attributes, pairs them |
| `MISSING` | expected, not found — the Shivani defect |
| `UNEXPECTED` | found, not expected — a real discovery *or* a spurious section |
| `AMBIGUOUS` | cannot pair without guessing |

**Identity must not be mandatory.** Where a masked number is absent, product type plus principal plus
maturity date is a legitimate basis; where nothing pairs them, the answer is `AMBIGUOUS`, never a
fabricated identifier. Ground truth for real documents lives **outside the repository** — it is
derived from customer statements and is data of the same sensitivity (persistence design note §14).

## 8. OCR routing: no text-density threshold

**Do not introduce a `chars/page` cutoff.** The corpus falsifies it:

```
one statement    928 chars/page  →   0 rows
another          993 chars/page  →  58 rows, parsed correctly
```

6.5% apart, one useless and one perfect. Any threshold catching the first also catches the second.
And the zero-row failures in the corpus are not one problem: one document loses most of its text
*before* positioning (high chars/page, few positioned runs), another positions text correctly and
fails at row parsing (high chars/page, many runs). Neither needs OCR. Routing them there sends
someone to fix the wrong layer.

`SCANNED_OCR_REQUIRED` therefore stays gated on `extractedChars == 0`, which is a definition rather
than a tuned number. **There is currently no scanned statement in the corpus**, so no non-zero
threshold has any evidence behind it. Acquiring one is a prerequisite for the profiler, not a detail.

Where both paths run on a difficult document, disagreement between them **flags**; it never silently
picks a value. That is the existing `COLUMN_AMBIGUITY` posture applied to a new axis.

## 9. The AI boundary

**AI interprets; deterministic validation decides.** A model may answer "does this section describe a
recurring deposit?", "what does this unusual header mean?", "which fields belong to one product?" It
may not determine that an import is correct. Balances, totals, identity resolution and consistency
remain deterministic, and the release gate is unchanged: never present partial or incorrectly
attributed financial data as successfully processed.

**Raw statements and page images do not leave Finora by default.** Only the minimum extracted context
needed to resolve a specific ambiguity crosses that boundary — a header string, not a document. This
follows directly from the 2026-08-08 incident: real customer data must not become a development
artefact, and that investigation found `/tmp`, a build directory and a scanner's own log output all
counted. An external model is a far larger crossing, with retention, training use and provider access
outside our control.

## 10. Sequence, and why OCR and AI come last

```
ADR / contract  →  ground-truth model  →  section/entity persistence
   →  native extraction improvements  →  OCR acquisition  →  same PositionedText pipeline
   →  AI interpretation for ambiguity  →  deterministic validation  →  corpus regression
```

Ground truth precedes persistence because the success condition for the persistence change is "RD and
FD survive **and** the savings section is unchanged", and only ground truth makes the second half
checkable. OCR and AI come after because both add capability to a pipeline that cannot yet tell
"complete" from "no signal contradicts completeness" — and adding capability to that pipeline scales
the defect rather than the product.

## 11. The integrity gate

The pipeline must never report successful processing when an expected financial entity, or material
financial information, may have been silently lost or attributed to a different entity.

This is a release gate, not a guideline. A guideline loses to a deadline.
