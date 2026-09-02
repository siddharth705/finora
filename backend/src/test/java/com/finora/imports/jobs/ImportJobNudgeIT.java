package com.finora.imports.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.imports.StatementUpload;
import com.finora.entity.User;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The import queue's counterpart to {@code MerchantLearningNudgeIT}: the one place its asynchronous
 * machinery runs for real.
 *
 * <p>Everything else in this package drives {@code drainOnce()} directly with the queue switched
 * off, which keeps those tests deterministic but leaves the {@code afterCommit} → {@code @Async}
 * path unproven, and proves nothing about how much of the queue one nudge actually clears.
 */
@TestPropertySource(properties = {
        "app.import.queue.enabled=true",
        // Only the nudge may be responsible for what this asserts -- if the poller could run, it
        // would prove the opposite of the point.
        "app.import.queue.initial-delay-ms=3600000",
        "app.import.queue.poll-interval-ms=3600000"
})
class ImportJobNudgeIT extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired private ImportJobWorker worker;
    @Autowired private ImportJobStore store;
    @Autowired private ImportJobRepository jobRepository;
    @Autowired private UserRepository userRepository;

    /**
     * A nudge must drain the queue it was handed, not just the first batch of it.
     *
     * <p>{@link ImportJobStore#BATCH_SIZE} is 10 — small, because a pass is up to ten whole
     * statement parses. A pass that comes back full is evidence more is waiting, and stopping
     * there left the remainder for the next poll even though a worker had just been woken and the
     * queue was demonstrably not empty.
     *
     * <p>Seeded through {@link ImportJobStore#enqueue} rather than {@code ImportJobService.accept},
     * deliberately: {@code accept} registers its own afterCommit nudge per job, so seeding through
     * it would fire a nudge per job and prove nothing about what a single one does. The store
     * writes the row without waking anybody.
     *
     * <p>Asserts on attempt counts, not on success: these jobs point at object keys that do not
     * exist, so every one of them fails. That is the point — what is being proven is that the
     * worker <em>reached</em> all of them in one nudge, and a failure is as good a proof of reach
     * as a completion, without needing to stage twelve real statements.
     */
    @Test
    void oneNudgeDrainsABacklogLargerThanASingleBatch() {
        User user = userRepository.save(newUser());
        int backlog = ImportJobStore.BATCH_SIZE + 2;

        List<UUID> jobIds = new ArrayList<>();
        for (int i = 0; i < backlog; i++) {
            jobIds.add(store.enqueue(user.getId(), "statement-" + i + ".csv",
                    "hash-" + UUID.randomUUID(), "objects/does-not-exist-" + i,
                    StatementUpload.Format.CSV).getId());
        }

        worker.nudge();

        assertThat(everyJobAttemptedWithin(TIMEOUT, jobIds))
                .as("one nudge should reach past the first batch of %d", ImportJobStore.BATCH_SIZE)
                .isTrue();
    }

    /** True once every seeded job has been attempted at least once, or false if the budget runs
     *  out. Polls rather than sleeping a fixed interval — the nudge normally lands in
     *  milliseconds. */
    private boolean everyJobAttemptedWithin(Duration timeout, List<UUID> jobIds) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (jobIds.stream().allMatch(this::hasBeenAttempted)) {
                return true;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean hasBeenAttempted(UUID jobId) {
        ImportJob job = jobRepository.findById(jobId).orElseThrow();
        return job.getAttemptCount() >= 1;
    }

    private User newUser() {
        User user = new User();
        user.setEmail("import-nudge-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Nudge IT User");
        user.setPhoneVerified(true);
        return user;
    }
}
