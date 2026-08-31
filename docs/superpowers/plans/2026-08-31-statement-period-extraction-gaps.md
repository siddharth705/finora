# Statement Period Extraction Gaps (Groups A-E) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close 5 of 6 root-caused gaps in `statementPeriodStart`/`statementPeriodEnd` extraction
(`backend/src/main/java/com/finora/imports/pdf/PdfMetadataExtractor.java`), recovering 12 of the 13
real documents currently missing this field (2/15 → 14/15 corpus-wide), by adding narrow,
additive, evidence-backed patterns for 5 real label/shape variants no current pattern covers.
Group F (a bare, unlabeled date range on `new kotak.pdf`) is explicitly out of scope — it has no
adjacent label text, carries materially higher false-positive risk, and needs positional evidence
before any fix is attempted, not a vocabulary addition.

**Architecture:** Every existing statement-period pattern in this file routes through one shared
helper, `parsePeriod(String)`, which takes a single string containing a date range and returns
`LocalDate[]{start, end}`, both non-null or both null (never a half-committed pair). All 5 new
patterns follow this same convention — each captures (or is joined into) one range string and
calls the existing `parsePeriod`, rather than inventing new date-parsing logic. Each is checked in
the same shared `for` loop `extract(List<String>, DocumentContext)` already iterates, guarded by
`if (periodStart == null && periodEnd == null)` so the first successful match wins and later checks
are skipped once a period is found — identical to how `STATEMENT_PERIOD_ANYWHERE` and
`STATEMENT_PERIOD_IN_SENTENCE` already work.

**Tech Stack:** Java 21, JUnit 5, AssertJ. Test fixtures are plain `List<String>` (no PDF bytes,
no `PositionedText`) — the same convention `PdfMetadataExtractorTest.java` already uses throughout.

**Spec:** No separate spec document. Full root-cause investigation (corpus survey, per-document
`PositionedText`-level verification for the ambiguous case) is recorded in memory file
`fresh-metadata-sweep-2026-08-31.md` (outside the repo). This plan is self-contained.

## Global Constraints

- Never quote real document text verbatim in code, comments, or tests where it would expose a
  specific customer's real data — real *label/boilerplate* phrasing (bank-printed field labels,
  not customer content) is fine to describe or lightly paraphrase, matching this file's own
  existing comment style throughout (e.g. `STATEMENT_PERIOD_ANYWHERE`'s doc comment already
  describes SBI's real "for Statement Period:" phrasing). Do not use literal customer names,
  account numbers, or transaction amounts anywhere.
- Every new pattern must be purely additive: it must not narrow or change the matching behavior of
  any existing pattern (`STATEMENT_PERIOD`, `STATEMENT_PERIOD_IN_SENTENCE`,
  `STATEMENT_PERIOD_TRAILING_LABEL`, `PERIOD_RANGE_VALUE`) except where a task explicitly widens
  `STATEMENT_PERIOD_ANYWHERE` (Task C) — and that widening must only ADD an optional tolerance,
  never remove or restrict what it already matches.
- Every new pattern must route through the existing `parsePeriod(String)` helper for date parsing
  — do not write new date-parsing logic.
- Every new `ctx.record("...")` capability name must be registered in
  `backend/src/main/java/com/finora/imports/CapabilityCoverageService.java`'s
  `KNOWN_CAPABILITIES` list AND in `backend/src/test/java/com/finora/imports/
  CapabilityCorpusCoverageTest.java`'s `DECLARED_WITHOUT_A_TRACE` map (with a justification
  comment matching the existing style — no committed trace fixture exists for any of these 5 real
  documents yet), in the same commit that introduces it. Skipping this leaves the capability
  registry silently out of sync with the engine, which this file's own charter treats as a real
  defect, not a formality.
- Do not implement Group F (the bare unlabeled date range on `new kotak.pdf`) as part of this plan.

---

### Task A: `From : DATE ... To : DATE` as two separately colon-labeled fields on one row

**Real evidence:** `HDFC 3 month.pdf`, `HDFC sav.pdf`, `Mann HDFC.pdf`, `Sanjay HDFC.pdf` — 4 real
documents. Confirmed via a `PositionedText`-level diagnostic (not guessed) that the label/value
pairs survive PDF extraction as 6 separate runs (`From`, `:`, a date token, `To`, `:`, a second
date token) that `groupIntoRows`/`lineOf` already join into one line before `PdfMetadataExtractor`
ever sees it — so a normal line-level regex with two capture groups is sufficient; no raw-run-level
parsing is needed.

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/pdf/PdfMetadataExtractor.java`
- Modify: `backend/src/main/java/com/finora/imports/CapabilityCoverageService.java`
- Modify: `backend/src/test/java/com/finora/imports/CapabilityCorpusCoverageTest.java`
- Test: `backend/src/test/java/com/finora/imports/pdf/PdfMetadataExtractorTest.java`

**Interfaces:**
- Produces: `FROM_TO_LABELED_PERIOD` (new `Pattern` constant, 2 capture groups).

- [ ] **Step 1: Write the failing test**

Add to `PdfMetadataExtractorTest.java` (reuse the file's existing `extractor`/imports; find any
existing `@Test` method for a similar pattern to place this near, e.g. next to the
`STATEMENT_PERIOD_ANYWHERE` test):

```java
/** A real HDFC savings-account statement layout (HDFC 3 month.pdf, HDFC sav.pdf, Mann HDFC.pdf,
 *  Sanjay HDFC.pdf all share this shape): the period is two separately colon-labeled fields on
 *  one row -- "From : <date>" and "To : <date>" -- not one combined "Period" label. */
@Test
void extract_recognizesAStatementPeriod_statedAsSeparateFromAndToLabeledFields() {
    var metadata = extractor.extract(List.of(
            "From : 01/05/2026 To : 31/07/2026 Statement of account"));

    assertThat(metadata.statementPeriodStart()).isEqualTo(java.time.LocalDate.of(2026, 5, 1));
    assertThat(metadata.statementPeriodEnd()).isEqualTo(java.time.LocalDate.of(2026, 7, 31));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -o test -Dtest=PdfMetadataExtractorTest#extract_recognizesAStatementPeriod_statedAsSeparateFromAndToLabeledFields`
Expected: FAIL — both fields null.

- [ ] **Step 3: Write the minimal implementation**

In `PdfMetadataExtractor.java`, add the new pattern immediately after `PERIOD_RANGE_VALUE`'s
definition (search for `private static final Pattern PERIOD_RANGE_VALUE`):

```java
    // FROM_TO_LABELED_PERIOD. Real HDFC savings-account statements (HDFC 3 month.pdf,
    // HDFC sav.pdf, Mann HDFC.pdf) and a real Sanjay HDFC statement all print their period as two
    // separately colon-labeled fields on one row -- "From : <date>" and "To : <date>" -- rather
    // than one combined "Statement Period" label. Confirmed via direct PositionedText inspection:
    // each half survives PDF extraction as its own separate run ("From", ":", the date, "To",
    // ":", the second date), which groupIntoRows/lineOf already join into one line before this
    // class ever sees it. Two capture groups, not one pre-joined range string -- the two dates
    // are never adjacent in the source text the way parsePeriod's single-string entry point
    // expects, so the two groups are joined with " to " before calling it (see the call site).
    private static final Pattern FROM_TO_LABELED_PERIOD = Pattern.compile(
            "(?i)\\bFrom\\s*:\\s*(" + DATE_TOKEN_SRC + ")\\s+To\\s*:\\s*(" + DATE_TOKEN_SRC + ")");
```

Then wire it into the extraction loop. Search for the `STATEMENT_PERIOD_ANYWHERE` consultation
block inside `extract(...)` (the `if (periodStart == null && periodEnd == null)` block containing
`Matcher anywhere = STATEMENT_PERIOD_ANYWHERE.matcher(line);`) and add this new block immediately
after it:

```java
            if (periodStart == null && periodEnd == null) {
                Matcher fromTo = FROM_TO_LABELED_PERIOD.matcher(line);
                if (fromTo.find()) {
                    LocalDate[] parsed = parsePeriod(fromTo.group(1) + " to " + fromTo.group(2));
                    if (parsed[0] != null && parsed[1] != null) {
                        periodStart = parsed[0];
                        periodEnd = parsed[1];
                        if (ctx != null) ctx.record("STATEMENT_PERIOD_FROM_TO_FIELDS");
                        continue;
                    }
                }
            }
```

Register the capability. In `CapabilityCoverageService.java`, find the end of the
`KNOWN_CAPABILITIES` list (search for `"CHEQUE_REFERENCE_TRAILER_RECOVERED");`, the last entry) and
add, immediately before that closing `);`:

```java
            "CHEQUE_REFERENCE_TRAILER_RECOVERED",
            // A statement period stated as two separately colon-labeled fields on one row
            // ("From : <date>" / "To : <date>") rather than one combined "Period" label -- found
            // on real HDFC savings-account statements and a real Sanjay HDFC statement. See
            // PdfMetadataExtractor.FROM_TO_LABELED_PERIOD.
            "STATEMENT_PERIOD_FROM_TO_FIELDS");
```

(Change the existing final line's trailing `);` to a trailing `,` and add the new entry with the
`);` moved to it, as shown.)

In `CapabilityCorpusCoverageTest.java`, find the closing `}` of the `DECLARED_WITHOUT_A_TRACE`
static initializer (search for the last `DECLARED_WITHOUT_A_TRACE.put(...)` call before the block's
closing brace) and add:

```java
        DECLARED_WITHOUT_A_TRACE.put("STATEMENT_PERIOD_FROM_TO_FIELDS",
                "no trace yet -- evidenced from real HDFC savings-account statements and a real "
                        + "Sanjay HDFC statement, none of which have a committed trace in this "
                        + "corpus. Real-corpus behavior verified directly via CorpusProbe against "
                        + "the original files instead.");
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -o test -Dtest=PdfMetadataExtractorTest`
Expected: PASS (all tests in the file, including the new one and every pre-existing one — this
guards against the new pattern accidentally matching an existing test's fixture differently).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/PdfMetadataExtractor.java \
        backend/src/main/java/com/finora/imports/CapabilityCoverageService.java \
        backend/src/test/java/com/finora/imports/CapabilityCorpusCoverageTest.java \
        backend/src/test/java/com/finora/imports/pdf/PdfMetadataExtractorTest.java
git commit -m "feat(imports): recognize statement period stated as separate From/To labeled fields"
```

---

### Task B: `Statement From : DATE To DATE` (label is "Statement From", not "Statement Period")

**Real evidence:** `Manas_HDFC.pdf`, `Shivani_HDFC.pdf`, `Sanjay SBI.pdf` — 3 real documents. Same
date-range-after-label shape `STATEMENT_PERIOD_ANYWHERE` already captures as one group; only the
label word differs ("Statement From" instead of "Statement Period"/"Billing Period").

**Files:** same 4 files as Task A.

**Interfaces:**
- Produces: `STATEMENT_FROM_LABELED_PERIOD` (new `Pattern` constant, 1 capture group).

- [ ] **Step 1: Write the failing test**

```java
/** A real Manas_HDFC/Shivani_HDFC/Sanjay SBI statement shape: the field is labeled "Statement
 *  From", not "Statement Period"/"Billing Period" -- STATEMENT_PERIOD_ANYWHERE's own label
 *  alternation doesn't cover it. */
@Test
void extract_recognizesAStatementPeriod_labeledStatementFrom() {
    var metadata = extractor.extract(List.of(
            "Statement From : 01/06/2026 To 30/06/2026"));

    assertThat(metadata.statementPeriodStart()).isEqualTo(java.time.LocalDate.of(2026, 6, 1));
    assertThat(metadata.statementPeriodEnd()).isEqualTo(java.time.LocalDate.of(2026, 6, 30));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -o test -Dtest=PdfMetadataExtractorTest#extract_recognizesAStatementPeriod_labeledStatementFrom`
Expected: FAIL.

- [ ] **Step 3: Write the minimal implementation**

Add immediately after `FROM_TO_LABELED_PERIOD` (from Task A):

```java
    // STATEMENT_FROM_LABELED_PERIOD. Real Manas_HDFC.pdf, Shivani_HDFC.pdf, and Sanjay SBI.pdf
    // statements all label this field "Statement From" rather than "Statement Period"/"Billing
    // Period" (STATEMENT_PERIOD_ANYWHERE's own label alternation). Same date-range-after-label
    // shape STATEMENT_PERIOD_ANYWHERE already captures as one group, just a different label word
    // -- kept as a separate pattern rather than widening STATEMENT_PERIOD_ANYWHERE's own label
    // alternation, same "one pattern per real-evidenced shape" discipline every other pattern in
    // this class already follows.
    private static final Pattern STATEMENT_FROM_LABELED_PERIOD = Pattern.compile(
            "(?i)\\bStatement\\s+From\\s*:?\\s*("
                    + DATE_TOKEN_SRC + "\\s*(?:to|[-\u2013])\\s*" + DATE_TOKEN_SRC + ")");
```

Wire into the extraction loop, immediately after Task A's `FROM_TO_LABELED_PERIOD` block:

```java
            if (periodStart == null && periodEnd == null) {
                Matcher statementFrom = STATEMENT_FROM_LABELED_PERIOD.matcher(line);
                if (statementFrom.find()) {
                    LocalDate[] parsed = parsePeriod(statementFrom.group(1).trim());
                    if (parsed[0] != null && parsed[1] != null) {
                        periodStart = parsed[0];
                        periodEnd = parsed[1];
                        if (ctx != null) ctx.record("STATEMENT_PERIOD_STATEMENT_FROM_LABEL");
                        continue;
                    }
                }
            }
```

Register the capability in `CapabilityCoverageService.java` (append after Task A's new entry, same
pattern):

```java
            "STATEMENT_PERIOD_FROM_TO_FIELDS",
            // A statement period labeled "Statement From" rather than "Statement Period"/
            // "Billing Period" -- found on real Manas_HDFC, Shivani_HDFC, and Sanjay SBI
            // statements. See PdfMetadataExtractor.STATEMENT_FROM_LABELED_PERIOD.
            "STATEMENT_PERIOD_STATEMENT_FROM_LABEL");
```

And in `CapabilityCorpusCoverageTest.java`'s `DECLARED_WITHOUT_A_TRACE`:

```java
        DECLARED_WITHOUT_A_TRACE.put("STATEMENT_PERIOD_STATEMENT_FROM_LABEL",
                "no trace yet -- evidenced from real Manas_HDFC, Shivani_HDFC, and Sanjay SBI "
                        + "statements, none of which have a committed trace in this corpus. "
                        + "Real-corpus behavior verified directly via CorpusProbe against the "
                        + "original files instead.");
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -o test -Dtest=PdfMetadataExtractorTest`
Expected: PASS (all tests, including Task A's).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/PdfMetadataExtractor.java \
        backend/src/main/java/com/finora/imports/CapabilityCoverageService.java \
        backend/src/test/java/com/finora/imports/CapabilityCorpusCoverageTest.java \
        backend/src/test/java/com/finora/imports/pdf/PdfMetadataExtractorTest.java
git commit -m "feat(imports): recognize statement period labeled Statement From"
```

---

### Task C: "from"-tolerant separator + a new "Statement of Account" label

**Real evidence:** `BOB.pdf` ("Statement Period from DATE to DATE" — existing label, but the
separator only tolerates an optional colon, not the word "from" that's actually there) and
`CBI .pdf` ("STATEMENT OF ACCOUNT from DATE to DATE" — an entirely different label) — 2 real
documents.

**Files:** same 4 files as Task A.

**Interfaces:**
- Modifies: `STATEMENT_PERIOD_ANYWHERE` (widens its separator tolerance only — additive).
- Produces: `STATEMENT_OF_ACCOUNT_PERIOD` (new `Pattern` constant, 1 capture group).

- [ ] **Step 1: Write the failing tests**

```java
/** A real BOB.pdf statement prints "Statement Period from <date> to <date>" -- the existing
 *  STATEMENT_PERIOD_ANYWHERE label matches, but its separator only tolerates an optional colon,
 *  not the word "from" that's actually there. */
@Test
void extract_recognizesAStatementPeriod_whenTheLabelIsFollowedByTheWordFrom() {
    var metadata = extractor.extract(List.of(
            "Statement Period from Jun 01, 2026 to Jun 30, 2026"));

    assertThat(metadata.statementPeriodStart()).isEqualTo(java.time.LocalDate.of(2026, 6, 1));
    assertThat(metadata.statementPeriodEnd()).isEqualTo(java.time.LocalDate.of(2026, 6, 30));
}

/** A real CBI.pdf statement labels this field "Statement of Account" -- a different label from
 *  every existing pattern's "...Period" vocabulary. */
@Test
void extract_recognizesAStatementPeriod_labeledStatementOfAccount() {
    var metadata = extractor.extract(List.of(
            "STATEMENT OF ACCOUNT from 10/05/2026 to 08/08/2026"));

    assertThat(metadata.statementPeriodStart()).isEqualTo(java.time.LocalDate.of(2026, 5, 10));
    assertThat(metadata.statementPeriodEnd()).isEqualTo(java.time.LocalDate.of(2026, 8, 8));
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw -o test -Dtest=PdfMetadataExtractorTest#extract_recognizesAStatementPeriod_whenTheLabelIsFollowedByTheWordFrom+extract_recognizesAStatementPeriod_labeledStatementOfAccount`
Expected: both FAIL.

- [ ] **Step 3: Write the minimal implementation**

Widen `STATEMENT_PERIOD_ANYWHERE` (find its current definition) by adding an optional `(?:from\s+)?`
between the label and the capture group — this only ADDS an alternative, never removes what already
matches:

```java
    // The labelled form with unrelated text BEFORE the label -- a real SBI credit-card statement
    // renders it as "for Statement Period: <range>", and that leading "for " alone defeats
    // labelPattern's "^\s*" anchor. Kept as a separate, deliberately strict pattern rather than
    // unanchoring the primary one: the anchor is what stops prose ("...interest free credit
    // period...") being read as a field, the same guard F21 needed for "Account No". Safety here
    // comes instead from requiring a complete, date-shaped RANGE immediately after the label, so
    // a prose mention has nothing to match.
    //
    // Bug fix: a real BOB.pdf statement inserts the word "from" between the label and the range
    // ("Statement Period from <date> to <date>") -- the optional ":?" alone didn't tolerate that.
    // (?:from\s+)? is purely additive: every line this pattern already matched still matches.
    private static final Pattern STATEMENT_PERIOD_ANYWHERE = Pattern.compile(
            "(?i)\\b(?:Statement|Billing)\\s*Period\\s*:?\\s*(?:from\\s+)?("
                    + DATE_TOKEN_SRC + "\\s*(?:to|[-\u2013])\\s*" + DATE_TOKEN_SRC + ")");
```

Add the new `STATEMENT_OF_ACCOUNT_PERIOD` pattern immediately after Task B's
`STATEMENT_FROM_LABELED_PERIOD`:

```java
    // STATEMENT_OF_ACCOUNT_PERIOD. A real Central Bank of India statement's own top-of-document
    // heading labels this field "Statement of Account" -- an entirely different label from every
    // other pattern's "...Period"/"Statement From" vocabulary. Same "from"-tolerant date-range
    // capture as STATEMENT_PERIOD_ANYWHERE's own fix above.
    private static final Pattern STATEMENT_OF_ACCOUNT_PERIOD = Pattern.compile(
            "(?i)\\bStatement\\s+of\\s+Account\\s*:?\\s*(?:from\\s+)?("
                    + DATE_TOKEN_SRC + "\\s*(?:to|[-\u2013])\\s*" + DATE_TOKEN_SRC + ")");
```

Wire `STATEMENT_OF_ACCOUNT_PERIOD` into the extraction loop, immediately after Task B's block (no
change needed for `STATEMENT_PERIOD_ANYWHERE`'s own consultation block — the widened pattern is
used automatically):

```java
            if (periodStart == null && periodEnd == null) {
                Matcher statementOfAccount = STATEMENT_OF_ACCOUNT_PERIOD.matcher(line);
                if (statementOfAccount.find()) {
                    LocalDate[] parsed = parsePeriod(statementOfAccount.group(1).trim());
                    if (parsed[0] != null && parsed[1] != null) {
                        periodStart = parsed[0];
                        periodEnd = parsed[1];
                        if (ctx != null) ctx.record("STATEMENT_PERIOD_STATEMENT_OF_ACCOUNT_LABEL");
                        continue;
                    }
                }
            }
```

Register the capability in `CapabilityCoverageService.java`:

```java
            "STATEMENT_PERIOD_STATEMENT_FROM_LABEL",
            // A statement period labeled "Statement of Account" rather than any "...Period"
            // vocabulary -- found on a real Central Bank of India statement. See
            // PdfMetadataExtractor.STATEMENT_OF_ACCOUNT_PERIOD.
            "STATEMENT_PERIOD_STATEMENT_OF_ACCOUNT_LABEL");
```

And in `CapabilityCorpusCoverageTest.java`'s `DECLARED_WITHOUT_A_TRACE` (note: `STATEMENT_PERIOD_ANYWHERE`'s
own widening needs no new entry -- it's not a new capability name, just a wider match on an
existing one):

```java
        DECLARED_WITHOUT_A_TRACE.put("STATEMENT_PERIOD_STATEMENT_OF_ACCOUNT_LABEL",
                "no trace yet -- evidenced from a real Central Bank of India statement with no "
                        + "committed trace in this corpus. Real-corpus behavior verified directly "
                        + "via CorpusProbe against the original file instead.");
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -o test -Dtest=PdfMetadataExtractorTest`
Expected: PASS (all tests, including Tasks A and B's — this specifically confirms widening
`STATEMENT_PERIOD_ANYWHERE` didn't break its own existing `for Statement Period:` test).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/PdfMetadataExtractor.java \
        backend/src/main/java/com/finora/imports/CapabilityCoverageService.java \
        backend/src/test/java/com/finora/imports/CapabilityCorpusCoverageTest.java \
        backend/src/test/java/com/finora/imports/pdf/PdfMetadataExtractorTest.java
git commit -m "feat(imports): tolerate 'from' before a statement-period range, recognize Statement of Account label"
```

---

### Task D: `for the period DATE to DATE` embedded in an ordinary sentence, no parentheses

**Real evidence:** `canara.pdf`, `ICICI saving.pdf` — 2 real documents. `STATEMENT_PERIOD_IN_SENTENCE`
only matches a parenthesised range (AU's shape: `"...(19 Mar - 18 Apr 2026)"`); these two documents
state their range as plain prose with no parentheses at all.

**Files:** same 4 files as Task A.

**Interfaces:**
- Produces: `STATEMENT_PERIOD_PROSE` (new `Pattern` constant, 1 capture group).

- [ ] **Step 1: Write the failing test**

```java
/** Real canara.pdf and ICICI saving.pdf statements both state their period as plain prose --
 *  "...for the period <date> to <date>" -- with no parentheses at all, so
 *  STATEMENT_PERIOD_IN_SENTENCE (which requires parens) doesn't match. */
@Test
void extract_recognizesAStatementPeriod_statedAsProseWithNoParentheses() {
    var metadata = extractor.extract(List.of(
            "Statement for A/c XXXXXXXXX1455 for the period 02-Jul-2026 to 01-Aug-2026"));

    assertThat(metadata.statementPeriodStart()).isEqualTo(java.time.LocalDate.of(2026, 7, 2));
    assertThat(metadata.statementPeriodEnd()).isEqualTo(java.time.LocalDate.of(2026, 8, 1));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -o test -Dtest=PdfMetadataExtractorTest#extract_recognizesAStatementPeriod_statedAsProseWithNoParentheses`
Expected: FAIL.

- [ ] **Step 3: Write the minimal implementation**

Add immediately after Task C's `STATEMENT_OF_ACCOUNT_PERIOD`:

```java
    // STATEMENT_PERIOD_PROSE. Real canara.pdf and ICICI saving.pdf statements both state their
    // period as plain prose -- "...for the period <date> to <date>" -- with no parentheses at
    // all, unlike STATEMENT_PERIOD_IN_SENTENCE's AU-evidenced shape (which requires a
    // parenthesised range). Same "date-range immediately after a fixed phrase" idea as every
    // other pattern in this class, just a different fixed phrase and no parens to anchor on.
    private static final Pattern STATEMENT_PERIOD_PROSE = Pattern.compile(
            "(?i)\\bfor\\s+the\\s+period\\s+("
                    + DATE_TOKEN_SRC + "\\s*(?:to|[-\u2013])\\s*" + DATE_TOKEN_SRC + ")");
```

Wire into the extraction loop, immediately after Task C's block:

```java
            if (periodStart == null && periodEnd == null) {
                Matcher prose = STATEMENT_PERIOD_PROSE.matcher(line);
                if (prose.find()) {
                    LocalDate[] parsed = parsePeriod(prose.group(1).trim());
                    if (parsed[0] != null && parsed[1] != null) {
                        periodStart = parsed[0];
                        periodEnd = parsed[1];
                        if (ctx != null) ctx.record("STATEMENT_PERIOD_PROSE");
                        continue;
                    }
                }
            }
```

Register the capability in `CapabilityCoverageService.java`:

```java
            "STATEMENT_PERIOD_STATEMENT_OF_ACCOUNT_LABEL",
            // A statement period stated as plain prose ("...for the period <date> to <date>")
            // with no parentheses and no "Label:" shape at all -- found on real canara.pdf and
            // ICICI saving.pdf statements. See PdfMetadataExtractor.STATEMENT_PERIOD_PROSE.
            "STATEMENT_PERIOD_PROSE");
```

And in `CapabilityCorpusCoverageTest.java`'s `DECLARED_WITHOUT_A_TRACE`:

```java
        DECLARED_WITHOUT_A_TRACE.put("STATEMENT_PERIOD_PROSE",
                "no trace yet -- evidenced from real canara.pdf and ICICI saving.pdf statements, "
                        + "neither of which has a committed trace in this corpus. Real-corpus "
                        + "behavior verified directly via CorpusProbe against the original files "
                        + "instead.");
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -o test -Dtest=PdfMetadataExtractorTest`
Expected: PASS (all tests, including Tasks A, B, and C's).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/PdfMetadataExtractor.java \
        backend/src/main/java/com/finora/imports/CapabilityCoverageService.java \
        backend/src/test/java/com/finora/imports/CapabilityCorpusCoverageTest.java \
        backend/src/test/java/com/finora/imports/pdf/PdfMetadataExtractorTest.java
git commit -m "feat(imports): recognize statement period stated as plain prose with no parentheses"
```

---

### Task E: `For Period: DATE to DATE` (label prefix is "For", not "Statement"/"Billing")

**Real evidence:** `PNBONE_STMT_XX4802_31072026.pdf` — 1 real document, whose actual line is
`"Statement of Account:<account number> For Period: <date> to <date>"` — unrelated leading text
(an account number after a DIFFERENT "Statement of Account:" label) precedes "For Period:" on the
same line, the same "arbitrary text before the real label" tolerance `STATEMENT_PERIOD_ANYWHERE`
already relies on via unanchored `find()`.

**Files:** same 4 files as Task A.

**Interfaces:**
- Produces: `FOR_PERIOD_LABELED` (new `Pattern` constant, 1 capture group).

- [ ] **Step 1: Write the failing test**

```java
/** A real PNB ONE savings statement's own heading: "Statement of Account:<number> For Period:
 *  <date> to <date>" -- the account number's own "Statement of Account:" label is unrelated
 *  leading text; the real period label is "For Period:", found via unanchored matching the same
 *  way STATEMENT_PERIOD_ANYWHERE already tolerates arbitrary text before its own label. */
@Test
void extract_recognizesAStatementPeriod_labeledForPeriod() {
    var metadata = extractor.extract(List.of(
            "Statement of Account:1000200030004000 For Period: 30-06-2026 to 31-07-2026")); // synthetic-ok

    assertThat(metadata.statementPeriodStart()).isEqualTo(java.time.LocalDate.of(2026, 6, 30));
    assertThat(metadata.statementPeriodEnd()).isEqualTo(java.time.LocalDate.of(2026, 7, 31));
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd backend && ./mvnw -o test -Dtest=PdfMetadataExtractorTest#extract_recognizesAStatementPeriod_labeledForPeriod`
Expected: FAIL. (This line also happens to contain literal text "Statement of Account:" followed
by an account NUMBER, not a date range, so Task C's `STATEMENT_OF_ACCOUNT_PERIOD` must NOT
false-positive-match here — confirm in Step 4 that Task C's own tests still pass unchanged,
proving the two patterns don't interfere: `STATEMENT_OF_ACCOUNT_PERIOD` requires a date-shaped
range immediately after its label, and an account number is not date-shaped, so it can't match.)

- [ ] **Step 3: Write the minimal implementation**

Add immediately after Task D's `STATEMENT_PERIOD_PROSE`:

```java
    // FOR_PERIOD_LABELED. A real PNB ONE savings statement's own heading line reads "Statement
    // of Account:<account number> For Period: <date> to <date>" -- the account number's own
    // "Statement of Account:" label is unrelated leading text (a DIFFERENT field, not this one);
    // the real period label is "For Period:", matched the same unanchored way
    // STATEMENT_PERIOD_ANYWHERE already tolerates arbitrary text before its own label.
    private static final Pattern FOR_PERIOD_LABELED = Pattern.compile(
            "(?i)\\bFor\\s+Period\\s*:?\\s*("
                    + DATE_TOKEN_SRC + "\\s*(?:to|[-\u2013])\\s*" + DATE_TOKEN_SRC + ")");
```

Wire into the extraction loop, immediately after Task D's block:

```java
            if (periodStart == null && periodEnd == null) {
                Matcher forPeriod = FOR_PERIOD_LABELED.matcher(line);
                if (forPeriod.find()) {
                    LocalDate[] parsed = parsePeriod(forPeriod.group(1).trim());
                    if (parsed[0] != null && parsed[1] != null) {
                        periodStart = parsed[0];
                        periodEnd = parsed[1];
                        if (ctx != null) ctx.record("STATEMENT_PERIOD_FOR_PERIOD_LABEL");
                        continue;
                    }
                }
            }
```

Register the capability in `CapabilityCoverageService.java`:

```java
            "STATEMENT_PERIOD_PROSE",
            // A statement period labeled "For Period:" -- found on a real PNB ONE savings
            // statement, whose own line also carries an unrelated "Statement of Account:<number>"
            // label before it. See PdfMetadataExtractor.FOR_PERIOD_LABELED.
            "STATEMENT_PERIOD_FOR_PERIOD_LABEL");
```

And in `CapabilityCorpusCoverageTest.java`'s `DECLARED_WITHOUT_A_TRACE`:

```java
        DECLARED_WITHOUT_A_TRACE.put("STATEMENT_PERIOD_FOR_PERIOD_LABEL",
                "no trace yet -- evidenced from a real PNB ONE savings statement with no "
                        + "committed trace in this corpus. Real-corpus behavior verified directly "
                        + "via CorpusProbe against the original file instead.");
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -o test -Dtest=PdfMetadataExtractorTest`
Expected: PASS (every test in the file, including Tasks A-D's and, specifically,
`extract_recognizesAStatementPeriod_labeledStatementOfAccount` from Task C — confirming
`STATEMENT_OF_ACCOUNT_PERIOD` and `FOR_PERIOD_LABELED` don't interfere with each other on either
fixture).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/pdf/PdfMetadataExtractor.java \
        backend/src/main/java/com/finora/imports/CapabilityCoverageService.java \
        backend/src/test/java/com/finora/imports/CapabilityCorpusCoverageTest.java \
        backend/src/test/java/com/finora/imports/pdf/PdfMetadataExtractorTest.java
git commit -m "feat(imports): recognize statement period labeled For Period"
```

---

### Task F: Full corpus re-verification and PR

**Files:** none modified — verification only.

**Interfaces:**
- Consumes: `CorpusProbe` (`backend/src/test/java/com/finora/imports/analysis/CorpusProbe.java`),
  `scripts/corpus-run.py`.

- [ ] **Step 1: Run the full backend test suite**

```bash
cd backend && ./mvnw -o test
```

Expected: green (aside from any pre-existing unrelated flake already known from this session --
confirm by re-running that specific test class alone if one appears).

- [ ] **Step 2: Re-run the full real corpus and recompute field-presence rates**

```bash
cd backend && ./mvnw -q -o test-compile
./mvnw -q -o dependency:build-classpath -Dmdep.outputFile=/tmp/finora-cp.txt -Dmdep.includeScope=test
cd ..
python3 scripts/corpus-run.py "$HOME/Downloads/Bank statement/Savings accounts" -o /tmp/savings-postfix.jsonl
python3 scripts/corpus-run.py "$HOME/Downloads/Bank statement/Credit cards" -o /tmp/cc-postfix.jsonl
```

(If `/tmp` is refused by the sandbox, use this session's scratchpad directory instead.)

Write a small Python script (or reuse one from this session's scratchpad if still present) to
compute, across both JSONL files' `sectionDetail` entries with `rows > 0`:
- `statementPeriodStart` present / total
- `statementPeriodEnd` present / total
- for every document, whether it's present, and if not, which one(s) remain missing

- [ ] **Step 3: Confirm the expected outcome and report it**

Verify explicitly:
1. `statementPeriodStart` coverage is now 14/15 (up from 2/15) or better.
2. `statementPeriodEnd` coverage matches `statementPeriodStart` exactly, row for row (both fields
   are always set together via `parsePeriod`, so any mismatch between the two counts would itself
   be a bug worth investigating before proceeding).
3. `new kotak.pdf` (Group F) is the **only** remaining miss. If any of the 12 target documents is
   still missing the field, STOP and investigate that one specifically before proceeding — do not
   assume the pattern is simply not firing for a reason already covered by this plan.
4. No verification outcome (`BALANCE_CHAIN`, `STATEMENT_TOTALS`, `ROW_ACCOUNTING`, etc.) newly
   shows `WARNING`/`FAILED` on any of the 27 documents compared to the pre-fix baseline (the two
   pre-existing ones — `Axis credit.pdf` `CREDIT_CARD_STATEMENT_TOTALS`, `HSBC CC.pdf`
   `ROW_ACCOUNTING` — are expected and unrelated).
5. No document's `statementPeriodStart`/`End` value looks structurally wrong (e.g. an implausible
   year, start after end) — a quick sanity scan of the actual dates recovered, not just presence.

Report this exact breakdown back before moving to Step 4 — if anything in points 3-5 is off,
that's a new, real finding to investigate, not something to paper over to hit the "14/15" target.

- [ ] **Step 4: Follow `superpowers:finishing-a-development-branch`**

Push, open a PR against `main` summarizing the root-cause groups (A-E) and Group F's deferral, and
report the PR URL.
