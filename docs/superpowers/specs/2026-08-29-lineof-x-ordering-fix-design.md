# PdfTableLocator.lineOf X-Ordering Fix — Design Spec

**Date:** 2026-08-29
**Status:** Approved for implementation planning
**File under change:** `backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java`

## Problem

`groupIntoRows` ([PdfTableLocator.java:2124](../../../backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java)) sorts all `PositionedText` runs primarily by Y-coordinate, using X only as a tiebreak when two runs' Y values are *exactly* equal:

```java
sorted.sort((a, b) -> {
    if (a.pageIndex() != b.pageIndex()) return Integer.compare(a.pageIndex(), b.pageIndex());
    int byY = Float.compare(a.y(), b.y());
    return byY != 0 ? byY : Float.compare(a.x(), b.x());
});
```

Runs within `ROW_Y_TOLERANCE` (3.0pt, chain-based) are grouped into a physical row. `lineOf` ([PdfTableLocator.java:3933](../../../backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java)) then joins a row's members **in that same Y-primary sort order**, assuming row membership implies reading order. It doesn't: Y-proximity only establishes that two runs share a visual line, not which comes first left-to-right. Real PDF glyphs commonly carry tiny baseline-Y jitter between punctuation, digits, and letters — enough to flip Y-primary sort order even when X position is unambiguous.

### Confirmed real-world impact (2026-08-29 investigation)

Two known symptom shapes (values described structurally per this project's privacy discipline, never quoted):

1. A label/colon/value triple on one real SBI branch-code field, where the colon's Y was fractionally less than the label's Y (both far from any other run's Y) — X order unambiguous (label < colon < value), but Y-primary sort placed the colon first, producing a corrupted joined line.
2. A worse case, same document: two physically-adjacent but semantically unrelated label/value pairs chain-merged into one row purely because their Y-values fell within the 3pt chain tolerance, producing an interleaved, nonsensical joined line with the leftmost token last.

**Corpus-wide measurement**, run against the real, unredacted 27-document corpus at `~/Downloads/Bank statement/` (via reflection into the real `groupIntoRows`, counts and coordinate deltas only — no extracted text was printed or retained, consistent with this project's "describe shapes, never quote real values" rule):

- **22 of 27 documents** have at least one row whose Y-primary join order differs from strict X-ascending order.
- **813 of 10,306 total physical rows (7.9%)** are affected.
- Using a purely structural classifier (row contains a date-pattern token AND an amount-pattern token, as a proxy for "looks like a transaction row"), **28 disordered rows across 5 documents plausibly contain real transaction data**, not just metadata. **BOB.pdf alone accounts for 19 of those 28** — 19 of BOB's own 25 disordered rows look transaction-like.
- BOB.pdf was classified `CLEAN — no known extraction issue` in this project's own 2026-08-18 physical-layout corpus study (`~/Downloads/Bank statement/physical-layout-study-2026-08-18.json`). This means the ordering bug is a plausible **live, silent defect in a document nobody had flagged as broken** — not a purely hypothetical or already-mitigated risk.

This significantly raises the confirmed severity above the single-field metadata symptom that originally motivated this investigation (F23 branch-extraction work, PR #560).

## Relationship to the separate BOB/HSBC column-gap-width investigation

A separate, independently-dispatched investigation (background task `task_a166534b`, not yet reported back as of this writing — unreachable via `ListAgents`, no matching doc or worktree found) is looking at a different defect in the same function: `lineOf`'s single-space joining discards column-*gap-width* information, making it impossible for BOB/HSBC's genuinely multi-column account-number tables to distinguish "two separate columns" from "two words in one column."

These are **independent defects, not the same bug**:

- `groupIntoRows`'s outer sort must stay Y-primary for row *formation* to work at all — chain-based clustering (comparing each run only to the previously-added one) is what tolerates accumulated OCR jitter within one physical line; sorting by X first would scatter same-Y runs across unrelated rows and break row formation. So the ordering bug and row-formation logic are not entangled.
- The ordering bug lives entirely in how an already-correctly-formed row's members are joined. Fixing X-order does not recover discarded gap-width information, and fixing gap-width magnitude doesn't require X-order to be fixed first (though correctly-ordered rows make that follow-up problem easier to reason about).

**Decision: fix these as two independent changes.** Bundling a gap-width redesign into this fix would combine two unrelated risky changes to the highest-defect-density file in the codebase, for no shared benefit, and would additionally depend on an investigation that hasn't reported its findings yet.

## Chosen approach

Sort a row's members by `x` ascending, via a **stable** sort, immediately before joining — inside `lineOf` itself, the single choke point all 8 call sites route through ([PdfTableLocator.java:897](../../../backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java), 2488, 3928 (via `rowsToLines`), 4231, 4494, 4815, 4836). `groupIntoRows`, `ROW_Y_TOLERANCE`, and the chain-based clustering logic are untouched — this is purely a within-row join-order correction.

```java
private String lineOf(List<PositionedText> row) {
    List<PositionedText> ordered = new ArrayList<>(row);
    ordered.sort(Comparator.comparing(PositionedText::x));
    StringBuilder line = new StringBuilder();
    for (PositionedText t : ordered) {
        if (!line.isEmpty()) line.append(' ');
        line.append(t.text());
    }
    return line.toString();
}
```

Applied **uniformly** to all 8 call sites, including transaction-narration reconstruction (`lineOf(narrationCells)` inside `TwoLineBlock` construction, ~line 4815) — scoping the fix to metadata-only was considered and rejected, since the corpus evidence above shows narration-shaped rows are already affected today and a metadata-only scope would leave a confirmed defect live in transaction data users see.

### Why this is safe as a monotonic correctness fix

Row membership (which runs land in which physical row) is unaffected — only the join order within an already-correct row changes. There is no case where two runs genuinely printed on the same visual line should be joined in non-X order; X-ascending is the only order consistent with how a human reads the line.

### Edge cases

- Empty row → unchanged (empty string).
- Single-member row → unchanged (sort is a no-op).
- Two runs at genuinely identical `x` (rare — e.g. stacked/overlapping glyphs) → stable sort preserves their original insertion order, i.e. whatever `groupIntoRows`'s Y-primary sort had already put first for that pair — same tiebreak behavior as today, for that one degenerate case only.

## What this does NOT fix

- Column-gap-width loss (BOB/HSBC's genuine multi-column account-number tables) — tracked separately, pending `task_a166534b`'s findings.
- Any row-*formation* defect (which runs get grouped into which row) — `ROW_Y_TOLERANCE` and chain-based clustering are unchanged.

## Testing plan

1. **Unit tests** on `lineOf`/`groupIntoRows` with synthetic `PositionedText` runs replicating both confirmed real shapes (label/colon/value with near-equal Y; label+value+colon+value+label chain-merged across columns) — assert correct left-to-right join order.
2. **Full real-corpus diff**: run the compiled pipeline (`PdfPipelineDiagnostic` or equivalent) against all 27 real documents at `~/Downloads/Bank statement/`, before and after the change, diffing reconstructed transaction narration text per document. Every diff gets manually inspected (never quoting real values in any commit, comment, or doc — describe shapes only) to confirm it is a correction, not a new corruption.
   - **BOB.pdf is the priority case** — 19 of its 25 disordered rows structurally resemble transaction rows, and it's currently classified `CLEAN` in the corpus study. Confirm the reconstructed narration is genuinely fixed (not further corrupted) for every affected row.
   - Consider capturing a redacted regression trace fixture (`PdfPipelineDiagnostic#captureRedactedTrace`) for BOB.pdf's transaction section once the fix is confirmed, so this defect becomes a permanent regression case per this project's existing trace-lifecycle discipline.
3. **Existing suite regression check**: re-run `PdfTableLocatorTest` and related header-recovery/metadata-extraction tests to confirm no regression in already-passing fixtures (SBI, ICICI, Axis/HDFC, HSBC row-formation cases from prior fixes).
4. Update the 2026-08-18 physical-layout corpus study's BOB.pdf classification once verified, since this fix directly contradicts its "no known extraction issue" finding for that document (the study's own JSON notes it's meant to be checked against future `groupIntoRows` changes for exactly this reason).

## Out of scope

- Column-gap-width redesign (separate investigation, not yet reported).
- Any change to `ROW_Y_TOLERANCE` or the chain-based row-formation algorithm.
- Retroactively re-importing or correcting any already-staged/confirmed transactions that may have been affected in production — that is a separate decision (data remediation), not part of this code-level fix, and should be raised with the user separately once the corpus diff in testing step 2 confirms actual production impact.
