package com.finora.notification.api;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPriority;
import com.finora.notification.domain.NotificationType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What a caller asks for. Callers name the event semantically and supply parameters -- they never
 * pass a title/body string, because copy lives in notification_templates.
 *
 * @param notificationKey deterministic idempotency key, e.g. {@code IMPORT_READY_{jobId}}. The
 *     channel is appended per row, so one request across two channels yields two distinct keys.
 */
public record NotificationRequest(
        UUID userId,
        NotificationType type,
        NotificationCategory category,
        NotificationPriority priority,
        String notificationKey,
        Set<NotificationChannel> channels,
        Map<String, String> params) {

    public static NotificationRequest of(UUID userId, NotificationType type,
            NotificationCategory category, NotificationPriority priority, String notificationKey,
            Set<NotificationChannel> channels, Map<String, String> params) {
        return new NotificationRequest(userId, type, category, priority, notificationKey, channels,
                Map.copyOf(params));
    }
}
