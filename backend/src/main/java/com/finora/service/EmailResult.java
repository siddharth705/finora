package com.finora.service;

import java.time.Instant;

/**
 * Standardized outcome of an email send attempt, regardless of which EmailProvider handled it --
 * business services depend on this, never a provider-specific response type. providerMessageId is
 * null when the provider didn't return one (e.g. NoOpEmailProvider, or a failed send).
 */
public record EmailResult(
        ProviderType provider,
        String providerMessageId,
        boolean success,
        Instant timestamp,
        String failureReason
) {
    public static EmailResult success(ProviderType provider, String providerMessageId) {
        return new EmailResult(provider, providerMessageId, true, Instant.now(), null);
    }

    public static EmailResult failure(ProviderType provider, String failureReason) {
        return new EmailResult(provider, null, false, Instant.now(), failureReason);
    }
}
