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

    private Notification sentNotification() {
        Notification n = newNotification();
        n.markQueued(Instant.parse("2026-09-02T10:01:00Z"));
        n.markProcessing(Instant.parse("2026-09-02T10:02:00Z"));
        n.markSent(Instant.parse("2026-09-02T10:03:00Z"));
        return n;
    }

    private Notification deadLetteredNotification() {
        Notification n = newNotification();
        Instant now = Instant.parse("2026-09-02T10:00:00Z");
        for (int i = 0; i < Notification.MAX_ATTEMPTS; i++) {
            n.recordFailure("provider timeout", now);
        }
        return n;
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

    /**
     * Fix wave, IMPORTANT 1: {@code recordFailure} used to only truncate {@code error}, while
     * {@code NotificationLog.of} redacted the exact same {@code ChannelSendResult.detail()} string
     * before writing it to {@code notification_logs.response}. {@code last_error} is read straight
     * back by the admin portal, so an unredacted email/phone/token-shaped provider detail landing
     * here was just as much of a leak as it would have been in the log table. Same fixture shape as
     * {@code NotificationLogRepositoryIT.of_redactsEmailPhoneAndTokenShapesBeforePersisting} -- a
     * repeated-letter placeholder instead of a real-looking token, so this doesn't trip the repo's
     * gitleaks pre-commit scan.
     */
    @Test
    void recordFailure_redactsEmailPhoneAndTokenShapesFromLastError() {
        Notification n = newNotification();
        Instant now = Instant.parse("2026-09-02T10:00:00Z");
        String placeholderToken = "x".repeat(28);
        String raw = "550 no such user: alice@example.com, MSISDN +91 98765 43210, "
                + "token=" + placeholderToken;

        n.recordFailure(raw, now);

        assertThat(n.getLastError()).doesNotContain("alice@example.com")
                .doesNotContain("98765 43210")
                .doesNotContain(placeholderToken)
                .contains("[redacted-email]")
                .contains("[redacted-phone]")
                .contains("[redacted-token]");
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

    @Test
    void markQueued_onSentRowIsNoOp() {
        Notification n = sentNotification();
        Instant sentAt = n.getSentAt();
        Instant nextAttemptAt = n.getNextAttemptAt();

        n.markQueued(Instant.parse("2026-09-02T11:00:00Z"));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getSentAt()).isEqualTo(sentAt);
        assertThat(n.getNextAttemptAt()).isEqualTo(nextAttemptAt);
    }

    @Test
    void markQueued_onDeadLetterRowIsNoOp() {
        Notification n = deadLetteredNotification();
        Instant nextAttemptAt = n.getNextAttemptAt();

        n.markQueued(Instant.parse("2026-09-02T11:00:00Z"));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(n.getNextAttemptAt()).isEqualTo(nextAttemptAt);
    }

    @Test
    void markProcessing_onSentRowIsNoOp() {
        Notification n = sentNotification();
        Instant sentAt = n.getSentAt();
        Instant nextAttemptAt = n.getNextAttemptAt();

        n.markProcessing(Instant.parse("2026-09-02T11:00:00Z"));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(n.getSentAt()).isEqualTo(sentAt);
        assertThat(n.getNextAttemptAt()).isEqualTo(nextAttemptAt);
    }

    @Test
    void markProcessing_onDeadLetterRowIsNoOp() {
        Notification n = deadLetteredNotification();
        Instant nextAttemptAt = n.getNextAttemptAt();

        n.markProcessing(Instant.parse("2026-09-02T11:00:00Z"));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(n.getNextAttemptAt()).isEqualTo(nextAttemptAt);
    }

    @Test
    void markSent_onSentRowIsNoOp() {
        Notification n = sentNotification();
        Instant sentAt = n.getSentAt();

        n.markSent(Instant.parse("2026-09-02T11:00:00Z"));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.SENT);
        // A duplicate markSent call must not clobber the original delivery timestamp.
        assertThat(n.getSentAt()).isEqualTo(sentAt);
    }

    @Test
    void markSent_onDeadLetterRowIsNoOp() {
        Notification n = deadLetteredNotification();
        String lastError = n.getLastError();

        n.markSent(Instant.parse("2026-09-02T11:00:00Z"));

        assertThat(n.getStatus()).isEqualTo(NotificationStatus.DEAD_LETTER);
        assertThat(n.getSentAt()).isNull();
        // markSent's own logic clears lastError -- the guard must stop it from firing at all.
        assertThat(n.getLastError()).isEqualTo(lastError);
    }
}
