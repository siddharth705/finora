package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.User;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one test that runs with the queue's asynchronous machinery switched ON.
 *
 * <p>Everything else drives the worker directly through {@code drainOnce()} so the suite is
 * deterministic — see {@code MerchantLearningQueueIT} and the note in {@code application-test.yml}.
 * That leaves one thing unproven, and it is not a small thing: whether the {@code afterCommit}
 * nudge and the {@code @Async} executor are actually wired up. A queue whose worker is never
 * triggered still passes every direct-invocation test and does nothing in production until the
 * poller happens to run.
 *
 * <p>Scoped deliberately to that question. It asserts that enqueueing inside a committed
 * transaction results in the learning being applied <em>without anybody calling the worker</em>,
 * and nothing else.
 *
 * <h2>Why {@link #emptyTheQueueTheRestOfTheSuiteLeftBehind()} exists</h2>
 * This test used to fail under full-suite runs while passing whenever it was run narrowly, and the
 * reason was not timing. {@code merchant_learning_events} is <em>global</em>: one Testcontainers
 * Postgres is shared by every {@code *IT} class in the JVM, and the queue is disabled under test,
 * so every event any other test enqueues — {@code ImportService.confirm} and
 * {@code CategorizationService} both do, on paths a lot of tests exercise — stays PENDING for the
 * rest of the run unless that test happens to drain it explicitly. Most do not.
 *
 * <p>{@code claimDueEvents} is {@code ORDER BY next_attempt_at ... LIMIT 50}, unscoped by user. So
 * once the suite has leaked more than one batch of older PENDING events, the single {@code
 * drainOnce()} that this test's nudge performs claims fifty of <em>other tests' leftovers</em> and
 * returns, having never reached the event under test. The nudge fired correctly, the executor ran
 * correctly, and the assertion still failed — the event was simply never in the batch, and no
 * timeout would have changed that.
 *
 * <p>Measured while diagnosing it: six neighbouring classes alone left fifteen events behind, and
 * seeding a backlog of sixty reproduces the full-suite failure exactly — the same full-budget 20s
 * timeout, in an otherwise isolated run. Which backlog a given run has accumulated by the time
 * this class is scheduled is what decided whether it passed, and is why it read as flakiness.
 *
 * <p>Draining the backlog first restores the precondition this test always silently assumed — that
 * its own event is the one the nudge will find. It runs before the event under test exists, so the
 * claim above still holds: from the enqueue onwards, nothing calls the worker. Safe to do here
 * because {@code AbstractIntegrationTest} is {@code @Isolated}, so no other {@code *IT} class is
 * running to enqueue anything concurrently.
 */
@TestPropertySource(properties = {
        "app.learning.queue.enabled=true",
        // The poller must not be what makes this pass -- that would prove the opposite of the
        // point. Pushed far enough out that only the afterCommit nudge can be responsible.
        "app.learning.queue.initial-delay-ms=3600000",
        "app.learning.queue.poll-interval-ms=3600000"
})
class MerchantLearningNudgeIT extends AbstractIntegrationTest {

    /**
     * Wildly generous against a nudge that is measured in milliseconds — the observed latency is
     * one tick of the poll loop below, i.e. the sleep dominates the actual work.
     *
     * <p>Left generous rather than tightened because the executor hands off to another thread and
     * a loaded CI box is entitled to schedule it late. Worth being explicit about what this budget
     * does <em>not</em> buy, though, since the obvious response to seeing this test fail is to
     * raise the number: a nudge whose {@code drainOnce()} never claims this event does not land
     * late, it never lands at all, and no timeout is long enough. That was the actual bug — see
     * the class comment.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** Bound on the setup drain, so a queue that somehow refuses to empty fails loudly here rather
     *  than spinning. Each pass claims up to the worker's batch size of 50. */
    private static final int MAX_DRAIN_PASSES = 100;

    @Autowired private MerchantLearningEventPublisher publisher;
    @Autowired private MerchantLearningEventWorker worker;
    @Autowired private MerchantCategoryLearningRepository learningRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    /** Clears events other test classes left in the shared queue, so the nudge under test finds
     *  its own event rather than fifty of theirs. See the class comment for why this is load-
     *  bearing rather than tidiness. */
    @BeforeEach
    void emptyTheQueueTheRestOfTheSuiteLeftBehind() {
        int passes = 0;
        while (worker.drainOnce() > 0) {
            if (++passes >= MAX_DRAIN_PASSES) {
                throw new IllegalStateException("The learning queue would not drain in "
                        + MAX_DRAIN_PASSES + " passes; this test cannot establish its precondition "
                        + "that the event it enqueues is the one the nudge will claim.");
            }
        }
    }

    @Test
    void committingAnImportTriggersLearningWithoutAnyoneCallingTheWorker() {
        User user = new User();
        user.setEmail("learning-nudge-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Learning Nudge IT User");
        user.setPhoneVerified(true);
        User savedUser = userRepository.save(user);

        Merchant merchant = new Merchant();
        merchant.setUserId(savedUser.getId());
        merchant.setCanonicalName("Nudge Merchant " + UUID.randomUUID());
        Merchant savedMerchant = merchantRepository.save(merchant);

        Category category = new Category();
        category.setUserId(savedUser.getId());
        category.setName("Nudge Category " + UUID.randomUUID());
        Category savedCategory = categoryRepository.save(category);

        // Inside a transaction that COMMITS -- so the afterCommit hook is what fires, which is the
        // path a real import takes.
        transactionTemplate.executeWithoutResult(status ->
                publisher.enqueue(savedUser.getId(), savedMerchant.getId(), savedCategory.getId(), null, null));

        assertThat(learningAppliedWithin(TIMEOUT, savedUser.getId(), savedMerchant.getId()))
                .as("the afterCommit nudge should have applied the learning without an explicit drain")
                .isTrue();
    }

    /** Polls rather than sleeping a fixed interval: the nudge normally lands in milliseconds, so a
     *  fixed sleep would either be flaky or waste the whole budget on every run. */
    private boolean learningAppliedWithin(Duration timeout, UUID userId, UUID merchantId) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!learningRepository.findByUserIdAndMerchantId(userId, merchantId).isEmpty()) {
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
}
