package com.finora.integrations.setu;

/**
 * The only Setu-facing seam AccountAggregatorLink's business logic depends on -- mirrors
 * RazorpaySubscriptionGateway's role for billing. Every caller (SetuConsentService,
 * AccountAggregatorIdentityResolutionService) programs against this interface, never against an
 * HTTP client directly, so both are unit-testable with a plain hand-rolled fake.
 */
public interface SetuConsentGateway {

    boolean isConfigured();

    /** @param userReferenceId Fynora's own userId, passed through as Setu's customer reference --
     *                         never a Setu-side identifier Fynora would have to store back. */
    SetuConsentInitiation createConsent(String userReferenceId, FiType fiType);

    /** Called once a webhook reports consent.approved, to learn which FIP/account was actually
     *  linked before running identity resolution. */
    SetuConsentDetail fetchConsentDetail(String consentHandleId);
}
