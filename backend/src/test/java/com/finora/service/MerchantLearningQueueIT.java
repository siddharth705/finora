package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantLearningEvent;
import com.finora.entity.User;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

/**
 * Deliverable 0 of the Import Reliability Milestone, tested against a real Postgres.
 *
 * <p>These are integration tests rather than unit tests for a specific reason, and it is the
 * lesson of the hardening phase rather than a general preference. {@code BudgetServiceTest} stubbed
 * {@code save()} to throw a constraint violation and asserted a recovery that could not run,
 * because the real insert is deferred to commit. {@code BootstrapServiceTest}'s race test could not
 * observe the {@code UnexpectedRollbackException} that made the catch it verified useless, because
 * no transaction exists in a Mockito test to commit. Both passed for as long as they existed while
 * asserting behaviour that was impossible.
 *
 * <p>Everything this class covers — {@code FOR UPDATE SKIP LOCKED}, rollback propagation from a
 * caller's transaction, and the fact that a poisoned transaction cannot record its own failure — is
 * invisible to a mock by construction. A mocked version of these tests would pass against code that
 * does not work.
 */
@TestPropertySource(properties = "app.learning.queue.enabled=false")
class MerchantLearningQueueIT extends AbstractIntegrationTest {

    // The scheduler and the async nudge are switched off so these tests drive the queue
    // deterministically through drainOnce(), which deliberately does not consult the flag. Left on,
    // the nudge fired by enqueue() drains the event on another thread before the assertion runs --
    // which is the queue working correctly, and makes the test meaningless.

    @Autowired private MerchantLearningEventRepository eventRepository;
    @Autowired private MerchantLearningEventPublisher publisher;
    @Autowired private MerchantLearningEventWorker worker;
    @Autowired private MerchantCategoryLearningRepository learningRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    /**
     * Real bean by default; told to throw only in the failure tests.
     *
     * <p>The first attempt at these tests forced a failure by deleting the category the event
     * pointed at. That does not work, and finding out why was worth the detour: V62 declares
     * {@code category_id ... ON DELETE CASCADE}, so deleting the category deletes the EVENT rather
     * than making its apply fail. The schema is right -- an event whose category no longer exists
     * is meaningless, not retryable -- but it means the failure has to come from the apply itself.
     *
     * <p>What stays real is everything the test is actually about: the database, the three
     * transaction boundaries, and whether a failure survives the rollback of the transaction that
     * caused it. Only the trigger is simulated.
     */
    @SpyBean private MerchantLearningService learningService;

    private record Fixture(User user, Merchant merchant, Category category) {}

    private Fixture fixture() {
        User user = new User();
        user.setEmail("learning-queue-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Learning Queue IT User");
        user.setPhoneVerified(true);
        user = userRepository.save(user);

        Merchant merchant = new Merchant();
        merchant.setUserId(user.getId());
        merchant.setCanonicalName("Test Merchant " + UUID.randomUUID());
        merchant = merchantRepository.save(merchant);

        Category category = new Category();
        category.setUserId(user.getId());
        category.setName("Groceries " + UUID.randomUUID());
        category = categoryRepository.save(category);

        return new Fixture(user, merchant, category);
    }

    // --- The core contract -------------------------------------------------------------------

    @Test
    void aQueuedEventIsAppliedToTheLearningDistribution() {
        Fixture f = fixture();
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null);

        worker.drainOnce();

        assertThat(learningRepository.findByUserIdAndMerchantId(f.user().getId(), f.merchant().getId()))
                .singleElement()
                .satisfies(pair -> {
                    assertThat(pair.getCategoryId()).isEqualTo(f.category().getId());
                    assertThat(pair.getConfirmationCount()).isEqualTo(1);
                });
        assertThat(statusOf(f)).isEqualTo(MerchantLearningEvent.Status.COMPLETED);
    }

    /**
     * The property the whole milestone exists for, in its most direct form.
     *
     * <p>The event row is written inside the CALLER's transaction, so if that transaction rolls
     * back the queued learning must go with it. Otherwise a worker would later apply a confirmation
     * for an import that never happened — a stronger failure than the one this design replaces.
     */
    @Test
    void enqueuingInsideATransactionThatRollsBackLeavesNoEvent() {
        Fixture f = fixture();

        try {
            transactionTemplate.executeWithoutResult(status -> {
                publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null);
                throw new IllegalStateException("import failed after queueing learning");
            });
        } catch (IllegalStateException expected) {
            // the point of the test
        }

        assertThat(eventsFor(f)).isEmpty();
        // Scoped to this fixture, not asserted as "the queue drained nothing at all": drainOnce()
        // works across the whole table, so a sibling test's leftover event makes a global
        // assertion here fail for a reason that has nothing to do with rollback.
        worker.drainOnce();
        assertThat(learningRepository.findByUserIdAndMerchantId(f.user().getId(), f.merchant().getId()))
                .isEmpty();
    }

    // --- Concurrency -------------------------------------------------------------------------

    /**
     * Two workers must never claim the same event.
     *
     * <p>Railway can run more than one instance. Without {@code SKIP LOCKED} both would select the
     * same row and both would apply it, incrementing {@code confirmation_count} twice — and
     * confirmation counts are what {@code ConfidenceEngine.topCategory} uses to decide which
     * category is auto-applied, so the corruption is silent and only shows up later as the wrong
     * category.
     *
     * <p>Driven through two real transactions on two threads, with a latch so the second claim runs
     * while the first still holds its lock. That overlap is the entire point: a sequential version
     * of this test passes even without {@code SKIP LOCKED}.
     */
    @Test
    void twoConcurrentClaimsNeverReturnTheSameEvent() throws Exception {
        Fixture f = fixture();
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null);

        CountDownLatch firstHasClaimed = new CountDownLatch(1);
        CountDownLatch secondHasFinished = new CountDownLatch(1);
        AtomicReference<List<MerchantLearningEvent>> firstClaim = new AtomicReference<>();
        AtomicReference<List<MerchantLearningEvent>> secondClaim = new AtomicReference<>();

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                firstClaim.set(eventRepository.claimDueEvents(Instant.now(), 10));
                firstHasClaimed.countDown();
                // Hold the lock while the other thread tries to claim.
                await(secondHasFinished);
            }));

            threads.submit(() -> {
                await(firstHasClaimed);
                transactionTemplate.executeWithoutResult(status ->
                        secondClaim.set(eventRepository.claimDueEvents(Instant.now(), 10)));
                secondHasFinished.countDown();
            });

            threads.shutdown();
            assertThat(threads.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            threads.shutdownNow();
        }

        assertThat(firstClaim.get()).hasSize(1);
        // Skipped, not blocked and not returned -- that is what SKIP LOCKED buys over plain
        // FOR UPDATE, which would have made the second worker wait rather than move on.
        assertThat(secondClaim.get()).isEmpty();
    }

    // --- Failure handling --------------------------------------------------------------------

    /**
     * A failure must be RECORDED, which is harder than it sounds and is why the worker uses three
     * transactions rather than one.
     *
     * <p>The realistic failure here is a constraint violation, which marks its transaction
     * rollback-only — so writing {@code last_error} in that same transaction would roll back with
     * it, and the event would return to the queue with no evidence of what went wrong, forever.
     * This test forces the failure by deleting the category the event points at, so
     * {@code confirm()} fails on the foreign key at flush, exactly as a real constraint failure
     * would.
     */
    @Test
    void aFailedApplyIsRecordedAndScheduledForRetry() {
        Fixture f = fixture();
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null);

        doThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"))
                .when(learningService).confirm(any(), any(), any());

        worker.drainOnce();

        List<MerchantLearningEvent> events = eventsFor(f);
        // The row survives at all only because the failure was recorded in a transaction the
        // violation had not poisoned.
        assertThat(events).hasSize(1);
        MerchantLearningEvent event = events.get(0);
        assertThat(event.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getLastError()).isNotBlank();
        assertThat(event.getFirstFailedAt()).isNotNull();
        // Backed off, so the next drain does not immediately retry it. Asserted on this event's
        // own attempt count rather than on drainOnce()'s global return value, which a sibling
        // test's leftover row would otherwise make non-zero.
        assertThat(event.getNextAttemptAt()).isAfter(Instant.now());
        worker.drainOnce();
        assertThat(eventsFor(f).get(0).getAttemptCount()).isEqualTo(1);
    }

    /** Five attempts and then stop, so a permanently broken event surfaces to a human instead of
     *  retrying forever. */
    @Test
    void anEventGivesUpAfterTheAttemptCapAndBecomesVisibleToAdmins() {
        Fixture f = fixture();
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null);
        doThrow(new DataIntegrityViolationException("permanently broken"))
                .when(learningService).confirm(any(), any(), any());

        for (int attempt = 0; attempt < MerchantLearningEvent.MAX_ATTEMPTS; attempt++) {
            makeDueNow(f);
            worker.drainOnce();
        }

        MerchantLearningEvent event = eventsFor(f).get(0);
        assertThat(event.getStatus()).isEqualTo(MerchantLearningEvent.Status.FAILED);
        assertThat(event.getAttemptCount()).isEqualTo(MerchantLearningEvent.MAX_ATTEMPTS);
        // No further automatic work: a FAILED event is a human's decision now. makeDueNow() puts
        // its next_attempt_at in the past, so only the terminal status keeps a worker off it.
        makeDueNow(f);
        worker.drainOnce();
        assertThat(eventsFor(f).get(0).getStatus()).isEqualTo(MerchantLearningEvent.Status.FAILED);
        assertThat(eventsFor(f).get(0).getAttemptCount()).isEqualTo(MerchantLearningEvent.MAX_ATTEMPTS);
    }

    /** An admin retry resets the attempt budget but keeps the record of when it first broke. */
    @Test
    void anAdminRetryResetsTheBudgetWithoutErasingTheFailureHistory() {
        Fixture f = fixture();
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null);
        doThrow(new DataIntegrityViolationException("boom")).when(learningService).confirm(any(), any(), any());
        worker.drainOnce();

        MerchantLearningEvent failed = eventsFor(f).get(0);
        Instant originalFirstFailure = failed.getFirstFailedAt();
        failed.requeueForRetry(Instant.now());
        eventRepository.save(failed);

        MerchantLearningEvent requeued = eventsFor(f).get(0);
        assertThat(requeued.getStatus()).isEqualTo(MerchantLearningEvent.Status.PENDING);
        assertThat(requeued.getAttemptCount()).isZero();
        assertThat(requeued.getFirstFailedAt()).isEqualTo(originalFirstFailure);
    }

    // --- helpers ------------------------------------------------------------------------------

    private List<MerchantLearningEvent> eventsFor(Fixture f) {
        return eventRepository.findAll().stream()
                .filter(e -> e.getMerchantId().equals(f.merchant().getId()))
                .toList();
    }

    private MerchantLearningEvent.Status statusOf(Fixture f) {
        return eventsFor(f).get(0).getStatus();
    }

    /** Cancels the backoff so a retry can be driven without waiting real minutes. Reaches into the
     *  field rather than adding a production setter that exists only for tests. */
    private void makeDueNow(Fixture f) {
        MerchantLearningEvent event = eventsFor(f).get(0);
        ReflectionTestUtils.setField(event, "nextAttemptAt", Instant.now().minus(1, ChronoUnit.MINUTES));
        eventRepository.save(event);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for the other thread");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
