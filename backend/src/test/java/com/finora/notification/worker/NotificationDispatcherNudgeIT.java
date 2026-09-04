package com.finora.notification.worker;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationStatus;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationRepository;
import com.finora.repository.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code NotificationDispatcher} is "shaped directly after {@code MerchantLearningEventWorker}" --
 * its own class doc says so -- and it copied that worker's transaction bug right along with the
 * rest of the shape, before this fix: see {@code NotificationDispatcher}'s "Own
 * PROPAGATION_REQUIRES_NEW template" doc section, and {@code MerchantLearningEventWorker}'s own
 * doc comment, for the full mechanism this reproduces.
 *
 * <p>In short: {@link NotificationDispatcher#nudge} runs {@code @Async} on a two-thread,
 * {@code CallerRunsPolicy} pool, triggered from an {@code afterCommit} synchronization on the
 * transaction that just requested the notification. Under saturation, {@code CallerRunsPolicy}
 * runs {@code nudge()} <em>synchronously on the committing thread</em>, still inside {@code
 * afterCommit()}, before Spring's {@code cleanupAfterCompletion()} has unbound that transaction's
 * resources. A {@code PROPAGATION_REQUIRED} template then "joins" those stale, already-committed
 * resources instead of starting a real transaction, so the delivery outcome this worker computes
 * -- {@code repository.save(notification)} -- silently never flushes.
 *
 * <p>This test does not (and, per the investigation behind this fix, currently cannot) reproduce
 * actual executor saturation: as of this fix there is no code path that registers more than one
 * {@code afterCommit} nudge per transaction for notifications, unlike the learning queue's
 * per-event registrations. Instead, exactly like {@code MerchantLearningNudgeIT
 * #applyingInsideAfterCommitOnTheSameThreadStillSucceeds}, it calls {@code drainOnce()} directly
 * from an {@code afterCommit} callback registered on this test's own committing transaction --
 * reproducing the same-thread, resources-still-bound moment deterministically, without depending
 * on saturating the executor. This is the regression guard for a defensive fix, not proof of an
 * incident that has actually happened on this worker.
 */
class NotificationDispatcherNudgeIT extends AbstractIntegrationTest {

    @Autowired private NotificationDispatcher dispatcher;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    /**
     * PUSH, deliberately: {@code NoOpPushProvider.isConfigured()} is always {@code false} under
     * test (no Firebase credentials), so {@code deliverOne} takes the no-configured-provider
     * branch straight to {@code failTerminally} on the very first drain pass -- a single,
     * deterministic write with no network call and no dependence on a real provider's behaviour.
     */
    @Test
    void applyingInsideAfterCommitOnTheSameThreadStillSucceeds() {
        UUID userId = userRepository.save(newUser()).getId();
        Instant now = Instant.now();
        Notification notification = Notification.create(userId, NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.PUSH, NotificationPriority.NORMAL,
                "NUDGE_IT_" + UUID.randomUUID(), "Title", "Body", now);
        notification.markQueued(now);
        UUID notificationId = notificationRepository.save(notification).getId();

        transactionTemplate.executeWithoutResult(status ->
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        // Simulates the CallerRunsPolicy fallback: the async executor was
                        // saturated, so Spring ran the nudge on THIS thread instead of a dedicated
                        // notification-queue-* one.
                        dispatcher.drainOnce();
                    }
                }));

        assertThat(notificationRepository.findById(notificationId))
                .as("delivery must be recorded even when the drain runs synchronously inside "
                        + "afterCommit -- the CallerRunsPolicy fallback path a saturated executor "
                        + "takes in production")
                .hasValueSatisfying(reloaded ->
                        assertThat(reloaded.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER));
    }

    private User newUser() {
        User user = new User();
        user.setEmail("notification-nudge-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Notification Nudge IT User");
        user.setPhoneVerified(true);
        return user;
    }
}
