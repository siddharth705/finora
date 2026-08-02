package com.finora.service;

import java.time.Instant;

/** Standardized outcome of an SMS send attempt, regardless of which SmsProvider handled it --
 *  mirrors EmailResult's shape so both channels look the same to anything that logs/reports on
 *  notification outcomes. */
public record SmsResult(
        ProviderType provider,
        String providerMessageId,
        boolean success,
        Instant timestamp,
        String failureReason
) {
    public static SmsResult success(ProviderType provider, String providerMessageId) {
        return new SmsResult(provider, providerMessageId, true, Instant.now(), null);
    }

    public static SmsResult failure(ProviderType provider, String failureReason) {
        return new SmsResult(provider, null, false, Instant.now(), failureReason);
    }
}
