package com.finora.notification.domain;

/**
 * Urgency, used to inform channel selection. The field exists from the start specifically so a
 * security alert cannot end up buried among low-priority noise later (proposal section 2.1).
 */
public enum NotificationPriority {
    CRITICAL,
    HIGH,
    NORMAL,
    LOW
}
