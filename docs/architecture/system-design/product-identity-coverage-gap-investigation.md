# Product Identity Coverage Gap — Real-Corpus Investigation

Phase 3 of the PM-directed `ProductIdentityResolver` audit. Phase 1 (`ProductIdentityResolver`/
`ProductIdentity` core-logic read) and Phase 2 (`ProductIdentityResolutionIT`, proving the MATCHED
path end-to-end against a real database) found the matching logic itself sound. This phase asks the
question neither prior phase could: on real bank statements, does the matcher ever actually get
enough information to work?

## Scope verdict (up front)

**(a) — the original concern is resolved. Cross-product identity collision is safe on real data.**
Ran a new probe (`ProductIdentityCorpusProbe`, `backend/src/test/java/com/finora/imports/analysis/`)
against all 21 real statements in the out-of-tree corpus (`~/Downloads/Bank statement/`) — 15
savings/current accounts, 6 credit cards, 32 detected sections total, including every multi-section
composite statement in the corpus. Zero sections matched a sibling section of the same document.
`ProductIdentity`'s discriminator and type-agreement checks, read and traced in Phase 1, held on
every real composite statement tested, not just the synthetic cases the unit tests already covered.

**(b) — a new, real, and larger gap: the matcher usually has nothing to compare.** 26 of the 32 real
sections (81%) produced no strong identity key at all — `ProductIdentity.hash()` requires a full
account/product number with at least 4 digits, and for these sections none was extracted, so
`strongKey` is `null`. Worst case: **every credit-card section in the corpus — 6 files, 13
sections, 100% — had zero identity coverage.** A section with no strong key can never return
`Resolution.MATCHED` against a re-import of the identical statement; it silently creates a new
account every time, forever. This is `ProductIdentity` behaving exactly as designed (Phase 1 already
established that a numberless product falling back to `Match.NONE` is the safe, deliberate outcome,
not a bug) — the defect, if it is one, is that the number is usually never reaching it.

**(c) — this is not a `ProductIdentityResolver` or `ProductIdentity` defect, and no change is being
made to either.** Both classes are pure functions of what they are given, and Phase 1 + Phase 2 +
this phase's own collision check all confirm they act correctly on their inputs. The absence of
those inputs is upstream: `PdfMetadataExtractor`'s account/card-number detection, part of the PDF
extraction subsystem this repository's own prior bug-hunt report already flagged as "the largest and
most defect-prone body of code in the repository," never reviewed line-by-line. This document does
not attempt that review — it only establishes, with real-corpus measurement, that the identity layer
downstream of it is coverage-starved, and by how much.

## Methodology

`ProductIdentityCorpusProbe.probeOne(Path pdf)`:

1. Runs the identical pipeline construction `CorpusProbe` already uses (`PdfPreviewGenerator` wired
   with the real `PdfTextExtractor`/`PdfTableLocator`/`PdfMetadataExtractor`/`ProductAttributeExtractor`,
   only `CategorizationService`/`TransactionRepository` stubbed — DB-irrelevant to what this measures).
2. For each detected section, reads `DetectedAccountInfo.productIdentityHash()` (computed once, at
   extraction time, from the full number — never retained beyond the hash) and builds a
   `ProductIdentity` via `ProductIdentity.stored(...)`, the same call `ImportService.resolveTargetAccount`
   makes.
3. Checks every pair of sections in the document with `ProductIdentity.matches()`.
4. Reports, per section: bank id, detected product type, whether a masked number was found, whether
   a strong key was derived. **The full account number is never available to this probe** — it is
   hashed and discarded upstream, before this class or the real pipeline's own downstream code ever
   sees it — so nothing printed here is more sensitive than what a real `Account` row already stores
   and the review screen already displays.

Run manually (`java -cp ... ProductIdentityCorpusProbe <pdf> [<pdf> ...]`), not as a `@Test` — the
corpus lives outside the repository by policy (see `scripts/corpus-run.py`'s own doc comment), so
this cannot run in CI regardless.

## Results

| Category | Files | Sections | Sections with a strong key | Cross-section collisions |
|---|---:|---:|---:|---:|
| Savings / current | 15 | 15 | 4 | 0 |
| Composite (Shivani_HDFC — Savings + RD + 2 unclassified) | 1 | 4 | 2 | 0 |
| Credit cards | 6 | 13 | 0 | 0 |
| **Total** | **21** | **32** | **6 (19%)** | **0** |

(The Shivani_HDFC row is broken out because it is the corpus's only genuinely composite statement —
its 4 sections and 2 keyed sections are already included in the 32/6 totals above, not double-counted.)

Two sections of Shivani_HDFC — the one classified `RECURRING_DEPOSIT` and one classified `UNKNOWN` —
had no strong key, confirming Phase 1's "termless deposit" and "numberless product" residual risks
are not theoretical: they reproduce on the corpus's own real composite statement.

No document, single-section or composite, produced a false EXACT or PROBABLE match between two
different real sections.

## What this does and does not mean

**Does not mean:** that re-importing a credit-card statement today corrupts or merges data. Every
observed failure mode is `Resolution.NEW` — a new account gets created, never a wrong merge. The
worst measured outcome is a duplicate, user-visible account, not silently wrong transactions or
balances in an existing one.

**Does mean:** for the large majority of real statements in this corpus, and for every credit card
statement without exception, `ProductIdentityResolver`'s whole recognition mechanism is currently
inert. A user re-importing last month's credit card bill would get a second account, not their
existing one updated — repeated indefinitely on every re-import, since nothing here is self-limiting
the way the discriminator bug's duplicate-detection safety net was (Phase 1, `ProductIdentityResolver.java:85-89`)
until at least two duplicates already exist to be flagged.

## Ticketed as BH-061 (2026-08-16)

Formally assigned bug-hunt ID `BH-061`, filed in `docs/project-management/plans/project-plan-v1.0.md`
§4 (P2) — see the plan for current status. Class: operability / data hygiene, not financial
correctness (no balance or transaction total is ever wrong) and not security. Scope for a future fix,
**not started here and not part of this document's own recommendation to act on immediately**:
improve `PdfMetadataExtractor`'s account/card-number extraction coverage, re-run
`ProductIdentityCorpusProbe` against the same corpus, and track the 6/32 baseline this document
establishes as the metric a fix should move.
