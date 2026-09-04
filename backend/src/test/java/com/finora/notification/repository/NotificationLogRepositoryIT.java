package com.finora.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationLog;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationStatus;
import com.finora.notification.domain.NotificationType;
import com.finora.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exercises behavior only a real Postgres can validate: the FOR UPDATE SKIP LOCKED claim query and
 * the cascade from notifications to notification_logs.
 */
class NotificationLogRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationLogRepository logRepository;

    @Autowired
    private UserRepository userRepository;

    private UUID userId;

    @BeforeEach
    void setUp() {
        // Build a real user row -- notifications.user_id is a foreign key. Construction copied
        // from TransactionRepositoryIT.setUp() -- there is no shared newTestUser() helper.
        User user = new User();
        user.setEmail("test-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Test User");
        user = userRepository.save(user);
        userId = user.getId();
    }

    private Notification persistNotification(String key, Instant nextAttemptAt) {
        Notification n = Notification.create(userId, NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL, NotificationChannel.EMAIL,
                NotificationPriority.NORMAL, key, "Title", "Body", nextAttemptAt);
        n.markQueued(nextAttemptAt);
        return notificationRepository.save(n);
    }

    @Test
    @Transactional
    void claimDue_returnsOnlyNotificationsWhoseNextAttemptHasArrived() {
        Instant now = Instant.now();
        persistNotification("DUE:EMAIL", now.minusSeconds(60));
        persistNotification("FUTURE:EMAIL", now.plusSeconds(600));

        List<Notification> claimed = notificationRepository.claimDue(now, 10);

        assertThat(claimed).extracting(Notification::getNotificationKey)
                .containsExactly("DUE:EMAIL");
    }

    @Test
    @Transactional
    void claimDue_ignoresTerminalNotifications() {
        Instant now = Instant.now();
        Notification sent = persistNotification("SENT:EMAIL", now.minusSeconds(60));
        sent.markSent(now);
        notificationRepository.save(sent);

        assertThat(notificationRepository.claimDue(now, 10)).isEmpty();
    }

    @Test
    @Transactional
    void existsByNotificationKey_enforcesIdempotency() {
        persistNotification("K1:EMAIL", Instant.now());

        assertThat(notificationRepository.existsByNotificationKey("K1:EMAIL")).isTrue();
        assertThat(notificationRepository.existsByNotificationKey("K2:EMAIL")).isFalse();
    }

    @Test
    @Transactional
    void logs_areRetrievableNewestFirst() {
        Notification n = persistNotification("K1:EMAIL", Instant.now());
        Instant t1 = Instant.parse("2026-09-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-09-02T10:05:00Z");
        logRepository.save(NotificationLog.of(n.getId(), "resend", "502", false, 1, t1));
        logRepository.save(NotificationLog.of(n.getId(), "resend", "ok", true, 2, t2));

        List<NotificationLog> logs =
                logRepository.findByNotificationIdOrderByTimestampDesc(n.getId());

        assertThat(logs).extracting(NotificationLog::getAttempt).containsExactly(2, 1);
    }

    @Test
    @Transactional
    void logs_areDeletedWhenTheirNotificationIsDeleted() {
        // Flushed in dependency order (notification, then the log referencing it) before the
        // delete: without a real, already-flushed parent row, Hibernate would cancel the parent's
        // still-pending INSERT when it is removed in the same session, and the child's own INSERT
        // (a plain UUID column, not a JPA association Hibernate could reason about) would then
        // violate the FK against a notification row that was never actually written.
        Notification n = persistNotification("CASCADE:EMAIL", Instant.now());
        notificationRepository.flush();
        logRepository.save(NotificationLog.of(n.getId(), "resend", "ok", true, 1, Instant.now()));
        logRepository.flush();

        notificationRepository.delete(n);
        notificationRepository.flush();

        assertThat(logRepository.findByNotificationIdOrderByTimestampDesc(n.getId())).isEmpty();
    }

    @Test
    @Transactional
    void of_redactsEmailPhoneAndTokenShapesBeforePersisting() {
        Notification n = persistNotification("REDACT:EMAIL", Instant.now());
        // The token placeholder below is a repeated letter, not a real secret shape -- a
        // higher-entropy fixture here (e.g. a plausible-looking API key) trips the repo's
        // gitleaks pre-commit scan, which cannot tell a test fixture from a real credential.
        String placeholderToken = "x".repeat(28);
        String raw = "550 no such user: alice@example.com, MSISDN +91 98765 43210, "
                + "token=" + placeholderToken;

        NotificationLog saved = logRepository
                .save(NotificationLog.of(n.getId(), "resend", raw, false, 1, Instant.now()));

        assertThat(saved.getResponse()).doesNotContain("alice@example.com")
                .doesNotContain("98765 43210")
                .doesNotContain(placeholderToken)
                .contains("[redacted-email]")
                .contains("[redacted-phone]")
                .contains("[redacted-token]");
    }
}
