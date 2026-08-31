# PR #380 Suppression Regressions — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix two confirmed, currently-live silent-data-loss regressions in `PdfTableLocator.java`,
both introduced by commit `48534231` (PR #380, merged 2026-08-24), without weakening the real
garbage-merge fixes that same commit shipped.

**Architecture:** Both bugs are the same shape — a suppression flag/trigger with an unsound
"resume" or "this means the true end" assumption that a second real document falsifies. Both fixes
add one narrow, additionally-evidenced condition rather than removing or loosening the original
fix. No shared-mechanism restructuring: `TRAILING_CONTENT_TRIGGERS`' generic list stays generic for
the other 8 entries; only the two specific broken conditions change.

**Tech Stack:** Java 21, JUnit 5, AssertJ. Fully-synthetic `PositionedText` test fixtures (no real
PDF bytes) — the same convention as the file's existing 10 `*PdfTableLocatorTest.java` files.

**Spec:** No separate spec document — full investigation, root-cause tracing (via temporary debug
instrumentation, since reverted), and toggle-experiment verification is recorded in memory file
`pr380-page-suppression-regression-2026-08-31.md` (outside the repo; ask if you need the content
restated). This plan is self-contained enough to implement without it.

## Global Constraints

- Never quote real document text verbatim in code, comments, or tests — describe the shape only.
  All test fixtures below are fully synthetic (invented dates/amounts/descriptions), not derived
  from real document content.
- Do not remove or weaken `PAGE_LEGEND_BLOCK_START`'s HDFC alternative or
  `CHEQUE_PAYABLE_FOOTER_MARKER` — both fix real bugs on other real documents. Add a narrower
  condition instead.
- Keep blast radius scoped to exactly these two mechanisms. Do not touch
  `TRAILING_CONTENT_TRIGGERS`' other 8 entries, `MAX_TRAILING_CONTINUATION_ROWS`, or any
  continuation/merge logic not directly load-bearing for these two fixes.
- `PdfTableLocator`'s own class-level Javadoc: this class reconstructs physical structure only,
  never product/financial semantics. Both fixes stay within that boundary (date/amount shape and
  page position are structural signals, not financial ones).

---

### Task 1: Fix `pageLegendBlockActive` getting stuck when a document never reprints its header

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java` (the main
  `locateAll` row loop, immediately before the `if (currentRows == null) { ... } else if
  (pageLegendBlockActive) { ... }` chain — currently around line 1259, but re-locate by searching
  for `} else if (pageLegendBlockActive) {` since line numbers shift)
- Test: `backend/src/test/java/com/finora/imports/pdf/PageLegendBlockSuppressionTest.java`

**Interfaces:**
- Consumes: `isTransactionShapedRow(List<PositionedText> row)` (private instance method, already
  exists, returns `true` iff the row has both a parseable date, via `CsvParser.parseDate`, and a
  parseable decimal amount, via `CsvParser.parseNumeric` on a token containing `.`).
- Produces: nothing new consumed by later tasks — this is a self-contained fix.

- [ ] **Step 1: Write the failing test**

Add this test to `PageLegendBlockSuppressionTest.java` (same file as the existing
`legendBlockAtAPageBreak_...` test — reuse its `run(String text, float x, float width, float y, int
page)` helper and imports already present in that file):

```java
@Test
void legendBlockWithNoRepeatedHeaderOnTheNextPage_stillResumesOnceARealTransactionRowIsSeen() {
    // A real HDFC savings statement (24 pages) prints its header exactly once, on page 1, and
    // never again -- every later page's transactions resume directly with no header row at all.
    // pageLegendBlockActive's only resume signal used to be a newly recognized header row, so
    // once this page-1 footer set the flag, there was no "next header" ever again to clear it --
    // 231 of 243 real transactions were silently dropped for the rest of the document.
    List<PositionedText> positioned = new ArrayList<>();
    positioned.add(run("Date", 40f, 30f, 100f, 0));
    positioned.add(run("Description", 100f, 80f, 100f, 0));
    positioned.add(run("Amount", 300f, 45f, 100f, 0));
    positioned.add(run("11 Jul 26", 40f, 45f, 120f, 0));
    positioned.add(run("UPI-VMPL DEL 24", 100f, 80f, 120f, 0));
    positioned.add(run("390.00", 300f, 40f, 120f, 0));
    positioned.add(run("Closing balance includes funds earmarked for hold and uncleared funds",
            20f, 400f, 140f, 0));
    positioned.add(run("Address correctness is the customer's own responsibility", 20f, 400f, 150f, 0));
    // Page 1: NO repeated header -- a real transaction resumes directly.
    positioned.add(run("12 Jul 26", 40f, 45f, 50f, 1));
    positioned.add(run("UPI-ZOMATO", 100f, 80f, 50f, 1));
    positioned.add(run("25.00", 300f, 40f, 50f, 1));

    DocumentContext ctx = new DocumentContext("PDF", "test");
    PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

    assertThat(doc.sections()).hasSize(1);
    List<Map<String, String>> rows = doc.sections().get(0).rows();
    assertThat(rows)
            .as("the real transaction on page 1 must be recovered even with no repeated header to "
                    + "reset the legend-block suppression")
            .hasSize(2);
    assertThat(rows.get(1))
            .containsEntry("Description", "UPI-ZOMATO")
            .containsEntry("Amount", "25.00");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -o test -Dtest=PageLegendBlockSuppressionTest`
Expected: FAIL — `rows` has size 1, not 2 (the page-1 transaction was silently dropped).

- [ ] **Step 3: Write the minimal implementation**

In `PdfTableLocator.java`, find the row loop in `locateAll` (search for `} else if
(pageLegendBlockActive) {`). Immediately before the `if (currentRows == null) {` line that starts
that whole if/else-if chain, insert:

```java
            // Bug fix, 2026-08-31: pageLegendBlockActive's only resume signal used to be a newly
            // recognized header row (see the pageLegendBlockActive = false call sites above). That
            // assumption silently broke on any real document whose per-page legend/footer sits
            // under a table that does NOT reprint its header on every page -- a real HDFC savings
            // statement (24 pages) prints its header exactly once, on page 1, and never again; once
            // its own per-page footer ("Closing balance includes funds earmarked...", part of
            // PAGE_LEGEND_BLOCK_START above) set this flag on page 1, there was no "next header"
            // ever again to reset it, and every one of the document's remaining 23 pages -- 231 of
            // 243 real transactions -- was silently suppressed for the rest of the document. A
            // second, independent resume signal: a row that is unambiguously transaction-shaped
            // (isTransactionShapedRow -- has both a date and an amount, the same admission test the
            // headerless path already trusts) can only be genuine data, never legend/footer
            // boilerplate, so it is always safe to treat as proof the block has ended, with or
            // without a header row in between.
            if (pageLegendBlockActive && isTransactionShapedRow(row)) {
                pageLegendBlockActive = false;
            }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -o test -Dtest=PageLegendBlockSuppressionTest`
Expected: PASS (both the new test and the pre-existing
`legendBlockAtAPageBreak_doesNotPolluteTheLastTransactionAboveIt_andRealRowsResumeOnTheNextPage`
test, which must still pass unchanged — it already resumes via a repeated header, so this new
condition must not interfere with that path).

Also run the full `PdfTableLocator` test suite to confirm no other test regressed:
`cd backend && ./mvnw -o test -Dtest='*PdfTableLocatorTest'`
Expected: all PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java \
        backend/src/test/java/com/finora/imports/pdf/PageLegendBlockSuppressionTest.java
git commit -m "fix(imports): stop pageLegendBlockActive suppressing an entire document with no repeated header"
```

---

### Task 2: Fix `CHEQUE_PAYABLE_FOOTER_MARKER` closing the section on an early page

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java`
  - `trailingContentTriggerCapability` method (currently ~line 462)
  - `TRAILING_CONTENT_TRIGGERS` list definition (currently ~line 447)
  - Call site inside `locateAll`'s main loop (currently ~line 919)
  - Call site inside `bucketHeaderlessRowsWithContinuation` (currently ~line 4533)
- Test: create `backend/src/test/java/com/finora/imports/pdf/ChequePayableFooterPositionGuardTest.java`

**Interfaces:**
- Consumes: `CHEQUE_PAYABLE_FOOTER_MARKER` (existing `Pattern` constant), `PositionedText.pageIndex()`.
- Produces: `trailingContentTriggerCapability(String rowLine, int pageIndex, int lastPageIndex)` —
  signature change from the current `(String rowLine)`. Both existing call sites must be updated in
  this same task; do not leave one on the old signature.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/java/com/finora/imports/pdf/ChequePayableFooterPositionGuardTest.java`:

```java
package com.finora.imports.pdf;

import com.finora.imports.DocumentContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test: CHEQUE_PAYABLE_FOOTER_MARKER's own doc comment claims this sentence, confirmed
 * single-occurrence via grep, always means a real Axis Bank credit-card statement's true end. A
 * second real Axis document falsifies that: it prints the identical sentence on page 1, as part of
 * an ordinary payment-instructions panel next to the summary, well before the real transaction
 * table finishes. Fully synthetic fixtures -- no real document text quoted.
 */
class ChequePayableFooterPositionGuardTest {

    private static PositionedText run(String text, float x, float width, float y, int page) {
        return new PositionedText(text, x, y, page, width);
    }

    @Test
    void chequePayableFooterSentence_onAnEarlyPage_doesNotPrematurelyCloseTheSection() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f, 0));
        positioned.add(run("Description", 100f, 80f, 100f, 0));
        positioned.add(run("Amount", 300f, 45f, 100f, 0));
        positioned.add(run("11 Jul 26", 40f, 45f, 120f, 0));
        positioned.add(run("Card purchase one", 100f, 80f, 120f, 0));
        positioned.add(run("390.00", 300f, 40f, 120f, 0));
        // An early-page payment-instructions panel that happens to share this sentence, well
        // before the document's true end -- confirmed via pdftotext against the real document
        // this regression was found on.
        positioned.add(run("Your cheque should be payable to Axis Bank Card No.XXXXXXXXXXXX1234",
                20f, 400f, 140f, 0));
        // Page 1: a real transaction that must survive -- this is the actual rest of the table.
        positioned.add(run("12 Jul 26", 40f, 45f, 50f, 1));
        positioned.add(run("Card purchase two", 100f, 80f, 50f, 1));
        positioned.add(run("25.00", 300f, 40f, 50f, 1));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows)
                .as("the cheque-payable sentence on an early page must not close the section before "
                        + "the real table finishes -- it only means the document's true end when it "
                        + "sits on the document's own actual last page")
                .hasSize(2);
        assertThat(rows.get(1))
                .containsEntry("Description", "Card purchase two")
                .containsEntry("Amount", "25.00");
    }

    @Test
    void chequePayableFooterSentence_onTheDocumentsActualLastPage_stillClosesTheSection() {
        List<PositionedText> positioned = new ArrayList<>();
        positioned.add(run("Date", 40f, 30f, 100f, 0));
        positioned.add(run("Description", 100f, 80f, 100f, 0));
        positioned.add(run("Amount", 300f, 45f, 100f, 0));
        positioned.add(run("11 Jul 26", 40f, 45f, 120f, 0));
        positioned.add(run("Card purchase one", 100f, 80f, 120f, 0));
        positioned.add(run("390.00", 300f, 40f, 120f, 0));
        // Same page: the document's true end -- no later page exists at all, exactly the shape
        // CHEQUE_PAYABLE_FOOTER_MARKER was originally evidenced from.
        positioned.add(run("Your cheque should be payable to Axis Bank Card No.XXXXXXXXXXXX1234",
                20f, 400f, 140f, 0));

        DocumentContext ctx = new DocumentContext("PDF", "test");
        PdfTableLocator.LocatedDocument doc = new PdfTableLocator().locateAll(positioned, ctx);

        assertThat(doc.sections()).hasSize(1);
        List<Map<String, String>> rows = doc.sections().get(0).rows();
        assertThat(rows)
                .as("on the document's own actual last page, this sentence still means the true end")
                .hasSize(1);
        assertThat(ctx.capabilities().stream().map(c -> c.capability()))
                .contains("CHEQUE_PAYABLE_FOOTER_CLOSED");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -o test -Dtest=ChequePayableFooterPositionGuardTest`
Expected: FAIL on `chequePayableFooterSentence_onAnEarlyPage_...` — `rows` has size 1, not 2 (the
section closes prematurely on page 0, "Card purchase two" is never staged). The second test should
already PASS (it matches today's — buggy but coincidentally correct for this one shape — behavior);
that's fine, it exists to prove Step 3 doesn't break the true-end case.

- [ ] **Step 3: Write the minimal implementation**

In `PdfTableLocator.java`:

**3a.** Remove the `CHEQUE_PAYABLE_FOOTER_MARKER` entry from the `TRAILING_CONTENT_TRIGGERS` list
(it becomes a special case handled directly in `trailingContentTriggerCapability`, not a generic
list entry):

```java
    private static final List<TrailingContentTrigger> TRAILING_CONTENT_TRIGGERS = List.of(
            new TrailingContentTrigger(ILLUSTRATIVE_EXAMPLE_MARKER, "ILLUSTRATIVE_BLOCK_SUPPRESSED"),
            new TrailingContentTrigger(STATEMENT_CLOSING_MARKER, "TRANSACTION_TABLE_CLOSED"),
            new TrailingContentTrigger(TRANSACTION_TABLE_TOTAL_MARKER, "TRANSACTION_TABLE_TOTAL_CLOSED"),
            new TrailingContentTrigger(MITC_SECTION_MARKER, "MITC_SECTION_CLOSED"),
            new TrailingContentTrigger(ACCOUNT_DISCREPANCY_DISCLAIMER_MARKER,
                    "ACCOUNT_DISCREPANCY_DISCLAIMER_CLOSED"),
            new TrailingContentTrigger(STATEMENT_SUMMARY_BLOCK_MARKER, "STATEMENT_SUMMARY_BLOCK_CLOSED"),
            new TrailingContentTrigger(NEUCOINS_FOOTNOTE_MARKER, "NEUCOINS_FOOTNOTE_CLOSED"),
            new TrailingContentTrigger(SAVINGS_AND_BENEFITS_SECTION_MARKER,
                    "SAVINGS_AND_BENEFITS_SECTION_CLOSED"));
```

**3b.** Replace `trailingContentTriggerCapability`'s signature and body:

```java
    /** The capability name the first matching trigger should record for {@code rowLine}, or null
     *  if none match. {@code pageIndex}/{@code lastPageIndex} exist only for
     *  CHEQUE_PAYABLE_FOOTER_CLOSED -- see its own check below for why. */
    private static String trailingContentTriggerCapability(String rowLine, int pageIndex, int lastPageIndex) {
        // CHEQUE_PAYABLE_FOOTER_CLOSED needs one more check than every other entry below: unlike
        // those (each confirmed single-occurrence AND genuinely at their evidencing document's true
        // end), this exact sentence was found on a SECOND real Axis Bank credit-card statement,
        // printed on page 1 of 3 as part of an ordinary payment-instructions panel next to the
        // summary -- not a closing block. "Single occurrence" alone does not distinguish an early
        // informational panel from a genuine document-closing footer; the two real Axis documents
        // this pattern has now been evidenced against disagree on where it prints. Requiring it to
        // sit on the document's own actual last page is the one thing a true closing block and this
        // false-positive panel cannot both satisfy at once, and it needs no new vocabulary -- the
        // page position is already known to the caller.
        if (CHEQUE_PAYABLE_FOOTER_MARKER.matcher(rowLine).find()) {
            return pageIndex == lastPageIndex ? "CHEQUE_PAYABLE_FOOTER_CLOSED" : null;
        }
        for (TrailingContentTrigger trigger : TRAILING_CONTENT_TRIGGERS) {
            if (trigger.pattern().matcher(rowLine).find()) return trigger.capability();
        }
        return null;
    }
```

**3c.** In `locateAll`, hoist the existing page-count scan (currently inside `if (ctx != null) {
... }` near the top of the method, computing a block-scoped `maxPageIndex`) so it is always
computed and available to the whole method:

Find:
```java
        if (ctx != null) {
            int maxPageIndex = -1;
            for (PositionedText t : positionedText) maxPageIndex = Math.max(maxPageIndex, t.pageIndex());
            ctx.recordPages(maxPageIndex + 1);
        }
```

Replace with:
```java
        int lastPageIndex = -1;
        for (PositionedText t : positionedText) lastPageIndex = Math.max(lastPageIndex, t.pageIndex());
        if (ctx != null) ctx.recordPages(lastPageIndex + 1);
```

**3d.** Update the call site inside `locateAll`'s main row loop (search for `String
trailingContentTrigger = trailingContentTriggerCapability(rowLine);`):

```java
            int rowPageIndex = row.isEmpty() ? -1 : row.get(0).pageIndex();
            String trailingContentTrigger = trailingContentTriggerCapability(rowLine, rowPageIndex, lastPageIndex);
```

**3e.** Update `bucketHeaderlessRowsWithContinuation`: add a local `lastPageIndex` computed from its
own `allRows` parameter at the top of the method (right after the existing local variable
declarations, before the `for (List<PositionedText> row : allRows)` loop):

```java
        int lastPageIndex = -1;
        for (List<PositionedText> r : allRows) {
            if (!r.isEmpty()) lastPageIndex = Math.max(lastPageIndex, r.get(0).pageIndex());
        }
```

Then update its call site (search for `String trailingTrigger = trailingContentTriggerCapability(rowLine);`):

```java
            int rowPageIndex = row.isEmpty() ? -1 : row.get(0).pageIndex();
            String trailingTrigger = trailingContentTriggerCapability(rowLine, rowPageIndex, lastPageIndex);
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -o test -Dtest=ChequePayableFooterPositionGuardTest`
Expected: both tests PASS.

Also run the full `PdfTableLocator` suite (all 8 remaining `TRAILING_CONTENT_TRIGGERS` entries must
still behave identically — none of them changed signature-wise, only the shared method's parameter
list did):
`cd backend && ./mvnw -o test -Dtest='*PdfTableLocatorTest'`
Expected: all PASS. Pay particular attention to any existing test exercising
`STATEMENT_SUMMARY_BLOCK_MARKER`'s headerless-path parity fix (the Sanjay SBI case documented in
`bucketHeaderlessRowsWithContinuation`'s own comment) — it must still fire correctly with the new
3-argument call.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java \
        backend/src/test/java/com/finora/imports/pdf/ChequePayableFooterPositionGuardTest.java
git commit -m "fix(imports): require CHEQUE_PAYABLE_FOOTER_CLOSED to sit on the document's actual last page"
```

---

### Task 3: Verify both fixes against the real documents and close out the investigation

**Files:** none modified — verification only.

**Interfaces:**
- Consumes: `CorpusProbe` (`backend/src/test/java/com/finora/imports/analysis/CorpusProbe.java`,
  already exists from Phase 2), run via `java -cp <classpath> com.finora.imports.analysis.CorpusProbe <path-to-pdf>`.

- [ ] **Step 1: Build the classpath and probe all four real documents**

```bash
cd backend
./mvnw -q -o test-compile
./mvnw -q -o dependency:build-classpath -Dmdep.outputFile=/tmp/finora-cp.txt -Dmdep.includeScope=test
```

(If `/tmp` is refused by the sandbox, write the classpath file to this session's scratchpad
directory instead and reference it in the next commands.)

```bash
CP="target/test-classes:target/classes:$(cat /tmp/finora-cp.txt)"
java -cp "$CP" com.finora.imports.analysis.CorpusProbe "$HOME/Downloads/Bank statement/Savings accounts/HDFC sav.pdf"
java -cp "$CP" com.finora.imports.analysis.CorpusProbe "$HOME/Downloads/Bank statement/Savings accounts/HDFC 3 month.pdf"
java -cp "$CP" com.finora.imports.analysis.CorpusProbe "$HOME/Downloads/Bank statement/Savings accounts/Mann HDFC.pdf"
java -cp "$CP" com.finora.imports.analysis.CorpusProbe "$HOME/Downloads/Bank statement/Credit cards/Axis credit.pdf"
```

Expected `observed.rows` in each JSON output: `HDFC sav.pdf` → 243, `HDFC 3 month.pdf` → 243, `Mann
HDFC.pdf` → 360, `Axis credit.pdf` → 108. Expected `derived.documentClassification`:
`PARSED_COMPLETE` for all four (not `PARSED_INCOMPLETE`).

- [ ] **Step 2: Re-run the full backend test suite**

```bash
cd backend && ./mvnw -o test
```

Expected: full suite green (ignore any pre-existing flaky Spring-context/DB-collision failures
unrelated to this change — confirm by re-running just the failing class in isolation if any
failures appear).

- [ ] **Step 3: Update memory**

This step has no code — it's a note for whoever executes this plan to update the two memory files
this investigation already created: `pr380-page-suppression-regression-2026-08-31.md` (mark both
regressions as fixed, with the commit hashes from Tasks 1 and 2) and
`import-pipeline-bug-hunt-roadmap-2026-08-31.md` (note this is resolved and the roadmap can resume
at Phase 4a).

- [ ] **Step 4: Follow `superpowers:finishing-a-development-branch`**

Push, open a PR against `main`, and report the PR URL. Both regressions being fixed on `main` again
is the actual deliverable here — this should not be left as an unmerged branch.
