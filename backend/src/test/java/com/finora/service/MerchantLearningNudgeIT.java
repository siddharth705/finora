package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.User;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.UserRepository;
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
 */
@TestPropertySource(properties = {
        "app.learning.queue.enabled=true",
        // The poller must not be what makes this pass -- that would prove the opposite of the
        // point. Pushed far enough out that only the afterCommit nudge can be responsible.
        "app.learning.queue.initial-delay-ms=3600000",
        "app.learning.queue.poll-interval-ms=3600000"
})
class MerchantLearningNudgeIT extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired private MerchantLearningEventPublisher publisher;
    @Autowired private MerchantCategoryLearningRepository learningRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionTemplate transactionTemplate;

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
                publisher.enqueue(savedUser.getId(), savedMerchant.getId(), savedCategory.getId(), null));

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
