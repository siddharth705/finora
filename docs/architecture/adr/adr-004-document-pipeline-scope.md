# ADR-004: the document pipeline targets a document *space*, not a list of files

**Status:** accepted. Scope-setting; no implementation follows directly from it.
**Supersedes nothing.** Constrains the observability/regression milestone and
[persistence-boundary-design.md](../system-design/persistence-boundary-design.md).

---

## 1. Why this is being written now

A 16-statement corpus was just made repeatable and machine-readable
([`scripts/corpus-run.py`](../../scripts/corpus-run.py), `CorpusProbe`). That is a good instrument
and a bad target. The failure mode it invites is real and cheap to fall into: a diff tool exists, two
statements look wrong, and the shortest path to a green diff is a change that fits those two
documents. Repeat sixteen times and the result is sixteen special cases wearing one parser's name.

So the target is stated before the diff is built, not after.

## 2. Decision

**Finora's document pipeline is designed for the range of financial documents users will upload. The
16 statements in the corpus are evidence and regression fixtures. They are not the product boundary
and not the definition of done.**

Corpus coverage ≠ product coverage. The corpus tells us how today's implementation behaves against a
sample of real documents. It says nothing about the seventeenth.

### The document space we design for

| Dimension | Range |
|---|---|
| Institution | any bank, NBFC, card issuer, broker — no per-institution code path |
| Product | savings, current, credit card, RD, FD, and later loans and other investments |
| Composition | single product; savings + card; savings + RD + FD; several accounts in one file |
| Layout | different layouts from the same institution; the same layout across statement periods |
| Acquisition | text-extractable PDF, image/scanned PDF requiring OCR, CSV, Excel (XLS/XLSX) |

A document type not yet supported must be *refused with a reason*, never partially ingested.

### The stages, and why they are stages

```
   any supported financial document
        ↓  acquisition          PDF / OCR / CSV / Excel  →  a common internal representation
        ↓  understanding        layout, structure, capabilities — institution-agnostic
        ↓  financial entities   what products does this document describe?
        ↓  accounts/products    identity, so re-import resolves rather than duplicates
        ↓  sections             where in the document each product's data lives
        ↓  extraction           product- and layout-specific, selected by evidence
        ↓  validation           reconciliation, ambiguity, totals
        ↓  confidence+evidence  and an explicit record of what could NOT be determined
        ↓  persistence          complete financial entities, including ones with no transactions
        ↓  presentation         only what is relevant, and uncertainty shown as uncertainty
```

The specificity is deliberately pushed to one stage. *Extraction* may be product- and
layout-specific; nothing upstream of it may be. Which extractor runs is chosen from observed
evidence (layout fingerprint, capability probes, detected product), not from an institution's name.

## 3. The non-goal, stated plainly

**"Finora handles every PDF" is not an engineering promise, and we will not make it.** Encrypted,
corrupted, proprietary-encoding, image-only-at-unusable-resolution and arbitrarily malformed
documents exist. Any roadmap that implies otherwise is a roadmap that will be met by silently
lowering the bar on what "handled" means.

The promise we will make instead:

> **Broad document coverage plus graceful failure.** When Finora cannot fully understand a document,
> it must *know* that it cannot, and state what is missing — to us and to the user.

This is the stronger requirement, and it is stronger in a specific way: it is achievable for
documents we have never seen, whereas "all PDFs pass" is achievable only for documents we already
have. A pipeline that correctly reports "this is a scanned statement, no text layer, OCR not
available" has behaved *well* on a document it cannot parse. A pipeline that extracts 1 row from 4
pages and reports success has behaved *badly* on a document it nearly can.

**Corollary that decides arguments:** a document that does not match a known layout must be
detected, classified and surfaced for review. It must never yield partial data under a success
label. Partial-and-labelled-success is the only outcome in this ADR that is categorically
unacceptable — it is worse than outright refusal, because refusal is visible.

## 4. What must not be built

```
   HSBC parser        ✗
   HDFC parser        ✗
   Axis parser        ✗
   if (bank == "ICICI") ...   ✗
```

or a fix whose justification is the name of one file in the corpus.

**Current state, verified rather than assumed:** `git grep -niE '\b(hdfc|icici|axis|hsbc|kotak|
canara|bandhan|pnb|bob)\b' -- backend/src/main/java` returns **nothing**. There is no
institution-specific branch in the pipeline today. Layout fingerprinting and the capability registry
are the institution-agnostic mechanisms that replaced the need for one.

So this section is a **property to preserve**, not a goal to reach. That matters for how it is
enforced: the cheapest guard is the one that fails the moment the property is first broken. A
Repository Guardian rule asserting no institution literal appears in the pipeline packages is
in scope for a later step, and it is enforceable precisely because the current count is zero.

Per-institution knowledge that is legitimately needed belongs in **data** — a fingerprint, a
capability declaration, a ground-truth fixture — never in a control-flow branch.

## 5. Where the implementation stands against this target

Written down because a target with no honest gap list becomes a claim.

| Stage | Today | Gap against the ADR |
|---|---|---|
| Acquisition | `StatementUpload.Format` = `{PDF, CSV}` | No Excel. No OCR: no `tesseract`/`tess4j` in `pom.xml`, and `ImportController:52` says so in a comment. `SCANNED_OCR_REQUIRED` *names* this gap; nothing implements it, and no corpus statement reaches it — that path is exercised only by a synthetic fixture |
| Understanding | layout fingerprint + capability registry, institution-agnostic | Meets the ADR. Preserve it |
| Financial entities | `DetectedAccountInfo` models deposits per section | Attribute extraction returns `null` for Shivani's RD and FD, so the model is right and unfed |
| Identity | `ProductIdentity.forDeposit(principal, maturity, installment)` | Correct mechanism; depends on the attributes above, which are absent. The two must land together |
| Sections | located and, since Step 2b, recorded per section in the corpus | Not persisted per section |
| Validation | four validators, evaluated per section | Collapsed to one document verdict at persistence |
| Confidence + evidence | `productConfidence`, `productEvidence`, `productNeedsReview` exist | `productEvidence` is persisted nowhere, so no inference is auditable after import |
| Persistence | transaction-centric | A section with zero transactions is dropped |
| Presentation | accounts that have transactions | No representation of "product exists, no transaction history" |
| **Knows what it cannot understand** | `DocumentClassification` reports what *happened* | **The largest gap.** It does not report what is *missing*. `PARSED_COMPLETE` without ground truth means only "no available signal contradicts completeness". Shivani_HDFC drops two of three products and the import reports success — the exact outcome §3 calls categorically unacceptable |

One thing in that table is better than it looks. The acquisition seam already works the way this ADR
requires: `ImportController:52` records that everything downstream of staging is unaware whether a
session came from the CSV path or the PDF path, because both produce an identical
`StagingSessionResponse`/`ImportSession` shape. Adding PDF required no change below staging. That is
the property a fourth and fifth format depend on, and it is already held — OCR and Excel are missing
*implementations*, not missing *architecture*.

The last row is the ADR's own success criterion, and it is currently unmet. That is the reason the
milestone order is classification → corpus → diff → **ground truth** → extraction/persistence, and
not the reverse.

## 6. Consequences for the remaining milestone steps

**Step 3, `scripts/corpus-diff.py`.** Compares two runs across document facts, section structure,
per-section rows, product classification, account identity where available, verification findings,
extraction completeness, and capabilities/layout fingerprint. It carries **no expectation about any
specific document** — no "HDFC has 3 sections", no "HSBC yields N rows". It answers *what changed*,
never *what is correct*. Correctness lives in ground-truth fixtures (Step 4), which are per-document
data by definition and are the only place a filename may legitimately appear.

The diff must also be honest about its own limits: when section count changes between runs,
positional section comparison is no longer meaningful and the diff must say so rather than align by
index and report a stream of false changes.

**Step 4, ground truth.** The mechanism by which "we know we do not fully understand this" becomes
provable. Note the asymmetry that keeps it useful: ground truth for a document we hold is a fixture;
the *behaviour* it verifies — reporting a shortfall rather than reporting success — is what
generalises to a document we have never seen.

**Step 5, RD/FD extraction and zero-transaction persistence.** Land together (see
[persistence-boundary-design.md §6](../system-design/persistence-boundary-design.md)). Success is "RD
and FD survive **and** the savings section's 75 rows are unchanged", which is exactly the
proposition Step 3 exists to check.

## 7. How this ADR is kept true

- **A fix justified by a filename is rejected in review.** The justification must be a document
  property — a layout, a capability, a product type — that the fix generalises over.
- **New format support extends `StatementUpload.Format` and the acquisition stage**; it does not
  branch inside extraction.
- **A guardian rule against institution literals** in the pipeline packages, added while the count
  is zero.
- **The corpus grows, and growth is expected to break things.** A new statement that classifies as
  unsupported is the pipeline working as specified in §3. A new statement that classifies as
  `PARSED_COMPLETE` while quietly dropping a product is a defect of the kind this ADR exists to make
  impossible to ship unnoticed.

## 8. The criterion, in one sentence

Not *"all 16 statements pass"* but:

> Finora can take an unfamiliar financial document, determine what it contains, extract what it can
> prove, preserve what it found, identify its own uncertainty, and never silently discard financial
> information.

The 16 statements are the first measurable safety net on the way there — not the destination.
