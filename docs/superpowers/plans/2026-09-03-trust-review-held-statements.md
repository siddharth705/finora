# Held Statement Review — Plan 1 of 4: Quarantine Foundation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this
> plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop an import whose extraction evidence says it may be wrong from reaching a user's
ledger — quarantine it under a human-readable Held ID, record why, notify the user, and let an
admin approve or reject it. Everything downstream (operator portal, engineer workflow, parser
re-run, metrics) depends on this being right, and is planned separately.

**Architecture:** The statement's bytes never move. A hold is a row in `held_statements` pointing
at the same `object_key` the job already has, plus a new terminal
`ImportJob.Status.HELD_FOR_TRUST_REVIEW`. That status is load-bearing rather than cosmetic:
`StatementStorageSweepService` treats `COMPLETED` as "no longer a live reference," so completing a
held job would eventually let the sweep reclaim the very PDF a reviewer still needs.

**Tech Stack:** Spring Boot / JPA / Flyway, Postgres, React + TypeScript (admin-portal),
Cloudflare R2 (unchanged).

**Spec:** The repository owner's approved brief, "Held Statement Review System — Implementation
Plan" (in-conversation, 2026-09-03). This plan implements its Phases 0–4 plus the minimum of
Phase 8 needed to make a hold resolvable.

## Scope Check

The approved brief spans several independent subsystems. Per this skill's own guidance, each gets
a plan that produces working, testable software on its own:

| Plan | Covers | Status |
|---|---|---|
| **1 — Quarantine Foundation (this plan)** | Brief Phases 0–4, minimal Phase 8 | ready |
| 2 — Operator Portal | Brief Phases 5, 9 (admin list/detail views, user-facing status) | after 1 |
| 3 — Engineer Workflow & Parser Re-run | Brief Phases 6, 7 | after 2 |
| 4 — Metrics & False-Positive Tracking | Brief Phase 10 | after 3 |

Brief Phase 11 (future trust signals) is explicitly deferred until telemetry volume exists, per
the brief's own instruction.

## Global Constraints

- **Storage is immutable.** No second bucket, no copy, no move. `held_statements.statement_object_key`
  names the same R2 object `import_jobs.object_key` already does.
- **Telemetry can never break imports.** Creating a hold record, writing an audit event, or
  sending a notification must never roll back or fail a successful extraction — the same rule V62
  states for merchant learning.
- **Never trust future parser versions.** Every hold records `parser_version`, `reliability_status`,
  `text_source` and `header_reconstruction_uncertain` as a snapshot at hold time.
- **Held IDs are `HLD-YYYY-NNNNNN`** (e.g. `HLD-2026-000001`) — year, then a six-digit sequence.
  Operators never see a raw UUID.
- **V1 hold conditions are exactly three, and nothing else.** Printed-vs-parsed *count* mismatch;
  a confirmed `PRE_HEADER_ACTIVITY_CANDIDATE` dropped transaction; statement-period metadata
  integrity failure. Explicitly NOT holding on: OCR provenance, column ambiguity, header
  reconstruction uncertainty, balance chain, duplicates, missing holder, missing account number,
  or a **missing** statement period.
- Before writing any migration: `git fetch origin`, scan
  `backend/src/main/resources/db/migration` across `origin/main` and every remote branch, and use
  the next free version. A lower version arriving later fails the backend's boot outright — this
  repository has hit that three times. Do not hardcode a number from this plan.

---

## Evidence behind the three hold conditions

Each condition maps onto a signal that already exists. Verified by reading the source, not assumed:

| Brief condition | Existing signal | How to detect |
|---|---|---|
| Printed vs parsed **count** mismatch | `SummaryTotalsValidator` | finding `rule == "SUMMARY_TOTALS"`, `outcome == "FAILED"`, and `details.suspectedCause != "AMOUNTS"` — the validator already classifies count-driven causes (`DIRECTION`, `ROW_GROUPING`, `MISSING_OR_EXTRA_ROWS`) separately from an amounts-only mismatch |
| Confirmed dropped transaction | `RowAccountingValidator` | finding `rule == RowAccountingValidator.RULE` whose `details.droppedTransactionCandidateReasons` map contains key `PRE_HEADER_ACTIVITY_CANDIDATE` — the same detection `ImportReliabilityStatusDeriver` already performs |
| Metadata integrity | `DetectedAccountInfo.statementPeriodStart` / `statementPeriodEnd` (`LocalDate`, `ImportDto:297-298`) | end < start, start in future, end in future, or span > 400 days. A **null** period never holds |

---

## File Structure

**Create:**
- `backend/src/main/resources/db/migration/V<NEXT>__held_statement_review.sql`
- `backend/src/main/java/com/finora/entity/HeldStatement.java`
- `backend/src/main/java/com/finora/entity/HeldStatementEvent.java`
- `backend/src/main/java/com/finora/repository/HeldStatementRepository.java`
- `backend/src/main/java/com/finora/repository/HeldStatementEventRepository.java`
- `backend/src/main/java/com/finora/imports/trust/HeldStatementIdGenerator.java`
- `backend/src/main/java/com/finora/imports/trust/TrustPredicate.java`
- `backend/src/main/java/com/finora/imports/trust/HoldDecision.java`
- `backend/src/main/java/com/finora/service/HeldStatementService.java`
- `backend/src/main/java/com/finora/dto/HeldStatementDto.java`
- `backend/src/main/java/com/finora/controller/AdminHeldStatementController.java`

**Modify:**
- `backend/src/main/java/com/finora/entity/ImportJob.java` — status, `holdForTrustReview()`, `heldStatementId`
- `backend/src/main/java/com/finora/imports/jobs/StagedForJob.java` — carry period dates
- `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java` — branch on the predicate
- `backend/src/main/java/com/finora/imports/storage/StatementStorageSweepService.java` — doc only

---

### Task 1: Schema

**Files:**
- Create: `backend/src/main/resources/db/migration/V<NEXT>__held_statement_review.sql`
- Test: `backend/src/test/java/com/finora/entity/HeldStatementSchemaIT.java`

**Interfaces:**
- Produces: tables `held_statements`, `held_statement_events`; sequence
  `held_statement_reference_seq`; `import_jobs.held_statement_id`; status value
  `HELD_FOR_TRUST_REVIEW`; permission `TRUST_REVIEW_MANAGE`.

- [ ] **Step 1: Resolve the version**

```bash
git fetch origin
git log --remotes --name-only --format= -- backend/src/main/resources/db/migration | grep -oE 'V[0-9]+__' | sort -u -V | tail -3
```

Use one higher than the highest shown. Substitute for every `V<NEXT>` below.

- [ ] **Step 2: Write the migration**

```sql
-- V<NEXT>__held_statement_review.sql
--
-- Held Statement Review, Plan 1: quarantine an import whose extraction evidence says it may be
-- wrong, instead of letting it reach a user's ledger unreviewed.
--
-- Storage is untouched. held_statements.statement_object_key names the SAME R2 object
-- import_jobs.object_key already names -- nothing is copied, nothing is moved, and the
-- content-addressed sharing the sweep depends on is preserved exactly.

-- A new terminal status, distinct from HELD_FOR_REVIEW (V134), which is scoped to a human fixing
-- a parser. This is a judgment call about a document that parsed fine.
ALTER TABLE import_jobs DROP CONSTRAINT import_jobs_status_valid;
ALTER TABLE import_jobs ADD CONSTRAINT import_jobs_status_valid CHECK (status IN (
    'QUEUED', 'PARSING', 'ANALYZING', 'DEDUPING', 'IMPORTING', 'LEARNING',
    'COMPLETED', 'FAILED', 'HELD_FOR_REVIEW', 'HELD_FOR_TRUST_REVIEW', 'CANCELLED'));

-- Mirrors V134's own precedent for HELD_FOR_REVIEW: a job waiting on a human is not "in flight"
-- for the duplicate-upload guard, so re-uploading the same statement stays possible.
DROP INDEX idx_import_jobs_live_content;
CREATE UNIQUE INDEX idx_import_jobs_live_content ON import_jobs (user_id, content_hash)
    WHERE status NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'HELD_FOR_REVIEW',
                          'HELD_FOR_TRUST_REVIEW');

-- Back-reference, per the brief's Phase 0. Nullable: NULL means this import was never held.
ALTER TABLE import_jobs ADD COLUMN held_statement_id UUID;

-- HELD_FOR_TRUST_REVIEW is deliberately NOT added to
-- StatementStorageSweepService.IMPORT_JOB_EXCLUDED_STATUSES ({COMPLETED, CANCELLED}). That set
-- decides whether a job counts as a LIVE reference to its object. A held job must keep counting
-- as live, or the sweep could reclaim the PDF a reviewer still needs. This is the load-bearing
-- reason the status exists at all rather than reusing COMPLETED with a side table.

CREATE SEQUENCE held_statement_reference_seq START 1;

CREATE TABLE held_statements (
    id                                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    held_id                           VARCHAR(32) NOT NULL UNIQUE,
    import_job_id                     UUID NOT NULL UNIQUE REFERENCES import_jobs(id),
    user_id                           UUID NOT NULL REFERENCES users(id),
    statement_object_key              TEXT NOT NULL,

    -- Snapshot at hold time. The brief's Principle 3: a statement held under one parser build
    -- must never be silently re-imported under that same build without review.
    parser_version                    VARCHAR(64),
    reliability_status                VARCHAR(24),
    text_source                       VARCHAR(24),
    header_reconstruction_uncertain   BOOLEAN,

    status                            VARCHAR(32) NOT NULL CHECK (status IN (
                                          'HELD', 'ASSIGNED', 'INVESTIGATING',
                                          'READY_FOR_IMPORT', 'IMPORTED', 'REJECTED')),

    assigned_engineer_id              UUID REFERENCES users(id),
    trigger_summary                   TEXT,
    engineer_notes                    TEXT,

    created_at                        TIMESTAMPTZ NOT NULL DEFAULT now(),
    assigned_at                       TIMESTAMPTZ,
    ready_at                          TIMESTAMPTZ,
    resolved_at                       TIMESTAMPTZ,

    created_by                        UUID REFERENCES users(id),
    resolved_by                       UUID REFERENCES users(id)
);

-- The queue's ordering: oldest first, matching every other operator queue in this codebase.
CREATE INDEX idx_held_statements_open ON held_statements (created_at)
    WHERE status IN ('HELD', 'ASSIGNED', 'INVESTIGATING', 'READY_FOR_IMPORT');

COMMENT ON COLUMN held_statements.statement_object_key IS
    'The same R2 key import_jobs.object_key already names. Never a copy, never moved -- this row '
    'tracks a workflow state over one shared object.';
COMMENT ON COLUMN held_statements.parser_version IS
    'The deploy that parsed this statement, snapshotted at hold time. A hold must never be '
    'auto-released under the same build that produced it.';

CREATE TABLE held_statement_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    held_statement_id   UUID NOT NULL REFERENCES held_statements(id),
    actor_id            UUID REFERENCES users(id),
    event_type          VARCHAR(64) NOT NULL,
    from_status         VARCHAR(32),
    to_status           VARCHAR(32),
    notes               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_held_statement_events_by_statement
    ON held_statement_events (held_statement_id, created_at);

COMMENT ON TABLE held_statement_events IS
    'Every state transition and operator action, in order. Financial workflows eventually have to '
    'answer why a statement was held, who reviewed it and who released it -- captured as it '
    'happens rather than reconstructed from logs later. actor_id NULL means the system acted.';

-- Its own permission, not a reuse -- the same reasoning V135 applied to IMPORT_TRIAGE_MANAGE:
-- reviewing a hold means reading a real customer's statement content.
INSERT INTO permissions (name, description) VALUES
    ('TRUST_REVIEW_MANAGE',
     'View and resolve statements held for trust review. Grants access to real user statement '
     'content; every resolution is audited.');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.name IN ('ADMIN', 'SUPER_ADMIN') AND p.name = 'TRUST_REVIEW_MANAGE';
```

- [ ] **Step 3: Write the schema test**

```java
package com.finora.entity;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Proves the DDL is internally consistent -- nothing below the database can. */
class HeldStatementSchemaIT extends AbstractIntegrationTest {

    @Autowired private JdbcTemplate jdbc;

    @Test
    void statusCheckAcceptsHeldForTrustReview() {
        jdbc.update("UPDATE import_jobs SET status = 'HELD_FOR_TRUST_REVIEW' "
                + "WHERE id = (SELECT id FROM import_jobs LIMIT 1)");
    }

    @Test
    void heldStatementSequenceIssuesValues() {
        assertThat(jdbc.queryForObject("SELECT nextval('held_statement_reference_seq')", Long.class))
                .isNotNull();
    }

    @Test
    void permissionIsGrantedToAdminAndSuperAdmin() {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM role_permissions rp
                  JOIN roles r ON r.id = rp.role_id
                  JOIN permissions p ON p.id = rp.permission_id
                 WHERE p.name = 'TRUST_REVIEW_MANAGE' AND r.name IN ('ADMIN','SUPER_ADMIN')
                """, Integer.class)).isEqualTo(2);
    }

    @Test
    void heldStatementsRejectsAnUnknownStatus() {
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                jdbc.update("INSERT INTO held_statements (held_id, import_job_id, user_id, "
                        + "statement_object_key, status) VALUES ('HLD-2026-000001', "
                        + "gen_random_uuid(), gen_random_uuid(), 'k', 'NOT_A_STATUS')")))
                .isNotNull();
    }
}
```

- [ ] **Step 4: Run**

```bash
cd backend && ./mvnw -Dtest=HeldStatementSchemaIT -DfailIfNoTests=false test
```

Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/resources/db/migration/ backend/src/test/java/com/finora/entity/HeldStatementSchemaIT.java
git commit -m "feat(imports): schema for held statement review"
```

---

### Task 2: ImportJob — the new terminal status

**Files:**
- Modify: `backend/src/main/java/com/finora/entity/ImportJob.java`
- Modify: `backend/src/main/java/com/finora/imports/storage/StatementStorageSweepService.java` (doc only)
- Test: `backend/src/test/java/com/finora/entity/ImportJobTest.java`

**Interfaces:**
- Produces: `ImportJob.Status.HELD_FOR_TRUST_REVIEW`; `holdForTrustReview(UUID sessionId, UUID
  heldStatementId, Instant now)`; `getHeldStatementId()`.

- [ ] **Step 1: Write the failing tests**

Append to `ImportJobTest`:

```java
@Test
void holdForTrustReview_isTerminalAndNotCompleted() {
    ImportJob job = job();
    job.markClaimed("worker", Instant.now());
    UUID sessionId = UUID.randomUUID();
    UUID heldId = UUID.randomUUID();

    job.holdForTrustReview(sessionId, heldId, Instant.now());

    assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
    assertThat(job.getStatus().isTerminal()).isTrue();
    assertThat(job.getImportSessionId()).isEqualTo(sessionId);
    assertThat(job.getHeldStatementId()).isEqualTo(heldId);
}

/** The invariant that keeps a held statement's PDF readable: the sweep must keep counting this
 *  job as a live reference, or it could reclaim the object mid-review. */
@Test
void heldForTrustReviewMustNeverBeExcludedFromLiveObjectReferences() {
    assertThat(com.finora.imports.storage.StatementStorageSweepService.IMPORT_JOB_EXCLUDED_STATUSES)
            .as("a held job must keep its object reclaim-protected")
            .doesNotContain(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
}
```

- [ ] **Step 2: Run to verify failure**

```bash
cd backend && ./mvnw -Dtest=ImportJobTest -DfailIfNoTests=false test
```

Expected: compile error — neither the status nor the method exists.

- [ ] **Step 3: Implement**

In `ImportJob.java`, extend the enum and `TERMINAL`:

```java
public enum Status {
    QUEUED, PARSING, ANALYZING, DEDUPING, IMPORTING, LEARNING, COMPLETED, FAILED,
    HELD_FOR_REVIEW, HELD_FOR_TRUST_REVIEW, CANCELLED;

    public static final Set<Status> IN_FLIGHT =
            EnumSet.of(PARSING, ANALYZING, DEDUPING, IMPORTING, LEARNING);

    public static final Set<Status> TERMINAL =
            EnumSet.of(COMPLETED, FAILED, HELD_FOR_REVIEW, HELD_FOR_TRUST_REVIEW, CANCELLED);

    public boolean isTerminal() { return TERMINAL.contains(this); }
    public boolean isInFlight() { return IN_FLIGHT.contains(this); }
    boolean isBefore(Status other) { return ordinal() < other.ordinal(); }
}
```

Add the field and transition:

```java
@Column(name = "held_statement_id")
private UUID heldStatementId;

/**
 * Staging succeeded -- the rows are in {@code sessionId} -- but the trust predicate says this
 * import may be wrong, so it is withheld from the user's confirm flow until a human decides.
 *
 * <p>Deliberately not {@link #complete}: COMPLETED is what
 * {@link com.finora.imports.storage.StatementStorageSweepService} reads as "no longer a live
 * reference", and this object is exactly the one a reviewer still needs to open.
 */
public void holdForTrustReview(UUID sessionId, UUID heldStatementId, Instant now) {
    this.status = Status.HELD_FOR_TRUST_REVIEW;
    this.importSessionId = sessionId;
    this.heldStatementId = heldStatementId;
    this.finishedAt = now;
}

public UUID getHeldStatementId() { return heldStatementId; }
```

- [ ] **Step 4: Extend the sweep's doc comment** (no behaviour change)

```java
/** ... existing text ...
 *
 *  <p>HELD_FOR_TRUST_REVIEW is deliberately absent and must stay absent: a held job has not been
 *  released, so its object must keep counting as live or the sweep could reclaim a PDF still
 *  under review. ImportJobTest#heldForTrustReviewMustNeverBeExcludedFromLiveObjectReferences
 *  fails if this set ever grows to include it. */
static final Set<ImportJob.Status> IMPORT_JOB_EXCLUDED_STATUSES =
        EnumSet.of(ImportJob.Status.COMPLETED, ImportJob.Status.CANCELLED);
```

- [ ] **Step 5: Run to verify pass**

```bash
cd backend && ./mvnw -Dtest=ImportJobTest -DfailIfNoTests=false test
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/java/com/finora/entity/ImportJob.java \
        backend/src/main/java/com/finora/imports/storage/StatementStorageSweepService.java \
        backend/src/test/java/com/finora/entity/ImportJobTest.java
git commit -m "feat(imports): HELD_FOR_TRUST_REVIEW terminal status"
```

---

### Task 3: The trust predicate

**Files:**
- Create: `backend/src/main/java/com/finora/imports/trust/HoldDecision.java`
- Create: `backend/src/main/java/com/finora/imports/trust/TrustPredicate.java`
- Test: `backend/src/test/java/com/finora/imports/trust/TrustPredicateTest.java`

**Interfaces:**
- Consumes: `ImportDto.VerificationReport` / `VerificationFinding`, `java.time.LocalDate`.
- Produces: `HoldDecision` (record: `boolean hold`, `List<String> reasons`, `String summary()`);
  `TrustPredicate.evaluate(List<ImportDto.VerificationReport> reports, List<LocalDate[]> periods,
  LocalDate today)` → `HoldDecision`.

- [ ] **Step 1: Write the failing tests**

```java
package com.finora.imports.trust;

import com.finora.dto.ImportDto;
import com.finora.imports.ImportReliabilityStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three v1 hold conditions, and — just as importantly — the signals that must NOT hold.
 * Every "does not hold" case below is a deliberate scope decision from the approved brief:
 * persist and observe first, gate later, once real distributions exist.
 */
class TrustPredicateTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 9, 3);

    private static ImportDto.VerificationReport report(ImportDto.VerificationFinding... findings) {
        return new ImportDto.VerificationReport(List.of(findings), false, "NATIVE_PDF",
                ImportReliabilityStatus.CLEAN);
    }

    private static ImportDto.VerificationFinding summaryTotals(String cause) {
        return new ImportDto.VerificationFinding("SUMMARY_TOTALS", "FAILED",
                Map.of("suspectedCause", cause));
    }

    // ---------------------------------------------------------------- condition 1: count mismatch

    @Test
    void holdsWhenPrintedAndParsedCountsDisagree() {
        for (String cause : List.of("DIRECTION", "ROW_GROUPING", "MISSING_OR_EXTRA_ROWS")) {
            HoldDecision decision = TrustPredicate.evaluate(
                    List.of(report(summaryTotals(cause))), List.of(), TODAY);
            assertThat(decision.hold()).as(cause).isTrue();
            assertThat(decision.summary()).contains("count");
        }
    }

    /** An amounts-only mismatch is a different defect and is explicitly out of v1 scope: the
     *  document's own count reconciliation is the high-quality signal, not its arithmetic. */
    @Test
    void doesNotHoldWhenOnlyAmountsDisagree() {
        assertThat(TrustPredicate.evaluate(
                List.of(report(summaryTotals("AMOUNTS"))), List.of(), TODAY).hold()).isFalse();
    }

    // ------------------------------------------------------- condition 2: dropped transaction

    @Test
    void holdsOnAConfirmedPreHeaderActivityCandidate() {
        ImportDto.VerificationFinding rowAccounting = new ImportDto.VerificationFinding(
                "ROW_ACCOUNTING", "WARNING",
                Map.of("droppedTransactionCandidateReasons",
                        Map.of("PRE_HEADER_ACTIVITY_CANDIDATE", 1)));

        HoldDecision decision = TrustPredicate.evaluate(
                List.of(report(rowAccounting)), List.of(), TODAY);

        assertThat(decision.hold()).isTrue();
        assertThat(decision.summary()).contains("dropped");
    }

    /** Any OTHER dropped-row reason is unproven and must not hold -- only the pre-header one has
     *  been confirmed against real documents to mean a genuinely lost transaction. */
    @Test
    void doesNotHoldOnOtherDroppedRowReasons() {
        ImportDto.VerificationFinding rowAccounting = new ImportDto.VerificationFinding(
                "ROW_ACCOUNTING", "WARNING",
                Map.of("droppedTransactionCandidateReasons", Map.of("UNEXPLAINED_ROW", 3)));

        assertThat(TrustPredicate.evaluate(List.of(report(rowAccounting)), List.of(), TODAY).hold())
                .isFalse();
    }

    // ------------------------------------------------------------ condition 3: metadata integrity

    @Test
    void holdsWhenPeriodEndsBeforeItStarts() {
        HoldDecision decision = TrustPredicate.evaluate(List.of(),
                List.of(new LocalDate[]{LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)}), TODAY);

        assertThat(decision.hold()).isTrue();
        assertThat(decision.summary()).contains("period");
    }

    @Test
    void holdsWhenPeriodIsInTheFuture() {
        assertThat(TrustPredicate.evaluate(List.of(),
                List.of(new LocalDate[]{LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 31)}), TODAY)
                .hold()).isTrue();
    }

    @Test
    void holdsWhenPeriodSpansMoreThan400Days() {
        assertThat(TrustPredicate.evaluate(List.of(),
                List.of(new LocalDate[]{LocalDate.of(2024, 1, 1), LocalDate.of(2026, 1, 1)}), TODAY)
                .hold()).isTrue();
    }

    /**
     * A missing period must never hold. Historical corpus data showed that would quarantine the
     * majority of otherwise-good imports -- the single most important negative case here.
     */
    @Test
    void doesNotHoldOnAMissingPeriod() {
        assertThat(TrustPredicate.evaluate(List.of(), List.of(new LocalDate[]{null, null}), TODAY)
                .hold()).isFalse();
        assertThat(TrustPredicate.evaluate(List.of(),
                List.of(new LocalDate[]{LocalDate.of(2026, 8, 1), null}), TODAY).hold()).isFalse();
    }

    // ------------------------------------------------------------------ explicit non-conditions

    /** Every one of these is a real signal the pipeline computes and v1 deliberately does NOT
     *  gate on. If any starts holding imports, that is a scope regression, not an improvement. */
    @Test
    void doesNotHoldOnSignalsExcludedFromV1() {
        ImportDto.VerificationReport ocrAndUncertainHeader = new ImportDto.VerificationReport(
                List.of(new ImportDto.VerificationFinding("BALANCE_CHAIN", "FAILED", Map.of()),
                        new ImportDto.VerificationFinding("COLUMN_AMBIGUITY", "WARNING", Map.of())),
                true, "OCR", ImportReliabilityStatus.NEEDS_ATTENTION);

        assertThat(TrustPredicate.evaluate(List.of(ocrAndUncertainHeader), List.of(), TODAY).hold())
                .as("OCR, column ambiguity, header uncertainty and balance chain are all v1 "
                        + "observe-only signals")
                .isFalse();
    }

    @Test
    void cleanExtractionDoesNotHold() {
        assertThat(TrustPredicate.evaluate(
                List.of(report(new ImportDto.VerificationFinding("SUMMARY_TOTALS", "VERIFIED", Map.of()))),
                List.of(new LocalDate[]{LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)}), TODAY)
                .hold()).isFalse();
    }

    @Test
    void reasonsAccumulateWhenSeveralConditionsFire() {
        HoldDecision decision = TrustPredicate.evaluate(
                List.of(report(summaryTotals("ROW_GROUPING"))),
                List.of(new LocalDate[]{LocalDate.of(2026, 8, 31), LocalDate.of(2026, 8, 1)}), TODAY);

        assertThat(decision.reasons()).hasSize(2);
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
cd backend && ./mvnw -Dtest=TrustPredicateTest -DfailIfNoTests=false test
```

Expected: compile error — neither class exists.

- [ ] **Step 3: Implement**

```java
// HoldDecision.java
package com.finora.imports.trust;

import java.util.List;

/** Whether an import is quarantined, and every reason that fired. */
public record HoldDecision(boolean hold, List<String> reasons) {

    public static final HoldDecision RELEASE = new HoldDecision(false, List.of());

    /** The one line stored on {@code held_statements.trigger_summary} and shown to an operator. */
    public String summary() {
        return String.join("; ", reasons);
    }
}
```

```java
// TrustPredicate.java
package com.finora.imports.trust;

import com.finora.dto.ImportDto;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Decides whether an extraction is trustworthy enough to reach a user's ledger unreviewed.
 *
 * <p>Three conditions, and deliberately only three. Each is a signal the pipeline already
 * computes, chosen because it is evidence a specific transaction is wrong or missing rather than
 * evidence that extraction was merely difficult:
 *
 * <ol>
 *   <li><b>Printed vs parsed count mismatch.</b> The document grades its own extraction -- the
 *       bank printed how many debits and credits it thinks are there. Amounts-only mismatches are
 *       excluded: a wrong amount is a different defect from a missing row.</li>
 *   <li><b>A confirmed dropped transaction.</b> Only {@code PRE_HEADER_ACTIVITY_CANDIDATE}, the
 *       one dropped-row reason verified against real documents to mean a genuinely lost
 *       transaction rather than a merely unexplained row.</li>
 *   <li><b>Statement period integrity.</b> A period that ends before it starts, sits in the
 *       future, or spans more than 400 days did not come out of the document correctly.</li>
 * </ol>
 *
 * <p><b>What is deliberately excluded.</b> OCR provenance, column ambiguity, header
 * reconstruction uncertainty, balance-chain discrepancies, duplicates, and missing account
 * metadata are all observed and persisted, and none of them hold an import. A <b>missing</b>
 * period never holds either -- corpus data showed that would quarantine most good imports. Those
 * become candidates only once telemetry shows their real distribution.
 */
public final class TrustPredicate {

    /** Beyond this, a "statement period" is not a statement period. */
    private static final long MAX_PERIOD_DAYS = 400;

    private static final String SUMMARY_TOTALS = "SUMMARY_TOTALS";
    private static final String ROW_ACCOUNTING = "ROW_ACCOUNTING";
    private static final String PRE_HEADER_ACTIVITY_CANDIDATE = "PRE_HEADER_ACTIVITY_CANDIDATE";

    private TrustPredicate() {}

    /**
     * @param periods one {@code {start, end}} pair per account section; either element may be
     *                null, which is never on its own a reason to hold
     */
    public static HoldDecision evaluate(List<ImportDto.VerificationReport> reports,
                                        List<LocalDate[]> periods, LocalDate today) {
        List<String> reasons = new ArrayList<>();

        if (reports != null) {
            for (ImportDto.VerificationReport report : reports) {
                if (report == null || report.findings() == null) continue;
                for (ImportDto.VerificationFinding finding : report.findings()) {
                    countMismatch(finding).ifPresent(reasons::add);
                    droppedTransaction(finding).ifPresent(reasons::add);
                }
            }
        }
        if (periods != null) {
            for (LocalDate[] period : periods) {
                periodIntegrity(period, today).ifPresent(reasons::add);
            }
        }

        return reasons.isEmpty() ? HoldDecision.RELEASE
                : new HoldDecision(true, List.copyOf(reasons));
    }

    /** SummaryTotalsValidator classifies its own FAILED outcome; every cause but AMOUNTS is
     *  count-driven, which is the signal this condition is about. */
    private static java.util.Optional<String> countMismatch(ImportDto.VerificationFinding f) {
        if (!SUMMARY_TOTALS.equals(f.rule()) || !"FAILED".equals(f.outcome())) {
            return java.util.Optional.empty();
        }
        Object cause = f.details() == null ? null : f.details().get("suspectedCause");
        if (cause == null || "AMOUNTS".equals(cause)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(
                "Printed and parsed transaction count disagree (" + cause + ")");
    }

    private static java.util.Optional<String> droppedTransaction(ImportDto.VerificationFinding f) {
        if (!ROW_ACCOUNTING.equals(f.rule()) || f.details() == null) {
            return java.util.Optional.empty();
        }
        Object reasons = f.details().get("droppedTransactionCandidateReasons");
        if (reasons instanceof Map<?, ?> map && map.containsKey(PRE_HEADER_ACTIVITY_CANDIDATE)) {
            return java.util.Optional.of("A transaction was likely dropped before the header row");
        }
        return java.util.Optional.empty();
    }

    private static java.util.Optional<String> periodIntegrity(LocalDate[] period, LocalDate today) {
        if (period == null || period.length != 2 || period[0] == null || period[1] == null) {
            // A missing period is not a defect this gates on -- see the class doc.
            return java.util.Optional.empty();
        }
        LocalDate start = period[0];
        LocalDate end = period[1];
        if (end.isBefore(start)) {
            return java.util.Optional.of("Statement period ends before it starts");
        }
        if (start.isAfter(today) || end.isAfter(today)) {
            return java.util.Optional.of("Statement period is in the future");
        }
        if (ChronoUnit.DAYS.between(start, end) > MAX_PERIOD_DAYS) {
            return java.util.Optional.of("Statement period spans more than " + MAX_PERIOD_DAYS + " days");
        }
        return java.util.Optional.empty();
    }
}
```

- [ ] **Step 4: Run to verify pass**

```bash
cd backend && ./mvnw -Dtest=TrustPredicateTest -DfailIfNoTests=false test
```

Expected: `Tests run: 11, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/finora/imports/trust/ backend/src/test/java/com/finora/imports/trust/
git commit -m "feat(imports): the v1 trust predicate"
```

---

### Task 4: Entities, repositories and the Held ID generator

**Files:**
- Create: `backend/src/main/java/com/finora/entity/HeldStatement.java`
- Create: `backend/src/main/java/com/finora/entity/HeldStatementEvent.java`
- Create: `backend/src/main/java/com/finora/repository/HeldStatementRepository.java`
- Create: `backend/src/main/java/com/finora/repository/HeldStatementEventRepository.java`
- Create: `backend/src/main/java/com/finora/imports/trust/HeldStatementIdGenerator.java`
- Test: `backend/src/test/java/com/finora/repository/HeldStatementRepositoryIT.java`
- Test: `backend/src/test/java/com/finora/imports/trust/HeldStatementIdGeneratorTest.java`

**Interfaces:**
- Produces: `HeldStatement` with `Status {HELD, ASSIGNED, INVESTIGATING, READY_FOR_IMPORT,
  IMPORTED, REJECTED}`, snapshot fields, and transitions `assign`, `startInvestigation`,
  `markReadyForImport`, `markImported`, `reject`; `HeldStatementEvent`;
  `HeldStatementRepository.nextHeldSequence()`; `HeldStatementIdGenerator.next()` → `HLD-2026-000001`.

- [ ] **Step 1: Write the failing tests**

```java
// HeldStatementIdGeneratorTest.java
package com.finora.imports.trust;

import com.finora.repository.HeldStatementRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HeldStatementIdGeneratorTest {

    private HeldStatementIdGenerator generator(long seq, String instant) {
        HeldStatementRepository repository = mock(HeldStatementRepository.class);
        when(repository.nextHeldSequence()).thenReturn(seq);
        return new HeldStatementIdGenerator(repository,
                Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    @Test
    void formatsAsHldYearAndSixDigitSequence() {
        assertThat(generator(1L, "2026-09-03T10:00:00Z").next()).isEqualTo("HLD-2026-000001");
    }

    @Test
    void keepsSixDigitsForLargerSequences() {
        assertThat(generator(123456L, "2026-09-03T10:00:00Z").next()).isEqualTo("HLD-2026-123456");
    }
}
```

```java
// HeldStatementRepositoryIT.java
package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HeldStatementRepositoryIT extends AbstractIntegrationTest {

    @Autowired private HeldStatementRepository repository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;

    private User user() {
        User u = new User();
        u.setEmail("held-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant");
        u.setFullName("Held Test");
        return userRepository.save(u);
    }

    private HeldStatement seed(String heldId) {
        User owner = user();
        ImportJob job = importJobRepository.save(
                new ImportJob(owner.getId(), "s.pdf", "h-" + UUID.randomUUID(), "k1", "PDF"));
        return repository.save(new HeldStatement(heldId, job.getId(), owner.getId(),
                job.getObjectKey(), "Printed and parsed transaction count disagree"));
    }

    @Test
    void findsByHeldIdAndImportJob() {
        HeldStatement held = seed("HLD-2026-000001");

        assertThat(repository.findByHeldId("HLD-2026-000001")).isPresent();
        assertThat(repository.findByImportJobId(held.getImportJobId())).isPresent();
    }

    @Test
    void openQueueExcludesResolvedStatements() {
        seed("HLD-2026-000002");
        HeldStatement resolved = seed("HLD-2026-000003");
        resolved.reject(UUID.randomUUID(), "unusable", Instant.now());
        repository.save(resolved);

        List<HeldStatement> open = repository.findByStatusIn(
                List.of(HeldStatement.Status.HELD, HeldStatement.Status.ASSIGNED,
                        HeldStatement.Status.INVESTIGATING, HeldStatement.Status.READY_FOR_IMPORT),
                PageRequest.of(0, 25)).getContent();

        assertThat(open).extracting(HeldStatement::getHeldId).contains("HLD-2026-000002")
                .doesNotContain("HLD-2026-000003");
    }

    @Test
    void lifecycleTransitionsRecordTheirTimestamps() {
        HeldStatement held = seed("HLD-2026-000004");
        UUID engineer = UUID.randomUUID();
        Instant now = Instant.now();

        held.assign(engineer, now);
        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.ASSIGNED);
        assertThat(held.getAssignedAt()).isEqualTo(now);

        held.startInvestigation();
        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.INVESTIGATING);

        held.markReadyForImport(now);
        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.READY_FOR_IMPORT);
        assertThat(held.getReadyAt()).isEqualTo(now);

        held.markImported(UUID.randomUUID(), now);
        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.IMPORTED);
        assertThat(held.getResolvedAt()).isEqualTo(now);
    }
}
```

- [ ] **Step 2: Run to verify failure**

```bash
cd backend && ./mvnw -Dtest=HeldStatementIdGeneratorTest,HeldStatementRepositoryIT -DfailIfNoTests=false test
```

Expected: compile errors — none of these classes exist.

- [ ] **Step 3: Implement `HeldStatement`**

```java
package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One statement quarantined before it reached a user's ledger. Points at the same object key the
 * owning {@link ImportJob} already has -- a workflow state over one shared object, never a copy.
 *
 * <p>The snapshot fields ({@code parserVersion}, {@code reliabilityStatus}, {@code textSource},
 * {@code headerReconstructionUncertain}) are captured at hold time on purpose: a later re-run
 * under a different build must be comparable against what the original build actually saw.
 */
@Entity
@Table(name = "held_statements")
public class HeldStatement {

    public enum Status { HELD, ASSIGNED, INVESTIGATING, READY_FOR_IMPORT, IMPORTED, REJECTED }

    @Id private UUID id = UUID.randomUUID();

    @Column(name = "held_id", nullable = false, unique = true) private String heldId;
    @Column(name = "import_job_id", nullable = false) private UUID importJobId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "statement_object_key", nullable = false) private String statementObjectKey;

    @Column(name = "parser_version") private String parserVersion;
    @Column(name = "reliability_status") private String reliabilityStatus;
    @Column(name = "text_source") private String textSource;
    @Column(name = "header_reconstruction_uncertain") private Boolean headerReconstructionUncertain;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false) private Status status = Status.HELD;

    @Column(name = "assigned_engineer_id") private UUID assignedEngineerId;
    @Column(name = "trigger_summary") private String triggerSummary;
    @Column(name = "engineer_notes") private String engineerNotes;

    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "assigned_at") private Instant assignedAt;
    @Column(name = "ready_at") private Instant readyAt;
    @Column(name = "resolved_at") private Instant resolvedAt;

    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "resolved_by") private UUID resolvedBy;

    protected HeldStatement() {}

    public HeldStatement(String heldId, UUID importJobId, UUID userId, String statementObjectKey,
                          String triggerSummary) {
        this.heldId = heldId;
        this.importJobId = importJobId;
        this.userId = userId;
        this.statementObjectKey = statementObjectKey;
        this.triggerSummary = triggerSummary;
    }

    /** The extraction snapshot, recorded once when the hold is created. */
    public void recordSnapshot(String parserVersion, String reliabilityStatus, String textSource,
                                Boolean headerReconstructionUncertain) {
        this.parserVersion = parserVersion;
        this.reliabilityStatus = reliabilityStatus;
        this.textSource = textSource;
        this.headerReconstructionUncertain = headerReconstructionUncertain;
    }

    public void assign(UUID engineerId, Instant now) {
        this.assignedEngineerId = engineerId;
        this.assignedAt = now;
        this.status = Status.ASSIGNED;
    }

    public void startInvestigation() { this.status = Status.INVESTIGATING; }

    public void addNotes(String notes) { this.engineerNotes = notes; }

    public void markReadyForImport(Instant now) {
        this.status = Status.READY_FOR_IMPORT;
        this.readyAt = now;
    }

    public void markImported(UUID adminId, Instant now) {
        this.status = Status.IMPORTED;
        this.resolvedBy = adminId;
        this.resolvedAt = now;
    }

    public void reject(UUID adminId, String reason, Instant now) {
        this.status = Status.REJECTED;
        this.resolvedBy = adminId;
        this.engineerNotes = reason;
        this.resolvedAt = now;
    }

    public UUID getId() { return id; }
    public String getHeldId() { return heldId; }
    public UUID getImportJobId() { return importJobId; }
    public UUID getUserId() { return userId; }
    public String getStatementObjectKey() { return statementObjectKey; }
    public String getParserVersion() { return parserVersion; }
    public String getReliabilityStatus() { return reliabilityStatus; }
    public String getTextSource() { return textSource; }
    public Boolean getHeaderReconstructionUncertain() { return headerReconstructionUncertain; }
    public Status getStatus() { return status; }
    public UUID getAssignedEngineerId() { return assignedEngineerId; }
    public String getTriggerSummary() { return triggerSummary; }
    public String getEngineerNotes() { return engineerNotes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAssignedAt() { return assignedAt; }
    public Instant getReadyAt() { return readyAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public UUID getResolvedBy() { return resolvedBy; }
}
```

- [ ] **Step 4: Implement `HeldStatementEvent`**

```java
package com.finora.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** One entry in a held statement's history. {@code actorId} null means the system acted. */
@Entity
@Table(name = "held_statement_events")
public class HeldStatementEvent {

    @Id private UUID id = UUID.randomUUID();

    @Column(name = "held_statement_id", nullable = false) private UUID heldStatementId;
    @Column(name = "actor_id") private UUID actorId;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(name = "from_status") private String fromStatus;
    @Column(name = "to_status") private String toStatus;
    @Column(name = "notes") private String notes;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected HeldStatementEvent() {}

    public HeldStatementEvent(UUID heldStatementId, UUID actorId, String eventType,
                               String fromStatus, String toStatus, String notes) {
        this.heldStatementId = heldStatementId;
        this.actorId = actorId;
        this.eventType = eventType;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.notes = notes;
    }

    public UUID getId() { return id; }
    public UUID getHeldStatementId() { return heldStatementId; }
    public UUID getActorId() { return actorId; }
    public String getEventType() { return eventType; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getNotes() { return notes; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: Implement the repositories and the generator**

```java
// HeldStatementRepository.java
package com.finora.repository;

import com.finora.entity.HeldStatement;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface HeldStatementRepository extends JpaRepository<HeldStatement, UUID> {

    Optional<HeldStatement> findByHeldId(String heldId);

    Optional<HeldStatement> findByImportJobId(UUID importJobId);

    Page<HeldStatement> findByStatusIn(Collection<HeldStatement.Status> statuses, Pageable pageable);

    long countByStatusIn(Collection<HeldStatement.Status> statuses);

    /** The raw sequence value. Formatting is HeldStatementIdGenerator's job -- the same split
     *  StatementAnalysisRecorder uses for its SA- references. */
    @Query(value = "SELECT nextval('held_statement_reference_seq')", nativeQuery = true)
    long nextHeldSequence();
}
```

```java
// HeldStatementEventRepository.java
package com.finora.repository;

import com.finora.entity.HeldStatementEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HeldStatementEventRepository extends JpaRepository<HeldStatementEvent, UUID> {

    List<HeldStatementEvent> findByHeldStatementIdOrderByCreatedAtAsc(UUID heldStatementId);
}
```

```java
// HeldStatementIdGenerator.java
package com.finora.imports.trust;

import com.finora.repository.HeldStatementRepository;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Mints the reference operators actually quote -- {@code HLD-2026-000001}. Year plus a database
 * sequence, so it is readable, sortable and collision-free. Operators never see a raw UUID.
 */
@Component
public class HeldStatementIdGenerator {

    private final HeldStatementRepository repository;
    private final Clock clock;

    public HeldStatementIdGenerator(HeldStatementRepository repository) {
        this(repository, Clock.systemUTC());
    }

    HeldStatementIdGenerator(HeldStatementRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public String next() {
        long seq = repository.nextHeldSequence();
        return "HLD-" + LocalDate.now(clock.withZone(ZoneOffset.UTC)).getYear()
                + "-" + String.format("%06d", seq);
    }
}
```

- [ ] **Step 6: Run to verify pass**

```bash
cd backend && ./mvnw -Dtest=HeldStatementIdGeneratorTest,HeldStatementRepositoryIT -DfailIfNoTests=false test
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/java/com/finora/entity/HeldStatement.java \
        backend/src/main/java/com/finora/entity/HeldStatementEvent.java \
        backend/src/main/java/com/finora/repository/HeldStatementRepository.java \
        backend/src/main/java/com/finora/repository/HeldStatementEventRepository.java \
        backend/src/main/java/com/finora/imports/trust/HeldStatementIdGenerator.java \
        backend/src/test/java/com/finora/repository/HeldStatementRepositoryIT.java \
        backend/src/test/java/com/finora/imports/trust/HeldStatementIdGeneratorTest.java
git commit -m "feat(imports): held statement entities, repositories and Held ID generation"
```

---

### Task 5: Carry the statement period through to the worker

**Files:**
- Modify: `backend/src/main/java/com/finora/imports/jobs/StagedForJob.java`
- Test: `backend/src/test/java/com/finora/imports/jobs/StagedForJobTest.java`

**Interfaces:**
- Produces: `StagedForJob.statementPeriods()` → `List<LocalDate[]>`, one `{start, end}` per
  section, consumed by `TrustPredicate.evaluate` in Task 6.

- [ ] **Step 1: Write the failing test**

```java
package com.finora.imports.jobs;

import com.finora.dto.ImportDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class StagedForJobTest {

    /** A composite statement's sections each carry their own period, and the predicate has to see
     *  all of them -- one bad section is enough to hold the document. */
    @Test
    void carriesOnePeriodPerSection() {
        // Build a PdfStagingSessionResponse with two sections whose DetectedAccountInfo carry
        // different periods, using this codebase's existing constructors, then:
        // StagedForJob staged = StagedForJob.of(response);
        // assertThat(staged.statementPeriods()).hasSize(2);
    }

    @Test
    void aSectionWithNoPeriodYieldsNulls() {
        // A DetectedAccountInfo with null start/end must still produce an entry, so section
        // count and period count stay aligned.
    }
}
```

> Fill both test bodies by copying the exact `DetectedAccountInfo` / `StagedAccountSection` /
> `PdfStagingSessionResponse` constructor shapes from `ImportDto.java` (records at lines ~292 and
> ~446) — these have many positional fields and must not be guessed at.

- [ ] **Step 2: Run to verify failure**

```bash
cd backend && ./mvnw -Dtest=StagedForJobTest -DfailIfNoTests=false test
```

- [ ] **Step 3: Implement**

Extend the record and both factories:

```java
public record StagedForJob(UUID sessionId, int totalParsed, int stagedRows, String bankName,
                            List<ImportDto.VerificationReport> verificationReports,
                            List<java.time.LocalDate[]> statementPeriods) {

    /** One {@code {start, end}} pair per section, in section order. Either element may be null;
     *  a section that reported no period still contributes an entry so counts stay aligned. */
    private static List<java.time.LocalDate[]> periodsOf(DetectedAccountInfo detected) {
        return detected == null
                ? List.of(new java.time.LocalDate[]{null, null})
                : List.of(new java.time.LocalDate[]{
                        detected.statementPeriodStart(), detected.statementPeriodEnd()});
    }
```

Then thread `periodsOf(...)` through `of(StagingSessionResponse)` (single section) and
`of(PdfStagingSessionResponse)` (map over `sections()`, same as `verificationReports` already
does), passing the new argument to each `new StagedForJob(...)` call.

- [ ] **Step 4: Run to verify pass, then commit**

```bash
cd backend && ./mvnw -Dtest=StagedForJobTest -DfailIfNoTests=false test
git add backend/src/main/java/com/finora/imports/jobs/StagedForJob.java \
        backend/src/test/java/com/finora/imports/jobs/StagedForJobTest.java
git commit -m "feat(imports): carry statement periods through to the worker"
```

---

### Task 6: Hold instead of complete

**Files:**
- Create: `backend/src/main/java/com/finora/service/HeldStatementService.java`
- Modify: `backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java`
- Test: `backend/src/test/java/com/finora/imports/jobs/ImportJobWorkerTest.java`

**Interfaces:**
- Consumes: `TrustPredicate` (Task 3), `HeldStatement*` (Task 4), `StagedForJob.statementPeriods()`
  (Task 5).
- Produces: `HeldStatementService.createHold(ImportJob, StagedForJob, HoldDecision, String
  parserVersion)` → `HeldStatement`.

- [ ] **Step 1: Write the failing tests**

```java
/** The behaviour this whole plan exists for: an untrustworthy extraction must not reach
 *  COMPLETED on its own. */
@Test
void aHeldExtractionDoesNotComplete() throws IOException {
    when(importService.parseAndStageWithSession(any(), any(), any()))
            .thenReturn(stagedWithCountMismatch());

    worker.drainOnce();

    assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
    verify(heldStatementService).createHold(any(), any(), any(), any());
}

@Test
void aTrustworthyExtractionStillCompletes() throws IOException {
    when(importService.parseAndStageWithSession(any(), any(), any())).thenReturn(staged());

    worker.drainOnce();

    assertThat(job.getStatus()).isEqualTo(ImportJob.Status.COMPLETED);
    verify(heldStatementService, never()).createHold(any(), any(), any(), any());
}

/** Creating the hold record must never be able to fail the import itself. */
@Test
void aFailingHoldRecordDoesNotCorruptTheJob() throws IOException {
    when(importService.parseAndStageWithSession(any(), any(), any()))
            .thenReturn(stagedWithCountMismatch());
    doThrow(new IllegalStateException("db down"))
            .when(heldStatementService).createHold(any(), any(), any(), any());

    worker.drainOnce();

    assertThat(job.getStatus()).isEqualTo(ImportJob.Status.HELD_FOR_TRUST_REVIEW);
}
```

> `stagedWithCountMismatch()` — add a helper beside this class's existing `staged()` that returns
> a `StagedForJob` carrying one `SUMMARY_TOTALS` / `FAILED` finding with
> `details.suspectedCause = "ROW_GROUPING"`.

- [ ] **Step 2: Run to verify failure**

```bash
cd backend && ./mvnw -Dtest=ImportJobWorkerTest -DfailIfNoTests=false test
```

- [ ] **Step 3: Implement `HeldStatementService`**

```java
package com.finora.service;

import com.finora.entity.HeldStatement;
import com.finora.entity.HeldStatementEvent;
import com.finora.entity.ImportJob;
import com.finora.imports.jobs.StagedForJob;
import com.finora.imports.jobs.VerificationTelemetry;
import com.finora.imports.trust.HeldStatementIdGenerator;
import com.finora.imports.trust.HoldDecision;
import com.finora.repository.HeldStatementEventRepository;
import com.finora.repository.HeldStatementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the quarantine record for an import the trust predicate held.
 *
 * <p>{@code REQUIRES_NEW} so the hold's own row and its first audit event commit together and
 * independently of the job's transaction -- the worker treats a failure here as non-fatal, and a
 * half-written hold would be worse than none.
 */
@Service
public class HeldStatementService {

    private final HeldStatementRepository repository;
    private final HeldStatementEventRepository eventRepository;
    private final HeldStatementIdGenerator idGenerator;

    public HeldStatementService(HeldStatementRepository repository,
                                HeldStatementEventRepository eventRepository,
                                HeldStatementIdGenerator idGenerator) {
        this.repository = repository;
        this.eventRepository = eventRepository;
        this.idGenerator = idGenerator;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HeldStatement createHold(ImportJob job, StagedForJob staged, HoldDecision decision,
                                    String parserVersion) {
        VerificationTelemetry telemetry = VerificationTelemetry.from(staged.verificationReports());

        HeldStatement held = new HeldStatement(idGenerator.next(), job.getId(), job.getUserId(),
                job.getObjectKey(), decision.summary());
        held.recordSnapshot(parserVersion,
                telemetry.reliabilityStatus() == null ? null : telemetry.reliabilityStatus().name(),
                telemetry.textSource(),
                telemetry.isEmpty() ? null : telemetry.headerReconstructionUncertain());
        repository.save(held);

        eventRepository.save(new HeldStatementEvent(held.getId(), null, "HELD_CREATED",
                null, HeldStatement.Status.HELD.name(), decision.summary()));
        return held;
    }
}
```

- [ ] **Step 4: Wire the worker**

Add `TrustPredicate` usage and the `HeldStatementService` dependency (constructor injection, same
style as `verificationRecorder`), then replace the success-path block:

```java
            VerificationTelemetry telemetry = VerificationTelemetry.from(staged.verificationReports());
            HoldDecision decision = TrustPredicate.evaluate(staged.verificationReports(),
                    staged.statementPeriods(), LocalDate.now(java.time.ZoneOffset.UTC));

            // Created before the job transition so the job can carry the hold's id. Failure here
            // must not fail the import -- the same rule V62 states for merchant learning -- so a
            // failed hold falls back to holding WITHOUT a review record rather than completing,
            // which would silently release exactly the import this is meant to stop.
            UUID heldStatementId = null;
            if (decision.hold()) {
                try {
                    heldStatementId = heldStatementService
                            .createHold(job, staged, decision, parserVersion).getId();
                } catch (RuntimeException e) {
                    log.error("Could not create the hold record for import job {}; holding the "
                            + "import anyway, with no review record to work from", jobId, e);
                }
            }
            final UUID heldId = heldStatementId;

            jobStore.update(jobId, j -> {
                j.recordProgress(staged.totalParsed(), staged.stagedRows());
                if (decision.hold()) {
                    j.holdForTrustReview(staged.sessionId(), heldId, Instant.now());
                } else {
                    j.complete(staged.sessionId(), Instant.now());
                }
                j.recordVerificationTelemetry(
                        telemetry.reliabilityStatus(), telemetry.textSource(),
                        telemetry.isEmpty() ? null : telemetry.headerReconstructionUncertain(),
                        telemetry.isEmpty() ? null : telemetry.findingsCount(),
                        telemetry.isEmpty() ? null : telemetry.failedCount(),
                        telemetry.isEmpty() ? null : telemetry.warningCount(),
                        parserVersion);
                if (!decision.hold()) {
                    notifyIfPreviouslyHeld(j, staged.bankName());
                }
            });
            recordVerificationFindings(jobId, staged);
```

- [ ] **Step 5: Run to verify pass, then commit**

```bash
cd backend && ./mvnw -Dtest=ImportJobWorkerTest -DfailIfNoTests=false test
git add backend/src/main/java/com/finora/service/HeldStatementService.java \
        backend/src/main/java/com/finora/imports/jobs/ImportJobWorker.java \
        backend/src/test/java/com/finora/imports/jobs/ImportJobWorkerTest.java
git commit -m "feat(imports): quarantine an untrustworthy extraction instead of completing it"
```

---

### Task 7: Minimum admin resolution — approve or reject

**Files:**
- Create: `backend/src/main/java/com/finora/dto/HeldStatementDto.java`
- Create: `backend/src/main/java/com/finora/controller/AdminHeldStatementController.java`
- Modify: `backend/src/main/java/com/finora/service/HeldStatementService.java`
- Test: `backend/src/test/java/com/finora/controller/AdminHeldStatementControllerIT.java`

**Interfaces:**
- Produces: `GET /api/v1/admin/held-statements`, `GET /api/v1/admin/held-statements/{heldId}`,
  `POST .../{heldId}/approve`, `POST .../{heldId}/reject` — all gated on `TRUST_REVIEW_MANAGE`.

- [ ] **Step 1: Write the failing IT**

Model it on `AdminHeldImportControllerIT` exactly — same `TestRestTemplate` + `JwtService` +
`TestSessions` shape (copy that file's real `adminAuth()` helper rather than guessing its
signature). Assert three things:

```java
@Test
void listRequiresTheTrustReviewPermission() { /* unauthenticated call → 401 */ }

@Test
void approveReleasesTheJobAndMarksTheHoldImported() {
    // seed a HELD_FOR_TRUST_REVIEW job + HeldStatement, POST approve,
    // assert ImportJob.Status.COMPLETED and HeldStatement.Status.IMPORTED
}

@Test
void rejectFailsTheJobAndNeverImports() {
    // POST reject with a reason, assert ImportJob.Status.FAILED and HeldStatement.Status.REJECTED
}
```

- [ ] **Step 2: Run to verify failure, then implement the DTO, the two service methods and the
      controller**

`HeldStatementService.approve(UUID adminId, String heldId)`: load the hold and its job, call
`held.markImported(adminId, now)` and `job.complete(job.getImportSessionId(), now)`, write an
`IMPORTED` event, audit `TRUST_REVIEW_APPROVED`. Reject mirrors it with `held.reject(...)`,
`job.recordFailure(...)` (FAIL_FAST), a `REJECTED` event and `TRUST_REVIEW_REJECTED`. Both refuse
with 409 when the hold is already `IMPORTED` or `REJECTED`, naming the state — the same
convention `AdminHeldImportService.reprocess` uses.

The controller mirrors `AdminHeldImportController`: class-level
`@PreAuthorize("hasAuthority('TRUST_REVIEW_MANAGE')")`, `ApiResponse.ok(...)` envelopes,
`CurrentUser` for the acting admin.

- [ ] **Step 3: Run, then commit**

```bash
cd backend && ./mvnw -Dtest=AdminHeldStatementControllerIT -DfailIfNoTests=false test
git add backend/src/main/java/com/finora/dto/HeldStatementDto.java \
        backend/src/main/java/com/finora/controller/AdminHeldStatementController.java \
        backend/src/main/java/com/finora/service/HeldStatementService.java \
        backend/src/test/java/com/finora/controller/AdminHeldStatementControllerIT.java
git commit -m "feat(imports): admin approve and reject for held statements"
```

---

### Task 8: Full verification

- [ ] **Step 1: Clean verify**

```bash
cd backend && ./mvnw clean verify
```

Expected: `BUILD SUCCESS`, zero failures. Run this alone — never concurrently with another Maven
invocation against the same `target/`, which makes `clean` delete files the other process is using.

- [ ] **Step 2: Re-measure the corpus**

```bash
FINORA_CORPUS_DIR="$HOME/Downloads/Bank statement" ./mvnw -Dtest=CorpusTrustTelemetryCalibrationIT -DfailIfNoTests=false test
```

The distribution must be unchanged — this plan adds a gate, it does not change extraction. If any
verdict moved, something in the pipeline was altered unintentionally.

- [ ] **Step 3: Confirm the predicate's real-world firing rate**

Add a temporary assertion-free run over the corpus applying `TrustPredicate.evaluate` to each
statement, and record how many of the 29 would now be held. This is the number that tells you
whether the gate is safe to enable — a rate far above the ~1/27 NEEDS_ATTENTION baseline means a
condition is broader than intended.

- [ ] **Step 4: Commit and open the PR**

---

## Self-Review

**Spec coverage:** Brief Phase 0 → Task 1 (`held_statement_id`; the other Phase 0 columns and
`recordForJob` wiring already shipped in PR #820). Phase 1 → Tasks 1, 4. Phase 2 → Tasks 1, 4, 6.
Phase 3 → Task 4's six-state `Status`. Phase 4 → Task 3, with all three conditions and all eight
excluded signals covered by explicit tests. Phase 8 (minimum) → Task 7. Phases 5, 6, 7, 9, 10
are deliberately out of scope per the Scope Check and get their own plans.

**Placeholder scan:** the migration version is resolved by an executable command in Task 1 Step 1
(this repo forbids hardcoding one in advance). Task 5's test bodies and Task 7's IT point at the
exact existing files whose constructor shapes must be copied rather than guessed — those records
have many positional fields and inventing a signature would be worse than naming the source.

**Type consistency:** `HeldStatement.Status` is defined once in Task 4 and used with those exact
six names in Tasks 6 and 7. `HoldDecision` (`hold()`, `reasons()`, `summary()`) is defined in
Task 3 and consumed identically in Tasks 6 and 7. `StagedForJob.statementPeriods()` is added in
Task 5 and consumed in Task 6. `holdForTrustReview(sessionId, heldStatementId, now)` is defined in
Task 2 with the same three arguments used in Task 6.
