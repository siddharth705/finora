# Import pipeline — corpus regression suite

**Implements:** Phase 2 of the import-pipeline bug-fix roadmap. **Plan:**
[2026-08-31-import-corpus-regression-suite.md](../../superpowers/plans/2026-08-31-import-corpus-regression-suite.md).

This is the operating manual for the tooling in `scripts/corpus-run.py`, `scripts/corpus-diff.py`,
`scripts/run-corpus-ground-truth.py`, `scripts/ground-truth-match.py`, and
`backend/.../analysis/CorpusProbe.java`. It answers two questions a future parser-touching PR needs
answered: what does CI actually check, and what do I need to run myself before opening the PR.

## The three tiers

The real corpus does **not** grow into a mandatory, ever-larger merge gate. It never was one — CI
has no access to it, by policy, permanently (the corpus is real customer statements and lives
outside the working tree). The distinction that actually matters is what each tier is *for*:

**Tier A — canonical regression suite.** Small, synthetic, committed, CI-gated, permanent.
`backend/src/test/resources/synthetic-corpus-regression/` (regenerated at build time from
`SyntheticFixtureGenerator.java` — its PDF is never committed; see that class's own doc comment for
why). One fixture per known parser failure mode, not one per document ever seen. This is what
actually prevents a fixed bug from coming back, and it is fast and cheap enough to run on every PR
— it already does, as the "Synthetic corpus regression (mechanism-gated)" CI step.

**Tier B — real corpus, validation and discovery.** Large, growing, real, never committed, never
CI-gated. Used for: periodic/release validation (run the whole thing, review the diff, before a
parser-affecting release), and mining for bug classes nobody has found yet. Not run on every PR as
a rule — run it when a change plausibly affects the corpus (touches `PdfTableLocator`,
`TransactionNormalizer`, header/footer/continuation logic, or anything else in the parse path), using
judgment about scope rather than a mechanical "every PR, every document" policy. At 27 documents
today that distinction barely matters; it is stated now so it still holds if this grows to hundreds.

**Tier C — new real documents.** Every new statement a user's import surfaces a bug on is a Tier C
document until: the bug is fixed, and a Tier A fixture is added reproducing that failure mode. After
that, the document itself has no further special status — it doesn't need to be "retired" or marked
inactive, because Tier B never required active per-document tracking in the first place. It stays in
Tier B, useful for future validation and discovery, same as anything else there. (The same real
document can expose a second, unrelated bug months later — that's a Tier B/C discovery, not a
regression of the Tier A fixture the first bug produced.)

No lifecycle machinery, retirement rules, or active/inactive bookkeeping exists for Tier B, and none
is planned. If the corpus grows enough that "run the whole thing" stops being practical, sampling
strategy is a problem to solve then, against real operational cost data — not something to build
speculatively now.

## What CI actually gates

One step, `Synthetic corpus regression (mechanism-gated)`, right after the backend test suite:

```bash
cd backend && java -cp "target/test-classes:target/classes:$(cat target/corpus-classpath.txt)" \
    com.finora.imports.analysis.SyntheticFixtureGenerator
cd ..
python3 scripts/run-corpus-ground-truth.py \
    backend/target/synthetic-corpus-regression \
    --ground-truth backend/target/synthetic-corpus-regression/ground-truth \
    --allow-in-repo-synthetic-corpus
```

Non-zero exit fails the build. Two Python self-test steps also run in CI
(`test-corpus-diff.py`, `test-ground-truth-match.py`) — these test the *mechanism* against
hand-built synthetic records, not any real or Tier A document; they exist because the mechanism
needs testing somehow, and the real corpus can't be the way that happens in CI.

**Adding a Tier A fixture for a newly-fixed bug:** follow `SyntheticFixtureGenerator.definition()`'s
shape — declare a `SyntheticStatementDefinition` that reproduces the failure mode (not the real
document; a plainly-fictional statement with the same structural shape: same footer pattern, same
continuation-boundary condition, same wrapped-header layout, whatever the bug actually was), add a
second `main()`-invocable generator (or extend the existing one to accept a fixture name), and add
its own entry alongside `mechanism-proof` in the CI step. Do this as part of fixing the bug, in the
same PR — the fixture is the regression test for the fix, not a follow-up task.

## What you run locally before a parser-affecting PR

If your change touches the parse path (Tier B is relevant): pick a corpus snapshot from before your
change and one from after, then:

The real corpus is two PDF subdirectories (`Savings accounts`, `Credit cards`) under one root, with
`ground-truth` alongside them at that root — `corpus-run.py` reads one directory of loose PDFs at a
time (not recursive), so run it once per subdirectory:

```bash
# Before your change (or on main):
python3 scripts/corpus-run.py <path to the real corpus>/Savings accounts -o /tmp/before-savings.jsonl
python3 scripts/corpus-run.py <path to the real corpus>/Credit cards -o /tmp/before-cc.jsonl

# After your change:
python3 scripts/corpus-run.py <path to the real corpus>/Savings accounts -o /tmp/after-savings.jsonl
python3 scripts/corpus-run.py <path to the real corpus>/Credit cards -o /tmp/after-cc.jsonl

# What moved, and in which direction:
python3 scripts/corpus-diff.py /tmp/before-savings.jsonl /tmp/after-savings.jsonl
python3 scripts/corpus-diff.py /tmp/before-cc.jsonl /tmp/after-cc.jsonl
```

`corpus-diff.py` reports `regression` / `review` / `improvement` per document, including the new
`descriptionDrift` dimension (Task 7): a hash-level signal that a section's row descriptions
changed, at some rate, without ever printing the actual text (a SHA-256 digest per row, one-way —
see `CorpusProbe.descriptionHashesOf`'s doc comment). It's always `review` severity, never an
automatic pass or fail — a description hash differing could be the fix or a new corruption, and only
opening the flagged document locally answers which. When it's non-zero, open the real PDF, compare
the flagged rows by eye, and paste a short summary (not the raw customer text) into the PR
description: what changed, and whether it's the intended fix.

Where ground truth is established (`<stem>.json`, 21 of the 27 real documents today), also run:

```bash
python3 scripts/run-corpus-ground-truth.py <path to the real corpus>/Savings accounts \
    --ground-truth <path to the real corpus>/ground-truth
python3 scripts/run-corpus-ground-truth.py <path to the real corpus>/Credit cards \
    --ground-truth <path to the real corpus>/ground-truth
```

The `ground-truth` directory sits at the top level, one above the two PDF subdirectories --
`--ground-truth` must be passed explicitly (the default, `<corpus>/ground-truth`, silently finds
nothing and reports every document `NOT_ESTABLISHED` when pointed at a subdirectory).

This asserts, per established document, transaction count/product/identity (as it always has) plus
— since Task 2 — opening/closing balance, statement period, and credit-card summary (credit limit,
total amount due, payment due date), wherever the ground-truth file asserts them. A `FAIL` here is a
real, specific, per-document defect; paste the verdict table into the PR description alongside the
diff summary.

## A known gap, not yet closed

The 21 existing real ground-truth files do not yet have `expectedOpeningBalance` /
`expectedClosingBalance` / `expectedStatementPeriodStart` / `expectedStatementPeriodEnd` /
`expectedCreditLimit` / `expectedTotalAmountDue` / `expectedPaymentDueDate` populated — the schema
and the matcher support them (Task 2), but establishing them means reading each real PDF by hand and
writing down what it actually says, never copying `CorpusProbe`'s own extracted value for that field
(ground truth must never be derived from parser output). This is real per-document work, tracked as
follow-up, not something this phase's own merge depends on.
