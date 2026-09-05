package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.HeldStatementEvent;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The parts of the hold that only PostgreSQL can prove: that the mapping matches V144, that the
 * queries return what the operator queue needs, and that the sequence behind the Held ID issues
 * distinct values.
 *
 * <p>The lifecycle rules themselves are asserted in {@code HeldStatementTest}, in memory.
 */
class HeldStatementRepositoryIT extends AbstractIntegrationTest {

    @Autowired private HeldStatementRepository repository;
    @Autowired private HeldStatementEventRepository eventRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;

    private static final List<HeldStatement.Status> OPEN = List.of(
            HeldStatement.Status.HELD, HeldStatement.Status.ASSIGNED,
            HeldStatement.Status.INVESTIGATING, HeldStatement.Status.READY_FOR_IMPORT);

    private User user() {
        User u = new User();
        u.setEmail("held-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Held Test");
        return userRepository.save(u);
    }

    /**
     * A real row, not a random UUID.
     *
     * <p>{@code assigned_engineer_id}, {@code resolved_by} and {@code actor_id} are all real
     * foreign keys to {@code users} (ON DELETE SET NULL, so an admin's deletion never erases the
     * record of what they decided). A fabricated id fails the constraint, which is the constraint
     * doing its job.
     */
    private UUID staff() {
        return user().getId();
    }

    private HeldStatement seed(String heldId) {
        User owner = user();
        ImportJob job = importJobRepository.save(new ImportJob(
                owner.getId(), "s.pdf", "h-" + UUID.randomUUID(), "objects/k1", "PDF"));
        return repository.save(new HeldStatement(heldId, job.getId(), owner.getId(),
                job.getObjectKey(), "Printed and parsed transaction count disagree"));
    }

    @Test
    void findsByHeldIdAndImportJob() {
        HeldStatement held = seed("HLD-2026-000001");

        assertThat(repository.findByHeldId("HLD-2026-000001")).isPresent();
        assertThat(repository.findByImportJobId(held.getImportJobId())).isPresent();
        assertThat(repository.findByHeldId("HLD-2026-999999")).isEmpty();
    }

    @Test
    void openQueueExcludesResolvedStatements() {
        seed("HLD-2026-000002");
        HeldStatement resolved = seed("HLD-2026-000003");
        resolved.reject(staff(), Instant.now());
        repository.save(resolved);

        List<HeldStatement> open =
                repository.findByStatusIn(OPEN, PageRequest.of(0, 25)).getContent();

        assertThat(open).extracting(HeldStatement::getHeldId)
                .contains("HLD-2026-000002")
                .doesNotContain("HLD-2026-000003");
    }

    @Test
    void countsOnlyTheOpenQueue() {
        seed("HLD-2026-000010");
        HeldStatement resolved = seed("HLD-2026-000011");
        resolved.markImported(staff(), Instant.now());
        repository.save(resolved);

        assertThat(repository.countByStatusIn(OPEN)).isEqualTo(1);
    }

    /**
     * The snapshot and every lifecycle field survive a round trip.
     *
     * <p>This is what actually proves the entity matches V144: a column the mapping got wrong
     * fails here rather than the first time an operator opens a hold in production.
     */
    @Test
    void theWholeRowSurvivesARoundTrip() {
        HeldStatement held = seed("HLD-2026-000020");
        UUID engineer = staff();
        Instant now = Instant.now();
        held.recordSnapshot("parser-sha", "NEEDS_ATTENTION", "OCR", true, List.of("COUNT_MISMATCH"));
        held.assign(engineer, now);
        held.addNotes("Second section's rows never reached the ledger.");
        repository.saveAndFlush(held);

        HeldStatement reloaded = repository.findByHeldId("HLD-2026-000020").orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(HeldStatement.Status.ASSIGNED);
        assertThat(reloaded.getAssignedEngineerId()).isEqualTo(engineer);
        assertThat(reloaded.getParserVersion()).isEqualTo("parser-sha");
        assertThat(reloaded.getReliabilityStatus()).isEqualTo("NEEDS_ATTENTION");
        assertThat(reloaded.getTextSource()).isEqualTo("OCR");
        assertThat(reloaded.getHeaderReconstructionUncertain()).isTrue();
        assertThat(reloaded.getHoldReasonCategories()).containsExactly("COUNT_MISMATCH");
        assertThat(reloaded.getEngineerNotes()).contains("never reached the ledger");
        assertThat(reloaded.getTriggerSummary()).contains("count");
        assertThat(reloaded.getStatementObjectKey()).isEqualTo("objects/k1");
        assertThat(reloaded.getCreatedAt()).isNotNull();
    }

    /**
     * One import may be held once. The unique constraint is what stops a retry -- or two workers
     * racing the same job -- from opening a second review for the same statement.
     *
     * <p>Asserts the specific constraint, not merely "something threw": a NOT NULL violation from
     * an unrelated column would satisfy a bare {@code assertThrows} and leave this passing while
     * testing nothing.
     */
    @Test
    void anImportCanOnlyBeHeldOnce() {
        HeldStatement first = seed("HLD-2026-000030");

        Throwable thrown = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () ->
                repository.saveAndFlush(new HeldStatement("HLD-2026-000031",
                        first.getImportJobId(), first.getUserId(), "objects/k1", "again")));

        assertThat(org.assertj.core.util.Throwables.getRootCause(thrown))
                .hasMessageContaining("held_statements_import_job_id_key");
    }

    @Test
    void eventsComeBackOldestFirst() {
        HeldStatement held = seed("HLD-2026-000040");
        UUID actor = staff();

        eventRepository.save(new HeldStatementEvent(
                held.getId(), null, "HELD_CREATED", null, "HELD", "counts disagree"));
        eventRepository.save(new HeldStatementEvent(
                held.getId(), actor, "ASSIGNED", "HELD", "ASSIGNED", null));

        List<HeldStatementEvent> events =
                eventRepository.findByHeldStatementIdOrderByCreatedAtAsc(held.getId());

        assertThat(events).extracting(HeldStatementEvent::getEventType)
                .containsExactly("HELD_CREATED", "ASSIGNED");
        assertThat(events.getFirst().getActorId())
                .as("a null actor is the system acting, and must round-trip as null")
                .isNull();
    }

    /** V150: the bank name is a snapshot on the row itself, not something the repository has to
     *  join for -- see the column's own comment for why a join to {@code import_sessions} would be
     *  wrong. */
    @Test
    void theBankNameIsSnapshottedOnTheHold() {
        HeldStatement held = seed("HLD-2026-000050");
        held.recordBank("HDFC Bank");
        repository.saveAndFlush(held);

        assertThat(repository.findByHeldId("HLD-2026-000050").orElseThrow().getBankName())
                .isEqualTo("HDFC Bank");
    }

    /** The sequence is what makes Held IDs unique; two calls must never agree. */
    @Test
    void theHeldSequenceIssuesDistinctValues() {
        assertThat(repository.nextHeldSequence())
                .isNotEqualTo(repository.nextHeldSequence());
    }
}
