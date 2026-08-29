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

### The credit-card identity picture, corrected

The workstream opened on a claim of "100% credit-card identity failure." Measurement showed
otherwise:

| Tier | Real state |
|---|---|
| `EXACT` continuity | 0 / 7 |
| `PROBABLE` continuity | 5 / 7 |
| Bad matches caused by F22 | 2 / 7 (since fixed) |

That reframed the strategic question from *"build identity"* to *"is auto-resolution worth
the collision risk?"* — which is still open, and is the next item below.

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
