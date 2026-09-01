package com.finora.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Notification state machine. Mockito-free -- this is a pure entity test,
 * matching the style of other entity state-machine tests in this codebase.
 */
class NotificationTest {

    private Notification newNotification() {
        return Notification.create(
                UUID.randomUUID(),
                NotificationType.IMPORT_STATEMENT_READY,
                NotificationCategory.FINANCIAL,
                NotificationChannel.EMAIL,
                NotificationPriority.NORMAL,
                "IMPORT_READY_test-key",
                "Your statement is ready",
                "We finished importing your statement.",
                Instant.parse("2026-09-02T10:00:00Z"));
    }

    @Test
    void create_startsInCreatedStatusWithNoAttempts() {
        Notification n = newNotification();

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.CREATED);
        assertThat(n.getAttemptCount()).isZero();
        assertThat(n.getSentAt()).isNull();
    }

    @Test
    void markSent_recordsTimestampAndTerminalStatus() {
        Notification n = newNotification();
        Instant sentAt = Instant.parse("2026-09-02T10:05:00Z");

        n.markQueued(Instant.parse("2026-09-02T10:01:00Z"));
        n.markProcessing(Instant.parse("2026-09-02T10:04:00Z"));
        n.markSent(sentAt);

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getSentAt()).isEqualTo(sentAt);
    }

    @Test
    void recordFailure_schedulesRetryWithBackoffUntilMaxAttempts() {
        Notification n = newNotification();
        Instant now = Instant.parse("2026-09-02T10:00:00Z");

        Notification.FailureOutcome outcome = n.recordFailure("provider timeout", now);

        assertThat(outcome).isEqualTo(Notification.FailureOutcome.RETRY_SCHEDULED);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.RETRYING);
        assertThat(n.getAttemptCount()).isEqualTo(1);
        // 2^1 minutes of backoff -- same exponential shape as MerchantLearningEvent.
        assertThat(n.getNextAttemptAt()).isEqualTo(now.plusSeconds(120));
    }

    @Test
    void recordFailure_deadLettersOnceAttemptsAreExhausted() {
        Notification n = newNotification();
        Instant now = Instant.parse("2026-09-02T10:00:00Z");

        Notification.FailureOutcome outcome = null;
        for (int i = 0; i < Notification.MAX_ATTEMPTS; i++) {
            outcome = n.recordFailure("provider timeout", now);
        }

        assertThat(outcome).isEqualTo(Notification.FailureOutcome.DEAD_LETTERED);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(n.getAttemptCount()).isEqualTo(Notification.MAX_ATTEMPTS);
    }

    @Test
    void recordFailure_onAlreadySentNotificationIsIgnored() {
        Notification n = newNotification();
        n.markQueued(Instant.parse("2026-09-02T10:01:00Z"));
        n.markProcessing(Instant.parse("2026-09-02T10:02:00Z"));
        n.markSent(Instant.parse("2026-09-02T10:03:00Z"));

        Notification.FailureOutcome outcome =
                n.recordFailure("late failure", Instant.parse("2026-09-02T10:04:00Z"));

        assertThat(outcome).isEqualTo(Notification.FailureOutcome.ALREADY_FINISHED);
        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
    }
}
