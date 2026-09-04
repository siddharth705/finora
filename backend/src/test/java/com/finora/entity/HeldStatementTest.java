package com.finora.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hold's own lifecycle rules, which are pure and need no database.
 *
 * <p>Split from {@code HeldStatementRepositoryIT} for the same reason {@code ImportJobTest} is
 * split from {@code ImportJobStoreIT}: these assert a state machine decided entirely in memory,
 * and running them against Testcontainers would cost a container start to test bookkeeping.
 */
class HeldStatementTest {

    private static final Instant NOW = Instant.parse("2026-09-03T10:00:00Z");

    private HeldStatement held() {
        return new HeldStatement("HLD-2026-000001", UUID.randomUUID(), UUID.randomUUID(),
                "statements/ab/cd/abcd.bin", "Printed and parsed transaction count disagree");
    }

    @Test
    void startsHeldAndUnassigned() {
        HeldStatement held = held();

        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.HELD);
        assertThat(held.getAssignedEngineerId()).isNull();
        assertThat(held.getResolvedAt()).isNull();
        assertThat(held.getTriggerSummary()).contains("count");
    }

    @Test
    void lifecycleTransitionsRecordTheirTimestamps() {
        HeldStatement held = held();
        UUID engineer = UUID.randomUUID();
        UUID admin = UUID.randomUUID();

        held.assign(engineer, NOW);
        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.ASSIGNED);
        assertThat(held.getAssignedEngineerId()).isEqualTo(engineer);
        assertThat(held.getAssignedAt()).isEqualTo(NOW);

        held.startInvestigation();
        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.INVESTIGATING);

        held.markReadyForImport(NOW);
        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.READY_FOR_IMPORT);
        assertThat(held.getReadyAt()).isEqualTo(NOW);

        held.markImported(admin, NOW);
        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.IMPORTED);
        assertThat(held.getResolvedBy()).isEqualTo(admin);
        assertThat(held.getResolvedAt()).isEqualTo(NOW);
    }

    /**
     * An engineer's investigation notes must survive the admin's decision.
     *
     * <p>There is exactly one notes column, so a rejection reason written into it would overwrite
     * the findings the rejection was based on -- in a workflow whose entire point is that somebody
     * investigated first. The reason belongs in {@code held_statement_events}, which exists to
     * record who did what and why; this row keeps the engineer's own words.
     */
    @Test
    void rejectingDoesNotDestroyTheEngineersNotes() {
        HeldStatement held = held();
        held.assign(UUID.randomUUID(), NOW);
        held.addNotes("Section 2's closing balance does not match the printed summary.");

        held.reject(UUID.randomUUID(), NOW);

        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.REJECTED);
        assertThat(held.getEngineerNotes())
                .as("the findings the rejection was based on")
                .contains("closing balance");
    }

    // ------------------------------------------------------------------ terminal-state guards

    /**
     * IMPORTED and REJECTED are resolutions, and a resolution is final.
     *
     * <p>Without this, a double-clicked approve button or a retried request whose response was
     * lost would re-resolve a hold: overwriting {@code resolvedBy} and {@code resolvedAt} with a
     * second admin's, and -- far worse for IMPORTED -- signalling a second import of a statement
     * that already reached the user's ledger. Same shape of guard, and the same reasoning, as
     * {@link ImportJob#complete}'s refusal to complete a cancelled job.
     */
    @Test
    void anImportedHoldCannotBeResolvedAgain() {
        HeldStatement held = held();
        UUID firstAdmin = UUID.randomUUID();
        held.markImported(firstAdmin, NOW);

        assertThatThrownBy(() -> held.markImported(UUID.randomUUID(), NOW.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> held.reject(UUID.randomUUID(), NOW.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(held.getResolvedBy()).isEqualTo(firstAdmin);
        assertThat(held.getResolvedAt()).isEqualTo(NOW);
    }

    @Test
    void aRejectedHoldCannotBeImportedAfterwards() {
        HeldStatement held = held();
        held.reject(UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> held.markImported(UUID.randomUUID(), NOW.plusSeconds(60)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.REJECTED);
    }

    /** A resolved hold cannot be dragged back into the working queue either. */
    @Test
    void aResolvedHoldCannotBeReassignedOrReopened() {
        HeldStatement held = held();
        held.markImported(UUID.randomUUID(), NOW);

        assertThatThrownBy(() -> held.assign(UUID.randomUUID(), NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(held::startInvestigation).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> held.markReadyForImport(NOW))
                .isInstanceOf(IllegalStateException.class);
    }

    /**
     * Reassignment before resolution is legitimate and must keep working -- the first engineer
     * goes on leave, or the hold turns out to need someone else.
     */
    @Test
    void anUnresolvedHoldCanBeReassigned() {
        HeldStatement held = held();
        held.assign(UUID.randomUUID(), NOW);
        UUID second = UUID.randomUUID();

        held.assign(second, NOW.plusSeconds(60));

        assertThat(held.getAssignedEngineerId()).isEqualTo(second);
        assertThat(held.getAssignedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    /**
     * Approving straight from HELD is allowed on purpose.
     *
     * <p>The brief's flow is admin -> engineer -> admin, but not every hold needs an engineer: an
     * operator who looks at the statement and can see the extraction is fine should be able to
     * release it. The guard is on resolving twice, not on the path taken to get there.
     */
    @Test
    void aHoldCanBeResolvedWithoutEverBeingAssigned() {
        HeldStatement held = held();

        held.markImported(UUID.randomUUID(), NOW);

        assertThat(held.getStatus()).isEqualTo(HeldStatement.Status.IMPORTED);
    }

    @Test
    void theSnapshotIsRecordedAsGiven() {
        HeldStatement held = held();

        held.recordSnapshot("abc123", "NEEDS_ATTENTION", "OCR", true);

        assertThat(held.getParserVersion()).isEqualTo("abc123");
        assertThat(held.getReliabilityStatus()).isEqualTo("NEEDS_ATTENTION");
        assertThat(held.getTextSource()).isEqualTo("OCR");
        assertThat(held.getHeaderReconstructionUncertain()).isTrue();
    }

    @Test
    void theBankIsRecordedAsGiven() {
        HeldStatement held = held();

        held.recordBank("HDFC Bank");

        assertThat(held.getBankName()).isEqualTo("HDFC Bank");
    }

    @Test
    void aMissingBankStaysNull() {
        HeldStatement held = held();

        held.recordBank(null);

        assertThat(held.getBankName()).isNull();
    }
}
