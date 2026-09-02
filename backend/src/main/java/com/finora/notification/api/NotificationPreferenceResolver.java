package com.finora.notification.api;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import java.util.UUID;

/**
 * Whether a user wants a given category on a given channel. Task 8 supplies the real
 * implementation; until then a permissive default is wired in so the outbox path is testable.
 */
public interface NotificationPreferenceResolver {
    boolean isEnabled(UUID userId, NotificationCategory category, NotificationChannel channel);
}
