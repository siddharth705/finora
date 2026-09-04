# Held Statement Review — Plan 3 of 4: Engineer Workflow & Parser Re-run

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the one risk [Plan 2](2026-09-04-trust-review-operator-portal.md) explicitly left open — an
operator can approve a held statement without ever confirming the parser bug that caused the hold
has actually been fixed. Let an engineer re-run the *current* parser build against a held
statement's original bytes, see whether the trust predicate would still flag it, and record what
was wrong and where the fix landed — without ever touching the staged rows a later approve still
needs.

**Architecture:** The re-run reads the held statement's original bytes (the same
`StatementContentService.read(job)` call Plan 2's download endpoint already uses) and re-parses
them through a new, zero-write entry point on `ImportService` — never the live
`parseAndStageWithSession`/`parseAndStagePdfWithSession` path, which would delete the very
`ImportSession` a later approve still depends on (see Global Constraints). The fresh parse feeds
the same `TrustPredicate.evaluate(...)` the worker itself uses, and the result is recorded as one
event, never as new rows in the write-once `import_verification_findings` table. When it now
clears, the hold's dead `READY_FOR_IMPORT` status — defined in Plan 1, never reached by any code
path — finally gets a producer.

**Tech Stack:** Spring Boot / JPA / Flyway, Postgres, React + TypeScript (admin-portal). No new
libraries.

**Spec:** The repository owner's approved brief, "Held Statement Review System — Implementation
Plan" (in-conversation, 2026-09-03), the remainder of Phase 6 (the notes taxonomy Plan 2
deliberately left as free text) and Phase 7 (parser re-run) in full, per the scope table in
[Plan 1](2026-09-03-trust-review-held-statements.md#scope-check).

## Global Constraints

- **The re-run must never call `ImportSessionService.findLiveSessionByContentHash`, directly or
  indirectly, against a held statement's content hash.** That method deletes a live `STAGED`
  session whenever its recorded `parserVersion` differs from the currently running build's
  (`ImportSessionService.java:509-524`) — exactly the condition a parser re-run exists to test.
  `ImportJob.importSessionId` still points at that session; deleting it orphans the id and breaks
  `approve()`'s later confirm flow. This is why the live `ImportService.parseAndStageWithSession`/
  `parseAndStagePdfWithSession` methods are off-limits for a re-run and Task 2 adds a separate,
  zero-write entry point instead.
- **A re-run must write no new `ImportVerificationFinding` rows.** That table has no attempt or
  parser-version column and every row is `updatable = false`
  (`ImportVerificationFinding.java:53-74`); `ImportVerificationRecorder.recordForJob` always
  inserts, never upserts. A second call against the same `importJobId` would sit indistinguishably
  beside the original hold's evidence in `HeldStatementService.detail`. A re-run's evidence lives
  in a `HeldStatementEvent`, which is built for exactly this (append-only, one row per action).
- **`HeldStatement.parserVersion` is never overwritten by a re-run.** It is Plan 1's snapshot of
  the build that produced the *staged rows still sitting in `import_sessions`* — a re-run never
  re-stages, so the snapshot stays true of what approve would actually release. What changes on a
  re-run is only the hold's status and its event log.
- **Storage is immutable** (Plan 1's own constraint, restated): the re-run reads the same
  `statement_object_key` every other action on this hold already reads. No second copy.
- **Telemetry can never break a re-run's own visible outcome.** Same rule V62 and Plan 1 both
  state: writing the `HeldStatementEvent` or the audit entry must never be what makes the endpoint
  fail — but unlike those, a re-run's *primary* output is diagnostic, so this constraint mainly
  guards against a notification or audit write turning a successful parser comparison into a 500.
- **`HeldStatement` gets `@Version`.** Every other concurrently-written entity in this codebase
  already carries one (`ImportJob.version`'s own doc: "BH-001. Every other concurrently-written
  entity here already carries this") — `HeldStatement` is the exception, and Plan 2 already put
  three independent writers (assign, investigate, notes) on the same row with no protection against
  a lost update. Plan 3 adds a fourth (`rerunParser`) running concurrently with `approve`, which is
  exactly the scenario (an engineer and an operator acting on the same hold near-simultaneously)
  this gap stops being theoretical for. `GlobalExceptionHandler.handleOptimisticLock` already
  answers 409 for `OptimisticLockingFailureException` — no new exception handling needed, only the
  column and the annotation.
- **A re-run's trust evaluation is anchored to when the hold was first opened, not to when the
  re-run happens.** `TrustPredicate.evaluate`'s `periodIntegrity` check compares a statement's own
  period against a `today` argument to catch a period that claims to extend into the future.
  `ImportJobWorker` passes `LocalDate.now(UTC)` at parse time, which is effectively
  `HeldStatement.createdAt`'s date. If a re-run instead passed the CURRENT date, the exact same
  statement bytes, re-evaluated months later, could stop being flagged as "in the future" for no
  reason but the passage of calendar time — a rerun would then look like it exonerates the parser
  when nothing about parsing changed at all. Task 3 anchors the re-run's `today` to
  `held.getCreatedAt()`'s date instead.

## Decisions this plan makes and why

| Question | Decision | Why |
|---|---|---|
| Structure the engineer's write-up now, or keep it free text? | **Two new plain columns: `root_cause`, `fix_reference`** | Plan 2 refused to structure `engineer_notes` because "structuring it before anyone has written any would be inventing a schema for work nobody has done." This plan *is* that work — root cause and fix reference are the two facts a parser-bug write-up always needs, and Task 6's re-run event needs somewhere machine-readable to point an engineer back to. `engineer_notes` itself stays untouched free text for everything else. |
| Does a re-run go through the live staging pipeline? | **No — a new `ImportService.dryRunParse` entry point** | The live path's duplicate-upload guard deletes stale sessions on version mismatch (see Global Constraints). Reusing it for a re-run would delete the session a held statement's later approve needs. |
| Does clearing a re-run auto-approve? | **No — it only reaches `READY_FOR_IMPORT`** | `HeldStatement.markImported`'s own doc already establishes "one resolution, not one path": approve is a human decision made from any open status, including `READY_FOR_IMPORT`. A re-run that auto-approved would let a machine make the release decision Plan 1 built this whole system to keep human. |
| Introduce a distinct ENGINEER role/permission? | **No — reuse `TRUST_REVIEW_MANAGE`, unchanged** | No `ENGINEER` role exists anywhere in this codebase today (confirmed by grep), and `assign(engineerId, ...)` already accepts any user id with no permission check — that gap predates this plan and stays open (see Known Risks). Inventing a role for one plan, on top of an assignment mechanism that doesn't enforce it, would be scope creep this plan doesn't need. |
| What happens to a password-protected PDF? | **Re-run fails cleanly with an explained error; the engineer falls back to reviewing without one** | No password is ever persisted for a `HeldStatement` or `ImportJob` — correctly, for security — so a re-run has nothing to open the file with. `dryRunParse` passes `null` and lets the resulting `ApiException` surface as the re-run's own "still held" reason, rather than crashing. |
| Where does the current parser version come from? | **A new shared `ParserVersionProvider` bean, used by both `ImportJobWorker` and the rerun path** | The original design had `HeldStatementService` carry its own `@Value("${app.parser-version:${RAILWAY_GIT_COMMIT_SHA:}}")` field, duplicating `ImportJobWorker`'s identical one. Two independently-maintained copies of the same expression is exactly the kind of drift this codebase has been bitten by before ([[preauthorize-method-overrides-class-level]], [[readonly-transactional-silently-drops-writes]] are both "the same fact expressed twice, and the copies disagreed" bugs in shape) — a future edit to one and not the other would make `parserVersionChanged` report a false positive on every hold, permanently. One bean, two consumers. |
| How does `rerunParser` avoid a lost update against a concurrent `approve`? | **`@Version` on `HeldStatement` (see Global Constraints), plus a concurrency IT** | `HeldStatement` was the one mutated-by-multiple-actors entity in this codebase without one. Without it, Hibernate's `save()` is a blind `UPDATE ... WHERE id = ?` with no version predicate — whichever transaction commits last silently overwrites the other's status change with no exception, no log line, nothing to search for later. |

## File Structure

- Modify: `backend/src/main/resources/db/migration/` — add `V151__held_statement_findings.sql`
  (verify 151 is still free before writing it — re-run the check in Task 1 Step 1).
- Modify: `backend/src/main/java/com/finora/entity/HeldStatement.java` — `rootCause`,
  `fixReference`, `version` fields and `recordEngineerFindings`.
- Modify: `backend/src/main/java/com/finora/imports/ImportService.java` — new `dryRunParse` method
  and `DryRunResult` record.
- Create: `backend/src/main/java/com/finora/imports/jobs/ParserVersionProvider.java` — the single
  source both `ImportJobWorker` and `HeldStatementService` read the running build's version from.
- Modify: `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java` — replace its private
  `@Value` `parserVersion` field with an injected `ParserVersionProvider`.
- Modify: `backend/src/main/java/com/finora/service/HeldStatementService.java` — new
  `recordFindings` and `rerunParser` methods, new `ImportService`/`ParserVersionProvider`
  dependencies.
- Create: `backend/src/main/java/com/finora/dto/HeldStatementRerunResultDto.java`.
- Modify: `backend/src/main/java/com/finora/dto/HeldStatementDto.java` — `rootCause`,
  `fixReference` fields.
- Modify: `backend/src/main/java/com/finora/controller/AdminHeldStatementController.java` — two new
  endpoints.
- Modify tests: `HeldStatementTest.java`, `HeldStatementRepositoryIT.java` (if column defaults
  matter there), a new `backend/src/test/java/com/finora/imports/ImportServiceDryRunIT.java`, and
  `AdminHeldStatementControllerIT.java` (or a new
  `AdminHeldStatementRerunIT.java` — Task 4 decides which after reading the existing file's size).
- Modify: `admin-portal/src/types/index.ts`, `admin-portal/src/api/endpoints.ts`,
  `admin-portal/src/pages/HeldStatementDetail.tsx`, `admin-portal/src/pages/HeldStatementDetail.test.tsx`.

---

### Task 1: Schema and entity — root cause and fix reference

**Files:**
- Create: `backend/src/main/resources/db/migration/V151__held_statement_findings.sql`
- Modify: `backend/src/main/java/com/finora/entity/HeldStatement.java`
- Test: `backend/src/test/java/com/finora/entity/HeldStatementTest.java`

**Interfaces:**
- Produces: `HeldStatement.getRootCause()`, `HeldStatement.getFixReference()`,
  `HeldStatement.recordEngineerFindings(String rootCause, String fixReference)` — consumed by
  Task 3's service method. `HeldStatement`'s new `@Version` field, protecting every existing
  mutating method (`assign`/`startInvestigation`/`addNotes`/`markImported`/`reject`) as well as
  Task 3's new ones — no interface change for callers, just a 409 instead of a silent overwrite on
  a write collision.

- [ ] **Step 1: Confirm V151 is still free**

```bash
git fetch origin
ls backend/src/main/resources/db/migration | sed -E 's/^V([0-9]+)__.*/\1/' | sort -n | tail -3
git ls-tree -r --name-only origin/main -- backend/src/main/resources/db/migration | sed -E 's#.*/V([0-9]+)__.*#\1#' | sort -n | tail -3
```

If either shows 151 or higher already taken, use the next free number and rename the file below
accordingly.

- [ ] **Step 2: Write the migration**

```sql
-- What an engineer found and fixed, recorded once a parser gap is diagnosed. Two plain columns
-- rather than folding this into engineer_notes -- see HeldStatementService.addNotes's own doc for
-- why that column stays free text (structuring it before anyone had written any note would have
-- been inventing a schema for work nobody had done). Plan 3 is that work: root_cause and
-- fix_reference are the two facts a parser-bug write-up always needs, kept separate from
-- engineer_notes so a re-run's own event log has somewhere machine-readable to point back to.
--
-- Both nullable: most holds never need an engineer, and never get one.
ALTER TABLE held_statements ADD COLUMN root_cause TEXT;
ALTER TABLE held_statements ADD COLUMN fix_reference VARCHAR(200);

COMMENT ON COLUMN held_statements.root_cause IS
    'What the engineer found wrong with the parser, if anything. Free text, set once diagnosed.';
COMMENT ON COLUMN held_statements.fix_reference IS
    'Where the fix landed -- a PR number or URL, at the engineer''s discretion. Free text, not validated.';

-- Optimistic locking. held_statements is mutated by several independent actors on the same row --
-- assign, investigate, notes, findings, approve, reject, and (Plan 3) rerun-parser -- and until
-- now carried no @Version, unlike every other concurrently-written entity in this codebase (see
-- ImportJob.version's own doc, BH-001). Without it, two concurrent admin actions on the same hold
-- resolve as a silent last-write-wins UPDATE: no exception, no log line, just whichever commit
-- landed second overwriting the first admin's change. GlobalExceptionHandler.handleOptimisticLock
-- already answers 409 for the resulting ObjectOptimisticLockingFailureException -- adding the
-- column is the only new thing this needs.
ALTER TABLE held_statements ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
```

- [ ] **Step 3: Add the failing entity test**

In `HeldStatementTest.java`, alongside the existing lifecycle tests:

```java
@Test
void recordEngineerFindingsSetsBothFieldsAndCanBeCalledOnAResolvedHold() {
    HeldStatement held = newHeld();
    held.markImported(UUID.randomUUID(), NOW);

    held.recordEngineerFindings("Header row misdetected on a two-line HSBC header", "PR #950");

    assertThat(held.getRootCause()).isEqualTo("Header row misdetected on a two-line HSBC header");
    assertThat(held.getFixReference()).isEqualTo("PR #950");
}
```

(Match this test's exact `newHeld()`/`NOW` helpers to whatever the existing file already defines —
read the file first.)

- [ ] **Step 4: Run it, confirm it fails to compile** (the method doesn't exist yet)

```bash
cd backend && ./mvnw test -Dtest=HeldStatementTest -q
```

- [ ] **Step 5: Add the fields and method to `HeldStatement.java`**

```java
@Column(name = "root_cause")
private String rootCause;

@Column(name = "fix_reference")
private String fixReference;

/** BH-001, same reasoning as {@code ImportJob.version}'s own doc: every other
 *  concurrently-written entity here already carries one. See this plan's Global Constraints for
 *  why this row specifically needed it before Plan 3 could safely add a fourth concurrent writer. */
@jakarta.persistence.Version
@Column(nullable = false)
private Long version = 0L;
```

Add all three fields next to `engineerNotes` (`version` can sit wherever the class's other
bookkeeping fields already are — match `ImportJob.java`'s placement of its own `version` field for
consistency). Add the method next to `addNotes`:

```java
/** Records what an engineer found and where the fix landed. Replaces both fields wholesale, same
 *  as {@link #addNotes} -- the history lives in {@code held_statement_events}, not a second pair
 *  of columns. Deliberately not guarded by {@link #refuseIfResolved}, for the same reason
 *  {@code addNotes} isn't: writing up a root cause after the hold is already resolved is a
 *  legitimate thing to do, not a state-machine violation. */
public void recordEngineerFindings(String rootCause, String fixReference) {
    this.rootCause = rootCause;
    this.fixReference = fixReference;
}
```

Add getters next to the other getters:

```java
public String getRootCause() { return rootCause; }
public String getFixReference() { return fixReference; }
public Long getVersion() { return version; }
```

- [ ] **Step 6: Run the test, confirm it passes**

```bash
cd backend && ./mvnw test -Dtest=HeldStatementTest -q
```

- [ ] **Step 7: Self-review this task**

Check for bugs and gaps missed in this task before committing: confirm the migration comment's
claim about `addNotes`'s own doc is accurate (read it), confirm no other test in
`HeldStatementRepositoryIT`/`HeldStatementQueryIT` constructs a `HeldStatement` in a way that would
break from the new nullable columns (it shouldn't — no non-null constraint was added), and confirm
`HeldStatementTest` doesn't already have a test with the same name.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/resources/db/migration/V151__held_statement_findings.sql \
        backend/src/main/java/com/finora/entity/HeldStatement.java \
        backend/src/test/java/com/finora/entity/HeldStatementTest.java
git commit -m "feat: add root cause and fix reference to held statements"
```

---

### Task 2: Zero-write parser re-run on `ImportService`

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/ImportService.java`
- Test: Create `backend/src/test/java/com/finora/imports/ImportServiceDryRunIT.java`

**Interfaces:**
- Consumes: `PreviewGenerator.generateWithContext(UUID, String, InputStream)`,
  `PdfPreviewGenerator.generateSectionsWithContext(UUID, String, byte[], String)` — both already
  constructor-injected into `ImportService`. `ExtractionCheck.rejectIfNothingWasExtracted(...)`,
  `ImportService`'s own private `onlySectionsThatAreActuallyAccounts(List<StagedAccountSection>)`
  and `toStagingResponse(StagedAccountSection)` — all already present in this class.
- Produces: `ImportService.dryRunParse(UUID userId, String fileName, byte[] fileContent, String
  sourceFormat) throws IOException`, returning `ImportService.DryRunResult(List<VerificationReport>
  verificationReports, List<LocalDate[]> statementPeriods)` — consumed by Task 3.

This is the task the Global Constraints section exists for: read it again before writing this
method. The live `parseAndStageWithSession`/`parseAndStagePdfWithSession` methods are not reused
here — they create/reuse an `ImportSession` and call `findLiveSessionByContentHash` first, which
would delete a held statement's still-referenced session on a parser-version mismatch. This method
calls the same `pdfPreviewGenerator`/`previewGenerator` beans those methods call, and the same
private section-filtering/rejection helpers, but stops before any session or evidence-table write.

- [ ] **Step 1: Write the failing test**

Add `ImportServiceDryRunIT.java`. Follow the existing IT pattern this codebase uses for real
`ImportService` integration tests — `@SpringBootTest`, autowire `ImportService`, use a real fixture
from the corpus or an inline minimal CSV. Read `ImportServiceIT.java` (or whichever existing IT
autowires `ImportService` against real Spring context) first to match its base-class/profile setup
exactly, then write:

```java
@Test
void dryRunParseReturnsVerificationAndPeriodsWithoutWritingAnySession(@Autowired
        ImportSessionRepository importSessionRepository) throws Exception {
    UUID userId = createTestUser();
    byte[] csv = ("Date,Description,Amount,Balance\n"
            + "01/01/2026,Opening balance,,1000.00\n"
            + "05/01/2026,Coffee shop,-150.00,850.00\n").getBytes(StandardCharsets.UTF_8);
    long sessionsBefore = importSessionRepository.count();

    ImportService.DryRunResult result = importService.dryRunParse(userId, "statement.csv", csv, "CSV");

    assertThat(result.verificationReports()).isNotNull();
    assertThat(result.statementPeriods()).isNotNull();
    assertThat(importSessionRepository.count()).isEqualTo(sessionsBefore);
}

@Test
void dryRunParseTwiceInARowDoesNotTriggerTheLiveDuplicateGuard() throws Exception {
    // The live path's findLiveSessionByContentHash would short-circuit a second call on the same
    // bytes. A dry run must not: an engineer re-running the same held statement's bytes twice
    // (e.g. after fixing nothing, checking again) must get two independent parses, not a
    // duplicate-detection response meant for a different feature.
    UUID userId = createTestUser();
    byte[] csv = ("Date,Description,Amount,Balance\n"
            + "01/01/2026,Opening balance,,1000.00\n").getBytes(StandardCharsets.UTF_8);

    ImportService.DryRunResult first = importService.dryRunParse(userId, "statement.csv", csv, "CSV");
    ImportService.DryRunResult second = importService.dryRunParse(userId, "statement.csv", csv, "CSV");

    assertThat(first.verificationReports()).hasSameSizeAs(second.verificationReports());
}
```

(Match `createTestUser()` to whatever helper the existing IT base class already provides — read it
first rather than inventing a new one.)

- [ ] **Step 2: Run it, confirm it fails to compile** (`dryRunParse` doesn't exist)

```bash
cd backend && ./mvnw test -Dtest=ImportServiceDryRunIT -q
```

- [ ] **Step 3: Implement `dryRunParse` on `ImportService`**

Add near `parseAndStageWithSession`/`parseAndStagePdfWithSession`:

```java
/**
 * Re-runs the current parser build against a document's raw bytes and reports what it would find,
 * without writing anything: no {@code ImportSession}, no evidence-table row, no duplicate-upload
 * bookkeeping.
 *
 * <p>Built for Plan 3 of the Held Statement Review System. An engineer re-testing a held statement
 * after a parser fix needs to know what the CURRENT build produces, and the live staging path
 * ({@link #parseAndStageWithSession} / {@link #parseAndStagePdfWithSession}) cannot safely answer
 * that: both call {@link ImportSessionService#findLiveSessionByContentHash} first, which deletes a
 * live {@code STAGED} session whenever its recorded parser version differs from the running
 * build's -- exactly the condition a re-run exists to test. Running a held statement's own
 * still-referenced session through that check would delete the very session {@code
 * ImportJob.importSessionId} still points at.
 *
 * @throws ApiException the same rejection a live parse would throw (e.g. {@code
 *         IMPORT_NO_ACTIVITY_IN_PERIOD}) if the current build extracts nothing at all from these
 *         bytes -- a real, reportable re-run outcome, not a bug in this method. The caller decides
 *         what that means for the hold.
 */
public DryRunResult dryRunParse(UUID userId, String fileName, byte[] fileContent, String sourceFormat)
        throws IOException {
    if (StatementUpload.Format.PDF.name().equals(sourceFormat)) {
        var result = pdfPreviewGenerator.generateSectionsWithContext(userId, fileName, fileContent, null);
        List<StagedAccountSection> sections = onlySectionsThatAreActuallyAccounts(result.sections());
        ExtractionCheck.rejectIfNothingWasExtracted(sections, result.documentContext());
        if (sections.size() <= 1) {
            StagingResponse staged = sections.isEmpty()
                    ? new StagingResponse(List.of(), 0, 0, null, List.of())
                    : toStagingResponse(sections.get(0));
            return new DryRunResult(reportsOf(staged.verification()),
                    List.of(periodOf(staged.detectedAccount())));
        }
        return new DryRunResult(
                sections.stream().map(StagedAccountSection::verification)
                        .filter(java.util.Objects::nonNull).toList(),
                sections.stream().map(s -> periodOf(s.detectedAccount())).toList());
    }
    var result = previewGenerator.generateWithContext(userId, fileName,
            new java.io.ByteArrayInputStream(fileContent));
    StagingResponse staged = result.response();
    ExtractionCheck.rejectIfNothingWasExtracted(staged, result.documentContext());
    return new DryRunResult(reportsOf(staged.verification()), List.of(periodOf(staged.detectedAccount())));
}

/** One report per section for the caller ({@code TrustPredicate}), same convention {@code
 *  StagedForJob} already uses -- absent verification and verification that found nothing are
 *  different facts, so a null report yields an empty list, never a list holding null. */
private static List<ImportDto.VerificationReport> reportsOf(ImportDto.VerificationReport one) {
    return one == null ? List.of() : List.of(one);
}

/** {@code {start, end}}, possibly holding nulls -- a missing period is never on its own a reason
 *  to hold, so it is carried rather than dropped. Same helper {@code StagedForJob} keeps privately
 *  for the live path; duplicated here rather than shared across packages for a four-line method. */
private static LocalDate[] periodOf(DetectedAccountInfo detected) {
    return detected == null
            ? new LocalDate[]{null, null}
            : new LocalDate[]{detected.statementPeriodStart(), detected.statementPeriodEnd()};
}

/** What one dry run found: enough for {@code TrustPredicate.evaluate} and nothing else -- no
 *  session id, because nothing was staged. */
public record DryRunResult(List<ImportDto.VerificationReport> verificationReports,
                            List<LocalDate[]> statementPeriods) {}
```

Check the exact import already present for `LocalDate`/`DetectedAccountInfo`/`StagedAccountSection`
in `ImportService.java` — they are very likely already imported (the class already uses all three
in `parseAndStagePdfWithSession`); add only what's missing.

- [ ] **Step 4: Run the test, confirm it passes**

```bash
cd backend && ./mvnw test -Dtest=ImportServiceDryRunIT -q
```

- [ ] **Step 5: Mutation-test the zero-write guarantee**

Temporarily change the PDF branch to call `pdfPreviewGenerator.generateSectionsWithContext` via
`parseAndStagePdfWithSession` instead (i.e. deliberately route through the live path), rerun
`dryRunParseReturnsVerificationAndPeriodsWithoutWritingAnySession`, confirm it now fails (session
count changes), then revert.

- [ ] **Step 6: Self-review this task**

Check for bugs and gaps missed: confirm `onlySectionsThatAreActuallyAccounts` and
`toStagingResponse` are visible from this new method (they're private instance methods on the same
class — they are). Confirm the CSV branch's `ExtractionCheck.rejectIfNothingWasExtracted(staged,
...)` overload signature matches (`(StagingResponse, DocumentContext)` — verify against
`ExtractionCheck.java` directly, don't assume). Confirm no existing `dryRunParse` name collision.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/imports/ImportService.java \
        backend/src/test/java/com/finora/imports/ImportServiceDryRunIT.java
git commit -m "feat: add zero-write parser dry-run to ImportService"
```

---

### Task 3: `HeldStatementService.recordFindings` and `rerunParser`

**Files:**
- Create: `backend/src/main/java/com/finora/imports/jobs/ParserVersionProvider.java`
- Modify: `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java`
- Modify: `backend/src/main/java/com/finora/service/HeldStatementService.java`
- Create: `backend/src/main/java/com/finora/dto/HeldStatementRerunResultDto.java`
- Modify: `backend/src/main/java/com/finora/dto/HeldStatementDto.java`
- Test: Add to `backend/src/test/java/com/finora/repository/HeldStatementQueryIT.java` or a new
  `HeldStatementServiceRerunIT.java` — read the existing test file for `HeldStatementService` (the
  one covering `approve`/`reject`) first and add there if it's the natural home; only create a new
  file if that one has grown unwieldy.

**Interfaces:**
- Consumes: `ImportService.dryRunParse(...)` and `ImportService.DryRunResult` from Task 2;
  `HeldStatement.recordEngineerFindings(...)` and `HeldStatement`'s new `@Version` field from
  Task 1; `TrustPredicate.evaluate(List<ImportDto.VerificationReport>, List<LocalDate[]>,
  LocalDate)` and `HoldDecision(boolean hold, List<String> reasons)` (both already exist,
  unmodified); `HeldStatement.markReadyForImport(Instant)` (already exists, unused until now);
  `StatementContentService.read(StoredStatement)` (already used by `download`).
- Produces: `ParserVersionProvider.current() : String` — consumed by both `ImportJobWorker` and
  `HeldStatementService`. `HeldStatementService.recordFindings(UUID actingAdminId, String heldId,
  String rootCause, String fixReference) : HeldStatementDto`,
  `HeldStatementService.rerunParser(UUID actingAdminId, String heldId) :
  HeldStatementRerunResultDto` — both consumed by Task 4's controller.

- [ ] **Step 1: Extract `ParserVersionProvider`**

`ImportJobWorker` reads the running build's version through its own private `@Value` field
(`ImportJobWorker.java:147-148`), and `HeldStatement.parserVersion` was stamped from exactly that
value. A re-run needs to compare against the SAME source, not a second copy of the same
expression — see this plan's Decisions table for why duplicating it is a real drift risk, not a
style preference.

Create `backend/src/main/java/com/finora/imports/jobs/ParserVersionProvider.java`:

```java
package com.finora.imports.jobs;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one source of "what parser build is running right now."
 *
 * <p>{@code app.parser-version}, falling back to {@code RAILWAY_GIT_COMMIT_SHA} -- the full
 * commit SHA, not {@code BuildVersionResolver}'s short one. Deliberately a different source from
 * {@code BuildVersionResolver.currentCommit()}: that one prefers Spring Boot's {@code
 * GitProperties} (typically unavailable in this Docker build, per its own doc) and falls back to
 * {@code app.build.commit}, which is not wired to {@code RAILWAY_GIT_COMMIT_SHA} anywhere in this
 * codebase -- the two can disagree. {@code HeldStatement.parserVersion} is stamped from THIS
 * class, via {@link com.finora.imports.jobs.ImportJobWorker}, so anything comparing against it
 * (a parser re-run) has to read the same source back.
 *
 * <p>Extracted from {@code ImportJobWorker}'s own private field (Plan 3) specifically so a second
 * caller never has to duplicate the expression -- a duplicated {@code @Value} default is exactly
 * the kind of "the same fact stated twice, and the copies drift" bug this codebase has hit before
 * ({@code @PreAuthorize} class-level-vs-method-level, {@code readOnly} on a write-performing
 * method) in different shapes.
 */
@Component
public class ParserVersionProvider {

    @Value("${app.parser-version:${RAILWAY_GIT_COMMIT_SHA:}}")
    private String version;

    public String current() {
        return version;
    }
}
```

In `ImportJobWorker.java`, remove the private `@Value private String parserVersion;` field, inject
`ParserVersionProvider` through the constructor instead, and replace every existing use of the
field (`ImportJobWorker.java:317-318`, `:347`) with `parserVersionProvider.current()`. Run the
existing `ImportJobWorker` test suite to confirm this refactor changes nothing observable:

```bash
cd backend && ./mvnw test -Dtest=ImportJobWorker* -q
```

- [ ] **Step 2: Add the DTO**

`backend/src/main/java/com/finora/dto/HeldStatementRerunResultDto.java`:

```java
package com.finora.dto;

import java.util.List;

/**
 * What one parser re-run found: the two parser versions being compared, whether they differ, and
 * the fresh trust decision.
 *
 * @param previousParserVersion the build that produced the rows still staged for this hold --
 *                              {@code HeldStatement.parserVersion}, unchanged by this call.
 * @param currentParserVersion the build running right now, read from {@code
 *                             ParserVersionProvider} -- the same source {@code
 *                             HeldStatement.parserVersion} was originally stamped from via {@code
 *                             ImportJobWorker}. Null if neither {@code app.parser-version} nor
 *                             {@code RAILWAY_GIT_COMMIT_SHA} is set.
 * @param parserVersionChanged whether the two differ. False does not mean the re-run is pointless
 *                             -- a config or dependency change with no commit bump is possible,
 *                             if rare -- so the UI shows this as a hint, not a gate.
 * @param stillHeld whether {@code TrustPredicate} still flags this statement under the current
 *                  build.
 * @param reasons why it still holds, if it does -- empty when {@code stillHeld} is false.
 * @param summary the hold's current state after this call -- {@code READY_FOR_IMPORT} if this
 *                run cleared it, unchanged otherwise.
 */
public record HeldStatementRerunResultDto(
        String previousParserVersion,
        String currentParserVersion,
        boolean parserVersionChanged,
        boolean stillHeld,
        List<String> reasons,
        HeldStatementDto summary) {}
```

- [ ] **Step 3: Extend `HeldStatementDto`**

Add `rootCause` and `fixReference` to the record's field list (after `engineerNotes`) and to
`from(HeldStatement held)`:

```java
        String engineerNotes,
        String rootCause,
        String fixReference,
        Instant createdAt,
```

```java
                held.getEngineerNotes(),
                held.getRootCause(),
                held.getFixReference(),
                held.getCreatedAt(),
```

- [ ] **Step 4: Write the failing tests**

In the chosen test file (see Files above):

```java
@Test
void recordFindingsSavesBothFieldsAndWritesAnEvent() {
    HeldStatement held = seedHold(...); // match existing seeding helper in this file
    UUID adminId = UUID.randomUUID();

    HeldStatementDto result = heldStatementService.recordFindings(adminId, held.getHeldId(),
            "Two-line HSBC header confused the column locator", "PR #950");

    assertThat(result.rootCause()).isEqualTo("Two-line HSBC header confused the column locator");
    assertThat(result.fixReference()).isEqualTo("PR #950");
    List<HeldStatementEvent> events = eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId());
    assertThat(events).extracting(HeldStatementEvent::getEventType).contains("FINDINGS_UPDATED");
}

@Test
void rerunParserMarksReadyForImportWhenTheCurrentBuildNoLongerTriggersTheHold() throws Exception {
    // Seed a hold whose ORIGINAL trigger was a count mismatch, but whose statement bytes -- when
    // parsed fresh right now -- produce clean verification (use a real minimal CSV/PDF fixture
    // with no discrepancy, exactly like ImportServiceDryRunIT's fixture).
    HeldStatement held = seedHoldWithRealBytes(cleanCsvFixture());

    HeldStatementRerunResultDto result = heldStatementService.rerunParser(UUID.randomUUID(), held.getHeldId());

    assertThat(result.stillHeld()).isFalse();
    assertThat(result.reasons()).isEmpty();
    HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.READY_FOR_IMPORT);
}

@Test
void rerunParserLeavesTheHoldAloneWhenTheProblemStillReproduces() throws Exception {
    HeldStatement held = seedHoldWithRealBytes(stillMismatchedCsvFixture());

    HeldStatementRerunResultDto result = heldStatementService.rerunParser(UUID.randomUUID(), held.getHeldId());

    assertThat(result.stillHeld()).isTrue();
    assertThat(result.reasons()).isNotEmpty();
    HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.HELD);
}

@Test
void rerunParserWritesNoNewVerificationFindingRows() throws Exception {
    HeldStatement held = seedHoldWithRealBytes(cleanCsvFixture());
    long findingsBefore = importVerificationFindingRepository.count();

    heldStatementService.rerunParser(UUID.randomUUID(), held.getHeldId());

    assertThat(importVerificationFindingRepository.count()).isEqualTo(findingsBefore);
}

@Test
void rerunParserIsIdempotentOnAnAlreadyClearedHold() throws Exception {
    // An engineer re-running the same already-cleared hold a second time (nothing left to fix,
    // just double-checking) must not throw. HeldStatement.markReadyForImport's own guard
    // (refuseIfResolved) only blocks IMPORTED/REJECTED, so READY_FOR_IMPORT -> READY_FOR_IMPORT
    // is already legal at the entity level -- this test is what proves the SERVICE method doesn't
    // add a narrower guard on top that would break it.
    HeldStatement held = seedHoldWithRealBytes(cleanCsvFixture());
    heldStatementService.rerunParser(UUID.randomUUID(), held.getHeldId());

    HeldStatementRerunResultDto second = heldStatementService.rerunParser(UUID.randomUUID(), held.getHeldId());

    assertThat(second.stillHeld()).isFalse();
    HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.READY_FOR_IMPORT);
}

@Test
void rerunParserAnchorsPeriodIntegrityToTheOriginalHoldDateNotToday() throws Exception {
    // A fixture whose statement period end is one day after the hold's OWN createdAt -- a
    // genuine "period in the future relative to when this was uploaded" defect at hold time.
    // If rerunParser anchored TrustPredicate's `today` to LocalDate.now() instead of
    // held.getCreatedAt(), this test would only fail once real wall-clock time passed the
    // fixture's date -- exactly the silent, hard-to-catch drift this test exists to pin down
    // today, deterministically, regardless of when the suite actually runs.
    Instant heldAt = Instant.parse("2026-01-10T00:00:00Z");
    HeldStatement held = seedHoldWithRealBytesAndCreatedAt(periodEndsOneDayAfter(heldAt), heldAt);

    HeldStatementRerunResultDto result = heldStatementService.rerunParser(UUID.randomUUID(), held.getHeldId());

    assertThat(result.stillHeld()).isTrue();
    assertThat(result.reasons()).anyMatch(r -> r.contains("future"));
}

@Test
void rerunParserEventRecordsBothParserVersionsAndWhetherTheyChanged() throws Exception {
    HeldStatement held = seedHoldWithRealBytes(cleanCsvFixture()); // parserVersion snapshot: "old-build"

    heldStatementService.rerunParser(UUID.randomUUID(), held.getHeldId());

    List<HeldStatementEvent> events = eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId());
    HeldStatementEvent rerun = events.stream()
            .filter(e -> "PARSER_RERUN".equals(e.getEventType())).findFirst().orElseThrow();
    assertThat(rerun.getNotes()).contains("old-build");
    // The currently-running test build's version, from ParserVersionProvider -- assert it's
    // PRESENT in the note text, not a specific literal value the test environment doesn't control.
    assertThat(rerun.getNotes()).containsIgnoringCase("parser version");
}

@Test
void concurrentApproveAndRerunDoNotSilentlyOverwriteEachOther() throws Exception {
    // Two admins acting on the same hold near-simultaneously: one approves, one re-runs the
    // parser. Without HeldStatement.version, whichever transaction commits second would blindly
    // overwrite the first's status -- no exception, no log line. With it, the second commit must
    // throw ObjectOptimisticLockingFailureException (mapped to 409 by
    // GlobalExceptionHandler.handleOptimisticLock) and the hold's final state must be exactly
    // whichever action actually committed first, never a corrupted mix of both.
    HeldStatement held = seedHoldWithRealBytes(cleanCsvFixture());

    HeldStatement loadedForApprove = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
    HeldStatement loadedForRerun = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
    assertThat(loadedForApprove.getVersion()).isEqualTo(loadedForRerun.getVersion());

    heldStatementService.approve(UUID.randomUUID(), held.getHeldId(), null);

    assertThatThrownBy(() -> heldStatementService.rerunParser(UUID.randomUUID(), held.getHeldId()))
            .isInstanceOf(org.springframework.dao.OptimisticLockingFailureException.class)
            .isInstanceOf(IllegalStateException.class); // whichever refuseIfResolved throws now that approve won the race

    HeldStatement reloaded = heldStatementRepository.findByHeldId(held.getHeldId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.IMPORTED);
}
```

**Verified: this codebase has no existing `CountDownLatch`/`ExecutorService` concurrency-test
precedent to follow** (checked directly — no hits under `backend/src/test`). Do not invent one from
scratch for this single test. Write `concurrentApproveAndRerunDoNotSilentlyOverwriteEachOther` the
simpler way that still genuinely exercises `@Version`: load two separate `HeldStatement` instances
for the same row in two separate transactions (e.g. two `@Transactional(propagation =
Propagation.REQUIRES_NEW)` helper calls, or two calls to `heldStatementRepository.findByHeldId`
each inside its own `TransactionTemplate.execute`), mutate and save the first to completion, THEN
attempt to save the second (already-loaded, now-stale) instance and confirm THAT save throws
`OptimisticLockingFailureException` — this is a legitimate, deterministic way to prove the version
check fires on stale data, without needing genuine thread interleaving. If `HeldStatementService`'s
public methods make this awkward to drive directly (they each do their own `require(heldId)`
read-then-save internally), test one layer down against `heldStatementRepository` directly with two
separately-loaded, separately-mutated `HeldStatement` entities instead — the goal is proving the
`@Version` column behaves as expected under a stale write, not necessarily routing the test through
the full service call.

`seedHoldWithRealBytesAndCreatedAt(...)` needs to backdate `HeldStatement.createdAt`, which has no
public setter (`private Instant createdAt = Instant.now();`, field-initializer only). Use
`org.springframework.test.util.ReflectionTestUtils.setField(held, "createdAt", heldAt)` after
persisting the seeded hold — there is no existing precedent for this exact pattern in this
codebase either (checked directly), so this is new, not a match to something already established.

`seedHoldWithRealBytes(...)` needs to actually store real, downloadable bytes for the job — follow
`AdminHeldStatementDownloadIT.seedHold`'s exact pattern (`@TestPropertySource` with
`app.statement-storage.provider=filesystem`, `StatementStorage.store(bytes)` for a real
`ContentAddress`, per [[import-service-confirm-test-only-overload]]-adjacent BH-045 —
`ImportJob.getFileContent()` always returns null). Read that file before writing this one; do not
re-derive the pattern from scratch.

- [ ] **Step 5: Run, confirm compile failure** (`recordFindings`/`rerunParser` don't exist yet)

- [ ] **Step 6: Add `ImportService` and `ParserVersionProvider` as constructor dependencies**

`HeldStatementService`'s constructor grows two parameters. Check first whether any test constructs
`HeldStatementService` directly with `new HeldStatementService(...)` rather than via Spring
autowiring — if so, update every such call site.

```java
private final ImportService importService;
private final ParserVersionProvider parserVersionProvider;

public HeldStatementService(HeldStatementRepository repository,
                            HeldStatementEventRepository eventRepository,
                            HeldStatementIdGenerator idGenerator,
                            ImportJobRepository importJobRepository,
                            ImportVerificationFindingRepository findingRepository,
                            AuditService auditService,
                            NotificationService notificationService,
                            ImportSessionService importSessionService,
                            ObjectMapper objectMapper,
                            StatementContentService statementContentService,
                            ImportService importService,
                            ParserVersionProvider parserVersionProvider) {
    // ... existing assignments ...
    this.importService = importService;
    this.parserVersionProvider = parserVersionProvider;
}
```

- [ ] **Step 7: Implement `recordFindings`**

Add near `addNotes`:

```java
/** Records what an engineer found and where the fix landed. Same replace-wholesale semantics as
 *  {@link #addNotes}, and deliberately not guarded by {@code refuseIfResolved} for the identical
 *  reason. */
@Transactional
public HeldStatementDto recordFindings(UUID actingAdminId, String heldId, String rootCause,
                                       String fixReference) {
    HeldStatement held = require(heldId);
    held.recordEngineerFindings(rootCause, fixReference);
    repository.save(held);

    eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, "FINDINGS_UPDATED",
            null, null, rootCause));
    auditService.record(actingAdminId, "TRUST_REVIEW_FINDINGS_UPDATED", "HeldStatement", held.getId(),
            Map.of("actorId", actingAdminId.toString(),
                    "subjectUserId", held.getUserId().toString(),
                    "heldId", held.getHeldId()));
    return HeldStatementDto.from(held);
}
```

- [ ] **Step 8: Implement `rerunParser`**

Add near `approve`/`reject`. Note three deliberate departures from the most obvious first draft,
each closing a specific gap identified reviewing this plan before implementation:

1. `today` is anchored to `held.getCreatedAt()`, not `Instant.now()` — see Global Constraints.
2. The `ApiException` catch preserves the real `ErrorCode` in the reported reason, rather than
   asserting a specific narrative ("extracts nothing") that may not match what actually failed —
   `dryRunParse`'s call chain can throw `IMPORT_NO_ACTIVITY_IN_PERIOD`, `IMPORT_NO_HEADER_DETECTED`,
   `IMPORT_NO_TRANSACTIONS_FOUND`, or `IMPORT_SCANNED_OCR_REQUIRED` (confirmed by reading
   `PreviewGenerator`/`PdfPreviewGenerator`/`ExtractionCheck` directly) — all four are genuine
   extraction failures, but a future addition to that call chain might not be, and this reads the
   code back rather than guessing.
3. The event's `notes` carries both parser versions and the changed flag in the text itself —
   `HeldStatementEvent` has no structured payload column (unlike `AuditLog.metadata`), so this is
   the only place in the visible timeline this information can live at all.

```java
private static final String PARSER_RERUN_EVENT = "PARSER_RERUN";

/**
 * Re-parses this hold's original bytes with the CURRENT parser build and reports whether the
 * trust predicate would still flag it.
 *
 * <p>Reads through {@link ImportService#dryRunParse} exclusively -- see that method's own doc,
 * and this plan's Global Constraints, for why the live staging path is unsafe here: it would
 * delete the {@code ImportSession} a later {@link #approve} still needs.
 *
 * <p>{@code today} is {@code held.getCreatedAt()}'s date, not the date this method runs on --
 * see this plan's Global Constraints for why using the current date would let a genuinely
 * future-dated statement period stop being flagged for no reason but calendar drift, which would
 * misreport a rerun as having fixed something no parser change touched.
 *
 * <p>Writes exactly one thing beyond the hold's own status: a {@code PARSER_RERUN} event. It
 * never calls {@code ImportVerificationRecorder.recordForJob} -- see this plan's Global
 * Constraints for why a second write against the same {@code importJobId} would be ambiguous
 * beside the original hold's evidence in {@link #detail}.
 *
 * <p>Clearing moves the hold to {@code READY_FOR_IMPORT}, never straight to {@code IMPORTED} --
 * see this plan's own Decisions table. A human still approves. Calling this again on an
 * already-{@code READY_FOR_IMPORT} hold is legal and idempotent -- {@code
 * HeldStatement.markReadyForImport}'s only guard is {@code refuseIfResolved}, which does not
 * single out a required starting status.
 *
 * <p>Protected against a concurrent {@link #approve}/{@link #reject}/etc. on the same hold by
 * {@code HeldStatement}'s {@code @Version} column (Task 1): a losing concurrent write throws
 * {@code ObjectOptimisticLockingFailureException}, mapped to a 409 by {@code
 * GlobalExceptionHandler.handleOptimisticLock} -- never a silent overwrite of whichever admin
 * action committed first.
 */
@Transactional
public HeldStatementRerunResultDto rerunParser(UUID actingAdminId, String heldId) {
    HeldStatement held = require(heldId);
    refuseIfResolved(held, "re-parsed");
    ImportJob job = requireJob(held);

    byte[] content = statementContentService.read(job);
    ImportService.DryRunResult dryRun;
    String extractionError = null;
    try {
        dryRun = importService.dryRunParse(job.getUserId(), job.getFileName(), content, job.getSourceFormat());
    } catch (ApiException e) {
        dryRun = new ImportService.DryRunResult(List.of(), List.of());
        String code = e.getCode() != null ? e.getCode().name() : "UNKNOWN";
        extractionError = code + ": " + e.getMessage();
    } catch (java.io.IOException e) {
        throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Could not re-read this statement: " + e.getMessage());
    }

    LocalDate anchoredToday = held.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate();
    HoldDecision decision = extractionError != null
            ? new HoldDecision(true, List.of("Current parser build fails to extract this document ("
                    + extractionError + ")"))
            : TrustPredicate.evaluate(dryRun.verificationReports(), dryRun.statementPeriods(), anchoredToday);

    String previousVersion = held.getParserVersion();
    String currentVersion = parserVersionProvider.current();
    boolean versionChanged = currentVersion != null && !currentVersion.equals(previousVersion);
    HeldStatement.Status from = held.getStatus();
    if (!decision.hold()) {
        held.markReadyForImport(Instant.now());
        repository.save(held);
    }

    String summaryNote = (decision.hold()
            ? "Still held: " + String.join("; ", decision.reasons())
            : "Clears under the current parser build.")
            + " Parser version: " + previousVersion + " -> " + currentVersion
            + " (" + (versionChanged ? "changed" : "unchanged") + ").";
    eventRepository.save(new HeldStatementEvent(held.getId(), actingAdminId, PARSER_RERUN_EVENT,
            from.name(), held.getStatus().name(), summaryNote));
    auditService.record(actingAdminId, "TRUST_REVIEW_PARSER_RERUN", "HeldStatement", held.getId(),
            Map.of("actorId", actingAdminId.toString(),
                    "subjectUserId", held.getUserId().toString(),
                    "heldId", held.getHeldId(),
                    "stillHeld", decision.hold(),
                    "previousParserVersion", String.valueOf(previousVersion),
                    "currentParserVersion", String.valueOf(currentVersion),
                    "parserVersionChanged", versionChanged));

    return new HeldStatementRerunResultDto(previousVersion, currentVersion, versionChanged,
            decision.hold(), decision.reasons(), HeldStatementDto.from(held));
}
```

Confirm `refuseIfResolved` throws the same 409-shaped exception the existing private helper already
does (read it, don't re-derive) — a re-run on an already-resolved hold should refuse the same way
`approve`/`reject` do. Confirm `ApiException.getCode()` is the actual accessor name (read
`ApiException.java` — do not assume `code()`/`getCode()` without checking, since this plan's
research read the field as `private final ErrorCode code;` but the accessor's exact name needs
confirming against the source, not this plan's paraphrase of it).

- [ ] **Step 9: Run all tests, confirm they pass**

```bash
cd backend && ./mvnw test -Dtest=<the chosen test class> -q
```

- [ ] **Step 10: Mutation-test `rerunParser`'s READY_FOR_IMPORT wiring**

Temporarily make the clearing branch unconditional (always call `markReadyForImport`, regardless of
`decision.hold()`), confirm `rerunParserLeavesTheHoldAloneWhenTheProblemStillReproduces` now fails,
then revert. This is the same discipline [[preauthorize-method-overrides-class-level]] and
[[readonly-transactional-silently-drops-writes]] were caught with in Plan 2 — mutation-test the
specific test meant to catch this, don't just watch the suite stay green.

- [ ] **Step 11: Mutation-test the `today`-anchoring fix**

Temporarily change `anchoredToday` back to `LocalDate.now(ZoneOffset.UTC)`, confirm
`rerunParserAnchorsPeriodIntegrityToTheOriginalHoldDateNotToday` now fails (it will, once real
wall-clock time has passed the fixture's future-dated period — if the suite runs on the same day
the fixture's date was chosen relative to, pick a fixture date far enough in the past that this is
unambiguous), then revert.

- [ ] **Step 12: Self-review this task**

Check for bugs and gaps missed: confirm `rerunParser` is plain `@Transactional`, not `readOnly`
(it writes an event and an audit entry — the exact bug class documented in
[[readonly-transactional-silently-drops-writes]]). Confirm the `ApiException` catch block's
`e.getCode()`/equivalent call compiles against the real `ApiException` API. Confirm
`held.getParserVersion()` is read into `previousVersion` *before* any mutation this method makes
(it never gets mutated here, but verify by reading, not by assuming). Confirm the concurrency test
from Step 4 actually exercises `@Version` and isn't secretly passing for an unrelated reason (e.g.
`refuseIfResolved` alone would also make the second call fail, but with `IllegalStateException`,
not `OptimisticLockingFailureException` — the test's `isInstanceOf` assertions need to distinguish
these, and if `approve` reliably wins the race in practice, the test may need to assert on
`IllegalStateException` rather than the optimistic-lock exception; verify empirically which one the
test actually throws rather than assuming the plan's guess is correct, and fix the test to match
reality).

- [ ] **Step 13: Commit**

```bash
git add backend/src/main/java/com/finora/imports/jobs/ParserVersionProvider.java \
        backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java \
        backend/src/main/java/com/finora/service/HeldStatementService.java \
        backend/src/main/java/com/finora/dto/HeldStatementRerunResultDto.java \
        backend/src/main/java/com/finora/dto/HeldStatementDto.java \
        <the chosen test file>
git commit -m "feat: re-run the current parser build against a held statement"
```

---

### Task 4: Controller endpoints

**Files:**
- Modify: `backend/src/main/java/com/finora/controller/AdminHeldStatementController.java`
- Test: `backend/src/test/java/com/finora/controller/AdminHeldStatementControllerIT.java` (or the
  file Plan 2 actually used for controller-level tests of this controller — check first)

**Interfaces:**
- Consumes: `HeldStatementService.recordFindings`, `HeldStatementService.rerunParser` from Task 3.

- [ ] **Step 1: Write the failing tests**

```java
@Test
void rerunParserReturnsTheComparisonAndIsGatedOnTrustReviewManage() throws Exception {
    HeldStatement held = seedHold(...);

    mockMvc.perform(post("/api/v1/admin/held-statements/{heldId}/rerun-parser", held.getHeldId())
                    .with(user(adminWithTrustReviewManage())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.stillHeld").exists());

    mockMvc.perform(post("/api/v1/admin/held-statements/{heldId}/rerun-parser", held.getHeldId())
                    .with(user(adminWithoutTrustReviewManage())))
            .andExpect(status().isForbidden());
}

@Test
void findingsEndpointSavesRootCauseAndFixReference() throws Exception {
    HeldStatement held = seedHold(...);

    mockMvc.perform(post("/api/v1/admin/held-statements/{heldId}/findings", held.getHeldId())
                    .with(user(adminWithTrustReviewManage()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"rootCause\":\"Header misdetected\",\"fixReference\":\"PR #950\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.rootCause").value("Header misdetected"))
            .andExpect(jsonPath("$.data.fixReference").value("PR #950"));
}
```

Match `seedHold`/`adminWithTrustReviewManage`/`adminWithoutTrustReviewManage` to whatever helpers
the existing controller IT file already defines for Plan 2's endpoints — read it first.

- [ ] **Step 2: Run, confirm they fail** (404/no such endpoint)

- [ ] **Step 3: Add the endpoints**

```java
/** Re-parses this hold's original bytes with the parser build running right now and reports
 *  whether it would still be flagged. Writes nothing to the staged rows -- only an event, and
 *  (when it now clears) the status transition to READY_FOR_IMPORT. */
@PostMapping("/{heldId}/rerun-parser")
public ApiResponse<HeldStatementRerunResultDto> rerunParser(@PathVariable String heldId) {
    return ApiResponse.ok(heldStatementService.rerunParser(currentUser.id(), heldId));
}

/** Records what an engineer found and where the fix landed. Replaces both fields wholesale. */
@PostMapping("/{heldId}/findings")
public ApiResponse<HeldStatementDto> findings(@PathVariable String heldId,
                                              @RequestBody(required = false) Map<String, String> body) {
    String rootCause = body == null ? null : body.get("rootCause");
    String fixReference = body == null ? null : body.get("fixReference");
    return ApiResponse.ok(
            heldStatementService.recordFindings(currentUser.id(), heldId, rootCause, fixReference),
            "Findings saved");
}
```

Both inherit the class-level `@PreAuthorize("hasAuthority('TRUST_REVIEW_MANAGE')")` — neither
touches the raw document, so neither needs the `/document` endpoint's extra role pin. Do not add a
method-level `@PreAuthorize` to either; per [[preauthorize-method-overrides-class-level]], doing so
would *replace* the class gate, not add to it, and there is nothing stricter these two need.

Add the `HeldStatementRerunResultDto` import.

- [ ] **Step 4: Run the tests, confirm they pass**

- [ ] **Step 5: Self-review this task**

Check for bugs and gaps missed: confirm neither new endpoint accidentally carries a method-level
`@PreAuthorize` (the exact bug Plan 2's `/document` endpoint doc warns about). Confirm the
`findings` endpoint's null-body handling matches the existing `notes`/`assign` endpoints' pattern
exactly (same `body == null ? null : body.get(...)` idiom, not a different one that would behave
differently on a missing body).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/controller/AdminHeldStatementController.java \
        backend/src/test/java/com/finora/controller/AdminHeldStatementControllerIT.java
git commit -m "feat: expose parser re-run and findings endpoints"
```

---

### Task 5: Admin-portal — types and API client

**Files:**
- Modify: `admin-portal/src/types/index.ts`
- Modify: `admin-portal/src/api/endpoints.ts`

**Interfaces:**
- Produces: `HeldStatementRerunResult` type, `adminHeldStatementApi.rerunParser(heldId)`,
  `adminHeldStatementApi.saveFindings(heldId, rootCause, fixReference)` — consumed by Task 6.

- [ ] **Step 1: Extend `HeldStatementRow` and add `HeldStatementRerunResult`**

In `types/index.ts`, add to `HeldStatementRow` (matching the backend `HeldStatementDto` field
order added in Task 3):

```ts
  rootCause: string | null;
  fixReference: string | null;
```

Add a new type near the other `HeldStatement*` types:

```ts
export interface HeldStatementRerunResult {
  previousParserVersion: string | null;
  currentParserVersion: string | null;
  parserVersionChanged: boolean;
  stillHeld: boolean;
  reasons: string[];
  summary: HeldStatementRow;
}
```

- [ ] **Step 2: Extend `adminHeldStatementApi`**

In `api/endpoints.ts`, add alongside the existing methods:

```ts
  rerunParser: (heldId: string) =>
    api.post<HeldStatementRerunResult>(`/admin/held-statements/${heldId}/rerun-parser`).then((r) => r.data),
  saveFindings: (heldId: string, rootCause?: string, fixReference?: string) =>
    api.post<HeldStatementRow>(`/admin/held-statements/${heldId}/findings`, { rootCause, fixReference })
      .then((r) => r.data),
```

Add the `HeldStatementRerunResult` import from `../types`.

- [ ] **Step 3: Type-check**

```bash
cd admin-portal && npx tsc -b
```

(No `typecheck` npm script exists in this package — use `npx tsc -b` directly, per
[[plan-2-operator-portal-shipped]].)

- [ ] **Step 4: Commit**

```bash
git add admin-portal/src/types/index.ts admin-portal/src/api/endpoints.ts
git commit -m "feat: add parser re-run and findings to the admin-portal API client"
```

---

### Task 6: Admin-portal — detail page UI

**Files:**
- Modify: `admin-portal/src/pages/HeldStatementDetail.tsx`
- Modify: `admin-portal/src/pages/HeldStatementDetail.test.tsx`

**Interfaces:**
- Consumes: `adminHeldStatementApi.rerunParser`, `adminHeldStatementApi.saveFindings` from Task 5.

- [ ] **Step 1: Write the failing tests**

Add to `HeldStatementDetail.test.tsx`, following the file's existing `useMutation`/`render`/
`screen` pattern exactly (read the file first — it already tests `saveNotes` the same shape this
needs):

```tsx
it('runs a parser re-run and shows the comparison', async () => {
  vi.mocked(adminHeldStatementApi.rerunParser).mockResolvedValue({
    previousParserVersion: 'abc1234',
    currentParserVersion: 'def5678',
    parserVersionChanged: true,
    stillHeld: false,
    reasons: [],
    summary: { ...baseSummary, status: 'READY_FOR_IMPORT' },
  });
  renderDetail();

  fireEvent.click(await screen.findByRole('button', { name: /re-run parser/i }));

  expect(await screen.findByText(/clears under the current parser build/i)).toBeInTheDocument();
});

it('shows the still-held reasons when a re-run does not clear it', async () => {
  vi.mocked(adminHeldStatementApi.rerunParser).mockResolvedValue({
    previousParserVersion: 'abc1234',
    currentParserVersion: 'abc1234',
    parserVersionChanged: false,
    stillHeld: true,
    reasons: ['Printed and parsed transaction count disagree (DIRECTION)'],
    summary: baseSummary,
  });
  renderDetail();

  fireEvent.click(await screen.findByRole('button', { name: /re-run parser/i }));

  expect(await screen.findByText(/transaction count disagree/i)).toBeInTheDocument();
});

it('saves root cause and fix reference', async () => {
  vi.mocked(adminHeldStatementApi.saveFindings).mockResolvedValue({ ...baseSummary,
      rootCause: 'Header misdetected', fixReference: 'PR #950' });
  renderDetail();

  fireEvent.change(screen.getByLabelText(/root cause/i), { target: { value: 'Header misdetected' } });
  fireEvent.change(screen.getByLabelText(/fix reference/i), { target: { value: 'PR #950' } });
  fireEvent.click(screen.getByRole('button', { name: /save findings/i }));

  await waitFor(() => expect(adminHeldStatementApi.saveFindings)
      .toHaveBeenCalledWith(heldId, 'Header misdetected', 'PR #950'));
});

it('clears a previous rerun result when navigating to a different held statement', async () => {
  vi.mocked(adminHeldStatementApi.rerunParser).mockResolvedValue({
    previousParserVersion: 'abc1234', currentParserVersion: 'abc1234', parserVersionChanged: false,
    stillHeld: false, reasons: [], summary: baseSummary,
  });
  const { rerender } = renderDetail(heldId);
  fireEvent.click(await screen.findByRole('button', { name: /re-run parser/i }));
  await screen.findByText(/clears under the current parser build/i);

  rerender(<HeldStatementDetail /* whatever prop/route this file uses to select the held id */ heldId={otherHeldId} />);

  expect(screen.queryByText(/clears under the current parser build/i)).not.toBeInTheDocument();
});
```

Match `baseSummary`/`renderDetail`/`heldId`/`otherHeldId` and the exact re-render mechanism (prop
vs. route param — read how the file's other tests already switch between two different held
statements, if any do; if none do, use the router/`MemoryRouter` navigation the rest of the
admin-portal's page tests already use for this) to whatever the file and its surrounding test
utilities actually provide.

- [ ] **Step 2: Run, confirm they fail** (no such button/label yet)

- [ ] **Step 3: Add the state and mutations**

Near the existing `notesDraft`/`saveNotes` state:

```tsx
const [rootCauseDraft, setRootCauseDraft] = useState('');
const [fixReferenceDraft, setFixReferenceDraft] = useState('');
const [rerunResult, setRerunResult] = useState<HeldStatementRerunResult | null>(null);
```

In the existing pre-fill `useEffect` (the one that seeds `notesDraft` from `detail.data`), add:

```tsx
if (detail.data) {
  setRootCauseDraft(detail.data.summary.rootCause ?? '');
  setFixReferenceDraft(detail.data.summary.fixReference ?? '');
}
```

Near the existing `saveNotes` mutation:

```tsx
const saveFindings = useMutation({
  mutationFn: () => adminHeldStatementApi.saveFindings(heldId, rootCauseDraft || undefined,
      fixReferenceDraft || undefined),
  onSuccess: () => queryClient.invalidateQueries({ queryKey: ['held-statement', heldId] }),
});

const rerunParser = useMutation({
  mutationFn: () => adminHeldStatementApi.rerunParser(heldId),
  onSuccess: (result) => {
    setRerunResult(result);
    queryClient.invalidateQueries({ queryKey: ['held-statement', heldId] });
  },
});
```

(Match the query key to whatever the existing `approve`/`reject` mutations' `onSuccess` already
invalidate — read it, don't guess a different key.)

Add an explicit reset for `rerunResult` when the page navigates to a different hold — without this,
a re-run result from the PREVIOUSLY viewed held statement stays on screen and reads as if it
belongs to the newly-loaded one, which is actively misleading (a "clears under the current build"
message that has nothing to do with the row now showing). Add near the file's existing
`heldId`-keyed effects:

```tsx
useEffect(() => {
  setRerunResult(null);
}, [heldId]);
```

- [ ] **Step 4: Add the UI**

Near the existing Notes section, add a Findings section:

```tsx
<section className="stack-3">
  <label className="text-xs text-muted" htmlFor="held-statement-root-cause">Root cause</label>
  <textarea
    id="held-statement-root-cause"
    value={rootCauseDraft}
    onChange={(e) => setRootCauseDraft(e.target.value)}
    disabled={resolved}
  />
  <label className="text-xs text-muted" htmlFor="held-statement-fix-reference">Fix reference</label>
  <input
    id="held-statement-fix-reference"
    value={fixReferenceDraft}
    onChange={(e) => setFixReferenceDraft(e.target.value)}
    disabled={resolved}
  />
  <button onClick={() => saveFindings.mutate()} disabled={saveFindings.isPending}>
    {saveFindings.isPending ? 'Saving…' : 'Save findings'}
  </button>
</section>

<section className="stack-3">
  <button onClick={() => rerunParser.mutate()} disabled={rerunParser.isPending || resolved}>
    {rerunParser.isPending ? 'Re-running…' : 'Re-run Parser'}
  </button>
  {rerunResult && (
    <p>
      {rerunResult.stillHeld
        ? `Still held: ${rerunResult.reasons.join('; ')}`
        : 'Clears under the current parser build.'}
      {rerunResult.parserVersionChanged && (
        <span className="text-muted"> ({rerunResult.previousParserVersion ?? 'unknown'} →{' '}
          {rerunResult.currentParserVersion ?? 'unknown'})</span>
      )}
    </p>
  )}
</section>
```

Match class names (`stack-3`, `text-muted`, etc.) to whatever the surrounding sections in this file
actually use — read the file's existing markup before writing this, don't invent new class names.
Disable the Re-run Parser button under the same `busy || resolved` condition the approve/reject
buttons already use, extended to include `rerunParser.isPending`.

Add a `READY_FOR_IMPORT` case to wherever the page renders a status label/badge, if it renders one
per-status today (check — Plan 2's status handling may already fall through generically via
`.replace(/_/g, ' ')`, in which case nothing else is needed).

- [ ] **Step 5: Run the tests, confirm they pass**

```bash
cd admin-portal && npx vitest run HeldStatementDetail.test.tsx
```

- [ ] **Step 6: Self-review this task**

Check for bugs and gaps missed: confirm the Re-run Parser button is reachable and not accidentally
gated behind `canDownload` (it shouldn't be — re-running the parser is not the same permission
boundary as downloading the raw document). Confirm the `useEffect` reset added in Step 3 actually
fires before the new `detail` query for the next `heldId` resolves — not after — otherwise the
stale result could flash briefly or, worse, get reset AFTER a fresh one loads and wipe out a
legitimate result for the new row instead of the stale one for the old row.

- [ ] **Step 7: Commit**

```bash
git add admin-portal/src/pages/HeldStatementDetail.tsx admin-portal/src/pages/HeldStatementDetail.test.tsx
git commit -m "feat: add parser re-run and findings UI to the held statement detail page"
```

---

### Task 7: Full verification

- [ ] **Step 1:** `cd backend && ./mvnw clean verify` — run alone, never concurrently with another
      Maven invocation against the same `target/`.
- [ ] **Step 2:** `cd admin-portal && npx vitest run && npx tsc -b && npx eslint . --max-warnings 0`
- [ ] **Step 3:** Re-run the corpus calibration and confirm the distribution is unchanged — this
      plan adds a new caller of `TrustPredicate.evaluate`, not a change to the predicate itself, so
      the held-on-import rate for the real corpus must still read `0 of 27`.
- [ ] **Step 4: Cross-task review.** Before committing anything further, re-read Tasks 1-6 together,
      not one at a time — [[cross-task-review-catches-what-per-task-review-misses]] found 3 real
      bugs this way on Plan 2 that no per-task review caught. Specifically check: (a) does
      `HeldStatementRerunResultDto`'s doc comment about `currentParserVersion`'s source still match
      what Task 3 actually implemented; (b) does the frontend's `HeldStatementRerunResult` type
      still match the backend DTO's field names and order exactly; (c) does any doc comment written
      in an earlier task assert something about a later task's code that turned out different once
      written.
- [ ] **Step 5:** Commit any fixes from Step 4, then open the PR (or follow whatever branch/PR
      granularity the user has directed for this plan at execution time — this plan does not
      presume Plan 2's one-branch-one-PR choice applies here unless re-confirmed).

---

## Self-Review

**Spec coverage:** Brief Phase 6's remaining piece (notes taxonomy) → Task 1 (`root_cause`,
`fix_reference`). Phase 7 (parser re-run) → Tasks 2-4 (dry-run capability, service method,
endpoint) and Tasks 5-6 (UI). `READY_FOR_IMPORT`, defined in Plan 1 and never reached by any code
path through Plan 2, gets its first producer in Task 3.

**Placeholder scan:** Every step above either shows the actual code or names the exact existing
file/pattern to read and match (e.g. Task 3 Step 4's test-fixture pattern points at
`AdminHeldStatementDownloadIT.seedHold` by name rather than inventing a new one). No task says
"add appropriate tests" without showing the test. Task 3's concurrency and anchored-date tests are
the two places this plan is honest about NOT having an existing in-repo pattern to point at
(checked directly — neither exists yet) rather than fabricating one that isn't really there.

**Type consistency:** `HeldStatementRerunResultDto`'s field names (`previousParserVersion`,
`currentParserVersion`, `parserVersionChanged`, `stillHeld`, `reasons`, `summary`) are used
identically in Task 3 (Java), Task 5 (`HeldStatementRerunResult` TS type) and Task 6 (the
component). `ImportService.DryRunResult`'s two fields (`verificationReports`, `statementPeriods`)
match the exact parameter names `TrustPredicate.evaluate` already takes, so Task 3's call site
needs no renaming or reshaping between the two. `ParserVersionProvider.current()` is the single
source both `ImportJobWorker` (refactored in Task 3 Step 1) and `HeldStatementService` read —
introduced specifically so `currentParserVersion` can never silently diverge from what
`HeldStatement.parserVersion` was originally stamped from.

**Reviewed and revised before implementation:** an external review of this plan's first draft
raised seven points; six changed the plan (parser-version-source duplication → `ParserVersionProvider`;
overly-broad `ApiException` handling → preserves the real `ErrorCode`; a too-thin `PARSER_RERUN`
event → both versions and the changed flag now in its `notes`; unverified `READY_FOR_IMPORT`
idempotency → confirmed true by reading `markReadyForImport`'s actual guard, and pinned down with a
regression test; frontend stale-state risk → an explicit `useEffect` reset, not just a review note;
no concurrency coverage → `@Version` plus a dedicated IT). The seventh — `LocalDate.now(UTC)`
inside `TrustPredicate.evaluate` causing rerun outcomes to drift with calendar time — was
independently the most consequential: verified directly against `TrustPredicate.periodIntegrity`'s
actual comparison, confirmed real, and fixed by anchoring to `held.getCreatedAt()`.

**Known risks this plan does not close:**
- `HeldStatementService.assign`'s `engineerId` parameter still accepts any user id with no
  permission check (confirmed by reading `AdminHeldStatementController.assign` and
  `HeldStatementService.assign` directly — no FK-to-role check exists, only the DB's `ON DELETE SET
  NULL` foreign key to `users(id)`). This predates this plan and stays open; introducing real
  engineer-identity validation is a separate, larger decision this plan's scope does not require.
- A password-protected PDF cannot be re-run — `dryRunParse` passes `null` and the resulting
  extraction failure is reported as a still-held reason, not a distinct error state. An engineer
  hitting this has to fall back to reviewing without a re-run, same as before this plan existed.
- The re-run reasons a `TrustPredicate` produces are already-existing sentences (see
  `TrustPredicate.evaluate`'s own dedup logic) — this plan does not add any new predicate condition,
  and does not change what can trigger a hold in the first place. That is explicitly out of scope:
  this plan is entirely about re-testing an *existing* hold, never about changing what causes one.
- Adding `@Version` to `HeldStatement` means every EXISTING mutating method on it
  (`assign`/`startInvestigation`/`addNotes`/`markImported`/`reject`) can now throw
  `ObjectOptimisticLockingFailureException` under concurrent writes where it silently couldn't
  before. `GlobalExceptionHandler` already maps that to a clean 409 globally, so no NEW failure
  mode reaches a client uncaught — but Task 1 should still run Plan 2's existing controller ITs for
  those methods after adding the column, to confirm none of them relies on a specific exception
  type this change doesn't affect, rather than assuming compatibility.

**Next:** Plan 4 (Metrics & False-Positive Tracking, Brief Phase 10) is unwritten and unstarted.
