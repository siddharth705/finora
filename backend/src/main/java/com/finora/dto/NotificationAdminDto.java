package com.finora.dto;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the admin notification dashboard (Task 12).
 *
 * <p>Deliberately carries no email or phone number: {@code userId} is a bare UUID with no join
 * back to the user's contact details, and {@code title} comes from {@code notification_templates}'
 * own placeholder-substituted copy (a bank name is the only substitution any template uses today
 * -- see V126), never from a raw provider payload. See {@link NotificationAdminDetailDto} for the
 * per-attempt log, whose {@code response} field is redacted at write time by
 * {@code NotificationLog.of}, not by anything in this DTO.
 */
public record NotificationAdminDto(
        UUID id,
        UUID userId,
        String type,
        String category,
        String channel,
        String priority,
        String status,
        String title,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        Instant sentAt,
        Instant createdAt
) {

    public static NotificationAdminDto from(Notification n) {
        return new NotificationAdminDto(
                n.getId(),
                n.getUserId(),
                n.getType().name(),
                n.getCategory().name(),
                n.getChannel().name(),
                n.getPriority().name(),
                n.getStatus().name(),
                n.getTitle(),
                n.getAttemptCount(),
                n.getNextAttemptAt(),
                n.getLastError(),
                n.getSentAt(),
                n.getCreatedAt());
    }

    /**
     * Send-outcome counts for the dashboard's stat tiles, overall and broken down by channel --
     * the proposal (section 2.5/4) scopes this dashboard to exactly this: a list plus basic
     * send-outcome counts, nothing richer.
     *
     * <p>"Sent" means {@link NotificationStatus#SENT}. "Failed" means {@link
     * NotificationStatus#DEAD_LETTER} -- the actual terminal-failure state {@code
     * Notification.recordFailure} assigns once a notification exhausts its retry budget.
     * {@code NotificationStatus.FAILED} is declared in the enum and documented on the
     * {@code notifications.status} column comment (V124), but no code path in this codebase ever
     * assigns it to a row -- {@code Notification}'s own state machine only ever moves a row
     * through CREATED/QUEUED/PROCESSING/RETRYING to SENT or DEAD_LETTER. Counting the unused
     * FAILED value here would always report zero and silently under-count every real failure, so
     * it is deliberately excluded.
     */
    public record Summary(long sent, long failed, List<ChannelSummary> byChannel) {}

    public record ChannelSummary(String channel, long sent, long failed) {}
}
