# HSBC Yearless-Date Resolution Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let `PdfTableLocator`'s INFERRED_HEADERLESS_LAYOUT capability recognize a transaction
date printed with no year (`30JUN`, the real shape HSBC prints its credit-card transactions in),
by resolving the year from other full dates already visible on the same page.

**Architecture:** Two new private helpers confined entirely to the INFERRED_HEADERLESS_LAYOUT
region of `PdfTableLocator.java` (lines ~4018-4750): `yearsByPage` (scans all physical rows once
per document, recording which calendar years appear among each page's own full, year-bearing
dates) and `resolveYearlessDate` (given a `WEAK_DAY_MONTH`-shaped string and that page's candidate
years, returns the one unambiguous `LocalDate`, or `null` if zero or multiple years fit — never
guesses). Three read sites inside the headerless capability are made year-context-aware:
`isTransactionShapedRow`'s admission gate (via a new overload — the existing zero-arg call sites
elsewhere in the file are untouched, so this cannot change behaviour for any other capability),
`clusterIntoColumns`'s date-fraction scoring, and `bucketRow`'s stored value (via a
pre-substitution step so the string that reaches `TransactionNormalizer` downstream is a
`CsvParser.parseDate`-consumable full date, not the original yearless text).

**Tech Stack:** Java 21, JUnit 5, existing `PositionedText`/`PdfTableLocator` synthetic test
convention (`run(text,x,y)` / `runOnPage(text,x,width,y,page)` helpers already used by this
package's ~10 `*PdfTableLocatorTest.java` files).

**Spec:** No separate spec doc — root-caused directly against the real corpus this session (see
memory `payment-due-date-grid-extraction-fix.md` and the conversation that led here). Investigated
via a throwaway diagnostic (`ZZZDiagHsbcTest.java`, already deleted, never committed) that dumped
raw `PositionedText` for `/Users/sid/Downloads/Bank statement/Credit cards/HSBC CC.pdf`.

## Global Constraints

- Never quote real customer document text or values literally in code comments or test fixtures —
  describe the shape only (project-wide discipline, violated and caught 3 times previously this
  project's history).
- Synthetic Fixture Policy pre-commit hook: any literal-looking long digit sequence in a new test
  file must be a synthetic placeholder with a `// synthetic-ok` comment.
- `PdfTableLocator` is a Spring `@Component` singleton — nothing added here may be stored as
  instance state; all per-document context must be passed as method parameters.
- Do not touch `HEADERLESS_MIN_TRANSACTION_ROWS` (currently 3) or any other existing threshold in
  this file. It is a deliberate, separate corpus-wide safety guard — out of scope for this plan.
  **Known consequence, confirmed via direct diagnostic:** HSBC CC.pdf itself has only 2
  date+amount-shaped candidate rows even after this fix (its one real transaction, plus its
  closing-balance line which coincidentally also carries a date prefix), so it will still fail the
  3-row floor and still classify `LAYOUT_UNSUPPORTED` after this plan ships. This plan is
  infrastructure for any future document with this same yearless-date shape AND at least 3 real
  transaction rows — it does not, by itself, flip HSBC CC.pdf's row count off zero. That is a
  known, accepted limitation, not a bug to chase in this plan.
- Every new/changed method needs a doc comment following this file's own convention: state the
  real document that motivated it, what real risk it guards against, and why the chosen threshold
  or approach is what it is — this file's existing comments (see `WEAK_DAY_MONTH`,
  `HEADERLESS_MIN_TRANSACTION_ROWS`) are the model to match.

---

## File Map

- Modify: `backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java`
  - Add `yearsByPage`, `resolveYearlessDate`, `substituteYearlessDates` (new private methods, near
    the existing `WEAK_DAY_MONTH` constant around line 4149)
  - Add `isTransactionShapedRow(row, Set<Integer> candidateYears)` overload (near line 4112)
  - Modify `clusterIntoColumns` to accept and use per-page year context (near line 4365)
  - Modify `inferHeaderlessSection` to compute `yearsByPage` once and thread it through (near line
    4623)
  - Modify `bucketHeaderlessRowsWithContinuation` to accept year context and pre-substitute
    resolved dates before calling `bucketRow` (near line 4561)
- Test: `backend/src/test/java/com/finora/imports/pdf/YearlessDateResolutionTest.java` (new) — unit
  tests for `resolveYearlessDate` in isolation, plus a synthetic end-to-end headerless-document test
  proving 3 yearless-dated rows now bucket correctly with resolved full dates.
- Test: `backend/src/test/java/com/finora/imports/pdf/HsbcCreditCardYearlessDateRegressionTest.java`
  (new) — real-trace regression proving the resolution logic works against actual HSBC CC
  positioned-text geometry (captured via `scripts/trace-capture.sh`), scoped to what's actually
  true post-fix: the date resolves correctly in isolation; the document's row count assertion
  documents (does not chase) the known 3-row-floor limitation.
- New fixture: `backend/src/test/resources/traces/hsbc-credit-card-yearless-dates.trace`

---

## Task 1: `yearsByPage` and `resolveYearlessDate`

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java`
- Test: `backend/src/test/java/com/finora/imports/pdf/YearlessDateResolutionTest.java`

**Interfaces:**
- Produces: `private Map<Integer, Set<Integer>> yearsByPage(List<List<PositionedText>> rows)` —
  page index to the set of calendar years seen among that page's own full, year-bearing dates.
- Produces: `private LocalDate resolveYearlessDate(String text, Set<Integer> candidateYears)` —
  resolves a `WEAK_DAY_MONTH`-shaped string to the one unambiguous `LocalDate`, or `null`.

- [ ] **Step 1: Write the failing tests**

Create `backend/src/test/java/com/finora/imports/pdf/YearlessDateResolutionTest.java`:

```java
package com.finora.imports.pdf;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PdfTableLocator#resolveYearlessDate} and {@link PdfTableLocator#yearsByPage} in
 * isolation, via package-private test-only accessors. See the class-level doc comment above
 * {@code PdfTableLocator}'s {@code HEADERLESS_COLUMN_CLUSTER_TOLERANCE} for why this whole
 * capability exists: a real HSBC credit-card statement prints its one transaction's date as a
 * bare day+month with no year ("30JUN"-shaped), relying on the statement period printed
 * elsewhere on the same page to supply it.
 */
class YearlessDateResolutionTest {

    private final PdfTableLocator locator = new PdfTableLocator();

    @Test
    void resolvesADayMonthDateWhenExactlyOneCandidateYearFits() {
        LocalDate resolved = locator.resolveYearlessDateForTest("30JUN", Set.of(2026));
        assertThat(resolved).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void acceptsASpaceOrHyphenBetweenDayAndMonth() {
        assertThat(locator.resolveYearlessDateForTest("30 JUN", Set.of(2026)))
                .isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(locator.resolveYearlessDateForTest("30-JUN", Set.of(2026)))
                .isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void isCaseInsensitive() {
        assertThat(locator.resolveYearlessDateForTest("30jun", Set.of(2026)))
                .isEqualTo(LocalDate.of(2026, 6, 30));
    }

    @Test
    void returnsNullWhenNoCandidateYearIsGiven() {
        assertThat(locator.resolveYearlessDateForTest("30JUN", Set.of())).isNull();
    }

    @Test
    void returnsNullWhenTheTextIsNotDayMonthShaped() {
        assertThat(locator.resolveYearlessDateForTest("BBPS PMT", Set.of(2026))).isNull();
        assertThat(locator.resolveYearlessDateForTest("1,582.00", Set.of(2026))).isNull();
        // A full date already carries its own year -- this method's contract is yearless input
        // only, so a full date is not something it resolves (isTransactionShapedRow's own
        // CsvParser.parseDate check already handles this shape; overlapping responsibility here
        // would be redundant, not incorrect, but confuses which check owns which shape).
        assertThat(locator.resolveYearlessDateForTest("30 JUN 2026", Set.of(2026))).isNull();
    }

    @Test
    void returnsNullWhenTheDayMonthCombinationIsNotACalendarDate() {
        // 2026 is not a leap year -- 29 Feb 2026 does not exist. Must not silently coerce to 28
        // Feb the way java.time's SMART resolver style would (see this session's
        // StatementTitleDateRangeExtractor fix for the same class of bug).
        assertThat(locator.resolveYearlessDateForTest("29FEB", Set.of(2026))).isNull();
    }

    @Test
    void resolvesAFebTwentyNinthInALeapYearCandidate() {
        assertThat(locator.resolveYearlessDateForTest("29FEB", Set.of(2028)))
                .isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    void returnsNullWhenTwoCandidateYearsBothProduceAValidDate() {
        // Ambiguous: both 2025 and 2026 have a real 30 June. Never guess -- see this file's
        // established "fail-safe over fabrication" discipline.
        assertThat(locator.resolveYearlessDateForTest("30JUN", Set.of(2025, 2026))).isNull();
    }

    @Test
    void yearsByPageGroupsFullDatesByTheirOwnPageIndependently() {
        List<PositionedText> page0Row = List.of(
                new PositionedText("24 JUN 2026", 10f, 10f, 0));
        List<PositionedText> page1Row = List.of(
                new PositionedText("01 JAN 2025", 10f, 10f, 1));
        Map<Integer, Set<Integer>> byPage =
                locator.yearsByPageForTest(List.of(page0Row, page1Row));
        assertThat(byPage.get(0)).containsExactly(2026);
        assertThat(byPage.get(1)).containsExactly(2025);
    }

    @Test
    void yearsByPageIgnoresCellsThatAreNotFullDates() {
        List<PositionedText> row = List.of(
                new PositionedText("30JUN", 10f, 10f, 0),
                new PositionedText("BBPS PMT", 40f, 10f, 0),
                new PositionedText("24 JUN 2026", 90f, 10f, 0));
        Map<Integer, Set<Integer>> byPage = locator.yearsByPageForTest(List.of(row));
        assertThat(byPage.get(0)).containsExactly(2026);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw -q -Dtest=YearlessDateResolutionTest test`
Expected: compile failure — `resolveYearlessDateForTest`/`yearsByPageForTest` don't exist yet.

- [ ] **Step 3: Implement `yearsByPage` and `resolveYearlessDate` in `PdfTableLocator`**

Add near the existing `WEAK_DAY_MONTH` constant (around line 4149), inside the
`INFERRED_HEADERLESS_LAYOUT` region:

```java
// Small, explicit map rather than Month.valueOf(String) -- java.time's Month enum only parses
// full English names ("JUNE"), not the three-letter abbreviations WEAK_DAY_MONTH matches
// ("JUN"), and TextStyle-based lookups pull in a Locale-dependent parser this file has
// deliberately avoided everywhere else (see DATE_FORMAT's own Locale.ENGLISH pin in
// StatementTitleDateRangeExtractor for why: locale-implicit parsing is exactly the kind of bug
// class this project keeps re-discovering).
private static final Map<String, Integer> MONTH_ABBREVIATIONS = Map.ofEntries(
        Map.entry("JAN", 1), Map.entry("FEB", 2), Map.entry("MAR", 3), Map.entry("APR", 4),
        Map.entry("MAY", 5), Map.entry("JUN", 6), Map.entry("JUL", 7), Map.entry("AUG", 8),
        Map.entry("SEP", 9), Map.entry("OCT", 10), Map.entry("NOV", 11), Map.entry("DEC", 12));

/**
 * Page index to the set of calendar years appearing among that page's own full, year-bearing
 * dates ({@link CsvParser#parseDate} already succeeds on these -- this does not introduce a new
 * date shape, only collects the year off ones already recognized). Scoped per page, not per
 * document: a statement whose transaction table spans a year boundary must not let one page's
 * year silently leak onto another page's yearless dates.
 *
 * <p>Computed once per document, from every physical row {@link #inferHeaderlessSection} already
 * has in hand -- not just the transaction-shaped candidates, since the full dates that supply
 * year context (a statement period, a payment due date) live in the surrounding account-summary
 * rows this capability's candidate filter deliberately excludes.
 */
private Map<Integer, Set<Integer>> yearsByPage(List<List<PositionedText>> rows) {
    Map<Integer, Set<Integer>> result = new HashMap<>();
    for (List<PositionedText> row : rows) {
        for (PositionedText cell : row) {
            LocalDate parsed = CsvParser.parseDate(cell.text().trim());
            if (parsed != null) {
                result.computeIfAbsent(cell.pageIndex(), k -> new HashSet<>()).add(parsed.getYear());
            }
        }
    }
    return result;
}

/**
 * Resolves a {@link #WEAK_DAY_MONTH}-shaped string ("30JUN", no year) to the one calendar date
 * it unambiguously names, using {@code candidateYears} -- typically the years already seen among
 * other full dates on the same page (see {@link #yearsByPage}). Motivated by a real HSBC
 * credit-card statement whose one transaction that cycle prints its date exactly this way,
 * relying on the statement period printed elsewhere on the same page to supply the year.
 *
 * <p>Never guesses: returns {@code null} when zero candidate years produce a valid calendar date
 * (e.g. "29FEB" against a non-leap year) or when two or more candidate years each produce a
 * DIFFERENT valid date (genuinely ambiguous -- a statement spanning a year boundary could see
 * both "31DEC" and "01JAN" become resolvable against either year without this check). Matches
 * this file's "fail-safe over fabrication" discipline established throughout the headerless-layout
 * capability: see this capability's own top-level doc comment above {@link
 * #HEADERLESS_COLUMN_CLUSTER_TOLERANCE}.
 *
 * <p>Deliberately separate from {@link CsvParser#parseDate}, not an addition to it -- see {@link
 * #WEAK_DAY_MONTH}'s own doc comment for why a yearless pattern stays local to this evidence-gated
 * capability rather than becoming a general date shape every caller of {@code CsvParser} would
 * then also accept.
 */
private LocalDate resolveYearlessDate(String text, Set<Integer> candidateYears) {
    java.util.regex.Matcher m = WEAK_DAY_MONTH.matcher(text.trim());
    if (!m.matches()) return null;
    int day = Integer.parseInt(text.trim().replaceAll("[\\s-]?[A-Za-z]+$", ""));
    String monthAbbrev = m.group(1).toUpperCase(Locale.ENGLISH);
    Integer month = MONTH_ABBREVIATIONS.get(monthAbbrev);
    if (month == null) return null;

    Set<LocalDate> resolved = new HashSet<>();
    for (int year : candidateYears) {
        try {
            resolved.add(LocalDate.of(year, month, day));
        } catch (java.time.DateTimeException notARealCalendarDate) {
            // e.g. 29 Feb against a non-leap year -- excluded, not coerced.
        }
    }
    return resolved.size() == 1 ? resolved.iterator().next() : null;
}
```

Add test-only package-private accessors right below (or in a `@VisibleForTesting`-style comment
block, matching this file's existing convention if one exists — check for a precedent before
adding a new one):

```java
/** Test-only accessor -- see {@link #resolveYearlessDate}. */
LocalDate resolveYearlessDateForTest(String text, Set<Integer> candidateYears) {
    return resolveYearlessDate(text, candidateYears);
}

/** Test-only accessor -- see {@link #yearsByPage}. */
Map<Integer, Set<Integer>> yearsByPageForTest(List<List<PositionedText>> rows) {
    return yearsByPage(rows);
}
```

Add required imports if not already present: `java.util.HashMap`, `java.util.HashSet`,
`java.util.Set`, `java.time.DateTimeException` (or fully-qualify inline as shown above to avoid an
import collision — check the file's existing import block first).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q -Dtest=YearlessDateResolutionTest test`
Expected: PASS, all cases.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java \
        backend/src/test/java/com/finora/imports/pdf/YearlessDateResolutionTest.java
git commit -m "feat(imports): resolve yearless day-month dates from same-page year context"
```

---

## Task 2: Wire year context into `isTransactionShapedRow` and `clusterIntoColumns`

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java`
- Test: `backend/src/test/java/com/finora/imports/pdf/YearlessDateResolutionTest.java` (extend)

**Interfaces:**
- Consumes: `resolveYearlessDate(String, Set<Integer>)` from Task 1.
- Produces: `private boolean isTransactionShapedRow(List<PositionedText> row, Set<Integer>
  candidateYears)` overload. The existing zero-arg-context `isTransactionShapedRow(row)` at line
  4112 becomes a thin wrapper calling this with `Set.of()` — so its behavior for the two call
  sites NOT touched by this plan (line 1304's `pageLegendBlockActive` resume check, and line 4138's
  `recordIfTransactionShaped` evidence recorder) is byte-for-byte unchanged.
- Produces: `clusterIntoColumns(List<List<PositionedText>> transactionRows, Map<Integer,
  Set<Integer>> yearsByPage)` — same method, one new parameter.

- [ ] **Step 1: Write the failing test**

Add to `YearlessDateResolutionTest.java`:

```java
@Test
void transactionShapeAdmitsAYearlessDateRowWhenAYearContextIsGiven() {
    List<PositionedText> row = List.of(
            new PositionedText("30JUN", 10f, 10f, 0),
            new PositionedText("BBPS PMT reference", 40f, 10f, 0),
            new PositionedText("1,582.00", 200f, 10f, 0));
    assertThat(locator.isTransactionShapedRowForTest(row, Set.of())).isFalse();
    assertThat(locator.isTransactionShapedRowForTest(row, Set.of(2026))).isTrue();
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q -Dtest=YearlessDateResolutionTest test`
Expected: FAIL — `isTransactionShapedRowForTest` not defined, or the true case returns false.

- [ ] **Step 3: Implement the overload and thread it into `clusterIntoColumns`**

Replace the existing `isTransactionShapedRow` (around line 4112) with:

```java
private boolean isTransactionShapedRow(List<PositionedText> row) {
    return isTransactionShapedRow(row, Set.of());
}

/** Same contract as {@link #isTransactionShapedRow(List)}, plus {@code candidateYears} --
 *  see {@link #resolveYearlessDate}. Kept as a genuine overload rather than changing the
 *  single-argument signature everywhere: this file has four call sites for the plain form (the
 *  pageLegendBlockActive resume check and the dropped-candidate evidence recorder among them),
 *  and only the two INFERRED_HEADERLESS_LAYOUT call sites this plan touches have year context
 *  worth passing. An empty candidate set makes {@link #resolveYearlessDate} always return null,
 *  so the plain overload's behaviour for every untouched call site is unchanged. */
private boolean isTransactionShapedRow(List<PositionedText> row, Set<Integer> candidateYears) {
    boolean hasDate = false;
    boolean hasAmount = false;
    for (PositionedText cell : row) {
        String text = cell.text().trim();
        if (!hasDate && (CsvParser.parseDate(text) != null
                || resolveYearlessDate(text, candidateYears) != null)) hasDate = true;
        if (!hasAmount && text.contains(".") && CsvParser.parseNumeric(text) != null) hasAmount = true;
    }
    return hasDate && hasAmount;
}

/** Test-only accessor -- see {@link #isTransactionShapedRow(List, Set)}. */
boolean isTransactionShapedRowForTest(List<PositionedText> row, Set<Integer> candidateYears) {
    return isTransactionShapedRow(row, candidateYears);
}
```

In `clusterIntoColumns` (around line 4365), add a `Map<Integer, Set<Integer>> yearsByPage`
parameter and change the date-counting line (originally line 4404):

```java
private List<ColumnStats> clusterIntoColumns(List<List<PositionedText>> transactionRows,
        Map<Integer, Set<Integer>> yearsByPage) {
    // ... unchanged body until the per-cell loop ...
    for (PositionedText cell : group) {
        String text = cell.text().trim();
        repLeft = Math.min(repLeft, cell.x());
        if (CsvParser.parseNumeric(text) != null) {
            anyNumeric = true;
            repRight = Math.max(repRight, cell.endX());
            if (text.contains(".")) amountLikeCount++;
        }
        Set<Integer> pageYears = yearsByPage.getOrDefault(cell.pageIndex(), Set.of());
        if (CsvParser.parseDate(text) != null || resolveYearlessDate(text, pageYears) != null) {
            dateCount++;
        }
        wordSum += text.split("\\s+").length;
    }
    // ... unchanged tail ...
}
```

Update `clusterIntoColumns`'s own doc comment to mention the new parameter, following this file's
existing style (state why it's needed, not just what it does).

- [ ] **Step 4: Run to verify it passes**

Run: `cd backend && ./mvnw -q -Dtest=YearlessDateResolutionTest test`
Expected: PASS.

- [ ] **Step 5: Fix the now-broken call site at line 4634**

`inferHeaderlessSection` calls `clusterIntoColumns(candidates)` — update to
`clusterIntoColumns(candidates, yearsByPage)` once `yearsByPage` exists locally (Task 3 introduces
that local variable; if Task 3 hasn't landed yet in your working state, pass `Map.of()` as a
placeholder here and let Task 3's edit replace it — but since these are sequential steps in one
plan, just do Task 3's `yearsByPage` computation as part of this step so the build stays green;
see Task 3's Step 3 for the exact line to add).

- [ ] **Step 6: Compile-check the whole module**

Run: `cd backend && ./mvnw -q compile test-compile`
Expected: BUILD SUCCESS (Task 3 not done yet is fine as long as you pulled its one line forward
per Step 5's note — otherwise this step will fail to compile, which is expected until Task 3
lands; if so, proceed directly to Task 3 without a separate commit here).

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java \
        backend/src/test/java/com/finora/imports/pdf/YearlessDateResolutionTest.java
git commit -m "feat(imports): admit yearless-dated rows into headerless column inference"
```

---

## Task 3: Thread year context through `inferHeaderlessSection` and substitute resolved dates before bucketing

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java`
- Test: `backend/src/test/java/com/finora/imports/pdf/YearlessDateResolutionTest.java` (extend,
  end-to-end synthetic case)

**Interfaces:**
- Consumes: `yearsByPage`, `resolveYearlessDate` (Task 1); `isTransactionShapedRow(row,
  candidateYears)`, `clusterIntoColumns(rows, yearsByPage)` (Task 2).
- Produces: `inferHeaderlessSection` now resolves and substitutes yearless dates before any row
  reaches `bucketRow`, so the stored "Date" column value is always something
  `CsvParser.parseDate` can re-parse downstream in `TransactionNormalizer`.

- [ ] **Step 1: Write the failing end-to-end test**

Add to `YearlessDateResolutionTest.java` — a synthetic 3-row headerless document (mirrors the
existing SBI-motivated headerless tests' fixture style: no header vocabulary anywhere, an
account-summary line with a full date supplying year context, then 3 date+narration+amount rows
using the yearless shape):

```java
@Test
void headerlessInferenceResolvesYearlessDatesAcrossThreeTransactionRows() {
    List<List<PositionedText>> rows = List.of(
            // Account-summary furniture -- carries the only full (year-bearing) date on the page.
            List.of(new PositionedText("Statement period", 50f, 10f, 0),
                    new PositionedText("24 JUN 2026 To 23 JUL 2026", 200f, 10f, 0)),
            List.of(new PositionedText("Opening Balance", 50f, 30f, 0),
                    new PositionedText("1000.00", 300f, 30f, 0)),
            // Three real, yearless-dated transaction rows.
            List.of(new PositionedText("25JUN", 50f, 50f, 0),
                    new PositionedText("Merchant Payment One", 100f, 50f, 0),
                    new PositionedText("1582.00", 300f, 50f, 0)),
            List.of(new PositionedText("30JUN", 50f, 70f, 0),
                    new PositionedText("Merchant Payment Two", 100f, 70f, 0),
                    new PositionedText("240.00", 300f, 70f, 0)),
            List.of(new PositionedText("05JUL", 50f, 90f, 0),
                    new PositionedText("Merchant Payment Three", 100f, 90f, 0),
                    new PositionedText("99.00", 300f, 90f, 0)));

    PdfTableLocator.LocatedDocument doc = locator.locateAll(flattenForFullDocument(rows), null);

    assertThat(doc.sections()).hasSize(1);
    List<Map<String, String>> stagedRows = doc.sections().get(0).rows();
    assertThat(stagedRows).hasSize(3);
    assertThat(stagedRows).extracting(r -> r.get("Date"))
            .allSatisfy(dateText -> assertThat(CsvParser.parseDate(dateText)).isNotNull());
}
```

This test needs a `List<PositionedText>` for the whole document, not
`List<List<PositionedText>>` — check `PdfTableLocatorTest`'s existing `run(...)` helper convention
for how physical rows already get flattened/regrouped by `locateAll` internally (it re-derives
rows from `y` clustering, it does not take pre-grouped rows as public input). Write a small local
`flattenForFullDocument` helper that just concatenates every row's cells into one flat list (the
same order `locateAll` expects from `PdfTextExtractor.extract`), OR — preferably — check whether
an existing `*PdfTableLocatorTest.java` file already has a reusable synthetic-document builder for
this shape and reuse it instead of writing a new one. **Read at least one existing headerless-path
test file in this package before writing this step**, since the exact input shape `locateAll`
expects must match established convention, not be guessed.

- [ ] **Step 2: Run to verify it fails**

Run: `cd backend && ./mvnw -q -Dtest=YearlessDateResolutionTest test`
Expected: FAIL — either 0 sections (yearsByPage not threaded yet) or `stagedRows` still carrying
raw `"25JUN"`-shaped text that fails `CsvParser.parseDate`.

- [ ] **Step 3: Compute `yearsByPage` once in `inferHeaderlessSection` and thread it**

In `inferHeaderlessSection` (around line 4623), right after the method's opening:

```java
private LocatedSection inferHeaderlessSection(List<List<PositionedText>> rows, DocumentContext ctx) {
    Map<Integer, Set<Integer>> yearsByPage = yearsByPage(rows);
    List<List<PositionedText>> candidates = new ArrayList<>();
    for (List<PositionedText> row : rows) {
        Set<Integer> rowYears = row.isEmpty() ? Set.of()
                : yearsByPage.getOrDefault(row.get(0).pageIndex(), Set.of());
        if (isTransactionShapedRow(row, rowYears)) candidates.add(row);
    }
    candidates = dedupeAdjacentIdenticalRows(candidates);
    if (candidates.size() < HEADERLESS_MIN_TRANSACTION_ROWS) {
        if (ctx != null) ctx.recordDiagnostic("HEADERLESS_TOO_FEW_TRANSACTION_ROWS");
        return null;
    }

    List<ColumnStats> columns = clusterIntoColumns(candidates, yearsByPage);
    // ... rest of the method unchanged until the bucketHeaderlessRowsWithContinuation call ...
```

Update the call at (originally) line 4741 from
`bucketHeaderlessRowsWithContinuation(rows, headerNames, headerAnchors, headerEnds, ctx)` to
`bucketHeaderlessRowsWithContinuation(rows, headerNames, headerAnchors, headerEnds, ctx, yearsByPage)`.

- [ ] **Step 4: Substitute resolved dates before `bucketRow` inside `bucketHeaderlessRowsWithContinuation`**

Add the `yearsByPage` parameter to `bucketHeaderlessRowsWithContinuation`'s signature (around line
4561), and add a small substitution helper used right before both existing `bucketRow(row, ...)`
calls in that method (originally lines 4598 and 4605):

```java
/**
 * Returns {@code row} unchanged unless one of its cells is {@link #WEAK_DAY_MONTH}-shaped and
 * resolves unambiguously via {@link #resolveYearlessDate} -- in which case that ONE cell is
 * replaced with an equivalent {@link PositionedText} (same x/y/page/width, so bucketing-by-
 * position is unaffected) whose text is the resolved date formatted "dd MMM yyyy", a shape
 * {@link CsvParser#parseDate} already accepts (see its own DATE_FORMATS list). Without this
 * substitution, {@link #isTransactionShapedRow}'s admission gate would accept the row but the
 * RAW yearless text would still reach {@code TransactionNormalizer} downstream via {@link
 * #bucketRow}'s stored value, which parses dates through the exact same {@link
 * CsvParser#parseDate} call and would reject it there instead -- moving the failure one layer
 * down rather than fixing it.
 */
private List<PositionedText> substituteYearlessDates(List<PositionedText> row, Set<Integer> pageYears) {
    List<PositionedText> result = null;
    for (int i = 0; i < row.size(); i++) {
        PositionedText cell = row.get(i);
        LocalDate resolved = resolveYearlessDate(cell.text().trim(), pageYears);
        if (resolved != null) {
            if (result == null) result = new ArrayList<>(row);
            String formatted = resolved.format(
                    java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH));
            result.set(i, new PositionedText(formatted, cell.x(), cell.y(), cell.pageIndex(),
                    cell.width(), cell.height(), cell.confidence(), cell.source()));
        }
    }
    return result != null ? result : row;
}
```

Update both call sites in `bucketHeaderlessRowsWithContinuation`:

```java
Set<Integer> rowYears = row.isEmpty() ? Set.of()
        : yearsByPage.getOrDefault(row.get(0).pageIndex(), Set.of());
List<PositionedText> resolvedRow = substituteYearlessDates(row, rowYears);
if (isTransactionShapedRow(resolvedRow, rowYears)) {
    // ... use resolvedRow in place of row for the bucketRow(...) call on this branch ...
    Map<String, String> bucketed = bucketRow(resolvedRow, headerNames, headerAnchors, headerEnds, ctx);
    // ...
} else if (currentAnchor != null && continuationCount < MAX_BLOCK_CONTINUATION_ROWS) {
    Map<String, String> bucketed = bucketRow(resolvedRow, headerNames, headerAnchors, headerEnds, ctx);
    // ...
}
```

Keep every OTHER use of `row` in that method (the `rowLine`/`PAGE_FOOTER`/dedup-by-line checks) on
the ORIGINAL `row`, not `resolvedRow` — those checks compare against real printed text and must
keep seeing what was actually on the page; only the two `bucketRow` calls should see the
substituted version.

- [ ] **Step 5: Run to verify the end-to-end test passes**

Run: `cd backend && ./mvnw -q -Dtest=YearlessDateResolutionTest test`
Expected: PASS.

- [ ] **Step 6: Run the full existing `PdfTableLocator` test suite for regressions**

Run: `cd backend && ./mvnw -q -Dtest='*PdfTableLocatorTest' test`
Expected: PASS, no regressions on the existing SBI-motivated headerless test or any other. If
anything fails, read the failure before changing anything — this plan's changes are additive and
gated behind non-empty candidate-year sets, so a regression here means the substitution or
threading broke an existing code path, not that an existing test's expectation was wrong.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/PdfTableLocator.java \
        backend/src/test/java/com/finora/imports/pdf/YearlessDateResolutionTest.java
git commit -m "feat(imports): substitute resolved yearless dates before headerless row bucketing"
```

---

## Task 4: Real-document regression test + full corpus re-verification

**Files:**
- Create: `backend/src/test/resources/traces/hsbc-credit-card-yearless-dates.trace` (captured, not
  hand-written)
- Test: `backend/src/test/java/com/finora/imports/pdf/HsbcCreditCardYearlessDateRegressionTest.java`

**Interfaces:**
- Consumes: `fixtures.PdfTrace.load(...)` (existing trace-loading convention, see any prior
  `*RegressionTest.java` in this package, e.g. `PaymentDueDateGridRegressionTest` from the prior
  session's work for the exact pattern to copy), `PdfTextExtractor`, `PdfTableLocator`.

- [ ] **Step 1: Capture the real trace**

Run: `./scripts/trace-capture.sh hsbc-credit-card-yearless-dates "/Users/sid/Downloads/Bank statement/Credit cards/HSBC CC.pdf"`

Follow whatever redaction/review step `trace-capture.sh` itself prints (check its `--help` output
first — read the script, don't assume flags). Confirm the resulting `.trace` file does NOT contain
the real account number, full name, or any other PII — grep it for the literal cardholder name and
masked-account digits before ever committing it. If the script's default redaction already handles
this (as it evidently did for every other trace fixture in this directory), just verify, don't
re-implement.

- [ ] **Step 2: Write the regression test**

```java
package com.finora.imports.pdf;

import fixtures.PdfTrace;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-document proof for {@link PdfTableLocator#resolveYearlessDate} (via its test-only
 * accessor): a real HSBC credit-card statement's one transaction that cycle prints its date as a
 * bare day+month with no year, and the statement period printed elsewhere on the same page
 * supplies the 2026 that resolves it.
 *
 * <p>Deliberately does NOT assert the whole document now imports a non-zero row count.
 * Confirmed via direct diagnostic during this fix's own investigation: this real document has
 * only 2 date+amount-shaped candidate rows on its whole 4 pages (the one real transaction, plus
 * its closing-balance line, which is not a transaction), one short of {@code
 * HEADERLESS_MIN_TRANSACTION_ROWS}'s deliberate 3-row floor -- a separate, existing safety guard
 * this fix does not touch. See docs/superpowers/plans/2026-09-01-hsbc-yearless-date-resolution.md
 * for the full reasoning. This test proves the date-resolution unit itself is correct against
 * real geometry; it does not claim the document parses end to end, because it still doesn't.
 */
class HsbcCreditCardYearlessDateRegressionTest {

    private final PdfTableLocator locator = new PdfTableLocator();

    @Test
    void resolvesTheRealTransactionDateAgainstThePrintedStatementPeriod() throws Exception {
        List<PositionedText> page0 = PdfTrace.load("hsbc-credit-card-yearless-dates")
                .page(0);

        var byPage = locator.yearsByPageForTest(List.of(page0));
        Set<Integer> page0Years = byPage.getOrDefault(0, Set.of());
        assertThat(page0Years).isNotEmpty();

        // The exact yearless cell text is whatever the real trace's own transaction-date run
        // reads -- re-derive it from the trace rather than hardcoding a value the trace might not
        // actually contain (traces can redact/shift text; asserting against a value not proven
        // present in THIS fixture would be a false-confidence test).
        boolean anyResolved = page0.stream()
                .anyMatch(cell -> locator.resolveYearlessDateForTest(cell.text().trim(), page0Years) != null);
        assertThat(anyResolved)
                .as("expected at least one WEAK_DAY_MONTH-shaped cell on page 0 to resolve "
                        + "against that page's own full-date year context")
                .isTrue();
    }
}
```

Check `fixtures.PdfTrace`'s actual API before finalizing this test — the `.page(0)` accessor above
is a guess at the convention; read an existing regression test in this package (e.g. the prior
session's `PaymentDueDateGridRegressionTest.java` or
`KotakSavingsTitleDateRangeRegressionTest.java`) and match its real method names exactly.

- [ ] **Step 3: Run the test**

Run: `cd backend && ./mvnw -q -Dtest=HsbcCreditCardYearlessDateRegressionTest test`
Expected: PASS. If it fails because the trace's transaction-date cell got redacted into something
that no longer matches `WEAK_DAY_MONTH` (redaction sometimes replaces digits with placeholder
digits, which could still coincidentally match, or could shift width per the SBI trace's
zero-width redaction issue noted elsewhere in this project's memory), adapt the assertion to what
the actual captured trace contains — re-read the trace file directly rather than guessing.

- [ ] **Step 4: Full backend suite**

Run: `cd backend && ./mvnw -q test 2>&1 | tail -60`
Expected: all green except the already-known, pre-existing, unrelated intermittent flake
`MerchantLearningNudgeIT` (seen earlier this session; confirmed unrelated to this change — if any
OTHER test fails, stop and investigate before proceeding).

- [ ] **Step 5: Corpus re-verification**

Run: `python3 scripts/corpus-run.py "/Users/sid/Downloads/Bank statement" -o /tmp/full-corpus-post-yearless.jsonl --quiet`
(adjust the corpus root path if the real one-flat-directory layout differs from the two
subdirectories probed during investigation — check `scripts/corpus-run.py --help` for whether it
recurses).

Confirm via a quick Python one-liner: no document's `rows` count DECREASED versus a baseline run
captured before this change (this fix is additive/conservative by construction, but corpus-wide
confirmation is this project's established discipline before calling any fix done). Also confirm
`HSBC CC.pdf` specifically still shows `rows: 0` / `LAYOUT_UNSUPPORTED` (the known, accepted,
unchanged limitation this plan documents rather than fixes) — a change here would mean an
assumption in this plan was wrong and needs re-investigation before merging.

- [ ] **Step 6: Self-review pass**

Re-read every changed method's doc comment against this file's own established standard (see
Global Constraints). Check for: stale comments referencing counts/alternatives that changed during
implementation (this session hit exactly this mistake once already, on `PdfMetadataExtractor`'s
`DATE_LIKE` comment), any accidental real-document text or values in a comment or test literal
(grep the new test files for anything that isn't clearly synthetic), and confirm the two untouched
`isTransactionShapedRow(row)` call sites (line ~1304, ~4138 in the original numbering) truly still
compile against the zero-arg overload and were not accidentally changed.

- [ ] **Step 7: Update memory**

Write a new memory file (or extend `payment-due-date-grid-extraction-fix.md`) documenting: the
HSBC CC full-document-failure discovery, the root cause (yearless transaction dates + no header
vocabulary at all), what this plan fixed (year-resolution infrastructure for the
INFERRED_HEADERLESS_LAYOUT capability) versus what it deliberately left unfixed (HSBC CC itself
stays `LAYOUT_UNSUPPORTED` / 0 rows, blocked by the separate, untouched `HEADERLESS_MIN_TRANSACTION_ROWS`
floor — a future decision, not a bug in this fix). Update `MEMORY.md`'s index with a one-line
pointer.

- [ ] **Step 8: Commit**

```bash
git add backend/src/test/resources/traces/hsbc-credit-card-yearless-dates.trace \
        backend/src/test/java/com/finora/imports/pdf/HsbcCreditCardYearlessDateRegressionTest.java
git commit -m "test(imports): real-trace regression for HSBC yearless-date resolution"
```

---

## Task 5: Push, open PR, finish the branch

**Files:** none (process only)

- [ ] **Step 1: Push**

```bash
git push -u origin worktree-payment-due-date-investigation
```

- [ ] **Step 2: Open PR**

Write the PR body to a scratchpad file first (this worktree's sandbox has previously rejected an
inline heredoc `gh pr create --body` as "too complex to verify it stays inside the worktree" —
use `--body-file` instead, per this session's own established workaround). Summarize: the HSBC CC
full-document-failure discovery (0 rows across 4 real pages, root-caused to yearless transaction
dates with zero header vocabulary anywhere in the document), what this PR adds (year-resolution
infrastructure inside `PdfTableLocator`'s existing INFERRED_HEADERLESS_LAYOUT capability, gated
behind same-page year context, never guessing when ambiguous), and explicitly what it does NOT fix
(HSBC CC.pdf itself stays at 0 rows post-merge — blocked by the separate, deliberately untouched
`HEADERLESS_MIN_TRANSACTION_ROWS` 3-row floor — flagged as a follow-up decision, not silently
left unmentioned).

```bash
gh pr create --title "feat(imports): resolve yearless day-month transaction dates from same-page year context" \
  --body-file /path/to/scratchpad/pr-body.md
```

- [ ] **Step 3: Report completion**

Once CI is green (or if it fails, fix per this session's established out-of-scope-vs-in-scope CI
discipline — only fix failures the diff in this PR plausibly caused; surface anything else instead
of unilaterally fixing it), stop and report the PR URL, final corpus numbers, and the explicit
HSBC CC limitation summary to the user. Do not merge without being asked.
