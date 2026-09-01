# Phase B: Import Held-For-Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** When a bank statement import fails for a reason the parser doesn't recognize, hold it in a distinct state, show the user an honest "we're running additional checks" message instead of a dead-end error, surface it in an admin triage queue, and auto-reprocess it (no reupload) with a push + email notification once the underlying parser bug is fixed.

**Architecture:** A new `ImportJob.Status.HELD_FOR_REVIEW` terminal state, entered only when the existing `ExceptionClassifier` classified the failure as `RETRY_ONCE_THEN_ALERT` (genuinely unclassified — not a known `FAIL_FAST` `ErrorCode`, not an exhausted transient-infra retry) *and* the job dead-lettered. An admin queue mirroring the existing `AdminLearningQueueController`/`LearningQueue.tsx` pattern lets an admin inspect and reprocess. Reprocess reuses the raw statement file already retained in object storage for exactly this purpose. On success, `NotificationService.request(...)` (Phase A) fires push + email.

**Tech Stack:** Java 25, Spring Boot 3.5.16, Jakarta Persistence (no Lombok), PostgreSQL + Flyway, JUnit 5 + AssertJ + Mockito (plain `mock(Class.class)`), React + React Query + TypeScript (admin portal).

**Spec:** `docs/superpowers/specs/2026-09-02-import-failure-triage-and-notification-platform-design.md` (§4 Phase B)

---

## Hard Prerequisite: Phase A

**Task 6 of this plan calls `NotificationService.request(...)`, which does not exist until Phase A ships.**

Before starting Task 6, confirm Phase A's completion checklist is green — specifically that `NotificationService.request(...)` is callable and that `NotificationType.IMPORT_STATEMENT_READY` has active `notification_templates` rows for both `EMAIL` and `PUSH`. See `docs/superpowers/plans/2026-09-02-phase-a-notification-platform.md`.

Tasks 1–5 have **no** Phase A dependency and can proceed in parallel with it. Only Task 6 blocks.

---

## Global Constraints

- **Only unclassified failures are held.** A failure enters `HELD_FOR_REVIEW` if and only if `ExceptionClassifier.classify(cause)` returned `RetryPolicy.RETRY_ONCE_THEN_ALERT` **and** `ImportJob.recordFailure(...)` returned `FailureOutcome.DEAD_LETTERED`. Known `ErrorCode` failures (`FAIL_FAST` — wrong password, unsupported file type, no header detected) and exhausted transient-infra retries (`RETRY`) continue to land in plain `FAILED` exactly as today, with their existing specific messages untouched. This scoping is the project owner's explicit decision: "if the error is straightforward then no need for admin intervention, but if it bank statement failed error like due to header mismatch or pattern then it should go to admin."
- **User-facing copy — locked intent, wording is a final product call:**
  > "We need to run some additional checks on this statement before we can complete the import. We'll notify you once it's ready — no action needed from you right now."
  - **No time commitment.** No "within an hour", no SLA, no ETA. Triage is manual and volume-dependent; a promised deadline would break as volume grows.
  - **No genuineness or fraud framing.** Never imply the user's document is suspect or under verification for authenticity. The real cause is a parser gap on our side. This is a deliberate reversal of the feature's original framing, decided in brainstorming: in a financial app, telling a user their statement is being checked for legitimacy is a trust risk that lands worse than an honest delay.
  - The message must remain **true**: additional checks genuinely are run. Do not write copy that describes work that isn't happening.
- **Every admin view of a held statement's content is audited.** `AuditService.record(...)` with `SCREAMING_SNAKE_CASE` past-tense action names, following the `PasswordChangeService` / `UserAccountLifecycleService` call shape exactly.
- **Flyway migration versions must never be hardcoded from this plan.** Concurrent sessions have caused duplicate-version collisions three times, each breaking `main`'s boot. Immediately before writing *each* migration:
  ```bash
  git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5
  ```
  `V122` was the latest observed at plan-writing time — a snapshot, **not a reservation**.
- **Async path only.** This feature lives entirely on the `ImportJob`/`ImportJobWorker` path (`app.import.queue.enabled`, confirmed enabled in production). The synchronous `ImportSessionService`/`StatementImport` path persists no job row with a storage address on failure, so there is nothing there to hold or reprocess. Do not attempt to extend this to the sync path.
- **Codebase conventions:** `jakarta.persistence.*`, no Lombok, `@Enumerated(EnumType.STRING)` + `VARCHAR` columns (never native Postgres enums), `idx_<table>_<purpose>` index naming, liberal `COMMENT ON`. Tests use `mock(Class.class)` in `@BeforeEach` (no `@Mock`/`@InjectMocks`), AssertJ assertions, `ReflectionTestUtils.setField` for no-setter fields. Integration tests extend `AbstractIntegrationTest` and carry `@Isolated`.
- **Build/test commands:**
  ```bash
  cd backend && ./mvnw test -Dtest=ClassName
  ```
- **Non-code follow-up, not in this plan:** the privacy policy needs a line disclosing that failed imports may be manually reviewed by staff (and AI tooling) to resolve the issue. This is an owner/legal task. Flag it at PR time; do not silently ship without raising it.

---

## File Structure

Mostly modifications to existing files — this feature adds one state and one admin surface to a pipeline that already exists.

- `backend/src/main/java/com/finora/entity/ImportJob.java` — add the `HELD_FOR_REVIEW` status value and a `holdForReview(...)` transition.
- `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java` — route dead-lettered `RETRY_ONCE_THEN_ALERT` failures into the hold.
- `backend/src/main/java/com/finora/imports/jobs/UserFacingImportStatus.java` — surface the held state to the user.
- `backend/src/main/java/com/finora/service/StatementStorageSweepService.java` — protect held jobs' stored files from garbage collection.
- `backend/src/main/java/com/finora/controller/AdminHeldImportController.java` *(new)* — the triage queue API.
- `backend/src/main/java/com/finora/service/AdminHeldImportService.java` *(new)* — queue logic, audit, reprocess.
- `admin-portal/src/pages/HeldImports.tsx` *(new)* — the queue UI.

---

## Task 1: Add the HELD_FOR_REVIEW state

**Files:**
- Modify: `backend/src/main/java/com/finora/entity/ImportJob.java`
- Create: `backend/src/main/resources/db/migration/V<next>__import_job_held_for_review.sql`
- Test: `backend/src/test/java/com/finora/entity/ImportJobTest.java` (extend; create if absent)

**Interfaces:**
- Consumes: existing `ImportJob.Status`, `ImportJob.FailureOutcome`, `ImportJob.recordFailure(...)`.
- Produces: `ImportJob.Status.HELD_FOR_REVIEW`; `ImportJob.holdForReview(String reason, Instant now)`; `ImportJob.returnToQueueForReprocess(Instant now)`. Tasks 2, 3, 4, 5 all consume these.

- [ ] **Step 1: Read the existing status model before changing it**

```bash
sed -n '55,120p' backend/src/main/java/com/finora/entity/ImportJob.java
sed -n '290,400p' backend/src/main/java/com/finora/entity/ImportJob.java
grep -n "TERMINAL\|IN_FLIGHT" backend/src/main/java/com/finora/entity/ImportJob.java
```

Critical detail: the `Status` enum is declared **in progression order and is ordinal-comparable**, with `IN_FLIGHT` and `TERMINAL` sets derived from it. Adding a value in the wrong position can silently break an ordinal comparison. `HELD_FOR_REVIEW` belongs **adjacent to `FAILED`**, in the terminal group — it behaves terminally for the worker (no further automatic attempt) while remaining distinguishable from `FAILED` for the sweep and reprocess logic. Read how `TERMINAL` is used at every call site before deciding whether `HELD_FOR_REVIEW` joins it:

```bash
grep -rn "Status.TERMINAL\|isTerminal" backend/src/main/java --include=*.java
```

- [ ] **Step 2: Write the failing test**

Extend `backend/src/test/java/com/finora/entity/ImportJobTest.java` (create it following `NotificationTest`'s plain-entity style if it does not exist):

```java
    @Test
    void holdForReview_isTerminalForTheWorkerButDistinctFromFailed() {
        ImportJob job = newQueuedJob();
        Instant now = Instant.parse("2026-09-02T10:00:00Z");

        job.holdForReview("HeaderDetectionException", now);

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
        assertThat(job.getStatus()).isNotEqualTo(ImportJob.Status.FAILED);
        assertThat(ImportJob.Status.TERMINAL).contains(ImportJob.Status.HELD_FOR_REVIEW);
    }

    @Test
    void holdForReview_recordsTheUnrecognizedFailureCode() {
        ImportJob job = newQueuedJob();

        job.holdForReview("HeaderDetectionException", Instant.parse("2026-09-02T10:00:00Z"));

        assertThat(job.getFailureCode()).isEqualTo("HeaderDetectionException");
    }

    @Test
    void returnToQueueForReprocess_clearsAttemptsSoAFixedParserGetsAFullBudget() {
        ImportJob job = newQueuedJob();
        job.holdForReview("HeaderDetectionException", Instant.parse("2026-09-02T10:00:00Z"));

        job.returnToQueueForReprocess(Instant.parse("2026-09-03T09:00:00Z"));

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        assertThat(job.getAttemptCount()).isZero();
    }

    @Test
    void returnToQueueForReprocess_isRejectedForAJobThatIsNotHeld() {
        ImportJob job = newQueuedJob();

        assertThatThrownBy(() ->
                job.returnToQueueForReprocess(Instant.parse("2026-09-03T09:00:00Z")))
                .isInstanceOf(IllegalStateException.class);
    }
```

Write `newQueuedJob()` to match however `ImportJob` is actually constructed — read its factory/constructor in Step 1 rather than assuming.

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=ImportJobTest
```

Expected: FAIL — `HELD_FOR_REVIEW`, `holdForReview`, `returnToQueueForReprocess` do not exist.

- [ ] **Step 4: Add the status value and transitions**

In `ImportJob.java`, add `HELD_FOR_REVIEW` to the `Status` enum adjacent to `FAILED`, include it in `TERMINAL`, and add:

```java
    /**
     * Holds a job whose failure nothing recognized, instead of dead-lettering it to FAILED.
     *
     * <p>Entered only for a RETRY_ONCE_THEN_ALERT classification that already exhausted its
     * attempts -- that is, a genuinely unclassified exception, which in practice means a parser
     * gap on a statement layout this codebase has not seen. A known ErrorCode failure (wrong
     * password, unsupported file) still goes to FAILED with its own specific message; there is
     * nothing for an admin to troubleshoot there.
     *
     * <p>The stored statement object is retained for a held job exactly as it is for a FAILED one,
     * which is what makes reprocess-without-reupload possible once the parser is fixed.
     */
    public void holdForReview(String failureCode, Instant now) {
        this.status = Status.HELD_FOR_REVIEW;
        this.failureCode = failureCode;
        this.finishedAt = now;
    }

    /**
     * Returns a held job to the queue after its underlying parser bug was fixed.
     *
     * <p>Resets the attempt count: the previous attempts were spent against a parser that could
     * not have succeeded, so charging them against the fixed one would be wrong.
     */
    public void returnToQueueForReprocess(Instant now) {
        if (status != Status.HELD_FOR_REVIEW) {
            throw new IllegalStateException(
                    "Only a HELD_FOR_REVIEW job can be reprocessed; this one is " + status);
        }
        this.status = Status.QUEUED;
        this.attemptCount = 0;
        this.nextAttemptAt = now;
        this.finishedAt = null;
    }
```

Match the actual field names found in Step 1 (`finishedAt`, `nextAttemptAt`, `attemptCount` may differ) — adapt rather than forcing these.

- [ ] **Step 5: Pick the migration version, then write the migration**

```bash
git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5
```

Check first whether `import_jobs.status` carries a `CHECK` constraint — `V66__import_jobs.sql` may or may not have one:

```bash
grep -n "CHECK" backend/src/main/resources/db/migration/V66__import_jobs.sql
```

If it does, the migration must drop and recreate it with the new value. If it does not, the migration is comment-only but still worth writing so the schema documents the new state:

```sql
-- Adds HELD_FOR_REVIEW to import_jobs.status: a dead-lettered job whose failure nothing
-- recognized, held for admin triage instead of being shown to the user as a bare failure.

-- If a CHECK constraint on status exists, drop and recreate it here including the new value.
-- (Verify against V66__import_jobs.sql before assuming either way.)

COMMENT ON COLUMN import_jobs.status IS
    'QUEUED/PARSING/ANALYZING/DEDUPING/IMPORTING/LEARNING/COMPLETED/FAILED/CANCELLED/'
    'HELD_FOR_REVIEW. HELD_FOR_REVIEW is terminal for the worker but distinct from FAILED: it '
    'means the failure was unclassified (a likely parser gap), the statement object is retained, '
    'and an admin can reprocess it once the parser is fixed.';
```

- [ ] **Step 6: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=ImportJobTest
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/entity/ImportJob.java backend/src/main/resources/db/migration backend/src/test/java/com/finora/entity/ImportJobTest.java
git commit -m "feat(imports): add HELD_FOR_REVIEW state for unclassified import failures"
```

---

## Task 2: Route unclassified dead-letters into the hold

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java` (`recordFailure`, around lines 322–367)
- Test: `backend/src/test/java/com/finora/imports/jobs/ImportJobWorkerTest.java` (extend)

**Interfaces:**
- Consumes: `ImportJob.holdForReview(...)` from Task 1; existing `ExceptionClassifier.classify(Throwable)` returning `ErrorCode.RetryPolicy`; existing `ImportJob.recordFailure(...)` returning `FailureOutcome`.
- Produces: the routing behavior every later task depends on — after this, held jobs actually exist.

- [ ] **Step 1: Read the current failure path**

```bash
sed -n '310,400p' backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java
sed -n '40,70p' backend/src/main/java/com/finora/imports/jobs/ExceptionClassifier.java
```

Today: `classify(cause)` → `ErrorCode.failureCodeOf(cause)` → `job.recordFailure(describe(cause), failureCode, policy, now)` → `severityFor(policy)` maps `RETRY_ONCE_THEN_ALERT` to `AlertSeverity.ERROR`. The alert stays — this task adds a hold alongside it, it does not replace the alerting.

- [ ] **Step 2: Write the failing test**

Extend `ImportJobWorkerTest`:

```java
    @Test
    void unclassifiedFailure_thatDeadLetters_isHeldForReviewRatherThanFailed() {
        // An exception no ErrorCode recognizes -- classified RETRY_ONCE_THEN_ALERT, which is the
        // "likely a genuine Finora bug" bucket.
        when(exceptionClassifier.classify(any()))
                .thenReturn(ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT);
        ImportJob job = jobWithAttemptsRemaining(1);

        worker.recordFailure(job, new IllegalStateException("no header row found"));

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
    }

    @Test
    void knownErrorCodeFailure_stillGoesToFailed() {
        when(exceptionClassifier.classify(any())).thenReturn(ErrorCode.RetryPolicy.FAIL_FAST);
        ImportJob job = jobWithAttemptsRemaining(1);

        worker.recordFailure(job, new ApiException(ErrorCode.IMPORT_PDF_PASSWORD_REQUIRED));

        // A user can act on this one themselves; there is nothing for an admin to troubleshoot.
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
    }

    @Test
    void exhaustedTransientRetries_stillGoToFailed() {
        when(exceptionClassifier.classify(any())).thenReturn(ErrorCode.RetryPolicy.RETRY);
        ImportJob job = jobWithAttemptsRemaining(1);

        worker.recordFailure(job, new DataAccessResourceFailureException("db down"));

        // Infrastructure trouble is not a parser gap -- holding it would put noise in the queue.
        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
    }

    @Test
    void unclassifiedFailure_withAttemptsRemaining_stillRetriesBeforeBeingHeld() {
        when(exceptionClassifier.classify(any()))
                .thenReturn(ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT);
        ImportJob job = jobWithAttemptsRemaining(2);

        worker.recordFailure(job, new IllegalStateException("transient-looking parser crash"));

        assertThat(job.getStatus()).isNotEqualTo(ImportJob.Status.HELD_FOR_REVIEW);
    }

    @Test
    void heldJob_stillEmitsTheDeadLetterAlert() {
        when(exceptionClassifier.classify(any()))
                .thenReturn(ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT);
        ImportJob job = jobWithAttemptsRemaining(1);

        worker.recordFailure(job, new IllegalStateException("no header row found"));

        // Holding for triage does not replace engineering alerting -- it adds to it.
        verify(workerExecution).deadLettered(any(), anyInt(), any());
    }
```

Adapt `jobWithAttemptsRemaining(int)` and the `recordFailure` invocation to the test class's existing fixtures and to `recordFailure`'s real visibility (it may be private — if so, drive it through the public path the existing tests already use rather than widening visibility for the test).

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=ImportJobWorkerTest
```

Expected: FAIL — held jobs currently land in `FAILED`.

- [ ] **Step 4: Implement the routing**

In `ImportJobWorker.recordFailure`, after `job.recordFailure(...)` returns its `FailureOutcome`, add the hold before persisting:

```java
        // A dead-lettered unclassified failure is the one case that is plausibly a genuine parser
        // gap rather than a user error or an infrastructure blip. Hold it for triage instead of
        // showing the user a bare FAILED they can do nothing about. The alert below still fires --
        // this adds a destination, it does not replace engineering visibility.
        if (outcome == ImportJob.FailureOutcome.DEAD_LETTERED
                && policy == ErrorCode.RetryPolicy.RETRY_ONCE_THEN_ALERT) {
            job.holdForReview(failureCode, Instant.now());
        }
```

Leave `severityFor(policy)` and the `deadLettered(...)` observability call exactly as they are.

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=ImportJobWorkerTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java backend/src/test/java/com/finora/imports/jobs/ImportJobWorkerTest.java
git commit -m "feat(imports): hold dead-lettered unclassified failures for admin review"
```

---

## Task 3: Protect held jobs' stored files from the sweep

**Files:**
- Modify: `backend/src/main/java/com/finora/service/StatementStorageSweepService.java` (`IMPORT_JOB_EXCLUDED_STATUSES`, around lines 126–127)
- Test: `backend/src/test/java/com/finora/service/StatementStorageSweepServiceTest.java` (extend; find the real name first)

**Interfaces:**
- Consumes: `ImportJob.Status.HELD_FOR_REVIEW` from Task 1.
- Produces: the guarantee Task 5's reprocess depends on — a held job's statement file still exists when the fix lands.

**Why this matters:** the sweep already excludes `FAILED` jobs' objects specifically so "retry without re-upload" stays possible — the service's own class doc records this as the response to a real incident. A held job can sit far longer than a failed one (it waits on a human fixing a parser), so omitting it here would let the sweep delete the very file the feature exists to reprocess.

- [ ] **Step 1: Read the sweep's exclusion logic and its doc**

```bash
sed -n '30,70p' backend/src/main/java/com/finora/service/StatementStorageSweepService.java
sed -n '110,140p' backend/src/main/java/com/finora/service/StatementStorageSweepService.java
ls backend/src/test/java/com/finora/service/ | grep -i sweep
```

- [ ] **Step 2: Write the failing test**

Extend the sweep's existing test class:

```java
    @Test
    void heldForReviewJobs_objectsAreRetainedLikeFailedOnes() {
        // A held job is waiting on a human fixing a parser -- it can sit far longer than a failed
        // one, and its object is exactly what reprocess-without-reupload needs intact.
        assertThat(StatementStorageSweepService.IMPORT_JOB_EXCLUDED_STATUSES)
                .contains(ImportJob.Status.HELD_FOR_REVIEW);
    }
```

Then add a behavioral test in the same class's existing style, asserting that a sweep run leaves a `HELD_FOR_REVIEW` job's object in place — copy the shape of whatever test already proves this for `FAILED`.

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=StatementStorageSweepServiceTest
```

Expected: FAIL.

- [ ] **Step 4: Add the status to the exclusion set**

```java
    /**
     * Statuses whose stored objects the sweep must not collect.
     *
     * <p>FAILED and HELD_FOR_REVIEW both keep their objects so a retry needs no re-upload. HELD_FOR_
     * REVIEW especially: it is waiting on a human fixing a parser, so it can sit far longer than a
     * failed job, and its object is the entire input to the reprocess this feature exists to
     * perform. COMPLETED and CANCELLED stay collectable -- neither has a resume path.
     */
    static final Set<ImportJob.Status> IMPORT_JOB_EXCLUDED_STATUSES =
            EnumSet.of(ImportJob.Status.COMPLETED, ImportJob.Status.CANCELLED);
```

**Read the existing set carefully before editing** — the research found it defined as the statuses *excluded from retention* (`{COMPLETED, CANCELLED}` are the ones whose objects **are** collectable). Confirm the polarity by reading the code, then add `HELD_FOR_REVIEW` on whichever side means "retained". Getting this backwards would delete exactly the files this feature needs.

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=StatementStorageSweepServiceTest
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/service/StatementStorageSweepService.java backend/src/test/java/com/finora/service
git commit -m "fix(imports): retain stored statements for held-for-review jobs"
```

---

## Task 4: Show the user an honest holding message

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/jobs/UserFacingImportStatus.java` (lines ~21–43)
- Modify: the mobile app's import status handling (find it: `grep -rn "ACTION_REQUIRED" mobile/src --include=*.ts --include=*.tsx`)
- Test: `backend/src/test/java/com/finora/imports/jobs/UserFacingImportStatusTest.java` (extend; create if absent)

**Interfaces:**
- Consumes: `ImportJob.Status.HELD_FOR_REVIEW` from Task 1.
- Produces: `UserFacingImportStatus.HELD_FOR_REVIEW`, consumed by the mobile app.

**Copy (locked intent — see Global Constraints):**
> "We need to run some additional checks on this statement before we can complete the import. We'll notify you once it's ready — no action needed from you right now."

No ETA. No genuineness/fraud framing. Must stay true.

- [ ] **Step 1: Read the current mapping**

```bash
cat backend/src/main/java/com/finora/imports/jobs/UserFacingImportStatus.java
```

Note the live gap this fixes: an unclassified failure's `failureCode` is a raw exception class name, which `ErrorCode.userActionRequiredOrDefault` cannot resolve, so it defaults to `false` and the user currently sees a plain `FAILED` — not even `ACTION_REQUIRED`.

- [ ] **Step 2: Write the failing test**

```java
    @Test
    void heldJobs_mapToTheirOwnUserFacingStatus() {
        assertThat(UserFacingImportStatus.from(ImportJob.Status.HELD_FOR_REVIEW))
                .isEqualTo(UserFacingImportStatus.HELD_FOR_REVIEW);
    }

    @Test
    void heldStatus_isNotPresentedAsAFailure() {
        // The whole point: the user is told work is in progress, not handed a dead end.
        assertThat(UserFacingImportStatus.from(ImportJob.Status.HELD_FOR_REVIEW))
                .isNotIn(UserFacingImportStatus.FAILED, UserFacingImportStatus.ACTION_REQUIRED);
    }

    @Test
    void heldStatus_requiresNoUserAction() {
        assertThat(UserFacingImportStatus.HELD_FOR_REVIEW.requiresUserAction()).isFalse();
    }

    @Test
    void plainFailuresAreUnchanged() {
        assertThat(UserFacingImportStatus.from(ImportJob.Status.FAILED))
                .isEqualTo(UserFacingImportStatus.FAILED);
        assertThat(UserFacingImportStatus.from(ImportJob.Status.COMPLETED))
                .isEqualTo(UserFacingImportStatus.COMPLETED);
    }
```

Adapt `from(...)` / `requiresUserAction()` to the class's real API, read in Step 1.

- [ ] **Step 3: Run test to verify it fails, then implement**

```bash
cd backend && ./mvnw test -Dtest=UserFacingImportStatusTest
```

Add the `HELD_FOR_REVIEW` value with a doc comment recording *why* the copy avoids an ETA and a genuineness framing, so a future editor does not "improve" it back:

```java
    /**
     * The statement needs work on our side before it can be imported. Deliberately NOT presented
     * as a failure and NOT given an ETA: triage is manual, so any promised deadline would break as
     * volume grows. Equally deliberately, the copy never suggests the document's authenticity is in
     * question -- the real cause is a parser gap here, and implying otherwise in a financial app is
     * a trust risk that lands worse than an honest delay.
     */
    HELD_FOR_REVIEW,
```

- [ ] **Step 4: Update the mobile app**

Find where import statuses are rendered and add the held case with the locked copy. Verify the string is not truncated in the UI at common device widths.

- [ ] **Step 5: Verify in the running app**

Run the mobile app, force a job into `HELD_FOR_REVIEW` (a DB update against the dev database is sufficient), and confirm the message renders as intended and the row is not styled as an error. Do not report this task complete on a passing backend test alone — this is a user-facing surface.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/imports/jobs/UserFacingImportStatus.java backend/src/test/java/com/finora/imports/jobs mobile/src
git commit -m "feat(imports): show an honest holding message for held-for-review statements"
```

---

## Task 5: Admin held-imports triage queue

**Files:**
- Create: `backend/src/main/java/com/finora/service/AdminHeldImportService.java`
- Create: `backend/src/main/java/com/finora/controller/AdminHeldImportController.java`
- Create: `backend/src/main/resources/db/migration/V<next>__import_triage_admin_permission.sql`
- Create: `admin-portal/src/pages/HeldImports.tsx`
- Modify: the admin-portal route table (`grep -rn "LearningQueue" admin-portal/src --include=*.tsx`)
- Test: `backend/src/test/java/com/finora/service/AdminHeldImportServiceTest.java`

**Interfaces:**
- Consumes: `ImportJob.returnToQueueForReprocess(...)` from Task 1; existing `ImportJobRepository`, `ImportJobWorker`, `AuditService`.
- Produces: `AdminHeldImportService.list(...)`, `.summary()`, `.detail(UUID)`, `.reprocess(UUID adminUserId, UUID jobId)`, `.resolve(UUID adminUserId, UUID jobId, String reason)`.

**Mirror `AdminLearningQueueController`/`AdminLearningQueueService`/`LearningQueue.tsx` closely** — this is a deliberate consistency choice, not incidental.

- [ ] **Step 1: Read the reference implementation in full**

```bash
cat backend/src/main/java/com/finora/controller/AdminLearningQueueController.java
cat backend/src/main/java/com/finora/service/AdminLearningQueueService.java
sed -n '1,80p' admin-portal/src/pages/LearningQueue.tsx
cat backend/src/main/resources/db/migration/V63__learning_queue_admin.sql
```

Note especially: the `afterCommit` worker nudge (never nudge before the retry commits), the bounded `MAX_RETRY_ALL` cap on bulk actions, and `LearningQueue.tsx`'s principle of never re-deriving server-owned state-machine rules client-side.

- [ ] **Step 2: Pick the migration version, then seed the permission**

```bash
git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5
```

Create `backend/src/main/resources/db/migration/V<next>__import_triage_admin_permission.sql`:

```sql
INSERT INTO permissions (name, description) VALUES
    ('IMPORT_TRIAGE_MANAGE',
     'View statements held for review after an unclassified import failure, inspect their '
     'diagnostic detail, and reprocess or resolve them. Grants access to real user statement '
     'content, so it is deliberately its own permission rather than folded into a broader import '
     'or diagnostics permission.');

-- ADMIN and SUPER_ADMIN, matching every permission added since V24. SUPER_ADMIN needs its own
-- explicit grant -- its V16 "every permission" catch-all was a one-time snapshot, not a standing
-- rule, so a new permission is not picked up by it automatically.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'IMPORT_TRIAGE_MANAGE';
```

**Both inserts are mandatory** — a permission with no `role_permissions` row grants nothing.

- [ ] **Step 3: Write the failing service test**

Create `backend/src/test/java/com/finora/service/AdminHeldImportServiceTest.java`:

```java
package com.finora.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finora.entity.ImportJob;
import com.finora.imports.jobs.ImportJobWorker;
import com.finora.repository.ImportJobRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AdminHeldImportServiceTest {

    private ImportJobRepository repository;
    private ImportJobWorker worker;
    private AuditService auditService;
    private AdminHeldImportService service;

    private final UUID adminUserId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(ImportJobRepository.class);
        worker = mock(ImportJobWorker.class);
        auditService = mock(AuditService.class);
        service = new AdminHeldImportService(repository, worker, auditService);
        when(repository.save(any(ImportJob.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private ImportJob heldJob() {
        ImportJob job = newTestImportJob();
        job.holdForReview("HeaderDetectionException", java.time.Instant.now());
        return job;
    }

    @Test
    void detail_auditsEveryViewOfAHeldStatement() {
        ImportJob job = heldJob();
        when(repository.findById(any())).thenReturn(Optional.of(job));

        service.detail(adminUserId, job.getId());

        // Viewing a held statement means looking at a real user's financial document. Every such
        // view is recorded.
        verify(auditService).record(eq(adminUserId), eq("HELD_IMPORT_VIEWED"), eq("ImportJob"),
                eq(job.getId()), any(Map.class));
    }

    @Test
    void reprocess_returnsTheJobToTheQueueAndAudits() {
        ImportJob job = heldJob();
        when(repository.findById(any())).thenReturn(Optional.of(job));

        service.reprocess(adminUserId, job.getId());

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.QUEUED);
        verify(auditService).record(eq(adminUserId), eq("HELD_IMPORT_REPROCESSED"),
                eq("ImportJob"), eq(job.getId()), any(Map.class));
    }

    @Test
    void reprocess_isRejectedForAJobThatIsNotHeld() {
        ImportJob job = newTestImportJob();
        when(repository.findById(any())).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> service.reprocess(adminUserId, job.getId()))
                .isInstanceOf(IllegalStateException.class);
        verify(worker, never()).nudge();
    }

    @Test
    void resolve_movesTheJobToPlainFailedAndRecordsTheReasonInTheAudit() {
        ImportJob job = heldJob();
        when(repository.findById(any())).thenReturn(Optional.of(job));

        service.resolve(adminUserId, job.getId(), "bank does not publish a parseable format");

        assertThat(job.getStatus()).isEqualTo(ImportJob.Status.FAILED);
        verify(auditService).record(eq(adminUserId), eq("HELD_IMPORT_RESOLVED"), eq("ImportJob"),
                eq(job.getId()), any(Map.class));
    }
}
```

Write `newTestImportJob()` against `ImportJob`'s real construction path. **Do not** widen entity visibility to make the test easier — use `ReflectionTestUtils.setField` for no-setter fields, matching this codebase's convention.

- [ ] **Step 4: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=AdminHeldImportServiceTest
```

Expected: FAIL — `AdminHeldImportService` does not exist.

- [ ] **Step 5: Implement the service**

`AdminHeldImportService`, `@Transactional`, mirroring `AdminLearningQueueService`:

- `list(Pageable)` / `summary()` — read-only, paged, counts for filter chips.
- `detail(UUID adminUserId, UUID jobId)` — returns the job plus its diagnostic detail (`failureCode`, and whatever `AdminImportTraceController`/`AdminImportRowTraceController` already expose for a job — reuse, do not duplicate). **Audits `HELD_IMPORT_VIEWED` on every call.**
- `reprocess(UUID adminUserId, UUID jobId)` — `job.returnToQueueForReprocess(now)`, save, audit `HELD_IMPORT_REPROCESSED`, then nudge the worker **only after commit**, via the `TransactionSynchronizationManager`/`afterCommit` pattern `AdminLearningQueueService` already uses. Safe to call speculatively: if the parser bug is not actually fixed, the job simply lands back in `HELD_FOR_REVIEW`.
- `reprocessAll()` — bounded by an explicit cap constant, same shape as `MAX_RETRY_ALL`. **Log what was dropped when the cap truncates the batch** — a silent cap reads as "reprocessed everything" when it didn't.
- `resolve(UUID adminUserId, UUID jobId, String reason)` — gives up without a fix; moves to plain `FAILED` (where the job would have landed today), records the reason **on the audit entry, not the entity**, matching the learning queue's `resolve` exactly.

- [ ] **Step 6: Implement the controller**

`AdminHeldImportController`, `@RequestMapping("/api/v1/admin/held-imports")`, class-level `@PreAuthorize("hasAuthority('IMPORT_TRIAGE_MANAGE')")`, with `GET /`, `GET /summary`, `GET /{jobId}`, `POST /{jobId}/reprocess`, `POST /reprocess-all`, `POST /{jobId}/resolve`. Return 409 when an action targets a job that is not `HELD_FOR_REVIEW`, matching the learning queue's 409-on-wrong-state behavior.

- [ ] **Step 7: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=AdminHeldImportServiceTest
```

Expected: PASS (4 tests).

- [ ] **Step 8: Build the admin page**

`admin-portal/src/pages/HeldImports.tsx`, copying `LearningQueue.tsx`: `AdminLayout` + `RequirePermission` + `DataTable`/`Pagination`, React Query with `useQuery`/`useMutation`, status filter chips, reprocess/reprocess-all/resolve mutations invalidating both the list and summary queries on success. Show bank/user names rather than raw UUIDs, and never re-derive "is this reprocessable" client-side — trust the server's field.

- [ ] **Step 9: Verify in the browser**

Start the admin portal, seed a `HELD_FOR_REVIEW` job, and confirm: the queue lists it, detail shows the failure code, reprocess moves it out of the queue, and a wrong-state action surfaces the 409 rather than failing silently. UI is not verified by a passing test suite.

- [ ] **Step 10: Commit**

```bash
git add backend/src/main/java/com/finora backend/src/main/resources/db/migration backend/src/test/java/com/finora admin-portal/src
git commit -m "feat(imports): add admin held-imports triage queue"
```

---

## Task 6: Notify the user when a held statement imports successfully

> **BLOCKED ON PHASE A.** Do not start until `NotificationService.request(...)` exists and `NotificationType.IMPORT_STATEMENT_READY` has active `notification_templates` rows for `EMAIL` and `PUSH`. See this plan's Hard Prerequisite section.

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java` (the success path)
- Test: `backend/src/test/java/com/finora/imports/jobs/ImportJobWorkerTest.java` (extend)

**Interfaces:**
- Consumes: `NotificationService.request(NotificationRequest)` and `NotificationRequest.of(...)` from Phase A Task 2; `NotificationType.IMPORT_STATEMENT_READY` from Phase A Task 1.
- Produces: the user-visible completion of the feature loop.

**Design constraint:** only a job that was **previously held** notifies. A normal import that succeeds first time must not send anything — it never told the user to wait, so there is nothing to follow up on. This requires knowing the job was held; `ImportJob` needs a `wasHeldForReview` marker (a boolean column set by `holdForReview(...)` and never cleared by `returnToQueueForReprocess(...)`), because the status itself is gone by the time the job completes.

- [ ] **Step 1: Add the "was held" marker**

Extend Task 1's work: add a `was_held_for_review BOOLEAN NOT NULL DEFAULT FALSE` column (fresh migration version — `git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5`), set it to `true` in `ImportJob.holdForReview(...)`, and **never** clear it in `returnToQueueForReprocess(...)`. Add an entity test asserting it survives a reprocess round-trip.

- [ ] **Step 2: Write the failing test**

```java
    @Test
    void aPreviouslyHeldJob_thatCompletes_notifiesTheUserOnPushAndEmail() {
        ImportJob job = newTestImportJob();
        job.holdForReview("HeaderDetectionException", Instant.now());
        job.returnToQueueForReprocess(Instant.now());

        worker.markCompleted(job);

        ArgumentCaptor<NotificationRequest> captor =
                ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(captor.capture());
        assertThat(captor.getValue().type())
                .isEqualTo(NotificationType.IMPORT_STATEMENT_READY);
        assertThat(captor.getValue().channels())
                .containsExactlyInAnyOrder(NotificationChannel.PUSH, NotificationChannel.EMAIL);
        assertThat(captor.getValue().category()).isEqualTo(NotificationCategory.FINANCIAL);
    }

    @Test
    void anOrdinaryImport_thatSucceedsFirstTime_notifiesNobody() {
        ImportJob job = newTestImportJob();

        worker.markCompleted(job);

        // We never told this user to wait, so there is nothing to follow up on.
        verify(notificationService, never()).request(any());
    }

    @Test
    void theNotificationKeyIsDerivedFromTheJobSoARedeliveryCannotDoubleSend() {
        ImportJob job = newTestImportJob();
        job.holdForReview("HeaderDetectionException", Instant.now());
        job.returnToQueueForReprocess(Instant.now());

        worker.markCompleted(job);

        ArgumentCaptor<NotificationRequest> captor =
                ArgumentCaptor.forClass(NotificationRequest.class);
        verify(notificationService).request(captor.capture());
        assertThat(captor.getValue().notificationKey()).contains(job.getId().toString());
    }
```

Adapt `markCompleted` to the worker's real success-path method name, found by reading the class.

- [ ] **Step 3: Run test to verify it fails**

```bash
cd backend && ./mvnw test -Dtest=ImportJobWorkerTest
```

Expected: FAIL.

- [ ] **Step 4: Implement the notification call**

In the worker's success path, inside the same transaction that marks the job completed (that transaction is what makes the outbox write durable):

```java
        if (job.wasHeldForReview()) {
            // This user was told we were running additional checks. Close that loop.
            // A first-time success notifies nobody -- we never asked them to wait.
            notificationService.request(NotificationRequest.of(
                    job.getUserId(),
                    NotificationType.IMPORT_STATEMENT_READY,
                    NotificationCategory.FINANCIAL,
                    NotificationPriority.NORMAL,
                    "IMPORT_READY_" + job.getId(),
                    Set.of(NotificationChannel.PUSH, NotificationChannel.EMAIL),
                    Map.of("bank", bankNameFor(job))));
        }
```

`NotificationPriority.NORMAL`, not `CRITICAL` — `CRITICAL` is reserved for security events per the frozen notification proposal. Resolve the `bank` parameter from whatever the job already knows; if no bank name is available, pass a neutral value rather than letting `{{bank}}` render literally.

- [ ] **Step 5: Run test to verify it passes**

```bash
cd backend && ./mvnw test -Dtest=ImportJobWorkerTest
```

Expected: PASS.

- [ ] **Step 6: End-to-end verification — do not skip**

Per the seam-verification standard this project already applies: verify the identifier flows producer → storage → API → consumer, not just that each layer's unit tests pass.

1. Force a real import failure that classifies as `RETRY_ONCE_THEN_ALERT` (a malformed statement the parser cannot header-detect).
2. Confirm the job lands in `HELD_FOR_REVIEW` and the user-facing status shows the holding message in the mobile app.
3. Confirm it appears in the admin Held Imports queue.
4. Reprocess it from the admin portal.
5. Confirm a `notifications` row is written with the expected `notification_key`, and that `NotificationDispatcher` delivers it.
6. Confirm the user receives both the push and the email.
7. Confirm the `audit_logs` rows exist for the admin view and the reprocess.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora backend/src/main/resources/db/migration backend/src/test/java/com/finora
git commit -m "feat(imports): notify users by push and email when a held statement imports"
```

---

## Phase B completion checklist

- [ ] `cd backend && ./mvnw test` passes in full. Any failure unrelated to this diff is **surfaced, not unilaterally fixed**.
- [ ] End-to-end verification (Task 6 Step 6) completed and its results reported concretely — not "tests pass".
- [ ] Every new migration got a freshly-checked version number; `git fetch origin && ls backend/src/main/resources/db/migration | sort -V | tail -5` shows no duplicates.
- [ ] Self-review the full diff for bugs and gaps before opening the PR — standing project rule, not optional.
- [ ] **Privacy policy disclosure raised with the owner.** This feature makes real users' bank statements visible to admin review; the policy needs a line saying so. Do not close this out silently.
- [ ] Held statements that get fixed are worth adding to the ground-truth corpus — the parser fix workflow is the same one the existing corpus uses. Mention any new real-world layouts discovered during triage.

---

## Self-Review Notes

Checked against the spec:

- **Spec coverage:** §4.1 (new state) → Task 1; §4.1 routing → Task 2; §4.2 (user message) → Task 4; §4.3 (admin queue) → Task 5; §4.4 (audit) → Task 5; §4.5 (notification) → Task 6; §5 (data model, sweep exclusion) → Tasks 1 and 3; §4.6 (privacy disclosure) → completion checklist, flagged as an owner task rather than a code step.
- **Dependency direction is explicit:** Tasks 1–5 are Phase-A-independent; Task 6 is gated at the top of the plan and again at the task itself.
- **Deliberate scope exclusions restated in Global Constraints:** the synchronous import path, known-`ErrorCode` failures, and exhausted transient-infra retries all stay exactly as they are today.
- **Two decisions this plan makes that the spec left open:** the `wasHeldForReview` marker (Task 6 Step 1 — needed because status alone cannot tell a completed job it was once held) and `NotificationPriority.NORMAL` for the ready notification (spec said "NORMAL or HIGH, implementation-time"; NORMAL chosen because `CRITICAL`/`HIGH` are for security events).
- **Known soft spots for the implementer:** Tasks 1, 2, 3, and 4 each open with a "read the existing code first" step because exact field names, method visibility, and the sweep's exclusion-set polarity were not fully read at plan-writing time. Task 3 in particular carries an explicit warning: getting the polarity backwards would delete the very files this feature reprocesses.
