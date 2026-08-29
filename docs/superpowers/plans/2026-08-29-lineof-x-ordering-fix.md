# PdfTableLocator.lineOf X-Ordering Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix `PdfTableLocator.lineOf` so a physical row's text runs are joined in left-to-right reading order, instead of whatever order `groupIntoRows`' Y-primary sort happened to leave them in.

**Architecture:** `groupIntoRows` groups PDF text runs into physical rows correctly (Y-proximity, chain-tolerant) but its sort is Y-primary, so a row's internal member order is not guaranteed to be reading order when runs on the same line have tiny baseline-Y jitter. `lineOf` — the single choke point all 8 call sites route through — will sort a defensive copy of each row by `x` ascending (stable sort) immediately before joining. `groupIntoRows` itself, `ROW_Y_TOLERANCE`, and the chain-based clustering are untouched.

**Tech Stack:** Java 21, Spring Boot backend, JUnit 5 + AssertJ, Maven (Surefire).

**Spec:** [docs/superpowers/specs/2026-08-29-lineof-x-ordering-fix-design.md](../specs/2026-08-29-lineof-x-ordering-fix-design.md)

## Global Constraints

- Never quote a real extracted account number, card number, narration, or other literal value from `~/Downloads/Bank statement/` in any commit message, code comment, test fixture, or written document — describe shapes only (per this project's privacy discipline; see spec).
- All hand-written test fixtures use fully synthetic (invented) text and coordinates — no value from any real document.
- `groupIntoRows`'s row-*formation* logic (`ROW_Y_TOLERANCE`, chain-based clustering, Y-primary outer sort) must not change — only `lineOf`'s within-row join order changes.
- The fix applies uniformly to all 8 `lineOf`/`rowsToLines` call sites in `PdfTableLocator.java` (metadata AND transaction narration) — no site is scoped out.
- This is a shared-infrastructure, high-blast-radius file (`PdfTableLocator.java`, ~4862 lines, this project's own highest-defect-density file) — every task ends with the existing suite still green before moving on.

---

### Task 1: Failing tests reproducing both confirmed real bug shapes

**Files:**
- Create: `backend/src/test/java/com/finora/imports/pdf/RowJoinXOrderingPdfTableLocatorTest.java`

**Interfaces:**
- Consumes: `PdfTableLocator.locate(List<PositionedText>)` → `PdfTableLocator.LocatedTable` (existing public API, `PdfTableLocator.java:751`), `LocatedTable.preTableLines()` (existing accessor, `PdfTableLocator.java:596`), `PositionedText(String text, float x, float y, int pageIndex, float width)` (existing 5-arg constructor, `PositionedText.java:47`).
- Produces: nothing new — this task only adds tests against existing public surface.

This test drives the fix through the *public* `locate()` API rather than reflecting into the private `groupIntoRows`/`lineOf` methods, matching how every other `*PdfTableLocatorTest` in this package is written. When `locate()` is given text runs with no recognizable header row at all, it returns them verbatim as `preTableLines()` (see `PdfTableLocator.java:757-758`), which is exactly `rowsToLines(groupIntoRows(...))` — the same path metadata extraction and (via a different call site) transaction narration use.

- [ ] **Step 1: Write the failing test file**

```java
package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * groupIntoRows sorts primarily by Y, using X only as a tiebreak on exactly-equal Y. That leaves
 * lineOf joining a row's members in whatever order the Y-primary sort produced, not left-to-right
 * reading order -- wrong whenever two runs on the same visual line have any Y jitter at all, which
 * real PDF glyph metrics commonly produce between punctuation/digits and letters.
 *
 * <p>Both fixtures below reproduce the real shapes found on a real SBI statement during the F23
 * investigation (2026-08-29) -- described structurally per this project's privacy discipline, no
 * real extracted value is used. All coordinates and text here are fully synthetic.
 */
class RowJoinXOrderingPdfTableLocatorTest {

    private static PositionedText run(String text, float x, float y) {
        return new PositionedText(text, x, y, 0, text.length() * 6f);
    }

    @Test
    void colonWithSlightlyLowerYThanItsLabel_stillJoinsAfterTheLabel() {
        // X order is unambiguous (label < colon < value). The colon's y is a hair below the
        // label's/value's y -- exactly the kind of sub-point baseline jitter real PDFs produce
        // between punctuation and letters/digits on the same printed line.
        List<PositionedText> positioned = List.of(
                run("Branch", 100f, 200.00f),
                run("Code", 155f, 200.00f),
                run(":", 210f, 199.90f),
                run("XYZ001", 225f, 200.00f)
        );

        PdfTableLocator.LocatedTable table = new PdfTableLocator().locate(positioned);

        assertThat(table.preTableLines()).containsExactly("Branch Code : XYZ001");
    }

    @Test
    void twoAdjacentFieldsChainMergedWithinTolerance_stillJoinInLeftToRightOrder() {
        // Two physically-adjacent but unrelated label/value pairs land in the same physical row
        // because each consecutive Y-gap is within ROW_Y_TOLERANCE (3.0pt), even though the whole
        // chain's Y spans more than that end to end. Pure Y-ascending order (no exact-Y ties here)
        // interleaves the two pairs; X order does not.
        List<PositionedText> positioned = List.of(
                run("Branch", 100f, 300.00f),
                run("Phone", 155f, 300.00f),
                run(":", 210f, 300.90f),
                run("5550001234", 225f, 300.95f), // synthetic-ok: invented placeholder digits, not a real phone/account number
                run(":", 400f, 302.80f),
                run("1000.00", 415f, 302.85f),
                run("Clear", 40f, 303.80f),
                run("Balance", 90f, 303.80f)
        );

        PdfTableLocator.LocatedTable table = new PdfTableLocator().locate(positioned);

        assertThat(table.preTableLines())
                .containsExactly("Clear Balance Branch Phone : 5550001234 : 1000.00"); // synthetic-ok: invented placeholder digits, not a real phone/account number
    }
}
```

Note on the second fixture's expected string: "Clear Balance" (x=40/90) is the leftmost content on the line, followed by "Branch Phone" (x=100/155), then the first colon/value pair (x=210/225), then the second colon/value pair (x=400/415) — that is the correct left-to-right reading order once X-sort is applied. This is a synthetic reproduction of the real shape (unrelated fields chain-merging within `ROW_Y_TOLERANCE`), not a claim about what any real document's actual field values were.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && mvn -q -Dtest=RowJoinXOrderingPdfTableLocatorTest test`
Expected: both tests FAIL — actual `preTableLines()` values are scrambled (colon or "Clear Balance" out of place), not the asserted left-to-right strings.

- [ ] **Step 3: Commit the failing test**

```bash
git add backend/src/test/java/com/finora/imports/pdf/RowJoinXOrderingPdfTableLocatorTest.java
git commit -m "test: reproduce lineOf's Y-primary join-order bug (failing)"
```

---

### Task 2: Fix `lineOf` to join in X order

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java:3933-3940` (the `lineOf` method)

**Interfaces:**
- Consumes: `PositionedText.x()` (existing record accessor).
- Produces: `lineOf(List<PositionedText>)` (existing private method, unchanged signature) — now returns runs joined in `x`-ascending order instead of `groupIntoRows`' Y-primary sort order. All 8 existing call sites (`PdfTableLocator.java:897, 2488, 3928, 4231, 4494, 4815, 4836`) get this fix automatically since they all route through this one method.

- [ ] **Step 1: Implement the fix**

Replace the current `lineOf` body:

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

with:

```java
    /** Joins a row's members left-to-right by x -- NOT in whatever order {@link #groupIntoRows}'
     *  Y-primary sort happened to leave them in. Row membership (which runs share a physical line)
     *  is decided entirely by groupIntoRows and is correct; this method only decides read order
     *  within an already-correct row. Sorts a defensive copy (stable, so genuinely tied x -- e.g.
     *  overlapping/stacked glyphs -- keeps its prior relative order rather than being reshuffled)
     *  so callers holding the original row list see no side effect.
     *
     *  <p>Found via a real SBI branch-code field whose colon ran a hair below its label's y --
     *  common sub-point baseline jitter between punctuation and letters/digits on the same printed
     *  line -- which Y-primary sort placed before the label despite x making the true order
     *  unambiguous. Corpus-wide measurement (2026-08-29, real 27-doc corpus) found this affects
     *  22/27 documents and 7.9% of all physical rows, including transaction-shaped rows in at
     *  least one document previously believed clean -- not a rare edge case. */
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

`java.util.ArrayList` and `java.util.Comparator` are both already imported in this file (lines 8 and 10) — no import changes needed.

- [ ] **Step 2: Run the Task 1 tests to verify they now pass**

Run: `cd backend && mvn -q -Dtest=RowJoinXOrderingPdfTableLocatorTest test`
Expected: both tests PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java
git commit -m "fix(imports): join lineOf's row members in x order, not groupIntoRows' y-primary sort order"
```

---

### Task 3: Full existing-suite regression check

**Files:**
- None modified — verification only.

**Interfaces:**
- Consumes: the full existing `com.finora.imports.pdf` test package (all `*Test.java` files under `backend/src/test/java/com/finora/imports/pdf/`, including `fixtures`, `ocr`, and `acquisition` subpackages).
- Produces: a pass/fail signal gating whether Task 4 proceeds.

- [ ] **Step 1: Run the full PDF-import test package**

Run: `cd backend && mvn -q -Dtest="com.finora.imports.pdf.*Test,com.finora.imports.pdf.fixtures.*Test,com.finora.imports.pdf.ocr.*Test,com.finora.imports.pdf.acquisition.*Test" test`
Expected: all tests PASS — this includes `PdfMetadataExtractorTest`, `HeaderReconstructionEngineTest`, `TwoLineDateBlockInferenceTest`, `GoldenOutputSnapshotTest`, `TraceFixtureRegressionTest`, `RowAccountingEvidencePdfTableLocatorTest`, and every other existing regression fixture that already encodes prior SBI/ICICI/Axis/HDFC/HSBC row-formation fixes.

- [ ] **Step 2: If anything fails, diagnose before proceeding**

If any test fails, read its assertion and the fixture it uses. A failure here means either (a) an existing fixture's expected string was itself written against the old, scrambled join order and needs updating to reflect the corrected order (acceptable — update the fixture's expected value, and note in the commit message that the fixture encoded the pre-fix scrambled order), or (b) the fix genuinely regresses a different, previously-correct behavior (not acceptable — stop and re-examine Task 2's implementation against the failing fixture's specific row shape before continuing). Do not proceed to Task 4 until this task is fully green.

- [ ] **Step 3: Commit any fixture updates from Step 2** (skip if Step 1 was already green)

```bash
git add backend/src/test/java/com/finora/imports/pdf/<updated-fixture-file>.java
git commit -m "test: update fixture expectation for corrected lineOf join order"
```

---

### Task 4: Real-corpus before/after verification, prioritizing BOB.pdf

**Files:**
- None in the repo — this task runs the compiled pipeline against the real corpus at `~/Downloads/Bank statement/` and inspects output; no real value from that corpus is ever written into the repo, a commit message, or a code comment.

**Interfaces:**
- Consumes: `PdfPipelineDiagnostic.main(String[] args)` (existing tool, `backend/src/test/java/com/finora/imports/pdf/PdfPipelineDiagnostic.java:67`), invoked as `mvn test -Dtest=PdfPipelineDiagnostic#runFromSystemProperty -DpdfPath=<file>`.
- Produces: a manually-reviewed confirmation (recorded in this plan's checkbox state, not in a new file) that no real document's reconstructed transaction narration was corrupted by the fix, with `BOB.pdf` specifically confirmed corrected rather than further broken.

This step requires the fix from Task 2 to already be in place (it is, as of this task) — it verifies the *already-applied* fix against real data, it does not implement anything new.

- [ ] **Step 1: Capture "after" diagnostic output for every real document**

```bash
mkdir -p /tmp/lineof-fix-verification/after
for f in ~/Downloads/"Bank statement"/"Savings accounts"/*.pdf ~/Downloads/"Bank statement"/"Savings accounts"/*.PDF \
         ~/Downloads/"Bank statement"/"Credit cards"/*.pdf ~/Downloads/"Bank statement"/"Credit cards"/*.PDF; do
  [ -f "$f" ] || continue
  name=$(basename "$f" | tr ' /' '__')
  (cd backend && mvn -q -Dtest=PdfPipelineDiagnostic#runFromSystemProperty -DpdfPath="$f") \
    > "/tmp/lineof-fix-verification/after/${name}.txt" 2>&1
done
```

- [ ] **Step 2: Capture "before" diagnostic output by temporarily reverting the Task 2 commit**

```bash
git log --oneline -5   # confirm the exact SHA of the Task 2 fix commit
git revert --no-commit <task-2-fix-commit-sha>
mkdir -p /tmp/lineof-fix-verification/before
for f in ~/Downloads/"Bank statement"/"Savings accounts"/*.pdf ~/Downloads/"Bank statement"/"Savings accounts"/*.PDF \
         ~/Downloads/"Bank statement"/"Credit cards"/*.pdf ~/Downloads/"Bank statement"/"Credit cards"/*.PDF; do
  [ -f "$f" ] || continue
  name=$(basename "$f" | tr ' /' '__')
  (cd backend && mvn -q -Dtest=PdfPipelineDiagnostic#runFromSystemProperty -DpdfPath="$f") \
    > "/tmp/lineof-fix-verification/before/${name}.txt" 2>&1
done
git reset --hard HEAD   # discard the temporary revert, restoring the Task 2 fix
```

**Safety note for whoever executes this step:** `git reset --hard HEAD` discards uncommitted changes in the working tree. Run `git status --short` immediately before it to confirm the only pending change is the temporary revert from this step's `git revert --no-commit` — if anything else is uncommitted, commit or stash it first (per this project's own git-safety rules) rather than running the reset.

- [ ] **Step 3: Diff before vs. after for every document**

```bash
diff -rq /tmp/lineof-fix-verification/before /tmp/lineof-fix-verification/after
```

For every file the diff reports as differing, open both versions side by side:

```bash
diff /tmp/lineof-fix-verification/before/<name>.txt /tmp/lineof-fix-verification/after/<name>.txt
```

- [ ] **Step 4: Manually confirm each diff is a correction, not a new corruption**

For each differing document, read the diff's changed lines (auxiliary text and/or transaction rows) and confirm: words that were out of left-to-right order in "before" now read correctly in "after", and no line in "after" has become *more* scrambled than it was in "before". Do this for every differing document, but treat `BOB.pdf` as the priority case per the spec's corpus measurement (19 of its 25 disordered rows structurally resembled transaction rows). **Never copy a real extracted value from these diagnostic outputs into a commit message, code comment, or any file in the repo** — if you need to reference what you found, describe it structurally (e.g., "a two-word narration whose words were swapped is now in order"), exactly as this plan and its spec do throughout.

Do not proceed to Task 5 until every differing document's diff has been reviewed and confirmed as a correction.

- [ ] **Step 5: Clean up scratch diagnostic output**

```bash
rm -rf /tmp/lineof-fix-verification
```

- [ ] **Step 6: Update the external corpus study's BOB.pdf classification if BOB's diff confirmed the fix**

The 2026-08-18 physical-layout corpus study at `~/Downloads/Bank statement/physical-layout-study-2026-08-18.json` classifies `Savings accounts/BOB.pdf` as `CLEAN` under `"_revisionNote"`-style tracking that the study itself expects future `groupIntoRows`-adjacent changes to check against. This file lives outside the repository (per this project's policy for anything measured from real customer statements) — edit it directly at that path, not inside the repo. Add a new entry to its `"_revisionNote"` field (following the same "keep prior wrong/stale entries visible, mark them corrected, don't silently replace" convention the file already uses) noting that BOB.pdf's row-join order was confirmed corrected by this fix on today's date, without quoting any real extracted value.

- [ ] **Step 7: No repo commit for this task**

This task produces no repo changes (Steps 1-5 are scratch verification in `/tmp`, and Step 6 edits a file outside the repo) — there is nothing to commit here. Proceed directly to Task 5.

---

### Task 5: Capture a permanent regression fixture for BOB.pdf's affected section

**Files:**
- Create: `backend/src/test/resources/traces/bob-transaction-row-x-ordering.trace` (generated by the tool below, not hand-written)

**Interfaces:**
- Consumes: `PdfPipelineDiagnostic.captureRedactedTrace()` (existing tool, `PdfPipelineDiagnostic.java:113`), invoked as `mvn test -Dtest=PdfPipelineDiagnostic#captureRedactedTrace -DpdfPath=<file> -DtraceName=<name> -Dsource=<...> -Dmotivation=<...>`.
- Produces: a committable, redacted trace fixture plus (Step 2) a `TraceFixtureRegressionTest`-style test that loads it and asserts the corrected join order — turning this specific real production defect into a permanent regression case, per this project's existing trace-lifecycle discipline (`docs/engineering/trace-lifecycle.md`).

This task only proceeds if Task 4 confirmed BOB.pdf's diff was a genuine correction — skip it (and say why, in the final report) if Task 4's diff for BOB.pdf showed no change or an unresolved issue.

- [ ] **Step 1: Capture the redacted trace**

```bash
cd backend && mvn -q -Dtest=PdfPipelineDiagnostic#captureRedactedTrace \
  -DpdfPath="$HOME/Downloads/Bank statement/Savings accounts/BOB.pdf" \
  -DtraceName=bob-transaction-row-x-ordering \
  -Dsource="real BOB savings statement, 2026-08-29 lineOf X-ordering investigation" \
  -Dregressions="transaction row word order scrambled by groupIntoRows' Y-primary sort before this fix" \
  -Dmotivation="permanent regression case for the lineOf X-ordering fix (docs/superpowers/specs/2026-08-29-lineof-x-ordering-fix-design.md)"
```

Expected output ends with `Written -> src/test/resources/traces/bob-transaction-row-x-ordering.trace`. If instead it ends with `Trace REFUSED`, read the printed blockers (unmasked customer data or lost structural evidence) — do not proceed until the capture is committable; this refusal is deliberate (see `PdfPipelineDiagnostic`'s own doc comment) and must not be bypassed.

- [ ] **Step 2: Write a regression test that loads the trace**

`TraceFixtureRegressionTest.java` already establishes the pattern every trace-driven test in this file follows: a `private static final String ..._TRACE = "<traceName>";` constant, loaded via `PdfTrace.load(...)` and run through `new PdfTableLocator().locateAll(PdfTrace.load(TRACE_CONSTANT), ctx)` (see `theHdfcStatementThatExtractedNothing_nowYieldsExactlyOneTableWithARecognizedHeader` and `everyTransactionInThatStatement_parsesItsDate` in that same file for the exact shape). Follow it exactly:

```java
    /**
     * Captured from the real BOB savings statement whose transaction table has rows where
     * groupIntoRows' Y-primary sort put a run out of left-to-right order before the 2026-08-29
     * lineOf X-ordering fix (docs/superpowers/specs/2026-08-29-lineof-x-ordering-fix-design.md).
     */
    private static final String BOB_X_ORDERING_TRACE = "bob-transaction-row-x-ordering";

    @Test
    void bobTransactionRowXOrdering_joinsInLeftToRightOrder() {
        List<String> auxiliaryText = new PdfTableLocator()
                .locateAll(PdfTrace.load(BOB_X_ORDERING_TRACE), null).sections().get(0).auxiliaryText();

        // Fill in the exact expected line(s) here using the ACTUAL masked output from Step 1's
        // capture -- open the written .trace file (or re-run the diagnostic against BOB.pdf) to
        // see the redactor's masked tokens (digits -> '9', uppercase letters -> 'X', lowercase ->
        // 'x', everything else preserved verbatim) in their now-corrected left-to-right order, and
        // assert that exact masked string here. Never substitute a real extracted value.
        assertThat(auxiliaryText).anySatisfy(line ->
                assertThat(line).as("row previously scrambled by Y-primary join order").isNotBlank());
    }
```

If the affected row turns out to live in a section's `rows()` (a recognized transaction row) rather than `auxiliaryText()` (unattached pre-table text), assert against the relevant column's value in `rows()` instead, following `everyTransactionInThatStatement_parsesItsDate`'s pattern for reading a row map — determine which applies by inspecting where in `PdfPipelineDiagnostic`'s Step 1 output the previously-scrambled line appeared.

- [ ] **Step 3: Run the new regression test to verify it passes**

Run: `cd backend && mvn -q -Dtest=TraceFixtureRegressionTest test`
Expected: PASS, including the new `bobTransactionRowXOrdering_joinsInLeftToRightOrder` method.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/resources/traces/bob-transaction-row-x-ordering.trace \
        backend/src/test/java/com/finora/imports/pdf/TraceFixtureRegressionTest.java
git commit -m "test: add permanent regression fixture for BOB.pdf's lineOf X-ordering fix"
```

---

## Final check

- [ ] Run the full backend PDF-import package one more time (`cd backend && mvn -q -Dtest="com.finora.imports.pdf.*Test,com.finora.imports.pdf.fixtures.*Test,com.finora.imports.pdf.ocr.*Test,com.finora.imports.pdf.acquisition.*Test" test`) to confirm everything is green after all five tasks.
- [ ] Confirm `git status --short` shows a clean working tree with only the expected commits from Tasks 1, 2, 3 (if fixtures needed updating), and 5 (Task 4 makes no repo commit).
