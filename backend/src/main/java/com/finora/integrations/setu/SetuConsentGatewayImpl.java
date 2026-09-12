package com.finora.integrations.setu;

import org.springframework.stereotype.Component;

/**
 * Placeholder production bean -- a real HTTP-backed implementation against Setu's actual API is a
 * named follow-up (see the design spec's Task 4 notes: it needs real Setu sandbox credentials to
 * build correctly, which this plan does not have). Exists purely so Spring can wire
 * {@code SetuConsentService}/{@code AccountAggregatorIdentityResolutionService} at all -- without
 * some {@code @Component} implementing this interface, the whole application fails to boot, not
 * just the Account Aggregator feature.
 *
 * <p>{@link #isConfigured()} delegates to {@link SetuProperties#isConfigured()}, which is false
 * until real credentials are set -- every caller already checks this before doing anything real
 * ({@code SetuConsentService.initiateLink} refuses with 503 otherwise), so the two methods below
 * are never reached in that state. They throw rather than return a fabricated response: guessing
 * at Setu's real API shape here would be worse than an honest "not implemented yet."
 */
@Component
public class SetuConsentGatewayImpl implements SetuConsentGateway {

    private final SetuProperties properties;

    public SetuConsentGatewayImpl(SetuProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean isConfigured() {
        return properties.isConfigured();
    }

    @Override
    public SetuConsentInitiation createConsent(String userReferenceId, FiType fiType) {
        throw new UnsupportedOperationException(
                "Setu integration is not yet implemented -- see docs/superpowers/specs/"
                + "2026-09-12-account-aggregator-sync-design.md.");
    }

    @Override
    public SetuConsentDetail fetchConsentDetail(String consentHandleId) {
        throw new UnsupportedOperationException(
                "Setu integration is not yet implemented -- see docs/superpowers/specs/"
                + "2026-09-12-account-aggregator-sync-design.md.");
    }
}
