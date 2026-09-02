package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.User;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

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
 * <p>This test is why {@code AbstractIntegrationTest.emptyTheSharedWorkQueues()} exists. It used
 * to fail under full-suite runs and pass whenever run narrowly, because a nudge drains one batch
 * of a table-wide queue and the rest of the suite had filled that batch with its own leftovers.
 * The precondition it needs — that its event is one a single drain will reach — is established
 * there now, for every test rather than this one.
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

    @Autowired private MerchantLearningEventPublisher publisher;
    @Autowired private MerchantLearningEventWorker worker;
    @Autowired private MerchantCategoryLearningRepository learningRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private MerchantLearningEventRepository eventRepository;

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

    /**
     * A nudge must drain the queue it was handed, not just the first batch of it.
     *
     * <p>{@code drainOnce()} claims at most {@link MerchantLearningEventWorker#BATCH_SIZE}. A pass
     * that comes back full is itself evidence there is more waiting, and before this was fixed the
     * nudge stopped there anyway — so a burst larger than one batch left the remainder sitting
     * until the next poll, thirty seconds later, for no reason. The row was durable and the poller
     * would have collected it, so this was never a correctness bug; it was the user's
     * categorisation silently not taking effect for half a minute after an import that had
     * visibly succeeded.
     *
     * <p>Seeds the backlog directly rather than through the publisher on purpose: {@code enqueue}
     * registers one afterCommit nudge per call, so enqueueing the whole burst would fire fifty-odd
     * nudges and prove nothing about what a single one does.
     */
    @Test
    void oneNudgeDrainsABacklogLargerThanASingleBatch() {
        User savedUser = userRepository.save(newUser());
        Category savedCategory = categoryRepository.save(newCategory(savedUser));

        int backlog = MerchantLearningEventWorker.BATCH_SIZE + 5;
        List<UUID> merchantIds = new ArrayList<>();
        for (int i = 0; i < backlog; i++) {
            merchantIds.add(merchantRepository.save(newMerchant(savedUser)).getId());
        }
        // All but the last land with no nudge behind them -- this is the backlog a real burst
        // leaves. The last one commits through the publisher, so exactly ONE nudge fires.
        merchantIds.subList(0, backlog - 1).forEach(merchantId ->
                eventRepository.save(com.finora.entity.MerchantLearningEvent.pending(
                        savedUser.getId(), merchantId, savedCategory.getId(), null, null)));
        UUID last = merchantIds.get(backlog - 1);
        transactionTemplate.executeWithoutResult(status ->
                publisher.enqueue(savedUser.getId(), last, savedCategory.getId(), null, null));

        assertThat(everyLearningAppliedWithin(TIMEOUT, savedUser.getId(), backlog))
                .as("one nudge should drain past the first batch of %d", MerchantLearningEventWorker.BATCH_SIZE)
                .isTrue();
    }

    /** True once the user has {@code expected} learning rows, or false if the budget runs out. */
    private boolean everyLearningAppliedWithin(Duration timeout, UUID userId, int expected) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (learningRepository.findByUserId(userId).size() >= expected) {
                return true;
            }
            if (!sleepBriefly()) return false;
        }
        return false;
    }

    private User newUser() {
        User user = new User();
        user.setEmail("learning-nudge-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Learning Nudge IT User");
        user.setPhoneVerified(true);
        return user;
    }

    private Merchant newMerchant(User owner) {
        Merchant merchant = new Merchant();
        merchant.setUserId(owner.getId());
        merchant.setCanonicalName("Nudge Merchant " + UUID.randomUUID());
        return merchant;
    }

    private Category newCategory(User owner) {
        Category category = new Category();
        category.setUserId(owner.getId());
        category.setName("Nudge Category " + UUID.randomUUID());
        return category;
    }

    private boolean sleepBriefly() {
        try {
            Thread.sleep(100);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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
