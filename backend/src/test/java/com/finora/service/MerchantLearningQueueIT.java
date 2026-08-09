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
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null, null);

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
                publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null, null);
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
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null, null);

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

        // BH-058. Scoped to THIS fixture's events, not to whatever the table happens to hold.
        //
        // claimDueEvents is table-wide by design -- a worker claims work, not one user's work --
        // so asserting on the raw result made this test depend on the entire suite leaving the
        // queue empty. Every confirmed import enqueues learning events and the test profile
        // disables the worker that would drain them, so that assumption held only by luck. Adding
        // an unrelated import-heavy test class turned this red, which is how it was found.
        //
        // The contract being tested is unchanged and is not weakened by scoping: the SAME event
        // must never be returned to two claimants. Filtering to this fixture's user is what
        // expresses that, where hasSize(1) merely expressed "nothing else is going on".
        assertThat(mine(firstClaim.get(), f))
                .as("the first claimant takes this fixture's event")
                .hasSize(1);
        // Skipped, not blocked and not returned -- that is what SKIP LOCKED buys over plain
        // FOR UPDATE, which would have made the second worker wait rather than move on.
        assertThat(mine(secondClaim.get(), f))
                .as("and the second must not see it while the first still holds the lock")
                .isEmpty();
    }

    /**
     * BH-058, the negative case: unrelated pending work must not change the answer.
     *
     * <p>A test-isolation defect cuts both ways. The failure this one produced was a false
     * FAILURE -- another class's leftovers made a correct implementation look broken. The
     * dangerous direction is the false PASS: if the assertions were loose enough, a genuinely
     * broken SKIP LOCKED could be masked by whichever rows happened to be in the table. So this
     * deliberately fills the queue with unrelated pending events and asserts the outcome is
     * identical to the clean case.
     *
     * <p>The unrelated events belong to a DIFFERENT user and are left claimable, which is the
     * hostile arrangement: they are eligible for both claims, they sort ahead of or behind this
     * fixture's event unpredictably, and they are exactly what a real multi-tenant queue looks
     * like at any busy moment.
     */
    @Test
    void unrelatedPendingEventsCannotChangeWhoClaimsWhat() throws Exception {
        Fixture noise = fixture();
        for (int i = 0; i < 5; i++) {
            publisher.enqueue(noise.user().getId(), noise.merchant().getId(), noise.category().getId(), null, null);
        }

        Fixture f = fixture();
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null, null);

        CountDownLatch firstHasClaimed = new CountDownLatch(1);
        CountDownLatch secondHasFinished = new CountDownLatch(1);
        AtomicReference<List<MerchantLearningEvent>> firstClaim = new AtomicReference<>();
        AtomicReference<List<MerchantLearningEvent>> secondClaim = new AtomicReference<>();

        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            threads.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                firstClaim.set(eventRepository.claimDueEvents(Instant.now(), 10));
                firstHasClaimed.countDown();
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

        assertThat(mine(firstClaim.get(), f))
                .as("six events in the queue instead of one changes nothing about who gets this one")
                .hasSize(1);
        assertThat(mine(secondClaim.get(), f))
                .as("still skipped -- the noise must not let a second claimant through to it")
                .isEmpty();

        // And the property that actually matters across the whole table, not just this fixture:
        // no event may appear in both claims, whoever it belongs to.
        List<UUID> claimedTwice = firstClaim.get().stream()
                .map(MerchantLearningEvent::getId)
                .filter(id -> secondClaim.get().stream().anyMatch(e -> e.getId().equals(id)))
                .toList();
        assertThat(claimedTwice)
                .as("SKIP LOCKED's whole promise: no row is handed to two workers")
                .isEmpty();
    }

    /** This fixture's events only. See BH-058 -- claimDueEvents is table-wide by design, so an
     *  assertion about it has to say which events it means. */
    private static List<MerchantLearningEvent> mine(List<MerchantLearningEvent> claimed, Fixture f) {
        return claimed.stream().filter(e -> e.getUserId().equals(f.user().getId())).toList();
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
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null, null);

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
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null, null);
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
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null, null);
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

    /**
     * Applying the same event twice must not confirm the merchant twice.
     *
     * <p>{@code MerchantLearningService.confirm} is not itself idempotent -- each call is a real
     * confirmation and increments {@code confirmation_count}, which is correct. So the guarantee
     * has to come from each EVENT being applied exactly once, and it does, structurally: the apply
     * and the {@code COMPLETED} status write share one transaction. Either both commit or neither
     * does, so there is no window where learning was applied but the event still looks claimable.
     * A COMPLETED event is then invisible to every claim, which only selects PENDING.
     *
     * <p>Asserted by draining repeatedly, including concurrently -- the case a naive "check status,
     * then apply" implementation would fail.
     */
    @Test
    void applyingAnEventIsExactlyOnceEvenUnderRepeatedAndConcurrentDrains() throws Exception {
        Fixture f = fixture();
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null, null);

        ExecutorService threads = Executors.newFixedThreadPool(4);
        try {
            for (int i = 0; i < 4; i++) {
                threads.submit(() -> worker.drainOnce());
            }
            threads.shutdown();
            assertThat(threads.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            threads.shutdownNow();
        }
        worker.drainOnce();
        worker.drainOnce();

        assertThat(learningRepository.findByUserIdAndMerchantId(f.user().getId(), f.merchant().getId()))
                .singleElement()
                .satisfies(pair -> assertThat(pair.getConfirmationCount())
                        .as("one queued confirmation must mean exactly one increment")
                        .isEqualTo(1));
    }

    /**
     * A worker that dies mid-apply strands its events, and nothing else would ever free them.
     *
     * <p>The row lock dies with the transaction, but the status does not: the row reads PROCESSING
     * forever and claims only look at PENDING. Simulated by writing the state a crashed worker
     * leaves behind -- PROCESSING, last touched longer ago than the timeout -- because actually
     * killing a worker mid-transaction is not something a test can do reliably.
     */
    @Test
    void anEventAbandonedInProcessingIsReturnedToTheQueue() {
        Fixture f = fixture();
        publisher.enqueue(f.user().getId(), f.merchant().getId(), f.category().getId(), null, null);

        MerchantLearningEvent stranded = eventsFor(f).get(0);
        stranded.markProcessing(Instant.now());
        ReflectionTestUtils.setField(stranded, "updatedAt", Instant.now().minus(1, ChronoUnit.HOURS));
        eventRepository.save(stranded);

        // Confirms the premise: while stranded, no amount of draining reaches it.
        worker.drainOnce();
        assertThat(eventsFor(f).get(0).getStatus()).isEqualTo(MerchantLearningEvent.Status.PROCESSING);

        // poll() is gated by the enabled flag, which this class switches off for determinism, so
        // recovery is driven directly -- the same reason drainOnce() is public.
        worker.recoverAbandoned();

        MerchantLearningEvent recovered = eventsFor(f).get(0);
        assertThat(recovered.getStatus())
                .as("recovery returns the stranded event to the queue")
                .isEqualTo(MerchantLearningEvent.Status.PENDING);
        assertThat(recovered.getAttemptCount())
                .as("the abandonment counts as an attempt, so a repeatedly crashing apply still terminates")
                .isPositive();
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
