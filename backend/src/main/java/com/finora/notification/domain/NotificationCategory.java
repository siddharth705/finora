package com.finora.notification.domain;

/**
 * Preference grouping. SECURITY notifications default on and are the ones a user should not be
 * able to silence on their only verified channel; MARKETING is a placeholder with no send logic
 * in v1 (proposal section 4, explicitly out of scope).
 */
public enum NotificationCategory {
    SECURITY,
    FINANCIAL,
    MARKETING
}
