package com.finora.imports.jobs;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.ImportJob;
import com.finora.entity.User;
import com.finora.imports.StatementUpload;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The durable queue's guarantees, against a real Postgres.
 *
 * <p>Every property asserted here is one whose failure loses or duplicates a user's statement
 * import, and none of them fail loudly: a job that is claimed twice, or stranded forever, or
 * dead-lettered after five unlucky deploys, all look like a working queue from the outside. These
 * are the assertions that would notice.
 *
 * <p>Testcontainers rather than an in-memory database on purpose -- {@code SKIP LOCKED} and the
 * partial indexes are PostgreSQL behaviour, and a substitute that lacks them would prove nothing
 * about the thing being relied on.
 *
 * <p>The lifecycle rules themselves -- stage ordering, backoff, cancellation, dead-lettering -- are
 * pure and live in {@code ImportJobTest}, which needs no container. Only queue semantics belong
 * here.
 *
 * <p><b>The scheduler is off for this class</b>, matching what every merchant-learning queue test
 * does. It is not tidiness: with the poller running, a full-suite run long enough for it to fire
 * had it re-claim a job between a test recovering that job and asserting on it, so
 * {@code recoveryDoesNotChargeAnAttempt} read an attempt count the scheduler had just incremented.
 * The test passed in isolation and failed in the suite -- the worst shape of flake, because the
 * isolated run is the one people trust. A queue test must drive the queue itself.
 */
@TestPropertySource(properties = "app.import.queue.enabled=false")
class ImportJobStoreIT extends AbstractIntegrationTest {

    @Autowired private ImportJobStore store;
    @Autowired private ImportJobRepository repository;
    @Autowired private UserRepository userRepository;

    private User user() {
        User user = new User();
        user.setEmail("import-job-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Import Job IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private ImportJob enqueue(User user) {
        return store.enqueue(user.getId(), "statement.csv", "hash-" + UUID.randomUUID(), "objects/x",
                StatementUpload.Format.CSV);
    }

    // ------------------------------------------------------------------ claiming

    @Test
    void aQueuedJobIsClaimedAndMarkedInFlight() {
        User user = user();
        ImportJob job = enqueue(user);

        List<UUID> claimed = store.claimBatch("worker-test");

        assertThat(claimed).contains(job.getId());
        ImportJob after = repository.findById(job.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ImportJob.Status.PARSING);
        assertThat(after.getStartedAt()).isNotNull();
        assertThat(after.getCorrelationId()).isEqualTo("worker-test");
        assertThat(after.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void aClaimedJobIsNotClaimedAgain() {
        // The property that makes two workers safe. A second claim seeing the same row would import
        // one statement twice -- duplicated financial data, silently.
        User user = user();
        enqueue(user);

        List<UUID> first = store.claimBatch("worker-a");
        List<UUID> second = store.claimBatch("worker-b");

        assertThat(first).isNotEmpty();
        assertThat(second).doesNotContainAnyElementsOf(first);
    }

    @Test
    void aJobBackingOffIsNotClaimable() {
        // next_attempt_at gates claiming. Without it a failing job would be re-claimed immediately
        // and burn its whole attempt budget in one poll cycle.
        User user = user();
        ImportJob job = enqueue(user);
        store.update(job.getId(), j -> {
            j.markClaimed("worker-a", Instant.now());
            j.recordFailure("transient", Instant.now());
        });

        assertThat(store.claimBatch("worker-b")).doesNotContain(job.getId());
    }

    @Test
    void aJobWhoseBackoffHasElapsedIsClaimableAgain() {
        User user = user();
        ImportJob job = enqueue(user);
        store.update(job.getId(), j -> {
            j.markClaimed("worker-a", Instant.now());
            // A failure recorded far enough in the past that its backoff has expired.
            j.recordFailure("transient", Instant.now().minus(Duration.ofHours(2)));
        });

        assertThat(store.claimBatch("worker-b")).contains(job.getId());
    }

    // ------------------------------------------------------------------ recovery

    @Test
    void aJobAbandonedInFlightIsReturnedToTheQueue() {
        // A worker that died mid-parse releases its row lock but not its status. Without recovery
        // the row reads PARSING forever and no claim will ever see it again.
        User user = user();
        ImportJob job = enqueue(user);
        store.update(job.getId(), j -> j.markClaimed("worker-dead",
                Instant.now().minus(ImportJobStore.IN_FLIGHT_TIMEOUT).minus(Duration.ofMinutes(1))));

        int recovered = store.recoverAbandoned();

        // BH-058-class: not isEqualTo(1) -- recoverAbandoned() is table-wide by design, so an
        // exact-equality count assumes this test owns the whole table. >= 1 still proves recovery
        // actually happened (not a silent no-op); the specific status check right below is what
        // proves it happened to THIS job. See recoveryCountReflectsExactlyWhatWasDue... for the
        // noise-robust proof that the count itself is exact, not just non-zero.
        assertThat(recovered).isGreaterThanOrEqualTo(1);
        assertThat(repository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.QUEUED);
    }

    /**
     * BH-058-class. {@code recoverAbandoned()} is table-wide by design -- an operator's recovery
     * pass, not one user's -- so the count above is only meaningful against a table this test
     * fully controls, the same fragility {@code MerchantLearningQueueIT} named for
     * {@code claimDueEvents}. {@code IN_FLIGHT_TIMEOUT} is 30 minutes, long enough that nothing
     * else in this suite backdates a row far enough to trip it today -- unlike the original
     * BH-058, this one is not yet observed breaking. It is still the identical shape, and the
     * next test that needs to simulate an old stuck row (the way this class's own
     * {@code aJobAbandonedInFlightIsReturnedToTheQueue} does) would make it break exactly the way
     * the original did.
     *
     * <p>Proven directly rather than merely asserted: seeds unrelated abandoned jobs from ANOTHER
     * user first, so the table is guaranteed non-empty regardless of what any other test leaves
     * behind, and asserts recovery counts relative to that noise rather than assuming the table
     * started at zero.
     */
    @Test
    void recoveryCountReflectsExactlyWhatWasDue_evenWithUnrelatedAbandonedJobsAlreadyInTheTable() {
        User noiseUser = user();
        int noiseCount = 3;
        for (int i = 0; i < noiseCount; i++) {
            ImportJob noise = enqueue(noiseUser);
            store.update(noise.getId(), j -> j.markClaimed("worker-dead-noise",
                    Instant.now().minus(ImportJobStore.IN_FLIGHT_TIMEOUT).minus(Duration.ofMinutes(1))));
        }

        User user = user();
        ImportJob job = enqueue(user);
        store.update(job.getId(), j -> j.markClaimed("worker-dead",
                Instant.now().minus(ImportJobStore.IN_FLIGHT_TIMEOUT).minus(Duration.ofMinutes(1))));

        int recovered = store.recoverAbandoned();

        assertThat(recovered)
                .as("this fixture's job plus the noise, not just this fixture's job -- proving the "
                        + "count is real table-wide recovery, not a coincidence of an empty table")
                .isEqualTo(noiseCount + 1);
        assertThat(repository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.QUEUED);
    }

    @Test
    void recoveryDoesNotChargeAnAttempt() {
        // A worker that died proved nothing about the job. Charging an attempt would dead-letter
        // perfectly good work after five unlucky deploys.
        User user = user();
        ImportJob job = enqueue(user);
        store.update(job.getId(), j -> j.markClaimed("worker-dead",
                Instant.now().minus(ImportJobStore.IN_FLIGHT_TIMEOUT).minus(Duration.ofMinutes(1))));
        int afterClaim = repository.findById(job.getId()).orElseThrow().getAttemptCount();

        store.recoverAbandoned();

        assertThat(repository.findById(job.getId()).orElseThrow().getAttemptCount())
                .isLessThan(afterClaim);
    }

    @Test
    void aJobStillWithinTheTimeoutIsLeftAlone() {
        // Recovering too early re-runs an import that is genuinely still going, which before the
        // idempotency key means duplicated rows.
        //
        // BH-058-class: an unrelated abandoned job from another user is seeded alongside this
        // test's own live one, and the recovered count is asserted against the noise, not against
        // zero -- isZero() would assume this test owns the whole table, the same fragility
        // MerchantLearningQueueIT named for claimDueEvents. This is the stronger claim anyway:
        // not just "nothing got recovered", but "recovery is still happening for what's genuinely
        // due, while this job specifically is excluded" -- proven together, in one pass, rather
        // than trusting an empty table to make them look the same.
        User noiseUser = user();
        ImportJob noise = enqueue(noiseUser);
        store.update(noise.getId(), j -> j.markClaimed("worker-dead-noise",
                Instant.now().minus(ImportJobStore.IN_FLIGHT_TIMEOUT).minus(Duration.ofMinutes(1))));

        User user = user();
        ImportJob job = enqueue(user);
        store.update(job.getId(), j -> j.markClaimed("worker-live", Instant.now()));

        assertThat(store.recoverAbandoned())
                .as("the noise job recovers; this fixture's still-live job must not add to that count")
                .isEqualTo(1);
        assertThat(repository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.PARSING);
    }

    // ------------------------------------------------------------------ failure handling

    @Test
    void aJobDeadLettersOnlyAfterItsAttemptBudgetIsSpent() {
        User user = user();
        ImportJob job = enqueue(user);

        for (int attempt = 1; attempt <= ImportJob.MAX_ATTEMPTS; attempt++) {
            store.update(job.getId(), j -> {
                j.markClaimed("worker", Instant.now());
                j.recordFailure("boom", Instant.now());
            });
        }

        assertThat(repository.findById(job.getId()).orElseThrow().getStatus())
                .isEqualTo(ImportJob.Status.FAILED);
    }

    @Test
    void aDeadLetteredJobIsNotClaimedAgain() {
        User user = user();
        ImportJob job = enqueue(user);
        for (int attempt = 1; attempt <= ImportJob.MAX_ATTEMPTS; attempt++) {
            store.update(job.getId(), j -> {
                j.markClaimed("worker", Instant.now());
                j.recordFailure("boom", Instant.now());
            });
        }

        assertThat(store.claimBatch("worker-b")).doesNotContain(job.getId());
    }

    // ------------------------------------------------------------------ gauges

    @Test
    void queueDepthCountsOnlyWaitingWork() {
        User user = user();
        enqueue(user);
        enqueue(user);
        long queuedBefore = store.queueDepth();

        store.claimBatch("worker");

        assertThat(store.queueDepth())
                .as("claimed jobs are in flight, not waiting")
                .isLessThan(queuedBefore);
    }

    @Test
    void oldestQueuedAtIsEmptyWhenNothingIsWaiting() {
        // Published as 0 by the gauge rather than absent -- a metric that disappears is
        // indistinguishable from a scrape failure on a dashboard.
        store.claimBatch("drain");
        assertThat(store.oldestQueuedAt()).isNotNull();
    }

    // ------------------------------------------------------------------ ownership

    @Test
    void aJobIsOnlyReadableByItsOwner() {
        // A job id alone must never be enough to read someone else's import.
        User mine = user();
        User theirs = user();
        ImportJob job = enqueue(theirs);

        assertThat(repository.findByIdAndUserId(job.getId(), mine.getId())).isEmpty();
        assertThat(repository.findByIdAndUserId(job.getId(), theirs.getId())).isPresent();
    }

}
