package com.finora.notification.api;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import java.util.UUID;

/**
 * Whether a user wants a given category on a given channel. Implemented by
 * {@link DatabaseNotificationPreferenceResolver}, which reads real per-user preferences plus an
 * account-status gate; see that class's doc comment for the rules.
 */
public interface NotificationPreferenceResolver {
    boolean isEnabled(UUID userId, NotificationCategory category, NotificationChannel channel);
}
