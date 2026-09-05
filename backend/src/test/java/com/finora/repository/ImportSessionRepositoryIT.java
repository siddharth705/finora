package com.finora.repository;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.HeldStatement;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ImportSessionRepository.claimForConfirmation} -- the native SQL half of the fix for the
 * TOCTOU window {@code ImportSessionService.claimForConfirmation}'s own doc comment describes: a
 * hold that commits between a plain read and this atomic UPDATE used to slip through, because the
 * UPDATE's own {@code WHERE} clause only checked {@code status = 'STAGED'}, nothing about holds.
 *
 * <p>Deliberately against a real Postgres instance, not mocks: the fix is a hand-written native
 * {@code @Query} with a {@code JOIN} and a {@code NOT EXISTS} subquery across three tables, and a
 * mock cannot catch a wrong column name, a wrong join condition, or invalid SQL the way a real
 * database does. {@link ImportSessionServiceTest} already covers the service-layer half (the
 * re-check that tells "held" apart from "genuinely already confirmed" when this returns 0 rows).
 */
class ImportSessionRepositoryIT extends AbstractIntegrationTest {

    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private HeldStatementRepository heldStatementRepository;
    @Autowired private UserRepository userRepository;

    private User user() {
        User user = new User();
        user.setEmail("import-session-repository-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Session Repository IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ImportSession stagedSession(UUID ownerId) {
        ImportSession session = new ImportSession();
        session.setUserId(ownerId);
        session.setFileName("statement.csv");
        session.setFileContent(new byte[] {1, 2, 3});
        session.setStagedRowsJson("[]");
        session.setDetectedAccountJson("{}");
        session.setExpiresAt(Instant.now().plusSeconds(600));
        session.setStatus(ImportSession.STATUS_STAGED);
        return importSessionRepository.save(session);
    }

    /** Persisted immediately, not just constructed -- {@code held_statements.import_job_id} has a
     *  real FK to {@code import_jobs(id)} (V144), so a {@link HeldStatement} referencing this job's
     *  id needs the row to already exist, the same ordering {@code HeldStatementService.openHold}
     *  itself follows (job persisted well before the hold that references it). */
    private ImportJob jobFor(UUID ownerId, UUID sessionId) {
        ImportJob job = new ImportJob(ownerId, "statement.csv", "hash-" + sessionId, "objects/key-" + sessionId, "CSV");
        job.markClaimed("worker", Instant.now());
        return importJobRepository.save(job);
    }

    private HeldStatement heldStatementFor(ImportJob job, UUID ownerId, HeldStatement.Status status) {
        HeldStatement held = new HeldStatement("IT-" + System.nanoTime(), job.getId(), ownerId,
                job.getObjectKey(), "test trigger summary");
        held = heldStatementRepository.save(held);
        if (status == HeldStatement.Status.REJECTED) {
            held.reject(ownerId, Instant.now());
            held = heldStatementRepository.save(held);
        } else if (status == HeldStatement.Status.IMPORTED) {
            held.markImported(ownerId, Instant.now(), false);
            held = heldStatementRepository.save(held);
        }
        return held; // default (no branch taken) is Status.HELD
    }

    @Test
    @Transactional
    void claimForConfirmation_confirmsAnOrdinarySession_withNoHoldAtAll() {
        User owner = user();
        ImportSession session = stagedSession(owner.getId());

        int updated = importSessionRepository.claimForConfirmation(session.getId());

        assertThat(updated).isEqualTo(1);
        assertThat(importSessionRepository.findById(session.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportSession.STATUS_CONFIRMED);
    }

    @Test
    @Transactional
    void claimForConfirmation_refusesASessionWithAnOpenHold() {
        User owner = user();
        ImportSession session = stagedSession(owner.getId());
        ImportJob job = jobFor(owner.getId(), session.getId());
        HeldStatement held = heldStatementFor(job, owner.getId(), HeldStatement.Status.HELD);
        job.holdForTrustReview(session.getId(), held.getId(), Instant.now());
        importJobRepository.save(job);

        int updated = importSessionRepository.claimForConfirmation(session.getId());

        assertThat(updated).isEqualTo(0);
        assertThat(importSessionRepository.findById(session.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportSession.STATUS_STAGED);
    }

    /** The bug the {@code NOT EXISTS} clause fixes: a REJECTED hold moves the job to plain FAILED
     *  (not HELD_FOR_TRUST_REVIEW), so a status-only check on {@code import_jobs} would have missed
     *  it -- only the {@code held_statements} row itself still says "not IMPORTED". */
    @Test
    @Transactional
    void claimForConfirmation_refusesASessionWhoseHoldWasRejected() {
        User owner = user();
        ImportSession session = stagedSession(owner.getId());
        ImportJob job = jobFor(owner.getId(), session.getId());
        HeldStatement held = heldStatementFor(job, owner.getId(), HeldStatement.Status.REJECTED);
        job.holdForTrustReview(session.getId(), held.getId(), Instant.now());
        job.rejectAfterTrustReview("IMPORT_TRUST_REVIEW_REJECTED", Instant.now());
        importJobRepository.save(job);

        int updated = importSessionRepository.claimForConfirmation(session.getId());

        assertThat(updated).isEqualTo(0);
    }

    /** The mirror case: an APPROVED hold (status IMPORTED) is the one outcome that must NOT block
     *  confirmation -- this is the intended release the whole feature exists to allow. */
    @Test
    @Transactional
    void claimForConfirmation_confirmsASessionWhoseHoldWasApproved() {
        User owner = user();
        ImportSession session = stagedSession(owner.getId());
        ImportJob job = jobFor(owner.getId(), session.getId());
        HeldStatement held = heldStatementFor(job, owner.getId(), HeldStatement.Status.IMPORTED);
        job.holdForTrustReview(session.getId(), held.getId(), Instant.now());
        job.releaseAfterTrustReview(Instant.now());
        importJobRepository.save(job);

        int updated = importSessionRepository.claimForConfirmation(session.getId());

        assertThat(updated).isEqualTo(1);
    }
}
