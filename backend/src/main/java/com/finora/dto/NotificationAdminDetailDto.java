package com.finora.dto;

import com.finora.notification.domain.Notification;
import com.finora.notification.domain.NotificationLog;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Notification detail plus its attempt log (Task 12) -- everything {@code
 * AdminNotificationController}'s {@code GET /{id}} needs to answer "what was sent, and what
 * happened when we tried to send it." A flat record carrying every {@link NotificationAdminDto}
 * field again (rather than wrapping one), matching {@code AdminDtos.UserDetailDto}'s own
 * precedent for a detail DTO that is a summary DTO's fields plus a few more, not a composed
 * wrapper around it.
 *
 * <p>{@code attempts} is newest-first, matching {@code
 * NotificationLogRepository.findByNotificationIdOrderByTimestampDesc} -- the same "read the
 * detail view back through the exact shape the data already comes in" discipline
 * {@code AdminLearningQueueService.single} documents for its own detail lookup.
 */
public record NotificationAdminDetailDto(
        UUID id,
        UUID userId,
        String type,
        String category,
        String channel,
        String priority,
        String status,
        String title,
        String message,
        int attemptCount,
        Instant nextAttemptAt,
        String lastError,
        Instant sentAt,
        Instant createdAt,
        List<AttemptDto> attempts
) {

    public static NotificationAdminDetailDto from(Notification n, List<NotificationLog> attempts) {
        return new NotificationAdminDetailDto(
                n.getId(),
                n.getUserId(),
                n.getType().name(),
                n.getCategory().name(),
                n.getChannel().name(),
                n.getPriority().name(),
                n.getStatus().name(),
                n.getTitle(),
                n.getMessage(),
                n.getAttemptCount(),
                n.getNextAttemptAt(),
                n.getLastError(),
                n.getSentAt(),
                n.getCreatedAt(),
                attempts.stream().map(AttemptDto::from).toList());
    }

    /**
     * One provider delivery attempt. {@code response} is already redacted (emails, phone numbers
     * and tokens replaced with placeholders) by {@code NotificationLog.of} before the row was
     * ever written to {@code notification_logs} -- this DTO reads it back as-is and applies no
     * redaction of its own, so it can never re-expose anything the write path already scrubbed.
     */
    public record AttemptDto(UUID id, String provider, String response, boolean success, int attempt,
                              Instant timestamp) {

        public static AttemptDto from(NotificationLog log) {
            return new AttemptDto(log.getId(), log.getProvider(), log.getResponse(), log.isSuccess(),
                    log.getAttempt(), log.getTimestamp());
        }
    }
}
