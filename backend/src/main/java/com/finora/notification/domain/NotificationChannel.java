package com.finora.notification.domain;

/** Delivery channels. PUSH is the only genuinely new one; EMAIL and SMS wrap existing providers. */
public enum NotificationChannel {
    EMAIL,
    SMS,
    PUSH
}
