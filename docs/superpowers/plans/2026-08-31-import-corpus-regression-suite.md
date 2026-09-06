# Import Corpus Regression Suite (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the mechanism that lets the import pipeline's corpus regression tooling (1) assert
statement-level financial facts (balance, period, credit-card summary) against the real 27-document
corpus, and (2) detect transaction-description corruption without ever storing real customer
transaction text — as a CI-gated check on a small curated synthetic corpus, plus a local/manual
diff-based check on the real corpus that every future parser-touching PR must run before merging.

**Architecture:** Extend the existing three-layer corpus tooling (`CorpusProbe.java` observes,
`ground-truth-match.py` asserts against per-document expected values, `corpus-diff.py` diffs two runs
with no per-document knowledge) rather than replacing it. Two additions land on two different
privacy tiers of the existing model: statement-level facts (balance/period/CC-summary) join the
existing `expectedProduct`/`expectedIdentity` tier, which already applies to real documents (their
ground-truth files live outside the repo, never in git). Per-transaction description text joins the
existing `expectedTransactionValues` tier, which is *structurally* refused for real documents
(`ground-truth-match.py` hard-fails a real-corpus record that carries row-level financial values) —
so description ground truth only ever applies to committed synthetic fixtures, never real statements.
For the real corpus, description drift is caught by comparing row-content *hashes* (never raw text)
between two `corpus-run.py` runs, surfaced as a review-severity finding a human decides on locally.

**Tech Stack:** Java 21 / JUnit 5 / Mockito (backend, `CorpusProbe` + `GroundTruthDocument` +
`SyntheticStatementDefinition` + `PdfFixtureBuilder`), Python 3 stdlib (`corpus-diff.py`,
`ground-truth-match.py`, `run-corpus-ground-truth.py`, `corpus-run.py`, their `unittest` self-tests).

**Spec:** No standalone spec doc — this plan implements Phase 2 of
`import-pipeline-fix-roadmap.md` (delivered to the user out-of-repo,
`/private/tmp/claude-501/-Users-sid-Downloads-finora/dd5c6810-4fe7-4a04-9a27-a25303bdd40e/scratchpad/import-pipeline-fix-roadmap.md`),
narrowed by two explicit decisions the user made when this phase started:
1. **CI gating**: CI only ever gates a committed, redacted/synthetic corpus — never the real one. The
   real 27-document corpus stays a manual, local pre-merge step for every future Phase 3/4/5 PR,
   with results pasted into the PR description.
2. **Description checks**: no literal description ground truth for real documents, ever. Instead:
   (a) a small curated set of synthetic canonical fixtures, each reproducing one known parser failure
   shape, carries explicit reviewed expected descriptions and is CI-gated; (b) the large real corpus
   gets diff-based description-*drift* detection (hash-level, not literal text) with a change-rate
   metric and human review required on any non-zero drift, never automated pass/fail on content.

## Global Constraints

- Real ground-truth JSON files live exclusively at `~/Downloads/Bank statement/ground-truth/*.json`
  — never committed to this repo. Any script that could point at that directory must keep refusing
  to run when its target path resolves inside the repo (`_refuse_if_inside_repo`), by default.
- Ground truth must never be derived from parser output (`ground-truth-model-design.md`'s central
  rule) — a new expected field on a real document's ground-truth file must come from reading the
  actual PDF, never from copying `CorpusProbe`'s own extracted value for that field.
- `ground-truth-match.py`'s refusal of per-transaction financial values on a `REAL_CORPUS`
  observation (`ground-truth-match.py:189-191`) stays untouched and unconditional. Nothing in this
  plan weakens it; the new `description` dimension only ever becomes reachable through an explicit,
  loudly-named opt-in that a real-corpus run has no reason to ever pass.
- `corpus-diff.py` carries no per-document knowledge (ADR-004, enforced by
  `Genericity.test_the_script_contains_no_institution_name_and_no_corpus_filename` in
  `scripts/test-corpus-diff.py`) — no filename, bank name, or document-specific constant may appear
  in it. New dimensions must stay generic across every document.
- No PII, real account identifiers, or real transaction text may appear in any commit, code comment,
  or doc this plan produces — describe shapes, never quote real values (per this project's existing
  standing rule, learned from three prior incidents).

---

### Task 1: `CorpusProbe` emits statement-level financial facts

**Files:**
- Modify: `backend/src/test/java/com/finora/imports/analysis/CorpusProbe.java`
- Test: `backend/src/test/java/com/finora/imports/analysis/CorpusProbeTest.java`

**Interfaces:**
- Consumes: `DetectedAccountInfo` (`backend/src/main/java/com/finora/dto/ImportDto.java:292`) —
  `openingBalance`, `closingBalance`, `statementPeriodStart`, `statementPeriodEnd`, `creditLimit`,
  `totalAmountDue`, `paymentDueDate` (all already populated by the real pipeline; `CorpusProbe`
  currently reads this record but only forwards `detectedProduct`/`suggestedAccountType`/
  `accountNumberMasked`/`productConfidence`/`productNeedsReview` from it).
- Produces: each element of `CorpusProbe.Section` (and its JSON rendering in `sectionsJson`) gains
  seven new fields — `openingBalance`, `closingBalance`, `statementPeriodStart`,
  `statementPeriodEnd`, `creditLimit`, `totalAmountDue`, `paymentDueDate` — every one nullable
  (`null` in JSON when the source column wasn't present, exactly like `accountNumberMasked` today).
  Task 2 (`ground-truth-match.py`) and Task 7 (`corpus-diff.py`) both consume these by name.

These are statement-level aggregate facts, not per-transaction ledger detail — the same privacy
tier as the `accountNumberMasked` field `CorpusProbe` already emits unconditionally for real
documents. They stay in scope for the `REAL_CORPUS` path with no opt-in flag.

- [ ] **Step 1: Write the failing tests**

Add to `CorpusProbeTest.java` (mirroring its existing `sectionsJson` tests):

```java
@Test
void sectionsJson_emitsStatementLevelFinancialFacts_whenPresent() {
    CorpusProbe.Section section = new CorpusProbe.Section(
            0, 3, "SAVINGS", "SAVINGS", "****1234", 0.9, false, Map.of(),
            new BigDecimal("1000.00"), new BigDecimal("1500.00"),
            LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 31),
            null, null, null);

    String json = CorpusProbe.sectionsJson(List.of(section));

    assertThat(json).contains("\"openingBalance\":\"1000.00\"");
    assertThat(json).contains("\"closingBalance\":\"1500.00\"");
    assertThat(json).contains("\"statementPeriodStart\":\"2026-07-01\"");
    assertThat(json).contains("\"statementPeriodEnd\":\"2026-07-31\"");
    assertThat(json).contains("\"creditLimit\":null");
    assertThat(json).contains("\"totalAmountDue\":null");
    assertThat(json).contains("\"paymentDueDate\":null");
}

@Test
void sectionsJson_emitsCreditCardSummaryFields_whenPresent() {
    CorpusProbe.Section section = new CorpusProbe.Section(
            0, 3, "CREDIT_CARD", "CREDIT_CARD", null, 0.9, false, Map.of(),
            null, null, null, null,
            new BigDecimal("50000.00"), new BigDecimal("4321.50"), LocalDate.of(2026, 8, 15));

    String json = CorpusProbe.sectionsJson(List.of(section));

    assertThat(json).contains("\"creditLimit\":\"50000.00\"");
    assertThat(json).contains("\"totalAmountDue\":\"4321.50\"");
    assertThat(json).contains("\"paymentDueDate\":\"2026-08-15\"");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=CorpusProbeTest`
Expected: compile error — `Section`'s constructor doesn't accept these extra arguments yet.

- [ ] **Step 3: Extend the `Section` record and its JSON rendering**

In `CorpusProbe.java`, change the `Section` record (line 218) to:

```java
record Section(int index, int rows, String detectedProduct, String suggestedAccountType,
               String accountNumberMasked, double productConfidence, boolean productNeedsReview,
               Map<String, String> verification,
               BigDecimal openingBalance, BigDecimal closingBalance,
               LocalDate statementPeriodStart, LocalDate statementPeriodEnd,
               BigDecimal creditLimit, BigDecimal totalAmountDue, LocalDate paymentDueDate) {}
```

Add `import java.math.BigDecimal;` and `import java.time.LocalDate;` at the top.

Update the `Section` construction in `probe()` (around line 133) to pass the seven new values from
`account` (the `DetectedAccountInfo` local variable), each `null` when `account == null`:

```java
detail.add(new Section(i, sectionRows,
        account == null ? null : account.detectedProduct(),
        account == null ? null : account.suggestedAccountType(),
        account == null ? null : account.accountNumberMasked(),
        account == null ? 0.0 : account.productConfidence(),
        account != null && account.productNeedsReview(),
        sectionVerification,
        account == null ? null : account.openingBalance(),
        account == null ? null : account.closingBalance(),
        account == null ? null : account.statementPeriodStart(),
        account == null ? null : account.statementPeriodEnd(),
        account == null ? null : account.creditLimit(),
        account == null ? null : account.totalAmountDue(),
        account == null ? null : account.paymentDueDate()));
```

Update `sectionsJson()` to append the seven fields (BigDecimal and LocalDate both render via
`.toString()`, quoted; `null` renders bare, matching the existing `accountNumberMasked` pattern):

```java
.append(",\"openingBalance\":").append(s.openingBalance() == null ? "null" : quote(s.openingBalance().toPlainString()))
.append(",\"closingBalance\":").append(s.closingBalance() == null ? "null" : quote(s.closingBalance().toPlainString()))
.append(",\"statementPeriodStart\":").append(s.statementPeriodStart() == null ? "null" : quote(s.statementPeriodStart().toString()))
.append(",\"statementPeriodEnd\":").append(s.statementPeriodEnd() == null ? "null" : quote(s.statementPeriodEnd().toString()))
.append(",\"creditLimit\":").append(s.creditLimit() == null ? "null" : quote(s.creditLimit().toPlainString()))
.append(",\"totalAmountDue\":").append(s.totalAmountDue() == null ? "null" : quote(s.totalAmountDue().toPlainString()))
.append(",\"paymentDueDate\":").append(s.paymentDueDate() == null ? "null" : quote(s.paymentDueDate().toString()))
```

(placed before the closing `.append('}')` in the per-section loop)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=CorpusProbeTest`
Expected: PASS, including all pre-existing tests in this file (the `Section` record's constructor
signature change means every existing test that constructs one needs its call site updated — do
that as part of this step, adding `null` for each of the seven new positions where a test doesn't
care about them).

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/com/finora/imports/analysis/CorpusProbe.java backend/src/test/java/com/finora/imports/analysis/CorpusProbeTest.java
git commit -m "feat(imports): CorpusProbe emits statement-level balance/period/credit-card facts"
```

---

### Task 2: `ground-truth-match.py` asserts the new statement-level fields

**Files:**
- Modify: `scripts/ground-truth-match.py`
- Test: `scripts/test-ground-truth-match.py`

**Interfaces:**
- Consumes: the seven new `CorpusProbe` section fields from Task 1, read off `record["observed"]
  ["sectionDetail"][i]` by the same string keys used in the JSON (e.g. `"openingBalance"`).
- Produces: `match()`'s per-entity `out` list gains up to seven new issue strings when an expected
  field is asserted and disagrees; `worsen(FAIL)` on any real mismatch, matching existing
  `expectedProduct` handling. New optional keys on the ground-truth schema's per-entity object:
  `expectedOpeningBalance`, `expectedClosingBalance`, `expectedStatementPeriodStart`,
  `expectedStatementPeriodEnd`, `expectedCreditLimit`, `expectedTotalAmountDue`,
  `expectedPaymentDueDate` — every one optional; absence means "not asserted," never "expected
  null," exactly like the existing `expectedIdentity` key.

- [ ] **Step 1: Write the failing tests**

Add to `test-ground-truth-match.py` (following its existing style — synthetic `truth`/`record`
dicts, no real filenames or values):

```python
def test_a_matched_opening_balance_passes(self):
    truth = _entity(id="acct", product="SAVINGS", expected_opening_balance="1000.00")
    record = _record(sections=[_section(suggested_account_type="SAVINGS",
                                         opening_balance="1000.00")])
    result = match(truth, record)
    self.assertEqual(result["verdict"], PASS)

def test_a_mismatched_opening_balance_fails(self):
    truth = _entity(id="acct", product="SAVINGS", expected_opening_balance="1000.00")
    record = _record(sections=[_section(suggested_account_type="SAVINGS",
                                         opening_balance="999.00")])
    result = match(truth, record)
    self.assertEqual(result["verdict"], FAIL)

def test_an_unasserted_field_never_fails_the_match(self):
    truth = _entity(id="acct", product="SAVINGS")  # no expected_opening_balance at all
    record = _record(sections=[_section(suggested_account_type="SAVINGS",
                                         opening_balance="1000.00")])
    result = match(truth, record)
    self.assertEqual(result["verdict"], PASS)

def test_an_asserted_field_the_probe_never_observed_is_a_review_not_a_pass(self):
    truth = _entity(id="acct", product="SAVINGS", expected_closing_balance="1500.00")
    record = _record(sections=[_section(suggested_account_type="SAVINGS", closing_balance=None)])
    result = match(truth, record)
    self.assertEqual(result["verdict"], REVIEW)
```

(Extend this file's existing `_entity`/`_record`/`_section` synthetic-builder helpers with the new
optional keyword arguments needed above, following whatever pattern they already use for
`expected_product`/`suggested_account_type`.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 scripts/test-ground-truth-match.py`
Expected: FAIL — `TypeError` (helper functions don't accept the new kwargs yet) or `AssertionError`
(match doesn't check the new fields yet).

- [ ] **Step 3: Implement the comparison**

In `ground-truth-match.py`, add a module-level tuple naming the new statement-level fields and their
JSON key pairs (expected-key on the truth entity, observed-key on the section):

```python
# (expected key on the ground-truth entity, observed key on the CorpusProbe section). Statement-
# level facts, not per-transaction values -- these apply to REAL_CORPUS records exactly like
# expectedProduct already does; see the plan's Global Constraints for why this tier is different
# from VALUE_DIMENSIONS.
STATEMENT_FIELDS = (
    ("expectedOpeningBalance", "openingBalance"),
    ("expectedClosingBalance", "closingBalance"),
    ("expectedStatementPeriodStart", "statementPeriodStart"),
    ("expectedStatementPeriodEnd", "statementPeriodEnd"),
    ("expectedCreditLimit", "creditLimit"),
    ("expectedTotalAmountDue", "totalAmountDue"),
    ("expectedPaymentDueDate", "paymentDueDate"),
)
```

Inside `match()`, in the per-entity loop, after the existing `expectedProduct` check (around line
182) and before the value-axis block, add:

```python
for expected_key, observed_key in STATEMENT_FIELDS:
    if expected_key not in e:
        continue                                   # not asserted -- never fails, never passes
    want = e[expected_key]
    got = s.get(observed_key)
    if got is None:
        issues.append(f"{observed_key}: asserted {want} but not observed")
        worst = REVIEW if worst == PASS else worst  # unobserved is not agreement, but not a proven defect either
    elif str(got) != str(want):
        issues.append(f"{observed_key}: observed {got}, expected {want}")
        worst = FAIL
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 scripts/test-ground-truth-match.py`
Expected: PASS, all tests including pre-existing ones.

- [ ] **Step 5: Commit**

```bash
git add scripts/ground-truth-match.py scripts/test-ground-truth-match.py
git commit -m "feat(imports): ground-truth-match.py asserts balance/period/credit-card facts"
```

---

### Task 3: `description` joins the per-transaction value axis (synthetic-only)

**Files:**
- Modify: `backend/src/test/java/com/finora/imports/pdf/fixtures/GroundTruthDocument.java`
- Modify: `scripts/ground-truth-match.py`
- Test: `scripts/test-ground-truth-match.py`
- Test: `backend/src/test/java/com/finora/imports/pdf/fixtures/SyntheticGroundTruthTest.java`
  (confirmed to exist and contain
  `theGroundTruthDocumentIsProducedWithoutRenderingAnything` — read it fully before adding to it, to
  match its existing assertion style)

**Interfaces:**
- Consumes: `SyntheticStatementDefinition.Row.description()` (already exists,
  `SyntheticStatementDefinition.java:59` — currently declared but never read by
  `GroundTruthDocument.row()`).
- Produces: `GroundTruthDocument.row()`'s emitted JSON gains a `"description"` key alongside
  `date`/`amount`/`direction`/`currency`. `ground-truth-match.py`'s `VALUE_DIMENSIONS` tuple gains
  `"description"`. No other matcher logic changes — `_compare_values()` already iterates
  `VALUE_DIMENSIONS` generically (`ground-truth-match.py:70`), so adding the name to the tuple is
  sufficient; the existing structural refusal for `REAL_CORPUS` observations
  (`ground-truth-match.py:189-191`) applies to this new dimension automatically, with no separate
  code path to keep in sync.

- [ ] **Step 1: Write the failing tests**

Add to `test-ground-truth-match.py`:

```python
def test_description_is_compared_on_a_synthetic_observation(self):
    truth = _entity(id="acct", product="SAVINGS",
                     rows=[_row(date="2026-07-01", amount="100.00", direction="DEBIT",
                                description="Coffee shop")])
    record = _record(observation_source="SYNTHETIC",
                      sections=[_section(suggested_account_type="SAVINGS",
                                          transactions=[{"date": "2026-07-01", "amount": "100.00",
                                                          "direction": "DEBIT", "currency": "INR",
                                                          "description": "Coffee shop"}])])
    result = match(truth, record)
    self.assertEqual(result["verdict"], PASS)

def test_a_mismatched_description_fails_on_a_synthetic_observation(self):
    truth = _entity(id="acct", product="SAVINGS",
                     rows=[_row(date="2026-07-01", amount="100.00", direction="DEBIT",
                                description="Coffee shop")])
    record = _record(observation_source="SYNTHETIC",
                      sections=[_section(suggested_account_type="SAVINGS",
                                          transactions=[{"date": "2026-07-01", "amount": "100.00",
                                                          "direction": "DEBIT", "currency": "INR",
                                                          "description": "WRONG TEXT"}])])
    result = match(truth, record)
    self.assertEqual(result["verdict"], FAIL)
```

Add to the Java fixtures test suite found by the `grep` above (mirroring its existing assertions on
`date`/`amount`/`direction`/`currency`):

```java
@Test
void theGroundTruthDocumentEmitsEachRowsDescription() {
    var definition = new SyntheticStatementDefinition("doc-1", List.of(
            new SyntheticStatementDefinition.ExpectedEntity("acct", "SAVINGS",
                    SyntheticStatementDefinition.Presence.DETECTED, null,
                    SyntheticStatementDefinition.ZeroTransactions.FALSE,
                    List.of(new SyntheticStatementDefinition.Row(
                            LocalDate.of(2026, 7, 1), "Coffee shop", new BigDecimal("100.00"), false)))),
            List.of());

    String json = GroundTruthDocument.of(definition);

    assertThat(json).contains("\"description\": \"Coffee shop\"");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python3 scripts/test-ground-truth-match.py && cd backend && ./mvnw -q test -Dtest=SyntheticGroundTruthTest`
Expected: FAIL — description missing from emitted JSON; description not in `VALUE_DIMENSIONS` so
the Python matcher never looks at it (first test would pass vacuously without the dimension added —
confirm it actually exercises the new field once Step 3 lands, not before).

- [ ] **Step 3: Implement**

In `GroundTruthDocument.row()` (line 92-97), add the description key:

```java
private static String row(SyntheticStatementDefinition.Row r) {
    return "        { \"date\": " + quote(r.date().toString())
            + ", \"description\": " + quote(r.description())
            + ", \"amount\": " + quote(r.amount().toPlainString())
            + ", \"direction\": " + quote(r.credit() ? "CREDIT" : "DEBIT")
            + ", \"currency\": \"INR\" }";
}
```

In `ground-truth-match.py` line 33, change:

```python
VALUE_DIMENSIONS = ("date", "amount", "direction", "currency", "description")
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python3 scripts/test-ground-truth-match.py && cd backend && ./mvnw -q test -Dtest=SyntheticGroundTruthTest`
Expected: PASS, all tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/com/finora/imports/pdf/fixtures/GroundTruthDocument.java scripts/ground-truth-match.py scripts/test-ground-truth-match.py backend/src/test/java/com/finora/imports/pdf/fixtures/
git commit -m "feat(imports): description joins the synthetic-only per-transaction value axis"
```

---

### Task 4: `CorpusProbe` opt-in SYNTHETIC mode — per-row content, explicitly gated

**Files:**
- Modify: `backend/src/test/java/com/finora/imports/analysis/CorpusProbe.java`
- Test: `backend/src/test/java/com/finora/imports/analysis/CorpusProbeTest.java`

**Interfaces:**
- Consumes: `StagedRow.description()`, `.date()`, `.amount()` (`ImportDto.java:41-`), and `StagedRow`
  sign convention `.type()` (confirm the exact debit/credit representation by reading
  `ImportDto.StagedRow` and `TransactionNormalizer` before writing this task's code — do not assume).
- Produces: `probe(Path pdf, boolean synthetic)` — the `main()` entrypoint gains a second, explicit
  CLI flag (`--synthetic`), defaulting `synthetic=false`. When `true`: the JSON gains a
  top-level `"observed":{"observationSource":"SYNTHETIC", ...}` key, and every `Section` in
  `sectionDetail` gains a `"transactions"` array of `{date, description, amount, direction,
  currency}` objects, one per `StagedRow`, exactly matching the shape `ground-truth-match.py`'s
  `_values_of()`/`_compare_values()` already expect (Task 3). When `false` (default, used by every
  existing caller): behavior is byte-for-byte unchanged from before this task — no
  `observationSource` key, no `transactions` key, matching `_observation_source()`'s existing
  "absent means REAL_CORPUS" default.

This is the one place in this plan where a flag controls whether real customer transaction content
could ever be emitted. The flag must default to the safe (`false`/omitted) behavior, and every call
site that isn't Task 6's synthetic-fixture regression check must never pass it.

- [ ] **Step 1: Write the failing tests**

`CorpusProbeTest.java` has no existing direct call to `probe()` today (its current tests only cover
`sectionsJson`/`errorRecord`/`worse`/`severity` — confirmed by
`grep -n "probe(" CorpusProbeTest.java` returning nothing). Add a `@BeforeAll`/local helper that
builds one minimal one-row PDF via `PdfFixtureBuilder` (following whatever construction pattern
`PdfFixtureBuilder`'s own tests use — read `PdfFixtureBuilder.java` first) into a `@TempDir`, for
this test class's sole use:

```java
@Test
void probe_omitsObservationSourceAndTransactions_whenSyntheticFlagIsAbsent(@TempDir Path tempDir) throws Exception {
    Path pdf = oneRowFixture(tempDir);

    String json = CorpusProbe.probe(pdf, false);

    assertThat(json).doesNotContain("observationSource");
    assertThat(json).doesNotContain("\"transactions\"");
}

@Test
void probe_emitsObservationSourceAndPerRowTransactions_whenSyntheticFlagIsSet(@TempDir Path tempDir) throws Exception {
    Path pdf = oneRowFixture(tempDir);

    String json = CorpusProbe.probe(pdf, true);

    assertThat(json).contains("\"observationSource\":\"SYNTHETIC\"");
    assertThat(json).contains("\"transactions\":[");
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=CorpusProbeTest`
Expected: compile error — `probe(Path)` doesn't accept a second argument yet.

- [ ] **Step 3: Implement**

Change `probe()`'s signature to `static String probe(Path pdf, boolean synthetic) throws Exception`,
update `main()` to parse `--synthetic` out of `args` before extracting the path argument, and update
the internal call in `main()` accordingly. Add `observationSource` to the top-level `observed` object
(only when `synthetic`) and a `transactions` array to each section's JSON (only when `synthetic`),
built from `section.rows()`'s `StagedRow`s using the exact date/description/amount/direction/currency
shape Task 3 established. Update `Section`/`sectionsJson()` (from Task 1) to accept and render an
optional `List<Map<String,String>> transactions` (null when not synthetic, matching the existing
null-means-absent convention throughout this class).

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=CorpusProbeTest`
Expected: PASS, all tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/test/java/com/finora/imports/analysis/CorpusProbe.java backend/src/test/java/com/finora/imports/analysis/CorpusProbeTest.java
git commit -m "feat(imports): CorpusProbe --synthetic opt-in emits per-row transaction content"
```

---

### Task 5: explicit opt-in for pointing corpus scripts at an in-repo (synthetic) corpus

**Files:**
- Modify: `scripts/corpus-run.py`
- Modify: `scripts/run-corpus-ground-truth.py`
- Test: `scripts/test-corpus-diff.py` or a new `scripts/test-corpus-run.py` if none of the existing
  self-test files already cover `_refuse_if_inside_repo` — check first with
  `grep -rl _refuse_if_inside_repo scripts/test-*.py`.

**Interfaces:**
- Consumes: nothing new.
- Produces: both scripts' `_refuse_if_inside_repo(path)` call sites gain a sibling
  `--allow-in-repo-synthetic-corpus` CLI flag (explicit, unabbreviated, impossible to pass by
  accident) that, when set, skips the refusal for that one invocation. Both scripts must keep
  refusing by default — this flag exists solely for Task 6's committed synthetic fixture directory.

- [ ] **Step 1: Write the failing test**

Add (to whichever self-test file is appropriate per the grep above):

```python
def test_refuse_if_inside_repo_is_skipped_only_with_the_explicit_flag(self):
    # Uses this script file's own directory as a stand-in "inside the repo" path -- never the
    # real corpus location, which this test must not reference even as a string.
    inside_repo_path = Path(__file__).parent
    with self.assertRaises(SystemExit):
        _refuse_if_inside_repo(inside_repo_path, allow_in_repo_synthetic_corpus=False)
    # Does not raise:
    _refuse_if_inside_repo(inside_repo_path, allow_in_repo_synthetic_corpus=True)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python3 <the test file from Step 1>`
Expected: FAIL — `_refuse_if_inside_repo` doesn't accept the new keyword argument yet.

- [ ] **Step 3: Implement**

In both `corpus-run.py` and `run-corpus-ground-truth.py`, add `allow_in_repo_synthetic_corpus=False`
as a keyword parameter to `_refuse_if_inside_repo`, short-circuiting the refusal when `True`. Add
`--allow-in-repo-synthetic-corpus` (`action="store_true"`) to each script's `argparse` setup, and
thread it through to the `_refuse_if_inside_repo` call site.

- [ ] **Step 4: Run test to verify it passes**

Run: `python3 <the test file from Step 1>`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add scripts/corpus-run.py scripts/run-corpus-ground-truth.py scripts/test-corpus-diff.py
git commit -m "feat(imports): explicit opt-in for corpus scripts to target an in-repo synthetic corpus"
```

---

### Task 6: one demonstrative synthetic canonical fixture, CI-gated end-to-end

**Files:**
- Create: `backend/src/test/resources/synthetic-corpus-regression/mechanism-proof.pdf` (rendered,
  committed binary — generate via a throwaway local run of `PdfFixtureBuilder`, do not hand-craft)
- Create: `backend/src/test/resources/synthetic-corpus-regression/ground-truth/mechanism-proof.json`
  (generated via `GroundTruthDocument.of(...)`, committed as text)
- Create: `backend/src/test/java/com/finora/imports/analysis/SyntheticFixtureGenerator.java` (a
  small `main()`-only helper, following `CorpusProbe`'s/`ClosingBalanceCorpusProbe`'s existing
  pattern of manual-invocation generator classes, used once locally to produce the two committed
  files above — not itself part of the regression check)
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: `SyntheticStatementDefinition` + `PdfFixtureBuilder` (existing), `CorpusProbe.probe(path,
  true)` (Task 4), `GroundTruthDocument.of(...)` (existing, extended by Task 3), `ground-truth-
  match.py` (Task 2/3), `run-corpus-ground-truth.py --allow-in-repo-synthetic-corpus` (Task 5).
- Produces: a new CI step that fails the build if this fixture's `CorpusProbe --synthetic` output
  doesn't `PASS` against its committed ground truth. This is the first thing in the whole corpus
  tooling stack that is genuinely CI-gated end-to-end (probe → match → merge-blocking), closing the
  "CI has no access to the corpus" gap for exactly the slice that's safe to close: synthetic,
  committed, reviewed content.

Read `docs/architecture/system-design/header-reconstruction-regression-corpus.md` before writing
this task's fixture — it's the closest existing precedent for "a small number of named regression
documents with an explicit correctness bar," and this fixture should follow the same review
discipline (a human reads the intended output and confirms it's right, not just "the code produced
this so it must be right").

- [ ] **Step 1: Design and hand-review the fixture's intended content**

Author a `SyntheticStatementDefinition` for a minimal statement: one `SAVINGS` entity, 3-5 rows,
each with a hand-written, unambiguous description (e.g. `"Grocery store"`, `"Salary credit"`,
`"ATM withdrawal"` — plainly fictional, no resemblance to any real institution's real statement
text). This is not yet a reproduction of any specific known bug (that happens per-bug in Phase 3a/
3b/4a, each of which should add its own fixture here using this same mechanism) — it exists solely
to prove probe → render → match works end-to-end before those phases depend on it.

- [ ] **Step 2: Generate and commit the two fixture files**

Write `SyntheticFixtureGenerator.main()` to: build the `SyntheticStatementDefinition` from Step 1,
render it via `PdfFixtureBuilder` to `synthetic-corpus-regression/mechanism-proof.pdf`, and write
`GroundTruthDocument.of(definition)` to `synthetic-corpus-regression/ground-truth/mechanism-
proof.json`. Run it once locally (`./mvnw -q test-compile exec:java -Dexec.mainClass=...` or
equivalent), inspect both committed files by hand, then `git add` them.

- [ ] **Step 3: Verify the mechanism catches a real mismatch, manually, before wiring CI**

Temporarily edit the committed ground-truth JSON's expected description for one row to something
wrong, run `python3 scripts/run-corpus-ground-truth.py --corpus backend/src/test/resources/synthetic-corpus-regression --ground-truth backend/src/test/resources/synthetic-corpus-regression/ground-truth --allow-in-repo-synthetic-corpus` (this composes `CorpusProbe --synthetic` internally per its
existing behavior — confirm it actually invokes the probe with `--synthetic`, extending its own
argument-passing if it doesn't yet), confirm it reports `FAIL`, then revert the edit and confirm it
reports `PASS`. This step is manual verification, not a new automated test — it's checking that the
whole chain is wired correctly before CI depends on it.

- [ ] **Step 4: Wire the CI step**

Add to `.github/workflows/ci.yml`, alongside the existing `Corpus diff (self-test)` /
`Ground-truth matcher (self-test)` steps:

```yaml
- name: Synthetic corpus regression (mechanism-gated)
  run: python3 scripts/run-corpus-ground-truth.py \
       --corpus backend/src/test/resources/synthetic-corpus-regression \
       --ground-truth backend/src/test/resources/synthetic-corpus-regression/ground-truth \
       --allow-in-repo-synthetic-corpus
```

Place it after the backend build step (it needs compiled classes on the classpath to invoke
`CorpusProbe` the same way `run-corpus-ground-truth.py` already does for local use).

- [ ] **Step 5: Run the full backend suite to confirm nothing else broke**

Run: `cd backend && ./mvnw -q test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/test/resources/synthetic-corpus-regression backend/src/test/java/com/finora/imports/analysis/SyntheticFixtureGenerator.java .github/workflows/ci.yml
git commit -m "feat(imports): CI-gated synthetic corpus regression fixture, mechanism proof"
```

---

### Task 7: `corpus-diff.py` detects description drift without ever seeing description text

**Files:**
- Modify: `backend/src/test/java/com/finora/imports/analysis/CorpusProbe.java` (real-corpus path —
  unconditional, not behind `--synthetic`)
- Modify: `scripts/corpus-diff.py`
- Test: `backend/src/test/java/com/finora/imports/analysis/CorpusProbeTest.java`
- Test: `scripts/test-corpus-diff.py`

**Interfaces:**
- Consumes: `StagedRow.description()` (hashed, never emitted raw, on the default/real-corpus path).
- Produces: each `Section` in `CorpusProbe`'s (non-synthetic) output gains a
  `"descriptionHashes":[...]` array — one short hex digest per row, in row order, computed as
  `SHA-256(description).substring(0, 16)` (documented inline as intentionally one-way: this exists
  to detect change, never to reconstruct or compare content across documents). `corpus-diff.py`
  gains a `compare_description_drift(b, a, out)` step: when both sides have the same row count for a
  section, count positions where the hash differs and report `f"{changed}/{total} rows ({rate}%)"`
  at `REVIEW` severity (never `REGRESSION` — a human decides whether the change is the bug or the
  fix, matching the Phase 2 design decision); when row counts differ, this dimension is silently
  skipped for that section (the existing `sections` count-change finding already flags that case,
  and a positional hash comparison across a length change is meaningless, same reasoning as
  `compare_sections`'s existing section-count-changed early return).

- [ ] **Step 1: Write the failing tests**

Java (`CorpusProbeTest.java`):

```java
@Test
void sectionsJson_emitsOneDescriptionHashPerRow_neverTheRawText() {
    // Build a StagedAccountSection with 2 StagedRows with distinct descriptions, run probe() or
    // call the section-building logic directly (whichever this class's existing tests already do
    // for row-level assertions -- follow that pattern), and assert:
    //   - the JSON contains a "descriptionHashes" array of length 2
    //   - neither of the two literal description strings used in the test appears anywhere in
    //     the JSON output
}
```

Python (`test-corpus-diff.py`):

```python
def test_a_changed_description_hash_is_reported_as_review_not_regression(self):
    before = _record(sections=[_section(rows=2, description_hashes=["aaaa", "bbbb"])])
    after = _record(sections=[_section(rows=2, description_hashes=["aaaa", "cccc"])])
    changes = compare_record(before, after)
    drift = [c for c in changes if c["dimension"] == "section[0].descriptionDrift"]
    self.assertEqual(len(drift), 1)
    self.assertEqual(drift[0]["severity"], REVIEW)
    self.assertIn("1/2", drift[0]["detail"])

def test_no_drift_dimension_when_row_count_changed(self):
    before = _record(sections=[_section(rows=2, description_hashes=["aaaa", "bbbb"])])
    after = _record(sections=[_section(rows=3, description_hashes=["aaaa", "bbbb", "eeee"])])
    changes = compare_record(before, after)
    drift = [c for c in changes if c["dimension"] == "section[0].descriptionDrift"]
    self.assertEqual(drift, [])
```

(Extend `test-corpus-diff.py`'s existing `_record`/`_section` synthetic builders with a
`description_hashes` keyword, following its established pattern.)

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd backend && ./mvnw -q test -Dtest=CorpusProbeTest && python3 scripts/test-corpus-diff.py`
Expected: FAIL on both.

- [ ] **Step 3: Implement the Java side**

In `CorpusProbe.java`, compute `descriptionHashes` per section unconditionally (not gated by the
`synthetic` flag from Task 4 — this is hash-only, never raw text, so it's safe on the default real-
corpus path too) using `java.security.MessageDigest.getInstance("SHA-256")`. Add the field to
`Section` and its `sectionsJson()` rendering, following the same pattern as Task 1's fields. Document
inline, directly above the hashing code, exactly why this is one-way (matches this task's own
Produces note above — keep the comment and the code from drifting apart).

- [ ] **Step 4: Implement the Python side**

In `corpus-diff.py`, add:

```python
def compare_description_drift(b, a, out):
    """Row-content CHANGE, never row-content VALUE -- see CorpusProbe's descriptionHashes comment.
    A hash differing is not a verdict on which side is right; only a human with the real PDFs open
    locally can say that, which is why this is always REVIEW and never REGRESSION."""
    bh, ah = b.get("descriptionHashes"), a.get("descriptionHashes")
    if bh is None or ah is None or len(bh) != len(ah) or not bh:
        return
    changed = sum(1 for x, y in zip(bh, ah) if x != y)
    if changed:
        rate = round(100 * changed / len(bh))
        out.append(_c("descriptionDrift", REVIEW, f"{changed}/{len(bh)} rows ({rate}%) hash-differ"))
```

Call it from `compare_sections()`'s per-section loop (after the existing `compare_verification`
call), passing `x`/`y` as `b`/`a` and prefixing the dimension name with `p` the same way every other
per-section dimension in that loop already does — i.e. change the function to accept `out` with the
dimension already prefixed, matching the existing pattern exactly (adjust the dimension string
inside `compare_description_drift` to accept a `prefix` parameter, mirroring
`compare_verification`'s signature).

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd backend && ./mvnw -q test -Dtest=CorpusProbeTest && python3 scripts/test-corpus-diff.py`
Expected: PASS, all tests including pre-existing ones (in particular
`Genericity.test_the_script_contains_no_institution_name_and_no_corpus_filename` — confirm the new
function still contains no filename/institution constant).

- [ ] **Step 6: Commit**

```bash
git add backend/src/test/java/com/finora/imports/analysis/CorpusProbe.java backend/src/test/java/com/finora/imports/analysis/CorpusProbeTest.java scripts/corpus-diff.py scripts/test-corpus-diff.py
git commit -m "feat(imports): corpus-diff.py detects description drift via row-content hashes"
```

---

### Task 8: document the suite and the manual real-corpus process

**Files:**
- Create: `docs/architecture/system-design/corpus-regression-suite.md`

**Interfaces:**
- Consumes: nothing (documentation only).
- Produces: a doc explaining, for whoever opens the next Phase 3a/3b/4a/5 PR: (1) what's CI-gated
  today (Task 6's synthetic mechanism-proof fixture, and instructions for adding a new one per
  known-bug-shape as each phase fixes its bug) and why it can never expand to the real corpus, (2)
  the exact commands to run locally before opening a PR (`corpus-run.py` before/after,
  `corpus-diff.py` the two, `run-corpus-ground-truth.py` against the 21 established real
  ground-truth files), (3) what to do when `corpus-diff.py` reports a non-zero `descriptionDrift`
  rate (open the flagged rows' before/after text locally against the real PDF, decide if it's the
  fix or a new corruption, paste a summary — not raw customer text — into the PR description), and
  (4) an explicit, tracked note that the 21 existing real ground-truth files do not yet have the
  new `expectedOpeningBalance`/etc. fields from Task 2 populated, and establishing them requires
  reading each real PDF by hand (never copying `CorpusProbe`'s own output, per this plan's Global
  Constraints) — named as follow-up work this plan does not include.

- [ ] **Step 1: Write the doc**

Follow the structure and tone of `docs/architecture/system-design/header-reconstruction-regression-corpus.md` (the closest existing precedent) — concrete commands, no placeholders, explicit about what
this doesn't do.

- [ ] **Step 2: Cross-check against every script this plan touched**

Re-read `corpus-run.py`, `corpus-diff.py`, `run-corpus-ground-truth.py`, `ground-truth-match.py`,
and `CorpusProbe.java` as they now stand (post Tasks 1-7) and confirm every command in the doc is
copy-pasteable and correct — run each one for real against a local test PDF before committing the
doc.

- [ ] **Step 3: Commit**

```bash
git add docs/architecture/system-design/corpus-regression-suite.md
git commit -m "docs(imports): document the corpus regression suite and its manual real-corpus step"
```

---

## Follow-up work explicitly NOT included in this plan

- **Retrofitting the 21 existing real ground-truth files** (at `~/Downloads/Bank statement/
  ground-truth/`, outside the repo) with the new `expectedOpeningBalance`/`expectedClosingBalance`/
  `expectedStatementPeriodStart`/`expectedStatementPeriodEnd`/`expectedCreditLimit`/
  `expectedTotalAmountDue`/`expectedPaymentDueDate` fields, and establishing ground truth for the 6
  currently-unestablished documents. This is real per-document verification work against real PDFs
  (up to 7 fields × 27 documents), tracked as a follow-up, not blocking this phase's own merge.
- **Per-bug canonical synthetic fixtures** (BOB footer/continuation-budget, canara directional
  attribution, HDFC-credit disclaimer footer, wrapped-header FD/RD collapse) — each of Phase 3a/3b/
  4a should add its own fixture using Task 6's mechanism as part of fixing that specific bug (the
  fixture IS the regression test for the fix), not speculatively ahead of time here.
