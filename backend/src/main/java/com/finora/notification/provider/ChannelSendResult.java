package com.finora.notification.provider;

/**
 * Outcome of one delivery attempt. Providers return this instead of throwing, matching
 * ResendEmailProvider/TwoFactorSmsProvider -- a send failure is data, not an exception.
 *
 * @param providerName low-cardinality provider identifier for the notification_logs row
 * @param detail human-readable outcome, already masked/redacted by the provider
 */
public record ChannelSendResult(boolean success, String providerName, String detail) {

    public static ChannelSendResult success(String providerName, String detail) {
        return new ChannelSendResult(true, providerName, detail);
    }

    public static ChannelSendResult failure(String providerName, String detail) {
        return new ChannelSendResult(false, providerName, detail);
    }
}
