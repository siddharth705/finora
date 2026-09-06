package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A statement held for trust review must not lose its staged rows to the session sweep.
 *
 * <h2>The bug this pins</h2>
 *
 * <p>{@code ImportSession.expiresAt} is set once, to {@code now + 48h}, and never extended. The
 * sweep selected purely on that timestamp, with no knowledge of import jobs at all -- so a hold
 * that outlived the TTL had its session deleted underneath it.
 *
 * <p>What made that quietly serious rather than merely untidy: approving the hold marks the job
 * COMPLETED pointing at {@code importSessionId} and notifies the user their statement is ready.
 * With the session gone, that promise leads to an empty review screen, and the rows the reviewer
 * actually approved no longer exist anywhere.
 *
 * <p>The asymmetry is the tell. {@code StatementStorageSweepService} was deliberately built so a
 * held job's stored PDF survives -- that is the whole reason HELD_FOR_TRUST_REVIEW is terminal but
 * absent from {@code IMPORT_JOB_EXCLUDED_STATUSES}. Nothing gave the staged rows the same
 * protection, so a reviewer could still open the document while the user got nothing.
 *
 * <p>48 hours is not a generous window for this. A hold waiting on an engineer to fix a parser and
 * re-run it -- the workflow this feature exists to enable -- routinely takes longer.
 */
class HeldSessionSurvivesCleanupIT extends AbstractIntegrationTest {

    @Autowired private ImportSessionService importSessionService;
    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private ImportJobRepository importJobRepository;
    @Autowired private UserRepository userRepository;

    private User user() {
        User user = new User();
        user.setEmail("held-session-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Held Session User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** A session whose TTL has already elapsed -- the state a long review leaves behind. */
    private ImportSession expiredSession(User owner) {
        ImportSession session = importSessionService.createSession(
                owner.getId(), "statement.pdf", "irrelevant".getBytes(), List.of(), null);
        session.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        return importSessionRepository.save(session);
    }

    private ImportJob jobHolding(User owner, UUID sessionId) {
        ImportJob job = new ImportJob(owner.getId(), "statement.pdf",
                "hash-" + UUID.randomUUID(), "objects/key-" + UUID.randomUUID(), "PDF");
        job.markClaimed("worker", Instant.now());
        job.holdForTrustReview(sessionId, null, Instant.now());
        return importJobRepository.save(job);
    }

    @Test
    void aHeldStatementsStagedRowsSurviveTheSweep() {
        User owner = user();
        ImportSession session = expiredSession(owner);
        jobHolding(owner, session.getId());

        importSessionService.sweepExpiredSessions();

        assertThat(importSessionRepository.findById(session.getId()))
                .as("the rows a reviewer is being asked to judge, and that approving hands to the "
                        + "user, must still exist when the review finishes")
                .isPresent();
    }

    /**
     * The control, and the reason this fix is not simply "stop deleting things".
     *
     * <p>Retention is the point of the sweep: these rows hold real statement content, and an
     * expired session nothing is waiting on must still be removed on schedule.
     */
    @Test
    void anExpiredSessionNobodyIsWaitingOnIsStillSwept() {
        User owner = user();
        ImportSession session = expiredSession(owner);

        importSessionService.sweepExpiredSessions();

        assertThat(importSessionRepository.findById(session.getId()))
                .as("retention still applies to everything that is not under review")
                .isEmpty();
    }

    /**
     * Releasing a hold restarts the clock, so the user gets a usable window.
     *
     * <p>Without this the fix above would only move the broken promise. The exemption lifts the
     * instant the job leaves HELD_FOR_TRUST_REVIEW, and {@code expiresAt} is still whatever staging
     * set it to -- long elapsed for any review worth holding for -- so approving would notify the
     * user their statement was ready and the very next sweep, up to fifteen minutes later, would
     * delete it.
     */
    @Test
    void releasingAHoldGivesTheUserAFullWindowAgain() {
        User owner = user();
        ImportSession session = expiredSession(owner);
        jobHolding(owner, session.getId());

        importSessionService.renewExpiry(session.getId());

        assertThat(importSessionRepository.findById(session.getId()).orElseThrow().getExpiresAt())
                .as("the wait was ours, so the user's window starts when they are told")
                .isAfter(Instant.now());

        importSessionService.sweepExpiredSessions();
        assertThat(importSessionRepository.findById(session.getId())).isPresent();
    }

    /** A hold that has been resolved is no longer protected -- the exemption lasts exactly as long
     *  as the review does, rather than pinning the row forever. Renewal, not the exemption, is what
     *  keeps a released hold's rows alive. */
    @Test
    void aResolvedHoldsSessionBecomesSweepableAgain() {
        User owner = user();
        ImportSession session = expiredSession(owner);
        ImportJob job = jobHolding(owner, session.getId());
        job.releaseAfterTrustReview(Instant.now());
        importJobRepository.save(job);

        importSessionService.sweepExpiredSessions();

        assertThat(importSessionRepository.findById(session.getId()))
                .as("once released, this is an ordinary completed import and retention resumes")
                .isEmpty();
    }
}
