# ADR-006: Evidence Decision & Reconciliation Extension

**Status:** proposed. Specification only — nothing here is implemented. The routing dependency
previously left open in §4b is now resolved as an explicit architectural decision (see §4b) — the
*decision* is approved; the *implementation* of that routing change is not yet built, and nothing in
this ADR should be implemented until the detailed technical design is also approved.

**Implementation note (added during Phase C, not a revision to the decision above):** everywhere
below that says `FieldCandidate` and describes it as also carrying structural provenance,
corroboration, financial validation, contradictions, and a combined decision (§3's pseudocode, §5's
enforcement point, §5a's explanation example) — that combined type shipped as **two** types, not
one: `FieldCandidate` (value + supporting `FieldFact`s + a narrower, fact-only `status`) and
`FieldAssessment` (a `FieldCandidate` plus the three `DimensionResult`s, `contradictions`, and the
*real* three-dimension `status` this ADR's §3 describes). This was a deliberate detailed-design
decision, not an oversight: keeping `FieldCandidate` narrow avoids one type answering two different
questions at two different grains. **The consequence that matters for anyone implementing this ADR
further: every reference below to "`FieldCandidate.status`" as the confirm-time signal (§5's
enforcement point, §5a's explanation, §6 if it exists in a later revision) means
`FieldAssessment.status()`, never `FieldCandidate.status()`.** The latter is real and used, but it
answers a narrower question (do this field's own facts agree) than this ADR's §3 status (do enough
independent dimensions support this value) — checking it alone at the confirm gate would silently
implement a weaker rule than this ADR specifies.
**Extends** [ADR-005](adr-005-document-intelligence-contract.md), which established the shared
`PositionedText` representation and the entity-level Document Intelligence Contract. This narrows to
exactly the three gaps ADR-005 does not cover.
**Does not reopen** ADR-004's document-space promise, or ADR-005's entity contract, extraction-status
vocabulary (`PRESENT_NO_TRANSACTIONS`/`PRESENT_EXTRACTION_FAILED`/`NOT_DETECTED`), or ground-truth
matching outcomes (`MATCHED`/`MISSING`/`UNEXPECTED`/`AMBIGUOUS`). All reused as-is.

See [adr-responsibility-map.md](adr-responsibility-map.md) for the full ADR-004/005/006 boundary.

---

## 1. Why a narrow extension, not a new contract

A recent architecture review proposed a "common evidence model" as if from nothing. Re-reading
ADR-004 and ADR-005 in full before drafting anything found that most of it already exists on paper:
one shared representation regardless of acquisition source (ADR-005 §3), a per-entity evidence and
confidence contract with `UNKNOWN` as a first-class, never-defaults-to-true state (ADR-005 §5-6), a
rejection of invented confidence thresholds in favor of corpus evidence (ADR-005 §8), and a build
sequence that already puts ground-truth and persistence work before OCR capability (ADR-005 §10).

Writing a second, competing definition of "evidence" would have created exactly the problem ADR-004
§4 warns against in a different dimension — not per-institution parsers, but per-*document* contracts
that drift from each other the moment nobody is looking. So this ADR adds only what a full re-read
confirmed is actually missing: evidence at the level of one field, not one entity; a named mechanism
for comparing two acquisition sources' observations of the same fact; and an actual enforcement point,
since today verification is advisory only.

**Revision note (round 1):** the first version of this ADR was reviewed and sent back with four
required corrections — source-strength alone was too easy a bar for `SUPPORTED`; cross-source
comparison had no step establishing two observations refer to the *same* fact before comparing their
values; confirm-time enforcement blocked on any `FAILED` finding regardless of what it actually
invalidates; and the routing dependency (§4b) needed to stay explicit, not be designed around. All
four are applied below — see §3, §4a, §5, and §4b respectively. A follow-up wording pass also replaced
§3's "two of three agree" language, which implied comparability across three genuinely different kinds
of evidence.

**Revision note (round 2, this draft):** §4b's previously-open routing dependency is now resolved as
an explicit, approved architectural decision, not left implicit in implementation — see §4b. A new
§5a adds a statement evidence explanation/audit-trail requirement to the detailed design, proposed as
an addition rather than a second decision model — it renders the evidence and decision this ADR already
defines, it does not define new ones.

## 2. What already exists, reused without renaming

| Existing type | What it already provides | Gap for this ADR |
|---|---|---|
| `PositionedText{..., confidence, source: NATIVE_PDF\|OCR\|NATIVE_PLUS_OCR}` (ADR-005 §3) | Per-run acquisition provenance — *which engine* produced this text | Nothing at the field level — a `DetectedAccountInfo.creditLimit` carries no link back to the runs that produced it |
| `EvidenceSource{DOCUMENT_TEXT, SECTION_TEXT, ROW_DATA, COLUMN_HEADERS}` (`com.finora.imports.product`) | Per-fact *structural* provenance — where in the document a fact was observed, ranked weakest to strongest | Scoped to product classification only; not generalized to arbitrary metadata fields. **Also not, by itself, a correctness signal — see §3.** |
| `ObservedFact(signal, source, observed, named)` (`com.finora.imports.product`) | The existing "neutral fact" pattern — an observation carries no opinion until a hypothesis is tested against it | `signal` is `ProductSignal`-typed, i.e. product-classification-specific; needs generalizing to name any material field |
| `DetectedAccountInfo` | Where field values (`creditLimit`, `accountHolderName`, `statementPeriodStart/End`, `openingBalance`, `closingBalance`, `accountNumberMasked`, ...) currently live | As bare values, with no evidence, no status, and no way to distinguish "confidently correct" from "the only candidate we happened to find" |
| Verification outcomes `VERIFIED`/`WARNING`/`FAILED`/`NOT_APPLICABLE`, and their `suspectedCause` detail (`StatementTotalsValidator`: `OPENING_BALANCE`/`TRANSACTIONS`; `SummaryTotalsValidator`: `DIRECTION`/`ROW_GROUPING`/`MISSING_OR_EXTRA_ROWS`/`AMOUNTS`) | Rule-level arithmetic self-consistency across a whole section, **already carrying a coarse "what specifically is suspect" signal**, not just a pass/fail | A different, orthogonal axis from field evidence — this ADR does not rename or replace it, but §5 now builds directly on `suspectedCause` rather than ignoring it |
| Ground-truth `MATCHED`/`MISSING`/`UNEXPECTED`/`AMBIGUOUS` | Entity pairing against *expected fixtures* in tests | Test-time only; says nothing about live-document field trust |

**Two provenance axes already exist and must not be collapsed into one.** *Which acquisition engine*
produced a run (`PositionedText.source`) and *where structurally* a fact was observed
(`EvidenceSource`) are different questions. The ICICI credit-limit failure that motivated this ADR is
a pure `EvidenceSource`-axis failure — acquisition found the correct text; structural attribution
routed it to neither located section. A field-level evidence candidate needs to carry both axes,
because a value can fail on either one independently — and, per §3 below, being strong on one axis is
not evidence the value is *correct*, only evidence about *where it was said*.

## 3. Field-level evidence (gap 1 of 3) — revised

**Correction applied:** the previous draft of this ADR said a fact from any source stronger than
`DOCUMENT_TEXT`, with nothing contradicting it, was enough for `SUPPORTED`. That rule is withdrawn. A
stronger structural location says where a value was found, not whether it is right — a `ROW_DATA`
observation of `CREDIT_CARD` is better-placed evidence than a `DOCUMENT_TEXT` one, but it is exactly
the shape of evidence the ICICI incident had, and it was wrong. This ADR now keeps three questions
separate, matching the review's own framing exactly:

```
Where was this value found?        (structural provenance — EvidenceSource, existing)
Is this value corroborated?        (independent agreement — §4, not source strength)
Does this value make financial sense?   (financial validation — existing verifiers, §5)
```

```
FieldFact(field: MaterialField, source: EvidenceSource, observed: String, textSource: TextSource)
    // signal generalized from ProductSignal to a MaterialField enum: ACCOUNT_TYPE, STATEMENT_PERIOD,
    // ACCOUNT_NUMBER, OPENING_BALANCE, CLOSING_BALANCE, CREDIT_LIMIT, TRANSACTION_DATE,
    // TRANSACTION_AMOUNT, TRANSACTION_DIRECTION, ... — extend only as a real field needs it, the
    // same discipline ProductSignal's own growth already follows.
    // textSource carries PositionedText.source through, so acquisition provenance survives
    // alongside structural provenance -- see §2's two-axis note.

FieldCandidate<T> {
    value: T
    observations: List<FieldFact>              // what was seen, and where — §2's existing types
    acquisitionProvenance: Set<TextSource>      // which engine(s) produced the underlying runs
    structuralProvenance: EvidenceSource        // strongest source location among observations
    corroboration: CorroborationResult          // §4a/§4b — independent agreement on the SAME fact
    financialValidation: ValidationResult       // does this value fit the section's own arithmetic
                                                 //   (references existing verifier outcomes, §5 —
                                                 //   not duplicated here)
    contradictions: List<FieldFact>             // observations proposing a materially different value
    status: EvidenceStatus                      // DERIVED below, never asserted directly
}

EvidenceStatus = SUPPORTED | CONFLICTING | INSUFFICIENT
```

**Wording correction (this revision):** the previous draft of this section said `SUPPORTED` requires
two of the three dimensions to "agree." That word is withdrawn — these three dimensions are not the
same *kind* of evidence, and "agree" implies a comparability they don't have. `COLUMN_HEADERS` +
passing financial validation is not two sources agreeing with each other; one states *where* the value
was found, the other states *whether* the value is consistent with the section's arithmetic. Each
dimension instead answers its own, distinct question, and `SUPPORTED` requires enough of those
questions to be answered *yes* — not any comparison between them:

```
StructuralEvidence   → Is the observation located in a semantically credible region?
                        (EvidenceSource strength — was this seen in a column header, a labelled
                        field, a row, or only in free-standing document text?)

Corroboration         → Does an independent acquisition source establish the SAME_FACT
                        (§4a) with the same value? (§4b's EvidenceComparison — AGREE only,
                        never UNCONTESTED, which answers this question "no," not "not yet")

FinancialValidation    → Does the value satisfy the applicable financial consistency checks
                        (existing verifiers — BalanceChainValidator, StatementTotalsValidator,
                        SummaryTotalsValidator, ColumnAmbiguityValidator — referenced, not
                        duplicated)?
```

**Derivation — the hard constraint first, the open question second:**

- **Hard constraint (non-negotiable, what this correction exists to enforce):** no single dimension's
  criterion being satisfied is sufficient for `SUPPORTED` on its own. Not `StructuralEvidence` alone
  (the originally withdrawn rule). Not `Corroboration` alone (§4a/§4b — two sources can share the same
  mistake). Not `FinancialValidation` alone (a value can be arithmetically consistent and still be the
  wrong value, e.g. two transposed digits that happen to still balance).
- **`CONFLICTING`** — `contradictions` is non-empty. This is asymmetric and always wins: a
  contradiction forces `CONFLICTING` regardless of how many other criteria are satisfied, exactly as
  the previous draft already had it, unchanged.
- **`INSUFFICIENT`** — `contradictions` is empty, but the candidate does not clear the bar below.
- **`SUPPORTED`** — `contradictions` is empty **and** at least two of the three dimensions'
  criteria — `StructuralEvidence`, `Corroboration`, `FinancialValidation` — are independently
  satisfied.

**Required before this is implemented, not merely before it is tuned:** the two (or more) satisfied
dimensions counted toward `SUPPORTED` must be shown to be **genuinely independent failure modes**, not
two manifestations of the same underlying extraction error. A concrete risk this ADR names explicitly
so the detailed design cannot skip it: if `FinancialValidation`'s arithmetic is itself computed from
the same mis-attributed section that produced a wrong `StructuralEvidence` reading (the ICICI shape —
wrong section, so both the "where" and the "does it balance" checks are working from the same bad
input), counting them as two independent dimensions is exactly the false corroboration this ADR exists
to prevent, just moved one level up. The detailed design must demonstrate, against real documents, that
each pairing of dimensions can fail independently before the implementation is allowed to treat their
joint satisfaction as sufficient.

**The exact combining function beyond this hard constraint — precisely which pairs suffice under what
conditions, and any weighting — is a detailed-design decision, evaluated against real corpus evidence,
not fixed here.** This is the same discipline ADR-005 §8 already applies to OCR routing thresholds:
this ADR fixes the *structure* (three independently-answered questions; no single answer is
sufficient; the answers must be shown to be independent) and the *hard constraint*, not a specific
formula invented without evidence behind it.

## 4a. Same-fact correlation (new — required before any comparison happens)

**Correction applied:** the previous draft's `EvidenceComparison` compared two candidates' *values*
directly. That has a real failure mode the review identified precisely: Native's ₹50,000 on
transaction A and OCR's ₹50,000 on transaction B are not agreement just because the numbers match —
they may not be observations of the same fact at all. Comparing values before establishing they refer
to the same fact can manufacture false `AGREE` results, which is worse than not comparing, because it
looks like corroboration and is not.

So comparison is now two steps, and the first is mandatory:

```
correlate(observationA, observationB) → SAME_FACT | DIFFERENT_FACT | UNCERTAIN
```

Only a `SAME_FACT` pair proceeds to §4b's value comparison. Correlation criteria, per the review's own
split:

- **For a transaction:** page, date, description/identity features, amount, direction, geometry
  (position on the page), and ordinal position within the section's transaction sequence.
- **For a metadata field:** field identity (which `MaterialField`), document/section identity, source
  region (geometric proximity of the two observations), and semantic context (surrounding label text).

**`UNCERTAIN` is not treated as either outcome.** It never contributes an `AGREE` (that would repeat
the exact mistake this section exists to close), and it does not by itself force `DISAGREE` either —
manufacturing a conflict between two observations that may not even describe the same fact would
create false contradictions, which is a different failure than false agreement but not a smaller one.
An `UNCERTAIN` correlation is surfaced as its own diagnostic — evidence that section/region boundaries
may be unreliable for this document — rather than folded into either candidate's status silently.

Which specific fields/geometry are weighted, and how close is "close enough" for source region or
ordinal position, are detailed-design decisions informed by real documents once this is implemented —
not fixed here, same discipline as §3.

## 4b. Cross-source comparison (gap 2 of 3), and the routing decision this now requires

Only for pairs `correlate()` returned `SAME_FACT` for:

```
EvidenceComparison = AGREE | DISAGREE | UNCONTESTED | ABSENT

AGREE       — every SAME_FACT-correlated source proposes the same value
DISAGREE    — SAME_FACT-correlated sources disagree; this is what forces CONFLICTING (§3),
              never a silent pick
UNCONTESTED — exactly one source produced a candidate for this fact; nothing to correlate
              against, let alone compare
ABSENT      — no source produced a candidate for this fact at all
```

**`UNCONTESTED` is deliberately not `AGREE`, and this invariant is unchanged from the previous
draft — the review confirmed it explicitly and asked that it be kept exactly as written.** One source
with no rival is not agreement. This is the field-level form of "`UNKNOWN`/unestablished evidence must
never be treated as agreement." An `UNCONTESTED` candidate can still reach `SUPPORTED` through §3's
two-of-three rule (structural provenance + financial validation, without corroboration), but never
through a comparison that never happened.

**Agreement is evidence, never proof.** `AGREE` is one of the three dimensions §3's hard constraint
weighs — it does not by itself make a candidate `SUPPORTED`. Two sources can share the same
misreading. This is a hard invariant on the implementation, not aspirational text.

**The dependency, as originally surfaced — kept in full, because the decision below is meaningless
without it:** under the *currently wired* routing policy (`RoutingTextAcquirer`, in production today),
native extraction and OCR are mutually exclusive per document — native runs and, if it returns *any*
runs at all, its output is used untouched and no recogniser ever runs; OCR is only ever tried when
native returns zero runs, in which case there is no native `FieldCandidate` to compare it against
either. Under this policy, `EvidenceComparison` is **structurally vacuous** in production —
`AGREE`/`DISAGREE` can never occur, only `UNCONTESTED` or `ABSENT`.

**Decision (round 2, explicitly approved, not left to emerge from implementation):** the routing
policy changes. Native acquisition still runs first, but its output is now passed through a **statement
evidence assessment** before being accepted as sufficient on its own:

```
Statement
   ↓
Native acquisition
   ↓
Statement evidence assessment
   │
   ├── sufficient → continue, OCR does not run
   │
   └── insufficient or materially suspicious
             ↓
          OCR acquisition also runs
             ↓
       same evidence model (§3) → same-fact correlation (§4a) → cross-source comparison (§4b)
```

OCR is invoked not merely because a PDF is difficult, but specifically when the *evidence* native
acquisition produced does not clear §3's bar on its own. This is what makes §4b non-vacuous: the two
sources now co-occur on exactly the documents where corroboration is most valuable.

**This is a deliberate tradeoff, stated plainly rather than left implicit:**

| | |
|---|---|
| **Benefit** | Better recovery of statements native extraction handles partially; ability to corroborate native extraction rather than only fall back to OCR on total failure; ability to catch native extraction errors (the ICICI shape) *before* import, which zero-runs-only routing structurally cannot do — native produced text there, so today's policy would never have invoked OCR regardless. |
| **Cost** | Additional OCR processing time and compute on every statement where native evidence is judged insufficient, not only on fully-image statements. Higher operational cost at volume. More complex orchestration — "sufficient" is now a real decision point with its own failure modes (§3's own bar, applied one level up to trigger routing, inherits §3's open detailed-design question about what "sufficient" means precisely). |

**What is decided here, and what is not:** that this tradeoff is worth taking, and that "native returns
zero runs" is no longer the only trigger for OCR, is decided by this ADR. The exact "statement evidence
assessment" — what specifically counts as *insufficient* or *materially suspicious* to trigger a second
acquisition pass, short of full §3 field-by-field evaluation being available before OCR has even run —
is a detailed-design decision, evaluated against real documents, same discipline as §3's own combining
function. This ADR fixes the *policy shape* (native-first, evidence-gated escalation, not
zero-runs-gated escalation) and the *cost tradeoff being knowingly accepted* — not the exact threshold.

## 5. Confirm-time enforcement (gap 3 of 3) — revised

Grounded in the actual current code, not a hypothetical boundary: `ImportService.confirmSession()` /
`persistSection()` today never reads a `VerificationReport` at all — `ConfirmedRowIntegrity` checks
only that confirmed rows multiset-match what was staged, nothing about evidence status. This is the
literal current gap ADR-005 §11's integrity gate already names but does not itself close.

**Correction applied:** the previous draft said any section-level `FAILED` verification finding should
refuse the confirm by default, alongside `CONFLICTING`/`INSUFFICIENT` on a load-bearing field. That
second half is withdrawn as stated — a `FAILED` finding does not mean every fact in the section is
wrong, and blocking the whole confirm on that basis is disproportionate to what the finding actually
established.

**The existing validators already carry more scope information than the previous draft used.**
`StatementTotalsValidator`'s `FAILED` outcome already sets `suspectedCause` to `OPENING_BALANCE` or
`TRANSACTIONS` — a real, existing distinction between "the stated starting point is probably wrong"
and "the row data disagrees with the stated ending point," which implicate different facts.
`SummaryTotalsValidator`'s `FAILED` outcome already distinguishes `DIRECTION` /
`ROW_GROUPING` / `MISSING_OR_EXTRA_ROWS` / `AMOUNTS` as the suspected cause — none of which mean "every
transaction in the section is wrong." Enforcement must read and act on this existing scoping
information rather than collapsing every `FAILED` finding to one undifferentiated block:

```
Verification finding (with its existing suspectedCause, where the rule provides one)
        ↓
Which specific field(s) or fact(s) does this finding actually implicate?
        ↓
Impact assessment — does the implicated scope include a load-bearing field?
        ↓
Import decision, scoped to what was actually called into question
```

Proposed enforcement point, revised: before `persistSection()` commits, check the combined
three-dimension status (`FieldAssessment.status` in the implementation — see the note under
"Status" at the top of this document; NOT the narrower `FieldCandidate.status`)
for fields designated materially load-bearing (opening/closing balance, account identity,
per-transaction amount and direction, at minimum). Separately, map each section-level `FAILED`
verification finding to the field(s) or transaction(s) its `suspectedCause` (or equivalent scoping
detail, where the rule provides one) actually implicates, and apply the same load-bearing check to
*that* scope — not to the section as a whole by default. On `CONFLICTING` or `INSUFFICIENT` for a
load-bearing field, whether reached directly (§3) or through a scoped verification finding, refuse the
confirm by default — routed to review, not silently imported — mirroring the existing override
pattern already in the codebase for duplicate transactions (`confirmedNotDuplicate`), so a user can
explicitly accept a flagged value rather than being permanently blocked by it.

**Which fields count as "load-bearing," the exact mapping from each existing validator's
`suspectedCause` (and any rule that does not yet provide one) to an implicated scope, and the exact
HTTP/API shape of a refusal, are implementation decisions for the detailed design that follows this
ADR — not decided here.** Rules that do not yet provide a scoping detail (`BalanceChainValidator`,
`ColumnAmbiguityValidator`) may need one added as part of that detailed design; this ADR does not
mandate that they must, only that enforcement must not treat their absence as license to block
everything by default.

## 5a. Statement evidence explanation (proposed addition, round 2)

**This is not a second decision model.** It is a rendering of the evidence and decision §3–§5 already
define — the requirement that every material decision this ADR makes is *explainable*, not just
enforced. Proposed as an explicit requirement on the detailed design that follows this ADR, not a new
architectural layer of its own.

For every `FieldCandidate` a confirm decision (§5) acts on (in the implementation: the
`FieldAssessment` wrapping it — see the implementation note at the top of this document), Finora
must be able to produce, from data already specified above and nothing further invented:

```
Field:                  Closing Balance
Observed value:          ₹38,098.10
Acquisition provenance:  Native PDF                          (FieldFact.textSource, §3)
Structural provenance:   Located in closing-balance context   (EvidenceSource, §3)
Same-fact correlation:   Confirmed against OCR observation     (§4a)
Cross-source comparison: AGREE                                 (§4b)
Financial validation:    Balance chain PASSED                  (§5, existing verifiers)
Contradictions:          none
Decision:                SUPPORTED
```

And for a flagged field:

```
Field:                  Transaction Amount
Native observation:      ₹55,000
OCR observation:         ₹65,000
Same-fact correlation:   Confirmed (§4a — same page, date, description, position)
Cross-source comparison: DISAGREE                               (§4b)
Financial validation:    Unresolved (balance chain cannot confirm either figure alone)
Decision:                CONFLICTING
Action:                  Review required
```

**Why this belongs in the detailed design, precisely stated:** §3's `FieldCandidate` already carries
every field this explanation needs — `observations`, `acquisitionProvenance`, `structuralProvenance`,
`corroboration`, `financialValidation`, `contradictions`, `status`. An explanation view is a projection
of that struct, not new data. What is new, and what the detailed design must specify, is: (a) that this
projection is a first-class, retrievable output for every confirmed or blocked field, not just internal
reasoning discarded after the decision is made, and (b) where it surfaces — at minimum the admin
diagnostic tooling this codebase already has a pattern for (`PdfPipelineDiagnostic`,
`AdminImportTraceController`), and optionally the user-facing review screen for `CONFLICTING`/
`INSUFFICIENT` fields specifically, which is a product decision outside this ADR's scope.

**Explicitly not decided here:** the exact rendering format, whether it is persisted or computed
on-demand, and whether/how much of it reaches the end user versus staying an internal diagnostic —
all detailed-design and product decisions.

## 6. Sequencing — extends ADR-005 §10, does not reorder it

**Correction applied:** the previous draft treated OCR acquisition as fully outside this ADR's
concern, blocked on nothing here. The review's point stands: OCR working as an engine and OCR output
being *trusted* financial data must not be allowed to collapse into the same milestone, or the project
repeats — with a new engine — the exact problem this whole ADR exists to close.

ADR-005 §10's sequence, unchanged:

```
contract → ground truth → section/entity persistence → native extraction improvements
   → OCR acquisition → same PositionedText pipeline → AI interpretation for ambiguity
   → deterministic validation → corpus regression
```

This ADR's pieces, per the already-approved phase order and the review's revision to it:

```
Evidence contract (§3)
      ↓
Structure reconstruction hardening
      ↓
Tesseract production acquisition   ← under CONSERVATIVE ACQUISITION SEMANTICS, see below
      ↓
Candidate evidence (§3, populated for both native- and OCR-sourced facts)
      ↓
Statement evidence assessment + routing change (§4b's decision — implemented here, not before;
      the decision is approved as of round 2, the code is not yet written)
      ↓
Cross-source comparison (§4a/§4b — now non-vacuous once the routing change above ships)
      ↓
Financial validation integration
      ↓
Import enforcement (§5)
      ↓
Statement evidence explanation (§5a — a rendering of what the phases above already produce,
      can land alongside or shortly after §5, not a blocking dependency for any earlier phase)
```

**Conservative acquisition semantics for OCR, effective from the moment Tesseract is promoted to
production, independent of whether §4b's dependency is ever resolved:** an OCR-sourced `FieldFact`
does not, by virtue of successful extraction alone, contribute toward `SUPPORTED` any more readily
than the withdrawn source-strength-alone rule in §3 would have for native text. Concretely, `TextSource
== OCR` in a candidate's `acquisitionProvenance` is itself a fact the §3 combining function is aware
of — an `UNCONTESTED` OCR-only candidate (the only shape reachable under §4b's current routing) needs
to clear the same two-of-three bar as any other candidate, and financial validation (§5) carries
correspondingly more weight for it precisely because corroboration is structurally unavailable to it
today. This is the same posture ADR-005 §3 already states the reason for `confidence` existing at
all — "a low-confidence OCR run reaches validation as uncertain rather than as fact" — applied here to
the production milestone boundary itself, not just the data shape.

## 7. Principles carried forward, restated not re-argued

Each of these is already established; this ADR adds no new reasoning for them, only cites where they
already live and confirms this extension is bound by them:

- `PositionedText` remains the common acquisition representation — ADR-005 §3, unchanged.
- OCR remains an acquisition capability, not a second financial parser — ADR-005 §3, unchanged.
- `UNKNOWN`/unestablished evidence must never be treated as agreement — ADR-005 §5's `UNKNOWN` value
  for `zeroTransactionsLegitimate`; restated at field-candidate granularity as `UNCONTESTED ≠ AGREE`
  in §4b, and as `UNCERTAIN` correlation forcing neither agreement nor conflict in §4a.
- Provider confidence is evidence, not the final trust decision — ADR-005 §3's rationale for why
  `confidence` exists at all; restated as binding on `EvidenceStatus` derivation in §3, and extended
  to the OCR production milestone itself in §6.
- Structural provenance strength is evidence, not proof — **new invariant this revision adds**,
  the direct correction from §3: a `COLUMN_HEADERS`-sourced value is a strong observation, not
  automatically a supported one.
- Two observations must be established as the same fact before their values are compared —
  **new invariant this revision adds**, §4a.
- Native/OCR agreement does not by itself prove correctness — stated as a hard invariant in §4b.
- A verification finding's scope, not its mere existence, determines what it may block —
  **new invariant this revision adds**, §5.
- Every material decision this ADR makes must be explainable, not only enforced —
  **new invariant, round 2**, §5a. Not a new decision model — a required rendering of §3–§5's
  existing evidence.
- OCR is invoked when evidence is insufficient, not only when native acquisition returns nothing —
  **new invariant, round 2**, §4b. Approved as a decision with a stated cost tradeoff, not left to
  emerge unstated from implementation.
- Material information must never be silently lost — ADR-005 §11's integrity gate; §5 is this ADR's
  attempt to actually close the enforcement gap ADR-005 named but left open, scoped correctly rather
  than bluntly.
- No arbitrary OCR routing threshold without corpus evidence — ADR-005 §8, unchanged; §3 and §4a
  follow the same discipline for field-evidence and correlation strength respectively.
- No new PDF/OCR library merely for library coverage — ADR-004 §4's per-institution-parser ban,
  generalized; not this ADR's subject matter, restated for completeness.

## 8. Explicitly out of scope for this ADR

- Any actual OCR engine promotion to production — still a separate phase (§6), but now explicitly
  under conservative acquisition semantics from the moment it ships, not unconditionally approved.
- The exact "statement evidence assessment" threshold that triggers OCR under §4b's now-approved
  routing decision — the *policy* (evidence-gated, not zero-runs-gated) and the *cost tradeoff* are
  decided; the precise trigger condition is not.
- Specific confidence/strength threshold values, and the exact §3 combining function beyond
  "no single dimension suffices, at least two of three required, shown to be independent."
- The exact mapping from each verification rule's `suspectedCause` to an implicated scope, and which
  fields are "load-bearing" enough to gate confirm (§5) — detailed-design decisions.
- §5a's exact rendering format, persistence, and how much (if any) reaches the end user versus
  staying an internal diagnostic — detailed-design and product decisions respectively.
- Additional PDF/OCR libraries of any kind.
