# Column-Aligned Account Number Extraction — Design

**Date:** 2026-08-29
**Status:** Approved design, not yet implemented
**Origin:** Gap found while implementing F21 (account-number label vocabulary) from the
extraction coverage audit.

## Problem

Two real statements in the 26-document corpus print their account number only inside a
genuine multi-column table, never as a `Label: Value` line:

- **BOB** — under an `Account Related Other Information` / `NOMINEE DETAILS` heading, with
  columns `SR.NO. | ACCOUNT TYPE | ACCOUNT NUMBER | NOMINEE NAME(S)`. One data row.
- **HSBC** — a `Summary of Your Portfolio` block containing **two** sub-tables,
  `Deposits and Investments` and `Borrowings`, each carrying its own `Account Number`
  column.

In both, the label sits on a header row and the value on a data row below it at the same
horizontal position. Every existing tier in `PdfMetadataExtractor.extract` is line-oriented
and label-adjacent, so none of them can reach either value.

### Why the obvious fix does not work

A character-column-alignment technique (find the label's character index in its line, look
for a value near that index on the next line) was written and verified against
`pdftotext -layout` output for both files. It failed against the real pipeline.

`PdfTableLocator.lineOf` (PdfTableLocator.java:3933) builds every line Finora's extractors
ever see by joining a row's runs with **exactly one space**, discarding the real horizontal
gaps:

```java
private String lineOf(List<PositionedText> row) {
    StringBuilder line = new StringBuilder();
    for (PositionedText t : row) {
        if (!line.isEmpty()) line.append(' ');
        line.append(t.text());
    }
    return line.toString();
}
```

The data is not lost, but all column-position information is. Any technique that reads
column position out of the flattened string is working against text that never had it.

### Why `lineOf` is not the place to fix it

Eight call sites. Seven feed regex gates or equality comparisons; one
(PdfTableLocator.java:4815) builds `TwoLineBlock` narration — **user-visible transaction
description text, for every bank in the corpus**.

Two further findings ruled out both originally-proposed shapes:

1. **Rerouting auxiliary-text construction to a gap-preserving builder** is not a
   contained change. Auxiliary text is not primarily built by `rowsToLines`; it is
   accumulated by `pendingAuxiliary.add(rowLine)` at roughly fourteen sites inside
   `locateAll`'s ~800-line main loop, where `rowLine` is the *same string* every regex gate
   in that loop tests. There is no seam to redirect.

2. **Making `lineOf` gap-proportional** has a concrete regression vector beyond narration
   whitespace. Most gate patterns are whitespace-tolerant (`\s+`, `.*`), but
   `MITC_SECTION_MARKER` (PdfTableLocator.java:366) matches the literal string
   `MOST IMPORTANT TERMS AND CONDITIONS`. Multi-space joining breaks it silently, and it is
   unlikely to be the only literal-space pattern.

`PdfTableLocator` is 4862 lines and is described in this repo's own bug-hunt reporting as
the largest and most defect-prone body of code in the repository. Neither shape is worth
that exposure for one field on two documents.

## Approach

Do not reformat any string. Hand the geometry to the metadata extractor as a **second,
additive channel**.

`PositionedText` already carries `x` and `width` (so `endX()` gives the right edge), and
`PdfPreviewGenerator` already holds the document's raw `List<PositionedText>` at
PdfPreviewGenerator.java:192 before calling `locateAll` at line 202. The coordinates are
therefore available at the exact call site that invokes metadata extraction — no change
inside `PdfTableLocator` is required at all.

This is strictly more accurate than the character-index technique it replaces: it aligns on
true coordinates rather than inferring them from a rendering.

### Rejected alternatives

| Approach | Why rejected |
|---|---|
| Reroute auxiliary construction to a parallel gap-preserving builder | No seam; ~14 mutation sites inside an 800-line loop sharing state with every regex gate |
| Make `lineOf` gap-proportional | Touches user-visible narration for every bank; silently breaks literal-space patterns such as `MITC_SECTION_MARKER` |

## Design

### 1. The seam

Add an overload to `PdfMetadataExtractor`:

```java
public ExtractedMetadata extract(List<String> preTableLines,
                                 List<List<PositionedText>> geometricRows,
                                 String sectionProduct,
                                 DocumentContext ctx)
```

The existing two-arg `extract(preTableLines, ctx)` (PdfMetadataExtractor.java:339)
delegates to it with an empty list and a null product. The existing one-arg
`extract(preTableLines)` (line 331) is unchanged and keeps delegating as it does today.

`PdfPreviewGenerator.sharedFacts` (line 623) passes the document's grouped rows and the
section's classified product.

All 60+ existing `PdfMetadataExtractorTest` cases call the **one-arg** form, placing them
two delegation levels away from the new code.

### 2. The algorithm

Runs only when `accountNumberMasked == null` after every existing text-based tier.

1. **Find header cells.** Scan rows for a cell matching the shared account-number label
   vocabulary (see below). Require the row to carry **at least 3 cells**.

   This rejects HSBC's prose false positive — a disclaimer bullet reading
   `use your Account Number/PhoneBanking Number (PBN)...` is a single long run in a
   one-cell row and never becomes a candidate. BOB's real header has 4 cells; HSBC's has 6.

2. **Find the aligned value.** Examine the next 1–2 rows (two, because HSBC's header wraps
   onto a `/ Unit` continuation line). Select cells whose horizontal span
   `[x, endX()]` **overlaps** the header cell's span.

   Overlap, not `nearestColumn` (PdfTableLocator.java:3912): that helper is uncapped and
   will bind an arbitrarily distant cell — the mechanism behind the ICICI Savings header
   defect. Overlap is bounded and yields nothing when nothing lines up.

   **Strict overlap, and deliberately not overlap-with-tolerance.** The obvious future
   modification to this step is a tolerance window — ±5pt, or nearest overlapping column, or
   center-distance ranking — most likely proposed the first time a document *nearly* aligns.
   It should be rejected. Strict overlap is a bounded geometric test: the cell either
   occupies the label's horizontal span or it does not, and "does not" is answerable without
   ranking. Any tolerance converts it into a nearest-neighbour search, which reintroduces
   precisely the class of distant-column misbinding that motivated this design, and does so
   in the one place where a wrong answer is silently adopted as account identity. A document
   that fails strict overlap should widen this feature's evidence base by being examined, not
   widen this feature's matching region.

3. **Shape test.** The value must match a separator-tolerant account shape,
   `\d[\d-]{3,}\d|\d{4,}` — reused from `PdfTableLocator.ACCOUNT_NUMBER_IN_MARKER`
   (line 198), whose doc comment cites HSBC's own hyphen-separated format. Values matching
   the 4-groups-of-4-digits card shape are **explicitly rejected**.

4. **Disambiguate.** If more than one candidate survives, compare each data row's leading
   cell against `sectionProduct`. If that does not resolve to exactly one candidate,
   **return null.** No guessing — the same discipline `BalanceSequenceResolver` follows
   when day ordering cannot be determined.

5. **Zero-width guard.** If the header cell's `width` is 0, abstain immediately and record
   nothing. `PositionedText.width` defaults to 0 when unmeasured, making `endX() == x`, so
   overlap would degenerate to point equality and the tier would do nothing while appearing
   to run. No measured geometry means no reliable answer; a tolerance-window fallback would
   invent precision the input does not carry.

The tier must never throw. Degenerate geometry — empty rows, a header in the last row with
nothing below it — returns null. A metadata tier that throws would fail an import that
succeeds today.

#### Shared label vocabulary

The geometry tier must not carry its own copy of the label vocabulary. If it did, a label
added to a text tier by a later F21-style fix would be recognized line-wise but not
column-wise, or the reverse — a drift that would surface as an inexplicable per-bank gap.

There is currently no single source to reuse. The vocabulary is spread across five patterns
that each bundle label text *with* positional structure: `ACCOUNT_NUMBER` (line 46),
`ACCOUNT_NUMBER_TRAILING_LABEL` (89), `STATEMENT_OF_ACCOUNT_SAME_LINE` (98),
`STATEMENT_OF_ACCOUNT_LABEL_ONLY` (105), and `CARD_NUMBER_LABEL` (110). The geometry tier
needs the vocabulary *without* the positional structure, because in a table the position is
geometric rather than textual.

So: extract the label alternation into a shared constant — an account-number label
vocabulary holding `Account Number` and `Statement of Account` — and have both the existing
patterns and the geometry tier compose from it.

Two constraints on that extraction:

- **It must not change any compiled pattern.** Replacing the literal in
  `labelPattern("Account Number")` with a constant of identical text yields an identical
  compiled regex. Any rewrite that would alter a pattern's matching behavior is out of scope
  here and belongs in its own change.
- **`CARD_NUMBER_LABEL` is excluded.** It includes `Account Number` in its own alternation,
  but it also carries card vocabulary, and feeding that to the geometry tier would surface
  exactly the card values step 3 exists to reject. The shared constant is account-only.

The geometry tier consumes the full account vocabulary rather than a `Account Number`-only
subset. A label worth recognizing on a text line is equally valid on a header row, and
consuming the full set is what makes the drift-prevention real rather than nominal.

#### Why HSBC resolves

Two independent mechanisms resolve it, either alone sufficient:

- Step 3 rejects the `Borrowings` value on card shape before disambiguation runs.
- Step 4 discriminates `Savings Account` from `Credit Card` on the leading cell.

This matters for more than redundancy. Without step 3, the `Borrowings` row's value would be
a **credit card number**, and adopting it would write a card number into
`accountNumberMasked` and `accountNumberFullForHashingOnly` — which feeds account hashing.
That is a correctness bug, not a missed field, and it is the design's hardest requirement.

#### Known limit

The disambiguation rule is designed against exactly one ambiguous document. A bank
presenting two genuine deposit accounts in one summary table would reach the abstain path.
That is designed behavior; the `_AMBIGUOUS` telemetry below is what would surface it.

### 3. Telemetry

`ctx.record(...)`, null-guarded, matching the convention at PdfMetadataExtractor.java:450.

| Outcome | Recorded |
|---|---|
| Resolved | `COLUMN_ALIGNED_ACCOUNT_NUMBER` |
| Two or more candidates, product did not discriminate | `COLUMN_ALIGNED_ACCOUNT_NUMBER_AMBIGUOUS` |
| No candidate | nothing |
| **Skipped — a text tier already populated `accountNumberMasked`** | **nothing** |

The last row is a required acceptance criterion, not an implementation detail. The tier never
runs when a text tier has already resolved the value, and it must record nothing in that
case. Recording on a skipped run would produce metrics asserting
`COLUMN_ALIGNED_ACCOUNT_NUMBER` for documents where a text tier produced the value and the
geometry tier never participated — making the capability appear to carry documents it had no
part in, and corrupting the only evidence available for deciding whether this feature earns
its keep. The precedence test covers the extraction path; this covers the telemetry contract,
and they are separately assertable.

The ambiguous code distinguishes "no such table here" from "a table we could see and
refused to guess at" — the signal that would later show whether the abstain rule is too
strict, without shipping a guess to find out. The silent case is the normal state of 24 of
26 documents; recording it would be noise.

Both are stable machine codes carrying **no customer data**. The matched value and the
matched label text are never recorded — the discipline `DroppedRowCandidate` already states
for itself (PdfTableLocator.java:607).

### 4. Testing

**New capability test file**, following the repo's existing per-capability convention:
`ColumnAlignedAccountNumberPdfPreviewGeneratorTest.java`, from hand-built `PositionedText`
fixtures.

| Case | Fixture | Expected |
|---|---|---|
| BOB shape | 4-cell header, 4-cell data row aligned under the label | resolves |
| HSBC shape | 6-cell header, wrapped continuation line, data row | resolves the deposits value |
| Card rejection | second sub-table, 4x4-digit value | never becomes a candidate |
| Prose guard | 1-cell row containing the label inside a sentence | does not fire |
| Ambiguity abstain | two candidates, no discriminating product | returns null |
| Zero-width guard | header cell with `width == 0` | abstains, records nothing |
| Precedence | a text tier already resolved the number | geometry tier never runs |
| Precedence telemetry | as above, with a table present that *would* have matched | records nothing |
| Vocabulary sharing | a label added to the shared constant | recognized by the geometry tier without further change |
| Degenerate input | header in last row, nothing below | returns null, does not throw |

**Fixture digits must be altered.** Structurally identical to the real documents — same
length, same separator placement, same 4x4 card grouping — but never the real values. This
repo has embedded real unredacted account values in fixtures and comments three times; the
pre-commit hook caught two and missed one, which reached a pushed PR. The geometry is what
is under test; the specific digits are irrelevant to it. The same rule applies to the PR
body: report shape and pass/fail, never the literal value.

**Delegation invariance.** One test asserting `extract(lines)` and
`extract(lines, List.of(), null, ctx)` produce identical `ExtractedMetadata`. This protects
the existing 60+ tests structurally rather than by assumption, and fails loudly if the
delegation default is ever changed.

**Precedence is an enforced test, not a documented assumption.** The safety argument for the
agreed regression bar rests entirely on step 5 of the cascade ordering. If the tier were
reordered ahead of a text tier, the bar would not catch the resulting regressions — so the
precedence case above is named and explicit.

### 5. Acceptance bar

Agreed scope for this change:

- Full backend test suite green.
- `CorpusProbe` run manually against the two real out-of-tree files, both resolving.

Not committed as tests — the PDFs live outside the repo. A full corpus field-by-field diff
was considered and deliberately not required, on the grounds that the change is additive and
last in the cascade.

Note that the shared-vocabulary extraction is the one part of this change that touches
existing code paths. It is constrained to be compiled-pattern-identical, and the 60+ existing
`PdfMetadataExtractorTest` cases exercise those patterns directly — so the suite is a real
check on that refactor rather than a formality. If any of them fail, the extraction changed
behavior and is wrong.

## Out of scope

- **Other fields.** The same mechanism could extract IFSC, branch, or holder name from
  tables. It will not. Two documents, one field; anything further is speculation without
  evidence to design against.
- **Splitting `PdfTableLocator`.** Worth doing (constants, records, and the two
  self-contained inference capabilities are safely extractable; `locateAll`'s stateful main
  loop is not) but as its own PR under its own regression bar. Bundling it here would make
  any corpus diff unattributable to a cause. The two are independent — this design barely
  touches that file.
- **OCR-sourced documents.** Recognized runs do carry widths, so this tier could fire on
  one, but no OCR document in the corpus exercises it. Untested, not claimed.

## Cost/benefit

This fixes one field on 2 of 26 corpus documents. It was scoped with that ratio explicit and
approved on that basis. The justification for building it rather than accepting the gap is
that the additive-channel approach carries near-zero regression surface — no existing string
is reformatted, no existing resolution path changes — and that the geometry channel is
reusable if table-bound extraction turns out to be more common than two documents suggest.
