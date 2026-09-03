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
 * Notification copy, per type and channel. Centralized so wording is reviewable in one place
 * rather than hardcoded across ImportService, BudgetService and every future caller.
 *
 * <p>English only in v1. A language column can be added later without breaking this schema; i18n
 * is a separate initiative (no message bundles, no locale resolver exist today).
 */
@Entity
@Table(name = "notification_templates")
public class NotificationTemplate {

    @Id
    @GeneratedValue
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private NotificationType type;

    /** Wording differs by channel: push is terse, email has room. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private NotificationChannel channel;

    @Column(name = "title_template", nullable = false, length = 300)
    private String titleTemplate;

    @Column(name = "body_template", nullable = false, length = 2000)
    private String bodyTemplate;

    @Column(nullable = false)
    private boolean active = true;

    protected NotificationTemplate() {
        // for JPA
    }

    private NotificationTemplate(NotificationType type, NotificationChannel channel,
            String titleTemplate, String bodyTemplate) {
        this.type = type;
        this.channel = channel;
        this.titleTemplate = titleTemplate;
        this.bodyTemplate = bodyTemplate;
    }

    public static NotificationTemplate of(NotificationType type, NotificationChannel channel,
            String titleTemplate, String bodyTemplate) {
        return new NotificationTemplate(type, channel, titleTemplate, bodyTemplate);
    }

    public UUID getId() {
        return id;
    }

    public NotificationType getType() {
        return type;
    }

    public NotificationChannel getChannel() {
        return channel;
    }

    public String getTitleTemplate() {
        return titleTemplate;
    }

    public String getBodyTemplate() {
        return bodyTemplate;
    }

    public boolean isActive() {
        return active;
    }
}
