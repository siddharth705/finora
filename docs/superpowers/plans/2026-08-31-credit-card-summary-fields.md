# Credit-Card Summary Fields (Phase 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the credit-card `totalAmountDue` extraction gap (currently succeeds for 1 of 8 real
documents) with the smallest set of narrowly-scoped fixes that the corpus evidence actually
supports — no heuristic broader than what a specific, root-caused real-document failure requires.

**Architecture:** All five fixes live inside `CreditCardSummaryExtractor.java` alone. None touch
`StatementSummaryExtractor.java` (shared with savings-statement parsing — touching it would widen
this phase's blast radius beyond credit cards, which was the whole point of sequencing this phase
low-risk) or `PdfMetadataExtractor.java` (owns `paymentDueDate`/`creditLimit` via an entirely
separate line-scanning mechanism — out of scope, see Non-Goals). Two of the five are general
mechanisms (apply to any future document with the same shape); three are narrow, document-specific
label/geometry fixes.

**Tech Stack:** Java 21 / JUnit 5 / AssertJ, hand-built `PositionedText` fixtures (this class's test
file's existing convention — no PDF rendering needed, see `CreditCardSummaryExtractorTest.java`'s
own doc comment on why: synthetic geometry reproducing an observed *shape*, never a real document's
positions or figures).

**Spec:** No standalone spec doc — implements Phase 5 of the import-pipeline roadmap
(`import-pipeline-fix-roadmap.md`, delivered out-of-repo). Quantification (root-cause matrix across
all 8 real credit-card statements) and two scope decisions were done directly in conversation before
this plan, not in a separate doc:
1. **Gate loosening scope (Axis):** when GRID and INLINE_LABEL_VALUE disagree on `totalAmountDue`,
   leave it unresolved (do not guess which strategy is right from one document's evidence). Axis's
   own `totalAmountDue` is *not* fixed by this plan — its two readings genuinely conflict, and that
   stays correctly unresolved; the mechanism this plan adds unblocks every other document where only
   one strategy finds anything, or both agree.
2. **Kotak duplicate-label scope:** a label occurring more than once on a page is accepted when
   every occurrence resolves to the identical value (redundancy, not ambiguity) — implemented as a
   generic rule with tests for both the accept (same value) and refuse (different values) cases, not
   a Kotak-specific carve-out.

## Non-Goals (confirmed via root-cause investigation, not assumed)

- **HDFC** — whole-document font/glyph-mapping corruption (confirmed: extensive "No Unicode
  mapping for CID+N" warnings from PDFBox). A text-extraction-fidelity problem, not a label or gate
  issue. Needs its own investigation.
- **HSBC** — whole document fails to parse at all (`LAYOUT_UNSUPPORTED`, 0 rows via `CorpusProbe`).
  Unrelated to metadata extraction.
- **ICICI** — pre-existing, already-documented table-formation degradation (`PARSED_INCOMPLETE`),
  same class as HSBC's failure, not a label-variant gap.
- **`paymentDueDate` for Axis and SBI** — this field is extracted by `PdfMetadataExtractor`'s
  separate line-scanning mechanism (`PAYMENT_DUE_DATE`/`PAYMENT_DUE_DATE_SENTENCE`/
  `GRID_CREDIT_LIMIT_LABEL`-adjacent fallback), not `CreditCardSummaryExtractor`. Both statements'
  due-date gaps remain open — a distinct root-cause thread in a different file, not investigated as
  part of this plan.
- **Kotak's label-joining and duplicate-acceptance mechanisms (Tasks 4 and 2)** apply generically,
  by design (per the scope decisions above) — but no other real document in the current 8-document
  corpus is known to need them today. They are not Kotak-specific hacks; they simply have one
  confirmed beneficiary so far.

## Global Constraints

- No change in this plan may alter `CreditCardStatementTotalsValidator` or
  `CreditCardFlowReconciliationValidator`'s existing behavior on any currently-passing document.
  Both are re-verified against their own existing test suites at the end of every task that touches
  shared logic (Tasks 1 and 2).
- `hasReconcilableFields()`'s existing four-field contract (`previousBalance`, `purchases`,
  `paymentsAndCredits`, `totalAmountDue` all non-null) is never weakened — Task 1 adds a
  *best-effort* `totalAmountDue` to the returned evidence without ever fabricating the other three
  fields, so a document that could not reconcile before this plan still cannot reconcile after it.
- Every new label-matching or geometry rule is scoped to `CreditCardSummaryExtractor.java` only.
  `StatementSummaryExtractor.normalize()`/`rowBelow()`/`groupIntoRows()` are read but never
  modified in this plan.
- Every test fixture is synthetic, built via this file's own `run()`/`runOnPage()` helpers,
  reproducing an observed *shape* (confirmed against the real corpus during investigation) — never
  a real document's literal label text, coordinates, or figures.

---

### Task 1: gate loosening — `totalAmountDue` independent of full reconciliation

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/CreditCardSummaryExtractor.java`
- Test: `backend/src/test/java/com/finora/imports/pdf/CreditCardSummaryExtractorTest.java`

**Interfaces:**
- Consumes: `CreditCardSummaryEvidence.totalAmountDue()` from both the `grid` and `sameRow`
  intermediate results already computed in `extract()` (line ~182-183).
- Produces: `extract()`'s returned `CreditCardSummaryEvidence` now has `totalAmountDue()` populated
  whenever it is confidently known from either strategy alone, EVEN IF `previousBalance`/
  `purchases`/`paymentsAndCredits` are not. `hasReconcilableFields()` itself is UNCHANGED (still
  reads the same four fields with the same all-non-null contract) — Tasks 2-5 below are the ones
  that make more documents' `grid`/`sameRow` intermediate results actually contain a
  `totalAmountDue` value for this task to pick up; this task only changes how `extract()` uses
  whatever those two already produce.

- [ ] **Step 1: Write the failing tests**

Add to `CreditCardSummaryExtractorTest.java`:

```java
@Test
void totalAmountDueSurfacesAlone_whenOnlyOneStrategyFoundIt_evenWithoutFullReconciliation() {
    // GRID finds ONLY totalAmountDue on this page (no previous balance, purchases, or payments
    // printed alongside it) -- a real shape: some statements' top summary prints just the
    // headline total next to a due date, with no component breakdown anywhere.
    List<PositionedText> runs = new ArrayList<>(List.of(
            run("Total Amount Due", 50f, 100f, 200f),
            run("13,100.00", 55f, 60f, 230f)));

    var summary = CreditCardSummaryExtractor.extract(runs);

    assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
    assertThat(summary.hasReconcilableFields())
            .as("the other three fields are genuinely absent -- reconciliation must still refuse")
            .isFalse();
}

@Test
void totalAmountDueStaysNull_whenTheTwoStrategiesDisagree() {
    // GRID resolves a value from a clean stacked grid on page 0; INLINE_LABEL_VALUE separately
    // resolves a DIFFERENT value from an unrelated same-row match on page 1 (the shape of a real
    // illustrative worked-example section elsewhere in a statement). Genuine disagreement --
    // per the explicit scope decision, this stays unresolved rather than guessing a winner.
    List<PositionedText> runs = new ArrayList<>(List.of(
            run("Total Amount Due", 50f, 100f, 200f),
            run("13,100.00", 55f, 60f, 230f),
            runOnPage("Total Amount Due", 50f, 100f, 500f, 1),
            runOnPage("9,999.00", 55f, 60f, 500f, 1)));

    var summary = CreditCardSummaryExtractor.extract(runs);

    assertThat(summary.totalAmountDue()).isNull();
    assertThat(summary.conflictingFields()).contains("totalAmountDue");
}

@Test
void totalAmountDueSurfaces_whenTheTwoStrategiesAgree() {
    // A genuinely different shape per page, each strategy resolving the SAME amount from its own
    // page independently: page 0 is a stacked grid (label y=200, value row y=230 -- GRID's
    // shape, too far apart in y for SAME_ROW's 3pt tolerance); page 1 is a same-row layout
    // (label and value both y=200 -- SAME_ROW's shape; GRID finds nothing there, since there is
    // no second row on that page for rowBelow to pair it with). Both land on the identical
    // figure, so this exercises the TRUE agreement branch, not just "one strategy silent."
    List<PositionedText> runs = new ArrayList<>(List.of(
            run("Total Amount Due", 50f, 100f, 200f),
            run("13,100.00", 55f, 60f, 230f),
            runOnPage("Total Amount Due", 50f, 100f, 200f, 1),
            runOnPage("13,100.00", 160f, 60f, 200f, 1)));

    var summary = CreditCardSummaryExtractor.extract(runs);

    assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
    assertThat(summary.conflictingFields())
            .as("equal values across strategies must never register as a conflict")
            .doesNotContain("totalAmountDue");
}

@Test
void aFullyReconciledDocumentIsUnaffected() {
    // Guards against Task 1 accidentally changing AU's already-passing, already-tested shape.
    var summary = CreditCardSummaryExtractor.extract(cleanSummaryBlock());

    assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
    assertThat(summary.hasReconcilableFields()).isTrue();
}
```

- [ ] **Step 2: Run tests to verify the new ones fail**

Run: `cd backend && ./mvnw -q test -Dtest=CreditCardSummaryExtractorTest`
Expected: `totalAmountDueSurfacesAlone_...` and `totalAmountDueSurfaces_whenTheTwoStrategiesAgree`
FAIL (both currently return `null` — `hasReconcilableFields()` is false on this page, so
`chosen = NONE`). `totalAmountDueStaysNull_whenTheTwoStrategiesDisagree` and
`aFullyReconciledDocumentIsUnaffected` PASS already (today's actual behavior already matches them —
confirming Step 1 didn't accidentally assert something already true everywhere).

- [ ] **Step 3: Implement**

In `CreditCardSummaryExtractor.extract()`, after the existing `chosen` selection block and before
the `conflicts` handling (around line 195):

```java
CreditCardSummaryEvidence chosen;
if (grid.hasReconcilableFields()) {
    chosen = grid;
    if (ctx != null) ctx.record("CREDIT_CARD_SUMMARY_TOTALS");
} else if (sameRow.hasReconcilableFields()) {
    chosen = sameRow;
    if (ctx != null) ctx.record("CREDIT_CARD_SUMMARY_INLINE_LABEL_VALUE");
} else {
    chosen = CreditCardSummaryEvidence.NONE;
}

if (chosen.totalAmountDue() == null) {
    BigDecimal bestEffort = bestEffortTotalAmountDue(grid, sameRow);
    if (bestEffort != null) {
        chosen = new CreditCardSummaryEvidence(chosen.previousBalance(), chosen.purchases(),
                chosen.cashAdvances(), chosen.fees(), chosen.paymentsAndCredits(),
                bestEffort, chosen.extractionMethod(), chosen.conflictingFields());
    }
}

if (conflicts.isEmpty()) return chosen;
```

Add the new helper near `disagree()`:

```java
/**
 * {@code totalAmountDue} alone, independent of whether the other three reconciliation fields are
 * present -- see this class's own "gate loosening" note above. Only when the two strategies agree
 * or one is silent; a genuine disagreement stays null, the same "refuse rather than guess"
 * discipline {@link #conflictsBetween} already applies. Explicit scope decision: this deliberately
 * does NOT prefer one strategy's reading over the other's when they conflict -- that would be a
 * precedence rule generalised from a single document's evidence, not yet validated against a
 * second one.
 */
private static BigDecimal bestEffortTotalAmountDue(CreditCardSummaryEvidence grid,
                                                     CreditCardSummaryEvidence sameRow) {
    BigDecimal g = grid.totalAmountDue();
    BigDecimal s = sameRow.totalAmountDue();
    if (g == null) return s;
    if (s == null) return g;
    return g.compareTo(s) == 0 ? g : null;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=CreditCardSummaryExtractorTest`
Expected: PASS, all tests including every pre-existing one in this file (20 tests before this task;
confirm the count only grows, nothing pre-existing flips).

- [ ] **Step 5: Re-verify the two validators are unaffected**

Run: `cd backend && ./mvnw -q test -Dtest=CreditCardStatementTotalsValidatorTest,CreditCardFlowReconciliationValidatorTest`
(confirmed exact names — `backend/src/test/java/com/finora/imports/CreditCardStatementTotalsValidatorTest.java`
and `.../CreditCardFlowReconciliationValidatorTest.java`)
Expected: PASS, unchanged — both validators still gate on `hasReconcilableFields()`, which Task 1
never touches.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/CreditCardSummaryExtractor.java backend/src/test/java/com/finora/imports/pdf/CreditCardSummaryExtractorTest.java
git commit -m "feat(imports): surface totalAmountDue independent of full reconciliation"
```

---

### Task 2: accept a duplicate label when every occurrence agrees

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/CreditCardSummaryExtractor.java`
- Test: `backend/src/test/java/com/finora/imports/pdf/CreditCardSummaryExtractorTest.java`

**Interfaces:**
- Consumes: `resolvedByKey: Map<String, List<PositionedText>>` — already built by both `tryGrid` and
  `trySameRow` before calling `onlyUnambiguous`.
- Produces: `onlyUnambiguous()` now accepts a key with more than one resolved occurrence when every
  occurrence's amount is identical, in addition to its existing single-occurrence acceptance. A key
  with disagreeing occurrences is still refused — unchanged.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aDuplicateLabelIsAcceptedWhenEveryOccurrenceAgrees() {
    // Two occurrences of the same label on one page, same value both times -- a bank printing
    // its own total under two different footnote markers/wordings for the identical figure.
    List<PositionedText> runs = new ArrayList<>(List.of(
            run("Previous Balance", 50f, 90f, 300f),
            run("Purchases", 150f, 60f, 300f),
            run("Payments / Credits", 340f, 90f, 300f),
            run("Total Amount Due", 440f, 90f, 300f),
            run("10,000.00", 55f, 40f, 330f),
            run("5,000.00", 155f, 40f, 330f),
            run("2,000.00", 345f, 40f, 330f),
            run("13,000.00", 445f, 40f, 330f),
            run("Total Amount Due", 440f, 90f, 400f),
            run("13,000.00", 445f, 40f, 430f)));

    var summary = CreditCardSummaryExtractor.extract(runs);

    assertThat(summary.totalAmountDue()).isEqualByComparingTo("13000.00");
    assertThat(summary.hasReconcilableFields()).isTrue();
}

@Test
void aDuplicateLabelStillRefusesWhenOccurrencesDisagree() {
    // Same shape as above, but the second occurrence's value differs -- must remain refused,
    // unchanged from today's behaviour (this is the existing test this task must not break, made
    // explicit).
    List<PositionedText> runs = new ArrayList<>(List.of(
            run("Previous Balance", 50f, 90f, 300f),
            run("Purchases", 150f, 60f, 300f),
            run("Payments / Credits", 340f, 90f, 300f),
            run("Total Amount Due", 440f, 90f, 300f),
            run("10,000.00", 55f, 40f, 330f),
            run("5,000.00", 155f, 40f, 330f),
            run("2,000.00", 345f, 40f, 330f),
            run("13,000.00", 445f, 40f, 330f),
            run("Total Amount Due", 440f, 90f, 400f),
            run("999.00", 445f, 40f, 430f)));

    var summary = CreditCardSummaryExtractor.extract(runs);

    assertThat(summary.totalAmountDue()).isNull();
    assertThat(summary.hasReconcilableFields()).isFalse();
}
```

- [ ] **Step 2: Run tests to verify the first fails, the second already passes**

Run: `cd backend && ./mvnw -q test -Dtest=CreditCardSummaryExtractorTest`
Expected: `aDuplicateLabelIsAcceptedWhenEveryOccurrenceAgrees` FAILS (today refuses any duplicate
unconditionally). `aDuplicateLabelStillRefusesWhenOccurrencesDisagree` PASSES already.

- [ ] **Step 3: Implement**

Replace `onlyUnambiguous`:

```java
/** Accepts a key only when exactly one label occurrence resolved a value for it, OR when more
 *  than one did and every occurrence resolved to the IDENTICAL amount -- redundancy (the same
 *  figure printed twice under different wording or footnote markers), not ambiguity. Occurrences
 *  that disagree are still refused, unchanged: two different numbers under one label is real
 *  ambiguity, which this class already refuses rather than guesses at everywhere else. Shared by
 *  both strategies so this is one rule, not two that could drift apart. */
private static Map<String, PositionedText> onlyUnambiguous(Map<String, List<PositionedText>> resolvedByKey) {
    Map<String, PositionedText> labelled = new LinkedHashMap<>();
    for (Map.Entry<String, List<PositionedText>> entry : resolvedByKey.entrySet()) {
        List<PositionedText> occurrences = entry.getValue();
        if (occurrences.size() == 1 || allOccurrencesAgree(occurrences)) {
            labelled.put(entry.getKey(), occurrences.get(0));
        }
    }
    return labelled;
}

private static boolean allOccurrencesAgree(List<PositionedText> occurrences) {
    BigDecimal first = amount(occurrences.get(0));
    if (first == null) return false;
    for (PositionedText t : occurrences) {
        BigDecimal a = amount(t);
        if (a == null || a.compareTo(first) != 0) return false;
    }
    return true;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=CreditCardSummaryExtractorTest`
Expected: PASS, all tests.

- [ ] **Step 5: Re-verify the two validators are unaffected** (same command as Task 1 Step 5)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/CreditCardSummaryExtractor.java backend/src/test/java/com/finora/imports/pdf/CreditCardSummaryExtractorTest.java
git commit -m "feat(imports): accept a duplicate summary label when every occurrence agrees"
```

---

### Task 3: strip footnote/parenthetical decoration before label matching (SBI)

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/CreditCardSummaryExtractor.java`
- Test: `backend/src/test/java/com/finora/imports/pdf/CreditCardSummaryExtractorTest.java`

**Interfaces:**
- Consumes: the already-`StatementSummaryExtractor.normalize()`-normalized label text inside
  `keyFor()`.
- Produces: `keyFor()` matches a label carrying a leading footnote marker (`*`, `**`, ...) and/or a
  trailing parenthetical annotation, in addition to its already-exact form. Deliberately local to
  this class — `StatementSummaryExtractor.normalize()` itself is never touched (see Global
  Constraints).

- [ ] **Step 1: Write the failing tests**

```java
@Test
void aFootnoteMarkedTotalDueLabelStillMatches() {
    // A real shape: some statements print an asterisk before "Total Amount Due" pointing to a
    // footnote, and/or a currency-symbol placeholder in parens after it.
    List<PositionedText> runs = new ArrayList<>(List.of(
            run("*Total Amount Due ( `)", 50f, 130f, 200f),
            run("13,100.00", 55f, 60f, 230f)));

    var summary = CreditCardSummaryExtractor.extract(runs);

    assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
}

@Test
void decorationStrippingDoesNotCreateAFalseMatchForAnUnrelatedLabel() {
    // Guards against over-generalising the strip: an unrelated label that happens to end in a
    // parenthetical must still not match anything.
    List<PositionedText> runs = new ArrayList<>(List.of(
            run("Total Outstanding (Principal)", 50f, 140f, 200f),
            run("13,100.00", 55f, 60f, 230f)));

    var summary = CreditCardSummaryExtractor.extract(runs);

    assertThat(summary.totalAmountDue()).isNull();
}
```

- [ ] **Step 2: Run tests to verify the first fails, the second already passes**

Run: `cd backend && ./mvnw -q test -Dtest=CreditCardSummaryExtractorTest`
Expected: `aFootnoteMarkedTotalDueLabelStillMatches` FAILS. `decorationStrippingDoesNotCreateAFalseMatchForAnUnrelatedLabel`
PASSES already (confirms the baseline before Step 3 changes anything).

- [ ] **Step 3: Implement**

```java
private static String keyFor(String normalized) {
    String stripped = stripDecoration(normalized);
    if (matches(stripped, PREVIOUS_BALANCE_LABELS)) return "previousBalance";
    if (matches(stripped, PURCHASES_LABELS)) return "purchases";
    if (matches(stripped, CASH_ADVANCE_LABELS)) return "cashAdvances";
    if (matches(stripped, FEES_LABELS) || stripped.startsWith("fee & charges")) return "fees";
    if (matches(stripped, PAYMENTS_LABELS)) return "paymentsAndCredits";
    if (matches(stripped, TOTAL_DUE_LABELS)) return "totalAmountDue";
    return null;
}

/** Some statements print a footnote marker before an otherwise-exact label, and/or a trailing
 *  parenthetical annotation after it (a currency-symbol placeholder, an abbreviation). Stripped
 *  before matching against this class's own fixed, curated label lists -- this can only ever
 *  recognise MORE of what was already an exact match one layer down; it never introduces fuzzy
 *  matching or a new false-positive category, since the stripped result still has to equal one of
 *  the fixed strings exactly. */
private static String stripDecoration(String normalized) {
    String s = normalized;
    while (s.startsWith("*")) s = s.substring(1);
    return s.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=CreditCardSummaryExtractorTest`
Expected: PASS, all tests.

- [ ] **Step 5: Re-verify the two validators are unaffected** (same command as Task 1 Step 5)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/CreditCardSummaryExtractor.java backend/src/test/java/com/finora/imports/pdf/CreditCardSummaryExtractorTest.java
git commit -m "feat(imports): match a summary label past a footnote marker or trailing parenthetical"
```

---

### Task 4: search past a non-matching intervening row for the value (Indusland)

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/CreditCardSummaryExtractor.java`
- Test: `backend/src/test/java/com/finora/imports/pdf/CreditCardSummaryExtractorTest.java`

**Interfaces:**
- Consumes: `rows: List<List<PositionedText>>` from `StatementSummaryExtractor.groupIntoRows` (used
  as-is — this task does not change row grouping itself, only what `tryGrid` does with the result).
- Produces: a new local method, `valueRowWithinGap`, used in place of
  `StatementSummaryExtractor.rowBelow` inside `tryGrid` only. Strictly widens what can be found:
  identical result to today whenever the immediate next row already qualifies (every currently
  passing document), and additionally searches further rows (still bounded by `MAX_VALUE_ROW_GAP`
  and the same-page check) when it does not.

- [ ] **Step 1: Write the failing test**

```java
@Test
void findsTheValueRowPastAnUnrelatedInterveningRow() {
    // A real shape: an unrelated marketing/notice column running down the left side of the page
    // (x=30) has text at a y-position BETWEEN the summary label and its own value, in the right
    // column (x=440+). The immediate next row by y is the unrelated column's text -- not numeric,
    // not recoverable -- so the value one row further down must still be reachable.
    List<PositionedText> runs = new ArrayList<>(List.of(
            run("Total Amount Due", 440f, 90f, 200f),
            run("Please note our updated fee schedule", 30f, 200f, 206f),   // unrelated column
            run("13,100.00", 445f, 60f, 214f)));

    var summary = CreditCardSummaryExtractor.extract(runs);

    assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -q test -Dtest=CreditCardSummaryExtractorTest`
Expected: FAIL — `rowBelow` returns the unrelated-column row (immediate next by y), which is
non-numeric and not amount-bearing-recoverable, so `tryGrid` gives up on this label entirely today.

- [ ] **Step 3: Implement**

In `tryGrid`, replace:

```java
List<PositionedText> valueRow = StatementSummaryExtractor.rowBelow(rows, i, MAX_VALUE_ROW_GAP);
if (valueRow == null) continue;
```

with:

```java
List<PositionedText> valueRow = valueRowWithinGap(rows, i, MAX_VALUE_ROW_GAP);
if (valueRow == null) continue;
```

Add the new method (near `amountBearingSubset`):

```java
/**
 * Unlike {@link StatementSummaryExtractor#rowBelow}, which this class reuses everywhere else and
 * which savings-statement parsing also depends on -- deliberately NOT touched here, see this
 * plan's Global Constraints -- this scans every subsequent row within {@code maxGap} for the
 * first one this class can actually use as a value row, rather than only ever considering the
 * literal next row.
 *
 * <p>A real statement's billing-summary widget can share a page with an unrelated column of
 * running text (a marketing notice, a footer) whose rows interleave with the widget's own by Y
 * position -- the immediate next row can belong to that unrelated column, not the widget. This
 * is a strict superset of {@code rowBelow}'s own behaviour: whenever the immediate next row
 * already qualifies, this returns that exact same row (identical to today), so it can only ever
 * recover cases {@code rowBelow} used to give up on, never change one that already worked.
 */
private static List<PositionedText> valueRowWithinGap(List<List<PositionedText>> rows, int i, float maxGap) {
    if (i + 1 >= rows.size()) return null;
    int page = rows.get(i).get(0).pageIndex();
    float labelY = rows.get(i).get(0).y();
    for (int j = i + 1; j < rows.size(); j++) {
        List<PositionedText> candidate = rows.get(j);
        if (candidate.get(0).pageIndex() != page) return null;
        if (candidate.get(0).y() - labelY > maxGap) return null;
        boolean allNumeric = candidate.stream()
                .allMatch(t -> CsvParser.parseNumeric(t.text().trim()) != null);
        if (allNumeric || amountBearingSubset(candidate) != null) return candidate;
    }
    return null;
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=CreditCardSummaryExtractorTest`
Expected: PASS, all tests, including every existing `tryGrid`-exercising test (confirms the
strict-superset claim empirically, not just by reasoning).

- [ ] **Step 5: Re-verify the two validators are unaffected** (same command as Task 1 Step 5)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/CreditCardSummaryExtractor.java backend/src/test/java/com/finora/imports/pdf/CreditCardSummaryExtractorTest.java
git commit -m "feat(imports): search past a non-matching intervening row for a grid value"
```

---

### Task 5: join adjacent same-row runs into one label (Kotak)

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/CreditCardSummaryExtractor.java`
- Test: `backend/src/test/java/com/finora/imports/pdf/CreditCardSummaryExtractorTest.java`

**Interfaces:**
- Consumes: `runs: List<PositionedText>`, the same input `trySameRow` already receives.
- Produces: `trySameRow` recognizes a label whose words are split across several adjacent
  positioned-text runs (same y, small x-gaps), not only a single run carrying the whole phrase.
  Deliberately does NOT use `groupIntoRows` (per `trySameRow`'s own existing doc comment on why —
  full-page row-grouping is what caused Indusland-shaped cross-column contamination for `tryGrid`;
  joining is scoped far tighter here, to immediately-adjacent runs only, which cannot span an
  unrelated column the way a full row-group can).

- [ ] **Step 1: Write the failing tests**

```java
@Test
void joinsAdjacentSameRowRunsIntoOneLabel() {
    // A real shape: "Total", "Amount", "Due" printed as three separate positioned-text runs
    // (individually differently styled/spaced) rather than one contiguous string, immediately
    // followed on the same row by the value.
    List<PositionedText> runs = new ArrayList<>(List.of(
            run("Total", 280f, 18f, 132f),
            run("Amount", 300.7f, 28f, 132f),
            run("Due", 331.5f, 15f, 132f),
            run("13,100.00", 435f, 60f, 132f)));

    var summary = CreditCardSummaryExtractor.extract(runs);

    assertThat(summary.totalAmountDue()).isEqualByComparingTo("13100.00");
}

@Test
void doesNotJoinRunsAcrossALargeXGap() {
    // Guards against over-generalising the join: two runs far enough apart to plausibly belong to
    // different columns must not be joined even if their concatenation would happen to match.
    List<PositionedText> runs = new ArrayList<>(List.of(
            run("Total", 30f, 18f, 132f),
            run("Amount", 500f, 28f, 132f),      // implausibly far from "Total" to be one label
            run("Due", 531f, 15f, 132f),
            run("13,100.00", 600f, 60f, 132f)));

    var summary = CreditCardSummaryExtractor.extract(runs);

    assertThat(summary.totalAmountDue()).isNull();
}

@Test
void aJoinedLabelStillRequiresExactlyOneUnambiguousCandidate() {
    // The existing "refuse on competing candidates" rule must still apply to a joined label, not
    // just a single-run one.
    List<PositionedText> runs = new ArrayList<>(List.of(
            run("Total", 280f, 18f, 132f),
            run("Amount", 300.7f, 28f, 132f),
            run("Due", 331.5f, 15f, 132f),
            run("13,100.00", 435f, 60f, 132f),
            run("14,200.00", 500f, 60f, 132f)));

    var summary = CreditCardSummaryExtractor.extract(runs);

    assertThat(summary.totalAmountDue()).isNull();
}
```

- [ ] **Step 2: Run tests to verify the first and third fail, the second already passes**

Run: `cd backend && ./mvnw -q test -Dtest=CreditCardSummaryExtractorTest`
Expected: `joinsAdjacentSameRowRunsIntoOneLabel` and `aJoinedLabelStillRequiresExactlyOneUnambiguousCandidate`
FAIL (today, no single run's text matches `keyFor`, so nothing is found — `aJoinedLabel...`
currently passes vacuously with `totalAmountDue()` null for the wrong reason; re-verify after Step 3
that it now fails for the RIGHT reason — i.e. it actually finds two competing candidates for a
correctly-joined label, not that it still can't see the label at all). `doesNotJoinRunsAcrossALargeXGap`
passes already.

- [ ] **Step 3: Implement**

Add a small record and a joining pass, then use it inside `trySameRow`:

```java
/** How far apart two adjacent runs may sit horizontally and still be considered one continued
 *  label -- tight enough to never span two genuinely different columns (Kotak's own real
 *  word-to-word gaps observed: roughly 2-20pt), unlike {@link #SAME_ROW_MAX_X_DISTANCE}, which
 *  bounds a LABEL to its VALUE, a materially larger and different distance. */
private static final float LABEL_JOIN_MAX_X_GAP = 30.0f;

/** Every maximal span of immediately-adjacent, same-row runs whose concatenation matches one of
 *  this class's own known labels -- checked incrementally so the SHORTEST matching span wins
 *  (never over-extends past a match looking for a longer one). A single run that already matches
 *  on its own is naturally included (a one-run "span"). Scoped narrowly: it can only ever
 *  recognise the same fixed, curated label vocabulary {@link #keyFor} already knows, split across
 *  runs -- it invents no new labels and matches no unrecognised text. */
private record JoinedLabel(String key, float y, int pageIndex, float x, float endX) {}

private static List<JoinedLabel> joinedLabelsOnSameRow(List<PositionedText> runs) {
    List<JoinedLabel> found = new ArrayList<>();
    List<PositionedText> sorted = runs.stream()
            .sorted(Comparator.comparingInt(PositionedText::pageIndex)
                    .thenComparingDouble(PositionedText::y)
                    .thenComparingDouble(PositionedText::x))
            .toList();
    for (int start = 0; start < sorted.size(); start++) {
        StringBuilder joined = new StringBuilder();
        PositionedText first = sorted.get(start);
        float endX = first.x();
        for (int end = start; end < sorted.size(); end++) {
            PositionedText t = sorted.get(end);
            if (t.pageIndex() != first.pageIndex() || Math.abs(t.y() - first.y()) > SAME_ROW_Y_TOLERANCE) break;
            if (end > start && t.x() - endX > LABEL_JOIN_MAX_X_GAP) break;
            joined.append(joined.isEmpty() ? "" : " ").append(t.text().trim());
            endX = t.endX();
            String key = keyFor(StatementSummaryExtractor.normalize(joined.toString()));
            if (key != null) {
                found.add(new JoinedLabel(key, first.y(), first.pageIndex(), first.x(), endX));
                break;
            }
        }
    }
    return found;
}
```

Change `trySameRow`'s label loop from iterating `runs` directly to iterating
`joinedLabelsOnSameRow(runs)`, adapting the candidate search to use the joined label's own
`endX()`/`y()`/`pageIndex()` in place of a single `PositionedText`'s:

```java
private static CreditCardSummaryEvidence trySameRow(List<PositionedText> runs) {
    Map<Integer, Map<String, List<PositionedText>>> resolvedByPageAndKey = new TreeMap<>();

    for (JoinedLabel label : joinedLabelsOnSameRow(runs)) {
        List<PositionedText> candidates = runs.stream()
                .filter(t -> t.pageIndex() == label.pageIndex())
                .filter(t -> t.x() > label.endX())
                .filter(t -> t.x() - label.endX() <= SAME_ROW_MAX_X_DISTANCE)
                .filter(t -> Math.abs(t.y() - label.y()) <= SAME_ROW_Y_TOLERANCE)
                .filter(t -> CsvParser.parseNumeric(t.text().trim()) != null)
                .sorted(Comparator.comparingDouble(t -> Math.abs(t.y() - label.y())))
                .toList();

        if (candidates.size() == 1) {
            resolvedByPageAndKey.computeIfAbsent(label.pageIndex(), p -> new LinkedHashMap<>())
                    .computeIfAbsent(label.key(), k -> new ArrayList<>()).add(candidates.get(0));
        }
    }

    return bestPageEvidence(resolvedByPageAndKey, CreditCardSummaryEvidence.ExtractionMethod.INLINE_LABEL_VALUE);
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=CreditCardSummaryExtractorTest`
Expected: PASS, all tests — including every pre-existing `trySameRow` test (a single-run label is
still found, since `joinedLabelsOnSameRow` includes the one-run span; confirm
`readsFieldsFromASameRowLabelLeftValueRightLayout`, `refusesToGuessWhenTwoCandidateAmountsCompeteForTheSameLabel`,
`requiresTheCandidateToBeToTheRightOfTheLabelNotJustNearItInY`,
`requiresTheCandidateToBeReasonablyCloseInXNotJustAnywhereOnThePage`,
`refusesADuplicateLabelRatherThanTakingTheFirstOccurrence` all still pass unchanged).

- [ ] **Step 5: Re-verify the two validators are unaffected** (same command as Task 1 Step 5)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/CreditCardSummaryExtractor.java backend/src/test/java/com/finora/imports/pdf/CreditCardSummaryExtractorTest.java
git commit -m "feat(imports): join adjacent same-row runs into one label before matching"
```

---

### Task 6: real-corpus verification, bug-and-gap review, full suite

**Files:** none created — verification only.

- [ ] **Step 1: Re-run the diagnostic sweep against all 8 real credit-card statements**

Using the same reflection-based approach from this plan's own investigation (or a lightweight
throwaway `main()` class, deleted before committing — never commit a diagnostic tied to real
document content), confirm the actual outcome per statement now matches what each task predicted:
AU unaffected, Axis's `totalAmountDue` still correctly null (genuine conflict), Indusland/SBI/Kotak's
`totalAmountDue` now populated, HDFC/HSBC/ICICI unaffected (still null, different root causes).
Do not commit this diagnostic or any output containing real figures — describe outcomes as
pass/fail per document in the PR description, never quote a real value.

- [ ] **Step 2: Full backend suite**

Run: `cd backend && ./mvnw -q test`
Expected: PASS. If a pre-existing, unrelated flaky test fails (this project has one documented
history of `MerchantLearningNudgeIT` intermittently failing under full-suite load, unrelated to
imports), re-run it in isolation to confirm before treating the full run as green.

- [ ] **Step 3: Bug-and-gap self-review**

Read the complete diff (`git diff origin/main..HEAD`) fresh, checking specifically:
- Every new method's doc comment still accurately describes the code below it after all 5 tasks
  landed (a later task can make an earlier task's comment stale).
- No new method silently duplicates logic `StatementSummaryExtractor` already has a tested,
  documented version of.
- `bestEffortTotalAmountDue` (Task 1) and `allOccurrencesAgree` (Task 2) don't have overlapping
  responsibility that should be one function instead of two lookalikes.
- Every new synthetic test fixture's geometry is internally consistent (a label's `endX` doesn't
  overlap a value it isn't supposed to match, etc.) — rerun the tests after any fixture edit.

- [ ] **Step 4: `finishing-a-development-branch`**

Follow that skill: verify tests once more, then push and open a PR (per this project's established
pattern for this roadmap — Phases 1A/1B/2 all followed push-then-PR without a separate ask).
