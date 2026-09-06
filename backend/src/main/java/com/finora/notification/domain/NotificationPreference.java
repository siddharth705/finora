package com.finora.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * One user's opt-in/out for a category on a channel. Absent row means the category default:
 * MARKETING is opt-in, every other category is opt-out.
 *
 * <p>SECURITY rows may exist here without effect -- {@code DatabaseNotificationPreferenceResolver}
 * never consults this table for SECURITY at all. See that class's doc comment.
 */
@Entity
@Table(name = "notification_preferences")
public class NotificationPreference {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private NotificationCategory category;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(nullable = false)
    private boolean enabled;

    protected NotificationPreference() {
        // for JPA
    }

    private NotificationPreference(UUID userId, NotificationCategory category,
            NotificationChannel channel, boolean enabled) {
        this.userId = userId;
        this.category = category;
        this.channel = channel;
        this.enabled = enabled;
    }

    public static NotificationPreference of(UUID userId, NotificationCategory category,
            NotificationChannel channel, boolean enabled) {
        return new NotificationPreference(userId, category, channel, enabled);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public NotificationCategory getCategory() {
        return category;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
