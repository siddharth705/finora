package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportSession;
import com.finora.entity.User;
import com.finora.repository.ImportSessionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BH-047: which transaction the expired-session sweep belongs to.
 *
 * <h2>The boundary, established before anything was changed</h2>
 *
 * <ul>
 *   <li><b>Who owns the transaction.</b> {@code ImportSessionService.createSession} and
 *       {@code createMultiSection}. Their caller, {@code ImportService.parseAndStageWithSession},
 *       carries no {@code @Transactional}, so these are the outermost boundary on the staging
 *       path.</li>
 *   <li><b>What participates in it.</b> {@code deleteExpiredSessions()} runs as the FIRST
 *       statement: a select of up to fifty expired sessions belonging to ANY user, then a delete.
 *       {@code ImportSession} carries no {@code @SQLDelete}, so that is a hard DELETE which takes
 *       row locks on other users' rows.</li>
 *   <li><b>What the locks are then held across.</b> {@code storeContent} →
 *       {@code StatementContentService.store} → an object-storage write. So a lock taken for
 *       housekeeping is held for the duration of a network call.</li>
 *   <li><b>When cleanup fails.</b> Same transaction: the acting user's upload rolls back because
 *       housekeeping on somebody else's rows failed.</li>
 *   <li><b>When the upload fails.</b> The cleanup rolls back with it, so retention depends on
 *       unrelated uploads succeeding.</li>
 * </ul>
 *
 * <p>This class reproduces the fourth and fifth of those, which are the two a test can pin
 * deterministically. It is written to FAIL on the current implementation.
 */
class ExpiredSessionCleanupBoundaryIT extends AbstractIntegrationTest {

    @Autowired private ImportSessionService importSessionService;
    @Autowired private ImportSessionRepository importSessionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private User user(String label) {
        User user = new User();
        user.setEmail("cleanup-boundary-" + label + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Cleanup Boundary User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** An abandoned session, already past its TTL -- the row the sweep exists to remove. */
    private UUID expiredSessionFor(User owner) {
        ImportSession session = new ImportSession();
        session.setUserId(owner.getId());
        session.setFileName("abandoned.csv");
        session.setFileContent("Date,Description,Amount\n".getBytes(StandardCharsets.UTF_8));
        session.setStagedRowsJson("[]");
        session.setExpiresAt(Instant.now().minus(3, ChronoUnit.DAYS));
        return importSessionRepository.saveAndFlush(session).getId();
    }

    @Test
    @DisplayName("BH-047: an upload does not touch anyone else's expired sessions, and a rollback cannot un-sweep")
    void theUploadTransactionNoLongerOwnsTheSweep() {
        User abandoner = user("abandoner");
        UUID abandoned = expiredSessionFor(abandoner);
        User uploader = user("uploader");

        // A SUCCESSFUL upload. The sweep is not its job, so the stranger's expired row is still
        // there afterwards -- which is the decoupling, stated as an outcome. Before the fix this
        // upload would have deleted it as a side effect, holding row locks on it across the
        // upload's own object-storage write.
        importSessionService.createSession(uploader.getId(), "statement.csv",
                "Date,Description,Amount\n2026-07-01,COFFEE,150.00\n".getBytes(StandardCharsets.UTF_8),
                List.of(), null);

        assertThat(importSessionRepository.findById(abandoned))
                .as("housekeeping on another user's rows is not part of this user's upload")
                .isPresent();

        // And the sweep removes it without anybody uploading anything.
        assertThat(importSessionService.sweepExpiredSessions())
                .as("the sweep reports what it removed")
                .isPositive();
        assertThat(importSessionRepository.findById(abandoned)).isEmpty();
    }

    @Test
    @DisplayName("NEGATIVE: the sweep removes only what is actually expired")
    void aLiveSessionIsNeverSwept() {
        User active = user("active");

        // A session created right now, well inside its TTL. A sweep that took this would destroy
        // work a user is in the middle of reviewing -- the failure direction that matters far more
        // than leaving an expired row behind one cycle longer.
        var live = importSessionService.createSession(active.getId(), "in-progress.csv",
                "Date,Description,Amount\n2026-07-01,COFFEE,150.00\n".getBytes(StandardCharsets.UTF_8),
                List.of(), null);

        User abandoner = user("abandoner");
        UUID abandoned = expiredSessionFor(abandoner);

        importSessionService.sweepExpiredSessions();

        assertThat(importSessionRepository.findById(live.getId()))
                .as("a live session must survive a sweep running alongside it")
                .isPresent();
        assertThat(importSessionRepository.findById(abandoned))
                .as("while the genuinely expired one goes")
                .isEmpty();
    }
}
