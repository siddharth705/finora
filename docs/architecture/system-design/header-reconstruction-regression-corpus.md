# Header reconstruction — regression corpus matrix

**Companion to** [header-reconstruction-design.md](header-reconstruction-design.md). Not a test
file — the actual assertions belong in real JUnit tests against the committed trace corpus and,
where a real ground-truth transaction count exists, against the external ground-truth corpus.
This document is the list an implementation is expected to satisfy, kept in one place so "did this
regress anything" has a fixed answer to check against rather than a re-derivation each time.

## Known failures this design must recover

| Document | Failure (per phase2e1 investigation) | Failure class | Expected after redesign |
|---|---|---|---|
| SBI CC Section 1 | Header partitioned across 2 lines (line 1: 1 column; line 2: other 2, >40pt away) | `HEADER_PARTITION` | Header reconstructs to Date / Transaction Details / Amount; all ~30 real rows recover, verified against the external ground-truth transaction count for this section |
| Statement.pdf (IOB) | Same partition mechanism, 3 lines, refused on the first 2-line attempt | `HEADER_PARTITION` + `TEXT_EXTRACTION_FUSION` (separate layer, §7 of the design) | Recovers only once the `PdfTextExtractor` fusion issue is fixed independently; the header-reconstruction engine alone is necessary but not sufficient here — track as two linked but separately verifiable fixes |
| HSBC DB.pdf | Accepted header `[Date, Balance]` only; `vocabularySignals: [withdrawals, deposits]` present but unmerged, via OCR | `HEADER_PARTITION` (hypothesis — mechanism not fully confirmed, see design §3/§7) | Recovers if the hypothesis holds; if row-grouping is confirmed as the actual cause instead, this document needs a separate `groupIntoRows` fix and should be re-evaluated against the engine only after that |

## Structural guarantee — must NOT change

Every real document in the committed trace corpus and the external ground-truth corpus that
currently produces a correct header (i.e., no `HeaderReconstructionFinding`) must continue to be
resolved entirely by `mergeHeaderLines`, with the new engine never invoked. This includes, at
minimum, the three real HDFC statements and Central Bank of India that motivated
`mergeHeaderLines`'s existing refinement model (P-001), and every one of the ~20 real documents
Phase 2D's row-completeness cross-check found row-complete.

Concretely: an implementation should add a test asserting the reconstruction engine's entry point
is never reached for any currently-passing document — not merely that their output is unchanged
(output could coincidentally match while the new code path was exercised and got lucky). Structural
non-invocation is the guarantee, output equality is the symptom.

## What "recovered" means, precisely

Not "a `HeaderReconstructionFinding` no longer fires." That would be satisfied by a candidate that
looks like a header but produces zero or wrong rows — exactly the failure mode §4 of the design
exists to prevent. "Recovered" means:

1. No `HeaderReconstructionFinding` for the section, AND
2. The section's staged row count matches the external ground-truth expected count for that
   document/entity (SBI has this; IOB and HSBC would need it added to the ground-truth corpus if
   not already present — verify before claiming either recovers, don't assume), AND
3. Spot-checked values (at least date and one amount column) on a sample of recovered rows match
   the real document, not just a plausible-looking count.

## Explicitly out of scope for this matrix

Per the design's §7 non-goals: IOB's extraction-layer fix and HSBC's row-grouping question (if
confirmed) are not header-reconstruction regressions or recoveries — they are prerequisites or
siblings, tracked separately so a partial recovery on IOB or HSBC is never mistaken for the new
engine failing when the actual blocker is a different layer entirely.
