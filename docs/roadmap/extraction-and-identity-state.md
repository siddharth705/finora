# Extraction & identity — workstream state

**Last updated:** 2026-08-29

## Why this file exists

The audit driving this workstream (`extraction-coverage-audit.md`, source of the F-numbered
items) lives **outside this repository**. Before this file, nothing in the repo recorded
what those items were, which had shipped, or — most expensively — which approaches had been
investigated and **rejected**.

That gap has a cost with a worked example. The geometry channel described below was designed
in full, reviewed twice, and approved before measurement showed its motivating premise was
false. Without a durable record, the next investigation starts from the same wrong premise
and repeats the design. Rejections are the part worth writing down; coverage gaps announce
themselves, discarded approaches do not.

This is a state file, not a plan. It records what is true, what was decided, and why.

## Completed

| Item | What it did | Where |
|---|---|---|
| F22 | Bounded card-number capture, fixing a false match; measured real CC identity | PR #535 |
| F21 (vocabulary) | Recognized `Account No` / `Account No.` / `A/c` abbreviations — 4 real documents, 4 banks | PR #551 |
| F21 (banner) | Recognized product-banner account numbers (`SAVINGS ACCOUNT - <number>`) — BOB | PR #559 |
| CC continuity measurement | Corrected the "100% identity failure" claim to its real state | PR #535 |
| Corpus-diff workflow | Established `CorpusProbe` sweep + golden-output snapshot as the regression bar for extraction changes | PR #559 |

### The credit-card identity picture, corrected twice

The workstream opened on a claim of "100% credit-card identity failure." PR #535 measured
that as wrong and reported 0/7 `EXACT`, 5/7 `PROBABLE`, 2/7 previously-false matches.

Those figures were **re-derived from the corpus on 2026-08-29** by running
`ProductIdentityCorpusProbe` at both `08847148` (#535 itself) and current `main`. Output was
identical at both commits, so nothing has drifted since — but the reported figures do not
match the corpus:

| | Reported (#535) | Measured |
|---|---|---|
| Credit-card documents | 7 | **8** |
| `EXACT` continuity | 0 | **0** — confirmed |
| `PROBABLE` continuity | 5 | **6** |
| `NONE` | 2 | 2 (HSBC CC, ICICI CC) |

The reported numbers reproduce exactly if **AU Credit card is excluded** from the
denominator — its mask comes from `CARD_ENDING_DIGITS_IDENTITY`, a prose sentence rather
than a card-number label, so it was scoped out rather than miscounted. AU is nonetheless a
real data point, and one of the two most informative for the collision question below.

`0 EXACT` is confirmed unambiguously: every section in every document reports
`strongKey=(none -- no usable number extracted)`.

That reframed the strategic question from *"build identity"* to *"is auto-resolution worth
the collision risk?"* — still open, and the next item below.

## Deferred, with reasons

### Geometry-based column extraction — PARKED

Full design at
[`docs/superpowers/specs/2026-08-29-column-aligned-account-number-design.md`](../superpowers/specs/2026-08-29-column-aligned-account-number-design.md).

Premise was that BOB and HSBC print their account number *only* inside multi-column tables,
which `PdfTableLocator.lineOf` flattens to single spaces. Measurement disproved it: both
documents also print the value on ordinary label-adjacent lines, and a probe confirmed it
already reaches `PdfMetadataExtractor` through `auxiliaryText`. The fix was one regex.

**Do not revive without a genuinely table-only document.** The design doc retains material
that stays true regardless: why `lineOf` cannot be changed safely (one of its eight call
sites builds user-visible narration for every bank), why both originally-proposed fix shapes
were rejected, the HSBC card-number hazard, and measured span geometry showing that
**containment vs. overlap** — not tolerance windows — is the real implementation trap.

### `PdfTableLocator` decomposition — DEFERRED

4862 lines, described in this repo's own bug-hunt reporting as its most defect-prone body of
code. The safe boundary is already identified in the design doc's Out of Scope section:
the 23 regex constants, the records, and the two self-contained inference capabilities are
mechanically extractable (~4862 → ~2600); `locateAll`'s ~800-line loop, whose correctness
lives in branch ordering and five threaded mutable variables, is not.

Deferred because it is architectural debt reduction, not coverage improvement, and the
identity and extraction-quality backlog has clearer user impact.

### Sanjay SBI branch fields — DEFERRED

`Branch Code` / `Branch Name` on a real SBI savings statement use a leading-colon
column-reordering shape. Scoped out deliberately as structural rather than a vocabulary gap,
and deferred on the same grounds as the tabular account-number work above.

One caveat attached at the time of writing, because it costs nothing to note and something
to rediscover: the class this was compared to did not survive measurement. BOB and HSBC were
also classified as structural, and both turned out to be vocabulary gaps whose values already
reached `PdfMetadataExtractor` — the fix was one regex, not a new extraction channel. That
does not make this classification wrong; SBI's shape is a different one and was assessed on
its own. It does mean the classification is **unverified**, and that the check which settled
the last two is cheap: probe whether the branch value reaches `auxiliaryText`. If it does,
this is vocabulary. If it does not, the structural call is confirmed and better evidenced
than it is today.

### HSBC account number — DEFERRED

Its value reaches the extractor and could be matched, but the document is
`LAYOUT_UNSUPPORTED` with **zero rows extracted**. Resolving its account number would
identify an account with no transactions. Its banner is also value-then-label, a different
pattern family from BOB's. The ticket is its layout, not its metadata.

## Next strategic item — credit-card collision study

The gating question for everything above: **should credit cards ever be granted `EXACT`
continuity?** No implementation should relax the strong-key floor before empirical collision
evidence exists. Three questions the study must answer:

1. How many customer-discriminating digits actually survive masking?
2. How often do two cards from the same issuer collide on what survives?
3. Are SBI-style masks structurally different from AU/Kotak/HDFC-style masks?

Question 3 is load-bearing: if mask structure varies by issuer, a single global `EXACT`
policy is wrong whatever the aggregate collision rate turns out to be.

### Study results — Phases 1 and 3 (2026-08-29)

Phase 1 (entropy census) and Phase 3 (risk model) were run. Phase 2 was resolved as a design
decision rather than a measurement (see below). Phase 4 deliberately not started.

#### Conclusion 1 — measured corpus outcome

Mask structures vary widely; discriminating power does not. Each visible digit was mapped to
its absolute card position and classified BIN (1–6), account (7–15), or Luhn check (16).

| Issuer | Visible | BIN | Account | Check | **d_eff** |
|---|---|---|---|---|---|
| Axis | 10 | 6 | 3 | 1 | **4** |
| HDFC | 10 | 6 | 3 | 1 | **4** |
| IndusInd | 8 | 4 | 3 | 1 | **4** |
| Kotak | 8 | 4 | 3 | 1 | **4** |
| AU | 4 | 0 | 3 | 1 | **4** |
| **SBI** | 2 | 0 | 1 | 1 | **2** |

**Five issuers converge on `d_eff = 4` despite masks that look nothing alike.** Axis shows
ten visible digits and AU shows four, yet they carry identical discriminating power:
everything Axis reveals beyond the last four is BIN, which identifies the *product*, not the
customer, and the identity key already carries bank separately. **Mask verbosity is
cosmetic.**

SBI sits at `d_eff = 2` — a **100× difference** from every other issuer measured. Treating
SBI under the same rule as HDFC would be difficult to justify against these numbers.

#### Conclusion 2 — policy implication

Any future `EXACT` promotion must be gated on **effective entropy, computed from the observed
mask**, never on visible-digit count and never on an issuer table.

This resolves Phase 2. Issuer tables were rejected: they assume issuer-wide consistency from
n=1, need maintenance, break silently when a bank changes format, and cannot represent an
issuer with several products. Runtime measurement needs only the observed mask — count
visible positions, classify each by position — which is exactly the census logic above, so
the study's Phase 1 *is* the implementation.

The current code cannot express this at all: it stores a masked string and compares equality,
with no representation of how much entropy survived. That is the first thing any `EXACT` work
would have to change.

#### Conclusion 3 — open question

**Collision probabilities are modeled, not measured.** Under the assumption that surviving
digits are approximately uniform and independent within a bank/product population,
`P(false EXACT) ≈ C(k,2) × 10^-d` for a user holding *k* same-product cards:

| d | k=2 | k=3 | k=5 |
|---|---|---|---|
| 4 | 1.0×10⁻⁴ | 3.0×10⁻⁴ | 1.0×10⁻³ |
| 2 | 1.0×10⁻² | 3.0×10⁻² | 1.0×10⁻¹ |

Required `d` to meet a given tolerance, against a corpus maximum of 4:

| Tolerance | k=2 | k=3 | k=5 | Achievable? |
|---|---|---|---|---|
| 10⁻³ | 3 | 4 | 4 | yes, for `d_eff = 4` issuers |
| 10⁻⁴ | 4 | 5 | 5 | only at k=2 |
| 10⁻⁶ | 6 | 7 | 7 | no |

**The uniformity assumption is the model's largest uncertainty, and the direction of its
error is unknown.** Real issuers may allocate account numbers sequentially or in blocks. Two
cards issued together with adjacent numbers differ in their trailing digits *by
construction*, which would make these figures over-state risk; block allocation with
recycling could produce repeats at a rate above uniform, which would make them under-state
it. Both are plausible and nothing in the corpus distinguishes them. **Do not treat these as
worst-case bounds in either direction.**

Secondary limit: still n=1 statement per issuer, so this establishes mask structure per
issuer, not that an issuer always masks this way. Runtime measurement makes that limit
non-blocking rather than resolved.

#### Phase 4 — candidate supplementary signals, enumerated (not designed)

Reached only because Phase 3 shows no observed mask meets a 10⁻⁴-or-stricter tolerance.
Enumeration only: no signal below has been designed, and none should be built without its
own scoping.

Availability was **measured**, not assumed, across the 8 credit-card documents (10 sections):

| Signal | Sections populated |
|---|---|
| Masked number | 6 / 10 |
| Credit limit | 4 / 10 |
| Payment due date | 3 / 10 |
| Statement period | **1 / 10** |
| Total amount due | 1 / 10 |
| Opening balance | **0 / 10** |
| Closing balance | **0 / 10** |

**Correction to an earlier claim in this workstream.** Statement-period adjacency and
balance-chain continuity were described as signals "the pipeline already computes." For
credit cards it computes neither. `openingBalance` is derived from a running-balance column,
which credit-card statements do not have — they print a transaction list and a payment
summary. That claim was extrapolated from bank statements and is wrong for this product.

**Group 1 — available today, all weak.**

| Signal | Avail. | Est. power | Stability |
|---|---|---|---|
| Payment due date (day-of-month) | 3/10 | ~1.4 digits (≈28 values) | stable; billing cycle fixed at issuance |
| Credit limit | 4/10 | very low; clusters on round values | drifts as limits increase |
| Total amount due | 1/10 | none | changes every month by design — not an identity signal |

**Group 2 — would be strong, but absent.** Statement-period adjacency (1/10; billing cycle
is customer-specific and would compose well with the mask) and balance-chain continuity
(0/10; highest power of any candidate if present).

**Group 3 — a different problem.** Transaction-set overlap is not a `DetectedAccountInfo`
field and needs staged rows compared against stored ones. It is decisive for *re-uploading
the same statement* and useless for month N → N+1. Phase 4 must not conflate the two.

**Group 4 — explicit non-signals**, recorded so they are not re-proposed:

- *Account holder name.* Every card a user owns carries their name, so it has zero power for
  the only collision that matters — one of their cards against another. Accounts are
  per-user.
- *Card product name / BIN.* Discriminates product, not customer; already counted in Phase 1.

**Viability caveat.** The two documents most needing supplementary signals — HSBC CC and
ICICI CC, the `NONE` cases with no mask at all — have almost nothing else either. HSBC has
zero populated fields; ICICI has only credit limit.

**Unresolved, and it decides Group 2's fate.** This probe conflates *absent from the
document* with *present but unextracted*. Statement periods are printed on essentially every
credit-card statement, so 1/10 is far more likely an extraction gap (recoverable, F-item
work) than genuine absence; balances at 0/10 are probably genuine. Separating the two is
exactly what the extraction-loss ledger below would answer — the second question in this
workstream that a ledger would have answered immediately.

#### What the study settled

The gating question is no longer *"can we extract enough of the card number?"* — it is
**"what collision risk is Finora willing to accept for automatic identity resolution?"** The
decision point sits entirely between 10⁻³ and 10⁻⁴. Above it, masked-card `EXACT` is
defensible for five of six issuers; below it, no observed mask qualifies and supplementary
signals (Phase 4) become mandatory rather than optional. SBI qualifies at no tolerance
considered.

## Scoped and ready — statement-period extraction gap

**F-number not yet assigned.** It belongs to the out-of-tree `extraction-coverage-audit.md`
sequence (F1, F21, F22, F23 are known to exist). Assign the next free number *from that file*,
not by guessing — parallel sessions on this repo have collided on shared numbering before.

### Finding

Phase 4 measured statement period as populated in 1 of 10 credit-card sections and flagged
that it could not distinguish *absent from the document* from *present but unextracted*. That
was checked directly against the documents' text.

**It is an extraction gap.** Six of eight credit-card documents print a statement or billing
period; one already works; five fail for identified, separate reasons.

| Document | Prints a period | Failure | Class |
|---|---|---|---|
| Kotak | yes | — works today | — |
| Axis | yes, `Statement Period` as a grid header | value not on the label's line | layout |
| IndusInd | yes, label present | value placement | layout |
| HDFC | yes, **`Billing Period`** | label absent from vocabulary | vocabulary |
| ICICI | yes, `Statement period : <Month> d, yyyy to …` | `MMMM d, yyyy` absent from `DATE_FORMATS` | date format |
| SBI | yes, `Statement Period: d MMM yy to d MMM yy   <trailing>` | `d MMM yy` absent **and** trailing content on the line | format + parse |
| AU | **no** — statement *date* only | n/a | genuine absence |
| HSBC CC | **no** — prose mentions only | n/a | genuine absence |

### Mechanism, verified empirically

`parsePeriod` (PdfMetadataExtractor) splits on `\s+to\s+` and calls `LocalDate.parse` on each
half, which requires the **entire string** to match a format. Both dates must parse or the
field stays null. Tested against the real printed values and the real `DATE_FORMATS` list:

- ICICI start (`<FullMonth> d, yyyy`) — fails; the list has `MMM`, not `MMMM`
- SBI end with trailing content — fails
- SBI end with trailing content removed — **still fails**; 2-digit year, list has only `yyyy`
- Control (`d MMM, yyyy`) — parses, confirming the test harness

SBI therefore has two independent gaps; fixing trailing content alone does not recover it.

### Scope

Four sub-gaps, addressable independently:

1. **Date formats** — `MMMM d, yyyy` (ICICI) and a 2-digit-year form (SBI).
2. **Trailing-content tolerance** — extract dates from the captured span rather than requiring
   each split half to parse whole.
3. **Vocabulary** — `Billing Period` alongside `Statement Period`.
4. **Grid layout** — Axis and IndusInd place the value off the label's line; the grid path
   already exists for due date and credit limit.

**Out of scope:** AU and HSBC CC. Neither prints a period, so no extraction change reaches
them. Inferring a period from a statement date plus a cycle length would be *deriving* a value
the document never stated — against this codebase's standing discipline.

### Principal hazard

`DATE_FORMATS` is a **shared, ordered** array used by every date field in the class, not just
the period. A 2-digit-year format is inherently ambiguous and, placed early, could shadow a
correct parse for an unrelated field on an unrelated document. Sub-gap 1 is the smallest
change here and carries the largest blast radius. Treat the golden-output corpus as the gate,
and consider scoping the lenient format to period parsing rather than adding it to the shared
array.

### Acceptance

Statement period populated for Axis, IndusInd, HDFC, ICICI, SBI; full suite green; golden
corpus diff shows only the intended documents changing; real-corpus sweep diffed against the
merge target.

### Why it matters beyond coverage

Statement period is the strongest *available* supplementary identity signal (Phase 4). But
note two limits recorded there: only the **cycle day** is stable across months (~1.4 digits),
and it is **not independent of payment due date** — both derive from the same billing cycle,
so they must be counted once, not twice.

## Candidate work, not yet scheduled

- **Extraction-loss ledger.** Per field, record why a value was not produced — absent from
  document, present but unmatched, matched but rejected, or overwritten later. Turns future
  F-item investigations from archaeology into a lookup. This session spent most of its
  effort answering exactly the question such a ledger would have answered immediately.
- **False-positive sweep.** F22 surfaced almost accidentally. A dedicated sweep for phone
  numbers, statement IDs, card numbers, and balances captured as account identifiers is
  likely higher value than more coverage work — coverage gaps are visible, false positives
  are not.
- **Longitudinal re-import testing.** The real business question is not "did we extract the
  account number" but "do month N and month N+1 resolve to the same product." A re-import
  corpus measures that directly.

## Working notes

**The corpus regression bar for any extraction change.** The repo already has the machinery;
use it rather than building another:

- `GoldenOutputSnapshotTest` diffs full pipeline output per committed trace and names what
  changed. Regenerate deliberately with `-Dfinora.golden.regenerate=true` and commit the
  snapshot in the same change.
- A `CorpusProbe` sweep across the real out-of-tree corpus catches what no committed trace
  covers.

**Baseline against the right commit.** `main` moves during implementation. A baseline taken
before a merge will attribute another session's changes to yours — this happened in PR #559,
where a pre-merge baseline showed 8 changed documents and the correct baseline showed 1.
Rebuild the baseline from the merge target, not from where you started.

**Never let a corpus value into the repo or a PR description.** Compare by hash when you
need to prove a specific value resolved correctly.
