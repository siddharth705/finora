package com.finora.notification.provider;

/**
 * Outcome of one delivery attempt. Providers return this instead of throwing, matching
 * ResendEmailProvider/TwoFactorSmsProvider -- a send failure is data, not an exception.
 *
 * @param providerName low-cardinality provider identifier for the notification_logs row
 * @param detail human-readable outcome, already masked/redacted by the provider
 * @param permanent true when THIS failure will never succeed no matter how many times or how long
 *     {@code NotificationDispatcher} retries it -- e.g. FCM's "no registered device", or email/SMS's
 *     "user account deleted"/"no address on file". At launch, most users have no device token yet,
 *     so "no registered device" is the COMMON push outcome, not an edge case -- without this flag,
 *     every one of those burned all {@code Notification.MAX_ATTEMPTS} retries over their full
 *     backoff window (5 provider calls and 5 {@code notification_logs} rows, ~15 minutes) before
 *     landing on the exact same DEAD_LETTER a first-attempt terminal call reaches immediately.
 *     {@code NotificationDispatcher} routes a permanent failure straight to its terminal
 *     (dead-letter) path instead of the ordinary retry-scheduling one. Always {@code false} on a
 *     success -- see the compact constructor.
 */
public record ChannelSendResult(boolean success, String providerName, String detail,
        boolean permanent) {

    public ChannelSendResult {
        if (success && permanent) {
            throw new IllegalArgumentException("A successful send cannot be marked permanent");
        }
    }

    public static ChannelSendResult success(String providerName, String detail) {
        return new ChannelSendResult(true, providerName, detail, false);
    }

    /** A failure that may still succeed on a later retry -- an outage, a rate limit, a rejection
     *  that isn't confidently permanent. See {@link #permanentFailure} for the other kind. */
    public static ChannelSendResult failure(String providerName, String detail) {
        return new ChannelSendResult(false, providerName, detail, false);
    }

    /** A failure retrying can never fix. See this record's {@code permanent} doc for why the
     *  dispatcher treats it differently from an ordinary {@link #failure}. */
    public static ChannelSendResult permanentFailure(String providerName, String detail) {
        return new ChannelSendResult(false, providerName, detail, true);
    }
}
