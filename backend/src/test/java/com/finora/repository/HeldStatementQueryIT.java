package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link HeldStatementRepository#findForAdmin} against real Postgres -- Plan 2 Task 2.
 *
 * <p>Split from {@code HeldStatementRepositoryIT}, which already covers the unfiltered queue and
 * the entity's own round trip, so this file stays about one thing: that each filter axis narrows
 * correctly and combines with the others by AND.
 */
class HeldStatementQueryIT extends AbstractIntegrationTest {

    @Autowired private HeldStatementRepository repository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EntityManager entityManager;

    private static final List<HeldStatement.Status> OPEN = List.of(
            HeldStatement.Status.HELD, HeldStatement.Status.ASSIGNED,
            HeldStatement.Status.INVESTIGATING, HeldStatement.Status.READY_FOR_IMPORT);

    private User user() {
        User u = new User();
        u.setEmail("held-query-" + UUID.randomUUID() + "@example.com");
        u.setPasswordHash("irrelevant-for-this-test");
        u.setFullName("Held Query Test");
        return userRepository.save(u);
    }

    /** A real row for {@code assigned_engineer_id}, distinct from the statement's own owner --
     *  that column is a foreign key to {@code users}, so a fabricated id fails the constraint. */
    private UUID engineer() {
        return user().getId();
    }

    private HeldStatement seed(String heldId, HeldStatement.Status status, String bankName) {
        User owner = user();
        ImportJob job = importJobRepository.save(new ImportJob(
                owner.getId(), "s.pdf", "h-" + UUID.randomUUID(), "objects/k1", "PDF"));
        HeldStatement held = new HeldStatement(heldId, job.getId(), owner.getId(),
                job.getObjectKey(), "Printed and parsed transaction count disagree");
        held.recordBank(bankName);
        if (status == HeldStatement.Status.ASSIGNED) {
            held.assign(engineer(), Instant.now());
        }
        return repository.save(held);
    }

    private void backdate(UUID id, Instant createdAt) {
        entityManager.createNativeQuery("UPDATE held_statements SET created_at = :createdAt WHERE id = :id")
                .setParameter("createdAt", Timestamp.from(createdAt))
                .setParameter("id", id)
                .executeUpdate();
    }

    /** Each filter axis narrows to just its own row; no filter still returns both -- the queue's
     *  existing unfiltered behaviour is unchanged by adding filters on top of it. */
    @Test
    @Transactional
    void filtersByStatusBankAgeAndEngineer() {
        seed("HLD-2026-100101", HeldStatement.Status.HELD, "HDFC Bank");
        HeldStatement old = seed("HLD-2026-100102", HeldStatement.Status.ASSIGNED, "ICICI Bank");
        backdate(old.getId(), Instant.now().minus(5, ChronoUnit.DAYS));
        entityManager.flush();
        entityManager.clear();

        var page = PageRequest.of(0, 25);

        assertThat(repository.findForAdmin(OPEN, null, null, null, null, page).getContent())
                .as("no filter returns both")
                .extracting(HeldStatement::getHeldId)
                .containsExactlyInAnyOrder("HLD-2026-100101", "HLD-2026-100102");

        assertThat(repository.findForAdmin(OPEN, HeldStatement.Status.HELD, null, null, null, page)
                .getContent())
                .extracting(HeldStatement::getHeldId)
                .containsExactly("HLD-2026-100101");

        assertThat(repository.findForAdmin(OPEN, null, "ICICI Bank", null, null, page)
                .getContent())
                .extracting(HeldStatement::getHeldId)
                .containsExactly("HLD-2026-100102");

        assertThat(repository.findForAdmin(OPEN, null, null, null, old.getAssignedEngineerId(), page)
                .getContent())
                .extracting(HeldStatement::getHeldId)
                .containsExactly("HLD-2026-100102");
    }

    /** "Older than", not "newer than": the cutoff selects what has been waiting longest, not what
     *  just arrived. */
    @Test
    @Transactional
    void ageFilterSelectsTheOldest() {
        seed("HLD-2026-100110", HeldStatement.Status.HELD, "HDFC Bank");
        HeldStatement old = seed("HLD-2026-100111", HeldStatement.Status.HELD, "HDFC Bank");
        backdate(old.getId(), Instant.now().minus(5, ChronoUnit.DAYS));
        entityManager.flush();
        entityManager.clear();

        Instant cutoff = Instant.now().minus(1, ChronoUnit.DAYS);
        var page = PageRequest.of(0, 25);

        assertThat(repository.findForAdmin(OPEN, null, null, cutoff, null, page).getContent())
                .extracting(HeldStatement::getHeldId)
                .containsExactly("HLD-2026-100111");
    }

    /** An unmatched filter has to filter to nothing -- a queue used to decide what reaches a
     *  ledger must never fall back to "everything" just because a filter matched no row. */
    @Test
    @Transactional
    void anUnmatchedFilterReturnsNothingRatherThanEverything() {
        seed("HLD-2026-100120", HeldStatement.Status.HELD, "HDFC Bank");

        var page = PageRequest.of(0, 25);

        assertThat(repository.findForAdmin(OPEN, null, "A Bank Nobody Uploaded From", null, null, page)
                .getContent())
                .isEmpty();
    }
}
