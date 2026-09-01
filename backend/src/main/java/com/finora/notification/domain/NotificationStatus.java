package com.finora.notification.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Lifecycle, deliberately capped at what this system can observe truthfully.
 *
 * <p>DELIVERED and READ are absent on purpose: neither Resend nor 2Factor has a delivery webhook
 * wired up in this codebase, so those states could never be populated honestly and would sit
 * permanently stale. They return only once provider webhooks exist (proposal section 2.5).
 *
 * <p>SENT means the provider's synchronous API call returned success. That is the only
 * confirmation any provider gives us today.
 */
public enum NotificationStatus {
    CREATED,
    QUEUED,
    PROCESSING,
    SENT,
    FAILED,
    RETRYING,
    DEAD_LETTER;

    /** No further dispatch attempt will be made for a notification in one of these states. */
    public static final Set<NotificationStatus> TERMINAL =
            EnumSet.of(SENT, DEAD_LETTER);

    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }
}
