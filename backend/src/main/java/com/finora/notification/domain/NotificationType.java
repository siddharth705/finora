package com.finora.notification.domain;

/**
 * Semantic trigger names. A caller names the event, never a title/body string -- copy lives in
 * notification_templates so it is reviewable in one place and reusable across channels.
 *
 * <p>Add a value here together with its notification_templates rows; a type with no template row
 * cannot render and will dead-letter.
 */
public enum NotificationType {
    PASSWORD_CHANGED,
    IMPORT_STATEMENT_READY
}
